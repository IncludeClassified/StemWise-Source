"""Exact alignment audio identity, time transforms and retained separation publication."""
from __future__ import annotations

from stemwise_engine.common import WorkerTemporaryDirectory, WorkerRetirementError, raise_with_cleanup_failure
from stemwise_engine.provenance import semantic_source_identity
from fractions import Fraction
import stemwise_engine.worker_protocol as worker_protocol
import math
import re
from pathlib import Path
from typing import Any
from stemwise_engine.common import run_process_tree, sha256_file, atomic_write_json
from stemwise_engine.worker_protocol import parse_worker_json, read_worker_json
from stemwise_engine.alignment_phases import AudioIdentity
from stemwise_engine.ctc_worker import source_duration_ms
from stemwise_engine.lyrics_alignment import AlignmentWorkspace
import stemwise_engine.alignment_models as models
import stemwise_engine.alignment_endpoints as endpoints

AUDIO_PROVENANCE_VERSION = 3


ROFORMER_RESULT_VERSION = 1


ROFORMER_RECONSTRUCTION_KEYS = frozenset(
    {
        "chunkSizeFrames",
        "numOverlap",
        "stepFrames",
        "fadeFrames",
        "leftReflectPadFrames",
        "rightReflectPadFrames",
        "modelInputFrameCount",
        "chunkCount",
        "lastChunkSourceFrames",
        "lastChunkPadFrames",
        "lastChunkReflectPad",
        "leftTrimFrames",
        "rightTrimFrames",
        "modelOutputFrameCount",
    }
)


def audio_stream(path: Path) -> dict[str, int]:
    completed = run_process_tree(
        [
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=sample_rate,channels,duration_ts,time_base",
            "-of",
            "json",
            str(path),
        ],
        text=True,
        capture_output=True,
        check=False,
        timeout=120,
    )
    if completed.returncode != 0:
        raise RuntimeError("alignment audio stream could not be inspected")
    try:
        streams = parse_worker_json(completed.stdout).get("streams")
        stream = streams[0]
        sample_rate = int(stream["sample_rate"])
        channels = int(stream["channels"])
        duration_ts = int(stream["duration_ts"])
        time_base = Fraction(stream["time_base"])
        frame_count = Fraction(duration_ts) * time_base * sample_rate
    except (IndexError, KeyError, TypeError, ValueError, ZeroDivisionError) as error:
        raise RuntimeError("alignment audio stream identity is incomplete") from error
    if (
        sample_rate <= 0
        or channels <= 0
        or duration_ts <= 0
        or frame_count.denominator != 1
        or frame_count <= 0
    ):
        raise RuntimeError("alignment audio has no exact positive sample timeline")
    frames = int(frame_count)
    return {
        "sampleRate": sample_rate,
        "channelCount": channels,
        "frameCount": frames,
        "durationMs": source_duration_ms(frames, sample_rate),
    }


def file_identity(path: Path) -> dict[str, Any]:
    stream = audio_stream(path)
    return AudioIdentity(sha256_file(path), path.stat().st_size, stream['sampleRate'],
                         stream['channelCount'], stream['frameCount']).to_record()


def identity_transform(stream: dict[str, int]) -> dict[str, Any]:
    return {
        "inputSampleRate": stream["sampleRate"],
        "outputSampleRate": stream["sampleRate"],
        "inputChannelCount": stream["channelCount"],
        "outputChannelCount": stream["channelCount"],
        "inputFrameCount": stream["frameCount"],
        "outputFrameCount": stream["frameCount"],
        "resamplingRatioNumerator": 1,
        "resamplingRatioDenominator": 1,
        "offsetFrames": 0,
        "trimFrames": 0,
        "padFrames": 0,
        "separatorLatencyFrames": 0,
        "measuredResidualFrames": 0,
        "measuredResidualMs": 0.0,
    }


def demucs_transform(
    mix_stream: dict[str, int], vocals_stream: dict[str, int]
) -> dict[str, Any]:
    ratio = Fraction(vocals_stream["sampleRate"], mix_stream["sampleRate"])
    expected = Fraction(mix_stream["frameCount"]) * ratio
    expected_frames = (expected.numerator * 2 + expected.denominator) // (
        expected.denominator * 2
    )
    residual = vocals_stream["frameCount"] - expected_frames
    if abs(residual) > max(1, vocals_stream["sampleRate"] // 1000):
        raise RuntimeError("Demucs output has a material canonical-time length residual")
    return {
        "inputSampleRate": mix_stream["sampleRate"],
        "outputSampleRate": vocals_stream["sampleRate"],
        "inputChannelCount": mix_stream["channelCount"],
        "outputChannelCount": vocals_stream["channelCount"],
        "inputFrameCount": mix_stream["frameCount"],
        "outputFrameCount": vocals_stream["frameCount"],
        "expectedOutputFrameCount": expected_frames,
        "resamplingRatioNumerator": ratio.numerator,
        "resamplingRatioDenominator": ratio.denominator,
        "offsetFrames": 0,
        "trimFrames": 0,
        "padFrames": 0,
        "separatorLatencyFrames": 0,
        "separatorLatencyStatus": "zero-by-offline-index-contract",
        "measuredResidualFrames": residual,
        "measuredResidualMs": residual * 1000.0 / vocals_stream["sampleRate"],
    }


def roformer_transform(
    mix_stream: dict[str, int],
    source_stream: dict[str, int],
    output_stream: dict[str, int],
    stage_transform: dict[str, Any],
) -> dict[str, Any]:
    stage_keys = {
        "sourceSampleRate",
        "sourceChannelCount",
        "sourceFrameCount",
        "targetSampleRate",
        "targetChannelCount",
        "targetFrameCount",
        "expectedTargetFrameCount",
        "resamplingRatioNumerator",
        "resamplingRatioDenominator",
        "measuredResidualFrames",
        "measuredResidualMs",
        "indexOffsetTargetFrames",
        "separatorLatencyFrames",
        "separatorLatencyStatus",
        "chunkOverlapReconstruction",
    }
    reconstruction_keys = ROFORMER_RECONSTRUCTION_KEYS
    reconstruction = stage_transform.get("chunkOverlapReconstruction")
    stage_ratio = Fraction(output_stream["sampleRate"], source_stream["sampleRate"])
    stage_expected = Fraction(source_stream["frameCount"]) * stage_ratio
    stage_expected_frames = (stage_expected.numerator * 2 + stage_expected.denominator) // (
        stage_expected.denominator * 2
    )
    stage_residual = output_stream["frameCount"] - stage_expected_frames
    if (
        set(stage_transform) != stage_keys
        or not isinstance(reconstruction, dict)
        or set(reconstruction) != reconstruction_keys
        or stage_transform["sourceSampleRate"] != source_stream["sampleRate"]
        or stage_transform["sourceChannelCount"] != source_stream["channelCount"]
        or stage_transform["sourceFrameCount"] != source_stream["frameCount"]
        or stage_transform["targetSampleRate"] != output_stream["sampleRate"]
        or stage_transform["targetChannelCount"] != output_stream["channelCount"]
        or stage_transform["targetFrameCount"] != output_stream["frameCount"]
        or stage_transform["expectedTargetFrameCount"] != stage_expected_frames
        or stage_transform["resamplingRatioNumerator"] != stage_ratio.numerator
        or stage_transform["resamplingRatioDenominator"] != stage_ratio.denominator
        or stage_transform["measuredResidualFrames"] != stage_residual
        or type(stage_transform["measuredResidualMs"]) not in {int, float}
        or not math.isfinite(float(stage_transform["measuredResidualMs"]))
        or abs(
            float(stage_transform["measuredResidualMs"])
            - stage_residual * 1000.0 / output_stream["sampleRate"]
        )
        > 1e-9
        or abs(float(stage_transform["measuredResidualMs"])) > 1.0
        or stage_transform["indexOffsetTargetFrames"] != 0
        or stage_transform["separatorLatencyFrames"] != 0
        or stage_transform["separatorLatencyStatus"]
        != "zero-by-offline-index-contract"
        or any(type(value) is not int or value < 0 for value in reconstruction.values())
        or reconstruction["chunkSizeFrames"] <= 0
        or reconstruction["numOverlap"] <= 0
        or reconstruction["stepFrames"] <= 0
        or reconstruction["chunkCount"] <= 0
        or reconstruction["lastChunkSourceFrames"] <= 0
        or reconstruction["lastChunkSourceFrames"] > reconstruction["chunkSizeFrames"]
        or reconstruction["lastChunkPadFrames"]
        != reconstruction["chunkSizeFrames"] - reconstruction["lastChunkSourceFrames"]
        or reconstruction["lastChunkReflectPad"] not in {0, 1}
        or reconstruction["lastChunkReflectPad"]
        != int(
            reconstruction["lastChunkPadFrames"] > 0
            and reconstruction["lastChunkSourceFrames"]
            > reconstruction["chunkSizeFrames"] // 2 + 1
        )
        or reconstruction["modelOutputFrameCount"] != output_stream["frameCount"]
    ):
        raise ValueError("RoFormer stage canonical-time transform is invalid")
    ratio = Fraction(output_stream["sampleRate"], mix_stream["sampleRate"])
    expected = Fraction(mix_stream["frameCount"]) * ratio
    expected_frames = (expected.numerator * 2 + expected.denominator) // (
        expected.denominator * 2
    )
    residual = output_stream["frameCount"] - expected_frames
    residual_ms = residual * 1000.0 / output_stream["sampleRate"]
    if abs(residual_ms) > 1.0:
        raise RuntimeError("RoFormer output has a material canonical-time residual")
    return {
        "inputSampleRate": mix_stream["sampleRate"],
        "outputSampleRate": output_stream["sampleRate"],
        "inputChannelCount": mix_stream["channelCount"],
        "outputChannelCount": output_stream["channelCount"],
        "inputFrameCount": mix_stream["frameCount"],
        "outputFrameCount": output_stream["frameCount"],
        "expectedOutputFrameCount": expected_frames,
        "resamplingRatioNumerator": ratio.numerator,
        "resamplingRatioDenominator": ratio.denominator,
        "offsetFrames": 0,
        "trimFrames": 0,
        "padFrames": 0,
        "separatorLatencyFrames": 0,
        "separatorLatencyStatus": "zero-by-offline-index-contract",
        "measuredResidualFrames": residual,
        "measuredResidualMs": residual_ms,
        "stageTimeTransform": stage_transform,
    }


def read_audio_provenance(
    workspace: AlignmentWorkspace,
    video_id: str,
    duration_ms: int,
    *,
    expected_mix_sha256: str | None = None,
    expected_producer_runtime: dict[str, Any] | None = None,
) -> dict[str, Any] | None:
    path = workspace.audio_provenance_path(video_id)
    if not path.is_file():
        return None
    try:
        value = read_worker_json(path)
    except Exception:
        return None
    def valid_identity(identity: Any) -> bool:
        stream = identity.get("stream") if isinstance(identity, dict) else None
        return (
            isinstance(identity, dict)
            and re.fullmatch(r"[0-9a-f]{64}", str(identity.get("sha256") or ""))
            is not None
            and type(identity.get("byteCount")) is int
            and identity["byteCount"] > 0
            and isinstance(stream, dict)
            and set(stream) == {"sampleRate", "channelCount", "frameCount", "durationMs"}
            and all(type(stream[key]) is int and stream[key] > 0 for key in stream)
            and stream["durationMs"]
            == (stream["frameCount"] * 1000 + stream["sampleRate"] // 2)
            // stream["sampleRate"]
            and isinstance(identity.get("canonicalTimeTransform"), dict)
        )

    if (
        not isinstance(value, dict)
        or set(value) != {
            "provenanceVersion",
            "videoId",
            "durationMs",
            "mix",
            "audioViews",
        }
        or value.get("provenanceVersion") != AUDIO_PROVENANCE_VERSION
        or value.get("videoId") != video_id
        or value.get("durationMs") != duration_ms
        or not valid_identity(value.get("mix"))
        or not isinstance(value.get("audioViews"), dict)
        or not set(value["audioViews"]).issubset(
            {
                "htdemucs-vocals",
                "kimberley-jensen-roformer-vocals",
                "sucial-v2-roformer-dry-vocals",
            }
        )
        or (
            expected_mix_sha256 is not None
            and value["mix"].get("sha256") != expected_mix_sha256
        )
    ):
        return None
    if value["mix"]["canonicalTimeTransform"] != identity_transform(
        value["mix"]["stream"]
    ):
        return None
    views = value["audioViews"]
    if expected_producer_runtime is not None and any(
        not isinstance(view, dict) or view.get('producerRuntime') != expected_producer_runtime
        for view in views.values()
    ):
        return None
    htdemucs = views.get("htdemucs-vocals")
    if htdemucs is not None:
        expected_keys = {
            "sha256",
            "byteCount",
            "stream",
            "canonicalTimeTransform",
            "routeId",
            "familyId",
            "sourceViewId",
            "sourceAudioSha256",
            "demucsModelSha256",
            "model",
            "parameters",
        }
        if isinstance(htdemucs, dict) and 'producerRuntime' in htdemucs:
            expected_keys.add('producerRuntime')
        if (
            not valid_identity(htdemucs)
            or set(htdemucs) != expected_keys
            or htdemucs.get("routeId") != "htdemucs-vocals"
            or htdemucs.get("familyId") != "htdemucs-vocals"
            or htdemucs.get("sourceViewId") != "mix"
            or htdemucs.get("sourceAudioSha256") != value["mix"].get("sha256")
            or not re.fullmatch(
                r"[0-9a-f]{64}", str(htdemucs.get("demucsModelSha256") or "")
            )
            or htdemucs.get("model") != "htdemucs"
            or htdemucs.get("parameters") != {"shifts": 0, "twoStems": "vocals"}
        ):
            return None
        try:
            expected_vocals_transform = demucs_transform(
                value["mix"]["stream"], htdemucs["stream"]
            )
        except (KeyError, RuntimeError, TypeError, ValueError, ZeroDivisionError):
            return None
        if htdemucs["canonicalTimeTransform"] != expected_vocals_transform:
            return None
    for route_id in (
        "kimberley-jensen-roformer-vocals",
        "sucial-v2-roformer-dry-vocals",
    ):
        route = views.get(route_id)
        if route is None:
            continue
        expected_keys = {
            "sha256",
            "byteCount",
            "stream",
            "canonicalTimeTransform",
            "routeId",
            "familyId",
            "sourceViewId",
            "sourceAudioSha256",
            "checkpointSha256",
            "configSha256",
            "packageVersion",
            "packageWheelSha256",
            "packageSourceManifestSha256",
            "packageSourceCommit",
            "assetRepository",
            "assetRevision",
            "helperSha256",
            "runtime",
            "device",
            "parameters",
            "stageMetrics",
        }
        if isinstance(route, dict) and 'producerRuntime' in route:
            expected_keys.add('producerRuntime')
        definition = endpoints.ROFORMER_ROUTES[route_id]
        runtime = route.get("runtime") if isinstance(route, dict) else None
        parameters = route.get("parameters") if isinstance(route, dict) else None
        stage_metrics = route.get("stageMetrics") if isinstance(route, dict) else None
        expected_runtime = models.roformer_runtime_pins(Path(__file__).resolve().parent.parent)
        expected_package = {
            "version": models.ROFORMER_PACKAGE_VERSION,
            "wheelSha256": models.ROFORMER_PACKAGE_WHEEL_SHA256,
            "sourceManifestSha256": models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
            "sourceCommit": models.ROFORMER_PACKAGE_SOURCE_COMMIT,
        }
        runtime_keys = {
            "runtimeVersion",
            "supportedResultVersions",
            "python",
            "implementation",
            "platform",
            "machine",
            "package",
            "packages",
            "requestedDevice",
            "resolvedDevice",
            "cudaAvailable",
            "cudaDeviceName",
            "cudaRuntime",
            "cudnn",
        }
        parameter_keys = {
            "deterministicAlgorithms",
            "cudnnBenchmark",
            "cudnnDeterministic",
            "modelSampleRate",
            *ROFORMER_RECONSTRUCTION_KEYS,
        }
        metric_keys = {
            "preprocessingRuntimeMs",
            "modelLoadRuntimeMs",
            "inferenceRuntimeMs",
            "writeRuntimeMs",
            "runtimeMs",
        }
        source = (
            value["mix"]
            if definition["sourceViewId"] == "mix"
            else views.get(definition["sourceViewId"])
        )
        if (
            source is None
            or not valid_identity(route)
            or set(route) != expected_keys
            or route.get("routeId") != route_id
            or route.get("familyId") != definition["familyId"]
            or route.get("sourceViewId") != definition["sourceViewId"]
            or route.get("sourceAudioSha256") != source.get("sha256")
            or route.get("checkpointSha256") != definition["checkpointSha256"]
            or route.get("configSha256") != definition["configSha256"]
            or route.get("packageVersion") != models.ROFORMER_PACKAGE_VERSION
            or route.get("packageWheelSha256") != models.ROFORMER_PACKAGE_WHEEL_SHA256
            or route.get("packageSourceManifestSha256")
            != models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256
            or route.get("packageSourceCommit") != models.ROFORMER_PACKAGE_SOURCE_COMMIT
            or route.get("assetRepository") != definition["assetRepository"]
            or route.get("assetRevision") != definition["assetRevision"]
            or route.get("helperSha256") != semantic_source_identity(Path(__file__).resolve().with_name("roformer_separation.py"))
            or not isinstance(runtime, dict)
            or set(runtime) != runtime_keys
            or runtime.get("runtimeVersion") != ROFORMER_RESULT_VERSION
            or runtime.get("supportedResultVersions") != [ROFORMER_RESULT_VERSION]
            or runtime.get("python") != expected_runtime["python"]
            or runtime.get("packages") != expected_runtime["packages"]
            or runtime.get("package") != expected_package
            or route.get("device") not in {"cpu", "cuda"}
            or runtime.get("requestedDevice") != route.get("device")
            or runtime.get("resolvedDevice") != route.get("device")
            or (route.get("device") == "cuda" and runtime.get("cudaAvailable") is not True)
            or not isinstance(parameters, dict)
            or set(parameters) != parameter_keys
            or parameters.get("deterministicAlgorithms") is not True
            or parameters.get("cudnnBenchmark") is not False
            or parameters.get("cudnnDeterministic") is not True
            or parameters.get("modelSampleRate") != route["stream"]["sampleRate"]
            or any(
                parameters.get(key)
                != route["canonicalTimeTransform"]
                .get("stageTimeTransform", {})
                .get("chunkOverlapReconstruction", {})
                .get(key)
                for key in ROFORMER_RECONSTRUCTION_KEYS
            )
            or not isinstance(stage_metrics, dict)
            or set(stage_metrics) - {"peakCudaMemoryBytes"} != metric_keys
            or (
                ("peakCudaMemoryBytes" in stage_metrics)
                != (route.get("device") == "cuda")
            )
            or any(type(metric) is not int or metric < 0 for metric in stage_metrics.values())
        ):
            return None
        try:
            transform = roformer_transform(
                value["mix"]["stream"],
                source["stream"],
                route["stream"],
                route["canonicalTimeTransform"]["stageTimeTransform"],
            )
        except (KeyError, RuntimeError, TypeError, ValueError, ZeroDivisionError):
            return None
        if route["canonicalTimeTransform"] != transform:
            return None
    return value


def write_audio_provenance(
    workspace: AlignmentWorkspace,
    video_id: str,
    *,
    duration_ms: int,
    mix_audio: Path,
    vocals_audio: Path | None = None,
    demucs_model_sha256: str | None = None,
    expected_mix_sha256: str | None = None,
    producer_runtime: dict[str, Any] | None = None,
) -> dict[str, Any]:
    mix = file_identity(mix_audio)
    if expected_mix_sha256 is not None and mix["sha256"] != expected_mix_sha256:
        raise RuntimeError("alignment audio does not match completed analysis audio")
    if (
        mix["stream"]["sampleRate"] != 48_000
        or mix["stream"]["channelCount"] != 2
        or abs(mix["stream"]["durationMs"] - duration_ms) > 1
    ):
        raise RuntimeError("alignment audio differs from the canonical PCM format or duration")
    mix["canonicalTimeTransform"] = identity_transform(mix["stream"])
    prior = read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    ) or {}
    views = (
        dict(prior.get("audioViews", {}))
        if prior.get("mix") == mix and isinstance(prior.get("audioViews"), dict)
        else {}
    )
    value: dict[str, Any] = {
        "provenanceVersion": AUDIO_PROVENANCE_VERSION,
        "videoId": video_id,
        "durationMs": duration_ms,
        "mix": mix,
        "audioViews": views,
    }
    if vocals_audio is not None:
        if not demucs_model_sha256:
            raise ValueError("Demucs model identity is required for isolated vocals")
        vocals = file_identity(vocals_audio)
        vocals["canonicalTimeTransform"] = demucs_transform(
            mix["stream"], vocals["stream"]
        )
        value["audioViews"]["htdemucs-vocals"] = {
            **vocals,
            "routeId": "htdemucs-vocals",
            "familyId": "htdemucs-vocals",
            "sourceViewId": "mix",
            "sourceAudioSha256": mix["sha256"],
            "demucsModelSha256": demucs_model_sha256,
            "model": "htdemucs",
            "parameters": {"shifts": 0, "twoStems": "vocals"},
            **({'producerRuntime': producer_runtime} if producer_runtime is not None else {}),
        }
    atomic_write_json(workspace.audio_provenance_path(video_id), value)
    return value


def invalidate_downstream_roformer_views(
    views: dict[str, Any], route_id: str
) -> None:
    stale = {route_id}
    for candidate_id, definition in endpoints.ROFORMER_ROUTES.items():
        if definition["sourceViewId"] in stale:
            stale.add(candidate_id)
    for candidate_id in stale - {route_id}:
        views.pop(candidate_id, None)


def write_roformer_audio_provenance(
    workspace: AlignmentWorkspace,
    video_id: str,
    *,
    duration_ms: int,
    expected_mix_sha256: str,
    route_id: str,
    output_audio: Path,
    result: dict[str, Any],
    helper_sha256: str,
    producer_runtime: dict[str, Any] | None = None,
) -> dict[str, Any]:
    value = read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    )
    if value is None:
        raise RuntimeError("canonical audio provenance is missing before RoFormer")
    definition = endpoints.ROFORMER_ROUTES[route_id]
    source = (
        value["mix"]
        if definition["sourceViewId"] == "mix"
        else value["audioViews"].get(definition["sourceViewId"])
    )
    output = file_identity(output_audio)
    package = result.get("package") if isinstance(result, dict) else None
    model = result.get("model") if isinstance(result, dict) else None
    recorded_input = result.get("input") if isinstance(result, dict) else None
    recorded_output = result.get("output") if isinstance(result, dict) else None
    if (
        source is None
        or result.get("resultVersion") != ROFORMER_RESULT_VERSION
        or result.get("routeId") != route_id
        or result.get("targetInstrument")
        != ("vocals" if route_id == "kimberley-jensen-roformer-vocals" else "dry")
        or not isinstance(package, dict)
        or package
        != {
            "version": models.ROFORMER_PACKAGE_VERSION,
            "wheelSha256": models.ROFORMER_PACKAGE_WHEEL_SHA256,
            "sourceManifestSha256": models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
            "sourceCommit": models.ROFORMER_PACKAGE_SOURCE_COMMIT,
        }
        or model
        != {
            "checkpointSha256": definition["checkpointSha256"],
            "configSha256": definition["configSha256"],
        }
        or recorded_input
        != {
            "sha256": source["sha256"],
            "byteCount": source["byteCount"],
            "stream": source["stream"],
        }
        or not isinstance(recorded_output, dict)
        or recorded_output.get("sha256") != output["sha256"]
        or recorded_output.get("byteCount") != output["byteCount"]
        or recorded_output.get("stream") != output["stream"]
        or not isinstance(result.get("runtime"), dict)
        or not isinstance(result.get("device"), str)
        or not isinstance(result.get("parameters"), dict)
        or not isinstance(result.get("stageMetrics"), dict)
    ):
        raise RuntimeError("RoFormer result identity is malformed or stale")
    transform = roformer_transform(
        value["mix"]["stream"],
        source["stream"],
        output["stream"],
        result.get("canonicalTimeTransform"),
    )
    invalidate_downstream_roformer_views(value["audioViews"], route_id)
    value["audioViews"][route_id] = {
        **output,
        "canonicalTimeTransform": transform,
        "routeId": route_id,
        "familyId": definition["familyId"],
        "sourceViewId": definition["sourceViewId"],
        "sourceAudioSha256": source["sha256"],
        "checkpointSha256": definition["checkpointSha256"],
        "configSha256": definition["configSha256"],
        "packageVersion": models.ROFORMER_PACKAGE_VERSION,
        "packageWheelSha256": models.ROFORMER_PACKAGE_WHEEL_SHA256,
        "packageSourceManifestSha256": models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
        "packageSourceCommit": models.ROFORMER_PACKAGE_SOURCE_COMMIT,
        "assetRepository": definition["assetRepository"],
        "assetRevision": definition["assetRevision"],
        "helperSha256": helper_sha256,
        "runtime": result["runtime"],
        **({'producerRuntime': producer_runtime} if producer_runtime is not None else {}),
        "device": result["device"],
        "parameters": result["parameters"],
        "stageMetrics": result["stageMetrics"],
    }
    atomic_write_json(workspace.audio_provenance_path(video_id), value)
    checked = read_audio_provenance(
        workspace,
        video_id,
        duration_ms,
        expected_mix_sha256=expected_mix_sha256,
    )
    if checked is None:
        raise RuntimeError("written RoFormer audio provenance failed validation")
    return checked


class AlignmentAudioViews:
    """One song attempt owns separated audio, dependency records and temporary files."""
    def __init__(self, *, config, args, workspace, video_id, duration_ms, expected_mix_sha256,
                 analysis_audio, temp_root, release_model, pinned_models, state,
                 runtime_cache, route_records, failure_codes, separate_vocals, run_roformer, worker_duration):
        self.config, self.args, self.workspace = config, args, workspace
        self.video_id, self.duration_ms = video_id, duration_ms
        self.expected_mix_sha256, self.temp_root = expected_mix_sha256, temp_root
        self.release_model, self.pinned_models, self.state = release_model, pinned_models, state
        self.runtime_cache, self.route_records, self.failure_codes = runtime_cache, route_records, failure_codes
        self.separate_vocals, self.run_roformer = separate_vocals, run_roformer
        self.worker_duration = worker_duration
        self.canonical_mix_path = analysis_audio
        self.htdemucs_temporary = self.roformer_temporary = self.htdemucs_output = None
        self.outputs = {}
        self.closed = False

    def close(self):
        if self.closed:
            return
        self.closed = True
        from sys import exc_info
        failure = exc_info()[1]
        preserve = isinstance(failure, WorkerRetirementError)
        try:
            self.release_model()
        except BaseException as cleanup_error:
            preserve = preserve or isinstance(cleanup_error, WorkerRetirementError)
            raise_with_cleanup_failure(failure, cleanup_error)
        finally:
            failure = exc_info()[1]
            try:
                try:
                    if self.roformer_temporary is not None:
                        self.roformer_temporary.cleanup(preserve=preserve)
                finally:
                    cleanup_failure = exc_info()[1]
                    preserve = preserve or isinstance(cleanup_failure, WorkerRetirementError)
                    try:
                        if self.htdemucs_temporary is not None:
                            self.htdemucs_temporary.cleanup(preserve=preserve)
                    except BaseException as cleanup_error:
                        raise_with_cleanup_failure(cleanup_failure, cleanup_error)
            except BaseException as cleanup_error:
                raise_with_cleanup_failure(failure, cleanup_error)

    def canonical_mix(self) -> tuple[Path, dict[str, Any]]:
        config = self.config
        workspace = self.workspace
        video_id = self.video_id
        duration_ms = self.duration_ms
        expected_mix_sha256 = self.expected_mix_sha256
        if self.canonical_mix_path is None:
            candidate = Path(config["tempDir"]) / video_id / "analysis.wav"
            if not candidate.is_file():
                raise RuntimeError(
                    "exact retained analysis PCM is unavailable for RoFormer"
                )
            self.canonical_mix_path = candidate
        provenance = write_audio_provenance(
            workspace,
            video_id,
            duration_ms=duration_ms,
            mix_audio=self.canonical_mix_path,
            expected_mix_sha256=expected_mix_sha256,
        )
        return self.canonical_mix_path, provenance["mix"]

    def get_htdemucs_audio(self) -> tuple[Path, dict[str, Any], dict[str, Any]]:
        config = self.config
        args = self.args
        workspace = self.workspace
        video_id = self.video_id
        duration_ms = self.duration_ms
        expected_mix_sha256 = self.expected_mix_sha256
        alignment_temp_root = self.temp_root
        release_model = self.release_model
        pinned_models = self.pinned_models
        if self.htdemucs_output is not None:
            return self.htdemucs_output
        alignment_temp_root.mkdir(parents=True, exist_ok=True)
        self.htdemucs_temporary = WorkerTemporaryDirectory(
            prefix=f"htdemucs-{video_id}-", dir=alignment_temp_root
        )
        mix, _ = self.canonical_mix()
        release_model()
        vocals = self.separate_vocals(
            mix,
            Path(self.htdemucs_temporary.name) / "demucs",
            project_dir=Path(config['projectDir']),
            device=args.device,
            timeout_seconds=int(config["analysisTimeoutMinutes"]) * 60,
        )
        provenance = write_audio_provenance(
            workspace,
            video_id,
            duration_ms=duration_ms,
            mix_audio=mix,
            vocals_audio=vocals,
            demucs_model_sha256=pinned_models["demucsModelSha256"],
            expected_mix_sha256=expected_mix_sha256,
        )
        identity = provenance["audioViews"]["htdemucs-vocals"]
        if self.worker_duration(identity) <= 0:
            raise ValueError("HTDemucs endpoint audio has an invalid duration")
        self.htdemucs_output = (
            vocals,
            identity,
            {"routeId": "htdemucs-vocals"},
        )
        return self.htdemucs_output

    def refresh_roformer_cascade(self) -> None:
        state = self.state
        roformer_route_records = self.route_records
        state["roformerCascade"] = {
            "cascadeVersion": 1,
            "routes": [
                roformer_route_records[value]
                for value in endpoints.ROFORMER_ROUTES
                if value in roformer_route_records
            ],
        }

    def record_roformer_route(self, route_id: str, *, cache_reused: bool) -> None:
        workspace = self.workspace
        video_id = self.video_id
        duration_ms = self.duration_ms
        expected_mix_sha256 = self.expected_mix_sha256
        roformer_route_records = self.route_records
        roformer_route_failure_codes = self.failure_codes
        definition = endpoints.ROFORMER_ROUTES[route_id]
        source_route = definition["sourceViewId"]
        if source_route in endpoints.ROFORMER_ROUTES and source_route not in roformer_route_records:
            self.record_roformer_route(source_route, cache_reused=True)
        provenance = read_audio_provenance(
            workspace,
            video_id,
            duration_ms,
            expected_mix_sha256=expected_mix_sha256,
        )
        identity = (
            provenance.get("audioViews", {}).get(route_id)
            if isinstance(provenance, dict)
            and isinstance(provenance.get("audioViews"), dict)
            else None
        )
        if not isinstance(identity, dict):
            raise RuntimeError("RoFormer route has no current audio identity")
        prior = roformer_route_records.get(route_id)
        record: dict[str, Any] = {
            "routeId": route_id,
            "familyId": definition["familyId"],
            "sourceViewId": definition["sourceViewId"],
            "outputViewId": route_id,
            "status": "complete",
            "cacheReused": (
                cache_reused
                if prior is None
                else bool(prior["cacheReused"]) and cache_reused
            ),
        }
        metrics = identity.get("stageMetrics")
        if isinstance(metrics, dict):
            runtime_ms = metrics.get("runtimeMs")
            peak_memory = metrics.get("peakCudaMemoryBytes")
            if type(runtime_ms) is int and runtime_ms >= 0:
                record["runtimeMs"] = runtime_ms
            if type(peak_memory) is int and peak_memory >= 0:
                record["peakCudaMemoryBytes"] = peak_memory
        roformer_route_records[route_id] = record
        roformer_route_failure_codes.pop(route_id, None)
        self.refresh_roformer_cascade()

    def get_roformer_audio(
        self, route_id: str,
    ) -> tuple[Path, dict[str, Any], dict[str, Any]]:
        config = self.config
        args = self.args
        workspace = self.workspace
        video_id = self.video_id
        duration_ms = self.duration_ms
        expected_mix_sha256 = self.expected_mix_sha256
        alignment_temp_root = self.temp_root
        release_model = self.release_model
        roformer_outputs = self.outputs
        runtime_probe_cache = self.runtime_cache
        roformer_route_failure_codes = self.failure_codes
        cached = roformer_outputs.get(route_id)
        if cached is not None:
            return cached
        if route_id in roformer_route_failure_codes:
            raise worker_protocol.WorkerProcessError("RoFormer dependency route is unavailable",
                                     roformer_route_failure_codes[route_id])
        definition = endpoints.ROFORMER_ROUTES.get(route_id)
        if definition is None:
            raise ValueError("unsupported RoFormer cascade route")
        if self.roformer_temporary is None:
            alignment_temp_root.mkdir(parents=True, exist_ok=True)
            self.roformer_temporary = WorkerTemporaryDirectory(
                prefix=f"roformer-{video_id}-", dir=alignment_temp_root
            )
        if definition["sourceViewId"] == "mix":
            source_audio, source_identity = self.canonical_mix()
        else:
            source_audio, source_identity, _ = self.get_roformer_audio(
                definition["sourceViewId"]
            )
        release_model()
        generated = self.run_roformer(
            config=config,
            video_id=video_id,
            route_id=route_id,
            input_audio=source_audio,
            input_identity=source_identity,
            workspace=workspace,
            duration_ms=duration_ms,
            expected_mix_sha256=expected_mix_sha256,
            temp_dir=Path(self.roformer_temporary.name),
            device=args.device,
            timeout_seconds=int(config["analysisTimeoutMinutes"]) * 60,
            runtime_cache=runtime_probe_cache,
        )
        roformer_outputs[route_id] = generated
        self.record_roformer_route(route_id, cache_reused=False)
        return generated

    def get_endpoint_audio(
        self, route_id: str,
    ) -> tuple[Path, dict[str, Any], dict[str, Any]]:
        return (
            self.get_htdemucs_audio()
            if route_id == "htdemucs-vocals"
            else self.get_roformer_audio(route_id)
        )
