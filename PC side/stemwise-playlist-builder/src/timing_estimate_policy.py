"""Choose among close, recording-bound vocal estimates for an admitted word."""
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.word_timing_review import RELEASE_FLAGS

LIMIT_MS = 1000
BASIS = 'Estimated from same-occurrence vocal timings within one second.'


def candidates(track, choices, position, confirmed):
    i, j = position
    timing = track['lines'][i-1]['words'][j-1]['timing']
    if timing['status'] != 'estimated' or (i-1, j-1) in confirmed:
        return {}
    start, end = timing['startMs'], timing['endMs']
    values = {}
    for family, row in choices.get(position, {}).items():
        word = row['word']
        if (family != 'owsm-ctc' and abs(word['startMs'] - start) <= WORD_START_AGREEMENT_MS
                and word['endMs'] > start):
            values.setdefault(word['endMs'], []).append(family)
    if not values:
        return {}
    values.setdefault(end, [])
    if len(values) < 2 or max(values) - min(values) >= LIMIT_MS:
        return {}
    return values


def select(track, choices, confirmed, positions, preferred):
    """Keep onsets and prior overlap limits. A stronger physical cue may rank a measured end."""
    flat = [((i,j), w['timing']) for i,line in enumerate(track['lines'],1)
            for j,w in enumerate(line['words'],1)]
    indices = {key:k for k,(key,_) in enumerate(flat)}
    result = []
    for pos in sorted(positions):
        values = candidates(track, choices, pos, confirmed)
        if not values:
            continue
        k = indices[pos]
        timing = flat[k][1]
        end = timing['endMs']
        # Previously admitted overlaps have their own authority and cannot grow.
        ceiling = max(end, flat[k+1][1]['startMs']) if k+1 < len(flat) else max(values)
        feasible = {t for t in values if t <= ceiling}
        cue = preferred.get(pos)
        chosen, reason = end, 'incumbent-vocal-estimate'
        if cue in feasible:
            chosen, reason = cue, 'local-instrumental-attack'
        else:
            isolated = [t for t in feasible if 'mms-fa' in values[t] and
                        'vocals' in choices[pos]['mms-fa']['route']]
            if len(isolated) == 1:
                chosen, reason = isolated[0], 'isolated-vocal-estimate'
        result.append(dict(line=pos[0],wordIndex=pos[1],startMs=timing['startMs'],priorEndMs=end,
                           endMs=chosen,rangeMs=[min(values),max(values)],reason=reason))
    return result


def _apply(track, rows):
    for row in rows:
        if row['endMs'] == row['priorEndMs']:
            continue
        line = track['lines'][row['line']-1]
        line['words'][row['wordIndex']-1]['timing'].update(endMs=row['endMs'],basis=BASIS)
        line['timing']['endMs'] = max(w['timing']['endMs'] for w in line['words'])


def replay(track, choices, confirmed, record, recording, mapping):
    if (record['limitExclusiveMs'] != LIMIT_MS or record['recording'] != recording
            or record['mapping'] != mapping):
        raise ValueError('Equivalent timing estimates belong to a different policy or recording')
    positions = {(r['line'],r['wordIndex']) for r in record['words']}
    preferred = {(r['line'],r['wordIndex']):r['endMs'] for r in record['words']
                 if r['reason'] == 'local-instrumental-attack'}
    rows = select(track,choices,confirmed,positions,preferred)
    if rows != record['words']:
        raise ValueError('Equivalent timing estimates differ from the native vocal evidence')
    _apply(track,rows)


def prepare(workflow, job, workspace, state, original, admission, audio, track, choices, confirmed):
    from stemwise_engine.instrumental_onset_review import automatic
    from stemwise_engine.lyric_preview.job import recording_entrances
    from stemwise_engine.word_timing_review import inspect
    from stemwise_engine.repeat_beat_review import checked_beats
    from stemwise_engine.common import read_bounded_json, sha256_file
    from stemwise_engine.contract import MAX_JSON_BYTES
    from stemwise_engine.packages import read_child
    from stemwise_engine import spoken_targets
    vid = state['videoId']
    sources = admission.source_physical_indices
    spoken = (spoken_targets.load(workflow._job_dir(vid),vid,original,sources,audio['sha256'])
              if admission.spoken_targets else None)
    anchors = recording_entrances(workspace, vid, audio['sha256'], original, sources, audio['durationMs'], spoken=spoken)
    summary = state['endpointEvidence']
    flags = inspect(original,track,summary,anchors,audio['durationMs'],None,resolved_releases=confirmed)['publicationFlags']
    positions = {(r['line'],r['wordIndex']) for r in flags if r['reason'] in RELEASE_FLAGS}
    positions.difference_update((r['line'],r['wordIndex']) for r in flags if r['reason'] not in RELEASE_FLAGS)
    positions = {p for p in positions if candidates(track,choices,p,confirmed)}
    preferred = {}
    if positions:
        events = []
        child = workflow.runtime/'children'/f'{vid}.stemwise.zip'
        checkpoint = workflow.runtime/'work/checkpoints'/f'{vid}.json'
        if child.is_file() and checkpoint.is_file():
            cp = read_bounded_json(checkpoint, max_bytes=MAX_JSON_BYTES, root=checkpoint.parent)
            child_hash = sha256_file(child)
            if cp.get('videoId') == vid and cp.get('childSha256') == child_hash and cp.get('originalAudio',{}).get('sha256') == audio['sha256']:
                doc,_ = read_child(child,workflow._runtime_config())
                if checked_beats(doc,cp,child_hash,audio['sha256'],audio['durationMs']):
                    events = doc['analysis']['rhythm']['beats']
        selected_flags = [r for r in flags if (r['line'],r['wordIndex']) in positions]
        physical = automatic(workflow,job,audio,track,summary,anchors,selected_flags,events,[],True)
        for r in physical['boundaries']:
            if r['boundary'] == 'end' and r['association'] == 'candidate-for-listening':
                preferred[r['line'],r['wordIndex']] = r['listenFirstMs'][0]
    rows = select(track,choices,confirmed,positions,preferred)
    _apply(track,rows)
    return dict(limitExclusiveMs=LIMIT_MS,recording=audio['sha256'],mapping=state['mappingSha256'],words=rows)
