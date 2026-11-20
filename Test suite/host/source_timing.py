"""Offline timing diagnostics. No model/provider calls; no audio correction.

Only the frozen synthetic calibration family can earn a measured PASS. Musical
references remain UNVERIFIED until a suitable independent calibration exists.
Manifest provenance is supplied by the caller, not authenticated by this tool.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import sys
import tempfile
import unittest
import wave

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "Test suite/android/fixtures/mvsep-source-timing-inventory.json"
VERSION = "source-timing-1"
PROFILE = {
    "version": "analytic-broadband-1", "rate": 48000, "epsilonSamples48k": .25,
    "windowFrames": 8192, "hopFrames": 2048, "searchFrames": 256,
    "spectralFrames": 1024, "minimumSpectralAverages": 8,
    "minimumCoherence": .995, "maximumPhaseResidualRadians": .03,
    "maximumBandDisagreementSamples": .02, "minimumGain": .2, "maximumGain": 1.7,
    "maximumPcmMagnitude": 4., "minimumNonzeroPcmMagnitude": 1e-100,
    "maximumCompetitorPeakRatio": .92, "uncertaintySamples48k": .04,
    "maximumAnalyticModelRelativeResidual": 1e-9,
    "minimumAffectedSpanFrames": 12288,
    "maximumCalibratedReferenceFrames": 131072,
    "calibratedRuntime": {"pythonRelease": "3.10.18", "numpyVersion": "2.2.6", "scipyVersion": "1.15.3", "machine": "x86_64", "system": "Linux"},
    "uncertaintyMethod": "empirical envelope on analytic gain/polarity/delay controls; no probability or arbitrary-audio enclosure",
    "domain": "48kHz analytic sums of 192 positive-amplitude components per channel, each amplitude .002-.008, frequency .025-.40 cycles/frame, phase -3..3 radians; isolated upload equals reference; affine coordinates and constant gain/polarity only; <=131072 frames",
    "limitations": "No arbitrary music/model accuracy qualification; no guarantee for defects shorter than 12288 frames, hidden within-window changes, unobserved gaps, or arbitrary filtering",
}


class OutsideProfile(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_inventory() -> dict:
    value = json.loads(INVENTORY.read_text(encoding="utf-8"))
    if value.get("schemaVersion") != 1 or not value.get("configurations") or not value.get("sourceHashes"):
        raise ValueError("Missing versioned production inventory")
    for relative, expected in value["sourceHashes"].items():
        actual = hashlib.sha256((ROOT / relative).read_text(encoding="utf-8-sig").replace("\r\n", "\n").encode("utf-8")).hexdigest()
        if actual != expected:
            raise ValueError(f"Stale production inventory: {relative}")
    return value


def inventory_report() -> dict:
    value = load_inventory()
    bindings = [dict(configurationId=c["configurationId"], renderId=c["renderId"], options=c["options"],
                     selectedRole=selected, providerRole=provider, decision="UNVERIFIED", reason="NO_REFERENCE_MEASUREMENT",
                     availability="UNKNOWN_NOT_QUERIED")
                for c in value["configurations"] for selected, provider in c["outputBindings"].items()]
    return {"schemaVersion": 1, "analyzerVersion": VERSION, "inventorySha256": sha256(INVENTORY),
            "configured": len(bindings), "configurations": len(value["configurations"]),
            "renderIds": len({c["renderId"] for c in value["configurations"]}),
            "selectedSources": len(value["selectedSourceIds"]), "measured": 0, "passed": 0,
            "failed": 0, "unverified": len(bindings), "unavailable": 0,
            "availabilityUnknown": len(bindings), "bindings": bindings}


def numerical_modules():
    try:
        import numpy as np
        from scipy import signal
    except ImportError as exc:
        raise ValueError("NumPy/SciPy unavailable; use the existing PC engine WSL .venv (no provider needed)") from exc
    return np, signal


@lru_cache(maxsize=1)
def runtime_identity() -> dict:
    np, _ = numerical_modules()
    import scipy
    def file_identity(path) -> dict:
        if not path:
            return {"status": "unknown", "path": None, "sha256": None, "reason": "No loaded file path"}
        resolved = Path(path).resolve()
        try:
            return {"status": "known", "path": str(resolved), "sha256": sha256(resolved)}
        except OSError as exc:
            return {"status": "unknown", "path": str(resolved), "sha256": None, "reason": str(exc)}
    extensions = {}
    for name, candidates in {
        "numpyMultiarrayUmath": ("numpy._core._multiarray_umath", "numpy.core._multiarray_umath"),
        "numpyUmathLinalg": ("numpy.linalg._umath_linalg",),
        "scipyPocketfft": ("scipy.fft._pocketfft.pypocketfft",),
    }.items():
        module = next((sys.modules[c] for c in candidates if c in sys.modules), None)
        extensions[name] = dict(module=getattr(module, "__name__", None), **file_identity(getattr(module, "__file__", None)))
    return {"pythonVersion": sys.version, "pythonExecutable": file_identity(sys.executable),
            "pythonRelease": platform.python_version(), "machine": platform.machine(), "system": platform.system(),
            "numpyVersion": np.__version__, "scipyVersion": scipy.__version__, "coreExtensions": extensions,
            "limitation": "Loaded core file identities; does not enumerate every transitive shared library or hardware characteristic"}


def runtime_status(identity=None):
    identity = runtime_identity() if identity is None else identity
    return ('CALIBRATED_RUNTIME' if all(identity.get(key) == value for key, value in PROFILE['calibratedRuntime'].items())
            else 'UNQUALIFIED_ANALYZER_RUNTIME')


def interval_decision(delay: float, uncertainty: float) -> str:
    if abs(delay) + uncertainty <= PROFILE["epsilonSamples48k"]:
        return "PASS_WITHIN_BOUND"
    if abs(delay) - uncertainty > PROFILE["epsilonSamples48k"]:
        return "FAIL_TIMING"
    return "UNVERIFIED"


def worst(decisions) -> str:
    values = list(decisions)
    if "FAIL_INTEGRITY" in values:
        return "FAIL_INTEGRITY"
    if "FAIL_TIMING" in values:
        return "FAIL_TIMING"
    return "PASS_WITHIN_BOUND" if values and all(v == "PASS_WITHIN_BOUND" for v in values) else "UNVERIFIED"


def analytic_samples(spec: dict, frames: int, channels: int):
    """Validate/evaluate an explicit continuous sum, not an interpolated delay oracle."""
    np, _ = numerical_modules()
    components = spec.get("channels", [])
    if spec.get("kind") != "analytic-bandlimited-sum-v1" or len(components) != channels:
        raise ValueError("Unsupported analytic reference specification")
    time = np.arange(frames, dtype=np.float64)
    output = np.zeros((frames, channels), dtype=np.float64)
    for channel, terms in enumerate(components):
        if len(terms) != 192:
            raise ValueError("Analytic calibration requires exactly 192 independent frequencies per channel")
        frequencies = [float(t["cyclesPerFrame"]) for t in terms]
        if len(set(frequencies)) != len(terms) or min(frequencies) < .025 or max(frequencies) > .40:
            raise ValueError("Analytic frequencies outside frozen band or duplicated")
        if min(frequencies) > .06 or max(frequencies) < .35:
            raise ValueError("Analytic calibration requires broadband support")
        for term in terms:
            frequency, amplitude, phase = (float(term[k]) for k in ("cyclesPerFrame", "amplitude", "phaseRadians"))
            if not all(math.isfinite(v) for v in (frequency, amplitude, phase)) or not .002 <= amplitude <= .008 or not -3 <= phase <= 3:
                raise ValueError("Invalid analytic component")
            output[:, channel] += amplitude * np.sin(2 * np.pi * frequency * time + phase)
    return output


def measure_window(reference, returned) -> dict:
    np, signal = numerical_modules()
    x, y = np.asarray(reference, dtype=float), np.asarray(returned, dtype=float)
    rejected = lambda reason: {"decision": "UNVERIFIED", "reason": reason}
    for values in (x, y):
        magnitude = np.abs(values)
        if (not np.all(np.isfinite(values)) or np.any(magnitude > PROFILE["maximumPcmMagnitude"]) or
                np.any((magnitude > 0) & (magnitude < PROFILE["minimumNonzeroPcmMagnitude"]))):
            return rejected("NUMERIC_RANGE_UNQUALIFIED")
    if not np.any(y):
        return rejected("EXACT_ZERO_NONCONTRIBUTING_NO_TIMING_INFORMATION")
    if not np.any(x):
        return rejected("NO_REFERENCE_TIMING_INFORMATION")
    x, y = x - np.mean(x), y - np.mean(y)
    gain_magnitude = np.linalg.norm(y) / max(np.linalg.norm(x), 1e-300)
    if gain_magnitude < PROFILE["minimumGain"] * (1 - 1e-10) or gain_magnitude > PROFILE["maximumGain"] * (1 + 1e-10):
        return rejected("SUPPRESSED_OR_OUTSIDE_CALIBRATED_GAIN_RANGE")
    correlation = signal.correlate(y, x, mode="full", method="fft")
    lags = signal.correlation_lags(len(y), len(x), mode="full")
    allowed = np.abs(lags) <= PROFILE["searchFrames"]
    candidates, candidate_lags = np.abs(correlation[allowed]), lags[allowed]
    index = int(np.argmax(candidates))
    integer = int(candidate_lags[index])
    if abs(integer) == PROFILE["searchFrames"]:
        return rejected("SEARCH_EDGE")
    competitors = candidates[np.abs(candidate_lags - integer) > 2]
    if not candidates[index] or np.max(competitors) / candidates[index] > PROFILE["maximumCompetitorPeakRatio"]:
        return rejected("AMBIGUOUS_OCCURRENCE")
    polarity = 1 if correlation[allowed][index] >= 0 else -1
    if integer >= 0:
        xx, yy = x[:len(x) - integer or None], y[integer:]
    else:
        xx, yy = x[-integer:], y[:len(y) + integer]
    yy = yy * polarity
    size = PROFILE["spectralFrames"]
    averages = 1 + (len(xx) - size) // (size // 2)
    if averages < PROFILE["minimumSpectralAverages"]:
        return rejected("INSUFFICIENT_AVERAGED_SEGMENTS")
    options = dict(fs=1., nperseg=size, noverlap=size // 2, detrend=False)
    frequency, cross = signal.csd(xx, yy, **options)
    _, px = signal.welch(xx, **options)
    _, py = signal.welch(yy, **options)
    coherence = np.abs(cross) ** 2 / np.maximum(px * py, 1e-300)
    band = (frequency >= .04) & (frequency <= .35) & (px >= max(px) * 1e-4)
    if np.count_nonzero(band) < 48 or np.min(coherence[band]) < PROFILE["minimumCoherence"]:
        return rejected("LOW_OR_INCOHERENT_TARGET_INFORMATION")
    f, phase = frequency[band], np.unwrap(np.angle(cross[band]))
    weight = np.sqrt(px[band])
    slope, intercept = np.polyfit(f, phase, 1, w=weight)
    residual = float(np.max(np.abs(phase - (slope * f + intercept))))
    delay = float(-slope / (2 * np.pi))
    halves = [f <= .195, f > .195]
    if any(np.count_nonzero(part) < 16 for part in halves):
        return rejected("INSUFFICIENT_INDEPENDENT_BANDS")
    band_delays = [-float(np.polyfit(f[part], phase[part], 1, w=weight[part])[0]) / (2 * np.pi) for part in halves]
    disagreement = abs(band_delays[0] - band_delays[1])
    if residual > PROFILE["maximumPhaseResidualRadians"] or disagreement > PROFILE["maximumBandDisagreementSamples"] or abs(delay) > .75:
        return rejected("PHASE_OR_BAND_DISAGREEMENT")
    # The fixed allowance is an empirical calibration envelope, not a fitted confidence interval.
    uncertainty = PROFILE["uncertaintySamples48k"]
    total = integer + delay
    return {"decision": interval_decision(total, uncertainty), "delaySamples48k": total,
            "uncertaintySamples48k": uncertainty, "intervalSamples48k": [total - uncertainty, total + uncertainty],
            "integerLag": integer, "fractionalLag": delay, "polarity": polarity,
            "gainMagnitude": float(gain_magnitude), "minimumCoherence": float(np.min(coherence[band])),
            "phaseResidualRadians": residual, "bandDelays": band_delays, "spectralAverages": averages}


def analytic_model_residual(returned, spec: dict, trajectories: list[dict]) -> float:
    """A strict domain check rejects leakage and even brief non-affine defects.

    This fits coordinates for diagnosis only; it never changes saved audio.
    """
    np, _ = numerical_modules()
    coordinate = np.arange(len(returned), dtype=float)
    time = coordinate / max(1, len(returned) - 1)
    maximum = 0.
    for channel, terms in enumerate(spec["channels"]):
        trajectory = trajectories[channel]
        slope = trajectory.get("driftSamplesPerSecond", 0.) / 48000
        b = slope * (len(returned) - 1)
        a = trajectory.get("fixedDelaySamples48k", 0.) - b / 2
        gain = 1.
        for _ in range(5):
            point = coordinate - a - b * time
            values, derivative = np.zeros(len(point)), np.zeros(len(point))
            for term in terms:
                angular = 2 * np.pi * term["cyclesPerFrame"]
                phase = angular * point + term["phaseRadians"]
                values += term["amplitude"] * np.sin(phase)
                derivative += term["amplitude"] * angular * np.cos(phase)
            gain = float(np.dot(values, returned[:, channel]) / np.dot(values, values))
            error = returned[:, channel] - gain * values
            jacobian = np.column_stack((-gain * derivative, -gain * derivative * time))
            update = np.linalg.lstsq(jacobian, error, rcond=None)[0]
            a, b = a + float(update[0]), b + float(update[1])
            if float(np.max(np.abs(update))) < 1e-12:
                break
        residual = float(np.max(np.abs(error)) / max(np.max(np.abs(returned[:, channel])), 1e-300))
        maximum = max(maximum, residual)
    return maximum


def measure_audio(reference, returned, *, calibrated: bool, analytic_spec: dict | None = None) -> dict:
    np, _ = numerical_modules()
    if reference.shape != returned.shape or reference.ndim != 2:
        raise ValueError("Reference/output frame or channel extent mismatch")
    frames, channels = reference.shape
    if frames > PROFILE["maximumCalibratedReferenceFrames"]:
        calibrated = False
    size, hop = PROFILE["windowFrames"], PROFILE["hopFrames"]
    starts = sorted(set(range(0, max(0, frames - size + 1), hop)) | ({frames - size} if frames >= size else set()))
    windows, trajectories = [], []
    for channel in range(channels):
        local = []
        for start in starts:
            item = measure_window(reference[start:start + size, channel], returned[start:start + size, channel])
            item.update(channel=channel, startFrame=start, endFrame=start + size)
            if not calibrated:
                item.update(diagnosticDecision=item["decision"], decision="UNVERIFIED", reason="OUTSIDE_CALIBRATED_REFERENCE_CLASS_OR_DURATION")
            windows.append(item)
            if "delaySamples48k" in item:
                local.append(item)
        trajectory = {"channel": channel, "decision": "UNVERIFIED", "jumps": []}
        if local:
            times = np.array([(w["startFrame"] + size / 2) / PROFILE["rate"] for w in local])
            delays = np.array([w["delaySamples48k"] for w in local])
            slope = float(np.polyfit(times - times[0], delays, 1)[0]) if len(local) > 1 else 0.
            spread = float(np.max(delays) - np.min(delays))
            u = PROFILE["uncertaintySamples48k"] * 2
            drift_decision = interval_decision(spread, u) if len(local) > 1 else "UNVERIFIED"
            trajectory.update(fixedDelaySamples48k=float(np.median(delays)), driftSamplesPerSecond=slope,
                              driftPpm=slope / 48000 * 1e6, accumulatedDriftSamples48k=float(delays[-1] - delays[0]),
                              lagRangeSamples48k=spread, rangeUncertaintySamples48k=u, decision=drift_decision,
                              jumps=[{"startFrame": b["startFrame"], "changeSamples48k": b["delaySamples48k"] - a["delaySamples48k"],
                                      "decision": interval_decision(b["delaySamples48k"] - a["delaySamples48k"], u)}
                                     for a, b in zip(local, local[1:]) if abs(b["delaySamples48k"] - a["delaySamples48k"]) + u > .25])
        trajectories.append(trajectory)
    skew = []
    if channels == 2:
        by_key = {(w["channel"], w["startFrame"]): w for w in windows if "delaySamples48k" in w}
        for start in starts:
            a, b = by_key.get((0, start)), by_key.get((1, start))
            if a and b:
                difference = a["delaySamples48k"] - b["delaySamples48k"]
                skew.append({"startFrame": start, "skewSamples48k": difference, "uncertaintySamples48k": .08,
                             "decision": interval_decision(difference, .08)})
    decisions = [w["decision"] for w in windows] + [t["decision"] for t in trajectories] + [s["decision"] for s in skew]
    decision = worst(decisions)
    if not calibrated:
        decision = "UNVERIFIED"
    model_residual = None
    if calibrated and decision in ("PASS_WITHIN_BOUND", "FAIL_TIMING"):
        if analytic_spec is None:
            decision = "UNVERIFIED"
        else:
            model_residual = analytic_model_residual(returned, analytic_spec, trajectories)
            if model_residual > PROFILE["maximumAnalyticModelRelativeResidual"]:
                decision = "UNVERIFIED"
        if decision == "UNVERIFIED":
            for item in windows:
                item.update(diagnosticDecision=item["decision"], decision="UNVERIFIED", reason="OUTSIDE_EXACT_ANALYTIC_AFFINE_DOMAIN_OR_LEAKAGE")
    if not calibrated or model_residual is not None and model_residual > PROFILE["maximumAnalyticModelRelativeResidual"]:
        for item in windows:
            if "uncertaintySamples48k" in item:
                item["diagnosticCalibrationAllowanceSamples48k"] = item.pop("uncertaintySamples48k")
                item.pop("intervalSamples48k", None)
        for item in trajectories + skew:
            item.update(diagnosticDecision=item["decision"], decision="UNVERIFIED")
            for jump in item.get("jumps", []):
                jump.update(diagnosticDecision=jump["decision"], decision="UNVERIFIED")
    measured = [{"channel": w["channel"], "startFrame": w["startFrame"], "endFrame": w["endFrame"]}
                for w in windows if "delaySamples48k" in w]
    unverified = [{"channel": w["channel"], "startFrame": w["startFrame"], "endFrame": w["endFrame"], "reason": w.get("reason", "BOUNDARY_OVERLAP")}
                  for w in windows if w["decision"] == "UNVERIFIED"]
    if not starts:
        unverified = [{"channel": c, "startFrame": 0, "endFrame": frames, "reason": "BELOW_MINIMUM_WINDOW"} for c in range(channels)]
    return {"decision": decision, "analyticModelRelativeResidual": model_residual,
            "windows": windows, "trajectories": trajectories, "stereoSkew": skew,
            "maximumAbsoluteLagSamples48k": max((abs(w["delaySamples48k"]) for w in windows if "delaySamples48k" in w), default=None),
            "maximumStereoSkewSamples48k": max((abs(s["skewSamples48k"]) for s in skew), default=None),
            "measuredIntervals": measured, "unverifiedIntervals": unverified,
            "boundaryCoverage": {"beginning": bool(starts), "end": bool(starts), "knownChunkBoundaries": "not supplied"},
            "firstNonpassingRegion": next((w for w in windows if w["decision"] != "PASS_WITHIN_BOUND"), None)}


def read_artifact(description: dict, base: Path, *, pcm=True):
    np, _ = numerical_modules()
    path = Path(description["path"])
    path = path if path.is_absolute() else base / path
    if not path.is_file() or sha256(path) != description["sha256"]:
        raise ValueError(f"Artifact missing or hash changed: {path.name}")
    if description.get("encodedArtifact"):
        encoded = description["encodedArtifact"]
        encoded_path = Path(encoded["path"])
        encoded_path = encoded_path if encoded_path.is_absolute() else base / encoded_path
        if sha256(encoded_path) != encoded["sha256"]:
            raise ValueError("Encoded artifact bytes changed")
    if not pcm:
        return None
    channels, frames = description["channels"], description["frames"]
    if type(channels) is not int or channels not in (1, 2) or type(frames) is not int or frames <= 0:
        raise ValueError("Invalid decoded extent")
    if description["format"] in ("f64le", "f32le"):
        width, dtype = (8, "<f8") if description["format"] == "f64le" else (4, "<f4")
        if path.stat().st_size != frames * channels * width:
            raise ValueError("PCM byte extent mismatch")
        values = np.fromfile(path, dtype=dtype).reshape(frames, channels).astype(float)
    elif description["format"] == "wav-pcm16":
        with wave.open(str(path), "rb") as source:
            if (source.getnchannels(), source.getnframes(), source.getframerate(), source.getsampwidth()) != (channels, frames, description["sampleRate"], 2):
                raise ValueError("WAV header disagrees with decoded declaration")
            values = np.frombuffer(source.readframes(frames), dtype="<i2").reshape(frames, channels) / 32768.
    else:
        raise OutsideProfile("UNQUALIFIED_PCM_DECODER_PROFILE")
    if not np.all(np.isfinite(values)):
        raise ValueError("Nonfinite PCM")
    magnitude = np.abs(values)
    if np.any(magnitude > PROFILE["maximumPcmMagnitude"]) or np.any((magnitude > 0) & (magnitude < PROFILE["minimumNonzeroPcmMagnitude"])):
        raise OutsideProfile("NUMERIC_RANGE_UNQUALIFIED")
    return values


def validate_binding(binding: dict) -> None:
    def valid_string(value):
        return isinstance(value, str) and 0 < len(value) <= 512 and value == value.strip() and not any(ord(c) < 32 or 127 <= ord(c) < 160 for c in value)
    strings = ("sourceId", "configurationId", "selectedRole", "providerRole", "acceptedJobId", "decoderProfile", "srcProfile", "processingDate")
    if not isinstance(binding, dict) or any(not valid_string(binding.get(k)) for k in strings):
        raise ValueError("Source/route/job/profile identities must be bounded nonblank strings without control characters")
    if any(type(binding.get(k)) is not int or binding[k] <= 0 for k in ("generation", "renderId")):
        raise ValueError("Generation/render ID must be positive integers")
    if "observableRevision" not in binding or binding["observableRevision"] is not None and not valid_string(binding["observableRevision"]):
        raise ValueError("Observable revision must be a string or explicit null")
    options = binding.get("options")
    if not isinstance(options, dict) or any(not valid_string(k) or not valid_string(v) for k, v in options.items()):
        raise ValueError("Options must be the exact string-valued production tuple")
    clock = binding.get("uploadClock")
    if (not isinstance(clock, dict) or set(clock) != {"sampleRate", "originFrame", "preskipFrames", "trimFrames", "channelOrder"} or
            type(clock["sampleRate"]) is not int or clock["sampleRate"] <= 0 or
            any(type(clock[k]) is not int or clock[k] < 0 for k in ("originFrame", "preskipFrames", "trimFrames")) or
            clock["channelOrder"] not in (["mono"], ["left", "right"])):
        raise ValueError("Malformed bound upload clock")


def finite_json_number(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        raise ValueError("Nonfinite JSON number")
    return value


def manifest_document(path: Path) -> dict:
    document = json.loads(path.read_text(encoding="utf-8"), parse_float=finite_json_number, parse_constant=finite_json_number)
    if not isinstance(document, dict) or type(document.get("schemaVersion")) is not int or document["schemaVersion"] != 1:
        raise ValueError("Manifest must be a schemaVersion 1 object")
    if "targets" in document:
        if not isinstance(document["targets"], list) or not document["targets"] or any(
                not isinstance(t, dict) or not isinstance(t.get("manifest"), str) or not t["manifest"] or not isinstance(t.get("sha256"), str)
                for t in document["targets"]):
            raise ValueError("Bundle needs nonempty hashed target manifests")
        return document
    artifacts = document.get("artifacts")
    if not isinstance(artifacts, dict) or set(artifacts) != {"upload", "raw", "prepared"}:
        raise ValueError("Require upload/raw/prepared artifact objects")
    descriptions = list(artifacts.values())
    if "reference" in document:
        ref = document["reference"]
        if not isinstance(ref, dict) or not isinstance(ref.get("provenance"), dict):
            raise ValueError("Reference and provenance must be objects")
        descriptions.append(ref.get("artifact"))
        if "analyticSpecification" in ref and not isinstance(ref["analyticSpecification"], dict):
            raise ValueError("Analytic reference specification must be an object")
    for description in descriptions:
        if not isinstance(description, dict) or not isinstance(description.get("path"), str) or not description["path"] or not isinstance(description.get("sha256"), str):
            raise ValueError("Artifact requires explicit path/hash strings")
        if "encodedArtifact" in description:
            encoded = description["encodedArtifact"]
            if not isinstance(encoded, dict) or not isinstance(encoded.get("path"), str) or not isinstance(encoded.get("sha256"), str):
                raise ValueError("Encoded artifact must be an explicit path/hash object")
    return document


def validate_output(manifest: Path, output: Path) -> None:
    if not output.parent.is_dir():
        raise ValueError("--analyze requires --output in an existing Test suite/work directory")
    output.resolve().relative_to((ROOT / "Test suite/work").resolve())
    pending, protected, seen, complete = [manifest], set(), set(), True
    while pending:
        try:
            current = pending.pop().resolve()
        except (ValueError, OSError):
            complete = False
            continue
        if current in seen:
            continue
        seen.add(current)
        protected.add(current)
        try:
            document = json.loads(current.read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise ValueError("Non-object manifest")
            values = [document]
            while values:
                value = values.pop()
                if isinstance(value, dict):
                    if isinstance(value.get("path"), str) and value["path"]:
                        try:
                            candidate = Path(value["path"])
                            protected.add((candidate if candidate.is_absolute() else current.parent / candidate).resolve())
                        except (ValueError, OSError):
                            complete = False
                    values.extend(value.values())
                elif isinstance(value, list):
                    values.extend(value)
            if "targets" in document:
                if not isinstance(document["targets"], list) or not document["targets"]:
                    raise ValueError("Malformed targets")
                for target in document["targets"]:
                    if not isinstance(target, dict) or not isinstance(target.get("manifest"), str):
                        complete = False
                        continue
                    child = Path(target["manifest"])
                    pending.append(child if child.is_absolute() else current.parent / child)
            else:
                artifacts = document.get("artifacts")
                if not isinstance(artifacts, dict) or set(artifacts) != {"upload", "raw", "prepared"}:
                    raise ValueError("Malformed artifact set")
                descriptions = list(artifacts.values())
                if "reference" in document:
                    ref = document["reference"]
                    if not isinstance(ref, dict):
                        raise ValueError("Malformed reference")
                    descriptions.append(ref.get("artifact"))
                for description in descriptions:
                    if not isinstance(description, dict):
                        raise ValueError("Malformed artifact")
                    for item in [description] + ([description["encodedArtifact"]] if "encodedArtifact" in description else []):
                        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not item["path"]:
                            raise ValueError("Malformed artifact path")
                        candidate = Path(item["path"])
                        protected.add((candidate if candidate.is_absolute() else current.parent / candidate).resolve())
        except (ValueError, TypeError, OSError):
            complete = False
    if output.resolve() in protected:
        raise ValueError("Output must not overwrite input manifests or artifacts")
    if not complete and output.exists():
        try:
            previous = json.loads(output.read_text(encoding="utf-8"))
            owned = (isinstance(previous, dict) and previous.get("schemaVersion") == 1 and previous.get("analyzerVersion") == VERSION and
                     isinstance(previous.get("manifestPath"), str) and Path(previous["manifestPath"]).resolve() == manifest.resolve() and
                     previous.get("decision") in {"PASS_WITHIN_BOUND", "FAIL_TIMING", "FAIL_INTEGRITY", "UNVERIFIED"} and
                     isinstance(previous.get("analyzerSha256"), str) and len(previous["analyzerSha256"]) == 64)
        except (ValueError, TypeError, OSError):
            owned = False
        if not owned:
            raise ValueError("Incomplete input paths: existing output is not this manifest's analyzer result")


def integrity_failure(path: Path, error: Exception) -> dict:
    try:
        identity = sha256(path)
    except OSError:
        identity = None
    return {"schemaVersion": 1, "analyzerVersion": VERSION, "analyzerSha256": sha256(Path(__file__)),
            "manifestPath": str(path.resolve()), "manifestSha256": identity, "runtime": runtime_identity(),
            "decision": "FAIL_INTEGRITY", "structuralDecision": "STRUCTURAL_TIMELINE_INVALID",
            "jobAdmissionDecision": "JOB_TIMING_UNVERIFIED", "rawProviderDecision": "UNVERIFIED",
            "localConversionDecision": "UNVERIFIED", "reason": f"{type(error).__name__}: {error}"}


def analyze_manifest(path: Path) -> dict:
    np, _ = numerical_modules()
    document = manifest_document(path)
    result = {"schemaVersion": 1, "analyzerVersion": VERSION, "analyzerSha256": sha256(Path(__file__)), "manifestPath": str(path.resolve()),
              "manifestSha256": sha256(path), "profile": PROFILE, "runtime": runtime_identity(), "decision": "UNVERIFIED",
              "structuralDecision": "STRUCTURAL_TIMELINE_INVALID", "rawProviderDecision": "UNVERIFIED",
              "localConversionDecision": "UNVERIFIED", "jobAdmissionDecision": "JOB_TIMING_UNVERIFIED",
              "provenanceLimitation": "Caller-supplied reference/job provenance; hashes bind bytes, not provider authenticity or reference independence",
              "boundaries": [], "artifacts": document.get("artifacts", {}), "reference": document.get("reference")}
    try:
        if document.get("schemaVersion") != 1:
            raise ValueError("Unsupported manifest schema")
        binding = document["binding"]
        validate_binding(binding)
        route = next((c for c in load_inventory()["configurations"] if c["configurationId"] == binding["configurationId"]), None)
        if not route or any(binding[k] != route[k] for k in ("renderId", "options")) or route["outputBindings"].get(binding["selectedRole"]) != binding["providerRole"]:
            raise ValueError("Route/options/selected-provider role mismatch")
        artifacts = document["artifacts"]
        if set(artifacts) != {"upload", "raw", "prepared"}:
            raise ValueError("Require actual upload, raw and prepared artifacts")
        upload = read_artifact(artifacts["upload"], path.parent)
        raw, prepared = [read_artifact(artifacts[k], path.parent) for k in ("raw", "prepared")]
        result.update(binding=binding, artifacts=artifacts)
        unsupported_clock = False
        for name in ("upload", "raw", "prepared"):
            desc = artifacts[name]
            if desc.get("binding") != binding:
                raise ValueError(f"Stale {name} operation/decoder binding")
            clock = desc["clock"]
            required_clock = {"originFrame", "preskipFrames", "trimFrames", "channelOrder"}
            if (not isinstance(clock, dict) or set(clock) != required_clock or any(type(clock[k]) is not int or clock[k] < 0 for k in required_clock - {"channelOrder"}) or
                    type(desc["sampleRate"]) is not int or desc["sampleRate"] <= 0 or not isinstance(clock["channelOrder"], list)):
                raise ValueError("Malformed decoded audible-clock declaration")
            if desc.get("expectedClock") is not None and clock != desc["expectedClock"]:
                raise ValueError("Observed decoded clock differs from bound expected mapping")
            expected_order = ["mono"] if desc["channels"] == 1 else ["left", "right"]
            unsupported_clock |= any(clock[k] != 0 for k in ("originFrame", "preskipFrames", "trimFrames")) or clock["channelOrder"] != expected_order
        if binding["uploadClock"] != dict(sampleRate=artifacts["upload"]["sampleRate"], **artifacts["upload"]["clock"]):
            raise ValueError("Upload audible clock differs from bound request")
        unsupported_clock |= any(artifacts[n]["sampleRate"] != 48000 for n in ("upload", "raw", "prepared")) or raw.shape != prepared.shape
        if not unsupported_clock and upload.shape != prepared.shape:
            raise ValueError("Prepared output does not retain Original extent")
        result["structuralDecision"] = "STRUCTURAL_TIMELINE_VALID"
        if unsupported_clock:
            result.update(structuralDecision="STRUCTURAL_TIMELINE_UNVERIFIED", reason="UNQUALIFIED_CLOCK_PROFILE",
                          declaredClockMapping=document.get("clockMapping"),
                          clockLimitation="Actual extents/rates read; non-native48k conversion mapping not independently qualified")
            return result
        if "reference" not in document:
            result["reason"] = "NO_INDEPENDENT_REFERENCE"
            return result
        ref = document["reference"]
        reference = read_artifact(ref["artifact"], path.parent)
        provenance = ref.get("provenance", {})
        if (provenance.get("kind") != "independent-synchronized" or not provenance.get("id") or
                provenance.get("role") != binding["selectedRole"] or provenance.get("sourceId") != binding["sourceId"] or
                ref["artifact"].get("clock") != artifacts["prepared"]["clock"] or ref["artifact"].get("sampleRate") != 48000):
            result["reason"] = "REFERENCE_PROVENANCE_OR_CLOCK_UNVERIFIED"
            return result
        calibrated = False
        if "analyticSpecification" in ref:
            try:
                expected = analytic_samples(ref["analyticSpecification"], *reference.shape)
            except ValueError:
                result["referenceCalibrationReason"] = "OUTSIDE_CALIBRATED_ANALYTIC_SPECIFICATION"
            else:
                calibrated = bool(np.max(np.abs(expected - reference)) <= 2e-12)
                if not calibrated:
                    raise ValueError("Reference PCM disagrees with independent analytic coordinates")
            if provenance.get("inputPolicy") != "isolated-reference" or upload.shape != reference.shape or not np.array_equal(upload, reference):
                calibrated = False
        result['analyzerRuntimeStatus'] = runtime_status(result['runtime'])
        runtime_qualified = result['analyzerRuntimeStatus'] == 'CALIBRATED_RUNTIME'
        if not runtime_qualified:
            calibrated = False
            result["reason"] = "UNQUALIFIED_ANALYZER_RUNTIME"
        for name, samples in (("raw", raw), ("prepared", prepared)):
            measured = measure_audio(reference, samples, calibrated=calibrated, analytic_spec=ref.get("analyticSpecification"))
            measured["boundary"] = name
            result["boundaries"].append(measured)
        result["rawProviderDecision"] = result["boundaries"][0]["decision"]
        if np.array_equal(raw, prepared):
            conversion = {"decision": "PASS_WITHIN_BOUND", "method": "exact native-clock PCM coordinate identity",
                          "intervalSamples48k": [0., 0.], "measuredIntervals": [{"startFrame": 0, "endFrame": len(raw)}]}
        else:
            conversion = measure_audio(raw, prepared, calibrated=calibrated,
                                       analytic_spec=ref.get("analyticSpecification") if np.array_equal(raw, reference) else None)
        result["localConversion"] = conversion
        result["localConversionDecision"] = conversion["decision"]
        result["decision"] = worst([b["decision"] for b in result["boundaries"]] + [conversion["decision"]])
        result["jobAdmissionDecision"] = {"PASS_WITHIN_BOUND": "JOB_TIMING_VERIFIED_WITHIN_BOUND", "FAIL_TIMING": "JOB_TIMING_FAILED", "UNVERIFIED": "JOB_TIMING_UNVERIFIED"}[result["decision"]]
        result["reference"] = ref
    except OutsideProfile as exc:
        result.update(decision="UNVERIFIED", structuralDecision="STRUCTURAL_TIMELINE_UNVERIFIED", reason=str(exc))
    except (KeyError, TypeError, AttributeError, ValueError, OSError, wave.Error, EOFError) as exc:
        result.update(decision="FAIL_INTEGRITY", structuralDecision="STRUCTURAL_TIMELINE_INVALID",
                      jobAdmissionDecision="JOB_TIMING_UNVERIFIED", reason=str(exc))
    return result


def relative_timing(results: list[dict]) -> dict:
    """Compare measured clock errors, never unrelated source waveforms."""
    comparisons = []
    for index, left in enumerate(results):
        for right in results[index + 1:]:
            same_clock = (left.get("binding", {}).get("sourceId") == right.get("binding", {}).get("sourceId") and
                          left.get("artifacts", {}).get("upload", {}).get("sha256") == right.get("artifacts", {}).get("upload", {}).get("sha256"))
            if not same_clock:
                comparisons.append({"decision": "UNVERIFIED", "reason": "NO_SHARED_SOURCE_CLOCK"})
                continue
            left_windows = {(b["boundary"], w["channel"], w["startFrame"]): w
                            for b in left["boundaries"] for w in b["windows"]}
            right_windows = {(b["boundary"], w["channel"], w["startFrame"]): w
                             for b in right["boundaries"] for w in b["windows"]}
            keys = sorted(left_windows.keys() | right_windows.keys())
            if not keys:
                comparisons.append({"decision": "UNVERIFIED", "reason": "NO_JOINTLY_MEASURED_SUPPORT"})
            for key in keys:
                a, b = left_windows.get(key), right_windows.get(key)
                item = {"leftRole": left["binding"]["selectedRole"], "rightRole": right["binding"]["selectedRole"],
                        "boundary": key[0], "channel": key[1], "startFrame": key[2], "decision": "UNVERIFIED"}
                if a and b and all("delaySamples48k" in w and "uncertaintySamples48k" in w for w in (a, b)):
                    difference = a["delaySamples48k"] - b["delaySamples48k"]
                    uncertainty = a["uncertaintySamples48k"] + b["uncertaintySamples48k"]
                    decision = interval_decision(difference, uncertainty)
                    if any(w.get("reason", "").startswith("OUTSIDE_") for w in (a, b)):
                        decision = "UNVERIFIED"
                    item.update(relativeLagSamples48k=difference, uncertaintySamples48k=uncertainty, decision=decision)
                comparisons.append(item)
    return {"decision": worst(c["decision"] for c in comparisons), "comparisons": comparisons,
            "maximumRelativeLagSamples48k": max((abs(c["relativeLagSamples48k"]) for c in comparisons if "relativeLagSamples48k" in c), default=None)}


def analyze(path: Path) -> dict:
    document = manifest_document(path)
    if "targets" not in document:
        result = analyze_manifest(path)
        result["bindingCounts"] = binding_counts([result])
        return result
    if document.get("schemaVersion") != 1 or not document["targets"]:
        raise ValueError("Bundle needs nonempty hashed target manifests")
    results = []
    for target in document["targets"]:
        manifest = Path(target["manifest"])
        manifest = manifest if manifest.is_absolute() else path.parent / manifest
        if sha256(manifest) != target["sha256"]:
            raise ValueError("Bundle target manifest hash changed")
        results.append(analyze_manifest(manifest))
    relative = relative_timing(results) if len(results) > 1 else None
    decisions = [r["decision"] for r in results] + ([relative["decision"]] if relative else [])
    return {"schemaVersion": 1, "analyzerVersion": VERSION, "analyzerSha256": sha256(Path(__file__)),
            "manifestPath": str(path.resolve()), "manifestSha256": sha256(path), "profile": PROFILE, "runtime": runtime_identity(), "decision": worst(decisions), "targets": results,
            "relativeTiming": relative, "bindingCounts": binding_counts(results)}


def binding_counts(results: list[dict]) -> dict:
    inventory = load_inventory()
    grouped = {}
    configured = {(c["configurationId"], selected): c for c in inventory["configurations"] for selected in c["outputBindings"]}
    for result in results:
        binding = result.get("binding", {})
        key = (binding.get("configurationId"), binding.get("selectedRole"))
        route = configured.get(key)
        if route and route["options"] == binding.get("options") and route["renderId"] == binding.get("renderId") and route["outputBindings"][key[1]] == binding.get("providerRole"):
            grouped.setdefault(key, []).append(result)
    decisions = {key: worst(item["decision"] for item in items) for key, items in grouped.items()}
    passed = sum(d == "PASS_WITHIN_BOUND" for d in decisions.values())
    failed = sum(d == "FAIL_TIMING" for d in decisions.values())
    return {"configured": len(configured), "measured": 0, "passed": 0, "failed": 0, "unverified": len(configured), "unavailable": 0,
            "availabilityUnknown": len(configured),
            "suppliedMeasurementResults": {"bindings": len(grouped), "measured": sum(any(i["boundaries"] for i in items) for items in grouped.values()),
                "passed": passed, "failedTiming": failed, "invalidIntegrity": sum(d == "FAIL_INTEGRITY" for d in decisions.values()),
                "invalidInputs": sum(r["decision"] == "FAIL_INTEGRITY" for r in results),
                "unverified": sum(d == "UNVERIFIED" for d in decisions.values())},
            "qualificationLimitation": "Synthetic analyzer controls do not qualify a musical model route"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--inventory", action="store_true")
    mode.add_argument("--self-check", action="store_true")
    mode.add_argument('--runtime-check', action='store_true')
    mode.add_argument("--analyze", type=Path, metavar="MANIFEST")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        if args.output and not args.analyze:
            raise ValueError("--output belongs to --analyze; inventory/self-check print JSON")
        if args.runtime_check:
            identity = runtime_identity()
            status = runtime_status(identity)
            print(json.dumps({'analyzerRuntimeStatus': status, 'required': PROFILE['calibratedRuntime'],
                              'observed': {key: identity.get(key) for key in PROFILE['calibratedRuntime']}}, sort_keys=True))
            return 0 if status == 'CALIBRATED_RUNTIME' else 2
        elif args.inventory:
            result = inventory_report()
        elif args.self_check:
            numerical_modules()
            status = runtime_status()
            print('Source timing calibration prerequisite: '+status, file=sys.stderr)
            checks = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern="test_source_timing.py")
            execution = unittest.TextTestRunner(stream=sys.stderr, verbosity=1).run(checks)
            result = {"schemaVersion": 1, "analyzerVersion": VERSION, "selfCheck": "PASS" if execution.wasSuccessful() else "FAIL", "tests": execution.testsRun, "profile": PROFILE}
            result["runtime"] = runtime_identity()
            result['analyzerRuntimeStatus'] = status
            result["calibrationObservations"] = getattr(sys.modules["test_source_timing"].TimingChecks, "calibration_observations", {})
            result["longSparseDiagnostic"] = getattr(sys.modules["test_source_timing"].TimingChecks, "long_diagnostic", {})
            print(json.dumps(result, sort_keys=True, allow_nan=False))
            return 0 if execution.wasSuccessful() else 1
        else:
            if args.output is None:
                raise ValueError("--analyze requires --output in an existing Test suite/work directory")
            validate_output(args.analyze, args.output)
            numerical_modules()
            try:
                result = analyze(args.analyze)
            except (ValueError, TypeError, AttributeError, KeyError, OSError, wave.Error, EOFError) as exc:
                result = integrity_failure(args.analyze, exc)
            result["command"] = [sys.executable, "-B", str(Path(__file__).resolve()), *sys.argv[1:]]
        if args.output:
            validate_output(args.analyze, args.output)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=args.output.parent, suffix=".tmp", delete=False) as writer:
                    temporary = Path(writer.name)
                    writer.write(json.dumps(result, indent=2, sort_keys=True, allow_nan=False) + "\n")
                os.replace(temporary, args.output)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        print(json.dumps(result, sort_keys=True, allow_nan=False))
        return {"PASS_WITHIN_BOUND": 0, "FAIL_TIMING": 1, "FAIL_INTEGRITY": 1, "UNVERIFIED": 2}.get(result.get("decision"), 0)
    except (ValueError, OSError, KeyError) as exc:
        print(f"SOURCE TIMING INCOMPLETE: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
