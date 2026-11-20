"""Bounded nearest-candidate prediction, never an exact physical onset claim."""
from __future__ import annotations

import json
import math
import os
from dataclasses import dataclass
from pathlib import Path
from stemwise_engine.model_registry import model_assets, runtime_profile
from stemwise_engine.runtime_environment import environment_python, environment_lease
import sys

import numpy as np
from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
from stemwise_engine.worker_protocol import require_worker_success

from stemwise_engine.common import (heavy_worker_stage, atomic_write_bytes, json_bytes, read_bounded_json, run_process_tree,
                    sha256_file, validate_generated_path)
from stemwise_engine.endpoint_ctc_evidence import ROUTES, SCORER_VERSION, _assess, _plan, infer_records
from stemwise_engine.lyrics_alignment import canonical_language

METHOD = "local-vocal-candidate-prediction-v1"
REPOSITORY = model_assets('endpoint_candidate_prediction')['REPOSITORY']
SNAPSHOT = model_assets('endpoint_candidate_prediction')['SNAPSHOT']
MODEL_FILES = model_assets('endpoint_candidate_prediction')['MODEL_FILES']
MODEL_IDENTITY = {"repository": REPOSITORY, "snapshot": SNAPSHOT, "targetKind": "grapheme",
                  "language": "ar", "modelSha256": MODEL_FILES["pytorch_model.bin"]}
EN_REPOSITORY = model_assets('endpoint_candidate_prediction')['EN_REPOSITORY']
EN_SNAPSHOT = model_assets('endpoint_candidate_prediction')['EN_SNAPSHOT']
EN_MODEL_FILES = model_assets('endpoint_candidate_prediction')['EN_MODEL_FILES']
EN_MODEL_IDENTITY = {"repository": EN_REPOSITORY, "snapshot": EN_SNAPSHOT, "targetKind": "grapheme",
                     "language": "en", "modelSha256": EN_MODEL_FILES["model.safetensors"]}


@dataclass(frozen=True)
class PhrasePredictionPolicy:
    version: int
    maxVariants: int
    maxBoundaryCandidates: int
    startSpreadMs: int
    endSpreadMs: int
    spacingFraction: float

    def __post_init__(self):
        if (any(type(value) not in (int, float) or not math.isfinite(value) for value in self.__dict__.values())
                or any(type(getattr(self,name)) is not int for name in ('version','maxVariants',
                    'maxBoundaryCandidates','startSpreadMs','endSpreadMs'))
                or self.version != 1 or self.maxVariants != 4 or self.maxBoundaryCandidates != 8
                or self.startSpreadMs != 300 or self.endSpreadMs != 400 or self.spacingFraction != .5):
            raise ValueError('Unsupported acoustic phrase prediction policy')


def phrase_prediction_policy():
    from stemwise_engine.model_registry import execution_policy
    policy = PhrasePredictionPolicy(**model_assets('endpoint_candidate_prediction')['phrasePolicy'])
    execution = execution_policy()
    if (policy.maxVariants != execution.predictionMaxVariants or policy.maxBoundaryCandidates != execution.predictionMaxCandidates):
        raise ValueError('Prediction candidate-search policies differ')
    return policy


def summarize_boundary_curves(identity, onset, offset, edges, *, minimum_frames=1, preceding_gap_frames=0):
    """Retain bounded target-specific modes, not full logits or invented precision."""
    policy = phrase_prediction_policy()
    edges = np.asarray(edges, dtype=np.float64)
    CURRENT_BUDGET.require(retained_bytes=(len(edges)*3+policy.maxBoundaryCandidates*8)*8)
    if (edges.ndim != 1 or len(edges) < 2 or not np.isfinite(edges).all()
            or not (np.diff(edges) > 0).all()):
        raise ValueError('Prediction boundary time map is invalid')
    result = {'wordId': identity, 'minimumFrames': minimum_frames,
              'precedingGapFrames': preceding_gap_frames, 'frameMs': float(np.median(np.diff(edges)))}
    for name, curve in (('onset', onset), ('offset', offset)):
        mass = np.asarray(curve, dtype=np.float64)
        if (mass.shape != edges.shape or not np.isfinite(mass).all() or (mass < 0).any()
                or not np.isclose(mass.sum(), 1, atol=1e-5)):
            raise ValueError('Prediction boundary mass is invalid')
        modes = np.flatnonzero((mass > 0) & (mass >= np.r_[-np.inf, mass[:-1]])
                               & (mass >= np.r_[mass[1:], -np.inf]))
        result[name+'Truncated'] = len(modes) > policy.maxBoundaryCandidates
        result[name+'Informative'] = bool(mass.max() > mass.min())
        selected = sorted(modes.tolist(), key=lambda index: (-mass[index], index))[:policy.maxBoundaryCandidates]
        result[name] = [{'edge': index, 'timeMs': float(edges[index]), 'cost': -math.log(float(mass[index]))}
                        for index in sorted(selected)]
    return result


def assess(records: list[dict], families: dict) -> dict:
    # Recompute from numeric peaks and the frame grid, never reported pass flags.
    from stemwise_engine.alignment_endpoints import _endpoint_boundary_supported
    result = _assess([], families)
    if len(records) != 9 or {(r["route"], r["shiftMs"]) for r in records} != {(r, s) for r in ROUTES for s in (-5, 0, 5)}:
        return result
    for boundary, allowance in (("startMs", 150), ("endMs", 200)):
        eligible = {family: candidate for family, candidate in families.items()
                    if _endpoint_boundary_supported(candidate, family, boundary)}
        decisions = [r["decisions"][boundary] for r in records]
        if any(not all(type(d.get(k)) in (int, float) and np.isfinite(d[k])
                       for k in ("peakMs", "peakMass", "frameStepMs"))
               or not .9 <= d["peakMass"] <= 1 or d["frameStepMs"] != 20 for d in decisions):
            continue
        if any(type(r.get("originMs")) not in (int, float) or not np.isfinite(r["originMs"])
               or r.get("stepMs") != 20 for r in records):
            continue
        peaks = [d["peakMs"] for d in decisions]
        if max(peaks)-min(peaks) > 40:
            continue
        times = sorted({c[boundary] for c in families.values()})
        compatible = set(times)
        valid_grid = True
        for record, peak in zip(records, peaks):
            origin = record["originMs"]
            frame = round((peak-origin)/20)
            if frame < 0 or abs(origin+frame*20-peak) > .001:
                valid_grid = False
                break
            compatible &= {t for t in times if int(np.floor((t-origin)/20+.5)) == frame}
        if not valid_grid:
            continue
        if len(compatible) == 1:
            chosen = next(iter(compatible))
            supported = sorted(k for k, c in eligible.items() if c[boundary] == chosen)
            if not supported:
                continue
            result[boundary] = {"result": "supported", "candidateMs": chosen,
                "supportedFamilies": supported,
                "reason": "stable-grapheme-ctc-candidate"}
            continue
        low, high = min(peaks)-10, max(peaks)+10
        winners = [{t for t in times if abs(t-p) == min(abs(v-p) for v in times)} for p in (low, high)]
        if len(winners[0]) != 1 or winners[0] != winners[1]:
            continue
        chosen = next(iter(winners[0]))
        supported = sorted(k for k, c in eligible.items() if c[boundary] == chosen)
        # At most half the engine's existing 300/400 ms agreement budget.
        # A second existing family must corroborate the same local region.
        if not supported or max(abs(chosen-low), abs(chosen-high)) > allowance or sum(
                max(abs(c[boundary]-low), abs(c[boundary]-high)) <= 2*allowance
                for c in eligible.values()) < 2:
            continue
        result[boundary] = {"result": "supported", "candidateMs": chosen,
            "supportedFamilies": supported,
            "reason": "bounded-nearest-acoustic-prediction", "predictionRangeMs": [low, high]}
    return result


def _directory(attempt: Path) -> Path:
    return validate_generated_path(attempt.parent.parent, attempt.parent.parent / "endpoint-candidate-prediction" / attempt.name)


def _model(project: Path, language: str = "ar") -> tuple[Path, dict]:
    if language not in ("ar", "en"):
        raise ValueError("candidate prediction language is unsupported")
    identity, files = (EN_MODEL_IDENTITY, EN_MODEL_FILES) if language == "en" else (MODEL_IDENTITY, MODEL_FILES)
    path = project.parent / "model-cache/lyrics-alignment" / ("models--"+identity["repository"].replace("/", "--")) / "snapshots" / identity["snapshot"]
    if {p.name for p in path.iterdir() if p.is_file()} != set(files):
        raise ValueError("candidate prediction model files differ")
    for name, digest in files.items():
        if sha256_file(path / name) != digest:
            raise ValueError("candidate prediction model asset changed")
    return path, identity


def _previous_packet(attempt, binding, *, declaration=None, declaration_sha=None, audio=None):
    import stemwise_engine.endpoint_ctc_evidence as ctc
    import stemwise_engine.endpoint_repeat_alignment as repeat
    if not isinstance(binding, dict) or set(binding) != {"method", "manifestSha256"}:
        raise ValueError("candidate prediction previous evidence binding is invalid")
    if binding.get("method") == ctc.METHOD:
        packet = ctc.verify_stored_packet(attempt, binding)
        ctc_binding = binding
    elif binding.get("method") == repeat.METHOD:
        packet = repeat.verify_stored_packet(attempt, binding)
        ctc_binding = packet["ctcEvidence"]
    else:
        raise ValueError("candidate prediction previous evidence is unsupported")
    if declaration is not None:
        ctc.verify_packet(attempt, declaration, declaration_sha, audio, ctc_binding)
    return packet


def _preserve_supported(previous, predicted):
    return {boundary: previous[boundary] if previous[boundary]["result"] == "supported" else predicted[boundary]
            for boundary in ("startMs", "endMs")}


def verify_stored_packet(attempt: Path, binding: dict) -> dict:
    path = _directory(attempt) / "manifest.json"
    if (not isinstance(binding, dict) or set(binding) != {"method", "manifestSha256"}
            or binding["method"] != METHOD or sha256_file(path) != binding["manifestSha256"]):
        raise ValueError("candidate prediction binding is stale")
    manifest = read_bounded_json(path, max_bytes=16*1024*1024, root=path.parent)
    if manifest.get("method") != METHOD or not manifest.get("occurrences"):
        raise ValueError("candidate predictions are missing")
    if manifest.get('predictionVersion') != 2 or manifest.get('contextVersion') != SCORER_VERSION:
        raise ValueError('Candidate prediction packet requires regeneration')
    previous = _previous_packet(attempt, manifest["previousEvidence"], declaration=manifest,
                                declaration_sha=manifest["declarationSha256"])
    if len(manifest["occurrences"]) != len(previous["occurrences"]):
        raise ValueError("candidate prediction previous occurrence count differs")
    for entry, prior in zip(manifest["occurrences"], previous["occurrences"]):
        if entry["occurrenceId"] != prior["occurrenceId"]:
            raise ValueError("candidate prediction previous occurrence differs")
        language = canonical_language(entry["language"])
        if "inference" in entry:
            if language not in ("ar", "en") or "unsupported" in entry:
                raise ValueError("candidate prediction context language is unsupported")
            inference = entry["inference"]
            identity = EN_MODEL_IDENTITY if language == "en" else MODEL_IDENTITY
            if inference["model"] != identity:
                raise ValueError("candidate prediction context model differs")
            expected = _preserve_supported(prior["assessment"], assess(inference["records"], entry["candidateFamilies"]))
        else:
            if not entry.get("unsupported"):
                raise ValueError("candidate prediction context evidence is missing")
            expected = prior["assessment"]
        if entry["assessment"] != expected:
            raise ValueError("candidate prediction differs from acoustic results")
    return manifest


def verify_packet(attempt: Path, declaration: dict, declaration_sha: str, audio: Path, binding: dict) -> dict:
    manifest = verify_stored_packet(attempt, binding)
    _previous_packet(attempt, manifest["previousEvidence"], declaration=declaration,
                     declaration_sha=declaration_sha, audio=audio)
    if (manifest["declarationSha256"] != declaration_sha or manifest["analysisAudio"] != declaration["analysisAudio"]
            or manifest["canonicalPlainSha256"] != declaration["canonicalPlainSha256"]
            or len(manifest["occurrences"]) != len(declaration["occurrences"])):
        raise ValueError("candidate prediction authority differs")
    for entry, occurrence in zip(manifest["occurrences"], declaration["occurrences"]):
        if entry["occurrenceId"] != occurrence["occurrenceId"] or entry["candidateFamilies"] != occurrence["candidateFamilies"]:
            raise ValueError("candidate prediction declaration differs")
    return manifest


def _summary(path: Path, manifest: dict) -> dict:
    return {"method": METHOD, "manifestSha256": sha256_file(path),
        "reviewRequired": not all(a["result"] == "supported" for e in manifest["occurrences"] for a in e["assessment"].values()),
        "occurrences": [{"occurrenceId": e["occurrenceId"], "assessment": e["assessment"]} for e in manifest["occurrences"]]}


def write_packet(attempt: Path, declaration: dict, declaration_sha: str, audio: Path, config: dict,
                 mapping: dict, evidence: dict, canonical: Path, previous: dict) -> dict:
    from stemwise_engine.endpoint_ctc_evidence import _directory as ctc_directory
    if sha256_file(canonical) != declaration["canonicalPlainSha256"]:
        raise ValueError("candidate prediction authority changed")
    lyrics = read_bounded_json(canonical, max_bytes=16*1024*1024, root=canonical.parent)
    plans = _plan(declaration, mapping, evidence, lyrics,
        evidence_root=canonical.parent.parent / "jobs" / declaration["videoId"])
    supported = [(index, plan) for index, plan in enumerate(plans)
                 if canonical_language(plan["language"]) in ("ar", "en") and "unsupported" not in plan]
    if not supported:
        return previous
    previous_binding = {k: previous.get(k) for k in ("method", "manifestSha256")}
    prior = _previous_packet(attempt, previous_binding, declaration=declaration,
              declaration_sha=declaration_sha, audio=audio)
    if prior and [e["occurrenceId"] for e in prior["occurrences"]] != [p["occurrenceId"] for p in plans]:
        raise ValueError("candidate prediction previous occurrence order differs")
    directory = _directory(attempt)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "manifest.json"
    if path.exists():
        cached = read_bounded_json(path, max_bytes=16*1024*1024, root=directory)
        refresh = cached.get("predictionVersion") != 2 or (
            cached.get("previousEvidence") != previous_binding or cached.get("contextVersion") != SCORER_VERSION)
        if not refresh:
            manifest = verify_packet(attempt, declaration, declaration_sha, audio,
                {"method": METHOD, "manifestSha256": sha256_file(path)})
            if (manifest.get("predictionVersion") != 2
                    or [e["language"] for e in manifest["occurrences"]] != [p["language"] for p in plans]):
                raise ValueError("candidate prediction context languages changed")
            return _summary(path, manifest)
    project = Path(config["projectDir"])
    entries = []
    contexts = []
    for index, plan in supported:
        scratch = ctc_directory(attempt) / f"context-{index:03d}"
        if read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch) != plan:
            raise ValueError("candidate prediction acoustic context changed")
        contexts.append(scratch)
    for scratch in contexts:
        validate_generated_path(scratch, scratch / 'candidate-prediction.json').unlink(missing_ok=True)
    with heavy_worker_stage(config), environment_lease('main', builder=project):
        completed = run_process_tree([str(environment_python('main', project)), '-m', 'stemwise_engine.endpoint_candidate_prediction', str(project),
                                      *(str(scratch) for scratch in contexts)],
            capture_output=True, text=True, check=False, timeout=int(config["analysisTimeoutMinutes"])*60,
            env={**os.environ, "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1"})
    require_worker_success(completed, code='candidate-prediction-worker-failed',
        diagnostic_path=directory / 'candidate-prediction-error.private.txt')
    contexts = {index: scratch for (index, _), scratch in zip(supported, contexts)}
    for index, plan in enumerate(plans):
        entry = {"occurrenceId": plan["occurrenceId"], "candidateFamilies": plan["candidateFamilies"]}
        entry["language"] = plan["language"]
        if index not in contexts:
            entry.update(unsupported=plan.get("unsupported", "candidate-prediction-language-unsupported"),
                         assessment=prior["occurrences"][index]["assessment"])
            entries.append(entry)
            continue
        scratch = contexts[index]
        inference = read_bounded_json(scratch / "candidate-prediction.json", max_bytes=1024*1024, root=scratch)
        if inference.get("model") != (EN_MODEL_IDENTITY if canonical_language(plan["language"]) == "en" else MODEL_IDENTITY):
            raise ValueError("candidate prediction worker used the wrong model")
        assessment = assess(inference["records"], plan["candidateFamilies"])
        assessment = _preserve_supported(prior["occurrences"][index]["assessment"], assessment)
        entry.update(inference=inference, assessment=assessment)
        entries.append(entry)
    manifest = {"method": METHOD, "contextVersion": SCORER_VERSION,
                "declarationSha256": declaration_sha, "analysisAudio": declaration["analysisAudio"],
                "canonicalPlainSha256": declaration["canonicalPlainSha256"], "occurrences": entries}
    manifest.update(predictionVersion=2, previousEvidence=previous_binding)
    atomic_write_bytes(path, json_bytes(manifest), root=directory)
    verify_packet(attempt, declaration, declaration_sha, audio, {"method": METHOD, "manifestSha256": sha256_file(path)})
    return _summary(path, manifest)


def _infer_contexts(project, contexts):
    import importlib.metadata
    from stemwise_engine.endpoint_context_cache import context_binding, read_context_result, write_context_result
    from stemwise_engine.provenance import endpoint_producer_identity
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    runtime = {**torch_runtime_identity(), 'transformers': importlib.metadata.version('transformers')}
    producer = endpoint_producer_identity('ctc')
    groups = {}
    for scratch in contexts:
        plan = read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch)
        language = canonical_language(plan["language"])
        groups.setdefault(language, []).append(scratch)
    cache = {}
    for language, group in groups.items():
        assets = _model(project, language)
        identity = {'method': METHOD, 'model': assets[1], 'producer': producer, 'runtime': runtime}
        for scratch in group:
            binding = context_binding(scratch, identity)
            result = read_context_result(scratch, identity, binding=binding)
            if result is None:
                result = infer_records(scratch, assets, model_cache=cache)
                if result.get('runtime') != runtime:
                    raise ValueError('Candidate prediction used a different numerical producer')
                write_context_result(scratch, identity, result, binding=binding)
            atomic_write_bytes(scratch / "candidate-prediction.json",
                json_bytes(result), root=scratch)


def main():
    import torch
    torch.set_num_threads(6)
    project, *contexts = (Path(p) for p in sys.argv[1:])
    _infer_contexts(project, contexts)


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
