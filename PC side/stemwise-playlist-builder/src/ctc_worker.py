"""Shared CTC request, emission publication and bounded model-worker lifecycle.

Adapters own tokenization and model numerics; this module owns target-independent
request validation, exact time projection, scratch reuse and batch retirement.
"""
from __future__ import annotations
from stemwise_engine.worker_protocol import WorkerReasonError
import gc
import hashlib
import json
import os
import pickle
import re
import signal
import sys
import threading
import time
import traceback
from contextlib import ExitStack, contextmanager
from fractions import Fraction
from pathlib import Path
from typing import Any, Callable
from stemwise_engine.worker_protocol import read_worker_json, fatal_model_failure
from stemwise_engine.worker_publication import publish_json
from stemwise_engine.common import source_duration_ms

REQUEST_VERSION = 5
EOF_ROUNDING_MS = 1
SEGMENT_BOUNDARY_GUARD_MS = 250
_SEGMENT_KEYS = {"segmentIndex", "startLineIndex", "endLineIndex", "windowStartMs", "windowEndMs"}


def project_eof_end(start_ms: int, end_ms: int, duration_ms: int) -> int:
    if (not 0 <= start_ms < end_ms <= duration_ms + EOF_ROUNDING_MS
            or start_ms >= min(end_ms, duration_ms)):
        raise ValueError('Endpoint interval exceeds or collapses at source EOF')
    return min(end_ms, duration_ms)

def validate_request(value: Any) -> tuple[int, list[dict[str, int]]]:
    if not isinstance(value, dict) or value.get("formatVersion") != REQUEST_VERSION:
        raise ValueError("forced-alignment request has the wrong format version")
    duration_ms = value.get("durationMs")
    if type(duration_ms) is not int or duration_ms <= 0:
        raise ValueError("forced-alignment request duration is malformed")
    raw_lines = value.get("lines")
    if not isinstance(raw_lines, list) or not raw_lines:
        raise ValueError("forced-alignment request has no lyric lines")
    for item in raw_lines:
        if (not isinstance(item, dict) or type(item.get("sourcePhysicalIndex")) is not int
                or item["sourcePhysicalIndex"] < 0):
            raise ValueError("forced-alignment line record is malformed")
        identifier(item.get("lineId"), "line ID")
        raw_words = item.get("words")
        if not isinstance(raw_words, list) or not raw_words:
            raise ValueError("forced-alignment line has no display words")
        for word in raw_words:
            if not isinstance(word, dict) or not isinstance(word.get("acousticText"), str):
                raise ValueError("forced-alignment word record is malformed")
            identifier(word.get("wordId"), "word ID")
    raw_segments = value.get("segments")
    if not isinstance(raw_segments, list) or not raw_segments:
        raise ValueError("forced-alignment request has no segments")
    segments: list[dict[str, int]] = []
    previous_line_end = 0
    previous_window_end = -1
    for expected_index, segment in enumerate(raw_segments):
        if not isinstance(segment, dict) or set(segment) != _SEGMENT_KEYS:
            raise ValueError("forced-alignment segment is malformed")
        if any(type(segment[key]) is not int for key in _SEGMENT_KEYS):
            raise ValueError("forced-alignment segment is malformed")
        start_line = segment["startLineIndex"]
        end_line = segment["endLineIndex"]
        window_start = segment["windowStartMs"]
        window_end = segment["windowEndMs"]
        if (
            segment["segmentIndex"] != expected_index
            or start_line != previous_line_end
            or not 0 <= start_line < end_line <= len(raw_lines)
            or not 0 <= window_start < window_end <= duration_ms
            or window_start < previous_window_end
        ):
            raise ValueError("forced-alignment segment is malformed")
        segments.append(segment)
        previous_line_end = end_line
        previous_window_end = window_end
    if previous_line_end != len(raw_lines):
        raise ValueError("forced-alignment segments do not cover every line")
    return duration_ms, segments

def identifier(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 120:
        raise ValueError(f"forced-alignment {field} is malformed")
    return value


def chunk_time_map(
    target_start: int,
    target_end: int,
    emission_start: int,
    emission_end: int,
) -> dict[str, int]:
    target_count = target_end - target_start
    emission_count = emission_end - emission_start
    if target_count <= 0 or emission_count <= 0:
        raise ValueError("MMS chunk time map is empty")
    ratio = Fraction(target_count, emission_count)
    return {
        "targetStartFrame": target_start,
        "targetEndFrame": target_end,
        "emissionStartFrame": emission_start,
        "emissionEndFrame": emission_end,
        "emissionToTargetRatioNumerator": ratio.numerator,
        "emissionToTargetRatioDenominator": ratio.denominator,
    }


def window_to_emission_frames(
    window_start_ms: int,
    window_end_ms: int,
    duration_ms: int,
    sample_rate: int,
    chunks: list[dict[str, int]],
) -> tuple[int, int]:
    """Invert the exact chunk map, flooring starts and ceiling ends."""

    if (
        type(window_start_ms) is not int
        or type(window_end_ms) is not int
        or type(duration_ms) is not int
        or type(sample_rate) is not int
        or sample_rate <= 0
        or not 0 <= window_start_ms < window_end_ms <= duration_ms
        or not chunks
    ):
        raise ValueError("forced-alignment segment window is malformed")
    target_start = (window_start_ms * sample_rate) // 1000
    target_end = (window_end_ms * sample_rate + 999) // 1000
    if window_start_ms == 0:
        target_start = chunks[0]["targetStartFrame"]
    if window_end_ms == duration_ms:
        target_end = chunks[-1]["targetEndFrame"]

    def project(target_frame: int, *, ceiling: bool) -> int:
        for chunk in chunks:
            target_chunk_start = chunk["targetStartFrame"]
            target_chunk_end = chunk["targetEndFrame"]
            if target_chunk_start <= target_frame <= target_chunk_end:
                target_count = target_chunk_end - target_chunk_start
                emission_count = (
                    chunk["emissionEndFrame"] - chunk["emissionStartFrame"]
                )
                numerator = (target_frame - target_chunk_start) * emission_count
                offset = (
                    (numerator + target_count - 1) // target_count
                    if ceiling
                    else numerator // target_count
                )
                return chunk["emissionStartFrame"] + offset
        raise ValueError("forced-alignment segment window is outside the chunk map")

    emission_start = project(target_start, ceiling=False)
    emission_end = project(target_end, ceiling=True)
    if emission_start >= emission_end:
        raise ValueError("forced-alignment segment emission window is empty")
    return emission_start, emission_end


def segment_word_range(
    records: list[dict[str, Any]], segment: dict[str, int]
) -> tuple[int, int]:
    selected = records[segment["startLineIndex"] : segment["endLineIndex"]]
    if not selected:
        raise ValueError("forced-alignment segment target is empty")
    start = selected[0]["startWord"]
    last = selected[-1]
    return start, last["startWord"] + last["wordCount"]


def validate_segment_boundary(
    lines: list[dict[str, Any]], segment: dict[str, int], duration_ms: int
) -> None:
    if not lines:
        raise ValueError("forced-alignment segment returned no lines")
    if (
        segment["windowStartMs"] > 0
        and lines[0]["startMs"] - segment["windowStartMs"]
        < SEGMENT_BOUNDARY_GUARD_MS
    ):
        raise WorkerReasonError(f"wav2vec2-segment-boundary-guard:{segment['segmentIndex']}:start",
            f"forced-alignment segment {segment['segmentIndex']} touches its start boundary guard")
    if (
        segment["windowEndMs"] < duration_ms
        and segment["windowEndMs"] - lines[-1]["endMs"]
        < SEGMENT_BOUNDARY_GUARD_MS
    ):
        raise WorkerReasonError(f"wav2vec2-segment-boundary-guard:{segment['segmentIndex']}:end",
            f"forced-alignment segment {segment['segmentIndex']} touches its end boundary guard")


def validate_global_results(
    lines: list[dict[str, Any]], expected: list[dict[str, Any]], duration_ms: int
) -> None:
    if len(lines) != len(expected):
        raise ValueError("forced-alignment result changed the line count")
    previous_word_end = -1
    for index, (line, planned) in enumerate(zip(lines, expected)):
        words = line.get("words")
        planned_words = planned.get("words")
        if (
            line.get("lineIndex") != index
            or line.get("sourcePhysicalIndex") != planned.get("sourcePhysicalIndex")
            or line.get("lineId") != planned.get("lineId")
            or not isinstance(words, list)
            or not isinstance(planned_words, list)
            or [word.get("wordId") for word in words]
            != [word.get("wordId") for word in planned_words]
        ):
            raise ValueError("forced-alignment result changed the approved target")
        for word in words:
            start = word.get("startMs")
            end = word.get("endMs")
            if (
                type(start) is not int
                or type(end) is not int
                or not 0 <= start < end <= duration_ms
                or start < previous_word_end
            ):
                raise ValueError("forced-alignment results are not globally ordered")
            previous_word_end = end


def emission_boundary_to_target_frame(
    emission_frame: int,
    chunks: list[dict[str, int]],
) -> int:
    """Project one CTC boundary through its exact canonical-audio chunk."""

    if type(emission_frame) is not int or emission_frame < 0:
        raise ValueError("MMS emission boundary is invalid")
    required = {
        "targetStartFrame",
        "targetEndFrame",
        "emissionStartFrame",
        "emissionEndFrame",
        "emissionToTargetRatioNumerator",
        "emissionToTargetRatioDenominator",
    }
    for chunk in chunks:
        if not isinstance(chunk, dict) or set(chunk) != required:
            raise ValueError("MMS chunk time map is malformed")
        target_start = chunk["targetStartFrame"]
        target_end = chunk["targetEndFrame"]
        emission_start = chunk["emissionStartFrame"]
        emission_end = chunk["emissionEndFrame"]
        if any(type(value) is not int for value in chunk.values()):
            raise ValueError("MMS chunk time map is malformed")
        target_count = target_end - target_start
        emission_count = emission_end - emission_start
        ratio = Fraction(target_count, emission_count) if emission_count > 0 else None
        if (
            target_count <= 0
            or ratio is None
            or chunk["emissionToTargetRatioNumerator"] != ratio.numerator
            or chunk["emissionToTargetRatioDenominator"] != ratio.denominator
        ):
            raise ValueError("MMS chunk time map is inconsistent")
        if emission_start <= emission_frame <= emission_end:
            offset = Fraction(emission_frame - emission_start) * ratio
            rounded = (offset.numerator * 2 + offset.denominator) // (
                offset.denominator * 2
            )
            return target_start + rounded
    raise ValueError("MMS emission boundary is outside the chunk map")


def canonical_time_transform(
    *,
    source_sample_rate: int,
    source_channel_count: int,
    source_frame_count: int,
    target_sample_rate: int,
    target_frame_count: int,
    chunk_target_frames: int,
    chunks: list[dict[str, int]],
) -> dict[str, Any]:
    ratio = Fraction(target_sample_rate, source_sample_rate)
    expected = Fraction(source_frame_count) * ratio
    expected_frames = (expected.numerator * 2 + expected.denominator) // (
        expected.denominator * 2
    )
    residual = target_frame_count - expected_frames
    if abs(residual) > 1:
        raise RuntimeError("MMS resampling has a material canonical-time length residual")
    return {
        "sourceSampleRate": source_sample_rate,
        "sourceChannelCount": source_channel_count,
        "sourceFrameCount": source_frame_count,
        "monoChannelCount": 1,
        "targetSampleRate": target_sample_rate,
        "targetFrameCount": target_frame_count,
        "expectedTargetFrameCount": expected_frames,
        "resamplingRatioNumerator": ratio.numerator,
        "resamplingRatioDenominator": ratio.denominator,
        "offsetTargetFrames": 0,
        "trimSourceFrames": 0,
        "padSourceFrames": 0,
        "measuredResidualFrames": residual,
        "measuredResidualMs": residual * 1000.0 / target_sample_rate,
        "chunkTargetFrames": chunk_target_frames,
        "chunks": chunks,
    }


def align_ctc(emission, target_ids: list[int], *, blank: int, allow_trailing_audio: bool):
    """Align every lexical target while allowing non-lyric audio after the last."""
    import torch
    import torchaudio

    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
    CURRENT_BUDGET.require(frames=int(emission.shape[0]),
        states=2*len(target_ids)+1,arrays=3,retained_bytes=emission.numel()*emission.element_size())
    if allow_trailing_audio:
        star = emission.shape[-1]
        # Compete with the last lexical token instead of giving background a
        # free score: free background would truncate a sustained final phoneme.
        values, indices = emission.topk(2, dim=-1)
        background = torch.where(indices[:, 0] == target_ids[-1], values[:, 1], values[:, 0])
        # The extra frame lets the wildcard consume zero real audio, preserving
        # words ending exactly at EOF. No wildcard is inserted between words.
        emission = torch.nn.functional.pad(emission, (0, 1, 0, 1), value=0.0)
        emission[:-1, -1] = background
        emission[-1, :-1] = float("-inf")
        target_ids = [*target_ids, star]
    paths, scores = torchaudio.functional.forced_align(
        emission.unsqueeze(0),
        torch.tensor([target_ids], dtype=torch.int32, device=emission.device),
        blank=blank,
    )
    if allow_trailing_audio:
        paths = paths[:, :-1].masked_fill(paths[:, :-1] == star, blank)
        scores = scores[:, :-1]
    return paths, scores


def cached_emissions(
    request_path: Path, cache_key: str | None, sample_count: int, vocabulary_size: int,
    produce: Callable[[], tuple[Any, list[dict[str, int]], int]],
    *, cache_status: dict[str, Any] | None = None,
) -> tuple[Any, list[dict[str, int]], int]:
    """Reuse exact acoustic logits in attempt scratch; always decode current targets."""
    if cache_status is not None:
        cache_status.clear()
        cache_status["ready"] = False
    if cache_key is None:
        return produce()
    if re.fullmatch(r"[0-9a-f]{64}", cache_key) is None:
        raise ValueError("emission cache key is malformed")
    import torch

    path = request_path.parent / f"emissions-{cache_key}.pt"
    try:
        saved = torch.load(path, map_location="cpu", weights_only=True, mmap=True)
        emission, chunks, chunk_samples = saved["emission"], saved["chunks"], saved["chunkSamples"]
        if (saved["key"] != cache_key or saved["sampleCount"] != sample_count
                or not isinstance(emission, torch.Tensor) or emission.ndim != 2
                or not emission.is_floating_point() or emission.shape[1] != vocabulary_size
                or emission.shape[0] <= 0
                or type(chunk_samples) is not int or chunk_samples <= 0
                or not isinstance(chunks, list) or not chunks):
            raise ValueError("invalid cached emissions")
        if any(not torch.isfinite(block).all() for block in emission.split(8192)):
            raise ValueError('nonfinite cached emissions')
        target_cursor = emission_cursor = 0
        for chunk in chunks:
            if (not isinstance(chunk, dict) or any(type(value) is not int for value in chunk.values())
                    or chunk["targetStartFrame"] != target_cursor
                    or chunk["emissionStartFrame"] != emission_cursor
                    or chunk != chunk_time_map(target_cursor, chunk["targetEndFrame"],
                                               emission_cursor, chunk["emissionEndFrame"])):
                raise ValueError("invalid cached emission map")
            target_cursor, emission_cursor = chunk["targetEndFrame"], chunk["emissionEndFrame"]
        if target_cursor != sample_count or emission_cursor != emission.shape[0]:
            raise ValueError("incomplete cached emissions")
        if cache_status is not None:
            cache_status.update(ready=True, identity=cache_file_identity(path))
        return emission, chunks, chunk_samples
    except (OSError, EOFError, pickle.UnpicklingError, RuntimeError, ValueError, KeyError, TypeError):
        pass  # Optional scratch cache: malformed or absent entries require inference.

    emission, chunks, chunk_samples = produce()
    temporary = path.with_suffix(".tmp")
    try:
        torch.save({"key": cache_key, "sampleCount": sample_count, "emission": emission,
                    "chunks": chunks, "chunkSamples": chunk_samples}, temporary)
        os.replace(temporary, path)
        if cache_status is not None:
            cache_status.update(ready=True, identity=cache_file_identity(path))
    except (OSError, RuntimeError) as error:
        from stemwise_engine.private_diagnostics import retain_error
        try:
            diagnostic = retain_error(request_path.parent / 'emissions.failure.private.txt', error)
            if cache_status is not None:
                cache_status['writeDiagnostic'] = diagnostic
        except (OSError, ValueError) as diagnostic_error:
            if cache_status is not None:
                cache_status['diagnosticError'] = type(diagnostic_error).__name__
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError as cleanup_error:
            if cache_status is not None:
                cache_status['cleanupError'] = type(cleanup_error).__name__
    return emission, chunks, chunk_samples


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def cache_file_identity(path: Path) -> tuple[int, ...]:
    value = path.stat()
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def retire_model(model_state: dict[str, Any]) -> None:
    model = model_state.pop("model", None)
    if model is not None:
        del model
        gc.collect()
    if model_state.pop("cuda_model", False):
        import torch
        torch.cuda.synchronize()
        torch.cuda.empty_cache()


@contextmanager
def execution_deadline(seconds: float):
    # The kernel must terminate a stuck native call, even while Python cannot run.
    if not hasattr(signal, "setitimer") or threading.current_thread() is not threading.main_thread():
        raise RuntimeError("alignment batches require the main POSIX worker thread")
    previous = signal.getsignal(signal.SIGALRM)
    previous_timer = signal.setitimer(signal.ITIMER_REAL, 0)
    started = time.monotonic()
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    budget = min(seconds, previous_timer[0]) if previous_timer[0] > 0 else seconds
    signal.setitimer(signal.ITIMER_REAL, max(.001, budget))
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)
        if previous_timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL,
                            max(.001, previous_timer[0] - (time.monotonic() - started)),
                            previous_timer[1])


def batch_plan(manifest_path: Path, output_path: Path) -> tuple[dict[str, Any], list[tuple[dict[str, Any], dict[str, Any]]]]:
    manifest = read_worker_json(manifest_path)
    if (not isinstance(manifest, dict)
            or set(manifest) != {"formatVersion", "contexts", "timeoutSeconds", "repairSegments"}
            or type(manifest["formatVersion"]) is not int or manifest["formatVersion"] != 1
            or type(manifest["timeoutSeconds"]) is not int or not 0 < manifest["timeoutSeconds"] <= 86400
            or type(manifest["repairSegments"]) is not bool
            or not isinstance(manifest["contexts"], list) or not 1 <= len(manifest["contexts"]) <= 4):
        raise ValueError("alignment batch manifest is malformed")
    rows = []
    identifiers, inputs, outputs = set(), {manifest_path.resolve()}, {output_path.resolve()}
    keys = {"contextId", "audio", "request", "output", "audioSha256", "requestSha256", "emissionCacheKey"}
    for context in manifest["contexts"]:
        if (not isinstance(context, dict) or set(context) != keys
                or not isinstance(context["contextId"], str) or not context["contextId"]
                or len(context["contextId"]) > 120 or context["contextId"] in identifiers
                or any(not isinstance(context[key], str) or re.fullmatch(r"[0-9a-f]{64}", context[key]) is None
                       for key in ("audioSha256", "requestSha256", "emissionCacheKey"))
                or any(not isinstance(context[key], str) or not context[key] for key in ("audio", "request", "output"))):
            raise ValueError("alignment batch context is malformed")
        identifiers.add(context["contextId"])
        audio, request_path, destination = (Path(context[key]).resolve() for key in ("audio", "request", "output"))
        if destination in outputs:
            raise ValueError("alignment batch output is duplicated")
        inputs.update((audio, request_path))
        outputs.add(destination)
        before = audio.stat()
        digest = hashlib.sha256()
        with audio.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        after = audio.stat()
        identity = lambda item: (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        if identity(before) != identity(after):
            raise ValueError("alignment batch audio changed while hashing")
        request = read_worker_json(request_path)
        if digest.hexdigest() != context["audioSha256"] or canonical_sha256(request) != context["requestSha256"]:
            raise ValueError("alignment batch input differs from its binding")
        validate_request(request)
        rows.append(({**context, "audioFileIdentity": identity(after)}, request))
    if inputs & outputs:
        raise ValueError("alignment batch output would overwrite an input")
    return manifest, rows


def merge_failed_segment(segments: list[dict[str, int]], segment_index: int,
                          boundary: str | None) -> list[dict[str, int]]:
    if (len(segments) < 2 or not 0 <= segment_index < len(segments)
            or boundary not in (None, "start", "end")
            or boundary == "start" and segment_index == 0
            or boundary == "end" and segment_index == len(segments) - 1):
        raise ValueError("the failed alignment segment cannot be widened")
    left_index = (segment_index - 1 if boundary == "start" or segment_index == len(segments) - 1
                  else segment_index)
    left, right = segments[left_index:left_index + 2]
    merged = [dict(segment) for segment in segments]
    merged[left_index:left_index + 2] = [{"segmentIndex": left_index,
        "startLineIndex": left["startLineIndex"], "endLineIndex": right["endLineIndex"],
        "windowStartMs": left["windowStartMs"], "windowEndMs": right["windowEndMs"]}]
    for index, segment in enumerate(merged):
        segment["segmentIndex"] = index
    return merged


def decode_repaired(prepared: dict[str, Any], request: dict[str, Any], decode: Callable,
                     repair: bool) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    current = {**request, "segments": [dict(segment) for segment in request["segments"]]}
    merges = []
    while True:
        prepared["segments"] = current["segments"]
        try:
            return decode(prepared), current, merges
        except ValueError as error:
            code = error.reason_code if isinstance(error, WorkerReasonError) else ''
            match = re.fullmatch(r"wav2vec2-segment-(?:too-short:(\d+)|boundary-guard:(\d+):(start|end))", code)
            if not repair or match is None:
                raise
            failed, boundary = int(match.group(1) or match.group(2)), match.group(3)
            current["segments"] = merge_failed_segment(current["segments"], failed, boundary)
            validate_request(current)
            merges.append({"segmentIndex": failed, "boundary": boundary})


def run_alignment_batch(manifest_path: Path, output_path: Path, device_name: str,
                         prepare: Callable, decode: Callable, *, project_dir: Path | None,
                         cuda_decode: bool, preparation_plan: tuple | None = None) -> None:
    manifest, rows = batch_plan(manifest_path, output_path) if preparation_plan is None else preparation_plan
    model_state: dict[str, Any] = {}
    prepared_rows, elapsed = [], []
    reply = {"formatVersion": 1, "batchSha256": canonical_sha256(manifest), "contexts": []}
    waited = 0.0
    with ExitStack() as stage:
        acquired = False
        def acquire_stage():
            nonlocal acquired, waited
            if acquired or project_dir is None:
                return
            from stemwise_engine.common import heavy_worker_stage
            timer = signal.setitimer(signal.ITIMER_REAL, 0)
            started = time.monotonic()
            try:
                stage.enter_context(heavy_worker_stage({"projectDir": str(project_dir)}))
                acquired = True
            finally:
                waited += time.monotonic() - started
                if timer[0] > 0:
                    signal.setitimer(signal.ITIMER_REAL, timer[0], timer[1])
        model_state["acquire_stage"] = acquire_stage
        try:
            for context, request in rows:
                started, initial_waited = time.monotonic(), waited
                with execution_deadline(manifest["timeoutSeconds"]):
                    before = Path(context["audio"]).stat()
                    if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                            != context["audioFileIdentity"]):
                        raise ValueError("alignment batch audio changed before preparation")
                    prepared = prepare(context, model_state, request)
                    after = Path(context["audio"]).stat()
                    if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns)
                            != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)):
                        raise ValueError("alignment batch audio changed during preparation")
                    if preparation_plan is not None:
                        reply["contexts"].append(publish_emissions(context, prepared))
                        publish_json(output_path, reply)
                        del prepared
                    else:
                        prepared_rows.append(prepared)
                elapsed.append(time.monotonic() - started - (waited - initial_waited))
        except BaseException as error:
            traceback.clear_frames(error.__traceback__)
            raise
        finally:
            retirement_budget = min(30.0, manifest["timeoutSeconds"] - (elapsed[-1] if elapsed else 0))
            retirement_started = time.monotonic()
            with execution_deadline(retirement_budget):
                try:
                    retire_model(model_state)
                except BaseException:
                    # Keep the lease until the kernel destroys the failed CUDA context.
                    fatal_model_failure('alignment-model-retirement-failed')
            if elapsed:
                elapsed[-1] += time.monotonic() - retirement_started
        if not cuda_decode:
            stage.close()
        if preparation_plan is not None:
            return
        for (context, request), prepared, used in zip(rows, prepared_rows, elapsed):
            with execution_deadline(manifest["timeoutSeconds"] - used):
                try:
                    if cuda_decode:
                        acquire_stage()
                    result, resolved, merges = decode_repaired(prepared, request, decode, manifest["repairSegments"])
                    publish_json(Path(context["output"]), result)
                    reply["contexts"].append({"contextId": context["contextId"],
                        "audioSha256": context["audioSha256"], "requestSha256": context["requestSha256"],
                        "resolvedRequestSha256": canonical_sha256(resolved),
                        "resolvedSegments": resolved["segments"], "mergeSteps": merges})
                    publish_json(output_path, reply)
                except BaseException as error:
                    traceback.clear_frames(error.__traceback__)
                    raise
                finally:
                    if cuda_decode:
                        with execution_deadline(min(30.0, signal.getitimer(signal.ITIMER_REAL)[0])):
                            try:
                                retire_model({"cuda_model": True})
                            except BaseException:
                                fatal_model_failure('alignment-model-retirement-failed')
        prepared_rows.clear()


def prepare_batch_plan(manifest_path: Path, output_path: Path) -> tuple[dict[str, Any], list]:
    manifest = read_worker_json(manifest_path)
    if (not isinstance(manifest, dict) or set(manifest) != {"formatVersion", "contexts", "timeoutSeconds"}
            or type(manifest["formatVersion"]) is not int or manifest["formatVersion"] != 1
            or type(manifest["timeoutSeconds"]) is not int or not 0 < manifest["timeoutSeconds"] <= 86400
            or not isinstance(manifest["contexts"], list) or not 1 <= len(manifest["contexts"]) <= 2):
        raise ValueError("emission preparation batch is malformed")
    rows, identifiers, destinations = [], set(), {manifest_path.resolve(), output_path.resolve()}
    audio_paths = set()
    for context in manifest["contexts"]:
        if (not isinstance(context, dict)
                or set(context) != {"contextId", "audio", "audioSha256", "durationMs", "emissionCacheKey", "cacheOutput"}
                or not isinstance(context["contextId"], str) or not context["contextId"]
                or len(context["contextId"]) > 120 or context["contextId"] in identifiers
                or type(context["durationMs"]) is not int or context["durationMs"] <= 0
                or any(not isinstance(context[key], str) or re.fullmatch(r"[0-9a-f]{64}", context[key]) is None
                       for key in ("audioSha256", "emissionCacheKey"))
                or any(not isinstance(context[key], str) or not context[key] for key in ("audio", "cacheOutput"))):
            raise ValueError("emission preparation context is malformed")
        identifiers.add(context["contextId"])
        audio, destination = Path(context["audio"]).resolve(), Path(context["cacheOutput"]).resolve()
        if destination.name != f"emissions-{context['emissionCacheKey']}.pt" or destination in destinations:
            raise ValueError("emission preparation cache destination is invalid")
        destinations.add(destination)
        audio_paths.add(audio)
        before = audio.stat()
        digest = hashlib.sha256()
        with audio.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        after = audio.stat()
        identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
        if digest.hexdigest() != context["audioSha256"] or identity(before) != identity(after):
            raise ValueError("emission preparation audio differs from its binding")
        # No lyric target is supplied or decoded by this operation.
        request = {"formatVersion": REQUEST_VERSION, "durationMs": context["durationMs"],
            "lines": [{"sourcePhysicalIndex": 0, "lineId": "emissions", "words": [
                {"wordId": "emissions", "acousticText": "a"}]}],
            "segments": [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 1,
                          "windowStartMs": 0, "windowEndMs": context["durationMs"]}]}
        rows.append(({**context, "request": str(destination.parent / "emissions.request.private.json"),
                      "audioFileIdentity": identity(after)}, request))
    if audio_paths & destinations:
        raise ValueError("emission preparation would overwrite source audio")
    for context, _request in rows:
        Path(context["cacheOutput"]).parent.mkdir(parents=True, exist_ok=True)
    return manifest, rows


def publish_emissions(context: dict[str, Any], prepared: dict[str, Any]) -> dict[str, Any]:
    import torch
    destination = Path(context["cacheOutput"])
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".tmp")
    payload = {"key": context["emissionCacheKey"], "sampleCount": prepared["sample_count"],
               "emission": prepared["emission"], "chunkSamples": prepared["chunk_samples"],
               "chunks": prepared.get("chunks", prepared.get("chunk_map"))}
    status = prepared.get("cache_status", {})
    if not (status.get("ready") is True and destination.is_file()
            and status.get("identity") == cache_file_identity(destination)):
        try:
            torch.save(payload, temporary)
            os.replace(temporary, destination)
        finally:
            temporary.unlink(missing_ok=True)
    digest = hashlib.sha256()
    with destination.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"contextId": context["contextId"], "audioSha256": context["audioSha256"],
            "durationMs": context["durationMs"], "emissionCacheKey": context["emissionCacheKey"],
            "cacheSha256": digest.hexdigest(), "cacheByteCount": destination.stat().st_size}



def helper_source_identity(helper: Path) -> str:
    """Bind an adapter and the numerical/request infrastructure it executes."""
    from stemwise_engine.provenance import semantic_source_identity, CTC_SOURCE_FILES
    names = (helper.name, *CTC_SOURCE_FILES)
    identities = {name: semantic_source_identity(helper.with_name(name)) for name in names}
    identities['source_duration_ms'] = semantic_source_identity(helper.with_name('common.py'), ('source_duration_ms',))
    return canonical_sha256(identities)
