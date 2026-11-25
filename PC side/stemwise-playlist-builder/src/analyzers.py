"""Normalized adapters for the six required Stemwise analysis runs."""

from __future__ import annotations

from stemwise_engine.common import WorkerTemporaryDirectory
from stemwise_engine.runtime_environment import environment_lease

from bisect import bisect_left
import importlib.metadata
import hashlib
import json
import math
import os
import re
import statistics
import signal
import shutil
import threading
import uuid
from pathlib import Path
from typing import Any, Callable, Iterable

from stemwise_engine.model_registry import model_assets
from stemwise_engine.common import WorkerRetirementError, diagnostic_code, run_process_tree
from stemwise_engine.private_diagnostics import DiagnosticCode, error_record, with_stderr, failure_from_file


_BEAT_THIS: Any = None
_BEAT_THIS_EXECUTION: Any = None
_SKEY_COMPONENTS: tuple[Any, ...] | None = None


# These are content identities, not model-quality claims.  Refuse silently
# replaced weights instead of producing results that look comparable while
# using a different model.
BEAT_THIS_FINAL0_SHA256 = model_assets('analyzers')['BEAT_THIS_FINAL0_SHA256']
SKEY_MODEL_SHA256 = model_assets('analyzers')['SKEY_MODEL_SHA256']
ALLIN1_MODEL_REVISION = model_assets('analyzers')['ALLIN1_MODEL_REVISION']
ALLIN1_MODEL_SHA256 = model_assets('analyzers')['ALLIN1_MODEL_SHA256']
HTDEMUCS_MODEL_REVISION = model_assets('analyzers')['HTDEMUCS_MODEL_REVISION']
HTDEMUCS_MODEL_SHA256 = model_assets('analyzers')['HTDEMUCS_MODEL_SHA256']
SKEY_UNCALIBRATED_REASON = (
    "S-KEY always selects one of 24 Western major/minor classes and publishes "
    "no calibrated confidence or out-of-distribution rejection rule."
)


def package_version(*names: str) -> str:
    for name in names:
        try:
            return importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            continue
    return "source-install"


def round_ms(seconds: float) -> int:
    value = float(seconds)
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"Invalid nonnegative time: {seconds!r}")
    return int(value * 1000.0 + 0.5)


def _verify_file_sha256(path: Path, expected: str, label: str) -> None:
    """Verify a model file before its result is accepted."""

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != expected.lower():
        raise RuntimeError(
            f"{label} content identity mismatch: expected {expected.lower()}, got {actual}"
        )


def _unique_times(values: Iterable[Any], duration_ms: int) -> list[int]:
    result: set[int] = set()
    for raw in values:
        try:
            value = round_ms(float(raw))
        except (TypeError, ValueError, OverflowError):
            continue
        if 0 <= value < duration_ms:
            result.add(value)
    return sorted(result)


def _failed_tool(name: str, error: BaseException, *, version: str | None = None, **extra: Any) -> dict[str, Any]:
    result: dict[str, Any] = {"name": name, "status": "failed"}
    if version:
        result["version"] = version
    result.update(extra)
    result["reason"] = DiagnosticCode(diagnostic_code(error), [error_record(error)])
    return result


def _skipped_tool(name: str, reason: str) -> dict[str, Any]:
    return {"name": name, "status": "skipped", "reason": reason}


def _full_window_reason(duration_ms):
    from stemwise_engine.analysis_resources import analysis_resource_plan
    return analysis_resource_plan(duration_ms)['reason']


def analyze_beat_this(audio_path: Path, duration_ms: int) -> tuple[list[int], list[int], dict[str, Any]]:
    version = package_version("beat-this", "beat_this")
    reason = _full_window_reason(duration_ms)
    if reason:
        return [], [], _skipped_tool('beat-this-full', reason)
    try:
        global _BEAT_THIS, _BEAT_THIS_EXECUTION
        if _BEAT_THIS is None:
            import torch
            from beat_this.inference import File2Beats

            device = "cuda" if torch.cuda.is_available() else "cpu"
            from stemwise_engine.provenance import numerical_execution_identity
            execution = numerical_execution_identity(torch, device)
            checkpoint_path = Path(torch.hub.get_dir()) / "checkpoints" / "beat_this-final0.ckpt"
            if checkpoint_path.is_file():
                _verify_file_sha256(
                    checkpoint_path, BEAT_THIS_FINAL0_SHA256, "Beat This final0 checkpoint"
                )
            candidate = File2Beats(checkpoint_path="final0", device=device, dbn=False)
            if not checkpoint_path.is_file():
                raise RuntimeError("Beat This did not retain its final0 checkpoint in the expected cache")
            _verify_file_sha256(
                checkpoint_path, BEAT_THIS_FINAL0_SHA256, "Beat This final0 checkpoint"
            )
            if not callable(candidate):
                raise RuntimeError("Beat This initialized an invalid model")
            _BEAT_THIS = candidate
            _BEAT_THIS_EXECUTION = execution
        beats, downbeats = _BEAT_THIS(str(audio_path))
        normalized_beats = _unique_times(beats, duration_ms)
        normalized_downbeats = _unique_times(downbeats, duration_ms)
        if not normalized_beats:
            raise RuntimeError("Beat This returned no usable beat events")
        return normalized_beats, normalized_downbeats, {
            "name": "beat-this-full",
            "status": "complete",
            "version": version,
            "model": "final0",
            "profile": json.dumps(_BEAT_THIS_EXECUTION, sort_keys=True, separators=(',', ':')),
        }
    except WorkerRetirementError:
        raise
    except Exception as error:
        return [], [], _failed_tool(
            "beat-this-full", error, version=version, model="final0"
        )


def _nearest_downbeat_indices(beats: list[int], downbeats: list[int]) -> set[int]:
    indices = set()
    for downbeat in downbeats:
        right = bisect_left(beats, downbeat)
        candidates = range(max(0, right - 1), min(len(beats), right + 1))
        nearest = min(candidates, key=lambda i: abs(beats[i] - downbeat), default=None)
        if nearest is not None and abs(beats[nearest] - downbeat) <= 70:
            indices.add(nearest)
    return indices


def normalize_rhythm(
    beat_times_ms: list[int], downbeat_times_ms: list[int], duration_ms: int
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not beat_times_ms:
        reason = "Beat This returned no usable events."
        return {"status": "unavailable", "reason": reason}, _skipped_tool(
            "stemwise-rhythm-normalizer", reason
        )
    try:
        intervals = [
            (right - left) / 1000.0
            for left, right in zip(beat_times_ms, beat_times_ms[1:])
            if 100 <= right - left <= 3000
        ]
        if not intervals:
            raise RuntimeError("Too few valid inter-beat intervals to select tempo")
        median_interval = statistics.median(intervals)
        bpm = 60.0 / median_interval
        if not math.isfinite(bpm) or not 0 < bpm <= 600:
            raise RuntimeError("Derived tempo was outside the contract range")
        bpm = round(bpm, 3)

        # A global tempo needs broad event coverage and predominantly steady intervals.
        covered = sum(intervals) * 1000 >= duration_ms * .5
        consistent = sum(abs(value-median_interval) <= median_interval*.1 for value in intervals) >= .9*len(intervals)
        if not covered or not consistent:
            reason = 'Insufficient beat coverage for global tempo.' if not covered else 'Beat intervals do not support one global tempo.'
            downbeats = _nearest_downbeat_indices(beat_times_ms, downbeat_times_ms)
            return {'status': 'partial', 'reason': reason, 'beats': [
                {'timeMs': value, 'isDownbeat': index in downbeats} for index, value in enumerate(beat_times_ms)
            ]}, {'name': 'stemwise-rhythm-normalizer', 'status': 'complete', 'version': '1'}

        downbeat_indices = _nearest_downbeat_indices(beat_times_ms, downbeat_times_ms)

        meter_numerator: int | None = None
        ordered_downbeats = sorted(downbeat_indices)
        gaps = [b - a for a, b in zip(ordered_downbeats, ordered_downbeats[1:]) if 1 <= b - a <= 32]
        if len(gaps) >= 4:
            counts: dict[int, int] = {}
            for gap in gaps:
                counts[gap] = counts.get(gap, 0) + 1
            selected, occurrences = max(counts.items(), key=lambda pair: (pair[1], -pair[0]))
            if occurrences / len(gaps) >= 0.75:
                meter_numerator = selected

        events: list[dict[str, Any]] = []
        for index, time_ms in enumerate(beat_times_ms):
            is_downbeat = index in downbeat_indices
            event: dict[str, Any] = {"timeMs": time_ms, "isDownbeat": is_downbeat}
            if is_downbeat:
                event["beatInBar"] = 1
            events.append(event)

        # Fill interior beat positions only for spans where two independently
        # detected downbeats are separated by exactly the selected meter. Do
        # not cycle positions through missed/extra beats and do not invent a
        # barIndex when an earlier bar/downbeat may have been missed.
        if meter_numerator is not None:
            for left, right in zip(ordered_downbeats, ordered_downbeats[1:]):
                if right - left != meter_numerator:
                    continue
                for index in range(left + 1, right):
                    events[index]["beatInBar"] = index - left + 1

        rhythm: dict[str, Any] = {"status": "complete", "bpm": bpm, "beats": events}
        alternatives: list[float] = []
        if 20 <= bpm / 2 <= 600:
            alternatives.append(round(bpm / 2, 3))
        if 20 <= bpm * 2 <= 600:
            alternatives.append(round(bpm * 2, 3))
        if alternatives:
            rhythm["alternativeBpms"] = alternatives
        if meter_numerator is not None:
            rhythm["meter"] = {"numerator": meter_numerator}
        # A global median BPM or dominant bar length does not prove that tempo
        # and meter remain constant for the entire recording. The contract makes
        # segments optional, so omit them until a sustained-change detector is
        # independently qualified instead of inventing full-duration ranges.
        return rhythm, {
            "name": "stemwise-rhythm-normalizer",
            "status": "complete",
            "version": "1",
        }
    except Exception as error:
        downbeats = set(downbeat_times_ms)
        return {"status": "partial", "reason": diagnostic_code(error), "beats": [
            {"timeMs": value, "isDownbeat": value in downbeats} for value in beat_times_ms
        ]}, _failed_tool("stemwise-rhythm-normalizer", error, version="1")


def _parse_key_label(label: str) -> tuple[str, str]:
    match = re.fullmatch(r"\s*([A-Ga-g])([#b]?)[\s_-]+(major|minor)\s*", label, re.IGNORECASE)
    if not match:
        raise RuntimeError(f"S-KEY returned an unsupported label: {label!r}")
    return match.group(1).upper() + match.group(2), match.group(3).lower()


def _interpret_skey_scores(scores: Iterable[Any], labels: dict[int, str]) -> dict[str, Any]:
    """Keep S-KEY's forced-choice estimate without calling it calibrated.

    ChromaNet returns a 24-way softmax vector, but the project does not publish
    a calibration set, confidence mapping, or OOD threshold.  Even a very large
    softmax maximum therefore remains an uncertain forced-choice estimate.
    """

    values = [float(value) for value in scores]
    if len(values) != 24 or set(labels) != set(range(24)):
        raise RuntimeError("S-KEY returned an unexpected class vector")
    if any(not math.isfinite(value) or value < 0 for value in values) or sum(values) <= 0:
        raise RuntimeError("S-KEY returned invalid class scores")
    ranked = sorted(range(24), key=lambda index: (-values[index], index))
    label = labels[ranked[0]]
    tonic, mode = _parse_key_label(label)
    result = {
        "status": "uncertain",
        "reason": SKEY_UNCALIBRATED_REASON,
        "tonic": tonic,
        "mode": mode,
    }
    return result


def analyze_skey(audio_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    version = package_version('skey')
    try:
        import soundfile as sf
        info = sf.info(audio_path)
        reason = _full_window_reason(round(info.frames * 1000 / info.samplerate))
        if reason:
            return {'status': 'unavailable', 'reason': reason}, {**_skipped_tool('s-key', reason),
                '_privateKeyEvidence': {'status': 'unavailable', 'reason': reason}}
        global _SKEY_COMPONENTS
        import torch
        from skey.key_detection import (
            DEFAULT_CHECKPOINT_PATH,
            key_map,
            load_audio,
            load_checkpoint,
            load_model_components,
        )

        if _SKEY_COMPONENTS is None:
            device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            _verify_file_sha256(
                Path(DEFAULT_CHECKPOINT_PATH), SKEY_MODEL_SHA256, "S-KEY checkpoint"
            )
            checkpoint = load_checkpoint(DEFAULT_CHECKPOINT_PATH)
            sample_rate = checkpoint["audio"]["sr"]
            components = load_model_components(checkpoint, device)
            _SKEY_COMPONENTS = (*components, device, sample_rate)
        hcqt, chromanet, crop_fn, device, sample_rate = _SKEY_COMPONENTS
        audio = load_audio(str(audio_path), sample_rate).to(device)
        with torch.inference_mode():
            batch = audio.unsqueeze(0).to(device)
            cropped = crop_fn(hcqt(batch), torch.zeros(1, device=device))
            scores = torch.mean(chromanet(cropped), dim=0).detach().cpu().reshape(-1).tolist()
        key = _interpret_skey_scores(scores, key_map)
        from stemwise_engine.provenance import numerical_execution_identity
        execution = numerical_execution_identity(torch, device)
        return key, {
            "name": "s-key",
            "status": "complete",
            "version": version,
            "model": "skey.pt",
            "profile": json.dumps(execution, sort_keys=True, separators=(',', ':')),
            "warnings": [SKEY_UNCALIBRATED_REASON],
            '_privateKeyEvidence': {'status': 'candidate', 'version': version,
                'modelSha256': SKEY_MODEL_SHA256, 'execution': execution, 'inputSampleRate': sample_rate,
                'classScores': [{'classIndex': index, 'label': key_map[index], 'rawScore': float(value)}
                                for index, value in enumerate(scores)]},
        }
    except WorkerRetirementError:
        raise
    except Exception as error:
        return {"status": "unavailable", "reason": diagnostic_code(error)}, {
            **_failed_tool("s-key", error, version=version, model="skey.pt"),
            '_privateKeyEvidence': {'status': 'unavailable', 'reason': diagnostic_code(error)}}


def analyze_tonal_evidence(audio_path: Path, structure: dict[str, Any]) -> dict[str, Any]:
    """Retain independent tonal candidates; agreement never approves a public key."""
    result = {'name': 'essentia-key-extractor', 'version': package_version('essentia'),
              'device': 'cpu', 'channelMix': 'float32-arithmetic-mean'}
    try:
        import numpy as np
        import soundfile as sf
        import essentia.standard as es
        from stemwise_engine.analysis_resources import require_analysis_capacity
        from stemwise_engine.common import sha256_file
        with sf.SoundFile(audio_path) as source:
            result.update(sourceSha256=sha256_file(audio_path), sampleRate=source.samplerate,
                          sourceFrames=source.frames, channels=source.channels)
            extractor = es.KeyExtractor(sampleRate=source.samplerate)
            result['parameters'] = {name: extractor.paramValue(name) for name in extractor.parameterNames()}
            def estimate(start_frame, end_frame):
                record = {'startFrame': start_frame, 'endFrame': end_frame}
                try:
                    count = end_frame - start_frame
                    if count <= 0 or not 0 <= start_frame < end_frame <= source.frames:
                        raise ValueError('tonal-window-invalid')
                    admission = require_analysis_capacity((count * 1000 + source.samplerate - 1)//source.samplerate)
                    if not admission['fullWindowAdmitted']:
                        return {**record, 'status': 'unavailable', 'reason': admission['reason']}
                    source.seek(start_frame)
                    pcm = source.read(count, dtype='float32', always_2d=True)
                    if len(pcm) != count or not np.isfinite(pcm).all():
                        raise ValueError('tonal-window-incomplete')
                    tonic, mode, strength = extractor(np.mean(pcm, axis=1, dtype=np.float32))
                    tonic, mode = _parse_key_label(f'{tonic} {mode}')
                    strength = float(strength)
                    if not math.isfinite(strength):
                        raise ValueError('tonal-strength-invalid')
                    return {**record, 'status': 'candidate', 'tonic': tonic, 'mode': mode, 'rawStrength': strength}
                except WorkerRetirementError:
                    raise
                except Exception as error:
                    from stemwise_engine.provider_operations import raise_if_operation_stopped
                    raise_if_operation_stopped(error)
                    return {**record, 'status': 'unavailable', 'reason': diagnostic_code(error)}
            result['recording'] = estimate(0, source.frames)
            sections = result['sections'] = []
            for index, section in enumerate(structure.get('sections', [])):
                if any(type(section[field]) is not int for field in ('startMs', 'endMs')):
                    raise ValueError('tonal-section-boundary-invalid')
                start = section['startMs'] * source.samplerate // 1000
                end = section['endMs'] * source.samplerate // 1000
                sections.append({'sectionIndex': index, 'label': section['label'],
                    'startMs': section['startMs'], 'endMs': section['endMs'],
                    **estimate(start, min(end, source.frames))})
            supported = [section for section in sections if section['status'] == 'candidate']
            result['sectionConsistency'] = {'analyzedSections': len(supported), 'totalSections': len(sections),
                'structureStatus': structure.get('status', 'unavailable'),
                'matchingRecordingSections': (sum((section['tonic'], section['mode']) ==
                    (result['recording']['tonic'], result['recording']['mode']) for section in supported)
                    if result['recording']['status'] == 'candidate' else None),
                'allSectionsAgree': bool(sections) and len(supported) == len(sections)
                    and len({(section['tonic'], section['mode']) for section in supported}) == 1}
            result['status'] = 'candidate' if result['recording']['status'] == 'candidate' or supported else 'unavailable'
    except WorkerRetirementError:
        raise
    except Exception as error:
        from stemwise_engine.provider_operations import raise_if_operation_stopped
        raise_if_operation_stopped(error)
        result.update(status='unavailable', reason=diagnostic_code(error))
    return result


def _allin1_python(project_dir: Path) -> Path:
    configured = os.environ.get("STEMWISE_ALLIN1_PYTHON")
    if configured:
        return Path(configured)
    from stemwise_engine.runtime_environment import environment_python
    return environment_python('allin1', project_dir)





def analyze_allin1(
    audio_path: Path,
    duration_ms: int,
    *,
    project_dir: Path,
    timeout_seconds: int,
    cancelled: Callable[[], bool] | None = None,
) -> tuple[dict[str, Any], dict[str, Any] | None, dict[str, Any]]:
    reason = _full_window_reason(duration_ms)
    if reason:
        return {'status': 'unavailable', 'reason': reason}, None, _skipped_tool('all-in-one', reason)
    python = _allin1_python(project_dir)
    version = "source-install"
    if not python.is_file():
        error = RuntimeError("The isolated All-In-One environment is not installed")
        return {"status": "unavailable", "reason": str(error)}, None, _failed_tool(
            "all-in-one", error, version=version, model="harmonix-all"
        )
    try:
        with environment_lease('allin1', builder=project_dir):
            completed = run_process_tree(
                [
                    str(python),
                    "-B", '-m', 'stemwise_engine.allin1_worker',
                    "single", "--timeout-seconds", str(timeout_seconds), str(audio_path),
                ],
                cwd=audio_path.parent,
                text=True,
                capture_output=True,
                check=False,
                timeout=timeout_seconds,
                env={**os.environ, "PYTHONUNBUFFERED": "1"},
                cancelled=cancelled,
            )
        marker_lines = [
            line.removeprefix("STEMWISE_RESULT=")
            for line in completed.stdout.splitlines()
            if line.startswith("STEMWISE_RESULT=")
        ]
        if completed.returncode != 0 or not marker_lines:
            message = completed.stderr.strip()[-500:] or completed.stdout.strip()[-500:]
            raise with_stderr(RuntimeError(f"All-In-One failed: {message}"), completed.stderr)
        raw = json.loads(marker_lines[-1])
        version = str(raw.get("version") or version)

        return _normalize_allin1_result(raw, duration_ms)
    except (WorkerRetirementError, InterruptedError):
        raise
    except Exception as error:
        return {"status": "unavailable", "reason": diagnostic_code(error)}, None, _failed_tool(
            "all-in-one", error, version=version, model="harmonix-all"
        )


def _normalize_allin1_result(raw: dict[str, Any], duration_ms: int) -> tuple[dict[str, Any], dict[str, Any] | None, dict[str, Any]]:
    version = str(raw.get("version") or "source-install")
    sections: list[dict[str, Any]] = []
    discarded: set[str] = set()
    for segment in raw.get("segments") or []:
        if not isinstance(segment, dict) or not str(segment.get("label") or "").strip():
            discarded.add('missing label')
            continue
        try:
            start = min(round_ms(segment["start"]), duration_ms)
            end = min(round_ms(segment["end"]), duration_ms)
        except (KeyError, TypeError, ValueError):
            discarded.add('invalid bounds')
            continue
        if start < end and (not sections or start >= sections[-1]["endMs"]):
            sections.append({"startMs": start, "endMs": end, "label": str(segment["label"]).strip()})
        else:
            discarded.add('overlapping or reversed bounds')
    if sections:
        boundaries = [(0, sections[0]['startMs']),
                      *((left['endMs'], right['startMs']) for left, right in zip(sections, sections[1:])),
                      (sections[-1]['endMs'], duration_ms)]
        gaps = [(start, end) for start, end in boundaries if start < end]
        if gaps:
            start, end = gaps[0]
            discarded.add(f'uncovered {start}–{end} ms ({sum(b-a for a,b in gaps)} ms total)')
        structure = {"status": "partial" if discarded else "complete", "sections": sections}
        if discarded:
            structure['reason'] = 'All-In-One discarded sections: ' + ', '.join(sorted(discarded)) + '.'
    else:
        structure = {"status": "unavailable", "reason": "all-in-one-no-usable-sections"}

    beats = _unique_times(raw.get("beats") or [], duration_ms)
    raw_downbeats = _unique_times(raw.get("downbeats") or [], duration_ms)
    downbeats = [beats[i] for i in sorted(_nearest_downbeat_indices(beats, raw_downbeats))]
    diagnostic: dict[str, Any] = {}
    bpm = raw.get("bpm")
    if isinstance(bpm, (int, float)) and math.isfinite(bpm) and 0 < bpm <= 600:
        diagnostic["bpm"] = round(float(bpm), 3)
    if beats:
        diagnostic["beatsMs"] = beats
    if downbeats:
        diagnostic["downbeatsMs"] = downbeats
    if diagnostic:
        complete = "bpm" in diagnostic and "beatsMs" in diagnostic
        diagnostic["status"] = "complete" if complete else "partial"
        if not complete:
            diagnostic["reason"] = "All-In-One returned only part of its rhythm diagnostic."
        # Keep status first for readable reports; object order is non-normative.
        diagnostic = {"status": diagnostic.pop("status"), **diagnostic}
    else:
        diagnostic = None

    warnings: list[str] = []
    if structure["status"] != "complete":
        warnings.append(structure["reason"])
    tool: dict[str, Any] = {
        "name": "all-in-one",
        "status": "complete",
        "version": version,
        "model": "harmonix-all",
    }
    if warnings:
        tool["warnings"] = warnings
    if 'demixProfile' in raw:
        tool['profile'] = json.dumps(raw['demixProfile'], sort_keys=True, separators=(',', ':'))
    return structure, diagnostic, tool




def analyze_allin1_many(
    inputs: list[tuple[Path, int]],
    *,
    project_dir: Path,
    timeout_seconds: int,
    on_result: Callable[[int, tuple[dict[str, Any], dict[str, Any] | None, dict[str, Any]]], None] | None = None,
    cancelled: Callable[[], bool] | None = None,
) -> list[tuple[dict[str, Any], dict[str, Any] | None, dict[str, Any]]]:
    """Share pinned models, retaining only independently completed current inputs."""
    if not inputs or type(timeout_seconds) is not int or timeout_seconds <= 0:
        raise ValueError("All-In-One requires inputs and a positive per-song timeout")
    if any(_full_window_reason(duration) for _, duration in inputs):
        results = []
        for index, (audio, duration) in enumerate(inputs):
            if cancelled is not None and cancelled():
                raise InterruptedError('Acoustic batch cancelled')
            result = analyze_allin1(audio, duration, project_dir=project_dir, timeout_seconds=timeout_seconds,
                                   cancelled=cancelled)
            results.append(result)
            if on_result is not None:
                on_result(index, result)
        return results
    sources: list[tuple[Path, int]] = []
    for audio, duration in inputs:
        path = Path(audio).resolve()
        if not path.is_file() or type(duration) is not int or duration <= 0:
            raise ValueError("All-In-One requires existing audio and a positive duration")
        if any(path == existing for existing, _ in sources):
            raise ValueError("All-In-One duplicate inputs must share one analysis")
        sources.append((path, duration))
    python = _allin1_python(project_dir)
    def failed(error: Exception):
        return {"status": "unavailable", "reason": diagnostic_code(error)}, None, _failed_tool(
            "all-in-one", error, version="source-install", model="harmonix-all")
    if not python.is_file():
        results = [failed(RuntimeError("The isolated All-In-One environment is not installed")) for _ in sources]
        if on_result is not None:
            for index, result in enumerate(results):
                on_result(index, result)
        return results
    # Keep scratch inside the admitted input owner's reclaimable operation directory.
    work_root = sources[0][0].parent
    with WorkerTemporaryDirectory(prefix="allin1-", dir=work_root) as directory:
        work = Path(directory)
        request_id = uuid.uuid4().hex
        items = []
        for index, (source, duration) in enumerate(sources):
            alias = work / f"input-{index:06d}{source.suffix}"
            try:
                os.link(source, alias)
            except OSError:
                shutil.copyfile(source, alias)
            items.append({"index": index, "source": str(source), "audio": str(alias), "durationMs": duration})
        request = {"requestId": request_id, "timeoutSeconds": timeout_seconds, "inputs": items}
        request_path = work / "request.json"
        request_path.write_text(json.dumps(request, separators=(",", ":")), encoding="utf-8")
        failure: Exception = RuntimeError("All-In-One returned no current result")
        def read_result(index: int):
            source, duration = sources[index]
            result = failed(failure)
            path = work / f"result-{index}.json"
            if not path.is_file():
                return None
            try:
                if path.stat().st_size > 4 * 1024 * 1024:
                    raise ValueError("All-In-One result is too large")
                row = json.loads(path.read_text(encoding="utf-8"))
                if (not isinstance(row, dict) or type(row.get("index")) is not int or
                        row.get("index") != index or row.get("requestId") != request_id or
                        row.get("source") != str(source) or type(row.get("durationMs")) is not int or
                        row.get("durationMs") != duration):
                    raise ValueError("All-In-One result does not match current input")
                if set(row) == {"requestId", "index", "source", "durationMs", "result"} and isinstance(row["result"], dict):
                    result = _normalize_allin1_result(row["result"], duration)
                elif set(row) == {"requestId", "index", "source", "durationMs", "error"}:
                    code = row["error"]
                    if not isinstance(code, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,119}", code):
                        raise ValueError("Malformed All-In-One failure code")
                    result[0]["reason"] = result[2]["reason"] = failure_from_file(
                        code, work/f'failure-{index}.private.txt', result[2]['reason'])
                else:
                    raise ValueError("Malformed All-In-One result")
            except Exception as error:
                result = failed(error)
            return result
        results: list[Any] = [None] * len(sources)
        stop = threading.Event()
        callback_errors: list[BaseException] = []
        def monitor_results():
            try:
                while not stop.wait(.05):
                    for index in range(len(sources)):
                        if results[index] is None:
                            result = read_result(index)
                            if result is not None:
                                results[index] = result
                                on_result(index, result)
            except BaseException as error:
                callback_errors.append(error)
        monitor = threading.Thread(target=monitor_results, name="stemwise-allin1-results") if on_result is not None else None
        if monitor is not None:
            monitor.start()
        try:
            try:
                while True:
                    with environment_lease('allin1', builder=project_dir):
                        completed = run_process_tree(
                            [str(python), "-B", '-m', 'stemwise_engine.allin1_worker',
                             "batch", "--timeout-seconds", str(timeout_seconds), str(request_path)], cwd=work, text=True, capture_output=True, check=False,
                            timeout=timeout_seconds * len(request['inputs']), env={**os.environ, "PYTHONUNBUFFERED": "1"},
                            cancelled=cancelled)
                    if completed.returncode != 0:
                        failure = with_stderr(RuntimeError("All-In-One batch failed: " + completed.stderr.strip()[-500:]), completed.stderr)
                    if completed.returncode != -signal.SIGALRM:
                        break
                    # run_process_tree has retired every descendant before returning.
                    active_path = work/'batch-active.json'
                    active = json.loads(active_path.read_text(encoding='utf-8'))
                    indices = {item['index'] for item in request['inputs']}
                    used = active.get('usedSeconds')
                    if (active.get('requestId') != request_id or type(active.get('index')) is not int
                            or active['index'] not in indices or not isinstance(used, dict)
                            or set(used) != {str(index) for index in indices}
                            or any(type(value) not in (int, float) or not math.isfinite(value) or value < 0
                                   for value in used.values())):
                        raise ValueError('All-In-One timeout identity is invalid')
                    index = active['index']
                    if results[index] is None and read_result(index) is None:
                        results[index] = failed(with_stderr(TimeoutError('All-In-One per-song deadline expired'), completed.stderr))
                        if on_result is not None:
                            try:
                                on_result(index, results[index])
                            except BaseException as error:
                                callback_errors.append(error)
                                raise
                    pending = [item for item in request['inputs'] if results[item['index']] is None
                               and read_result(item['index']) is None and used[str(item['index'])] < timeout_seconds]
                    if not pending:
                        break
                    request = {'requestId': request_id, 'timeoutSeconds': timeout_seconds, 'inputs': pending,
                        'spentSeconds': {str(item['index']): used[str(item['index'])] for item in pending}}
                    active_path.unlink()
                    request_path.write_text(json.dumps(request), encoding='utf-8')
            except (WorkerRetirementError, InterruptedError):
                raise
            except Exception as error:
                failure = error
        finally:
            stop.set()
            if monitor is not None:
                monitor.join()
        if callback_errors:
            raise callback_errors[0]
        for index in range(len(sources)):
            if results[index] is None:
                results[index] = read_result(index) or failed(failure)
                if on_result is not None:
                    on_result(index, results[index])
        return results


def _finite_scalar(value: Any) -> float:
    try:
        # Essentia sometimes wraps scalar outputs in a length-one ndarray.
        import numpy as np

        array = np.asarray(value, dtype=float).reshape(-1)
        finite = array[np.isfinite(array)]
        if not finite.size:
            raise ValueError("no finite value")
        return float(finite[-1])
    except Exception as error:
        raise ValueError("Analyzer returned no finite scalar") from error


def _essentia_mono_frames(audio_path, duration_ms, es):
    import numpy as np
    from stemwise_engine.analysis_resources import analysis_resource_policy
    policy = analysis_resource_policy()
    for start in range(0, duration_ms, policy.essentiaWindowMs):
        length = min(policy.essentiaWindowMs, duration_ms-start)
        result = run_process_tree(['ffmpeg', '-v', 'error', '-ss', str(start/1000), '-i', str(audio_path),
            '-t', str(length/1000), '-ac', '1', '-ar', '44100', '-f', 'f32le', 'pipe:1'],
            capture_output=True, check=True, timeout=120)
        mono = np.frombuffer(result.stdout, dtype='<f4')
        if len(mono) < 4096 or not np.isfinite(mono).all():
            if start + length == duration_ms and len(mono) < 4096:
                continue
            raise ValueError('Essentia window decode is incomplete')
        yield from es.FrameGenerator(mono, frameSize=4096, hopSize=2048, startFromZero=True)


def _stream_loudness(audio_path):
    result = run_process_tree(['ffmpeg', '-hide_banner', '-nostats', '-i', str(audio_path),
        '-filter:a', 'ebur128=framelog=verbose', '-f', 'null', '-'],
        capture_output=True, check=True, text=True, timeout=600)
    summary = result.stderr.rsplit('Summary:', 1)[-1]
    integrated = re.search(r'Integrated loudness:\s*I:\s*(-?[0-9.]+) LUFS', summary)
    spread = re.search(r'Loudness range:\s*LRA:\s*([0-9.]+) LU', summary)
    if integrated is None or spread is None:
        raise ValueError('Streaming EBU loudness summary is incomplete')
    return {'status': 'complete', 'integratedLufs': float(integrated[1]), 'loudnessRangeLu': float(spread[1])}


def analyze_essentia(audio_path: Path) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    version = package_version("essentia")
    tuning: dict[str, Any]
    loudness: dict[str, Any]
    warnings: list[str] = []
    try:
        import numpy as np
        import soundfile as sf
        from stemwise_engine.analysis_resources import require_analysis_capacity
        info = sf.info(audio_path)
        from stemwise_engine.common import source_duration_ms
        duration_ms = source_duration_ms(info.frames, info.samplerate)
        admission = require_analysis_capacity(duration_ms)
        import essentia.standard as es
        windowed = not admission['fullWindowAdmitted']
        mono_sample_rate = 44100
        mono = None if windowed else es.MonoLoader(filename=str(audio_path), sampleRate=mono_sample_rate)()
        if mono is not None and mono.size < 4096:
            raise RuntimeError("Audio was too short for Essentia analysis")

        frames = frame = None
        try:
            window = es.Windowing(type="hann")
            spectrum = es.Spectrum()
            peaks = es.SpectralPeaks(minFrequency=40, maxFrequency=5000, magnitudeThreshold=1e-5)
            estimator = es.TuningFrequency()
            frequencies: list[float] = []
            observed_frames = 0
            frames = (_essentia_mono_frames(audio_path, duration_ms, es) if windowed else
                es.FrameGenerator(mono, frameSize=4096, hopSize=2048, startFromZero=True))
            for frame in frames:
                observed_frames += 1
                peak_frequencies, magnitudes = peaks(spectrum(window(frame)))
                if len(peak_frequencies) < 2:
                    continue
                # The estimator accumulates a histogram across calls. Each frame
                # must have independent evidence before testing global agreement.
                estimator.reset()
                frequency, _ = estimator(peak_frequencies, magnitudes)
                frequency = float(frequency)
                if math.isfinite(frequency) and 400 <= frequency <= 480:
                    frequencies.append(frequency)
            if len(frequencies) < 8:
                tuning = {'status': 'unavailable', 'reason': 'tuning-insufficient-pitched-frames'}
            else:
                selected = float(statistics.median(frequencies))
                cents = 1200.0 * math.log2(selected / 440.0)
                cents_values = [1200.0 * math.log2(value / selected) for value in frequencies]
                dispersion = float(statistics.median(abs(value) for value in cents_values))
                agreeing = sum(abs(value) <= 8.0 for value in cents_values)
                tuning_status = "complete" if dispersion <= 8.0 and agreeing >= .9*len(frequencies) and len(frequencies) >= .5*observed_frames else "uncertain"
                tuning = {
                    "status": tuning_status,
                    "frequencyHz": round(selected, 4),
                    "centsFromA440": round(cents, 3),
                }
                if tuning_status != "complete":
                    tuning["reason"] = "Pitched frames give insufficient coverage or agreement for one recording-wide tuning."
        except WorkerRetirementError:
            raise
        except Exception as error:
            tuning = {"status": "unavailable", "reason": diagnostic_code(error)}
            warnings.append(DiagnosticCode(f"Tuning unavailable: {diagnostic_code(error)}", [error_record(error)]))
        finally:
            # FrameGenerator and its last view can otherwise retain the full mono array.
            frames = frame = None

        if windowed:
            try:
                loudness = _stream_loudness(audio_path)
            except WorkerRetirementError:
                raise
            except Exception as error:
                loudness = {'status': 'unavailable', 'reason': diagnostic_code(error)}
                warnings.append(DiagnosticCode(f'Loudness unavailable: {diagnostic_code(error)}', [error_record(error)]))
            warnings.append('Dynamic complexity omitted: its full-recording producer exceeds the duration budget.')
            return tuning, loudness, {'name': 'essentia', 'status': ('failed' if
                tuning['status'] == loudness['status'] == 'unavailable' else 'complete'), 'version': version,
                'profile': 'stemwise-v2-windowed', 'warnings': warnings, **({'reason': 'essentia-features-unavailable'}
                    if tuning['status'] == loudness['status'] == 'unavailable' else {})}

        descriptor = None
        try:
            complexity, _ = es.DynamicComplexity(sampleRate=mono_sample_rate)(mono)
            descriptor = {'id': 'dynamic-complexity', 'value': round(_finite_scalar(complexity), 3), 'unit': 'dB'}
        except Exception as error:
            warnings.append(DiagnosticCode(f'Dynamic complexity unavailable: {diagnostic_code(error)}', [error_record(error)]))
        del mono
        try:
            loaded = es.AudioLoader(filename=str(audio_path))()
            audio = loaded[0]
            sample_rate = float(loaded[1])
            if getattr(audio, "ndim", 1) == 1:
                stereo = np.column_stack((audio, audio))
            elif audio.shape[1] == 1:
                stereo = np.repeat(audio, 2, axis=1)
            else:
                stereo = audio[:, :2]
            ebu = es.LoudnessEBUR128(sampleRate=sample_rate)
            _, _, integrated, loudness_range = ebu(stereo)
            integrated_value = _finite_scalar(integrated)
            range_value = _finite_scalar(loudness_range)
            loudness = {
                "status": "complete",
                "integratedLufs": round(integrated_value, 3),
                "loudnessRangeLu": round(range_value, 3),
            }
            if descriptor is not None:
                loudness['descriptors'] = [descriptor]
        except Exception as error:
            loudness = {"status": "unavailable", "reason": diagnostic_code(error)}
            warnings.append(DiagnosticCode(f"Loudness unavailable: {diagnostic_code(error)}", [error_record(error)]))

        tool: dict[str, Any] = {
            "name": "essentia",
            "status": ('failed' if tuning['status'] == loudness['status'] == 'unavailable' else 'complete'),
            "version": version,
            "profile": "stemwise-v1",
        }
        if tool['status'] == 'failed':
            tool['reason'] = 'essentia-features-unavailable'
        if warnings:
            tool["warnings"] = warnings
        return tuning, loudness, tool
    except WorkerRetirementError:
        raise
    except Exception as error:
        reason = diagnostic_code(error)
        return (
            {"status": "unavailable", "reason": reason},
            {"status": "unavailable", "reason": reason},
            _failed_tool("essentia", error, version=version, profile="stemwise-v1"),
        )


def overall_analysis_status(analysis: dict[str, Any]) -> str:
    statuses = [analysis[name]["status"] for name in ("rhythm", "key", "structure", "tuning", "loudness", "waveform")]
    if all(value == "complete" for value in statuses):
        return "complete"
    if any(value in ("complete", "partial", "uncertain") for value in statuses):
        return "partial"
    return "unavailable"
