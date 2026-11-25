"""Estimated multilingual word spans; every target and qualified fence is required."""
from __future__ import annotations

from pathlib import Path
from collections import OrderedDict
from hashlib import sha256
import numpy as np

from stemwise_engine.common import read_bounded_json, sha256_file
import stemwise_engine.ctc_worker as ctc_worker
import stemwise_engine.mms_forced_alignment as worker
from stemwise_engine.endpoint_ctc_evidence import _ctc_trellis
from stemwise_engine.ctc_numerics import forward_path as _forward_path
from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
from stemwise_engine.model_registry import model_assets

FAMILY = "mms-windowed"
RATE = 16000
MODEL = {"bundle": worker.BUNDLE_IDENTITY,
    "modelSha256": model_assets("lyrics_align_pc")["PINNED_MMS_SHA256"],
    "decoder": "complete-ctc-qualified-word-windows-v1", "timeMap": "mms-canonical-chunks-v1"}
PREDICTION_METHOD = "local-multilingual-mms-prediction-v2"
PREDICTION_MODEL = {**MODEL, "decoder": "complete-ctc-qualified-context-posterior-medians-v2",
                    "fusion": "all-vocals-mean-probability-v1", "targetKind": "complete-word-bound-roman-characters"}


def numerical_model(template):
    import torch
    actual = worker.runtime_info('auto')
    runtime = {key: actual[key] for key in ('python', 'torch', 'torchaudio', 'demucs', 'uroman', 'cudaRuntime', 'cudnn')}
    device = {'type': actual['resolvedDevice']}
    if device['type'] == 'cuda':
        device.update(name=torch.cuda.get_device_name(0), capability=list(torch.cuda.get_device_capability(0)),
            totalMemoryBytes=torch.cuda.get_device_properties(0).total_memory)
    value = {**template, 'execution': {'runtime': runtime, 'device': device}}
    return validate_recorded_model(value, template)


def validate_recorded_model(value, template):
    from stemwise_engine.model_registry import runtime_profile
    if not isinstance(value, dict) or {key: item for key, item in value.items() if key != 'execution'} != template:
        raise ValueError('multilingual prediction model identity changed')
    execution = value.get('execution')
    if not isinstance(execution, dict) or set(execution) != {'runtime', 'device'}:
        raise ValueError('multilingual prediction execution identity is unavailable')
    runtime, device = execution['runtime'], execution['device']
    profile = runtime_profile('main')
    if (not isinstance(runtime, dict) or set(runtime) != {'python', 'torch', 'torchaudio', 'demucs', 'uroman', 'cudaRuntime', 'cudnn'}
            or runtime['python'] != profile['python']
            or any(runtime[key] != profile['packages'][key] for key in ('torch', 'torchaudio', 'demucs', 'uroman'))
            or any(not isinstance(runtime[key], str) or not runtime[key] for key in ('cudaRuntime', 'cudnn'))
            or not isinstance(device, dict) or device.get('type') not in ('cpu', 'cuda')):
        raise ValueError('multilingual prediction runtime or device is invalid')
    if device['type'] == 'cpu':
        if set(device) != {'type'}:
            raise ValueError('multilingual CPU prediction device is invalid')
    elif (set(device) != {'type', 'name', 'capability', 'totalMemoryBytes'}
            or not isinstance(device['name'], str) or not device['name'].strip()
            or not isinstance(device['capability'], list) or len(device['capability']) != 2
            or any(type(part) is not int or part < 0 for part in device['capability'])
            or type(device['totalMemoryBytes']) is not int or device['totalMemoryBytes'] <= 0):
        raise ValueError('multilingual CUDA prediction device is invalid')
    return value


class EmissionCache:
    """Worker-local, bounded reuse of one MMS model's exact PCM inputs."""
    def __init__(self, model, reader, *, max_bytes=128*1024*1024):
        self.model, self.reader, self.max_bytes = model, reader, max_bytes
        self.entries, self.bytes_used = OrderedDict(), 0

    def __call__(self, model, signal, origin_ms):
        if model is not self.model:
            return self.reader(model, signal, origin_ms)
        pcm = np.ascontiguousarray(signal)
        key = (pcm.dtype.str, pcm.shape, origin_ms, sha256(memoryview(pcm).cast('B')).digest())
        if key in self.entries:
            self.entries.move_to_end(key)
            return self.entries[key][0]
        result = self.reader(model, signal, origin_ms)
        # Charge array storage and conservative per-entry metadata. Never keep
        # PCM, GPU tensors or probabilities on disk or across independent replay.
        size = sum(values.nbytes for values in result) + 512
        if size <= self.max_bytes:
            while self.bytes_used + size > self.max_bytes:
                _, (_, removed) = self.entries.popitem(last=False)
                self.bytes_used -= removed
            self.entries[key] = result, size
            self.bytes_used += size
        return result


def target_plan(request, _dictionary, vocab):
    records, normalized, _, _ = worker._request_plan({"formatVersion": worker.FORMAT_VERSION,
        "durationMs": 1, "lines": request["lines"], "segments": [{"segmentIndex": 0,
        "startLineIndex": 0, "endLineIndex": len(request["lines"]), "windowStartMs": 0, "windowEndMs": 1}]})
    targets, words, owners = [], [], []
    for line in records:
        for word in line["words"]:
            identity = word["wordId"]
            if identity in words:
                raise ValueError("multilingual prediction repeats a word identity")
            text = "".join(normalized[word["startWord"]:word["startWord"]+word["wordCount"]])
            if not text or any(c not in vocab or vocab[c] == 0 for c in text):
                raise ValueError("multilingual prediction cannot represent a complete word")
            targets.extend(vocab[c] for c in text)
            owners.extend([len(words)]*len(text))
            words.append(identity)
    return targets, words, owners


def decode(logp, targets, owners, words, edges, bounds, *, blank=0, estimator="viterbi", token_logp=None,
           boundary_summaries=None, latest_entries=None):
    """Maximum complete CTC path, with constraints on states rather than tokens."""
    CURRENT_BUDGET.require(frames=int(logp.shape[0]), states=2*len(targets)+1,
        arrays=5, retained_bytes=int(logp.nbytes))
    labels, emissions, skip = _ctc_trellis(logp, targets, blank, allow_edge_audio=True)
    token_values = np.asarray(logp)[:, targets] if token_logp is None else np.asarray(token_logp, dtype=np.float64)
    if (token_values.shape != (len(logp), len(targets)) or not np.isfinite(token_values).all()
            or np.any(token_values > 1e-7)):
        raise ValueError("multilingual prediction token probabilities are invalid")
    emissions[:, 1::2] = token_values
    edges = np.asarray(edges, dtype=np.float64)
    if (edges.shape != (len(logp)+1,) or not np.isfinite(edges).all()
            or np.any(np.diff(edges) <= 0) or len(owners) != len(targets)
            or len(set(words)) != len(words) or not set(bounds) <= set(words)
            or any(type(o) is not int or not 0 <= o < len(words) for o in owners)
            or owners != sorted(owners) or set(owners) != set(range(len(words)))):
        raise ValueError("multilingual prediction time map or target ownership is invalid")
    for token, owner in enumerate(owners):
        low, high = bounds.get(words[owner], (None, None))
        if (any(v is not None and (type(v) not in (int, float) or not np.isfinite(v)) for v in (low, high))
                or (low is not None and high is not None and low >= high)):
            raise ValueError("multilingual prediction qualified window is invalid")
        valid = np.ones(len(logp), dtype=bool)
        if low is not None:
            valid &= edges[:-1] >= low
        if high is not None:
            valid &= edges[1:] <= high
        emissions[~valid, 2*token+1] = -np.inf
    if latest_entries:
        for word, latest in latest_entries.items():
            if word not in words or type(latest) not in (int, float) or not np.isfinite(latest):
                raise ValueError("Invalid acoustic word entrance fence")
            first = 2*owners.index(words.index(word))+1
            # Only predecessor states expire. The word can stay on its first
            # vowel and sustain for the rest of the measured vocal phrase.
            emissions[edges[1:]>latest, :first] = -np.inf
    if estimator in ("posterior", "posterior+risk"):
        return _posterior_timings(token_values, labels, emissions, skip, targets, owners, words, edges,
                                  boundary_risk=estimator == "posterior+risk", boundary_summaries=boundary_summaries)
    if estimator != "viterbi":
        raise ValueError("multilingual prediction estimator is unsupported")
    count, states = emissions.shape
    previous = np.full(states, -np.inf)
    previous[:2] = emissions[0, :2]
    predecessors = np.zeros((count, states), dtype=np.uint8)
    for t in range(1, count):
        options = np.stack((previous, np.r_[-np.inf, previous[:-1]],
            np.r_[-np.inf, -np.inf, np.where(skip, previous[:-2], -np.inf)]))
        chosen = np.argmax(options, axis=0)
        predecessors[t] = chosen
        previous = options[chosen, np.arange(states)] + emissions[t]
    state = states-2 + int(previous[-1] > previous[-2])
    if not np.isfinite(previous[state]):
        raise ValueError("multilingual prediction cannot retain every target within qualified windows")
    path = np.empty(count, dtype=int)
    for t in range(count-1, -1, -1):
        path[t] = state
        state -= int(predecessors[t, state])
    spans, scores = [[] for _ in words], []
    for token, owner in enumerate(owners):
        frames = np.flatnonzero(path == 2*token+1)
        if not len(frames):
            raise ValueError("multilingual prediction lost a required target")
        spans[owner].extend(frames.tolist())
        scores.extend(token_values[frames, token].tolist())
    timings = []
    for identity, frames in zip(words, spans):
        if not frames:
            raise ValueError("multilingual prediction lost a required word")
        timings.append({"wordId": identity, "startMs": float(edges[min(frames)]),
                        "endMs": float(edges[max(frames)+1])})
    return timings, float(np.exp(np.mean(scores)))




def _posterior_timings(token_values, labels, emissions, skip, targets, owners, words, edges, *, boundary_risk=False,
                       boundary_summaries=None):
    """Median boundary minimizes expected absolute error over complete paths."""
    count, states = emissions.shape
    alpha, partition = _forward_path(emissions, skip)
    if not np.isfinite(partition):
        raise ValueError("multilingual prediction cannot retain every target within qualified windows")
    beta = np.full_like(alpha, -np.inf)
    beta[-1, -2:] = 0
    for t in range(count-2, -1, -1):
        following = emissions[t+1] + beta[t+1]
        row = following.copy()
        row[:-1] = np.logaddexp(row[:-1], following[1:])
        row[:-2] = np.logaddexp(row[:-2], np.where(skip, following[2:], -np.inf))
        beta[t] = row
    timings, curves = [], []
    for word, identity in enumerate(words):
        first, last = 2*owners.index(word)+1, 2*(len(owners)-1-owners[::-1].index(word))+1
        onset, ending = np.full(count+1, -np.inf), np.full(count+1, -np.inf)
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
            ending[t] = alpha[t-1, last] + following
        if last == states-2:
            ending[count] = alpha[-1, last]
        medians, word_curves = [], []
        for distribution in (onset, ending):
            mass = np.exp(distribution-partition)
            if not np.isfinite(mass).all() or not np.isclose(mass.sum(), 1, atol=1e-5):
                raise ValueError("multilingual prediction boundary posterior is invalid")
            if boundary_risk:
                curves.append(mass)
            if boundary_summaries is not None:
                word_curves.append(mass)
            medians.append(float(edges[np.searchsorted(np.cumsum(mass), mass.sum()/2)]))
        if not medians[0] < medians[1] or (timings and timings[-1]["endMs"] > medians[0]):
            raise ValueError("multilingual prediction posterior word order is invalid")
        timings.append({"wordId": identity, "startMs": medians[0], "endMs": medians[1]})
        if boundary_summaries is not None:
            from stemwise_engine.endpoint_candidate_prediction import summarize_boundary_curves
            from stemwise_engine.acoustic_inputs import AcousticBudgetExceeded
            first_token, last_token = owners.index(word), len(owners)-1-owners[::-1].index(word)
            token_ids = targets[first_token:last_token+1]
            acoustic = token_values[:,first_token:last_token+1]
            blank_values = emissions[:,2] if states > 3 else emissions[:,0]
            if (not (acoustic > blank_values[:,None]).any()
                    or np.allclose(acoustic,acoustic[0],rtol=0,atol=1e-12)):
                boundary_summaries.clear()
            else:
                try:
                    CURRENT_BUDGET.require(frames=count, states=states, arrays=5,
                        retained_bytes=token_values.nbytes+8*(count+1)*6+len(words)*2048)
                    boundary_summaries.append(summarize_boundary_curves(identity, *word_curves, edges,
                        minimum_frames=len(token_ids)+sum(a == b for a,b in zip(token_ids, token_ids[1:])),
                        preceding_gap_frames=int(first_token > 0 and targets[first_token-1] == targets[first_token])))
                except AcousticBudgetExceeded:
                    boundary_summaries.clear()
    occupancy = np.exp(alpha[:, 1::2]+beta[:, 1::2]-partition)
    confidence = np.exp(np.sum(occupancy*token_values)/occupancy.sum())
    if boundary_risk:
        return {'posterior': (timings, float(confidence)),
                'risk': (_minimum_risk_timings(curves,targets,owners,words,edges),float(confidence))}
    return timings, float(confidence)


def _minimum_risk_timings(curves, targets, owners, words, edges):
    """Minimize expected wrong CTC boundary frames while retaining every token.

    Summing boundary posterior mass is expected exact-frame correctness,
    conditional on this acoustic model, not calibrated physical accuracy.
    """
    first = [owners.index(word) for word in range(len(words))]
    last = [len(owners)-1-owners[::-1].index(word) for word in range(len(words))]
    distances = [0]
    for word in range(len(words)):
        if word:
            distances.append(int(targets[last[word-1]] == targets[first[word]]))
        tokens = targets[first[word]:last[word]+1]
        distances.append(len(tokens)+sum(a == b for a,b in zip(tokens,tokens[1:])))
    size = len(edges)
    score = np.where(np.asarray(curves[0]) > 0,curves[0],-np.inf)
    parents = []
    for curve,distance in zip(curves[1:],distances[1:]):
        if distance >= size:
            raise ValueError('boundary risk cannot retain every target')
        prefix = np.maximum.accumulate(score)
        changes = np.r_[True,score[1:] > prefix[:-1]]
        best = np.maximum.accumulate(np.where(changes,np.arange(size),0))
        parent = np.full(size,-1,dtype=int)
        parent[distance:] = best[:size-distance]
        current = np.full(size,-np.inf)
        current[distance:] = prefix[:size-distance]+np.asarray(curve)[distance:]
        current[np.asarray(curve) <= 0] = -np.inf
        parents.append(parent)
        score = current
    if not np.isfinite(score).any():
        raise ValueError('boundary risk cannot retain every target')
    selected = [int(np.argmax(score))]
    for parent in reversed(parents):
        selected.append(int(parent[selected[-1]]))
    selected.reverse()
    return [{'wordId':word,'startMs':float(edges[selected[2*i]]),
             'endMs':float(edges[selected[2*i+1]])} for i,word in enumerate(words)]


def load_model(project: Path):
    import torch
    import torchaudio
    checkpoint = project.parent / "model-cache/torch/hub/checkpoints/model.pt"
    if sha256_file(checkpoint) != MODEL["modelSha256"]:
        raise ValueError("multilingual prediction checkpoint differs")
    from stemwise_engine.model_registry import runtime_profile
    runtime = runtime_profile('main')
    expected = {k: runtime["packages"][k] for k in ("torch", "torchaudio", "demucs", "uroman")}
    expected["python"] = runtime["python"]
    actual = worker.runtime_info("auto")
    if any(actual.get(k) != v for k, v in expected.items()):
        raise ValueError("multilingual prediction runtime differs")
    bundle = torchaudio.pipelines.MMS_FA
    model = bundle.get_model(with_star=False).to("cuda" if torch.cuda.is_available() else "cpu").eval()
    model.vocab = bundle.get_dict(star=None)
    return model


def align_signal(model, signal, targets, origin_ms, *, bounds=None, latest_entries=None):
    logp, edges = prediction_emissions(model, signal, origin_ms)
    return decode(logp, targets[0], targets[2], targets[1], edges, bounds or {}, latest_entries=latest_entries)


def prediction_emissions(model, signal, origin_ms):
    import torch
    signal = np.asarray(signal, dtype=np.float32)
    if signal.ndim != 1 or len(signal) < 400 or not np.isfinite(signal).all():
        raise ValueError("multilingual prediction audio is invalid")
    emissions, chunks, cursor = [], [], 0
    with torch.inference_mode():
        from stemwise_engine.acoustic_inputs import chunk_ranges
        for start, stop in chunk_ranges(len(signal), 30*RATE):
            tensor = torch.from_numpy(signal[start:stop].copy())[None].to(next(model.parameters()).device)
            values, _ = model(tensor)
            values = values[0].log_softmax(-1).cpu().numpy()
            CURRENT_BUDGET.require(frames=cursor+len(values), states=int(values.shape[1]), arrays=2)
            emissions.append(values)
            chunks.append(ctc_worker.chunk_time_map(start, stop, cursor, cursor+len(values)))
            cursor += len(values)
    edges = [origin_ms + ctc_worker.emission_boundary_to_target_frame(i, chunks)*1000/RATE
             for i in range(cursor+1)]
    return np.concatenate(emissions), np.asarray(edges, dtype=np.float64)
