"""Native multilingual CTC recovery using the already-installed OWSM model."""
from __future__ import annotations

from contextlib import redirect_stdout
import json
import math
import os
from pathlib import Path
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success, run_worker_main
from stemwise_engine.endpoint_planning import qualified_context_bounds
import sys
import unicodedata

import numpy as np
import soundfile as sf

from stemwise_engine.common import atomic_write_bytes, json_bytes, read_bounded_json, run_process_tree, sha256_file, validate_generated_path, heavy_worker_stage
import stemwise_engine.endpoint_ctc_evidence as ctc
import stemwise_engine.endpoint_context_cache as context_cache
from stemwise_engine.endpoint_candidate_prediction import _preserve_supported
from stemwise_engine.endpoint_vocal_evidence import ROUTES, _vocal_samples
import stemwise_engine.owsm_ctc_forced_alignment as worker

from stemwise_engine.provenance import endpoint_producer_identity

METHOD = "local-multilingual-native-ctc-v1"
FRAME_MS = 26000 / 324  # Pinned worker: one 26-second chunk produces 324 frames.
MODEL = {"repository": "espnet/owsm_ctc_v4_1B", "revision": worker.MODEL_REVISION,
         "sha256": worker.CHECKPOINT_SHA256, "sourceRevision": worker.ESPNET_SOURCE_REVISION,
         "targetKind": "bpe", "frameStepMs": FRAME_MS}
MODEL['producer'] = endpoint_producer_identity('owsm')
PREDICTION_METHOD = "local-multilingual-owsm-prediction-v8"
PREDICTION_FAMILY = "owsm-windowed"
FUSION = "all-vocals-mean-probability-v1"
FUSION_ROUTE = "owsm-vocal-ensemble"
PREDICTION_FRAME_MS = 80.
PREDICTION_CONTEXT_SECONDS = 3
PREDICTION_MODEL = {**MODEL, "decoder": "complete-ctc-declared-word-language-posterior-medians-v4", "fusion": FUSION,
                    "representationSelection": "contrastive-declared-word-language-v2",
                    "contextSeconds": PREDICTION_CONTEXT_SECONDS, "chunkMs": 24000, "frameStepMs": PREDICTION_FRAME_MS}
UNCONDITIONED_PREDICTION_METHOD = "local-multilingual-owsm-unconditioned-prediction-v1"
UNCONDITIONED_PREDICTION_MODEL = {**PREDICTION_MODEL, "conditioning": "<nolang>",
    "decoder": "complete-ctc-unconditioned-posterior-medians-v1",
    "representationSelection": "contrastive-order-control-v1"}
VIEWS = {(route, shift): gain for route in ROUTES for shift, gain in ((-5, .9), (0, 1.), (5, 1.1))}


def numerical_model(template=None):
    return {**(MODEL if template is None else template),
        'cudaDevice': worker.cuda_device_identity('owsm_ctc_forced_alignment')}


def validate_recorded_model(value, template=None):
    template = MODEL if template is None else template
    if not isinstance(value, dict) or {key: item for key, item in value.items() if key != 'cudaDevice'} != template:
        raise ValueError('multilingual CTC model identity changed')
    worker.validate_cuda_device(value.get('cudaDevice'), 'owsm_ctc_forced_alignment')
    return value


def _routes_for_context(plan, components):
    from stemwise_engine.lyrics_alignment import canonical_language
    language = plan['language'].split('-', 1)[0].lower()
    components = {canonical_language(value) for value in components}
    words = [word for line in plan['request']['lines'] for word in line['words']]
    if any('acousticLanguage' in word for word in words):
        owners = [canonical_language(word.get('acousticLanguage', 'und')) for word in words]
        declared = components if language == 'mul' else {language}
        if not words or not set(owners) <= declared & set(worker.ROUTES_BY_LANGUAGE):
            return []
        return sorted({canonical_language(word['acousticLanguage'])
            for word in plan['request']['lines'][plan['targetLine']]['words']})
    if language in worker.ROUTES_BY_LANGUAGE:
        return [language]
    if language != "mul" or not components <= set(worker.ROUTES_BY_LANGUAGE):
        return []
    scripts = {"ar": "ARABIC", "en": "LATIN", "zu": "LATIN", "tr": "LATIN"}
    if len({scripts[c] for c in components}) != len(components):
        return []
    by_script = {scripts[c]: c for c in components}
    bound = []
    for line in plan["request"]["lines"]:
        languages = set()
        for word in line["words"]:
            letters = {unicodedata.name(c, "").split(" ", 1)[0]
                       for c in word["acousticText"] if unicodedata.category(c).startswith("L")}
            if len(letters) != 1 or not letters <= set(by_script):
                return []
            languages.add(by_script[next(iter(letters))])
        bound.append(languages)
    # Conditioning follows the target's declared script, never the best result.
    # A mixed-script target must agree under both declared conditioning routes.
    return sorted(bound[plan["targetLine"]])


def assess(records, families):
    """Native frame uncertainty and two other families are mandatory."""
    from stemwise_engine.alignment_endpoints import _endpoint_boundary_supported
    result = ctc._assess([], families)
    if len(records) != 9 or {(r.get("route"), r.get("shiftMs")) for r in records} != set(VIEWS):
        return result
    finite = lambda v: type(v) in (int, float) and math.isfinite(v)
    for boundary, budget in (("startMs", 300), ("endMs", 400)):
        if not _endpoint_boundary_supported({}, "owsm-ctc", boundary):
            continue
        times = sorted({c[boundary] for c in families.values()})
        compatible, peaks = set(times), []
        for record in records:
            d = record.get("decisions", {}).get(boundary, {})
            origin, peak, mass = record.get("originMs"), d.get("peakMs"), d.get("peakMass")
            if (record.get("gain") != VIEWS[record["route"], record["shiftMs"]]
                    or record.get("stepMs") != FRAME_MS or d.get("frameStepMs") != FRAME_MS
                    or not all(finite(v) for v in (origin, peak, mass)) or not .5 <= mass <= 1):
                compatible.clear()
                break
            frame = round((peak-origin)/FRAME_MS)
            scores = d.get("candidateScores", [])
            if (frame < 0 or abs(origin+frame*FRAME_MS-peak) > .001
                    or [s.get("candidateMs") for s in scores] != times):
                compatible.clear()
                break
            supported = set()
            for score in scores:
                edge = math.floor((score["candidateMs"]-origin)/FRAME_MS+.5)
                value = score.get("mass")
                if score.get("frameEdge") != edge or not finite(value) or not 0 <= value <= 1:
                    compatible.clear()
                    break
                if edge == frame:
                    if abs(value-mass) > 1e-7:
                        compatible.clear()
                        break
                    supported.add(score["candidateMs"])
                elif mass < 3*value:
                    compatible.clear()
                    break
            compatible &= supported
            peaks.append(peak)
        if len(compatible) != 1 or len(peaks) != 9 or max(peaks)-min(peaks) > FRAME_MS+10:
            continue
        chosen = next(iter(compatible))
        low, high = min(peaks)-FRAME_MS/2, max(peaks)+FRAME_MS/2
        if any([t for t in times if abs(t-p) == min(abs(v-p) for v in times)] != [chosen]
               for p in (low, high)):
            continue
        supported = sorted(k for k, c in families.items() if c[boundary] == chosen
                           and _endpoint_boundary_supported(c, k, boundary))
        if not supported or sum(k != "owsm-ctc" and _endpoint_boundary_supported(c, k, boundary)
               and max(abs(c[boundary]-low), abs(c[boundary]-high)) <= budget
               for k, c in families.items()) < 2:
            continue
        result[boundary] = {"result": "supported", "candidateMs": chosen,
            "supportedFamilies": supported,
            "reason": "stable-multilingual-native-ctc", "predictionRangeMs": [low, high]}
    return result


def _assessment(inference, families, previous):
    results = [assess(row["records"], families) for row in inference["conditionings"].values()]
    result = ctc._assess([], families)
    for boundary in result:
        if results and all(r[boundary]["result"] == "supported" for r in results) and len({r[boundary]["candidateMs"] for r in results}) == 1:
            result[boundary] = results[0][boundary]
    return _preserve_supported(previous, result)


def _directory(attempt):
    return validate_generated_path(attempt.parent.parent, attempt.parent.parent / "endpoint-owsm-ctc" / attempt.name)


def _parent(attempt, binding, declaration, declaration_sha, audio=None):
    import stemwise_engine.endpoint_candidate_prediction as prediction
    import stemwise_engine.endpoint_repeat_alignment as repeat
    import stemwise_engine.endpoint_native_ctc as native
    providers = {p.METHOD: p for p in (ctc, prediction, repeat, native)}
    if not isinstance(binding, dict) or binding.get("method") not in providers:
        raise ValueError("multilingual CTC parent method is unsupported")
    packet = providers[binding["method"]].verify_stored_packet(attempt, binding)
    authority = packet
    if binding["method"] == repeat.METHOD:
        authority = ctc.verify_packet(attempt, declaration, declaration_sha, audio, packet["ctcEvidence"])
    elif binding["method"] in (ctc.METHOD, prediction.METHOD):
        providers[binding["method"]].verify_packet(attempt, declaration, declaration_sha, audio, binding)
    if (any(authority.get(k) != declaration[k] for k in ("analysisAudio", "canonicalPlainSha256"))
            or authority.get("declarationSha256") != declaration_sha
            or [e["occurrenceId"] for e in packet["occurrences"]] != [e["occurrenceId"] for e in declaration["occurrences"]]):
        raise ValueError("multilingual CTC parent authority changed")
    return packet


def _plans(declaration, canonical, mapping, evidence):
    from stemwise_engine.lyrics_alignment import _plain_original
    if sha256_file(canonical) != declaration["canonicalPlainSha256"]:
        raise ValueError("multilingual CTC approved text changed")
    lyrics = read_bounded_json(canonical, max_bytes=16*1024*1024, root=canonical.parent)
    plans = ctc._plan(declaration, mapping, evidence, lyrics,
        evidence_root=canonical.parent.parent / "jobs" / declaration["videoId"])
    components = _plain_original(lyrics).get("componentLanguages", [])
    reasons = {e["occurrenceId"]: e["publishDecision"].get("code") for e in evidence["occurrenceCandidates"]}
    allowed = {"line-start-disagreement", "line-end-disagreement", "low-forced-line-support",
               "missing-unprompted-faster-whisper-boundary"}
    return plans, [_routes_for_context(p, components) if reasons[p["occurrenceId"]] in allowed else [] for p in plans]


def _contexts(attempt, plans, languages, audio, config, *, prepare=True):
    from stemwise_engine.endpoint_vocal_evidence import _separate_many
    contexts, pending = [], []
    for index, (plan, routes) in enumerate(zip(plans, languages)):
        if not routes:
            continue
        scratch = ctc._directory(attempt) / f"context-{index:03d}"
        if not (scratch / "prepared.json").exists():
            scratch = _directory(attempt) / f"context-{index:03d}"
            if not prepare and not (scratch / "prepared.json").exists():
                raise ValueError("multilingual CTC retained context is incomplete")
            scratch.mkdir(parents=True, exist_ok=True)
            ctc._write_context_request(scratch, plan)
            with sf.SoundFile(audio) as source:
                if source.samplerate != 48000:
                    raise ValueError("multilingual CTC needs canonical PCM")
                source.seek(plan["startMs"]*48)
                samples = source.read((plan["endMs"]-plan["startMs"])*48, dtype="float32", always_2d=True)
            from stemwise_engine.source_crop import ensure_source_crop
            ensure_source_crop(scratch / 'source.wav', samples, 48000, soundfile=sf, numpy=np)
        if read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch) != plan:
            raise ValueError("multilingual CTC retained request changed")
        retained, rate = sf.read(scratch / "source.wav", dtype="float32", always_2d=True)
        with sf.SoundFile(audio) as source:
            # Millisecond endpoints may include the recording's fractional final millisecond.
            if source.samplerate != 48000 or plan["endMs"] > (source.frames + 47) // 48:
                raise ValueError("multilingual CTC retained crop differs from the recording")
            source.seek(plan["startMs"]*48)
            expected = source.read((plan["endMs"]-plan["startMs"])*48, dtype="float32", always_2d=True)
        if rate != 48000 or not len(retained) or not np.array_equal(retained, expected):
            raise ValueError("multilingual CTC retained crop differs from the recording")
        if prepare or not (scratch / "prepared.json").exists():
            pending.append(scratch)
        contexts.append({"index": index, "path": str(scratch), "languages": routes})
    if pending:
        separated = _separate_many(config, [(s / 'source.wav', s) for s in pending])
        if len(separated) != len(pending) or any(set(v) != set(ctc.ROUTES) for v in separated):
            raise ValueError('multilingual CTC separation is incomplete')
        for scratch, outputs in zip(pending, separated):
            prepared_path = scratch / 'prepared.json'
            prepared = read_bounded_json(prepared_path, max_bytes=1024*1024, root=scratch) if prepared_path.exists() else {}
            prepared['views'] = {r: {'path': str(p.relative_to(scratch)), 'provenance': v}
                                 for r, (p, v) in outputs.items()}
            atomic_write_bytes(prepared_path, json_bytes(prepared), root=scratch)
    for context in contexts:
        scratch = Path(context['path'])
        context_cache.context_binding(scratch, MODEL)
        context['inputs'] = {n: sha256_file(scratch / n) for n in ('request.private.json', 'prepared.json', 'source.wav')}
    return contexts


def _infer(project, contexts, timeout, *, replay=False):
    if not contexts:
        return {}
    model = numerical_model()
    saved = {str(c['index']): context_cache.read_context_result(Path(c['path']), model) for c in contexts} if not replay else {}
    if any(value is not None and (not isinstance(value, dict) or value.get('model') != model) for value in saved.values()):
        raise ValueError('multilingual CTC retained result used a different model or device')
    pending = [c for c in contexts if saved.get(str(c['index'])) is None]
    if not pending:
        return saved
    runtime = project.parent / "runtime/espnet-202511-owsm"
    with heavy_worker_stage({'projectDir': str(project)}):
        result = run_process_tree([str(runtime / ".venv/bin/python"), '-m', 'stemwise_engine.endpoint_owsm_ctc', str(project), json.dumps(pending),
                                  ],
            capture_output=True, text=True, check=False, timeout=timeout,
            env={**os.environ, "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1", "TOKENIZERS_PARALLELISM": "false"})
    require_worker_success(result, code='owsm-ctc-worker-failed',
        diagnostic_path=Path(contexts[0]['path']).parent/'owsm-ctc-worker.failure.private.txt')
    if len(result.stdout.encode()) > 16*1024*1024:
        raise ValueError("multilingual CTC output is too large")
    inferred = parse_worker_json(result.stdout)
    if not isinstance(inferred, dict) or set(inferred) != {str(c['index']) for c in pending}:
        raise ValueError('multilingual CTC context result count changed')
    if any(not isinstance(value, dict) or value.get('model') != model for value in inferred.values()):
        raise ValueError('multilingual CTC worker used a different model or device')
    return {**{k: v for k, v in saved.items() if v is not None}, **inferred}




def verify_stored_packet(attempt, binding):
    path = _directory(attempt) / "manifest.json"
    if set(binding) != {"method", "manifestSha256"} or binding["method"] != METHOD or sha256_file(path) != binding["manifestSha256"]:
        raise ValueError("multilingual CTC binding changed")
    packet = read_bounded_json(path, max_bytes=16*1024*1024, root=path.parent)
    if packet.get("method") != METHOD or packet.get("contextVersion") != ctc.SCORER_VERSION:
        raise ValueError("multilingual CTC model or context version changed")
    model = validate_recorded_model(packet.get('model'))
    prior = _parent(attempt, packet["previousEvidence"], packet, packet["declarationSha256"])
    for entry, previous in zip(packet["occurrences"], prior["occurrences"]):
        inference = entry.get("inference")
        if inference is not None and (inference.get("model") != model or set(inference["conditionings"]) != set(entry["languages"])):
            raise ValueError("multilingual CTC conditioning changed")
        expected = _assessment(inference, entry["candidateFamilies"], previous["assessment"]) if inference is not None else previous["assessment"]
        if entry["assessment"] != expected:
            raise ValueError("multilingual CTC assessment differs from acoustic masses")
    return packet


def write_packet(attempt, declaration, declaration_sha, audio, config, mapping, evidence, canonical, previous):
    plans, languages = _plans(declaration, canonical, mapping, evidence)
    binding = {k: previous[k] for k in ("method", "manifestSha256")}
    prior = _parent(attempt, binding, declaration, declaration_sha, audio)
    # Qualified boundaries never move; unsupported contexts do not suppress others.
    languages = [routes if any(v["result"] != "supported" for v in p["assessment"].values()) else []
                 for routes, p in zip(languages, prior["occurrences"])]
    if not any(languages):
        return previous
    if sha256_file(audio) != declaration["analysisAudio"]["sha256"]:
        raise ValueError("multilingual CTC recording changed")
    path = _directory(attempt) / "manifest.json"
    contexts = _contexts(attempt, plans, languages, audio, config)
    model = numerical_model()
    authority = {"method": METHOD, "model": model, "contextVersion": ctc.SCORER_VERSION,
        "declarationSha256": declaration_sha, "analysisAudio": declaration["analysisAudio"],
        "canonicalPlainSha256": declaration["canonicalPlainSha256"], "previousEvidence": binding, "contexts": contexts}
    if path.exists():
        packet = verify_stored_packet(attempt, {"method": METHOD, "manifestSha256": sha256_file(path)})
        if any(packet.get(k) != v for k, v in authority.items()):
            raise ValueError("multilingual CTC retained authority changed")
    else:
        inferred = _infer(Path(config["projectDir"]), contexts, int(config["analysisTimeoutMinutes"])*60)
        if set(inferred) != {str(c["index"]) for c in contexts}:
            raise ValueError("multilingual CTC occurrence count changed")
        entries = []
        for index, (plan, routes, parent) in enumerate(zip(plans, languages, prior["occurrences"])):
            inference = inferred.get(str(index))
            if inference is not None and (inference.get("model") != model or set(inference.get("conditionings", {})) != set(routes)):
                raise ValueError("multilingual CTC worker used the wrong model or conditioning")
            entry = {"occurrenceId": plan["occurrenceId"], "candidateFamilies": plan["candidateFamilies"], "languages": routes,
                     "assessment": _assessment(inference, plan["candidateFamilies"], parent["assessment"]) if inference is not None else parent["assessment"]}
            if inference is not None:
                entry["inference"] = inference
            entries.append(entry)
        packet = {**authority, "occurrences": entries}
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_bytes(path, json_bytes(packet), root=path.parent)
        verify_stored_packet(attempt, {"method": METHOD, "manifestSha256": sha256_file(path)})
    summary = {"method": METHOD, "manifestSha256": sha256_file(path), "occurrences": [
        {"occurrenceId": e["occurrenceId"], "assessment": e["assessment"]} for e in packet["occurrences"]],
        "reviewRequired": not all(a["result"] == "supported" for e in packet["occurrences"] for a in e["assessment"].values())}
    return summary


def verify_packet(attempt, declaration, declaration_sha, audio, binding, config, mapping, evidence, canonical, *, replay=False):
    packet = verify_stored_packet(attempt, binding)
    plans, languages = _plans(declaration, canonical, mapping, evidence)
    prior = _parent(attempt, packet["previousEvidence"], declaration, declaration_sha, audio)
    languages = [r if any(v["result"] != "supported" for v in p["assessment"].values()) else []
                 for r, p in zip(languages, prior["occurrences"])]
    contexts = _contexts(attempt, plans, languages, audio, config, prepare=False)
    if packet["contexts"] != contexts or any(e["candidateFamilies"] != p["candidateFamilies"] or e["languages"] != r
            for e, p, r in zip(packet["occurrences"], plans, languages)):
        raise ValueError("multilingual CTC declaration changed")
    if replay:
        inferred = _infer(Path(config["projectDir"]), contexts, int(config["analysisTimeoutMinutes"])*60, replay=True)
        if set(inferred) != {str(c["index"]) for c in contexts} or any(
                inferred[str(c["index"])].get('model') != packet['model'] or
                _assessment(inferred[str(c["index"])], plans[c["index"]]["candidateFamilies"], prior["occurrences"][c["index"]]["assessment"])
                != packet["occurrences"][c["index"]]["assessment"] for c in contexts):
            raise ValueError("multilingual CTC selection did not reproduce")
    return packet


def _request(project, scratch, plan, language):
    runtime = project.parent / "runtime/espnet-202511-owsm"
    minor = '.'.join(worker.PYTHON_VERSION.split('.')[:2])
    site = runtime / f".venv/lib/python{minor}/site-packages"
    snapshot = project.parent / "model-cache/lyrics-alignment/models--espnet--owsm_ctc_v4_1B/snapshots" / worker.MODEL_REVISION
    run = snapshot / worker.MODEL_RUN_RELATIVE_PATH
    artifact = lambda p, digest, size: {"path": str(p), "sha256": digest, "byteCount": size}
    files = {"checkpoint": (run / worker.CHECKPOINT_NAME, worker.CHECKPOINT_SHA256, worker.CHECKPOINT_BYTE_COUNT),
        "config": (run / worker.CONFIG_NAME, worker.CONFIG_SHA256, worker.CONFIG_BYTE_COUNT),
        "bpe": (snapshot / worker.BPE_RELATIVE_PATH, worker.BPE_SHA256, worker.BPE_BYTE_COUNT),
        "stats": (snapshot / worker.STATS_RELATIVE_PATH, worker.STATS_SHA256, worker.STATS_BYTE_COUNT)}
    source = {"align": (site / "espnet2/bin/s2t_ctc_align.py", worker.ALIGN_SHA256, worker.ALIGN_BYTE_COUNT),
        "helper": (site / "espnet2/tasks/s2t_ctc.py", worker.HELPER_SHA256, worker.HELPER_BYTE_COUNT)}
    versions = {"python": worker.PYTHON_VERSION, "espnet": worker.ESPNET_VERSION, "torch": worker.TORCH_VERSION,
        "torchaudio": worker.TORCHAUDIO_VERSION, "cudaRuntime": worker.CUDA_VERSION,
        "cudaDevice": worker.cuda_device_identity('owsm_ctc_forced_alignment'),
        "ctcSegmentation": worker.CTC_SEGMENTATION_VERSION, "numpy": worker.NUMPY_VERSION,
        "soundfile": worker.SOUNDFILE_VERSION, "libsndfile": worker.LIBSNDFILE_VERSION,
        "wheel": artifact(runtime / worker.ESPNET_WHEEL_NAME, worker.ESPNET_WHEEL_SHA256, worker.ESPNET_WHEEL_BYTE_COUNT),
        "lock": artifact(runtime / worker.RUNTIME_LOCK_NAME, worker.RUNTIME_LOCK_SHA256, worker.RUNTIME_LOCK_BYTE_COUNT)}
    audio = scratch / "source.wav"
    return worker._validated_request({"formatVersion": worker.REQUEST_VERSION, "route": worker.ROUTES_BY_LANGUAGE[language],
        "durationMs": plan["endMs"]-plan["startMs"], "audio": artifact(audio, sha256_file(audio), audio.stat().st_size),
        "model": {"revision": worker.MODEL_REVISION, **{k: artifact(*v) for k, v in files.items()}},
        "source": {"revision": worker.ESPNET_SOURCE_REVISION, **{k: artifact(*v) for k, v in source.items()}}, "runtime": versions,
        "lines": [{**line, "words": [{k: w[k] for k in ("wordId", "acousticText")} for w in line["words"]]}
                  for line in plan["request"]["lines"]]})


def infer_contexts(project, contexts):
    result, aligner = {}, None
    if not contexts:
        return result
    model = numerical_model()
    for context in contexts:
        scratch = Path(context["path"])
        binding = context_cache.context_binding(scratch, model)
        plan = read_bounded_json(scratch / "request.private.json", max_bytes=1024*1024, root=scratch)
        prepared = read_bounded_json(scratch / "prepared.json", max_bytes=1024*1024, root=scratch)
        if set(prepared["views"]) != set(ROUTES):
            raise ValueError("multilingual CTC requires all vocal views")
        conditionings = {}
        for language in context["languages"]:
            request = _request(project, scratch, plan, language)
            if request['runtime']['cudaDevice'] != model['cudaDevice']:
                raise ValueError('multilingual CTC device changed during inference')
            if aligner is None:
                paths = worker._resolved_bindings(request)
                worker._runtime_identity(request["runtime"])
                aligner = worker._load_aligner(paths, request["route"])
            aligner.lang_sym = worker._route_config(request["route"])["langSym"]
            _, token_plan, preflight = worker._preflight(aligner, request)
            targets = [t for line in token_plan for t in line["tokenIds"]]
            first = sum(len(line["tokenIds"]) for line in token_plan[:plan["targetLine"]])
            last = first + len(token_plan[plan["targetLine"]]["tokenIds"])-1
            blank = int(aligner.config.blank)
            if blank in targets:
                raise ValueError("multilingual CTC target contains a blank")
            def infer(signal):
                logp, valid = aligner.get_lpz(signal)
                step = int(valid)/len(logp)/16
                if abs(step-FRAME_MS) > .001 or not np.isfinite(logp).all():
                    raise ValueError("multilingual CTC frame grid changed")
                # Drop padded buffers; their silence must not absorb approved words.
                return logp[:math.ceil(len(signal)/(FRAME_MS*16))]
            records, representations = [], {}
            for route in ROUTES:
                view = prepared["views"][route]
                samples = _vocal_samples(validate_generated_path(scratch, scratch / view["path"]), view["provenance"], plan["endMs"]-plan["startMs"])
                name, signal, nominal, scores = ctc._select_acoustic_representation(samples, targets, blank, infer)
                representations[route] = {"selected": name, "scores": scores}
                for shift, gain in ((0, 1.), (-5, .9), (5, 1.1)):
                    begin = 160 + shift*16
                    logp = nominal if shift == 0 else infer(signal[begin:-160]*gain)
                    onset, ending = ctc.boundary_distributions(logp, targets, blank, first, last, allow_edge_audio=True)
                    origin = plan["startMs"] + begin/16
                    records.append({"route": route, "shiftMs": shift, "gain": gain, "representation": name,
                        "originMs": origin, "stepMs": FRAME_MS, "decisions": {b: ctc.select_candidate(m,
                            [c[b] for c in plan["candidateFamilies"].values()], origin_ms=origin, step_ms=FRAME_MS)
                            for b, m in (("startMs", onset), ("endMs", ending))}})
            conditionings[language] = {"records": records, "representations": representations, "preflight": preflight}
        result[str(context["index"])] = {"model": model, "conditionings": conditionings}
        context_cache.write_context_result(scratch, model, result[str(context['index'])], binding=binding)
    return result




def prediction_word_languages(plan, components):
    bindings = {}
    for line in plan["request"]["lines"]:
        for word in line["words"]:
            choices = _routes_for_context({**plan, "targetLine": 0,
                "request": {"lines": [{**line, "words": [word]}]}}, components)
            if len(choices) != 1 or word["wordId"] in bindings:
                return {}
            bindings[word["wordId"]] = choices[0]
    return bindings


def prediction_context(project, scratch, plan, language, aligner=None, *, unconditioned=False):
    """Reuse the pinned native tokenizer/model with the complete word decoder."""
    request = _request(project, scratch, plan, "en" if unconditioned else language)
    if aligner is None:
        paths = worker._resolved_bindings(request)
        worker._runtime_identity(request["runtime"])
        aligner = worker._load_aligner(paths, request["route"])
    if unconditioned:
        if "<nolang>" not in aligner.token_list:
            raise ValueError("multilingual prediction model lacks trained no-language conditioning")
        aligner.lang_sym = "<nolang>"
    else:
        aligner.lang_sym = worker._route_config(request["route"])["langSym"]
    # Three-second margins retain 300 frames per 24-second chunk: the native
    # 80 ms stride, without stretching a 324-frame block to 26 seconds.
    aligner.context_len_in_secs = PREDICTION_CONTEXT_SECONDS
    _, lines, _ = worker._preflight(aligner, request)
    tokens, words, owners = [], [], []
    for line in lines:
        for word in line["words"]:
            ids = line["tokenIds"][word["targetStart"]:word["targetEnd"]]
            tokens.extend(ids)
            owners.extend([len(words)]*len(ids))
            words.append(word["wordId"])
    return aligner, (tokens, words, owners)


def align_prediction_signal(aligner, signal, targets, origin_ms, *, bounds=None):
    from stemwise_engine.endpoint_mms_prediction import decode
    logp, edges = prediction_emissions(aligner, signal, origin_ms)
    return decode(logp, targets[0], targets[2], targets[1], edges, bounds or {},
                  blank=int(aligner.config.blank), estimator="posterior")


def prediction_emissions(aligner, signal, origin_ms):
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
    vocabulary = getattr(aligner, 'token_list', ())
    if vocabulary:
        CURRENT_BUDGET.require(frames=math.ceil(len(signal)/(PREDICTION_FRAME_MS*16)),
            states=len(vocabulary), arrays=2)
    if aligner.context_len_in_secs != PREDICTION_CONTEXT_SECONDS:
        raise ValueError("multilingual prediction context changed")
    logp, valid = aligner.get_lpz(signal)
    if not len(logp) or abs(int(valid)/len(logp)/16-PREDICTION_FRAME_MS) > .001:
        raise ValueError("multilingual prediction frame grid changed")
    logp = logp[:math.ceil(len(signal)/(PREDICTION_FRAME_MS*16))]
    CURRENT_BUDGET.require(frames=len(logp), states=int(logp.shape[1]), arrays=2)
    edges = origin_ms + np.arange(len(logp)+1)*PREDICTION_FRAME_MS
    # The last partial frame ends at real PCM EOF, not the padded model buffer.
    edges[-1] = min(edges[-1], origin_ms+len(signal)/16)
    return logp, edges


def fuse_emissions(values):
    if len(values) != len(ROUTES):
        raise ValueError("vocal fusion requires every separated source")
    merged = None
    for value in values:
        value = np.asarray(value, dtype=np.float64)
        if (value.ndim != 2 or not len(value) or not np.isfinite(value).all()
                or not np.allclose(np.logaddexp.reduce(value, axis=1), 0, atol=1e-4)
                or (merged is not None and value.shape != merged.shape)):
            raise ValueError("vocal fusion emission grids or probabilities differ")
        merged = value.copy() if merged is None else np.logaddexp(merged, value)
    return merged-np.log(len(ROUTES))


def _language_emissions(streams, targets, word_languages):
    languages = list(streams)
    logp = streams[languages[0]].copy()
    for language in languages[1:]:
        value = streams[language]
        if value.shape != logp.shape or not np.isfinite(value).all():
            raise ValueError("native language emission grids differ")
        logp = np.logaddexp(logp, value)
    logp -= np.log(len(languages))
    tokens = None if word_languages is None else np.column_stack([
        streams[word_languages[targets[1][owner]]][:, token]
        for token, owner in zip(targets[0], targets[2])])
    return logp, tokens


def _language_score(streams, targets, word_languages, blank):
    if len(streams) == 1:
        return worker._contrastive_structure_scores(next(iter(streams.values())), [targets[0]], blank)[2][0]
    from stemwise_engine.endpoint_mms_prediction import _forward_path
    logp, tokens = _language_emissions(streams, targets, word_languages)
    scores = []
    for labels, values in ((targets[0], tokens), (targets[0][::-1], tokens[:, ::-1])):
        _, emissions, skip = ctc._ctc_trellis(logp, labels, blank, allow_edge_audio=True)
        emissions[:, 1::2] = values
        scores.append(_forward_path(emissions, skip)[1])
    return scores[0]-scores[1]


def decode_neighborhoods(logp, targets, edges, bounds, windows, wanted, *, blank, token_logp=None,
                         estimator='posterior', _decoded=None, boundary_summaries=None):
    """Decode complete local contexts on the existing original-PCM frame grid."""
    from stemwise_engine.endpoint_mms_prediction import decode
    words, owners = targets[1], targets[2]
    if not windows or [w for window in windows for w in window['targetWordIds']] != wanted:
        raise ValueError('neighborhood prediction target sequence changed')
    result, scores = [], []
    decoded = {} if _decoded is None else _decoded
    paired = {'posterior': ([], []), 'risk': ([], [])} if estimator == 'posterior+risk' else None
    for window in windows:
        local_words = window['wordIds']
        positions = [words.index(word) for word in local_words]
        selected = window['targetWordIds']
        start, end = window['startMs'], window['endMs']
        if (not positions or positions != list(range(positions[0], positions[-1]+1))
                or not selected or not set(selected) <= set(local_words)
                or selected != [word for word in local_words if word in selected]
                or not np.isfinite([start, end]).all() or not 0 <= start < end):
            raise ValueError('neighborhood prediction context is incomplete or invalid')
        first = int(np.searchsorted(edges, start, side='left'))
        last = min(len(logp), int(np.searchsorted(edges, end, side='right'))-1)
        if last <= first:
            raise ValueError('neighborhood prediction has no acoustic frames')
        columns = [i for i, owner in enumerate(owners) if positions[0] <= owner <= positions[-1]]
        key = (tuple(local_words), first, last, estimator)
        summary_key = ('boundarySummaries', key)
        if key not in decoded:
            local_summaries = [] if boundary_summaries is not None else None
            decoded[key] = decode(logp[first:last], [targets[0][i] for i in columns],
                [owners[i]-positions[0] for i in columns], local_words, edges[first:last+1],
                {word: bounds[word] for word in local_words if word in bounds}, blank=blank,
                estimator=estimator, token_logp=None if token_logp is None else token_logp[first:last, columns],
                **({'boundary_summaries': local_summaries} if local_summaries is not None else {}))
            decoded[summary_key] = local_summaries
        if boundary_summaries is not None:
            boundary_summaries.extend(summary for summary in decoded.get(summary_key) or () if summary['wordId'] in selected)
        if paired is not None:
            for name,(rows,score) in decoded[key].items():
                paired[name][0].extend(row for row in rows if row['wordId'] in selected)
                paired[name][1].append(score)
        else:
            rows, score = decoded[key]
            result.extend(row for row in rows if row['wordId'] in selected)
            scores.append(score)
    if paired is not None:
        return {name:(rows,float(np.mean(values))) for name,(rows,values) in paired.items()}
    return result, float(np.mean(scores))


def infer_neighborhood_sources(project, context, probe, *, aligner=None):
    """Compute complete local word records from the original and actual stems."""
    from scipy.signal import resample_poly
    from stemwise_engine.source_crop import read_source_crop as _source_crop
    context = validate_generated_path(project,context)
    names = {'request.private.json','prepared.json','source.wav'}
    if (set(probe) != {'inputs','window','bounds'} or set(probe['inputs']) != names
            or any(sha256_file(validate_generated_path(context,context/name)) != digest
                   for name,digest in probe['inputs'].items())):
        raise ValueError('source probe input binding changed')
    plan = read_bounded_json(context/'request.private.json',max_bytes=1024*1024,root=context)
    prepared = read_bounded_json(context/'prepared.json',max_bytes=1024*1024,root=context)
    if set(prepared['views']) != set(ROUTES):
        raise ValueError('source probe requires every retained vocal source')
    window = probe['window']
    lines = plan['request']['lines']
    matches = [i for i,line in enumerate(lines) if line['lineId'] == window['occurrenceId']]
    if len(matches) != 1:
        raise ValueError('source probe target is not in the retained context')
    target = matches[0]
    selected = set(window['wordIds'])
    positions = [i for i,line in enumerate(lines) if any(w['wordId'] in selected for w in line['words'])]
    local_words = [w['wordId'] for i in positions for w in lines[i]['words']]
    if (not positions or positions != list(range(positions[0],positions[-1]+1))
            or local_words != window['wordIds']
            or window['targetWordIds'] != [w['wordId'] for w in lines[target]['words']]
            or not set(range(max(0,target-1),min(len(lines),target+2))) <= set(positions)):
        raise ValueError('source probe requires complete ordered neighboring lines')
    start,end = window['startMs'],window['endMs']
    if not np.isfinite([start,end]).all() or not plan['startMs'] <= start < end <= plan['endMs']:
        raise ValueError('source probe crop is outside its retained source')
    original = resample_poly(_source_crop(context,plan),1,3,axis=0).astype(np.float32)
    aligner,targets = prediction_context(project,context,plan,None,aligner,unconditioned=True)
    words,owners = targets[1],targets[2]
    first_word = words.index(local_words[0])
    if words[first_word:first_word+len(local_words)] != local_words or not set(probe['bounds']) <= set(words):
        raise ValueError('source probe token ownership changed')
    columns = [i for i,owner in enumerate(owners) if first_word <= owner < first_word+len(local_words)]
    local_targets = ([targets[0][i] for i in columns],local_words,[owners[i]-first_word for i in columns])
    first,last = round((start-plan['startMs'])*16),round((end-plan['startMs'])*16)
    result = {
              'model':numerical_model({**UNCONDITIONED_PREDICTION_MODEL,
                       'decoder':'complete-ctc-neighborhood-posterior-and-viterbi-v1',
                       'fusion':'none;each-source-decoded-independently'}),
              'inputs':probe['inputs'],'windowMs':[start,end],
              'contextWordCount':len(local_words),'contextWordIds':local_words,
              'targetWordCount':len(window['targetWordIds']),'sources':{}}
    for route in ('original',*ROUTES):
        if route == 'original':
            samples = original
        else:
            view = prepared['views'][route]
            samples = _vocal_samples(validate_generated_path(context,context/view['path']),view['provenance'],
                                     plan['endMs']-plan['startMs'],sample_rate=16000)
        # A validated whole-ms EOF may round past its actual trailing PCM.
        stop = min(last, len(samples)) if end == plan['endMs'] else last
        if not 0 <= first < stop <= len(samples):
            raise ValueError('source probe crop has no complete PCM samples')
        records,representations = infer_fused_views({route:samples[first:stop]},aligner,local_targets,
            local_words,{w:probe['bounds'][w] for w in local_words if w in probe['bounds']},
            plan['startMs']+first/16,at_start=start == 0,estimator='both',ensemble_route=route,source_routes=(route,))
        result['sources'][route] = {'representation':representations[route],'records':records}
    return result,aligner


def probe_neighborhood_sources(project, context, probe):
    """Read-only numeric comparison; never approve or publish its output."""
    result,_ = infer_neighborhood_sources(project,context,probe)
    offset = result['contextWordIds'].index(probe['window']['targetWordIds'][0])
    count = result['targetWordCount']
    result.pop('contextWordIds')
    for source in result['sources'].values():
        decoders = {}
        for decoder,rows in source.pop('records').items():
            starts = [r['words'][offset]['startMs'] for r in rows]
            ends = [r['words'][offset+count-1]['endMs'] for r in rows]
            decoders[decoder] = {'startMs':starts,'endMs':ends,
                'startStable':max(starts)-min(starts) <= 300,'endStable':max(ends)-min(ends) <= 400}
        source['decoders'] = decoders
    return {'method':'original-and-vocals-neighborhood-probe-v1','diagnosticOnly':True,**result}


def infer_fused_views(samples, aligner, targets, wanted, bounds, origin, *, at_start=False, at_end=False,
                      word_languages=None, emission_reader=None, blank=None, ensemble_route=FUSION_ROUTE,
                      decode_windows=None, estimator='posterior', complete_context=False, boundary_risk=False,
                      source_routes=None):
    from stemwise_engine.endpoint_mms_prediction import decode
    if complete_context and (estimator != 'both' or not decode_windows):
        raise ValueError('complete context prediction requires both local decoders')
    if boundary_risk and not complete_context:
        raise ValueError('boundary risk requires complete local and shared contexts')
    routes = ROUTES if source_routes is None else source_routes
    if (routes != ROUTES and (not isinstance(routes,tuple) or len(routes) != 1
                             or routes[0] not in (*ROUTES,'original'))):
        raise ValueError('source comparison route is unsupported')
    if set(samples) != set(routes):
        raise ValueError("vocal fusion requires every separated source")
    reader = emission_reader or prediction_emissions
    blank = int(aligner.config.blank) if blank is None else blank
    if word_languages is not None and (set(word_languages) != set(targets[1])
            or not set(word_languages.values()) <= set(worker.ROUTES_BY_LANGUAGE)):
        raise ValueError("vocal fusion word languages are incomplete or unsupported")
    languages = sorted(set(word_languages.values())) if word_languages is not None else [None]
    begin, stop = (0 if at_start else 160), (None if at_end else -160)
    selected, nominal, representations = {}, {}, {}
    for route in routes:
        audio = samples[route]
        if audio.ndim != 2 or audio.shape[1] not in (1, 2) or not np.isfinite(audio).all():
            raise ValueError("vocal fusion audio is invalid")
        views = {"mono": audio[:, 0]} if audio.shape[1] == 1 else {
            "left": audio[:, 0], "right": audio[:, 1],
            "mid": audio.mean(axis=1), "side": (audio[:, 0]-audio[:, 1])/2}
        scores, best = {}, None
        for name, signal in views.items():
            if not np.any(signal):
                continue
            streams, grid = {}, None
            for language in languages:
                if language is not None:
                    aligner.lang_sym = worker._route_config(worker.ROUTES_BY_LANGUAGE[language])["langSym"]
                logp, edges = reader(aligner, signal[begin:stop], origin+begin/16)
                if grid is not None and not np.array_equal(grid, edges):
                    raise ValueError("native language PCM time grids differ")
                grid, streams[language] = edges, logp
            score = _language_score(streams, targets, word_languages, blank)
            scores[name] = score
            if best is None or score > scores[best]:
                best, selected[route] = name, signal
                for language in languages:
                    nominal[(language, route)] = streams[language], grid
        if best is None:
            raise ValueError("vocal fusion source is silent")
        representations[route] = {"selected": best, "scores": scores,
            "scoring": "contrastive-declared-word-language-v2" if len(languages) > 1 else "contrastive-order-control-v1"}
    records = {'posterior': [], 'viterbi': []} if estimator == 'both' else []
    if complete_context:
        records.update({'shared-posterior': [], 'shared-viterbi': []})
    if boundary_risk:
        records.update({'risk': [], 'shared-risk': []})
    for shift in (-5, 0, 5):
        offset = max(0, (0 if at_start else 160)+shift*16)
        for gain in (.9, 1., 1.1):
            streams, grid = {}, None
            for language in languages:
                if language is not None:
                    aligner.lang_sym = worker._route_config(worker.ROUTES_BY_LANGUAGE[language])["langSym"]
                values = []
                for route in routes:
                    key = (language, route)
                    logp, edges = nominal[key] if shift == 0 and gain == 1 else reader(
                        aligner, selected[route][offset:stop]*gain, origin+offset/16)
                    if grid is not None and not np.array_equal(grid, edges):
                        raise ValueError("vocal fusion PCM time grids differ")
                    values.append(logp)
                    grid = edges
                streams[language] = fuse_emissions(values) if len(routes) == len(ROUTES) else values[0]
            logp, token_logp = _language_emissions(streams, targets, word_languages)
            # This cache belongs only to these fixed probabilities, targets and
            # bounds. Reuse a complete decode when local/shared inputs coincide.
            paired, decoded, boundary_summaries = {}, {}, []
            for name in (tuple(records) if estimator == 'both' else (estimator,)):
                decoder = name.removeprefix('shared-')
                context = 'shared' if name.startswith('shared-') else 'local'
                if decoder != 'risk':
                    boundary_summaries = []
                if decoder == 'risk':
                    timings,confidence = paired[context]['risk']
                else:
                    calculation = 'posterior+risk' if boundary_risk and decoder == 'posterior' else decoder
                    if decode_windows is None or context == 'shared':
                        key = (tuple(targets[1]), 0, len(logp), calculation)
                        summary_key = ('boundarySummaries', key)
                        if key not in decoded:
                            local_summaries = []
                            decoded[key] = decode(logp, targets[0], targets[2], targets[1], grid,
                                bounds, blank=blank, estimator=calculation, token_logp=token_logp,
                                boundary_summaries=local_summaries)
                            decoded[summary_key] = local_summaries
                        boundary_summaries.extend(decoded.get(summary_key) or ())
                        computed = decoded[key]
                    else:
                        computed = decode_neighborhoods(logp, targets, grid, bounds, decode_windows,
                            wanted, blank=blank, token_logp=token_logp, estimator=calculation, _decoded=decoded,
                            boundary_summaries=boundary_summaries)
                    if calculation == 'posterior+risk':
                        paired[context] = computed
                        timings,confidence = computed['posterior']
                    else:
                        timings,confidence = computed
                by_id = {word["wordId"]: word for word in timings}
                output = records[name] if estimator == 'both' else records
                output.append({"route": ensemble_route, "members": list(routes), "shiftMs": shift, "gain": gain,
                    "confidence": confidence, "words": [by_id[word] for word in wanted],
                    **({'boundarySummaries': [summary for summary in boundary_summaries if summary['wordId'] in wanted]}
                       if boundary_summaries else {})})
    if len(json_bytes(records)) > 1024*1024:
        for rows in (records.values() if isinstance(records, dict) else (records,)):
            for record in rows:
                record.pop('boundarySummaries', None)
    return records, representations


def main():
    with redirect_stdout(sys.stderr):
        if sys.argv[2] == '--probe-neighborhood':
            output = probe_neighborhood_sources(Path(sys.argv[1]),Path(sys.argv[3]),parse_worker_json(sys.argv[4]))
        else:
            output = infer_contexts(Path(sys.argv[1]), parse_worker_json(sys.argv[2]))
    print(json.dumps(output, separators=(",", ":"), allow_nan=False))


if __name__ == "__main__":
    raise SystemExit(run_worker_main(main))
