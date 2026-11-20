"""PC-only runtime and model provenance for reproducible enrichment runs.

Nothing produced here belongs in ``song.json``.  The phone contract intentionally
stays stable; this module supplies an auditable environment snapshot for
``pc-report.json`` and a canonical fingerprint that checkpoints can reference.
"""

from __future__ import annotations

from stemwise_engine.common import WorkerRetirementError

from functools import lru_cache
import hashlib
import ast
import importlib.metadata
import json
import os
import platform
import shutil
import sys
from pathlib import Path
from typing import Any

from stemwise_engine.analyzers import (
    ALLIN1_MODEL_REVISION,
    ALLIN1_MODEL_SHA256,
    BEAT_THIS_FINAL0_SHA256,
    HTDEMUCS_MODEL_REVISION,
    HTDEMUCS_MODEL_SHA256,
    SKEY_MODEL_SHA256,
)
from stemwise_engine.common import diagnostic_code, sha256_file, run_process_tree


PROVENANCE_VERSION = 2
MAIN_PACKAGES = (
    "yt-dlp",
    "numpy",
    "Pillow",
    "requests",
    "soundfile",
    "torch",
    "torchaudio",
    "scipy",
    "soxr",
    "nnAudio",
    "einops",
    "beat-this",
    "skey",
    "essentia",
)
ALLIN1_PACKAGES = (
    "allin1",
    "natten",
    "torch",
    "torchaudio",
    "numpy",
    "soundfile",
    "huggingface-hub",
    "librosa",
    "demucs",
    "madmom",
)


def file_identity(path: Path, *, expected_sha256: str | None = None) -> dict[str, Any]:
    """Describe a local artifact without making a model-quality claim."""

    record: dict[str, Any] = {"fileName": path.name, "available": path.is_file()}
    if expected_sha256 is not None:
        record["expectedSha256"] = expected_sha256.lower()
    if not path.is_file():
        return record
    resolved = path.resolve()
    digest = sha256_file(resolved)
    record.update(bytes=resolved.stat().st_size, sha256=digest)
    if expected_sha256 is not None:
        record["verified"] = digest == expected_sha256.lower()
    return record


def package_identity(name: str) -> dict[str, Any]:
    try:
        distribution = importlib.metadata.distribution(name)
    except importlib.metadata.PackageNotFoundError:
        return {"available": False}
    record: dict[str, Any] = {"available": True, "version": distribution.version}
    if name == "yt-dlp":
        # Record the downloader actually imported, not the untouched bootstrap wheel.
        from stemwise_engine.ytdlp_update import RUNTIME
        module = sys.modules.get("yt_dlp")
        if module is not None and str(getattr(module, "__file__", "")).startswith(str(RUNTIME) + "/"):
            record["version"] = module.version.__version__
    try:
        direct = distribution.read_text("direct_url.json")
        if direct:
            payload = json.loads(direct)
            source: dict[str, Any] = {}
            vcs = payload.get("vcs_info")
            if isinstance(vcs, dict):
                source.update(
                    vcs=vcs.get("vcs"),
                    commit=vcs.get("commit_id"),
                    requestedRevision=vcs.get("requested_revision"),
                )
            source = {key: value for key, value in source.items() if value}
            if source:
                record["source"] = source
    except Exception as error:
        record["sourceReadError"] = diagnostic_code(error)
    return record


def executable_identity(name: str) -> dict[str, Any]:
    executable = shutil.which(name)
    if not executable:
        return {"available": False}
    path = Path(executable).resolve()
    record = file_identity(path)
    try:
        completed = run_process_tree(
            [executable, "-version"],
            text=True,
            capture_output=True,
            check=False,
            timeout=15,
        )
        lines = [line.strip() for line in completed.stdout.splitlines() if line.strip()]
        record["returnCode"] = completed.returncode
        if lines:
            record["versionLine"] = lines[0]
    except WorkerRetirementError:
        raise
    except Exception as error:
        record["probeError"] = diagnostic_code(error)
    return record


def _main_model_identities() -> dict[str, Any]:
    torch_home = Path(
        os.environ.get("TORCH_HOME")
        or Path(os.environ.get("XDG_CACHE_HOME") or (Path.home() / ".cache")) / "torch"
    )
    beat_this = torch_home / "hub" / "checkpoints" / "beat_this-final0.ckpt"
    try:
        skey_dist = importlib.metadata.distribution("skey")
        skey = Path(skey_dist.locate_file("skey/models/skey.pt"))
    except importlib.metadata.PackageNotFoundError:
        skey = Path("skey/models/skey.pt")
    return {
        "beatThisFinal0": file_identity(
            beat_this, expected_sha256=BEAT_THIS_FINAL0_SHA256
        ),
        "skey": file_identity(skey, expected_sha256=SKEY_MODEL_SHA256),
    }


def _huggingface_hub_cache() -> Path:
    if os.environ.get("HF_HUB_CACHE"):
        return Path(os.environ["HF_HUB_CACHE"])
    if os.environ.get("HF_HOME"):
        return Path(os.environ["HF_HOME"]) / "hub"
    base = Path(os.environ.get("XDG_CACHE_HOME") or (Path.home() / ".cache"))
    return base / "huggingface" / "hub"


def _allin1_model_identity() -> dict[str, Any]:
    repository = _huggingface_hub_cache() / "models--taejunkim--allinone"
    snapshot = repository / "snapshots" / ALLIN1_MODEL_REVISION
    return {
        "repository": "taejunkim/allinone",
        "revision": ALLIN1_MODEL_REVISION,
        "files": {
            filename: file_identity(snapshot / filename, expected_sha256=expected)
            for filename, expected in sorted(ALLIN1_MODEL_SHA256.items())
        },
    }


def _htdemucs_model_identity() -> dict[str, Any]:
    repository = _huggingface_hub_cache() / "models--adefossez--HTDemucs"
    snapshot = repository / "snapshots" / HTDEMUCS_MODEL_REVISION
    return {
        "repository": "adefossez/HTDemucs",
        "revision": HTDEMUCS_MODEL_REVISION,
        "files": {
            filename: file_identity(snapshot / filename, expected_sha256=expected)
            for filename, expected in sorted(HTDEMUCS_MODEL_SHA256.items())
        },
    }


def _allin1_environment(python: Path | None) -> dict[str, Any]:
    if python is None or not python.is_file():
        return {
            "available": False,
            "pythonFileName": python.name if python else None,
        }
    try:
        completed = run_process_tree(
            [str(python), "-B", '-m', 'stemwise_engine.environment_probe', "packages", *ALLIN1_PACKAGES],
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
        if completed.returncode != 0:
            raise RuntimeError("All-In-One environment probe failed")
        record = json.loads(completed.stdout)
        record.update(available=True, executable=file_identity(python))
        return record
    except WorkerRetirementError:
        raise
    except Exception as error:
        return {
            "available": False,
            "pythonFileName": python.name,
            "error": diagnostic_code(error),
        }


def semantic_source_identity(path: Path, names=None, *, portable=False) -> str:
    """Bind executable Python structure; keep full file provenance separately."""
    text = path.read_text(encoding='utf-8')
    if path.suffix != '.py':
        return hashlib.sha256(text.encode('utf-8')).hexdigest()
    tree = ast.parse(text)
    if names is not None:
        definitions = {node.name: node for node in tree.body
                       if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))}
        tree.body = [definitions[name] for name in names]
    for node in ast.walk(tree):
        if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            if (node.body and isinstance(node.body[0], ast.Expr)
                    and isinstance(node.body[0].value, ast.Constant)
                    and isinstance(node.body[0].value.value, str)):
                node.body.pop(0)
    if portable:
        import inspect
        for node in ast.walk(tree):
            if getattr(node, 'type_params', None) == []:
                delattr(node, 'type_params')
        options = {'show_empty': True} if 'show_empty' in inspect.signature(ast.dump).parameters else {}
        return hashlib.sha256(ast.dump(tree, include_attributes=False, **options).encode('utf-8')).hexdigest()
    return hashlib.sha256(ast.dump(tree, include_attributes=False).encode('utf-8')).hexdigest()


def content_assessment_identity():
    """Comparison/revision policy identity, separate from immutable acoustic producers."""
    source = Path(__file__).resolve().parent
    return {'lexicalPolicy': semantic_source_identity(source / 'lyrics_tokenization.py'),
        'comparisonPolicy': semantic_source_identity(source / 'lyric_comparison.py'),
        'sourceRecordingPolicy': semantic_source_identity(source / 'source_recording_content.py'),
        'physicalLinePolicy': semantic_source_identity(source / 'lyrics_normalize.py', ('physical_lines',)),
        'contentPolicy': semantic_source_identity(source / 'lyric_content.py'),
        'spokenTargetPolicy': semantic_source_identity(source / 'spoken_targets.py'),
        'revisionPolicy': semantic_source_identity(source / 'lyrics_alignment.py', ('build_revision_mapping',))}


CTC_SOURCE_FILES = ('ctc_worker.py', 'acoustic_inputs.py', 'worker_protocol.py', 'worker_publication.py')


# Numerical source sets exclude attempt state, publication, diagnostics and unrelated models.
ENDPOINT_SHARED_SOURCES = ('acoustic_inputs.py', 'ctc_numerics.py', 'source_crop.py',
    'lyrics_tokenization.py', 'lyrics_unicode.py', 'lyrics_normalize.py')
ENDPOINT_PRODUCERS = {
    'owsm': (('endpoint_owsm_ctc.py', 'owsm_ctc_forced_alignment.py'),
        ('owsm_ctc_forced_alignment',), ('owsm_ctc_forced_alignment',), ()),
    'native': (('endpoint_native_ctc.py', 'nemo_nfa_forced_alignment.py'),
        ('nemo_nfa_forced_alignment',), ('nemo_nfa_forced_alignment',), ()),
    'ctc': (('endpoint_ctc_evidence.py', 'wav2vec2_forced_alignment.py', 'ctc_worker.py'),
        ('lyrics_align_pc', 'endpoint_candidate_prediction'), ('main',), ('main',)),
    'mms': (('endpoint_mms_prediction.py', 'mms_forced_alignment.py', 'ctc_worker.py'),
        ('lyrics_align_pc',), ('main',), ('main',)),
}


@lru_cache(maxsize=8)
def endpoint_producer_identity(family=None):
    from stemwise_engine.environment_locks import dependency_identity
    from stemwise_engine.model_registry import model_assets, runtime_profile
    if family is None:
        return {name: endpoint_producer_identity(name) for name in ENDPOINT_PRODUCERS}
    files, assets, profiles, environments = ENDPOINT_PRODUCERS[family]
    source = Path(__file__).resolve().parent
    identities = {name: semantic_source_identity(source/name) for name in (*ENDPOINT_SHARED_SOURCES, *files)}
    # Shared CTC recursion is used by all decoders, without its unrelated orchestration.
    identities['ctcBoundaries'] = semantic_source_identity(source/'endpoint_ctc_evidence.py',
        ('_validated_ctc_emissions', '_validate_ctc_target', '_ctc_trellis_validated', '_ctc_forward_validated',
         '_ctc_trellis', '_ctc_forward', '_ctc_sequence_scores', 'boundary_distributions', 'select_candidate'))
    if family == 'owsm':
        identities['sharedDecoder'] = semantic_source_identity(source/'endpoint_mms_prediction.py',
            ('decode', '_posterior_timings', '_minimum_risk_timings'))
    identities['source_duration_ms'] = semantic_source_identity(source/'common.py', ('source_duration_ms',))
    identities['vocalPcmReader'] = semantic_source_identity(source/'endpoint_vocal_evidence.py', ('_vocal_samples',))
    if family == 'mms':
        identities['vocalFusion'] = semantic_source_identity(source/'endpoint_owsm_ctc.py',
            ('fuse_emissions', '_language_emissions', '_language_score', 'decode_neighborhoods', 'infer_fused_views'))
        identities['contrastiveScoring'] = semantic_source_identity(source/'owsm_ctc_forced_alignment.py',
            ('_contrastive_structure_scores',))
    numerical_assets = {name: model_assets(name) for name in assets}
    if family in ('owsm', 'mms', 'ctc'):
        identities['boundarySummary'] = semantic_source_identity(source/'endpoint_candidate_prediction.py',
            ('PhrasePredictionPolicy', 'phrase_prediction_policy', 'summarize_boundary_curves'))
        numerical_assets['boundarySummaryPolicy'] = model_assets('endpoint_candidate_prediction')['phrasePolicy']
    runtime = {'profiles': {name: runtime_profile(name) for name in profiles}}
    if environments:
        runtime['dependencies'] = dependency_identity(source.parent, environments=environments, profiles=())
    return json.loads(json.dumps({'source': identities, 'runtime': runtime,
            'assets': numerical_assets}))


ACOUSTIC_SOURCE_FILES = ('analysis_numerics.py', 'analyzers.py', 'analysis_resources.py',
    'analysis-resource-policy.json', 'waveform.py', 'allin1_worker.py', 'allin1_demix_worker.py')


def numerical_source_identity() -> dict[str, str]:
    from stemwise_engine.model_registry import model_assets
    from stemwise_engine.original_delivery import STRICT_DECODE_OPTIONS
    from stemwise_engine.youtube import MAX_DURATION_MS, MAX_ANALYSIS_WAV_BYTES
    from stemwise_engine.contract import WAVEFORM_ENCODING, WAVEFORM_LEVELS_MS
    source = Path(__file__).resolve().parent
    sources = {name: semantic_source_identity(source/name)
               for name in ACOUSTIC_SOURCE_FILES}
    sources['modelAssets'] = hashlib.sha256(json.dumps(model_assets('analyzers'), sort_keys=True,
        separators=(',', ':')).encode()).hexdigest()
    sources['decodePolicy'] = hashlib.sha256(json.dumps({
        'ffmpegOptions': STRICT_DECODE_OPTIONS, 'maximumDurationMs': MAX_DURATION_MS,
        'maximumWavBytes': MAX_ANALYSIS_WAV_BYTES}, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    sources['waveformPolicy'] = hashlib.sha256(json.dumps({
        'encoding': WAVEFORM_ENCODING, 'levelsMs': sorted(WAVEFORM_LEVELS_MS)},
        sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    for filename, names in [('youtube.py', ('decode_analysis_audio', 'probe_duration_ms')),
                            ('common.py', ('source_duration_ms',))]:
        for name in names:
            sources[name] = semantic_source_identity(source/filename, (name,))
    return sources


def collect_provenance(*, allin1_python: Path | None = None, observe_execution=False) -> dict[str, Any]:
    """Collect a timestamp-free snapshot so its canonical hash is stable."""

    try:
        import soundfile

        libsndfile = getattr(soundfile, "__libsndfile_version__", None)
    except Exception as error:
        libsndfile = f"unavailable:{diagnostic_code(error)}"
    models = _main_model_identities()
    models["allInOneHarmonixAll"] = _allin1_model_identity()
    models["allInOneHtdemucs"] = _htdemucs_model_identity()
    return {
        "provenanceVersion": PROVENANCE_VERSION,
        'numericalProducer': numerical_source_identity(),
        'acousticRuntime': collect_acoustic_provenance(allin1_python=allin1_python, observe_execution=observe_execution),
        'sourceProvenance': {path.name: sha256_file(path) for path in sorted(Path(__file__).parent.glob('*.py'))},
        "runtime": {
            "python": sys.version.split()[0],
            "implementation": platform.python_implementation(),
            "platform": platform.platform(),
            "machine": platform.machine(),
        },
        "packages": {name: package_identity(name) for name in MAIN_PACKAGES},
        "allInOneEnvironment": _allin1_environment(allin1_python),
        "decoders": {
            "analysisAudio": "FFmpeg decode to PCM float32 WAV",
            "skeyAudio": "torchaudio soundfile backend",
            "waveformAudio": "python-soundfile/libsndfile",
            "essentiaAudio": "Essentia MonoLoader and AudioLoader",
            "allInOneAudio": "All-In-One 1.1 audio feature pipeline over the decoded WAV",
            "libsndfileVersion": libsndfile,
            "ffmpeg": executable_identity("ffmpeg"),
            "ffprobe": executable_identity("ffprobe"),
        },
        "models": models,
    }


def numerical_execution_identity(torch, device):
    """Observe the same selected device used by the numerical model."""
    device = torch.device(device)
    value = {'device': device.type, 'torch': str(torch.__version__)}
    if device.type == 'cpu':
        return value
    if device.type != 'cuda':
        raise ValueError('Unsupported acoustic execution device')
    index = device.index if device.index is not None else torch.cuda.current_device()
    properties = torch.cuda.get_device_properties(index)
    gpu_uuid = str(properties.uuid)
    if not gpu_uuid.startswith('GPU-'):
        gpu_uuid = 'GPU-' + gpu_uuid
    driver = run_process_tree(['nvidia-smi', '--query-gpu=driver_version', '--format=csv,noheader',
        '--id='+gpu_uuid], capture_output=True, text=True, check=True, timeout=15).stdout.strip()
    if not driver:
        raise ValueError('CUDA driver identity is unavailable')
    value.update(cudaRuntime=str(torch.version.cuda), cudnn=str(torch.backends.cudnn.version()),
        driver=driver, uuid=gpu_uuid, name=properties.name,
        capability=list(torch.cuda.get_device_capability(index)), totalMemoryBytes=properties.total_memory)
    return value


def observe_numerical_execution(python):
    # A short owned worker observes CUDA without initializing it in status/inspection callers.
    script = ('import json,torch; from stemwise_engine.provenance import numerical_execution_identity; '
              'print(json.dumps(numerical_execution_identity(torch,"cuda" if torch.cuda.is_available() else "cpu")))')
    result = run_process_tree([str(python), '-B', '-c', script], capture_output=True,
        text=True, check=True, timeout=30)
    value = json.loads(result.stdout)
    if not isinstance(value, dict) or value.get('device') not in {'cpu', 'cuda'}:
        raise ValueError('Acoustic execution identity is invalid')
    return value


def collect_acoustic_provenance(*, allin1_python: Path | None = None, observe_execution=False) -> dict[str, Any]:
    """Identity of the acoustic producer, without rehashing immutable model weights.

    Analyzer adapters verify these content pins before accepting a new result.
    Reusing a completed result needs the producer's pins and runtime, rather than
    the current presence or location of several gigabytes of already-used weights.
    """
    import soundfile
    allin1 = _allin1_environment(allin1_python)
    if isinstance(allin1.get('packages'), dict):
        allin1 = {**allin1, 'packages': {name: value for name, value in allin1['packages'].items()
                                      if name != 'huggingface-hub'}}
    return {
        **({'execution': {'main': observe_numerical_execution(sys.executable)}}
           if observe_execution else {}),
        "runtime": {"python": sys.version.split()[0],
                    "implementation": platform.python_implementation(),
                    "platform": platform.platform(), "machine": platform.machine()},
        "packages": {name: package_identity(name) for name in MAIN_PACKAGES
                     if name not in {"yt-dlp", "Pillow", "requests"}},
        "allInOneEnvironment": allin1,
        "decoders": {"libsndfileVersion": soundfile.__libsndfile_version__,
                     "ffmpeg": executable_identity("ffmpeg"),
                     "ffprobe": executable_identity("ffprobe")},
        "models": {"beatThisFinal0": BEAT_THIS_FINAL0_SHA256, "skey": SKEY_MODEL_SHA256,
                   "allInOne": {"revision": ALLIN1_MODEL_REVISION, "files": dict(ALLIN1_MODEL_SHA256)},
                   "htdemucs": {"revision": HTDEMUCS_MODEL_REVISION, "files": dict(HTDEMUCS_MODEL_SHA256)}},
    }


def provenance_fingerprint(provenance: dict[str, Any]) -> str:
    if 'numericalProducer' in provenance and 'acousticRuntime' in provenance:
        provenance = {key: provenance[key] for key in ('numericalProducer', 'acousticRuntime')}
    encoded = json.dumps(
        {key: value for key, value in provenance.items() if key != 'sourceProvenance'},
        ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
