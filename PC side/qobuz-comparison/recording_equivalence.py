"""Find recording counterparts without requiring an identical stereo master."""
import collections
import argparse
import json
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from threading import Event

import numpy as np
from scipy import signal
from stemwise_media.compare import _anchor, _correlation
from stemwise_engine.common import WorkerRetirementError, run_process_tree
import stemwise_media.bounded_pcm as pcm

RATE = 8000


def analyze_equivalence(reference, candidate, *, deep=False):
    reference, candidate = np.asarray(reference), np.asarray(candidate)
    result = _compare_performance(reference, candidate)
    if not deep or supported(result) or min(len(reference), len(candidate)) < 24 * RATE:
        return result
    filtered = _compare_performance(reference, candidate, fixed_filter=True)
    if supported(filtered):
        return filtered
    # A short block tolerates clock drift; the full song must still pass afterward.
    start = max(0, (len(candidate) - RATE // 4) // 2)
    short = _anchor(pcm.mono(reference), candidate[start:start + RATE // 4].mean(1))
    if short and short[1] >= .5:
        for up, down in ((1001, 1000), (1000, 1001)):
            adjusted = pcm.resample(candidate, up, down)
            trial = _compare_performance(reference, adjusted, fixed_filter=True)
            if supported(trial):
                return trial | {'candidateTimeScale': up / down}
    return max((result, filtered), key=lambda r: r.get('supportedWindowFraction', 0)) | {'deepChecked': True}


def _compare_performance(reference, candidate, *, fixed_filter=False):
    reference, candidate = np.asarray(reference), np.asarray(candidate)
    if (reference.ndim != 2 or candidate.ndim != 2 or reference.shape[1] != 2
            or candidate.shape[1] != 2 or not pcm.finite(reference) or not pcm.finite(candidate)):
        raise ValueError('Expected finite stereo audio')
    result = {'classification': 'not_supported', 'fullContentSupported': False}
    if min(len(reference), len(candidate)) < 24 * RATE:
        return result | {'reason': 'Insufficient audio'}
    for channel in (0, 1):
        if (pcm.mean_square(reference[:, channel]) < 1e-9) != (pcm.mean_square(candidate[:, channel]) < 1e-9):
            return result | {'reason': 'Channel silence differs'}
    a, b = pcm.mono(reference), pcm.mono(candidate)
    if pcm.mean_square(a) < pcm.mean_square(reference) * .01 or pcm.mean_square(b) < pcm.mean_square(candidate) * .01:
        a = reference[:, np.argmax(pcm.mean_square(reference, axis=0))]
        b = candidate[:, np.argmax(pcm.mean_square(candidate, axis=0))]
    anchors = []
    for fraction in (.15, .5, .85):
        start = round((len(candidate) - 8 * RATE) * fraction)
        found = _anchor(a, b[start:start + 8 * RATE])
        if found:
            anchors.append({'candidateSeconds': start / RATE,
                'offsetSeconds': (found[0] - start) / RATE, 'correlation': found[1]})
    result['anchors'] = anchors
    threshold = .5 if fixed_filter else .85
    if fixed_filter and len(anchors) == 3 and np.ptp([a['offsetSeconds'] for a in anchors]) > .02:
        recovered = []
        for seed in anchors:
            if seed['correlation'] < threshold:
                continue
            trial = []
            for anchor in anchors:
                start = round(anchor['candidateSeconds'] * RATE)
                expected = start + round(seed['offsetSeconds'] * RATE)
                left, right = max(0, expected - 160), min(len(a), expected + 8 * RATE + 160)
                found = _anchor(a[left:right], b[start:start + 8 * RATE]) if right > left else None
                if not found or found[1] < threshold:
                    break
                trial.append(anchor | {'offsetSeconds': (left + found[0] - start) / RATE, 'correlation': found[1]})
            if len(trial) == 3 and np.ptp([v['offsetSeconds'] for v in trial]) <= .02:
                recovered.append(trial)
        if recovered:
            anchors = max(recovered, key=lambda values: min(v['correlation'] for v in values))
    if (len(anchors) != 3 or min(a['correlation'] for a in anchors) < threshold
            or np.ptp([a['offsetSeconds'] for a in anchors]) > .02):
        return result | {'reason': 'No consistent distributed performance alignment'}
    offset = round(np.median([a['offsetSeconds'] for a in anchors]) * RATE)
    rs, cs = max(offset, 0), max(-offset, 0)
    count = min(len(reference) - rs, len(candidate) - cs)
    if count < 24 * RATE or count < .85 * min(len(reference), len(candidate)) or count < .5 * max(len(reference), len(candidate)):
        return result | {'reason': 'Only a small excerpt overlaps'}
    a, b = reference[rs:rs + count], candidate[cs:cs + count]
    training = np.concatenate([np.arange(round(f * (count - 8 * RATE)), round(f * (count - 8 * RATE)) + 8 * RATE, 8)
                               for f in (.15, .5, .85)])
    # One channel matrix for the whole song; never fit individual checking windows.
    features = np.concatenate([b[training + shift] for shift in range(-8, 9)], axis=1) if fixed_filter else b[training]
    matrix = np.linalg.lstsq(features, a[training], rcond=None)[0]
    if not np.isfinite(matrix).all() or np.abs(matrix).max() > 8:
        return result | {'reason': 'Unstable stereo mapping'}
    if fixed_filter:
        taps = matrix.reshape(17, 2, 2)
        mapped = pcm.stereo_filter(b, taps)
        result.update(compensation='fixed_stereo_filter', filterCoefficients=taps.tolist(), deepChecked=True)
        verified = []
        alignment_reference, alignment_candidate = pcm.mono(a), pcm.mono(mapped)
        if pcm.mean_square(alignment_reference) < pcm.mean_square(a) * .01:
            channel = np.argmax(pcm.mean_square(a, axis=0))
            alignment_reference, alignment_candidate = a[:, channel], mapped[:, channel]
        for fraction in (.15, .5, .85):
            start = round((count - 8 * RATE) * fraction)
            score = _correlation(alignment_reference[start:start + 8 * RATE], alignment_candidate[start:start + 8 * RATE])
            verified.append({'candidateSeconds': (cs + start) / RATE, 'offsetSeconds': offset / RATE, 'correlation': score})
        anchors = verified
    else:
        mapped = pcm.channel_map(b, matrix)
        result['channelMapping'] = matrix.tolist()
    result['anchors'] = anchors
    scores, spans = [], []
    starts = sorted(set(range(0, count - 2 * RATE + 1, RATE)) | {count - 2 * RATE})
    for start in starts:
        x, y = a[start:start + 2 * RATE], mapped[start:start + 2 * RATE]
        if max(float(np.mean(x * x)), float(np.mean(y * y))) < 1e-9:
            continue
        channels = [pcm.channel_agreement(x[:, channel], y[:, channel]) for channel in (0, 1)]
        channels = [1.0 if score is None else score for score in channels]
        scores.append(channels)
        if min(channels) < .95:
            left, right = (rs + start) / RATE, (rs + start + 2 * RATE) / RATE
            if spans and left <= spans[-1][1]:
                spans[-1][1] = right
            else:
                spans.append([left, right])
        if start % pcm.CHUNK_FRAMES < RATE:
            pcm.discard(a)
            pcm.discard(mapped)
    if len(scores) < 20:
        return result | {'reason': 'Insufficient non-silent evidence'}
    scores = np.asarray(scores)
    coverage = float(np.mean(scores.min(1) >= .90))
    medians = np.median(scores, axis=0).tolist()
    result.update(referenceOffsetSeconds=offset / RATE, overlapSeconds=count / RATE,
        channelMedians=medians,
        checkedWindows=len(scores), supportedWindowFraction=coverage, differentReferenceWindows=spans)
    if coverage >= .90 and min(medians) >= .95 and min(v['correlation'] for v in anchors) >= .85:
        result['classification'] = 'same_performance_with_differences'
    else:
        result['reason'] = 'Only parts of the performance agree'
    return result


def supported(result):
    anchors = result.get('anchors', [])
    return bool(result.get('classification') == 'same_performance_with_differences'
            and result.get('checkedWindows', 0) >= 20 and result.get('supportedWindowFraction', 0) >= .90
            and len(result.get('channelMedians', [])) == 2 and min(result['channelMedians']) >= .95
            and len(anchors) == 3 and min(a['correlation'] for a in anchors) >= .85
            and np.ptp([a['offsetSeconds'] for a in anchors]) <= .02)


def fingerprint(path, *, cancelled=None):
    if cancelled is not None and cancelled():
        raise InterruptedError('Fingerprint discovery cancelled')
    result = run_process_tree(['ffmpeg', '-nostdin', '-v', 'error', '-i', str(path), '-map', '0:a:0',
        '-f', 'chromaprint', '-fp_format', 'raw', 'pipe:1'], capture_output=True, check=True,
        timeout=300, stdin=subprocess.DEVNULL, cancelled=cancelled)
    return np.frombuffer(result.stdout, dtype='<u4')


def fingerprint_index(items, *, fuzzy=False):
    index = collections.defaultdict(list)
    for identity, words in items.items():
        for position, word in enumerate(words):
            for shift in (0, 8, 16, 24) if fuzzy else (0, 16):
                index[(shift, (int(word) >> shift) & (255 if fuzzy else 65535))].append((identity, position, int(word)))
    return index


def discover(words, index, *, fuzzy=False, limit=8):
    votes = collections.Counter()
    for position in range(0, len(words), 8):
        word = int(words[position])
        for shift in (0, 8, 16, 24) if fuzzy else (0, 16):
            for identity, location, other in index.get((shift, (word >> shift) & (255 if fuzzy else 65535)), ()):
                if (word ^ other).bit_count() > 6:
                    continue
                votes[(identity, round((location - position) / 4))] += 1
    peaks = {}
    for (identity, _), count in votes.items():
        peaks[identity] = max(peaks.get(identity, 0), count)
    return [identity for identity, count in sorted(peaks.items(), key=lambda pair: pair[1], reverse=True)[:limit] if count >= 8]


def main():
    import stemwise_media.recording_review as review
    root = review.batch.ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--deep', action='store_true', help='Broader fingerprint search and fixed filter/clock compensation')
    deep = parser.parse_args().deep
    data = review.batch.read_catalogue(review.batch.ROOT / 'catalogue.json')
    library = json.loads((root / 'comparison-results/library-files.json').read_text(encoding='utf-8'))
    paths, skipped = {}, []
    entries = [('youtube', row['position'], review.batch.source_file, row['videoId']) for row in data['rows']]
    entries += [('flac', t['trackId'], review.batch.qobuz_file, t['trackId']) for t in library['flac']]
    for source, identity, resolve, argument in entries:
        reason = 'Retained audio unavailable or rejected'
        try:
            path = resolve(argument)
        except InterruptedError:
            raise
        except (OSError, ValueError) as error:
            path, reason = None, review.batch.safe_error(error)
        if path is None:
            skipped.append({'source': source, 'trackId' if source == 'flac' else 'position': identity, 'reason': reason})
        else:
            paths[(source, identity)] = path
    started, fingerprints = time.monotonic(), {}
    cancelled = Event()
    pool = ThreadPoolExecutor(max_workers=2)
    try:
        futures = {pool.submit(fingerprint, path, cancelled=cancelled.is_set): key for key, path in paths.items()}
        for future in as_completed(futures):
            source, identity = futures[future]
            try:
                fingerprints[(source, identity)] = future.result()
            except (WorkerRetirementError, InterruptedError):
                raise
            except Exception as error:
                skipped.append({'source': source, 'trackId' if source == 'flac' else 'position': identity,
                                'reason': review.batch.safe_error(error)})
                continue
            if len(fingerprints) % 100 == 0:
                print(json.dumps({'fingerprints': len(fingerprints), 'total': len(paths)}), flush=True)
    finally:
        cancelled.set()
        pool.shutdown(wait=True, cancel_futures=True)
    youtube_index = fingerprint_index({i[1]: f for i, f in fingerprints.items() if i[0] == 'youtube'}, fuzzy=deep)
    flac_index = fingerprint_index({i[1]: f for i, f in fingerprints.items() if i[0] == 'flac'}, fuzzy=deep)
    paths = {key: path for key, path in paths.items() if key in fingerprints}
    evidence = review.batch.ComparisonEvidence()
    for source, identity, _, argument in entries:
        if (source, identity) not in paths:
            if source == 'flac':
                evidence.candidates[identity] = None
            else:
                evidence.sources[argument] = None
    admitted_track_ids = {identity for source, identity in paths if source == 'flac'}
    paired = review.paired_tracks(data, evidence=evidence) | {
        c['trackId'] for row in data['rows'] for c in review.equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids)}
    proposed = collections.defaultdict(set)
    for row in data['rows']:
        strict = row.get('refinedAudioComparison', {}).get('candidates', [])
        if not review.current_matches(row, evidence=evidence) and not review.equivalences(row, evidence=evidence, admitted_track_ids=admitted_track_ids) and ('youtube', row['position']) in fingerprints:
            proposed[row['position']].update(c['trackId'] for c in strict if ('flac', c['trackId']) in paths)
            proposed[row['position']].update(discover(fingerprints[('youtube', row['position'])], flac_index, fuzzy=deep, limit=32 if deep else 8))
    for track in library['flac']:
        if ('flac', track['trackId']) in fingerprints and track['trackId'] not in paired:
            for position in discover(fingerprints[('flac', track['trackId'])], youtube_index, fuzzy=deep, limit=32 if deep else 8):
                proposed[position].add(track['trackId'])
    del fingerprints, youtube_index, flac_index
    measured, accepted, cached = 0, 0, 0
    for row in data['rows']:
        ids = proposed.get(row['position'], set()) - set(row.get('rejectedTrackIds', []))
        if not ids:
            continue
        previous = row.get('recordingEquivalence', {})
        prior_ids = {c['trackId'] for c in previous.get('candidates', [])
                     if ('flac', c['trackId']) in paths and c['trackId'] not in row.get('rejectedTrackIds', [])}
        reference_file = paths[('youtube', row['position'])].relative_to(root).as_posix()
        reference_sha = review.batch.file_sha256(paths[('youtube', row['position'])])
        candidate_hashes = {i: review.batch.file_sha256(paths[('flac', i)]) for i in ids | prior_ids}
        reusable = (previous.get('sampleRate') == RATE and previous.get('referenceFile') == reference_file
                    and previous.get('referenceSha256') == reference_sha and previous.get('recipeVersion') == 1)
        results = {c['trackId']: c for c in previous.get('candidates', [])
                   if reusable and ('flac', c['trackId']) in paths
                   and c.get('candidateSha256') == candidate_hashes.get(c['trackId'])
                   and c.get('candidateSha256') is not None
                   and c.get('candidateFile') == paths[('flac', c['trackId'])].relative_to(root).as_posix()}
        todo = [i for i in sorted(ids) if i not in results
                or results[i].get('classification') == 'error'
                or deep and results[i].get('deepChecked') is not True]
        cached += len(ids) - len(todo)
        if not todo:
            continue
        reference_error = None
        try:
            reference = review.batch.decode(paths[('youtube', row['position'])], RATE)
        except (WorkerRetirementError, InterruptedError):
            raise
        except Exception as error:
            reference_error = error
        for track_id in todo:
            try:
                if reference_error is not None:
                    raise reference_error
                candidate = review.batch.decode(paths[('flac', track_id)], RATE)
                result = analyze_equivalence(reference, candidate, deep=deep)
            except (WorkerRetirementError, InterruptedError):
                raise
            except Exception as error:
                result = {'classification': 'error', 'reason': review.batch.safe_error(error)}
            if review.batch.file_sha256(paths[('flac', track_id)]) != candidate_hashes[track_id]:
                raise ValueError('Compared candidate audio changed')
            results[track_id] = result | {'trackId': track_id, 'deepChecked': deep,
                'candidateSha256': candidate_hashes[track_id],
                'candidateFile': paths[('flac', track_id)].relative_to(root).as_posix()}
            measured += 1
            accepted += supported(result)
        if review.batch.file_sha256(paths[('youtube', row['position'])]) != reference_sha:
            raise ValueError('Compared reference audio changed')
        row['recordingEquivalence'] = {'sampleRate': RATE, 'referenceSha256': reference_sha, 'recipeVersion': 1,
            'referenceFile': reference_file, 'candidates': list(results.values())}
        review.batch.write_json(review.batch.ROOT / 'catalogue.json', data)
        print(json.dumps({'position': row['position'], 'comparedPairs': measured, 'supportedPairs': accepted,
            'elapsedSeconds': round(time.monotonic() - started)}), flush=True)
    print(json.dumps({'comparedPairs': measured, 'supportedPairs': accepted, 'reusedDeepPairs': cached, 'skippedEntries': skipped,
        'elapsedSeconds': round(time.monotonic() - started)}), flush=True)


if __name__ == '__main__':
    main()
