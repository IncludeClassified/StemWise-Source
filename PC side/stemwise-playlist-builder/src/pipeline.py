"""Resumable playlist-to-enrichment pipeline for the frozen Stemwise contract."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import stat
import subprocess
import sys
import time
import traceback
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any
from stemwise_engine.private_diagnostics import harvest_analysis_causes, analysis_diagnostics
from stemwise_engine.common import _fsync_directory

from stemwise_engine.common import (
    WorkerRetirementError,
    WORKER_RETIREMENT_FAILURE_MARKER,
    GENERATED_MANIFEST_NAME,
    atomic_write_bytes,
    atomic_write_json,
    diagnostic_code,
    create_manifested_directory,
    durable_replace,
    exclusive_process_lock,
    heavy_worker_lock_path,
    manifested_temporary_directory,
    parse_strict_json_bytes,
    process_lock_descriptor,
    read_bounded_json,
    read_bounded_regular_bytes,
    read_json,
    remove_manifested_directory,
    resolve_path,
    run_process_tree,
    sha256_file as _sha256_file,
    utc_now,
    validate_generated_path,
    validate_video_id,
)
from stemwise_engine.metadata_research import MusicBrainzClient, enrich_metadata
from stemwise_engine.runtime_environment import environment_python
from stemwise_engine.packages import (
    PackageValidationError,
    create_child_zip,
    read_child,
    validate_child_zip,
    _verify_png,
)
from stemwise_engine.provenance import collect_provenance, provenance_fingerprint
from stemwise_engine.song_enrichment_v2 import validate_lyrics, require_publication_lyrics
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.youtube import (
    inventory_playlist,
    probe_duration_ms,
)


_REPORT_DIAGNOSTIC_KEYS = {
    "error",
    "reason",
    "warning",
    "statusError",
    "outputInvalidationError",
}


def require_cleanup_root_disjoint(
    cleanup_root: Path,
    durable_roots: dict[str, Path],
    *,
    subject: str = "Temporary cleanup root",
) -> None:
    """Reject a recursive-cleanup root that could contain durable state."""

    cleanup = Path(os.path.abspath(cleanup_root))
    for label, value in durable_roots.items():
        durable = Path(os.path.abspath(value))
        if cleanup.is_relative_to(durable) or durable.is_relative_to(cleanup):
            raise ValueError(f"{subject} overlaps {label}")


class RetainedAnalysisBindingError(ValueError):
    """The retained PCM or its durable identity is structurally inconsistent."""




def _pc_report_value(value: Any, *, key: str = "") -> Any:
    """Project resumable state without local paths, URLs, or free-form errors."""

    if key.lower().endswith(("url", "path")):
        return None
    if key in {"output", "preparedInputs"}:
        return None
    if key in _REPORT_DIAGNOSTIC_KEYS:
        if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,160}", value):
            return value
        return "redacted-diagnostic"
    if key == "warnings":
        if not isinstance(value, list):
            return []
        return [
            item
            if isinstance(item, str)
            and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,160}", item)
            else "redacted-diagnostic"
            for item in value
        ]
    if isinstance(value, dict):
        return {
            child_key: _pc_report_value(child_value, key=child_key)
            for child_key, child_value in value.items()
            if not child_key.lower().endswith(("url", "path"))
        }
    if isinstance(value, list):
        return [_pc_report_value(item) for item in value]
    return value


def _strictly_later_generated_at(value: Any) -> str:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise PackageValidationError("generatedAt is not an RFC 3339 UTC instant")
    try:
        previous = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise PackageValidationError("generatedAt is not an RFC 3339 UTC instant") from error
    current = datetime.now(timezone.utc).replace(microsecond=0)
    return max(current, previous + timedelta(seconds=1)).isoformat().replace("+00:00", "Z")












def _analysis_failure(message, completed, reason, *, cause=None, retirement=False):
    error = WorkerRetirementError(message) if retirement else RuntimeError(message)
    error.returncode = completed.returncode
    stderr = getattr(completed, 'stderr', None) or ''
    raw = stderr if isinstance(stderr, bytes) else str(stderr).encode('utf-8', errors='replace')
    error.stderr = raw[-12000:].decode('utf-8', errors='ignore')
    error.worker_reason = reason
    error.__cause__ = cause
    return error


def _run_analysis_single(audio_path: Path, duration_ms: int, project_dir: Path,
                        timeout_seconds: int, *, repair_from: Path | None = None) -> dict[str, Any]:
    lock_path = heavy_worker_lock_path({'projectDir': str(project_dir.resolve())})
    lease = process_lock_descriptor(lock_path)
    if lease is None:
        with exclusive_process_lock(lock_path):
            return _run_analysis_single(audio_path, duration_ms, project_dir, timeout_seconds, repair_from=repair_from)
    worker_timeout_seconds = max(1, math.ceil(timeout_seconds - 10))
    completed = run_process_tree(
        [
            sys.executable,
            "-B", '-m', 'stemwise_engine.analysis_worker', "single",
            str(audio_path),
            str(duration_ms),
            str(project_dir),
            str(worker_timeout_seconds),
            str(lock_path),
            str(lease),
        ] + (['--repair-from', str(repair_from)] if repair_from is not None else []),
        pass_fds=(lease,),
        cwd=audio_path.parent,
        text=True,
        capture_output=True,
        check=False,
        timeout=timeout_seconds,
        env={
            **os.environ,
            "PYTHONUNBUFFERED": "1",
        },
    )
    marker = "STEMWISE_ANALYSIS_RESULT="
    if WORKER_RETIREMENT_FAILURE_MARKER in completed.stdout.splitlines():
        raise _analysis_failure('Owned analysis workers could not be retired', completed,
                                'worker-retirement-failed', retirement=True)
    payloads = [
        line.removeprefix(marker)
        for line in completed.stdout.splitlines()
        if line.startswith(marker)
    ]
    if completed.returncode != 0 or len(payloads) != 1:
        raise _analysis_failure("analysis worker failed", completed,
            'worker-exit' if completed.returncode != 0 else 'result-marker-invalid')
    try:
        analysis = json.loads(payloads[-1])
    except (ValueError, UnicodeError) as error:
        raise _analysis_failure('analysis worker returned invalid JSON', completed, 'result-json-invalid', cause=error)
    if not isinstance(analysis, dict):
        raise _analysis_failure("analysis worker returned an invalid result", completed, 'result-shape-invalid')
    return analysis


def _run_analysis_batch(inputs, project_dir, timeout_seconds):
    if len(inputs) == 1:
        audio, duration = inputs[0]
        return [_run_analysis_single(audio, duration, project_dir, timeout_seconds)]
    lock_path = heavy_worker_lock_path({'projectDir': str(project_dir.resolve())})
    lease = process_lock_descriptor(lock_path)
    if lease is None:
        raise RuntimeError('Acoustic batch requires the engine model lease')
    interrupted = None
    try:
        completed = run_process_tree([sys.executable, '-B', '-m', 'stemwise_engine.analysis_worker', 'batch',
            json.dumps([[str(audio), duration] for audio, duration in inputs]), str(project_dir),
            str(max(1, math.ceil(timeout_seconds - 10))), str(lock_path), str(lease)],
            pass_fds=(lease,), cwd=inputs[0][0].parent, text=True, capture_output=True,
            check=False, timeout=timeout_seconds * len(inputs),
            env={**os.environ, 'PYTHONUNBUFFERED': '1'})
    except subprocess.TimeoutExpired as error:
        stdout = error.output or ''
        # Drop the incomplete byte record before decoding: process death may split a character.
        if isinstance(stdout, bytes):
            stdout = stdout[:stdout.rfind(b'\n')+1].decode('utf-8', errors='strict')
        else:
            stdout = stdout[:stdout.rfind('\n')+1]
        interrupted = error
        completed = subprocess.CompletedProcess(error.cmd, None, stdout, error.stderr)
    marker = 'STEMWISE_ACOUSTIC_ROW='
    if WORKER_RETIREMENT_FAILURE_MARKER in completed.stdout.splitlines():
        raise _analysis_failure('Owned analysis workers could not be retired', completed,
                                'worker-retirement-failed', retirement=True)
    payloads = [line.removeprefix(marker) for line in completed.stdout.splitlines() if line.startswith(marker)]
    failure_marker = 'STEMWISE_ACOUSTIC_FAILURE='
    raw_stderr = completed.stderr or ''
    stderr = raw_stderr.decode('utf-8', errors='replace') if isinstance(raw_stderr, bytes) else raw_stderr
    stage_failures, shared_stderr = {}, []
    for line in stderr.splitlines():
        if line.startswith(failure_marker):
            try:
                row = parse_strict_json_bytes(line.removeprefix(failure_marker).encode('utf-8'))
                if (isinstance(row, dict) and set(row) == {'index', 'component', 'errorType', 'diagnostic'}
                        and type(row['index']) is int and row['index'] in range(len(inputs))
                        and type(row['component']) is int and row['component'] in (0, 1)
                        and isinstance(row['errorType'], str) and re.fullmatch(r'[A-Za-z0-9_.:+-]{1,160}', row['errorType'])
                        and isinstance(row['diagnostic'], str) and len(row['diagnostic'].encode('utf-8')) <= 12000):
                    component = 'gpu' if row['component'] == 0 else 'cpu'
                    stage_failures.setdefault(row['index'], {})[component] = (
                        f"Acoustic row {row['index']} {component} {row['errorType']}:\n{row['diagnostic']}")
                    continue
            except (ValueError, UnicodeError):
                pass
        shared_stderr.append(line)

    def failed_row(index, message, reason, *, cause=None):
        error = _analysis_failure(message, completed, reason, cause=cause)
        detail = '\n'.join([*shared_stderr, *stage_failures.get(index, {}).values()]).encode('utf-8')
        error.stderr = detail[-12000:].decode('utf-8', errors='ignore')
        return error

    reason = ('worker-timeout' if interrupted is not None else
              'worker-exit' if completed.returncode != 0 else 'song-result-missing')
    results = [failed_row(index, 'acoustic song analysis failed', reason, cause=interrupted) for index in range(len(inputs))]
    started_marker = 'STEMWISE_ACOUSTIC_SERIAL_STARTED='
    started = [line.removeprefix(started_marker) for line in completed.stdout.splitlines()
               if line.startswith(started_marker)]
    if started:
        if started != [str(index) for index in range(len(started))] or len(started) > len(inputs):
            raise _analysis_failure('Acoustic serial worker returned invalid progress', completed, 'result-shape-invalid')
        if interrupted is not None or completed.returncode != 0:
            from stemwise_engine.analysis_queue import AnalysisInputNotStarted
            for index in range(len(started), len(inputs)):
                results[index] = AnalysisInputNotStarted('Serial worker retired before this input started')
    seen = set()
    for payload in payloads:
        try:
            row = parse_strict_json_bytes(payload.encode())
        except (ValueError, UnicodeError) as error:
            raise _analysis_failure('Acoustic batch worker returned invalid JSON', completed, 'result-json-invalid', cause=error)
        if (not isinstance(row, dict) or set(row) != {'index', 'analysis'}
                or type(row.get('index')) is not int or row['index'] not in range(len(inputs))
                or row['index'] in seen or not isinstance(row.get('analysis'), dict)):
            raise _analysis_failure('Acoustic batch worker returned invalid results', completed, 'result-shape-invalid')
        index = row['index']
        seen.add(index)
        analysis = row['analysis']
        if 'error' not in analysis and analysis.get('durationMs') == inputs[index][1]:
            results[index] = analysis
        else:
            results[index] = failed_row(index, 'acoustic song analysis failed', 'song-analysis-failed')
    return results


def run_analysis_worker(audio_path: Path, duration_ms: int, project_dir: Path,
                        timeout_seconds: int, *, repair_from: Path | None = None) -> dict[str, Any]:
    # Admission is below every native/YouTube caller; agents never manage models.
    if repair_from is not None:
        result = _run_analysis_single(audio_path, duration_ms, project_dir, timeout_seconds, repair_from=repair_from)
    elif process_lock_descriptor(heavy_worker_lock_path({'projectDir': str(project_dir.resolve())})) is not None:
        result = _run_analysis_single(audio_path, duration_ms, project_dir, timeout_seconds)
    else:
        from stemwise_engine.analysis_queue import analyze
        result = analyze(audio_path, duration_ms, project_dir, timeout_seconds, _run_analysis_batch)
    harvest_analysis_causes(result)
    return result

class StemwisePipeline:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        from stemwise_engine.youtube import YouTubeSource
        if 'localRecordingKey' in config:
            from stemwise_engine.recording_source import RecordingSource
            self.source = RecordingSource(config)
        else:
            self.source = YouTubeSource(config)
        self.project_dir = Path(config["projectDir"])
        self.workspace_root = self.project_dir.parent.parent
        self.lyrics_input_dir = Path(
            config.get("lyricsInputDir") or (self.project_dir / "owner-input")
        )
        self.output_dir = Path(config["outputDir"])
        self.children_dir = self.output_dir / "children"
        self.work_dir = self.output_dir / "work"
        self.checkpoint_dir = self.work_dir / "checkpoints"
        self.inventory_path = self.work_dir / "inventory.json"
        self.report_path = self.output_dir / "pc-report.json"
        self.temp_root = Path(config["tempDir"])
        self.pc_side_root = resolve_path(
            str(config.get("pcSideRootWindows") or self.project_dir.parent),
            base=self.project_dir,
        )
        self.future_root = resolve_path(
            str(config.get("futureRootWindows") or self.project_dir / "future-songs"),
            base=self.project_dir,
        )
        self.future_ready_dir = resolve_path(
            str(
                config.get("futureReadyDirWindows")
                or self.workspace_root / "Ready to Import"
            ),
            base=self.project_dir,
        )
        for path in (
            self.project_dir,
            self.lyrics_input_dir,
            self.output_dir,
            self.temp_root,
        ):
            validate_generated_path(
                self.workspace_root,
                path,
                workspace_root=self.workspace_root,
            )
        require_cleanup_root_disjoint(
            self.temp_root,
            {
                "projectDir": self.project_dir,
                "lyricsInputDir": self.lyrics_input_dir,
                "outputDir": self.output_dir,
            },
        )
        output_durable_roots = {
            "projectDir": self.project_dir,
            "lyricsInputDir": self.lyrics_input_dir,
            "future policy state": self.future_root,
            "Ready to Import": self.future_ready_dir,
            "private reports": self.pc_side_root / "reports",
            "model cache": self.pc_side_root / "model-cache",
            "alignment state": self.pc_side_root / "lyrics-alignment",
            "bundled runtime": self.pc_side_root / "runtime",
        }
        future_runtime = (
            self.output_dir.name == "runtime"
            and self.output_dir.parent.parent == self.future_root / "policy-state"
            and self.lyrics_input_dir == self.output_dir.parent / "owner-input"
        )
        if future_runtime:
            output_durable_roots.pop("projectDir")
            output_durable_roots.pop("future policy state")
        require_cleanup_root_disjoint(
            self.output_dir,
            output_durable_roots,
            subject="Generated output root",
        )
        for directory in (
            self.output_dir,
            self.children_dir,
            self.checkpoint_dir,
        ):
            validate_generated_path(
                self.output_dir,
                directory,
                workspace_root=self.workspace_root,
            )
            directory.mkdir(parents=True, exist_ok=True)
        self.metadata_client = MusicBrainzClient(
            self.work_dir / "metadata-cache" / "musicbrainz",
            generator_version=str(config["generatorVersion"]),
        )
        configured_allin1 = os.environ.get("STEMWISE_ALLIN1_PYTHON")
        self.allin1_python = Path(configured_allin1) if configured_allin1 else (
            environment_python('allin1', self.project_dir)
        )
        if not self.allin1_python.resolve().is_relative_to(self.workspace_root):
            raise ValueError("All-In-One runtime must stay inside the StemWise workspace")
        self.provenance: dict[str, Any] = {}
        self.provenance_sha256 = ""
        self._provenance_needs_refresh = True

    @staticmethod
    def _has_unverified_expected_file(value: Any) -> bool:
        if isinstance(value, dict):
            if "expectedSha256" in value and value.get("verified") is not True:
                return True
            return any(StemwisePipeline._has_unverified_expected_file(item) for item in value.values())
        if isinstance(value, list):
            return any(StemwisePipeline._has_unverified_expected_file(item) for item in value)
        return False

    def _ensure_provenance(self) -> None:
        if not self.provenance_sha256:
            self._refresh_provenance()

    def _refresh_provenance(self) -> None:
        self.provenance = collect_provenance(allin1_python=self.allin1_python, observe_execution=True)
        self.provenance_sha256 = provenance_fingerprint(self.provenance)
        self._provenance_needs_refresh = self._has_unverified_expected_file(
            self.provenance.get("models", {})
        )

    def checkpoint_path(self, video_id: str) -> Path:
        return validate_generated_path(
            self.checkpoint_dir,
            self.checkpoint_dir / f"{validate_video_id(video_id)}.json",
            workspace_root=self.workspace_root,
        )

    def child_path(self, video_id: str) -> Path:
        return validate_generated_path(
            self.children_dir,
            self.children_dir / f"{validate_video_id(video_id)}.stemwise.zip",
            workspace_root=self.workspace_root,
        )

    def load_checkpoint(self, video_id: str) -> dict[str, Any] | None:
        self._recover_analysis_replacement(video_id)
        return self._read_checkpoint(video_id)

    def _read_checkpoint(self, video_id: str) -> dict[str, Any] | None:
        path = self.checkpoint_path(video_id)
        try:
            value = read_json(path)
        except FileNotFoundError:
            return None
        if not isinstance(value, dict):
            raise ValueError('Checkpoint must be an object')
        return value

    def save_checkpoint(self, state: dict[str, Any]) -> None:
        state["updatedAt"] = utc_now()
        atomic_write_json(self.checkpoint_path(state["videoId"]), state)

    def _record_attempt_failure(
        self, video_id: str, error: BaseException, previous_checkpoint_raw: bytes | None,
        *, analysis_stage: dict[str, Any] | None = None
    ) -> None:
        video_id = validate_video_id(video_id)
        diagnostic = "".join(traceback.format_exception(error)).encode("utf-8", errors="replace")
        cause, seen = error, set()
        worker = None
        while cause is not None and id(cause) not in seen:
            seen.add(id(cause))
            if worker is None and (hasattr(cause, 'returncode') or isinstance(cause, subprocess.TimeoutExpired)):
                worker = cause
            stderr = getattr(cause, "stderr", None)
            if stderr:
                raw = stderr if isinstance(stderr, bytes) else str(stderr).encode("utf-8", errors="replace")
                diagnostic += b"\nChild stderr:\n" + raw[-12000:]
            cause = cause.__cause__ or (None if cause.__suppress_context__ else cause.__context__)
        record = {
            "failureVersion": 1,
            "videoId": video_id,
            "failedAt": utc_now(),
            "errorType": diagnostic_code(error),
            "error": diagnostic_code(error),
            "privateDiagnostic": diagnostic[-12000:].decode("utf-8", errors="ignore"),
            "previousCheckpointSha256": (
                hashlib.sha256(previous_checkpoint_raw).hexdigest()
                if previous_checkpoint_raw is not None
                else None
            ),
        }
        if worker is not None:
            record['workerExitCode'] = getattr(worker, 'returncode', None)
            record['workerFailure'] = getattr(worker, 'worker_reason',
                'worker-timeout' if isinstance(worker, subprocess.TimeoutExpired) else 'worker-exit')
        if analysis_stage is not None:
            record['analysisStage'] = analysis_stage
        token = os.environ.get('STEMWISE_LAUNCH_TOKEN', '')
        if re.fullmatch(r'[0-9a-f]{32}', token):
            record['operationId'] = token
        pointer = self._attempt_failure_path(video_id)
        path = pointer.parent / 'runs' / f'{uuid.uuid4().hex}.failure.json'
        atomic_write_json(path, record)
        atomic_write_json(pointer, {'file': path.relative_to(pointer.parent).as_posix(),
                                   'sha256': _sha256_file(path), 'byteCount': path.stat().st_size})

    def _attempt_failure_path(self, video_id: str) -> Path:
        from stemwise_engine.workflow_context import telemetry_job_root
        video_id = validate_video_id(video_id)
        recording = self._source_identity(video_id).get('recording')
        key = recording['recordingKey'] if recording is not None else f'youtube:{video_id}'
        root = telemetry_job_root(self.workspace_root, key) / 'pipeline'
        if recording is not None:
            digest = recording['sha256']
            if not isinstance(digest, str) or re.fullmatch(r'[0-9a-f]{64}', digest) is None:
                raise ValueError('Telemetry recording identity is invalid')
            root = root / digest
        return validate_generated_path(self.workspace_root,
            root / video_id / 'latest-failure.json', workspace_root=self.workspace_root)


    def make_inventory(self, url: str | None = None) -> dict[str, Any]:
        snapshot = inventory_playlist(url or self.config["playlistUrl"])
        atomic_write_json(self.inventory_path, snapshot)
        self.write_report(snapshot=snapshot)
        return snapshot

    def load_inventory(self, *, create_if_missing: bool = True) -> dict[str, Any]:
        if not self.inventory_path.is_file():
            if create_if_missing:
                return self.make_inventory()
            raise FileNotFoundError("No inventory exists. Run inventory first.")
        snapshot = read_json(self.inventory_path)
        if not isinstance(snapshot, dict) or not isinstance(snapshot.get("items"), list):
            raise ValueError("work/inventory.json is not a valid playlist snapshot")
        return snapshot

    def _lyrics_path(self, video_id: str) -> Path:
        return self.lyrics_input_dir / f"{video_id}.lyrics.json"

    def lyrics_fingerprint(self, video_id: str) -> str:
        path = self._lyrics_path(video_id)
        if not path.exists() and not path.is_symlink():
            return "absent"
        payload = read_bounded_regular_bytes(
            path,
            max_bytes=int(self.config["jsonMaxBytes"]),
            root=self.lyrics_input_dir,
        )
        return hashlib.sha256(payload).hexdigest()

    def load_lyrics_snapshot(self, video_id: str) -> tuple[dict[str, Any], str]:
        """Parse lyrics and hash the exact same byte snapshot.

        If the owner edits a sidecar during a long analysis, the completed
        checkpoint continues to describe the bytes actually packaged.  The next
        pass sees the new fingerprint and performs a cheap lyrics-only refresh.
        """

        from stemwise_engine.lyrics_intake import require_content_review_clear
        require_content_review_clear(self.lyrics_input_dir, video_id)
        path = self._lyrics_path(video_id)
        if not path.exists() and not path.is_symlink():
            return (
                {
                    "status": "unavailable",
                    "reason": "No owner-supplied or authorized full lyrics were provided.",
                    "tracks": [],
                },
                "absent",
            )
        payload = read_bounded_regular_bytes(
            path,
            max_bytes=int(self.config["jsonMaxBytes"]),
            root=self.lyrics_input_dir,
        )
        fingerprint = hashlib.sha256(payload).hexdigest()
        try:
            raw = parse_strict_json_bytes(payload)
        except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
            raise ValueError(f"{path.name} is not valid UTF-8 JSON: {error}") from error
        if not isinstance(raw, dict):
            raise ValueError(f"{path.name} must contain the exact lyrics object")
        validate_lyrics(raw)
        require_publication_lyrics(raw)
        return raw, fingerprint

    def _new_state(self, item: dict[str, Any], owner_lyrics_sha256: str, *, retain_prepared=True) -> dict[str, Any]:
        self._ensure_provenance()
        previous = self.load_checkpoint(item["videoId"]) or {}
        if retain_prepared and 'preparedInputs' in previous:
            self.prepared_inputs(item['videoId'])
            previous = self.load_checkpoint(item['videoId']) or {}
        state = {
            "checkpointVersion": 1,
            "videoId": item["videoId"],
            "position": item.get("position"),
            "title": item.get("title"),
            "status": "running",
            "attempt": int(previous.get("attempt") or 0) + 1,
            "startedAt": utc_now(),
            "generatorVersion": self.config["generatorVersion"],
            "provenanceSha256": self.provenance_sha256,
            **({'acousticExecution': self.provenance['acousticRuntime']['execution']}
               if 'execution' in self.provenance.get('acousticRuntime', {}) else {}),
            "ownerLyricsSha256": owner_lyrics_sha256,
            "stages": {},
            **({'preparedInputs': previous['preparedInputs']}
               if retain_prepared and 'preparedInputs' in previous else {}),
        }
        if isinstance(previous.get("originalAudio"), dict):
            state["originalAudio"] = previous["originalAudio"]
        if self.config.get('localRecordingKey'):
            from stemwise_engine.recording_analysis import acoustic_producer_identity
            state.update(recordingInput=self.source.recording.identity,
                acousticProducerIdentity=acoustic_producer_identity(self.config))
        return state

    def _stage(
        self,
        state: dict[str, Any],
        name: str,
        status: str,
        *,
        reason: str | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        record: dict[str, Any] = {"status": status, "at": utc_now()}
        if reason:
            record["reason"] = reason
        if details:
            record.update(details)
        state.setdefault("stages", {})[name] = record
        self.save_checkpoint(state)

    def _temp_dir(self, video_id: str) -> Path:
        video_id = validate_video_id(video_id)
        temporary = validate_generated_path(
            self.temp_root,
            self.temp_root / video_id,
            workspace_root=self.workspace_root,
        )
        owner = {"kind": "analysis-temporary", "videoId": video_id}
        from stemwise_engine.common import manifested_cleanup_receipt
        receipt = manifested_cleanup_receipt(temporary)
        if receipt.exists() or receipt.is_symlink():
            self._cleanup_temp(temporary)
        if temporary.exists():
            manifest = read_bounded_json(temporary / GENERATED_MANIFEST_NAME, max_bytes=16*1024, root=temporary)
            if manifest != {'manifestVersion': 1, 'owner': owner, 'generatedPaths': ['.']}:
                raise ValueError('Temporary audio owner differs')
            return temporary
        return create_manifested_directory(
            self.temp_root,
            temporary,
            owner=owner,
            workspace_root=self.workspace_root,
        )

    def _initial_phase(self, video_id, temporary):
        path = temporary / 'initial-phase.json'
        if not path.exists():
            return None
        phase = read_bounded_json(path, max_bytes=2*MAX_JSON_BYTES+16384, root=temporary)
        if not isinstance(phase, dict) or phase.get('source') != self._source_identity(video_id):
            raise ValueError('Initial phase belongs to another recording')
        if not isinstance(phase.get('info'), dict) or phase['info'].get('id') != video_id:
            raise ValueError('Initial phase metadata belongs to another recording')
        audio = validate_generated_path(temporary, temporary/'analysis.wav', workspace_root=self.workspace_root)
        pcm = phase.get('pcm')
        if (not isinstance(pcm, dict) or audio.stat().st_size != pcm.get('byteCount')
                or _sha256_file(audio) != pcm.get('sha256') or probe_duration_ms(audio) != pcm.get('durationMs')):
            raise ValueError('Initial phase PCM changed')
        if phase.get('producerSha256') != self.provenance_sha256:
            phase.pop('analysis', None)
        elif 'analysis' in phase:
            from stemwise_engine.contract import require_usable_analysis
            require_usable_analysis(phase['analysis'], pcm['durationMs'])
        return phase

    def prepared_inputs(self, video_id):
        temporary = validate_generated_path(self.temp_root, self.temp_root / validate_video_id(video_id),
            workspace_root=self.workspace_root)
        checkpoint = self.load_checkpoint(video_id)
        if not checkpoint or 'preparedInputs' not in checkpoint:
            return None
        from stemwise_engine.common import manifested_cleanup_receipt
        receipt = manifested_cleanup_receipt(temporary)
        if receipt.exists() or receipt.is_symlink():
            self._cleanup_temp(temporary)
            return None
        if checkpoint.get('status') == 'complete' and not self.config.get('retainSuccessfulAnalysisAudio'):
            retired = not temporary.exists() and not temporary.is_symlink()
            if (not retired and not temporary.is_symlink() and temporary.is_dir()
                    and {path.name for path in temporary.iterdir()} == {GENERATED_MANIFEST_NAME}):
                self._temp_dir(video_id)  # Authenticate the empty owner left by interrupted cleanup/rebuild.
                retired = True
            if retired:
                self._retire_prepared_inputs(video_id)
                return None
        self._ensure_provenance()
        temporary = self._temp_dir(video_id)
        value = checkpoint['preparedInputs']
        if (not isinstance(value, dict) or value.get('source') != self._source_identity(video_id)
                or not isinstance(value.get('info'), dict) or value['info'].get('id') != video_id
                or not isinstance(value.get('metadata'), dict) or not isinstance(value.get('artwork'), dict)):
            raise ValueError('Prepared source/artwork binding changed')
        cover = read_bounded_regular_bytes(temporary/'cover.png', max_bytes=int(self.config['pngMaxBytes']), root=temporary)
        if hashlib.sha256(cover).hexdigest() != value.get('artworkSha256'):
            raise ValueError('Prepared artwork changed')
        _verify_png(cover, max_bytes=int(self.config['pngMaxBytes']), max_pixels=int(self.config['artworkMaxPixels']))
        if (temporary/'initial-phase.json').exists():
            self._initial_phase(video_id, temporary)
        return value

    def _retire_prepared_inputs(self, video_id):
        checkpoint = self.load_checkpoint(video_id)
        if checkpoint and 'preparedInputs' in checkpoint:
            checkpoint.pop('preparedInputs')
            self.save_checkpoint(checkpoint)

    def _cleanup_temp(self, temporary: Path) -> None:
        video_id = validate_video_id(temporary.name)
        remove_manifested_directory(
            self.temp_root,
            temporary,
            owner={"kind": "analysis-temporary", "videoId": video_id},
            workspace_root=self.workspace_root,
            before_remove=lambda: self._retire_prepared_inputs(video_id),
        )

    def _require_retained_analysis_audio(self, video_id, state):
        if not self.config.get('localRecordingKey'):
            return self._read_retained_analysis_audio(video_id, state)
        from stemwise_engine.recording_source import selection
        from stemwise_engine.workflow_records import WorkflowInputError
        from stemwise_engine.youtube import decode_analysis_audio
        if state.get('recordingInput') != self.source.recording.identity:
            raise ValueError('Completed analysis belongs to another recording')
        temporary = validate_generated_path(self.temp_root, self.temp_root / validate_video_id(video_id),
            workspace_root=self.workspace_root)
        with exclusive_process_lock(self.temp_root / f'{video_id}.pcm-recovery.lock'):
            try:
                return self._read_retained_analysis_audio(video_id, state)
            except RetainedAnalysisBindingError:
                from stemwise_engine.common import GENERATED_MANIFEST_NAME
                if temporary.exists():
                    manifest = read_bounded_json(temporary / GENERATED_MANIFEST_NAME,
                        max_bytes=16*1024, root=temporary)
                    if manifest != {'manifestVersion': 1,
                            'owner': {'kind': 'analysis-temporary', 'videoId': video_id},
                            'generatedPaths': ['.']}:
                        raise WorkflowInputError('Native PCM recovery directory ownership changed', reason_code='retryable-technical',
                            affected_input='retained analysis PCM', next_action='Preserve the checkpoint and resolve its exact source binding before Retry.')
                identity = state.get('originalAudio')
                if (state.get('videoId') != video_id or state.get('status') != 'complete' or not isinstance(identity, dict)
                        or identity.get('retained') is not True):
                    raise WorkflowInputError('Native PCM recovery requires its completed checkpoint', reason_code='retryable-technical',
                            affected_input='retained analysis PCM', next_action='Preserve the checkpoint and resolve its exact source binding before Retry.')
                current = selection(self.config, self.config['localRecordingKey'])
                if current.identity != self.source.recording.identity:
                    raise WorkflowInputError('Native PCM recovery input changed', reason_code='retryable-technical',
                            affected_input='retained analysis PCM', next_action='Preserve the checkpoint and resolve its exact source binding before Retry.')
                owner = {'kind': 'analysis-temporary', 'videoId': video_id}
                staging = validate_generated_path(self.temp_root,
                    self.temp_root / f'.pcm-recovery-{video_id}', workspace_root=self.workspace_root)
                if staging.exists():
                    remove_manifested_directory(self.temp_root, staging, owner=owner,
                        workspace_root=self.workspace_root)
                create_manifested_directory(self.temp_root, staging, owner=owner,
                    workspace_root=self.workspace_root)
                try:
                    with exclusive_process_lock(staging / '.stemwise-worker.lock'):
                        decode_analysis_audio(current.audio, staging, timeout_seconds=600)
                        if _sha256_file(current.audio) != current.identity['sha256']:
                            raise WorkflowInputError('Native PCM recovery input changed during decoding', reason_code='retryable-technical',
                                affected_input='retained analysis PCM', next_action='Preserve the checkpoint and resolve its exact source binding before Retry.')
                        audio = self._read_retained_analysis_audio(video_id, state, temporary=staging)
                        with audio.open('rb+') as handle:
                            os.fsync(handle.fileno())
                        _fsync_directory(staging)
                    if temporary.exists():
                        durable_replace(audio, temporary / 'analysis.wav', staged_is_synced=True)
                    else:
                        durable_replace(staging, temporary, staged_is_synced=True)
                finally:
                    if staging.exists() and not isinstance(sys.exc_info()[1], WorkerRetirementError):
                        remove_manifested_directory(self.temp_root, staging, owner=owner,
                            workspace_root=self.workspace_root)
        return self._read_retained_analysis_audio(video_id, state)


    def _read_retained_analysis_audio(
        self, video_id: str, state: dict[str, Any] | None, *, temporary: Path | None = None
    ) -> Path:
        video_id = validate_video_id(video_id)
        identity = state.get("originalAudio") if isinstance(state, dict) else None
        temporary = validate_generated_path(
            self.temp_root,
            temporary if temporary is not None else self.temp_root / video_id,
            workspace_root=self.workspace_root,
        )
        try:
            manifest = read_bounded_json(
                temporary / GENERATED_MANIFEST_NAME,
                max_bytes=16 * 1024,
                root=temporary,
            )
        except UnicodeError as error:
            raise RetainedAnalysisBindingError(
                "retained analysis PCM manifest is invalid"
            ) from error
        except ValueError as error:
            cause = error.__cause__
            if isinstance(cause, OSError) and not isinstance(cause, FileNotFoundError):
                raise cause
            raise RetainedAnalysisBindingError(
                "retained analysis PCM manifest is invalid"
            ) from error
        if manifest != {
            "manifestVersion": 1,
            "owner": {"kind": "analysis-temporary", "videoId": video_id},
            "generatedPaths": ["."],
        }:
            raise RetainedAnalysisBindingError("retained analysis PCM manifest is invalid")
        audio = validate_generated_path(
            temporary,
            temporary / "analysis.wav",
            workspace_root=self.workspace_root,
        )
        try:
            audio_stat = audio.lstat()
        except FileNotFoundError as error:
            raise RetainedAnalysisBindingError(
                "retained analysis PCM differs from its completed checkpoint"
            ) from error
        if (
            not isinstance(state, dict)
            or state.get("videoId") != video_id
            or state.get("status") != "complete"
            or not isinstance(identity, dict)
            or identity.get("retained") is not True
            or not stat.S_ISREG(audio_stat.st_mode)
            or audio_stat.st_size != identity.get("byteCount")
            or _sha256_file(audio) != identity.get("sha256")
            or probe_duration_ms(audio) != identity.get("durationMs")
        ):
            raise RetainedAnalysisBindingError(
                "retained analysis PCM differs from its completed checkpoint"
            )
        return audio

    def require_retained_analysis_audio(self, video_id: str) -> Path:
        """Return the exact successful PCM retained for future-song lyrics."""

        video_id = validate_video_id(video_id)
        return self._require_retained_analysis_audio(
            video_id, self.load_checkpoint(video_id)
        )




    def commit_child_edit(self, video_id, candidate, *, state=None, stage, binding=None):
        """Publish a validated child/checkpoint pair through the existing recovery journal."""
        current = self.load_checkpoint(video_id)
        child, checkpoint = self.child_path(video_id), self.checkpoint_path(video_id)
        old_child, old_checkpoint = child.read_bytes(), checkpoint.read_bytes()
        if (not current or current.get('status') != 'complete'
                or current.get('childSha256') != hashlib.sha256(old_child).hexdigest()):
            raise ValueError('Child edit requires its exact completed checkpoint')
        updated = {**current, **(state or {})}
        updated.update(status='complete', childSha256=_sha256_file(candidate), updatedAt=utc_now())
        updated['stages'] = {**current.get('stages', {}), **updated.get('stages', {}),
                             stage: {'status': 'complete', 'at': utc_now()}}
        identity = {'videoId': video_id, 'stage': stage, 'binding': binding,
                    'oldChildSha256': hashlib.sha256(old_child).hexdigest(),
                    'oldCheckpointSha256': hashlib.sha256(old_checkpoint).hexdigest(),
                    'newChildSha256': updated['childSha256']}
        attempt_id = hashlib.sha256(json.dumps(identity, sort_keys=True).encode('utf-8')).hexdigest()
        root = self._analysis_replacement_root(video_id)
        attempt = root / attempt_id
        if not attempt.exists():
            create_manifested_directory(root, attempt,
                owner={'kind': 'analysis-replacement', 'videoId': video_id}, workspace_root=self.workspace_root)
        atomic_write_bytes(attempt / 'previous.zip', old_child)
        atomic_write_bytes(attempt / 'previous.json', old_checkpoint)
        atomic_write_bytes(attempt / 'next.zip', candidate.read_bytes())
        atomic_write_json(attempt / 'next.json', updated)
        atomic_write_json(root / 'pending.json', {'version': 1, 'phase': 'prepared', 'attemptId': attempt_id,
            **{key: identity[key] for key in ('oldChildSha256', 'oldCheckpointSha256', 'newChildSha256')},
            'newCheckpointSha256': _sha256_file(attempt / 'next.json'),
            'editBinding': {'videoId': video_id, 'stage': stage, 'review': binding}})
        self._recover_analysis_replacement(video_id)
        return updated

    def _refresh_lyrics_only(
        self,
        video_id: str,
        state: dict[str, Any],
        lyrics: dict[str, Any],
        owner_lyrics_sha256: str,
    ) -> dict[str, Any]:
        child = self.child_path(video_id)
        document, cover = read_child(child, self.config)
        document["lyrics"] = lyrics
        document["generatedAt"] = _strictly_later_generated_at(document.get("generatedAt"))
        document["generator"] = {
            "name": "stemwise-pc",
            "version": self.config["generatorVersion"],
        }
        staging_root = self.work_dir / "staging"
        with manifested_temporary_directory(
            staging_root,
            prefix=f"{video_id}-",
            owner={"kind": "lyrics-refresh-staging", "videoId": video_id},
            workspace_root=self.workspace_root,
        ) as staging:
            cover_path = staging / "cover.png"
            cover_path.write_bytes(cover)
            candidate = staging / "candidate.zip"
            create_child_zip(candidate, document, cover_path, self.config)
            state.update(status="complete", completedAt=utc_now(), ownerLyricsSha256=owner_lyrics_sha256,
                         output=child.name)
            return self.commit_child_edit(video_id, candidate, state=state, stage="lyrics-refresh",
                                          binding={"ownerLyricsSha256": owner_lyrics_sha256})
    def _checkpoint_producer(self, state: dict[str, Any]) -> dict[str, Any] | None:
        current = self.provenance
        if not self.provenance_sha256:
            current = collect_provenance(allin1_python=self.allin1_python)
            if state.get('acousticExecution') is not None:
                current['acousticRuntime']['execution'] = state['acousticExecution']
        if state.get('provenanceSha256') == (self.provenance_sha256 or provenance_fingerprint(current)):
            return current
        return None

    def _can_skip(
        self,
        item: dict[str, Any],
        *,
        force: bool,
        lyrics: dict[str, Any],
        owner_lyrics_sha256: str,
    ) -> bool:
        if force:
            return False
        self._ensure_provenance()
        video_id = item['videoId']
        state = self._read_checkpoint(video_id)
        if self.config.get('localRecordingKey'):
            from stemwise_engine.recording_analysis import acoustic_producer_identity
            if (not state or state.get('recordingInput') != self.source.recording.identity
                    or state.get('acousticProducerIdentity') != acoustic_producer_identity(self.config)):
                return False
        child = self.child_path(video_id)
        if not state or state.get("status") != "complete" or not child.is_file():
            return False
        if state.get('childSha256') != _sha256_file(child):
            return False
        try:
            document = validate_child_zip(child, self.config, expected_video_id=video_id)
        except Exception:
            return False
        if document.get("generator", {}).get("version") != self.config["generatorVersion"]:
            return False
        if document.get("analysis", {}).get("status") == "unavailable":
            return False
        generator_matches = state.get("generatorVersion") == self.config["generatorVersion"]
        provenance_matches = self._checkpoint_producer(state) is not None
        return generator_matches and provenance_matches

    def _refresh_reused_child(self, item, lyrics, owner_lyrics_sha256):
        """Apply planned editorial changes only after read-only cache admission."""
        state = self.load_checkpoint(item['videoId'])
        if state['ownerLyricsSha256'] != owner_lyrics_sha256:
            refresh = self._new_state(item, owner_lyrics_sha256)
            refresh['provenanceSha256'] = state['provenanceSha256']
            if 'acousticProducerIdentity' in state:
                refresh['acousticProducerIdentity'] = state['acousticProducerIdentity']
            self._refresh_lyrics_only(item['videoId'], refresh, lyrics, owner_lyrics_sha256)

        if not self.config.get('localRecordingKey'):
            return
        from stemwise_engine.recording_source import selection
        video_id = item['videoId']
        document, cover = read_child(self.child_path(video_id), self.config)
        metadata = self._finalize_metadata(document['metadata'])
        if metadata != document['metadata']:
            current = selection(self.config, self.config['localRecordingKey'])
            if (current.identity != self.source.recording.identity or current.reference_ids != self.source.recording.reference_ids
                    or current.metadata != self.source.recording.metadata):
                raise ValueError('Native recording metadata changed before publication')
            document['metadata'] = metadata
            document['generatedAt'] = _strictly_later_generated_at(document['generatedAt'])
            with manifested_temporary_directory(self.work_dir / 'staging', prefix=f'{video_id}-',
                    owner={'kind':'native-metadata-refresh','videoId':video_id},
                    workspace_root=self.workspace_root) as staging:
                cover_path = staging / 'cover.png'
                cover_path.write_bytes(cover)
                candidate = staging / 'candidate.zip'
                create_child_zip(candidate, document, cover_path, self.config)
                self.commit_child_edit(video_id, candidate, stage='metadata-refresh',
                    binding={'recordingInput': self.source.recording.identity, 'metadata': metadata})


    def _analysis(self, audio_path, duration_ms, *, preserve_native_cache=False):
        if not self.config.get('localRecordingKey'):
            return self._produce_analysis(audio_path, duration_ms)
        from stemwise_engine.recording_analysis import selected_input
        _, _, cache_root, _, _ = selected_input(self.config, self.config['localRecordingKey'])
        with exclusive_process_lock(cache_root / 'workflow.lock'):
            if preserve_native_cache:
                return self._produce_analysis(audio_path, duration_ms)
            return self._analysis_under_cache_lock(audio_path, duration_ms)


    def _analysis_under_cache_lock(self, audio_path, duration_ms):
        from stemwise_engine.recording_analysis import (status as acoustic_status, require_analysis,
            cache_completed_analysis, acoustic_producer_identity, matching_analysis)
        from stemwise_engine.provenance import provenance_fingerprint
        self._analysis_reuse = {'cacheReused': False, 'cacheReason': 'cache-check-failed'}
        cached = acoustic_status(self.config, self.config['localRecordingKey'],
            producer_identity=acoustic_producer_identity(self.config))
        self._analysis_reuse['producerSha256'] = provenance_fingerprint(cached['producerIdentity'])
        if cached['state'] == 'ANALYSIS_COMPLETE':
            if (cached['pcm']['sha256'] != _sha256_file(audio_path)
                    or cached['pcm']['byteCount'] != audio_path.stat().st_size
                    or cached['pcm']['durationMs'] != duration_ms):
                raise ValueError('Completed acoustic analysis does not match the decoded native PCM')
            result = read_bounded_json(self.workspace_root / cached['analysis'],
                                       max_bytes=MAX_JSON_BYTES, root=self.workspace_root)
            reuse_reason = 'complete-recording-cache'
        else:
            match = matching_analysis(self.config, audio_path, duration_ms, cached['producerIdentity'])
            reuse_reason = 'independent-pcm-cache' if match is not None else 'no-matching-producer-pcm-cache'
            result = match
            if match is None:
                result = self._produce_analysis(audio_path, duration_ms)
        require_analysis(result, duration_ms)
        if (_sha256_file(self.source.recording.audio) != self.source.recording.identity['sha256']
                or cached['producerIdentity'] != acoustic_producer_identity(self.config)):
            raise ValueError('Selected native recording or acoustic producer changed during analysis')
        self._analysis_reuse = {'cacheReused': reuse_reason != 'no-matching-producer-pcm-cache',
            'cacheReason': reuse_reason, 'producerSha256': provenance_fingerprint(
                cached['producerIdentity'])}
        if cached['state'] != 'ANALYSIS_COMPLETE':
            cache_completed_analysis(self.config, self.config['localRecordingKey'],
                                     audio_path, duration_ms, result, self.source.recording.identity, cached['producerIdentity'])
        return result


    def _produce_analysis(self, audio_path: Path, duration_ms: int) -> dict[str, Any]:
        self._ensure_provenance()
        self._analysis_reuse = {'cacheReused': False, 'cacheReason': 'no-matching-producer-pcm-cache'}
        result = run_analysis_worker(audio_path, duration_ms, self.project_dir,
                                     int(self.config["analysisTimeoutMinutes"]) * 60)
        from stemwise_engine.recording_analysis import require_execution_binding
        require_execution_binding(result, {'runtime': self.provenance['acousticRuntime']})
        return result

    def _song_info(self, video_id):
        return self.source.song_info(video_id)

    def _metadata(self, info, video_id):
        return self.source.metadata(info, video_id)

    def _finalize_metadata(self, metadata):
        return self.source.finalize_metadata(metadata)

    def _create_cover(self, info, video_id, destination):
        return self.source.create_cover(info, video_id, destination)

    def _acquire_analysis_audio(self, video_id, temporary):
        return self.source.acquire_analysis_audio(video_id, temporary)

    def _source_identity(self, video_id):
        return self.source.identity(video_id)

    def _analysis_replacement_root(self, video_id: str) -> Path:
        return validate_generated_path(self.work_dir,
            self.work_dir / 'analysis-replacements' / validate_video_id(video_id),
            workspace_root=self.workspace_root)

    def _recover_analysis_replacement(self, video_id: str) -> bool:
        """Finish an exact staged pair after interruption; never adopt unrelated bytes."""
        root = self._analysis_replacement_root(video_id)
        pending = root / 'pending.json'
        if not pending.exists():
            return False
        record = read_bounded_json(pending, max_bytes=int(self.config['jsonMaxBytes']), root=root)
        fields = {'version', 'phase', 'attemptId', 'oldChildSha256', 'oldCheckpointSha256',
                  'newChildSha256', 'newCheckpointSha256'}
        if (not isinstance(record, dict) or set(record) not in (fields, fields | {'editBinding'}) or record['version'] != 1
                or record['phase'] not in ('prepared', 'committed')
                or any(not isinstance(record[key], str)
                       or re.fullmatch('[0-9a-f]{64}', record[key]) is None
                       for key in fields - {'version', 'phase'})):
            raise ValueError('Analysis replacement transaction is invalid')
        edit = record.get('editBinding')
        if edit is not None and (not isinstance(edit, dict) or set(edit) != {'videoId', 'stage', 'review'}
                or edit['videoId'] != video_id or edit['stage'] not in {
                    'lyrics-refresh', 'metadata-refresh', 'metadata-review', 'artwork-review', 'artwork-rollback'}):
            raise ValueError('Child edit transaction binding is invalid')
        attempt = validate_generated_path(root, root / record['attemptId'],
                                         workspace_root=self.workspace_root)
        pairs = ((self.child_path(video_id), attempt / 'previous.zip', attempt / 'next.zip',
                  record['oldChildSha256'], record['newChildSha256'], int(self.config['childZipMaxBytes'])),
                 (self.checkpoint_path(video_id), attempt / 'previous.json', attempt / 'next.json',
                  record['oldCheckpointSha256'], record['newCheckpointSha256'], int(self.config['jsonMaxBytes'])))
        def require_package(state, document):
            if (not isinstance(state, dict) or state.get('status') != 'complete'
                    or state.get('videoId') != video_id
                    or state.get('childSha256') != record['newChildSha256']
                    or document['source'] != self._source_identity(video_id)):
                raise ValueError('Analysis replacement package binding is invalid')
            if edit is None:
                self._require_retained_analysis_audio(video_id, state)
        if record['phase'] == 'committed':
            current = []
            for target, _previous, _replacement, _old_sha, new_sha, limit in pairs:
                raw = read_bounded_regular_bytes(target, max_bytes=limit, root=target.parent)
                if hashlib.sha256(raw).hexdigest() != new_sha:
                    raise ValueError('Committed analysis replacement conflicts with the current result')
                current.append(raw)
            require_package(parse_strict_json_bytes(current[1]),
                validate_child_zip(self.child_path(video_id), self.config, expected_video_id=video_id))
        else:
            staged = []
            for target, previous, replacement, old_sha, new_sha, limit in pairs:
                old = read_bounded_regular_bytes(previous, max_bytes=limit, root=attempt)
                new = read_bounded_regular_bytes(replacement, max_bytes=limit, root=attempt)
                current = read_bounded_regular_bytes(target, max_bytes=limit, root=target.parent)
                if (hashlib.sha256(old).hexdigest() != old_sha
                        or hashlib.sha256(new).hexdigest() != new_sha
                        or hashlib.sha256(current).hexdigest() not in (old_sha, new_sha)):
                    raise ValueError('Analysis replacement conflicts with the committed result')
                staged.append((target, old, new))
            require_package(parse_strict_json_bytes(staged[1][2]),
                validate_child_zip(attempt / 'next.zip', self.config, expected_video_id=video_id))
            try:
                for target, _old, new in staged:
                    atomic_write_bytes(target, new)
                for target, _old, new in staged:
                    if _sha256_file(target) != hashlib.sha256(new).hexdigest():
                        raise ValueError('Analysis replacement readback failed')
                validate_child_zip(self.child_path(video_id), self.config, expected_video_id=video_id)
            except Exception:
                for target, old, _new in reversed(staged):
                    atomic_write_bytes(target, old)
                raise
            atomic_write_json(pending, {**record, 'phase': 'committed'})
        if attempt.exists():
            remove_manifested_directory(root, attempt,
                owner={'kind': 'analysis-replacement', 'videoId': video_id},
                workspace_root=self.workspace_root)
        pending.unlink()
        return True

    def _rebuild_retained_analysis(self, item, previous_state, previous_raw,
                                   previous_child_raw, previous_document, audio, lyrics, lyrics_sha, *, force=False,
                                   replacement_operation_id=None):
        video_id = item['videoId']
        binding = {'version': 1, 'producerSha256': self.provenance_sha256,
                   'previousCheckpointSha256': hashlib.sha256(previous_raw).hexdigest(),
                   'previousChildSha256': hashlib.sha256(previous_child_raw).hexdigest(),
                   'pcm': previous_state['originalAudio'], 'ownerLyricsSha256': lyrics_sha}
        attempt_id = hashlib.sha256(json.dumps(binding, sort_keys=True).encode('utf-8')).hexdigest()
        root = self._analysis_replacement_root(video_id)
        attempt = root / attempt_id
        owner = {'kind': 'analysis-replacement', 'videoId': video_id}
        if not attempt.exists():
            create_manifested_directory(root, attempt, owner=owner, workspace_root=self.workspace_root)
        state = self._new_state(item, lyrics_sha, retain_prepared=replacement_operation_id is None)
        if replacement_operation_id is not None:
            state['analysisReplacementOperationId'] = replacement_operation_id
        state['originalAudio'] = dict(previous_state['originalAudio'])
        state['stages'] = {key: value for key, value in previous_state.get('stages', {}).items()
                           if key not in ('analysis', 'package')}
        analysis_path = attempt / 'analysis.json'
        try:
            if analysis_path.exists():
                saved = read_bounded_json(analysis_path, max_bytes=int(self.config['jsonMaxBytes']), root=attempt)
                if saved.get('binding') != binding:
                    raise ValueError('Staged analysis does not match the current replacement')
                analysis, details = saved['analysis'], saved['details']
            else:
                started = time.monotonic()
                details = {'startedAt': utc_now(), 'producerSha256': self.provenance_sha256,
                           'pcmSha256': binding['pcm']['sha256'], 'cacheReused': None}
                self._analysis_reuse = {}
                if not force and self._checkpoint_producer(previous_state) is not None:
                    analysis = previous_document['analysis']
                    causes = {}
                    self._analysis_reuse = {'cacheReused': True, 'cacheReason': 'verified-committed-numerics'}
                else:
                    with analysis_diagnostics(self._attempt_failure_path(video_id).parent / 'causes',
                            workspace_root=self.workspace_root) as causes:
                        # Explicit replacement keeps the independent native cache intact.
                        if replacement_operation_id is not None and self.config.get('localRecordingKey'):
                            analysis = self._analysis(audio, binding['pcm']['durationMs'], preserve_native_cache=True)
                        else:
                            analysis = self._analysis(audio, binding['pcm']['durationMs'])
                details.update(causes)
                details.update(self._analysis_reuse)
                details['elapsedMs'] = round((time.monotonic() - started) * 1000)
                if analysis.get('status') == 'unavailable':
                    raise ValueError('Every canonical PC analysis feature was unavailable')
                if self._provenance_needs_refresh:
                    self._refresh_provenance()
                    if self.provenance_sha256 != binding['producerSha256']:
                        remove_manifested_directory(root, attempt, owner=owner,
                                                   workspace_root=self.workspace_root)
                        binding['producerSha256'] = self.provenance_sha256
                        attempt_id = hashlib.sha256(json.dumps(binding, sort_keys=True).encode('utf-8')).hexdigest()
                        attempt = root / attempt_id
                        if not attempt.exists():
                            create_manifested_directory(root, attempt, owner=owner, workspace_root=self.workspace_root)
                        analysis_path = attempt / 'analysis.json'
                        details['producerSha256'] = self.provenance_sha256
                # Retain the successful expensive phase independently of packaging/publication.
                atomic_write_json(analysis_path, {'binding': binding, 'analysis': analysis, 'details': details})
            self._require_retained_analysis_audio(video_id, previous_state)
            if self.load_lyrics_snapshot(video_id)[1] != lyrics_sha:
                raise ValueError('Owner lyrics changed during analysis replacement')
            document, cover = read_child(self.child_path(video_id), self.config)
            if _sha256_file(self.child_path(video_id)) != binding['previousChildSha256']:
                raise ValueError('Committed child changed during analysis replacement')
            document.update(analysis=analysis, lyrics=lyrics,
                generatedAt=_strictly_later_generated_at(previous_document['generatedAt']),
                generator={'name': 'stemwise-pc', 'version': self.config['generatorVersion']})
            cover_path = attempt / 'cover.png'
            atomic_write_bytes(cover_path, cover)
            create_child_zip(attempt / 'next.zip', document, cover_path, self.config)
            validate_child_zip(attempt / 'next.zip', self.config, expected_video_id=video_id)
            state.update(status='complete', completedAt=utc_now(), output=self.child_path(video_id).name,
                         childSha256=_sha256_file(attempt / 'next.zip'), updatedAt=utc_now(),
                         provenanceSha256=binding['producerSha256'])
            state['stages']['analysis'] = {**details, 'status': 'complete', 'analysisStatus': analysis['status']}
            state['stages']['package'] = {'status': 'complete', 'at': utc_now(),
                                         'bytes': (attempt / 'next.zip').stat().st_size}
            atomic_write_json(attempt / 'next.json', state)
            atomic_write_bytes(attempt / 'previous.zip', previous_child_raw)
            atomic_write_bytes(attempt / 'previous.json', previous_raw)
            atomic_write_json(root / 'pending.json', {'version': 1, 'phase': 'prepared', 'attemptId': attempt_id,
                'oldChildSha256': binding['previousChildSha256'],
                'oldCheckpointSha256': binding['previousCheckpointSha256'],
                'newChildSha256': state['childSha256'], 'newCheckpointSha256': _sha256_file(attempt / 'next.json')})
            self._recover_analysis_replacement(video_id)
            return 'complete'
        except BaseException as error:
            try:
                self._record_attempt_failure(video_id, error, previous_raw,
                                             analysis_stage=state.get('stages', {}).get('analysis'))
            except Exception:
                pass
            finally:
                if isinstance(error, WorkerRetirementError):
                    raise error
            if not isinstance(error, Exception):
                raise
            return 'failed'

    def analysis_replacement_complete(self, video_id, request, pcm):
        """Read the committed owner and PCM without observing numerical execution hardware."""
        current = self.load_checkpoint(video_id)
        if (current and current.get('status') == 'complete'
                and current.get('analysisReplacementOperationId') == request['operationId']
                and current.get('originalAudio') == pcm and current.get('childSha256') == _sha256_file(self.child_path(video_id))):
            self._require_retained_analysis_audio(video_id, current)
            return True
        return False

    def replace_missing_analysis(self, item, request, root, pcm):
        """Fresh PCM may replace an old numerical owner only through explicit admission."""
        video_id = item['videoId']
        self._ensure_provenance()
        if self.analysis_replacement_complete(video_id, request, pcm):
            return 'complete'
        previous_raw = read_bounded_regular_bytes(root/'previous.json', max_bytes=int(self.config['jsonMaxBytes']), root=root)
        previous_child = read_bounded_regular_bytes(root/'previous.zip', max_bytes=int(self.config['childZipMaxBytes']), root=root)
        if (request['source'] != self._source_identity(video_id)
                or _sha256_file(self.checkpoint_path(video_id)) != request['checkpointSha256']
                or _sha256_file(self.child_path(video_id)) != request['childSha256']
                or hashlib.sha256(previous_raw).hexdigest() != request['checkpointSha256']
                or hashlib.sha256(previous_child).hexdigest() != request['childSha256']):
            raise ValueError('Explicit analysis replacement owner changed')
        previous = parse_strict_json_bytes(previous_raw)
        previous['originalAudio'] = dict(pcm)
        audio = self._require_retained_analysis_audio(video_id, previous)
        document, _ = read_child(self.child_path(video_id), self.config)
        lyrics, lyrics_sha = self.load_lyrics_snapshot(video_id)
        return self._rebuild_retained_analysis(item, previous, previous_raw, previous_child, document,
            audio, lyrics, lyrics_sha, force=True, replacement_operation_id=request['operationId'])

    def process_song(self, item: dict[str, Any], *, force: bool = False):
        from stemwise_engine.telemetry_retention import publication
        with publication(self.workspace_root, self._attempt_failure_path(item['videoId']).parent):
            return self._process_song(item, force=force)

    def _cleanup_completed_song(self, video_id):
        from stemwise_engine.common import manifested_cleanup_receipt
        root = self.work_dir / 'staging'
        paths = set(root.glob(f'{video_id}-*'))
        paths.update(path.with_name(path.name[1:-len('.cleanup.json')])
            for path in root.glob(f'.{video_id}-*.cleanup.json'))
        for path in sorted(paths):
            if re.fullmatch(re.escape(video_id) + '-[0-9a-f]{32}', path.name):
                try:
                    receipt = manifested_cleanup_receipt(path)
                    if receipt.exists() or receipt.is_symlink():
                        pending = read_bounded_json(receipt, max_bytes=16384, root=root)
                        manifest = pending.get('manifest') if isinstance(pending, dict) else None
                    else:
                        manifest = read_bounded_json(path / GENERATED_MANIFEST_NAME, max_bytes=16384, root=path)
                    owner = manifest.get('owner') if isinstance(manifest, dict) else None
                    if (not isinstance(owner, dict) or set(owner) != {'kind', 'videoId'}
                            or owner['videoId'] != video_id or owner['kind'] not in {
                                'song-package-staging', 'lyrics-refresh-staging', 'native-metadata-refresh',
                                'metadata-review-staging', 'artwork-rollback'}):
                        raise ValueError('Completed song staging owner differs')
                    remove_manifested_directory(root, path,
                        owner=owner,
                        workspace_root=self.workspace_root)
                except OSError as error:
                    print(f'package-cleanup-unavailable:{diagnostic_code(error)}', file=sys.stderr)
        temporary = self.temp_root / video_id
        if not self.config.get('retainSuccessfulAnalysisAudio'):
            try:
                self._cleanup_temp(temporary)
            except OSError as error:
                print(f'package-cleanup-unavailable:{diagnostic_code(error)}', file=sys.stderr)

    def _process_song(self, item: dict[str, Any], *, force: bool = False) -> str:
        video_id = validate_video_id(str(item["videoId"]))
        if self._recover_analysis_replacement(video_id):
            recovered = self.load_checkpoint(video_id)
            if (self._checkpoint_producer(recovered) is not None
                    and recovered.get('generatorVersion') == self.config['generatorVersion']
                    and recovered.get('ownerLyricsSha256') == self.load_lyrics_snapshot(video_id)[1]):
                return 'complete'
        child = self.child_path(video_id)
        checkpoint_path = self.checkpoint_path(video_id)
        try:
            checkpoint_stat = checkpoint_path.lstat()
        except FileNotFoundError:
            previous_checkpoint_raw = None
        else:
            if not stat.S_ISREG(checkpoint_stat.st_mode):
                raise ValueError("analysis checkpoint must be a regular file")
            previous_checkpoint_raw = read_bounded_regular_bytes(
                checkpoint_path,
                max_bytes=int(self.config["jsonMaxBytes"]),
                root=self.checkpoint_dir,
            )
        previous_checkpoint: dict[str, Any] | None = None
        if previous_checkpoint_raw is not None:
            try:
                parsed_checkpoint = parse_strict_json_bytes(previous_checkpoint_raw)
                if isinstance(parsed_checkpoint, dict):
                    previous_checkpoint = parsed_checkpoint
            except (UnicodeError, ValueError):
                pass
        previous_complete_raw = (
            previous_checkpoint_raw
            if previous_checkpoint is not None
            and previous_checkpoint.get("status") == "complete"
            else None
        )
        previous_child_raw: bytes | None = None
        previous_document = None
        try:
            previous_document = validate_child_zip(child, self.config, expected_video_id=video_id)
        except PackageValidationError:
            previous_child_raw = None
        else:
            previous_child_raw = read_bounded_regular_bytes(
                child,
                max_bytes=int(self.config["childZipMaxBytes"]),
                root=self.children_dir,
            )
        previous_complete_child_raw = (
            previous_child_raw if previous_complete_raw is not None else None
        )
        state: dict[str, Any] | None = None
        owner_lyrics_sha256 = "unreadable"
        temporary: Path | None = None
        package_succeeded = False
        retained_rebuild = False
        retirement_error = None
        try:
            lyrics, owner_lyrics_sha256 = self.load_lyrics_snapshot(video_id)
            if self._can_skip(
                item,
                force=force,
                lyrics=lyrics,
                owner_lyrics_sha256=owner_lyrics_sha256,
            ):
                self._refresh_reused_child(item, lyrics, owner_lyrics_sha256)
                self._cleanup_completed_song(video_id)
                return "skipped"
            if (self.config.get('retainSuccessfulAnalysisAudio')
                    and previous_complete_raw is not None and previous_complete_child_raw is not None):
                retained_audio = self._require_retained_analysis_audio(video_id, previous_checkpoint)
                if (previous_checkpoint.get('childSha256') != hashlib.sha256(previous_complete_child_raw).hexdigest()
                        or previous_document.get('source') != self._source_identity(video_id)):
                    raise ValueError('Completed child differs from its analysis checkpoint')
                retained_rebuild = True
                return self._rebuild_retained_analysis(item, previous_checkpoint,
                    previous_complete_raw, previous_complete_child_raw, previous_document,
                    retained_audio, lyrics, owner_lyrics_sha256, force=force)
            state = self._new_state(item, owner_lyrics_sha256)
            self.save_checkpoint(state)

            prepared = self.prepared_inputs(video_id)
            info_warning: str | None = None
            try:
                info = prepared['info'] if prepared is not None else self._song_info(video_id)
            except WorkerRetirementError:
                raise
            except Exception as error:
                info_warning = diagnostic_code(error)
                info = {
                    "id": video_id,
                    "title": item.get("title") or video_id,
                    "uploader": item.get("uploader") or item.get("channel") or "Unknown artist",
                }
            metadata = prepared['metadata'] if prepared is not None else self._metadata(info, video_id)
            details = {"matchStatus": metadata["matchStatus"]}
            if info_warning:
                details["warning"] = info_warning
            self._stage(state, "metadata", "complete", details=details)
            from stemwise_engine.analysis_resources import require_analysis_capacity
            from stemwise_engine.youtube import MAX_DURATION_MS
            advertised = info.get('duration')
            known_duration = type(advertised) in (int, float) and math.isfinite(advertised) and advertised > 0
            admission_ms = max(1, round(advertised*1000)) if known_duration else MAX_DURATION_MS
            admission = require_analysis_capacity(admission_ms, scratch_root=self.work_dir)
            self._stage(state, 'analysis-admission', 'complete', details={
                'advertisedDurationMs': admission_ms if known_duration else None, **admission})


            self._stage(state, "lyrics", "complete", details={"lyricsStatus": lyrics["status"]})

            staging_root = self.work_dir / "staging"
            with manifested_temporary_directory(
                staging_root,
                prefix=f"{video_id}-",
                owner={"kind": "song-package-staging", "videoId": video_id},
                workspace_root=self.workspace_root,
            ) as staging:
                cover_path = staging / "cover.png"
                temporary = self._temp_dir(video_id)
                if prepared is not None:
                    atomic_write_bytes(cover_path, read_bounded_regular_bytes(temporary/'cover.png',
                        max_bytes=int(self.config['pngMaxBytes']), root=temporary))
                    artwork = prepared['artwork']
                else:
                    artwork = self._create_cover(info, video_id, cover_path)
                    cover = read_bounded_regular_bytes(cover_path, max_bytes=int(self.config['pngMaxBytes']), root=staging)
                    _verify_png(cover, max_bytes=int(self.config['pngMaxBytes']), max_pixels=int(self.config['artworkMaxPixels']))
                    atomic_write_bytes(temporary/'cover.png', cover)
                    state['preparedInputs'] = {'source': self._source_identity(video_id),
                        'info': info, 'metadata': metadata, 'artwork': artwork,
                        'artworkSha256': hashlib.sha256(cover).hexdigest()}
                from PIL import Image

                with Image.open(cover_path) as decoded_cover:
                    artwork_details: dict[str, Any] = {
                        "bytes": cover_path.stat().st_size,
                        "width": decoded_cover.width,
                        "height": decoded_cover.height,
                    }
                    if decoded_cover.width < 1400 or decoded_cover.height < 1400:
                        artwork_details["warning"] = (
                            "The source exposed no artwork meeting the preferred 1400 px size; "
                            "the available source image was retained without upscaling."
                        )
                self._stage(
                    state,
                    "artwork",
                    "complete",
                    details=artwork_details,
                )

                try:
                    phase = self._initial_phase(video_id, temporary)
                    if phase is None:
                        downloaded_info, audio_path = self._acquire_analysis_audio(video_id, temporary)
                    else:
                        downloaded_info, audio_path = phase['info'], temporary/'analysis.wav'
                    duration_ms = probe_duration_ms(audio_path)
                    audio_sha256 = _sha256_file(audio_path)
                except Exception as error:
                    reason = f"temporary-audio-failed:{diagnostic_code(error)}"
                    try:
                        self._stage(state, "temporary-audio", "failed", reason=reason)
                    finally:
                        if isinstance(error, WorkerRetirementError):
                            raise error
                    raise RuntimeError(reason) from error

                state["originalAudio"] = {
                    "sha256": audio_sha256,
                    "byteCount": audio_path.stat().st_size,
                    "durationMs": duration_ms,
                }
                phase = {**(phase or {}), 'source': self._source_identity(video_id),
                    'info': downloaded_info, 'pcm': state['originalAudio'],
                    'producerSha256': self.provenance_sha256}
                atomic_write_json(temporary/'initial-phase.json', phase)
                self._stage(
                    state,
                    "temporary-audio",
                    "complete",
                    details={
                        "durationMs": duration_ms,
                        "sha256": audio_sha256,
                        "byteCount": audio_path.stat().st_size,
                    },
                )

                # The download extraction may expose richer YouTube Music fields.
                try:
                    metadata = self._metadata(downloaded_info, video_id)
                except Exception:
                    pass
                metadata, research = enrich_metadata(
                    metadata,
                    duration_ms,
                    self.metadata_client,
                    diagnostics_dir=self._attempt_failure_path(video_id).parent / 'causes',
                )
                metadata = self._finalize_metadata(metadata)
                self._stage(
                    state,
                    "metadata",
                    "complete",
                    details={
                        "matchStatus": metadata["matchStatus"],
                        "research": research,
                    },
                )

                analysis_started = time.monotonic()
                analysis_details = {'startedAt': utc_now(), 'producerSha256': self.provenance_sha256,
                                    'pcmSha256': audio_sha256, 'cacheReused': None, 'cacheReason': 'not-checked'}
                self._analysis_reuse = {}
                self._stage(state, 'analysis', 'running', details=analysis_details)
                private_causes = {}
                try:
                    with analysis_diagnostics(self._attempt_failure_path(video_id).parent/'causes',
                            workspace_root=self.workspace_root) as private_causes:
                        if 'analysis' in phase:
                            analysis = phase['analysis']
                            self._analysis_reuse = {'cacheReused': True, 'cacheReason': 'initial-phase-checkpoint'}
                        else:
                            analysis = self._analysis(audio_path, duration_ms)
                except BaseException as error:
                    analysis_details.update(private_causes)
                    reason = f"analysis-failed:{diagnostic_code(error)}"
                    analysis_details.update(self._analysis_reuse)
                    analysis_details['elapsedMs'] = round((time.monotonic() - analysis_started) * 1000)
                    try:
                        self._stage(state, 'analysis', 'failed' if isinstance(error, Exception) else 'interrupted',
                                    reason=reason, details=analysis_details)
                    finally:
                        if isinstance(error, WorkerRetirementError):
                            raise error
                    if not isinstance(error, Exception):
                        raise
                    raise RuntimeError(reason) from error
                analysis_details.update(self._analysis_reuse)
                analysis_details.update(private_causes)
                analysis_details['elapsedMs'] = round((time.monotonic() - analysis_started) * 1000)
                if analysis.get("status") == "unavailable":
                    reason = "Every canonical PC analysis feature was unavailable."
                    self._stage(state, "analysis", "failed", reason=reason, details=analysis_details)
                    raise RuntimeError(reason)
                if self._provenance_needs_refresh:
                    # Fresh installations may download model assets during the
                    # first analyzer pass. Recollect until every expected model
                    # identity is present and verified, then freeze this process's
                    # fingerprint for later songs.
                    self._refresh_provenance()
                    state["provenanceSha256"] = self.provenance_sha256
                from stemwise_engine.contract import require_usable_analysis
                require_usable_analysis(analysis, duration_ms)
                if _sha256_file(audio_path) != audio_sha256:
                    raise ValueError('Initial analysis PCM changed during inference')
                phase.update(analysis=analysis, producerSha256=self.provenance_sha256)
                atomic_write_json(temporary/'initial-phase.json', phase)
                self._stage(
                    state,
                    "analysis",
                    "complete",
                    details={
                        **analysis_details,
                        "analysisStatus": analysis["status"],
                        "toolStatuses": {
                            run["name"]: run["status"] for run in analysis["toolRuns"]
                        },
                    },
                )

                document: dict[str, Any] = {
                    "formatVersion": 2,
                    "kind": "stemwise.song-enrichment",
                    "source": self._source_identity(video_id),
                    "generatedAt": (_strictly_later_generated_at(previous_document['generatedAt'])
                                    if previous_document is not None else utc_now()),
                    "generator": {
                        "name": "stemwise-pc",
                        "version": self.config["generatorVersion"],
                    },
                    "metadata": metadata,
                    "artwork": artwork,
                    "lyrics": lyrics,
                    "analysis": analysis,
                }
                create_child_zip(child, document, cover_path, self.config)
                state["status"] = "complete"
                state["completedAt"] = utc_now()
                state["output"] = child.name
                state['childSha256'] = _sha256_file(child)
                state["ownerLyricsSha256"] = owner_lyrics_sha256
                if bool(self.config.get("retainSuccessfulAnalysisAudio")):
                    (temporary / "download-info.json").unlink(missing_ok=True)
                    state["originalAudio"]["retained"] = True
                self._stage(
                    state,
                    "package",
                    "complete",
                    details={"bytes": child.stat().st_size},
                )
                # The durable complete checkpoint is the package commit marker.
                package_succeeded = True
            return "complete"
        except BaseException as error:
            if isinstance(error, WorkerRetirementError):
                retirement_error = error
            if package_succeeded:
                if isinstance(error, WorkerRetirementError) or not isinstance(error, Exception):
                    raise
                print(f'package-cleanup-unavailable:{diagnostic_code(error)}', file=sys.stderr)
                return 'complete'
            if retained_rebuild:
                raise  # The replacement transaction owns its interruption and rollback.
            try:
                self._record_attempt_failure(video_id, error, previous_checkpoint_raw,
                    analysis_stage=(state or {}).get('stages', {}).get('analysis'))
            except Exception:
                pass
            if (
                previous_complete_raw is not None
                and previous_complete_child_raw is not None
                and not package_succeeded
            ):
                atomic_write_bytes(child, previous_complete_child_raw)
                atomic_write_bytes(checkpoint_path, previous_complete_raw)
            else:
                if state is None:
                    state = self._new_state(item, owner_lyrics_sha256)
                if previous_child_raw is None:
                    child.unlink(missing_ok=True)
                else:
                    atomic_write_bytes(child, previous_child_raw)
                state["status"] = "failed" if isinstance(error, Exception) else "interrupted"
                state["failedAt"] = utc_now()
                state["error"] = diagnostic_code(error)
                state.pop("output", None)
                self.save_checkpoint(state)
            if isinstance(error, WorkerRetirementError) or not isinstance(error, Exception):
                raise
            return "failed"
        finally:
            if retirement_error is not None:
                raise retirement_error
            if temporary is not None and temporary.exists():
                retain_success = package_succeeded and bool(
                    self.config.get("retainSuccessfulAnalysisAudio")
                )
                retain_phase = not package_succeeded and (temporary / 'initial-phase.json').is_file()
                if not package_succeeded and not retain_phase and not self.config.get('localRecordingKey'):
                    from stemwise_engine.youtube import completed_analysis_download
                    try:
                        retain_phase = completed_analysis_download(video_id, temporary) is not None
                    except (OSError, ValueError, KeyError, TypeError):
                        retain_phase = False
                if not retain_success and not retain_phase and (
                    package_succeeded or not bool(self.config["keepFailedAudio"])
                ):
                    try:
                        self._cleanup_temp(temporary)
                    except OSError as error:
                        if not package_succeeded:
                            raise
                        print(f'package-cleanup-unavailable:{diagnostic_code(error)}', file=sys.stderr)


    def checkpoints(self) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for path in sorted(self.checkpoint_dir.glob("*.json")):
            try:
                state = self._read_checkpoint(path.stem)
                if isinstance(state, dict):
                    result.append(state)
            except Exception as error:
                result.append({"videoId": path.stem, "status": "corrupt",
                               "error": diagnostic_code(error)})
        return result

    def write_report(self, *, snapshot: dict[str, Any] | None = None) -> dict[str, Any]:
        if snapshot is None and self.inventory_path.is_file():
            snapshot = self.load_inventory(create_if_missing=False)
        raw_states = self.checkpoints()
        states = [_pc_report_value(state) for state in raw_states]
        counts: dict[str, int] = {}
        for state in states:
            candidate = state.get("status")
            status = (
                candidate
                if isinstance(candidate, str)
                and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,80}", candidate)
                else "corrupt"
            )
            state["status"] = status
            counts[status] = counts.get(status, 0) + 1
        report: dict[str, Any] = {
            "reportVersion": 2,
            "generatedAt": utc_now(),
            "playlist": {
                key: value
                for key, value in ((snapshot or {}).get("playlist") or {}).items()
                if key in {"id", "title", "uploader"}
            },
            "inventoryCount": (snapshot or {}).get("itemCount", 0),
            "checkpointCounts": counts,
            "completedChildFileCount": 0,
            "provenanceSha256": self.provenance_sha256 or None,
            "provenance": self.provenance,
            "currentRuntimeVerification": {"requested": bool(self.provenance_sha256)},
            "states": states,
        }
        # A checkpoint is marked complete only after the final child has been
        # reopened and validated.  Avoid re-decompressing every prior waveform
        # after each song; the explicit `validate` command performs a full scan.
        completed_children = 0
        for state in states:
            if state.get("status") != "complete":
                continue
            try:
                exists = self.child_path(str(state.get("videoId", ""))).is_file()
            except ValueError:
                exists = False
            completed_children += int(exists)
        report["completedChildFileCount"] = completed_children
        atomic_write_json(self.report_path, report)
        return report

    def status(self) -> dict[str, Any]:
        return self.write_report()
