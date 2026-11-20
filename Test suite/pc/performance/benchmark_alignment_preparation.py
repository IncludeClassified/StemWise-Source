"""Compare two fresh MMS preparations with one compatible batch; print results and clean up."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import threading
import time
from contextlib import contextmanager

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT / "PC side/stemwise-playlist-builder"
WORKER = PROJECT / "src/mms_forced_alignment.py"


@contextmanager
def benchmark_directory(work):
    from stemwise_engine.common import (WorkerRetirementError, WORKER_DIRECTORY_LOCK,
        create_manifested_directory, exclusive_process_lock, remove_manifested_directory)
    root, owner = ROOT/'Test suite/work', {'kind': 'alignment-preparation-benchmark'}
    create_manifested_directory(root, work, owner=owner, workspace_root=ROOT)
    unretired = False
    try:
        with exclusive_process_lock(work/WORKER_DIRECTORY_LOCK):
            yield
    except WorkerRetirementError:
        unretired = True
        print(f'Unretired alignment benchmark workspace retained: {work}', file=sys.stderr)
        raise
    finally:
        if not unretired:
            remove_manifested_directory(root, work, owner=owner, workspace_root=ROOT)


def child(manifest: Path, reply: Path, device: str):
    from unittest.mock import patch
    import torchaudio
    import stemwise_engine.mms_forced_alignment as mms_forced_alignment

    bundle = torchaudio.pipelines.MMS_FA
    kind, loads, seconds = type(bundle), 0, 0.0
    original = kind.get_model

    def load(self, *args, **kwargs):
        nonlocal loads, seconds
        started = time.perf_counter()
        try:
            return original(self, *args, **kwargs)
        finally:
            if self is bundle:
                loads += 1
                seconds += time.perf_counter() - started

    with patch.object(kind, "get_model", load):
        mms_forced_alignment.prepare_many(manifest, reply, device, project_dir=PROJECT)
    print("STEMWISE_PREP_BENCH=" + json.dumps({"modelLoads": loads, "modelLoadSeconds": seconds}), flush=True)


def telemetry(stop, samples, executable):
    while not stop.is_set():
        try:
            result = subprocess.run([executable, "--query-gpu=utilization.gpu,memory.used,power.draw",
                "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=2)
            if result.returncode == 0:
                samples.append([float(value.strip()) for value in result.stdout.splitlines()[0].split(",")])
        except (OSError, ValueError, IndexError, subprocess.TimeoutExpired):
            pass  # Telemetry is optional and cannot approve a numerical result.
        stop.wait(.5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audio", type=Path, required=True, nargs=2, metavar=("FIRST", "SECOND"),
                        help="Two distinct retained PCM WAV files inside this project.")
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cuda")
    parser.add_argument("--work", type=Path, required=True,
                        help="A new disposable directory beneath Test suite/work; removed on exit.")
    args = parser.parse_args()
    try:
        audio = [path.resolve(strict=True) for path in args.audio]
        for path in audio:
            path.relative_to(ROOT)
            if not path.is_file():
                raise ValueError("--audio must name regular files")
        if audio[0] == audio[1]:
            raise ValueError("provide two distinct recordings")
        work = args.work.resolve()
        if work == ROOT / "Test suite/work":
            raise ValueError("--work must be a new child directory, not the test work root")
        work.relative_to(ROOT / "Test suite/work")
        if work.exists() or work.is_symlink():
            raise ValueError("--work already exists; existing files will never be removed")
        if not work.parent.is_dir():
            raise ValueError("--work parent must already exist")
    except (OSError, ValueError) as error:
        parser.error(str(error))
    if sys.platform != "linux":
        parser.error("run this model benchmark using the configured WSL .venv Python")

    import resource
    import soundfile
    from stemwise_engine.common import heavy_worker_stage, run_process_tree, sha256_file
    from stemwise_engine.runtime_environment import environment_python, environment_lease
    from stemwise_engine.lyrics_align_pc import PINNED_MMS_SHA256, _worker_pcm_duration_ms
    from stemwise_engine.alignment_models import require_pinned_file

    checkpoint = ROOT / "PC side/model-cache/torch/hub/checkpoints/model.pt"
    require_pinned_file(checkpoint, PINNED_MMS_SHA256, "MMS_FA checkpoint")
    worker_sha = sha256_file(WORKER)
    checkpoint_identity = checkpoint.stat()
    checkpoint_identity = tuple(getattr(checkpoint_identity, name) for name in
                                ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns"))
    entries = []
    for index, path in enumerate(audio):
        info = soundfile.info(path)
        if info.format != "WAV" or info.frames <= 0 or info.samplerate <= 0:
            parser.error("--audio requires nonempty PCM WAV recordings")
        digest = sha256_file(path)
        entries.append({"contextId": str(index), "audio": str(path), "audioSha256": digest,
            "durationMs": _worker_pcm_duration_ms({'stream': {'frameCount': info.frames, 'sampleRate': info.samplerate}}),
            "emissionCacheKey": hashlib.sha256(("prep-benchmark:" + digest).encode()).hexdigest()})
    if entries[0]["audioSha256"] == entries[1]["audioSha256"]:
        parser.error("provide recordings with different audio bytes")
    environment = {**os.environ, "PYTHONPATH": str(PROJECT / "src"),
        "TORCH_HOME": str(ROOT / "PC side/model-cache/torch"), "HF_HUB_OFFLINE": "1",
        "TRANSFORMERS_OFFLINE": "1", "HF_DATASETS_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1",
        "PYTHONDONTWRITEBYTECODE": "1", "CUBLAS_WORKSPACE_CONFIG": ":4096:8"}
    python = environment_python('main', PROJECT)
    smi = shutil.which("nvidia-smi") or "/usr/lib/wsl/lib/nvidia-smi"
    measurements = []
    with benchmark_directory(work):
        for iteration, mode in enumerate(("serial", "batch", "batch", "serial")):
            if sha256_file(WORKER) != worker_sha:
                raise RuntimeError("MMS worker changed during the comparison")
            current_stat = checkpoint.stat()
            if tuple(getattr(current_stat, name) for name in
                     ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")) != checkpoint_identity:
                raise RuntimeError("MMS checkpoint changed during the comparison")
            directory = work / f"{iteration}-{mode}"
            directory.mkdir()
            manifests = []
            for index, group in enumerate(([row] for row in entries) if mode == "serial" else [entries]):
                contexts = []
                for entry in group:
                    output = directory / entry["contextId"] / f"emissions-{entry['emissionCacheKey']}.pt"
                    output.parent.mkdir()
                    contexts.append({**entry, "cacheOutput": str(output)})
                request, reply = directory / f"{index}.request.json", directory / f"{index}.reply.json"
                request.write_text(json.dumps({"formatVersion": 1, "contexts": contexts,
                                               "timeoutSeconds": 300}), encoding="utf-8")
                manifests.append((request, reply))
            queued = time.perf_counter()
            samples, stop = [], threading.Event()
            with heavy_worker_stage({"projectDir": str(PROJECT)}, wait_seconds=1800), \
                    environment_lease('main', builder=PROJECT):
                queue_seconds = time.perf_counter() - queued
                started = time.perf_counter()
                cpu_start = resource.getrusage(resource.RUSAGE_CHILDREN)
                monitor = threading.Thread(target=telemetry, args=(stop, samples, smi))
                monitor.start()
                loads, load_seconds = 0, 0.0
                try:
                    for request, reply in manifests:
                        completed = run_process_tree([str(python), "-B", str(Path(__file__)), "--child",
                            str(request), str(reply), args.device], timeout=660, env=environment,
                            capture_output=True, text=True)
                        if completed.returncode != 0:
                            sys.stderr.write((completed.stderr or "")[-2000:])
                            raise RuntimeError(f"owned MMS preparation failed with exit {completed.returncode}")
                        counters = [line.split("=", 1)[1] for line in completed.stdout.splitlines()
                                    if line.startswith("STEMWISE_PREP_BENCH=")]
                        if len(counters) != 1:
                            raise RuntimeError("model-load counter missing or repeated")
                        counter = json.loads(counters[0])
                        loads += counter["modelLoads"]
                        load_seconds += counter["modelLoadSeconds"]
                    seconds = time.perf_counter() - started
                    cpu = resource.getrusage(resource.RUSAGE_CHILDREN)
                    cpu_seconds = cpu.ru_utime + cpu.ru_stime - cpu_start.ru_utime - cpu_start.ru_stime
                finally:
                    stop.set()
                    monitor.join(3)
                if monitor.is_alive():
                    raise RuntimeError("benchmark telemetry did not retire")
            if loads != (2 if mode == "serial" else 1):
                raise AssertionError(f"unexpected model-load count: {mode}={loads}")
            import torch
            for entry in entries:
                name = f"emissions-{entry['emissionCacheKey']}.pt"
                value = torch.load(directory / entry["contextId"] / name, map_location="cpu", weights_only=True)
                reference = torch.load(work / "0-serial" / entry["contextId"] / name,
                                       map_location="cpu", weights_only=True)
                if value.get("key") != entry["emissionCacheKey"] or set(value) != set(reference):
                    raise AssertionError("emission cache fields differ")
                for key in value:
                    equal = (torch.equal(value[key], reference[key]) if isinstance(value[key], torch.Tensor)
                             else value[key] == reference[key])
                    if not equal:
                        raise AssertionError(f"emission tensor or chunk mapping differs: {key}")
            result = {"iteration": iteration, "mode": mode, "seconds": seconds,
                "queueWaitSeconds": queue_seconds, "modelLoads": loads, "modelLoadSeconds": load_seconds,
                "cpuCorePercent": 100 * cpu_seconds / seconds,
                "gpuMeanPercent": statistics.mean(row[0] for row in samples) if samples else None,
                "gpuPeakMiB": max(row[1] for row in samples) if samples else None,
                "gpuMeanWatts": statistics.mean(row[2] for row in samples) if samples else None}
            measurements.append(result)
            print(json.dumps(result), flush=True)
        if sha256_file(WORKER) != worker_sha:
            raise RuntimeError("MMS worker changed during the comparison")
        serial = statistics.mean(row["seconds"] for row in measurements if row["mode"] == "serial")
        batch = statistics.mean(row["seconds"] for row in measurements if row["mode"] == "batch")
        print(json.dumps({"serialMeanSeconds": serial, "batchMeanSeconds": batch, "speedup": serial / batch,
            "allEmissionsExactlyEqual": True,
            "scope": "two full MMS preparations including process/model startup; excludes queue wait and lyric decoding"}), flush=True)


if __name__ == "__main__":
    if len(sys.argv) == 5 and sys.argv[1] == "--child":
        child(Path(sys.argv[2]), Path(sys.argv[3]), sys.argv[4])
    else:
        main()
