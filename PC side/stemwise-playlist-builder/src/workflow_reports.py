"""Single-owner immutable report publication, independent of job orchestration."""
from pathlib import Path
from typing import Any
import hashlib
import json
from stemwise_engine.workflow_context import report_control_path
import stemwise_engine.workflow_diagnostics as workflow_diagnostics
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.workflow_records import PRIVATE_REPORT_VERSION, OperationStart, ReportBindings
import re
import stat
import time
import uuid
from stemwise_engine.workflow_records import WORKFLOW_OPERATION_STATES, WORKFLOW_OPERATION_ACTIONS, engine_source_sha256
from stemwise_engine.common import GENERATED_MANIFEST_NAME, create_manifested_directory, read_json, utc_now
from stemwise_engine.common import (atomic_write_bytes, atomic_write_json, json_bytes, read_bounded_json,
                    read_bounded_regular_bytes, sha256_file, validate_generated_path, validate_video_id,
                    parse_strict_json_bytes)
from stemwise_engine.workflow_records import FutureSongError, ReportSizeLimitExceeded, operation_record_is_safe


class ImmutableReportStore:
    def __init__(self, workspace_root, max_json_bytes):
        self.workspace_root = workspace_root
        self.max_json_bytes = max_json_bytes

    @staticmethod
    def _validate_alignment_attempt_id(value):
        if not isinstance(value, str) or re.fullmatch('[0-9a-f]{32}', value) is None:
            raise FutureSongError('alignment attempt ID is invalid')
        return value

    @staticmethod
    def _require_payload(record, evidence):
        operation = record.get('operation')
        if operation == 'workflow-operation':
            valid = evidence is None and operation_record_is_safe(record)
        elif operation == 'lyrics-alignment':
            valid = (isinstance(evidence, dict)
                and set(evidence) == {'evidenceVersion', 'runId', 'videoId', 'operation', 'diagnostics'}
                and evidence['evidenceVersion'] == 1 and isinstance(evidence['diagnostics'], dict)
                and all(evidence.get(key) == record.get(key) for key in ('runId', 'videoId', 'operation')))
        else:
            valid = evidence is None and operation in {'package-finalization', 'authority-outcome', 'display-review', 'artwork-repair', 'capability-completion'}
        if not valid:
            raise FutureSongError('immutable run payload is invalid')

    def artifacts_match(
        self, run_dir: Path, record: dict[str, Any]
    ) -> bool:
        """Validate every content-addressed run artifact and reject extras."""

        operation = record.get("operation")
        manifests = record.get("reportArtifacts")
        if operation == "lyrics-alignment":
            if not isinstance(manifests, list) or len(manifests) != 1:
                return False
        elif operation == "workflow-operation":
            if manifests is not None or not operation_record_is_safe(record):
                return False
            manifests = []
        elif operation in {
            "package-finalization",
            "authority-outcome",
            "display-review",
            "artwork-repair",
            "capability-completion",
        }:
            if manifests is not None:
                return False
            manifests = []
        else:
            return False
        expected_names = {"run.json"}
        for manifest in manifests:
            if (
                not isinstance(manifest, dict)
                or set(manifest) != {"kind", "fileName", "sha256", "byteCount"}
                or manifest.get("kind") != "alignment-evidence"
                or not isinstance(manifest.get("sha256"), str)
                or re.fullmatch(r"[0-9a-f]{64}", manifest["sha256"]) is None
                or manifest.get("fileName") != f"evidence.{manifest['sha256']}.json"
                or type(manifest.get("byteCount")) is not int
                or manifest["byteCount"] <= 0
            ):
                return False
            path = validate_generated_path(
                run_dir,
                run_dir / manifest["fileName"],
                workspace_root=self.workspace_root,
            )
            payload = read_bounded_regular_bytes(
                path, max_bytes=self.max_json_bytes, root=run_dir
            )
            if (
                len(payload) != manifest["byteCount"]
                or hashlib.sha256(payload).hexdigest() != manifest["sha256"]
            ):
                return False
            evidence = read_bounded_json(
                path, max_bytes=self.max_json_bytes, root=run_dir
            )
            if (
                not isinstance(evidence, dict)
                or set(evidence)
                != {"evidenceVersion", "runId", "videoId", "operation", "diagnostics"}
                or evidence.get("evidenceVersion") != 1
                or evidence.get("runId") != record.get("runId")
                or evidence.get("videoId") != record.get("videoId")
                or evidence.get("operation") != operation
                or not isinstance(evidence.get("diagnostics"), dict)
            ):
                return False
            expected_names.add(manifest["fileName"])
        try:
            actual_names = {
                path.name
                for path in run_dir.iterdir()
                if path.is_file() and not path.is_symlink()
            }
            if any(not path.is_file() or path.is_symlink() for path in run_dir.iterdir()):
                return False
        except OSError:
            return False
        return actual_names == expected_names


    def commit(self, report_root, record, *, evidence=None, current_pointer="current-run.json"):
        from stemwise_engine.telemetry_retention import publication
        with publication(self.workspace_root, report_root):
            return self._commit(report_root, record, evidence=evidence, current_pointer=current_pointer)

    def _commit(
        self,
        report_root: Path,
        record: dict[str, Any],
        *,
        evidence: dict[str, Any] | None = None,
        current_pointer: str = "current-run.json",
    ) -> str:
        if current_pointer not in {"current-run.json", "current-operation.json"}:
            raise FutureSongError("immutable run pointer name is invalid")
        run_id = self._validate_alignment_attempt_id(record["runId"])
        run_dir = validate_generated_path(
            report_root,
            report_root / "runs" / run_id,
            workspace_root=self.workspace_root,
        )
        stored_record = dict(record)
        artifact_payload: bytes | None = None
        artifact_name: str | None = None
        if evidence is not None:
            artifact_payload = json_bytes(evidence, pretty=True)
            if len(artifact_payload) > self.max_json_bytes:
                raise ReportSizeLimitExceeded(
                    "alignment evidence exceeds the JSON size limit"
                )
            artifact_sha256 = hashlib.sha256(artifact_payload).hexdigest()
            artifact_name = f"evidence.{artifact_sha256}.json"
            stored_record["reportArtifacts"] = [
                {
                    "kind": "alignment-evidence",
                    "fileName": artifact_name,
                    "sha256": artifact_sha256,
                    "byteCount": len(artifact_payload),
                }
            ]
        record_payload = json_bytes(stored_record, pretty=True)
        if len(record_payload) > self.max_json_bytes:
            raise ReportSizeLimitExceeded(
                "immutable run record exceeds the JSON size limit"
            )
        self._require_payload(stored_record, evidence)
        run_path = validate_generated_path(
            report_root,
            run_dir / "run.json",
            workspace_root=self.workspace_root,
        )
        if run_path.is_file():
            if read_bounded_json(
                run_path, max_bytes=self.max_json_bytes, root=report_root
            ) != stored_record or not self.artifacts_match(
                run_dir, stored_record
            ):
                raise FutureSongError("immutable run record already exists or is damaged")
        else:
            run_dir.mkdir(parents=True, exist_ok=True)
            allowed_partial = {artifact_name} if artifact_name is not None else set()
            atomic_destinations = {"run.json", *allowed_partial}
            for path in run_dir.iterdir():
                if any(
                    re.fullmatch(
                        rf"\.{re.escape(destination)}\.[a-z0-9_]{{8}}",
                        path.name,
                    )
                    for destination in atomic_destinations
                ):
                    metadata = path.lstat()
                    if (
                        path.is_symlink()
                        or not stat.S_ISREG(metadata.st_mode)
                        or getattr(metadata, "st_file_attributes", 0)
                        & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
                    ):
                        raise FutureSongError("immutable run temporary residue is unsafe")
                    validate_generated_path(
                        run_dir,
                        path,
                        workspace_root=self.workspace_root,
                    ).unlink()
            existing = {path.name for path in run_dir.iterdir()}
            if not existing.issubset(allowed_partial):
                raise FutureSongError("run directory is not empty")
            if artifact_name is not None and artifact_payload is not None:
                artifact_path = validate_generated_path(
                    run_dir,
                    run_dir / artifact_name,
                    workspace_root=self.workspace_root,
                )
                if artifact_path.is_file():
                    if read_bounded_regular_bytes(
                        artifact_path, max_bytes=self.max_json_bytes, root=run_dir
                    ) != artifact_payload:
                        raise FutureSongError("partial immutable run artifact is damaged")
                else:
                    atomic_write_bytes(
                        artifact_path,
                        artifact_payload,
                        root=run_dir,
                        workspace_root=self.workspace_root,
                    )
            atomic_write_bytes(
                run_path,
                record_payload,
                root=run_dir,
                workspace_root=self.workspace_root,
            )
            if not self.artifacts_match(run_dir, stored_record):
                raise FutureSongError("immutable run artifacts failed verification")
        run_sha256 = sha256_file(run_path)
        atomic_write_json(
            validate_generated_path(
                report_root,
                report_control_path(report_root, current_pointer),
                workspace_root=self.workspace_root,
            ),
            {"runId": run_id, "runSha256": run_sha256},
            root=report_root,
            workspace_root=self.workspace_root,
        )
        return run_sha256


def alignment_checkpoint_key(
    job: dict[str, Any],
    *,
    editorial_sha256: str,
    state: dict[str, Any],
    workspace_root: Path, alignment_job: Path, runtime: Path, config_path: Path, project_dir: Path,
) -> dict[str, Any]:
    from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION

    if state.get("alignmentVersion") != ALIGNMENT_VERSION:
        raise FutureSongError("alignment state uses an unsupported version")
    video_id = validate_video_id(job["videoId"])
    if state.get('endpointReview') is not None:
        from stemwise_engine.workflow_records import EndpointReviewResult
        EndpointReviewResult.from_record(state['endpointReview'])
    from stemwise_engine.alignment_phases import AttemptCacheBindings
    cache_bindings = list(AttemptCacheBindings.records(state))
    admission = state.get('contentAdmission')
    owner_admission = None
    if isinstance(admission, dict):
        from stemwise_engine.lyric_content import ContentAdmission
        admitted = ContentAdmission.from_record(admission).to_record()
        evidence = admitted['evidence']
        owner_admission = admitted if 'ownerVerification' in admitted else None
        if owner_admission is not None:
            raw = read_bounded_regular_bytes(alignment_job/evidence['path'], max_bytes=MAX_JSON_BYTES,
                root=alignment_job)
            if hashlib.sha256(raw).hexdigest() != evidence['sha256']:
                raise FutureSongError('owner content evidence changed')
        elif not any(row['cacheId'] == evidence['path'] for row in cache_bindings):
            family = 'mms-asr' if evidence['path'].startswith('native-asr-') else 'whisper-asr'
            cache_bindings.append({'cacheId': evidence['path'], 'sha256': evidence['sha256'],
                                   'familyId': family, 'use': 'consumed'})
    used_cache_paths: dict[str, list[Path]] = {}
    for binding in cache_bindings:
        path = validate_generated_path(alignment_job, alignment_job / binding['cacheId'],
                                       workspace_root=workspace_root)
        try:
            raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=alignment_job)
            if not isinstance(parse_strict_json_bytes(raw), dict):
                raise ValueError('cache is not an object')
        except (OSError, ValueError) as error:
            raise FutureSongError('attempt cache is missing or malformed') from error
        if hashlib.sha256(raw).hexdigest() != binding['sha256']:
            raise FutureSongError('attempt cache changed after it was consumed or produced')
        used_cache_paths.setdefault(binding['familyId'], []).append(path)
    audio: dict[str, Any] = {}
    audio_path = alignment_job / "audio-provenance.private.json"
    if audio_path.is_file():
        from stemwise_engine.alignment_audio import read_audio_provenance
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        provenance = read_audio_provenance(
            AlignmentWorkspace(alignment_job.parents[2], workspace_root=workspace_root),
            video_id, state.get('durationMs'))
        if provenance is None:
            raise FutureSongError('alignment audio provenance is malformed or stale')
        audio['mix'] = workflow_diagnostics._safe_report_value(provenance['mix'])
        if provenance['audioViews']:
            audio['audioViews'] = {view_id: workflow_diagnostics._safe_report_value(view)
                for view_id, view in provenance['audioViews'].items()}

    analysis_checkpoint_path = validate_generated_path(
        runtime,
        runtime / "work" / "checkpoints" / f"{video_id}.json",
        workspace_root=workspace_root,
    )
    analysis_checkpoint = (
        read_bounded_json(
            analysis_checkpoint_path,
            max_bytes=MAX_JSON_BYTES,
            root=runtime,
        )
        if analysis_checkpoint_path.is_file()
        else {}
    )
    if isinstance(analysis_checkpoint, dict) and isinstance(
        analysis_checkpoint.get("originalAudio"), dict
    ):
        original_audio = analysis_checkpoint["originalAudio"]
        identity = workflow_diagnostics._safe_report_value({
            key: original_audio[key]
            for key in ("sha256", "byteCount", "durationMs", "retained")
            if key in original_audio
        })
        if isinstance(identity, dict) and identity:
            audio["analysisOriginal"] = identity
    asr_models: list[dict[str, Any]] = []
    for path in sorted(used_cache_paths.get('whisper-asr', [])):
        value = read_optional_alignment_cache(path, root=alignment_job)
        if value is None:
            continue
        provenance = value.get("provenance")
        if not isinstance(provenance, dict):
            provenance = {}
        record = workflow_diagnostics._safe_report_value(
            {
                "cacheSha256": sha256_file(path),
                "cacheId": path.name,
                "modelId": workflow_diagnostics._stable_private_id(value.get("model")),
                "promptSha256": value.get("promptSha256"),
                "fasterWhisperVersion": provenance.get("fasterWhisperVersion"),
                "modelSnapshot": workflow_diagnostics._stable_private_id(
                    provenance.get("modelSnapshot")
                ),
                "device": provenance.get("device"),
                "computeType": provenance.get("computeType"),
                "decoder": provenance.get("decoder"),
            }
        )
        if isinstance(record, dict) and record:
            asr_models.append(record)
    qwen_structure: dict[str, Any] | None = None
    witness = state.get("structureWitness") or {}
    witness_diagnostics = workflow_diagnostics._structure_witness_diagnostics(witness) if witness else {}
    hint = witness_diagnostics.get("languageHint")
    view = witness_diagnostics.get("route", "qwen3-asr-unprompted-mix").removeprefix("qwen3-asr-unprompted-")
    cache_name = (f"qwen-structure-{hint}.private.json" if hint else "qwen-structure.private.json")
    if view != "mix":
        cache_name = f"qwen-structure-{view}-{hint or 'auto'}.private.json"
    qwen_path = alignment_job / cache_name
    if witness.get("selectedSequenceSupported") is True and not qwen_path.is_file():
        raise FutureSongError("selected Qwen structure cache is missing")
    if witness.get("selectedSequenceSupported") is True or qwen_path in used_cache_paths.get('qwen3-asr', []):
        qwen_cache = read_optional_alignment_cache(
            qwen_path, root=alignment_job
        )
        provenance = (
            qwen_cache.get("provenance")
            if isinstance(qwen_cache, dict)
            else None
        )
        if (
            not isinstance(qwen_cache, dict)
            or set(qwen_cache)
            != {"cacheVersion", "videoId", "provenance", "workerOutput"}
            or qwen_cache.get("cacheVersion") != 1
            or qwen_cache.get("videoId") != video_id
            or not isinstance(provenance, dict)
        ):
            raise FutureSongError("Qwen structure cache is malformed")
        if "languageHint" in witness and (
            provenance.get("language") != hint
            or not isinstance(qwen_cache.get("workerOutput"), dict)
            or qwen_cache["workerOutput"].get("language") != hint
        ):
            raise FutureSongError("Qwen structure cache language differs from the selected witness")
        if view != "mix" and provenance.get("audioView") != view:
            raise FutureSongError("Qwen structure cache audio view differs from the selected witness")
        qwen_structure = {
            "familyId": "qwen3-asr",
            "cacheId": qwen_path.name,
            "cacheSha256": sha256_file(qwen_path),
        }
        for key in (
            "modelRevision",
            "modelSnapshotSha256",
            "helperSha256",
        ):
            value = provenance.get(key)
            if isinstance(value, str) and re.fullmatch(
                r"[0-9a-f]{40}" if key == "modelRevision" else r"[0-9a-f]{64}",
                value,
            ):
                qwen_structure[key] = value
            else:
                raise FutureSongError("Qwen structure cache identity is invalid")
    forced_models: list[dict[str, Any]] = []
    forced_cache_paths = sorted(used_cache_paths.get('mms-fa', []) + used_cache_paths.get('wav2vec2', []))
    for path in forced_cache_paths:
        value = read_optional_alignment_cache(path, root=alignment_job)
        if value is None:
            continue
        provenance = value.get("provenance")
        if not isinstance(provenance, dict):
            provenance = {}
        record = workflow_diagnostics._safe_report_value(
            {
                "cacheSha256": sha256_file(path),
                "cacheId": path.name,
                "variant": value.get("variant"),
                "requestVariant": value.get("requestVariant"),
                "engine": value.get("engine"),
                "route": value.get("route"),
                "language": value.get("language"),
                "acousticTrackKind": value.get("acousticTrackKind"),
                "requestSha256": value.get("requestSha256"),
                "helperSha256": value.get("helperSha256"),
                "requestHelperSha256": value.get("requestHelperSha256"),
                "sampleRate": value.get("sampleRate"),
                "durationMs": value.get("durationMs"),
                "emissionFrameCount": value.get("emissionFrameCount"),
                "segmentCount": value.get("segmentCount"),
                "segmentBoundaryGuardMs": value.get("segmentBoundaryGuardMs"),
                "wordCount": value.get("wordCount"),
                "lineCount": value.get("lineCount"),
                "confidence": value.get("confidence"),
                "canonicalTimeTransform": value.get("canonicalTimeTransform"),
                "demucsModelSha256": provenance.get("demucsModelSha256"),
                "mmsModelSha256": provenance.get("mmsModelSha256"),
                "audioViewId": provenance.get("audioViewId"),
                "model": provenance.get("model"),
                "runtime": provenance.get("runtime"),
                "decoder": provenance.get("decoder"),
            }
        )
        if isinstance(record, dict) and record:
            forced_models.append(record)
    from stemwise_engine.provenance import semantic_source_identity
    helpers = {}
    for name in (
        "common.py",
        "contract.py",
        "lyrics_align_pc.py",
        "lyrics_alignment.py",
        "lyrics_tokenization.py",
        "lyrics_unicode.py",
        "alignment_attempt.py",
        "mms_forced_alignment.py",
        "owsm_ctc_forced_alignment.py",
        "qwen3_asr_transcribe.py",
        "wav2vec2_forced_alignment.py",
        "roformer_separation.py",
        "song_enrichment_v2.py",
    ):
        path = validate_generated_path(
            workspace_root,
            project_dir / "src" / name,
            workspace_root=workspace_root,
        )
        helpers[name] = semantic_source_identity(path)
    authority = {"editorialSha256": editorial_sha256}
    if owner_admission is not None:
        authority['ownerVerification'] = owner_admission['ownerVerification']
        authority['ownerEvidence'] = owner_admission['evidence']
    canonical_plain_sha = state.get("canonicalPlainSha256")
    if isinstance(canonical_plain_sha, str) and re.fullmatch(
        r"[0-9a-f]{64}", canonical_plain_sha
    ):
        authority["canonicalPlainSha256"] = canonical_plain_sha
    models: dict[str, Any] = {}
    if cache_bindings:
        models['attemptCacheBindings'] = cache_bindings
    analysis_provenance_sha = (
        analysis_checkpoint.get("provenanceSha256")
        if isinstance(analysis_checkpoint, dict)
        else None
    )
    if isinstance(analysis_provenance_sha, str) and re.fullmatch(
        r"[0-9a-f]{64}", analysis_provenance_sha
    ):
        models["analysisProvenanceSha256"] = analysis_provenance_sha
    if asr_models:
        models["asr"] = {"familyId": "whisper-asr", "routes": asr_models}
    if qwen_structure:
        models["structureAsr"] = qwen_structure
    native_asr = []
    for path in sorted(used_cache_paths.get('mms-asr', [])):
        import stemwise_engine.mms_asr as mms_asr
        value = read_optional_alignment_cache(path, root=alignment_job)
        provenance = value.get("provenance") if isinstance(value, dict) else None
        if (not isinstance(provenance, dict)
                or provenance.get("familyId") != "mms-asr"
                or provenance.get("modelRevision") != mms_asr.REVISION
                or provenance.get("adapter") not in mms_asr.ASSETS_BY_ADAPTER
                or provenance.get("assets") != mms_asr.ASSETS_BY_ADAPTER[provenance["adapter"]]
                or provenance.get("promptMode") != "withheld"
                or re.fullmatch(r"[0-9a-f]{64}", str(provenance.get("audioSha256"))) is None):
            raise FutureSongError("native ASR cache identity is invalid")
        native_asr.append({
            "cacheId": path.name, "cacheSha256": sha256_file(path),
            **{key: provenance[key] for key in
               ("modelRevision", "adapter", "assets", "audioSha256", "promptMode")},
        })
    if native_asr:
        models["nativeAsr"] = {"familyId": "mms-asr", "routes": native_asr}
        helpers["mms_asr.py"] = semantic_source_identity(project_dir / "src/mms_asr.py")
    if forced_models:
        models["forcedAlignment"] = forced_models
    policy_hashes = {
        key: value
        for key, value in (job.get("policy") or {}).items()
        if isinstance(key, str)
        and key.endswith("Sha256")
        and isinstance(value, str)
        and re.fullmatch(r"[0-9a-f]{64}", value)
    } if isinstance(job.get("policy"), dict) else {}
    policies: dict[str, Any] = {
        "policyVersion": job.get("policyVersion"),
        "configurationSha256": sha256_file(config_path),
    }
    if policy_hashes:
        policies["hashes"] = policy_hashes
    parameters: dict[str, Any] = {}
    for key, value in (
        ("alignmentVersion", state.get("alignmentVersion")),
        ("modelId", workflow_diagnostics._stable_private_id(state.get("model"))),
        ("durationMs", state.get("durationMs")),
    ):
        safe = workflow_diagnostics._safe_report_value(value)
        if safe is not None:
            parameters[key] = safe
    pinned_runtime = workflow_diagnostics._public_runtime_identity(state.get("pinnedRuntime"))
    if pinned_runtime:
        parameters["pinnedRuntime"] = pinned_runtime
    checkpoint: dict[str, Any] = {
        "authority": authority,
        "helpers": helpers,
        "policies": policies,
    }
    for key, value in (
        ("audio", audio),
        ("models", models),
        ("parameters", parameters),
    ):
        if value:
            checkpoint[key] = value
    return checkpoint


def read_optional_alignment_cache(
    path: Path, *, root: Path
) -> dict[str, Any] | None:
    try:
        metadata = path.lstat()
    except OSError as error:
        raise FutureSongError("alignment cache identity cannot be verified") from error
    if path.is_symlink() or getattr(metadata, "st_file_attributes", 0) & getattr(
        stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0
    ):
        raise FutureSongError("alignment cache must not be a link")
    try:
        value = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=root)
    except (UnicodeError, ValueError, json.JSONDecodeError):
        return None
    except OSError as error:
        raise FutureSongError("alignment cache cannot be read safely") from error
    return value if isinstance(value, dict) else None


def workflow_operation_record(*, run_id: str, video_id: str, operation_id: str, action: str,
        phase: str, status: str, started_at: int, state_before: str, bindings: dict[str, str],
        completed_at: int | None = None, state_after: str | None = None,
        outcome_code: str | None = None, failure_details: dict[str, Any] | None = None,
        private_diagnostics: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    start = OperationStart(run_id, video_id, operation_id, action, started_at, state_before,
        ReportBindings(bindings['configSha256'], bindings['policySha256'], bindings['engineSourceSha256']))
    record = (start.to_record() if phase == 'start' else
        start.finish(run_id, completed_at, state_after, outcome_code).to_record())
    if failure_details is not None:
        from stemwise_engine.workflow_records import failure_details_are_safe
        if phase != 'outcome' or status != 'failed' or not failure_details_are_safe(failure_details):
            raise FutureSongError('workflow failure details are invalid')
        record['failureDetails'] = failure_details
    if private_diagnostics is not None:
        from stemwise_engine.private_diagnostics import validate_references
        if phase != 'outcome' or status != 'failed':
            raise FutureSongError('Private provider diagnostics require a failed outcome')
        record['privateDiagnostics'] = validate_references(private_diagnostics)
    if record['status'] != status:
        raise FutureSongError('workflow operation status differs from its typed outcome')
    return record


def alignment_run_record(job: dict[str, Any], *, run_id: str, video_id: str,
        state: dict[str, Any], checkpoint_key: dict[str, Any], checkpoint_raw: bytes,
        completed_at: int, status: str, run_artifacts: dict[str, Any], failure_code: str | None,
        producing_inputs: dict[str, Any] | None, observed_input_changes: dict[str, Any]) -> tuple[dict, dict]:
    diagnostics = workflow_diagnostics._alignment_diagnostics(state)
    record: dict[str, Any] = {
        "reportVersion": PRIVATE_REPORT_VERSION,
        "runId": run_id,
        "videoId": video_id,
        "operation": "lyrics-alignment",
        "status": status,
        "completedAtUnix": completed_at,
        "checkpointKey": checkpoint_key,
        "checkpointKeySha256": hashlib.sha256(checkpoint_raw).hexdigest(),
        "summary": {
            key: diagnostics[key]
            for key in ("resultClass", "timedLineCount", "reasons")
            if key in diagnostics
        },
    }
    runtime = {"completedAtUnix": completed_at}
    started_at = state.get("workerStartedAtUnix")
    if type(started_at) is int and 0 <= started_at <= completed_at:
        runtime.update(
            startedAtUnix=started_at,
            elapsedSeconds=completed_at - started_at,
        )
    if type(state.get("workerPid")) is int and state["workerPid"] > 0:
        runtime["workerPid"] = state["workerPid"]
    if type(state.get("updatedAtUnix")) is int and state["updatedAtUnix"] >= 0:
        runtime["lastStateUpdateAtUnix"] = state["updatedAtUnix"]
    record["runtime"] = runtime
    if run_artifacts:
        record["artifacts"] = run_artifacts
    if status == "complete":
        schema_sha = (job.get("policy") or {}).get("schemaSha256")
        if not isinstance(schema_sha, str) or re.fullmatch(
            r"[0-9a-f]{64}", schema_sha
        ) is None:
            raise FutureSongError("successful run has no bound external schema identity")
        record["schemaValidation"] = {
            "result": "pass",
            "externalFormatVersion": 2,
            "schemaSha256": schema_sha,
        }
    if failure_code:
        codes = workflow_diagnostics._diagnostic_codes([failure_code])
        record["failureCode"] = codes[0] if codes else "TechnicalFailure"
    if status == 'failed' and producing_inputs is not None:
        record['producingInputs'] = producing_inputs
        if observed_input_changes:
            record['observedInputChanges'] = observed_input_changes
    evidence = {
        "evidenceVersion": 1,
        "runId": run_id,
        "videoId": video_id,
        "operation": "lyrics-alignment",
        "diagnostics": diagnostics,
    }
    return record, evidence


def authority_outcome_record(job: dict[str, Any], *, video_id: str, run_id: str,
        review_sha256: str, sources_sha256: str, completed_at: int,
        unresolved_issue_count: int) -> dict[str, Any]:
    checkpoint_key = {
        "authorityOutcome": "authority-unresolved",
        "reviewSha256": review_sha256,
        "sourcesSha256": sources_sha256,
        "researchComplete": {
            "automaticSourceChecksComplete": True,
            "recordingOrSourceAssociationReviewed": True,
            "youtubeSurfaceVariantsReviewed": True,
            "publicSourceResearchComplete": True,
        },
        "policyHashes": {
            key: value
            for key, value in (job.get("policy") or {}).items()
            if isinstance(key, str)
            and key.endswith("Sha256")
            and isinstance(value, str)
            and re.fullmatch(r"[0-9a-f]{64}", value)
        },
    }
    checkpoint_raw = json.dumps(
        checkpoint_key,
        ensure_ascii=True,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    record = {
        "reportVersion": PRIVATE_REPORT_VERSION,
        "runId": run_id,
        "videoId": video_id,
        "operation": "authority-outcome",
        "status": "complete",
        "completedAtUnix": completed_at,
        "checkpointKey": checkpoint_key,
        "checkpointKeySha256": hashlib.sha256(checkpoint_raw).hexdigest(),
        "artifacts": {
            "reviewSha256": review_sha256,
            "sourcesSha256": sources_sha256,
        },
        "summary": {
            "authorityOutcome": "authority-unresolved",
            "unresolvedIssueCount": unresolved_issue_count,
        },
    }
    return record


def compact_alignment_run(record: dict[str, Any], evidence: dict[str, Any], *,
        state: dict[str, Any], checkpoint_raw: bytes) -> tuple[dict, dict]:
    checkpoint_key = record['checkpointKey']
    report_authority = checkpoint_key['reportAuthority']
    sources_sha256 = record.get('artifacts', {}).get('sourcesSha256')
    run_id, video_id, completed_at = record['runId'], record['videoId'], record['completedAtUnix']
    runtime = record['runtime']
    checkpoint_sha256 = hashlib.sha256(checkpoint_raw).hexdigest()
    compact_checkpoint = {
        "authority": checkpoint_key["authority"],
        "reportAuthority": report_authority,
        "omittedCheckpointKeySha256": checkpoint_sha256,
        "omittedCheckpointKeyByteCount": len(checkpoint_raw),
    }
    compact_checkpoint_raw = json.dumps(
        compact_checkpoint,
        ensure_ascii=True,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    compact_record: dict[str, Any] = {
        "reportVersion": PRIVATE_REPORT_VERSION,
        "runId": run_id,
        "videoId": video_id,
        "operation": "lyrics-alignment",
        "status": "failed",
        "completedAtUnix": completed_at,
        "checkpointKey": compact_checkpoint,
        "checkpointKeySha256": hashlib.sha256(
            compact_checkpoint_raw
        ).hexdigest(),
        "summary": {
            "resultClass": "retryable-technical",
            "reasons": ["report-size-limit-exceeded"],
        },
        "runtime": runtime,
        "failureCode": "ReportSizeLimitExceeded",
    }
    if isinstance(sources_sha256, str):
        compact_record["artifacts"] = {"sourcesSha256": sources_sha256}
    evidence_payload = json_bytes(evidence, pretty=True)
    compact_diagnostics: dict[str, Any] = {
        "resultClass": "retryable-technical",
        "reasons": ["report-size-limit-exceeded"],
        "omittedEvidenceSha256": hashlib.sha256(evidence_payload).hexdigest(),
        "omittedEvidenceByteCount": len(evidence_payload),
    }
    if type(state.get("alignmentVersion")) is int:
        compact_diagnostics["alignmentVersion"] = state["alignmentVersion"]
    compact_evidence = {
        "evidenceVersion": 1,
        "runId": run_id,
        "videoId": video_id,
        "operation": "lyrics-alignment",
        "diagnostics": compact_diagnostics,
    }
    return compact_record, compact_evidence


class WorkflowReports:
    def __init__(self, *, workspace_root, root, owner, reports, runtime, jobs,
                 config_path, policy_dir, config, policy, native):
        self.workspace_root, self.root, self.owner = workspace_root, root, owner
        self.reports, self.runtime, self.jobs = reports, runtime, jobs
        self.config_path, self.policy_dir = config_path, policy_dir
        self.config, self.policy, self.native = dict(config), dict(policy), native
        self.store = ImmutableReportStore(workspace_root, MAX_JSON_BYTES)

    def _job_dir(self, video_id):
        return validate_generated_path(self.jobs, self.jobs/validate_video_id(video_id),
            workspace_root=self.workspace_root)

    def _job_path(self, video_id):
        return self._job_dir(video_id)/'job.json'

    def _alignment_state_path(self, video_id):
        directory = self.root/'lyrics-alignment/jobs'
        return validate_generated_path(directory, directory/validate_video_id(video_id)/'state.json',
            workspace_root=self.workspace_root)

    _validate_alignment_attempt_id = staticmethod(ImmutableReportStore._validate_alignment_attempt_id)

    def report_root(self, video_id: str) -> Path:
        from stemwise_engine.workflow_context import telemetry_job_root
        video_id = validate_video_id(video_id)
        reports = validate_generated_path(
            self.workspace_root,
            self.reports,
            workspace_root=self.workspace_root,
        )
        target = (reports / video_id if self.native else
                  reports / telemetry_job_root(self.workspace_root, f"youtube:{video_id}").name / "reports")
        report_root = validate_generated_path(
            reports,
            target,
            workspace_root=self.workspace_root,
        )
        owner_root = validate_generated_path(
            self.root,
            self.owner,
            workspace_root=self.workspace_root,
        )
        if report_root.is_relative_to(owner_root) or owner_root.is_relative_to(report_root):
            raise FutureSongError("private reports must be disjoint from owner authority inputs")
        if report_root.exists() or report_root.is_symlink():
            manifest = read_bounded_json(
                report_root / GENERATED_MANIFEST_NAME,
                max_bytes=16 * 1024,
                root=report_root,
            )
            if manifest != {
                "manifestVersion": 1,
                "owner": {"kind": "private-song-report", "videoId": video_id},
                "generatedPaths": ["."],
            }:
                raise FutureSongError("private report generated-file manifest is invalid")
        return report_root


    def ensure_report_identity(self, video_id: str) -> Path:
        video_id = validate_video_id(video_id)
        report_root = self.report_root(video_id)
        if not report_root.exists():
            report_root.parent.mkdir(parents=True, exist_ok=True)
            create_manifested_directory(
                report_root.parent,
                report_root,
                owner={"kind": "private-song-report", "videoId": video_id},
                workspace_root=self.workspace_root,
            )
        report_root = self.report_root(video_id)
        identity = {
            "reportVersion": PRIVATE_REPORT_VERSION,
            "videoId": video_id,
            "identityType": "exact-youtube-video-id",
        }
        identity_path = validate_generated_path(
            report_root,
            report_root / "identity.json",
            workspace_root=self.workspace_root,
        )
        if identity_path.is_file():
            if read_json(identity_path) != identity:
                raise FutureSongError("private report identity is immutable")
        else:
            atomic_write_json(identity_path, identity)
        return report_root


    @staticmethod
    def report_authority_values(
        video_id: str, editorial_sha256: str, sources_sha256: str
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        video_id = validate_video_id(video_id)
        for name, value in (
            ("editorial", editorial_sha256),
            ("sources", sources_sha256),
        ):
            if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
                raise FutureSongError(f"private report {name} hash is invalid")
        authority_id = hashlib.sha256(
            f"stemwise-authority\0{video_id}".encode("ascii")
        ).hexdigest()
        source_id = hashlib.sha256(
            f"stemwise-source-evidence\0{video_id}".encode("ascii")
        ).hexdigest()
        return (
            {
                "referenceVersion": 1,
                "videoId": video_id,
                "authorityId": f"sha256:{authority_id}",
                "editorialSha256": editorial_sha256,
            },
            {
                "sourceEvidenceVersion": 1,
                "videoId": video_id,
                "sourceEvidenceId": f"sha256:{source_id}",
                "sourcesSha256": sources_sha256,
            },
        )


    def write_report_authority_references(
        self, video_id: str, editorial_sha256: str, sources_sha256: str
    ) -> dict[str, str]:
        report_root = self.ensure_report_identity(video_id)
        authority_dir = validate_generated_path(
            report_root,
            report_root / "authority",
            workspace_root=self.workspace_root,
        )
        authority_dir.mkdir(parents=True, exist_ok=True)
        authority_reference, source_evidence = self.report_authority_values(
            video_id, editorial_sha256, sources_sha256
        )
        authority_raw = json_bytes(authority_reference, pretty=True)
        source_raw = json_bytes(source_evidence, pretty=True)
        authority_sha256 = hashlib.sha256(authority_raw).hexdigest()
        source_sha256 = hashlib.sha256(source_raw).hexdigest()
        authority_path = authority_dir / f"authority-reference.{authority_sha256}.json"
        source_path = authority_dir / f"source-evidence.{source_sha256}.json"
        for path, raw in ((authority_path, authority_raw), (source_path, source_raw)):
            if path.exists():
                if read_bounded_regular_bytes(
                    path, max_bytes=16 * 1024, root=report_root
                ) != raw:
                    raise FutureSongError("private report authority history is immutable")
            else:
                atomic_write_bytes(
                    path,
                    raw,
                    root=report_root,
                    workspace_root=self.workspace_root,
                )
        return {
            "authorityReferenceSha256": authority_sha256,
            "sourceEvidenceReferenceSha256": source_sha256,
        }


    def report_authority_matches(
        self, video_id: str, editorial_sha256: str, sources_sha256: str
    ) -> bool:
        report_root = self.report_root(video_id)
        authority_dir = report_root / "authority"
        expected_authority, expected_sources = self.report_authority_values(
            video_id, editorial_sha256, sources_sha256
        )
        authority_raw = json_bytes(expected_authority, pretty=True)
        source_raw = json_bytes(expected_sources, pretty=True)
        authority_path = authority_dir / (
            "authority-reference." + hashlib.sha256(authority_raw).hexdigest() + ".json"
        )
        source_path = authority_dir / (
            "source-evidence." + hashlib.sha256(source_raw).hexdigest() + ".json"
        )
        try:
            return read_bounded_json(
                authority_path,
                max_bytes=16 * 1024,
                root=report_root,
            ) == expected_authority and read_bounded_json(
                source_path,
                max_bytes=16 * 1024,
                root=report_root,
            ) == expected_sources
        except (OSError, ValueError, UnicodeError, json.JSONDecodeError):
            return False


    def alignment_checkpoint_key(self, job, *, editorial_sha256, state):
        from stemwise_engine.workflow_reports import alignment_checkpoint_key
        return alignment_checkpoint_key(job, editorial_sha256=editorial_sha256, state=state,
            workspace_root=self.workspace_root, alignment_job=self._alignment_state_path(job['videoId']).parent,
            runtime=self.runtime, config_path=self.config_path,
            project_dir=Path(self.config.get('projectDir', self.config_path.parent)))


    def workflow_operation_state(self, video_id: str) -> str:
        """Return only a fixed, text-free state token for operation reports."""

        job_path = self._job_path(video_id)
        if not job_path.is_file():
            return "ABSENT"
        try:
            job = read_bounded_json(
                job_path,
                max_bytes=MAX_JSON_BYTES,
                root=self._job_dir(video_id),
            )
        except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
            return "UNKNOWN"
        state = job.get("state") if isinstance(job, dict) else None
        return state if state in WORKFLOW_OPERATION_STATES else "UNKNOWN"


    def workflow_operation_bindings(self) -> dict[str, str]:
        """Bind the exact active config and policy files without copying paths."""

        config_path = getattr(self, "config_path", None)
        policy_dir = getattr(self, "policy_dir", None)
        if config_path is None and policy_dir is None:
            config = getattr(self, "config", None)
            policy = getattr(self, "policy", None)
            if not isinstance(config, dict) or not isinstance(policy, dict):
                raise FutureSongError("workflow operation report bindings are missing")
            return {
                "configSha256": hashlib.sha256(json_bytes(config)).hexdigest(),
                "policySha256": hashlib.sha256(json_bytes(policy)).hexdigest(),
                "engineSourceSha256": engine_source_sha256(Path(__file__).parent),
            }
        policy_path = policy_dir / "lyrics-policy.json" if isinstance(policy_dir, Path) else None
        if (
            not isinstance(config_path, Path)
            or not isinstance(policy_path, Path)
        ):
            raise FutureSongError("workflow operation report bindings are missing")
        result = {}
        for key, path in (("configSha256", config_path), ("policySha256", policy_path)):
            try:
                checked = validate_generated_path(
                    self.workspace_root,
                    path,
                    workspace_root=self.workspace_root,
                )
                metadata = checked.lstat()
                if (
                    checked.is_symlink()
                    or getattr(metadata, "st_file_attributes", 0)
                    & getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
                    or not checked.is_file()
                ):
                    raise FutureSongError("workflow operation report binding is not a regular file")
                result[key] = sha256_file(checked)
            except (OSError, ValueError) as error:
                raise FutureSongError("workflow operation report binding cannot be verified") from error
        result["engineSourceSha256"] = engine_source_sha256(Path(__file__).parent)
        return result


    def latest_workflow_operation(self, video_id: str) -> dict[str, Any] | None:
        report_root = self.report_root(video_id)
        pointer_path = report_control_path(report_root, "current-operation.json")
        try:
            pointer_path.lstat()
        except FileNotFoundError:
            return None
        pointer = read_bounded_json(pointer_path, max_bytes=16 * 1024, root=report_root)
        if (not isinstance(pointer, dict) or set(pointer) != {"runId", "runSha256"}
                or not isinstance(pointer["runId"], str) or not isinstance(pointer["runSha256"], str)
                or re.fullmatch(r"[0-9a-f]{32}", pointer["runId"]) is None
                or re.fullmatch(r"[0-9a-f]{64}", pointer["runSha256"]) is None):
            raise FutureSongError("workflow operation pointer is invalid")
        run_dir = validate_generated_path(report_root, report_root / "runs" / pointer["runId"],
                                          workspace_root=self.workspace_root)
        payload = read_bounded_regular_bytes(run_dir / "run.json", max_bytes=MAX_JSON_BYTES, root=run_dir)
        record = parse_strict_json_bytes(payload)
        if (hashlib.sha256(payload).hexdigest() != pointer["runSha256"]
                or not isinstance(record, dict) or not operation_record_is_safe(record)
                or record["runId"] != pointer["runId"] or record["videoId"] != video_id
                or not self.store.artifacts_match(run_dir, record)):
            raise FutureSongError("workflow operation record is invalid")
        return {**record, "runSha256": pointer["runSha256"]}


    def commit_workflow_operation_record(
        self,
        video_id: str,
        *,
        operation_id: str,
        action: str,
        phase: str,
        status: str,
        started_at: int,
        state_before: str,
        bindings: dict[str, str],
        completed_at: int | None = None,
        state_after: str | None = None,
        outcome_code: str | None = None,
        failure_details: dict[str, Any] | None = None,
        private_diagnostics: list[dict[str, Any]] | None = None,
    ) -> tuple[str, str]:
        video_id = validate_video_id(video_id)
        operation_id = self._validate_alignment_attempt_id(operation_id)
        if action not in WORKFLOW_OPERATION_ACTIONS:
            raise FutureSongError("workflow operation action is invalid")
        if phase not in {"start", "outcome"}:
            raise FutureSongError("workflow operation phase is invalid")
        run_id = uuid.uuid4().hex
        from stemwise_engine.workflow_reports import workflow_operation_record
        record = workflow_operation_record(run_id=run_id, video_id=video_id, operation_id=operation_id,
            action=action, phase=phase, status=status, started_at=started_at, state_before=state_before,
            bindings=bindings, completed_at=completed_at, state_after=state_after, outcome_code=outcome_code,
            failure_details=failure_details, private_diagnostics=private_diagnostics)
        report_root = self.ensure_report_identity(video_id)
        run_sha256 = self.store.commit(
            report_root,
            record,
            current_pointer="current-operation.json",
        )
        return run_id, run_sha256


    def commit_authority_unresolved_run(
        self,
        job: dict[str, Any], *, review: dict[str, Any],
    ) -> tuple[str, str, Path]:
        """Commit one text-free record after automatic authority research ends."""

        video_id = validate_video_id(job["videoId"])
        job_dir = self._job_dir(video_id)
        review_path = job_dir / "agent-review.json"
        sources_path = job_dir / "sources.json"
        review_sha256 = sha256_file(review_path)
        sources_sha256 = sha256_file(sources_path)
        identity = (
            f"{video_id}\0authority-unresolved\0{review_sha256}\0{sources_sha256}"
        ).encode("ascii")
        run_id = hashlib.sha256(identity).hexdigest()[:32]
        report_root = self.ensure_report_identity(video_id)
        run_path = report_root / "runs" / run_id / "run.json"
        completed_at = int(time.time())
        if run_path.is_file():
            existing = read_bounded_json(
                run_path, max_bytes=MAX_JSON_BYTES, root=report_root
            )
            if isinstance(existing, dict) and type(existing.get("completedAtUnix")) is int:
                completed_at = existing["completedAtUnix"]
        from stemwise_engine.workflow_reports import authority_outcome_record
        record = authority_outcome_record(job, video_id=video_id, run_id=run_id,
            review_sha256=review_sha256, sources_sha256=sources_sha256, completed_at=completed_at,
            unresolved_issue_count=len(review['unresolvedMaterialIssues']))
        run_sha256 = self.store.commit(report_root, record)
        projection = {
            "reportVersion": PRIVATE_REPORT_VERSION,
            "videoId": video_id,
            "authorityOutcome": "authority-unresolved",
            "unresolvedIssueCount": len(review["unresolvedMaterialIssues"]),
            "reviewSha256": review_sha256,
            "sourcesSha256": sources_sha256,
            "runId": run_id,
            "runSha256": run_sha256,
        }
        projection_path = validate_generated_path(
            report_root,
            report_root / "authority" / "outcome.json",
            workspace_root=self.workspace_root,
        )
        projection_path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_json(projection_path, projection)
        return run_id, run_sha256, projection_path


    def commit_display_review_run(
        self,
        job: dict[str, Any],
        *,
        declaration: dict[str, Any],
        declaration_sha256: str,
        final_display_sha256: str,
        artifacts: dict[str, Any],
    ) -> tuple[str, int]:
        video_id = validate_video_id(job["videoId"])
        run_id = self._validate_alignment_attempt_id(declaration["attemptId"])
        schema_sha256 = (job.get("policy") or {}).get("schemaSha256")
        if not isinstance(schema_sha256, str) or re.fullmatch(
            r"[0-9a-f]{64}", schema_sha256
        ) is None:
            raise FutureSongError("display review has no bound external schema identity")
        checkpoint_key = {
            "declarationSha256": declaration_sha256,
            "declaration": declaration,
            "finalDisplaySha256": final_display_sha256,
            "configurationSha256": sha256_file(self.config_path),
            "policyHashes": {
                key: value
                for key, value in (job.get("policy") or {}).items()
                if isinstance(key, str)
                and key.endswith("Sha256")
                and isinstance(value, str)
                and re.fullmatch(r"[0-9a-f]{64}", value)
            },
        }
        checkpoint_raw = json.dumps(
            checkpoint_key,
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        report_root = self.ensure_report_identity(video_id)
        run_path = report_root / "runs" / run_id / "run.json"
        completed_at = int(time.time())
        if run_path.is_file():
            existing = read_bounded_json(
                run_path, max_bytes=MAX_JSON_BYTES, root=report_root
            )
            if isinstance(existing, dict) and type(existing.get("completedAtUnix")) is int:
                completed_at = existing["completedAtUnix"]
        record = {
            "reportVersion": PRIVATE_REPORT_VERSION,
            "runId": run_id,
            "videoId": video_id,
            "operation": "display-review",
            "status": "complete",
            "completedAtUnix": completed_at,
            "checkpointKey": checkpoint_key,
            "checkpointKeySha256": hashlib.sha256(checkpoint_raw).hexdigest(),
            "summary": {
                "result": "accepted-display-only",
                "presentationChanged": final_display_sha256
                != declaration["preReviewTextSha256"],
            },
            "schemaValidation": {
                "result": "pass",
                "externalFormatVersion": 2,
                "schemaSha256": schema_sha256,
            },
            "artifacts": artifacts,
        }
        return self.store.commit(report_root, record), completed_at


    def immutable_run_matches(
        self,
        video_id: str,
        *,
        run_id: Any,
        run_sha256: Any,
        operation: str,
        artifacts: dict[str, Any],
        schema_sha256: str,
        require_current_pointer: bool,
        checkpoint_requirements: dict[str, Any] | None = None,
    ) -> bool:
        """Verify one content-bound private run record without trusting job paths."""

        if (
            not isinstance(run_id, str)
            or re.fullmatch(r'[0-9a-f]{32}', run_id) is None
            or not isinstance(run_sha256, str)
            or re.fullmatch(r"[0-9a-f]{64}", run_sha256) is None
        ):
            return False
        report_root = self.report_root(video_id)
        run_path = validate_generated_path(
            report_root,
            report_root / "runs" / run_id / "run.json",
            workspace_root=self.workspace_root,
        )
        if not run_path.is_file() or sha256_file(run_path) != run_sha256:
            return False
        record = read_bounded_json(
            run_path,
            max_bytes=MAX_JSON_BYTES,
            root=report_root,
        )
        if (
            not isinstance(record, dict)
            or record.get("reportVersion") != PRIVATE_REPORT_VERSION
            or record.get("runId") != run_id
            or record.get("videoId") != video_id
            or record.get("operation") != operation
            or record.get("status") != "complete"
            or record.get("artifacts") != artifacts
        ):
            return False
        checkpoint_key = record.get("checkpointKey")
        if not isinstance(checkpoint_key, dict):
            return False
        if checkpoint_requirements is not None and any(
            checkpoint_key.get(key) != value
            for key, value in checkpoint_requirements.items()
        ):
            return False
        if operation == "package-finalization":
            helper_sha256 = checkpoint_key.get("helperSha256")
            if (
                checkpoint_requirements is None
                or set(checkpoint_key)
                != ({
                    "validationReportSha256",
                    "validationRunId",
                    "validationRunSha256",
                    "alignmentRunId",
                    "alignmentRunSha256",
                    "childSha256",
                    "policyHashes",
                    "configurationSha256",
                    "helperSha256",
                } - ({'alignmentRunId', 'alignmentRunSha256'}
                    if 'alignmentRunId' not in (checkpoint_requirements or {}) else set()))
                or not isinstance(helper_sha256, dict)
                or set(helper_sha256) != {"common.py", "future_song.py", "packages.py"}
                or any(
                    not isinstance(value, str)
                    or re.fullmatch(r"[0-9a-f]{64}", value) is None
                    for value in (
                        checkpoint_key.get("configurationSha256"),
                        *helper_sha256.values(),
                    )
                )
            ):
                return False
        checkpoint_raw = json.dumps(
            checkpoint_key,
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        if record.get("checkpointKeySha256") != hashlib.sha256(
            checkpoint_raw
        ).hexdigest() or not self.store.artifacts_match(run_path.parent, record):
            return False
        schema_validation = record.get("schemaValidation")
        if (
            not isinstance(schema_validation, dict)
            or schema_validation.get("result") != "pass"
            or schema_validation.get("externalFormatVersion") != 2
            or schema_validation.get("schemaSha256") != schema_sha256
            or re.fullmatch(r"[0-9a-f]{64}", schema_sha256) is None
        ):
            return False
        if require_current_pointer:
            current_run_path = validate_generated_path(
                report_root,
                report_control_path(report_root, "current-run.json"),
                workspace_root=self.workspace_root,
            )
            if not current_run_path.is_file():
                return False
            current_run = read_bounded_json(
                current_run_path,
                max_bytes=16 * 1024,
                root=report_root,
            )
            if current_run != {"runId": run_id, "runSha256": run_sha256}:
                return False
        return True

    def commit_alignment_run(
        self,
        job: dict[str, Any],
        *,
        run_id: str,
        editorial_sha256: str,
        state: dict[str, Any],
        status: str,
        artifacts: dict[str, Any] | None = None,
        failure_code: str | None = None,
        observed_input_changes: dict[str, Any] | None = None,
    ) -> str:
        run_id = self._validate_alignment_attempt_id(run_id)
        if status not in {"complete", "failed"}:
            raise FutureSongError("immutable alignment run status is invalid")
        video_id = validate_video_id(job["videoId"])
        report_root = self.ensure_report_identity(video_id)
        run_artifacts = dict(artifacts or {})
        sources_sha256 = run_artifacts.get("sourcesSha256")
        if not isinstance(sources_sha256, str):
            sources_path = self._job_dir(video_id) / "sources.json"
            if sources_path.is_file():
                sources_sha256 = sha256_file(sources_path)
        if isinstance(sources_sha256, str):
            run_artifacts["sourcesSha256"] = sources_sha256
        existing_run_path = report_root / "runs" / run_id / "run.json"
        if existing_run_path.is_file():
            existing_run = read_bounded_json(
                existing_run_path, max_bytes=MAX_JSON_BYTES, root=report_root
            )
            if (
                not isinstance(existing_run, dict)
                or existing_run.get("checkpointKey", {})
                .get("authority", {})
                .get("editorialSha256")
                != editorial_sha256
                or existing_run.get("artifacts", {}).get("sourcesSha256")
                != sources_sha256
            ):
                raise FutureSongError("immutable run authority binding already exists")
        report_authority = self.write_report_authority_references(
            video_id, editorial_sha256, sources_sha256
        )

        checkpoint_key = self.alignment_checkpoint_key(
            job, editorial_sha256=editorial_sha256, state=state
        )
        producing_inputs = job.get('alignmentAttemptInputs')
        if isinstance(producing_inputs, dict) and producing_inputs.get('attemptId') == run_id:
            from stemwise_engine.alignment_attempt import AttemptInputs
            inputs = AttemptInputs.from_record(producing_inputs)
            if inputs.editorial_sha256 != editorial_sha256 or inputs.sources_sha256 != sources_sha256:
                raise FutureSongError('alignment report differs from its producing input identity')
            checkpoint_key['policies']['configurationSha256'] = inputs.configuration_sha256
            checkpoint_key['helpers'] = {name: dict(inputs.helpers)[name]
                for name in checkpoint_key['helpers']}
        checkpoint_key["reportAuthority"] = report_authority
        checkpoint_raw = json.dumps(
            checkpoint_key,
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        run_dir = report_root / "runs" / run_id
        existing_run = run_dir / "run.json"
        completed_at = int(time.time())
        if existing_run.is_file():
            existing = read_bounded_json(
                existing_run, max_bytes=MAX_JSON_BYTES, root=report_root
            )
            if isinstance(existing, dict) and type(existing.get("completedAtUnix")) is int:
                completed_at = existing["completedAtUnix"]
        record, evidence = alignment_run_record(job, run_id=run_id, video_id=video_id, state=state,
            checkpoint_key=checkpoint_key, checkpoint_raw=checkpoint_raw, completed_at=completed_at,
            status=status, run_artifacts=run_artifacts, failure_code=failure_code,
            producing_inputs=inputs.to_record() if isinstance(producing_inputs, dict)
                and producing_inputs.get('attemptId') == run_id else None,
            observed_input_changes=observed_input_changes or {})
        try:
            return self.store.commit(report_root, record, evidence=evidence)
        except ReportSizeLimitExceeded:
            compact_record, compact_evidence = compact_alignment_run(record, evidence,
                state=state, checkpoint_raw=checkpoint_raw)
            compact_sha256 = self.store.commit(
                report_root, compact_record, evidence=compact_evidence
            )
            if status == "complete":
                raise
            return compact_sha256


    def finalization_checkpoint_requirements(
        self,
        job: dict[str, Any],
        package_sha256: str,
    ) -> dict[str, Any] | None:
        validation = job.get("validation")
        if not isinstance(validation, dict):
            return None
        required = {
            "validationReportSha256": validation.get("reportSha256"),
            "validationRunId": validation.get("runId"),
            "validationRunSha256": validation.get("runSha256"),
            "alignmentRunId": validation.get("alignmentRunId"),
            "alignmentRunSha256": validation.get("alignmentRunSha256"),
            "childSha256": package_sha256,
            "policyHashes": {
                key: value
                for key, value in (job.get("policy") or {}).items()
                if isinstance(key, str)
                and key.endswith("Sha256")
                and isinstance(value, str)
                and re.fullmatch(r"[0-9a-f]{64}", value)
            },
        }
        if validation.get('kind') == 'capability-completion':
            required.pop('alignmentRunId')
            required.pop('alignmentRunSha256')
            if (any(not isinstance(required[key], str) or re.fullmatch('[0-9a-f]{64}', required[key]) is None
                    for key in ('validationReportSha256', 'validationRunSha256', 'childSha256'))
                    or re.fullmatch('[0-9a-f]{32}', str(required['validationRunId'])) is None):
                return None
            return required
        hashes = (
            required["validationReportSha256"],
            required["validationRunSha256"],
            required["alignmentRunSha256"],
            required["childSha256"],
        )
        run_ids = (required["validationRunId"], required["alignmentRunId"])
        if any(
            not isinstance(value, str)
            or re.fullmatch(r"[0-9a-f]{64}", value) is None
            for value in hashes
        ) or any(
            not isinstance(value, str)
            or re.fullmatch(r'[0-9a-f]{32}', value) is None
            for value in run_ids
        ):
            return None
        return required


    def commit_finalization_run(
        self,
        job: dict[str, Any],
        *,
        destination: Path,
        package_sha256: str,
    ) -> tuple[str, str]:
        video_id = validate_video_id(job["videoId"])
        schema_sha = (job.get("policy") or {}).get("schemaSha256")
        if not isinstance(schema_sha, str) or re.fullmatch(
            r"[0-9a-f]{64}", schema_sha
        ) is None:
            raise FutureSongError("finalization has no bound external schema identity")
        checkpoint_requirements = self.finalization_checkpoint_requirements(
            job, package_sha256
        )
        if checkpoint_requirements is None:
            raise FutureSongError("finalization has no bound validation run identity")
        relative_destination = destination.relative_to(self.workspace_root).as_posix()
        checkpoint_key = {
            **checkpoint_requirements,
            "configurationSha256": sha256_file(self.config_path),
            "helperSha256": {
                name: sha256_file(
                    validate_generated_path(
                        self.workspace_root,
                        Path(self.config.get("projectDir", self.config_path.parent)) / "src" / name,
                        workspace_root=self.workspace_root,
                    )
                )
                for name in ("common.py", "future_song.py", "packages.py")
            },
        }
        checkpoint_raw = json.dumps(
            checkpoint_key,
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        destination_stat = destination.stat()
        identity = {
            "videoId": video_id,
            "operation": "package-finalization",
            "status": "complete",
            "completedAtUnixNs": destination_stat.st_mtime_ns,
            "checkpointKey": checkpoint_key,
            "checkpointKeySha256": hashlib.sha256(checkpoint_raw).hexdigest(),
            "diagnostics": {"packageBytes": destination_stat.st_size},
            "schemaValidation": {
                "result": "pass",
                "externalFormatVersion": 2,
                "schemaSha256": schema_sha,
            },
            "artifacts": {
                "workspaceRelativePath": relative_destination,
                "sha256": package_sha256,
                "reviewEvidenceRetained": True,
            },
        }
        identity_raw = json.dumps(
            identity,
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        run_id = hashlib.sha256(identity_raw).hexdigest()[:32]
        record = {
            "reportVersion": PRIVATE_REPORT_VERSION,
            "runId": run_id,
            **identity,
        }
        report_root = self.ensure_report_identity(video_id)
        run_sha256 = self.store.commit(report_root, record)
        return run_id, run_sha256


    def artwork_repair_artifacts(self, job, *, source_video_id, previous_child_sha256,
            previous_artwork_sha256, child_sha256, artwork_sha256):
        from stemwise_engine.original_delivery import artwork_delivery_binding
        artifacts = {'sourceVideoId': source_video_id,
            'previousChildSha256': previous_child_sha256,
            'previousArtworkSha256': previous_artwork_sha256,
            'childSha256': child_sha256, 'artworkSha256': artwork_sha256,
            'validationChildSha256': job['validation']['childSha256'],
            'validationArtworkSha256': job['validation']['artworkSha256'],
            'previousDelivery': artwork_delivery_binding(job)}
        if job.get('artworkRepair') is not None:
            artifacts['previousRepair'] = {key: job['artworkRepair'][key]
                                           for key in ('runId', 'runSha256')}
        return artifacts


    def commit_artwork_repair_run(
        self,
        job: dict[str, Any],
        *,
        source_video_id: str,
        previous_child_sha256: str,
        previous_artwork_sha256: str,
        child_sha256: str,
        artwork_sha256: str,
    ) -> tuple[str, str, str]:
        """Record one artwork-only replacement against an intact delivery."""

        video_id = validate_video_id(job["videoId"])
        run_id = uuid.uuid4().hex
        repaired_at = utc_now()
        artifacts = self.artwork_repair_artifacts(job,
            source_video_id=source_video_id, previous_child_sha256=previous_child_sha256,
            previous_artwork_sha256=previous_artwork_sha256, child_sha256=child_sha256,
            artwork_sha256=artwork_sha256)
        checkpoint_key = {
            "validationReportSha256": job["validation"]["reportSha256"],
            "validationRunId": job["validation"]["runId"],
            "validationRunSha256": job["validation"]["runSha256"],
            "deliveryRunId": job["delivery"]["runId"],
            "deliveryRunSha256": job["delivery"]["runSha256"],
            "configurationSha256": sha256_file(self.config_path),
            "policyHashes": {
                key: value
                for key, value in (job.get("policy") or {}).items()
                if isinstance(key, str)
                and key.endswith("Sha256")
                and isinstance(value, str)
                and re.fullmatch(r"[0-9a-f]{64}", value)
            },
        }
        checkpoint_raw = json.dumps(
            checkpoint_key,
            ensure_ascii=True,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        record = {
            "reportVersion": PRIVATE_REPORT_VERSION,
            "runId": run_id,
            "videoId": video_id,
            "operation": "artwork-repair",
            "status": "complete",
            "completedAtUnix": int(time.time()),
            "checkpointKey": checkpoint_key,
            "checkpointKeySha256": hashlib.sha256(checkpoint_raw).hexdigest(),
            "summary": {"result": "accepted-artwork-only"},
            "schemaValidation": {
                "result": "pass",
                "externalFormatVersion": 2,
                "schemaSha256": job["policy"]["schemaSha256"],
            },
            "artifacts": artifacts,
        }
        run_sha256 = self.store.commit(self.report_root(video_id), record)
        return run_id, run_sha256, repaired_at

    def alignment_failure_snapshot(
        self, report_root: Path, video_id: str, attempt_id: str
    ) -> tuple[Path, dict[str, Any], dict[str, Any]]:
        """Load one intact immutable alignment failure and its body-free diagnostics."""

        if re.fullmatch('[0-9a-f]{32}', attempt_id) is None:
            raise FutureSongError("endpoint failure alignment attempt is invalid")
        run_path = validate_generated_path(
            report_root,
            report_root / "runs" / attempt_id / "run.json",
            workspace_root=self.workspace_root,
        )
        try:
            run = read_bounded_json(
                run_path,
                max_bytes=MAX_JSON_BYTES,
                root=report_root,
            )
        except (OSError, ValueError) as error:
            raise FutureSongError(
                "endpoint failure immutable alignment run is unavailable"
            ) from error
        if (
            not isinstance(run, dict)
            or run.get("runId") != attempt_id
            or run.get("videoId") != video_id
            or run.get("operation") != "lyrics-alignment"
            or run.get("status") != "failed"
        ):
            raise FutureSongError("endpoint failure immutable alignment run is ineligible")
        try:
            artifacts_match = self.store.artifacts_match(run_path.parent, run)
        except (OSError, ValueError) as error:
            raise FutureSongError(
                "endpoint failure immutable alignment run is ineligible"
            ) from error
        if not artifacts_match:
            raise FutureSongError("endpoint failure immutable alignment run is ineligible")
        manifest = run["reportArtifacts"][0]
        evidence = read_bounded_json(
            run_path.parent / manifest["fileName"],
            max_bytes=MAX_JSON_BYTES,
            root=run_path.parent,
        )
        diagnostics = evidence.get("diagnostics") if isinstance(evidence, dict) else None
        if not isinstance(diagnostics, dict):
            raise FutureSongError("endpoint failure immutable diagnostics are ineligible")
        return run_path, run, diagnostics
