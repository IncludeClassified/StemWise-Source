"""Private grapheme-or-phoneme Wav2Vec2 CTC forced-alignment worker."""

from __future__ import annotations
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.worker_protocol import WorkerReasonError

import argparse
import hashlib
import importlib.metadata
import json
import math
import platform
import sys
import unicodedata
from pathlib import Path
from stemwise_engine.worker_protocol import read_worker_json, parse_worker_json
from typing import Any

import stemwise_engine.ctc_worker as ctc_worker
from stemwise_engine.worker_publication import publish_json


FORMAT_VERSION = 4
ENGINE = "wav2vec2-ctc-forced-alignment"
SAMPLE_RATE = 16_000
CHUNK_SECONDS = 30
STRIDE_SECONDS = 5
RETAINED_CORE_SECONDS = CHUNK_SECONDS - 2 * STRIDE_SECONDS
_APOSTROPHES = frozenset("'\u2018\u2019\u02bc\u02bb\uff07")
_TURKISH_CIRCUMFLEX_FOLD = str.maketrans({"â": "a", "î": "i", "û": "u"})
_IPA_LENGTH_MARK = "\u02d0"


class TargetVocabularyUnsupported(ValueError):
    """A valid target cannot be encoded; no acoustic inference has occurred."""

    def __init__(self, request: dict[str, Any]) -> None:
        super().__init__("Wav2Vec2 acoustic word maps outside the vocabulary")
        self.request_sha256 = hashlib.sha256(json.dumps(
            request, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")).hexdigest()


def _resolved_tokenizer_class(local_model: Path) -> str:
    tokenizer_config_path = local_model / "tokenizer_config.json"
    if tokenizer_config_path.is_file():
        tokenizer_class = json.loads(
            tokenizer_config_path.read_text(encoding="utf-8")
        ).get("tokenizer_class")
    else:
        model_config = json.loads(
            (local_model / "config.json").read_text(encoding="utf-8")
        )
        tokenizer_class = (
            "Wav2Vec2CTCTokenizer"
            if model_config.get("model_type") == "wav2vec2"
            and (local_model / "vocab.json").is_file()
            else None
        )
    if tokenizer_class not in {
        "Wav2Vec2CTCTokenizer",
        "Wav2Vec2PhonemeCTCTokenizer",
    }:
        raise ValueError("Wav2Vec2 model has an unsupported tokenizer class")
    return tokenizer_class


def _normalized_word(text: Any, language: str, vocabulary: dict[str, int]) -> str:
    if not isinstance(text, str) or not text:
        raise ValueError("Wav2Vec2 request contains an empty acoustic word")
    normalized = unicodedata.normalize("NFKC", text)
    if language.split("-", 1)[0].casefold() == "tr":
        normalized = normalized.replace("I", "\u0131").replace("\u0130", "i")
        normalized = normalized.lower().translate(_TURKISH_CIRCUMFLEX_FOLD)
    else:
        normalized = normalized.lower()
    result: list[str] = []
    for raw_character in normalized:
        character = "'" if raw_character in _APOSTROPHES else raw_character
        if character == "'" and character in vocabulary:
            result.append(character)
        elif unicodedata.category(character).startswith("P"):
            continue
        elif character.isspace():
            raise ValueError("Wav2Vec2 acoustic words cannot contain whitespace")
        elif character in vocabulary or character.isalnum() or unicodedata.category(character).startswith("M"):
            result.append(character)
        else:
            raise ValueError("Wav2Vec2 acoustic word contains an unsupported character")
    if not result:
        raise ValueError("Wav2Vec2 acoustic word has no alignable characters")
    return "".join(result)


def _target_plan(
    request: dict[str, Any], tokenizer: Any, language: str, *, phoneme: bool = False
) -> tuple[list[int], list[dict[str, Any]], int | None]:
    if not isinstance(language, str) or not language.strip():
        raise ValueError("Wav2Vec2 language is malformed")
    vocabulary = tokenizer.get_vocab()
    if not isinstance(vocabulary, dict) or not vocabulary:
        raise ValueError("Wav2Vec2 tokenizer vocabulary is malformed")
    delimiter = None if phoneme else tokenizer.word_delimiter_token_id
    if not phoneme and (
        type(delimiter) is not int
        or delimiter < 0
        or delimiter not in vocabulary.values()
    ):
        raise ValueError("Wav2Vec2 tokenizer has no word delimiter")
    special_ids = {
        value for value in tokenizer.all_special_ids if type(value) is int
    }
    unknown = tokenizer.unk_token_id
    if type(unknown) is not int or unknown < 0 or unknown not in vocabulary.values():
        raise ValueError("Wav2Vec2 tokenizer has no unknown token")
    targets: list[int] = []
    line_records: list[dict[str, Any]] = []
    seen_line_ids: set[str] = set()
    seen_word_ids: set[str] = set()
    unsupported = False
    for line_index, line in enumerate(request.get("lines", [])):
        if not isinstance(line, dict):
            raise ValueError("Wav2Vec2 request line is malformed")
        line_id = line.get("lineId")
        source = line.get("sourcePhysicalIndex")
        raw_words = line.get("words")
        if (
            not isinstance(line_id, str)
            or not line_id
            or line_id in seen_line_ids
            or type(source) is not int
            or source < 0
            or not isinstance(raw_words, list)
            or not raw_words
        ):
            raise ValueError("Wav2Vec2 request line is malformed")
        seen_line_ids.add(line_id)
        words: list[dict[str, Any]] = []
        for raw_word in raw_words:
            if not isinstance(raw_word, dict):
                raise ValueError("Wav2Vec2 request word is malformed")
            word_id = raw_word.get("wordId")
            if not isinstance(word_id, str) or not word_id or word_id in seen_word_ids:
                raise ValueError("Wav2Vec2 request word ID is malformed")
            seen_word_ids.add(word_id)
            acoustic_text = raw_word.get("acousticText")
            if not isinstance(acoustic_text, str) or not acoustic_text.split():
                raise ValueError("Wav2Vec2 request contains an empty acoustic word")
            word_tokens = []
            for acoustic_text in acoustic_text.split():
                if phoneme:
                    acoustic_language = raw_word.get("acousticLanguage", language)
                    if (
                        not isinstance(acoustic_text, str)
                        or not acoustic_text
                        or any(character.isspace() for character in acoustic_text)
                        or not isinstance(acoustic_language, str)
                        or not acoustic_language.strip()
                    ):
                        raise ValueError("Wav2Vec2 request contains an invalid acoustic word")
                    normalized = unicodedata.normalize("NFKC", acoustic_text)
                    if acoustic_language.split("-", 1)[0].casefold() == "tr":
                        normalized = normalized.replace("I", "\u0131").replace("\u0130", "i")
                    phones = tokenizer.phonemize(
                        normalized.lower(), phonemizer_lang=acoustic_language
                    )
                    phone_delimiter = tokenizer.phone_delimiter_token
                    if not isinstance(phones, str) or not phones.strip():
                        raise ValueError("Wav2Vec2 acoustic word has no alignable phonemes")
                    if not isinstance(phone_delimiter, str) or not phone_delimiter:
                        raise ValueError("Wav2Vec2 tokenizer has no phone delimiter")
                    word_delimiter = tokenizer.word_delimiter_token
                    phone_tokens = [
                        token
                        for token in phones.split(phone_delimiter)
                        if token and token != word_delimiter
                    ]
                    if not phone_tokens:
                        raise ValueError("Wav2Vec2 acoustic word has no alignable phonemes")
                    expanded_tokens: list[str] = []
                    for token in phone_tokens:
                        if token in vocabulary:
                            expanded_tokens.append(token)
                            continue
                        base_phone = token.removesuffix(_IPA_LENGTH_MARK)
                        if base_phone != token and base_phone in vocabulary:
                            expanded_tokens.extend([base_phone, base_phone])
                            continue
                        for unit_length in range(1, len(token)):
                            unit = token[:unit_length]
                            repeat_count, remainder = divmod(len(token), unit_length)
                            if not remainder and unit in vocabulary and unit * repeat_count == token:
                                expanded_tokens.extend([unit] * repeat_count)
                                break
                        else:
                            if all(unit in vocabulary for unit in token):
                                expanded_tokens.extend(token)
                            else:
                                unsupported = True
                    phone_tokens = expanded_tokens
                    token_ids = [vocabulary[token] for token in phone_tokens]
                else:
                    normalized = _normalized_word(acoustic_text, language, vocabulary)
                    if any(character not in vocabulary for character in normalized):
                        unsupported = True
                        token_ids = []
                    else:
                        token_ids = [vocabulary[character] for character in normalized]
                if any(
                    type(token) is not int
                    or token < 0
                    or token == delimiter
                    or token == unknown
                    or token in special_ids
                    for token in token_ids
                ):
                    raise ValueError("Wav2Vec2 acoustic word maps to a reserved token")
                if word_tokens and delimiter is not None:
                    word_tokens.append(delimiter)
                word_tokens.extend(token_ids)
            token_ids = word_tokens
            if targets and delimiter is not None:
                targets.append(delimiter)
            start = len(targets)
            targets.extend(token_ids)
            words.append(
                {
                    "wordId": word_id,
                    "targetStart": start,
                    "targetEnd": len(targets),
                }
            )
        line_records.append(
            {
                "lineIndex": line_index,
                "sourcePhysicalIndex": source,
                "lineId": line_id,
                "words": words,
            }
        )
    if not line_records:
        raise ValueError("Wav2Vec2 request has no lyric lines")
    if unsupported:
        # Validate every word/ID before reporting capability. A malformed later
        # record must not be hidden by an earlier unsupported sound.
        raise TargetVocabularyUnsupported(request)
    return targets, line_records, delimiter


def _probability(log_probability: Any) -> float:
    if not isinstance(log_probability, (int, float)):
        raise ValueError("Wav2Vec2 returned a non-numeric token score")
    value = math.exp(float(log_probability))
    if not math.isfinite(value) or not 0.0 <= value <= 1.000001:
        raise ValueError("Wav2Vec2 returned an invalid token score")
    return min(1.0, value)


def _segment_target_plan(
    target_ids: list[int],
    line_plan: list[dict[str, Any]],
    segment: dict[str, int],
    *,
    has_word_delimiters: bool = True,
) -> tuple[list[int], list[dict[str, Any]]]:
    selected = line_plan[segment["startLineIndex"] : segment["endLineIndex"]]
    if not selected:
        raise ValueError("Wav2Vec2 segment target is empty")
    first_word_start = selected[0]["words"][0]["targetStart"]
    target_start = (
        max(0, first_word_start - 1) if has_word_delimiters else first_word_start
    )
    target_end = selected[-1]["words"][-1]["targetEnd"]
    local_plan = [
        {
            **line,
            "words": [
                {
                    **word,
                    "targetStart": word["targetStart"] - target_start,
                    "targetEnd": word["targetEnd"] - target_start,
                }
                for word in line["words"]
            ],
        }
        for line in selected
    ]
    return target_ids[target_start:target_end], local_plan


def _milliseconds(frame: int, chunks: list[dict[str, int]]) -> int:
    target = ctc_worker.emission_boundary_to_target_frame(frame, chunks)
    return (target * 1000 + SAMPLE_RATE // 2) // SAMPLE_RATE


def _result_records(
    line_plan: list[dict[str, Any]],
    token_spans: list[Any],
    chunks: list[dict[str, int]],
    duration_ms: int,
    frame_offset: int = 0,
) -> tuple[list[dict[str, Any]], list[float]]:
    word_confidences: list[float] = []
    lines: list[dict[str, Any]] = []
    previous_line_start = -1
    for planned_line in line_plan:
        words: list[dict[str, Any]] = []
        for planned_word in planned_line["words"]:
            selected = token_spans[
                planned_word["targetStart"] : planned_word["targetEnd"]
            ]
            if not selected:
                raise ValueError("Wav2Vec2 returned an empty word alignment")
            start_ms = _milliseconds(frame_offset + int(selected[0].start), chunks)
            end_ms = _milliseconds(frame_offset + int(selected[-1].end), chunks)
            if end_ms == start_ms and end_ms < duration_ms:
                end_ms += 1
            if not 0 <= start_ms < end_ms <= duration_ms:
                raise ValueError("Wav2Vec2 returned an out-of-range word timing")
            confidence = sum(_probability(span.score) for span in selected) / len(
                selected
            )
            word_confidences.append(confidence)
            words.append(
                {
                    "wordId": planned_word["wordId"],
                    "startMs": start_ms,
                    "endMs": end_ms,
                    "confidence": confidence,
                    "acousticTokenCount": len(selected),
                }
            )
        start_ms = words[0]["startMs"]
        end_ms = words[-1]["endMs"]
        if start_ms <= previous_line_start or any(
            right["startMs"] < left["endMs"] for left, right in zip(words, words[1:])
        ):
            raise ValueError("Wav2Vec2 word or line timings are out of order")
        previous_line_start = start_ms
        token_count = sum(word["acousticTokenCount"] for word in words)
        lines.append(
            {
                "lineIndex": planned_line["lineIndex"],
                "sourcePhysicalIndex": planned_line["sourcePhysicalIndex"],
                "lineId": planned_line["lineId"],
                "startMs": start_ms,
                "endMs": end_ms,
                "confidence": sum(
                    word["confidence"] * word["acousticTokenCount"] for word in words
                )
                / token_count,
                "minimumWordConfidence": min(word["confidence"] for word in words),
                "displayWordCount": len(words),
                "acousticTokenCount": token_count,
                "words": words,
            }
        )
    return lines, word_confidences


def _quantile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((len(ordered) - 1) * fraction)))]


def _infer_window(
    waveform: Any,
    start_frame: int,
    end_frame: int,
    feature_extractor: Any,
    model: Any,
    device: Any,
    dtype: Any,
) -> Any:
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    samples = (waveform.read(start_frame, end_frame)[0] if isinstance(waveform, StreamedMonoAudio)
               else waveform[0, start_frame:end_frame]).numpy()
    inputs = feature_extractor(
        samples, sampling_rate=SAMPLE_RATE, return_tensors="pt"
    )
    model_inputs = {
        key: value.to(device=device, dtype=dtype)
        if value.is_floating_point()
        else value.to(device)
        for key, value in inputs.items()
    }
    emitted = model(**model_inputs).logits[0].log_softmax(dim=-1).float().cpu()
    if int(emitted.shape[0]) <= 0:
        raise RuntimeError("Wav2Vec2 returned an empty emission chunk")
    return emitted


def _overlapped_emission_chunks(
    waveform: Any,
    sample_count: int,
    feature_extractor: Any,
    model: Any,
    device: Any,
    dtype: Any,
    inputs_to_logits_ratio: int,
    *, directory: Path | None = None,
) -> tuple[list[Any], list[dict[str, int]], int]:
    if type(inputs_to_logits_ratio) is not int or inputs_to_logits_ratio <= 0:
        raise ValueError("Wav2Vec2 model has an invalid inputs-to-logits ratio")
    maximum_chunk_samples = CHUNK_SECONDS * SAMPLE_RATE
    chunk_samples = min(
        round(CHUNK_SECONDS * SAMPLE_RATE / inputs_to_logits_ratio)
        * inputs_to_logits_ratio,
        maximum_chunk_samples // inputs_to_logits_ratio * inputs_to_logits_ratio,
    )
    stride_samples = int(
        round(STRIDE_SECONDS * SAMPLE_RATE / inputs_to_logits_ratio)
        * inputs_to_logits_ratio
    )
    advance_samples = chunk_samples - 2 * stride_samples
    if advance_samples <= 0:
        raise ValueError("Wav2Vec2 emission chunk stride is invalid")

    chunks: list[dict[str, int]] = []
    def infer_chunks():
        emission_start = 0
        for start in range(0, sample_count, advance_samples):
            end = min(start + chunk_samples, sample_count)
            emitted = _infer_window(waveform, start, end, feature_extractor, model, device, dtype)
            input_count = end - start
            left = 0 if start == 0 else stride_samples
            is_last = end >= sample_count
            right = 0 if is_last else stride_samples
            projected_count = int(round(input_count / inputs_to_logits_ratio))
            if abs(int(emitted.shape[0]) - projected_count) > 1:
                raise RuntimeError("Wav2Vec2 emission length is inconsistent with its ratio")
            emission_left = int(round(left / input_count * projected_count))
            emission_right = int(round(right / input_count * projected_count))
            retained = emitted[emission_left : projected_count - emission_right]
            retained_count = int(retained.shape[0])
            if retained_count <= 0:
                raise RuntimeError("Wav2Vec2 returned an empty retained emission chunk")
            emission_end = emission_start + retained_count
            chunks.append(ctc_worker.chunk_time_map(start+left, end-right, emission_start, emission_end))
            emission_start = emission_end
            yield retained
            if is_last:
                break
    from stemwise_engine.acoustic_inputs import mapped_emissions
    emissions = list(infer_chunks()) if directory is None else mapped_emissions(infer_chunks(), directory)
    return emissions, chunks, chunk_samples


def _prepare(audio_path: Path, request_path: Path, model_path: Path, language: str, device_name: str,
             model_state: dict[str, Any], *, emission_cache_key: str | None = None,
             request_value: dict[str, Any] | None = None, emissions_only: bool = False) -> dict[str, Any]:
    import torch
    import torchaudio
    from transformers import (
        AutoConfig,
        Wav2Vec2CTCTokenizer,
        Wav2Vec2FeatureExtractor,
        Wav2Vec2PhonemeCTCTokenizer,
    )

    request = read_worker_json(request_path) if request_value is None else request_value
    request_duration_ms, segments = ctc_worker.validate_request(request)
    local_model = model_path.resolve(strict=True)
    if not local_model.is_dir():
        raise ValueError("Wav2Vec2 model path is not a directory")
    if "target_runtime" not in model_state:
        tokenizer_class = _resolved_tokenizer_class(local_model)
        if tokenizer_class == "Wav2Vec2PhonemeCTCTokenizer":
            tokenizer = Wav2Vec2PhonemeCTCTokenizer.from_pretrained(
                str(local_model), local_files_only=True, phonemizer_lang=language
            )
            phoneme = True
        elif tokenizer_class == "Wav2Vec2CTCTokenizer":
            tokenizer = Wav2Vec2CTCTokenizer.from_pretrained(
                str(local_model), local_files_only=True
            )
            phoneme = False
        feature_extractor = Wav2Vec2FeatureExtractor.from_pretrained(
            str(local_model), local_files_only=True
        )
        if int(feature_extractor.sampling_rate) != SAMPLE_RATE:
            raise ValueError("Wav2Vec2 feature extractor is not 16 kHz")

        model_state["target_runtime"] = (tokenizer, phoneme, feature_extractor)
    tokenizer, phoneme, feature_extractor = model_state["target_runtime"]
    if emissions_only:
        target_ids, line_plan, delimiter = [], [], None
    else:
        target_ids, line_plan, delimiter = _target_plan(
            request, tokenizer, language, phoneme=phoneme
        )

    resolved_device = (
        device_name
        if device_name != "auto"
        else ("cuda" if torch.cuda.is_available() else "cpu")
    )
    if resolved_device == "cuda" and not torch.cuda.is_available():
        raise WorkerReasonError("cuda-unavailable", "CUDA was requested but is unavailable")
    device = torch.device(resolved_device)
    dtype = torch.float16 if resolved_device == "cuda" else torch.float32
    model_config = AutoConfig.from_pretrained(str(local_model), local_files_only=True)
    blank = model_config.pad_token_id
    if type(blank) is not int or blank < 0 or (
        delimiter is not None and blank == delimiter
    ):
        raise ValueError("Wav2Vec2 model has an invalid CTC blank token")
    if tokenizer.pad_token_id is not None and tokenizer.pad_token_id != blank:
        raise ValueError("Wav2Vec2 model and tokenizer disagree on the CTC blank token")

    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET, StreamedMonoAudio
    waveform = StreamedMonoAudio(audio_path, SAMPLE_RATE)
    pcm_bytes = (min(waveform.frames, CHUNK_SECONDS*waveform.source_rate)*waveform.channels
                 + min(waveform.sample_count, CHUNK_SECONDS*SAMPLE_RATE))*4
    if not emissions_only:
        for segment in segments:
            segment_targets, _ = _segment_target_plan(target_ids,line_plan,segment,has_word_delimiters=delimiter is not None)
            duration = segment['windowEndMs']-segment['windowStartMs']
            count = len(segment_targets)
            CURRENT_BUDGET.require(frames=(duration+19)//20+2,
                states=2*count+1,arrays=3,retained_bytes=pcm_bytes+((duration+19)//20+2)*int(model_config.vocab_size)*4)
    source_sample_rate = waveform.source_rate
    source_channel_count = waveform.channels
    source_frame_count = waveform.frames
    sample_count = waveform.sample_count
    duration_ms = (sample_count * 1000 + SAMPLE_RATE // 2) // SAMPLE_RATE
    if duration_ms != request_duration_ms:
        raise ValueError("Wav2Vec2 request duration does not match the audio")

    def produce_emissions():
        from transformers import AutoModelForCTC

        model_state.get("acquire_stage", lambda: None)()
        if "model" not in model_state:
            model_state["cuda_model"] = resolved_device == "cuda"
            model_state["model"] = AutoModelForCTC.from_pretrained(
                str(local_model), local_files_only=True, torch_dtype=dtype
            ).to(device)
            model_state["model"].eval()
        model = model_state["model"]
        with torch.inference_mode():
            emissions, chunks, chunk_samples = _overlapped_emission_chunks(
                waveform, sample_count, feature_extractor, model, device, dtype,
                model.config.inputs_to_logits_ratio,
                directory=request_path.parent,
            )
        return emissions, chunks, chunk_samples

    cache_status: dict[str, Any] = {}
    emission, chunks, chunk_samples = ctc_worker.cached_emissions(
        request_path, emission_cache_key, sample_count, model_config.vocab_size, produce_emissions,
        cache_status=cache_status,
    )
    return {
        "emission": emission,
        "chunks": chunks,
        "chunk_samples": chunk_samples,
        "target_ids": target_ids,
        "blank": blank,
        "delimiter": delimiter,
        "segments": segments,
        "line_plan": line_plan,
        "duration_ms": duration_ms,
        "source_sample_rate": source_sample_rate,
        "source_channel_count": source_channel_count,
        "source_frame_count": source_frame_count,
        "sample_count": sample_count,
        "cache_status": cache_status,
    }


def _decode(prepared: dict[str, Any]) -> dict[str, Any]:
    import torchaudio

    emission = prepared["emission"]
    chunks = prepared["chunks"]
    chunk_samples = prepared["chunk_samples"]
    target_ids = prepared["target_ids"]
    blank = prepared["blank"]
    delimiter = prepared["delimiter"]
    segments = prepared["segments"]
    line_plan = prepared["line_plan"]
    duration_ms = prepared["duration_ms"]
    source_sample_rate = prepared["source_sample_rate"]
    source_channel_count = prepared["source_channel_count"]
    source_frame_count = prepared["source_frame_count"]
    sample_count = prepared["sample_count"]

    validated_ids = target_ids + [blank] + ([delimiter] if delimiter is not None else [])
    if max(validated_ids) >= int(emission.shape[-1]):
        raise WorkerReasonError("wav2vec2-target-outside-vocabulary", "Wav2Vec2 target token exceeds the model vocabulary")
    lines: list[dict[str, Any]] = []
    word_confidences: list[float] = []
    aligned_target_ids: list[int] = []
    segment_results = prepared.setdefault("segment_results", {})
    for segment in segments:
        key = tuple(segment[field] for field in ("startLineIndex", "endLineIndex", "windowStartMs", "windowEndMs"))
        if key in segment_results:
            cached_lines, cached_confidences, cached_targets = segment_results[key]
            lines.extend(cached_lines)
            word_confidences.extend(cached_confidences)
            aligned_target_ids.extend(cached_targets)
            continue
        segment_targets, segment_plan = _segment_target_plan(
            target_ids,
            line_plan,
            segment,
            has_word_delimiters=delimiter is not None,
        )
        aligned_target_ids.extend(segment_targets)
        emission_start, emission_end = ctc_worker.window_to_emission_frames(
            segment["windowStartMs"],
            segment["windowEndMs"],
            duration_ms,
            SAMPLE_RATE,
            chunks,
        )
        segment_emission = emission[emission_start:emission_end]
        minimum_frames = len(segment_targets) + sum(
            left == right
            for left, right in zip(segment_targets, segment_targets[1:])
        )
        if int(segment_emission.shape[0]) < minimum_frames:
            raise WorkerReasonError(f"wav2vec2-segment-too-short:{segment['segmentIndex']}",
                f"Wav2Vec2 segment {segment['segmentIndex']} emission is too short for its target")
        paths, scores = ctc_worker.align_ctc(
            segment_emission,
            segment_targets,
            blank=blank,
            allow_trailing_audio=segment["windowEndMs"] == duration_ms,
        )
        spans = list(
            torchaudio.functional.merge_tokens(paths[0], scores[0], blank=blank)
        )
        if len(spans) != len(segment_targets) or any(
            int(span.token) != token for span, token in zip(spans, segment_targets)
        ):
            raise WorkerReasonError("wav2vec2-target-mismatch", "Wav2Vec2 forced alignment changed the approved target")
        segment_lines, segment_confidences = _result_records(
            segment_plan,
            spans,
            chunks,
            duration_ms,
            frame_offset=emission_start,
        )
        ctc_worker.validate_segment_boundary(segment_lines, segment, duration_ms)
        segment_results[key] = (segment_lines, segment_confidences, segment_targets)
        lines.extend(segment_lines)
        word_confidences.extend(segment_confidences)
    if aligned_target_ids != target_ids:
        raise WorkerReasonError("wav2vec2-segment-target-mismatch", "Wav2Vec2 segments changed the approved target")
    ctc_worker.validate_global_results(lines, line_plan, duration_ms)
    line_confidences = [line["confidence"] for line in lines]
    transform = ctc_worker.canonical_time_transform(
        source_sample_rate=int(source_sample_rate),
        source_channel_count=source_channel_count,
        source_frame_count=source_frame_count,
        target_sample_rate=SAMPLE_RATE,
        target_frame_count=sample_count,
        chunk_target_frames=chunk_samples,
        chunks=chunks,
    )
    return {
            "formatVersion": FORMAT_VERSION,
            "requestFormatVersion": ctc_worker.REQUEST_VERSION,
            "engine": ENGINE,
            "sampleRate": SAMPLE_RATE,
            "durationMs": duration_ms,
            "emissionFrameCount": int(emission.shape[0]),
            "canonicalTimeTransform": transform,
            "segmentCount": len(segments),
            "segmentBoundaryGuardMs": ctc_worker.SEGMENT_BOUNDARY_GUARD_MS,
            "displayWordCount": sum(len(line["words"]) for line in lines),
            "acousticTokenCount": sum(line["acousticTokenCount"] for line in lines),
            "lineCount": len(lines),
            "confidence": {
                "wordP10": _quantile(word_confidences, 0.10),
                "wordP50": _quantile(word_confidences, 0.50),
                "wordP90": _quantile(word_confidences, 0.90),
                "lineP10": _quantile(line_confidences, 0.10),
                "lineP50": _quantile(line_confidences, 0.50),
                "lineP90": _quantile(line_confidences, 0.90),
            },
            "lines": lines,
        }


def run(audio_path: Path, request_path: Path, output_path: Path, model_path: Path, language: str,
        device_name: str, *, emission_cache_key: str | None = None) -> None:
    model_state: dict[str, Any] = {}
    try:
        prepared = _prepare(audio_path, request_path, model_path, language, device_name, model_state, emission_cache_key=emission_cache_key)
    finally:
        ctc_worker.retire_model(model_state)
    publish_json(output_path, _decode(prepared))


def run_batch(manifest_path: Path, output_path: Path, model_path: Path, language: str,
              device_name: str, *, project_dir: Path | None = None) -> None:
    ctc_worker.run_alignment_batch(manifest_path, output_path, device_name,
        lambda row, state, request: _prepare(Path(row["audio"]), Path(row["request"]), model_path, language,
                                   device_name, state, emission_cache_key=row["emissionCacheKey"], request_value=request),
        _decode, project_dir=project_dir, cuda_decode=False)


def prepare_many(manifest_path: Path, output_path: Path, model_path: Path, language: str,
                 device_name: str, *, project_dir: Path | None = None) -> None:
    plan = ctc_worker.prepare_batch_plan(manifest_path, output_path)
    ctc_worker.run_alignment_batch(manifest_path, output_path, device_name,
        lambda row, state, request: _prepare(Path(row["audio"]), Path(row["request"]), model_path, language,
            device_name, state, emission_cache_key=row["emissionCacheKey"], request_value=request, emissions_only=True),
        _decode, project_dir=project_dir, cuda_decode=False, preparation_plan=plan)


def runtime_info(device_name: str) -> dict[str, Any]:
    import torch
    import torchaudio
    from phonemizer.backend import EspeakBackend

    resolved = (
        device_name
        if device_name != "auto"
        else ("cuda" if torch.cuda.is_available() else "cpu")
    )
    cuda_available = bool(torch.cuda.is_available())
    return {
        "runtimeVersion": 3,
        'acousticInputSha256': semantic_source_identity(Path(__file__).with_name('acoustic_inputs.py')),
        "supportedRequestVersions": [ctc_worker.REQUEST_VERSION],
        "python": platform.python_version(),
        "implementation": platform.python_implementation(),
        "torch": str(torch.__version__),
        "torchaudio": str(torchaudio.__version__),
        "transformers": importlib.metadata.version("transformers"),
        "safetensors": importlib.metadata.version("safetensors"),
        "phonemizer": importlib.metadata.version("phonemizer"),
        "espeak": ".".join(str(value) for value in EspeakBackend.version()),
        "engine": ENGINE,
        "sampleRate": SAMPLE_RATE,
        "chunkSeconds": CHUNK_SECONDS,
        "strideSeconds": STRIDE_SECONDS,
        "retainedCoreSeconds": RETAINED_CORE_SECONDS,
        "emissionMap": "shared-global-overlap-trimmed",
        "requestedDevice": device_name,
        "resolvedDevice": resolved,
        "cudaAvailable": cuda_available,
        "cudaDeviceName": (
            str(torch.cuda.get_device_name(0)) if cuda_available else None
        ),
        "cudaRuntime": str(getattr(torch.version, "cuda", None)),
        "cudnn": str(torch.backends.cudnn.version()),
    }


def check_targets(request_path: Path, model_path: Path, language: str) -> dict:
    """Check exact request/tokenizer capability without audio decoding or a model."""
    from transformers import Wav2Vec2CTCTokenizer, Wav2Vec2PhonemeCTCTokenizer
    request = read_worker_json(request_path)
    ctc_worker.validate_request(request)
    phoneme = _resolved_tokenizer_class(model_path) == 'Wav2Vec2PhonemeCTCTokenizer'
    cls = Wav2Vec2PhonemeCTCTokenizer if phoneme else Wav2Vec2CTCTokenizer
    tokenizer = cls.from_pretrained(str(model_path), local_files_only=True,
        **({'phonemizer_lang': language} if phoneme else {}))
    _target_plan(request, tokenizer, language, phoneme=phoneme)
    return {'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
            'requestSha256': ctc_worker.canonical_sha256(request), 'supported': True}


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--audio", type=Path)
    result.add_argument("--request", type=Path)
    result.add_argument("--output", type=Path)
    result.add_argument("--model", type=Path)
    result.add_argument("--language")
    result.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    result.add_argument("--emission-cache-key")
    result.add_argument("--batch", type=Path)
    result.add_argument("--prepare-batch", type=Path)
    result.add_argument("--project", type=Path)
    result.add_argument("--runtime-info", action="store_true")
    result.add_argument('--check-targets', action='store_true')
    return result


def main() -> int:
    arguments = parser().parse_args()
    if arguments.check_targets:
        if any(value is None for value in (arguments.request, arguments.model, arguments.language, arguments.output)):
            raise SystemExit('target checking requires request, model, language and output')
        try:
            result = check_targets(arguments.request, arguments.model, arguments.language)
        except TargetVocabularyUnsupported as error:
            publish_json(arguments.output, {'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                'requestSha256': error.request_sha256, 'supported': False})
            return 3
        publish_json(arguments.output, result)
        return 0
    if arguments.batch is not None and arguments.prepare_batch is not None:
        raise SystemExit("--batch and --prepare-batch are mutually exclusive")
    if arguments.runtime_info:
        json.dump(
            runtime_info(arguments.device),
            sys.stdout,
            ensure_ascii=True,
            sort_keys=True,
        )
        sys.stdout.write("\n")
        return 0
    if arguments.prepare_batch is not None:
        if arguments.output is None or arguments.model is None or arguments.language is None:
            raise SystemExit("--prepare-batch requires --output, --model, and --language")
        prepare_many(arguments.prepare_batch, arguments.output, arguments.model, arguments.language,
                     arguments.device, project_dir=arguments.project)
        return 0
    if arguments.batch is not None:
        if arguments.output is None or arguments.model is None or arguments.language is None:
            raise SystemExit("--batch requires --output, --model, and --language")
        try:
            run_batch(arguments.batch, arguments.output, arguments.model, arguments.language,
                      arguments.device, project_dir=arguments.project)
        except TargetVocabularyUnsupported as error:
            publish_json(arguments.output, {
                "formatVersion": 1, "engine": "wav2vec2-target-capability",
                "requestSha256": error.request_sha256, "supported": False,
            })
            return 3
        return 0
    required = (
        arguments.audio,
        arguments.request,
        arguments.output,
        arguments.model,
        arguments.language,
    )
    if any(value is None for value in required):
        raise SystemExit(
            "--audio, --request, --output, --model, and --language are required"
        )
    try:
        run(
            arguments.audio,
            arguments.request,
            arguments.output,
            arguments.model,
            arguments.language,
            arguments.device,
            emission_cache_key=arguments.emission_cache_key,
        )
    except TargetVocabularyUnsupported as error:
        publish_json(arguments.output, {
            "formatVersion": 1, "engine": "wav2vec2-target-capability",
            "requestSha256": error.request_sha256, "supported": False,
        })
        return 3
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
