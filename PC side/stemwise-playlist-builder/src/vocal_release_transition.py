"""Local vocal evidence for a lexically identified terminal phone."""
import math

import numpy as np


FORMAT = 'vocal-spectral-transition-v1'
FRAME_MS = 32
HOP_MS = 10
GUARD_FRAMES = 6
# Integrate formant bands rather than requiring the voice to stay on one note.
BANDS_HZ = (120, 260, 410, 600, 820, 1070, 1370, 1720, 2140, 2630, 3260, 4070, 5100, 6500)


def _spectrum(samples, frame, hop, fft):
    frames = np.lib.stride_tricks.sliding_window_view(samples, frame)[::hop]
    windowed = (frames - frames.mean(axis=1, keepdims=True)) * np.hanning(frame)
    power = np.abs(np.fft.rfft(windowed, n=fft)) ** 2
    return windowed, power


def _band_energy(power, frequencies):
    return np.column_stack([power[:, (frequencies >= low) & (frequencies < high)].sum(axis=1)
                            for low, high in zip(BANDS_HZ, BANDS_HZ[1:])])


def extract(vocals, backing, sample_rate, origin_ms):
    """Extract compact frames from two co-timed mono streams at their original relative gain."""
    if not isinstance(sample_rate, (int, np.integer)) or sample_rate < 8000 or not math.isfinite(origin_ms):
        raise ValueError('Invalid release measurement clock')
    frame, hop = round(sample_rate * FRAME_MS / 1000), round(sample_rate * HOP_MS / 1000)
    v, b = (np.asarray(x, dtype='float64') for x in (vocals, backing))
    if (v.ndim != 1 or v.shape != b.shape or len(v) < frame
            or not np.isfinite(v).all() or not np.isfinite(b).all()):
        raise ValueError('Invalid pair of release measurement samples')
    fft = 1 << (2 * frame - 1).bit_length()
    freq = np.fft.rfftfreq(fft, 1 / sample_rate)
    windowed, power = _spectrum(v, frame, hop, fft)
    bands = _band_energy(power, freq)
    _, other_power = _spectrum(b, frame, hop, fft)
    other_bands = _band_energy(other_power, freq)
    energy = bands.sum(axis=1)
    floor = np.maximum(energy, 1e-18)
    envelope = np.sqrt(bands / floor[:, None])
    vocal_contrast = np.maximum(bands - other_bands, 0).sum(axis=1) / floor

    # Normalized periodicity of the vocal frame, allowing different pitches in successive frames.
    corr = np.fft.irfft(power, n=fft)[:, :frame]
    squares = np.column_stack((np.zeros(len(windowed)), np.cumsum(windowed ** 2, axis=1)))
    lags = np.arange(round(sample_rate / 700), round(sample_rate / 75) + 1)
    norm = np.sqrt(squares[:, frame - lags] * np.maximum(0, squares[:, -1:] - squares[:, lags]))
    periodic = np.clip((corr[:, lags] / np.maximum(norm, 1e-18)).max(axis=1), 0, 1)
    frames = np.column_stack((10 * np.log10(floor), periodic, vocal_contrast, envelope))
    return {'format': FORMAT, 'originMs': float(origin_ms), 'sampleRate': int(sample_rate),
            'sampleCount': len(v), 'frames': frames.round(5).tolist()}


def _read(features):
    if features.get('format') != FORMAT:
        raise ValueError('Unknown release measurement format')
    rate, origin, count = (features[k] for k in ('sampleRate', 'originMs', 'sampleCount'))
    if type(rate) is not int or rate < 8000 or not math.isfinite(origin) or type(count) is not int:
        raise ValueError('Invalid release evidence clock')
    frame, hop = round(rate * FRAME_MS / 1000), round(rate * HOP_MS / 1000)
    data = np.asarray(features['frames'], dtype='float64')
    shape = ((count - frame) // hop + 1, 3 + len(BANDS_HZ) - 1)
    if (count < frame or data.shape != shape or not np.isfinite(data).all()
            or np.any((data[:, 1:] < 0) | (data[:, 1:] > 1))):
        raise ValueError('Invalid release evidence frames')
    time = origin + (frame / 2 + np.arange(len(data)) * hop) * 1000 / rate
    return time, data[:, 0], data[:, 1], data[:, 2], data[:, 3:]


def _core(time, start, end):
    # Partial cores outside the crop cannot identify this phone's harmonic body.
    if (not math.isfinite(start) or not math.isfinite(end) or start >= end
            or start < time[0] - FRAME_MS/2 or end > time[-1] + FRAME_MS/2):
        return np.array([], dtype=int)
    return np.flatnonzero((time >= start) & (time <= end))


def decide(features, vowel_start_ms, vowel_end_ms, release_guess_ms, *, following_phone_ms=None):
    """Measure a voiced terminal phone's exit into quiet after its independently matched core.

    The caller supplies terminal ownership. A subsequent audible coda requires its own measurement.
    A proposed end limits the competing region. A following phone can censor an exit, never set it.
    """
    if not math.isfinite(release_guess_ms) or following_phone_ms is not None and not math.isfinite(following_phone_ms):
        return None
    time, level, periodic, contrast, envelope = _read(features)
    core = _core(time, vowel_start_ms, vowel_end_ms)
    if not len(core) or np.median(periodic[core]) < .55 or np.median(contrast[core]) < .4:
        return None
    voice = np.median(envelope[core], axis=0)
    voice /= max(np.linalg.norm(voice), 1e-12)
    relative = level - np.median(level[core])
    match = (envelope @ voice >= .8) & (periodic >= .5) & (contrast >= .25)
    quiet = (relative <= -18) & ~match
    ceiling = min(time[-1], release_guess_ms + 400)
    if following_phone_ms is not None:
        ceiling = min(ceiling, following_phone_ms)
    last_match = core[-1]
    decay_floor = float('inf')
    for i in range(last_match+1, len(time)-GUARD_FRAMES+1):
        if time[i+GUARD_FRAMES-1] > ceiling:
            break
        if quiet[i:i+GUARD_FRAMES].all():
            # A release needs a full measured guard inside the recording.
            return int(round(time[i])) if abs(time[i] - release_guess_ms) <= 400 else None
        if match[i] and not quiet[i]:
            last_match, decay_floor = i, float('inf')
        elif i-last_match >= GUARD_FRAMES:
            # A fading phone can change colour as its upper harmonics disappear.
            # A differently coloured new excitation cannot extend the target.
            prior = np.median(relative[max(0, last_match-GUARD_FRAMES+1):last_match+1])
            current = np.median(relative[max(last_match+1, i-2):i+1])
            if current > prior-3 or current > decay_floor+3 or periodic[i] < .4 and relative[i] > -12:
                return None
            decay_floor = min(current, decay_floor)
    return None


def decide_transition(features, left_start_ms, left_end_ms, right_start_ms, right_end_ms, release_guess_ms):
    """Locate a single abrupt spectral handoff between independently owned adjacent voiced phones."""
    if left_end_ms > right_start_ms or not math.isfinite(release_guess_ms):
        return None
    time, level, periodic, contrast, envelope = _read(features)
    left, right = _core(time, left_start_ms, left_end_ms), _core(time, right_start_ms, right_end_ms)
    if min(len(left), len(right)) < 3:
        return None
    for core in (left, right):
        if np.median(periodic[core]) < .55 or np.median(contrast[core]) < .4:
            return None
    first, last = np.median(envelope[left], axis=0), np.median(envelope[right], axis=0)
    delta = last-first
    separation = np.linalg.norm(delta)
    scatter = max(np.max(np.linalg.norm(envelope[left]-first, axis=1)),
                  np.max(np.linalg.norm(envelope[right]-last, axis=1)))
    # Two distinct spectral envelopes must be resolvable above each core's variation.
    if separation < .35 or separation <= 3*scatter:
        return None
    span = np.arange(left[0], right[-1]+1)
    level_floor = min(np.median(level[left]), np.median(level[right])) - 12
    if (level[span].min() < level_floor or contrast[span].min() < .2 or periodic[span].min() < .4):
        return None
    displacement = envelope[span] - first
    phase = (displacement @ delta) / separation**2
    if np.max(np.linalg.norm(displacement - phase[:, None]*delta, axis=1)) > .5*separation:
        return None  # A third intervening phone or singer.
    switches = np.flatnonzero(np.diff(phase >= .5)) + 1
    if len(switches) != 1:
        return None
    at = switches[0]
    if at < 3 or at+3 >= len(span) or phase[at-3] > .2 or phase[at+3] < .8:
        return None  # A broad vowel glide has no unique word boundary.
    edge = (time[span[at]] + time[span[at-1]])/2
    if left_end_ms > edge or edge > right_start_ms or abs(edge-release_guess_ms) > 400:
        return None
    return int(round(edge))

def decide_attack(features, first_phone_start_ms, first_phone_end_ms, start_guess_ms, *, previous_phone_ms=None):
    """Measure the attack of an independently recognized initial voiced phone.

    This requires a rising vocal signal and either preceding quiet, a distinct
    spectral envelope, or disruption and re-excitation of a periodic voice. A
    continuous legato note or change of gain in an ongoing word is insufficient.
    """
    if not math.isfinite(start_guess_ms) or previous_phone_ms is not None and not math.isfinite(previous_phone_ms):
        return None
    time, level, periodic, contrast, envelope = _read(features)
    core = _core(time, first_phone_start_ms, first_phone_end_ms)
    if not len(core) or np.median(contrast[core]) < .4:
        return None
    mid = time[core[len(core)//2]]
    # The phone marker may land on its nucleus, after the attack. Inspect
    # the lead-in as well, with complete pre- and post-attack observations.
    low = max(mid-120, previous_phone_ms if previous_phone_ms is not None else time[0])
    window = np.flatnonzero((time >= low) & (time <= mid+HOP_MS))
    candidates = []
    for i in window:
        if i < GUARD_FRAMES or i+GUARD_FRAMES >= len(time) or abs(time[i]-start_guess_ms) > 400:
            continue
        pre = slice(i-6, i-1)
        post = slice(i+1, i+5)
        baseline, excited = np.median(level[pre]), np.median(level[post])
        rise = excited-baseline
        if rise < 6 or np.median(contrast[post]) < .5 or np.max(periodic[post]) < .6:
            continue
        before, after = np.median(envelope[pre], axis=0), np.median(envelope[post], axis=0)
        before /= max(np.linalg.norm(before), 1e-12)
        after /= max(np.linalg.norm(after), 1e-12)
        # Energy outside the previous formant pattern cannot be explained
        # by increasing the volume of that continuing phone.
        novelty = 1 - (before @ after)**2
        variation = np.median(1-(envelope[pre] @ before)**2)
        quiet = (excited - np.max(level[pre]) >= 18
                 and (np.median(periodic[pre]) < .4 or np.median(contrast[pre]) < .25))
        glottal = (np.median(periodic[pre]) >= .65 and np.min(periodic[i-2:i+2]) < .4
                   and novelty > 3*max(variation, .01))
        if quiet or novelty >= .25 and novelty > 3*variation or glottal:
            candidates.append(i)
    if not candidates or np.any(np.diff(candidates) > 2):
        return None
    # Locate the steepest new attack inside the single qualifying neighbourhood.
    # Interpolate the middle of its measured rise; the phone timestamp sets no edge.
    left = max(0, candidates[0] - 2)
    right = min(len(time) - 1, candidates[-1] + 2)
    j = left + int(np.argmax(level[left+1:right+1] - level[left:right]))
    edge = (time[j] + time[j+1])/2
    if edge > mid+HOP_MS or previous_phone_ms is not None and edge <= previous_phone_ms:
        return None
    return int(round(edge))
