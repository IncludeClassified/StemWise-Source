"""Local unprompted Kurmanji/Zulu ASR; CTC times are hypotheses, not endpoints."""
from __future__ import annotations

from stemwise_engine.common import WorkerTemporaryDirectory
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.runtime_environment import environment_python, environment_lease

import argparse
import itertools
import hashlib
import json
import math
import sys
from pathlib import Path

from stemwise_engine.common import heavy_worker_stage, atomic_write_json, read_bounded_json, run_process_tree, sha256_file
from stemwise_engine.contract import _language_primary
from stemwise_engine.model_registry import LANGUAGES, model_assets

MODEL = model_assets('mms_asr')['MODEL']
REVISION = model_assets('mms_asr')['REVISION']
ADAPTER = model_assets('mms_asr')['ADAPTER']
ASSETS = model_assets('mms_asr')['ASSETS']
ASSETS_BY_ADAPTER = model_assets('mms_asr')['ASSETS_BY_ADAPTER']


def adapters_for(original: dict) -> tuple[str, ...]:
    declared = original.get("language")
    if not isinstance(declared, str):
        return ()
    languages = original.get("componentLanguages", []) if _language_primary(declared) == "mul" else [declared]
    return tuple(dict.fromkeys(route['adapter']
        for language in (_language_primary(v) for v in languages if isinstance(v, str))
        if (route := LANGUAGES.get(language, {}).get('nativeAsr')) is not None
        and original.get('script') in route['scripts']))


def decode_words(ids: list[int], vocab: dict[int, str], blank: int, delimiter: int,
                 frame_ms: float, duration_ms: float) -> list[dict]:
    if not math.isfinite(frame_ms) or frame_ms <= 0 or not math.isfinite(duration_ms) or duration_ms <= 0:
        raise ValueError("invalid CTC frame duration")
    if len(ids) * frame_ms > duration_ms + frame_ms:
        raise ValueError("CTC frames exceed audio duration")
    words, letters = [], []
    start = end = 0

    def flush() -> None:
        if letters:
            words.append({"raw": "".join(letters), "start": start / 1000, "end": end / 1000})
            letters.clear()

    for token, group in itertools.groupby(enumerate(ids), key=lambda item: item[1]):
        frames = [i for i, _ in group]
        if token == blank:
            continue
        if token == delimiter:
            flush()
            continue
        char = vocab.get(token)
        if not char or char.startswith("<"):
            raise ValueError("unknown CTC token")
        if not letters:
            start = frames[0] * frame_ms
        end = min((frames[-1] + 1) * frame_ms, duration_ms)
        if end <= start:
            raise ValueError("CTC word exceeds audio duration")
        letters.append(char)
    flush()
    return words


def validate_output(value: dict, audio_sha: str, duration_ms: int, adapter: str = ADAPTER,
                    runtime: dict | None = None) -> list[dict]:
    if (not isinstance(value, dict) or value.get("familyId") != "mms-asr"
            or value.get("modelRevision") != REVISION or value.get("adapter") != adapter
            or adapter not in ASSETS_BY_ADAPTER or value.get("assets") != ASSETS_BY_ADAPTER[adapter]
            or value.get("audioSha256") != audio_sha
            or type(value.get("durationMs")) not in (int, float)
            or not math.isfinite(value["durationMs"])
            or abs(value["durationMs"] - duration_ms) > 1
            or value.get("frameMs") != 20 or not isinstance(value.get("words"), list)):
        raise ValueError("MMS ASR output binding differs")
    actual_runtime = value.get('runtime')
    if (not isinstance(actual_runtime, dict) or any(not isinstance(actual_runtime.get(key), str)
            or not actual_runtime[key] for key in ('torch', 'torchaudio', 'transformers', 'resolvedDevice', 'dtype', 'recipe'))
            or actual_runtime['resolvedDevice'] not in ('cpu', 'cuda')
            or actual_runtime['dtype'] != 'float32' or actual_runtime['recipe'] != 'mms-greedy-20ms-v1'
            or runtime is not None and actual_runtime != runtime):
        raise ValueError('MMS ASR numerical runtime differs')
    previous_end = 0.0
    for word in value["words"]:
        if (not isinstance(word, dict) or not isinstance(word.get("raw"), str)
                or not word["raw"].strip() or any(c.isspace() for c in word["raw"])
                or any(type(word.get(k)) not in (int, float) or not math.isfinite(word[k]) for k in ("start", "end"))
                or not previous_end <= word["start"] < word["end"] <= (duration_ms + 1) / 1000):
            raise ValueError("MMS ASR word timing is invalid")
        previous_end = word["end"]
    return value["words"]


def load_or_transcribe(*, project: Path, model_cache: Path, job_dir: Path,
                       audio: Path, audio_identity: dict, duration_ms: int,
                       route: str, device: str, timeout: int, env: dict,
                       rerun: bool, adapter: str = ADAPTER, cache_only: bool = False,
                       runtime_report: dict | None = None) -> tuple[list, dict, bool]:
    # Only the alignment parent imports syncalong; the main-runtime worker does not.
    from stemwise_engine.lyrics_alignment import WordTimestamp, normalize
    from stemwise_engine.contract import MAX_JSON_BYTES

    helper = Path(__file__).resolve()
    cache = job_dir / f"native-asr-{adapter}-{route}.private.json"
    if cache_only and (rerun or not cache.is_file()):
        raise FileNotFoundError('No current bound MMS ASR transcript cache')
    runtime = runtime_report or runtime_provenance(project, device, timeout, env)
    provenance = {
        "familyId": "mms-asr", "modelRevision": REVISION, "adapter": adapter,
        "assets": ASSETS_BY_ADAPTER[adapter], "audioSha256": audio_identity["sha256"],
        "durationMs": duration_ms, "device": device, "promptMode": "withheld",
        'runtime': runtime,
        "helpers": {name: semantic_source_identity(helper.parent / name) for name in
                    (helper.name, "endpoint_ctc_evidence.py", "wav2vec2_forced_alignment.py",
                     'acoustic_inputs.py', 'ctc_numerics.py', 'ctc_worker.py', 'worker_protocol.py',
                     'worker_publication.py', 'model_registry.py', 'model-route-registry.json')},
    }
    try:
        stored = read_bounded_json(cache, max_bytes=MAX_JSON_BYTES, root=job_dir) if cache.is_file() and not rerun else None
    except (OSError, ValueError):
        stored = None
    expected = provenance
    reused = isinstance(stored, dict) and stored.get("provenance") == expected
    if reused:
        try:
            validate_output(stored.get("workerOutput"), audio_identity["sha256"], duration_ms, adapter, runtime)
        except (TypeError, ValueError):
            reused = False
    if not reused:
        if cache_only:
            raise FileNotFoundError('No current bound MMS ASR transcript cache')
        if (isinstance(stored, dict) and isinstance(stored.get('provenance'), dict)
                and stored['provenance'].get('runtime') != runtime):
            raise RuntimeError('Retained MMS ASR producer requires runtime verification; existing cache preserved')
        with WorkerTemporaryDirectory(prefix="native-asr-", dir=job_dir) as temp:
            output = Path(temp) / "output.private.json"
            with heavy_worker_stage({"projectDir": str(project)}), environment_lease('main', builder=project):
                completed = run_process_tree([
                    str(environment_python('main', project)), "-m", "stemwise_engine." + helper.stem, "--audio", str(audio),
                    "--model", str(model_cache / "models--facebook--mms-1b-all/snapshots" / REVISION),
                    "--output", str(output), "--device", device, "--adapter", adapter,
                ], text=True, capture_output=True, check=False, timeout=timeout, env=env)
            if completed.returncode != 0 or not output.is_file():
                # Retain only the useful private failure, not successful process logs.
                from stemwise_engine.private_diagnostics import retain, reference
                error_path, identity = retain(job_dir / f'native-asr-{adapter}-{route}-error.private.txt',
                    (completed.stderr or 'MMS ASR returned no output').encode('utf-8'))
                error = RuntimeError(f'MMS ASR worker failed; see {error_path.name}')
                error.private_diagnostics = (reference(error_path, identity),)
                raise error
            value = read_bounded_json(output, max_bytes=MAX_JSON_BYTES, root=Path(temp))
            validate_output(value, audio_identity["sha256"], duration_ms, adapter, runtime)
            stored = {"provenance": provenance, "workerOutput": value}
            atomic_write_json(cache, stored, root=job_dir)
    words = [WordTimestamp(word=normalize(w["raw"]), raw=w["raw"], start=w["start"], end=w["end"])
             for w in stored["workerOutput"]["words"] if normalize(w["raw"])]
    evidence = {**stored['provenance'], "audioViewId": route, "wordCount": len(words), "frameMs": 20}
    return words, evidence, reused


def _runtime_identity(device: str) -> dict:
    import importlib.metadata
    import platform
    import torch
    resolved = ('cuda' if torch.cuda.is_available() else 'cpu') if device == 'auto' else device
    if resolved == 'cuda' and not torch.cuda.is_available():
        raise ValueError('MMS ASR CUDA runtime is unavailable')
    return {**{name: importlib.metadata.version(name) for name in ('torch', 'torchaudio', 'transformers')},
            'python': platform.python_version(), 'resolvedDevice': resolved,
            'cuda': torch.version.cuda, 'cudnn': torch.backends.cudnn.version(),
            'deviceName': torch.cuda.get_device_name(0) if resolved == 'cuda' else 'cpu',
            'dtype': 'float32', 'recipe': 'mms-greedy-20ms-v1'}


def runtime_provenance(project: Path, device: str, timeout: int, env: dict) -> dict:
    with environment_lease('main', builder=project):
        result = run_process_tree([str(environment_python('main', project)), '-m', 'stemwise_engine.mms_asr',
            '--runtime-info', '--device', device], text=True, capture_output=True,
            check=True, timeout=min(timeout, 300), env=env)
    from stemwise_engine.worker_protocol import parse_worker_json
    value = parse_worker_json(result.stdout)
    if not isinstance(value, dict) or value.get('recipe') != 'mms-greedy-20ms-v1':
        raise ValueError('MMS ASR runtime probe is invalid')
    return value


def run(audio: Path, model_path: Path, output: Path, device: str, adapter: str = ADAPTER) -> None:
    import importlib.metadata
    import torch
    import torchaudio
    from transformers import Wav2Vec2CTCTokenizer, Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET, StreamedMonoAudio
    from stemwise_engine.wav2vec2_forced_alignment import _overlapped_emission_chunks

    for name, expected in ASSETS_BY_ADAPTER[adapter].items():
        if sha256_file(model_path / name) != expected:
            raise ValueError(f"MMS ASR asset differs: {name}")
    tokenizer = Wav2Vec2CTCTokenizer.from_pretrained(model_path, target_lang=adapter, local_files_only=True)
    extractor = Wav2Vec2FeatureExtractor.from_pretrained(model_path, local_files_only=True)
    if extractor.sampling_rate != 16000:
        raise ValueError("MMS ASR requires 16 kHz features")
    waveform = StreamedMonoAudio(audio, 16000)
    if waveform.sample_count < 400:
        raise ValueError("MMS ASR audio is shorter than one acoustic frame")
    CURRENT_BUDGET.require(retained_bytes=(
        min(waveform.frames, 30*waveform.source_rate)*waveform.channels*4
        + min(waveform.sample_count, 30*16000)*8 + 1502*len(tokenizer)*4))
    model = Wav2Vec2ForCTC.from_pretrained(model_path, target_lang=adapter, local_files_only=True, use_safetensors=True)
    if (model.target_lang != adapter or model.lm_head.out_features != len(tokenizer)
            or model.config.pad_token_id != tokenizer.pad_token_id):
        raise ValueError("MMS ASR language adapter was not loaded")
    resolved = ("cuda" if torch.cuda.is_available() else "cpu") if device == "auto" else device
    model.to(device=resolved, dtype=torch.float32).eval()
    duration_ms = waveform.frames * 1000 / waveform.source_rate
    stride = model.config.inputs_to_logits_ratio
    with torch.inference_mode():
        emissions, chunks, _ = _overlapped_emission_chunks(waveform, waveform.sample_count,
            extractor, model, resolved, torch.float32, stride, directory=output.parent)
    if any(chunk['targetStartFrame'] != chunk['emissionStartFrame']*stride for chunk in chunks):
        raise RuntimeError('MMS ASR emissions have a gap or shifted frame grid')
    words = decode_words(emissions.argmax(dim=-1).tolist(),
                         {v: k for k, v in tokenizer.get_vocab().items()},
                         tokenizer.pad_token_id, tokenizer.word_delimiter_token_id,
                         stride * 1000 / 16000, duration_ms)
    atomic_write_json(output, {
        "familyId": "mms-asr", "modelRevision": REVISION, "adapter": adapter,
        "assets": ASSETS_BY_ADAPTER[adapter], "audioSha256": sha256_file(audio), "durationMs": duration_ms,
        "frameMs": stride * 1000 / 16000, "words": words,
        "runtime": _runtime_identity(device),
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-info', action='store_true')
    parser.add_argument("--audio", type=Path)
    parser.add_argument("--model", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--adapter", choices=tuple(ASSETS_BY_ADAPTER))
    args = parser.parse_args()
    if args.runtime_info:
        print(json.dumps(_runtime_identity(args.device), allow_nan=False))
    else:
        if not all((args.audio, args.model, args.output, args.adapter)):
            parser.error('audio, model, output and adapter are required for transcription')
        run(args.audio, args.model, args.output, args.device, args.adapter)


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
