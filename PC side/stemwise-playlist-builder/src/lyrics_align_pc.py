#!/usr/bin/env python3
"""Private PC CLI for repeat-aware Stemwise lyric synchronization."""

from __future__ import annotations

from stemwise_engine.provider_operations import (ProviderCancelled, ProviderDeadline, ProviderStageDeadline,
                                                raise_if_operation_stopped)
from stemwise_engine.common import raise_with_cleanup_failure

import argparse
import stemwise_engine.alignment_audio as audio_store
import stemwise_engine.alignment_models as models
import stemwise_engine.ctc_worker as ctc_worker
import copy
import gc
import hashlib
import importlib.metadata
import json
import math
import os
import platform
import re
import shutil
import sys
import time
import traceback
import unicodedata
from collections import Counter
from dataclasses import asdict, dataclass, field, replace
from fractions import Fraction
from functools import lru_cache
from itertools import combinations, product
from pathlib import Path
from stemwise_engine.model_registry import model_assets, runtime_profile
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.runtime_environment import environment_python, environment_lease
from subprocess import TimeoutExpired
from typing import Any, Callable, Sequence, TYPE_CHECKING
if TYPE_CHECKING:
    from stemwise_engine.pipeline import StemwisePipeline

from stemwise_engine.alignment_publication import AlignmentPublisher, _require_completed_structure_witness
from stemwise_engine.lyric_content import (confirm_content, require_content, require_exact_sequence, require_timing_only)
from stemwise_engine.workflow_records import LyricsContentBlocker, ContentEvidenceUnavailable, CONTENT_EVIDENCE_TECHNICAL_REASONS
import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.structure_selection as structure_selection
import stemwise_engine.lyric_content as lyric_content
import stemwise_engine.alignment_worker_session as _alignment_sessions
import stemwise_engine.worker_protocol as worker_protocol
from stemwise_engine.worker_protocol import parse_worker_json, WorkerProtocolError
from stemwise_engine.campaign_operations import model_operation_slot
from stemwise_engine.alignment_phases import (AudioIdentity, PairedEndpointResult, EndpointSelectionResult, EndpointAlignmentRequest,
    respects_reviewed_start,
    AlignmentContext, EndpointText, EndpointEvidence, EndpointAudio, EndpointWorkers,
    EndpointServices, AlignmentEvents, AlignmentProgress, StructureSelectionRequest,
    StructureSelectionResult, AlignmentPublicationRequest, EndpointTimings)
from stemwise_engine.structure_selection import (_baseline_source_indices, _has_structural_mutation, _result_score, _select_evidence_option)


from stemwise_engine.common import (
    WorkerRetirementError,
    WorkerTemporaryDirectory,
    WORKER_RETIREMENT_FAILURE_MARKER,
    atomic_write_bytes,
    atomic_write_json,
    create_manifested_directory,
    exclusive_process_lock,
    heavy_worker_lock_path,
    heavy_worker_stage,
    load_config,
    parse_strict_json_bytes,
    read_bounded_json,
    read_bounded_regular_bytes,
    read_json as _read_json,
    remove_manifested_directory,
    run_process_tree,
    sha256_file as _sha256_file,
    validate_generated_path,
    validate_video_id,
)
from stemwise_engine.contract import (
    MAX_JSON_BYTES,
)
from stemwise_engine.lyrics_alignment import (
    _plain_original,
    _ordered_endpoint_candidate_path,
    _lyric_lines_for_sources,
    _mapping,
    ALIGNMENT_VERSION,
    DEFAULT_MODEL,
    DEFAULT_THRESHOLD,
    TRANSCRIPT_CACHE_VERSION,
    _physical_lines as _plain_physical_lines,
    AlignmentResult,
    AlignmentWorkspace,
    FasterWhisperEvidence,
    StructureWitness,
    WordTimestamp,
    align_known_structure,
    align_known_structure_multi,
    build_lexical_occurrence_mapping,
    build_predicted_sidecar,
    build_synced_sidecar,
    canonical_language,
    deduplicated_lyrics_prompt,
    download_youtube_caption_evidence,
    inventory_summary,
    internal_repeat_structure_kind,
    load_sidecar,
    lexical_mapping_identity,
    normalize,
    score_structure_witness,
    serialize_result,
    separate_vocals_demucs,
    snapshot_canonical_plain,
    transcript_cache_matches,
    transcript_from_json,
    transcript_to_json,
    transcription_languages,
    whisper_word_candidates,
)
from stemwise_engine.lyrics_tokenization import (
    TOKENIZATION_VERSION,
    display_words,
    lexical_authority_sha256,
    lexical_line_tokens,
)
from stemwise_engine.packages import validate_child_zip
from stemwise_engine.qwen3_asr_transcribe import (
    DECODER as QWEN3_ASR_DECODER,
    ENGINE as QWEN3_ASR_ENGINE,
    FORMAT_VERSION as QWEN3_ASR_FORMAT_VERSION,
    _snapshot_sha256 as _qwen3_asr_snapshot_sha256,
    _validated_output as _validate_qwen3_asr_output,
    _validated_request as _validate_qwen3_asr_request,
)
import stemwise_engine.mms_asr as mms_asr
import stemwise_engine.nemo_nfa_forced_alignment as nemo_nfa
import stemwise_engine.owsm_ctc_forced_alignment as owsm_ctc
from stemwise_engine.song_enrichment_v2 import validate_lyrics
from stemwise_engine.youtube import download_analysis_audio


ALIGNMENT_ATTEMPT_ID_PATTERN = re.compile(r"[0-9a-f]{32}")
CAPTION_NEGATIVE_CACHE_TTL_SECONDS = 86_400
MMS_REQUEST_VERSION = 5
MMS_CACHE_VERSION = 6
WAV2VEC2_RESULT_VERSION = 4
WAV2VEC2_CACHE_VERSION = 5
QWEN3_ASR_CACHE_VERSION = 1
QWEN3_ASR_REVISION = model_assets('lyrics_align_pc')['QWEN3_ASR_REVISION']
QWEN3_ASR_SNAPSHOT_SHA256 = model_assets('lyrics_align_pc')['QWEN3_ASR_SNAPSHOT_SHA256']
QWEN3_ASR_ROUTE = "qwen3-asr-unprompted-mix"
QWEN3_STRUCTURE_CONFIRMATION = "qwen3-asr-confirmed-structure"
QWEN3_STRUCTURE_TRIGGER_REASONS = frozenset(
    {
        "conflicting-accepted-structures",
        "unsupported-mixed-repeat-structure",
        "structural-mutation-needs-independent-evidence",
    }
)
OWSM_CTC_CACHE_VERSION = 1
NEMO_NFA_CACHE_VERSION = 1


CACHE_IDENTITY_VERSION = 2
QWEN3_ASR_SOURCE_ROUTES = {
    view: f"qwen3-asr-unprompted-{view}"
    for view in ("mix", "htdemucs-vocals", *endpoints.ROFORMER_ROUTES)
}
PINNED_WHISPER_SNAPSHOT = model_assets('lyrics_align_pc')['PINNED_WHISPER_SNAPSHOT']
PINNED_WHISPER_SHA256 = model_assets('lyrics_align_pc')['PINNED_WHISPER_SHA256']
PINNED_WHISPER_FILES = model_assets('lyrics_align_pc')['PINNED_WHISPER_FILES']
PINNED_MMS_SHA256 = model_assets('lyrics_align_pc')['PINNED_MMS_SHA256']
PINNED_WAV2VEC2_MODELS = model_assets('lyrics_align_pc')['PINNED_WAV2VEC2_MODELS']
PINNED_UNIVERSAL_WAV2VEC2_MODEL = model_assets('lyrics_align_pc')['PINNED_UNIVERSAL_WAV2VEC2_MODEL']
WHISPER_DECODING_PACKAGES = (
    "av",
    "ctranslate2",
    "faster-whisper",
    "numpy",
    "rapidfuzz",
    "syncalong",
    "tokenizers",
)
_PINNED_GIT_BLOB_CACHE: dict[Path, tuple[int, int, int, int, int, str]] = {}


class ConditionalCascadeTechnicalError(RuntimeError):
    """All safe cascade routes were exhausted after a technical fault."""


class Wav2Vec2TargetUnsupported(RuntimeError):
    """The pinned worker cannot encode this request before acoustic inference."""




def _alignment_attempt_id(value: str) -> str:
    if not ALIGNMENT_ATTEMPT_ID_PATTERN.fullmatch(value):
        raise argparse.ArgumentTypeError("must be a 32-character lowercase hexadecimal token")
    return value


def _sha256_argument(value: str) -> str:
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise argparse.ArgumentTypeError("must be a 64-character lowercase SHA-256")
    return value


def _positive(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        prog="stemwise-lyrics-align",
        description=(
            "Align approved lyrics to exact song audio without changing their words. "
            "Full lyric bodies are never printed."
        ),
    )
    root.add_argument("--config", required=True, type=Path)
    commands = root.add_subparsers(dest="command", required=True)

    commands.add_parser("doctor", help="Report local dependency and corpus readiness")
    model = commands.add_parser("preload-model", help="Verify and open the pinned local model")
    model.add_argument("--model", default=DEFAULT_MODEL)
    model.add_argument("--device", default="cuda", choices=("cuda", "cpu"))
    model.add_argument("--compute-type")
    commands.add_parser("inventory", help="Count plain, synced, regional, unavailable, and invalid sidecars")
    commands.add_parser("status", help="Summarize alignment job states")

    align = commands.add_parser("align", help="Align selected approved plain lyrics")
    align.add_argument("--limit", type=_positive)
    align.add_argument("--from-position", type=_positive)
    align.add_argument("--to-position", type=_positive)
    align.add_argument("--video-id", action="append", default=[])
    align.add_argument("--model", default=DEFAULT_MODEL)
    align.add_argument("--device", default="cuda", choices=("cuda", "cpu"))
    align.add_argument("--compute-type")
    align.add_argument("--rerun-transcript", action="store_true")
    align.add_argument(
        "--realign-synced",
        action="store_true",
        help="Rebuild committed synced tracks from their immutable canonical plain backup",
    )
    align.add_argument(
        "--no-commit",
        action="store_true",
        help="Write private candidates and metrics but leave owner-input unchanged",
    )

    future = commands.add_parser(
        "future-align",
        help="Align one prepared future-song sidecar against its exact runtime child",
    )
    future.add_argument("--video-id", required=True)
    future.add_argument("--attempt-id", required=True, type=_alignment_attempt_id)
    future.add_argument("--analysis-audio", required=True, type=Path)
    future.add_argument("--future-root", required=True, type=Path)
    future.add_argument("--analysis-root", required=True, type=Path)
    future.add_argument("--expected-mix-sha256", required=True, type=_sha256_argument)
    future.add_argument("--editorial-sha256", required=True, type=_sha256_argument)
    future.add_argument(
        "--canonical-plain-sha256", required=True, type=_sha256_argument
    )
    future.add_argument("--model", default=DEFAULT_MODEL)
    future.add_argument("--device", default="cuda", choices=("cuda", "cpu"))
    future.add_argument("--compute-type")
    future.add_argument("--rerun-transcript", action="store_true")
    future.add_argument("--qwen-structure-witness", action="store_true")
    future.add_argument("--reviewed-baseline-structure", action="store_true")
    future.add_argument(
        "--performed-structure-review-sha256", type=_sha256_argument
    )
    future.set_defaults(
        limit=None,
        from_position=None,
        to_position=None,
        no_commit=False,
        realign_synced=False,
    )

    predicted = commands.add_parser('owner-word-prediction',
        help='Predict complete acoustic word timings for exact owner-verified lyrics')
    predicted.add_argument('--video-id', required=True)
    predicted.add_argument('--future-root', required=True, type=Path)
    predicted.add_argument('--analysis-root', required=True, type=Path)
    predicted.add_argument('--analysis-audio', required=True, type=Path)
    predicted.add_argument('--expected-mix-sha256', required=True, type=_sha256_argument)
    predicted.add_argument('--canonical-plain-sha256', required=True, type=_sha256_argument)
    predicted.add_argument('--device', default='cuda', choices=('cuda', 'cpu'))

    validate = commands.add_parser("validate", help="Validate every currently synced sidecar")
    validate.add_argument("--video-id", action="append", default=[])
    return root


def _print(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False))


def _cleanup_owned_attempt_root(
    attempt_root: Path,
    *,
    generated_root: Path,
    workspace_root: Path,
    video_id: str,
    attempt_id: str,
) -> None:
    remove_manifested_directory(
        generated_root,
        attempt_root,
        owner={
            "kind": "lyrics-alignment-attempt",
            "videoId": video_id,
            "attemptId": attempt_id,
        },
        workspace_root=workspace_root,
    )




def _retained_content_state(workspace, video_id):
    path = workspace.state_path(video_id)
    return (read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=workspace.job_dir(video_id))
            if path.exists() else {})


def _require_no_unchanged_content_block(prior, canonical_sha, audio_sha, *, workspace=None,
                                       video_id=None, original=None):
    if prior.get("status") != "lyrics-content-unresolved":
        return
    binding = prior.get("contentBinding")
    reasons = prior.get("reasons", ())
    try:
        if not isinstance(reasons, (list, tuple)):
            raise ValueError("invalid retained content reasons")
        blocker = LyricsContentBlocker(*reasons, binding=binding)
    except (TypeError, ValueError):
        raise LyricsContentBlocker("content-negative-evidence-invalid") from None
    if reasons and all(reason in CONTENT_EVIDENCE_TECHNICAL_REASONS for reason in reasons):
        return  # A fresh private witness must still independently admit current content.
    if isinstance(binding, dict) and (binding.get('canonicalPlainSha256') != canonical_sha
            or binding.get('audioSha256') != audio_sha):
        return
    if set(reasons) == {'pronunciation-support-unavailable'} and workspace is not None:
        if original is None:
            raw = read_bounded_regular_bytes(workspace.current_backup_path(video_id),
                max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
            if hashlib.sha256(raw).hexdigest() != canonical_sha:
                raise blocker
            original = _plain_original(parse_strict_json_bytes(raw))
        from stemwise_engine.spoken_targets import load
        sources = (binding['sourcePhysicalIndices'] if isinstance(binding, dict)
                   else _baseline_source_indices(original['text']))
        plan = load(workspace.root.parent/'jobs'/video_id, video_id, original, sources, audio_sha)
        if plan is not None:
            lyric_content.require_pronunciation_support(original['text'], plan)
            return  # Repaired pronunciation still needs independent current recognition admission.
        raise blocker
    if not isinstance(binding, dict):
        repairable_input = {"content-text-invalid", "content-text-unavailable",
                            "content-canonical-unavailable", "content-canonical-invalid"}
        if reasons and all(reason in repairable_input for reason in reasons):
            return  # Valid repaired input still needs fresh retained recognition admission.
        raise blocker
    if (set(reasons) == {'content-sequence-uncertain'} and workspace is not None
            and isinstance(prior.get('contentDispute'), dict)):
        if original is None:
            raw = read_bounded_regular_bytes(workspace.current_backup_path(video_id),
                max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
            if hashlib.sha256(raw).hexdigest() != canonical_sha:
                raise blocker
            original = _plain_original(parse_strict_json_bytes(raw))
        admission = lyric_content.reviewed_workspace_admission(workspace, video_id, original,
            binding['sourcePhysicalIndices'], canonical_sha, audio_sha)
        from stemwise_engine.alignment_attempt import ContentDispute
        try:
            dispute = ContentDispute.from_record(prior['contentDispute'],
                performed_count=len(binding['sourcePhysicalIndices']))
        except (TypeError, ValueError):
            raise blocker from None
        if admission is not None and (admission.to_record()['evidence'] == dispute.evidence
                or lyric_content.is_source_recording_admission(admission, workspace.job_dir(video_id))):
            return  # Only this exact recorded dispute has a current independently reviewed resolution.
    raise blocker


def _run_future_alignment_attempt(
    config: dict[str, Any],
    args: argparse.Namespace,
    *,
    video_id: str,
    attempt_id: str,
    workspace_root: Path,
    generated_root: Path,
    future_workspace: AlignmentWorkspace,
    future_paths: tuple[Path, Path, Path, AlignmentWorkspace],
) -> dict[str, Any]:
    review_sha256 = getattr(args, "performed_structure_review_sha256", None)
    if getattr(args, "reviewed_baseline_structure", False) != (
        review_sha256 is not None
    ):
        raise ValueError("performed structure review arguments are incomplete")
    state_fields = {
        "attemptId": attempt_id,
        "canonicalPlainSha256": args.canonical_plain_sha256,
        "workerPid": os.getpid(),
        "workerStartedAtUnix": int(time.time()),
    }
    if review_sha256 is not None:
        state_fields["performedStructureReview"] = {
            "decision": "baseline",
            "sha256": review_sha256,
        }
    state = {
        "videoId": video_id,
        "position": 1,
        "status": "queued",
        "model": args.model,
        **state_fields,
    }
    _require_no_unchanged_content_block(_retained_content_state(future_workspace, video_id),
        args.canonical_plain_sha256, getattr(args, "expected_mix_sha256", None),
        workspace=future_workspace, video_id=video_id)
    attempt_root: Path | None = None
    attempt_created = False
    try:
        attempt_root = validate_generated_path(
            generated_root,
            generated_root / video_id / attempt_id,
            workspace_root=workspace_root,
        )
        create_manifested_directory(
            generated_root,
            attempt_root,
            owner={
                "kind": "lyrics-alignment-attempt",
                "videoId": video_id,
                "attemptId": attempt_id,
            },
            workspace_root=workspace_root,
        )
        attempt_created = True
        with exclusive_process_lock(attempt_root / ".stemwise-worker.lock"), exclusive_process_lock(
            future_workspace.root / "lyrics-align.lock",
            wait_seconds=int(config["analysisTimeoutMinutes"]) * 60,
        ):
            return _align(
                config,
                args,
                selected_override=[{"position": 1, "videoId": video_id}],
                paths_override=future_paths,
                temp_root_override=attempt_root,
                state_fields_override=state_fields,
            )
    except WorkerRetirementError:
        raise
    except LyricsContentBlocker as error:
        future_workspace.write_state(video_id, {
            **state, "status": "lyrics-content-unresolved", "reasons": list(error.reasons),
            "contentBinding": error.binding,
        })
        raise
    except Exception as error:
        raise_if_operation_stopped(error)
        future_workspace.write_state(
            video_id,
            {
                **state,
                "status": "retryable-technical",
                "reasons": [worker_protocol.technical_diagnostic_code(error)],
            },
        )
        raise
    finally:
        failure = sys.exc_info()[1]
        if attempt_created and attempt_root is not None and not isinstance(failure, WorkerRetirementError):
            try:
                _cleanup_owned_attempt_root(
                    attempt_root,
                    generated_root=generated_root,
                    workspace_root=workspace_root,
                    video_id=video_id,
                    attempt_id=attempt_id,
                )
            except BaseException as cleanup_error:
                raise_with_cleanup_failure(failure, cleanup_error)


def _read_optional_cache_json(path: Path) -> dict[str, Any] | None:
    """Treat every missing, malformed, truncated, or oversized cache as a miss."""

    if not path.is_file():
        return None
    try:
        value = read_bounded_json(
            path,
            max_bytes=MAX_JSON_BYTES,
            root=path.parent,
        )
    except (OSError, UnicodeError, ValueError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def _inventory(config: dict[str, Any]) -> dict[str, Any]:
    path = Path(config["outputDir"]) / "work" / "inventory.json"
    value = _read_json(path)
    if not isinstance(value, dict) or not isinstance(value.get("items"), list):
        raise ValueError("frozen work/inventory.json is invalid")
    return value


def _selected_items(args: argparse.Namespace, snapshot: dict[str, Any]) -> list[dict[str, Any]]:
    requested = {validate_video_id(value) for value in getattr(args, "video_id", [])}
    selected: list[dict[str, Any]] = []
    for item in snapshot["items"]:
        position = int(item["position"])
        video_id = validate_video_id(str(item["videoId"]))
        if getattr(args, "from_position", None) and position < args.from_position:
            continue
        if getattr(args, "to_position", None) and position > args.to_position:
            continue
        if requested and video_id not in requested:
            continue
        selected.append(item)
    if requested:
        found = {str(item["videoId"]) for item in selected}
        missing = requested - found
        if missing:
            raise ValueError("requested video ID is not in the frozen baseline inventory")
    if getattr(args, "limit", None):
        selected = selected[: args.limit]
    return selected


def _paths(config: dict[str, Any]) -> tuple[Path, Path, Path, AlignmentWorkspace]:
    project = Path(config["projectDir"])
    agent_root = project.parent
    sidecars = project / "owner-input"
    output = Path(config["outputDir"])
    model_cache = agent_root / "model-cache" / "lyrics-alignment"
    return sidecars, output, model_cache, AlignmentWorkspace(agent_root)


def _checkpoint_mix_sha256(
    output: Path,
    config: dict[str, Any],
    video_id: str,
    *,
    duration_ms: int,
    active_pipeline: StemwisePipeline,
) -> str:
    checkpoint_dir = output / "work" / "checkpoints"
    checkpoint = read_bounded_json(
        checkpoint_dir / f"{video_id}.json",
        max_bytes=MAX_JSON_BYTES,
        root=checkpoint_dir,
    )
    audio = checkpoint.get("originalAudio") if isinstance(checkpoint, dict) else None
    if (
        not isinstance(checkpoint, dict)
        or checkpoint.get("videoId") != video_id
        or checkpoint.get("status") != "complete"
        or checkpoint.get("generatorVersion") != config["generatorVersion"]
        or active_pipeline._checkpoint_producer(checkpoint) is None
        or not isinstance(audio, dict)
        or re.fullmatch(r"[0-9a-f]{64}", str(audio.get("sha256") or "")) is None
        or type(audio.get("byteCount")) is not int
        or audio["byteCount"] <= 0
        or audio.get("durationMs") != duration_ms
    ):
        raise ValueError("completed analysis PCM identity is missing, invalid, or stale")
    return audio["sha256"]










def _alignment_runtime_identity(config: dict[str, Any]) -> dict[str, Any]:
    project = Path(config["projectDir"])
    expected_packages = runtime_profile('lyrics')['packages']
    missing_pins = sorted(set(WHISPER_DECODING_PACKAGES) - set(expected_packages))
    actual_packages: dict[str, str] = {}
    for name in sorted(set(expected_packages) | set(WHISPER_DECODING_PACKAGES)):
        try:
            actual_packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            actual_packages[name] = "missing"
    return {
        "python": platform.python_version(),
        "packages": actual_packages,
        "expectedPython": models.qualified_python_version(project),
        "expectedPackages": dict(sorted(expected_packages.items())),
        "missingPins": missing_pins,
    }


def _verify_alignment_runtime(config: dict[str, Any]) -> dict[str, Any]:
    identity = _alignment_runtime_identity(config)
    if identity["missingPins"]:
        raise RuntimeError(
            "authoritative dependency manifests have no exact version pins for: "
            + ", ".join(identity["missingPins"])
        )
    mismatches = [
        name
        for name, expected in identity["expectedPackages"].items()
        if identity["packages"].get(name) != expected
    ]
    if identity["python"] != identity["expectedPython"]:
        mismatches.append("python")
    if mismatches:
        raise RuntimeError(
            "lyrics alignment runtime differs from exact dependency pins: "
            + ", ".join(sorted(mismatches))
        )
    return {
        "python": identity["python"],
        "packages": identity["packages"],
    }












def _require_pinned_git_blob(
    path: Path, expected_sha1: str, expected_size: int, label: str
) -> str:
    if not path.is_file():
        raise RuntimeError(f"pinned {label} is missing")
    stat = path.stat()
    if stat.st_size != expected_size:
        raise RuntimeError(f"pinned {label} has the wrong byte count")
    identity = (
        stat.st_dev,
        stat.st_ino,
        stat.st_size,
        stat.st_mtime_ns,
        stat.st_ctime_ns,
    )
    cached = _PINNED_GIT_BLOB_CACHE.get(path)
    if os.name != "nt" and cached is not None and cached[:5] == identity:
        actual = cached[5]
    else:
        digest = hashlib.sha1(f"blob {stat.st_size}\0".encode("ascii"))
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        actual = digest.hexdigest()
    if actual != expected_sha1:
        raise RuntimeError(f"pinned {label} failed its Git blob identity check")
    _PINNED_GIT_BLOB_CACHE[path] = (*identity, actual)
    return actual


def _whisper_snapshot_path(model_cache: Path) -> Path:
    return (
        model_cache
        / "models--Systran--faster-whisper-large-v3"
        / "snapshots"
        / PINNED_WHISPER_SNAPSHOT
    )


def _whisper_manifest_sha256() -> str:
    manifest = {
        "snapshotCommit": PINNED_WHISPER_SNAPSHOT,
        "files": PINNED_WHISPER_FILES,
    }
    raw = json.dumps(
        manifest, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _verify_whisper_snapshot(model_cache: Path) -> tuple[Path, dict[str, str]]:
    snapshot = _whisper_snapshot_path(model_cache)
    if not snapshot.is_dir():
        raise RuntimeError("pinned faster-whisper snapshot is missing")
    actual_names = {path.name for path in snapshot.iterdir()}
    expected_names = set(PINNED_WHISPER_FILES)
    if actual_names != expected_names:
        raise RuntimeError("pinned faster-whisper snapshot file manifest differs")
    model_sha256 = ""
    for name, identity in PINNED_WHISPER_FILES.items():
        path = snapshot / name
        byte_count = int(identity["byteCount"])
        if name == "model.bin":
            if not path.is_file() or path.stat().st_size != byte_count:
                raise RuntimeError("pinned faster-whisper model has the wrong byte count")
            model_sha256 = models.require_pinned_file(
                path, str(identity["sha256"]), "faster-whisper large-v3 model"
            )
        else:
            _require_pinned_git_blob(
                path,
                str(identity["gitBlobSha1"]),
                byte_count,
                f"faster-whisper {name}",
            )
    return snapshot, {
        "whisperModelSha256": model_sha256,
        "whisperSnapshotManifestSha256": _whisper_manifest_sha256(),
    }


def _verify_pinned_stack(
    config: dict[str, Any], model_cache: Path, model_name: str
) -> dict[str, str]:
    if model_name != DEFAULT_MODEL:
        raise RuntimeError(f"endpoint publication requires pinned model {DEFAULT_MODEL}")
    agent_root = Path(config["projectDir"]).parent
    _, whisper_identity = _verify_whisper_snapshot(model_cache)
    return {
        **whisper_identity,
        "demucsModelSha256": models.require_pinned_file(
            agent_root
            / "model-cache"
            / "torch"
            / "hub"
            / "checkpoints"
            / "955717e8-8726e21a.th",
            models.PINNED_DEMUCS_SHA256,
            "HTDemucs checkpoint",
        ),
        "mmsModelSha256": models.require_pinned_file(
            agent_root / "model-cache" / "torch" / "hub" / "checkpoints" / "model.pt",
            PINNED_MMS_SHA256,
            "MMS_FA checkpoint",
        ),
    }


def _wav2vec2_model_assets(
    config: dict[str, Any], language: str | None
) -> tuple[Path, dict[str, Any]] | None:
    raw_language = language.strip().lower() if isinstance(language, str) else None
    language = canonical_language(language)
    if language is None and raw_language != "mul":
        return None
    language = language or "mul"
    specification = PINNED_WAV2VEC2_MODELS.get(language)
    target_kind = "grapheme" if specification is not None else "phoneme"
    if specification is None:
        specification = PINNED_UNIVERSAL_WAV2VEC2_MODEL
    phonemizer_language = (
        endpoints.ESPEAK_LANGUAGE_ALIASES.get(language, language if language != "mul" else "en-us")
        if target_kind == "phoneme"
        else None
    )
    snapshot = (
        Path(config["projectDir"]).parent
        / "model-cache"
        / "lyrics-alignment"
        / str(specification["cacheDirectory"])
        / "snapshots"
        / str(specification["snapshot"])
    )
    files = specification["files"]
    if not snapshot.is_dir() or {
        path.name for path in snapshot.iterdir() if path.is_file()
    } != set(files):
        raise RuntimeError("pinned Wav2Vec2 snapshot file manifest differs")
    identities: dict[str, str] = {}
    for name, (byte_count, sha256) in files.items():
        path = snapshot / name
        if not path.is_file() or path.stat().st_size != byte_count:
            raise RuntimeError("pinned Wav2Vec2 model asset is missing or has the wrong size")
        identities[name] = models.require_pinned_file(
            path, sha256, f"Wav2Vec2 {language} {name}"
        )
    manifest_raw = json.dumps(
        {
            "repository": specification["repository"],
            "snapshot": specification["snapshot"],
            "files": identities,
        },
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return snapshot, {
        "language": language,
        "targetKind": target_kind,
        "phonemizerLanguage": phonemizer_language,
        "repository": specification["repository"],
        "snapshot": specification["snapshot"],
        "modelSha256": identities["pytorch_model.bin"],
        "snapshotManifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
    }




@lru_cache(maxsize=1)
def _phonemizer_languages() -> frozenset[str]:
    # Query the inference runtime, not this alignment interpreter (which does
    # not install phonemizer). A broken runtime must still fail as technical.
    completed = run_process_tree(
        [str(environment_python('main', Path(__file__).resolve().parents[1])), "-B",
         '-m', 'stemwise_engine.environment_probe', 'phonemizer'],
        text=True, capture_output=True, check=True, timeout=30,
    )
    languages = parse_worker_json(completed.stdout)
    if not isinstance(languages, list) or not languages or any(
        not isinstance(language, str) or not language for language in languages
    ):
        raise RuntimeError("phonemizer language capabilities are malformed")
    return frozenset(languages)


def _endpoint_verifier_family(
    original: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]] | None = None,
) -> str:
    """Route by approved language; bootstrap confirms static target capability."""

    language = canonical_language(original.get("language"))
    if language in PINNED_WAV2VEC2_MODELS:
        return endpoints.WAV2VEC2_FAMILY
    if language is not None:
        required = {endpoints.ESPEAK_LANGUAGE_ALIASES.get(language, language)}
    else:
        components = (
            endpoints._component_phonemizer_languages(original, display_word_lines)
            if display_word_lines is not None else None
        )
        if not components:
            raise LyricsContentBlocker("pronunciation-support-unavailable")
        required = {item for line in components for item in line}
    if not required <= _phonemizer_languages():
        raise LyricsContentBlocker("pronunciation-support-unavailable")
    return endpoints.WAV2VEC2_FAMILY


def _endpoint_language(original: dict[str, Any]) -> str | None:
    """Choose the statically ranked concrete language for ASR/model routing."""

    return transcription_languages(original)[0]


def _original_track(lyrics: dict[str, Any]) -> dict[str, Any] | None:
    originals = [track for track in lyrics.get("tracks", []) if track.get("kind") == "original"]
    return originals[0] if len(originals) == 1 else None


def _doctor(config: dict[str, Any]) -> dict[str, Any]:
    import faster_whisper
    import rapidfuzz
    import syncalong
    import yt_dlp

    _, _, model_cache, _ = _paths(config)
    pinned_error: str | None = None
    try:
        pinned_models = _verify_pinned_stack(config, model_cache, DEFAULT_MODEL)
    except WorkerRetirementError:
        raise
    except Exception as error:
        pinned_models = {}
        pinned_error = type(error).__name__
    runtime_error: str | None = None
    try:
        alignment_runtime = _verify_alignment_runtime(config)
    except WorkerRetirementError:
        raise
    except Exception as error:
        alignment_runtime = _alignment_runtime_identity(config)
        runtime_error = type(error).__name__
    project = Path(config["projectDir"])
    mms_runtime_error: str | None = None
    try:
        mms_runtime = _mms_runtime_provenance(
            environment_python('main', project),
            project / "src" / "mms_forced_alignment.py",
            device="cpu",
            timeout_seconds=300,
        )
    except WorkerRetirementError:
        raise
    except Exception as error:
        mms_runtime = {"expected": models.mms_runtime_pins(project)}
        mms_runtime_error = type(error).__name__
    roformer_runtime_error: str | None = None
    try:
        roformer_runtime = _roformer_runtime_provenance(
            environment_python('main', project),
            project / "src" / "roformer_separation.py",
            device="cpu",
            timeout_seconds=300,
        )
    except WorkerRetirementError:
        raise
    except Exception as error:
        roformer_runtime = {"expected": models.roformer_runtime_pins(project)}
        roformer_runtime_error = type(error).__name__
    model_ready = "whisperModelSha256" in pinned_models
    demucs_ready = "demucsModelSha256" in pinned_models
    mms_ready = "mmsModelSha256" in pinned_models
    fallback_models = {}
    for route in endpoints.ROFORMER_ROUTES:
        try:
            models.require_roformer_wheel(project.parent)
            models.roformer_assets(project.parent, route)
        except (OSError, RuntimeError, ValueError) as error:
            fallback_models[route] = {"ready": False, "error": str(error)}
        else:
            fallback_models[route] = {"ready": roformer_runtime_error is None,
                                      "error": roformer_runtime_error}
    kim_roformer_ready = fallback_models['kimberley-jensen-roformer-vocals']['ready']
    sucial_roformer_ready = fallback_models['sucial-v2-roformer-dry-vocals']['ready']
    ffmpeg_ready = shutil.which("ffmpeg") is not None
    ffprobe_ready = shutil.which("ffprobe") is not None
    return {
        "ready": (
            ffmpeg_ready
            and ffprobe_ready
            and model_ready
            and demucs_ready
            and mms_ready
            and runtime_error is None
            and mms_runtime_error is None
        ),
        "optionalFallbacksReady": all(item["ready"] for item in fallback_models.values()),
        "optionalFallbacks": fallback_models,
        "dependencies": {
            "fasterWhisper": faster_whisper.__version__,
            "rapidfuzz": rapidfuzz.__version__,
            "syncalong": importlib.metadata.version("syncalong"),
            "ytDlp": yt_dlp.version.__version__,
        },
        "alignmentRuntime": alignment_runtime,
        "alignmentRuntimeError": runtime_error,
        "mmsRuntime": mms_runtime,
        "mmsRuntimeError": mms_runtime_error,
        "roformerRuntime": roformer_runtime,
        "roformerRuntimeError": roformer_runtime_error,
        "ffmpeg": ffmpeg_ready,
        "ffprobe": ffprobe_ready,
        "largeV3ModelReady": model_ready,
        "htdemucsModelReady": demucs_ready,
        "mmsModelReady": mms_ready,
        "kimRoformerModelReady": kim_roformer_ready,
        "sucialV2RoformerModelReady": sucial_roformer_ready,
        "pinnedModelError": pinned_error,
    }


def _job_summary(workspace: AlignmentWorkspace) -> dict[str, Any]:
    safe_statuses = {
        "starting",
        "queued",
        "interrupted",
        "skipped",
        "already-synced",
        "caption-check",
        "transcribing",
        "transcribing-roformer-fallback",
        "forced-aligning-repeat-structure",
        "aligning",
        "refining-endpoints",
        "candidate",
        "line-unresolved",
        "lyrics-content-unresolved",
        "retryable-technical",
        "committed-words",
        "committed-mixed",
        "committed-lines",
    }
    statuses: Counter[str] = Counter()
    reason_counts: Counter[str] = Counter()
    jobs: list[dict[str, Any]] = []
    for path in sorted(workspace.jobs.glob("*/state.json")):
        try:
            state = read_bounded_json(
                path, max_bytes=MAX_JSON_BYTES, root=workspace.jobs
            )
            if not isinstance(state, dict):
                raise ValueError("alignment state is not an object")
            status = state.get("status")
            if status not in safe_statuses:
                raise ValueError("alignment state status is invalid")
            video_id = validate_video_id(
                str(state.get("videoId") or path.parent.name)
            )
            if not isinstance(state.get('reasons', []), list):
                raise ValueError('invalid alignment reasons')
            reasons = [
                value
                for value in state.get("reasons", [])
                if isinstance(value, str)
                and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,120}", value)
            ]
            metrics = state.get("metrics")
            metrics = (
                {
                    key: value
                    for key, value in metrics.items()
                    if isinstance(key, str)
                    and re.fullmatch(r"[A-Za-z0-9_.:+-]{1,120}", key)
                    and type(value) in {bool, int, float}
                    and (type(value) is not float or math.isfinite(value))
                }
                if isinstance(metrics, dict)
                else {}
            )
        except Exception:
            status = "corrupt"
            try:
                video_id = validate_video_id(path.parent.name)
            except ValueError:
                video_id = "invalid-id"
            reasons = ["corrupt-state"]
            state = {}
            metrics = {}
        statuses[status] += 1
        reason_counts.update(reasons)
        jobs.append(
            {
                "videoId": video_id,
                "position": (
                    state.get("position")
                    if type(state.get("position")) is int
                    and state["position"] > 0
                    else None
                ),
                "status": status,
                "reasons": reasons,
                "evidenceType": (
                    state.get("evidenceType")
                    if isinstance(state.get("evidenceType"), str)
                    and re.fullmatch(
                        r"[A-Za-z0-9_.:+-]{1,160}", state["evidenceType"]
                    )
                    else None
                ),
                "metrics": metrics,
            }
        )
    return {
        "jobCount": len(jobs),
        "statusCounts": dict(sorted(statuses.items())),
        "reasonCounts": dict(sorted(reason_counts.items())),
        "jobs": jobs,
    }


def _transcribe_with_language_fallback(
    model: Any,
    audio: Path,
    *,
    language: str | None,
    prompt: str,
    precision: bool = False,
) -> tuple[list[Any], dict[str, Any]]:
    try:
        return model.transcribe(
            audio, language=language, prompt=prompt, precision=precision
        )
    except ValueError as error:
        if language is None or "not a valid language code" not in str(error):
            raise
        words, evidence = model.transcribe(
            audio, language=None, prompt=prompt, precision=precision
        )
        evidence = dict(evidence)
        evidence["requestedLanguage"] = language
        evidence["languageFallback"] = "auto-detect-unsupported-whisper-code"
        return words, evidence


def _caption_cache_identity_matches(value: Any, current: dict[str, Any]) -> bool:
    return value == current


def _current_cache_identity(original: dict[str, Any]) -> dict[str, Any]:
    """Bind reusable text evidence to every current meaning-affecting input."""
    import syncalong.textnorm as syncalong_textnorm

    project = Path(__file__).resolve().parent.parent
    source = project / "src"
    tokenizer = source / "lyrics_tokenization.py"
    normalization = Path(str(syncalong_textnorm.__file__)).resolve()
    helpers = {name: source / name for name in ('lyrics_tokenization.py', 'lyrics_unicode.py')}
    required = {**helpers, "lyrics_tokenization.py": tokenizer}
    for label, path in (
        *required.items(),
        ("normalization", normalization),
    ):
        if not path.is_file():
            raise RuntimeError(f"current cache identity input is missing: {label}")
    text = original.get("text")
    if not isinstance(text, str):
        raise ValueError("cache identity requires an exact original display text")
    return {
        "cacheIdentityVersion": CACHE_IDENTITY_VERSION,
        "lexicalAuthoritySha256": lexical_authority_sha256(original),
        "finalDisplaySha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "helpersSha256": {
            **{name: semantic_source_identity(path) for name, path in sorted(helpers.items())},
            'captionParser': semantic_source_identity(source/'lyrics_alignment.py',
                ('_caption_language_key', 'parse_youtube_json3', 'download_youtube_caption_evidence')),
        },
        "normalization": {
            "packageVersion": _package_version("syncalong"),
            "sourceSha256": semantic_source_identity(normalization),
        },
        "tokenizer": {
            "tokenizationVersion": TOKENIZATION_VERSION,
            "sourceSha256": semantic_source_identity(tokenizer),
        },
        "graphemePolicySha256": semantic_source_identity(tokenizer),
    }




















def _load_reviewed_lrc_starts(
    workspace: AlignmentWorkspace,
    video_id: str,
    *,
    sidecar_raw: bytes,
    duration_ms: int,
    editorial_sha256: str | None,
) -> dict[int, int]:
    path = workspace.reviewed_lrc_path(video_id)
    if editorial_sha256 is None:
        return {}
    if not path.is_file():
        raise ValueError("reviewed LRC evidence is missing")
    value = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=path.parent)
    if (
        not isinstance(value, dict)
        or set(value) != {
            "evidenceVersion",
            "videoId",
            "editorialSha256",
            "sidecarSha256",
            "sourceFiles",
            "starts",
        }
        or value.get("evidenceVersion") != 1
        or value.get("videoId") != video_id
        or value.get("editorialSha256") != editorial_sha256
        or value.get("sidecarSha256") != hashlib.sha256(sidecar_raw).hexdigest()
        or not isinstance(value.get("sourceFiles"), list)
        or any(
            not isinstance(name, str)
            or name
            not in {
                "original.lrc",
                "translation-en.lrc",
                "transliteration-ipa.lrc",
            }
            for name in value.get("sourceFiles", [])
        )
        or len(value.get("sourceFiles", [])) != len(set(value.get("sourceFiles", [])))
        or not isinstance(value.get("starts"), list)
    ):
        raise ValueError("reviewed LRC evidence identity is invalid")
    result: dict[int, int] = {}
    for expected_index, record in enumerate(value["starts"]):
        if (
            not isinstance(record, dict)
            or set(record) != {"sourcePhysicalIndex", "startMs"}
            or record.get("sourcePhysicalIndex") != expected_index
            or type(record.get("startMs")) is not int
            or not 0 <= record["startMs"] < duration_ms
        ):
            raise ValueError("reviewed LRC start evidence is malformed")
        result[expected_index] = record["startMs"]
    if bool(result) is not bool(value["sourceFiles"]):
        raise ValueError("reviewed LRC evidence source list is inconsistent")
    return result


def _package_version(name: str) -> str:
    try:
        from importlib.metadata import version

        return version(name)
    except Exception:
        return "unknown"


def _model_snapshot_identity(model_name: str, model_cache: Path) -> str:
    if Path(model_name).is_dir():
        return str(Path(model_name).resolve())
    candidates = sorted(
        path.name
        for root in model_cache.glob("models--*/snapshots")
        for path in root.iterdir()
        if path.is_dir() and model_name.casefold() in str(root.parent).casefold()
    )
    return candidates[-1] if candidates else f"unresolved:{model_name}"


def _whisper_decoder_config(*, precision: bool, prompted: bool) -> dict[str, Any]:
    value: dict[str, Any] = {
        "beamSize": 10 if precision else 5,
        "wordTimestamps": True,
        "conditionOnPreviousText": precision and prompted,
        "vadFilter": False,
        "temperature": 0.0,
        "promptMode": "approved-deduplicated" if prompted else "withheld",
    }
    if precision:
        value.update(patience=1.5, repetitionPenalty=1.05, hotwords=False)
    return value


def _whisper_cache_provenance(
    *,
    audio: dict[str, Any],
    duration_ms: int,
    model_name: str,
    model_cache: Path,
    device: str,
    compute_type: str,
    precision: bool,
    prompted: bool,
) -> dict[str, Any]:
    from stemwise_engine.worker_runtime_probe import ctranslate2_runtime_identity
    return {
        "audio": audio,
        "durationMs": duration_ms,
        "fasterWhisperVersion": _package_version("faster-whisper"),
        "modelSnapshot": (
            PINNED_WHISPER_SNAPSHOT
            if model_name == DEFAULT_MODEL
            else _model_snapshot_identity(model_name, model_cache)
        ),
        "modelSha256": PINNED_WHISPER_SHA256 if model_name == DEFAULT_MODEL else None,
        "modelManifestSha256": (
            _whisper_manifest_sha256() if model_name == DEFAULT_MODEL else None
        ),
        "device": device,
        "producerRuntime": ctranslate2_runtime_identity(device),
        "computeType": compute_type,
        "decoder": _whisper_decoder_config(precision=precision, prompted=prompted),
    }


def _whisper_recognition_cache_identity() -> dict[str, Any]:
    """Bind recognition code; actual audio and prompt are bound separately."""
    import syncalong.textnorm as syncalong_textnorm
    source = Path(__file__).resolve().parent
    helpers = {}
    for filename, names in (
        ("lyrics_alignment.py", ("FasterWhisperEvidence", "transcript_to_json", "transcript_from_json")),
        ("lyrics_align_pc.py", ("_transcribe_with_language_fallback", "_whisper_decoder_config",
                               "_whisper_cache_provenance", "_load_or_transcribe", "_load_or_transcribe_roformer")),
    ):
        for name in names:
            helpers[name] = semantic_source_identity(source / filename, (name,))
    return {
        "recognitionCacheVersion": 1,
        "helpersSha256": helpers,
        "normalizationSha256": semantic_source_identity(Path(syncalong_textnorm.__file__)),
        "transcriptTypesSha256": semantic_source_identity(Path(sys.modules[WordTimestamp.__module__].__file__)),
        "runtimeVersions": {name: _package_version(name)
                            for name in ("syncalong", "ctranslate2", "av", "tokenizers")},
    }


def _load_or_transcribe(
    *,
    video_id: str,
    original: dict[str, Any],
    workspace: AlignmentWorkspace,
    model_name: str,
    model_cache: Path,
    language: str | None,
    separate_vocals: bool,
    project_dir: Path,
    device: str,
    compute_type: str,
    duration_ms: int,
    get_model: Callable[[], FasterWhisperEvidence],
    temp_root: Path,
    timeout_seconds: int,
    rerun: bool,
    precision: bool = False,
    use_approved_prompt: bool = True,
    expected_mix_sha256: str | None = None,
    analysis_audio: Path | None = None,
    vocals_provider: Callable[[], Path] | None = None,
) -> tuple[list[Any], dict[str, Any], bool]:
    # Prompted ASR is useful for uncommon names and languages, but it is not an
    # independent check: Whisper can echo prompted words in the wrong musical
    # passage.  The final fallback deliberately withholds the approved text so
    # a prompted-only match cannot manufacture release evidence.
    prompt = (
        deduplicated_lyrics_prompt(original["text"], max_chars=600)
        if use_approved_prompt
        else ""
    )
    import hashlib

    prompt_sha256 = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
    cache_identity = _whisper_recognition_cache_identity()
    evidence_source = (
        "precision-unprompted-vocals"
        if separate_vocals and precision and not use_approved_prompt
        else "precision-vocals"
        if separate_vocals and precision
        else "unprompted-vocals"
        if separate_vocals and not use_approved_prompt
        else "vocals"
        if separate_vocals
        else "precision-unprompted-mix"
        if precision and not use_approved_prompt
        else "unprompted-mix"
        if not use_approved_prompt
        else "precision-mix"
        if precision
        else "mix"
    )
    cache_model = (
        f"{model_name}+htdemucs+precision-unprompted"
        if separate_vocals and precision and not use_approved_prompt
        else f"{model_name}+htdemucs+precision"
        if separate_vocals and precision
        else f"{model_name}+htdemucs+unprompted"
        if separate_vocals and not use_approved_prompt
        else f"{model_name}+htdemucs"
        if separate_vocals
        else f"{model_name}+precision-unprompted"
        if precision and not use_approved_prompt
        else f"{model_name}+unprompted"
        if not use_approved_prompt
        else f"{model_name}+precision"
        if precision
        else model_name
    )
    transcript_path = workspace.transcript_path(video_id, language, evidence_source)
    cache_candidates = [transcript_path]
    audio_provenance = audio_store.read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    )
    audio_kind = "htdemucs-vocals" if separate_vocals else "mix"
    cached_audio_identity = (
        audio_provenance.get("audioViews", {}).get(audio_kind)
        if separate_vocals
        and isinstance(audio_provenance, dict)
        and isinstance(audio_provenance.get("audioViews"), dict)
        else audio_provenance.get("mix")
        if isinstance(audio_provenance, dict)
        and isinstance(audio_provenance.get("mix"), dict)
        else None
    )
    demucs_model = (
        model_cache.parent
        / "torch"
        / "hub"
        / "checkpoints"
        / "955717e8-8726e21a.th"
    )
    current_demucs_sha256 = (
        _sha256_file(demucs_model) if separate_vocals and demucs_model.is_file() else None
    )
    if separate_vocals and (
        not isinstance(cached_audio_identity, dict)
        or cached_audio_identity.get("demucsModelSha256") != current_demucs_sha256
    ):
        cached_audio_identity = None
    expected_provenance = (
        _whisper_cache_provenance(
            audio=cached_audio_identity,
            duration_ms=duration_ms,
            model_name=model_name,
            model_cache=model_cache,
            device=device,
            compute_type=compute_type,
            precision=precision,
            prompted=use_approved_prompt,
        )
        if cached_audio_identity is not None
        else None
    )
    if not rerun and expected_provenance is not None:
        for cache_candidate in cache_candidates:
            if not cache_candidate.is_file():
                continue
            cached = _read_optional_cache_json(cache_candidate)
            if cached is not None and transcript_cache_matches(
                cached,
                video_id=video_id,
                model=cache_model,
                language=language,
                prompt_sha256=prompt_sha256,
                provenance=expected_provenance,
                cache_identity=cache_identity,
            ):
                try:
                    words = transcript_from_json(cached)
                except (AttributeError, KeyError, TypeError, ValueError):
                    continue
                return words, cached.get("evidence", {}), True

    temp_root.mkdir(parents=True, exist_ok=True)
    with WorkerTemporaryDirectory(prefix=f"lyrics-{video_id}-", dir=temp_root) as name:
        if analysis_audio is None:
            _, audio = download_analysis_audio(
                video_id,
                Path(name),
                timeout_seconds=timeout_seconds,
            )
        else:
            audio = analysis_audio
        evidence_audio = audio
        provenance = audio_store.write_audio_provenance(
            workspace,
            video_id,
            duration_ms=duration_ms,
            mix_audio=audio,
            expected_mix_sha256=expected_mix_sha256,
        )
        if separate_vocals:
            if current_demucs_sha256 is None:
                raise RuntimeError("pinned htdemucs model is missing")
            evidence_audio = vocals_provider() if vocals_provider is not None else separate_vocals_demucs(
                audio, Path(name) / "demucs", project_dir=project_dir,
                device=device, timeout_seconds=timeout_seconds)
            provenance = audio_store.write_audio_provenance(
                workspace,
                video_id,
                duration_ms=duration_ms,
                mix_audio=audio,
                vocals_audio=evidence_audio,
                demucs_model_sha256=current_demucs_sha256,
                expected_mix_sha256=expected_mix_sha256,
            )
        words, evidence = _transcribe_with_language_fallback(
            get_model(),
            evidence_audio,
            language=language,
            prompt=prompt,
            precision=precision,
        )
        evidence["promptMode"] = (
            "approved-deduplicated" if use_approved_prompt else "withheld"
        )
        evidence["vocalIsolation"] = "htdemucs" if separate_vocals else "none"
    audio_identity = (
        provenance["audioViews"][audio_kind]
        if separate_vocals
        else provenance["mix"]
    )
    cache_provenance = _whisper_cache_provenance(
        audio=audio_identity,
        duration_ms=duration_ms,
        model_name=model_name,
        model_cache=model_cache,
        device=device,
        compute_type=compute_type,
        precision=precision,
        prompted=use_approved_prompt,
    )
    payload = transcript_to_json(
        video_id,
        words,
        model=cache_model,
        language=language,
        prompt_sha256=prompt_sha256,
        evidence=evidence,
        provenance=cache_provenance,
        cache_identity=cache_identity,
    )
    atomic_write_json(
        transcript_path,
        payload,
        root=workspace.job_dir(video_id),
        workspace_root=workspace.workspace_root,
    )
    return words, evidence, False


def _cache_audio_identity(identity: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in identity.items() if key != "stageMetrics"}


def _worker_pcm_duration_ms(identity: dict[str, Any]) -> int:
    stream = identity.get("stream")
    if not isinstance(stream, dict):
        raise ValueError("alignment audio identity has no stream")
    source_rate = stream.get("sampleRate")
    source_frames = stream.get("frameCount")
    if (
        type(source_rate) is not int
        or source_rate <= 0
        or type(source_frames) is not int
        or source_frames <= 0
    ):
        raise ValueError("alignment audio stream is malformed")
    from stemwise_engine.acoustic_inputs import resampled_sample_count
    target_frames = resampled_sample_count(source_frames, source_rate, 16_000)
    return (target_frames * 1000 + 8_000) // 16_000


def _endpoint_request_timing(
    identity: dict[str, Any], canonical_duration_ms: int,
    segments: list[dict[str, int]] | None,
) -> tuple[int, list[dict[str, int]] | None]:
    """Use the bound view's exact length, preserving all interior time cuts."""
    native_duration_ms = _worker_pcm_duration_ms(identity)
    if abs(native_duration_ms - canonical_duration_ms) > ctc_worker.EOF_ROUNDING_MS:
        raise ValueError("endpoint audio duration differs materially from the request")
    if segments:
        endpoints._validate_forced_alignment_segments(
            segments, segments[-1]["endLineIndex"], canonical_duration_ms
        )
        segments = [
            {**segment, "windowEndMs": native_duration_ms}
            if segment["windowEndMs"] == canonical_duration_ms else dict(segment)
            for segment in segments
        ]
        endpoints._validate_forced_alignment_segments(
            segments, segments[-1]["endLineIndex"], native_duration_ms
        )
    return native_duration_ms, segments


def _project_endpoint_segment_eof(result: dict[str, Any], duration_ms: int) -> list[dict[str, int]]:
    """Return the worker's plan in the requested timeline without changing interior cuts."""
    if abs(result["durationMs"] - duration_ms) > ctc_worker.EOF_ROUNDING_MS:
        raise ValueError("endpoint result has a material duration mismatch")
    if result["durationMs"] == duration_ms:
        return result["resolvedSegments"]
    segments = [{**segment, "windowEndMs": duration_ms}
                if segment["windowEndMs"] == result["durationMs"] else dict(segment)
                for segment in result["resolvedSegments"]]
    endpoints._validate_forced_alignment_segments(segments, len(result["lines"]), duration_ms)
    return segments


def _project_endpoint_eof(
    result: dict[str, Any], duration_ms: int, *, request_duration_ms: int | None = None,
) -> dict[str, Any]:
    """Clip only permitted EOF rounding spill; retain the raw native cache."""
    if request_duration_ms is not None:
        if abs(result["durationMs"] - request_duration_ms) > ctc_worker.EOF_ROUNDING_MS:
            raise ValueError("endpoint result has a material duration mismatch")
        # Keep native endpoints already valid in public time independent of the request plan.
        if abs(result["durationMs"] - duration_ms) <= ctc_worker.EOF_ROUNDING_MS:
            return _project_endpoint_eof(result, duration_ms)
        if abs(request_duration_ms - duration_ms) > ctc_worker.EOF_ROUNDING_MS:
            raise ValueError("endpoint result has a material duration mismatch")
        requested = _project_endpoint_eof(result, request_duration_ms)
        projected = _project_endpoint_eof({**requested, "durationMs": request_duration_ms}, duration_ms)
        return {**projected, "durationMs": result["durationMs"]}
    if abs(result["durationMs"] - duration_ms) > ctc_worker.EOF_ROUNDING_MS:
        raise ValueError("endpoint result has a material duration mismatch")
    if "resolvedSegments" in result and result["durationMs"] != duration_ms:
        result = {**result, "resolvedSegments": _project_endpoint_segment_eof(result, duration_ms)}
    if result["durationMs"] <= duration_ms:
        return result
    lines = []
    for line in result["lines"]:
        words = [{**word, "endMs": ctc_worker.project_eof_end(word['startMs'], word['endMs'], duration_ms)}
                 for word in line["words"]]
        projected = {**line, "endMs": ctc_worker.project_eof_end(line['startMs'], line['endMs'], duration_ms), "words": words}
        lines.append(projected)
    return {**result, "lines": lines}


def _load_or_transcribe_roformer(
    *,
    video_id: str,
    original: dict[str, Any],
    route_id: str,
    workspace: AlignmentWorkspace,
    model_name: str,
    model_cache: Path,
    language: str | None,
    device: str,
    compute_type: str,
    duration_ms: int,
    get_model: Callable[[], FasterWhisperEvidence],
    get_audio: Callable[[], tuple[Path, dict[str, Any], dict[str, Any]]],
    rerun: bool,
    expected_mix_sha256: str,
) -> tuple[list[Any], dict[str, Any], bool]:
    prompt = ""
    prompt_sha256 = hashlib.sha256(b"").hexdigest()
    cache_identity = _whisper_recognition_cache_identity()
    source = (
        "roformer-kim-vocals"
        if route_id == "kimberley-jensen-roformer-vocals"
        else "roformer-sucial-dereverb-v2"
    )
    cache_model = f"{model_name}+{source}+precision-unprompted"
    transcript_path = workspace.transcript_path(video_id, language, source)
    stored = audio_store.read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    )
    stored_identity = (
        stored.get("audioViews", {}).get(route_id)
        if isinstance(stored, dict) and isinstance(stored.get("audioViews"), dict)
        else None
    )
    expected_provenance = (
        _whisper_cache_provenance(
            audio=_cache_audio_identity(stored_identity),
            duration_ms=duration_ms,
            model_name=model_name,
            model_cache=model_cache,
            device=device,
            compute_type=compute_type,
            precision=True,
            prompted=False,
        )
        if isinstance(stored_identity, dict)
        else None
    )
    if not rerun and expected_provenance is not None and transcript_path.is_file():
        cached = _read_optional_cache_json(transcript_path)
        if cached is not None and transcript_cache_matches(
            cached,
            video_id=video_id,
            model=cache_model,
            language=language,
            prompt_sha256=prompt_sha256,
            provenance=expected_provenance,
            cache_identity=cache_identity,
        ):
            try:
                words = transcript_from_json(cached)
            except (AttributeError, KeyError, TypeError, ValueError):
                pass
            else:
                return words, cached.get("evidence", {}), True

    audio, identity, _ = get_audio()
    words, evidence = _transcribe_with_language_fallback(
        get_model(), audio, language=language, prompt=prompt, precision=True
    )
    evidence = dict(evidence)
    evidence.update(
        {
            "promptMode": "withheld",
            "vocalIsolation": route_id,
            "separatorFamilyId": endpoints.ROFORMER_ROUTES[route_id]["familyId"],
        }
    )
    cache_provenance = _whisper_cache_provenance(
        audio=_cache_audio_identity(identity),
        duration_ms=duration_ms,
        model_name=model_name,
        model_cache=model_cache,
        device=device,
        compute_type=compute_type,
        precision=True,
        prompted=False,
    )
    payload = transcript_to_json(
        video_id,
        words,
        model=cache_model,
        language=language,
        prompt_sha256=prompt_sha256,
        evidence=evidence,
        provenance=cache_provenance,
        cache_identity=cache_identity,
    )
    atomic_write_json(
        transcript_path,
        payload,
        root=workspace.job_dir(video_id),
        workspace_root=workspace.workspace_root,
    )
    return words, evidence, False


def _load_caption(
    *,
    video_id: str,
    original: dict[str, Any],
    workspace: AlignmentWorkspace,
    temp_root: Path,
    record_outcome: Callable[[str, bool | None, str | None], None] | None = None,
) -> tuple[list[Any], dict[str, Any], bool] | None:
    cache_identity = _current_cache_identity(original)
    language = next(
        (value for value in transcription_languages(original) if value is not None),
        None,
    )
    cache_path = workspace.caption_path(video_id)
    cached = _read_optional_cache_json(cache_path)
    if cached is not None:
        if (
            set(cached)
            == {
                "cacheVersion",
                "videoId",
                "language",
                "cacheIdentity",
                "checkedAtUnix",
                "unavailable",
                "words",
            }
            and cached.get("words") == []
            and cached.get("cacheVersion") == TRANSCRIPT_CACHE_VERSION
            and cached.get("videoId") == video_id
            and cached.get("language") == language
            and _caption_cache_identity_matches(cached.get("cacheIdentity"), cache_identity)
            and cached.get("unavailable") is True
            and type(cached.get("checkedAtUnix")) is int
            and 0 <= int(time.time()) - cached["checkedAtUnix"]
            <= CAPTION_NEGATIVE_CACHE_TTL_SECONDS
        ):
            if record_outcome is not None:
                record_outcome("unavailable", True, None)
            return None
        evidence = cached.get("evidence")
        provenance = cached.get("provenance")
        if (
            isinstance(evidence, dict)
            and set(evidence)
            == {"type", "captionKind", "captionLanguage", "wordCount"}
            and evidence.get("type") == "youtube-caption"
            and evidence.get("captionKind") in {"manual", "automatic"}
            and isinstance(evidence.get("captionLanguage"), str)
            and type(evidence.get("wordCount")) is int
            and evidence["wordCount"] > 0
            and provenance
            == {
                "source": "youtube-caption",
                "captionKind": evidence["captionKind"],
                "captionLanguage": evidence["captionLanguage"],
            }
            and transcript_cache_matches(
                cached,
                video_id=video_id,
                model="youtube-caption",
                language=language,
                prompt_sha256="not-applicable",
                provenance=provenance,
                cache_identity=cache_identity,
            )
        ):
            try:
                words = transcript_from_json(cached)
            except (AttributeError, KeyError, TypeError, ValueError):
                pass
            else:
                if len(words) == evidence["wordCount"]:
                    if record_outcome is not None:
                        record_outcome("success", True, None)
                    return words, evidence, True
    temp_root.mkdir(parents=True, exist_ok=True)
    with WorkerTemporaryDirectory(prefix=f"caption-{video_id}-", dir=temp_root) as name:
        try:
            result = download_youtube_caption_evidence(
                video_id,
                Path(name),
                language=language,
            )
        except WorkerRetirementError:
            raise
        except Exception as error:
            if (isinstance(error, ProviderCancelled) or
                    isinstance(error, ProviderDeadline) and not isinstance(error, ProviderStageDeadline)):
                raise
            # Captions are an optional first-choice evidence source.  A network
            # throttle, provider error, or malformed caption must never prevent
            # the pinned local audio fallbacks from running.  Do not cache this
            # transient failure as a permanent no-caption result.
            if record_outcome is not None:
                record_outcome(
                    "unavailable" if isinstance(error, ProviderStageDeadline) else "transient-failure",
                    False,
                    worker_protocol.technical_diagnostic_code(error),
                )
            return None
    if result is None:
        atomic_write_json(
            cache_path,
            {
                "cacheVersion": TRANSCRIPT_CACHE_VERSION,
                "videoId": video_id,
                "language": language,
                "cacheIdentity": cache_identity,
                "checkedAtUnix": int(time.time()),
                "unavailable": True,
                "words": [],
            },
            root=workspace.job_dir(video_id),
            workspace_root=workspace.workspace_root,
        )
        if record_outcome is not None:
            record_outcome("unavailable", False, None)
        return None
    words, evidence = result
    payload = transcript_to_json(
        video_id,
        words,
        model="youtube-caption",
        language=language,
        prompt_sha256="not-applicable",
        evidence=evidence,
        provenance={
            "source": "youtube-caption",
            "captionKind": evidence.get("captionKind"),
            "captionLanguage": evidence.get("captionLanguage"),
        },
        cache_identity=cache_identity,
    )
    atomic_write_json(
        cache_path,
        payload,
        root=workspace.job_dir(video_id),
        workspace_root=workspace.workspace_root,
    )
    if record_outcome is not None:
        record_outcome("success", False, None)
    return words, evidence, False


def _qwen_structure_languages(original: dict[str, Any], supported: Sequence[str]) -> tuple[str, ...]:
    routes = tuple(language or "auto" for language in transcription_languages(original)
                   if language is None or language in supported)
    return routes or ("auto",)


def _score_qwen_structure_routes(original, sequences, languages, transcribe, *, required_sequence=None,
                                 eligible_sequences=None, admission=None, workspace=None, video_id=None,
                                 evidence_path=None):
    def evaluate(language):
        transcript, provenance, reused = transcribe(None if language == "auto" else language)
        baseline = _baseline_source_indices(original["text"])
        if admission is None:
            require_exact_sequence(original, baseline, transcript, None, None)
        else:
            lyric_content.require_admitted_sequence(admission, original, baseline, transcript,
                evidence_path=evidence_path,
                evidence_root=workspace.job_dir(video_id),
                adjudication_job_dir=workspace.root.parent / 'jobs' / video_id)
        if (any(tuple(sources) != baseline for sources in sequences)
                or required_sequence is not None and tuple(required_sequence) != baseline):
            raise LyricsContentBlocker("content-occurrence-uncertain")
        from stemwise_engine.spoken_targets import attached
        spoken = attached(original)
        scores = [score_structure_witness(spoken.matching_text() if spoken else original["text"], sources, transcript)
                  for sources in sorted(sequences)]
        if not scores or any(not score.accepted for score in scores):
            raise LyricsContentBlocker("content-support-uncertain")
        # An unpublishable omission cannot stop supported recovery routes.
        # Keep every rival scored; native recovery fixes its own sequence.
        return any(score.accepted and (required_sequence is None or
                   score.source_physical_indices == required_sequence) and
                   (eligible_sequences is None or score.source_physical_indices in eligible_sequences)
                   for score in scores), (scores, provenance, reused)
    return _run_conditional_cascade(languages, evaluate)[1]






def _needs_additional_structural_evidence(
    options: list[tuple[str, AlignmentResult, dict[str, Any], bool]],
    *,
    text: str,
    repeat_structure_kind: str,
) -> bool:
    """Continue past an early accept whenever structure is not yet settled."""

    return (
        not any(item[1].accepted for item in options)
        or repeat_structure_kind != "none"
        or any(
            item[1].accepted and _has_structural_mutation(text, item[1])
            for item in options
        )
    )


def _ctc_cache_helpers_match(stored: dict[str, str], current: dict[str, str]) -> bool:
    return bool(current) and stored == current


def _mms_cache_matches(
    value: dict[str, Any],
    *,
    video_id: str,
    variant: str,
    acoustic_track_kind: str,
    request_sha256: str,
    helper_sha256: str,
    provenance: dict[str, Any],
    mapping_identity: dict[str, Any],
) -> bool:
    return (
        value.get("cacheVersion") == MMS_CACHE_VERSION
        and value.get("videoId") == video_id
        and value.get("variant") == variant
        and value.get("acousticTrackKind") == acoustic_track_kind
        and value.get("requestSha256") == request_sha256
        and _ctc_cache_helpers_match({"mms_forced_alignment.py": value.get("helperSha256")},
                                    {"mms_forced_alignment.py": helper_sha256})
        and value.get("provenance") == provenance
        and value.get("mappingIdentity") == mapping_identity
        and value.get("engine") == "torchaudio-mms-fa"
        and isinstance(value.get("lines"), list)
        and isinstance(value.get("confidence"), dict)
    )


def _runtime_probe_cache_key(
    python: Path, helper: Path, *, device: str,
    expected: dict[str, Any], helper_hashes: dict[str, str],
    environment: dict[str, str] | None = None,
) -> tuple[Any, ...]:
    interpreter = python.resolve(strict=True)
    stat = interpreter.stat()
    return (
        str(python.absolute()), str(interpreter), stat.st_dev, stat.st_ino,
        stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns,
        str(helper.resolve(strict=True)), tuple(sorted(helper_hashes.items())),
        json.dumps(expected, sort_keys=True, separators=(",", ":")), device,
        tuple(sorted((environment if environment is not None else os.environ).items())),
    )


def _run_numerical_process(args: list[str], *, builder, timeout, **kwargs):
    with environment_lease('main', builder=builder):
        if _alignment_sessions.active_alignment_session() is None:
            return run_process_tree(args, timeout=timeout, **kwargs)
        return _alignment_sessions.run_alignment_process(args, timeout=timeout, **kwargs)


def _mms_runtime_provenance(
    mms_python: Path,
    helper: Path,
    *,
    device: str,
    timeout_seconds: int,
    attempt_cache: dict[tuple[Any, ...], dict[str, Any]] | None = None,
    diagnostic_path: Path | None = None,
) -> dict[str, Any]:
    expected = models.mms_runtime_pins(helper.parent.parent)
    cache_key = None
    if attempt_cache is not None:
        cache_key = _runtime_probe_cache_key(
            mms_python, helper, device=device, expected=expected,
            helper_hashes={helper.name: ctc_worker.helper_source_identity(helper)},
        )
        if cache_key in attempt_cache:
            return dict(attempt_cache[cache_key])
    completed = _run_numerical_process(
        [str(mms_python), "-m", "stemwise_engine." + helper.stem, "--runtime-info", "--device", device],
        builder=helper.parent.parent,
        text=True,
        capture_output=True,
        check=False,
        timeout=min(timeout_seconds, 300),
        env=os.environ.copy(),
    )
    worker_protocol.raise_if_worker_stopped(completed, code='MMS runtime identity',
        diagnostic_path=diagnostic_path or helper.parent.parent/'mms-runtime.failure.private.txt')
    if completed.returncode != 0:
        failure = diagnostic_path or (helper.resolve().parent.parent.parent.parent /
            'Engine telemetry/runtime-probes' / f'{helper.stem}.{device}.failure.private.txt')
        worker_protocol.raise_worker_failure('MMS runtime identity', completed, failure, diagnostic_path=failure)
    try:
        value = parse_worker_json(completed.stdout)
    except ValueError as error:
        raise RuntimeError("MMS runtime identity is malformed") from error
    if (
        not isinstance(value, dict)
        or value.get("runtimeVersion") != 1
        or value.get("bundle") != "torchaudio.pipelines.MMS_FA"
        or value.get("requestedDevice") != device
        or value.get("supportedRequestVersions") != [MMS_REQUEST_VERSION]
        or any(value.get(name) != version for name, version in expected.items())
        or (device != "auto" and value.get("resolvedDevice") != device)
        or (device == "cuda" and value.get("cudaAvailable") is not True)
    ):
        raise RuntimeError("MMS runtime differs from exact dependency pins")
    if attempt_cache is not None:
        attempt_cache[cache_key] = dict(value)
    return value


def _wav2vec2_runtime_provenance(
    python: Path,
    helper: Path,
    *,
    device: str,
    timeout_seconds: int,
    attempt_cache: dict[tuple[Any, ...], dict[str, Any]] | None = None,
    diagnostic_path: Path | None = None,
) -> dict[str, Any]:
    expected = models.wav2vec2_runtime_pins(helper.parent.parent)
    cache_key = None
    if attempt_cache is not None:
        cache_key = _runtime_probe_cache_key(
            python, helper, device=device, expected=expected,
            helper_hashes=_wav2vec2_helper_hashes(helper.parent.parent),
        )
        if cache_key in attempt_cache:
            return dict(attempt_cache[cache_key])
    completed = _run_numerical_process(
        [str(python), "-m", "stemwise_engine." + helper.stem, "--runtime-info", "--device", device],
        builder=helper.parent.parent,
        text=True,
        capture_output=True,
        check=False,
        timeout=min(timeout_seconds, 300),
        env=os.environ.copy(),
    )
    if completed.returncode != 0:
        failure = diagnostic_path or (helper.resolve().parent.parent.parent.parent /
            'Engine telemetry/runtime-probes' / f'{helper.stem}.{device}.failure.private.txt')
        worker_protocol.raise_worker_failure('Wav2Vec2 runtime identity', completed, failure, diagnostic_path=failure)
    try:
        value = parse_worker_json(completed.stdout)
    except ValueError as error:
        raise RuntimeError("Wav2Vec2 runtime identity is malformed") from error
    if (
        not isinstance(value, dict)
        or value.get("runtimeVersion") != 3
        or value.get("engine") != "wav2vec2-ctc-forced-alignment"
        or value.get("chunkSeconds") != 30
        or value.get("strideSeconds") != 5
        or value.get("retainedCoreSeconds") != 20
        or value.get("emissionMap") != "shared-global-overlap-trimmed"
        or value.get("supportedRequestVersions") != [MMS_REQUEST_VERSION]
        or any(value.get(name) != version for name, version in expected.items())
        or re.fullmatch(r"\d+\.\d+\.\d+", str(value.get("espeak") or "")) is None
        or (device != "auto" and value.get("resolvedDevice") != device)
        or (device == "cuda" and value.get("cudaAvailable") is not True)
    ):
        raise RuntimeError("Wav2Vec2 runtime differs from exact dependency pins")
    if attempt_cache is not None:
        attempt_cache[cache_key] = dict(value)
    return value


def _qwen3_asr_snapshot(model_cache: Path, *, attempt_cache: dict | None = None) -> Path:
    snapshot = (
        model_cache
        / "models--Qwen--Qwen3-ASR-1.7B-hf"
        / "snapshots"
        / QWEN3_ASR_REVISION
    )
    if not snapshot.is_dir():
        raise RuntimeError("pinned Qwen3-ASR snapshot is missing")
    file_identity = tuple((path.relative_to(snapshot).as_posix(), str(path.resolve(strict=True)),
                           stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns)
                          for path in sorted(snapshot.rglob('*')) if path.is_file()
                          for stat in [path.stat()])
    cache_key = ('qwen3-snapshot', str(snapshot.resolve()), file_identity)
    if attempt_cache is None or cache_key not in attempt_cache:
        if _qwen3_asr_snapshot_sha256(snapshot) != QWEN3_ASR_SNAPSHOT_SHA256:
            raise RuntimeError('pinned Qwen3-ASR snapshot failed its SHA-256 identity check')
        if attempt_cache is not None:
            attempt_cache[cache_key] = QWEN3_ASR_SNAPSHOT_SHA256
    return snapshot


def _qwen3_asr_runtime_provenance(
    python: Path,
    helper: Path,
    *,
    device: str,
    timeout_seconds: int,
) -> dict[str, Any]:
    with environment_lease('main', builder=helper.parent.parent):
        completed = run_process_tree(
            [str(python), "-m", "stemwise_engine." + helper.stem, "--runtime-info", "--device", device],
            text=True,
            capture_output=True,
            check=False,
            timeout=min(timeout_seconds, 300),
            env=models.roformer_offline_env(helper.parent.parent.parent),
        )
    if completed.returncode != 0:
        raise RuntimeError("Qwen3-ASR runtime identity could not be established")
    try:
        value = parse_worker_json(completed.stdout)
    except ValueError as error:
        raise RuntimeError("Qwen3-ASR runtime identity is malformed") from error
    runtime = value.get("runtime") if isinstance(value, dict) else None
    project = helper.parent.parent
    expected = {
        "python": models.qualified_python_version(project),
        "torch": runtime_profile('main')['packages']['torch'],
        "transformers": runtime_profile('main')['packages']['transformers'],
    }
    if (
        not isinstance(value, dict)
        or set(value) != {"workerFormatVersion", "engine", "runtime", "decoder", "supportedLanguages"}
        or value.get("workerFormatVersion") != QWEN3_ASR_FORMAT_VERSION
        or value.get("engine") != QWEN3_ASR_ENGINE
        or value.get("decoder") != QWEN3_ASR_DECODER
        or not isinstance(value.get("supportedLanguages"), list)
        or not value["supportedLanguages"]
        or any(not isinstance(language, str) or re.fullmatch(r"[a-z]{2,3}", language) is None
               for language in value["supportedLanguages"])
        or value["supportedLanguages"] != sorted(set(value["supportedLanguages"]))
        or not isinstance(runtime, dict)
        or runtime.get("runtimeVersion") != 1
        or any(runtime.get(name) != version for name, version in expected.items())
        or runtime.get("requestedDevice") != device
        or type(runtime.get("cudaAvailable")) is not bool
        or (
            device == "auto"
            and runtime.get("resolvedDevice")
            != ("cuda" if runtime.get("cudaAvailable") is True else "cpu")
        )
        or (device != "auto" and runtime.get("resolvedDevice") != device)
        or (device == "cuda" and runtime.get("cudaAvailable") is not True)
    ):
        raise RuntimeError("Qwen3-ASR runtime differs from exact dependency pins")
    return value


def _load_or_run_qwen3_structure_transcript(
    *,
    config: dict[str, Any],
    video_id: str,
    language: str | None,
    audio: Path,
    audio_identity: dict[str, Any],
    model_cache: Path,
    workspace: AlignmentWorkspace,
    temp_dir: Path,
    device: str,
    timeout_seconds: int,
    before_run: Callable[[], None] | None = None,
    runtime_report: dict[str, Any] | None = None,
    separate_language_cache: bool = False,
    audio_view: str = "mix",
    cache_only: bool = False,
    attempt_cache: dict | None = None,
) -> tuple[str, dict[str, Any], bool]:
    if audio_view not in QWEN3_ASR_SOURCE_ROUTES:
        raise ValueError("unsupported Qwen3-ASR audio view")
    project = Path(config["projectDir"])
    language = canonical_language(language)
    python = environment_python('main', project)
    helper = project / "src" / "qwen3_asr_transcribe.py"
    for required in (python, helper) + (() if cache_only else (audio,)):
        if not required.is_file():
            raise RuntimeError(f"pinned Qwen3-ASR dependency is missing: {required.name}")
    model = _qwen3_asr_snapshot(model_cache, attempt_cache=attempt_cache)
    if not cache_only:
        supplied_audio = audio_store.file_identity(audio)
        if any(
            supplied_audio[key] != audio_identity.get(key)
            for key in ("sha256", "byteCount", "stream")
        ):
            raise ValueError("Qwen3-ASR audio differs from its bound identity")
    runtime = runtime_report or _qwen3_asr_runtime_provenance(
        python, helper, device=device, timeout_seconds=timeout_seconds
    )
    if language not in runtime["supportedLanguages"]:
        language = None
    request = _validate_qwen3_asr_request(
        {
            "formatVersion": QWEN3_ASR_FORMAT_VERSION,
            "audio": {
                "path": str(audio),
                "sha256": str(audio_identity["sha256"]),
                "byteCount": int(audio_identity["byteCount"]),
            },
            "language": language,
            "model": {
                "path": str(model),
                "revision": QWEN3_ASR_REVISION,
                "snapshotSha256": QWEN3_ASR_SNAPSHOT_SHA256,
            },
        }
    )
    helper_sha256 = semantic_source_identity(helper)
    provenance = {
        "audio": _cache_audio_identity(audio_identity),
        "language": language,
        "modelRevision": QWEN3_ASR_REVISION,
        "modelSnapshotSha256": QWEN3_ASR_SNAPSHOT_SHA256,
        "helperSha256": helper_sha256,
        "runtime": runtime,
        "decoder": QWEN3_ASR_DECODER,
    }
    cache_name = (f"qwen-structure-{language}.private.json"
                  if separate_language_cache and language else "qwen-structure.private.json")
    if audio_view != "mix":
        provenance["audioView"] = audio_view
        cache_name = f"qwen-structure-{audio_view}-{language or 'auto'}.private.json"
    cache_path = workspace.job_dir(video_id) / cache_name
    cached = _read_optional_cache_json(cache_path)
    if (
        cached is not None
        and set(cached)
        == {"cacheVersion", "videoId", "provenance", "workerOutput"}
        and cached.get("cacheVersion") == QWEN3_ASR_CACHE_VERSION
        and cached.get("videoId") == video_id
        and cached.get("provenance") == provenance
        and isinstance(cached.get("workerOutput"), dict)
    ):
        try:
            worker_output = _validate_qwen3_asr_output(
                cached["workerOutput"], request
            )
        except (KeyError, TypeError, ValueError):
            pass
        else:
            return worker_output["result"]["transcription"], provenance, True
    if cache_only:
        raise FileNotFoundError("No current bound Qwen3-ASR transcript cache")
    if before_run is not None:
        before_run()
    temp_dir.mkdir(parents=True, exist_ok=True)
    request_path = temp_dir / "qwen-structure.request.private.json"
    output_path = temp_dir / "qwen-structure.output.private.json"
    atomic_write_json(request_path, request)
    output_path.unlink(missing_ok=True)
    with heavy_worker_stage(config), environment_lease('main', builder=project):
        completed = run_process_tree(
            [
                str(python),
                "-m", "stemwise_engine." + helper.stem,
                "--request",
                str(request_path),
                "--output",
                str(output_path),
                "--device",
                device,
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
            env=models.roformer_offline_env(project.parent),
        )
    if completed.returncode != 0 or not output_path.is_file():
        if output_path.is_file():
            retained = read_bounded_json(output_path, max_bytes=MAX_JSON_BYTES, root=temp_dir)
            _validate_qwen3_asr_output(retained, request, require_complete=False)
            observation_sha = hashlib.sha256(json.dumps(retained, sort_keys=True,
                separators=(',', ':'), ensure_ascii=False).encode('utf-8')).hexdigest()
            atomic_write_json(workspace.job_dir(video_id) /
                f'qwen-observation-{observation_sha}.private.json', retained,
                root=workspace.job_dir(video_id))
        worker_protocol.raise_worker_failure(
            "Qwen3-ASR structure transcription failed", completed, output_path,
            diagnostic_path=workspace.job_dir(video_id) / "qwen-structure.failure.private.txt",
        )
    worker_output = read_bounded_json(
        output_path, max_bytes=MAX_JSON_BYTES, root=temp_dir
    )
    worker_output = _validate_qwen3_asr_output(worker_output, request)
    atomic_write_json(
        cache_path,
        {
            "cacheVersion": QWEN3_ASR_CACHE_VERSION,
            "videoId": video_id,
            "provenance": provenance,
            "workerOutput": worker_output,
        },
    )
    return worker_output["result"]["transcription"], provenance, False




def _roformer_runtime_provenance(
    roformer_python: Path,
    helper: Path,
    *,
    device: str,
    timeout_seconds: int,
    attempt_cache: dict[tuple[Any, ...], dict[str, Any]] | None = None,
    diagnostic_path: Path | None = None,
) -> dict[str, Any]:
    agent_root = helper.parent.parent.parent
    environment = models.roformer_offline_env(agent_root)
    expected = models.roformer_runtime_pins(helper.parent.parent)
    package_identity = {
        "version": models.ROFORMER_PACKAGE_VERSION,
        "wheelSha256": models.ROFORMER_PACKAGE_WHEEL_SHA256,
        "sourceManifestSha256": models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
        "sourceCommit": models.ROFORMER_PACKAGE_SOURCE_COMMIT,
    }
    cache_key = None
    if attempt_cache is not None:
        cache_key = _runtime_probe_cache_key(roformer_python, helper, device=device,
            expected={**expected, "package": package_identity},
            helper_hashes={helper.name: semantic_source_identity(helper)}, environment=environment)
        if cache_key in attempt_cache:
            return copy.deepcopy(attempt_cache[cache_key])
    with environment_lease('main', builder=helper.parent.parent):
        completed = run_process_tree(
            [str(roformer_python), "-m", "stemwise_engine." + helper.stem, "--runtime-info", "--device", device],
            text=True,
            capture_output=True,
            check=False,
            timeout=min(timeout_seconds, 300),
            env=environment,
        )
    if completed.returncode != 0:
        failure = diagnostic_path or (helper.resolve().parent.parent.parent.parent /
            'Engine telemetry/runtime-probes' / f'{helper.stem}.{device}.failure.private.txt')
        worker_protocol.raise_worker_failure('RoFormer runtime identity', completed, failure, diagnostic_path=failure)
    try:
        value = parse_worker_json(completed.stdout)
    except ValueError as error:
        raise RuntimeError("RoFormer runtime identity is malformed") from error
    package = value.get("package") if isinstance(value, dict) else None
    if (
        not isinstance(value, dict)
        or value.get("runtimeVersion") != audio_store.ROFORMER_RESULT_VERSION
        or value.get("supportedResultVersions") != [audio_store.ROFORMER_RESULT_VERSION]
        or value.get("python") != expected["python"]
        or value.get("packages") != expected["packages"]
        or value.get("requestedDevice") != device
        or value.get("resolvedDevice") != device
        or (device == "cuda" and value.get("cudaAvailable") is not True)
        or package
        != package_identity
    ):
        raise RuntimeError("RoFormer runtime differs from exact dependency pins")
    if attempt_cache is not None:
        attempt_cache[cache_key] = copy.deepcopy(value)
    return value


def _run_roformer_separation(
    *,
    config: dict[str, Any],
    video_id: str,
    route_id: str,
    input_audio: Path,
    input_identity: dict[str, Any],
    workspace: AlignmentWorkspace,
    duration_ms: int,
    expected_mix_sha256: str,
    temp_dir: Path,
    device: str,
    timeout_seconds: int,
    runtime_cache: dict[tuple[Any, ...], dict[str, Any]] | None = None,
) -> tuple[Path, dict[str, Any], dict[str, Any]]:
    project = Path(config["projectDir"])
    agent_root = project.parent
    helper = project / "src" / "roformer_separation.py"
    roformer_python = environment_python('main', project)
    for required in (helper, roformer_python, input_audio):
        if not required.is_file():
            raise RuntimeError(f"pinned RoFormer dependency is missing: {required.name}")
    models.require_roformer_wheel(agent_root)
    assets = models.roformer_assets(agent_root, route_id)
    runtime = _roformer_runtime_provenance(
        roformer_python,
        helper,
        device=device,
        timeout_seconds=timeout_seconds,
        attempt_cache=runtime_cache,
        diagnostic_path=workspace.job_dir(video_id) / 'roformer-runtime.failure.private.txt',
    )
    helper_sha256 = semantic_source_identity(helper)
    temp_dir.mkdir(parents=True, exist_ok=True)
    output = temp_dir / f"{route_id}.wav"
    result_path = temp_dir / f"{route_id}.result.private.json"
    with heavy_worker_stage(config), environment_lease('main', builder=project):
        completed = run_process_tree(
            [
                str(roformer_python),
                "-m", "stemwise_engine." + helper.stem,
                "--route",
                route_id,
                "--audio",
                str(input_audio),
                "--input-sha256",
                str(input_identity["sha256"]),
                "--checkpoint",
                str(assets["checkpoint"]),
                "--checkpoint-sha256",
                assets["checkpointSha256"],
                "--config",
                str(assets["config"]),
                "--config-sha256",
                assets["configSha256"],
                "--output",
                str(output),
                "--result",
                str(result_path),
                "--device",
                device,
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_seconds,
            env=models.roformer_offline_env(agent_root),
        )
    if completed.returncode != 0 or not output.is_file() or not result_path.is_file():
        worker_protocol.raise_worker_failure('RoFormer separation', completed, result_path,
            diagnostic_path=workspace.job_dir(video_id) / f'{route_id}.failure.private.txt')
    result = read_bounded_json(
        result_path, max_bytes=MAX_JSON_BYTES, root=temp_dir
    )
    if not isinstance(result, dict) or result.get("runtime") != runtime:
        raise RuntimeError("RoFormer separation result has a stale runtime identity")
    provenance = audio_store.write_roformer_audio_provenance(
        workspace,
        video_id,
        duration_ms=duration_ms,
        expected_mix_sha256=expected_mix_sha256,
        route_id=route_id,
        output_audio=output,
        result=result,
        helper_sha256=helper_sha256,
    )
    return output, provenance["audioViews"][route_id], result


def _emission_cache_key(provenance: dict[str, Any], helper_hashes: dict[str, str], device: str) -> str:
    # Targets and retry windows do not change the audio/model forward pass.
    raw = json.dumps({"provenance": provenance, "helpers": helper_hashes, "device": device},
                     sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _forced_alignment_provenance(
    *,
    audio_identity: dict[str, Any],
    duration_ms: int,
    mms_model: Path,
    runtime: dict[str, Any],
) -> dict[str, Any]:
    if not mms_model.is_file():
        raise FileNotFoundError("pinned acoustic model identity is unavailable")
    observed_sha = models.require_pinned_file(mms_model, PINNED_MMS_SHA256, 'MMS_FA checkpoint')
    return _forced_alignment_provenance_record(audio_identity=audio_identity,
        duration_ms=duration_ms, mms_model_sha256=observed_sha, runtime=runtime)


def _forced_alignment_provenance_record(*, audio_identity, duration_ms, mms_model_sha256, runtime):
    """Describe an observed pinned producer without reopening its numerical assets."""
    if mms_model_sha256 != PINNED_MMS_SHA256:
        raise ValueError('forced-alignment acoustic model identity differs from the pin')
    route_id = audio_identity.get("routeId")
    family_id = audio_identity.get("familyId")
    canonical_mix = (set(audio_identity) == {'sha256', 'byteCount', 'stream', 'canonicalTimeTransform'}
            and re.fullmatch(r'[0-9a-f]{64}', str(audio_identity.get('sha256') or ''))
            and type(audio_identity.get('byteCount')) is int and audio_identity['byteCount'] > 0
            and audio_identity['canonicalTimeTransform'] == audio_store.identity_transform(audio_identity['stream']))
    if canonical_mix:
        route_id, family_id = 'mix', 'canonical-original-pcm'
    if not canonical_mix and (route_id not in {
        "htdemucs-vocals",
        "kimberley-jensen-roformer-vocals",
        "sucial-v2-roformer-dry-vocals",
    } or not isinstance(family_id, str)):
        raise FileNotFoundError("cached acoustic view has no trusted route identity")
    return {
        "audio": _cache_audio_identity(audio_identity),
        "durationMs": duration_ms,
        "audioViewId": route_id,
        "audioFamilyId": family_id,
        "mmsModelSha256": mms_model_sha256,
        "runtime": runtime,
        "decoder": {
            "chunkSeconds": 30,
            "channelMode": "mean-mono",
            "bundle": "torchaudio.pipelines.MMS_FA",
        },
    }


def _validate_mms_time_transform(value: dict[str, Any], duration_ms: int) -> None:
    from stemwise_engine.acoustic_inputs import CTC_MINIMUM_SAMPLES
    transform = value.get("canonicalTimeTransform")
    required = {
        "sourceSampleRate",
        "sourceChannelCount",
        "sourceFrameCount",
        "monoChannelCount",
        "targetSampleRate",
        "targetFrameCount",
        "expectedTargetFrameCount",
        "resamplingRatioNumerator",
        "resamplingRatioDenominator",
        "offsetTargetFrames",
        "trimSourceFrames",
        "padSourceFrames",
        "measuredResidualFrames",
        "measuredResidualMs",
        "chunkTargetFrames",
        "chunks",
    }
    if not isinstance(transform, dict) or set(transform) != required:
        raise ValueError("forced-alignment canonical-time transform is malformed")
    positive = (
        "sourceSampleRate",
        "sourceChannelCount",
        "sourceFrameCount",
        "monoChannelCount",
        "targetSampleRate",
        "targetFrameCount",
        "expectedTargetFrameCount",
        "resamplingRatioNumerator",
        "resamplingRatioDenominator",
        "chunkTargetFrames",
    )
    if any(type(transform[key]) is not int or transform[key] <= 0 for key in positive):
        raise ValueError("forced-alignment canonical-time dimensions are invalid")
    if (
        type(value.get("sampleRate")) is not int
        or value["sampleRate"] <= 0
        or type(value.get("durationMs")) is not int
        or value["durationMs"] <= 0
        or type(value.get("emissionFrameCount")) is not int
        or value["emissionFrameCount"] <= 0
        or any(
            type(transform[key]) is not int
            for key in (
                "offsetTargetFrames",
                "trimSourceFrames",
                "padSourceFrames",
                "measuredResidualFrames",
            )
        )
        or type(transform["measuredResidualMs"]) not in {int, float}
        or not math.isfinite(float(transform["measuredResidualMs"]))
    ):
        raise ValueError("forced-alignment canonical-time values are invalid")
    ratio = Fraction(transform["targetSampleRate"], transform["sourceSampleRate"])
    expected = Fraction(transform["sourceFrameCount"]) * ratio
    expected_frames = (expected.numerator * 2 + expected.denominator) // (
        expected.denominator * 2
    )
    residual = transform["targetFrameCount"] - expected_frames
    if (
        transform["monoChannelCount"] != 1
        or transform["resamplingRatioNumerator"] != ratio.numerator
        or transform["resamplingRatioDenominator"] != ratio.denominator
        or transform["expectedTargetFrameCount"] != expected_frames
        or transform["measuredResidualFrames"] != residual
        or abs(residual) > 1
        or transform["offsetTargetFrames"] != 0
        or transform["trimSourceFrames"] != 0
        or transform["padSourceFrames"] != 0
        or abs(
            float(transform["measuredResidualMs"])
            - residual * 1000.0 / transform["targetSampleRate"]
        )
        > 1e-9
        or value.get("sampleRate") != transform["targetSampleRate"]
        or value.get("durationMs")
        != (
            transform["targetFrameCount"] * 1000
            + transform["targetSampleRate"] // 2
        )
        // transform["targetSampleRate"]
        or value["durationMs"] != duration_ms
    ):
        raise ValueError("forced-alignment canonical-time transform is inconsistent")
    chunks = transform["chunks"]
    if not isinstance(chunks, list) or not chunks:
        raise ValueError("forced-alignment chunk map is missing")
    target_cursor = emission_cursor = 0
    for chunk in chunks:
        if (
            not isinstance(chunk, dict)
            or set(chunk)
            != {
                "targetStartFrame",
                "targetEndFrame",
                "emissionStartFrame",
                "emissionEndFrame",
                "emissionToTargetRatioNumerator",
                "emissionToTargetRatioDenominator",
            }
            or chunk["targetStartFrame"] != target_cursor
            or chunk["emissionStartFrame"] != emission_cursor
            or type(chunk["targetStartFrame"]) is not int
            or type(chunk["emissionStartFrame"]) is not int
            or type(chunk["targetEndFrame"]) is not int
            or type(chunk["emissionEndFrame"]) is not int
            or type(chunk["emissionToTargetRatioNumerator"]) is not int
            or type(chunk["emissionToTargetRatioDenominator"]) is not int
            or chunk["targetEndFrame"] <= target_cursor
            or chunk["emissionEndFrame"] <= emission_cursor
            or (chunk["targetEndFrame"] - target_cursor > transform["chunkTargetFrames"]
                and not (chunk["targetEndFrame"] == transform["targetFrameCount"]
                    and chunk["targetEndFrame"] - target_cursor < transform["chunkTargetFrames"] + CTC_MINIMUM_SAMPLES))
        ):
            raise ValueError("forced-alignment chunk map is inconsistent")
        chunk_ratio = Fraction(
            chunk["targetEndFrame"] - target_cursor,
            chunk["emissionEndFrame"] - emission_cursor,
        )
        if (
            chunk["emissionToTargetRatioNumerator"] != chunk_ratio.numerator
            or chunk["emissionToTargetRatioDenominator"] != chunk_ratio.denominator
        ):
            raise ValueError("forced-alignment chunk time transform is inconsistent")
        target_cursor = chunk["targetEndFrame"]
        emission_cursor = chunk["emissionEndFrame"]
    if (
        target_cursor != transform["targetFrameCount"]
        or emission_cursor != value.get("emissionFrameCount")
    ):
        raise ValueError("forced-alignment chunk map does not cover its output")


def _validate_numeric_mms_result(
    value: dict[str, Any],
    *,
    expected_sources: list[int],
    duration_ms: int,
    expected_line_ids: list[str],
    expected_word_ids: list[list[str]],
    allow_tied_starts: bool = False,
    expected_format_version: int = MMS_REQUEST_VERSION,
    expected_engine: str = "torchaudio-mms-fa",
    expected_segments: list[dict[str, int]] | None = None,
) -> None:
    if len(expected_line_ids) != len(expected_sources) or len(expected_word_ids) != len(
        expected_sources
    ):
        raise ValueError("forced-alignment expected identity mapping is malformed")
    if value.get("formatVersion") != expected_format_version:
        raise ValueError("forced-alignment result has the wrong format version")
    if value.get("engine") != expected_engine:
        raise ValueError("forced-alignment result uses an unexpected engine")
    _validate_mms_time_transform(value, duration_ms)
    lines = value.get("lines")
    if not isinstance(lines, list) or len(lines) != len(expected_sources):
        raise ValueError("forced-alignment result has the wrong line count")
    if expected_segments is not None and (
        value.get("segmentCount") != len(expected_segments)
        or value.get("segmentBoundaryGuardMs") != endpoints.WORKER_SEGMENT_BOUNDARY_GUARD_MS
        or value.get("lineCount") != len(expected_sources)
        or (
            expected_engine == "wav2vec2-ctc-forced-alignment"
            and value.get("requestFormatVersion") != MMS_REQUEST_VERSION
        )
    ):
        raise ValueError("forced-alignment segment result metadata is invalid")
    starts: list[int] = []
    ends: list[int] = []
    segment_for_line: dict[int, dict[str, int]] = {}
    if expected_segments is not None:
        endpoints._validate_forced_alignment_segments(
            expected_segments, len(expected_sources), duration_ms
        )
        for segment in expected_segments:
            for line_index in range(
                segment["startLineIndex"], segment["endLineIndex"]
            ):
                segment_for_line[line_index] = segment
    previous_word_end = -1
    for expected_index, (source, record) in enumerate(zip(expected_sources, lines)):
        expected_line_keys = {
            "lineIndex",
            "sourcePhysicalIndex",
            "startMs",
            "endMs",
            "confidence",
            "minimumWordConfidence",
            "displayWordCount", "acousticTokenCount",
        }
        if expected_engine == 'torchaudio-mms-fa':
            expected_line_keys.add('romanizedWordCount')
        expected_line_keys.update({"lineId", "words"})
        if not isinstance(record, dict) or set(record) != expected_line_keys:
            raise ValueError("forced-alignment line record is malformed")
        if record["lineIndex"] != expected_index or record["sourcePhysicalIndex"] != source:
            raise ValueError("forced-alignment line identity does not match the request")
        start = record["startMs"]
        end = record["endMs"]
        confidence = record["confidence"]
        minimum = record["minimumWordConfidence"]
        if (
            type(start) is not int
            or type(end) is not int
            or not 0
            <= start
            < end
            <= duration_ms
            or not isinstance(confidence, (int, float))
            or not isinstance(minimum, (int, float))
            or not 0.0 <= float(confidence) <= 1.0
            or not 0.0 <= float(minimum) <= 1.0
        ):
            raise ValueError("forced-alignment timing or confidence is invalid")
        segment = segment_for_line.get(expected_index)
        if segment is not None and not (
            segment["windowStartMs"] <= start < end <= segment["windowEndMs"]
        ):
            raise ValueError("forced-alignment line escapes its requested segment")
        if record["lineId"] != expected_line_ids[expected_index]:
            raise ValueError("forced-alignment line ID does not match the request")
        words = record["words"]
        if not isinstance(words, list) or len(words) != len(expected_word_ids[expected_index]):
            raise ValueError("forced-alignment result has the wrong display-word count")
        for expected_word_id, word in zip(expected_word_ids[expected_index], words):
            word_keys = {'wordId', 'startMs', 'endMs', 'confidence', 'acousticTokenCount'}
            if expected_engine == 'torchaudio-mms-fa':
                word_keys.add('romanizedWordCount')
            if not isinstance(word, dict) or set(word) != word_keys:
                raise ValueError("forced-alignment display-word record is malformed")
            if word["wordId"] != expected_word_id:
                raise ValueError("forced-alignment word ID does not match the request")
            if (
                type(word["startMs"]) is not int
                or type(word["endMs"]) is not int
                or not start <= word["startMs"] < word["endMs"] <= end
                or not isinstance(word["confidence"], (int, float))
                or not 0.0 <= float(word["confidence"]) <= 1.0
                or type(word["acousticTokenCount"]) is not int
                or word["acousticTokenCount"] <= 0
                or word["startMs"] < previous_word_end
            ):
                raise ValueError("forced-alignment display-word timing is invalid")
            previous_word_end = word["endMs"]
        if (type(record['displayWordCount']) is not int or record['displayWordCount'] != len(words)
                or type(record['acousticTokenCount']) is not int
                or record['acousticTokenCount'] != sum(word['acousticTokenCount'] for word in words)):
            raise ValueError('forced-alignment display/token count is inconsistent')
        if expected_engine == 'torchaudio-mms-fa' and (
                any(type(word['romanizedWordCount']) is not int or word['romanizedWordCount'] <= 0 for word in words)
                or type(record['romanizedWordCount']) is not int
                or record['romanizedWordCount'] != sum(word['romanizedWordCount'] for word in words)):
            raise ValueError('forced-alignment romanized word count is inconsistent')
        starts.append(start)
        ends.append(end)
    if expected_segments is not None:
        if ('wordCount' in value or type(value.get('displayWordCount')) is not int
                or value['displayWordCount'] != sum(len(record['words']) for record in lines)
                or type(value.get('acousticTokenCount')) is not int
                or value['acousticTokenCount'] != sum(record['acousticTokenCount'] for record in lines)):
            raise ValueError('forced-alignment result display/token count is inconsistent')
        if expected_engine == 'torchaudio-mms-fa' and (
                type(value.get('romanizedWordCount')) is not int
                or value['romanizedWordCount'] != sum(record['romanizedWordCount'] for record in lines)):
            raise ValueError('forced-alignment result romanized word count is inconsistent')
    if expected_segments is not None:
        for segment in expected_segments:
            selected = lines[
                segment["startLineIndex"] : segment["endLineIndex"]
            ]
            if (
                segment["windowStartMs"] > 0
                and selected[0]["startMs"] - segment["windowStartMs"]
                < endpoints.WORKER_SEGMENT_BOUNDARY_GUARD_MS
            ) or (
                segment["windowEndMs"] < duration_ms
                and segment["windowEndMs"] - selected[-1]["endMs"]
                < endpoints.WORKER_SEGMENT_BOUNDARY_GUARD_MS
            ):
                raise ValueError("forced-alignment result violates a segment edge guard")
    invalid_order = any(
        right < left if allow_tied_starts else right <= left
        for left, right in zip(starts, starts[1:])
    )
    if invalid_order:
        raise ValueError("forced-alignment line starts are out of order")
    if any(left_end > right_start for left_end, right_start in zip(ends, starts[1:])):
        raise ValueError("forced-alignment lines overlap")
    # Both workers derive line bounds from the first and last display words. A cache must
    # preserve that invariant; a longer line cannot justify an already-ended final word.
    if any(
        not line["words"]
        or line["startMs"] != line["words"][0]["startMs"]
        or line["endMs"] != line["words"][-1]["endMs"]
        for line in lines
    ):
        raise ValueError("forced-alignment line bounds differ from its display words")
    confidence = value.get("confidence")
    required = {"wordP10", "wordP50", "wordP90", "lineP10", "lineP50", "lineP90"}
    if not isinstance(confidence, dict) or set(confidence) != required:
        raise ValueError("forced-alignment confidence summary is malformed")
    if any(
        not isinstance(confidence[key], (int, float))
        or not 0.0 <= float(confidence[key]) <= 1.0
        for key in required
    ):
        raise ValueError("forced-alignment confidence summary is invalid")


def _forced_alignment_request(
    video_id: str,
    sources: list[int],
    occurrence_mapping: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
    *,
    duration_ms: int,
    segments: list[dict[str, int]] | None = None,
    acoustic_language_lines: list[list[str]] | None = None,
) -> tuple[dict[str, Any], list[str], list[list[str]], dict[str, Any]]:
    if len(display_word_lines) != len(sources) or any(
        not words for words in display_word_lines
    ):
        raise ValueError("display-word lines do not match the performed line sequence")
    request_word_lines = [
        [surface for _, _, surface in words] for words in display_word_lines
    ]
    from stemwise_engine.spoken_targets import ReviewedSpokenPlan
    from stemwise_engine.lyrics_normalize import physical_lines
    spoken = occurrence_mapping.get('_spokenTargets')
    if spoken is not None:
        if not isinstance(spoken, ReviewedSpokenPlan):
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        if display_word_lines != [display_words(physical_lines(spoken.original_text)[source]) for source in sources]:
            raise LyricsContentBlocker('pronunciation-support-unavailable')
        request_word_lines = spoken.word_lines(sources)
        acoustic_language_lines = spoken.language_lines(sources)
    for line in request_word_lines:
        for surface in line:
            lyric_content.require_pronunciation_support(surface)
    if acoustic_language_lines is not None and (
        len(acoustic_language_lines) != len(request_word_lines)
        or any(
            len(languages) != len(words)
            or any(not isinstance(language, str) or not language for language in languages)
            for languages, words in zip(acoustic_language_lines, request_word_lines)
        )
    ):
        raise ValueError("acoustic languages do not match the display-word plan")
    mapping_identity = lexical_mapping_identity(occurrence_mapping)
    occurrences = occurrence_mapping.get("performedOccurrences")
    if (
        occurrence_mapping.get("videoId") != video_id
        or not isinstance(occurrences, list)
        or len(occurrences) != len(sources)
    ):
        raise ValueError("forced-alignment occurrence mapping is stale")
    expected_line_ids: list[str] = []
    expected_word_ids: list[list[str]] = []
    for line_index, (source, words, occurrence) in enumerate(
        zip(sources, request_word_lines, occurrences)
    ):
        if not isinstance(occurrence, dict):
            raise ValueError("forced-alignment occurrence mapping is stale")
        mapped_words = occurrence.get("words")
        if (
            occurrence.get("performedIndex") != line_index
            or occurrence.get("sourcePhysicalIndex") != source
            or not isinstance(occurrence.get("occurrenceId"), str)
            or not isinstance(mapped_words, list)
            or len(mapped_words) != len(words)
        ):
            raise ValueError("forced-alignment occurrence mapping is stale")
        expected_line_ids.append(occurrence["occurrenceId"])
        expected_word_ids.append([word["occurrenceWordId"] for word in mapped_words])
    segments = segments or [
        {
            "segmentIndex": 0,
            "startLineIndex": 0,
            "endLineIndex": len(sources),
            "windowStartMs": 0,
            "windowEndMs": duration_ms,
        }
    ]
    endpoints._validate_forced_alignment_segments(segments, len(sources), duration_ms)
    return (
        {
            "formatVersion": MMS_REQUEST_VERSION,
            "durationMs": duration_ms,
            "segments": segments,
            "lines": [
                {
                    "lineId": expected_line_ids[line_index],
                    "sourcePhysicalIndex": source,
                    "words": [
                        {
                            "wordId": word_id,
                            "acousticText": surface,
                            **(
                                {"acousticLanguage": acoustic_language_lines[line_index][word_index]}
                                if acoustic_language_lines is not None
                                else {}
                            ),
                        }
                        for word_index, (word_id, surface) in enumerate(zip(
                            expected_word_ids[line_index],
                            request_word_lines[line_index],
                        ))
                    ],
                }
                for line_index, source in enumerate(sources)
            ],
        },
        expected_line_ids,
        expected_word_ids,
        mapping_identity,
    )




def _forced_alignment_cache_path(
    workspace: AlignmentWorkspace, video_id: str, variant: str
) -> Path:
    if variant in {"baseline", "candidate-structure"}:
        return workspace.forced_alignment_path(video_id, variant)
    if re.fullmatch(r"public-endpoints-v3(?:-[a-z0-9-]+)?", variant) is None:
        raise ValueError("unsupported forced-alignment cache variant")
    return workspace.job_dir(video_id) / f"mms-{variant}.private.json"


def _alignment_request_sha256(request: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps(request, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def _resolved_alignment_request(request: dict[str, Any], recovery: Any,
                                *, allow_repair: bool) -> dict[str, Any]:
    if recovery is None:
        return request
    keys = {"originalRequestSha256", "originalSegments", "resolvedRequestSha256", "resolvedSegments", "mergeSteps"}
    if (not allow_repair or not isinstance(recovery, dict) or set(recovery) != keys
            or not isinstance(recovery["mergeSteps"], list)
            or not isinstance(recovery["originalSegments"], list)
            or not 0 < len(recovery["mergeSteps"]) < len(recovery["originalSegments"])):
        raise ValueError("forced-alignment request recovery is malformed")
    original = {**request, "segments": recovery["originalSegments"]}
    endpoints._validate_forced_alignment_segments(original["segments"], len(request["lines"]), request["durationMs"])
    segments = original["segments"]
    for step in recovery["mergeSteps"]:
        if (not isinstance(step, dict) or set(step) != {"segmentIndex", "boundary"}
                or type(step["segmentIndex"]) is not int or step["boundary"] not in (None, "start", "end")):
            raise ValueError("forced-alignment merge step is malformed")
        segments = endpoints._merge_short_bootstrap_segment(segments, step["segmentIndex"], boundary=step["boundary"])
    resolved = {**request, "segments": segments}
    original_sha, resolved_sha = _alignment_request_sha256(original), _alignment_request_sha256(resolved)
    if (segments != recovery["resolvedSegments"] or original_sha != recovery["originalRequestSha256"]
            or resolved_sha != recovery["resolvedRequestSha256"]
            or _alignment_request_sha256(request) not in {original_sha, resolved_sha}):
        raise ValueError("forced-alignment recovery changed its bound request")
    return resolved


def _read_alignment_batch_reply(manifest: dict[str, Any], reply: Any, request: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any] | None]:
    context = manifest["contexts"][0]
    row_keys = {"contextId", "audioSha256", "requestSha256", "resolvedRequestSha256", "resolvedSegments", "mergeSteps"}
    if (not isinstance(reply, dict) or set(reply) != {"formatVersion", "batchSha256", "contexts"}
            or type(reply["formatVersion"]) is not int or reply["formatVersion"] != 1
            or reply["batchSha256"] != _alignment_request_sha256(manifest)
            or not isinstance(reply["contexts"], list) or len(reply["contexts"]) != 1):
        raise ValueError("forced-alignment batch reply is incomplete or stale")
    row = reply["contexts"][0]
    if (not isinstance(row, dict) or set(row) != row_keys
            or any(row.get(key) != context[key] for key in ("contextId", "audioSha256", "requestSha256"))
            or not isinstance(row["mergeSteps"], list)):
        raise ValueError("forced-alignment batch context differs from its request")
    if not row["mergeSteps"]:
        if row["resolvedSegments"] != request["segments"] or row["resolvedRequestSha256"] != _alignment_request_sha256(request):
            raise ValueError("forced-alignment batch changed an unrepaired request")
        return request, None
    recovery = {"originalRequestSha256": row["requestSha256"], "originalSegments": request["segments"],
                "resolvedRequestSha256": row["resolvedRequestSha256"],
                "resolvedSegments": row["resolvedSegments"], "mergeSteps": row["mergeSteps"]}
    return _resolved_alignment_request(request, recovery, allow_repair=manifest["repairSegments"]), recovery


def _run_alignment_worker_batch(*, config: dict[str, Any], python: Path, helper: Path,
                                audio: Path, audio_sha256: str, request: dict[str, Any],
                                request_path: Path, output_path: Path, device: str,
                                timeout_seconds: int, cache_key: str, producer: dict[str, Any],
                                environment: dict[str, str], failure_path: Path, repair: bool,
                                model: Path | None = None, language: str | None = None) -> tuple[Any, dict[str, Any], dict[str, Any] | None]:
    from stemwise_engine.alignment_model_queue import prepare_emissions
    output_path.unlink(missing_ok=True)
    if model is not None:
        capability = _run_numerical_process([str(python), "-m", "stemwise_engine." + helper.stem, '--check-targets',
            '--request', str(request_path), '--output', str(output_path),
            '--model', str(model), '--language', str(language)],
            builder=Path(config['projectDir']), timeout=min(timeout_seconds, 120), text=True, capture_output=True, check=False, env=environment)
        if capability.returncode not in (0, 3):
            return capability, request, None
        packet = _read_optional_cache_json(output_path)
        if (not isinstance(packet, dict) or set(packet) != {'formatVersion', 'engine', 'requestSha256', 'supported'}
                or type(packet['formatVersion']) is not int or packet['formatVersion'] != 1 or packet['engine'] != 'wav2vec2-target-capability'
                or packet['requestSha256'] != _alignment_request_sha256(request)
                or packet['supported'] is not (capability.returncode == 0)):
            worker_protocol.raise_worker_failure('target capability does not bind the current request',
                capability, output_path, diagnostic_path=failure_path)
        if capability.returncode == 3:
            return capability, request, None
        output_path.unlink()
    batch_path = request_path.with_suffix(".batch.json")
    reply_path = output_path.with_suffix(".batch.json")
    reply_path.unlink(missing_ok=True)
    manifest = {"formatVersion": 1, "timeoutSeconds": timeout_seconds, "repairSegments": repair,
        "contexts": [{"contextId": "0", "audio": str(audio), "audioSha256": audio_sha256,
            "request": str(request_path), "requestSha256": _alignment_request_sha256(request),
            "output": str(output_path), "emissionCacheKey": cache_key}]}
    atomic_write_json(batch_path, manifest)
    prepare_emissions(audio=audio, duration_ms=request["durationMs"],
        config={**config, "tempDir": str(request_path.parent)}, timeout_seconds=timeout_seconds,
        python=python, helper=helper, device=device, cache_key=cache_key,
        cache_path=request_path.parent / f"emissions-{cache_key}.pt", producer=producer,
        model=model, language=language, env=environment,
        diagnostic_path=failure_path.with_suffix(".prepare.failure.private.txt"))
    command = [str(python), "-m", "stemwise_engine." + helper.stem, "--batch", str(batch_path), "--output", str(reply_path),
               "--project", str(config["projectDir"]), "--device", device]
    if model is not None:
        command.extend(("--model", str(model), "--language", str(language)))
    completed = _run_numerical_process(command, builder=Path(config['projectDir']), text=True, capture_output=True, check=False,
        timeout=None, env=environment)
    worker_protocol.raise_if_worker_stopped(completed, code='forced-alignment batch', diagnostic_path=failure_path)
    if completed.returncode == 0:
        final_request, recovery = _read_alignment_batch_reply(manifest, _read_optional_cache_json(reply_path), request)
    else:
        final_request, recovery = request, None
        if completed.returncode == 3 and reply_path.is_file():
            # Preserve the existing exact-request static target-capability contract.
            atomic_write_json(output_path, _read_json(reply_path))
    return completed, final_request, recovery


def _run_closed_alignment_worker(*, python, helper, request, request_path, output_path,
                                 request_sha256, timeout_seconds, environment):
    atomic_write_json(request_path, request, pretty=False)
    return run_process_tree(
        [str(python), '-m', 'stemwise_engine.'+helper.stem, '--request', str(request_path),
         '--request-sha256', request_sha256, '--output', str(output_path),
         '--timeout-seconds', str(timeout_seconds)],
        text=True, capture_output=True, check=False, timeout=None, env=environment)


def _admit_alignment_cache(path, validate):
    cached = _read_optional_cache_json(path)
    if cached is None:
        return None
    try:
        return validate(cached)
    except (AttributeError, KeyError, TypeError, ValueError):
        return None


def _publish_alignment_cache(path, result, *, failure_path=None):
    atomic_write_json(path, result)
    if failure_path is not None:
        failure_path.unlink(missing_ok=True)


def _load_or_run_mms_alignment(
    *,
    video_id: str,
    variant: str,
    sources: list[int],
    acoustic_track_kind: str,
    duration_ms: int,
    workspace: AlignmentWorkspace,
    helper: Path,
    mms_python: Path,
    vocals_audio: Path | None,
    temp_dir: Path,
    device: str,
    timeout_seconds: int,
    provenance: dict[str, Any],
    occurrence_mapping: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
    segments: list[dict[str, int]] | None = None,
    config: dict[str, Any],
) -> tuple[dict[str, Any], bool]:
    request, expected_line_ids, expected_word_ids, mapping_identity = (
        _forced_alignment_request(
            video_id,
            sources,
            occurrence_mapping,
            display_word_lines,
            duration_ms=duration_ms,
            segments=segments,
        )
    )
    repair = variant.endswith("-anchored")
    helper_sha256 = ctc_worker.helper_source_identity(helper)
    cache_path = _forced_alignment_cache_path(workspace, video_id, variant)
    def admit_cached_result(cached):
        cached_request = _resolved_alignment_request(request, cached.get("alignmentRequestRecovery"), allow_repair=repair)
        cached_segments = cached_request["segments"]
        if "resolvedSegments" in cached and cached["resolvedSegments"] != cached_segments:
            raise ValueError("cached alignment resolved segments are stale")
        cached_request_sha256 = _alignment_request_sha256(cached_request)
        if not (_mms_cache_matches(
                    cached,
                    video_id=video_id,
                    variant=variant,
                    acoustic_track_kind=acoustic_track_kind,
                    request_sha256=cached_request_sha256,
                    helper_sha256=helper_sha256,
                    provenance=provenance,
                    mapping_identity=mapping_identity,
                )):
            return None
        _validate_numeric_mms_result(
            cached,
            expected_sources=sources,
            duration_ms=duration_ms,
            expected_line_ids=expected_line_ids,
            expected_word_ids=expected_word_ids,
            allow_tied_starts=True,
            expected_segments=cached_segments,
        )
        return {**cached, "resolvedSegments": cached_segments}, True

    cached_result = _admit_alignment_cache(cache_path, admit_cached_result)
    if cached_result is not None:
        return cached_result

    if vocals_audio is None:
        raise FileNotFoundError("forced-alignment acoustic cache is missing")
    if not vocals_audio.is_file():
        raise FileNotFoundError("forced-alignment acoustic audio is unavailable")
    bound_audio = provenance.get("audio")
    if not isinstance(bound_audio, dict):
        raise ValueError("forced-alignment provenance has no bound audio identity")
    supplied_identity = audio_store.file_identity(vocals_audio)
    if any(
        supplied_identity[key] != bound_audio.get(key)
        for key in ("sha256", "byteCount", "stream")
    ):
        raise ValueError("forced-alignment audio differs from its bound identity")

    request_path = temp_dir / f"mms-{variant}.request.private.json"
    output_path = temp_dir / f"mms-{variant}.output.private.json"
    atomic_write_json(request_path, request)
    recovery = None
    completed, final_request, recovery = _run_alignment_worker_batch(
        config=config, python=mms_python, helper=helper, audio=vocals_audio,
        audio_sha256=bound_audio["sha256"], request=request, request_path=request_path,
        output_path=output_path, device=device, timeout_seconds=timeout_seconds,
        cache_key=_emission_cache_key(provenance, {"mms_forced_alignment.py": helper_sha256}, device),
        producer={key: provenance[key] for key in ("model", "mmsModelSha256", "runtime", "decoder") if key in provenance},
        environment=os.environ.copy(), failure_path=workspace.job_dir(video_id) / f"mms-{variant}.failure.private.txt",
        repair=repair)
    request_segments = final_request["segments"]
    request_sha256 = _alignment_request_sha256(final_request)
    if completed.returncode != 0 or not output_path.is_file():
        worker_protocol.raise_worker_failure(
            "MMS forced alignment failed", completed, output_path,
            diagnostic_path=workspace.job_dir(video_id) / f"mms-{variant}.failure.private.txt",
        )
    result = _read_json(output_path)
    _validate_numeric_mms_result(
        result,
        expected_sources=sources,
        duration_ms=duration_ms,
        expected_line_ids=expected_line_ids,
        expected_word_ids=expected_word_ids,
        allow_tied_starts=True,
        expected_segments=request_segments,
    )
    result.update(
        {
            "cacheVersion": MMS_CACHE_VERSION,
            "videoId": video_id,
            "variant": variant,
            "requestSha256": request_sha256,
            "helperSha256": helper_sha256,
            "acousticTrackKind": acoustic_track_kind,
            "provenance": provenance,
            "mappingIdentity": mapping_identity,
        }
    )
    if recovery is not None:
        result["alignmentRequestRecovery"] = recovery
    result["resolvedSegments"] = request_segments
    _publish_alignment_cache(cache_path, result)
    return result, False




OWNER_WORD_PREDICTION_VARIANT = 'public-endpoints-v3-owner-prediction'
OWNER_WORD_PREDICTION_BASIS = 'Complete-target MMS CTC acoustic prediction; word timing is estimated, not independently verified.'


def _owner_word_prediction_inputs(workspace, video_id, original, canonical_sha, audio_sha, *, retain_owner_evidence=True):
    sources = _baseline_source_indices(original['text'])
    admission = lyric_content.owner_workspace_admission(
        workspace, video_id, original, sources, canonical_sha, audio_sha, retain=retain_owner_evidence)
    if admission is None or admission.owner_verification is None:
        raise LyricsContentBlocker('content-evidence-unavailable')
    mapping = build_lexical_occurrence_mapping(video_id, original, sources)
    from stemwise_engine.spoken_targets import load
    spoken = load(workspace.root.parent/'jobs'/video_id, video_id, original, sources, audio_sha)
    numerical_mapping = {**mapping, **({'_spokenTargets': spoken} if spoken is not None else {})}
    words = [display_words(_plain_physical_lines(original['text'])[source]) for source in sources]
    return sources, admission, mapping, numerical_mapping, words


def _owner_prediction_runtime(runtime, project):
    """Validate retained observed identity without starting a runtime or model."""
    pins = models.mms_runtime_pins(project)
    if (not isinstance(runtime, dict) or runtime.get('runtimeVersion') != 1
            or runtime.get('bundle') != 'torchaudio.pipelines.MMS_FA'
            or runtime.get('supportedRequestVersions') != [MMS_REQUEST_VERSION]
            or runtime.get('requestedDevice') not in {'cpu', 'cuda'}
            or runtime.get('resolvedDevice') != runtime.get('requestedDevice')
            or (runtime.get('requestedDevice') == 'cuda' and runtime.get('cudaAvailable') is not True)
            or any(runtime.get(key) != value for key, value in pins.items())):
        raise ValueError('owner word prediction runtime identity is stale')
    return runtime


def _validate_owner_prediction_result(project, video_id, result, numerical_mapping, words, sources, audio_sha):
    """Use the normal numerical cache contract for both production and passive readback."""
    provenance = result.get('provenance')
    if not isinstance(provenance, dict) or not isinstance(provenance.get('audio'), dict):
        raise ValueError('owner word prediction has no bound recording')
    identity = provenance['audio']
    if identity.get('sha256') != audio_sha:
        raise ValueError('owner word prediction recording differs')
    duration = identity['stream']['durationMs']
    native_duration, _ = _endpoint_request_timing(identity, duration, None)
    runtime = _owner_prediction_runtime(provenance.get('runtime'), project)
    expected_provenance = _forced_alignment_provenance_record(audio_identity=identity,
        duration_ms=native_duration, mms_model_sha256=PINNED_MMS_SHA256, runtime=runtime)
    if expected_provenance['audioViewId'] != 'mix':
        raise ValueError('owner word prediction requires the canonical mix')
    request, line_ids, word_ids, mapping_identity = _forced_alignment_request(
        video_id, sources, numerical_mapping, words, duration_ms=native_duration, segments=None)
    if (not _mms_cache_matches(result, video_id=video_id, variant=OWNER_WORD_PREDICTION_VARIANT,
            acoustic_track_kind='original-display-uroman:mix', request_sha256=_alignment_request_sha256(request),
            helper_sha256=ctc_worker.helper_source_identity(project/'src/mms_forced_alignment.py'),
            provenance=expected_provenance, mapping_identity=mapping_identity)
            or result.get('resolvedSegments') != request['segments']
            or 'alignmentRequestRecovery' in result):
        raise ValueError('owner word prediction producer or full-target request is stale')
    _validate_numeric_mms_result(result, expected_sources=sources, duration_ms=native_duration,
        expected_line_ids=line_ids, expected_word_ids=word_ids, allow_tied_starts=True,
        expected_segments=request['segments'])
    transform = result['canonicalTimeTransform']
    if any(transform[key] != identity['stream'][source] for key, source in (
            ('sourceSampleRate','sampleRate'), ('sourceChannelCount','channelCount'),
            ('sourceFrameCount','frameCount'))):
        raise ValueError('owner word prediction acoustic time base differs from the recording')
    return _project_endpoint_eof(result, duration), duration


@_alignment_sessions.alignment_worker_session()
def run_owner_word_prediction(config, workspace, video_id, lyrics, canonical_sha,
                              analysis_audio, expected_mix_sha, temp_root, *, device='cuda'):
    """Run one complete full-recording acoustic target, only under exact owner authority."""
    video_id = validate_video_id(video_id)
    original = _plain_original(lyrics)
    sources, admission, mapping, numerical_mapping, words = _owner_word_prediction_inputs(
        workspace, video_id, original, canonical_sha, expected_mix_sha)
    # The explicit owner decision precedes even PCM inspection and runtime probing.
    raw = read_bounded_regular_bytes(workspace.current_backup_path(video_id),
        max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
    if hashlib.sha256(raw).hexdigest() != canonical_sha or parse_strict_json_bytes(raw) != lyrics:
        raise ValueError('owner word prediction canonical target differs')
    identity = audio_store.file_identity(analysis_audio)
    if identity['sha256'] != expected_mix_sha:
        raise ValueError('owner word prediction requires the exact canonical PCM')
    identity = {**identity, 'canonicalTimeTransform': audio_store.identity_transform(identity['stream'])}
    duration, _ = _endpoint_request_timing(identity, identity['stream']['durationMs'], None)
    project = Path(config['projectDir'])
    python = environment_python('main', project)
    helper = project/'src/mms_forced_alignment.py'
    if not python.is_file() or not helper.is_file():
        raise FileNotFoundError('owner word prediction runtime is unavailable')
    timeout = int(config['analysisTimeoutMinutes']) * 60
    runtime = _mms_runtime_provenance(python, helper, device=device, timeout_seconds=timeout,
        diagnostic_path=workspace.job_dir(video_id)/'owner-word-prediction-runtime.failure.private.txt')
    provenance = _forced_alignment_provenance(audio_identity=identity, duration_ms=duration,
        mms_model=project.parent/'model-cache/torch/hub/checkpoints/model.pt', runtime=runtime)
    result, reused = _load_or_run_mms_alignment(video_id=video_id, variant=OWNER_WORD_PREDICTION_VARIANT,
        sources=list(sources), acoustic_track_kind='original-display-uroman:mix', duration_ms=duration,
        workspace=workspace, helper=helper, mms_python=python, vocals_audio=analysis_audio,
        temp_dir=temp_root, device=device, timeout_seconds=timeout, provenance=provenance,
        occurrence_mapping=numerical_mapping, display_word_lines=words, segments=None, config=config)
    current_identity = audio_store.file_identity(analysis_audio)
    if any(current_identity.get(key) != identity[key] for key in ('sha256', 'byteCount', 'stream')):
        raise ValueError('owner word prediction recording changed during inference')
    current = _owner_word_prediction_inputs(workspace, video_id, original, canonical_sha, expected_mix_sha)[1]
    if current.to_record() != admission.to_record():
        raise ValueError('owner word prediction authority changed during inference')
    projected, public_duration = _validate_owner_prediction_result(
        project, video_id, result, numerical_mapping, words, sources, expected_mix_sha)
    candidate = build_predicted_sidecar(lyrics, mapping, projected, public_duration, basis=OWNER_WORD_PREDICTION_BASIS)
    return candidate, mapping, result, admission, _forced_alignment_cache_path(
        workspace, video_id, OWNER_WORD_PREDICTION_VARIANT), reused


def load_owner_word_prediction(project, workspace, video_id, response, *, original, indices, canonical_sha, audio_sha):
    """Recheck functional references and current proof/producer without inference or PCM decoding."""
    video_id = validate_video_id(video_id)
    if (not isinstance(response, dict) or set(response)-{'vowelReleaseEvidence'} != {'version','videoId','status','audioSha256',
            'canonicalPlainSha256','candidate','mapping','forced','admission'}
            or type(response.get('version')) is not int or response['version'] != 1
            or response['videoId'] != video_id or response['status'] != 'owner-predicted-words'
            or response['audioSha256'] != audio_sha or response['canonicalPlainSha256'] != canonical_sha):
        raise ValueError('owner word prediction response binding differs')
    sources, admission, mapping, numerical_mapping, words = _owner_word_prediction_inputs(
        workspace, video_id, original, canonical_sha, audio_sha, retain_owner_evidence=False)
    if tuple(indices) != tuple(sources):
        raise ValueError('owner word prediction requires the complete source sequence')
    records = {}
    root = workspace.job_dir(video_id)
    for kind in ('candidate','mapping','forced','admission'):
        ref = response[kind]
        if (not isinstance(ref, dict) or set(ref) != {'path','sha256'}
                or not isinstance(ref['path'], str) or Path(ref['path']).name != ref['path']
                or not re.fullmatch(r'owner-word-prediction-[0-9a-f]{64}\.'+kind+r'\.private\.json', ref['path'])):
            raise ValueError('owner word prediction reference is invalid')
        raw = read_bounded_regular_bytes(root/ref['path'], max_bytes=MAX_JSON_BYTES, root=root)
        if hashlib.sha256(raw).hexdigest() != ref['sha256']:
            raise ValueError('owner word prediction artifact hash differs')
        records[kind] = parse_strict_json_bytes(raw)
    if records['admission'] != admission.to_record() or records['mapping'] != mapping:
        raise ValueError('owner word prediction content authority is stale')
    projected, duration = _validate_owner_prediction_result(Path(project), video_id, records['forced'],
        numerical_mapping, words, sources, audio_sha)
    candidate = records['candidate']
    source = copy.deepcopy(candidate)
    if not isinstance(source, dict) or not isinstance(source.get('tracks'), list):
        raise ValueError('owner word prediction candidate is malformed')
    source['tracks'] = [copy.deepcopy(original) if track.get('kind') == 'original' else track for track in source['tracks']]
    expected = build_predicted_sidecar(source, mapping, projected, duration, basis=OWNER_WORD_PREDICTION_BASIS)
    if 'vowelReleaseEvidence' in response:
        from stemwise_engine import endpoint_vowel_release as release
        observations = release.read_estimates(root,response['vowelReleaseEvidence'],audio_sha,duration)
        if numerical_mapping.get('_spokenTargets') is not None or not release.refine_estimated_track(expected,mapping,observations,duration):
            raise ValueError('Vowel evidence does not apply to this predicted performance')
        validate_lyrics(expected,duration_ms=duration)
    if expected != candidate:
        raise ValueError('owner word prediction candidate differs from acoustic words')
    return candidate, mapping, records['forced'], admission


def _owner_word_prediction_response(workspace, video_id, candidate, mapping, forced, admission,
                                    canonical_sha, audio_sha):
    response = {'version':1, 'videoId':video_id, 'status':'owner-predicted-words',
        'audioSha256':audio_sha, 'canonicalPlainSha256':canonical_sha}
    from stemwise_engine import endpoint_vowel_release as release
    original = next(t for t in candidate['tracks'] if t['kind']=='original')
    if release.english_track(original) and admission.spoken_targets is None:
        retained = _retained_content_state(workspace,video_id).get('estimatedVowelReleases') or {}
        if (retained.get('audioSha256')==audio_sha and retained.get('canonicalPlainSha256')==canonical_sha
                and retained.get('mapping')==lexical_mapping_identity(mapping)):
            try:
                ref = retained['evidence']
                duration = forced['provenance']['audio']['stream']['durationMs']
                observations = release.read_estimates(workspace.job_dir(video_id),ref,audio_sha,duration)
            except (OSError, ValueError, KeyError, TypeError) as error:
                print(f'Optional vowel evidence unavailable for this new prediction: {error}',file=sys.stderr)
            else:
                candidate = copy.deepcopy(candidate)
                if release.refine_estimated_track(candidate,mapping,observations,duration):
                    validate_lyrics(candidate,duration_ms=duration)
                    response['vowelReleaseEvidence'] = ref
    for kind, value in (('candidate',candidate), ('mapping',mapping), ('forced',forced),
                        ('admission',admission.to_record())):
        digest = hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=True,
            separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()
        path = workspace.job_dir(video_id)/f'owner-word-prediction-{digest}.{kind}.private.json'
        atomic_write_json(path, value)
        response[kind] = {'path':path.name, 'sha256':_sha256_file(path)}
    return response


def _load_or_run_wav2vec2_alignment(
    *,
    config: dict[str, Any],
    video_id: str,
    language: str,
    sources: list[int],
    duration_ms: int,
    workspace: AlignmentWorkspace,
    audio: Path | None,
    audio_identity: dict[str, Any],
    audio_view_id: str = "mix",
    temp_dir: Path,
    device: str,
    timeout_seconds: int,
    occurrence_mapping: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
    acoustic_language_lines: list[list[str]] | None = None,
    request_variant: str = "baseline",
    segments: list[dict[str, int]] | None = None,
    before_run: Callable[[], None] | None = None,
    runtime_cache: dict[tuple[Any, ...], dict[str, Any]] | None = None,
) -> tuple[dict[str, Any], bool, dict[str, Any]]:
    """Keep numeric endpoints native; return only the resolved plan in request time."""
    route = endpoints.WAV2VEC2_ROUTES_BY_AUDIO_VIEW.get(audio_view_id)
    if route is None:
        raise ValueError("unsupported Wav2Vec2 audio view")
    request_duration_ms = duration_ms
    duration_ms, segments = _endpoint_request_timing(audio_identity, duration_ms, segments)
    if audio_view_id == "mix":
        if (
            set(audio_identity)
            != {"sha256", "byteCount", "stream", "canonicalTimeTransform"}
            or audio_identity.get("canonicalTimeTransform")
            != audio_store.identity_transform(audio_identity["stream"])
        ):
            raise ValueError("Wav2Vec2 mix identity is not the canonical mix")
    elif audio_identity.get("routeId") != audio_view_id:
        raise ValueError("Wav2Vec2 audio identity belongs to a different view")
    if (
        re.fullmatch(r"[0-9a-f]{64}", str(audio_identity.get("sha256") or ""))
        is None
        or type(audio_identity.get("byteCount")) is not int
        or audio_identity["byteCount"] <= 0
    ):
        raise ValueError("Wav2Vec2 audio identity is malformed")
    project = Path(config["projectDir"])
    python = environment_python('main', project)
    helper = project / "src" / "wav2vec2_forced_alignment.py"
    model_assets = _wav2vec2_model_assets(config, language)
    if model_assets is None:
        raise ValueError("no pinned Wav2Vec2 model exists for the approved language")
    model, model_identity = model_assets
    for required in (python, helper):
        if not required.is_file():
            raise RuntimeError(f"pinned Wav2Vec2 dependency is missing: {required.name}")
    runtime = _wav2vec2_runtime_provenance(
        python, helper, device=device, timeout_seconds=timeout_seconds,
        attempt_cache=runtime_cache,
        diagnostic_path=workspace.job_dir(video_id) / 'wav2vec2-runtime.failure.private.txt',
    )
    request, expected_line_ids, expected_word_ids, mapping_identity = (
        _forced_alignment_request(
            video_id,
            sources,
            occurrence_mapping,
            display_word_lines,
            duration_ms=duration_ms,
            segments=segments,
            acoustic_language_lines=acoustic_language_lines,
        )
    )
    provenance = {
        "audio": _cache_audio_identity(audio_identity),
        "durationMs": duration_ms,
        "audioViewId": audio_view_id,
        "model": model_identity,
        "runtime": runtime,
        "decoder": {
            "chunkSeconds": 30,
            "strideSeconds": 5,
            "retainedCoreSeconds": 20,
            "emissionMap": "shared-global-overlap-trimmed",
            "channelMode": "mean-mono",
            "sampleRate": 16_000,
        },
    }
    helper_hashes = _wav2vec2_helper_hashes(project)
    helper_sha256 = helper_hashes["wav2vec2_forced_alignment.py"]
    request_helper_sha256 = helper_hashes["mms_forced_alignment.py"]
    if request_variant not in {"baseline", "anchored"}:
        raise ValueError("unsupported Wav2Vec2 request variant")
    suffix = "-anchored" if request_variant == "anchored" else ""
    view_suffix = "" if audio_view_id == "mix" else f"-{audio_view_id}"
    cache_path = workspace.job_dir(video_id) / (
        f"wav2vec2-public-endpoints-v4-{language}{view_suffix}{suffix}.private.json"
    )
    def admit_cached_result(cached):
        cached_request = _resolved_alignment_request(request, cached.get("alignmentRequestRecovery"), allow_repair=request_variant == "anchored")
        cached_segments = cached_request["segments"]
        if "resolvedSegments" in cached and cached["resolvedSegments"] != cached_segments:
            raise ValueError("cached alignment resolved segments are stale")
        cached_request_sha256 = _alignment_request_sha256(cached_request)
        if not (cached is not None
                and cached.get("cacheVersion") == WAV2VEC2_CACHE_VERSION
                and cached.get("videoId") == video_id
                and cached.get("language") == language
                and cached.get("route") == route
                and cached.get("requestVariant") == request_variant
                and cached.get("requestSha256") == cached_request_sha256
                and _ctc_cache_helpers_match(
                    {"wav2vec2_forced_alignment.py": cached.get("helperSha256"),
                     "mms_forced_alignment.py": cached.get("requestHelperSha256")}, helper_hashes)
                and cached.get("provenance") == provenance
                and cached.get("mappingIdentity") == mapping_identity):
            return None
        _validate_numeric_mms_result(
            cached,
            expected_sources=sources,
            duration_ms=duration_ms,
            expected_line_ids=expected_line_ids,
            expected_word_ids=expected_word_ids,
            expected_format_version=WAV2VEC2_RESULT_VERSION,
            expected_engine="wav2vec2-ctc-forced-alignment",
            expected_segments=cached_segments,
        )
        result = {**cached, "resolvedSegments": cached_segments}
        return {**result, "resolvedSegments": _project_endpoint_segment_eof(result, request_duration_ms)}, True, model_identity

    cached_result = _admit_alignment_cache(cache_path, admit_cached_result)
    if cached_result is not None:
        return cached_result

    if audio is None or not audio.is_file():
        raise worker_protocol.MissingAlignmentAudio("Wav2Vec2 acoustic audio is unavailable")
    supplied_identity = audio_store.file_identity(audio)
    if any(
        supplied_identity[key] != audio_identity.get(key)
        for key in ("sha256", "byteCount", "stream")
    ):
        raise ValueError("Wav2Vec2 acoustic audio differs from its bound identity")
    if before_run is not None:
        before_run()
    temp_dir.mkdir(parents=True, exist_ok=True)
    request_stem = f"wav2vec2-{audio_view_id}-{request_variant}"
    request_path = temp_dir / f"{request_stem}.request.private.json"
    output_path = temp_dir / f"{request_stem}.output.private.json"
    failure_path = workspace.job_dir(video_id) / f"{request_stem}.failure.private.txt"
    atomic_write_json(request_path, request)
    environment = os.environ.copy()
    environment.update(
        {
            "HF_HUB_OFFLINE": "1",
            "HF_HUB_DISABLE_TELEMETRY": "1",
            "TRANSFORMERS_OFFLINE": "1",
        }
    )
    recovery = None
    completed, final_request, recovery = _run_alignment_worker_batch(
        config=config, python=python, helper=helper, audio=audio, audio_sha256=audio_identity["sha256"],
        request=request, request_path=request_path, output_path=output_path, device=device,
        timeout_seconds=timeout_seconds, cache_key=_emission_cache_key(provenance, helper_hashes, device),
        producer={key: provenance[key] for key in ("model", "runtime", "decoder") if key in provenance},
        environment=environment, failure_path=failure_path, repair=request_variant == "anchored",
        model=model, language=str(model_identity.get("phonemizerLanguage") or language))
    request_segments = final_request["segments"]
    request_sha256 = _alignment_request_sha256(final_request)
    if completed.returncode == 3:
        capability = _read_optional_cache_json(output_path)
        if (
            capability is not None
            and set(capability) == {"formatVersion", "engine", "requestSha256", "supported"}
            and type(capability["formatVersion"]) is int
            and capability["formatVersion"] == 1
            and capability["engine"] == "wav2vec2-target-capability"
            and capability["requestSha256"] == request_sha256
            and capability["supported"] is False
        ):
            failure_path.unlink(missing_ok=True)
            raise Wav2Vec2TargetUnsupported("Wav2Vec2 target vocabulary is unsupported")
    if completed.returncode != 0 or not output_path.is_file():
        worker_protocol.raise_worker_failure(
            "Wav2Vec2 forced alignment failed",
            completed,
            output_path,
            diagnostic_path=failure_path,
        )
    result = _read_json(output_path)
    _validate_numeric_mms_result(
        result,
        expected_sources=sources,
        duration_ms=duration_ms,
        expected_line_ids=expected_line_ids,
        expected_word_ids=expected_word_ids,
        expected_format_version=WAV2VEC2_RESULT_VERSION,
        expected_engine="wav2vec2-ctc-forced-alignment",
        expected_segments=request_segments,
    )
    result.update(
        {
            "cacheVersion": WAV2VEC2_CACHE_VERSION,
            "videoId": video_id,
            "language": language,
            "route": route,
            "requestVariant": request_variant,
            "requestSha256": request_sha256,
            "helperSha256": helper_sha256,
            "requestHelperSha256": request_helper_sha256,
            "provenance": provenance,
            "mappingIdentity": mapping_identity,
        }
    )
    if recovery is not None:
        result["alignmentRequestRecovery"] = recovery
    result["resolvedSegments"] = request_segments
    _publish_alignment_cache(cache_path, result, failure_path=failure_path)
    return {**result, "resolvedSegments": _project_endpoint_segment_eof(result, request_duration_ms)}, False, model_identity


def _wav2vec2_helper_hashes(project: Path) -> dict[str, str]:
    return {
        name: ctc_worker.helper_source_identity(project / "src" / name)
        for name in ("mms_forced_alignment.py", "wav2vec2_forced_alignment.py")
    }


def _closed_endpoint_witness_candidates(
    result: dict[str, Any],
    *,
    expected_engine: str,
    expected_route: str,
    expected_format_version: int,
    duration_ms: int,
    request_sha256: str,
    request_byte_count: int,
    audio_identity: dict[str, Any],
    request_lines: list[dict[str, Any]],
) -> tuple[dict[str, Any], ...]:
    if (
        result.get("engine") != expected_engine
        or result.get("route") != expected_route
        or result.get("formatVersion") != expected_format_version
        or result.get("durationMs") != duration_ms
        or result.get("request")
        != {"sha256": request_sha256, "byteCount": request_byte_count}
        or result.get("audio")
        != {
            "sha256": audio_identity.get("sha256"),
            "byteCount": audio_identity.get("byteCount"),
        }
        or result.get("lineCount") != len(request_lines)
        or not isinstance(result.get("lines"), list)
        or len(result["lines"]) != len(request_lines)
    ):
        raise ValueError("closed endpoint witness result identity is invalid")
    semantics = result.get("endpointSemantics")
    if (expected_engine == owsm_ctc.ENGINE and semantics is not None
            and semantics != "first-to-last-approved-lexical-ctc-token-span-v1"):
        raise ValueError("closed endpoint witness semantics are invalid")

    def lexical_scores(record):
        if expected_engine != owsm_ctc.ENGINE or not ({"ctcLogScore", "acousticTokenCount"} & record.keys()):
            return {}
        score, count = record.get("ctcLogScore"), record.get("acousticTokenCount")
        if (type(score) not in (int, float) or not math.isfinite(score) or score > .000001
                or type(count) is not int or count <= 0):
            raise ValueError("closed endpoint witness acoustic scores are invalid")
        return {"ctcLogScore": score, "acousticTokenCount": count}

    candidates: list[dict[str, Any]] = []
    previous_end = 0
    for index, (line, expected) in enumerate(zip(result["lines"], request_lines)):
        words = line.get("words") if isinstance(line, dict) else None
        expected_words = expected["words"]
        if (
            not isinstance(line, dict)
            or line.get("lineIndex") != index
            or line.get("sourcePhysicalIndex") != expected["sourcePhysicalIndex"]
            or line.get("lineId") != expected["lineId"]
            or type(line.get("startMs")) is not int
            or type(line.get("endMs")) is not int
            or not previous_end <= line["startMs"] < line["endMs"] <= duration_ms
            or not isinstance(words, list)
            or len(words) != len(expected_words)
        ):
            raise ValueError("closed endpoint witness line result is invalid")
        candidate_words: list[dict[str, Any]] = []
        word_end = line["startMs"]
        for word, expected_word in zip(words, expected_words):
            if (
                not isinstance(word, dict)
                or word.get("wordId") != expected_word["wordId"]
                or type(word.get("startMs")) is not int
                or type(word.get("endMs")) is not int
                or not word_end <= word["startMs"] < word["endMs"] <= line["endMs"]
            ):
                raise ValueError("closed endpoint witness word result is invalid")
            candidate_words.append(
                {
                    "wordId": word["wordId"],
                    "startMs": word["startMs"],
                    "endMs": word["endMs"],
                    **lexical_scores(word),
                }
            )
            word_end = word["endMs"]
        if (
            candidate_words[0]["startMs"] != line["startMs"]
            or candidate_words[-1]["endMs"] != line["endMs"]
        ):
            raise ValueError("closed endpoint witness lexical span is inconsistent")
        candidates.append(
            {
                "lineId": line["lineId"],
                "route": expected_route,
                "startMs": line["startMs"],
                "endMs": line["endMs"],
                **({"endpointSemantics": semantics} if semantics is not None else {}),
                **lexical_scores(line),
                "words": tuple(candidate_words),
            }
        )
        previous_end = line["endMs"]
    return tuple(candidates)


def _worker_cuda_device(python, profile, *, timeout_seconds):
    from stemwise_engine.model_registry import validate_cuda_device
    pins = runtime_profile(profile)
    completed = run_process_tree([str(python), '-B', '-m', 'stemwise_engine.worker_runtime_probe',
        '--cuda-profile', profile], timeout=min(timeout_seconds, 300),
        text=True, capture_output=True, check=False, env=os.environ.copy())
    if completed.returncode != 0:
        raise RuntimeError('Selected worker CUDA runtime probe failed')
    value = parse_worker_json(completed.stdout)
    if (not isinstance(value, dict) or set(value) != {'profileId', 'python', 'torch', 'cudaRuntime', 'cudaDevice'}
            or value['profileId'] != pins['profileId'] or value['python'] != pins['PYTHON_VERSION']
            or value['torch'] != pins['TORCH_VERSION'] or value['cudaRuntime'] != pins['CUDA_VERSION']):
        raise RuntimeError('Selected worker CUDA runtime probe differs from exact pins')
    return validate_cuda_device(value['cudaDevice'], profile)


def _owsm_ctc_request(config, language, audio, audio_identity, lines, duration_ms, *, timeout_seconds=300):
    route = owsm_ctc.ROUTES_BY_LANGUAGE[language]
    project = Path(config["projectDir"])
    pc_root = project.parent
    runtime_root = pc_root / "runtime" / "espnet-202511-owsm"
    minor = '.'.join(owsm_ctc.PYTHON_VERSION.split('.')[:2])
    site_packages = runtime_root / '.venv' / 'lib' / f'python{minor}' / 'site-packages'
    snapshot = (
        pc_root
        / "model-cache"
        / "lyrics-alignment"
        / "models--espnet--owsm_ctc_v4_1B"
        / "snapshots"
        / owsm_ctc.MODEL_REVISION
    )
    model_run = snapshot / owsm_ctc.MODEL_RUN_RELATIVE_PATH

    def artifact(path: Path, sha256: str, byte_count: int) -> dict[str, Any]:
        return {"path": str(path), "sha256": sha256, "byteCount": byte_count}

    request = {
        "formatVersion": owsm_ctc.REQUEST_VERSION,
        "route": route,
        "durationMs": duration_ms,
        "audio": artifact(
            audio, str(audio_identity.get("sha256")), int(audio_identity.get("byteCount", 0))
        ),
        "model": {
            "revision": owsm_ctc.MODEL_REVISION,
            "checkpoint": artifact(
                model_run / owsm_ctc.CHECKPOINT_NAME,
                owsm_ctc.CHECKPOINT_SHA256,
                owsm_ctc.CHECKPOINT_BYTE_COUNT,
            ),
            "config": artifact(
                model_run / owsm_ctc.CONFIG_NAME,
                owsm_ctc.CONFIG_SHA256,
                owsm_ctc.CONFIG_BYTE_COUNT,
            ),
            "bpe": artifact(
                snapshot / owsm_ctc.BPE_RELATIVE_PATH,
                owsm_ctc.BPE_SHA256,
                owsm_ctc.BPE_BYTE_COUNT,
            ),
            "stats": artifact(
                snapshot / owsm_ctc.STATS_RELATIVE_PATH,
                owsm_ctc.STATS_SHA256,
                owsm_ctc.STATS_BYTE_COUNT,
            ),
        },
        "source": {
            "revision": owsm_ctc.ESPNET_SOURCE_REVISION,
            "align": artifact(
                site_packages / "espnet2" / "bin" / "s2t_ctc_align.py",
                owsm_ctc.ALIGN_SHA256,
                owsm_ctc.ALIGN_BYTE_COUNT,
            ),
            "helper": artifact(
                site_packages / "espnet2" / "tasks" / "s2t_ctc.py",
                owsm_ctc.HELPER_SHA256,
                owsm_ctc.HELPER_BYTE_COUNT,
            ),
        },
        "runtime": {
            "python": owsm_ctc.PYTHON_VERSION,
            "espnet": owsm_ctc.ESPNET_VERSION,
            "torch": owsm_ctc.TORCH_VERSION,
            "torchaudio": owsm_ctc.TORCHAUDIO_VERSION,
            "cudaRuntime": owsm_ctc.CUDA_VERSION,
            "cudaDevice": _worker_cuda_device(runtime_root/'.venv/bin/python', 'owsm_ctc_forced_alignment',
                timeout_seconds=timeout_seconds),
            "ctcSegmentation": owsm_ctc.CTC_SEGMENTATION_VERSION,
            "numpy": owsm_ctc.NUMPY_VERSION,
            "soundfile": owsm_ctc.SOUNDFILE_VERSION,
            "libsndfile": owsm_ctc.LIBSNDFILE_VERSION,
            "wheel": artifact(
                runtime_root / owsm_ctc.ESPNET_WHEEL_NAME,
                owsm_ctc.ESPNET_WHEEL_SHA256,
                owsm_ctc.ESPNET_WHEEL_BYTE_COUNT,
            ),
            "lock": artifact(
                runtime_root / owsm_ctc.RUNTIME_LOCK_NAME,
                owsm_ctc.RUNTIME_LOCK_SHA256,
                owsm_ctc.RUNTIME_LOCK_BYTE_COUNT,
            ),
        },
        "lines": lines,
    }
    return request


def _load_or_run_owsm_ctc_alignment(
    *,
    config: dict[str, Any],
    video_id: str,
    language: str,
    sources: list[int],
    duration_ms: int,
    workspace: AlignmentWorkspace,
    audio: Path,
    audio_identity: dict[str, Any],
    temp_dir: Path,
    timeout_seconds: int,
    occurrence_mapping: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
    before_run: Callable[[], None] | None = None,
) -> tuple[tuple[dict[str, Any], ...], bool]:
    language = canonical_language(language)
    route = owsm_ctc.ROUTES_BY_LANGUAGE.get(language)
    if route is None:
        raise ValueError(f"OWSM-CTC endpoint witness has no route for {language}")
    request_base, _, _, mapping_identity = _forced_alignment_request(
        video_id,
        sources,
        occurrence_mapping,
        display_word_lines,
        duration_ms=duration_ms,
    )
    project = Path(config["projectDir"])
    python = project.parent / "runtime/espnet-202511-owsm/.venv/bin/python"
    helper = project / "src/owsm_ctc_forced_alignment.py"
    request = _owsm_ctc_request(config, language, audio, audio_identity, request_base["lines"], duration_ms,
        timeout_seconds=timeout_seconds)
    request_raw = (
        json.dumps(request, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    request_sha256 = hashlib.sha256(request_raw).hexdigest()
    helper_sha256 = semantic_source_identity(helper)
    decoder_helper_sha256 = ctc_worker.helper_source_identity(project / "src" / "mms_forced_alignment.py")
    acoustic_helper_sha256 = semantic_source_identity(project / 'src/acoustic_inputs.py',
        ('informative_views', 'mono_samples', 'mono_representation'))
    cache_path = workspace.job_dir(video_id) / f"owsm-ctc-public-endpoints-v1-{language}.private.json"
    def admit_cached_result(cached):
        if not (cached is not None
                and cached.get("cacheVersion") == OWSM_CTC_CACHE_VERSION
                and cached.get("videoId") == video_id
                and cached.get("language") == language
                and _ctc_cache_helpers_match(
                    {"owsm_ctc_forced_alignment.py": cached.get("helperSha256"),
                     'acoustic_inputs.py': cached.get('acousticHelperSha256'),
                     "mms_forced_alignment.py": cached.get("decoderHelperSha256")},
                    {"owsm_ctc_forced_alignment.py": helper_sha256,
                     'acoustic_inputs.py': acoustic_helper_sha256,
                     "mms_forced_alignment.py": decoder_helper_sha256})
                and cached.get("mappingIdentity") == mapping_identity):
            return None
        candidates = _closed_endpoint_witness_candidates(
            cached,
            expected_engine=owsm_ctc.ENGINE,
            expected_route=route,
            expected_format_version=owsm_ctc.FORMAT_VERSION,
            duration_ms=duration_ms,
            request_sha256=request_sha256,
            request_byte_count=len(request_raw),
            audio_identity=audio_identity,
            request_lines=request["lines"],
        )
        return candidates, True

    cached_result = _admit_alignment_cache(cache_path, admit_cached_result)
    if cached_result is not None:
        return cached_result
    required = [python, helper, audio]
    required.extend(
        Path(value["path"])
        for section in (request["model"], request["source"], request["runtime"])
        for value in section.values()
        if isinstance(value, dict) and "path" in value
    )
    if any(not path.is_file() for path in required):
        raise RuntimeError("pinned OWSM-CTC dependency is unavailable")
    if before_run is not None:
        before_run()
    temp_dir.mkdir(parents=True, exist_ok=True)
    request_path = temp_dir / "owsm-ctc.request.private.json"
    output_path = temp_dir / "owsm-ctc.output.private.json"
    completed = _run_closed_alignment_worker(
        python=python, helper=helper, request=request, request_path=request_path,
        output_path=output_path, request_sha256=request_sha256, timeout_seconds=timeout_seconds,
        environment={
            **os.environ,
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
            "HF_DATASETS_OFFLINE": "1",
        })
    if completed.returncode != 0 or not output_path.is_file():
        worker_protocol.raise_worker_failure(
            "OWSM-CTC endpoint alignment failed",
            completed,
            output_path,
            diagnostic_path=cache_path.with_name("owsm-ctc.failure.private.txt"),
        )
    result = _read_json(output_path)
    candidates = _closed_endpoint_witness_candidates(
        result,
        expected_engine=owsm_ctc.ENGINE,
        expected_route=route,
        expected_format_version=owsm_ctc.FORMAT_VERSION,
        duration_ms=duration_ms,
        request_sha256=request_sha256,
        request_byte_count=len(request_raw),
        audio_identity=audio_identity,
        request_lines=request["lines"],
    )
    result.update(
        cacheVersion=OWSM_CTC_CACHE_VERSION,
        videoId=video_id,
        language=language,
        helperSha256=helper_sha256,
        decoderHelperSha256=decoder_helper_sha256,
        acousticHelperSha256=acoustic_helper_sha256,
        mappingIdentity=mapping_identity,
    )
    _publish_alignment_cache(cache_path, result,
        failure_path=cache_path.with_name("owsm-ctc.failure.private.txt"))
    return candidates, False


def _load_or_run_nemo_nfa_alignment(
    *,
    config: dict[str, Any],
    video_id: str,
    language: str,
    sources: list[int],
    duration_ms: int,
    workspace: AlignmentWorkspace,
    audio: Path,
    audio_identity: dict[str, Any],
    temp_dir: Path,
    timeout_seconds: int,
    occurrence_mapping: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
    before_run: Callable[[], None] | None = None,
) -> tuple[tuple[dict[str, Any], ...], bool]:
    if canonical_language(language) != "ar":
        raise ValueError("NeMo NFA endpoint witness is pinned only for Arabic")
    request_base, _, _, mapping_identity = _forced_alignment_request(
        video_id,
        sources,
        occurrence_mapping,
        display_word_lines,
        duration_ms=duration_ms,
    )
    project = Path(config["projectDir"])
    pc_root = project.parent
    runtime_root = pc_root / "runtime" / "nemo-speech-3.0.0"
    snapshot = (
        pc_root
        / "model-cache"
        / "lyrics-alignment"
        / "models--nvidia--stt_ar_fastconformer_hybrid_large_pcd_v1.0"
        / "snapshots"
        / nemo_nfa.MODEL_REVISION
    )
    helper = project / "src" / "nemo_nfa_forced_alignment.py"
    python = runtime_root / ".venv" / "bin" / "python"
    model = snapshot / nemo_nfa.MODEL_ARTIFACT
    align = runtime_root / "tools" / "nemo_forced_aligner" / "align.py"
    lock = runtime_root / "uv.lock"
    request = {
        "formatVersion": nemo_nfa.FORMAT_VERSION,
        "route": nemo_nfa.ROUTE,
        "cudaDevice": _worker_cuda_device(python, 'nemo_nfa_forced_alignment', timeout_seconds=timeout_seconds),
        "durationMs": duration_ms,
        "audio": {
            "path": str(audio),
            "sha256": str(audio_identity.get("sha256")),
            "byteCount": int(audio_identity.get("byteCount", 0)),
        },
        "model": {
            "path": str(model),
            "revision": nemo_nfa.MODEL_REVISION,
            "sha256": nemo_nfa.MODEL_SHA256,
            "byteCount": nemo_nfa.MODEL_BYTE_COUNT,
        },
        "nfa": {
            "sourceRoot": str(runtime_root),
            "sourceRevision": nemo_nfa.NEMO_SOURCE_REVISION,
            "alignPath": str(align),
            "alignSha256": nemo_nfa.NFA_ALIGN_SHA256,
            "lockPath": str(lock),
            "lockSha256": nemo_nfa.NEMO_LOCK_SHA256,
            "lockByteCount": nemo_nfa.NEMO_LOCK_BYTE_COUNT,
        },
        "lines": request_base["lines"],
    }
    request_raw = (
        json.dumps(request, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    request_sha256 = hashlib.sha256(request_raw).hexdigest()
    helper_sha256 = semantic_source_identity(helper)
    cache_path = workspace.job_dir(video_id) / "nemo-nfa-public-endpoints-v1-ar.private.json"
    acoustic_helper_sha256 = semantic_source_identity(project / 'src/acoustic_inputs.py',
        ('mono_representation', 'audio_mono_representation'))
    def admit_cached_result(cached):
        if not (cached is not None
                and cached.get("cacheVersion") == NEMO_NFA_CACHE_VERSION
                and cached.get("videoId") == video_id
                and cached.get("language") == "ar"
                and _ctc_cache_helpers_match(
                    {'nemo_nfa_forced_alignment.py': cached.get('helperSha256'),
                     'acoustic_inputs.py': cached.get('acousticHelperSha256')},
                    {'nemo_nfa_forced_alignment.py': helper_sha256,
                     'acoustic_inputs.py': acoustic_helper_sha256})
                and cached.get("mappingIdentity") == mapping_identity):
            return None
        candidates = _closed_endpoint_witness_candidates(
            cached,
            expected_engine=nemo_nfa.ENGINE,
            expected_route=nemo_nfa.ROUTE,
            expected_format_version=nemo_nfa.FORMAT_VERSION,
            duration_ms=duration_ms,
            request_sha256=request_sha256,
            request_byte_count=len(request_raw),
            audio_identity=audio_identity,
            request_lines=request["lines"],
        )
        return candidates, True

    cached_result = _admit_alignment_cache(cache_path, admit_cached_result)
    if cached_result is not None:
        return cached_result
    if any(not path.is_file() for path in (python, helper, audio, model, align, lock)):
        raise RuntimeError("pinned NeMo NFA dependency is unavailable")
    if before_run is not None:
        before_run()
    temp_dir.mkdir(parents=True, exist_ok=True)
    request_path = temp_dir / "nemo-nfa.request.private.json"
    output_path = temp_dir / "nemo-nfa.output.private.json"
    completed = _run_closed_alignment_worker(
        python=python, helper=helper, request=request, request_path=request_path,
        output_path=output_path, request_sha256=request_sha256, timeout_seconds=timeout_seconds,
        environment={
            **os.environ,
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
            "HF_DATASETS_OFFLINE": "1",
            "TMPDIR": str(temp_dir),
        })
    if completed.returncode != 0 or not output_path.is_file():
        worker_protocol.raise_worker_failure(
            "NeMo NFA endpoint alignment failed",
            completed,
            output_path,
            diagnostic_path=cache_path.with_name("nemo-nfa.failure.private.txt"),
        )
    result = _read_json(output_path)
    candidates = _closed_endpoint_witness_candidates(
        result,
        expected_engine=nemo_nfa.ENGINE,
        expected_route=nemo_nfa.ROUTE,
        expected_format_version=nemo_nfa.FORMAT_VERSION,
        duration_ms=duration_ms,
        request_sha256=request_sha256,
        request_byte_count=len(request_raw),
        audio_identity=audio_identity,
        request_lines=request["lines"],
    )
    result.update(
        cacheVersion=NEMO_NFA_CACHE_VERSION,
        acousticHelperSha256=acoustic_helper_sha256,
        videoId=video_id,
        language="ar",
        helperSha256=helper_sha256,
        mappingIdentity=mapping_identity,
    )
    _publish_alignment_cache(cache_path, result,
        failure_path=cache_path.with_name("nemo-nfa.failure.private.txt"))
    return candidates, False


def _load_final_mms_alignment(
    *,
    config: dict[str, Any],
    video_id: str,
    original: dict[str, Any],
    alignment: AlignmentResult,
    occurrence_mapping: dict[str, Any],
    duration_ms: int,
    workspace: AlignmentWorkspace,
    temp_root: Path,
    device: str,
    timeout_seconds: int,
    expected_mix_sha256: str | None = None,
    analysis_audio: Path | None = None,
    audio_view_id: str = "htdemucs-vocals",
    get_audio: Callable[[], tuple[Path, dict[str, Any], dict[str, Any]]] | None = None,
    request_variant: str = "baseline",
    segments: list[dict[str, int]] | None = None,
    request_duration_ms: int | None = None,
    runtime_cache: dict[tuple[Any, ...], dict[str, Any]] | None = None,
) -> tuple[dict[str, Any], bool]:
    """Return numeric MMS line/word endpoints on the canonical recording."""
    project = Path(config["projectDir"])
    mms_python = environment_python('main', project)
    helper = project / "src" / "mms_forced_alignment.py"
    agent_root = project.parent
    demucs_model = agent_root / "model-cache" / "torch" / "hub" / "checkpoints" / "955717e8-8726e21a.th"
    mms_model = agent_root / "model-cache" / "torch" / "hub" / "checkpoints" / "model.pt"
    required_paths = [mms_python, helper, mms_model]
    if audio_view_id == "htdemucs-vocals":
        required_paths.append(demucs_model)
    for required in required_paths:
        if not required.is_file():
            raise RuntimeError(f"pinned endpoint dependency is missing: {required.name}")
    variants = {
        "htdemucs-vocals": "public-endpoints-v3",
        "kimberley-jensen-roformer-vocals": "public-endpoints-v3-kim-roformer",
        "sucial-v2-roformer-dry-vocals": (
            "public-endpoints-v3-sucial-dereverb-v2"
        ),
    }
    variant = variants.get(audio_view_id)
    if variant is None:
        raise ValueError("unsupported endpoint audio view")
    if request_variant not in {"baseline", "anchored", "prior-recording-seed"} and (
            request_variant != "vocal-entry" or audio_view_id != "htdemucs-vocals"):
        raise ValueError("unsupported MMS endpoint request variant")
    if request_variant != "baseline":
        variant += "-" + request_variant
    request_duration_ms = request_duration_ms or duration_ms

    sources = list(alignment.source_physical_indices)
    physical = _plain_physical_lines(original["text"])
    word_lines = [display_words(physical[source]) for source in sources]
    runtime = _mms_runtime_provenance(
        mms_python,
        helper,
        device=device,
        timeout_seconds=timeout_seconds,
        attempt_cache=runtime_cache,
        diagnostic_path=workspace.job_dir(video_id) / 'mms-runtime.failure.private.txt',
    )

    def load(
        vocals: Path | None, temporary: Path, provenance: dict[str, Any]
    ) -> tuple[dict[str, Any], bool]:
        native_duration_ms, native_segments = _endpoint_request_timing(
            provenance["audio"], request_duration_ms, segments
        )
        result, reused = _load_or_run_mms_alignment(
            config=config,
            video_id=video_id,
            variant=variant,
            sources=sources,
            acoustic_track_kind=f"original-display-uroman:{audio_view_id}",
            duration_ms=native_duration_ms,
            workspace=workspace,
            helper=helper,
            mms_python=mms_python,
            vocals_audio=vocals,
            temp_dir=temporary,
            device=device,
            timeout_seconds=timeout_seconds,
            provenance=provenance,
            occurrence_mapping=occurrence_mapping,
            display_word_lines=word_lines,
            segments=native_segments,
        )
        projected = _project_endpoint_eof(result, duration_ms, request_duration_ms=request_duration_ms)
        # Repair orchestration uses mix time independently of public Original endpoints.
        if 'resolvedSegments' in result:
            projected = {**projected, 'resolvedSegments': _project_endpoint_segment_eof(result, request_duration_ms)}
        return projected, reused

    stored = audio_store.read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    )
    stored_vocals = (
        stored.get("audioViews", {}).get(audio_view_id)
        if isinstance(stored, dict)
        and isinstance(stored.get("audioViews"), dict)
        and isinstance(stored["audioViews"].get(audio_view_id), dict)
        else None
    )
    try:
        if stored_vocals is None:
            raise FileNotFoundError("cached vocals identity is missing")
        provenance = _forced_alignment_provenance(
            audio_identity=stored_vocals,
            duration_ms=_worker_pcm_duration_ms(stored_vocals),
            mms_model=mms_model,
            runtime=runtime,
        )
        return load(None, temp_root, provenance)
    except FileNotFoundError:
        if get_audio is not None:
            vocals, identity, _ = get_audio()
            provenance = _forced_alignment_provenance(
                audio_identity=identity,
                duration_ms=_worker_pcm_duration_ms(identity),
                mms_model=mms_model,
                runtime=runtime,
            )
            return load(vocals, temp_root, provenance)
        if audio_view_id != "htdemucs-vocals":
            raise RuntimeError("RoFormer endpoint audio view could not be reconstructed")
        temp_root.mkdir(parents=True, exist_ok=True)
        with WorkerTemporaryDirectory(prefix=f"mms-final-{video_id}-", dir=temp_root) as name:
            temporary = Path(name)
            if analysis_audio is None:
                _, audio = download_analysis_audio(
                    video_id, temporary, timeout_seconds=timeout_seconds
                )
            else:
                audio = analysis_audio
            vocals = separate_vocals_demucs(
                audio,
                temporary / "demucs",
                project_dir=project,
                device=device,
                timeout_seconds=timeout_seconds,
            )
            audio_provenance = audio_store.write_audio_provenance(
                workspace,
                video_id,
                duration_ms=duration_ms,
                mix_audio=audio,
                vocals_audio=vocals,
                demucs_model_sha256=_sha256_file(demucs_model),
                expected_mix_sha256=expected_mix_sha256,
            )
            provenance = _forced_alignment_provenance(
                audio_identity=audio_provenance["audioViews"]["htdemucs-vocals"],
                duration_ms=_worker_pcm_duration_ms(
                    audio_provenance["audioViews"]["htdemucs-vocals"]
                ),
                mms_model=mms_model,
                runtime=runtime,
            )
            return load(vocals, temporary, provenance)


def _structure_candidate_diagnostics(
    video_id: str, original: dict[str, Any], result: AlignmentResult
) -> dict[str, Any]:
    """Return stable occurrence IDs and numeric timing evidence."""

    diagnostics: dict[str, Any] = {}
    try:
        mapping = build_lexical_occurrence_mapping(
            video_id, original, result.source_physical_indices
        )
    except ValueError:
        return diagnostics
    diagnostics["performedOccurrences"] = []
    starts_match = len(result.starts_ms) == len(mapping["performedOccurrences"])
    for value, start_ms in zip(
        mapping["performedOccurrences"],
        result.starts_ms if starts_match else (None,) * len(mapping["performedOccurrences"]),
    ):
        occurrence = {
            "occurrenceId": value["occurrenceId"],
            "performedIndex": value["performedIndex"],
            "sourcePhysicalIndex": value["sourcePhysicalIndex"],
        }
        if start_ms is not None:
            occurrence["startMs"] = start_ms
        diagnostics["performedOccurrences"].append(occurrence)
    return diagnostics


def _forced_alignment_verifier_candidates(
    forced: dict[str, Any],
    display_word_lines: list[list[tuple[int, int, str]]],
    *,
    expected_route: str,
    duration_ms: int,
    request_duration_ms: int | None = None,
) -> tuple[dict[str, Any], ...]:
    route = forced.get("route")
    if route != expected_route or route not in endpoints.WAV2VEC2_ROUTES_BY_AUDIO_VIEW.values():
        raise ValueError("forced endpoint verifier has the wrong audio route")
    forced = _project_endpoint_eof(
        {"durationMs": forced["durationMs"], "lines": forced.get("lines")},
        duration_ms, request_duration_ms=request_duration_ms,
    )
    lines = forced.get("lines")
    if not isinstance(lines, list) or len(lines) != len(display_word_lines):
        raise ValueError("forced endpoint verifier line count is invalid")
    output: list[dict[str, Any]] = []
    for line, ranges in zip(lines, display_word_lines):
        words = line.get("words") if isinstance(line, dict) else None
        if not isinstance(words, list) or len(words) != len(ranges):
            raise ValueError("forced endpoint verifier word count is invalid")
        output.append(
            {
                "lineId": line["lineId"],
                "route": route,
                "startMs": line["startMs"],
                "endMs": line["endMs"],
                **(
                    {"confidence": line["confidence"]}
                    if isinstance(line.get("confidence"), (int, float))
                    else {}
                ),
                "words": tuple(
                    {
                        "wordId": word["wordId"],
                        "startUtf16": start_utf16,
                        "endUtf16": end_utf16,
                        "startMs": word["startMs"],
                        "endMs": word["endMs"],
                        **(
                            {
                                "confidence": word["confidence"],
                                "acousticTokenCount": word["acousticTokenCount"],
                            }
                            if isinstance(word.get("confidence"), (int, float))
                            and type(word.get("acousticTokenCount")) is int
                            else {}
                        ),
                    }
                    for word, (start_utf16, end_utf16, _) in zip(words, ranges)
                ),
            }
        )
    return tuple(output)


def _needs_route_local_verifier(
    endpoint_summary: dict[str, Any],
    source_physical_indices: tuple[int, ...],
) -> bool:
    if len(set(source_physical_indices)) != len(source_physical_indices):
        raise ValueError("performed lyric occurrences must have distinct source identities")
    occurrences = endpoint_summary.get("occurrenceCandidates")
    if (
        not isinstance(occurrences, list)
        or len(occurrences) != len(source_physical_indices)
    ):
        raise ValueError("endpoint summary has the wrong occurrence count")
    return any(
        occurrence.get("publishDecision", {}).get("result") == "unresolved"
        and (
            occurrence["publishDecision"].get("code")
            in {"line-start-disagreement", "line-end-disagreement", "low-forced-line-support"}
            or str(occurrence["publishDecision"].get("code", "")).startswith("missing-")
        )
        for occurrence in occurrences
    )










def _build_initial_alignment_segments(
    text: str,
    sources: list[int],
    occurrence_mapping: dict[str, Any],
    duration_ms: int,
    *,
    free_family_candidates: dict[str, tuple[dict[str, Any] | None, ...]],
    transcripts: list[Any],
    transcript_labels: list[str],
) -> list[dict[str, int]]:
    def planning_lines():
        yield from free_family_candidates.values()
        for transcript, label in zip(transcripts, transcript_labels, strict=True):
            yield whisper_word_candidates(
                text, sources, [transcript], duration_ms,
                occurrence_mapping=occurrence_mapping, transcript_labels=[label],
            )

    # These hints freeze guarded worker windows only. Prompted recognition never
    # joins the independent endpoint or occurrence-identity evidence families.
    for lines in planning_lines():
        segments = endpoints._build_bootstrap_segments(len(sources), duration_ms, lines)
        if segments is None:
            continue
        return segments
    return [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": len(sources),
             "windowStartMs": 0, "windowEndMs": duration_ms}]




















def _run_conditional_cascade(
    route_ids: tuple[str, ...],
    evaluate: Callable[[str], tuple[bool, Any]],
    *,
    on_failure: Callable[[str, str], None] | None = None,
    prior_technical_failure: bool = False,
    diagnostic_path: Path | None = None,
    diagnostic_context: dict[str, Any] | None = None,
    recover_completed_failures: Callable[[], bool] | None = None,
) -> tuple[str, Any]:
    """Run ordered fallbacks, preserving technical status only after exhaustion."""

    if not route_ids:
        raise ValueError("a conditional cascade needs at least one route")
    last_route = route_ids[0]
    last_value: Any = None
    technical_failure = prior_technical_failure
    last_error = None
    for route_id in route_ids:
        try:
            resolved, last_value = evaluate(route_id)
        except (LyricsContentBlocker, WorkerRetirementError):
            raise
        except Exception as error:
            endpoints._require_recoverable_endpoint_failure(error)
            technical_failure = True
            last_error = error
            code = worker_protocol.technical_diagnostic_code(error, diagnostic_path,
                context={**(diagnostic_context or {}), 'route': route_id})
            if on_failure is not None:
                on_failure(route_id, code)
            continue
        last_route = route_id
        if resolved:
            return last_route, last_value
    if technical_failure and not (
        last_value is not None
        and recover_completed_failures is not None
        and recover_completed_failures() is True
    ):
        raise ConditionalCascadeTechnicalError(
            "conditional cascade exhausted after a route-local technical failure"
        ) from last_error
    return last_route, last_value


def _partition_endpoint_route_failures(
    route_failures: list[dict[str, str]],
    endpoint_route_attempts: list[dict[str, Any]],
) -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    completed = {
        attempt["routeId"].removesuffix(":baseline")
        for attempt in endpoint_route_attempts
        if isinstance(attempt.get("routeId"), str)
        and attempt["routeId"].endswith(":baseline")
    }
    if not set(endpoints.ENDPOINT_MMS_ROUTES).issubset(completed):
        return [], list(route_failures)
    recovered = [
        failure
        for failure in route_failures
        if failure["stage"] == "structure"
        or failure["stage"]
        in {"endpoint-consensus", "endpoint-verifier", "endpoint-bootstrap"}
        or (
            failure["stage"] == "endpoint"
            and failure["routeId"] in completed
        )
    ]
    return recovered, [failure for failure in route_failures if failure not in recovered]


from stemwise_engine.workflow_records import route_attempt_event as _route_attempt_event


@dataclass(frozen=True)
class WhisperRoute:
    label: str
    variant: str
    vocals: bool = False
    precision: bool = False
    prompted: bool = True
    admit_content: bool = False


WHISPER_MIX_ROUTE = WhisperRoute('faster-whisper', 'unprompted-mix', prompted=False, admit_content=True)
WHISPER_VOCAL_ROUTE = WhisperRoute('faster-whisper-vocals', 'vocals', vocals=True)
WHISPER_PRECISION_ROUTE = WhisperRoute('faster-whisper-precision', 'precision-mix', precision=True)
WHISPER_HOLDOUT_ROUTE = WhisperRoute('faster-whisper-precision-unprompted',
    'precision-unprompted-mix', precision=True, prompted=False)
WHISPER_PRECISION_VOCAL_ROUTE = WhisperRoute('faster-whisper-precision-vocals',
    'precision-vocals', vocals=True, precision=True)


@dataclass(frozen=True)
class PreparedAlignmentText:
    lyrics: dict[str, Any]
    original: dict[str, Any]
    original_raw: bytes
    expected_current_raw: bytes
    canonical_plain_sha256: str


def _prepare_alignment_text(*, sidecar_path, sidecars, workspace, video_id,
                            realign_synced, expected_canonical_sha256, state):
    if not sidecar_path.is_file():
        raise FileNotFoundError("approved lyric sidecar is missing")
    try:
        lyrics, original_raw = load_sidecar(sidecar_path, root=sidecars)
    except ValueError:
        raise LyricsContentBlocker("content-text-invalid") from None
    expected_sidecar_raw = original_raw
    original = _original_track(lyrics)
    if original is None or lyrics.get("status") == "unavailable":
        raise LyricsContentBlocker("content-text-unavailable")
    if original.get("format") == "synced":
        if realign_synced:
            backup = workspace.current_backup_path(video_id)
            if not backup.is_file():
                raise LyricsContentBlocker("content-canonical-unavailable")
            try:
                lyrics, original_raw = load_sidecar(backup, root=workspace.canonical_plain)
            except ValueError:
                raise LyricsContentBlocker("content-canonical-invalid") from None
            original = _original_track(lyrics)
            if original is None or original.get("format") != "plain":
                raise ValueError("canonical backup has no approved plain original")
            state["realignedFromCanonicalPlain"] = True
        else:
            return None
    if original.get("format") != "plain" or not isinstance(original.get("text"), str):
        raise LyricsContentBlocker("content-text-unavailable")
    loaded_plain_sha256 = hashlib.sha256(original_raw).hexdigest()
    if expected_canonical_sha256 is None:
        _, canonical_plain_sha256 = snapshot_canonical_plain(
            workspace, video_id, original_raw
        )
    else:
        canonical_plain_sha256 = expected_canonical_sha256
        backup = workspace.current_backup_path(video_id)
        if (
            loaded_plain_sha256 != canonical_plain_sha256
            or backup.name
            != f"{video_id}.{canonical_plain_sha256}.lyrics.json"
            or read_bounded_regular_bytes(
                backup,
                max_bytes=MAX_JSON_BYTES,
                root=workspace.canonical_plain,
            )
            != original_raw
        ):
            raise ValueError(
                "canonical plain snapshot does not match the loaded sidecar"
            )
    state["canonicalPlainSha256"] = canonical_plain_sha256
    return PreparedAlignmentText(lyrics, original, original_raw, expected_sidecar_raw,
                                 canonical_plain_sha256)


def _write_alignment_report(workspace, *, model, selected_count, counts, results):
    report = {
        "reportVersion": 2,
        "selected": selected_count,
        "counts": dict(counts),
        "model": model,
        "results": [
            {
                "videoId": value["videoId"],
                "position": value.get("position"),
                "status": value.get("status"),
                "reasons": value.get("reasons", []),
                "canonicalPlainSha256": value.get("canonicalPlainSha256"),
                "contentBinding": value.get("contentBinding"),
                "contentAdmission": value.get("contentAdmission"),
                "metrics": value.get("metrics"),
                "lexicalAuthoritySha256": value.get("lexicalAuthoritySha256"),
                "tokenizationVersion": value.get("tokenizationVersion"),
                "mappingVersion": value.get("mappingVersion"),
                "mappingSha256": value.get("mappingSha256"),
                "mappingArtifactSha256": value.get("mappingArtifactSha256"),
                "endpointEvidence": value.get("endpointEvidence"),
                "endpointRouteAttempts": value.get("endpointRouteAttempts", []),
                "routeFailures": value.get("routeFailures", []),
                "roformerCascade": value.get("roformerCascade"),
            }
            for value in results
        ],
    }
    workspace.reports.mkdir(parents=True, exist_ok=True)
    report_path = workspace.reports / "latest.json"
    atomic_write_json(
        report_path,
        report,
        root=workspace.reports,
        workspace_root=workspace.workspace_root,
    )
    return report


def _refine_alignment_endpoints(*, config, args, workspace, video_id, duration_ms, alignment_temp_root, state,
                                original, lyrics, alignment, spoken, evidence_options, recognition_evidence,
                                expected_mix_sha256, analysis_audio, audio_views, services,
                                route_diagnostic, route_failures, reviewed_lrc_starts):
    endpoint_verifier_transcripts = list(recognition_evidence.transcripts)
    endpoint_verifier_labels = list(recognition_evidence.labels)
    endpoint_verifier_reused = list(recognition_evidence.reused)
    canonical_mix, get_endpoint_audio = audio_views.canonical_mix, audio_views.get_endpoint_audio
    record_route_failure = services.events.failure
    release_model, runtime_probe_cache = services.release_model, services.runtime_probe_cache
    candidate = public_line_timings = None
    lexical_mapping = build_lexical_occurrence_mapping(
        video_id, original, alignment.source_physical_indices
    )
    if spoken is not None:
        lexical_mapping['_spokenTargets'] = spoken
    state.update(lexical_mapping_identity(lexical_mapping))
    state["status"] = "refining-endpoints"
    workspace.write_state(video_id, state)
    endpoint_route_names = {
        "htdemucs-vocals": "torchaudio-mms-fa-on-htdemucs-vocals",
        "kimberley-jensen-roformer-vocals": (
            "torchaudio-mms-fa-on-kimberley-jensen-roformer-vocals"
        ),
        "sucial-v2-roformer-dry-vocals": (
            "torchaudio-mms-fa-on-sucial-v2-roformer-dry-vocals"
        ),
    }
    endpoint_route_attempts = services.progress.endpoint_attempts
    free_family_candidates = endpoints.select_free_candidates(
        original, alignment, evidence_options, endpoint_verifier_transcripts,
        endpoint_verifier_labels, endpoint_verifier_reused, duration_ms, lexical_mapping)
    language = _endpoint_language(original)
    sources = list(alignment.source_physical_indices)
    source_occurrences = tuple(sources)
    physical = _plain_physical_lines(original["text"])
    display_word_lines = [
        display_words(physical[source]) for source in sources
    ]
    acoustic_language_lines = endpoints._component_phonemizer_languages(
        original, display_word_lines
    )
    verifier_family = _endpoint_verifier_family(
        original, display_word_lines
    )
    wav2vec2_language = (
        canonical_language(original.get("language")) or "mul"
    )
    wav2vec2_assets = (
        _wav2vec2_model_assets(config, wav2vec2_language)
        if verifier_family == endpoints.WAV2VEC2_FAMILY
        else None
    )
    stored_audio = audio_store.read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    )
    stored_mix = (
        stored_audio.get("mix") if isinstance(stored_audio, dict) else None
    )
    mix_audio: Path | None = None
    mix_identity: dict[str, Any] | None = None
    if isinstance(stored_mix, dict):
        endpoint_duration_ms = _worker_pcm_duration_ms(stored_mix)
    else:
        mix_audio, mix_identity = canonical_mix()
        endpoint_duration_ms = _worker_pcm_duration_ms(mix_identity)
    baseline_segments = _build_initial_alignment_segments(
        original["text"], sources, lexical_mapping, endpoint_duration_ms,
        free_family_candidates=free_family_candidates,
        transcripts=endpoint_verifier_transcripts,
        transcript_labels=endpoint_verifier_labels,
    )
    verifier_candidates: tuple[dict[str, Any] | None, ...] = tuple(
        None for _ in sources
    )
    verifier_reused = False
    if verifier_family == endpoints.WAV2VEC2_FAMILY:
        if wav2vec2_assets is None:
            raise RuntimeError("statically pinned Wav2Vec2 model is unavailable")
        if mix_audio is None or mix_identity is None:
            mix_audio, mix_identity = canonical_mix()
            if _worker_pcm_duration_ms(mix_identity) != endpoint_duration_ms:
                raise ValueError(
                    "canonical mix duration changed within the endpoint pass"
                )
        try:
            wav2vec2, verifier_reused, wav2vec2_identity = (
                services.workers.load_wav2vec2(
                    config=config,
                    video_id=video_id,
                    language=wav2vec2_language,
                    sources=sources,
                    duration_ms=endpoint_duration_ms,
                    workspace=workspace,
                    audio=mix_audio,
                    audio_identity=mix_identity,
                    temp_dir=alignment_temp_root,
                    device=args.device,
                    timeout_seconds=int(config["analysisTimeoutMinutes"])
                    * 60,
                    occurrence_mapping=lexical_mapping,
                    display_word_lines=display_word_lines,
                    acoustic_language_lines=acoustic_language_lines,
                    request_variant="baseline",
                    segments=baseline_segments,
                    before_run=release_model,
                    runtime_cache=runtime_probe_cache,
                )
            )
        except Wav2Vec2TargetUnsupported:
            raise LyricsContentBlocker("pronunciation-support-unavailable",
                                      binding=state["contentBinding"]) from None
        except LyricsContentBlocker:
            raise
        except Exception as error:
            code = route_diagnostic(error, 'endpoint-verifier', endpoints.WAV2VEC2_ROUTE,
                model=wav2vec2_assets[1].get('repository', endpoints.WAV2VEC2_ROUTE))
            record_route_failure(
                "endpoint-verifier",
                endpoints.WAV2VEC2_ROUTE,
                code,
            )
            raise
        else:
            state["pinnedModels"].update(
                wav2vec2ModelSha256=wav2vec2_identity["modelSha256"],
                wav2vec2SnapshotManifestSha256=wav2vec2_identity[
                    "snapshotManifestSha256"
                ],
            )
            verifier_candidates = _forced_alignment_verifier_candidates(
                wav2vec2,
                display_word_lines,
                expected_route=endpoints.WAV2VEC2_ROUTE,
                duration_ms=duration_ms,
                request_duration_ms=endpoint_duration_ms,
            )
    seeded_mms_segments = None
    if verifier_family == endpoints.WAV2VEC2_FAMILY and config.get("recordingInput"):
        from stemwise_engine.recording_timing_seed import discover_timing_seed
        timing_hints = discover_timing_seed(
            config, video_id=video_id, lexical_mapping=lexical_mapping,
            sources=sources, duration_ms=endpoint_duration_ms,
            native_verifier=verifier_candidates,
        )
        if timing_hints is not None:
            seeded_mms_segments = endpoints._build_bootstrap_segments(
                len(sources), endpoint_duration_ms,
                timing_hints, verifier_candidates,
            )
            if seeded_mms_segments is not None and len(seeded_mms_segments) < 2:
                seeded_mms_segments = None

    endpoint_free_family_candidates = {
        family: candidates
        for family, candidates in free_family_candidates.items()
        if family != verifier_family
    }
    independent_inputs = endpoints._independent_endpoint_inputs(
        original, lexical_mapping, display_word_lines,
        set(owsm_ctc.ROUTES_BY_LANGUAGE),
    )

    endpoint_stage = endpoints.EndpointAlignmentStage(
        request=EndpointAlignmentRequest(
            context=AlignmentContext(config=config, video_id=video_id, workspace=workspace,
                temp_root=alignment_temp_root, device=args.device, duration_ms=duration_ms,
                endpoint_duration_ms=endpoint_duration_ms),
            text=EndpointText(original=original, alignment=alignment, lexical_mapping=lexical_mapping,
                sources=sources, source_occurrences=source_occurrences, display_word_lines=display_word_lines,
                acoustic_language_lines=acoustic_language_lines),
            evidence=EndpointEvidence(baseline_segments=baseline_segments,
                seeded_mms_segments=seeded_mms_segments, verifier_candidates=verifier_candidates,
                verifier_family=verifier_family, verifier_reused=verifier_reused,
                wav2vec2_language=wav2vec2_language, free_family_candidates=endpoint_free_family_candidates,
                independent_inputs=independent_inputs, reviewed_lrc_starts=reviewed_lrc_starts,
                route_names=endpoint_route_names, content_admission=state["contentAdmission"]),
            audio=EndpointAudio(expected_mix_sha256=expected_mix_sha256, analysis_audio=analysis_audio,
                canonical_mix=canonical_mix, get_endpoint_audio=get_endpoint_audio,
                mix_audio=mix_audio, mix_identity=mix_identity,
                opening_stem_power=getattr(audio_views, 'opening_stem_power', None))),
        services=services)

    selected_mms_route, endpoint_selection = _run_conditional_cascade(
        endpoints.ENDPOINT_MMS_ROUTES,
        endpoint_stage.evaluate_endpoint_route,
        on_failure=lambda route_id, code: record_route_failure(
            "endpoint", route_id, code
        ),
        diagnostic_path=workspace.job_dir(video_id) / "endpoint.failure.private.txt",
        diagnostic_context={'attemptId': getattr(args, 'attempt_id', None), 'videoId': video_id,
            'stage': 'endpoint', 'model': args.model, 'device': args.device},
        recover_completed_failures=lambda: not _partition_endpoint_route_failures(
            route_failures, endpoint_route_attempts
        )[1],
    )
    recovered_failures, blocking_failures = (
        _partition_endpoint_route_failures(
            route_failures, endpoint_route_attempts
        )
    )
    if recovered_failures:
        state["recoveredRouteFailures"] = recovered_failures
    state["routeFailures"] = blocking_failures
    endpoint_summary = endpoint_selection.summary
    endpoint_stage.record_vowel_estimates(endpoint_summary)
    if selected_mms_route in endpoints.ROFORMER_ROUTES:
        state["roformerCascade"][
            "selectedEndpointRoute"
        ] = selected_mms_route
    state["endpointRouteAttempts"] = list(endpoint_route_attempts)
    if endpoint_summary["resultClass"] != "line-unresolved":
        public_line_timings = list(endpoint_selection.timings)
        candidate = build_synced_sidecar(
            lyrics,
            alignment,
            duration_ms,
            line_timings=public_line_timings,
        )
    return lexical_mapping, endpoint_selection, public_line_timings, candidate


@_alignment_sessions.alignment_worker_session()
def _align(
    config: dict[str, Any],
    args: argparse.Namespace,
    *,
    selected_override: list[dict[str, Any]] | None = None,
    paths_override: tuple[Path, Path, Path, AlignmentWorkspace] | None = None,
    temp_root_override: Path | None = None,
    state_fields_override: dict[str, Any] | None = None,
) -> dict[str, Any]:
    from stemwise_engine.lyrics_alignment import resolve_compute_type
    args.compute_type = resolve_compute_type(args.device, args.compute_type)
    if args.from_position and args.to_position and args.from_position > args.to_position:
        raise ValueError("--from-position cannot exceed --to-position")
    sidecars, output, model_cache, workspace = paths_override or _paths(config)
    alignment_runtime = pinned_models = None
    if selected_override is None:
        snapshot = _inventory(config)
        selected = _selected_items(args, snapshot)
    else:
        selected = selected_override
    counts: Counter[str] = Counter(selected=len(selected))
    results: list[dict[str, Any]] = []
    alignment_temp_root = temp_root_override or Path(config["tempDir"]) / "lyrics-alignment"
    configured_expected_mix_sha256 = getattr(args, "expected_mix_sha256", None)
    configured_analysis_audio = getattr(args, "analysis_audio", None)
    configured_canonical_plain_sha256 = getattr(
        args, "canonical_plain_sha256", None
    )
    reviewed_baseline_structure = getattr(
        args, "reviewed_baseline_structure", False
    )
    performed_structure_review_sha256 = getattr(
        args, "performed_structure_review_sha256", None
    )
    active_analysis_pipeline = None
    model_owner = None
    def get_model():
        if model_owner is None:
            raise RuntimeError('alignment runtime was not admitted')
        model_owner.failure_owner = (workspace, video_id, state)
        return model_owner.get()
    def release_model():
        if model_owner is not None:
            model_owner.close()

    for index, item in enumerate(selected, start=1):
        video_id = validate_video_id(str(item["videoId"]))
        print(f"[{index}/{len(selected)}] {video_id}", flush=True)
        sidecar_path = sidecars / f"{video_id}.lyrics.json"
        child_path = output / "children" / f"{video_id}.stemwise.zip"
        state: dict[str, Any] = {
            "videoId": video_id,
            "position": int(item["position"]),
        }
        if state_fields_override:
            state.update(state_fields_override)
        audio_views = None
        roformer_route_records: dict[str, dict[str, Any]] = {}
        roformer_route_failure_codes: dict[str, str] = {}
        route_failures: list[dict[str, str]] = []
        from stemwise_engine.alignment_attempt import RouteOutcomeLedger
        route_ledger = RouteOutcomeLedger()
        route_attempts = route_ledger.events
        # Runtime dependencies are fixed during this locked song attempt.
        runtime_probe_cache: dict[tuple[Any, ...], dict[str, Any]] = {}
        prior_state = _retained_content_state(workspace, video_id)
        from stemwise_engine.alignment_phases import AttemptCacheBindings
        cache_bindings = AttemptCacheBindings(state, workspace.job_dir(video_id))

        def tracked_whisper(**kwargs):
            result = _load_or_transcribe(**kwargs)
            variant = '-'.join([*(['precision'] if kwargs.get('precision') else []),
                *(['unprompted'] if not kwargs.get('use_approved_prompt', True) else []),
                'vocals' if kwargs['separate_vocals'] else 'mix'])
            return cache_bindings.bind('whisper-asr', workspace.transcript_path(
                video_id, kwargs['language'], variant), result)

        def tracked_roformer(**kwargs):
            result = _load_or_transcribe_roformer(**kwargs)
            variant = ('roformer-kim-vocals' if kwargs['route_id'] == 'kimberley-jensen-roformer-vocals'
                       else 'roformer-sucial-dereverb-v2')
            return cache_bindings.bind('whisper-asr', workspace.transcript_path(
                video_id, kwargs['language'], variant), result)

        def tracked_native(**kwargs):
            result = mms_asr.load_or_transcribe(**kwargs)
            path = workspace.job_dir(video_id) / f"native-asr-{kwargs.get('adapter', mms_asr.ADAPTER)}-{kwargs['route']}.private.json"
            return cache_bindings.bind('mms-asr', path, result)

        def tracked_qwen(**kwargs):
            result = _load_or_run_qwen3_structure_transcript(**kwargs, attempt_cache=runtime_probe_cache)
            language, view = kwargs.get('language'), kwargs.get('audio_view', 'mix')
            name = f'qwen-structure-{language}.private.json' if kwargs.get('separate_language_cache') and language else 'qwen-structure.private.json'
            if view != 'mix': name = f"qwen-structure-{view}-{language or 'auto'}.private.json"
            return cache_bindings.bind('qwen3-asr', workspace.job_dir(video_id) / name, result)

        def tracked_caption(**kwargs):
            result = _load_caption(**kwargs)
            return cache_bindings.bind('caption', workspace.caption_path(video_id), result) if result else result

        def tracked_mms(**kwargs):
            result = _load_final_mms_alignment(**kwargs)
            if not isinstance(result[0], dict) or not isinstance(result[0].get('variant'), str):
                return result
            return cache_bindings.bind('mms-fa', _forced_alignment_cache_path(
                workspace, video_id, result[0]['variant']), result)

        def tracked_wav2vec2(**kwargs):
            result = _load_or_run_wav2vec2_alignment(**kwargs)
            view = kwargs.get('audio_view_id', 'mix')
            suffix = '-anchored' if kwargs.get('request_variant') == 'anchored' else ''
            view_suffix = '' if view == 'mix' else f'-{view}'
            path = workspace.job_dir(video_id) / f"wav2vec2-public-endpoints-v4-{kwargs['language']}{view_suffix}{suffix}.private.json"
            return cache_bindings.bind('wav2vec2', path, result)

        def tracked_owsm(**kwargs):
            result = _load_or_run_owsm_ctc_alignment(**kwargs)
            path = workspace.job_dir(video_id) / f"owsm-ctc-public-endpoints-v1-{canonical_language(kwargs['language'])}.private.json"
            return cache_bindings.bind('owsm-ctc', path, result)

        def tracked_nfa(**kwargs):
            result = _load_or_run_nemo_nfa_alignment(**kwargs)
            return cache_bindings.bind('nemo-nfa', workspace.job_dir(video_id) /
                'nemo-nfa-public-endpoints-v1-ar.private.json', result)

        try:
            prepared = _prepare_alignment_text(sidecar_path=sidecar_path, sidecars=sidecars,
                workspace=workspace, video_id=video_id, realign_synced=args.realign_synced,
                expected_canonical_sha256=configured_canonical_plain_sha256, state=state)
            if prepared is None:
                state.update(status="already-synced", reasons=[])
                counts["alreadySynced"] += 1
                if not prior_state:
                    workspace.write_state(video_id, state)
                results.append(state)
                continue
            lyrics, original = prepared.lyrics, prepared.original
            original_raw, expected_sidecar_raw = prepared.original_raw, prepared.expected_current_raw
            canonical_plain_sha256 = prepared.canonical_plain_sha256
            duration_ms = validate_child_zip(
                child_path, config, expected_video_id=video_id
            )["analysis"]["durationMs"]
            if configured_expected_mix_sha256 is None and active_analysis_pipeline is None:
                from stemwise_engine.pipeline import StemwisePipeline
                active_analysis_pipeline = StemwisePipeline(config)
            expected_mix_sha256 = configured_expected_mix_sha256 or _checkpoint_mix_sha256(
                output,
                config,
                video_id,
                duration_ms=duration_ms,
                active_pipeline=active_analysis_pipeline,
            )
            from stemwise_engine.spoken_targets import load as load_spoken_targets
            state['contentBinding'] = lyric_content._binding(
                _baseline_source_indices(original['text']), canonical_plain_sha256, expected_mix_sha256)
            spoken = load_spoken_targets(workspace.root.parent/'jobs'/video_id, video_id, original,
                _baseline_source_indices(original['text']), expected_mix_sha256)
            lyric_content.require_pronunciation_support(original['text'], spoken)
            if spoken is not None:
                original = {**original, '_spokenTargets': spoken}
            if alignment_runtime is None:
                alignment_runtime = _verify_alignment_runtime(config)
                pinned_models = _verify_pinned_stack(config, model_cache, args.model)
                model_owner = models.WhisperModelOwner(config=config, model_name=args.model,
                    snapshot=_whisper_snapshot_path(model_cache), device=args.device, compute_type=args.compute_type)
                if configured_expected_mix_sha256 is None:
                    from stemwise_engine.pipeline import StemwisePipeline
                    active_analysis_pipeline = StemwisePipeline(config)
            state.update(pinnedModels=dict(pinned_models), pinnedRuntime=alignment_runtime)
            if prior_state.get("status") == "lyrics-content-unresolved":
                try:
                    _require_no_unchanged_content_block(prior_state, canonical_plain_sha256, expected_mix_sha256,
                        workspace=workspace, video_id=video_id, original=original)
                except LyricsContentBlocker:
                    state = prior_state
                    raise
            analysis_audio = (
                Path(configured_analysis_audio)
                if configured_analysis_audio is not None
                else None
            )
            if analysis_audio is not None:
                audio_store.write_audio_provenance(
                    workspace,
                    video_id,
                    duration_ms=duration_ms,
                    mix_audio=analysis_audio,
                    expected_mix_sha256=expected_mix_sha256,
                )

            audio_views = audio_store.AlignmentAudioViews(config=config, args=args, workspace=workspace,
                video_id=video_id, duration_ms=duration_ms, expected_mix_sha256=expected_mix_sha256,
                analysis_audio=analysis_audio, temp_root=alignment_temp_root, release_model=release_model,
                pinned_models=pinned_models, state=state, runtime_cache=runtime_probe_cache,
                route_records=roformer_route_records, failure_codes=roformer_route_failure_codes,
                separate_vocals=separate_vocals_demucs, run_roformer=_run_roformer_separation,
                worker_duration=_worker_pcm_duration_ms)
            canonical_mix = audio_views.canonical_mix
            get_htdemucs_audio = audio_views.get_htdemucs_audio
            refresh_roformer_cascade = audio_views.refresh_roformer_cascade
            record_roformer_route = audio_views.record_roformer_route
            get_roformer_audio = audio_views.get_roformer_audio
            get_endpoint_audio = audio_views.get_endpoint_audio

            def record_route_failure(stage: str, route_id: str, code: str) -> None:
                record_route_attempt(stage, route_id, 'failure', code=code)
                if route_id in endpoints.ROFORMER_ROUTES:
                    definition = endpoints.ROFORMER_ROUTES[route_id]
                    prior = roformer_route_records.get(route_id, {})
                    roformer_route_failure_codes[route_id] = code
                    roformer_route_records[route_id] = {
                        **prior,
                        "routeId": route_id,
                        "familyId": definition["familyId"],
                        "sourceViewId": definition["sourceViewId"],
                        "outputViewId": route_id,
                        "status": "failed",
                        "failureStage": stage,
                        "failureCode": code,
                    }
                    refresh_roformer_cascade()
                workspace.write_state(video_id, state)

            def route_diagnostic(error: Exception, stage: str, route_id: str, *, model: str = args.model):
                raise_if_operation_stopped(error)
                return worker_protocol.technical_diagnostic_code(error,
                    workspace.job_dir(video_id) / 'model-route.failure.private.txt',
                    context={'attemptId': getattr(args, 'attempt_id', None), 'videoId': video_id,
                             'stage': stage, 'route': route_id, 'model': model,
                             'device': args.device, 'audioSha256': expected_mix_sha256})

            def record_route_attempt(
                stage: str,
                route_id: str,
                status: str,
                *,
                cache_reused: bool | None = None,
                code: str | None = None,
            ) -> None:
                event = _route_attempt_event(
                    len(route_attempts), stage, route_id, status,
                    cache_reused=cache_reused, code=code,
                )
                route_ledger.record(event)
                route_failures[:] = route_ledger.failures
                state["routeAttempts"] = list(route_attempts)
                state['routeFailures'] = list(route_failures)
                workspace.write_state(video_id, state)

            reviewed_lrc_starts = _load_reviewed_lrc_starts(
                workspace,
                video_id,
                sidecar_raw=original_raw,
                duration_ms=duration_ms,
                editorial_sha256=getattr(args, "editorial_sha256", None),
            )
            repeat_structure_kind = internal_repeat_structure_kind(original["text"])
            content_sources = _baseline_source_indices(original["text"])

            from stemwise_engine.alignment_attempt import RecognitionAdmission, RecognitionAdmissionRequest
            recognition = RecognitionAdmission(RecognitionAdmissionRequest(video_id,
                json.dumps({key: value for key, value in original.items() if key != '_spokenTargets'},
                           ensure_ascii=False).encode('utf-8'), tuple(content_sources),
                canonical_plain_sha256, expected_mix_sha256, spoken), workspace, state)
            admit_recognition = recognition.admit
            admit_cached_recognition = recognition.cached

            def align_transcript(transcript: Sequence[Any], *, evidence_path: Path | None = None) -> AlignmentResult:
                return recognition.align(transcript, duration_ms, align_known_structure, evidence_path=evidence_path)

            def align_transcripts(transcripts: Sequence[Sequence[Any]], labels: Sequence[str]) -> AlignmentResult:
                return recognition.align_many(transcripts, [recognition_evidence.path_for(label) for label in labels],
                    duration_ms, align_known_structure_multi)

            state.update(
                status="caption-check",
                durationMs=duration_ms,
                model=args.model,
                internalRepeatStructureKind=repeat_structure_kind,
                reviewedLrcStartCount=len(reviewed_lrc_starts),
            )
            workspace.write_state(video_id, state)
            evidence_options: list[tuple[str, Any, dict[str, Any], bool]] = []
            from stemwise_engine.alignment_attempt import RecognitionEvidenceSet
            recognition_evidence = RecognitionEvidenceSet()
            def record_recognition_route(label, transcribe, *, evidence_path=None, admit=False):
                transcript, evidence, reused = transcribe()
                if admit:
                    record_route_attempt('structure', label, 'success', cache_reused=reused)
                    admit_recognition(evidence_path)
                alignment = align_transcript(transcript, evidence_path=evidence_path)
                if alignment.reasons == ('recognition-wording-mismatch',):
                    record_route_attempt('structure', label, 'unavailable', cache_reused=reused,
                        code='recognition-wording-mismatch')
                    coarse = recognition.source_line_anchors(evidence_path, duration_ms)
                    if coarse is not None:
                        alignment, anchor_evidence = coarse
                        evidence = {**evidence, 'coarseStructure': anchor_evidence}
                else:
                    recognition_evidence.add(label, transcript, reused, evidence_path)
                evidence_options.append((label, alignment, evidence, reused))
                return transcript, alignment, evidence, reused

            def run_whisper_route(route, language):
                path = workspace.transcript_path(video_id, language, route.variant)
                regenerate = args.rerun_transcript
                if route.admit_content:
                    regenerate = regenerate or not admit_cached_recognition(path)
                kwargs = dict(video_id=video_id, original=original, workspace=workspace,
                    model_name=args.model, model_cache=model_cache, language=language,
                    separate_vocals=route.vocals,
                    project_dir=Path(config['projectDir']),
                    device=args.device, compute_type=args.compute_type, duration_ms=duration_ms,
                    get_model=get_model, temp_root=alignment_temp_root,
                    timeout_seconds=int(config['analysisTimeoutMinutes']) * 60,
                    rerun=regenerate, expected_mix_sha256=expected_mix_sha256,
                    analysis_audio=analysis_audio)
                if route.vocals:
                    kwargs['vocals_provider'] = lambda: get_htdemucs_audio()[0]
                if route.precision:
                    kwargs['precision'] = True
                if not route.prompted:
                    kwargs['use_approved_prompt'] = False
                return record_recognition_route(f"{route.label}:{language or 'auto'}",
                    lambda: tracked_whisper(**kwargs), evidence_path=path if route.admit_content else None,
                    admit=route.admit_content)

            if native_adapters := mms_asr.adapters_for(original):
                state.update(status="transcribing")
                workspace.write_state(video_id, state)
                release_model()
                native_runtime = None
                retained_audio = audio_store.read_audio_provenance(workspace, video_id, duration_ms,
                    expected_mix_sha256=expected_mix_sha256)
                for native_adapter, native_route in product(native_adapters, ("mix",)):
                    label = f"mms-asr:{native_adapter}:{native_route}"
                    record_route_attempt("structure", label, "started")
                    try:
                        retained_native = workspace.job_dir(video_id) / f"native-asr-{native_adapter}-{native_route}.private.json"
                        cache_admitted = admit_cached_recognition(retained_native)
                        regenerate_native = args.rerun_transcript or not cache_admitted
                        native_env = models.roformer_offline_env(Path(config['projectDir']).parent)
                        if native_runtime is None:
                            native_runtime = mms_asr.runtime_provenance(Path(config['projectDir']), args.device,
                                int(config['analysisTimeoutMinutes']) * 60, native_env)
                        retained_identity = (retained_audio or {}).get('audioViews', {}).get(native_route)
                        native_result = None
                        if retained_identity is not None and not regenerate_native:
                            try:
                                native_result = tracked_native(
                                    project=Path(config['projectDir']), model_cache=model_cache,
                                    job_dir=workspace.job_dir(video_id), audio=alignment_temp_root / 'unused-vocals.wav',
                                    audio_identity=retained_identity, duration_ms=duration_ms, route=native_route,
                                    device=args.device, timeout=int(config['analysisTimeoutMinutes']) * 60,
                                    env=native_env, rerun=False, adapter=native_adapter, cache_only=True,
                                    runtime_report=native_runtime)
                            except FileNotFoundError:
                                pass
                        if native_result is None:
                            native_audio, native_identity = (canonical_mix() if native_route == 'mix'
                                else get_endpoint_audio(native_route)[:2])
                            native_result = tracked_native(
                                project=Path(config['projectDir']), model_cache=model_cache,
                                job_dir=workspace.job_dir(video_id), audio=native_audio,
                                audio_identity=native_identity, duration_ms=duration_ms,
                                route=native_route, device=args.device,
                                timeout=int(config['analysisTimeoutMinutes']) * 60,
                                env=native_env, rerun=regenerate_native,
                                adapter=native_adapter, runtime_report=native_runtime)
                        native_words, native_evidence, native_reused = native_result
                    except (LyricsContentBlocker, WorkerRetirementError):
                        raise
                    except Exception as error:
                        code = route_diagnostic(error, 'structure', label, model=mms_asr.MODEL)
                        record_route_failure("structure", label, code)
                        continue
                    _, native_alignment, _, _ = record_recognition_route(label, lambda: native_result,
                        evidence_path=retained_native, admit=True)
                    if (native_alignment.accepted
                            and not _has_structural_mutation(original["text"], native_alignment)):
                        break

            needs_original_mix_evidence = not any(
                label.startswith(("faster-whisper:", "mms-asr:"))
                for label, _, _, _ in evidence_options
            )
            if needs_original_mix_evidence or _needs_additional_structural_evidence(
                evidence_options,
                text=original["text"],
                repeat_structure_kind=repeat_structure_kind,
            ):
                state.update(status="transcribing")
                workspace.write_state(video_id, state)
                raw_transcripts: list[list[Any]] = []
                raw_labels: list[str] = []
                for asr_language in transcription_languages(original):
                    label = f"faster-whisper:{asr_language or 'auto'}"
                    record_route_attempt("structure", label, "started")
                    try:
                        transcript, asr_alignment, asr_evidence, reused = run_whisper_route(WHISPER_MIX_ROUTE, asr_language)
                    except (LyricsContentBlocker, ContentEvidenceUnavailable, WorkerRetirementError):
                        raise
                    except Exception as error:
                        if route_attempts[-1].get('status') == 'success':
                            raise
                        record_route_failure('structure', label, route_diagnostic(error, 'structure', label))
                        continue
                    raw_transcripts.append(list(transcript))
                    raw_labels.append(label)
                    if (
                        asr_alignment.accepted
                        and not _has_structural_mutation(
                            original["text"], asr_alignment
                        )
                    ):
                        break

                if not evidence_options and any(failure["stage"] == "structure" for failure in route_failures):
                    raise ConditionalCascadeTechnicalError("initial recognition failed before content admission")
                lyric_content.require_workspace_content(workspace, video_id, content_sources,
                                           original=original, audio_sha=expected_mix_sha256)

                if (
                    len(raw_transcripts) >= 2
                    and not any(item[1].accepted for item in evidence_options)
                ):
                    merged_alignment = align_transcripts(raw_transcripts, raw_labels)
                    evidence_options.append(
                        (
                            "faster-whisper:multilingual-merge",
                            merged_alignment,
                            {
                                "type": "multilingual-merge",
                                "passes": raw_labels,
                                "passCount": len(raw_labels),
                            },
                            all(item[3] for item in evidence_options if item[0] in raw_labels),
                        )
                    )

                if not any(item[1].accepted for item in evidence_options):
                    raw_options = [
                        item for item in evidence_options if item[0] in raw_labels
                    ]
                    if raw_options:
                        for vocal_language in transcription_languages(original):
                            try:
                                _, vocal_alignment, _, _ = run_whisper_route(WHISPER_VOCAL_ROUTE, vocal_language)
                                if (
                                    vocal_alignment.accepted
                                    and repeat_structure_kind == "none"
                                    and not _has_structural_mutation(original["text"], vocal_alignment)
                                ):
                                    break
                            except (LyricsContentBlocker, WorkerRetirementError):
                                raise
                            except Exception as vocal_error:
                                state["vocalFallbackError"] = route_diagnostic(vocal_error, 'structure', 'faster-whisper-vocals')
                                record_route_failure(
                                    "structure", "faster-whisper-vocals", state["vocalFallbackError"]
                                )

                if not any(item[1].accepted for item in evidence_options):
                    for precision_language in transcription_languages(original):
                        try:
                            _, precision_alignment, _, _ = run_whisper_route(WHISPER_PRECISION_ROUTE, precision_language)
                            if (
                                precision_alignment.accepted
                                and repeat_structure_kind == "none"
                                and not _has_structural_mutation(original["text"], precision_alignment)
                            ):
                                break
                        except (LyricsContentBlocker, WorkerRetirementError):
                            raise
                        except Exception as precision_error:
                            state["precisionFallbackError"] = route_diagnostic(precision_error, 'structure', 'faster-whisper-precision')
                            record_route_failure(
                                "structure", "faster-whisper-precision", state["precisionFallbackError"]
                            )

                if _needs_additional_structural_evidence(
                    evidence_options,
                    text=original["text"],
                    repeat_structure_kind=repeat_structure_kind,
                ):
                    # Last local evidence pass: withhold the approved lyric
                    # prompt. Each component language uses the same ASR
                    # plan as the initial pass, giving the merger an
                    # acoustic holdout against prompt echo and repeated-stanza
                    # misassignment.
                    for unprompted_language in transcription_languages(original):
                        try:
                            _, unprompted_alignment, _, _ = run_whisper_route(WHISPER_HOLDOUT_ROUTE, unprompted_language)
                            if (
                                unprompted_alignment.accepted
                                and repeat_structure_kind == "none"
                                and not _has_structural_mutation(original["text"], unprompted_alignment)
                            ):
                                break
                        except (LyricsContentBlocker, WorkerRetirementError):
                            raise
                        except Exception as unprompted_error:
                            state["unpromptedFallbackError"] = route_diagnostic(unprompted_error, 'structure', 'faster-whisper-precision-unprompted')
                            record_route_failure(
                                "structure", "faster-whisper-precision-unprompted", state["unpromptedFallbackError"]
                            )

                # Exact internally repeated units are unusually sensitive to a
                # single mix/vocal transcript assigning ambiguous duplicate
                # words to the wrong written copy. If every existing pass still
                # cannot produce a candidate under the unchanged release gate,
                # add one precision decode on isolated vocals. This is a
                # corpus-wide evidence pass, not a per-song threshold change.
                if (
                    repeat_structure_kind == "collapsible"
                    and len(recognition_evidence.transcripts) >= 2
                    and not any(item[1].accepted for item in evidence_options)
                ):
                    for precision_vocal_language in transcription_languages(original):
                        try:
                            run_whisper_route(WHISPER_PRECISION_VOCAL_ROUTE, precision_vocal_language)
                        except (LyricsContentBlocker, WorkerRetirementError):
                            raise
                        except Exception as precision_vocal_error:
                            record_route_failure('structure', 'precision-vocal',
                                                 route_diagnostic(precision_vocal_error, 'structure', 'precision-vocal'))
                            state["precisionVocalFallbackError"] = type(
                                precision_vocal_error
                            ).__name__

            if (
                len(recognition_evidence.transcripts) >= 2
                and _needs_additional_structural_evidence(
                    evidence_options,
                    text=original["text"],
                    repeat_structure_kind=repeat_structure_kind,
                )
            ):
                merged_alignment = align_transcripts(recognition_evidence.transcripts, recognition_evidence.labels)
                evidence_options.append(
                    (
                        "multi-evidence-merge",
                        merged_alignment,
                        {
                            "type": "cross-evidence-merge",
                            "passes": recognition_evidence.labels,
                            "passCount": len(recognition_evidence.labels),
                        },
                        all(recognition_evidence.reused),
                    )
                )

            record_route_attempt("caption", "youtube-caption", "started")
            caption = tracked_caption(
                video_id=video_id,
                original=original,
                workspace=workspace,
                temp_root=alignment_temp_root,
                record_outcome=lambda status, reused, code: record_route_attempt(
                    "caption",
                    "youtube-caption",
                    status,
                    cache_reused=reused,
                    code=code,
                ),
            )
            if caption is not None:
                caption_words, caption_evidence, caption_reused = caption
                try:
                    require_exact_sequence(original, content_sources, caption_words,
                                           canonical_plain_sha256, expected_mix_sha256)
                except LyricsContentBlocker:
                    record_route_attempt("caption", "youtube-caption", "unavailable",
                                         cache_reused=caption_reused, code="caption-content-mismatch")
                    caption = None
            if caption is not None:
                caption_alignment = align_transcript(caption_words)
                caption_label = (
                    f"youtube-caption:{caption_evidence.get('captionKind') or 'unknown'}"
                )
                evidence_options.append(
                    (caption_label, caption_alignment, caption_evidence, caption_reused)
                )
                recognition_evidence.add(caption_label, caption_words, caption_reused)

            structure_stage = structure_selection.StructureSelectionStage(
                request=StructureSelectionRequest(video_id=video_id, original=original, duration_ms=duration_ms,
                    repeat_structure_kind=repeat_structure_kind, reviewed_baseline_structure=reviewed_baseline_structure,
                    performed_structure_review_sha256=performed_structure_review_sha256,
                    evidence_root=workspace.job_dir(video_id)),
                options=evidence_options, merge_transcripts=recognition_evidence.transcripts, progress=state,
                merge_evidence_paths=[recognition_evidence.path_for(label) for label in recognition_evidence.labels])
            def select_evidence():
                structure_stage.merge_transcripts = recognition_evidence.transcripts
                structure_stage.merge_evidence_paths = [recognition_evidence.path_for(label) for label in recognition_evidence.labels]
                return structure_stage.select()

            selected_evidence = select_evidence()
            if selected_evidence is None or not selected_evidence[1].accepted:
                roformer_labels = {
                    "kimberley-jensen-roformer-vocals": (
                        "faster-whisper-roformer-kim-vocals"
                    ),
                    "sucial-v2-roformer-dry-vocals": (
                        "faster-whisper-roformer-sucial-dereverb-v2"
                    ),
                }

                def evaluate_structure_route(
                    route_id: str,
                ) -> tuple[bool, tuple[str, AlignmentResult, dict[str, Any], bool]]:
                    state["status"] = "transcribing-roformer-fallback"
                    workspace.write_state(video_id, state)
                    for roformer_language in transcription_languages(original):
                        transcript, route_evidence, route_reused = (
                            tracked_roformer(
                                video_id=video_id,
                                original=original,
                                route_id=route_id,
                                workspace=workspace,
                                model_name=args.model,
                                model_cache=model_cache,
                                language=roformer_language,
                                device=args.device,
                                compute_type=args.compute_type,
                                duration_ms=duration_ms,
                                get_model=get_model,
                                get_audio=(
                                    lambda route_id=route_id: get_roformer_audio(route_id)
                                ),
                                rerun=args.rerun_transcript,
                                expected_mix_sha256=expected_mix_sha256,
                            )
                        )
                        record_roformer_route(route_id, cache_reused=route_reused)
                        route_label = (
                            f"{roformer_labels[route_id]}:{roformer_language or 'auto'}"
                        )
                        record_recognition_route(route_label, lambda: (transcript, route_evidence, route_reused))
                    evidence_options[:] = [
                        item for item in evidence_options if item[0] != "multi-evidence-merge"
                    ]
                    if len(recognition_evidence.transcripts) >= 2 and _needs_additional_structural_evidence(
                        evidence_options,
                        text=original["text"],
                        repeat_structure_kind=repeat_structure_kind,
                    ):
                        evidence_options.append(
                            (
                                "multi-evidence-merge",
                                align_transcripts(recognition_evidence.transcripts, recognition_evidence.labels),
                                {
                                    "type": "cross-evidence-merge",
                                    "passes": list(recognition_evidence.labels),
                                    "passCount": len(recognition_evidence.labels),
                                },
                                all(recognition_evidence.reused),
                            )
                        )
                    evidence_type, alignment, evidence, reused = select_evidence()
                    return alignment.accepted, (
                        evidence_type,
                        alignment,
                        evidence,
                        reused,
                    )

                _, structure_result = _run_conditional_cascade(
                    tuple(endpoints.ROFORMER_ROUTES),
                    evaluate_structure_route,
                    on_failure=lambda route_id, code: record_route_failure(
                        "structure", route_id, code
                    ),
                    diagnostic_path=workspace.job_dir(video_id) / 'model-route.failure.private.txt',
                    diagnostic_context={'attemptId': getattr(args, 'attempt_id', None), 'videoId': video_id,
                        'stage': 'structure', 'model': args.model, 'device': args.device},
                    prior_technical_failure=any(
                        failure["stage"] == "structure" for failure in route_failures
                    ),
                )
                evidence_type, alignment, evidence, reused = structure_result
            else:
                evidence_type, alignment, evidence, reused = selected_evidence
            if getattr(args, "qwen_structure_witness", False):
                state["structureWitness"] = {"family": "qwen3-asr", "route": QWEN3_ASR_ROUTE,
                    "status": "started", "candidateCount": 1}
                workspace.write_state(video_id, state)
                record_route_attempt("structure", QWEN3_ASR_ROUTE, "started")
                try:
                    qwen_audio, qwen_audio_identity = canonical_mix()
                    qwen_runtime = _qwen3_asr_runtime_provenance(
                        environment_python('main', Path(config['projectDir'])),
                        Path(config["projectDir"]) / "src" / "qwen3_asr_transcribe.py",
                        device=args.device,
                        timeout_seconds=int(config["analysisTimeoutMinutes"]) * 60)
                    qwen_language = _qwen_structure_languages(original, qwen_runtime["supportedLanguages"])[0]
                    qwen_text, qwen_provenance, qwen_reused = tracked_qwen(
                        config=config, video_id=video_id, language=None if qwen_language == "auto" else qwen_language,
                        audio=qwen_audio, audio_identity=qwen_audio_identity, model_cache=model_cache,
                        workspace=workspace, temp_dir=alignment_temp_root, device=args.device,
                        timeout_seconds=int(config["analysisTimeoutMinutes"]) * 60,
                        before_run=release_model, runtime_report=qwen_runtime, separate_language_cache=True)
                    qwen_admission = None
                    qwen_path = workspace.job_dir(video_id) / (f'qwen-structure-{qwen_language}.private.json'
                        if qwen_language != 'auto' else 'qwen-structure.private.json')
                    try:
                        require_exact_sequence(original, content_sources, qwen_text,
                                               canonical_plain_sha256, expected_mix_sha256)
                    except LyricsContentBlocker:
                        qwen_admission = confirm_content(original, content_sources, canonical_plain_sha256,
                            expected_mix_sha256, evidence_path=qwen_path,
                            evidence_root=workspace.job_dir(video_id),
                            adjudication_job_dir=workspace.root.parent / 'jobs' / video_id)
                        lyric_content.require_admitted_sequence(qwen_admission, original, content_sources, qwen_text,
                            evidence_path=qwen_path,
                            evidence_root=workspace.job_dir(video_id),
                            adjudication_job_dir=workspace.root.parent / 'jobs' / video_id)
                    scores, _, _ = _score_qwen_structure_routes(original, {content_sources}, (qwen_language,),
                        lambda _language: (qwen_text, qwen_provenance, qwen_reused), admission=qwen_admission,
                        workspace=workspace, video_id=video_id, evidence_path=qwen_path)
                    support = scores[0]
                    if not support.accepted or support.metrics.direct_line_count != support.metrics.line_count:
                        raise LyricsContentBlocker("content-witness-uncertain", binding=state["contentBinding"])
                    state["structureWitness"] = {"family": "qwen3-asr", "route": QWEN3_ASR_ROUTE,
                        "status": "success", "candidateCount": 1, "passingCandidateCount": 1,
                        "selectedSequenceSupported": True, "selectedSequenceEveryLineDirect": True,
                        "cacheReused": qwen_reused, "languageHint": qwen_provenance["language"],
                        "metrics": asdict(support.metrics), "lexicalAuthoritySha256": lexical_authority_sha256(original),
                        **{key: qwen_provenance[key] for key in ("modelRevision", "modelSnapshotSha256",
                            "helperSha256", "runtime", "decoder")}}
                    if qwen_admission is not None:
                        state['structureWitness']['contentAdmission'] = qwen_admission.to_record()
                    from stemwise_engine.workflow_records import structure_witness_record
                    state['structureWitness'] = structure_witness_record(state['structureWitness'])
                    record_route_attempt("structure", QWEN3_ASR_ROUTE, "success", cache_reused=qwen_reused)
                except LyricsContentBlocker:
                    raise
                except Exception as error:
                    code = route_diagnostic(error, 'structure', QWEN3_ASR_ROUTE, model=QWEN3_ASR_ROUTE)
                    state["structureWitness"] = {"family": "qwen3-asr", "route": QWEN3_ASR_ROUTE,
                        "status": "failure", "candidateCount": 1, "cacheReused": False, "failureCode": code}
                    record_route_attempt("structure", QWEN3_ASR_ROUTE, "failure", code=code)
                    raise
                _require_completed_structure_witness(alignment, state["structureWitness"])

            attempt_summaries = [
                {
                    "type": item[0],
                    "accepted": item[1].accepted,
                    "reasons": list(item[1].reasons),
                    "metrics": serialize_result(item[1])["metrics"],
                    "reused": item[3],
                    "sequenceCandidate": _structure_candidate_diagnostics(
                        video_id, original, item[1]
                    ),
                }
                for item in evidence_options
            ]
            state.update(
                status="aligning",
                evidenceType=evidence_type,
                evidenceReused=reused,
                evidence=evidence,
                attemptedEvidence=[item[0] for item in evidence_options],
                evidenceAttempts=attempt_summaries,
            )
            workspace.write_state(video_id, state)
            summary = serialize_result(alignment)
            candidate = None
            public_line_timings = None
            endpoint_selection = None
            lexical_mapping = None
            if alignment.accepted:
                endpoint_services = EndpointServices(
                    workers=EndpointWorkers(load_mms=tracked_mms, load_wav2vec2=tracked_wav2vec2,
                        load_owsm=tracked_owsm, load_nfa=tracked_nfa,
                        read_audio_provenance=audio_store.read_audio_provenance,
                        verifier_candidates=_forced_alignment_verifier_candidates,
                        needs_local_verifier=_needs_route_local_verifier),
                    events=AlignmentEvents(attempt=record_route_attempt, failure=record_route_failure,
                        roformer=record_roformer_route),
                    progress=AlignmentProgress(state=state, endpoint_attempts=[],
                        checkpoint=lambda: workspace.write_state(video_id, state)),
                    release_model=release_model, runtime_probe_cache=runtime_probe_cache)
                lexical_mapping, endpoint_selection, public_line_timings, candidate = _refine_alignment_endpoints(
                    config=config, args=args, workspace=workspace, video_id=video_id, duration_ms=duration_ms,
                    alignment_temp_root=alignment_temp_root, state=state, original=original, lyrics=lyrics,
                    alignment=alignment, spoken=spoken, evidence_options=evidence_options,
                    recognition_evidence=recognition_evidence, expected_mix_sha256=expected_mix_sha256,
                    analysis_audio=analysis_audio, audio_views=audio_views, services=endpoint_services,
                    route_diagnostic=route_diagnostic, route_failures=route_failures,
                    reviewed_lrc_starts=reviewed_lrc_starts)
            # Cache hits can leave the per-song Whisper owner resident. Its
            # retirement must succeed before replacing the approved sidecar.
            release_model()
            publication = AlignmentPublisher().publish(
                AlignmentPublicationRequest(workspace=workspace, video_id=video_id, sidecar_path=sidecar_path,
                    original_raw=original_raw, expected_current_raw=expected_sidecar_raw, candidate=candidate,
                    alignment=alignment, lexical_mapping=lexical_mapping, public_line_timings=public_line_timings,
                    summary=summary, endpoint_selection=endpoint_selection, no_commit=args.no_commit), state)
            counts[publication.counter] += 1
            results.append(state)
        except WorkerRetirementError:
            raise
        except LyricsContentBlocker as error:
            if state is not prior_state:
                state.pop("contentAdmission", None)
                state.update(status="lyrics-content-unresolved", reasons=list(error.reasons),
                             contentBinding=error.binding or state.get("contentBinding"))
                workspace.write_state(video_id, state)
            counts["blocked"] += 1
            results.append(state)
        except Exception as error:
            raise_if_operation_stopped(error)
            state.update(
                status="retryable-technical",
                reasons=[worker_protocol.technical_diagnostic_code(error, workspace.job_dir(video_id) / 'alignment.failure.private.txt',
                    context={'stage': state.get('status'), 'model': args.model, 'device': args.device,
                             'computeType': args.compute_type})],
            )
            diagnostics = list(getattr(state['reasons'][0], 'diagnostics', ()))
            if diagnostics:
                state['privateDiagnostics'] = diagnostics
            else:
                state.pop('privateDiagnostics', None)
            counts["failed"] += 1
            workspace.write_state(video_id, state)
            results.append(state)
        finally:
            failure = sys.exc_info()[1]
            try:
                if audio_views is not None:
                    audio_views.close()
                else:
                    release_model()
            except BaseException as cleanup_error:
                raise_with_cleanup_failure(failure, cleanup_error)
        print(f"  -> {state['status']}", flush=True)

    return _write_alignment_report(workspace, model=args.model, selected_count=len(selected),
        counts=counts, results=results)



def _validate(config: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    sidecars, output, _, _ = _paths(config)
    requested = {validate_video_id(value) for value in args.video_id}
    campaign = ({validate_video_id(item['videoId']) for item in _inventory(config)['items']}
                if not requested else set())
    valid: list[str] = []
    invalid: list[dict[str, str]] = []
    discovered: set[str] = set()
    skipped: list[str] = []
    for path in sorted(sidecars.glob("*.lyrics.json")):
        video_id = path.name.removesuffix(".lyrics.json")
        if requested and video_id not in requested:
            continue
        if not requested and video_id not in campaign:
            skipped.append(video_id)
            continue
        discovered.add(video_id)
        try:
            lyrics, _ = load_sidecar(path, root=sidecars)
            original = _original_track(lyrics)
            if original is None or original.get("format") != "synced":
                if requested:
                    invalid.append({'videoId': video_id, 'code': 'requested-original-not-synced'})
                else:
                    skipped.append(video_id)
                continue
            duration_ms = validate_child_zip(
                output / "children" / f"{video_id}.stemwise.zip",
                config,
                expected_video_id=video_id,
            )["analysis"]["durationMs"]
            validate_lyrics(lyrics, duration_ms=duration_ms)
            original_timeline = [
                (line["startMs"], line["endMs"]) for line in original["lines"]
            ]
            for track in lyrics.get("tracks", []):
                if track.get("format") != "synced":
                    raise ValueError("a synced release contains a plain companion track")
                timeline = [
                    (line["startMs"], line["endMs"])
                    for line in track.get("lines", [])
                ]
                if timeline != original_timeline:
                    raise ValueError("companion occurrence timing differs from the original")
            valid.append(video_id)
        except Exception as error:
            invalid.append(
                {"videoId": video_id, "code": worker_protocol.technical_diagnostic_code(error)}
            )
    invalid.extend({'videoId': video_id, 'code': 'requested-sidecar-missing'}
                   for video_id in sorted(requested - discovered))
    return {"valid": bool(valid) and not invalid, "syncedValidated": len(valid),
            'validatedIds': valid, 'requestedIds': sorted(requested), 'skippedIds': skipped,
            "invalid": invalid}


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    failure_path = failure_record = None
    failure_context = None
    try:
        config = load_config(args.config.resolve())
        sidecars, _, model_cache, workspace = _paths(config)
        if args.command in {"preload-model", "align", "future-align"}:
            failure_context = {'stage': args.command + '-startup', 'command': args.command,
                'configPath': str(args.config.resolve()), 'model': args.model,
                'device': args.device, 'computeType': args.compute_type}
            failure_name = 'preload' if args.command == 'preload-model' else args.command
            if args.command == 'future-align':
                failure_name += '-' + args.attempt_id
                failure_context.update(videoId=args.video_id, attemptId=args.attempt_id,
                    expectedMixSha256=args.expected_mix_sha256, editorialSha256=args.editorial_sha256,
                    canonicalPlainSha256=args.canonical_plain_sha256)
            failure_path = workspace.root / (failure_name + '.failure.private.txt')
            failure_record = workspace.root / (failure_name + '.failure.json')
            from stemwise_engine.lyrics_alignment import resolve_compute_type
            args.compute_type = resolve_compute_type(args.device, args.compute_type)
            failure_context['computeType'] = args.compute_type
        if args.command == "doctor":
            result = _doctor(config)
            _print(result)
            return 0 if result["ready"] else 1
        if args.command == "preload-model":
            if args.model != DEFAULT_MODEL:
                raise RuntimeError(f"model preload requires pinned model {DEFAULT_MODEL}")
            alignment_runtime = _verify_alignment_runtime(config)
            snapshot, whisper_identity = _verify_whisper_snapshot(model_cache)
            with model_operation_slot(config), heavy_worker_stage(config):
                try:
                    model = FasterWhisperEvidence(
                        args.model,
                        snapshot,
                        device=args.device,
                        compute_type=args.compute_type,
                    )
                    model.close()
                except BaseException as error:
                    try:
                        context = {**failure_context, 'stage': 'preload'}
                        code = worker_protocol.technical_diagnostic_code(error, failure_path, context=context)
                        atomic_write_json(failure_record,
                            {'status': 'retryable-technical', 'reason': str(code), 'context': context,
                             'privateDiagnostics': list(code.diagnostics)})
                    finally:
                        print('Whisper preload failed before retirement', file=sys.stderr, flush=True)
                        os._exit(70)
            _print(
                {
                    "ready": True,
                    "model": args.model,
                    "device": args.device,
                    "computeType": args.compute_type,
                    "modelCache": str(model_cache),
                    "pinnedModels": whisper_identity,
                    "pinnedRuntime": alignment_runtime,
                }
            )
            return 0
        if args.command == "inventory":
            _print(inventory_summary(sidecars))
            return 0
        if args.command == "status":
            _print(_job_summary(workspace))
            return 0
        if args.command == "align":
            with model_operation_slot(config), exclusive_process_lock(heavy_worker_lock_path(config)):
                with exclusive_process_lock(workspace.root / "lyrics-align.lock"):
                    result = _align(config, args)
            _print(result)
            return 1 if result["counts"].get("failed") or result["counts"].get("blocked") else 0
        if args.command == 'owner-word-prediction':
            video_id = validate_video_id(args.video_id)
            project = Path(config['projectDir'])
            workspace_root = project.parent.parent
            future_root = validate_generated_path(workspace_root, args.future_root, workspace_root=workspace_root)
            analysis_root = validate_generated_path(workspace_root, args.analysis_root, workspace_root=workspace_root)
            expected_audio = validate_generated_path(analysis_root, analysis_root/video_id/'analysis.wav',
                workspace_root=workspace_root)
            supplied_audio = validate_generated_path(analysis_root, args.analysis_audio, workspace_root=workspace_root)
            if supplied_audio != expected_audio or not supplied_audio.is_file():
                raise ValueError('owner word prediction requires its exact retained analysis PCM')
            owner_workspace = AlignmentWorkspace(future_root, workspace_root=workspace_root)
            failure_path = owner_workspace.job_dir(video_id)/'owner-word-prediction.failure.private.txt'
            failure_context = {'stage':'owner-word-prediction', 'videoId':video_id,
                'expectedMixSha256':args.expected_mix_sha256, 'canonicalPlainSha256':args.canonical_plain_sha256}
            with exclusive_process_lock(owner_workspace.job_dir(video_id)/'worker.lock'):
                raw = read_bounded_regular_bytes(owner_workspace.current_backup_path(video_id),
                    max_bytes=MAX_JSON_BYTES, root=owner_workspace.canonical_plain)
                if hashlib.sha256(raw).hexdigest() != args.canonical_plain_sha256:
                    raise ValueError('owner word prediction canonical target changed')
                lyrics = parse_strict_json_bytes(raw)
                _owner_word_prediction_inputs(owner_workspace, video_id, _plain_original(lyrics),
                    args.canonical_plain_sha256, args.expected_mix_sha256)
                from stemwise_engine.common import manifested_temporary_directory
                with model_operation_slot(config), manifested_temporary_directory(
                        analysis_root/'lyrics-alignment/owner-word-prediction', prefix=video_id+'-',
                        owner={'kind':'owner-word-prediction', 'videoId':video_id}, workspace_root=workspace_root) as temporary:
                    candidate, mapping, forced, admission, _, _ = run_owner_word_prediction(
                        config, owner_workspace, video_id, lyrics, args.canonical_plain_sha256,
                        supplied_audio, args.expected_mix_sha256, temporary, device=args.device)
                response = _owner_word_prediction_response(owner_workspace, video_id, candidate, mapping,
                    forced, admission, args.canonical_plain_sha256, args.expected_mix_sha256)
                load_owner_word_prediction(project, owner_workspace, video_id, response,
                    original=_plain_original(lyrics), indices=_baseline_source_indices(_plain_original(lyrics)['text']),
                    canonical_sha=args.canonical_plain_sha256, audio_sha=args.expected_mix_sha256)
            _print(response)
            return 0
        if args.command == "future-align":
            video_id = validate_video_id(args.video_id)
            attempt_id = args.attempt_id
            project = Path(config["projectDir"])
            future_root = validate_generated_path(
                project.parent.parent,
                args.future_root,
                workspace_root=project.parent.parent,
            )
            workspace_root = project.parent.parent
            analysis_root = validate_generated_path(
                workspace_root,
                args.analysis_root,
                workspace_root=workspace_root,
            )
            expected_audio = validate_generated_path(
                analysis_root,
                analysis_root / video_id / "analysis.wav",
                workspace_root=workspace_root,
            )
            supplied_audio = validate_generated_path(
                analysis_root,
                args.analysis_audio,
                workspace_root=workspace_root,
            )
            if supplied_audio != expected_audio or not supplied_audio.is_file():
                raise RuntimeError("future alignment requires its exact retained analysis PCM")
            args.analysis_audio = supplied_audio
            future_workspace = AlignmentWorkspace(
                future_root, workspace_root=workspace_root
            )
            future_paths = (
                future_root / "owner-input",
                future_root / "runtime",
                project.parent / "model-cache" / "lyrics-alignment",
                future_workspace,
            )
            worker_lock = future_workspace.job_dir(video_id) / "worker.lock"
            with model_operation_slot(config), exclusive_process_lock(worker_lock):
                job_path = future_root / "jobs" / video_id / "job.json"
                job = _read_json(job_path)
                if (
                    job.get("videoId") != video_id
                    or job.get("state") != "ALIGNING_LYRICS"
                    or job.get("activeAlignmentAttemptId") != attempt_id
                ):
                    raise RuntimeError("alignment attempt is stale and will not be started")
                failure_path = future_workspace.job_dir(video_id) / f'attempt-{attempt_id}.failure.private.txt'
                failure_record = future_workspace.job_dir(video_id) / f'attempt-{attempt_id}.failure.json'
                future_config = {**config, "tempDir": str(analysis_root)}
                if 'recordingInput' in job:
                    from stemwise_engine.recording_source import alignment_config
                    future_config = alignment_config(future_config, job, future_root)
                result = _run_future_alignment_attempt(
                    future_config,
                    args,
                    video_id=video_id,
                    attempt_id=attempt_id,
                    workspace_root=workspace_root,
                    generated_root=(
                        analysis_root / "lyrics-alignment" / "future"
                    ),
                    future_workspace=future_workspace,
                    future_paths=future_paths,
                )
            _print(result)
            return 0 if result["counts"].get("committed") == 1 else 1
        if args.command == "validate":
            result = _validate(config, args)
            _print(result)
            return 0 if result["valid"] else 1
        raise AssertionError(args.command)
    except KeyboardInterrupt:
        print("Interrupted; private transcript and alignment checkpoints remain resumable.", file=sys.stderr)
        return 130
    except LyricsContentBlocker as error:
        _print({"status": "lyrics-content-unresolved", "reasons": list(error.reasons),
                "contentBinding": error.binding})
        return 1
    except Exception as error:
        if isinstance(error, WorkerRetirementError):
            print(WORKER_RETIREMENT_FAILURE_MARKER, file=sys.stderr, flush=True)
        code = type(error).__name__
        try:
            if failure_path is not None and args.command == 'future-align':
                from stemwise_engine.private_diagnostics import preserve_failure
                preserve_failure(failure_path, error, getattr(error, 'stderr', None))
            code = worker_protocol.technical_diagnostic_code(error, failure_path, context=failure_context)
            if failure_record is not None:
                atomic_write_json(failure_record, {'status': 'retryable-technical', 'reason': str(code),
                    'context': failure_context, 'privateDiagnostics': list(code.diagnostics)})
        except Exception as diagnostic_error:
            print(f'stemwise-lyrics-align-diagnostic: {type(diagnostic_error).__name__}', file=sys.stderr)
        print(
            f"stemwise-lyrics-align: {code}",
            file=sys.stderr,
        )
        worker_protocol.emit_worker_failure(error)
        return 70 if isinstance(error, WorkerRetirementError) else 2


if __name__ == "__main__":
    raise SystemExit(main())
