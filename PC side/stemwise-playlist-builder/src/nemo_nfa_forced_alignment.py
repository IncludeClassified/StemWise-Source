"""Closed local NeMo NFA worker for one approved lyric sequence."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import platform
import re
import signal
import subprocess
import sys
import tempfile
import traceback
import stemwise_engine.worker_protocol as worker_protocol
import unicodedata
from contextlib import ExitStack, redirect_stdout
from decimal import Decimal, InvalidOperation
from pathlib import Path
from stemwise_engine.model_registry import model_assets, runtime_profile, cuda_device_identity, validate_cuda_device
from stemwise_engine.worker_protocol import read_worker_json, parse_worker_json
from typing import Any

from stemwise_engine.common import heavy_worker_stage
from stemwise_engine.ctc_worker import execution_deadline, retire_model, source_duration_ms, project_eof_end


FORMAT_VERSION = 1
ENGINE = "nemo-nfa"
ROUTE = "nemo-nfa-arabic-fastconformer-v1"
NEMO_VERSION = runtime_profile('nemo_nfa_forced_alignment')['NEMO_VERSION']
NEMO_SOURCE_REVISION = model_assets('nemo_nfa_forced_alignment')['NEMO_SOURCE_REVISION']
NFA_ALIGN_SHA256 = model_assets('nemo_nfa_forced_alignment')['NFA_ALIGN_SHA256']
NFA_ALIGN_BYTE_COUNT = model_assets('nemo_nfa_forced_alignment')['NFA_ALIGN_BYTE_COUNT']
NEMO_LOCK_SHA256 = model_assets('nemo_nfa_forced_alignment')['NEMO_LOCK_SHA256']
NEMO_LOCK_BYTE_COUNT = model_assets('nemo_nfa_forced_alignment')['NEMO_LOCK_BYTE_COUNT']
PYTHON_VERSION = runtime_profile('nemo_nfa_forced_alignment')['PYTHON_VERSION']
TORCH_VERSION = runtime_profile('nemo_nfa_forced_alignment')['TORCH_VERSION']
CUDA_VERSION = runtime_profile('nemo_nfa_forced_alignment')['CUDA_VERSION']
SOUNDFILE_VERSION = runtime_profile('nemo_nfa_forced_alignment')['SOUNDFILE_VERSION']
LIBSNDFILE_VERSION = runtime_profile('nemo_nfa_forced_alignment')['LIBSNDFILE_VERSION']
ACOUSTIC_TIMESTEP_MS = model_assets('nemo_nfa_forced_alignment')['ACOUSTIC_TIMESTEP_MS']
MODEL_REVISION = model_assets('nemo_nfa_forced_alignment')['MODEL_REVISION']
MODEL_ARTIFACT = model_assets('nemo_nfa_forced_alignment')['MODEL_ARTIFACT']
MODEL_SHA256 = model_assets('nemo_nfa_forced_alignment')['MODEL_SHA256']
MODEL_BYTE_COUNT = model_assets('nemo_nfa_forced_alignment')['MODEL_BYTE_COUNT']
FROZEN_CONFIG = {
    "alignUsingPredText": False,
    "transcribeDevice": "cuda:0",
    "viterbiDevice": "cpu",
    "batchSize": 1,
    "useLocalAttention": True,
    "useBufferedChunkedStreaming": False,
    "simulateCacheAwareStreaming": False,
    "additionalSegmentGroupingSeparator": None,
    "saveOutputFileFormats": ["ctm"],
    "minimumTimestampDuration": 0,
    "channelMix": "informative-recording-view-float32-v1",
}
_SHA256 = re.compile(r"[0-9a-f]{64}")
_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{label} must contain exactly {sorted(expected)}")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _IDENTIFIER.fullmatch(value) is None:
        raise ValueError(f"NeMo NFA {label} is malformed")
    return value


def _normalized_words(value: Any) -> list[str]:
    if not isinstance(value, str):
        raise ValueError("NeMo NFA acoustic word is malformed")
    normalized = unicodedata.normalize("NFKC", value)
    words = normalized.split()
    if not words:
        raise ValueError("NeMo NFA acoustic word is empty")
    return words


def _normalized_word(value: Any) -> str:
    words = _normalized_words(value)
    if len(words) != 1:
        raise ValueError("NeMo NFA CTM unit must contain exactly one word")
    return words[0]


def _validated_request(value: Any) -> dict[str, Any]:
    request = _exact_keys(
        value,
        {"formatVersion", "route", "durationMs", "audio", "model", "nfa", "lines", "cudaDevice"},
        "request",
    )
    if request["formatVersion"] != FORMAT_VERSION or request["route"] != ROUTE:
        raise ValueError("NeMo NFA request identity is malformed")
    validate_cuda_device(request['cudaDevice'], 'nemo_nfa_forced_alignment')
    if type(request["durationMs"]) is not int or request["durationMs"] <= 0:
        raise ValueError("NeMo NFA duration is malformed")
    audio = _exact_keys(request["audio"], {"path", "sha256", "byteCount"}, "audio")
    model = _exact_keys(
        request["model"], {"path", "revision", "sha256", "byteCount"}, "model"
    )
    nfa = _exact_keys(
        request["nfa"],
        {
            "sourceRoot",
            "sourceRevision",
            "alignPath",
            "alignSha256",
            "lockPath",
            "lockSha256",
            "lockByteCount",
        },
        "nfa",
    )
    for label, record in (("audio", audio), ("model", model)):
        if not isinstance(record["path"], str) or not record["path"]:
            raise ValueError(f"NeMo NFA {label} path is malformed")
        if _SHA256.fullmatch(record["sha256"]) is None:
            raise ValueError(f"NeMo NFA {label} SHA-256 is malformed")
        if type(record["byteCount"]) is not int or record["byteCount"] <= 0:
            raise ValueError(f"NeMo NFA {label} byte count is malformed")
    if (
        model["revision"] != MODEL_REVISION
        or model["sha256"] != MODEL_SHA256
        or model["byteCount"] != MODEL_BYTE_COUNT
    ):
        raise ValueError("NeMo NFA model identity is not pinned")
    expected_nfa = {
        "sourceRevision": NEMO_SOURCE_REVISION,
        "alignSha256": NFA_ALIGN_SHA256,
        "lockSha256": NEMO_LOCK_SHA256,
        "lockByteCount": NEMO_LOCK_BYTE_COUNT,
    }
    if any(nfa[key] != expected for key, expected in expected_nfa.items()):
        raise ValueError("NeMo NFA source identity is not pinned")
    for key in ("sourceRoot", "alignPath", "lockPath"):
        if not isinstance(nfa[key], str) or not nfa[key]:
            raise ValueError("NeMo NFA source path is malformed")

    lines = request["lines"]
    if not isinstance(lines, list) or not lines:
        raise ValueError("NeMo NFA request has no approved lines")
    line_ids: set[str] = set()
    word_ids: set[str] = set()
    for line in lines:
        _exact_keys(line, {"sourcePhysicalIndex", "lineId", "words"}, "line")
        source = line["sourcePhysicalIndex"]
        if type(source) is not int or source < 0:
            raise ValueError("NeMo NFA source line index is malformed")
        line_id = _identifier(line["lineId"], "line ID")
        if line_id in line_ids:
            raise ValueError("NeMo NFA line IDs are not unique")
        line_ids.add(line_id)
        if not isinstance(line["words"], list) or not line["words"]:
            raise ValueError("NeMo NFA approved line has no words")
        for word in line["words"]:
            keys = {"wordId", "acousticText"}
            if isinstance(word, dict) and "acousticLanguage" in word:
                keys.add("acousticLanguage")
            _exact_keys(word, keys, "word")
            if "acousticLanguage" in word:
                from stemwise_engine.lyrics_alignment import canonical_language
                if canonical_language(word["acousticLanguage"]) != "ar":
                    raise ValueError("NeMo NFA acoustic language is unsupported by the Arabic route")
            word_id = _identifier(word["wordId"], "word ID")
            if word_id in word_ids:
                raise ValueError("NeMo NFA word IDs are not unique")
            word_ids.add(word_id)
            _normalized_words(word["acousticText"])
    return request


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bound_file(path_value: str, sha256: str, byte_count: int, label: str) -> Path:
    path = Path(path_value)
    if not path.is_absolute():
        raise ValueError("NeMo NFA accepts absolute local paths only")
    path = path.resolve(strict=True)
    if not path.is_file() or path.stat().st_size != byte_count or _sha256_file(path) != sha256:
        raise ValueError(f"NeMo NFA {label} content binding does not match")
    return path


def _audio_info(path: Path):
    try:
        import soundfile

        info = soundfile.info(str(path))
    except Exception as error:
        raise ValueError("NeMo NFA audio is not a valid WAV") from error
    if (
        soundfile.__version__ != SOUNDFILE_VERSION
        or soundfile.__libsndfile_version__ != LIBSNDFILE_VERSION
        or info.format not in {"WAV", "WAVEX", "RF64"}
        or type(info.samplerate) is not int
        or info.samplerate <= 0
        or type(info.frames) is not int
        or info.frames <= 0
        or type(info.channels) is not int
        or info.channels <= 0
    ):
        raise ValueError("NeMo NFA audio stream is malformed")
    return info


def _wav_duration_ms(path: Path) -> int:
    info = _audio_info(path)
    return source_duration_ms(info.frames, info.samplerate)


def _prepare_mono_audio(source: Path, target: Path) -> dict[str, Any]:
    import soundfile

    import numpy as np
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET, audio_mono_representation
    source_info = _audio_info(source)
    if source_info.channels not in (1, 2):
        raise ValueError('NeMo NFA requires mono or stereo audio')
    CURRENT_BUDGET.require(retained_bytes=min(source_info.frames, 65536)*source_info.channels*4)
    sample_rate = source_info.samplerate
    representation = audio_mono_representation(source)
    count = 0
    with soundfile.SoundFile(source) as decoded, soundfile.SoundFile(
            target, mode='w', samplerate=sample_rate, channels=1, format='WAV', subtype='FLOAT') as output:
        for samples in decoded.blocks(blocksize=65536, dtype='float32', always_2d=True):
            if not np.isfinite(samples).all():
                raise ValueError('NeMo NFA decoded audio contains nonfinite samples')
            output.write(samples.mean(axis=1, dtype='float32') if representation in ('mid', 'mono')
                         else samples[:, 0 if representation == 'left' else 1])
            count += len(samples)
    if count != source_info.frames:
        raise ValueError('NeMo NFA decoded audio differs from its bound stream')
    target_info = _audio_info(target)
    if (
        target_info.frames != source_info.frames
        or target_info.samplerate != source_info.samplerate
        or target_info.channels != 1
    ):
        raise ValueError("NeMo NFA mono preparation changed the audio timeline")
    return {
        "method": "informative-recording-view-float32-v1",
        "representation": representation,
        "sourceChannelCount": source_info.channels,
        "outputChannelCount": 1,
        "sampleRate": sample_rate,
        "frameCount": source_info.frames,
        "sha256": _sha256_file(target),
        "byteCount": target.stat().st_size,
    }


def _resolved_bindings(
    request_path: Path, request_sha256: str, request: dict[str, Any]
) -> tuple[Path, Path, Path, Path, Path]:
    if not request_path.is_absolute() or _SHA256.fullmatch(request_sha256) is None:
        raise ValueError("NeMo NFA request must be an absolute hash-bound local path")
    request_path = request_path.resolve(strict=True)
    if not request_path.is_file() or _sha256_file(request_path) != request_sha256:
        raise ValueError("NeMo NFA request content binding does not match")
    audio = _bound_file(
        request["audio"]["path"], request["audio"]["sha256"], request["audio"]["byteCount"], "audio"
    )
    if _wav_duration_ms(audio) != request["durationMs"]:
        raise ValueError("NeMo NFA audio duration binding does not match")
    model = _bound_file(
        request["model"]["path"], request["model"]["sha256"], request["model"]["byteCount"], "model"
    )
    align = _bound_file(
        request["nfa"]["alignPath"],
        request["nfa"]["alignSha256"],
        NFA_ALIGN_BYTE_COUNT,
        "aligner",
    )
    lock = _bound_file(
        request["nfa"]["lockPath"], request["nfa"]["lockSha256"], request["nfa"]["lockByteCount"], "lock"
    )
    source_root = Path(request["nfa"]["sourceRoot"])
    if not source_root.is_absolute():
        raise ValueError("NeMo NFA accepts absolute local paths only")
    source_root = source_root.resolve(strict=True)
    if (
        not source_root.is_dir()
        or align != source_root / "tools" / "nemo_forced_aligner" / "align.py"
        or lock != source_root / "uv.lock"
        or model.name != MODEL_ARTIFACT
    ):
        raise ValueError("NeMo NFA local layout is malformed")
    return request_path, audio, model, align, source_root


def _flatten(request: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str]]:
    records: list[dict[str, Any]] = []
    words: list[str] = []
    for line_index, line in enumerate(request["lines"]):
        start = len(words)
        line_words = []
        for word in line["words"]:
            units = _normalized_words(word["acousticText"])
            line_words.append({"wordId": word["wordId"], "startWord": len(words), "wordCount": len(units)})
            words.extend(units)
        records.append(
            {
                "lineIndex": line_index,
                "sourcePhysicalIndex": line["sourcePhysicalIndex"],
                "lineId": line["lineId"],
                "startWord": start,
                "wordCount": len(words) - start,
                "words": line_words,
            }
        )
    return records, words


def _decimal_ms(value: str, label: str) -> int:
    try:
        milliseconds = Decimal(value) * 1000
    except InvalidOperation as error:
        raise ValueError(f"NeMo NFA CTM {label} is malformed") from error
    if not milliseconds.is_finite() or milliseconds < 0 or milliseconds != milliseconds.to_integral_value():
        raise ValueError(f"NeMo NFA CTM {label} is malformed")
    return int(milliseconds)


def _parse_word_ctm(
    path: Path, expected_source: str, expected_words: list[str], duration_ms: int
) -> list[dict[str, int]]:
    parsed: list[dict[str, Any]] = []
    previous_end = 0
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ValueError("NeMo NFA word CTM is unavailable") from error
    for raw in lines:
        fields = raw.split()
        if len(fields) != 8:
            raise ValueError("NeMo NFA word CTM record is malformed")
        source, channel, start_raw, duration_raw, word, confidence, token_type, speaker = fields
        if (
            source != expected_source
            or channel != "1"
            or confidence != "NA"
            or token_type != "lex"
            or speaker != "NA"
        ):
            raise ValueError("NeMo NFA word CTM identity is malformed")
        start_ms = _decimal_ms(start_raw, "start")
        span_ms = _decimal_ms(duration_raw, "duration")
        end_ms = start_ms + span_ms
        if span_ms <= 0 or start_ms < previous_end:
            raise ValueError("NeMo NFA word CTM timings are not ordered")
        end_ms = project_eof_end(start_ms, end_ms, duration_ms)
        parsed.append({"startMs": start_ms, "endMs": end_ms, "word": _normalized_word(word)})
        previous_end = end_ms
    if [record["word"] for record in parsed] != expected_words:
        raise ValueError("NeMo NFA changed the approved normalized word sequence or count")
    return [{"startMs": record["startMs"], "endMs": record["endMs"]} for record in parsed]


def _regroup(
    records: list[dict[str, Any]], timings: list[dict[str, int]]
) -> list[dict[str, Any]]:
    lines = []
    for record in records:
        start = record["startWord"]
        selected = timings[start : start + record["wordCount"]]
        if len(selected) != record["wordCount"]:
            raise ValueError("NeMo NFA result changed the approved word count")
        lines.append(
            {
                "lineIndex": record["lineIndex"],
                "sourcePhysicalIndex": record["sourcePhysicalIndex"],
                "lineId": record["lineId"],
                "startMs": selected[0]["startMs"],
                "endMs": selected[-1]["endMs"],
                "words": [
                    {"wordId": planned["wordId"],
                     "startMs": timings[planned["startWord"]]["startMs"],
                     "endMs": timings[planned["startWord"] + planned["wordCount"] - 1]["endMs"]}
                    for planned in record["words"]
                ],
            }
        )
    return lines


def _atomic_json(path: Path, value: Any) -> None:
    from stemwise_engine.worker_publication import publish_json
    publish_json(path, value, sort_keys=True, separators=(",", ":"))

def _runtime_identity() -> dict[str, Any]:
    if platform.python_version() != PYTHON_VERSION:
        raise RuntimeError("NeMo NFA Python runtime is not pinned")
    try:
        nemo_version = importlib.metadata.version("nemo_toolkit")
    except importlib.metadata.PackageNotFoundError as error:
        raise RuntimeError("NeMo NFA runtime is unavailable") from error
    if nemo_version != NEMO_VERSION:
        raise RuntimeError("NeMo NFA package runtime is not pinned")
    try:
        import torch
    except ImportError as error:
        raise RuntimeError("NeMo NFA CUDA runtime is unavailable") from error
    if (
        torch.__version__ != TORCH_VERSION
        or torch.version.cuda != CUDA_VERSION
        or not torch.cuda.is_available()
    ):
        raise RuntimeError("NeMo NFA CUDA runtime is not pinned")
    return {
        "python": platform.python_version(),
        "nemoToolkit": nemo_version,
        "torch": torch.__version__,
        "cudaRuntime": torch.version.cuda,
        "cudaDevice": cuda_device_identity('nemo_nfa_forced_alignment', torch_module=torch),
    }


def _verify_source_checkout(source_root: Path) -> None:
    head = subprocess.run(
        [
            "git",
            "-c",
            "core.autocrlf=true",
            "-C",
            str(source_root),
            "rev-parse",
            "--verify",
            "HEAD^{commit}",
        ],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if head.returncode != 0 or head.stdout.decode("ascii", "strict").strip() != NEMO_SOURCE_REVISION:
        raise RuntimeError("NeMo NFA source checkout revision is not pinned")
    clean = subprocess.run(
        [
            "git",
            "-c",
            "core.autocrlf=true",
            "-C",
            str(source_root),
            "status",
            "--porcelain=v1",
            "--untracked-files=all",
        ],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if clean.returncode != 0 or clean.stdout:
        raise RuntimeError("NeMo NFA source checkout is not clean")


def _load_nfa_module(align):
    name = '_stemwise_native_nfa'
    spec = importlib.util.spec_from_file_location(name, align)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def _execute_nfa(command, source_root, environment):
    import torch

    previous_cwd, previous_args, previous_path = Path.cwd(), sys.argv, sys.path[:]
    previous_environment = {key: os.environ.get(key) for key in environment}
    state = {}
    batches = 0
    with ExitStack() as lease:
        def retire():
            if state:
                try:
                    with execution_deadline(30):
                        state['model'].to('cpu')
                        retire_model(state)
                except BaseException:
                    worker_protocol.fatal_model_failure('nfa-model-retirement-failed')
                lease.close()
        try:
            os.environ.update(environment)
            os.chdir(source_root)
            sys.path[:0] = [str(Path(command[1]).parent), str(source_root)]
            sys.argv = command[1:]
            with redirect_stdout(sys.stderr):
                module = _load_nfa_module(Path(command[1]))
                setup, emit, decode = module.setup_model, module.get_batch_variables, module.viterbi_decoding
                def setup_model(*args, **kwargs):
                    if state or batches:
                        raise RuntimeError('NeMo requires exactly one model and input batch')
                    timer = signal.setitimer(signal.ITIMER_REAL, 0)
                    try:
                        lease.enter_context(heavy_worker_stage({'projectDir': str(Path(__file__).resolve().parent.parent)}))
                    finally:
                        if timer[0] > 0:
                            signal.setitimer(signal.ITIMER_REAL, timer[0], timer[1])
                    try:
                        result = setup(*args, **kwargs)
                        state.update(model=result[0], cuda_model=True)
                    except BaseException:
                        worker_protocol.fatal_model_failure('nfa-model-initialization-failed')
                    return result
                def get_batch_variables(*args, **kwargs):
                    nonlocal batches
                    if not state or batches:
                        raise RuntimeError('NeMo requires exactly one input batch')
                    try:
                        result = emit(*args, **kwargs)
                        result = tuple(value.cpu() if isinstance(value, torch.Tensor) else value for value in result)
                    except BaseException:
                        worker_protocol.fatal_model_failure('nfa-model-emission-failed')
                    retire()
                    batches += 1
                    return result
                def viterbi_decoding(*args, **kwargs):
                    if state or batches != 1:
                        raise RuntimeError('NeMo CPU decoding still owns a model')
                    threads = torch.get_num_threads()
                    try:
                        torch.set_num_threads(1)
                        return decode(*args, **kwargs)
                    finally:
                        torch.set_num_threads(threads)
                module.setup_model, module.get_batch_variables, module.viterbi_decoding = setup_model, get_batch_variables, viterbi_decoding
                from omegaconf import OmegaConf
                cfg = OmegaConf.merge(OmegaConf.structured(module.AlignmentConfig),
                                      OmegaConf.from_dotlist(command[2:]))
                module.main(cfg)
                if batches != 1:
                    raise RuntimeError('NeMo did not produce its required input batch')
        except BaseException as error:
            traceback.clear_frames(error.__traceback__)
            raise
        finally:
            retire()
            os.chdir(previous_cwd)
            sys.argv, sys.path[:] = previous_args, previous_path
            for key, value in previous_environment.items():
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value


def _run_nfa(
    audio: Path,
    model: Path,
    align: Path,
    source_root: Path,
    words: list[str],
    duration_ms: int,
    private_root: Path,
) -> tuple[list[dict[str, int]], dict[str, Any]]:
    private_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="stemwise-nfa-", dir=private_root) as directory:
        private = Path(directory)
        prepared_audio = private / "nfa-input.wav"
        preprocessing = _prepare_mono_audio(audio, prepared_audio)
        manifest = private / "request.jsonl"
        output = private / "output"
        manifest.write_text(
            json.dumps(
                {"audio_filepath": str(prepared_audio), "text": " ".join(words)},
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        command = [
            sys.executable,
            str(align),
            f"model_path={model}",
            "pretrained_name=null",
            f"manifest_filepath={manifest}",
            f"output_dir={output}",
            "align_using_pred_text=false",
            "transcribe_device=cuda:0",
            "viterbi_device=cpu",
            "batch_size=1",
            "use_local_attention=true",
            "use_buffered_chunked_streaming=false",
            "simulate_cache_aware_streaming=false",
            "additional_segment_grouping_separator=null",
            "save_output_file_formats=[ctm]",
            "ctm_file_config.remove_blank_tokens=true",
            "ctm_file_config.minimum_timestamp_duration=0",
        ]
        environment = dict(os.environ)
        environment.update(
            {
                "HF_HUB_OFFLINE": "1",
                "TRANSFORMERS_OFFLINE": "1",
                "HF_DATASETS_OFFLINE": "1",
                "TOKENIZERS_PARALLELISM": "false",
                "WANDB_MODE": "disabled",
                "HYDRA_FULL_ERROR": "0",
                "PYTHONPATH": str(source_root),
            }
        )
        _execute_nfa(command, source_root, environment)
        candidates = list((output / "ctm" / "words").glob("*.ctm"))
        if len(candidates) != 1:
            raise RuntimeError("NeMo NFA did not produce exactly one word CTM")
        return (
            _parse_word_ctm(
                candidates[0],
                prepared_audio.stem.replace(" ", "-"),
                words,
                duration_ms,
            ),
            preprocessing,
        )


def run(request_path: Path, request_sha256: str, output_path: Path) -> None:
    if not output_path.is_absolute():
        raise ValueError("NeMo NFA output path must be absolute")
    raw = request_path.read_bytes() if request_path.is_absolute() else b""
    if _SHA256.fullmatch(request_sha256) is None or hashlib.sha256(raw).hexdigest() != request_sha256:
        raise ValueError("NeMo NFA request content binding does not match")
    request = _validated_request(parse_worker_json(raw))
    request_path, audio, model, align, source_root = _resolved_bindings(
        request_path, request_sha256, request
    )
    output_path = output_path.resolve(strict=False)
    if output_path in {
        request_path,
        audio,
        model,
        align,
        source_root / "uv.lock",
    } or output_path.is_relative_to(source_root):
        raise ValueError("NeMo NFA output would overwrite an input")
    records, words = _flatten(request)
    runtime = _runtime_identity()
    if runtime['cudaDevice'] != request['cudaDevice']:
        raise RuntimeError('NeMo NFA CUDA device changed after request admission')
    _verify_source_checkout(source_root)
    timings, preprocessing = _run_nfa(
        audio,
        model,
        align,
        source_root,
        words,
        request["durationMs"],
        output_path.parent,
    )
    lines = _regroup(records, timings)
    _atomic_json(
        output_path,
        {
            "formatVersion": FORMAT_VERSION,
            "engine": ENGINE,
            "route": ROUTE,
            "request": {"sha256": request_sha256, "byteCount": len(raw)},
            "durationMs": request["durationMs"],
            "audio": {key: request["audio"][key] for key in ("sha256", "byteCount")},
            "model": {key: request["model"][key] for key in ("revision", "sha256", "byteCount")},
            "nfa": {
                "sourceRevision": NEMO_SOURCE_REVISION,
                "alignSha256": NFA_ALIGN_SHA256,
                "lockSha256": NEMO_LOCK_SHA256,
            },
            "runtime": runtime,
            "config": dict(FROZEN_CONFIG),
            "preprocessing": preprocessing,
            "acousticTimestepMs": ACOUSTIC_TIMESTEP_MS,
            "wordCount": len(words),
            "lineCount": len(lines),
            "lines": lines,
        },
    )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--request", type=Path, required=True)
    result.add_argument("--request-sha256", required=True)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--timeout-seconds", type=int, default=10_800)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if not 0 < arguments.timeout_seconds <= 86_400:
            raise ValueError('NeMo stage deadline is invalid')
        with execution_deadline(arguments.timeout_seconds):
            run(arguments.request, arguments.request_sha256, arguments.output)
    except Exception as error:
        traceback.print_exc(file=sys.stderr)
        from stemwise_engine.worker_protocol import emit_worker_failure
        emit_worker_failure(error)
        return 1
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
