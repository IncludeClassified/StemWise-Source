"""Conservative local recording comparison; codec validity is not source provenance."""
from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
from pathlib import Path

import numpy as np
import stemwise_media.bounded_pcm as pcm

from stemwise_engine.common import run_process_tree
from stemwise_engine.enrichment_constants import MAX_DURATION_MS

MAX_DURATION_SECONDS = MAX_DURATION_MS / 1000

def _correlation(a, b):
    return pcm.correlation(a, b)


def _anchor(reference, block):
    """Locate a non-silent block with normalized, mean-removed correlation."""
    from scipy import signal
    reference = np.asanyarray(reference)
    block = np.asarray(block, dtype=np.float64)
    block = block - block.mean()
    energy = float(np.dot(block, block))
    n = len(block)
    if energy < n * 1e-10 or len(reference) < n:
        return None
    if len(reference) > pcm.CHUNK_FRAMES + n - 1:
        best = None
        for first in range(0, len(reference)-n+1, pcm.CHUNK_FRAMES):
            pcm.check_budget()
            located = _anchor(reference[first:first+pcm.CHUNK_FRAMES+n-1], block)
            if located is not None and (best is None or located[1] > best[1]):
                best = first+located[0], located[1]
            pcm.discard(reference)
        return best
    reference = np.asarray(reference, dtype=np.float64)
    sums = np.r_[0.0, np.cumsum(reference, dtype=np.float64)]
    squares = np.r_[0.0, np.cumsum(reference * reference, dtype=np.float64)]
    local = squares[n:] - squares[:-n] - (sums[n:] - sums[:-n]) ** 2 / n
    denominator = np.sqrt(np.maximum(local, 0) * energy)
    coefficients = np.divide(
        np.abs(signal.correlate(reference, block, mode="valid", method="fft")),
        denominator,
        out=np.zeros_like(denominator),
        where=(denominator > 1e-10) & (local >= n * 1e-10),
    )
    index = int(np.argmax(coefficients))
    return index, float(min(coefficients[index], 1.0))


def analyze_waveforms(reference, candidate, rate, *, allow_edge_edits=False, allow_fractional_delay=False):
    """Require distributed anchors and dense independent stereo agreement."""
    from scipy import signal
    reference = pcm.float64(np.asanyarray(reference))
    candidate = pcm.float64(np.asanyarray(candidate))
    if (reference.ndim != 2 or candidate.ndim != 2
            or reference.shape[1] != 2 or candidate.shape[1] != 2
            or rate < 1000 or not pcm.finite(reference)
            or not pcm.finite(candidate)):
        raise ValueError("Expected finite stereo PCM and a sample rate >= 1000")
    result = {"classification": "insufficient_audio", "fullContentSupported": False}
    if min(len(reference), len(candidate)) < 24 * rate:
        return result | {"reason": "At least 24 seconds of both recordings is required"}
    ref_mono = pcm.mono(reference)
    cand_mono = pcm.mono(candidate)
    # Avoid losing anti-phase stereo during coarse alignment.
    alignment_channel = None
    if pcm.mean_square(ref_mono) < pcm.mean_square(reference) * .01:
        alignment_channel = int(np.argmax(pcm.mean_square(reference, axis=0)))
        ref_mono = reference[:, alignment_channel]
        cand_mono = candidate[:, alignment_channel]
    coarse_rate = min(rate, 8000)
    coarse_ref = pcm.resample(ref_mono, coarse_rate, rate) if rate > coarse_rate else ref_mono
    coarse_cand = pcm.resample(cand_mono, coarse_rate, rate) if rate > coarse_rate else cand_mono
    block_length = int(8 * coarse_rate)
    anchors = []
    for fraction in (.15, .5, .85):
        pcm.check_budget()
        start = int((len(coarse_cand) - block_length) * fraction)
        located = _anchor(coarse_ref, coarse_cand[start:start + block_length])
        if located is not None:
            native_start = int(round(start * rate / coarse_rate))
            native_location = int(round(located[0] * rate / coarse_rate))
            if rate > coarse_rate and located[1] >= .95:
                left = max(0, native_location - int(.02 * rate))
                right = min(len(ref_mono), native_location + 8 * rate + int(.02 * rate))
                refined = _anchor(ref_mono[left:right], cand_mono[native_start:native_start + 8 * rate])
                if refined is not None:
                    native_location, score = left + refined[0], refined[1]
                else:
                    score = 0.0
            else:
                score = located[1]
            anchors.append({"candidateSeconds": native_start / rate,
                            "offsetSeconds": (native_location - native_start) / rate,
                            "correlation": score})
    result["anchors"] = anchors
    if len(anchors) != 3:
        return result | {"reason": "Three non-silent anchors could not be established"}
    if (min(a["correlation"] for a in anchors) < .95
            or np.ptp([a["offsetSeconds"] for a in anchors]) > .02):
        # A repeated chorus can win a global search at the wrong occurrence.
        consistent = []
        for offset in {round(a["offsetSeconds"] * rate) for a in anchors if a["correlation"] >= .95}:
            trial = []
            for anchor in anchors:
                start = round(anchor["candidateSeconds"] * rate)
                location = start + offset
                left = max(0, location - int(.02 * rate))
                right = min(len(ref_mono), location + 8 * rate + int(.02 * rate))
                located = _anchor(ref_mono[left:right], cand_mono[start:start + 8 * rate]) if right > left else None
                if located is None or located[1] < .95:
                    break
                trial.append({"candidateSeconds": start / rate,
                              "offsetSeconds": (left + located[0] - start) / rate,
                              "correlation": located[1]})
            if len(trial) == 3 and np.ptp([a["offsetSeconds"] for a in trial]) <= .02:
                consistent.append(trial)
        if consistent:
            anchors = max(consistent, key=lambda trial: min(a["correlation"] for a in trial))
            result.update(anchors=anchors, repeatedAnchorRecovery=True)
    if (allow_fractional_delay and min(a["correlation"] for a in anchors) >= .9
            and np.ptp([a["offsetSeconds"] for a in anchors]) <= .02):
        fixed_offset = round(np.median([a["offsetSeconds"] for a in anchors]) * rate)
        baseline = _compare_aligned(reference, candidate, rate, fixed_offset, result, allow_edge_edits) if min(a['correlation'] for a in anchors) >= .95 else None
        if baseline and baseline['classification'] in ('strong_recording_match', 'matching_body_with_extra_audio', 'matching_body_with_edge_edits'):
            return baseline
        rs, cs = max(fixed_offset, 0) + 1, max(-fixed_offset, 0) + 1
        count = min(len(ref_mono) - rs, len(cand_mono) - cs) - 1
        scores = [_correlation(ref_mono[rs + d:rs + d + count], cand_mono[cs:cs + count])
                  for d in (-1, 0, 1)]
        curvature = scores[0] - 2 * scores[1] + scores[2]
        shift = .5 * (scores[0] - scores[2]) / curvature if curvature < -1e-9 else 0
        shifts = [shift] if .03 < abs(shift) <= .5 else []
        if baseline is None or all(c['minimumCorrelation'] >= .95 for c in baseline.get('channels', [])):
            shifts += [-.5, -.25, .25, .5]
        for shift in shifts:
            # One fixed sub-sample delay; every window still has to pass unchanged thresholds.
            kernel = np.sinc(np.arange(-32, 33) + shift) * np.blackman(65)
            kernel /= kernel.sum()
            shifted = pcm.convolve(reference, kernel[:, None])
            trial = []
            for anchor in anchors:
                start = round(anchor["candidateSeconds"] * rate)
                location = start + fixed_offset
                if location < 0 or location + 8 * rate > len(shifted):
                    break
                trial.append(anchor | {"offsetSeconds": fixed_offset / rate,
                    "correlation": _correlation((shifted[location:location + 8 * rate].mean(axis=1) if alignment_channel is None else shifted[location:location + 8 * rate, alignment_channel]),
                                                cand_mono[start:start + 8 * rate])})
            if len(trial) == 3 and min(a["correlation"] for a in trial) >= .95:
                aligned = _compare_aligned(shifted, candidate, rate, fixed_offset,
                                          result | {"anchors": trial}, allow_edge_edits)
                if aligned["classification"] in ("strong_recording_match", "matching_body_with_extra_audio", "matching_body_with_edge_edits"):
                    aligned["fractionalReferenceShiftSamples"] = shift
                    return aligned
    if min(a["correlation"] for a in anchors) < .95:
        return result | {"classification": "not_matched", "reason": "Distributed audio anchors disagree"}
    offsets = [a["offsetSeconds"] for a in anchors]
    if max(offsets) - min(offsets) > .02:
        return result | {"classification": "needs_review", "reason": "Timing changes within the recording"}
    offset = int(round(float(np.median(offsets)) * rate))
    return _compare_aligned(reference, candidate, rate, offset, result, allow_edge_edits)


def _compare_aligned(reference, candidate, rate, offset, result, allow_edge_edits):
    ref_start, cand_start = max(offset, 0), max(-offset, 0)
    count = min(len(reference) - ref_start, len(candidate) - cand_start)
    a, b = reference[ref_start:ref_start + count], candidate[cand_start:cand_start + count]
    window = int(2 * rate)
    starts = sorted(set(range(0, max(1, count - window + 1), rate)) | {max(0, count - window)})
    correlations = [[], []]
    bad_starts = set()
    silent_windows = [0, 0]
    mismatched_silence = 0
    for start in starts:
        pcm.check_budget()
        for channel in range(2):
            x = a[start:start + window, channel]
            y = b[start:start + window, channel]
            score = pcm.channel_agreement(x, y)
            if score is None:
                silent_windows[channel] += 1
                continue
            correlations[channel].append(score)
            if score < .97:
                bad_starts.add(start)
                if score == 0.0:
                    mismatched_silence += 1
        if start % pcm.CHUNK_FRAMES < rate:
            pcm.discard(reference)
            pcm.discard(candidate)
    if not any(correlations):
        return result | {"reason": "Insufficient non-silent stereo evidence"}
    channels = [{"channel": c, "windows": len(values), "mutuallySilentWindows": silent,
                 "minimumCorrelation": min(values) if values else 1.0,
                 "medianCorrelation": float(np.median(values)) if values else 1.0}
                for c, values, silent in zip(("left", "right"), correlations, silent_windows)]
    result.update(referenceOffsetSeconds=offset / rate, overlapSeconds=count / rate,
                  channels=channels, mismatchedSilentWindows=mismatched_silence)
    if bad_starts:
        regions = []
        for start in sorted(bad_starts):
            end = min(count, start + window)
            if regions and start <= regions[-1][1]:
                regions[-1][1] = max(regions[-1][1], end)
            else:
                regions.append([start, end])
        result['differingWindows'] = [{'referenceStartSeconds': (ref_start + start) / rate,
                'referenceEndSeconds': (ref_start + end) / rate,
                'candidateStartSeconds': (cand_start + start) / rate,
                'candidateEndSeconds': (cand_start + end) / rate} for start, end in regions]
    if any(c["minimumCorrelation"] < .97 for c in channels):
        if allow_edge_edits:
            # Only small edge edits qualify; the retained body must pass the original strict check.
            limit = min(10 * rate, int(.05 * count))
            interior = any(limit <= s < count - limit for s in bad_starts)
            left = max((s + window for s in bad_starts if s < limit), default=0)
            right = min((s for s in bad_starts if s >= count - limit), default=count)
            trimmed = left + count - right
            if not interior and trimmed <= limit and right - left >= 24 * rate:
                body = analyze_waveforms(a[left:right], b[left:right], rate)
                if body["classification"] == "strong_recording_match":
                    return result | {"classification": "matching_body_with_edge_edits",
                            "reason": "Matching body; beginning or ending differs",
                            "body": {"referenceStartSeconds": (ref_start + left) / rate,
                                     "candidateStartSeconds": (cand_start + left) / rate,
                                     "durationSeconds": (right - left) / rate,
                                     "edgeDifferencesSeconds": trimmed / rate,
                                     "channels": body["channels"]}}
        return result | {"classification": "needs_review", "reason": "Dense waveform comparison differs"}
    unmatched = [reference[:ref_start], reference[ref_start + count:],
                 candidate[:cand_start], candidate[cand_start + count:]]
    extra = any(len(part) and float(pcm.mean_square(part)) > 1e-9 for part in unmatched)
    classification = "matching_body_with_extra_audio" if extra else "strong_recording_match"
    return result | {"classification": classification, "fullContentSupported": not extra,
                     "unmatchedSeconds": (len(reference) + len(candidate) - 2 * count) / rate}


def probe(path, *, timeout_seconds=30):
    if not 0 < timeout_seconds <= 60:
        raise ValueError('Recording probe deadline must be within 60 seconds')
    timeout_seconds = pcm.remaining_seconds(timeout_seconds)
    completed = run_process_tree(["ffprobe", "-v", "error", "-select_streams", "a:0",
                                "-show_entries", "stream=codec_name,sample_rate,channels,bits_per_raw_sample,duration:format=duration",
                                "-of", "json", str(path)], check=True, capture_output=True, text=True,
                                stdin=subprocess.DEVNULL, timeout=timeout_seconds, cancelled=pcm.check_budget)
    value = json.loads(completed.stdout)
    if len(value.get("streams", [])) != 1:
        raise ValueError("No supported audio stream")
    return value["streams"][0] | {"duration": float(value.get("format", {}).get("duration", 0))}


def analyze_clock_drift(reference, candidate, rate):
    """Estimate one constant clock from independent anchors, then require dense stereo proof."""
    from fractions import Fraction
    from scipy.optimize import least_squares
    reference, candidate = np.asanyarray(reference), np.asanyarray(candidate)
    rejected = {'classification': 'needs_review', 'fullContentSupported': False,
                'reason': 'No independently established constant recording clock'}
    if (type(rate) is not int or rate < 1000 or reference.ndim != 2 or candidate.ndim != 2
            or reference.shape[1] != 2 or candidate.shape[1] != 2
            or min(len(reference), len(candidate)) < 24*rate
            or not pcm.finite(reference) or not pcm.finite(candidate)):
        return rejected
    channel = int(np.argmax(pcm.mean_square(reference, axis=0)))
    coarse_rate = min(rate, 2000)
    left = pcm.resample(reference[:, channel], coarse_rate, rate)
    right = pcm.resample(candidate[:, channel], coarse_rate, rate)
    length = coarse_rate//5
    anchors = []
    for fraction in (.1, .3, .5, .7, .9):
        start = round((len(right)-length)*fraction)
        located = _anchor(left, right[start:start+length])
        if located is None or located[1] < .6:
            return rejected | {'clockDriftEvidence': {'anchors': anchors, 'failedAnchorCorrelation': None if located is None else located[1]}}
        # Repeated passages cannot select an occurrence for the clock fit.
        first, last = max(0, located[0]-length), located[0]+2*length
        alternatives = [_anchor(part, right[start:start+length]) for part in (left[:first], left[last:])]
        if any(value is not None and value[1] >= located[1]-.02 for value in alternatives):
            return rejected
        anchors.append({'candidateSample': start*rate/coarse_rate,
                        'referenceSample': located[0]*rate/coarse_rate, 'correlation': located[1]})
    x = np.array([a['candidateSample'] for a in anchors])
    y = np.array([a['referenceSample'] for a in anchors])
    ratio, offset = np.polyfit(x, y, 1)
    residual = float(np.max(np.abs(y-(ratio*x+offset))))
    span = float(np.ptp(x))
    uncertainty = 2*rate/coarse_rate/span
    if not .995 <= ratio <= 1.005 or residual > 2*rate/coarse_rate:
        return rejected
    # Refine this fixed correspondence on the same independent anchor regions;
    # the full recording remains an untouched, separate admission check below.
    positions = np.concatenate([np.arange(round(a['referenceSample'])+32,
        round(a['referenceSample'])+length*rate//coarse_rate-32, 3) for a in anchors])
    observed = np.asarray(reference[positions, channel], dtype=np.float64)
    observed = (observed-observed.mean())/max(float(observed.std()), 1e-12)
    taps = np.arange(-16, 17)
    window = np.kaiser(len(taps), 5)
    def errors(parameters):
        pcm.check_budget()
        scale = ratio+parameters[0]/span
        coordinates = (positions-offset-parameters[1])/scale
        indices = np.floor(coordinates).astype(np.int64)[:, None]+taps
        if np.min(indices) < 0 or np.max(indices) >= len(candidate):
            return np.full(len(positions), 100.)
        weights = np.sinc(coordinates[:, None]-indices)*window
        values = np.sum(candidate[indices, channel]*weights, axis=1)/weights.sum(axis=1)
        return (values-values.mean())/max(float(values.std()), 1e-12)-observed
    fit = least_squares(errors, [0., 0.], bounds=([-4*rate/coarse_rate]*2, [4*rate/coarse_rate]*2),
                        max_nfev=40)
    scale = Fraction(float(ratio+fit.x[0]/span)).limit_denominator(100000)
    evidence = {'anchors': anchors, 'maximumAnchorResidualSamples': residual,
                'ratioUncertainty': uncertainty, 'candidateTimeScale': float(scale)}
    if (not fit.success or not .995 <= float(scale) <= 1.005
            or abs(float(scale)-1) <= uncertainty):
        return rejected | {'clockDriftEvidence': evidence}
    stretched = pcm.resample(candidate, scale.numerator, scale.denominator)
    result = analyze_waveforms(reference, stretched, rate, allow_edge_edits=True, allow_fractional_delay=True)
    if result['classification'] in ('strong_recording_match', 'matching_body_with_extra_audio', 'matching_body_with_edge_edits'):
        return {'classification': 'matching_body_with_speed_change', 'fullContentSupported': False,
                'candidateTimeScale': float(scale), 'clockDriftEvidence': evidence,
                'reason': 'Matching waveform after an independently estimated constant clock adjustment',
                'alignedWaveform': result}
    return rejected | {'clockDriftEvidence': evidence, 'alignedWaveform': result}


def decode(path, rate=48000):
    from stemwise_engine.common import WorkerRetirementError
    duration = probe(path)['duration']
    if type(rate) is not int or not 1000 <= rate <= 192000 or not math.isfinite(duration) or not 0 < duration <= MAX_DURATION_SECONDS:
        raise ValueError('Compare finite recordings up to two hours at a supported sample rate')
    directory = pcm.temporary_directory()
    try:
        root = Path(directory.name)
        if shutil.disk_usage(root).free < math.ceil(duration*rate)*8 + 16*1024*1024:
            raise OSError('Insufficient scratch disk for bounded recording comparison')
        command = ['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-err_detect', 'crccheck+explode',
                   '-i', str(path), '-map', '0:a:0', '-vn', '-ar', str(rate), '-ac', '2',
                   '-t', str(MAX_DURATION_SECONDS + 1/rate), '-c:a', 'pcm_f32le', '-f', 'f32le', '-']
        with (root/'pcm.bin').open('wb') as output, (root/'error.txt').open('wb+') as errors:
            try:
                completed = run_process_tree(command, check=False, stdout=output, stderr=errors,
                    timeout=pcm.remaining_seconds(300), stdin=subprocess.DEVNULL, cancelled=pcm.check_budget, pass_fds=(directory.descriptor,))
            except subprocess.TimeoutExpired as error:
                errors.seek(max(0, errors.tell()-12000))
                error.stderr = errors.read(12000)
                raise
            if completed.returncode:
                errors.seek(max(0, errors.tell()-12000))
                raise subprocess.CalledProcessError(completed.returncode, command, stderr=errors.read(12000))
        size = (root/'pcm.bin').stat().st_size
        if not size or size % 8 or size > (MAX_DURATION_SECONDS*rate+1)*8:
            raise ValueError('Incomplete or oversized decoded stereo PCM')
        return pcm.mapped_file(root/'pcm.bin', directory, dtype='<f4', shape=(size//8, 2))
    except WorkerRetirementError:
        directory.lease.close()
        raise
    except BaseException:
        directory.cleanup()
        raise


def inspect_flac(path):
    path = Path(path)
    metadata = probe(path)
    with path.open("rb") as stream:
        header = stream.read(42)
    if metadata["codec_name"] != "flac" or header[:4] != b"fLaC" or len(header) != 42:
        raise ValueError("Candidate is not a native FLAC file")
    if header[4] & 0x7f or int.from_bytes(header[5:8], "big") != 34:
        raise ValueError("Invalid FLAC STREAMINFO")
    packed = int.from_bytes(header[18:26], "big")
    rate, channels, bits, frames = packed >> 44, ((packed >> 41) & 7) + 1, ((packed >> 36) & 31) + 1, packed & ((1 << 36) - 1)
    if rate != int(metadata["sample_rate"]) or channels != metadata["channels"]:
        raise ValueError("FLAC stream information disagrees with decoder")
    if bits not in {8, 16, 24, 32} or frames <= 0:
        raise ValueError("Unsupported or unspecified FLAC sample structure")
    codec = {8: "pcm_s8", 16: "pcm_s16le", 24: "pcm_s24le", 32: "pcm_s32le"}[bits]
    completed = run_process_tree(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-err_detect", "crccheck+explode",
                                "-i", str(path), "-map", "0:a:0", "-c:a", codec, "-f", "tee",
                                "[f=md5]pipe:1|[f=framecrc]pipe:2"],
                               check=True, capture_output=True, text=True, timeout=300, stdin=subprocess.DEVNULL)
    decoded_bytes = 0
    for line in completed.stderr.splitlines():
        fields = line.split(",")
        if len(fields) == 6 and fields[0].strip() == "0":
            decoded_bytes += int(fields[4])
    if decoded_bytes != frames * channels * (bits // 8):
        raise ValueError("FLAC decoded sample count disagrees with STREAMINFO")
    decoded_md5 = completed.stdout.strip().split("=")[-1].lower()
    stored_md5 = header[26:42].hex()
    if stored_md5 != "0" * 32 and stored_md5 != decoded_md5:
        raise ValueError("FLAC decoded PCM disagrees with stored checksum")
    return {"codec": "flac", "sampleRate": rate, "channels": channels, "bitsPerSample": bits,
            "sampleFrames": frames, "durationSeconds": frames / rate,
            "fullDecodeAndCrcPassed": True, "pcmChecksumPresent": stored_md5 != "0" * 32,
            "pcmChecksumMatches": stored_md5 == decoded_md5 if stored_md5 != "0" * 32 else None,
            "losslessHistory": "not_proven_by_file_format",
            "qobuzOrigin": "not_proven_by_audio_analysis"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    flac = inspect_flac(args.candidate)
    result = analyze_waveforms(decode(args.reference), decode(args.candidate), 48000)
    print(json.dumps({"reference": str(args.reference), "candidate": str(args.candidate),
                      "flac": flac, "waveform": result}, ensure_ascii=False))


if __name__ == "__main__":
    main()
