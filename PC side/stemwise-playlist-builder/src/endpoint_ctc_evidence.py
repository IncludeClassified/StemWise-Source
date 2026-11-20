"""Transcript-conditioned CTC boundary evidence, private and candidate-only.

Forward/backward transition mass sums all compatible CTC paths. It does not
equate a CTC blank with a physical singing offset or publish a new timestamp.
"""
from __future__ import annotations

import hashlib
import json
import re
import os
import gc
import signal
from typing import Any, Callable
from pathlib import Path
import sys
import unicodedata

import numpy as np
from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success, run_worker_main

from stemwise_engine.common import (atomic_write_bytes, json_bytes, load_config, read_bounded_json,
                    read_bounded_regular_bytes, run_process_tree, sha256_file,
                    validate_generated_path, remove_disposable_path)
from stemwise_engine.endpoint_vocal_evidence import ROUTES, _vocal_samples

METHOD = "local-ctc-boundary-v1"
SCORER_VERSION = 10


def _bounded_emissions(waveform: Any, feature_extractor: Any, model: Any,
                       device: Any, dtype: Any, stride: int) -> Any:
    """Bound model memory while retaining the native, unwarped frame grid."""
    import torch
    CURRENT_BUDGET.require(frames=(int(waveform.shape[1])+stride-1)//stride,
        states=int(model.config.vocab_size), arrays=2)
    from stemwise_engine.wav2vec2_forced_alignment import _overlapped_emission_chunks
    emissions, chunks, _ = _overlapped_emission_chunks(
        waveform, int(waveform.shape[1]), feature_extractor, model, device, dtype, stride,
    )
    # Candidate adjudication uses physical convolution-frame edges, rather than
    # stretching a final partial chunk to fill its nominal PCM duration.
    if any(c["targetStartFrame"] != c["emissionStartFrame"] * stride for c in chunks):
        raise RuntimeError("bounded CTC emissions have a gap or shifted frame grid")
    return torch.cat(emissions, dim=0)


def _validated_ctc_emissions(logp: np.ndarray, blank: int) -> np.ndarray:
    if getattr(logp, 'ndim', None) != 2:
        raise ValueError('invalid CTC boundary inputs')
    CURRENT_BUDGET.require(frames=int(logp.shape[0]), states=int(logp.shape[1]))
    p = np.asarray(logp, dtype=np.float64)
    if (p.ndim != 2 or not len(p) or not np.isfinite(p).all()
            or type(blank) is not int or not 0 <= blank < p.shape[1]):
        raise ValueError("invalid CTC boundary inputs")
    if not np.allclose(np.logaddexp.reduce(p, axis=1), 0, atol=1e-4):
        raise ValueError("CTC emissions are not normalized log probabilities")
    return p


def _validate_ctc_target(p: np.ndarray, targets: list[int], blank: int) -> None:
    if (not targets or any(type(v) is not int or not 0 <= v < p.shape[1]
                           or v == blank for v in targets)):
        raise ValueError("invalid CTC boundary inputs")
    if len(targets) + sum(a == b for a, b in zip(targets, targets[1:])) > len(p):
        raise ValueError("audio cannot align the complete target sequence")


def _ctc_trellis_validated(p: np.ndarray, targets: list[int], blank: int, *,
                           allow_edge_audio: bool, edge_scores: dict[int, np.ndarray]) -> tuple:
    CURRENT_BUDGET.require(frames=int(p.shape[0]), states=2*len(targets)+1,
                           arrays=3, retained_bytes=p.nbytes)
    labels = np.full(2*len(targets)+1, blank, dtype=int)
    labels[1::2] = targets
    emissions = p[:, labels]
    if allow_edge_audio:
        # Competing acoustic scores, never a free wildcard that eats sustained
        # edge tokens. Only the context margins may contain untranscribed audio.
        for state, token in ((0, targets[0]), (-1, targets[-1])):
            if token not in edge_scores:
                edge_scores[token] = np.max(p[:, np.arange(p.shape[1]) != token], axis=1)
            emissions[:, state] = edge_scores[token]
    skip = (labels[2:] != blank) & (labels[2:] != labels[:-2])
    return labels, emissions, skip


def _ctc_forward_validated(p: np.ndarray, targets: list[int], blank: int, *,
                           allow_edge_audio: bool, edge_scores: dict[int, np.ndarray]) -> tuple:
    labels, emissions, skip = _ctc_trellis_validated(p, targets, blank,
        allow_edge_audio=allow_edge_audio, edge_scores=edge_scores)
    from stemwise_engine.ctc_numerics import forward_path
    alpha, partition = forward_path(emissions, skip)
    if not np.isfinite(partition):
        raise ValueError("audio cannot align the complete target sequence")
    return labels, emissions, skip, alpha, float(partition)


def _ctc_trellis(logp: np.ndarray, targets: list[int], blank: int, *,
                 allow_edge_audio: bool = False) -> tuple:
    p = _validated_ctc_emissions(logp, blank)
    _validate_ctc_target(p, targets, blank)
    return _ctc_trellis_validated(p, targets, blank,
                                 allow_edge_audio=allow_edge_audio, edge_scores={})


def _ctc_forward(logp: np.ndarray, targets: list[int], blank: int, *,
                 allow_edge_audio: bool = False) -> tuple:
    """Shared full-target path score for representation and boundary scoring."""
    p = _validated_ctc_emissions(logp, blank)
    _validate_ctc_target(p, targets, blank)
    return _ctc_forward_validated(p, targets, blank,
                                 allow_edge_audio=allow_edge_audio, edge_scores={})


def _ctc_sequence_scores(logp: np.ndarray, targets: list[list[int]], blank: int, *,
                         allow_edge_audio: bool = False) -> list[float]:
    """Validate one probability table once; preserve every full-target score."""
    p = _validated_ctc_emissions(logp, blank)
    scores, edge_scores = {}, {}
    result = []
    for target in targets:
        _validate_ctc_target(p, target, blank)
        key = tuple(target)
        if key not in scores:
            scores[key] = _ctc_forward_validated(p, target, blank,
                allow_edge_audio=allow_edge_audio, edge_scores=edge_scores)[-1]
        result.append(scores[key])
    return result


def boundary_distributions(logp: np.ndarray, targets: list[int], blank: int,
                           first_token: int, last_token: int, *,
                           allow_edge_audio: bool = False, summary_sink=None, word_ranges=None,
                           origin_ms=0., step_ms=20.) -> tuple[np.ndarray, np.ndarray]:
    """First-token entry / last-token exit mass on edges 0 through T.

    Optional acoustic background occupies only the two outer states; every
    target and all interior blank/repeated-label constraints remain required.
    """
    if not 0 <= first_token <= last_token < len(targets):
        raise ValueError("invalid CTC boundary inputs")
    labels, emissions, skip, alpha, partition = _ctc_forward(
        logp, targets, blank, allow_edge_audio=allow_edge_audio)
    count, states = emissions.shape
    beta = np.full_like(alpha, -np.inf)
    beta[-1, -2:] = 0
    for t in range(count-2, -1, -1):
        following = emissions[t+1] + beta[t+1]
        row = following.copy()
        row[:-1] = np.logaddexp(row[:-1], following[1:])
        row[:-2] = np.logaddexp(row[:-2], np.where(skip, following[2:], -np.inf))
        beta[t] = row
    def mass_for(first_token, last_token):
        onset, offset = np.full(count+1, -np.inf), np.full(count+1, -np.inf)
        first, last = 2*first_token+1, 2*last_token+1
        if first == 1:
            onset[0] = emissions[0, first] + beta[0, first]
        for t in range(1, count):
            previous = alpha[t-1, first-1]
            if first >= 2 and labels[first] != labels[first-2]:
                previous = np.logaddexp(previous, alpha[t-1, first-2])
            onset[t] = previous + emissions[t, first] + beta[t, first]
            following = emissions[t, last+1] + beta[t, last+1]
            if last+2 < states and labels[last] != labels[last+2]:
                following = np.logaddexp(following, emissions[t, last+2] + beta[t, last+2])
            offset[t] = alpha[t-1, last] + following
        if last == states-2:
            offset[count] = alpha[-1, last]
        return np.exp(onset-partition), np.exp(offset-partition)
    result = mass_for(first_token, last_token)
    if summary_sink is not None:
        from stemwise_engine.endpoint_candidate_prediction import summarize_boundary_curves
        from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
        edges = origin_ms+np.arange(count+1)*step_ms
        try:
            CURRENT_BUDGET.require(frames=count, states=states, arrays=3,
                retained_bytes=logp.nbytes+8*(count+1)*6+len(word_ranges or ())*2048)
        except AcousticBudgetExceeded:
            return result
        for identity, first, last in word_ranges or ():
            token_ids = targets[first:last+1]
            if (not np.isin(np.argmax(logp, axis=1), token_ids).any()
                    or np.allclose(logp[:,token_ids],logp[0,token_ids],rtol=0,atol=1e-12)):
                summary_sink.clear()
                break
            try:
                summary_sink.append(summarize_boundary_curves(identity, *mass_for(first, last), edges,
                    minimum_frames=len(token_ids)+sum(a == b for a,b in zip(token_ids,token_ids[1:])),
                    preceding_gap_frames=int(first > 0 and targets[first-1] == targets[first])))
            except AcousticBudgetExceeded:
                summary_sink.clear()
                break
    return result


def _select_acoustic_representation(samples: np.ndarray, targets: list[int], blank: int,
                                    infer: Callable[[np.ndarray], np.ndarray]
                                    ) -> tuple[str, np.ndarray, np.ndarray, dict[str, float]]:
    """Rank equal-length audio views before inspecting any timestamp candidates."""
    if (samples.ndim != 2 or samples.shape[1] not in (1, 2)
            or len(samples) <= 320 or not np.isfinite(samples).all()):
        raise ValueError("invalid acoustic representation input")
    views = {"mono": samples[:, 0]} if samples.shape[1] == 1 else {
        "left": samples[:, 0], "right": samples[:, 1],
        "mid": (samples[:, 0] + samples[:, 1]) / 2,
        "side": (samples[:, 0] - samples[:, 1]) / 2,
    }
    scores, best = {}, None
    for name, signal in views.items():
        logp = infer(signal[160:-160])
        score = _ctc_forward(logp, targets, blank, allow_edge_audio=True)[-1]
        scores[name] = score
        if best is None or score > scores[best[0]]:
            best = name, signal, logp
    return *best, scores


def select_candidate(mass: np.ndarray, times: list[int], *, origin_ms: float, step_ms: float) -> dict:
    """Never interpolate posterior mass into fabricated sub-frame precision."""
    if (not times or any(type(t) is not int or t < 0 for t in times) or step_ms <= 0
            or not np.isfinite(origin_ms) or not np.isfinite(step_ms)
            or mass.ndim != 1 or not np.isfinite(mass).all() or (mass < 0).any()
            or not np.isclose(mass.sum(), 1, atol=1e-4)):
        raise ValueError("invalid CTC candidate distribution")
    unique = sorted(set(times))
    bins = [int(np.floor((t-origin_ms)/step_ms + .5)) for t in unique]
    if any(b < 0 or b >= len(mass) for b in bins):
        # An incomplete candidate comparison cannot authorize a peak-based fallback.
        return {"result": "abstain", "candidateMs": None, "candidateScores": [],
                "frameStepMs": step_ms, "reason": "candidate-outside-acoustic-context",
                "candidatesAgree": len(unique) == 1,
                "compatibleCandidatesMs": [], "peakMs": None, "peakMass": None}
    records = [{"candidateMs": t, "frameEdge": b, "mass": round(float(mass[b]), 8)} for t, b in zip(unique, bins)]
    result = {"result": "abstain", "candidateMs": None, "candidateScores": records,
              "frameStepMs": step_ms, "reason": "insufficient-boundary-mass",
              "candidatesAgree": len(unique) == 1,
              "compatibleCandidatesMs": [], "peakMs": round(origin_ms + int(np.argmax(mass))*step_ms, 3),
              "peakMass": round(float(np.max(mass)), 8)}
    distinct_bins = sorted(set(bins), key=lambda b: float(mass[b]), reverse=True)
    winner = distinct_bins[0]
    best = float(mass[winner])
    other = float(mass[distinct_bins[1]]) if len(distinct_bins) > 1 else 0.
    # Rejection gates, not calibrated accuracy. Demand mass on the candidate,
    # not merely the best of several candidates far from the actual peak.
    if best < .5 or best < 3*other or winner != int(np.argmax(mass)):
        return result
    compatible = [t for t, b in zip(unique, bins) if b == winner]
    result["compatibleCandidatesMs"] = compatible
    if len(compatible) > 1:
        return {**result, "reason": "candidates-share-acoustic-frame"}
    return {**result, "result": "supported", "candidateMs": compatible[0], "reason": "ctc-boundary-support-review-required"}


def _clean_scratch(directory: Path, workspace_root: Path) -> None:
    # Completed numeric results are sufficient; audio and posterior dumps are scratch.
    directory = validate_generated_path(workspace_root, directory)
    if directory.is_dir():
        for scratch in directory.iterdir():
            if re.fullmatch(r"context-[0-9]+", scratch.name):
                remove_disposable_path(directory, scratch, workspace_root=workspace_root)


def _directory(attempt: Path) -> Path:
    return validate_generated_path(attempt.parent.parent, attempt.parent.parent / "endpoint-ctc-evidence" / attempt.name)


def _arabic_word_context(lines: list[dict]) -> bool:
    words = [word["acousticText"] for line in lines for word in line["words"]]
    return bool(words) and all(
        any(unicodedata.category(c).startswith("L") for c in word)
        and all(unicodedata.name(c, "").startswith("ARABIC ")
                for c in word if unicodedata.category(c).startswith("L"))
        for word in words)


def require_endpoint_content(declaration: dict, mapping: dict, evidence: dict, lyrics: dict,
                             evidence_root: Path | None):
    from stemwise_engine.lyric_content import require_content, require_timing_only
    from stemwise_engine.lyrics_alignment import _plain_original
    from stemwise_engine.workflow_records import LyricsContentBlocker
    if evidence_root is None:
        raise LyricsContentBlocker('content-evidence-unavailable')
    try:
        original = _plain_original(lyrics)
        indices = tuple(row['sourcePhysicalIndex'] for row in mapping['performedOccurrences'])
    except (KeyError, TypeError, ValueError):
        raise LyricsContentBlocker('content-occurrences-unavailable') from None
    admission = require_content(declaration.get('contentAdmission'), original, indices,
        declaration.get('canonicalPlainSha256'), declaration.get('analysisAudio', {}).get('sha256'),
        evidence_root)
    occurrences = evidence.get('occurrenceCandidates') if isinstance(evidence, dict) else None
    if not isinstance(occurrences, list) or len(occurrences) != len(indices):
        raise LyricsContentBlocker('content-occurrences-unavailable', binding=admission.binding)
    reasons = []
    for row in occurrences:
        decision = row.get('publishDecision') if isinstance(row, dict) else None
        if not isinstance(decision, dict) or decision.get('result') not in {'words', 'line-only', 'unresolved'}:
            raise LyricsContentBlocker('content-occurrences-unavailable', binding=admission.binding)
        if decision['result'] == 'unresolved':
            reasons.append(decision.get('code', 'content-evidence-unavailable'))
    require_timing_only(reasons, admission, occurrences=occurrences,
                        expected_occurrences=mapping.get('performedOccurrences'))
    return admission


def needs_timing_recovery(packet: dict, admission) -> bool:
    from stemwise_engine.lyric_content import ContentAdmission
    from stemwise_engine.workflow_records import LyricsContentBlocker
    if not isinstance(admission, ContentAdmission):
        raise LyricsContentBlocker('content-evidence-unavailable')
    occurrences = packet.get('occurrences') if isinstance(packet, dict) else None
    if not isinstance(occurrences, list) or not occurrences:
        raise LyricsContentBlocker('endpoint-evidence-uncertain', binding=admission.binding)
    unresolved = False
    for occurrence in occurrences:
        assessment = occurrence.get('assessment') if isinstance(occurrence, dict) else None
        if not isinstance(assessment, dict):
            raise LyricsContentBlocker('endpoint-evidence-uncertain', binding=admission.binding)
        for boundary in ('startMs', 'endMs'):
            decision = assessment.get(boundary)
            if not isinstance(decision, dict):
                raise LyricsContentBlocker('endpoint-evidence-uncertain', binding=admission.binding)
            if decision.get('result') == 'supported':
                continue
            if (decision.get('result') != 'abstain'
                    or decision.get('reason') != 'ctc-views-or-perturbations-inconclusive'):
                raise LyricsContentBlocker('endpoint-content-or-capability-uncertain', binding=admission.binding)
            unresolved = True
    return unresolved


def _plan(declaration: dict, mapping: dict, evidence: dict, lyrics: dict, *,
          evidence_root: Path | None = None, context_version: int = SCORER_VERSION) -> list[dict]:
    admission = require_endpoint_content(declaration, mapping, evidence, lyrics, evidence_root)
    from stemwise_engine.lyrics_alignment import _physical_lines, _plain_original
    from stemwise_engine.lyrics_tokenization import display_words
    original = _plain_original(lyrics)
    from stemwise_engine.spoken_targets import load as load_spoken_targets
    spoken = (load_spoken_targets(Path(evidence_root).parents[2]/'jobs'/declaration['videoId'],
        declaration['videoId'], original, admission.source_physical_indices, admission.audio_sha256)
        if admission.spoken_targets is not None else None)
    physical = _physical_lines(original["text"])
    mapped, numeric = mapping["performedOccurrences"], evidence["occurrenceCandidates"]
    if len(mapped) != len(numeric):
        raise ValueError("CTC occurrence sequence is inconsistent")
    if type(context_version) is not int or context_version != SCORER_VERSION:
        raise ValueError("unsupported CTC context version")
    plans = []
    for occurrence in declaration["occurrences"]:
        index = occurrence["performedIndex"]
        if mapped[index]["occurrenceId"] != occurrence["occurrenceId"]:
            raise ValueError("CTC target occurrence is stale")
        indices = list(range(max(0, index-1), min(len(mapped), index+2)))
        families = [c for i in indices for c in numeric[i]["familyCandidates"].values()]
        start = max(0, min(c["startMs"] for c in families)-1500)
        end = min(declaration["analysisAudio"]["durationMs"], max(c["endMs"] for c in families)+1500)
        # Disputed timestamps can make a three-line crop span much more singing.
        # Include other complete lines placed here by two families, in performed
        # order. Keep the crop and every declared candidate unchanged.
        covered = [i for i, item in enumerate(numeric) if sum(
            start <= c["startMs"] < c["endMs"] <= end
            for c in item["familyCandidates"].values()) >= 2]
        indices = list(range(min([*indices, *covered]), max([*indices, *covered])+1))
        lines = []
        for i in indices:
            m = mapped[i]
            words = display_words(physical[m["sourcePhysicalIndex"]])
            if len(words) != len(m["words"]):
                raise ValueError("CTC words differ from the bound occurrence mapping")
            source = m['sourcePhysicalIndex']
            forms = spoken.words[source] if spoken is not None else [(text, None) for _, _, text in words]
            lines.append({"lineId": m["occurrenceId"], "sourcePhysicalIndex": source,
                          "words": [{"wordId": w["occurrenceWordId"], "acousticText": text,
                              **({'acousticLanguage': language} if language else {})}
                                    for w, (text, language) in zip(m["words"], forms)]})
        plan = {"occurrenceId": occurrence["occurrenceId"], "startMs": start, "endMs": end,
                      "language": original["language"], "targetLine": indices.index(index),
                      "request": {"lines": lines}, "candidateFamilies": occurrence["candidateFamilies"]}
        if original["language"].split('-', 1)[0].lower() == "mul":
            if spoken is not None:
                languages = {word['acousticLanguage'] for line in lines for word in line['words']}
                if len(languages) == 1:
                    plan['language'] = next(iter(languages))
                # Per-word ownership is explicit even when this particular model
                # cannot score the complete mixed context.
                plans.append(plan)
                continue
            components = {v.lower().split("-", 1)[0] for v in original.get("componentLanguages", [])}
            # A shared script cannot establish which declared language owns a word.
            from stemwise_engine.model_registry import declared_script_owners
            owners = declared_script_owners(components)
            if owners is not None and owners.get('Arab') == {'ar'} and _arabic_word_context(lines):
                plan["language"] = "ar"  # Local context only; original metadata stays multilingual.
                for line in lines:
                    for word in line["words"]:
                        word["acousticLanguage"] = "ar"
            else:
                plan["unsupported"] = "mixed-language-context-needs-supported-word-bindings"
        plans.append(plan)
    return plans


def _write_context_request(scratch: Path, plan: dict) -> None:
    path = scratch / "request.private.json"
    if path.exists():
        previous = read_bounded_json(path, max_bytes=1024*1024, root=scratch)
        if previous != plan:
            try:
                old, new = previous["request"]["lines"], plan["request"]["lines"]
                positions = [new.index(line) for line in old]
                valid = (
                    {k: v for k, v in previous.items() if k not in ("request", "targetLine")}
                    == {k: v for k, v in plan.items() if k not in ("request", "targetLine")}
                    and set(previous["request"]) == set(plan["request"]) == {"lines"}
                    and bool(old) and positions == sorted(set(positions))
                    and type(previous["targetLine"]) is int and 0 <= previous["targetLine"] < len(old)
                    and type(plan["targetLine"]) is int and 0 <= plan["targetLine"] < len(new)
                    and old[previous["targetLine"]] == new[plan["targetLine"]]
                )
            except (KeyError, IndexError, TypeError, ValueError):
                valid = False
            if not valid:
                raise ValueError("CTC context request changed beyond a bound transcript expansion")
    atomic_write_bytes(path, json_bytes(plan), root=scratch)


def _invoke(project: Path, mode: str, scratch: Path | list[Path], timeout: int, *, alignment: bool) -> None:
    env = {**os.environ, "TORCH_HOME": str(project.parent / "model-cache/torch"),
           "HF_HOME": str(project.parent / "model-cache/huggingface"), "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1"}
    from stemwise_engine.runtime_environment import environment_python, environment_lease
    python = environment_python('lyrics' if alignment else 'main', project)
    paths = scratch if isinstance(scratch, list) else [scratch]
    if mode == 'prepare-infer-many':
        from stemwise_engine.alignment_models import roformer_offline_env
        env = roformer_offline_env(project.parent)
    batched = mode in ('infer-many', 'prepare-many', 'prepare-infer-many')
    with environment_lease('lyrics' if alignment else 'main', builder=project):
        result = run_process_tree([str(python), '-m', 'stemwise_engine.endpoint_ctc_evidence', mode, str(project),
                                  str(timeout) if batched else str(paths[0])],
                                 input=json.dumps(list(map(str, paths))) if batched else None,
                                 capture_output=True, text=True, check=False,
                                 timeout=timeout * len(paths) * (2 if mode == 'prepare-infer-many' else 1)
                                 if batched else timeout, env=env)
    require_worker_success(result, code='ctc-evidence-worker-failed',
        diagnostic_path=paths[0] / 'worker-error.private.txt')


def _prepare(project: Path, scratch: Path) -> None:
    from stemwise_engine.lyrics_align_pc import _wav2vec2_model_assets
    from stemwise_engine.endpoint_vocal_evidence import _separate
    config = load_config(project / "config.json")
    plan = read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch)
    if plan["language"] == "mul":
        raise ValueError("mixed-language phoneme context needs explicit per-word language bindings")
    assets = _wav2vec2_model_assets(config, plan["language"])
    if assets is None:
        raise ValueError("no supported acoustic model for the bound language")
    outputs = _separate(config, scratch / "source.wav", scratch)
    views = {r: {"path": str(p.relative_to(scratch)), "provenance": v} for r, (p, v) in outputs.items()}
    atomic_write_bytes(scratch / "prepared.json", json_bytes({"modelPath": str(assets[0]), "model": assets[1],
        "views": views}), root=scratch)


def _prepare_many(project: Path, scratches: list[Path], *, local: bool = False) -> None:
    from stemwise_engine.lyrics_align_pc import _wav2vec2_model_assets
    from stemwise_engine.endpoint_vocal_evidence import _separate_many, _separate_many_local
    if not scratches or len({p.resolve() for p in scratches}) != len(scratches):
        raise ValueError('Vocal preparation requires distinct contexts')
    config = load_config(project / 'config.json')
    contexts = []
    for scratch in scratches:
        plan = read_bounded_json(scratch / 'request.private.json', max_bytes=1024*1024, root=scratch)
        assets = _wav2vec2_model_assets(config, plan['language']) if plan['language'] != 'mul' else None
        if assets is None:
            raise ValueError('no supported acoustic model for the bound language')
        contexts.append((scratch, assets))
    pairs = [(scratch / 'source.wav', scratch) for scratch, _ in contexts]
    outputs = (_separate_many_local(config, pairs, int(config['analysisTimeoutMinutes']) * 60)
               if local else _separate_many(config, pairs))
    if len(outputs) != len(contexts) or any(set(o) != set(ROUTES) for o in outputs):
        raise ValueError('separation did not return every context and vocal view')
    for (scratch, assets), output in zip(contexts, outputs):
        views = {r: {'path': str(p.relative_to(scratch)), 'provenance': v}
                 for r, (p, v) in output.items()}
        atomic_write_bytes(scratch / 'prepared.json', json_bytes({'modelPath': str(assets[0]),
                           'model': assets[1], 'views': views}), root=scratch)


def _prepare_infer_many(project: Path, scratches: list[Path], timeout_seconds: int) -> None:
    if (type(timeout_seconds) is not int or timeout_seconds <= 0 or not scratches
            or len({p.resolve() for p in scratches}) != len(scratches)):
        raise ValueError('CTC batch requires distinct contexts and a positive deadline')
    import torch
    settings = (torch.backends.cudnn.benchmark, torch.backends.cudnn.deterministic,
                torch.are_deterministic_algorithms_enabled(),
                torch.is_deterministic_algorithms_warn_only_enabled())
    try:
        _prepare_many(project, scratches, local=True)
    finally:
        # RoFormer changes global Torch flags; CTC retains its fresh-worker settings.
        torch.backends.cudnn.benchmark, torch.backends.cudnn.deterministic = settings[:2]
        torch.use_deterministic_algorithms(settings[2], warn_only=settings[3])
    _infer_many(project, scratches, timeout_seconds)


def _infer(project: Path, scratch: Path, *, model_cache: dict | None = None) -> None:
    import importlib.metadata
    from stemwise_engine.endpoint_context_cache import context_binding, read_context_result, write_context_result
    from stemwise_engine.provenance import endpoint_producer_identity
    from stemwise_engine.wav2vec2_forced_alignment import TargetVocabularyUnsupported
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    prepared = read_bounded_json(scratch / 'prepared.json', max_bytes=1024*1024, root=scratch)
    identity = {'method': METHOD, 'scorerVersion': SCORER_VERSION, 'model': prepared['model'],
        'producer': endpoint_producer_identity('ctc'),
        'runtime': {**torch_runtime_identity(), 'transformers': importlib.metadata.version('transformers')}}
    binding = context_binding(scratch, identity)
    retained = read_context_result(scratch, identity, binding=binding)
    if retained is not None:
        atomic_write_bytes(scratch / 'inference.json', json_bytes(retained), root=scratch)
        return
    try:
        result = infer_records(scratch, model_cache=model_cache)
        if result.get('runtime') != identity['runtime']:
            raise ValueError('CTC inference used a different numerical producer')
    except TargetVocabularyUnsupported as error:
        result = {"formatVersion": 1, "engine": "wav2vec2-target-capability",
                  "requestSha256": error.request_sha256, "supported": False}
    write_context_result(scratch, identity, result, binding=binding)
    atomic_write_bytes(scratch / "inference.json", json_bytes(result), root=scratch)


def _infer_many(project: Path, scratches: list[Path], timeout_seconds: int = 10800) -> None:
    from stemwise_engine.lyrics_alignment import canonical_language
    if (type(timeout_seconds) is not int or timeout_seconds <= 0 or not scratches
            or len({path.resolve() for path in scratches}) != len(scratches)):
        raise ValueError('CTC batch requires distinct contexts and a positive deadline')
    groups = {}
    for scratch in scratches:
        prepared = read_bounded_json(scratch / 'prepared.json', max_bytes=1024*1024, root=scratch)
        plan = read_bounded_json(scratch / 'request.private.json', max_bytes=1024*1024, root=scratch)
        key = (str(Path(prepared['modelPath']).resolve()), json_bytes(prepared['model']),
               prepared['model'].get('phonemizerLanguage') or canonical_language(plan['language']) or plan['language'])
        groups.setdefault(key, []).append(scratch)
    cache = {}
    def expired(*_):
        raise TimeoutError('CTC context exceeded its deadline')
    previous_handler = signal.signal(signal.SIGALRM, expired)
    try:
        for group in groups.values():
            for scratch in group:
                signal.setitimer(signal.ITIMER_REAL, timeout_seconds)
                _infer(project, scratch, model_cache=cache)
                signal.setitimer(signal.ITIMER_REAL, 0)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        cache.clear()


def infer_records(scratch: Path, model_assets: tuple[Path, dict] | None = None, *, model_cache: dict | None = None) -> dict:
    from stemwise_engine.lyrics_alignment import canonical_language
    import torch
    import transformers
    from transformers import AutoModelForCTC, Wav2Vec2FeatureExtractor, Wav2Vec2CTCTokenizer, Wav2Vec2PhonemeCTCTokenizer
    from stemwise_engine.wav2vec2_forced_alignment import _target_plan, _resolved_tokenizer_class
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    runtime = {**torch_runtime_identity(), 'transformers': transformers.__version__}
    plan = read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch)
    prepared = read_bounded_json(scratch / "prepared.json", max_bytes=1024*1024, root=scratch)
    model_path, model_identity = model_assets or (Path(prepared["modelPath"]), prepared["model"])
    language = model_identity.get("phonemizerLanguage") or canonical_language(plan["language"]) or plan["language"]
    native_english = language == "en" and model_identity.get("targetKind") == "grapheme"
    phoneme = not native_english and _resolved_tokenizer_class(model_path) == "Wav2Vec2PhonemeCTCTokenizer"
    cls = Wav2Vec2PhonemeCTCTokenizer if phoneme else Wav2Vec2CTCTokenizer
    tokenizer = cls.from_pretrained(str(model_path), local_files_only=True, **({"phonemizer_lang": language} if phoneme else {}))
    if native_english:
        # The native English vocabulary is uppercase; target normalization is lowercase.
        # Keep token IDs unchanged, and reject an ambiguous case-folded vocabulary.
        encoder = {token.lower(): value for token, value in tokenizer.encoder.items()}
        if len(encoder) != len(tokenizer.encoder):
            raise ValueError("English CTC vocabulary has conflicting case variants")
        tokenizer.encoder = encoder
    targets, lines, _ = _target_plan(plan["request"], tokenizer, language, phoneme=phoneme)
    target = lines[plan["targetLine"]]["words"]
    first, last = target[0]["targetStart"], target[-1]["targetEnd"]-1
    cache_key = (str(model_path.resolve()), json_bytes(model_identity), language, json_bytes(runtime))
    cached = model_cache.get(cache_key) if model_cache is not None else None
    if cached is None:
        if model_cache:
            model_cache.clear()
            gc.collect()
            torch.cuda.empty_cache()
        extractor = Wav2Vec2FeatureExtractor.from_pretrained(str(model_path), local_files_only=True)
        model = AutoModelForCTC.from_pretrained(str(model_path), local_files_only=True, torch_dtype=torch.float32).to("cuda").eval()
        if model_cache is not None:
            model_cache[cache_key] = extractor, model
    else:
        extractor, model = cached
    blank = model.config.pad_token_id
    if blank != tokenizer.pad_token_id or extractor.sampling_rate != 16000:
        raise ValueError("acoustic tokenizer or sample rate differs from model")
    stride, receptive = 1, 1
    for kernel, step in zip(model.config.conv_kernel, model.config.conv_stride):
        receptive += (kernel-1)*stride
        stride *= step
    frame_ms = stride/16
    frame_origin = ((receptive-1)/2-stride/2)/16
    def infer(signal: np.ndarray) -> np.ndarray:
        waveform = torch.from_numpy(signal.astype(np.float32))[None]
        with torch.inference_mode():
            return _bounded_emissions(
                waveform, extractor, model, torch.device("cuda"), torch.float32, stride).numpy()

    records, representations = [], {}
    for route, view in prepared["views"].items():
        path = validate_generated_path(scratch, scratch / view["path"])
        samples = _vocal_samples(path, view["provenance"], plan["endMs"]-plan["startMs"])
        representation, signal, nominal_logp, scores = _select_acoustic_representation(
            samples, targets, blank, infer)
        representations[route] = {"selected": representation, "scores": scores}
        for shift, gain in ((0, 1.), (-5, .9), (5, 1.1)):
            begin = 160 + shift*16
            logp = nominal_logp if shift == 0 else infer(signal[begin:-160]*gain)
            origin = plan["startMs"] + begin/16 + frame_origin
            summaries = [] if shift == 0 else None
            onset, offset = boundary_distributions(
                logp, targets, blank, first, last, allow_edge_audio=True,
                **({'summary_sink': summaries, 'word_ranges': [(word['wordId'],word['targetStart'],word['targetEnd']-1)
                    for word in target], 'origin_ms': origin, 'step_ms': frame_ms} if summaries is not None else {}))
            decisions = {b: select_candidate(m, [v[b] for v in plan["candidateFamilies"].values()], origin_ms=origin, step_ms=frame_ms)
                         for b, m in (("startMs", onset), ("endMs", offset))}
            records.append({"route": route, "shiftMs": shift, "gain": gain, "representation": representation,
                            "originMs": origin, "stepMs": frame_ms, "decisions": decisions,
                            **({'boundarySummaries': summaries} if summaries else {})})
    output = {"records": records, "model": model_identity, "representations": representations,
        "runtime": runtime,
        "targetSha256": hashlib.sha256(json_bytes(targets)).hexdigest(), "targetCount": len(targets)}
    if len(json_bytes(output)) > 1024*1024:
        for record in records:
            record.pop('boundarySummaries', None)
    return output


def _assess(records: list[dict], families: dict) -> dict:
    from stemwise_engine.alignment_endpoints import _endpoint_boundary_supported
    complete = len(records) == 9 and {(r["route"], r["shiftMs"]) for r in records} == {(r, s) for r in ROUTES for s in (-5, 0, 5)}
    result = {}
    for boundary in ("startMs", "endMs"):
        decisions = [r["decisions"][boundary] for r in records]
        choices = [set(d.get("compatibleCandidatesMs", [d["candidateMs"]] if d["result"] == "supported" else []))
                   for d in decisions]
        times = set.intersection(*choices) if choices else set()
        # A quantized tie is compatible with either timestamp, not a contrary vote.
        eligible = {family: candidate for family, candidate in families.items()
                    if _endpoint_boundary_supported(candidate, family, boundary)}
        supported = complete and len(times) == 1 and any(
            candidate[boundary] in times for candidate in eligible.values())
        time = next(iter(times)) if supported else None
        result[boundary] = {"result": "supported" if supported else "abstain", "candidateMs": time,
                            "candidatesAgree": len({v[boundary] for v in families.values()}) == 1,
                            "supportedFamilies": sorted(k for k, v in eligible.items() if time is not None and v[boundary] == time),
                            "reason": "stable-ctc-support-review-required" if supported else "ctc-views-or-perturbations-inconclusive"}
    return result


def _summary(directory: Path, manifest: dict) -> dict:
    return {"method": METHOD, "manifestSha256": sha256_file(directory / "manifest.json"),
            "file": str(directory / "manifest.json"), "reviewRequired": True,
            "occurrences": [{"occurrenceId": e["occurrenceId"], "assessment": e["assessment"],
                             "records": e.get("inference", {}).get("records", [])} for e in manifest["occurrences"]]}


def write_packet(attempt: Path, declaration: dict, declaration_sha: str, audio: Path, config: dict,
                 mapping: dict, evidence: dict, canonical: Path) -> dict:
    import soundfile as sf
    raw = read_bounded_regular_bytes(canonical, max_bytes=16*1024*1024, root=canonical.parent)
    if hashlib.sha256(raw).hexdigest() != declaration["canonicalPlainSha256"]:
        raise ValueError("CTC authority text is stale")
    plans = _plan(declaration, mapping, evidence, parse_worker_json(raw),
        evidence_root=canonical.parent.parent / "jobs" / declaration["videoId"])
    if any('unsupported' in plan for plan in plans):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        admission = require_endpoint_content(declaration, mapping, evidence, parse_worker_json(raw),
            canonical.parent.parent / "jobs" / declaration["videoId"])
        raise LyricsContentBlocker('pronunciation-capability-unavailable', binding=admission.binding)
    directory = _directory(attempt)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "manifest.json"
    previous = read_bounded_json(path, max_bytes=16*1024*1024, root=directory) if path.exists() else None
    from stemwise_engine.worker_runtime_probe import selected_torch_runtime
    runtime = selected_torch_runtime(Path(config['projectDir']), int(config['analysisTimeoutMinutes']) * 60)
    if (previous and previous.get("scorerVersion") == SCORER_VERSION
            and previous.get('producerRuntime') == runtime):
        binding = {"method": METHOD, "manifestSha256": sha256_file(path)}
        manifest = verify_packet(attempt, declaration, declaration_sha, audio, binding)
        admission = require_endpoint_content(declaration, mapping, evidence, parse_worker_json(raw),
            canonical.parent.parent / "jobs" / declaration["videoId"])
        needs_timing_recovery(_summary(directory, manifest), admission)
        if all(a["result"] == "supported" for e in manifest["occurrences"] for a in e["assessment"].values()):
            _clean_scratch(directory, Path(config["projectDir"]).parent.parent)
        return _summary(directory, manifest)
    if previous:
        verify_packet(attempt, declaration, declaration_sha, audio,
                      {"method": METHOD, "manifestSha256": sha256_file(path)})
    entries, prepared_contexts = [], []
    for plan in plans:
        # Durable, private work preserves expensive separation after interruption.
        scratch = validate_generated_path(directory, directory / f"context-{len(entries):03d}")
        scratch.mkdir(exist_ok=True)
        _write_context_request(scratch, plan)
        clip = scratch / "source.wav"
        with sf.SoundFile(audio) as source:
            source.seek(plan["startMs"]*48)
            samples = source.read((plan["endMs"]-plan["startMs"])*48, dtype="float32", always_2d=True)
        from stemwise_engine.source_crop import ensure_source_crop
        ensure_source_crop(clip, samples, 48000, soundfile=sf, numpy=np)
        retained, rate = sf.read(clip, dtype="float32", always_2d=True)
        if rate != 48000 or not np.array_equal(retained, samples):
            raise ValueError("CTC retained source crop is stale")
        prepared_contexts.append((plan, scratch, len(entries)))
        entries.append(None)
    if prepared_contexts:
        for _, scratch, _ in prepared_contexts:
            validate_generated_path(scratch, scratch / 'inference.json').unlink(missing_ok=True)
        _invoke(Path(config['projectDir']), 'prepare-infer-many', [s for _, s, _ in prepared_contexts],
                int(config['analysisTimeoutMinutes'])*60, alignment=False)
    for plan, scratch, index in prepared_contexts:
        inference = read_bounded_json(scratch / "inference.json", max_bytes=1024*1024, root=scratch)
        if inference.get("engine") == "wav2vec2-target-capability":
            expected = {"formatVersion": 1, "engine": "wav2vec2-target-capability",
                "requestSha256": hashlib.sha256(json.dumps(plan["request"], ensure_ascii=False,
                    sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest(),
                "supported": False}
            if (inference != expected or type(inference["formatVersion"]) is not int
                    or inference["supported"] is not False):
                raise ValueError("CTC target capability differs from the current request")
            from stemwise_engine.workflow_records import LyricsContentBlocker
            admission = require_endpoint_content(declaration, mapping, evidence, parse_worker_json(raw),
                canonical.parent.parent / "jobs" / declaration["videoId"])
            raise LyricsContentBlocker('pronunciation-capability-unavailable', binding=admission.binding)
        if (not isinstance(inference.get('runtime'), dict)
                or any(inference['runtime'].get(key) != value for key, value in runtime.items())):
            raise ValueError('CTC packet worker used a different numerical producer')
        entries[index] = {"occurrenceId": plan["occurrenceId"], "startMs": plan["startMs"], "endMs": plan["endMs"],
                        "inference": inference,
                        "assessment": _assess(inference["records"], plan["candidateFamilies"])}
    manifest = {"method": METHOD, "scorerVersion": SCORER_VERSION,
                "producerRuntime": runtime,
                "declarationSha256": declaration_sha, "analysisAudio": declaration["analysisAudio"],
                "canonicalPlainSha256": declaration["canonicalPlainSha256"],
                "occurrences": entries, "reviewRequired": True}
    atomic_write_bytes(path, json_bytes(manifest), root=directory)
    if all(a["result"] == "supported" for e in entries for a in e["assessment"].values()):
        _clean_scratch(directory, Path(config["projectDir"]).parent.parent)
    return _summary(directory, manifest)


def verify_stored_packet(attempt: Path, binding: dict) -> dict:
    if not isinstance(binding, dict) or set(binding) != {"method", "manifestSha256"} or binding["method"] != METHOD:
        raise ValueError("invalid CTC evidence binding")
    directory = _directory(attempt)
    path = validate_generated_path(directory, directory / "manifest.json")
    if sha256_file(path) != binding["manifestSha256"]:
        raise ValueError("CTC manifest is stale")
    manifest = read_bounded_json(path, max_bytes=16*1024*1024, root=directory)
    if (manifest.get("method") != METHOD or manifest.get("reviewRequired") is not True
            or type(manifest.get("scorerVersion")) is not int or manifest["scorerVersion"] != SCORER_VERSION
            or not manifest.get("occurrences")):
        raise ValueError("invalid CTC manifest")
    return manifest


def verify_packet(attempt: Path, declaration: dict, declaration_sha: str, audio: Path, binding: dict) -> dict:
    # The workflow has already checked retained PCM. Only bind this small result
    # to that source and declaration; do not replay separation or inference.
    manifest = verify_stored_packet(attempt, binding)
    if (manifest["declarationSha256"] != declaration_sha or manifest["analysisAudio"] != declaration["analysisAudio"]
            or manifest["canonicalPlainSha256"] != declaration["canonicalPlainSha256"]
            or len(manifest["occurrences"]) != len(declaration["occurrences"])):
        raise ValueError("CTC evidence bindings are stale")
    for entry, occurrence in zip(manifest["occurrences"], declaration["occurrences"]):
        if entry["occurrenceId"] != occurrence["occurrenceId"]:
            raise ValueError("CTC occurrence differs")
        if "inference" not in entry:
            if any(v["result"] != "abstain" for v in entry["assessment"].values()):
                raise ValueError("missing CTC evidence")
            continue
        records = entry["inference"]["records"]
        if {(r["route"], r["shiftMs"]) for r in records} != {(r, s) for r in ROUTES for s in (-5, 0, 5)} or len(records) != 9:
            raise ValueError("CTC acoustic views are incomplete")
        if _assess(records, occurrence["candidateFamilies"]) != entry["assessment"]:
            raise ValueError("CTC consensus differs from posterior evidence")
    return manifest


def verify_selections(manifest: dict, selections: list[dict]) -> None:
    for entry in manifest["occurrences"]:
        selection = next((s for s in selections if s.get("occurrenceId") == entry["occurrenceId"]), None)
        for boundary, key in (("startMs", "startCandidateFamily"), ("endMs", "endCandidateFamily")):
            if selection is None or selection.get(key) not in entry["assessment"][boundary]["supportedFamilies"]:
                raise ValueError("CTC evidence abstained or does not support this family")


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in ("prepare", "infer", "infer-many", "prepare-many", "prepare-infer-many"):
        raise SystemExit("private CTC worker expects mode, project and scratch")
    if sys.argv[1] in ('infer-many', 'prepare-many', 'prepare-infer-many'):
        raw = sys.stdin.read(16*1024*1024+1)
        paths = parse_worker_json(raw) if len(raw.encode()) <= 16*1024*1024 else None
        if not isinstance(paths, list) or not paths or any(not isinstance(p, str) for p in paths):
            raise ValueError('Invalid CTC context batch')
        if sys.argv[1] == 'prepare-many':
            _prepare_many(Path(sys.argv[2]), list(map(Path, paths)))
        elif sys.argv[1] == 'prepare-infer-many':
            _prepare_infer_many(Path(sys.argv[2]), list(map(Path, paths)), int(sys.argv[3]))
        else:
            _infer_many(Path(sys.argv[2]), list(map(Path, paths)), int(sys.argv[3]))
    else:
        (_prepare if sys.argv[1] == "prepare" else _infer)(Path(sys.argv[2]), Path(sys.argv[3]))


if __name__ == "__main__":
    raise SystemExit(run_worker_main(main))
