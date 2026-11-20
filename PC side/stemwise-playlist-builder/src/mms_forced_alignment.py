"""Private MMS CTC forced-alignment worker for approved lyric lines.

The parent process supplies temporary text and audio paths.  This worker emits
only numeric line timing/confidence records; it never prints or copies lyric
text into logs.  Model weights are cached under the caller-provided TORCH_HOME.
"""

from __future__ import annotations
from stemwise_engine.provenance import semantic_source_identity

import argparse
import stemwise_engine.ctc_worker as ctc_worker
from stemwise_engine.worker_publication import publish_json
import gc
import hashlib
import signal
import threading
import time
import traceback
from contextlib import ExitStack, contextmanager
import importlib.metadata
import json
import os
import pickle
import platform
import re
import sys
import tempfile
import unicodedata
from fractions import Fraction
from pathlib import Path
from stemwise_engine.worker_protocol import read_worker_json, parse_worker_json
from typing import Any, Callable
from stemwise_engine.acoustic_inputs import chunk_ranges, StreamedMonoAudio, mapped_emissions


FORMAT_VERSION = ctc_worker.REQUEST_VERSION
BUNDLE_IDENTITY = "torchaudio.pipelines.MMS_FA"



_CHAR_REPLACEMENTS = {
    "ı": "i",
    "ß": "ss",
    "æ": "ae",
    "œ": "oe",
    "ø": "o",
    "ð": "dh",
    "þ": "th",
    "ŋ": "ng",
    "ɲ": "ny",
    "ʃ": "sh",
    "ʒ": "zh",
    "ʧ": "ch",
    "ʤ": "j",
    "θ": "th",
    "ɣ": "g",
    "χ": "x",
    "ħ": "h",
    "ʕ": "a",
    "ɾ": "r",
    "ɹ": "r",
    "ʀ": "r",
    "ʁ": "r",
    "ɫ": "l",
    "ɬ": "l",
    "ɥ": "w",
    "ɯ": "u",
    "ɰ": "w",
    "ɑ": "a",
    "ɒ": "o",
    "ɐ": "a",
    "ə": "e",
    "ɘ": "e",
    "ɜ": "e",
    "ɞ": "o",
    "ʌ": "a",
    "ɛ": "e",
    "ɪ": "i",
    "ɔ": "o",
    "ʊ": "u",
}


def _needs_uroman(text: str) -> bool:
    for character in text:
        if not character.isalpha():
            continue
        replaced = _CHAR_REPLACEMENTS.get(character.casefold(), character.casefold())
        decomposed = unicodedata.normalize("NFKD", replaced)
        if not any("a" <= value <= "z" for value in decomposed):
            return True
    return False


def _romanized_words(text: str, *, romanizer: Any | None = None) -> list[str]:
    from stemwise_engine.lyric_content import require_pronunciation_support
    require_pronunciation_support(text)
    if _needs_uroman(text):
        if romanizer is None:
            import uroman

            romanizer = uroman.Uroman()
        text = romanizer.romanize_string(text)
        require_pronunciation_support(text)
    lowered = text.casefold()
    replaced = "".join(_CHAR_REPLACEMENTS.get(character, character) for character in lowered)
    decomposed = unicodedata.normalize("NFKD", replaced)
    cleaned: list[str] = []
    previous_space = True
    for character in decomposed:
        if unicodedata.combining(character):
            continue
        if "a" <= character <= "z" or character == "'":
            cleaned.append(character)
            previous_space = False
        elif not previous_space:
            cleaned.append(" ")
            previous_space = True
    normalized = "".join(cleaned).strip()
    return [word for word in normalized.split() if word]




def _request_plan(
    value: Any,
) -> tuple[list[dict[str, Any]], list[str], int, list[dict[str, int]]]:
    duration_ms, segments = ctc_worker.validate_request(value)
    raw_lines = value['lines']
    records: list[dict[str, Any]] = []
    words: list[str] = []
    romanizer: Any | None = None
    for expected_index, item in enumerate(raw_lines):
        source = item.get("sourcePhysicalIndex")
        line_id = ctc_worker.identifier(item.get("lineId"), "line ID")
        raw_words = item.get("words")
        start_word = len(words)
        mapped_words: list[dict[str, Any]] = []
        for raw_word in raw_words:
            text = raw_word.get("acousticText")
            if _needs_uroman(text) and romanizer is None:
                import uroman

                romanizer = uroman.Uroman()
            normalized = _romanized_words(text, romanizer=romanizer)
            if not normalized:
                raise ValueError("a display word cannot be represented by the MMS tokenizer")
            mapped_start = len(words)
            words.extend(normalized)
            mapped = {
                "startWord": mapped_start,
                "wordCount": len(normalized),
            }
            mapped["wordId"] = ctc_worker.identifier(raw_word.get("wordId"), "word ID")
            mapped_words.append(mapped)
        records.append(
            {
                "lineIndex": expected_index,
                "sourcePhysicalIndex": source,
                "startWord": start_word,
                "wordCount": len(words) - start_word,
                "lineId": line_id,
                "words": mapped_words,
            }
        )
    return records, words, duration_ms, segments


def _aggregate_records(
    records: list[dict[str, Any]], word_records: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    line_records: list[dict[str, Any]] = []
    for record in records:
        start_word = record["startWord"]
        selected = word_records[start_word : start_word + record["wordCount"]]
        weights = [max(1, value["tokenCount"]) for value in selected]
        confidence = sum(
            value["confidence"] * weight for value, weight in zip(selected, weights)
        ) / sum(weights)
        line_record: dict[str, Any] = {
            "lineIndex": record["lineIndex"],
            "sourcePhysicalIndex": record["sourcePhysicalIndex"],
            "startMs": selected[0]["startMs"],
            "endMs": selected[-1]["endMs"],
            "confidence": confidence,
            "minimumWordConfidence": min(value["confidence"] for value in selected),
            "displayWordCount": len(record["words"]),
            "romanizedWordCount": len(selected),
            "acousticTokenCount": sum(value["tokenCount"] for value in selected),
        }
        line_record["lineId"] = record["lineId"]
        line_record["words"] = []
        for mapped in record["words"]:
            acoustic = word_records[
                mapped["startWord"] : mapped["startWord"] + mapped["wordCount"]
            ]
            line_record["words"].append(
                {
                    "wordId": mapped["wordId"],
                    "startMs": acoustic[0]["startMs"],
                    "endMs": acoustic[-1]["endMs"],
                    "confidence": min(value["confidence"] for value in acoustic),
                    "acousticTokenCount": sum(value["tokenCount"] for value in acoustic),
                    "romanizedWordCount": len(acoustic),
                }
            )
        line_records.append(line_record)
    return line_records





















def _prepare(audio_path: Path, request_path: Path, device_name: str, model_state: dict[str, Any],
             *, emission_cache_key: str | None = None, request_value: dict[str, Any] | None = None,
             emissions_only: bool = False) -> dict[str, Any]:
    import torch
    import torchaudio

    records, transcript, request_duration_ms, segments = _request_plan(
        read_worker_json(request_path) if request_value is None else request_value
    )
    bundle = torchaudio.pipelines.MMS_FA
    device = torch.device(device_name if device_name != "auto" else ("cuda" if torch.cuda.is_available() else "cpu"))
    if "tokenizer" not in model_state:
        model_state["tokenizer"] = bundle.get_tokenizer()
    tokenizer = model_state["tokenizer"]

    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
    waveform = StreamedMonoAudio(audio_path, int(bundle.sample_rate))
    pcm_bytes = (min(waveform.frames, 30*waveform.source_rate)*waveform.channels
                 + min(waveform.sample_count, 30*waveform.target_rate))*4
    for segment in (() if emissions_only else segments):
        first,last = ctc_worker.segment_word_range(records,segment)
        count = sum(len(word) for word in tokenizer(transcript[first:last]))
        duration = segment['windowEndMs']-segment['windowStartMs']
        CURRENT_BUDGET.require(frames=(duration+19)//20+2,
            states=2*count+1,arrays=3,retained_bytes=pcm_bytes+((duration+19)//20+2)*len(bundle.get_labels(star=None))*4)
    source_sample_rate = waveform.source_rate
    source_channel_count = waveform.channels
    source_frame_count = waveform.frames
    sample_rate = bundle.sample_rate
    sample_count = waveform.sample_count
    if sample_count <= 0:
        raise ValueError("forced-alignment audio is empty")
    duration_ms = (sample_count * 1000 + sample_rate // 2) // sample_rate
    if duration_ms != request_duration_ms:
        raise ValueError("forced-alignment request duration does not match the audio")

    # Wav2Vec2 self-attention is bounded by processing 30-second chunks.  The
    # concatenated CTC emissions retain global chronological order; the final
    # frame-to-audio ratio compensates for convolution edge frames per chunk.
    def produce_emissions():
        model_state.get("acquire_stage", lambda: None)()
        if "model" not in model_state:
            model_state["cuda_model"] = device.type == "cuda"
            model_state["model"] = bundle.get_model(with_star=False).to(device)
        model = model_state["model"]
        emission_start = 0
        with torch.inference_mode():
            for start, stop in chunk_ranges(sample_count, chunk_samples):
                chunk = waveform.read(start, stop).to(device)
                emission, _ = model(chunk)
                emitted = emission[0].detach().cpu()
                emission_end = emission_start + int(emitted.shape[0])
                chunk_map.append(
                    ctc_worker.chunk_time_map(
                        start,
                        stop,
                        emission_start,
                        emission_end,
                    )
                )
                emission_start = emission_end
                yield emitted

    chunk_map: list[dict[str, int]] = []
    chunk_samples = 30*sample_rate
    def produce():
        return mapped_emissions(produce_emissions(), request_path.parent), chunk_map, chunk_samples

    cache_status: dict[str, Any] = {}
    emission, chunk_map, chunk_samples = ctc_worker.cached_emissions(
        request_path, emission_cache_key, sample_count, len(bundle.get_labels(star=None)), produce,
        cache_status=cache_status,
    )
    return {
        "emission": emission,
        "device": device,
        "tokenizer": tokenizer,
        "transcript": transcript,
        "segments": segments,
        "records": records,
        "duration_ms": duration_ms,
        "sample_rate": sample_rate,
        "chunk_map": chunk_map,
        "source_sample_rate": source_sample_rate,
        "source_channel_count": source_channel_count,
        "source_frame_count": source_frame_count,
        "sample_count": sample_count,
        "chunk_samples": chunk_samples,
        "cache_status": cache_status,
    }


def _decode(prepared: dict[str, Any]) -> dict[str, Any]:
    import torchaudio

    emission = prepared["emission"]
    device = prepared["device"]
    tokenizer = prepared["tokenizer"]
    transcript = prepared["transcript"]
    segments = prepared["segments"]
    records = prepared["records"]
    duration_ms = prepared["duration_ms"]
    sample_rate = prepared["sample_rate"]
    chunk_map = prepared["chunk_map"]
    source_sample_rate = prepared["source_sample_rate"]
    source_channel_count = prepared["source_channel_count"]
    source_frame_count = prepared["source_frame_count"]
    sample_count = prepared["sample_count"]
    chunk_samples = prepared["chunk_samples"]

    tokens = tokenizer(transcript)
    word_records: list[dict[str, Any]] = []
    segment_results = prepared.setdefault("segment_results", {})
    for segment in segments:
        key = tuple(segment[field] for field in ("startLineIndex", "endLineIndex", "windowStartMs", "windowEndMs"))
        if key in segment_results:
            word_records.extend(segment_results[key])
            continue
        first_word = len(word_records)
        word_start, word_end = ctc_worker.segment_word_range(records, segment)
        segment_transcript = transcript[word_start:word_end]
        segment_tokens = tokenizer(segment_transcript)
        if segment_tokens != tokens[word_start:word_end]:
            raise RuntimeError("MMS segment changed the approved target")
        emission_start, emission_end = ctc_worker.window_to_emission_frames(
            segment["windowStartMs"],
            segment["windowEndMs"],
            duration_ms,
            int(sample_rate),
            chunk_map,
        )
        targets = [token for word in segment_tokens for token in word]
        minimum_frames = len(targets) + sum(left == right for left, right in zip(targets, targets[1:]))
        segment_emission = emission[emission_start:emission_end]
        if int(segment_emission.shape[0]) < minimum_frames:
            from stemwise_engine.worker_protocol import WorkerReasonError
            raise WorkerReasonError(f"wav2vec2-segment-too-short:{segment['segmentIndex']}",
                f"MMS segment {segment['segmentIndex']} emission is too short for its target")
        paths, scores = ctc_worker.align_ctc(
            segment_emission.to(device),
            targets,
            blank=0,
            allow_trailing_audio=segment["windowEndMs"] == duration_ms,
        )
        token_spans = torchaudio.functional.merge_tokens(paths[0], scores[0].exp(), blank=0)
        spans = []
        cursor = 0
        for word in segment_tokens:
            spans.append(token_spans[cursor : cursor + len(word)])
            cursor += len(word)
        if cursor != len(token_spans):
            raise RuntimeError("MMS forced alignment changed the approved target")
        if len(spans) != len(segment_tokens):
            raise RuntimeError("MMS returned the wrong number of aligned words")
        for expected_tokens, token_spans in zip(segment_tokens, spans):
            if (
                not token_spans
                or len(token_spans) != len(expected_tokens)
                or any(
                    int(span.token) != token
                    for span, token in zip(token_spans, expected_tokens)
                )
            ):
                raise RuntimeError("MMS forced alignment changed the approved target")
            frame_count = sum(
                max(1, int(span.end) - int(span.start)) for span in token_spans
            )
            confidence = sum(
                float(span.score) * max(1, int(span.end) - int(span.start))
                for span in token_spans
            ) / frame_count
            start_target_frame = ctc_worker.emission_boundary_to_target_frame(
                emission_start + int(token_spans[0].start), chunk_map
            )
            end_target_frame = ctc_worker.emission_boundary_to_target_frame(
                emission_start + int(token_spans[-1].end), chunk_map
            )
            start_ms = (start_target_frame * 1000 + sample_rate // 2) // sample_rate
            end_ms = (end_target_frame * 1000 + sample_rate // 2) // sample_rate
            word_records.append(
                {
                    "wordIndex": len(word_records),
                    "startMs": start_ms,
                    "endMs": max(start_ms + 1, end_ms),
                    "confidence": confidence,
                    "tokenCount": len(token_spans),
                }
            )
        segment_results[key] = word_records[first_word:]
    if len(word_records) != len(transcript):
        raise RuntimeError("MMS segment alignment changed the approved target count")

    line_records = _aggregate_records(records, word_records)
    ctc_worker.validate_global_results(line_records, records, duration_ms)
    for segment in segments:
        ctc_worker.validate_segment_boundary(
            line_records[segment["startLineIndex"] : segment["endLineIndex"]],
            segment,
            duration_ms,
        )

    confidence_values = sorted(value["confidence"] for value in word_records)
    line_confidence_values = sorted(value["confidence"] for value in line_records)
    canonical_time_transform = ctc_worker.canonical_time_transform(
        source_sample_rate=source_sample_rate,
        source_channel_count=source_channel_count,
        source_frame_count=source_frame_count,
        target_sample_rate=int(sample_rate),
        target_frame_count=sample_count,
        chunk_target_frames=chunk_samples,
        chunks=chunk_map,
    )

    def quantile(values: list[float], fraction: float) -> float:
        if not values:
            return 0.0
        return values[min(len(values) - 1, int(round((len(values) - 1) * fraction)))]

    return {
            "formatVersion": FORMAT_VERSION,
            "engine": "torchaudio-mms-fa",
            "sampleRate": sample_rate,
            "durationMs": duration_ms,
            "emissionFrameCount": int(emission.shape[0]),
            "canonicalTimeTransform": canonical_time_transform,
            "segmentCount": len(segments),
            "segmentBoundaryGuardMs": ctc_worker.SEGMENT_BOUNDARY_GUARD_MS,
            "displayWordCount": sum(len(line["words"]) for line in line_records),
            "romanizedWordCount": len(word_records),
            "acousticTokenCount": sum(value["tokenCount"] for value in word_records),
            "lineCount": len(line_records),
            "confidence": {
                "wordP10": quantile(confidence_values, 0.10),
                "wordP50": quantile(confidence_values, 0.50),
                "wordP90": quantile(confidence_values, 0.90),
                "lineP10": quantile(line_confidence_values, 0.10),
                "lineP50": quantile(line_confidence_values, 0.50),
                "lineP90": quantile(line_confidence_values, 0.90),
            },
            "lines": line_records,
        }


def run(audio_path: Path, request_path: Path, output_path: Path, device_name: str,
        *, emission_cache_key: str | None = None) -> None:
    model_state: dict[str, Any] = {}
    try:
        prepared = _prepare(audio_path, request_path, device_name, model_state, emission_cache_key=emission_cache_key)
    finally:
        ctc_worker.retire_model(model_state)
    publish_json(output_path, _decode(prepared))























def prepare_many(manifest_path: Path, output_path: Path, device_name: str,
                 *, project_dir: Path | None = None) -> None:
    plan = ctc_worker.prepare_batch_plan(manifest_path, output_path)
    ctc_worker.run_alignment_batch(manifest_path, output_path, device_name,
        lambda row, state, request: _prepare(Path(row["audio"]), Path(row["request"]), device_name, state,
            emission_cache_key=row["emissionCacheKey"], request_value=request, emissions_only=True),
        _decode, project_dir=project_dir, cuda_decode=False, preparation_plan=plan)


def run_batch(manifest_path: Path, output_path: Path, device_name: str,
              *, project_dir: Path | None = None) -> None:
    import torch
    resolved = device_name if device_name != "auto" else ("cuda" if torch.cuda.is_available() else "cpu")
    ctc_worker.run_alignment_batch(manifest_path, output_path, device_name,
        lambda row, state, request: _prepare(Path(row["audio"]), Path(row["request"]), device_name, state,
                                   emission_cache_key=row["emissionCacheKey"], request_value=request),
        _decode, project_dir=project_dir, cuda_decode=resolved == "cuda")


def runtime_info(device_name: str) -> dict[str, Any]:
    """Return lyric-free runtime identity used to bind private numeric caches."""

    import torch
    import torchaudio

    resolved = device_name if device_name != "auto" else ("cuda" if torch.cuda.is_available() else "cpu")
    cuda_available = bool(torch.cuda.is_available())
    return {
        "runtimeVersion": 1,
        'acousticInputSha256': semantic_source_identity(Path(__file__).with_name('acoustic_inputs.py')),
        "supportedRequestVersions": [FORMAT_VERSION],
        "python": platform.python_version(),
        "implementation": platform.python_implementation(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "torch": str(torch.__version__),
        "torchaudio": str(torchaudio.__version__),
        "demucs": importlib.metadata.version("demucs"),
        "uroman": importlib.metadata.version("uroman"),
        "bundle": BUNDLE_IDENTITY,
        "bundleSampleRate": int(torchaudio.pipelines.MMS_FA.sample_rate),
        "requestedDevice": device_name,
        "resolvedDevice": resolved,
        "cudaAvailable": cuda_available,
        "cudaDeviceName": (
            str(torch.cuda.get_device_name(0)) if cuda_available else None
        ),
        "cudaRuntime": str(getattr(torch.version, "cuda", None)),
        "cudnn": str(torch.backends.cudnn.version()),
    }


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--audio", type=Path)
    result.add_argument("--request", type=Path)
    result.add_argument("--output", type=Path)
    result.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    result.add_argument("--emission-cache-key")
    result.add_argument("--batch", type=Path)
    result.add_argument("--prepare-batch", type=Path)
    result.add_argument("--project", type=Path)
    result.add_argument("--runtime-info", action="store_true")
    return result


def main() -> int:
    arguments = parser().parse_args()
    if arguments.batch is not None and arguments.prepare_batch is not None:
        raise SystemExit("--batch and --prepare-batch are mutually exclusive")
    if arguments.runtime_info:
        json.dump(runtime_info(arguments.device), sys.stdout, ensure_ascii=True, sort_keys=True)
        sys.stdout.write("\n")
        return 0
    if arguments.prepare_batch is not None:
        if arguments.output is None:
            raise SystemExit("--prepare-batch requires --output")
        prepare_many(arguments.prepare_batch, arguments.output, arguments.device, project_dir=arguments.project)
        return 0
    if arguments.batch is not None:
        if arguments.output is None:
            raise SystemExit("--batch requires --output")
        run_batch(arguments.batch, arguments.output, arguments.device, project_dir=arguments.project)
        return 0
    if arguments.audio is None or arguments.request is None or arguments.output is None:
        raise SystemExit("--audio, --request, and --output are required")
    run(arguments.audio, arguments.request, arguments.output, arguments.device,
        emission_cache_key=arguments.emission_cache_key)
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
