"""Predict disputed boundaries from a resolved, identical performed phrase.

Original stereo channels remain separate. Nonblank phonetic patterns, rather
than CTC blank peaks or musical energy, supply the time correspondence.
This is a bounded prediction, not a claim of sample-accurate onset detection.
"""
from __future__ import annotations
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success, run_worker_main
from stemwise_engine.runtime_environment import environment_python, environment_lease

import json
import gc
import math
import os
from pathlib import Path
import sys
import signal

import numpy as np
from stemwise_engine.acoustic_inputs import CURRENT_BUDGET, resampled_sample_count
from stemwise_engine.workflow_records import LyricsContentBlocker

from stemwise_engine.common import (heavy_worker_stage, atomic_write_bytes, json_bytes, read_bounded_json, run_process_tree,
                    read_bounded_regular_bytes, sha256_file, validate_generated_path)

METHOD = "local-repeat-phonetic-v1"
PREDICTION_VERSION = 4
TEMPLATE_POLICY_VERSION = 1


class NoRepeatMatch(ValueError):
    """Valid inputs without an unambiguous phonetic correspondence."""


def _extent(families):
    if not families:
        return None
    return min(c["startMs"] for c in families.values()), max(c["endMs"] for c in families.values())


def _relative_clock(reference, target):
    """Use the range across the families, so disagreement cannot manufacture two sites."""
    ref, dest = _extent(reference), _extent(target)
    if ref and dest and ref[0] < ref[1] and dest[0] < dest[1]:
        if ref[1] <= dest[0]:
            return 1
        if dest[1] <= ref[0]:
            return -1
    return 0


def _reference_candidates(reference: dict) -> dict:
    from stemwise_engine.alignment_endpoints import (_applicable_endpoint_candidates,
        _qualified_word_candidates, assess_complete_word_sequence)
    if (reference.get("publishDecision", {}).get("result") != "words"
            or reference.get("qualifiedEndpointContradictions") or reference.get("qualifiedWordContradictions")):
        return {}
    families = _qualified_word_candidates(reference)
    publisher = reference.get("selectedPublisherFamily")
    if publisher not in families or len(families) < 2:
        return {}
    _, reason = assess_complete_word_sequence(families[publisher], _applicable_endpoint_candidates(reference),
                                             publisher_family=publisher)
    return families if reason is None else {}


def reference_plan(occurrence: dict, mapping: dict, evidence: dict, lyrics: dict, *, spoken=None) -> dict | None:
    from stemwise_engine.lyrics_alignment import _physical_lines, _plain_original
    from stemwise_engine.lyrics_tokenization import display_words
    physical = _physical_lines(_plain_original(lyrics)["text"])
    mapped = mapping["performedOccurrences"]
    index = occurrence["performedIndex"]
    words = lambda i: tuple(w[2] for w in display_words(physical[mapped[i]["sourcePhysicalIndex"]]))
    target_words = words(index)
    if len(target_words) < 3:
        return None
    candidates = evidence["occurrenceCandidates"]
    references = []
    for i, item in enumerate(candidates):
        if i == index or item["publishDecision"]["result"] != "words" or words(i) != target_words:
            continue
        if spoken and spoken.words[mapped[i]["sourcePhysicalIndex"]] != spoken.words[mapped[index]["sourcePhysicalIndex"]]:
            continue
        families = _reference_candidates(item)
        publisher = item.get("selectedPublisherFamily")
        if (publisher not in families or
                _relative_clock(families, candidates[index].get("familyCandidates")) != (1 if i < index else -1)):
            continue
        selected = families[publisher]
        if (len(families) >= 2 and len(selected.get("words", [])) == len(target_words)
                and all(max(c[b] for c in families.values()) - min(c[b] for c in families.values()) <= limit
                        for b, limit in (("startMs", 300), ("endMs", 400)))):
            references.append((abs(i-index), i, {**item, "familyCandidates": families}))
    if not references:
        return None
    # Choose by occurrence proximity before examining acoustic scores.
    reference = min(references, key=lambda v: (v[0], v[1]))[2]
    return {"reference": reference, "target": candidates[index]}


def transfer(logp_a: np.ndarray, times_a: np.ndarray, logp_b: np.ndarray,
             times_b: np.ndarray, blank: int, stamps: list[int], *, reference_bounds=None) -> list[float]:
    from scipy.spatial.distance import cdist
    from librosa.sequence import dtw
    # Cost, padded accumulated costs/steps and reverse costs coexist.
    CURRENT_BUDGET.require(frames=len(logp_a)+2, states=len(logp_b)+2, arrays=6,
        retained_bytes=(logp_a.size+logp_b.size)*16)
    features, informative = [], []
    for logp, times in ((logp_a, times_a), (logp_b, times_b)):
        if (logp.ndim != 2 or len(logp) != len(times) or len(times) < 2
                or not np.isfinite(logp).all() or not np.isfinite(times).all()
                or not (np.diff(times) > 0).all()
                or not 0 <= blank < logp.shape[1]
                or not np.allclose(np.logaddexp.reduce(logp, axis=1), 0, atol=1e-4)):
            raise ValueError("invalid repeat acoustic features")
        # CTC silence dominates raw posterior distances. Retain relative phone
        # evidence, with a floor so tiny numerical tails cannot dominate it.
        feature = np.maximum(logp[:, np.arange(logp.shape[1]) != blank], -12)
        feature = feature - feature.mean(axis=1, keepdims=True)
        usable = np.linalg.norm(feature, axis=1) >= 1e-8
        features.append(feature)
        informative.append(usable)
    low, high = reference_bounds if reference_bounds is not None else (times_a[0], times_a[-1])
    if not np.isfinite([low, high]).all() or not low <= times_a[0] <= times_a[-1] <= high:
        raise ValueError('repeat physical bounds do not contain the acoustic frames')
    if features[0].shape[1] != features[1].shape[1] or any(t < low or t > high for t in stamps):
        raise ValueError("repeat timestamps or vocabulary differ")
    for feature, usable in zip(features, informative):
        if np.count_nonzero(usable) < 2:
            raise NoRepeatMatch("repeat audio contains no discriminating phone evidence")
        directions = feature[usable] / np.linalg.norm(feature[usable], axis=1, keepdims=True)
        if np.allclose(directions, directions[0], rtol=0, atol=1e-12):
            raise NoRepeatMatch("repeat audio contains no discriminating temporal phone evidence")
    if len(times_a) > 2 * len(times_b) - 1:
        raise NoRepeatMatch("repeat DTW has no complete correspondence")
    cost = cdist(*features, metric="cosine")
    # Blank-only frames have undefined cosine distance. Keep their time positions
    # at neutral cost; matching two silent frames must not count as phone support.
    cost[~informative[0], :] = 1.
    cost[:, ~informative[1]] = 1.
    forward, path = dtw(C=cost, subseq=True, step_sizes_sigma=np.array([[1, 1], [1, 2], [2, 1]]),
                  weights_mul=np.array([1, 1.5, 1.5]))
    # Librosa flips returned columns for a tall supplied C without transposing C.
    if cost.shape[0] > cost.shape[1]:
        path = np.fliplr(path)
    if (path.ndim != 2 or path.shape[1] != 2 or not len(path)
            or not np.issubdtype(path.dtype, np.integer) or np.any(path < 0)
            or np.any(path[:, 0] >= len(times_a)) or np.any(path[:, 1] >= len(times_b))):
        raise ValueError("invalid repeat DTW path")
    backward = np.full(cost.shape, np.inf)
    backward[-1, :] = 0.
    transitions = ((1, 1, 1.), (1, 2, 1.5), (2, 1, 1.5))
    for row in range(len(times_a)-2, -1, -1):
        for dr, dc, weight in transitions:
            if row+dr < len(times_a):
                backward[row, :-dc] = np.minimum(backward[row, :-dc],
                    weight*cost[row+dr, dc:]+backward[row+dr, dc:])
    optimum = float(np.min(forward[-1]))
    # Roundoff only; this is not an acoustic or near-optimal score tolerance.
    tie_error = 64*np.finfo(np.float64).eps*(len(times_a)+len(times_b))*max(1., abs(optimum))
    if not np.isfinite(optimum):
        raise NoRepeatMatch("repeat DTW has no complete correspondence")
    result = []
    for timestamp in stamps:
        index = int(np.argmin(abs(times_a-timestamp)))
        columns = np.flatnonzero(abs(forward[index]+backward[index]-optimum) <= tie_error)
        possible = times_b[columns].tolist()
        # With strictly increasing reference rows, a missing nearest row can only
        # be crossed by (2, 1); its two equidistant nodes map by their median.
        if 0 < index < len(times_a)-1:
            crossing = forward[index-1, :-1] + 1.5*cost[index+1, 1:] + backward[index+1, 1:]
            columns = np.flatnonzero(abs(crossing-optimum) <= tie_error)
            possible.extend(((times_b[columns]+times_b[columns+1])/2).tolist())
        if not possible or min(possible) != max(possible):
            raise NoRepeatMatch("repeat DTW boundary correspondence is ambiguous")
        distance = abs(path[:, 0]-index)
        selected = float(np.median(times_b[path[distance == distance.min(), 1]]))
        if selected != possible[0]:
            raise ValueError("repeat DTW path differs from complete correspondence")
        result.append(round(selected, 3))
    return result


def assess(records: list[dict], plan: dict, base: dict, *, prediction_version: int = PREDICTION_VERSION) -> dict:
    from stemwise_engine.alignment_endpoints import _endpoint_boundary_supported
    result = dict(base)
    if type(prediction_version) is not int or prediction_version != PREDICTION_VERSION:
        raise ValueError("unsupported repeat prediction version")
    if len(records) != 6 or {(r["channel"], r["shiftMs"]) for r in records} != {(c, s) for c in (0, 1) for s in (-5, 0, 5)}:
        return result
    reference, target = plan["reference"], plan["target"]
    reference_families = _reference_candidates(reference)
    if not reference_families or reference_families != reference["familyCandidates"]:
        return result
    count = len(reference["familyCandidates"])
    word_count = len(reference["familyCandidates"][reference["selectedPublisherFamily"]]["words"])
    candidates = target["familyCandidates"]
    direction = _relative_clock(reference_families, candidates)
    source_index, target_index = reference.get('performedIndex'), target.get('performedIndex')
    if (not direction or (source_index is not None and target_index is not None
                          and (target_index-source_index)*direction <= 0)):
        return result
    source_start, source_end = _extent(reference_families)
    boundaries = ("startMs", "endMs")
    predictions = {b: [] for b in boundaries}
    prefix = count * len(boundaries)
    for record in records:
        mapped = record["mappedMs"]
        if len(mapped) != prefix+2*word_count or not np.isfinite(mapped).all():
            return result
        if (direction == 1 and min(mapped) < source_end) or (direction == -1 and max(mapped) > source_start):
            return result
        for i, boundary in enumerate(boundaries):
            predictions[boundary].extend(mapped[i*count:(i+1)*count])
        anchors = mapped[prefix:]
        if any(a > b for a, b in zip(anchors, anchors[1:])):
            return result
        if base["endMs"]["result"] == "supported" and abs(anchors[-1]-base["endMs"]["candidateMs"]) > 400:
            return result
        if base["startMs"]["result"] == "supported" and abs(anchors[0]-base["startMs"]["candidateMs"]) > 300:
            return result
        # Disputed outer edges cannot validate themselves. Interior word edges
        # must still agree with at least two existing model families.
        interior = anchors[1:-1]
        for edge, value in enumerate(interior, start=1):
            word, boundary = edge//2, "startMs" if edge % 2 == 0 else "endMs"
            limit = 300 if boundary == "startMs" else 400
            matches = sum(len(c.get("words", [])) == word_count
                          and _endpoint_boundary_supported({"words": [c['words'][word]]}, family, boundary)
                          and abs(c["words"][word][boundary]-value) <= limit for family, c in candidates.items())
            if matches < 2:
                return result
    # Include half a 20 ms model frame. This range is sensitivity, not a
    # calibrated statistical confidence interval or proof of physical onset.
    for boundary, stamps in predictions.items():
        if base[boundary]["result"] == "supported":
            continue
        low, high = min(stamps)-10, max(stamps)+10
        if high-low > (300 if boundary == "startMs" else 400):
            continue
        eligible = {family: candidate for family, candidate in candidates.items()
                    if _endpoint_boundary_supported(candidate, family, boundary)}
        compatible = sorted({c[boundary] for c in eligible.values() if low <= c[boundary] <= high})
        if len(compatible) != 1:
            continue
        time = compatible[0]
        result[boundary] = {"result": "supported", "candidateMs": time,
            "supportedFamilies": sorted(k for k, c in eligible.items() if c[boundary] == time),
            "reason": "stable-repeat-phonetic-prediction", "predictionRangeMs": [low, high]}
    return result


def _crop_specs(info, plan: dict) -> list[dict]:
    rate, physical_frames = info.samplerate, info.frames
    physical_end = physical_frames*1000/rate
    if info.channels != 2:
        return []
    crops = []
    for role in ("reference", "target"):
        families = plan[role]["familyCandidates"]
        start = min(candidate["startMs"] for candidate in families.values())
        end = max(candidate["endMs"] for candidate in families.values())
        if not 0 <= start < end <= physical_end:
            return []
        origin = max(0, (start//1000-6)*1000)
        finish = min(physical_end, max(origin+14020, end+1020))
        first, last = round(origin*rate/1000), min(physical_frames, round(finish*rate/1000))
        count = resampled_sample_count(last-first, rate, 16000)
        if count < 720:
            return []
        crops.append({"role": role, "sourceStartFrame": first, "sourceEndFrame": last,
            "sourceSampleRate": rate, "sampleRate": 16000, "sampleCount": count,
            "originMs": first*1000/rate, "atStart": first == 0, "atEnd": last == physical_frames,
            "lowMs": max(0, start-2000), "highMs": min(physical_end, end+750)})
    return crops


def _emission_window(crop: dict, shift: int) -> tuple[int, int]:
    begin = max(0, (0 if crop["atStart"] else 160)+shift*16)
    finish = crop["sampleCount"] if crop["atEnd"] else crop["sampleCount"]-320+begin
    return begin, finish


def infer(audio: Path, model_path: str, plan: dict, *, prediction_version: int = PREDICTION_VERSION,
          model_cache: dict | None = None, model_identity: dict | None = None) -> list[dict]:
    if type(prediction_version) is not int or prediction_version != PREDICTION_VERSION:
        raise ValueError('unsupported repeat prediction version')
    import soundfile as sf
    from scipy.signal import resample_poly
    data = []
    with sf.SoundFile(audio) as source:
        physical_end = len(source)*1000/source.samplerate
        crops = _crop_specs(source, plan)
        if len(crops) != 2:
            return []
        for crop in crops:
            first, last, rate = crop['sourceStartFrame'], crop['sourceEndFrame'], crop['sourceSampleRate']
            CURRENT_BUDGET.require(retained_bytes=(last-first)*source.channels*4
                + crop['sampleCount']*source.channels*4*3 + sum(samples.nbytes for samples, _ in data))
            source.seek(first)
            samples = resample_poly(source.read(last-first, dtype='float32', always_2d=True), 16000, rate, axis=0)
            if not np.isfinite(samples).all():
                raise ValueError('repeat audio contains nonfinite samples')
            if len(samples) != crop['sampleCount']:
                raise ValueError('repeat resampling differs from its planned sample count')
            data.append((samples, crop))
    import torch
    from transformers import AutoModelForCTC, Wav2Vec2FeatureExtractor
    from stemwise_engine.endpoint_ctc_evidence import _bounded_emissions
    torch.set_num_threads(6)
    key = (str(Path(model_path).resolve()), json.dumps(model_identity, sort_keys=True))
    cached = model_cache.get(key) if model_cache is not None else None
    if cached is None:
        if model_cache:
            model_cache.clear()
            gc.collect()
            torch.cuda.empty_cache()
        extractor = Wav2Vec2FeatureExtractor.from_pretrained(model_path, local_files_only=True)
        model = AutoModelForCTC.from_pretrained(model_path, local_files_only=True, torch_dtype=torch.float32).to("cuda").eval()
        if model_cache is not None:
            model_cache[key] = extractor, model
    else:
        extractor, model = cached
    stride, receptive = 1, 1
    for kernel, step in zip(model.config.conv_kernel, model.config.conv_stride):
        receptive += (kernel-1)*stride
        stride *= step
    if stride != 320 or extractor.sampling_rate != 16000:
        raise ValueError("repeat predictor expects 20 ms acoustic frames")
    frame_origin = ((receptive-1)/2-stride/2)/16
    reference = plan["reference"]
    families = reference["familyCandidates"]
    stamps = [c["startMs"] for c in families.values()]
    stamps.extend(c["endMs"] for c in families.values())
    stamps.extend(t for w in families[reference["selectedPublisherFamily"]]["words"] for t in (w["startMs"], w["endMs"]))
    records = []
    for channel in (0, 1):
        for shift in (-5, 0, 5):
            emissions = []
            for samples, crop in data:
                begin, finish = _emission_window(crop, shift)
                if finish-begin < receptive+stride:
                    return []
                wave = torch.from_numpy(samples[begin:finish, channel].astype(np.float32))[None]
                with torch.inference_mode():
                    logp = _bounded_emissions(wave, extractor, model, torch.device("cuda"), torch.float32, stride).numpy()
                times = crop['originMs']+begin/16+frame_origin+np.arange(len(logp))*20
                selected = (times >= crop['lowMs']) & (times <= crop['highMs'])
                if np.count_nonzero(selected) < 2:
                    return []
                emissions.append((logp[selected], times[selected]))
            bounds = (0 if data[0][1]['atStart'] else emissions[0][1][0],
                      physical_end if data[0][1]['atEnd'] else emissions[0][1][-1])
            mapped = transfer(*emissions[0], *emissions[1], model.config.pad_token_id, stamps,
                reference_bounds=bounds)
            records.append({"channel": channel, "shiftMs": shift, "mappedMs": mapped})
    return records


def infer_many(audio: Path, contexts: list[dict], timeout_seconds: int, *, prediction_version: int = PREDICTION_VERSION,
               checkpoint_dir: Path | None = None) -> list[dict]:
    if type(prediction_version) is not int or prediction_version != PREDICTION_VERSION:
        raise ValueError('unsupported repeat prediction version')
    if (type(timeout_seconds) is not int or timeout_seconds <= 0 or not isinstance(contexts, list) or not contexts
            or any(not isinstance(c, dict) or set(c) != {'index', 'modelPath', 'model', 'plan'}
                or type(c['index']) is not int or c['index'] < 0 or not isinstance(c['modelPath'], str)
                or not isinstance(c['model'], dict) or not isinstance(c['plan'], dict) for c in contexts)
            or len({c['index'] for c in contexts}) != len(contexts)):
        raise ValueError('Repeat batch needs distinct contexts and a positive deadline')
    groups = {}
    for context in contexts:
        key = (str(Path(context['modelPath']).resolve()), json_bytes(context['model']))
        groups.setdefault(key, []).append(context)
    checkpoint_identity = None
    if checkpoint_dir is not None:
        from importlib.metadata import version
        from stemwise_engine.worker_runtime_probe import torch_runtime_identity
        from stemwise_engine.provenance import endpoint_producer_identity, semantic_source_identity
        checkpoint_dir = Path(checkpoint_dir)
        checkpoint_identity = {'audioSha256': sha256_file(audio), 'predictionVersion': prediction_version,
            'runtime': {'torch': torch_runtime_identity(), 'transformers': version('transformers'),
                        'scipy': version('scipy')},
            'producer': endpoint_producer_identity('ctc'), 'repeatSourceSha256': semantic_source_identity(Path(__file__))}
    cache, results = {}, {}
    def expired(*_):
        raise TimeoutError('Repeat context exceeded its deadline')
    previous_handler = signal.signal(signal.SIGALRM, expired)
    try:
        for group in groups.values():
            for context in group:
                if checkpoint_identity is not None:
                    binding = {**checkpoint_identity, 'context': context}
                    path = validate_generated_path(checkpoint_dir, checkpoint_dir / f"context-{context['index']:03d}.private.json")
                    try:
                        saved = read_bounded_json(path, max_bytes=16*1024*1024, root=checkpoint_dir)
                        if (set(saved) == {'binding', 'records'} and saved['binding'] == binding
                                and isinstance(saved['records'], list)):
                            results[context['index']] = saved['records']
                            continue
                    except (OSError, ValueError, KeyError, TypeError):
                        pass
                signal.setitimer(signal.ITIMER_REAL, timeout_seconds)
                try:
                    results[context['index']] = infer(audio, context['modelPath'], context['plan'],
                        prediction_version=prediction_version, model_cache=cache, model_identity=context['model'])
                except NoRepeatMatch:
                    results[context['index']] = []
                signal.setitimer(signal.ITIMER_REAL, 0)
                if checkpoint_identity is not None:
                    if sha256_file(audio) != checkpoint_identity['audioSha256']:
                        raise ValueError('Repeat source audio changed during inference')
                    checkpoint_dir.mkdir(parents=True, exist_ok=True)
                    atomic_write_bytes(path, json_bytes({'binding': binding, 'records': results[context['index']]}),
                        root=checkpoint_dir)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)
        cache.clear()
        gc.collect()
    return [{'index': c['index'], 'records': results[c['index']]} for c in contexts]


def _directory(attempt: Path) -> Path:
    return validate_generated_path(attempt.parent.parent, attempt.parent.parent / "endpoint-repeat-evidence" / attempt.name)


def _summary(binding: dict, manifest: dict) -> dict:
    entries = [{"occurrenceId": e["occurrenceId"], "assessment": e["assessment"]} for e in manifest["occurrences"]]
    return {**binding, "reviewRequired": not all(a["result"] == "supported" for e in entries for a in e["assessment"].values()),
            "occurrences": entries}


def _read_packet(attempt, binding):
    directory = _directory(attempt)
    path = directory / "manifest.json"
    if (not isinstance(binding, dict) or set(binding) != {"method", "manifestSha256"}
            or binding["method"] != METHOD or sha256_file(path) != binding["manifestSha256"]):
        raise ValueError("repeat prediction binding is stale")
    manifest = read_bounded_json(path, max_bytes=16*1024*1024, root=directory)
    if (manifest.get("method") != METHOD or type(manifest.get('predictionVersion')) is not int
            or manifest['predictionVersion'] != PREDICTION_VERSION):
        raise ValueError("invalid repeat prediction method")
    return manifest


def verify_stored_packet(attempt: Path, binding: dict) -> dict:
    from stemwise_engine.endpoint_ctc_evidence import verify_stored_packet as verify_ctc
    manifest = _read_packet(attempt, binding)
    base = verify_ctc(attempt, manifest["ctcEvidence"])
    if len(manifest["occurrences"]) != len(base["occurrences"]):
        raise ValueError("incomplete repeat predictions")
    for entry, original in zip(manifest["occurrences"], base["occurrences"]):
        expected = assess(entry["records"], entry["plan"], original["assessment"], prediction_version=manifest.get("predictionVersion")) if entry["plan"] else original["assessment"]
        if entry["occurrenceId"] != original["occurrenceId"] or entry["assessment"] != expected:
            raise ValueError("repeat prediction differs from acoustic results")
        if expected != original["assessment"] and (type(manifest.get('templatePolicyVersion')) is not int
                or manifest['templatePolicyVersion'] != TEMPLATE_POLICY_VERSION):
            raise ValueError('repeat prediction requires current template qualification')
    return manifest


def _reviewed_readings(declaration, mapping, evidence, lyrics, canonical):
    from stemwise_engine.endpoint_ctc_evidence import require_endpoint_content
    from stemwise_engine.lyrics_alignment import _plain_original
    from stemwise_engine.spoken_targets import load

    root = canonical.parent.parent / 'jobs' / declaration['videoId']
    admission = require_endpoint_content(declaration, mapping, evidence, lyrics, root)
    if admission.spoken_targets is None:
        return None
    plan = load(root.parents[2]/'jobs'/declaration['videoId'], declaration['videoId'], _plain_original(lyrics),
                admission.source_physical_indices, admission.audio_sha256)
    if plan is None or plan.reference != admission.spoken_targets:
        raise LyricsContentBlocker('pronunciation-support-unavailable')
    return plan


def verify_packet(attempt: Path, declaration: dict, declaration_sha: str, audio: Path,
                  binding: dict, mapping: dict, evidence: dict, canonical: Path) -> dict:
    from stemwise_engine.endpoint_ctc_evidence import verify_packet as verify_ctc
    manifest = verify_stored_packet(attempt, binding)
    verify_ctc(attempt, declaration, declaration_sha, audio, manifest["ctcEvidence"])
    if sha256_file(canonical) != declaration["canonicalPlainSha256"]:
        raise ValueError("repeat authority is stale")
    lyrics = parse_worker_json(read_bounded_regular_bytes(canonical, max_bytes=16*1024*1024, root=canonical.parent))
    spoken = _reviewed_readings(declaration, mapping, evidence, lyrics, canonical)
    for entry, occurrence in zip(manifest["occurrences"], declaration["occurrences"]):
        if entry["plan"] != reference_plan(occurrence, mapping, evidence, lyrics, spoken=spoken):
            raise ValueError("repeat reference differs from approved occurrences")
    return manifest


def write_packet(attempt: Path, declaration: dict, declaration_sha: str, audio: Path, config: dict,
                 mapping: dict, evidence: dict, canonical: Path, ctc: dict) -> dict:
    from stemwise_engine.endpoint_ctc_evidence import _directory as ctc_directory, _invoke, verify_stored_packet as verify_ctc
    if sha256_file(canonical) != declaration["canonicalPlainSha256"]:
        raise ValueError("repeat authority is stale")
    lyrics = parse_worker_json(read_bounded_regular_bytes(canonical, max_bytes=16*1024*1024, root=canonical.parent))
    spoken = _reviewed_readings(declaration, mapping, evidence, lyrics, canonical)
    binding = {k: ctc[k] for k in ("method", "manifestSha256")}
    directory = _directory(attempt)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "manifest.json"
    retained = {}
    if path.exists():
        saved = {"method": METHOD, "manifestSha256": sha256_file(path)}
        try:
            manifest = verify_packet(attempt, declaration, declaration_sha, audio, saved, mapping, evidence, canonical)
            if manifest["ctcEvidence"] == binding and manifest.get("predictionVersion") == PREDICTION_VERSION:
                return _summary(saved, manifest)
        except LyricsContentBlocker:
            raise
        except (ValueError, KeyError, TypeError):
            # Requalify numerical records on the current declaration and template.
            # Source selection and boundary assessment below never use the old decision.
            try:
                old = _read_packet(attempt, saved)
                if old['ctcEvidence'] == binding and 'templatePolicyVersion' not in old:
                    retained = {e['occurrenceId']: e for e in old['occurrences']}
                    if len(retained) != len(old['occurrences']):
                        retained = {}
            except (ValueError, KeyError, TypeError):
                retained = {}
    if sha256_file(canonical) != declaration["canonicalPlainSha256"]:
        raise ValueError("repeat authority is stale")
    lyrics = parse_worker_json(read_bounded_regular_bytes(canonical, max_bytes=16*1024*1024, root=canonical.parent))
    base_manifest = verify_ctc(attempt, binding)
    if retained:
        from stemwise_engine.endpoint_ctc_evidence import verify_packet as verify_current_ctc
        verify_current_ctc(attempt, declaration, declaration_sha, audio, binding)
    if (len(declaration['occurrences']) != len(ctc['occurrences'])
            or len(base_manifest['occurrences']) != len(declaration['occurrences'])):
        raise ValueError('repeat context count differs from the declaration or bound CTC packet')
    entries, pending = [], []
    project = Path(config["projectDir"])
    for index, (occurrence, base) in enumerate(zip(declaration["occurrences"], ctc["occurrences"])):
        plan = reference_plan(occurrence, mapping, evidence, lyrics, spoken=spoken)
        records = []
        if (plan and "inference" in base_manifest["occurrences"][index]
                and any(v["result"] != "supported" for v in base["assessment"].values())):
            cached = retained.get(occurrence['occurrenceId'])
            if cached and cached['plan'] == plan and isinstance(cached.get('records'),list):
                records = cached['records']
            else:
                scratch = ctc_directory(attempt) / f"context-{index:03d}"
                pending.append((index, scratch, plan))
        entries.append({"occurrenceId": occurrence["occurrenceId"], "plan": plan, "records": records,
                        "assessment": assess(records, plan, base["assessment"]) if plan else base["assessment"]})
    if pending:
        timeout = int(config['analysisTimeoutMinutes']) * 60
        _invoke(project, 'prepare-many', [s for _, s, _ in pending], timeout, alignment=True)
        contexts = []
        for index, scratch, plan in pending:
            prepared = read_bounded_json(scratch / "prepared.json", max_bytes=1024*1024, root=scratch)
            if prepared["model"] != base_manifest["occurrences"][index]["inference"]["model"]:
                raise ValueError("repeat model differs from the bound CTC model")
            contexts.append({'index': index, 'modelPath': prepared['modelPath'], 'model': prepared['model'], 'plan': plan})
        request = directory / 'repeat-request.private.json'
        atomic_write_bytes(request, json_bytes({'audio': str(audio), 'contexts': contexts,
            'predictionVersion': PREDICTION_VERSION, 'timeoutSeconds': timeout}), root=directory)
        output = directory / 'repeat-inference.json'
        error = directory / 'repeat-worker-error.private.txt'
        output.unlink(missing_ok=True)
        with heavy_worker_stage(config), environment_lease('main', builder=project):
            outcome = run_process_tree([str(environment_python('main', project)), '-m', 'stemwise_engine.endpoint_repeat_alignment', str(request)],
                capture_output=True, text=True, check=False, timeout=timeout * len(pending),
                env={**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1'})
        require_worker_success(outcome, code='repeat-ctc-worker-failed', diagnostic_path=error)
        result = read_bounded_json(output, max_bytes=16*1024*1024, root=directory)
        if (not isinstance(result, dict) or set(result) != {'requestSha256', 'contexts'}
                or result['requestSha256'] != sha256_file(request) or not isinstance(result['contexts'], list)
                or any(not isinstance(r, dict) or set(r) != {'index', 'records'} or type(r['index']) is not int
                       or not isinstance(r['records'], list) for r in result['contexts'])
                or [r['index'] for r in result['contexts']] != [c['index'] for c in contexts]):
            raise ValueError('repeat batch does not contain every current context')
        for row in result['contexts']:
            entry = entries[row['index']]
            entry['records'] = row['records']
            entry['assessment'] = assess(row['records'], entry['plan'], ctc['occurrences'][row['index']]['assessment'],
                                         prediction_version=PREDICTION_VERSION)
        error.unlink(missing_ok=True)
    manifest = {"method": METHOD, "predictionVersion": PREDICTION_VERSION, "templatePolicyVersion": TEMPLATE_POLICY_VERSION,
                "ctcEvidence": binding, "occurrences": entries}
    atomic_write_bytes(path, json_bytes(manifest), root=directory)
    saved = {"method": METHOD, "manifestSha256": sha256_file(path)}
    verify_packet(attempt, declaration, declaration_sha, audio, saved, mapping, evidence, canonical)
    return _summary(saved, manifest)


def run_request(request: Path) -> None:
    data = read_bounded_json(request, max_bytes=16*1024*1024, root=request.parent)
    if set(data) != {'audio', 'contexts', 'timeoutSeconds', 'predictionVersion'}:
        raise ValueError('repeat worker requires the current context batch')
    records = {'requestSha256': sha256_file(request), 'contexts': infer_many(Path(data['audio']),
        data['contexts'], data['timeoutSeconds'], prediction_version=data['predictionVersion'],
        checkpoint_dir=request.parent/'completed-contexts')}
    atomic_write_bytes(request.with_name('repeat-inference.json'), json_bytes(records), root=request.parent)


if __name__ == "__main__":
    raise SystemExit(run_worker_main(lambda: run_request(Path(sys.argv[1]))))
