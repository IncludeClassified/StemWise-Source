"""Untrusted prior timing hints admitted against an unconstrained native verifier.

These intervals only propose search windows. They cannot approve or publish timing.
"""
import hashlib
import json
import math
from pathlib import Path
from statistics import median

from stemwise_engine.common import (read_bounded_json, read_bounded_regular_bytes, resolve_path,
                    validate_generated_path, validate_video_id)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyrics_alignment import (build_lexical_occurrence_mapping,
                              lexical_mapping_identity)


def _map_hints(timings, mapping, native_verifier, duration_ms):
    occurrences = mapping['performedOccurrences']
    if (len(timings) < 3 or len(timings) != len(occurrences)
            or len(native_verifier) != len(timings)):
        return None
    anchors = []
    previous_end = 0
    for index, (old, occurrence, native) in enumerate(zip(timings, occurrences, native_verifier)):
        start, end = old['startMs'], old['endMs']
        if (type(start) is not int or type(end) is not int
                or not 0 <= previous_end <= start < end):
            return None
        previous_end = end
        if native is None:
            continue
        if (native.get('route') != 'wav2vec2-ctc-forced-on-mix'
                or native.get('lineId') != occurrence['occurrenceId']
                or [word.get('wordId') for word in native.get('words', ())]
                   != [word['occurrenceWordId'] for word in occurrence['words']]):
            return None
        native_start, native_end = native['startMs'], native['endMs']
        if (type(native_start) is not int or type(native_end) is not int
                or not 0 <= native_start < native_end <= duration_ms):
            return None
        confidence = [native.get('confidence')] + [word.get('confidence') for word in native['words']]
        if (all(type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1
                for value in confidence)
                and confidence[0] >= .1 and median(confidence[1:]) >= .1):
            anchors.append((index, start, end, native_start, native_end))
    if len(anchors) < 3:
        return None
    offset = median((row[3] + row[4] - row[1] - row[2]) / 2 for row in anchors)
    inliers = [row for row in anchors if max(abs(row[3] - row[1] - offset),
                                           abs(row[4] - row[2] - offset)) <= 300]
    # A few agreeing adjacent words cannot establish a song-wide time map.
    if (len(inliers) < 3 or len(inliers) < len(anchors) / 2
            or inliers[0][0] > .2 * (len(timings) - 1)
            or inliers[-1][0] < .8 * (len(timings) - 1)
            or inliers[-1][2] - inliers[0][1] < .6 * (timings[-1]['endMs'] - timings[0]['startMs'])):
        return None
    xs = [(row[1] + row[2]) / 2 for row in inliers]
    ys = [(row[3] + row[4]) / 2 for row in inliers]
    x_mean, y_mean = sum(xs) / len(xs), sum(ys) / len(ys)
    variance = sum((value - x_mean) ** 2 for value in xs)
    if variance <= 0:
        return None
    scale = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys)) / variance
    offset = y_mean - scale * x_mean
    # Conservative hint admission, never endpoint confidence or a publication gate.
    if (not .998 <= scale <= 1.002
            or any(abs(old * scale + offset - native) > 300
                   for _, start, end, native_start, native_end in inliers
                   for old, native in ((start, native_start), (end, native_end)))):
        return None
    hints = [{'startMs': round(row['startMs'] * scale + offset),
              'endMs': round(row['endMs'] * scale + offset)} for row in timings]
    if any(not 0 <= row['startMs'] < row['endMs'] <= duration_ms for row in hints):
        return None
    return tuple(hints)


def discover_timing_seed(config, *, video_id, lexical_mapping, sources, duration_ms,
                         native_verifier):
    """Return old line intervals mapped to native search hints, or None.

    The caller must use the already completed *unconstrained whole-song native*
    Wav2Vec2 verifier, retain guarded segment construction, run native MMS, and
    retry the original full-song baseline after any seeded failure or gate issue.
    Current recording/text authority remains entirely the caller's normal review.
    """
    recording = config.get('recordingInput')
    if not isinstance(recording, dict) or not str(recording.get('recordingKey', '')).startswith('qobuz:'):
        return None
    try:
        video_id = validate_video_id(video_id)
        project = Path(config['projectDir'])
        root = project.parent.parent
        library = project.parent / 'qobuz-comparison'
        inventory = read_bounded_json(library / 'comparison-results/library-files.json',
                                      max_bytes=MAX_JSON_BYTES, root=library)
        selected = [row for row in inventory['playlist'] if row['videoId'] == video_id]
        if (len(selected) != 1 or selected[0].get('source') != 'qobuz'
                or selected[0].get('trackId') != recording.get('trackId')
                or (library / selected[0]['file']).resolve() != (root / recording['file']).resolve()):
            return None
        from stemwise_engine.workflow_context import current_state_root
        previous = current_state_root(resolve_path(config['futureRootWindows'], base=project))
        previous = validate_generated_path(root, previous, workspace_root=root)
        alignment = previous / 'lyrics-alignment/jobs' / video_id
        state = read_bounded_json(alignment / 'state.json', max_bytes=MAX_JSON_BYTES, root=previous)
        if (state.get('videoId') != video_id or state.get('accepted') is not True
                or state.get('status') not in {'committed-lines', 'committed-mixed', 'committed-words'}):
            return None
        mapping_raw = read_bounded_regular_bytes(alignment / 'mapping.private.json',
                                                 max_bytes=MAX_JSON_BYTES, root=previous)
        if hashlib.sha256(mapping_raw).hexdigest() != state.get('mappingArtifactSha256'):
            return None
        mapping = json.loads(mapping_raw)
        identity = lexical_mapping_identity(mapping)
        if (identity != lexical_mapping_identity(lexical_mapping)
                or any(state.get(key) != value for key, value in identity.items())
                or mapping.get('mappingSha256') != identity['mappingSha256']
                or mapping['numeric']['sourcePhysicalIndices'] != list(sources)):
            return None
        timings = mapping['numeric'].get('publicLineTimings')
        if not isinstance(timings, list) or len(timings) != len(sources):
            return None
        canonical = previous / 'lyrics-alignment/canonical-plain' / f"{video_id}.{state['canonicalPlainSha256']}.lyrics.json"
        raw = read_bounded_regular_bytes(canonical, max_bytes=MAX_JSON_BYTES, root=previous)
        if hashlib.sha256(raw).hexdigest() != state['canonicalPlainSha256']:
            return None
        original = [track for track in json.loads(raw)['tracks'] if track.get('kind') == 'original']
        if (len(original) != 1 or lexical_mapping_identity(
                build_lexical_occurrence_mapping(video_id, original[0], sources)) != identity):
            return None
        return _map_hints(timings, mapping, native_verifier, duration_ms)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return None
