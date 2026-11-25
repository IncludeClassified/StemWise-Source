"""Artifact publication owns the final compare-and-replace boundary."""
from __future__ import annotations
from typing import Any
import hashlib
import json
from stemwise_engine.alignment_phases import AlignmentPublicationRequest, AlignmentPublicationResult
from stemwise_engine.lyric_content import require_content
from stemwise_engine.editorial_admission import complete_original_word_timing
from stemwise_engine.workflow_records import LyricsContentBlocker
import stemwise_engine.worker_protocol as worker_protocol
from stemwise_engine.lyrics_alignment import save_alignment_artifacts, commit_candidate


def qwen_mix_cache_name(language):
    return f'qwen-structure-{language}.private.json' if language and language != 'auto' else 'qwen-structure.private.json'


def _require_completed_structure_witness(alignment, witness, *, admission=None) -> None:
    """A required failed or uncertain witness never authorizes forced timing."""
    if witness is None:
        return
    if not isinstance(witness, dict):
        raise LyricsContentBlocker("content-witness-unavailable")
    if witness.get("status") == "failure":
        raise worker_protocol.WorkerProcessError("required content witness failed",
                                 witness.get("failureCode") or "structure-witness-failure")
    if (witness.get("status") == "success" and witness.get('cacheReused') is True
            and witness.get('route') == 'qwen3-asr-unprompted-mix' and witness.get('family') == 'qwen3-asr'
            and admission is not None and admission.adjudication is not None
            and admission.evidence_path == qwen_mix_cache_name(witness.get('languageHint'))
            and tuple(alignment.source_physical_indices) == admission.source_physical_indices
            and witness.get('contentAdmission') == admission.to_record()):
        return  # The current exact recording review resolves the retained model's differences.
    if (witness.get("status") != "success"
            or witness.get("selectedSequenceSupported") is not True
            or witness.get("selectedSequenceEveryLineDirect") is not True):
        raise LyricsContentBlocker("content-witness-uncertain")


class AlignmentPublisher:
    def publish(self, request: AlignmentPublicationRequest, state: dict[str, Any]) -> AlignmentPublicationResult:
        canonical_sha = hashlib.sha256(request.original_raw).hexdigest()
        try:
            originals = [track for track in json.loads(request.original_raw)["tracks"] if track.get("kind") == "original"]
        except (ValueError, KeyError, TypeError, AttributeError):
            raise LyricsContentBlocker("content-canonical-invalid") from None
        if len(originals) != 1 or state.get("canonicalPlainSha256") != canonical_sha:
            raise LyricsContentBlocker("content-canonical-differs")
        admission = require_content(state.get("contentAdmission"), originals[0],
            request.alignment.source_physical_indices, canonical_sha,
            state.get("contentBinding", {}).get("audioSha256"), request.workspace.job_dir(request.video_id))
        _require_completed_structure_witness(request.alignment, state.get("structureWitness"), admission=admission)
        endpoint = request.endpoint_selection
        endpoint_summary = endpoint.summary if endpoint is not None else None
        word_candidate_incomplete = (endpoint_summary is not None
            and endpoint_summary["resultClass"] == "committed-words"
            and (request.candidate is None or not complete_original_word_timing(request.candidate)))
        endpoint_reused = endpoint.reused if endpoint is not None else False
        selected_endpoint_attempt_index = endpoint.attempt_index if endpoint is not None else None
        mapping_artifact = save_alignment_artifacts(
            request.workspace,
            request.video_id,
            candidate=request.candidate,
            result=request.alignment,
            lexical_mapping=request.lexical_mapping,
            public_line_timings=(request.public_line_timings or None),
        )
        if mapping_artifact is not None:
            state.update(mapping_artifact)
        state.update(request.summary)
        if endpoint_summary is not None:
            state.update(
                endpointEvidence=endpoint_summary,
                endpointEvidenceReused=endpoint_reused,
                selectedEndpointAttemptIndex=selected_endpoint_attempt_index,
            )
            if endpoint_summary["resultClass"] != "committed-words":
                state["reasons"] = sorted(set(endpoint_summary.get("reasons", ()))
                    | set(endpoint_summary.get("lineOnlyReasons", ())))
            if word_candidate_incomplete:
                state["reasons"] = ["incomplete-display-word-map"]
        if not request.alignment.accepted or (
            endpoint_summary is not None
            and (endpoint_summary["resultClass"] != "committed-words" or word_candidate_incomplete)
        ):
            state["status"] = "line-unresolved"
            counter = "reviewRequired"
        elif request.no_commit:
            state["status"] = "candidate"
            counter = "candidate"
        else:
            if request.candidate is None or endpoint_summary is None:
                raise RuntimeError("accepted endpoint result has no publishable candidate")
            _, backup_sha256 = commit_candidate(
                request.workspace,
                request.video_id,
                request.sidecar_path,
                request.original_raw,
                request.candidate,
                expected_current_raw=request.expected_current_raw,
            )
            state.update(
                status=endpoint_summary["resultClass"],
                canonicalPlainSha256=backup_sha256,
            )
            counter = "committed"
        request.workspace.write_state(request.video_id, state)
        return AlignmentPublicationResult(state["status"], counter)
