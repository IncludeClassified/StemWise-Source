"""Model and pinned-asset lifetimes shared by alignment stages and isolated workers."""
from __future__ import annotations
from dataclasses import dataclass, field
import gc
import os
import re
from stemwise_engine.model_registry import model_assets, runtime_profile
from pathlib import Path
import sys
from typing import Any
from stemwise_engine.common import heavy_worker_stage, sha256_file, validate_generated_path
from stemwise_engine.lyrics_alignment import FasterWhisperEvidence
import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.worker_protocol as worker_protocol

_PINNED_FILE_CACHE: dict[Path, tuple[int, int, int, int, int, str]] = {}

def roformer_assets(agent_root: Path, route_id: str) -> dict[str, Any]:
    route = endpoints.ROFORMER_ROUTES.get(route_id)
    if route is None:
        raise ValueError("unsupported RoFormer route")
    root = agent_root / "model-cache" / "lyrics-roformer" / route["directory"]
    checkpoint = root / route["checkpoint"]
    config = root / route["config"]
    for path, byte_count, sha256, label in (
        (
            checkpoint,
            route["checkpointByteCount"],
            route["checkpointSha256"],
            f"{route_id} checkpoint",
        ),
        (
            config,
            route["configByteCount"],
            route["configSha256"],
            f"{route_id} configuration",
        ),
    ):
        if not path.is_file() or path.stat().st_size != byte_count:
            raise RuntimeError(f"pinned {label} has the wrong byte count or is missing")
        require_pinned_file(path, sha256, label)
    return {
        "routeId": route_id,
        "familyId": route["familyId"],
        "sourceViewId": route["sourceViewId"],
        "checkpoint": checkpoint,
        "checkpointSha256": route["checkpointSha256"],
        "config": config,
        "configSha256": route["configSha256"],
    }


def require_roformer_wheel(agent_root: Path) -> str:
    wheel = agent_root / 'model-cache/python-wheels/melband_roformer_infer-0.1.5-py3-none-any.whl'
    if not wheel.is_file() or wheel.stat().st_size != 35_789:
        raise RuntimeError('pinned MelBand RoFormer wheel is missing or has the wrong size')
    return require_pinned_file(wheel, ROFORMER_PACKAGE_WHEEL_SHA256, 'MelBand RoFormer wheel')


def require_pinned_file(path: Path, expected_sha256: str, label: str) -> str:
    if not path.is_file():
        raise RuntimeError(f"pinned {label} is missing")
    stat = path.stat()
    identity = (
        stat.st_dev,
        stat.st_ino,
        stat.st_size,
        stat.st_mtime_ns,
        stat.st_ctime_ns,
    )
    cached = _PINNED_FILE_CACHE.get(path)
    if os.name != "nt" and cached is not None and cached[:5] == identity:
        actual = cached[5]
    else:
        actual = sha256_file(path)
    if actual != expected_sha256:
        raise RuntimeError(f"pinned {label} failed its SHA-256 identity check")
    _PINNED_FILE_CACHE[path] = (*identity, actual)
    return actual


def roformer_offline_env(agent_root: Path) -> dict[str, str]:
    workspace_root = agent_root.parent
    cache_root = validate_generated_path(
        agent_root,
        agent_root / "model-cache",
        workspace_root=workspace_root,
    )
    cache_root.mkdir(parents=True, exist_ok=True)
    cache_root = validate_generated_path(
        agent_root,
        cache_root,
        workspace_root=workspace_root,
    )
    locations: dict[str, Path] = {}
    for name in (
        "huggingface",
        "torch",
        "xdg",
        "numba",
        "matplotlib",
        "cuda",
        "triton",
        "torchinductor",
    ):
        location = validate_generated_path(
            cache_root,
            cache_root / name,
            workspace_root=workspace_root,
        )
        location.mkdir(parents=True, exist_ok=True)
        locations[name] = validate_generated_path(
            cache_root,
            location,
            workspace_root=workspace_root,
        )
    model_root = validate_generated_path(
        cache_root,
        cache_root / "lyrics-roformer",
        workspace_root=workspace_root,
    )
    environment = os.environ.copy()
    environment.update(
        {
            "HF_HOME": str(locations["huggingface"]),
            "TORCH_HOME": str(locations["torch"]),
            "XDG_CACHE_HOME": str(locations["xdg"]),
            "NUMBA_CACHE_DIR": str(locations["numba"]),
            "MPLCONFIGDIR": str(locations["matplotlib"]),
            "CUDA_CACHE_PATH": str(locations["cuda"]),
            "CUBLAS_WORKSPACE_CONFIG": ":4096:8",
            "TRITON_CACHE_DIR": str(locations["triton"]),
            "TORCHINDUCTOR_CACHE_DIR": str(locations["torchinductor"]),
            "MELBAND_ROFORMER_MODELS_PATH": str(model_root),
            "HF_HUB_OFFLINE": "1",
            "HF_HUB_DISABLE_TELEMETRY": "1",
            "TRANSFORMERS_OFFLINE": "1",
        }
    )
    return environment


@dataclass(kw_only=True)
class WhisperModelOwner:
    config: dict[str, Any]
    model_name: str
    snapshot: Path
    device: str
    compute_type: str
    model: FasterWhisperEvidence | None = field(default=None, init=False)
    lease: Any = field(default=None, init=False)
    failure_owner: Any = field(default=None, init=False)

    def retain_failure(self, error, stage):
        if self.failure_owner is not None:
            workspace, video_id, state = self.failure_owner
            code = worker_protocol.technical_diagnostic_code(error, workspace.job_dir(video_id) / 'whisper.failure.private.txt',
                context={'stage': stage, 'model': self.model_name, 'device': self.device, 'computeType': self.compute_type})
            state.update(status='retryable-technical', reasons=[str(code)],
                         privateDiagnostics=list(code.diagnostics))
            workspace.write_state(video_id, state)

    def get(self) -> FasterWhisperEvidence:
        if self.model is None:
            self.lease = heavy_worker_stage(self.config)
            self.lease.__enter__()
            try:
                self.model = FasterWhisperEvidence(
                    self.model_name,
                    self.snapshot,
                    device=self.device,
                    compute_type=self.compute_type,
                    timeout_seconds=int(self.config["analysisTimeoutMinutes"]) * 60,
                )
            except BaseException as error:
                try:
                    self.retain_failure(error, 'whisper-initialization')
                finally:
                    print("Whisper initialization failed before retirement", file=sys.stderr, flush=True)
                    os._exit(70)
        return self.model

    def close(self) -> None:
        if self.model is not None:
            try:
                self.model.close()
            except BaseException as error:
                try:
                    self.retain_failure(error, 'whisper-retirement')
                finally:
                    # A failed native unload must not hand a live CUDA context to a peer.
                    print("Whisper model retirement failed", file=sys.stderr, flush=True)
                    os._exit(70)
            self.model = None
            gc.collect()
        if self.lease is not None:
            self.lease.__exit__(None, None, None)
            self.lease = None



ROFORMER_PACKAGE_VERSION = model_assets('lyrics_align_pc')['ROFORMER_PACKAGE_VERSION']


ROFORMER_PACKAGE_WHEEL_SHA256 = model_assets('lyrics_align_pc')['ROFORMER_PACKAGE_WHEEL_SHA256']


ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256 = model_assets('lyrics_align_pc')['ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256']


ROFORMER_PACKAGE_SOURCE_COMMIT = model_assets('lyrics_align_pc')['ROFORMER_PACKAGE_SOURCE_COMMIT']


ROFORMER_RUNTIME_PACKAGES = tuple(model_assets('lyrics_align_pc')['ROFORMER_RUNTIME_PACKAGES'])


PINNED_DEMUCS_SHA256 = model_assets('lyrics_align_pc')['PINNED_DEMUCS_SHA256']


def qualified_python_version(project: Path) -> str:
    value = runtime_profile('main')['python']
    if not isinstance(value, str) or re.fullmatch(r"\d+\.\d+\.\d+", value) is None:
        raise RuntimeError('Current execution profile has no exact Python version')
    return value


def mms_runtime_pins(project: Path) -> dict[str, str]:
    versions = runtime_profile('main')['packages']
    torch_version = versions['torch']
    cuda_build = re.fullmatch(r".+\+cu(\d{2})(\d)", torch_version)
    if cuda_build is None:
        raise RuntimeError("the exact torch pin has no CUDA runtime identity")
    return {
        "python": qualified_python_version(project),
        "torch": torch_version,
        "torchaudio": versions["torchaudio"],
        "demucs": versions["demucs"],
        "uroman": versions["uroman"],
        "cudaRuntime": f"{int(cuda_build.group(1))}.{int(cuda_build.group(2))}",
    }


def wav2vec2_runtime_pins(project: Path) -> dict[str, str]:
    versions = runtime_profile('main')['packages']
    required = ("torch", "torchaudio", "transformers", "safetensors", "phonemizer")
    missing = [name for name in required if name not in versions]
    if missing:
        raise RuntimeError(
            "Wav2Vec2 runtime has no exact version pins for: " + ", ".join(missing)
        )
    cuda_build = re.fullmatch(r".+\+cu(\d{2})(\d)", versions["torch"])
    if cuda_build is None:
        raise RuntimeError("the exact Wav2Vec2 torch pin has no CUDA identity")
    return {
        "python": qualified_python_version(project),
        **{name: versions[name] for name in required},
        "cudaRuntime": f"{int(cuda_build.group(1))}.{int(cuda_build.group(2))}",
    }


def roformer_runtime_pins(project: Path) -> dict[str, Any]:
    versions = runtime_profile('main')['packages']
    missing = sorted(set(ROFORMER_RUNTIME_PACKAGES) - set(versions))
    if missing:
        raise RuntimeError(
            "RoFormer runtime has no exact version pins for: " + ", ".join(missing)
        )
    return {
        "python": qualified_python_version(project),
        "packages": {name: versions[name] for name in ROFORMER_RUNTIME_PACKAGES},
    }
