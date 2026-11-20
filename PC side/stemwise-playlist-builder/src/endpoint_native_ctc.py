"""Native Arabic CTC recovery for an otherwise qualified line ending."""
from __future__ import annotations

from contextlib import redirect_stdout
import json
import math
import os
from pathlib import Path
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success, run_worker_main
import sys
import tempfile

import numpy as np
import soundfile as sf
from stemwise_engine.acoustic_inputs import mono_samples

from stemwise_engine.common import (heavy_worker_stage, atomic_write_bytes, json_bytes, read_bounded_json, run_process_tree,
                    sha256_file, validate_generated_path)
from stemwise_engine.endpoint_ctc_evidence import _assess, _directory as ctc_directory, _plan, boundary_distributions, select_candidate
from stemwise_engine.endpoint_vocal_evidence import ROUTES, _vocal_samples
from stemwise_engine.lyrics_alignment import canonical_language
import stemwise_engine.nemo_nfa_forced_alignment as nfa

from stemwise_engine.provenance import endpoint_producer_identity

METHOD = "local-native-ctc-boundary-v1"
MODEL = {"repository": "nvidia/stt_ar_fastconformer_hybrid_large_pcd_v1.0",
         "revision": nfa.MODEL_REVISION, "sha256": nfa.MODEL_SHA256,
         "sourceRevision": nfa.NEMO_SOURCE_REVISION, "frameStepMs": nfa.ACOUSTIC_TIMESTEP_MS}
MODEL['producer'] = endpoint_producer_identity('native')
VIEWS = {(r, s): g for r in ROUTES for s, g in ((-5, .9), (0, 1.), (5, 1.1))}


def numerical_model():
    return {**MODEL, 'cudaDevice': nfa.cuda_device_identity('nemo_nfa_forced_alignment')}


def validate_recorded_model(value):
    if not isinstance(value, dict) or {key: item for key, item in value.items() if key != 'cudaDevice'} != MODEL:
        raise ValueError('native CTC model identity changed')
    nfa.validate_cuda_device(value.get('cudaDevice'), 'nemo_nfa_forced_alignment')
    return value


def _target_ids(utterance, tokenizer):
    targets = utterance.token_ids_with_blanks[1::2]
    if any(token in {"<unk>", "[UNK]"} for token in tokenizer.ids_to_tokens(targets)):
        raise ValueError("native CTC cannot represent every approved target")
    return targets


def _finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def assess(records, families, reason):
    """Recompute support from numeric masses; dependent views never add votes."""
    from stemwise_engine.alignment_endpoints import _endpoint_boundary_supported
    result = _assess([], families)
    if reason != "line-end-disagreement" or len(families) < 2 or "mms-fa" not in families:
        return result
    starts = {family: candidate for family, candidate in families.items()
              if _endpoint_boundary_supported(candidate, family, 'startMs')}
    if ('mms-fa' in starts and len(starts) >= 2
            and max(c['startMs'] for c in starts.values())-min(c['startMs'] for c in starts.values()) <= 300):
        result["startMs"] = {"result": "supported", "candidateMs": families["mms-fa"]["startMs"],
            "supportedFamilies": ["mms-fa"], "reason": "preserved-independent-start-agreement"}
    if len(records) != 9 or {(r.get("route"), r.get("shiftMs")) for r in records} != set(VIEWS):
        return result
    times = sorted({c["endMs"] for c in families.values()})
    choices, peaks = [], []
    for record in records:
        if (record.get("gain") != VIEWS[record["route"], record["shiftMs"]]
                or record.get("stepMs") != 80 or not _finite(record.get("originMs"))):
            return result
        decision = record.get("decisions", {}).get("endMs", {})
        peak, mass = decision.get("peakMs"), decision.get("peakMass")
        if (decision.get("frameStepMs") != 80 or not _finite(peak)
                or not _finite(mass) or not .5 <= mass <= 1):
            return result
        origin = record["originMs"]
        frame = round((peak-origin)/80)
        if frame < 0 or abs(origin+frame*80-peak) > .001:
            return result
        scores = decision.get("candidateScores", [])
        if len(scores) != len(times) or [s.get("candidateMs") for s in scores] != times:
            return result
        selected = []
        for score in scores:
            value = score.get("mass")
            edge = math.floor((score["candidateMs"]-origin)/80+.5)
            if score.get("frameEdge") != edge or not _finite(value) or not 0 <= value <= 1:
                return result
            if edge == frame:
                if abs(value-mass) > 1e-7:
                    return result
                selected.append(score["candidateMs"])
            elif mass < 3*value:
                return result
        if len(selected) != 1:
            return result
        choices.append(selected[0])
        peaks.append(peak)
    if len(set(choices)) != 1 or max(peaks)-min(peaks) > 90:
        return result
    chosen = choices[0]
    low, high = min(peaks)-40, max(peaks)+40
    # Half-frame uncertainty must stay in the same nearest-candidate region.
    if any([t for t in times if abs(t-p) == min(abs(v-p) for v in times)] != [chosen]
           for p in (low, high)):
        return result
    corroborators = [k for k, c in families.items() if k != "nemo-nfa"
                     and _endpoint_boundary_supported(c, k, 'endMs')
                     and max(abs(c["endMs"]-low), abs(c["endMs"]-high)) <= 400]
    supported = sorted(k for k, c in families.items() if c['endMs'] == chosen
                       and _endpoint_boundary_supported(c, k, 'endMs'))
    if len(corroborators) < 2 or not supported:
        return result
    result["endMs"] = {"result": "supported", "candidateMs": chosen,
        "supportedFamilies": supported,
        "reason": "stable-native-ctc-ending", "predictionRangeMs": [low, high]}
    return result


def _directory(attempt):
    return validate_generated_path(attempt.parent.parent,
        attempt.parent.parent / "endpoint-native-ctc" / attempt.name)


def _previous_packet(attempt, binding, declaration, declaration_sha, audio=None):
    import stemwise_engine.endpoint_candidate_prediction as prediction
    if isinstance(binding, dict) and binding.get('method') == prediction.METHOD:
        return prediction.verify_packet(attempt, declaration, declaration_sha, audio, binding)
    return prediction._previous_packet(attempt, binding, declaration=declaration,
        declaration_sha=declaration_sha, audio=audio)


def verify_stored_packet(attempt, binding):
    path = _directory(attempt) / "manifest.json"
    if (not isinstance(binding, dict) or set(binding) != {"method", "manifestSha256"}
            or binding["method"] != METHOD or sha256_file(path) != binding["manifestSha256"]):
        raise ValueError("native CTC binding changed")
    manifest = read_bounded_json(path, max_bytes=16*1024*1024, root=path.parent)
    if manifest.get("method") != METHOD or not manifest.get("occurrences"):
        raise ValueError("native CTC model or occurrences changed")
    model = validate_recorded_model(manifest.get('model'))
    declaration_path = attempt/'declaration.json'
    if sha256_file(declaration_path) != manifest['declarationSha256']:
        raise ValueError('native CTC declaration changed')
    declaration = read_bounded_json(declaration_path, max_bytes=16*1024*1024, root=attempt)
    if any(manifest[key] != declaration[key] for key in ('analysisAudio', 'canonicalPlainSha256')):
        raise ValueError('native CTC declaration changed')
    previous = _previous_packet(attempt, manifest['previousEvidence'], declaration, manifest['declarationSha256'])
    from stemwise_engine.endpoint_candidate_prediction import _preserve_supported
    if len(manifest['occurrences']) != len(previous['occurrences']):
        raise ValueError('native CTC previous occurrence count changed')
    for entry, prior, occurrence in zip(manifest["occurrences"], previous['occurrences'], declaration['occurrences']):
        if entry['occurrenceId'] != prior['occurrenceId']:
            raise ValueError('native CTC previous occurrence changed')
        if all(value['result'] == 'supported' for value in prior['assessment'].values()):
            if entry != prior:
                raise ValueError('native CTC inherited occurrence changed')
            continue
        if entry['inference'].get('model') != model:
            raise ValueError('native CTC result used a different model or device')
        if entry['candidateFamilies'] != occurrence['candidateFamilies']:
            raise ValueError('native CTC candidate declaration changed')
        expected = _preserve_supported(prior['assessment'],
            assess(entry["inference"]["records"], entry["candidateFamilies"], entry["reason"]))
        if entry["assessment"] != expected:
            raise ValueError("native CTC assessment differs from acoustic masses")
    return manifest


def _contexts(attempt, declaration, audio, mapping, evidence, canonical):
    from stemwise_engine.source_crop import read_source_crop as _source_crop
    if sha256_file(canonical) != declaration["canonicalPlainSha256"] or sha256_file(audio) != declaration["analysisAudio"]["sha256"]:
        raise ValueError("native CTC audio or approved text changed")
    lyrics = read_bounded_json(canonical, max_bytes=16*1024*1024, root=canonical.parent)
    plans = _plan(declaration, mapping, evidence, lyrics,
        evidence_root=canonical.parent.parent / "jobs" / declaration["videoId"])
    reasons = {row["occurrenceId"]: row["publishDecision"].get("code") for row in evidence["occurrenceCandidates"]}
    if any(canonical_language(p["language"]) != "ar" or reasons[p["occurrenceId"]] != "line-end-disagreement" for p in plans):
        return [], []
    contexts = []
    for index, plan in enumerate(plans):
        scratch = ctc_directory(attempt) / f"context-{index:03d}"
        if read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch) != plan:
            raise ValueError("native CTC retained context changed")
        retained = _source_crop(scratch, plan)
        with sf.SoundFile(audio) as source:
            if source.samplerate != 48000:
                raise ValueError("native CTC recording rate changed")
            source.seek(plan["startMs"]*48)
            if not np.array_equal(retained, source.read(len(retained), dtype="float32", always_2d=True)):
                raise ValueError("native CTC retained crop differs from the recording")
        from stemwise_engine.endpoint_context_cache import context_binding
        context_binding(scratch, MODEL)
        contexts.append(scratch)
    return plans, contexts


def _infer(project, contexts, timeout, *, replay=False):
    from stemwise_engine.endpoint_context_cache import read_context_result
    if not contexts:
        return []
    model = numerical_model()
    saved = [None if replay else read_context_result(scratch, model) for scratch in contexts]
    if any(result is not None and (not isinstance(result, dict) or result.get('model') != model) for result in saved):
        raise ValueError('native CTC retained result used a different model or device')
    pending = [scratch for scratch, result in zip(contexts, saved) if result is None]
    if not pending:
        return saved
    runtime = project.parent / "runtime/nemo-speech-3.0.0"
    with heavy_worker_stage({'projectDir': str(project)}):
        completed = run_process_tree([str(runtime / ".venv/bin/python"), '-m', 'stemwise_engine.endpoint_native_ctc', str(project),
            *(str(p) for p in pending)], capture_output=True, text=True, check=False, timeout=timeout,
            env={**os.environ, "PYTHONPATH": str(runtime), "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1",
                 "HF_DATASETS_OFFLINE": "1", "TOKENIZERS_PARALLELISM": "false", "WANDB_MODE": "disabled"})
    require_worker_success(completed, code='native-ctc-worker-failed', diagnostic_path=contexts[0].parent/'native-ctc-worker-failed.private.txt')
    if len(completed.stdout.encode("utf-8")) > 16*1024*1024:
        raise ValueError("native CTC output is too large")
    output = parse_worker_json(completed.stdout)
    if not isinstance(output, list) or len(output) != len(pending):
        raise ValueError('native CTC context result count changed')
    if any(not isinstance(result, dict) or result.get('model') != model for result in output):
        raise ValueError('native CTC worker used a different model or device')
    inferred = iter(output)
    return [next(inferred) if result is None else result for result in saved]


def write_packet(attempt, declaration, declaration_sha, audio, config, mapping, evidence, canonical, previous):
    plans, contexts = _contexts(attempt, declaration, audio, mapping, evidence, canonical)
    if not plans:
        return previous
    binding = {key: previous.get(key) for key in ('method', 'manifestSha256')}
    prior = _previous_packet(attempt, binding, declaration, declaration_sha, audio)
    if [entry['occurrenceId'] for entry in prior['occurrences']] != [plan['occurrenceId'] for plan in plans]:
        raise ValueError('native CTC previous occurrence order changed')
    pending = [index for index, entry in enumerate(prior['occurrences'])
               if any(value['result'] != 'supported' for value in entry['assessment'].values())]
    if not pending:
        return {**previous, 'occurrences': prior['occurrences'], 'reviewRequired': False}
    from stemwise_engine.endpoint_candidate_prediction import _preserve_supported
    path = _directory(attempt) / "manifest.json"
    reasons = {row["occurrenceId"]: row["publishDecision"].get("code") for row in evidence["occurrenceCandidates"]}
    model = numerical_model()
    authority = {"method": METHOD, "model": model, "declarationSha256": declaration_sha,
        "analysisAudio": declaration["analysisAudio"], "canonicalPlainSha256": declaration["canonicalPlainSha256"],
        "previousEvidence": binding}
    inputs = [{key: sha256_file(scratch / name) for key, name in (
        ("requestSha256", "request.private.json"), ("preparedSha256", "prepared.json"), ("sourceSha256", "source.wav"))}
        for scratch in contexts]
    if path.exists():
        manifest = verify_stored_packet(attempt, {"method": METHOD, "manifestSha256": sha256_file(path)})
        if any(manifest.get(k) != v for k, v in authority.items()) or manifest.get("contexts") != inputs:
            raise ValueError("native CTC authority or retained sources changed")
    else:
        inferred = _infer(Path(config["projectDir"]), [contexts[index] for index in pending],
                          int(config["analysisTimeoutMinutes"])*60)
        if len(inferred) != len(pending) or any(row.get('model') != model for row in inferred):
            raise ValueError("native CTC occurrence count changed")
        inferred = dict(zip(pending, inferred))
        entries = []
        for index, (plan, parent) in enumerate(zip(plans, prior['occurrences'])):
            if index not in inferred:
                entries.append(parent)
                continue
            record = inferred[index]
            entries.append({"occurrenceId": plan["occurrenceId"], "candidateFamilies": plan["candidateFamilies"],
                "reason": reasons[plan["occurrenceId"]], "inference": record,
                "assessment": _preserve_supported(parent['assessment'],
                    assess(record["records"], plan["candidateFamilies"], reasons[plan["occurrenceId"]]))})
        manifest = {**authority, "contexts": inputs, "occurrences": entries}
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_bytes(path, json_bytes(manifest), root=path.parent)
    if len(manifest["occurrences"]) != len(plans):
        raise ValueError("native CTC occurrence count changed")
    for index in pending:
        entry, plan = manifest['occurrences'][index], plans[index]
        if entry["occurrenceId"] != plan["occurrenceId"] or entry["candidateFamilies"] != plan["candidateFamilies"] or entry["reason"] != reasons[plan["occurrenceId"]]:
            raise ValueError("native CTC candidate declaration changed")
    return {"method": METHOD, "manifestSha256": sha256_file(path),
        "reviewRequired": not all(a["result"] == "supported" for e in manifest["occurrences"] for a in e["assessment"].values()),
        "occurrences": [{"occurrenceId": e["occurrenceId"], "assessment": e["assessment"]} for e in manifest["occurrences"]]}


def verify_packet(attempt, declaration, declaration_sha, audio, binding, config, mapping, evidence, canonical, *, replay=False):
    manifest = verify_stored_packet(attempt, binding)
    write_packet(attempt, declaration, declaration_sha, audio, config, mapping, evidence, canonical, manifest['previousEvidence'])
    plans, contexts = _contexts(attempt, declaration, audio, mapping, evidence, canonical)
    if len(plans) != len(manifest["occurrences"]):
        raise ValueError("native CTC review no longer has its declared contexts")
    if replay:
        from stemwise_engine.endpoint_candidate_prediction import _preserve_supported
        prior = _previous_packet(attempt, manifest['previousEvidence'], declaration, declaration_sha, audio)
        pending = [index for index, entry in enumerate(prior['occurrences'])
                   if any(value['result'] != 'supported' for value in entry['assessment'].values())]
        inferred = _infer(Path(config["projectDir"]), [contexts[index] for index in pending],
                          int(config["analysisTimeoutMinutes"])*60, replay=True)
        if len(inferred) != len(pending) or any(_preserve_supported(prior['occurrences'][index]['assessment'],
                assess(row["records"], plans[index]["candidateFamilies"], manifest['occurrences'][index]["reason"]))
                != manifest['occurrences'][index]["assessment"] for index, row in zip(pending, inferred)):
            raise ValueError("native CTC selection did not reproduce")
    return manifest


def infer_contexts(project, contexts):
    """One native model load, complete target sequences, no persistent posterior arrays."""
    runtime = project.parent / "runtime/nemo-speech-3.0.0"
    model_path = project.parent / "model-cache/lyrics-alignment/models--nvidia--stt_ar_fastconformer_hybrid_large_pcd_v1.0/snapshots" / nfa.MODEL_REVISION / nfa.MODEL_ARTIFACT
    nfa._bound_file(str(model_path), nfa.MODEL_SHA256, nfa.MODEL_BYTE_COUNT, "model")
    nfa._bound_file(str(runtime / "tools/nemo_forced_aligner/align.py"), nfa.NFA_ALIGN_SHA256, nfa.NFA_ALIGN_BYTE_COUNT, "aligner")
    nfa._bound_file(str(runtime / "uv.lock"), nfa.NEMO_LOCK_SHA256, nfa.NEMO_LOCK_BYTE_COUNT, "lock")
    nfa._verify_source_checkout(runtime)
    observed_runtime = nfa._runtime_identity()
    identity = numerical_model()
    if observed_runtime['cudaDevice'] != identity['cudaDevice']:
        raise ValueError('native CTC device changed before inference')
    import torch
    from nemo.collections.asr.models import ASRModel, EncDecHybridRNNTCTCModel
    from nemo.collections.asr.parts.utils.aligner_utils import get_batch_variables, Word, Segment
    model = ASRModel.restore_from(str(model_path), map_location=torch.device("cuda:0")).eval()
    if isinstance(model, EncDecHybridRNNTCTCModel):
        model.change_decoding_strategy(decoder_type="ctc")
    model.change_attention_model(self_attention_model="rel_pos_local_attn", att_context_size=[64, 64])
    result = []
    with tempfile.TemporaryDirectory(prefix="stemwise-native-ctc-") as directory:
        wav = Path(directory) / "input.wav"
        for scratch in contexts:
            from stemwise_engine.endpoint_context_cache import context_binding
            binding = context_binding(scratch, identity)
            plan = read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch)
            prepared = read_bounded_json(scratch / "prepared.json", max_bytes=1024*1024, root=scratch)
            if canonical_language(plan["language"]) != "ar" or set(prepared["views"]) != set(ROUTES):
                raise ValueError("native CTC needs Arabic and every vocal view")
            line_records, approved = nfa._flatten(plan['request'])
            target = line_records[plan['targetLine']]
            begin = target['startWord']
            end = begin + target['wordCount']-1
            records = []
            for route in ROUTES:
                view = prepared["views"][route]
                samples = _vocal_samples(validate_generated_path(scratch, scratch / view["path"]),
                    view["provenance"], plan["endMs"]-plan["startMs"], sample_rate=44100)
                mono, _ = mono_samples(samples)
                for shift, gain in ((-5, .9), (0, 1.), (5, 1.1)):
                    offset, tail = round((10+shift)*44.1), round(10*44.1)
                    sf.write(str(wav), mono[offset:len(mono)-tail]*gain, 44100, subtype="FLOAT")
                    logp, _, lengths, _, utterances, step = get_batch_variables([str(wav)], model,
                        segment_separators=[], gt_text_batch=[" ".join(approved)], verbose=False)
                    utt = utterances[0]
                    words = [w for s in utt.segments_and_tokens if isinstance(s, Segment)
                             for w in s.words_and_tokens if isinstance(w, Word)]
                    if [w.text for w in words] != approved or abs(step*1000-80) > .001:
                        raise ValueError("native CTC changed the approved words or frame grid")
                    targets = _target_ids(utt, model.tokenizer)
                    onset, ending = boundary_distributions(logp[0, :int(lengths[0])].numpy(),
                        targets, utt.token_ids_with_blanks[0],
                        (words[begin].s_start-1)//2, (words[end].s_end-1)//2, allow_edge_audio=True)
                    origin = plan["startMs"] + offset/44.1
                    decisions = {b: select_candidate(m, [c[b] for c in plan["candidateFamilies"].values()],
                        origin_ms=origin, step_ms=80) for b, m in (("startMs", onset), ("endMs", ending))}
                    records.append({"route": route, "shiftMs": shift, "gain": gain,
                                    "originMs": origin, "stepMs": 80, "decisions": decisions})
            if numerical_model() != identity:
                raise ValueError('native CTC device changed during inference')
            record = {"model": identity, "records": records, "targetCount": len(utt.token_ids_with_blanks[1::2]), "wordCount": len(approved)}
            from stemwise_engine.endpoint_context_cache import write_context_result
            write_context_result(scratch, identity, record, binding=binding)
            result.append(record)
    return result


def main():
    with redirect_stdout(sys.stderr):
        output = infer_contexts(Path(sys.argv[1]), [Path(p) for p in sys.argv[2:]])
    print(json.dumps(output, separators=(",", ":")))


if __name__ == "__main__":
    raise SystemExit(run_worker_main(main))
