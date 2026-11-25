"""Bound review and snapshot admission services; the workflow owns durable jobs."""
from __future__ import annotations
from stemwise_engine.workflow_context import report_control_path
import stemwise_engine.editorial_review as editorial
from stemwise_engine.common import companion_language_roles
import stemwise_engine.workflow_records as review_contract
import stemwise_engine.workflow_diagnostics as workflow_diagnostics
import copy
import hashlib
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from itertools import combinations
from pathlib import Path
from typing import Any, Callable
from stemwise_engine.common import (GENERATED_MANIFEST_NAME, create_manifested_directory, atomic_write_bytes, atomic_write_json,
    json_bytes, parse_strict_json_bytes, read_bounded_json, read_bounded_regular_bytes,
    sha256_file, utc_now, validate_generated_path, validate_video_id)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.workflow_records import FutureSongError, EndpointReviewSelectionError, EndpointRefusalCode, LyricsContentBlocker


@dataclass(frozen=True)
class ReviewPolicy:
    workflow_version: str
    terminal_states: frozenset[str]
    endpoint_version: int
    endpoint_policy: str
    display_version: int
    display_policy: str


@dataclass(frozen=True)
class ReviewOperations:
    read_child: Callable[..., tuple[dict[str, Any], bytes | None]]
    validate_plain_draft: Callable[[Path], None]
    validate_synced_companion_timing: Callable[[dict[str, Any]], None]
    valid_reviewer: Callable[[Any], bool]


@dataclass(frozen=True)
class ReviewCompleted:
    job: dict[str, Any]
    publication: dict[str, Any] | None = None
    refresh_task: bool = False


@dataclass(frozen=True)
class ReviewEvidence:
    evidence: dict[str, Any]


@dataclass(frozen=True, kw_only=True)
class EndpointReviewRequest:
    job: dict[str, Any]
    evidence_only: bool = False
    vocal: bool = False


@dataclass(frozen=True, kw_only=True)
class ValidatedSnapshotRequest:
    job: dict[str, Any]
    require_delivery: bool = False
    allow_finalization_pointer: bool = False
    allow_pending_delivery_cleanup: bool = False
    delivery_source: Path | None = None


@dataclass(frozen=True)
class ReviewPaths:
    root: Path
    workspace_root: Path
    owner: Path
    runtime: Path
    ready: Path
    analysis_temp: Path
    config: dict[str, Any]
    report_store: Any


@dataclass(frozen=True)
class EndpointReviewContext(ReviewPaths):
    alignment_state_path: Callable
    completed_analysis_audio: Callable
    editorial_sha: Callable
    endpoint_review_attempt_dir: Callable
    endpoint_review_occurrences: Callable
    endpoint_review_template: Callable
    failed_endpoint_diagnostics: Callable
    job_dir: Callable
    lexical_mapping: Callable
    owner_sidecar_path: Callable
    pipeline: Callable
    recover_endpoint_packet: Callable
    validate_alignment_attempt_id: Callable


@dataclass(frozen=True)
class ValidatedSnapshotContext(ReviewPaths):
    alignment_state_path: Callable
    capability_snapshot_current: Callable
    companion_projection_current: Callable
    completed_analysis_audio: Callable
    display_review_attempt_dir: Callable
    editorial_sha: Callable
    endpoint_review_attempt_dir: Callable
    finalization_checkpoint_requirements: Callable
    job_dir: Callable
    lexical_mapping: Callable
    owner_sidecar_path: Callable
    pipeline: Callable
    runtime_config: Callable
    validate_alignment_attempt_id: Callable
    validated_artwork_repair: Callable
    windows_path: Callable


@dataclass(frozen=True)
class EndpointReviewService:
    context: EndpointReviewContext
    operations: ReviewOperations
    policy: ReviewPolicy

    def validate(self, request: EndpointReviewRequest) -> ReviewCompleted | ReviewEvidence:
        """Publish only exact, reviewed candidates from one bound failed run."""
        workflow = self.context
        job = request.job
        evidence_only = request.evidence_only
        vocal = request.vocal
        expected_sidecar_raw = read_bounded_regular_bytes(workflow.owner_sidecar_path(job['videoId']),
            max_bytes=MAX_JSON_BYTES, root=workflow.owner)

        from stemwise_engine.lyrics_alignment import (
            ALIGNMENT_VERSION,
            AlignmentMetrics,
            AlignmentResult,
            AlignmentWorkspace,
            build_synced_sidecar,
            commit_candidate,
            save_alignment_artifacts,
        )

        video_id = validate_video_id(job["videoId"])
        pointer = job.get("endpointReview")
        pointer_keys = {
            "attemptId",
            "declarationSha256",
            "sourceAlignmentRunId",
            "sourceAlignmentRunSha256",
            "sourceAlignmentStateSha256",
        }
        if not isinstance(pointer, dict) or not pointer_keys <= set(pointer):
            raise FutureSongError("endpoint-review state pointer is missing or invalid")
        attempt_id = workflow.validate_alignment_attempt_id(pointer["attemptId"])
        attempt = workflow.endpoint_review_attempt_dir(video_id, attempt_id)
        owner = {
            "kind": "endpoint-review-attempt",
            "videoId": video_id,
            "attemptId": attempt_id,
        }
        manifest = read_bounded_json(
            attempt / GENERATED_MANIFEST_NAME,
            max_bytes=16 * 1024,
            root=attempt,
        )
        if manifest != {
            "manifestVersion": 1,
            "owner": owner,
            "generatedPaths": ["."],
        } or {path.name for path in attempt.iterdir()} != {
            GENERATED_MANIFEST_NAME,
            "declaration.json",
            "review.json",
        } or any(not path.is_file() or path.is_symlink() for path in attempt.iterdir()):
            raise FutureSongError("endpoint-review generated-file manifest is invalid")

        declaration_path = attempt / "declaration.json"
        declaration = read_bounded_json(
            declaration_path, max_bytes=MAX_JSON_BYTES, root=attempt
        )
        declaration_keys = {
            "declarationVersion",
            "policy",
            "videoId",
            "attemptId",
            "sourceAlignment",
            "editorialSha256",
            "sourcesSha256",
            "canonicalPlainSha256",
            "mapping",
            "analysisAudio",
            "occurrences",
            "contentAdmission",
        }
        if isinstance(declaration, dict) and 'contentAdmission' not in declaration:
            raise LyricsContentBlocker('content-evidence-unavailable')
        source_binding = declaration.get("sourceAlignment") if isinstance(declaration, dict) else None
        version = self.policy.endpoint_version
        if (
            not isinstance(declaration, dict)
            or set(declaration) != declaration_keys
            or declaration.get("declarationVersion") != version
            or declaration.get("policy") != self.policy.endpoint_policy
            or declaration.get("videoId") != video_id
            or declaration.get("attemptId") != attempt_id
            or sha256_file(declaration_path) != pointer["declarationSha256"]
            or not isinstance(source_binding, dict)
            or set(source_binding) != {"attemptId", "runSha256", "stateSha256"}
            or source_binding.get("attemptId") != pointer["sourceAlignmentRunId"]
            or source_binding.get("runSha256")
            != pointer["sourceAlignmentRunSha256"]
            or source_binding.get("stateSha256")
            != pointer["sourceAlignmentStateSha256"]
        ):
            raise FutureSongError("endpoint-review declaration is stale or invalid")
        for value in (
            pointer["declarationSha256"],
            pointer["sourceAlignmentRunSha256"],
            pointer["sourceAlignmentStateSha256"],
            declaration["editorialSha256"],
            declaration["sourcesSha256"],
            declaration["canonicalPlainSha256"],
        ):
            if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
                raise FutureSongError("endpoint-review hash binding is invalid")

        mapping, mapping_identity = workflow.lexical_mapping(video_id)
        analysis_audio = workflow.completed_analysis_audio(workflow.pipeline(), video_id)
        if (
            not isinstance(declaration.get("mapping"), dict)
            or any(
                mapping_identity.get(key) != value
                for key, value in declaration["mapping"].items()
                if key != "mappingArtifactSha256"
            )
            or workflow.editorial_sha(job) != declaration["editorialSha256"]
            or sha256_file(workflow.job_dir(video_id) / "sources.json")
            != declaration["sourcesSha256"]
            or {
                key: analysis_audio[key]
                for key in ("sha256", "byteCount", "durationMs")
            }
            != declaration.get("analysisAudio")
        ):
            raise FutureSongError("endpoint-review authority or audio binding is stale")
        diagnostics, endpoint_evidence = workflow.failed_endpoint_diagnostics(
            video_id,
            source_binding["attemptId"],
            source_binding["runSha256"],
        )
        workspace = AlignmentWorkspace(workflow.root, workspace_root=workflow.workspace_root)
        canonical = workspace.current_backup_path(video_id)
        if sha256_file(canonical) != declaration['canonicalPlainSha256']:
            raise FutureSongError('endpoint-review canonical lyrics are stale')
        from stemwise_engine.endpoint_ctc_evidence import require_endpoint_content
        require_endpoint_content(declaration, mapping, endpoint_evidence,
            read_bounded_json(canonical, max_bytes=MAX_JSON_BYTES, root=canonical.parent),
            workflow.alignment_state_path(video_id).parent)
        expected_occurrences = workflow.endpoint_review_occurrences(
            endpoint_evidence, mapping, analysis_audio["durationMs"]
        )
        if declaration["occurrences"] != expected_occurrences:
            raise FutureSongError("endpoint-review candidate declaration is stale")

        if evidence_only:
            if vocal:
                from stemwise_engine.endpoint_repeat_alignment import METHOD as REPEAT_METHOD
                from stemwise_engine.endpoint_candidate_prediction import METHOD as PREDICTION_METHOD
                from stemwise_engine.endpoint_native_ctc import METHOD as NATIVE_METHOD
                from stemwise_engine.endpoint_owsm_ctc import METHOD as OWSM_METHOD

                workspace = AlignmentWorkspace(workflow.root, workspace_root=workflow.workspace_root)
                args = (attempt, declaration, pointer["declarationSha256"], Path(analysis_audio["path"]), workflow.config,
                        mapping, endpoint_evidence, workspace.current_backup_path(video_id))
                packet = workflow.recover_endpoint_packet(job, args)
                ready = (packet["method"] in (REPEAT_METHOD, PREDICTION_METHOD, NATIVE_METHOD, OWSM_METHOD) and bool(packet["occurrences"])
                         and all(a["result"] == "supported" for e in packet["occurrences"] for a in e["assessment"].values()))
                review_path = attempt / "review.json"
                review = read_bounded_json(review_path, max_bytes=MAX_JSON_BYTES, root=attempt)
                if ready and review["outcome"] == "pending":
                    review.update(reviewer=packet["method"], outcome="resolved", completedAt=utc_now(),
                                  audioEvidence={k: packet[k] for k in ("method", "manifestSha256")})
                    for choice, entry in zip(review["occurrences"], packet["occurrences"]):
                        for boundary, key in (("startMs", "startCandidateFamily"), ("endMs", "endCandidateFamily")):
                            choice[key] = entry["assessment"][boundary]["supportedFamilies"][0]
                    atomic_write_bytes(review_path, json_bytes(review), root=attempt)
                return ReviewEvidence({**packet, "automaticallySelected": ready})
            from stemwise_engine.endpoint_audio_evidence import write_packet

            return ReviewEvidence(write_packet(attempt, declaration, pointer["declarationSha256"], Path(analysis_audio["path"])))

        review_path = attempt / "review.json"
        review_raw = read_bounded_regular_bytes(review_path, max_bytes=MAX_JSON_BYTES, root=attempt)
        review = parse_strict_json_bytes(review_raw)
        review_sha256 = hashlib.sha256(review_raw).hexdigest()
        review_keys = {
            "formatVersion",
            "videoId",
            "attemptId",
            "reviewer",
            "completedAt",
            "outcome",
            "occurrences",
        }
        if (
            not isinstance(review, dict)
            or set(review) not in (review_keys, review_keys | {"audioEvidence"})
            or review.get("formatVersion") != version
            or review.get("videoId") != video_id
            or review.get("attemptId") != attempt_id
            or review.get("outcome") not in {"pending", "resolved"}
        ):
            raise EndpointReviewSelectionError("endpoint-review-decision-invalid")
        if review["outcome"] == "pending":
            expected_pending = workflow.endpoint_review_template(video_id, attempt_id, expected_occurrences)
            if review != expected_pending:
                raise EndpointReviewSelectionError(
                    "endpoint-review-decision-invalid"
                )
            return ReviewCompleted(job, refresh_task=True)
        ctc_manifest = None
        repeat_manifest = None
        prediction_manifest = None
        native_manifest = None
        owsm_manifest = None
        if "audioEvidence" in review:
            from stemwise_engine.endpoint_dispatch import evidence_adapter

            try:
                binding = review["audioEvidence"]
                adapter = evidence_adapter(binding.get('method') if isinstance(binding, dict) else None)
                workspace = AlignmentWorkspace(workflow.root, workspace_root=workflow.workspace_root)
                manifest = adapter.verify(attempt, declaration, pointer['declarationSha256'],
                    Path(analysis_audio['path']), binding, config=workflow.config if adapter.arguments == 'model' else {}, mapping=mapping,
                    evidence=endpoint_evidence, canonical=workspace.current_backup_path(video_id))
                module = adapter.module.__name__
                ctc_manifest = manifest if module == 'stemwise_engine.endpoint_ctc_evidence' else None
                repeat_manifest = manifest if module == 'stemwise_engine.endpoint_repeat_alignment' else None
                prediction_manifest = manifest if module == 'stemwise_engine.endpoint_candidate_prediction' else None
                native_manifest = manifest if module == 'stemwise_engine.endpoint_native_ctc' else None
                owsm_manifest = manifest if module == 'stemwise_engine.endpoint_owsm_ctc' else None
            except LyricsContentBlocker:
                raise
            except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
                raise EndpointReviewSelectionError("endpoint-review-audio-evidence-invalid") from error
        completed_at = review.get("completedAt")
        expected_reviewer = (binding['method']
            if any(value is not None for value in (owsm_manifest, native_manifest, prediction_manifest, repeat_manifest)) else None)
        if (not self.operations.valid_reviewer(review.get('reviewer'))
            or expected_reviewer is not None and review.get('reviewer') != expected_reviewer) or not isinstance(
            completed_at, str
        ) or re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", completed_at) is None:
            raise EndpointReviewSelectionError("endpoint-review-metadata-invalid")
        try:
            completed_at_unix = int(
                datetime.strptime(completed_at, "%Y-%m-%dT%H:%M:%SZ")
                .replace(tzinfo=timezone.utc)
                .timestamp()
            )
        except ValueError as error:
            raise EndpointReviewSelectionError(
                "endpoint-review-metadata-invalid"
            ) from error

        selections = review.get("occurrences")
        if not isinstance(selections, list) or len(selections) != len(
            expected_occurrences
        ):
            raise EndpointReviewSelectionError(
                "endpoint-review-selections-incomplete"
            )
        selected: dict[str, tuple[str, str]] = {}
        review_positions: dict[str, int] = {}
        for review_position, (expected, selection) in enumerate(
            zip(expected_occurrences, selections), start=1
        ):
            if (
                not isinstance(selection, dict)
                or set(selection)
                != {
                    "occurrenceId",
                    "performedIndex",
                    "startCandidateFamily",
                    "endCandidateFamily",
                }
                or selection.get("occurrenceId") != expected["occurrenceId"]
                or selection.get("performedIndex") != expected["performedIndex"]
                or selection.get("startCandidateFamily")
                not in expected["candidateFamilies"]
                or selection.get("endCandidateFamily")
                not in expected["candidateFamilies"]
            ):
                raise EndpointReviewSelectionError(
                    f"endpoint-review-selection-invalid-{review_position}"
                )
            selected[expected["occurrenceId"]] = (
                selection["startCandidateFamily"],
                selection["endCandidateFamily"],
            )
            review_positions[expected["occurrenceId"]] = review_position

        verified_acoustic_review = False
        if ctc_manifest is not None or repeat_manifest is not None or prediction_manifest is not None or native_manifest is not None or owsm_manifest is not None:
            from stemwise_engine.endpoint_ctc_evidence import verify_selections as verify_ctc_selections

            try:
                verify_ctc_selections(owsm_manifest or native_manifest or prediction_manifest or repeat_manifest or ctc_manifest, selections)
            except ValueError as error:
                raise EndpointReviewSelectionError(EndpointRefusalCode.CTC_EVIDENCE_ABSTAINED) from error
            verified_acoustic_review = True

        line_timings: list[dict[str, int]] = []
        line_review_positions: list[int] = []
        source_occurrences = endpoint_evidence["occurrenceCandidates"]
        from stemwise_engine.alignment_endpoints import (
            _applicable_endpoint_candidates, _endpoint_boundary_supported,
            _supported_endpoint_agreement_reason,
            assess_complete_word_sequence,
        )
        for index, occurrence in enumerate(source_occurrences):
            families = occurrence["familyCandidates"]
            if occurrence["publishDecision"]["result"] == "unresolved":
                start_family, end_family = selected[occurrence["occurrenceId"]]
            else:
                start_family = end_family = occurrence["selectedPublisherFamily"]
            applicable = _applicable_endpoint_candidates(occurrence)
            acoustically_adjudicated = verified_acoustic_review and occurrence['occurrenceId'] in selected
            for boundary, chosen in (("startMs", start_family), ("endMs", end_family)):
                witnesses = {family: candidate for family, candidate in applicable.items()
                    if _endpoint_boundary_supported(candidate, family, boundary)}
                if chosen not in witnesses or (not acoustically_adjudicated and len(witnesses) < 2):
                    raise EndpointReviewSelectionError(
                        f"endpoint-review-selection-invalid-{review_positions.get(occurrence['occurrenceId'], 0)}")
            if (not acoustically_adjudicated
                    and any(_supported_endpoint_agreement_reason(left, left_family, right, right_family, 'line')
                        is not None for (left_family, left), (right_family, right) in combinations(applicable.items(), 2))):
                raise EndpointReviewSelectionError(
                    f"endpoint-review-selection-invalid-{review_positions.get(occurrence['occurrenceId'], 0)}")
            timing = {
                "startMs": families[start_family]["startMs"],
                "endMs": families[end_family]["endMs"],
            }
            if occurrence["publishDecision"]["result"] == "words":
                words, reason = assess_complete_word_sequence(families[start_family],
                    applicable, publisher_family=start_family)
                wanted = [word["occurrenceWordId"] for word in mapping["performedOccurrences"][index]["words"]]
                if (reason is not None or not words
                        or [word["occurrenceWordId"] for word in families[start_family]["words"]] != wanted):
                    raise FutureSongError("qualified endpoint word identity is incomplete")
                timing["words"] = words
            if timing["startMs"] >= timing["endMs"]:
                raise EndpointReviewSelectionError(
                    "endpoint-review-selection-empty-or-reversed-"
                    f"{review_positions.get(occurrence['occurrenceId'], 0)}"
                )
            line_timings.append(timing)
            line_review_positions.append(
                review_positions.get(occurrence["occurrenceId"], 0)
            )
        overlap_pairs = [
            f"{line_review_positions[index]}-{line_review_positions[index + 1]}"
            for index, (left, right) in enumerate(
                zip(line_timings, line_timings[1:])
            )
            if left["endMs"] > right["startMs"]
        ]
        if overlap_pairs:
            raise EndpointReviewSelectionError(
                "endpoint-review-selection-overlap-" + "_".join(overlap_pairs)
            )

        workspace = AlignmentWorkspace(workflow.root, workspace_root=workflow.workspace_root)
        canonical_path = workspace.current_backup_path(video_id)
        original_raw = read_bounded_regular_bytes(
            canonical_path, max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain
        )
        if hashlib.sha256(original_raw).hexdigest() != declaration[
            "canonicalPlainSha256"
        ]:
            raise FutureSongError("endpoint-review canonical lyrics changed")
        try:
            lyrics = json.loads(original_raw.decode("utf-8-sig"))
            metrics = AlignmentMetrics(**diagnostics["metrics"])
        except (KeyError, TypeError, ValueError, UnicodeError, json.JSONDecodeError) as error:
            raise FutureSongError("endpoint-review alignment inputs are invalid") from error
        mapped_occurrences = mapping["performedOccurrences"]
        numeric = mapping.get("numeric")
        if not isinstance(numeric, dict):
            raise FutureSongError("endpoint-review alignment mapping is incomplete")
        alignment = AlignmentResult(
            accepted=True,
            reasons=(),
            starts_ms=tuple(timing["startMs"] for timing in line_timings),
            source_physical_indices=tuple(
                occurrence["sourcePhysicalIndex"] for occurrence in mapped_occurrences
            ),
            metrics=metrics,
        )
        try:
            candidate = build_synced_sidecar(
                lyrics,
                alignment,
                analysis_audio["durationMs"],
                line_timings=line_timings,
            )
            self.operations.validate_synced_companion_timing(candidate)
        except (TypeError, ValueError) as error:
            raise FutureSongError("endpoint-review projection is invalid") from error
        require_endpoint_content(declaration, mapping, endpoint_evidence, lyrics,
            workflow.alignment_state_path(video_id).parent)
        if sha256_file(review_path) != review_sha256:
            raise EndpointReviewSelectionError('endpoint-review-decision-changed')
        mapping_artifact = save_alignment_artifacts(
            workspace,
            video_id,
            candidate=candidate,
            result=alignment,
            lexical_mapping=mapping,
            public_line_timings=line_timings,
        )
        if not isinstance(mapping_artifact, dict):
            raise FutureSongError("endpoint-review mapping could not be committed")
        sidecar = workflow.owner_sidecar_path(video_id)
        _, canonical_plain_sha256 = commit_candidate(
            workspace, video_id, sidecar, original_raw, candidate, expected_current_raw=expected_sidecar_raw
        )
        word_line_count = sum(bool(timing.get("words")) for timing in line_timings)
        result_class = "committed-words" if word_line_count == len(line_timings) else "committed-mixed" if word_line_count else "committed-lines"
        alignment_state = copy.deepcopy(diagnostics)
        alignment_state.pop("resultClass", None)
        alignment_state.update(
            videoId=video_id,
            attemptId=attempt_id,
            status=result_class,
            alignmentVersion=ALIGNMENT_VERSION,
            durationMs=analysis_audio["durationMs"],
            canonicalPlainSha256=canonical_plain_sha256,
            updatedAtUnix=completed_at_unix,
            reasons=[],
            routeFailures=[],
            endpointEvidence={
                "resultClass": result_class,
                "lineCount": len(line_timings),
                "lineReadyCount": len(line_timings),
                "wordLineCount": word_line_count,
                "lineOnlyCount": len(line_timings)-word_line_count,
                "lineOnlyReasons": {
                    "private-endpoint-candidate-review": len(expected_occurrences)
                },
                "unresolvedLineReasons": {},
            },
            endpointReview=review_contract.EndpointReviewResult(pointer['declarationSha256'], review_sha256,
                source_binding['attemptId'], source_binding['runSha256'], len(expected_occurrences),
                declaration['policy']).to_record(),
            **mapping_artifact,
        )
        state_path = workflow.alignment_state_path(video_id)
        atomic_write_json(state_path, alignment_state)
        sidecar_sha256 = sha256_file(sidecar)
        alignment_artifacts = {
            "sourcesSha256": declaration["sourcesSha256"],
            "sidecarSha256": sidecar_sha256,
            "alignmentCandidateSha256": sha256_file(
                state_path.with_name("candidate.private.lyrics.json")
            ),
            "mappingArtifactSha256": mapping_artifact["mappingArtifactSha256"],
            "alignmentStateSha256": sha256_file(state_path),
            "analysisAudioSha256": analysis_audio["sha256"],
            "artworkSha256": analysis_audio["artworkSha256"],
        }
        if alignment_artifacts["alignmentCandidateSha256"] != sidecar_sha256:
            raise FutureSongError("endpoint-review candidate and sidecar differ")
        originals = [
            track for track in lyrics.get("tracks", []) if track.get("kind") == "original"
        ]
        if len(originals) != 1 or not isinstance(originals[0].get("text"), str):
            raise FutureSongError("endpoint-review original display authority is invalid")
        return ReviewCompleted(job, publication={
            'attemptId': attempt_id, 'reviewSha256': review_sha256,
            'originalText': originals[0]['text'], 'editorialSha256': declaration['editorialSha256'],
            'analysisAudio': analysis_audio, 'alignmentState': alignment_state,
            'alignmentArtifacts': alignment_artifacts})


@dataclass(frozen=True)
class DisplayReviewInputs:
    job_json: bytes
    attempt: Path
    editorial_sha256: str
    sources_sha256: str
    mapping_json: bytes
    mapping_identity_json: bytes
    state_path: Path
    analysis_audio_json: bytes
    alignment_run_matches: bool
    authority_original_text: str
    sidecar: Path
    current_original_json: bytes | None = None
    canonical_plain_raw: bytes | None = None
    spoken_target_root: Path | None = None


@dataclass(frozen=True)
class DisplayReviewDecision:
    admitted_sidecar_raw: bytes
    projected_raw: bytes
    display_text: str
    declaration_json: bytes
    pointer_json: bytes
    alignment_state_json: bytes
    mapping_identity_json: bytes


class DisplayReviewAuthorityError(FutureSongError):
    """The display edit changes the frozen performed content."""


@dataclass(frozen=True)
class DisplayReviewService:
    operations: ReviewOperations
    policy: ReviewPolicy

    def validate(self, inputs: DisplayReviewInputs) -> DisplayReviewDecision:
        """Calculate a display projection from exact evidence without publishing it."""
        job = json.loads(inputs.job_json)
        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION, reproject_display_only

        video_id = validate_video_id(job["videoId"])
        pointer = job.get("displayReview")
        pointer_keys = {
            "attemptId",
            "declarationSha256",
            "preReviewTextSha256",
            "alignmentRunId",
            "alignmentRunSha256",
        }
        if not isinstance(pointer, dict) or not (
            set(pointer) == pointer_keys
            or (
                inputs.current_original_json is not None
                and set(pointer) == pointer_keys | {'result', 'originalSha256', 'runId', 'runSha256'}
                and pointer.get('result') == 'accepted-display-only'
                and pointer.get('runId') == pointer.get('attemptId')
                and all(isinstance(pointer.get(key), str) and re.fullmatch(r'[0-9a-f]{64}', pointer[key])
                        for key in ('originalSha256', 'runSha256'))
            )
            or (
                set(pointer) == pointer_keys | {"result", "rejectedEditorialSha256"}
                and pointer.get("result") == "rejected-authority-change"
                and pointer.get("rejectedEditorialSha256") == inputs.editorial_sha256
            )
        ):
            raise FutureSongError("display-review state pointer is missing or invalid")
        # A rejected display edit does not invalidate the frozen authority or alignment.
        # Recheck every binding and the current body; clear rejection only on acceptance.
        accepted_display = pointer if pointer.get('result') == 'accepted-display-only' else None
        pointer = {key: pointer[key] for key in pointer_keys}
        attempt_id = pointer["attemptId"]
        if not isinstance(attempt_id, str) or re.fullmatch(r"[0-9a-f]{32}", attempt_id) is None:
            raise FutureSongError("display-review attempt identity is invalid")
        attempt = inputs.attempt
        owner = {
            "kind": "display-review-attempt",
            "videoId": video_id,
            "attemptId": attempt_id,
        }
        manifest = read_bounded_json(
            attempt / GENERATED_MANIFEST_NAME,
            max_bytes=16 * 1024,
            root=attempt,
        )
        if manifest != {
            "manifestVersion": 1,
            "owner": owner,
            "generatedPaths": ["."],
        }:
            raise FutureSongError("display-review generated-file manifest is invalid")
        if {
            path.name for path in attempt.iterdir()
        } != {GENERATED_MANIFEST_NAME, "declaration.json", "original.txt"} or any(
            not path.is_file() or path.is_symlink() for path in attempt.iterdir()
        ):
            raise FutureSongError("display-review attempt contains an unexpected entry")
        declaration_path = attempt / "declaration.json"
        declaration = read_bounded_json(
            declaration_path, max_bytes=MAX_JSON_BYTES, root=attempt
        )
        declaration_keys = {
            "declarationVersion",
            "policy",
            "videoId",
            "attemptId",
            "bodyFile",
            "preReviewTextSha256",
            "preReviewSidecarSha256",
            "editorialSha256",
            "sourcesSha256",
            "analysisAudio",
            "alignment",
            "mapping",
        }
        if (
            not isinstance(declaration, dict)
            or set(declaration) != declaration_keys
            or declaration.get("declarationVersion") != self.policy.display_version
            or declaration.get("policy") != self.policy.display_policy
            or declaration.get("videoId") != video_id
            or declaration.get("attemptId") != attempt_id
            or declaration.get("bodyFile") != "original.txt"
            or sha256_file(declaration_path) != pointer["declarationSha256"]
            or declaration.get("preReviewTextSha256")
            != pointer["preReviewTextSha256"]
        ):
            raise FutureSongError("display-review declaration is stale or invalid")
        for value in (
            declaration.get("preReviewTextSha256"),
            declaration.get("preReviewSidecarSha256"),
            declaration.get("editorialSha256"),
            declaration.get("sourcesSha256"),
            pointer.get("alignmentRunSha256"),
        ):
            if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
                raise FutureSongError("display-review hash binding is invalid")

        alignment = declaration.get("alignment")
        mapping_binding = declaration.get("mapping")
        analysis_binding = declaration.get("analysisAudio")
        if (
            not isinstance(alignment, dict)
            or set(alignment)
            != {"attemptId", "stateSha256", "runId", "runSha256", "artifacts"}
            or alignment.get("attemptId") != pointer["alignmentRunId"]
            or alignment.get("runId") != pointer["alignmentRunId"]
            or alignment.get("runSha256") != pointer["alignmentRunSha256"]
            or not isinstance(alignment.get("artifacts"), dict)
            or not isinstance(mapping_binding, dict)
            or not isinstance(analysis_binding, dict)
            or set(analysis_binding) != {"sha256", "byteCount", "durationMs"}
        ):
            raise FutureSongError("display-review alignment binding is invalid")
        mapping, mapping_identity = json.loads(inputs.mapping_json), json.loads(inputs.mapping_identity_json)
        state_path = inputs.state_path
        alignment_state = read_bounded_json(
            state_path, max_bytes=MAX_JSON_BYTES, root=state_path.parent
        )
        candidate_path = state_path.with_name("candidate.private.lyrics.json")
        if (
            not isinstance(alignment_state, dict)
            or alignment_state.get("attemptId") != pointer["alignmentRunId"]
            or alignment_state.get("alignmentVersion") != ALIGNMENT_VERSION
            or alignment_state.get("status")
            not in {"committed-words", "committed-mixed", "committed-lines"}
            or sha256_file(state_path) != alignment.get("stateSha256")
            or mapping_identity != mapping_binding
            or any(
                alignment_state.get(key) != value
                for key, value in mapping_identity.items()
            )
            or not candidate_path.is_file()
            or sha256_file(candidate_path)
            != declaration["preReviewSidecarSha256"]
        ):
            raise FutureSongError("display-review alignment or mapping binding is stale")
        analysis_audio = json.loads(inputs.analysis_audio_json)
        if inputs.current_original_json is not None or inputs.canonical_plain_raw is not None:
            from stemwise_engine.lyrics_alignment import _plain_original
            if (inputs.current_original_json is None or inputs.canonical_plain_raw is None
                    or hashlib.sha256(inputs.canonical_plain_raw).hexdigest()
                        != alignment_state.get('canonicalPlainSha256')
                    or json.loads(inputs.current_original_json)
                        != _plain_original(json.loads(inputs.canonical_plain_raw))):
                raise FutureSongError('display-review original authority is stale')
        elif inputs.editorial_sha256 != declaration['editorialSha256']:
            raise FutureSongError('display-review editorial authority binding is stale')
        if inputs.sources_sha256 != declaration['sourcesSha256']:
            raise FutureSongError('display-review editorial authority binding is stale')
        if {key: analysis_audio[key] for key in ('sha256', 'byteCount', 'durationMs')} != analysis_binding or alignment['artifacts'].get('artworkSha256') != analysis_audio.get('artworkSha256'):
            raise FutureSongError('display-review analysis audio binding is stale')
        if not inputs.alignment_run_matches:
            raise FutureSongError('display-review alignment run binding is stale')

        body_path = attempt / "original.txt"
        self.operations.validate_plain_draft(body_path)
        display_text = read_bounded_regular_bytes(
            body_path, max_bytes=MAX_JSON_BYTES, root=attempt
        ).decode("utf-8")[:-1]
        if (accepted_display is not None
                and hashlib.sha256(display_text.encode('utf-8')).hexdigest() != accepted_display['originalSha256']):
            raise FutureSongError('accepted display-review original binding is stale')
        aligned_lyrics = read_bounded_json(
            candidate_path, max_bytes=MAX_JSON_BYTES, root=candidate_path.parent
        )
        if not isinstance(aligned_lyrics, dict):
            raise FutureSongError("display-review aligned baseline is invalid")
        try:
            reviewed_languages = None
            admission = alignment_state.get('contentAdmission')
            if isinstance(admission, dict) and admission.get('spokenTargets') is not None:
                from stemwise_engine.lyric_content import validate_content_admission_record
                from stemwise_engine.spoken_targets import load
                admission = validate_content_admission_record(admission)
                sources = [row['sourcePhysicalIndex'] for row in mapping['performedOccurrences']]
                if (inputs.spoken_target_root is None
                        or admission['sourcePhysicalIndices'] != sources
                        or admission['audioSha256'] != analysis_audio['sha256']
                        or admission['canonicalPlainSha256'] != alignment_state.get('canonicalPlainSha256')):
                    raise ValueError('display-review spoken language binding differs')
                originals = [track for track in aligned_lyrics.get('tracks', [])
                    if isinstance(track, dict) and track.get('kind') == 'original']
                if len(originals) != 1:
                    raise ValueError('display-review original language authority is invalid')
                original = {key: value for key, value in originals[0].items() if key != 'lines'}
                original.update(format='plain', text=inputs.authority_original_text)
                plan = load(inputs.spoken_target_root, video_id, original, sources, analysis_audio['sha256'])
                if plan is None or plan.reference != admission['spokenTargets']:
                    raise ValueError('display-review spoken language review changed')
                plan.require_current()
                reviewed_languages = [[language for _, language in plan.words[source]] for source in sources]
            projected = reproject_display_only(
                aligned_lyrics,
                display_text,
                mapping,
                analysis_audio["durationMs"],
                authority_original_text=inputs.authority_original_text,
                reviewed_word_languages=reviewed_languages,
            )
        except (OSError, TypeError, ValueError) as error:
            raise DisplayReviewAuthorityError(
                "display review changed lexical content, a token/line boundary, an "
                "occurrence, order, or a disallowed grapheme; undo the unsupported "
                "display edit and run Validate again, or correct the upstream authority "
                "and rerun alignment for an intentional wording or stanza change"
            ) from error
        self.operations.validate_synced_companion_timing(projected)
        projected_raw = json_bytes(projected, pretty=True)
        projected_sha256 = hashlib.sha256(projected_raw).hexdigest()
        sidecar = inputs.sidecar
        admitted_sidecar_raw = read_bounded_regular_bytes(sidecar, max_bytes=MAX_JSON_BYTES, root=sidecar.parent)
        current_sidecar_sha256 = hashlib.sha256(admitted_sidecar_raw).hexdigest()
        if current_sidecar_sha256 not in {
            declaration["preReviewSidecarSha256"],
            projected_sha256,
        }:
            raise FutureSongError("display-review sidecar binding is stale")
        return DisplayReviewDecision(admitted_sidecar_raw, projected_raw, display_text, json_bytes(declaration),
            json_bytes(pointer), json_bytes(alignment_state), inputs.mapping_identity_json)


def finalization_pointer_matches(workflow, job, child, current_child_sha, schema_sha256):
    """Recover a published package whose immutable run preceded the job commit."""
    report_root = workflow.report_store.report_root(job['videoId'])
    pointer = validate_generated_path(report_root, report_control_path(report_root, 'current-run.json'),
        workspace_root=workflow.workspace_root)
    current_run = read_bounded_json(pointer, max_bytes=16*1024, root=report_root)
    destination = validate_generated_path(workflow.ready, workflow.ready/child.name,
        workspace_root=workflow.workspace_root)
    package_sha = sha256_file(destination)
    if package_sha != current_child_sha:
        from stemwise_engine.original_delivery import read_bundle
        read_bundle(destination, workflow.runtime_config(), metadata=child)
    return (destination.is_file() and isinstance(current_run, dict)
        and workflow.report_store.immutable_run_matches(job['videoId'], run_id=current_run.get('runId'),
            run_sha256=current_run.get('runSha256'), operation='package-finalization',
            artifacts={'workspaceRelativePath': destination.relative_to(workflow.workspace_root).as_posix(),
                'sha256': package_sha, 'reviewEvidenceRetained': True}, schema_sha256=schema_sha256,
            require_current_pointer=True,
            checkpoint_requirements=workflow.finalization_checkpoint_requirements(job, package_sha)))


def finalized_delivery_matches(workflow, job, child, current_child_sha, schema_sha256, *,
        delivery_source=None):
    """Bind physical publication and its finalization run to the validated child."""
    delivery = job.get('delivery')
    if not isinstance(delivery, dict) or delivery.get('reviewEvidenceRetained') is not True:
        return False
    destination = workflow.ready/child.name
    package_sha = delivery.get('sha256')
    physical = delivery_source if delivery_source is not None else destination
    if package_sha != current_child_sha:
        from stemwise_engine.original_delivery import read_bundle
        if delivery_source is not None or not isinstance(delivery.get('original'), dict):
            return False
        read_bundle(physical, workflow.runtime_config(), metadata=child, original=delivery['original'])
    if (not physical.is_file() or sha256_file(physical) != package_sha
            or delivery.get('path') != workflow.windows_path(destination)
            or delivery.get('bytes') != physical.stat().st_size
            or not isinstance(delivery.get('preparedAt' if job['state'] == 'DELIVERY_PUBLICATION_PENDING' else 'deliveredAt'), str)
            or delivery.get('readyToImport') is not (job['state'] != 'DELIVERY_PUBLICATION_PENDING')):
        return False
    return workflow.report_store.immutable_run_matches(job['videoId'], run_id=delivery.get('runId'),
        run_sha256=delivery.get('runSha256'), operation='package-finalization',
        artifacts={'workspaceRelativePath': destination.relative_to(workflow.workspace_root).as_posix(),
            'sha256': package_sha, 'reviewEvidenceRetained': True}, schema_sha256=schema_sha256,
        require_current_pointer=delivery_source is None,
        checkpoint_requirements=workflow.finalization_checkpoint_requirements(job, package_sha))


@dataclass(frozen=True)
class ValidatedSnapshotService:
    context: ValidatedSnapshotContext
    operations: ReviewOperations
    policy: ReviewPolicy

    def validate(self, request: ValidatedSnapshotRequest) -> bool:
        """Verify that validation still names the exact current immutable inputs.

        This check is intentionally read-only.  It allows Validate and Finalize
        to be idempotent while refusing to reuse a snapshot after any editorial,
        sidecar, child-package, report, or delivered-package change.
        """
        workflow = self.context
        job = request.job
        require_delivery = request.require_delivery
        allow_finalization_pointer = request.allow_finalization_pointer
        allow_pending_delivery_cleanup = request.allow_pending_delivery_cleanup
        delivery_source = request.delivery_source
        if 'artworkRepairPending' in job or job.get("state") not in {"VALIDATED", "DELIVERY_PUBLICATION_PENDING", *self.policy.terminal_states}:
            return False
        if job.get("policyVersion") != self.policy.workflow_version:
            return False
        validation = job.get("validation")
        if not isinstance(validation, dict):
            return False
        if validation.get('kind') == 'capability-completion':
            return workflow.capability_snapshot_current(job, require_delivery=require_delivery,
                allow_finalization_pointer=allow_finalization_pointer,
                allow_pending_delivery_cleanup=allow_pending_delivery_cleanup, delivery_source=delivery_source)
        delivery = job.get("delivery")
        if isinstance(delivery, dict) and delivery.get("reviewEvidenceRetained") is not True:
            return False
        video_id = job.get("videoId")
        if not isinstance(video_id, str):
            return False
        try:
            editorial_sha = workflow.editorial_sha(job)
            policy = job.get("policy")
            schema_sha256 = policy.get("schemaSha256", "") if isinstance(policy, dict) else ""
            report_root = workflow.report_store.report_root(video_id)
            report_path = validate_generated_path(
                report_root,
                report_root / "validation.json",
                workspace_root=workflow.workspace_root,
            )
            sources_path = workflow.job_dir(video_id) / "sources.json"
            sidecar = workflow.owner_sidecar_path(video_id)
            child = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            if not all(
                path.is_file() for path in (report_path, sources_path, sidecar, child)
            ):
                return False
            report = read_bounded_json(
                report_path,
                max_bytes=MAX_JSON_BYTES,
                root=report_root,
            )
            sources_sha = sha256_file(sources_path)
            sidecar_sha = sha256_file(sidecar)
            current_child_sha = sha256_file(child)
            repair = None
            child_document = None
            if require_delivery and job.get("artworkRepair") is None:
                current_artwork_sha = validation.get("artworkSha256")
            else:
                child_document, cover = self.operations.read_child(child, workflow.runtime_config())
                if (
                    child_document.get("source", {}).get("videoId") != video_id
                    or cover is None
                ):
                    return False
                current_artwork_sha = hashlib.sha256(cover).hexdigest()
                require_artwork_pointer = not require_delivery and job.get("state") == "VALIDATED"
                artwork_inputs = dict(child_document=child_document,
                    child_sha256=current_child_sha, artwork_sha256=current_artwork_sha)
                try:
                    repair = workflow.validated_artwork_repair(job, **artwork_inputs,
                        require_current_pointer=require_artwork_pointer)
                except review_contract.ArtworkUnavailableError:
                    if not require_artwork_pointer or not allow_finalization_pointer:
                        raise
                    repair = workflow.validated_artwork_repair(job, **artwork_inputs,
                        require_current_pointer=False)
                    if not finalization_pointer_matches(workflow, job, child, current_child_sha, schema_sha256):
                        return False
            child_sha = (
                repair["validationChildSha256"] if repair else current_child_sha
            )
            artwork_sha = (
                repair["validationArtworkSha256"] if repair else current_artwork_sha
            )
            if any(
                not isinstance(value, str)
                or re.fullmatch(r"[0-9a-f]{64}", value) is None
                for value in (child_sha, artwork_sha)
            ):
                return False
            retained_audio = report.get("retainedAnalysisAudio")
            display_pointer = job.get("displayReview")
            display_artifacts = report.get("displayReviewArtifacts")
            alignment_artifacts = report.get("alignmentArtifacts")
            if (
                report.get("result") != "pass"
                or report.get("stage") != "display-review"
                or report.get("videoId") != video_id
                or report.get("policyVersion") != self.policy.workflow_version
                or not isinstance(report.get("generatedAt"), str)
                or report.get("editorialSha256") != editorial_sha
                or report.get("sourcesSha256") != sources_sha
                or report.get("sidecarSha256") != sidecar_sha
                or report.get("childSha256") != child_sha
                or report.get("artworkSha256") != artwork_sha
                or validation.get("report") != workflow.windows_path(report_path)
                or validation.get("reportSha256") != sha256_file(report_path)
                or validation.get("editorialSha256") != editorial_sha
                or validation.get("sourcesSha256") != sources_sha
                or validation.get("sidecarSha256") != sidecar_sha
                or validation.get("childSha256") != child_sha
                or validation.get("artworkSha256") != artwork_sha
                or validation.get("validatedAt") != report.get("generatedAt")
                or validation.get("lyricsTiming") != report.get("lyricsTiming")
                or validation.get("runId") != report.get("runId")
                or validation.get("runSha256") != report.get("runSha256")
                or validation.get("displayReviewAttemptId")
                != report.get("displayReviewAttemptId")
                or validation.get("displayReviewDeclarationSha256")
                != report.get("displayReviewDeclarationSha256")
                or validation.get("displayOriginalSha256")
                != report.get("displayOriginalSha256")
                or validation.get("alignmentRunId")
                != report.get("alignmentRunId")
                or validation.get("alignmentRunSha256")
                != report.get("alignmentRunSha256")
                or report.get("temporaryAudioRetained") is not True
                or not isinstance(retained_audio, dict)
                or set(retained_audio) != {"sha256", "byteCount", "durationMs"}
                or not isinstance(display_artifacts, dict)
                or not isinstance(alignment_artifacts, dict)
                or not isinstance(display_pointer, dict)
                or display_pointer.get("result") != "accepted-display-only"
                or display_pointer.get("attemptId")
                != report.get("displayReviewAttemptId")
                or display_pointer.get("declarationSha256")
                != report.get("displayReviewDeclarationSha256")
                or display_pointer.get("originalSha256")
                != report.get("displayOriginalSha256")
                or display_pointer.get("runId") != report.get("runId")
                or display_pointer.get("runSha256") != report.get("runSha256")
            ):
                return False
            if child_document is None:
                child_document, _ = self.operations.read_child(child, workflow.runtime_config())
            from stemwise_engine.editorial_admission import require_completed_lyrics
            require_completed_lyrics(child_document['lyrics'], duration_ms=retained_audio['durationMs'], endpoint_review=job.get('endpointReview'))
            if not workflow.companion_projection_current(job, child_document['lyrics'], retained_audio):
                return False
            attempt = workflow.display_review_attempt_dir(
                video_id, report["displayReviewAttemptId"]
            )
            declaration_path = attempt / "declaration.json"
            body_path = attempt / "original.txt"
            manifest = read_bounded_json(
                attempt / GENERATED_MANIFEST_NAME,
                max_bytes=16 * 1024,
                root=attempt,
            )
            if manifest != {
                "manifestVersion": 1,
                "owner": {
                    "kind": "display-review-attempt",
                    "videoId": video_id,
                    "attemptId": report["displayReviewAttemptId"],
                },
                "generatedPaths": ["."],
            }:
                return False
            self.operations.validate_plain_draft(body_path)
            body = read_bounded_regular_bytes(
                body_path, max_bytes=MAX_JSON_BYTES, root=attempt
            )
            if (
                not declaration_path.is_file()
                or sha256_file(declaration_path)
                != report.get("displayReviewDeclarationSha256")
                or hashlib.sha256(body[:-1]).hexdigest()
                != report.get("displayOriginalSha256")
                or {path.name for path in attempt.iterdir()}
                != {GENERATED_MANIFEST_NAME, "declaration.json", "original.txt"}
            ):
                return False
            _, mapping_identity = workflow.lexical_mapping(video_id)
            state_path = workflow.alignment_state_path(video_id)
            candidate_path = state_path.with_name("candidate.private.lyrics.json")
            if (
                report.get("mappingSha256") != mapping_identity["mappingSha256"]
                or not candidate_path.is_file()
            ):
                return False
            candidate_sha = sha256_file(candidate_path)
            if alignment_artifacts != {
                "sourcesSha256": sources_sha,
                "sidecarSha256": candidate_sha,
                "alignmentCandidateSha256": candidate_sha,
                "mappingArtifactSha256": mapping_identity[
                    "mappingArtifactSha256"
                ],
                "alignmentStateSha256": sha256_file(state_path),
                "analysisAudioSha256": retained_audio["sha256"],
                "artworkSha256": artwork_sha,
            } or display_artifacts != {
                "declarationSha256": report["displayReviewDeclarationSha256"],
                "originalSha256": report["displayOriginalSha256"],
                "sourcesSha256": sources_sha,
                "sidecarSha256": sidecar_sha,
                "childSha256": child_sha,
                "artworkSha256": artwork_sha,
                "mappingArtifactSha256": mapping_identity[
                    "mappingArtifactSha256"
                ],
                "alignmentRunSha256": report["alignmentRunSha256"],
            }:
                return False
            if not workflow.report_store.report_authority_matches(
                video_id, editorial_sha, sources_sha
            ):
                return False
            if not workflow.report_store.immutable_run_matches(
                video_id,
                run_id=report.get("alignmentRunId"),
                run_sha256=report.get("alignmentRunSha256"),
                operation="lyrics-alignment",
                artifacts=alignment_artifacts,
                schema_sha256=schema_sha256,
                require_current_pointer=False,
            ):
                return False
            if not workflow.report_store.immutable_run_matches(
                video_id,
                run_id=report.get("runId"),
                run_sha256=report.get("runSha256"),
                operation="display-review",
                artifacts=display_artifacts,
                schema_sha256=schema_sha256,
                require_current_pointer=False,
            ):
                return False

            endpoint_pointer = job.get("endpointReview")
            endpoint_metadata = (report.get("lyricsTiming") or {}).get(
                "endpointReview"
            )
            if (endpoint_pointer is None) != (endpoint_metadata is None):
                return False
            if endpoint_pointer is not None:
                if (
                    not isinstance(endpoint_pointer, dict)
                    or not isinstance(endpoint_metadata, dict)
                    or endpoint_pointer.get("result") != "accepted"
                    or any(
                        endpoint_pointer.get(key) != endpoint_metadata.get(key)
                        for key in (
                            "declarationSha256",
                            "reviewSha256",
                            "sourceAlignmentRunId",
                            "sourceAlignmentRunSha256",
                        )
                    )
                ):
                    return False
                endpoint_attempt = workflow.endpoint_review_attempt_dir(
                    video_id, endpoint_pointer["attemptId"]
                )
                endpoint_manifest = read_bounded_json(
                    endpoint_attempt / GENERATED_MANIFEST_NAME,
                    max_bytes=16 * 1024,
                    root=endpoint_attempt,
                )
                declaration_path = endpoint_attempt / "declaration.json"
                review_path = endpoint_attempt / "review.json"
                if (
                    endpoint_manifest
                    != {
                        "manifestVersion": 1,
                        "owner": {
                            "kind": "endpoint-review-attempt",
                            "videoId": video_id,
                            "attemptId": endpoint_pointer["attemptId"],
                        },
                        "generatedPaths": ["."],
                    }
                    or {path.name for path in endpoint_attempt.iterdir()}
                    != {GENERATED_MANIFEST_NAME, "declaration.json", "review.json"}
                    or sha256_file(declaration_path)
                    != endpoint_pointer["declarationSha256"]
                    or sha256_file(review_path) != endpoint_pointer["reviewSha256"]
                ):
                    return False

                endpoint_decision = read_bounded_json(review_path, max_bytes=MAX_JSON_BYTES, root=endpoint_attempt)
                if "audioEvidence" in endpoint_decision:
                    from stemwise_engine.endpoint_dispatch import evidence_adapter
                    binding = endpoint_decision["audioEvidence"]
                    evidence_adapter(binding.get('method') if isinstance(binding, dict) else None).stored(endpoint_attempt, binding)

            if not require_delivery and repair is None:
                display_is_current = workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=report.get("runId"),
                    run_sha256=report.get("runSha256"),
                    operation="display-review",
                    artifacts=display_artifacts,
                    schema_sha256=schema_sha256,
                    require_current_pointer=True,
                )
                if not display_is_current:
                    if (not allow_finalization_pointer or not finalization_pointer_matches(
                            workflow, job, child, current_child_sha, schema_sha256)):
                        return False

            temporary = workflow.analysis_temp / video_id
            if require_delivery:
                if (
                    temporary.exists() or temporary.is_symlink()
                ) and not allow_pending_delivery_cleanup:
                    return False
            elif repair is None:
                current_audio = workflow.completed_analysis_audio(
                    workflow.pipeline(), video_id
                )
                if {
                    key: current_audio[key]
                    for key in ("sha256", "byteCount", "durationMs")
                } != retained_audio:
                    return False

            if require_delivery:
                if job.get("state") not in {"DELIVERY_PUBLICATION_PENDING", *self.policy.terminal_states}:
                    return False
                if not finalized_delivery_matches(workflow, job, child, current_child_sha,
                        schema_sha256, delivery_source=delivery_source):
                    return False
            return True
        except (
            OSError,
            ValueError,
            TypeError,
            KeyError,
            UnicodeError,
            json.JSONDecodeError,
            FutureSongError,
        ):
            return False


def companion_requirements(manifest: dict[str, Any]) -> tuple[bool | None, bool | None]:
    return companion_language_roles(manifest.get('originalLanguage'), manifest.get('componentLanguages'))


def requirements_from_draft(draft: Path) -> tuple[bool | None, bool | None] | None:
    manifest_path = draft / "lyrics.meta.json"
    if not manifest_path.exists() and not manifest_path.is_symlink():
        return None
    try:
        manifest = read_bounded_json(
            manifest_path, max_bytes=MAX_JSON_BYTES, root=draft
        )
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise FutureSongError("lyrics.meta.json is not a safe bounded JSON file") from error
    return companion_requirements(manifest)


def review_template(
    video_id: str,
    sources: dict[str, Any] | None = None,
) -> dict[str, Any]:
    availability = (
        sources.get("lyricsAvailability")
        if isinstance(sources, dict)
        else "not-found"
    )
    evidence_ids: list[str] = []
    if isinstance(sources, dict):
        for record in sources.get("lyricEvidenceSources", []):
            source_id = record.get("videoId") if isinstance(record, dict) else None
            if isinstance(source_id, str) and source_id not in evidence_ids:
                evidence_ids.append(source_id)
    if video_id not in evidence_ids:
        evidence_ids.insert(0, video_id)
    if availability == "draft-acquired":
        availability_note = "An original draft is staged locally for editorial review."
    elif availability == "candidate-evidence":
        availability_note = (
            "Usable candidate evidence is staged locally; create and review the draft."
        )
    else:
        availability_note = (
            "No usable lyric evidence is staged yet; continue automatic and public-source research."
        )
    return {
        "formatVersion": 2,
        "policyVersion": editorial.POLICY_VERSION,
        "videoId": video_id,
        "authorityOutcome": "pending",
        "reviewer": "REPLACE_WITH_REVIEWER",
        "completedAt": "REPLACE_WITH_RFC3339_UTC",
        "sourceDisposition": "REPLACE_AFTER_REVIEW",
        "recordingOrSourceAssociationReviewed": False,
        "youtubeSurfaceVariantsReviewed": False,
        "publicSourceResearchComplete": False,
        "normalYouTubeVideoId": video_id,
        "youtubeMusicVideoId": video_id,
        "lyricEvidenceVideoIds": evidence_ids,
        "originalReviewed": False,


        "companionReviews": {},
        "recordingReview": {
            "formatVersion": 2,
            "audioSha256": "COPY_FROM_STATUS_REVIEW_INPUTS",
            "draftSha256": "COPY_FROM_STATUS_REVIEW_INPUTS",
            "sourcesSha256": "COPY_FROM_STATUS_REVIEW_INPUTS",
            "recordingCompared": False,
            "versionSpecificWordingReviewed": False,
            "performedOrderReviewed": False,
            "sourceAssociations": [
                {"videoId": source_id, "relation": "unusable"}
                for source_id in evidence_ids
            ],
            "publicSources": [],
        },
        "metadataReview": {
            "formatVersion": 1,
            "videoId": video_id,
            "originalMetadataSha256": "COPY_FROM_STATUS_REVIEW_INPUTS",
            "sourcesSha256": "COPY_FROM_STATUS_REVIEW_INPUTS",
            "title": "REPLACE_AFTER_RECORDING_REVIEW",
            "artists": [],
            "evidenceUrls": [],
            "recordingIdentityConfirmed": False,
        },
        "unresolvedMaterialIssues": ["Remove this placeholder after review."],
        "notes": (
            f"{availability_note} Do not put lyric bodies in this review file; "
            "record only concise evidence and review decisions."
        ),
    }

ENDPOINT_REVIEW_FAMILIES = frozenset(
    {
        "mms-fa",
        "language-wav2vec2",
        "owsm-ctc",
        "nemo-nfa",
    }
)
ENDPOINT_REVIEW_REASONS = frozenset(
    {
        "line-end-disagreement",
        "line-start-disagreement",
    }
)

@dataclass(frozen=True)
class ReviewConstructionContext:
    root: Path
    workspace_root: Path
    report_store: Any
    alignment_state_path: Callable
    display_review_attempt_dir: Callable
    endpoint_review_attempt_dir: Callable
    job_dir: Callable
    owner_sidecar_path: Callable
    lexical_mapping: Callable
    validate_alignment_attempt_id: Callable
    completed_analysis_audio: Callable
    editorial_sha: Callable
    pipeline: Callable
    song_policy_matches: Callable


@dataclass
class ReviewConstructionService:
    context: ReviewConstructionContext

    def create_display_review_attempt(
        self,
        job: dict[str, Any],
        *,
        original_text: str,
        editorial_sha256: str,
        analysis_audio: dict[str, Any],
        alignment_state: dict[str, Any],
        alignment_run_sha256: str,
        alignment_artifacts: dict[str, Any],
    ) -> dict[str, Any]:
        """Create or resume one restricted, body-minimal display review."""

        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION

        video_id = validate_video_id(job["videoId"])
        alignment_attempt_id = self.context.validate_alignment_attempt_id(
            alignment_state.get("attemptId")
        )
        if alignment_state.get("alignmentVersion") != ALIGNMENT_VERSION:
            raise FutureSongError("display review requires the current alignment version")
        mapping, mapping_identity = self.context.lexical_mapping(video_id)
        if any(
            alignment_state.get(key) != value
            for key, value in mapping_identity.items()
        ):
            raise FutureSongError("alignment state and occurrence mapping identities differ")
        if not isinstance(original_text, str) or not original_text or original_text.endswith(
            "\n"
        ) or "\r" in original_text:
            raise FutureSongError("aligned display authority text has invalid line endings")
        pre_review_text_sha256 = hashlib.sha256(original_text.encode("utf-8")).hexdigest()
        identity = json.dumps(
            {
                "videoId": video_id,
                "editorialSha256": editorial_sha256,
                "alignmentAttemptId": alignment_attempt_id,
                "alignmentRunSha256": alignment_run_sha256,
                "mappingSha256": mapping_identity["mappingSha256"],
                "preReviewTextSha256": pre_review_text_sha256,
            },
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("ascii")
        attempt_id = hashlib.sha256(b"stemwise-display-review\0" + identity).hexdigest()[
            :32
        ]
        state_path = self.context.alignment_state_path(video_id)
        candidate_path = state_path.with_name("candidate.private.lyrics.json")
        sidecar = self.context.owner_sidecar_path(video_id)
        pre_review_sidecar_sha256 = sha256_file(sidecar)
        if (
            not candidate_path.is_file()
            or sha256_file(candidate_path) != pre_review_sidecar_sha256
            or not isinstance(mapping.get("numeric"), dict)
        ):
            raise FutureSongError("aligned display-review baseline is missing or stale")
        declaration = {
            "declarationVersion": review_contract.DISPLAY_REVIEW_VERSION,
            "policy": review_contract.DISPLAY_REVIEW_POLICY,
            "videoId": video_id,
            "attemptId": attempt_id,
            "bodyFile": "original.txt",
            "preReviewTextSha256": pre_review_text_sha256,
            "preReviewSidecarSha256": pre_review_sidecar_sha256,
            "editorialSha256": editorial_sha256,
            "sourcesSha256": alignment_artifacts["sourcesSha256"],
            "analysisAudio": {
                key: analysis_audio[key]
                for key in ("sha256", "byteCount", "durationMs")
            },
            "alignment": {
                "attemptId": alignment_attempt_id,
                "stateSha256": sha256_file(state_path),
                "runId": alignment_attempt_id,
                "runSha256": alignment_run_sha256,
                "artifacts": alignment_artifacts,
            },
            "mapping": mapping_identity,
        }
        attempt = self.context.display_review_attempt_dir(video_id, attempt_id)
        root = attempt.parent
        root.mkdir(parents=True, exist_ok=True)
        root = validate_generated_path(
            self.context.job_dir(video_id),
            root,
            workspace_root=self.context.workspace_root,
        )
        attempt = validate_generated_path(
            root,
            attempt,
            workspace_root=self.context.workspace_root,
        )
        owner = {
            "kind": "display-review-attempt",
            "videoId": video_id,
            "attemptId": attempt_id,
        }
        if not attempt.exists():
            create_manifested_directory(
                root,
                attempt,
                owner=owner,
                workspace_root=self.context.workspace_root,
            )
        else:
            manifest = read_bounded_json(
                attempt / GENERATED_MANIFEST_NAME,
                max_bytes=16 * 1024,
                root=attempt,
            )
            if manifest != {
                "manifestVersion": 1,
                "owner": owner,
                "generatedPaths": ["."],
            }:
                raise FutureSongError("display-review generated-file manifest is invalid")
        declaration_path = attempt / "declaration.json"
        if declaration_path.is_symlink():
            raise FutureSongError("display-review declaration must not be a link")
        if declaration_path.is_file():
            if read_bounded_json(
                declaration_path, max_bytes=MAX_JSON_BYTES, root=attempt
            ) != declaration:
                raise FutureSongError("display-review declaration is stale or damaged")
        else:
            atomic_write_json(declaration_path, declaration)
        body_path = attempt / "original.txt"
        if body_path.is_symlink():
            raise FutureSongError("display-review body must not be a link")
        if not body_path.exists():
            atomic_write_bytes(body_path, (original_text + "\n").encode("utf-8"))
        editorial.validate_plain_draft(body_path)
        if any(
            path.name
            not in {GENERATED_MANIFEST_NAME, "declaration.json", "original.txt"}
            or not path.is_file()
            or path.is_symlink()
            for path in attempt.iterdir()
        ):
            raise FutureSongError("display-review attempt contains an unexpected entry")
        declaration_sha256 = sha256_file(declaration_path)
        pointer = {
            "attemptId": attempt_id,
            "declarationSha256": declaration_sha256,
            "preReviewTextSha256": pre_review_text_sha256,
            "alignmentRunId": alignment_attempt_id,
            "alignmentRunSha256": alignment_run_sha256,
        }
        job["state"] = "WAITING_FOR_DISPLAY_REVIEW"
        job["lastAlignmentAttemptId"] = alignment_attempt_id
        job["displayReview"] = pointer
        job.pop("activeAlignmentAttemptId", None)
        job.pop("validation", None)
        job.pop("error", None)
        return pointer


    def failed_endpoint_diagnostics(
        self, video_id: str, attempt_id: str, run_sha256: str
    ) -> tuple[dict[str, Any], dict[str, Any]]:
        video_id = validate_video_id(video_id)
        attempt_id = self.context.validate_alignment_attempt_id(attempt_id)
        report_root = self.context.report_store.report_root(video_id)
        run_path = validate_generated_path(
            report_root,
            report_root / "runs" / attempt_id / "run.json",
            workspace_root=self.context.workspace_root,
        )
        run = read_bounded_json(
            run_path, max_bytes=MAX_JSON_BYTES, root=report_root
        )
        if (
            sha256_file(run_path) != run_sha256
            or not isinstance(run, dict)
            or run.get("runId") != attempt_id
            or run.get("videoId") != video_id
            or run.get("operation") != "lyrics-alignment"
            or run.get("status") != "failed"
            or run.get("failureCode") != "line-unresolved"
            or not self.context.report_store.store.artifacts_match(run_path.parent, run)
        ):
            raise FutureSongError("endpoint-review source run is stale or invalid")
        manifest = run["reportArtifacts"][0]
        evidence = read_bounded_json(
            run_path.parent / manifest["fileName"],
            max_bytes=MAX_JSON_BYTES,
            root=run_path.parent,
        )
        diagnostics = evidence.get("diagnostics") if isinstance(evidence, dict) else None
        attempts = diagnostics.get("endpointRouteAttempts") if isinstance(diagnostics, dict) else None
        selected = diagnostics.get("selectedEndpointAttemptIndex") if isinstance(diagnostics, dict) else None
        selected_evidence = (
            attempts[selected].get("endpointEvidence")
            if isinstance(attempts, list)
            and type(selected) is int
            and 0 <= selected < len(attempts)
            and isinstance(attempts[selected], dict)
            else None
        )
        occurrences = (
            selected_evidence.get("occurrenceCandidates")
            if isinstance(selected_evidence, dict)
            else None
        )
        if (
            not isinstance(diagnostics, dict)
            or diagnostics.get("resultClass") != "line-unresolved"
            or not isinstance(occurrences, list)
            or not occurrences
        ):
            raise FutureSongError("endpoint-review source evidence is incomplete")
        return diagnostics, selected_evidence


    def endpoint_failure_source(self, job):
        from stemwise_engine.endpoint_adjudication import failure_source
        return failure_source(self.context, job, reasons_allowed=ENDPOINT_REVIEW_REASONS)


    def endpoint_review_source(
        self, job: dict[str, Any]
    ) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
        """Load one immutable endpoint-only failure and its ordered candidates."""

        blocker = self.endpoint_failure_source(job)
        if blocker.get("reason") != "exhausted-endpoint-cascade":
            raise FutureSongError("endpoint review requires exhausted endpoint evidence")
        diagnostics, selected_evidence = self.failed_endpoint_diagnostics(
            job["videoId"],
            blocker["alignmentAttemptId"],
            blocker["bindings"]["alignmentRunSha256"],
        )
        return blocker, diagnostics, selected_evidence


    @staticmethod
    def endpoint_review_occurrences(
        endpoint_evidence: dict[str, Any],
        mapping: dict[str, Any],
        duration_ms: int,
    ) -> list[dict[str, Any]]:
        """Return the exact unresolved occurrence choices without lyric bodies."""

        occurrences = workflow_diagnostics._occurrence_candidate_diagnostics(
            endpoint_evidence.get("occurrenceCandidates"))
        mapped = mapping.get("performedOccurrences")
        if (
            not isinstance(occurrences, list)
            or not isinstance(mapped, list)
            or len(occurrences) != len(mapped)
        ):
            raise FutureSongError("endpoint-review occurrence identity is incomplete")
        review: list[dict[str, Any]] = []
        for index, (occurrence, mapped_occurrence) in enumerate(zip(occurrences, mapped)):
            if (
                not isinstance(occurrence, dict)
                or not isinstance(mapped_occurrence, dict)
                or occurrence.get("occurrenceId")
                != mapped_occurrence.get("occurrenceId")
                or occurrence.get("performedIndex") != index
                or mapped_occurrence.get("performedIndex") != index
                or occurrence.get("sourcePhysicalIndex")
                != mapped_occurrence.get("sourcePhysicalIndex")
            ):
                raise FutureSongError("endpoint-review occurrence identity is stale")
            raw_candidates = occurrence.get("familyCandidates")
            if not isinstance(raw_candidates, dict) or not raw_candidates:
                raise FutureSongError("endpoint-review occurrence has no candidates")
            candidates: dict[str, dict[str, Any]] = {}
            for family, candidate in raw_candidates.items():
                if family not in ENDPOINT_REVIEW_FAMILIES:
                    continue
                if not isinstance(candidate, dict):
                    raise FutureSongError("endpoint-review candidate family is malformed")
                start_ms = candidate.get("startMs")
                end_ms = candidate.get("endMs")
                if (
                    type(start_ms) is not int
                    or type(end_ms) is not int
                    or not 0 <= start_ms < end_ms <= duration_ms
                ):
                    raise FutureSongError("endpoint-review candidate timing is invalid")
                candidates[family] = copy.deepcopy(candidate)
            if not candidates:
                raise FutureSongError("endpoint-review occurrence has no publishable candidate")
            decision = occurrence.get("publishDecision")
            if not isinstance(decision, dict) or decision.get("result") not in {
                "unresolved",
                "line-only",
                "words",
            }:
                raise FutureSongError("endpoint-review publish decision is invalid")
            if decision["result"] == "unresolved":
                review.append(
                    {
                        "occurrenceId": occurrence["occurrenceId"],
                        "performedIndex": index,
                        "sourcePhysicalIndex": occurrence["sourcePhysicalIndex"],
                        "candidateFamilies": candidates,
                    }
                )
            elif occurrence.get("selectedPublisherFamily") not in candidates:
                raise FutureSongError("ready endpoint publisher identity is invalid")
        if not review:
            raise FutureSongError("endpoint review has no unresolved occurrences")
        return review


    @staticmethod
    def endpoint_review_template(
        video_id: str, attempt_id: str, occurrences: list[dict[str, Any]]
    ) -> dict[str, Any]:
        return {
            "formatVersion": review_contract.ENDPOINT_REVIEW_VERSION,
            "videoId": video_id,
            "attemptId": attempt_id,
            "reviewer": "REPLACE_WITH_REVIEWER",
            "completedAt": "REPLACE_WITH_RFC3339_UTC",
            "outcome": "pending",
            "occurrences": [
                {
                    "occurrenceId": occurrence["occurrenceId"],
                    "performedIndex": occurrence["performedIndex"],
                    "startCandidateFamily": None,
                    "endCandidateFamily": None,
                }
                for occurrence in occurrences
            ],
        }


    def create_endpoint_review_attempt(self, job: dict[str, Any]) -> dict[str, Any]:
        """Create or resume one body-free, candidate-only private review."""

        from stemwise_engine.lyrics_alignment import AlignmentWorkspace

        video_id = validate_video_id(job["videoId"])
        existing = job.get("endpointReview")
        if isinstance(existing, dict):
            return existing
        blocker, diagnostics, endpoint_evidence = self.endpoint_review_source(job)
        mapping, mapping_identity = self.context.lexical_mapping(video_id)
        state_path = self.context.alignment_state_path(video_id)
        alignment_state = read_bounded_json(
            state_path, max_bytes=MAX_JSON_BYTES, root=state_path.parent
        )
        if (
            not isinstance(alignment_state, dict)
            or sha256_file(state_path)
            != blocker["bindings"]["alignmentStateSha256"]
            or any(
                alignment_state.get(key) != value
                for key, value in mapping_identity.items()
                if key != "mappingArtifactSha256"
            )
        ):
            raise FutureSongError("endpoint-review alignment identity is stale")
        analysis_audio = self.context.completed_analysis_audio(self.context.pipeline(), video_id)
        duration_ms = analysis_audio["durationMs"]
        if alignment_state.get("durationMs") != duration_ms:
            raise FutureSongError("endpoint-review audio duration is stale")
        occurrences = self.endpoint_review_occurrences(
            endpoint_evidence, mapping, duration_ms
        )
        editorial_sha256 = self.context.editorial_sha(job)
        sources_sha256 = sha256_file(self.context.job_dir(video_id) / "sources.json")
        workspace = AlignmentWorkspace(
            self.context.root, workspace_root=self.context.workspace_root
        )
        canonical_path = workspace.current_backup_path(video_id)
        canonical_plain_sha256 = sha256_file(canonical_path)
        if alignment_state.get("canonicalPlainSha256") != canonical_plain_sha256:
            raise FutureSongError("endpoint-review canonical lyrics are stale")
        identity = json_bytes(
            {
                "videoId": video_id,
                "alignmentAttemptId": blocker["alignmentAttemptId"],
                "alignmentRunSha256": blocker["bindings"]["alignmentRunSha256"],
                "alignmentStateSha256": blocker["bindings"]["alignmentStateSha256"],
                "mappingSha256": mapping_identity["mappingSha256"],
                "editorialSha256": editorial_sha256,
                "analysisAudioSha256": analysis_audio["sha256"],
            },
            pretty=False,
        )
        attempt_id = hashlib.sha256(b"stemwise-endpoint-review\0" + identity).hexdigest()[:32]
        declaration = {
            "declarationVersion": review_contract.ENDPOINT_REVIEW_VERSION,
            "policy": review_contract.ENDPOINT_REVIEW_POLICY,
            "videoId": video_id,
            "attemptId": attempt_id,
            "sourceAlignment": {
                "attemptId": blocker["alignmentAttemptId"],
                "runSha256": blocker["bindings"]["alignmentRunSha256"],
                "stateSha256": blocker["bindings"]["alignmentStateSha256"],
            },
            "editorialSha256": editorial_sha256,
            "sourcesSha256": sources_sha256,
            "canonicalPlainSha256": canonical_plain_sha256,
            "mapping": mapping_identity,
            "contentAdmission": diagnostics["contentAdmission"],
            "analysisAudio": {
                key: analysis_audio[key]
                for key in ("sha256", "byteCount", "durationMs")
            },
            "occurrences": occurrences,
        }
        attempt = self.context.endpoint_review_attempt_dir(video_id, attempt_id)
        root = attempt.parent
        root.mkdir(parents=True, exist_ok=True)
        owner = {
            "kind": "endpoint-review-attempt",
            "videoId": video_id,
            "attemptId": attempt_id,
        }
        if not attempt.exists():
            create_manifested_directory(
                root,
                attempt,
                owner=owner,
                workspace_root=self.context.workspace_root,
            )
        declaration_path = attempt / "declaration.json"
        if declaration_path.is_file():
            if read_bounded_json(
                declaration_path, max_bytes=MAX_JSON_BYTES, root=attempt
            ) != declaration:
                raise FutureSongError("endpoint-review declaration is stale or damaged")
        else:
            atomic_write_json(declaration_path, declaration)
        review_path = attempt / "review.json"
        if not review_path.exists():
            atomic_write_json(
                review_path,
                self.endpoint_review_template(video_id, attempt_id, occurrences),
            )
        if {path.name for path in attempt.iterdir()} != {
            GENERATED_MANIFEST_NAME,
            "declaration.json",
            "review.json",
        } or any(not path.is_file() or path.is_symlink() for path in attempt.iterdir()):
            raise FutureSongError("endpoint-review attempt contains an unexpected entry")
        pointer = {
            "attemptId": attempt_id,
            "declarationSha256": sha256_file(declaration_path),
            "sourceAlignmentRunId": blocker["alignmentAttemptId"],
            "sourceAlignmentRunSha256": blocker["bindings"]["alignmentRunSha256"],
            "sourceAlignmentStateSha256": blocker["bindings"]["alignmentStateSha256"],
        }
        job["endpointReview"] = pointer
        return pointer
