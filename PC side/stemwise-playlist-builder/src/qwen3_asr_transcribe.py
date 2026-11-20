"""Private, local-only Qwen3-ASR transcription worker."""

from __future__ import annotations
from stemwise_engine.worker_protocol import WorkerReasonError

import argparse
import hashlib
import json
import os
import platform
import re
import sys
import tempfile
from pathlib import Path
from stemwise_engine.worker_protocol import read_worker_json, parse_worker_json
from typing import Any


FORMAT_VERSION = 2
ENGINE = "qwen3-asr-transformers"
PARSER_ID = "qwen3-asr-lossless-leading-structure-v1"
DECODE_SETTINGS = {"skip_special_tokens": False, "clean_up_tokenization_spaces": False}
DECODER = {
    "strategy": "greedy",
    "doSample": False,
    "numBeams": 1,
    "maxNewTokens": 4096,
    "seed": 0,
    "conditioning": "audio-and-language-only",
    "languageMode": "authority-or-auto-detect",
    "timeOutput": "disabled",
    "torchDeterministicAlgorithms": True,
    "lexicalParser": PARSER_ID,
    "tokenizerDecode": DECODE_SETTINGS,
}
_FORBIDDEN_REQUEST_KEYS = frozenset(
    {
        "lyric",
        "lyrics",
        "text",
        "prompt",
        "hotword",
        "hotwords",
        "context",
        "timestamp",
        "timestamps",
    }
)
_SHA256 = re.compile(r"[0-9a-f]{64}")
_REVISION = re.compile(r"[0-9a-f]{40}")


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{label} must contain exactly {sorted(expected)}")
    return value


def _reject_conditioning_keys(value: Any) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if isinstance(key, str) and key.casefold() in _FORBIDDEN_REQUEST_KEYS:
                raise ValueError(f"Qwen3-ASR request field {key!r} is prohibited")
            _reject_conditioning_keys(child)
    elif isinstance(value, list):
        for child in value:
            _reject_conditioning_keys(child)


def _validated_request(value: Any) -> dict[str, Any]:
    _reject_conditioning_keys(value)
    request = _exact_keys(
        value, {"formatVersion", "audio", "language", "model"}, "request"
    )
    if request["formatVersion"] != FORMAT_VERSION:
        raise ValueError("Qwen3-ASR request format version is unsupported")
    audio = _exact_keys(request["audio"], {"path", "sha256", "byteCount"}, "audio")
    model = _exact_keys(
        request["model"],
        {"path", "revision", "snapshotSha256"},
        "model",
    )
    if not isinstance(audio["path"], str) or not audio["path"]:
        raise ValueError("Qwen3-ASR audio path is malformed")
    if not isinstance(model["path"], str) or not model["path"]:
        raise ValueError("Qwen3-ASR model path is malformed")
    if _SHA256.fullmatch(audio["sha256"]) is None:
        raise ValueError("Qwen3-ASR audio SHA-256 is malformed")
    if type(audio["byteCount"]) is not int or audio["byteCount"] <= 0:
        raise ValueError("Qwen3-ASR audio byte count is malformed")
    if _REVISION.fullmatch(model["revision"]) is None:
        raise ValueError("Qwen3-ASR model revision is malformed")
    if _SHA256.fullmatch(model["snapshotSha256"]) is None:
        raise ValueError("Qwen3-ASR model snapshot SHA-256 is malformed")
    if request["language"] is not None and (
        not isinstance(request["language"], str)
        or re.fullmatch(r"[a-z]{2,3}", request["language"]) is None
    ):
        raise ValueError("Qwen3-ASR language is malformed")
    return request


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _snapshot_sha256(path: Path) -> str:
    """Hash logical names, sizes, and contents of a local model snapshot."""

    digest = hashlib.sha256()
    files = sorted(candidate for candidate in path.rglob("*") if candidate.is_file())
    if not files:
        raise ValueError("Qwen3-ASR model snapshot is empty")
    content_hashes: dict[tuple[str, int], str] = {}
    for candidate in files:
        logical_name = candidate.relative_to(path).as_posix()
        resolved = candidate.resolve(strict=True)
        size = resolved.stat().st_size
        cache_key = (str(resolved), size)
        content_sha256 = content_hashes.get(cache_key)
        if content_sha256 is None:
            content_sha256 = content_hashes[cache_key] = _sha256_file(resolved)
        digest.update(logical_name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(str(size).encode("ascii"))
        digest.update(b"\0")
        digest.update(content_sha256.encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def _resolved_bindings(request: dict[str, Any]) -> tuple[Path, Path]:
    audio = Path(request["audio"]["path"])
    model = Path(request["model"]["path"])
    if not audio.is_absolute() or not model.is_absolute():
        raise ValueError("Qwen3-ASR accepts absolute local paths only")
    audio = audio.resolve(strict=True)
    model = model.resolve(strict=True)
    if not audio.is_file() or not model.is_dir():
        raise ValueError("Qwen3-ASR audio or model snapshot has the wrong type")
    if model.name != request["model"]["revision"]:
        raise ValueError("Qwen3-ASR model path is not the requested snapshot revision")
    if audio.stat().st_size != request["audio"]["byteCount"]:
        raise ValueError("Qwen3-ASR audio byte count does not match")
    if _sha256_file(audio) != request["audio"]["sha256"]:
        raise ValueError("Qwen3-ASR audio SHA-256 does not match")
    if _snapshot_sha256(model) != request["model"]["snapshotSha256"]:
        raise ValueError("Qwen3-ASR model snapshot SHA-256 does not match")
    return audio, model


def _runtime_identity(device_name: str) -> dict[str, Any]:
    import torch
    import transformers

    cuda_available = bool(torch.cuda.is_available())
    resolved = device_name if device_name != "auto" else ("cuda" if cuda_available else "cpu")
    if resolved == "cuda" and not cuda_available:
        raise WorkerReasonError("cuda-unavailable", "CUDA was requested but is unavailable")
    return {
        "runtimeVersion": 1,
        "python": platform.python_version(),
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "torch": str(torch.__version__),
        "transformers": str(transformers.__version__),
        "requestedDevice": device_name,
        "resolvedDevice": resolved,
        "cudaAvailable": cuda_available,
        "cudaDeviceName": str(torch.cuda.get_device_name(0)) if cuda_available else None,
        "cudaRuntime": str(getattr(torch.version, "cuda", None)),
        "cudnn": str(torch.backends.cudnn.version()),
    }


def runtime_info(device_name: str) -> dict[str, Any]:
    from transformers.models.qwen3_asr.processing_qwen3_asr import LANGUAGE_CODE_TO_NAME
    return {
        "workerFormatVersion": FORMAT_VERSION,
        "engine": ENGINE,
        "runtime": _runtime_identity(device_name),
        "decoder": dict(DECODER),
        "supportedLanguages": sorted(LANGUAGE_CODE_TO_NAME),
    }


def _validated_output(value: Any, request: dict[str, Any], *, require_complete=True) -> dict[str, Any]:
    output = _exact_keys(
        value,
        {
            "formatVersion",
            "engine",
            "audio",
            "language",
            "result",
            "runtime",
            "model",
            "decoder",
        },
        "output",
    )
    if output["formatVersion"] != FORMAT_VERSION or output["engine"] != ENGINE:
        raise ValueError("Qwen3-ASR output identity is malformed")
    audio = _exact_keys(output["audio"], {"sha256", "byteCount"}, "output audio")
    if audio != {key: request["audio"][key] for key in ("sha256", "byteCount")}:
        raise ValueError("Qwen3-ASR output audio binding does not match")
    if output["language"] != request["language"]:
        raise ValueError("Qwen3-ASR output language binding does not match")
    result = _exact_keys(output["result"], {"detectedLanguage", "transcription", "observation"}, "result")
    _validate_observation(result, request["language"], require_complete=require_complete)
    language = result["detectedLanguage"]
    if language is not None and (not isinstance(language, str) or not language.strip()):
        raise ValueError("Qwen3-ASR detected language is malformed")
    if result["transcription"] is not None and not isinstance(result["transcription"], str):
        raise ValueError("Qwen3-ASR transcription is malformed")
    runtime = _exact_keys(
        output["runtime"],
        {
            "runtimeVersion",
            "python",
            "implementation",
            "platform",
            "machine",
            "torch",
            "transformers",
            "requestedDevice",
            "resolvedDevice",
            "cudaAvailable",
            "cudaDeviceName",
            "cudaRuntime",
            "cudnn",
        },
        "runtime",
    )
    if runtime["runtimeVersion"] != 1:
        raise ValueError("Qwen3-ASR runtime identity is malformed")
    for key in ("python", "implementation", "platform", "machine", "torch", "transformers"):
        if not isinstance(runtime[key], str) or not runtime[key]:
            raise ValueError("Qwen3-ASR runtime identity is malformed")
    if runtime["requestedDevice"] not in {"auto", "cpu", "cuda"} or runtime["resolvedDevice"] not in {
        "cpu",
        "cuda",
    }:
        raise ValueError("Qwen3-ASR runtime device identity is malformed")
    if type(runtime["cudaAvailable"]) is not bool:
        raise ValueError("Qwen3-ASR runtime CUDA identity is malformed")
    expected_device = runtime["requestedDevice"]
    if expected_device == "auto":
        expected_device = "cuda" if runtime["cudaAvailable"] else "cpu"
    if runtime["resolvedDevice"] != expected_device:
        raise ValueError("Qwen3-ASR runtime device identity is inconsistent")
    for key in ("cudaDeviceName", "cudaRuntime", "cudnn"):
        if runtime[key] is not None and not isinstance(runtime[key], str):
            raise ValueError("Qwen3-ASR runtime CUDA identity is malformed")
    model = _exact_keys(
        output["model"],
        {"revision", "snapshotSha256", "modelType", "modelClass"},
        "output model",
    )
    if model["revision"] != request["model"]["revision"] or model["snapshotSha256"] != request["model"][
        "snapshotSha256"
    ]:
        raise ValueError("Qwen3-ASR output model binding does not match")
    if model["modelType"] != "qwen3_asr" or not isinstance(model["modelClass"], str) or not model["modelClass"]:
        raise ValueError("Qwen3-ASR output model identity is malformed")
    if output["decoder"] != DECODER:
        raise ValueError("Qwen3-ASR output decoder identity is malformed")
    return output


def _atomic_json(path: Path, value: Any) -> None:
    from stemwise_engine.worker_publication import publish_json
    publish_json(path, value, sort_keys=True, separators=(",", ":"))

def _lexical_projection(decoded: str, language: str | None, language_names) -> tuple[str | None, str, list]:
    if language is not None:
        return None, decoded, []
    match = re.match(r"language ([^\r\n<>]+)<asr_text>", decoded)
    if match is None or match[1] not in set(language_names) | {"None"}:
        raise ValueError("qwen-asr-invalid-leading-structure")
    return (None if match[1] == "None" else match[1], decoded[match.end():],
            [{"kind": "leading-language-structure", "start": 0, "end": match.end()}])


def _generation_result(tokens, tokenizer, language, eos_token_id, *, language_names, upstream_parsed=None):
    """Retain the generation before deriving lexical text; never run cleanup on it."""
    ids = [int(token) for token in tokens]
    eos = [eos_token_id] if type(eos_token_id) is int else eos_token_id
    if not isinstance(eos, list) or not eos or any(type(token) is not int for token in eos):
        eos = []
    raw = tokenizer.decode(ids, **DECODE_SETTINGS)
    complete = bool(eos) and all(type(token) is int for token in eos) and bool(ids) and ids[-1] in eos
    body_ids = ids[:-1] if complete else ids
    body = tokenizer.decode(body_ids, **DECODE_SETTINGS)
    status = ("completion-token-unavailable" if not eos else "complete" if complete
        else "truncated" if len(ids) >= DECODER["maxNewTokens"] else "incomplete")
    detected, lexical, edits = None, None, []
    if complete:
        try:
            detected, lexical, edits = _lexical_projection(body, language, language_names)
        except ValueError:
            status = "invalid-structure"
    observation = {"parserId": PARSER_ID, "decodeSettings": dict(DECODE_SETTINGS),
        "requestMode": "automatic-language" if language is None else "forced-language",
        "generatedTokenIds": ids, "decodedText": raw, "bodyDecodedText": body,
        "languageNames": sorted(set(language_names)), "completion": {"state": status,
            "eosTokenIds": eos, "completionTokenSpan": [len(ids)-1, len(ids)] if complete else None},
        "lexicalEdits": edits, "upstreamParsed": upstream_parsed}
    return {"detectedLanguage": detected, "transcription": lexical, "observation": observation}


def _validate_observation(result, language, *, require_complete):
    value = _exact_keys(result["observation"], {"parserId", "decodeSettings", "requestMode",
        "generatedTokenIds", "decodedText", "bodyDecodedText", "languageNames", "completion",
        "lexicalEdits", "upstreamParsed"}, "raw observation")
    ids, names = value["generatedTokenIds"], value["languageNames"]
    completion = _exact_keys(value["completion"], {"state", "eosTokenIds", "completionTokenSpan"}, "completion")
    eos = completion["eosTokenIds"]
    if (value["parserId"] != PARSER_ID or value["decodeSettings"] != DECODE_SETTINGS
            or value["requestMode"] != ("automatic-language" if language is None else "forced-language")
            or not isinstance(ids, list) or len(ids) > DECODER["maxNewTokens"]
            or any(type(token) is not int or token < 0 for token in ids)
            or not isinstance(names, list) or any(not isinstance(name, str) or not name for name in names)
            or names != sorted(set(names)) or not isinstance(value["decodedText"], str)
            or not isinstance(value["bodyDecodedText"], str)
            or not isinstance(eos, list) or any(type(token) is not int for token in eos)):
        raise ValueError("Qwen3-ASR raw observation identity is malformed")
    completed = bool(ids) and ids[-1] in eos
    if completion["completionTokenSpan"] != ([len(ids)-1, len(ids)] if completed else None):
        raise ValueError("Qwen3-ASR completion token span is malformed")
    state = ("completion-token-unavailable" if not eos else "complete" if completed
        else "truncated" if len(ids) >= DECODER["maxNewTokens"] else "incomplete")
    detected, lexical, edits = None, None, []
    if completed:
        try:
            detected, lexical, edits = _lexical_projection(value["bodyDecodedText"], language, names)
        except ValueError:
            state = "invalid-structure"
    if (completion["state"] != state or result["detectedLanguage"] != detected
            or result["transcription"] != lexical or value["lexicalEdits"] != edits):
        raise ValueError("Qwen3-ASR lexical projection differs from retained observation")
    if require_complete and state != "complete":
        raise WorkerReasonError(f"qwen-asr-output-{state}")


def _transcribe(
    audio: Path, model_path: Path, device_name: str, language: str | None
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    import torch
    from transformers import AutoConfig, AutoModelForSpeechSeq2Seq, AutoProcessor

    from stemwise_engine.acoustic_inputs import require_waveform_allocation
    # CPU features and their device copy coexist with the decoded waveform.
    require_waveform_allocation(audio, 16000, source_arrays=2, target_arrays=3, recording=True)

    runtime = _runtime_identity(device_name)
    device = torch.device(runtime["resolvedDevice"])
    dtype = (
        torch.bfloat16
        if device.type == "cuda" and torch.cuda.is_bf16_supported()
        else torch.float16
        if device.type == "cuda"
        else torch.float32
    )
    config = AutoConfig.from_pretrained(str(model_path), local_files_only=True, trust_remote_code=False)
    if config.model_type != "qwen3_asr":
        raise ValueError("Local model is not a native Qwen3-ASR snapshot")
    processor = AutoProcessor.from_pretrained(str(model_path), local_files_only=True, trust_remote_code=False)
    model = AutoModelForSpeechSeq2Seq.from_pretrained(
        str(model_path),
        local_files_only=True,
        trust_remote_code=False,
        dtype=dtype,
        attn_implementation="sdpa",
    ).to(device)
    model.eval()
    torch.manual_seed(0)
    if device.type == "cuda":
        torch.cuda.manual_seed_all(0)
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
    torch.use_deterministic_algorithms(True)
    inputs = processor.apply_transcription_request(
        audio=str(audio), language=language, prompt=None, return_tensors="pt"
    )
    inputs = {
        key: tensor.to(device=device, dtype=dtype) if tensor.is_floating_point() else tensor.to(device)
        for key, tensor in inputs.items()
    }
    prompt_length = int(inputs["input_ids"].shape[-1])
    with torch.inference_mode():
        generated = model.generate(
            **inputs,
            do_sample=DECODER["doSample"],
            num_beams=DECODER["numBeams"],
            max_new_tokens=DECODER["maxNewTokens"],
        )
    tokens = generated[0, prompt_length:].cpu()
    from transformers.models.qwen3_asr.processing_qwen3_asr import LANGUAGE_CODE_TO_NAME
    result = _generation_result(tokens, processor.tokenizer, language, model.generation_config.eos_token_id,
        language_names=LANGUAGE_CODE_TO_NAME.values())
    model_identity = {
        "modelType": str(config.model_type),
        "modelClass": model.__class__.__name__,
    }
    return runtime, model_identity, result


def run(request_path: Path, output_path: Path, device_name: str) -> None:
    request = _validated_request(read_worker_json(request_path))
    audio, model_path = _resolved_bindings(request)
    resolved_output = output_path.resolve(strict=False)
    if resolved_output in {request_path.resolve(strict=True), audio} or resolved_output.is_relative_to(model_path):
        raise ValueError("Qwen3-ASR output would overwrite an input")
    runtime, loaded_model, result = _transcribe(
        audio, model_path, device_name, request["language"]
    )
    output = {
        "formatVersion": FORMAT_VERSION,
        "engine": ENGINE,
        "audio": {key: request["audio"][key] for key in ("sha256", "byteCount")},
        "language": request["language"],
        "result": result,
        "runtime": runtime,
        "model": {
            "revision": request["model"]["revision"],
            "snapshotSha256": request["model"]["snapshotSha256"],
            **loaded_model,
        },
        "decoder": dict(DECODER),
    }
    _atomic_json(output_path, _validated_output(output, request, require_complete=False))
    _validated_output(output, request)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--request", type=Path)
    result.add_argument("--output", type=Path)
    result.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    result.add_argument("--runtime-info", action="store_true")
    return result


def main() -> int:
    arguments = parser().parse_args()
    if arguments.runtime_info:
        json.dump(runtime_info(arguments.device), sys.stdout, ensure_ascii=True, sort_keys=True)
        sys.stdout.write("\n")
        return 0
    if arguments.request is None or arguments.output is None:
        raise SystemExit("--request and --output are required")
    run(arguments.request, arguments.output, arguments.device)
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
