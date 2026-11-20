#!/usr/bin/env python3
"""Small deterministic dependency doctor used by setup and the CLI."""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import shutil
import subprocess
import sys
from pathlib import Path
from stemwise_engine.model_registry import model_assets, runtime_profile

from stemwise_engine.common import (
    diagnostic_code,
    load_config,
    resolve_path,
    sha256_file,
    validate_generated_path,
    run_process_tree,
)


EXPECTED_DENO_VERSION = model_assets('doctor')['EXPECTED_DENO_VERSION']
EXPECTED_DENO_SHA256 = model_assets('doctor')['EXPECTED_DENO_SHA256']
EXPECTED_EJS_VERSION = model_assets('doctor')['EXPECTED_EJS_VERSION']
EXPECTED_WAV2VEC2_PACKAGES = {name: runtime_profile('main')['packages'][name]
                              for name in ('phonemizer', 'safetensors', 'transformers')}

EXPECTED_WAV2VEC2_REPOSITORY = model_assets('lyrics_align_pc')['PINNED_WAV2VEC2_MODELS']['tr']['repository']
EXPECTED_WAV2VEC2_REVISION = model_assets('lyrics_align_pc')['PINNED_WAV2VEC2_MODELS']['tr']['snapshot']
EXPECTED_WAV2VEC2_FILES = model_assets('lyrics_align_pc')['PINNED_WAV2VEC2_MODELS']['tr']['files']
EXPECTED_UNIVERSAL_WAV2VEC2_REPOSITORY = model_assets('lyrics_align_pc')['PINNED_UNIVERSAL_WAV2VEC2_MODEL']['repository']
EXPECTED_UNIVERSAL_WAV2VEC2_REVISION = model_assets('lyrics_align_pc')['PINNED_UNIVERSAL_WAV2VEC2_MODEL']['snapshot']
EXPECTED_UNIVERSAL_WAV2VEC2_FILES = model_assets('lyrics_align_pc')['PINNED_UNIVERSAL_WAV2VEC2_MODEL']['files']


def json_probe(command, *, timeout=30):
    try:
        completed = run_process_tree(command, capture_output=True, check=False, timeout=timeout)
        from stemwise_engine.worker_protocol import parse_worker_json
        if completed.returncode != 0:
            raise ValueError('probe-failed')
        value = parse_worker_json(completed.stdout, max_bytes=64*1024)
        if not isinstance(value, dict):
            raise ValueError('probe-invalid-object')
        return value
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        return {'ok': False, 'error': diagnostic_code(error)}


def package_version(name: str) -> str | None:
    try:
        return importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        return None


def deno_identity(path: Path | None) -> dict[str, object]:
    if path is None or not path.is_file():
        return {
            "available": False,
            "expectedVersion": EXPECTED_DENO_VERSION,
            "expectedSha256": EXPECTED_DENO_SHA256,
        }
    try:
        digest = sha256_file(path)
        if digest != EXPECTED_DENO_SHA256:
            return {'available': True, 'verified': False, 'sha256': digest,
                    'expectedVersion': EXPECTED_DENO_VERSION, 'expectedSha256': EXPECTED_DENO_SHA256,
                    'error': 'executable-identity-mismatch'}
        completed = subprocess.run(
            [str(path), "--version"],
            text=True,
            capture_output=True,
            check=False,
            timeout=15,
        )
        first_line = completed.stdout.splitlines()[0] if completed.stdout else ""
        version_parts = first_line.split()
        version = (
            version_parts[1]
            if len(version_parts) >= 2 and version_parts[0] == "deno"
            else None
        )
        return {
            "available": completed.returncode == 0,
            "version": version,
            "expectedVersion": EXPECTED_DENO_VERSION,
            "sha256": digest,
            "expectedSha256": EXPECTED_DENO_SHA256,
            "verified": (
                completed.returncode == 0
                and version == EXPECTED_DENO_VERSION
                and digest == EXPECTED_DENO_SHA256
            ),
        }
    except Exception as error:
        return {
            "available": False,
            "expectedVersion": EXPECTED_DENO_VERSION,
            "expectedSha256": EXPECTED_DENO_SHA256,
            "error": diagnostic_code(error),
        }


def _model_identity(
    snapshot: Path,
    repository: str,
    revision: str,
    expected_files: dict[str, tuple[int, str]],
) -> dict[str, object]:
    result: dict[str, object] = {
        "repository": repository,
        "revision": revision,
        "available": False,
        "verified": False,
        "files": {},
    }
    try:
        if not snapshot.is_dir() or snapshot.is_symlink():
            return result
        files: dict[str, object] = {}
        for name, (expected_bytes, expected_sha256) in expected_files.items():
            path = snapshot / name
            available = path.is_file() and not path.is_symlink()
            actual_bytes = path.stat().st_size if available else None
            actual_sha256 = sha256_file(path) if actual_bytes == expected_bytes else None
            files[name] = {
                "available": available,
                "byteLength": actual_bytes,
                "expectedByteLength": expected_bytes,
                "sha256": actual_sha256,
                "expectedSha256": expected_sha256,
                "verified": actual_bytes == expected_bytes and actual_sha256 == expected_sha256,
            }
        result.update(
            available=True,
            verified=all(item["verified"] for item in files.values()),
            files=files,
            missingFiles=sorted(
                name for name, item in files.items() if not item["available"]
            ),
        )
    except Exception as error:
        result["error"] = diagnostic_code(error)
    return result


def wav2vec2_model_identity(snapshot: Path | None = None) -> dict[str, object]:
    if snapshot is None:
        snapshot = (
            Path(__file__).resolve().parents[2]
            / "model-cache"
            / "lyrics-alignment"
            / "models--mpoyraz--wav2vec2-xls-r-300m-cv7-turkish"
            / "snapshots"
            / EXPECTED_WAV2VEC2_REVISION
        )
    return _model_identity(
        snapshot,
        EXPECTED_WAV2VEC2_REPOSITORY,
        EXPECTED_WAV2VEC2_REVISION,
        EXPECTED_WAV2VEC2_FILES,
    )


def universal_wav2vec2_model_identity(
    snapshot: Path | None = None,
) -> dict[str, object]:
    if snapshot is None:
        snapshot = (
            Path(__file__).resolve().parents[2]
            / "model-cache"
            / "lyrics-alignment"
            / "models--facebook--wav2vec2-xlsr-53-espeak-cv-ft"
            / "snapshots"
            / EXPECTED_UNIVERSAL_WAV2VEC2_REVISION
        )
    return _model_identity(
        snapshot,
        EXPECTED_UNIVERSAL_WAV2VEC2_REPOSITORY,
        EXPECTED_UNIVERSAL_WAV2VEC2_REVISION,
        EXPECTED_UNIVERSAL_WAV2VEC2_FILES,
    )


def future_song_policy_identity(config_path: Path | None = None) -> dict[str, object]:
    """Validate the exact future-song policy/profile binding without touching jobs."""

    try:
        from stemwise_engine.future_song import FutureSongWorkflow

        config_path = (
            config_path or Path(__file__).resolve().parents[1] / "config.json"
        ).resolve()
        config = load_config(config_path)
        pc_side_root = resolve_path(
            config["pcSideRootWindows"], base=config_path.parent
        )
        workspace_root = pc_side_root.parent
        install_root = resolve_path(
            config["futureRootWindows"], base=config_path.parent
        )
        validate_generated_path(
            workspace_root, config_path, workspace_root=workspace_root
        )
        validate_generated_path(
            workspace_root, install_root, workspace_root=workspace_root
        )

        workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
        workflow.config_path = config_path
        workflow.config = config
        workflow.pc_side_root = pc_side_root
        workflow.workspace_root = workspace_root
        workflow.install_root = install_root
        workflow.policy_dir = validate_generated_path(
            install_root,
            install_root / "policy",
            workspace_root=workspace_root,
        )
        policy = workflow._validate_policy()
        return {
            "ready": True,
            "policyVersion": policy["policyVersion"],
            "deliveryAllowed": True,
            "qualificationClaimsAllowed": False,
        }
    except Exception as error:
        return {
            "ready": False,
            "error": diagnostic_code(error),
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--allin1-python")
    parser.add_argument("--deno", type=Path)
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()

    ejs_version = package_version("yt-dlp-ejs")
    deno = deno_identity(args.deno)
    wav2vec2_package_versions = {
        name: package_version(name) for name in EXPECTED_WAV2VEC2_PACKAGES
    }
    wav2vec2_packages = {
        name: {
            "version": wav2vec2_package_versions[name],
            "expectedVersion": expected,
            "verified": wav2vec2_package_versions[name] == expected,
        }
        for name, expected in EXPECTED_WAV2VEC2_PACKAGES.items()
    }
    wav2vec2_model = wav2vec2_model_identity()
    universal_wav2vec2_model = universal_wav2vec2_model_identity()
    wav2vec2_verified = (
        all(item["verified"] for item in wav2vec2_packages.values())
        and wav2vec2_model.get("verified") is True
        and universal_wav2vec2_model.get("verified") is True
    )
    future_song_policy = future_song_policy_identity(args.config)

    result: dict[str, object] = {
        "python": sys.version.split()[0],
        "executables": {
            name: shutil.which(name) is not None for name in ("ffmpeg", "ffprobe")
        },
        "packages": {
            name: package_version(name)
            for name in (
                "yt-dlp",
                "yt-dlp-ejs",
                "numpy",
                "Pillow",
                "requests",
                "soundfile",
                "safetensors",
                "torch",
                "torchaudio",
                "transformers",
                "beat-this",
                "skey",
                "essentia",
            )
        },
        "youtubeJavascript": {
            "signedUpdaterAvailable": all(shutil.which(name) for name in ("gpg", "gpgv")),
            "ytDlpEjs": {
                "version": ejs_version,
                "expectedVersion": EXPECTED_EJS_VERSION,
                "verified": ejs_version == EXPECTED_EJS_VERSION,
            },
            "deno": deno,
        },
        "wav2vec2ForcedAlignment": {
            "packages": wav2vec2_packages,
            "model": wav2vec2_model,
            "universalModel": universal_wav2vec2_model,
            "verified": wav2vec2_verified,
        },
        "futureSongPolicy": future_song_policy,
    }

    try:
        import torch

        result["cuda"] = {
            "available": bool(torch.cuda.is_available()),
            "torchCuda": torch.version.cuda,
            "device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
            "capability": list(torch.cuda.get_device_capability(0)) if torch.cuda.is_available() else None,
        }
    except Exception as error:  # doctor must report, not hide, import failures
        result["cuda"] = {"available": False, "error": diagnostic_code(error)}

    if args.allin1_python:
        result['allInOneEnvironment'] = json_probe([
            args.allin1_python, '-B', '-m', 'stemwise_engine.environment_probe', 'allin1'])

    print(json.dumps(result, ensure_ascii=False, indent=2))
    required = result["executables"]
    cuda = result.get("cuda", {})
    packages = result["packages"]
    allin1 = result.get("allInOneEnvironment", {})
    allin1_ok = (
        isinstance(allin1, dict)
        and bool(allin1.get("allin1"))
        and allin1.get("ok") is not False
    )
    youtube_javascript_ok = (
        ejs_version == EXPECTED_EJS_VERSION and deno.get("verified") is True
    )
    return 0 if (
        all(required.values())
        and all(packages.values())
        and cuda.get("available")
        and allin1_ok
        and youtube_javascript_ok
        and wav2vec2_verified
        and future_song_policy.get("ready") is True
    ) else 1


if __name__ == "__main__":
    raise SystemExit(main())
