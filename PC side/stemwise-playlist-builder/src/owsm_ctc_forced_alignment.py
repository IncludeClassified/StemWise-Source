"""Closed local OWSM-CTC worker for lexical line endpoint evidence."""

from __future__ import annotations

import argparse
import hashlib
import importlib
import importlib.metadata
import json
import math
import os
import platform
import re
import sys
import tempfile
import unicodedata
from pathlib import Path
from stemwise_engine.model_registry import model_assets, runtime_profile, cuda_device_identity, validate_cuda_device
from stemwise_engine.worker_protocol import read_worker_json, parse_worker_json
from typing import Any

import traceback
import stemwise_engine.worker_protocol as worker_protocol
import signal
from contextlib import contextmanager
from stemwise_engine.common import heavy_worker_stage
from stemwise_engine.ctc_worker import align_ctc, retire_model, execution_deadline


REQUEST_VERSION = 1
FORMAT_VERSION = 2
ENGINE = "owsm-ctc-lexical-endpoints"
ROUTE = "owsm-ctc-v4-1b-arabic-v1"
from stemwise_engine.model_registry import LANGUAGES
ROUTES_BY_LANGUAGE = {language: entry['owsmEndpointRoute'] for language, entry in LANGUAGES.items()
                      if 'owsmEndpointRoute' in entry}
MODEL_REVISION = model_assets('owsm_ctc_forced_alignment')['MODEL_REVISION']
CHECKPOINT_NAME = model_assets('owsm_ctc_forced_alignment')['CHECKPOINT_NAME']
CHECKPOINT_SHA256 = model_assets('owsm_ctc_forced_alignment')['CHECKPOINT_SHA256']
CHECKPOINT_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['CHECKPOINT_BYTE_COUNT']
MODEL_RUN_RELATIVE_PATH = Path(model_assets('owsm_ctc_forced_alignment')['MODEL_RUN_RELATIVE_PATH'])
CONFIG_NAME = model_assets('owsm_ctc_forced_alignment')['CONFIG_NAME']
CONFIG_SHA256 = model_assets('owsm_ctc_forced_alignment')['CONFIG_SHA256']
CONFIG_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['CONFIG_BYTE_COUNT']
BPE_RELATIVE_PATH = Path(model_assets('owsm_ctc_forced_alignment')['BPE_RELATIVE_PATH'])
BPE_SHA256 = model_assets('owsm_ctc_forced_alignment')['BPE_SHA256']
BPE_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['BPE_BYTE_COUNT']
STATS_RELATIVE_PATH = Path(model_assets('owsm_ctc_forced_alignment')['STATS_RELATIVE_PATH'])
STATS_SHA256 = model_assets('owsm_ctc_forced_alignment')['STATS_SHA256']
STATS_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['STATS_BYTE_COUNT']
ESPNET_SOURCE_REVISION = model_assets('owsm_ctc_forced_alignment')['ESPNET_SOURCE_REVISION']
ALIGN_SHA256 = model_assets('owsm_ctc_forced_alignment')['ALIGN_SHA256']
ALIGN_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['ALIGN_BYTE_COUNT']
HELPER_SHA256 = model_assets('owsm_ctc_forced_alignment')['HELPER_SHA256']
HELPER_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['HELPER_BYTE_COUNT']
PYTHON_VERSION = runtime_profile('owsm_ctc_forced_alignment')['PYTHON_VERSION']
ESPNET_VERSION = runtime_profile('owsm_ctc_forced_alignment')['ESPNET_VERSION']
ESPNET_WHEEL_SHA256 = model_assets('owsm_ctc_forced_alignment')['ESPNET_WHEEL_SHA256']
ESPNET_WHEEL_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['ESPNET_WHEEL_BYTE_COUNT']
ESPNET_WHEEL_NAME = model_assets('owsm_ctc_forced_alignment')['ESPNET_WHEEL_NAME']
RUNTIME_LOCK_SHA256 = model_assets('owsm_ctc_forced_alignment')['RUNTIME_LOCK_SHA256']
RUNTIME_LOCK_BYTE_COUNT = model_assets('owsm_ctc_forced_alignment')['RUNTIME_LOCK_BYTE_COUNT']
RUNTIME_LOCK_NAME = model_assets('owsm_ctc_forced_alignment')['RUNTIME_LOCK_NAME']
TORCH_VERSION = runtime_profile('owsm_ctc_forced_alignment')['TORCH_VERSION']
TORCHAUDIO_VERSION = runtime_profile('owsm_ctc_forced_alignment')['TORCHAUDIO_VERSION']
CUDA_VERSION = runtime_profile('owsm_ctc_forced_alignment')['CUDA_VERSION']
CTC_SEGMENTATION_VERSION = runtime_profile('owsm_ctc_forced_alignment')['CTC_SEGMENTATION_VERSION']
CTC_SEGMENTATION_REVISION = runtime_profile('owsm_ctc_forced_alignment')['CTC_SEGMENTATION_REVISION']
NUMPY_VERSION = runtime_profile('owsm_ctc_forced_alignment')['NUMPY_VERSION']
SOUNDFILE_VERSION = runtime_profile('owsm_ctc_forced_alignment')['SOUNDFILE_VERSION']
LIBSNDFILE_VERSION = runtime_profile('owsm_ctc_forced_alignment')['LIBSNDFILE_VERSION']
FROZEN_CONFIG = {
    "fs": 16_000,
    "ngpu": 1,
    "batchSize": 1,
    "dtype": "float32",
    "kaldiStyleText": True,
    "textConverter": "tokenize",
    "timeStamps": "auto",
    "langSym": "<ara>",
    "taskSym": "<asr>",
    "contextLenInSecs": 2,
    "gratisBlank": False,
    "replaceSpacesWithBlanks": False,
    "scoringLength": 30,
    "channelMix": "informative-recording-view-float32-v1",
    "resampling": "torchaudio-sinc-interp-hann-cpu",
}
_SHA256 = re.compile(r"[0-9a-f]{64}")
_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,119}")


def _route_config(route: str) -> dict[str, Any]:
    symbols = {ROUTE: "<ara>", ROUTES_BY_LANGUAGE["en"]: "<eng>",
               ROUTES_BY_LANGUAGE["zu"]: "<zul>", ROUTES_BY_LANGUAGE["tr"]: "<tur>"}
    if route not in symbols:
        raise ValueError("OWSM-CTC route is unsupported")
    return {**FROZEN_CONFIG, "langSym": symbols[route]}


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"OWSM-CTC {label} must contain exactly {sorted(expected)}")
    return value


def _identifier(value: Any, label: str) -> str:
    if not isinstance(value, str) or _IDENTIFIER.fullmatch(value) is None:
        raise ValueError(f"OWSM-CTC {label} is malformed")
    return value


def _spoken_text(value: Any) -> str:
    if not isinstance(value, str) or unicodedata.normalize("NFKC", value) != value:
        raise ValueError("OWSM-CTC acoustic word is not normalized")
    words = value.split()
    if not words:
        raise ValueError("OWSM-CTC acoustic word is empty")
    return " ".join(words)


def _artifact(value: Any, label: str) -> dict[str, Any]:
    record = _exact_keys(value, {"path", "sha256", "byteCount"}, label)
    if not isinstance(record["path"], str) or not record["path"]:
        raise ValueError(f"OWSM-CTC {label} path is malformed")
    if not isinstance(record["sha256"], str) or _SHA256.fullmatch(record["sha256"]) is None:
        raise ValueError(f"OWSM-CTC {label} SHA-256 is malformed")
    if type(record["byteCount"]) is not int or record["byteCount"] <= 0:
        raise ValueError(f"OWSM-CTC {label} byte count is malformed")
    return record


def _validated_request(value: Any) -> dict[str, Any]:
    request = _exact_keys(
        value,
        {"formatVersion", "route", "durationMs", "audio", "model", "source", "runtime", "lines"},
        "request",
    )
    if request["formatVersion"] != REQUEST_VERSION or request["route"] not in ROUTES_BY_LANGUAGE.values():
        raise ValueError("OWSM-CTC request identity is malformed")
    if type(request["durationMs"]) is not int or request["durationMs"] <= 0:
        raise ValueError("OWSM-CTC duration is malformed")
    _artifact(request["audio"], "audio")
    model = _exact_keys(
        request["model"], {"revision", "checkpoint", "config", "bpe", "stats"}, "model"
    )
    if model["revision"] != MODEL_REVISION:
        raise ValueError("OWSM-CTC model revision is not pinned")
    for label in ("checkpoint", "config", "bpe", "stats"):
        _artifact(model[label], label)
    expected_model = {
        "checkpoint": (CHECKPOINT_SHA256, CHECKPOINT_BYTE_COUNT),
        "config": (CONFIG_SHA256, CONFIG_BYTE_COUNT),
        "bpe": (BPE_SHA256, BPE_BYTE_COUNT),
        "stats": (STATS_SHA256, STATS_BYTE_COUNT),
    }
    if any(
        (model[label]["sha256"], model[label]["byteCount"]) != expected
        for label, expected in expected_model.items()
    ):
        raise ValueError("OWSM-CTC model artifact identity is not pinned")

    source = _exact_keys(
        request["source"], {"revision", "align", "helper"}, "source"
    )
    if source["revision"] != ESPNET_SOURCE_REVISION:
        raise ValueError("OWSM-CTC source revision is not pinned")
    for label in ("align", "helper"):
        _artifact(source[label], label)
    if (
        source["align"]["sha256"],
        source["align"]["byteCount"],
        source["helper"]["sha256"],
        source["helper"]["byteCount"],
    ) != (ALIGN_SHA256, ALIGN_BYTE_COUNT, HELPER_SHA256, HELPER_BYTE_COUNT):
        raise ValueError("OWSM-CTC source helper identity is not pinned")

    runtime = _exact_keys(
        request["runtime"],
        {
            "python",
            "espnet",
            "torch",
            "torchaudio",
            "cudaRuntime",
            "cudaDevice",
            "ctcSegmentation",
            "numpy",
            "soundfile",
            "libsndfile",
            "wheel",
            "lock",
        },
        "runtime",
    )
    expected_runtime = {
        "python": PYTHON_VERSION,
        "espnet": ESPNET_VERSION,
        "torch": TORCH_VERSION,
        "torchaudio": TORCHAUDIO_VERSION,
        "cudaRuntime": CUDA_VERSION,
        "ctcSegmentation": CTC_SEGMENTATION_VERSION,
        "numpy": NUMPY_VERSION,
        "soundfile": SOUNDFILE_VERSION,
        "libsndfile": LIBSNDFILE_VERSION,
    }
    if any(runtime[key] != expected for key, expected in expected_runtime.items()):
        raise ValueError("OWSM-CTC core runtime identity is not pinned")
    validate_cuda_device(runtime['cudaDevice'], 'owsm_ctc_forced_alignment')
    for label in ("wheel", "lock"):
        _artifact(runtime[label], label)
    if (
        runtime["wheel"]["sha256"],
        runtime["wheel"]["byteCount"],
        runtime["lock"]["sha256"],
        runtime["lock"]["byteCount"],
    ) != (
        ESPNET_WHEEL_SHA256,
        ESPNET_WHEEL_BYTE_COUNT,
        RUNTIME_LOCK_SHA256,
        RUNTIME_LOCK_BYTE_COUNT,
    ):
        raise ValueError("OWSM-CTC runtime files are not pinned")

    lines = request["lines"]
    if not isinstance(lines, list) or not lines:
        raise ValueError("OWSM-CTC request has no approved lines")
    line_ids: set[str] = set()
    word_ids: set[str] = set()
    for line in lines:
        _exact_keys(line, {"sourcePhysicalIndex", "lineId", "words"}, "line")
        if type(line["sourcePhysicalIndex"]) is not int or line["sourcePhysicalIndex"] < 0:
            raise ValueError("OWSM-CTC source line index is malformed")
        line_id = _identifier(line["lineId"], "line ID")
        if line_id in line_ids:
            raise ValueError("OWSM-CTC line IDs are not unique")
        line_ids.add(line_id)
        if not isinstance(line["words"], list) or not line["words"]:
            raise ValueError("OWSM-CTC approved line has no words")
        for word in line["words"]:
            keys = {"wordId", "acousticText"}
            if isinstance(word, dict) and "acousticLanguage" in word:
                keys.add("acousticLanguage")
            _exact_keys(word, keys, "word")
            if "acousticLanguage" in word:
                from stemwise_engine.lyrics_alignment import canonical_language
                language = canonical_language(word["acousticLanguage"])
                if ROUTES_BY_LANGUAGE.get(language) != request["route"]:
                    raise ValueError("OWSM-CTC acoustic language is unsupported by this route")
            word_id = _identifier(word["wordId"], "word ID")
            if word_id in word_ids:
                raise ValueError("OWSM-CTC word IDs are not unique")
            word_ids.add(word_id)
            _spoken_text(word["acousticText"])
    return request


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bound_file(value: dict[str, Any], label: str) -> Path:
    path = Path(value["path"])
    if not path.is_absolute():
        raise ValueError("OWSM-CTC accepts absolute local paths only")
    path = path.resolve(strict=True)
    if (
        not path.is_file()
        or path.stat().st_size != value["byteCount"]
        or _sha256_file(path) != value["sha256"]
    ):
        raise ValueError(f"OWSM-CTC {label} content binding does not match")
    return path


def _audio_info(path: Path):
    try:
        import soundfile

        info = soundfile.info(str(path))
    except Exception as error:
        raise ValueError("OWSM-CTC audio is not a valid WAV") from error
    if (
        info.format not in {"WAV", "WAVEX", "RF64"}
        or type(info.samplerate) is not int
        or info.samplerate <= 0
        or type(info.frames) is not int
        or info.frames <= 0
        or type(info.channels) is not int
        or info.channels <= 0
    ):
        raise ValueError("OWSM-CTC audio stream is malformed")
    return info


def _wav_duration_ms(path: Path) -> int:
    info = _audio_info(path)
    return (info.frames * 1000 + info.samplerate // 2) // info.samplerate


def _resolved_bindings(request: dict[str, Any]) -> dict[str, Path]:
    paths = {"audio": _bound_file(request["audio"], "audio")}
    for label in ("checkpoint", "config", "bpe", "stats"):
        paths[label] = _bound_file(request["model"][label], label)
    for label in ("align", "helper"):
        paths[label] = _bound_file(request["source"][label], label)
    for label in ("wheel", "lock"):
        paths[label] = _bound_file(request["runtime"][label], label)
    if _wav_duration_ms(paths["audio"]) != request["durationMs"]:
        raise ValueError("OWSM-CTC audio duration binding does not match")
    model_root = paths["config"].parents[2]
    if (
        paths["config"].name != CONFIG_NAME
        or paths["checkpoint"].name != CHECKPOINT_NAME
        or paths["checkpoint"].parent != paths["config"].parent
        or paths["bpe"] != model_root / BPE_RELATIVE_PATH
        or paths["stats"] != model_root / STATS_RELATIVE_PATH
        or paths["helper"] != paths["align"].parents[1] / "tasks" / "s2t_ctc.py"
        or paths["align"].name != "s2t_ctc_align.py"
        or paths["wheel"].name != ESPNET_WHEEL_NAME
        or paths["lock"].name != RUNTIME_LOCK_NAME
    ):
        raise ValueError("OWSM-CTC local model/source layout is malformed")
    config_text = paths["config"].read_text(encoding="utf-8")
    if (
        config_text.count(f"bpemodel: {BPE_RELATIVE_PATH.as_posix()}") != 1
        or config_text.count(f"    stats_file: {STATS_RELATIVE_PATH.as_posix()}") != 1
    ):
        raise ValueError("OWSM-CTC config does not bind the pinned BPE and stats")
    paths["modelRoot"] = model_root
    return paths


def _runtime_identity(expected: dict[str, Any]) -> dict[str, Any]:
    try:
        import numpy
        import soundfile
        import torch
        import torchaudio

        importlib.import_module("ctc_segmentation")
    except ImportError as error:
        raise RuntimeError("OWSM-CTC runtime is unavailable") from error
    try:
        espnet_version = importlib.metadata.version("espnet")
        ctc_version = importlib.metadata.version("ctc-segmentation")
    except importlib.metadata.PackageNotFoundError as error:
        raise RuntimeError("OWSM-CTC distribution runtime is unavailable") from error
    actual = {
        "python": platform.python_version(),
        "espnet": espnet_version,
        "torch": torch.__version__,
        "torchaudio": torchaudio.__version__,
        "cudaRuntime": torch.version.cuda,
        "cudaDevice": cuda_device_identity('owsm_ctc_forced_alignment', torch_module=torch),
        "ctcSegmentation": ctc_version,
        "numpy": numpy.__version__,
        "soundfile": soundfile.__version__,
        "libsndfile": soundfile.__libsndfile_version__,
    }
    expected_versions = {key: value for key, value in expected.items() if key not in {"wheel", "lock"}}
    if actual != expected_versions or not torch.cuda.is_available():
        raise RuntimeError("OWSM-CTC runtime identity is not pinned")
    return actual


def _load_aligner(paths: dict[str, Path], route: str = ROUTE):
    config = _route_config(route)
    os.environ.update(
        {
            "HF_HUB_OFFLINE": "1",
            "TRANSFORMERS_OFFLINE": "1",
            "HF_DATASETS_OFFLINE": "1",
            "TOKENIZERS_PARALLELISM": "false",
            "WANDB_MODE": "disabled",
        }
    )
    module = importlib.import_module("espnet2.bin.s2t_ctc_align")
    if Path(module.__file__).resolve(strict=True) != paths["align"]:
        raise RuntimeError("OWSM-CTC imported aligner differs from its content binding")
    helper = importlib.import_module("espnet2.tasks.s2t_ctc")
    if Path(helper.__file__).resolve(strict=True) != paths["helper"]:
        raise RuntimeError("OWSM-CTC imported task helper differs from its content binding")
    os.chdir(paths["modelRoot"])
    aligner = module.CTCSegmentation(
        s2t_train_config=str(paths["config"]),
        s2t_model_file=str(paths["checkpoint"]),
        fs=FROZEN_CONFIG["fs"],
        ngpu=FROZEN_CONFIG["ngpu"],
        batch_size=FROZEN_CONFIG["batchSize"],
        dtype=FROZEN_CONFIG["dtype"],
        kaldi_style_text=FROZEN_CONFIG["kaldiStyleText"],
        text_converter=FROZEN_CONFIG["textConverter"],
        time_stamps=FROZEN_CONFIG["timeStamps"],
        lang_sym=config["langSym"],
        task_sym=FROZEN_CONFIG["taskSym"],
        context_len_in_secs=FROZEN_CONFIG["contextLenInSecs"],
        gratis_blank=FROZEN_CONFIG["gratisBlank"],
        replace_spaces_with_blanks=FROZEN_CONFIG["replaceSpacesWithBlanks"],
        scoring_length=FROZEN_CONFIG["scoringLength"],
    )
    args = aligner.s2t_train_args
    if (
        getattr(args, "token_type", None) != "bpe"
        or Path(str(getattr(args, "bpemodel", ""))).resolve(strict=True) != paths["bpe"]
        or Path(str(getattr(args, "normalize_conf", {}).get("stats_file", ""))).resolve(strict=True)
        != paths["stats"]
        or aligner.config.blank_transition_cost_zero != FROZEN_CONFIG["gratisBlank"]
        or aligner.config.replace_spaces_with_blanks
        != FROZEN_CONFIG["replaceSpacesWithBlanks"]
        or aligner.config.score_min_mean_over_L != FROZEN_CONFIG["scoringLength"]
    ):
        raise RuntimeError("OWSM-CTC loaded model dependencies differ from their bindings")
    return aligner




@contextmanager
def _model_stage(paths, route):
    stage = heavy_worker_stage({'projectDir': str(Path(__file__).resolve().parent.parent)})
    timer = signal.setitimer(signal.ITIMER_REAL, 0)
    try:
        stage.__enter__()
    finally:
        if timer[0] > 0:
            signal.setitimer(signal.ITIMER_REAL, timer[0], timer[1])
    try:
        try:
            aligner = _load_aligner(paths, route)
        except BaseException:
            worker_protocol.fatal_model_failure('owsm-model-initialization-failed')
        try:
            yield aligner
        except BaseException as error:
            traceback.clear_frames(error.__traceback__)
            raise
        finally:
            try:
                with execution_deadline(30):
                    state = {'model': aligner.s2t_model, 'cuda_model': True}
                    aligner.s2t_model = None
                    aligner.ctc = None
                    retire_model(state)
            except BaseException:
                worker_protocol.fatal_model_failure('owsm-model-retirement-failed')
    finally:
        stage.__exit__(None, None, None)

def _framed(digest: Any, value: str) -> None:
    raw = value.encode("utf-8")
    digest.update(len(raw).to_bytes(8, "big"))
    digest.update(raw)


def _preflight(
    aligner: Any, request: dict[str, Any]
) -> tuple[list[str], list[dict[str, Any]], dict[str, Any]]:
    cleaner = aligner.preprocess_fn.text_cleaner
    tokenizer = aligner.preprocess_fn.tokenizer
    converter = aligner.preprocess_fn.token_id_converter
    try:
        unknown_id = aligner.config.char_list.index("<unk>")
    except (AttributeError, ValueError) as error:
        raise RuntimeError("OWSM-CTC tokenizer has no pinned unknown token") from error
    digest = hashlib.sha256()
    token_count = 0
    word_count = 0
    line_texts: list[str] = []
    token_plan: list[dict[str, Any]] = []

    def check(stable_id: str, text: str) -> list[int]:
        cleaned = cleaner(text)
        if not isinstance(cleaned, str) or cleaned != text:
            raise ValueError("OWSM-CTC text cleaner silently changed approved text")
        tokens = tokenizer.text2tokens(cleaned)
        if (
            not isinstance(tokens, (list, tuple))
            or not tokens
            or any(not isinstance(token, str) or not token or token == "<unk>" for token in tokens)
        ):
            raise ValueError("OWSM-CTC tokenizer produced empty or unknown tokens")
        ids = list(converter.tokens2ids(list(tokens)))
        if len(ids) != len(tokens) or any(type(value) is not int or value == unknown_id for value in ids):
            raise ValueError("OWSM-CTC token converter silently filtered approved text")
        if list(converter.ids2tokens(ids)) != list(tokens):
            raise ValueError("OWSM-CTC token conversion is not one-to-one")
        _framed(digest, stable_id)
        _framed(digest, json.dumps(ids, separators=(",", ":")))
        return ids

    for line in request["lines"]:
        words = [_spoken_text(word["acousticText"]) for word in line["words"]]
        line_text = " ".join(words)
        line_texts.append(line_text)
        line_ids = check(line["lineId"], line_text)
        concatenated: list[int] = []
        word_plan: list[dict[str, Any]] = []
        for word, word_text in zip(line["words"], words):
            word_ids = check(word["wordId"], word_text)
            word_plan.append(
                {
                    "wordId": word["wordId"],
                    "targetStart": len(concatenated),
                    "targetEnd": len(concatenated) + len(word_ids),
                }
            )
            concatenated.extend(word_ids)
            word_count += 1
        if concatenated != line_ids:
            raise ValueError("OWSM-CTC word tokenization differs from its approved line")
        token_count += len(line_ids)
        token_plan.append(
            {"lineId": line["lineId"], "tokenIds": line_ids, "words": word_plan}
        )
    return line_texts, token_plan, {
        "lineCount": len(line_texts),
        "wordCount": word_count,
        "tokenCount": token_count,
        "stableTokenizationSha256": digest.hexdigest(),
        "cleaner": f"{cleaner.__class__.__module__}.{cleaner.__class__.__qualname__}",
        "tokenizer": f"{tokenizer.__class__.__module__}.{tokenizer.__class__.__qualname__}",
        "converter": f"{converter.__class__.__module__}.{converter.__class__.__qualname__}",
    }


def _require_posterior_allocation(aligner, sample_count: int, *, retained_bytes: int) -> None:
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
    vocabulary = len(aligner.token_list)
    if vocabulary <= 0:
        raise ValueError('OWSM vocabulary is empty')
    rate = aligner.fs
    context = aligner.context_len_in_secs
    buffer_seconds = aligner.s2t_train_args.preprocessor_conf['speech_length']
    advance = int(rate*(buffer_seconds-2*context))
    if advance <= 0 or rate <= 0 or aligner.frames_per_sec <= 0:
        raise ValueError('OWSM buffer dimensions are invalid')
    # Bound padded batches, float32 concatenation and overlapping float64 scoring.
    buffers = math.ceil((sample_count+2*rate*context)/advance)
    frames = buffers*math.ceil(aligner.frames_per_sec*(buffer_seconds-2*context))
    pcm = buffers*int(rate*buffer_seconds)*8
    CURRENT_BUDGET.require_recording(retained_bytes+pcm+frames*vocabulary*16)


def _prepare_speech(audio: Path) -> tuple[Any, dict[str, Any]]:
    import numpy
    import soundfile
    import torch
    import torchaudio.functional

    info = _audio_info(audio)
    from stemwise_engine.acoustic_inputs import require_waveform_allocation
    require_waveform_allocation(audio, FROZEN_CONFIG['fs'], source_arrays=3,
                                target_arrays=3, recording=True)
    samples, sample_rate = soundfile.read(str(audio), dtype="float32", always_2d=True)
    if sample_rate != info.samplerate or samples.shape != (info.frames, info.channels):
        raise ValueError("OWSM-CTC decoded audio differs from its bound stream")
    from stemwise_engine.acoustic_inputs import mono_samples
    speech, representation = mono_samples(samples)
    if not numpy.isfinite(speech).all():
        raise ValueError("OWSM-CTC decoded audio contains non-finite samples")
    if sample_rate != FROZEN_CONFIG["fs"]:
        with torch.inference_mode():
            speech = (
                torchaudio.functional.resample(
                    torch.from_numpy(numpy.ascontiguousarray(speech)),
                    sample_rate,
                    FROZEN_CONFIG["fs"],
                    lowpass_filter_width=6,
                    rolloff=0.99,
                    resampling_method="sinc_interp_hann",
                    beta=None,
                )
                .cpu()
                .numpy()
                .astype(numpy.float32, copy=False)
            )
    if speech.ndim != 1 or speech.size == 0 or not numpy.isfinite(speech).all():
        raise ValueError("OWSM-CTC prepared audio is malformed")
    canonical = numpy.ascontiguousarray(speech, dtype="<f4")
    return canonical, {
        "method": FROZEN_CONFIG["channelMix"],
        "representation": representation,
        "sourceChannelCount": info.channels,
        "sourceSampleRate": info.samplerate,
        "sourceFrameCount": info.frames,
        "outputChannelCount": 1,
        "outputSampleRate": FROZEN_CONFIG["fs"],
        "outputSampleCount": int(canonical.size),
        "resampler": FROZEN_CONFIG["resampling"] if sample_rate != FROZEN_CONFIG["fs"] else None,
        "float32Sha256": hashlib.sha256(canonical.tobytes(order="C")).hexdigest(),
    }


def _lexical_records(
    lines: list[dict[str, Any]],
    token_plan: list[dict[str, Any]],
    spans: list[Any],
    frame_ms: float,
    duration_ms: int,
) -> list[dict[str, Any]]:
    expected_tokens = [
        token for plan in token_plan for token in plan["tokenIds"]
    ]
    if (
        len(lines) != len(token_plan)
        or len(spans) != len(expected_tokens)
        or not math.isfinite(frame_ms)
        or frame_ms <= 0.0
    ):
        raise ValueError("OWSM-CTC lexical span contract is malformed")
    result: list[dict[str, Any]] = []
    cursor = 0
    previous_end = 0.0
    for index, (line, plan) in enumerate(zip(lines, token_plan)):
        selected = spans[cursor : cursor + len(plan["tokenIds"])]
        if [int(span.token) for span in selected] != plan["tokenIds"]:
            raise RuntimeError("OWSM-CTC forced alignment changed the approved target")
        words: list[dict[str, Any]] = []
        for word in plan["words"]:
            word_spans = selected[word["targetStart"] : word["targetEnd"]]
            scores = [float(span.score) for span in word_spans]
            start_ms = int(float(word_spans[0].start) * frame_ms + 0.5)
            end_ms = int(float(word_spans[-1].end) * frame_ms + 0.5)
            if (
                not all(math.isfinite(value) for value in (*scores, start_ms, end_ms))
                or any(score > 0.000001 for score in scores)
                or not 0.0 <= start_ms < end_ms <= duration_ms
            ):
                raise ValueError("OWSM-CTC lexical word span is malformed")
            words.append(
                {
                    "wordId": word["wordId"],
                    "startMs": start_ms,
                    "endMs": end_ms,
                    "ctcLogScore": sum(scores) / len(scores),
                    "acousticTokenCount": len(scores),
                }
            )
        start_ms = words[0]["startMs"]
        end_ms = words[-1]["endMs"]
        if start_ms < previous_end:
            raise ValueError("OWSM-CTC lexical line spans overlap")
        token_count = sum(word["acousticTokenCount"] for word in words)
        result.append(
            {
                "lineIndex": index,
                "sourcePhysicalIndex": line["sourcePhysicalIndex"],
                "lineId": line["lineId"],
                "startMs": start_ms,
                "endMs": end_ms,
                "ctcLogScore": sum(
                    word["ctcLogScore"] * word["acousticTokenCount"] for word in words
                )
                / token_count,
                "acousticTokenCount": token_count,
                "words": words,
            }
        )
        previous_end = end_ms
        cursor += len(selected)
    return result




def _decode_lines(aligner, lpz, valid_speech_samples, line_texts, token_plan, lines, duration_ms):
    import numpy
    import torch
    import torchaudio

    if (
        not isinstance(lpz, numpy.ndarray)
        or lpz.ndim != 2
        or not lpz.size
        or not numpy.isfinite(lpz).all()
        or type(valid_speech_samples) not in {int, numpy.int32, numpy.int64}
        or int(valid_speech_samples) <= 0
    ):
        raise ValueError("OWSM-CTC posterior map is malformed")
    kaldi_lines = [f'{line["lineId"]} {text}' for line, text in zip(lines, line_texts)]
    task = aligner.prepare_segmentation_task(
        kaldi_lines, lpz, name="stemwise-shadow", speech_len=int(valid_speech_samples)
    )
    expected_ids = [line["lineId"] for line in lines]
    if (
        task.utt_ids != expected_ids
        or len(task.utt_begin_indices) != len(lines) + 1
        or any(
            list(
                task.ground_truth_mat[
                    task.utt_begin_indices[index] + 1 : task.utt_begin_indices[index + 1],
                    0,
                ]
            )
            != plan["tokenIds"]
            for index, plan in enumerate(token_plan)
        )
    ):
        raise ValueError("OWSM-CTC line identity changed during segmentation")
    targets = [token for plan in token_plan for token in plan["tokenIds"]]
    blank = int(task.config.blank)
    if not targets or blank in targets or max(targets) >= lpz.shape[1]:
        raise ValueError("OWSM-CTC lexical target is malformed")
    paths, scores = align_ctc(
        torch.from_numpy(lpz),
        targets,
        blank=blank,
        allow_trailing_audio=True,
    )
    spans = list(torchaudio.functional.merge_tokens(paths[0], scores[0], blank=blank))
    frame_ms = float(task.config.index_duration_in_seconds) * 1000.0
    return _lexical_records(lines, token_plan, spans, frame_ms, duration_ms)



def _atomic_json(path: Path, value: Any) -> None:
    from stemwise_engine.worker_publication import publish_json
    publish_json(path, value, sort_keys=True, separators=(",", ":"))

def run(request_path: Path, request_sha256: str, output_path: Path) -> None:
    if not request_path.is_absolute() or not output_path.is_absolute():
        raise ValueError("OWSM-CTC request and output paths must be absolute")
    raw = request_path.read_bytes()
    if _SHA256.fullmatch(request_sha256) is None or hashlib.sha256(raw).hexdigest() != request_sha256:
        raise ValueError("OWSM-CTC request content binding does not match")
    request = _validated_request(parse_worker_json(raw))
    request_path = request_path.resolve(strict=True)
    paths = _resolved_bindings(request)
    output_path = output_path.resolve(strict=False)
    protected_roots = (paths["modelRoot"], paths["align"].parents[2], paths["lock"].parent)
    if (
        output_path == request_path
        or output_path in paths.values()
        or any(output_path.is_relative_to(root) for root in protected_roots)
    ):
        raise ValueError("OWSM-CTC output would overwrite an input")
    runtime = _runtime_identity(request["runtime"])
    speech, preprocessing = _prepare_speech(paths["audio"])
    with _model_stage(paths, request["route"]) as aligner:
        line_texts, token_plan, preflight = _preflight(aligner, request)
        _require_posterior_allocation(aligner, len(speech), retained_bytes=speech.nbytes)
        lpz, valid_speech_samples = aligner.get_lpz(speech)
    lines = _decode_lines(aligner, lpz, valid_speech_samples, line_texts, token_plan,
                          request["lines"], request["durationMs"])
    _atomic_json(
        output_path,
        {
            "formatVersion": FORMAT_VERSION,
            "engine": ENGINE,
            "route": request["route"],
            "endpointSemantics": "first-to-last-approved-lexical-ctc-token-span-v1",
            "request": {"sha256": request_sha256, "byteCount": len(raw)},
            "durationMs": request["durationMs"],
            "audio": {key: request["audio"][key] for key in ("sha256", "byteCount")},
            "model": {
                "revision": MODEL_REVISION,
                "checkpointSha256": CHECKPOINT_SHA256,
                "configSha256": CONFIG_SHA256,
                "bpeSha256": BPE_SHA256,
                "statsSha256": STATS_SHA256,
            },
            "source": {
                "revision": ESPNET_SOURCE_REVISION,
                "alignSha256": ALIGN_SHA256,
                "helperSha256": HELPER_SHA256,
                "ctcSegmentationRevision": CTC_SEGMENTATION_REVISION,
            },
            "runtime": runtime,
            "runtimeFiles": {
                "espnetWheelSha256": ESPNET_WHEEL_SHA256,
                "lockSha256": RUNTIME_LOCK_SHA256,
            },
            "config": _route_config(request["route"]),
            "preflight": preflight,
            "preprocessing": preprocessing,
            "lineCount": len(lines),
            "lines": lines,
        },
    )


def _contrastive_structure_scores(logp, targets, blank):
    """Compare each complete order with its equal-length reverse-order control."""
    import stemwise_engine.endpoint_ctc_evidence as scorer
    scores = scorer._ctc_sequence_scores(logp,
        [*targets, *(list(reversed(target)) for target in targets)], blank, allow_edge_audio=True)
    raw, controls = scores[:len(targets)], scores[len(targets):]
    return raw, controls, [a-b for a, b in zip(raw, controls)]


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
            raise ValueError('OWSM stage deadline is invalid')
        with execution_deadline(arguments.timeout_seconds):
            run(arguments.request, arguments.request_sha256, arguments.output)
    except Exception:
        # The parent captures stderr into its bounded private failure diagnostic.
        import traceback
        traceback.print_exc(file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
