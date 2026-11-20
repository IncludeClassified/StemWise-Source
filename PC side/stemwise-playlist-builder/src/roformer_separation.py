"""Offline, hash-bound MelBand RoFormer inference for private lyric evidence."""

from __future__ import annotations
from stemwise_engine.worker_protocol import WorkerReasonError

import argparse
import contextlib
import hashlib
import importlib.metadata
import io
import json
import math
import os
import platform
import sys
import tempfile
import time
from fractions import Fraction
from pathlib import Path
from typing import Any
from stemwise_engine.common import source_duration_ms


RESULT_VERSION = 1
from stemwise_engine.model_registry import model_assets

_ASSETS = model_assets('lyrics_align_pc')
PACKAGE_VERSION = _ASSETS['ROFORMER_PACKAGE_VERSION']
PACKAGE_WHEEL_SHA256 = _ASSETS['ROFORMER_PACKAGE_WHEEL_SHA256']
PACKAGE_SOURCE_MANIFEST_SHA256 = _ASSETS['ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256']
PACKAGE_SOURCE_COMMIT = _ASSETS['ROFORMER_PACKAGE_SOURCE_COMMIT']
ROUTES = {name: value['outputInstrument'] for name, value in _ASSETS['ROFORMER_ROUTES'].items()}
RUNTIME_PACKAGES = tuple(_ASSETS['ROFORMER_RUNTIME_PACKAGES'])


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _package_source_manifest() -> str:
    distribution = importlib.metadata.distribution("melband-roformer-infer")
    entries = []
    for relative in sorted(
        (
            path
            for path in distribution.files or ()
            if str(path).replace("\\", "/").startswith("mel_band_roformer/")
        ),
        key=lambda path: str(path).replace("\\", "/"),
    ):
        path = Path(distribution.locate_file(relative))
        if not path.is_file():
            raise RuntimeError("MelBand RoFormer package source manifest is incomplete")
        entries.append(
            {
                "path": str(relative).replace("\\", "/"),
                "byteCount": path.stat().st_size,
                "sha256": _sha256_file(path),
            }
        )
    raw = json.dumps(
        entries, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _package_identity() -> dict[str, Any]:
    version = importlib.metadata.version("melband-roformer-infer")
    source = _package_source_manifest()
    if version != PACKAGE_VERSION or source != PACKAGE_SOURCE_MANIFEST_SHA256:
        raise RuntimeError("MelBand RoFormer package differs from the pinned runtime")
    return {
        "version": version,
        "wheelSha256": PACKAGE_WHEEL_SHA256,
        "sourceManifestSha256": source,
        "sourceCommit": PACKAGE_SOURCE_COMMIT,
    }


def _runtime_packages() -> dict[str, str]:
    values: dict[str, str] = {}
    for name in RUNTIME_PACKAGES:
        try:
            values[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            values[name] = "missing"
    return values


def runtime_info(device_name: str) -> dict[str, Any]:
    import torch

    if device_name == "cuda" and not torch.cuda.is_available():
        raise WorkerReasonError("cuda-unavailable", "CUDA was requested but is unavailable")
    resolved = "cuda" if device_name == "cuda" else "cpu"
    return {
        "runtimeVersion": RESULT_VERSION,
        "supportedResultVersions": [RESULT_VERSION],
        "python": platform.python_version(),
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "package": _package_identity(),
        "packages": _runtime_packages(),
        "requestedDevice": device_name,
        "resolvedDevice": resolved,
        "cudaAvailable": bool(torch.cuda.is_available()),
        "cudaDeviceName": (
            str(torch.cuda.get_device_name(0)) if torch.cuda.is_available() else None
        ),
        "cudaRuntime": str(getattr(torch.version, "cuda", None)),
        "cudnn": str(torch.backends.cudnn.version()),
    }


def _round_fraction(value: Fraction) -> int:
    return (value.numerator * 2 + value.denominator) // (2 * value.denominator)


def _stream(sample_rate: int, channels: int, frames: int) -> dict[str, int]:
    if sample_rate <= 0 or channels <= 0 or frames <= 0:
        raise ValueError("RoFormer audio stream is empty")
    return {
        "sampleRate": sample_rate,
        "channelCount": channels,
        "frameCount": frames,
        "durationMs": source_duration_ms(frames, sample_rate),
    }


def _chunk_reconstruction(config: Any, frame_count: int) -> dict[str, int]:
    chunk = int(
        config.inference.chunk_size
        if hasattr(config.inference, "chunk_size")
        else config.audio.chunk_size
    )
    overlap = int(config.inference.num_overlap)
    if chunk <= 0 or overlap <= 0 or chunk % overlap:
        raise ValueError("RoFormer chunk configuration is invalid")
    step = chunk // overlap
    border = chunk - step
    padded = frame_count + 2 * border if frame_count > 2 * border else frame_count
    last_start = (math.ceil(padded / step) - 1) * step
    last_source_frames = min(chunk, padded - last_start)
    last_pad_frames = chunk - last_source_frames
    return {
        "chunkSizeFrames": chunk,
        "numOverlap": overlap,
        "stepFrames": step,
        "fadeFrames": chunk // 10,
        "leftReflectPadFrames": border if padded != frame_count else 0,
        "rightReflectPadFrames": border if padded != frame_count else 0,
        "modelInputFrameCount": padded,
        "chunkCount": math.ceil(padded / step),
        "lastChunkSourceFrames": last_source_frames,
        "lastChunkPadFrames": last_pad_frames,
        "lastChunkReflectPad": int(
            last_pad_frames > 0 and last_source_frames > chunk // 2 + 1
        ),
        "leftTrimFrames": border if padded != frame_count else 0,
        "rightTrimFrames": border if padded != frame_count else 0,
        "modelOutputFrameCount": frame_count,
    }


def _time_transform(
    source: dict[str, int], target: dict[str, int], reconstruction: dict[str, int]
) -> dict[str, Any]:
    ratio = Fraction(target["sampleRate"], source["sampleRate"])
    expected = _round_fraction(Fraction(source["frameCount"]) * ratio)
    residual = target["frameCount"] - expected
    residual_ms = residual * 1000.0 / target["sampleRate"]
    if abs(residual_ms) > 1.0:
        raise RuntimeError("RoFormer output has a material canonical-time residual")
    if reconstruction["modelOutputFrameCount"] != target["frameCount"]:
        raise RuntimeError("RoFormer overlap-add reconstruction changed the target length")
    return {
        "sourceSampleRate": source["sampleRate"],
        "sourceChannelCount": source["channelCount"],
        "sourceFrameCount": source["frameCount"],
        "targetSampleRate": target["sampleRate"],
        "targetChannelCount": target["channelCount"],
        "targetFrameCount": target["frameCount"],
        "expectedTargetFrameCount": expected,
        "resamplingRatioNumerator": ratio.numerator,
        "resamplingRatioDenominator": ratio.denominator,
        "measuredResidualFrames": residual,
        "measuredResidualMs": residual_ms,
        "indexOffsetTargetFrames": 0,
        "separatorLatencyFrames": 0,
        "separatorLatencyStatus": "zero-by-offline-index-contract",
        "chunkOverlapReconstruction": reconstruction,
    }


def _atomic_json(path: Path, value: Any) -> None:
    from stemwise_engine.worker_publication import publish_json
    publish_json(path, value, ensure_ascii=True, indent=2)

def _nonfinite_summary(output: Any, np: Any) -> dict[str, int]:
    mask = ~np.isfinite(output)
    bad_frames = np.any(mask, axis=0)
    indices = np.flatnonzero(bad_frames)
    padded = np.concatenate(([False], bad_frames, [False]))
    edges = np.flatnonzero(padded[1:] != padded[:-1])
    runs = edges[1::2] - edges[::2]
    return {
        "sampleCount": int(mask.sum()),
        "frameCount": int(bad_frames.sum()),
        "firstFrame": int(indices[0]) if indices.size else -1,
        "lastFrame": int(indices[-1]) if indices.size else -1,
        "longestFrameRun": int(runs.max()) if runs.size else 0,
        "totalFrames": int(output.shape[-1]),
        "channelCount": int(output.shape[0]),
        "nanCount": int(np.isnan(output).sum()),
        "positiveInfinityCount": int(np.isposinf(output).sum()),
        "negativeInfinityCount": int(np.isneginf(output).sum()),
    }


def run(arguments: argparse.Namespace, *, model_cache: dict | None = None) -> None:
    import numpy as np
    import soundfile as sf
    import torch
    import torchaudio
    import yaml
    from mel_band_roformer.inference import SafeLoaderWithTuple
    from mel_band_roformer import demix_track, get_model_from_config
    from ml_collections import ConfigDict
    from stemwise_engine.alignment_models import require_pinned_file

    if arguments.route not in ROUTES:
        raise ValueError("unsupported RoFormer route")
    package = _package_identity()
    for path, expected, label in (
        (arguments.checkpoint, arguments.checkpoint_sha256, "checkpoint"),
        (arguments.config, arguments.config_sha256, "configuration"),
    ):
        require_pinned_file(path, expected, f"RoFormer {label}")
    input_sha256 = _sha256_file(arguments.audio)
    if input_sha256 != arguments.input_sha256:
        raise RuntimeError("RoFormer input differs from its supplied identity")

    started = time.perf_counter_ns()
    with arguments.config.open("r", encoding="utf-8") as handle:
        config = ConfigDict(yaml.load(handle, Loader=SafeLoaderWithTuple))
    model_rate = int(config.model.sample_rate)
    if model_rate <= 0:
        raise ValueError("RoFormer model sample rate is invalid")
    from stemwise_engine.acoustic_inputs import require_waveform_allocation
    stems = (1 if config.training.target_instrument is not None else len(config.training.instruments))
    # Original/padded/device mix, plus result/counter/quotient/CPU output per stem.
    require_waveform_allocation(arguments.audio, model_rate, target_channels=2,
                                target_arrays=3+4*stems, recording=True)
    waveform, source_rate = torchaudio.load(str(arguments.audio))
    source = _stream(int(source_rate), int(waveform.shape[0]), int(waveform.shape[-1]))
    if source["channelCount"] != 2:
        raise RuntimeError("RoFormer evidence input must be stereo")
    if source_rate != model_rate:
        waveform = torchaudio.functional.resample(waveform, source_rate, model_rate)
    resampled = _stream(model_rate, int(waveform.shape[0]), int(waveform.shape[-1]))
    preprocessing_done = time.perf_counter_ns()

    device = torch.device(arguments.device)
    if device.type == "cuda" and not torch.cuda.is_available():
        raise WorkerReasonError("cuda-unavailable", "CUDA was requested but is unavailable")
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.use_deterministic_algorithms(True)
    if device.type == "cuda":
        torch.cuda.reset_peak_memory_stats(device)

    key = (arguments.route, arguments.checkpoint_sha256, arguments.config_sha256, str(device))
    model = model_cache.get(key) if model_cache is not None else None
    if model is None:
        if model_cache is not None:
            model_cache.clear()
            import gc
            gc.collect()
            if device.type == "cuda":
                torch.cuda.empty_cache()
        model = get_model_from_config("mel_band_roformer", config)
        state = torch.load(
            arguments.checkpoint, map_location=torch.device("cpu"), weights_only=True
        )
        model.load_state_dict(state)
        model = model.to(device).eval()
        del state
        if model_cache is not None:
            model_cache[key] = model
    model_loaded = time.perf_counter_ns()

    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(
        io.StringIO()
    ):
        separated, _ = demix_track(config, model, waveform, device)
    target_name = ROUTES[arguments.route]
    if target_name not in separated:
        raise RuntimeError("RoFormer model did not emit its pinned target")
    output = np.asarray(separated[target_name], dtype=np.float32)
    if output.ndim != 2 or output.shape[0] != 2 or output.shape[1] != resampled["frameCount"]:
        raise RuntimeError("RoFormer model output shape does not preserve its input timeline")
    nonfinite = _nonfinite_summary(output, np)
    if nonfinite["sampleCount"]:
        raise RuntimeError(
            "RoFormer model output contains non-finite samples: "
            + json.dumps(nonfinite, sort_keys=True, separators=(",", ":"))
        )
    inference_done = time.perf_counter_ns()

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    sf.write(arguments.output, output.T, model_rate, subtype="FLOAT")
    info = sf.info(arguments.output)
    target = _stream(int(info.samplerate), int(info.channels), int(info.frames))
    if target != resampled:
        raise RuntimeError("serialized RoFormer output changed its sample timeline")
    written = time.perf_counter_ns()

    reconstruction = _chunk_reconstruction(config, resampled["frameCount"])
    metrics: dict[str, Any] = {
        "preprocessingRuntimeMs": (preprocessing_done - started + 500_000) // 1_000_000,
        "modelLoadRuntimeMs": (model_loaded - preprocessing_done + 500_000) // 1_000_000,
        "inferenceRuntimeMs": (inference_done - model_loaded + 500_000) // 1_000_000,
        "writeRuntimeMs": (written - inference_done + 500_000) // 1_000_000,
        "runtimeMs": (written - started + 500_000) // 1_000_000,
    }
    if device.type == "cuda":
        metrics["peakCudaMemoryBytes"] = int(torch.cuda.max_memory_allocated(device))
    result = {
        "resultVersion": RESULT_VERSION,
        "routeId": arguments.route,
        "targetInstrument": target_name,
        "package": package,
        "runtime": runtime_info(arguments.device),
        "device": str(device),
        "model": {
            "checkpointSha256": arguments.checkpoint_sha256,
            "configSha256": arguments.config_sha256,
        },
        "input": {
            "sha256": input_sha256,
            "byteCount": arguments.audio.stat().st_size,
            "stream": source,
        },
        "output": {
            "sha256": _sha256_file(arguments.output),
            "byteCount": arguments.output.stat().st_size,
            "stream": target,
        },
        "canonicalTimeTransform": _time_transform(source, target, reconstruction),
        "parameters": {
            "deterministicAlgorithms": True,
            "cudnnBenchmark": False,
            "cudnnDeterministic": True,
            "modelSampleRate": model_rate,
            **reconstruction,
        },
        "stageMetrics": metrics,
    }
    _atomic_json(arguments.result, result)


def _sha256(value: str) -> str:
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise argparse.ArgumentTypeError("must be a lowercase SHA-256")
    return value


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--runtime-info", action="store_true")
    result.add_argument("--route", choices=tuple(ROUTES))
    result.add_argument("--audio", type=Path)
    result.add_argument("--input-sha256", type=_sha256)
    result.add_argument("--checkpoint", type=Path)
    result.add_argument("--checkpoint-sha256", type=_sha256)
    result.add_argument("--config", type=Path)
    result.add_argument("--config-sha256", type=_sha256)
    result.add_argument("--output", type=Path)
    result.add_argument("--result", type=Path)
    result.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    return result


def main() -> int:
    arguments = parser().parse_args()
    if arguments.runtime_info:
        json.dump(runtime_info(arguments.device), sys.stdout, ensure_ascii=True, sort_keys=True)
        sys.stdout.write("\n")
        return 0
    required = (
        "route",
        "audio",
        "input_sha256",
        "checkpoint",
        "checkpoint_sha256",
        "config",
        "config_sha256",
        "output",
        "result",
    )
    if any(getattr(arguments, name) is None for name in required):
        raise SystemExit("all inference arguments are required")
    run(arguments)
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
