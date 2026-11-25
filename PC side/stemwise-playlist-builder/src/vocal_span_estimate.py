"""Estimate an owned, clipped word at a measured local vocal change."""
from stemwise_engine.lyric_preview.candidates import BAR
from stemwise_engine.timing_estimate_policy import LIMIT_MS


def estimate(request, local, phones, features):
    from stemwise_engine.vocal_release_transition import _read
    from stemwise_engine.vocal_span_recovery import _crop, _stable_runs
    import numpy as np

    a, b = request['selected']
    x, y = request['guide']
    if not request['flanked'] or not x - BAR <= a < y or y - b <= BAR or y - b >= LIMIT_MS:
        return None
    if not any(abs(a - left) <= BAR for left, _ in request['native']):
        return None
    # Require an acoustic nucleus in the same physical occurrence. A rival
    # timed outside the guide or wholly before the selected core cannot qualify.
    lo, hi = local
    if hi <= a or lo >= b + BAR or min(hi, y) - max(lo, x) < 40:
        return None
    exit_range = _crop(request['guideWord'], 1)
    if not exit_range or exit_range[1] - exit_range[0] > BAR:
        return None

    time, level, periodicity, contrast, envelope = _read(features)
    runs = _stable_runs(phones)
    vowel_classes = {'open', 'front', 'round'}
    candidates = []
    for left, right in zip(runs, runs[1:]):
        _, edge, previous = left
        right_start, right_end, following = right
        if (right_start != edge or previous == 'rest' or previous == following
                or right_end - edge < 50
                or not exit_range[0] - BAR <= edge <= exit_range[1] + BAR
                or not b + BAR < edge < b + LIMIT_MS):
            continue
        before = (time > edge - 75) & (time < edge - 10)
        after = (time > edge + 10) & (time < edge + 75)
        if min(sum(before), sum(after)) < 4:
            continue
        if np.median(contrast[before]) < .35:
            continue
        if previous in vowel_classes and np.median(periodicity[before]) < .5:
            continue
        decay = np.median(level[before]) - np.median(level[after])
        shape_change = np.linalg.norm(np.median(envelope[before], axis=0) -
                                      np.median(envelope[after], axis=0))
        if following == 'rest' and decay >= 9 or following != 'rest' and shape_change >= .24:
            candidates.append(edge)
    # Competing ends belong in the report. A nearby guide can refine the
    # listening window while the signal itself must identify one usable change.
    if len(candidates) != 1:
        return None
    return a, candidates[0]


def _transitions(times, level, periodic, contrast, envelope, hint, *, entrance):
    import numpy as np

    exposed = level + 10*np.log10(np.maximum(.05, contrast))
    window = np.flatnonzero(abs(times-hint) <= 125)
    candidates = {}
    for k in window:
        if k < 8 or k+8 >= len(times):
            continue
        vocal, other = slice(k-8,k-2), slice(k+2,k+8)
        if entrance:
            vocal, other = other, vocal
        if np.median(contrast[vocal]) < .3:
            continue
        decay = np.median(level[vocal])-np.median(level[other])
        separation = np.median(exposed[vocal])-np.median(exposed[other])
        shift = np.linalg.norm(np.median(envelope[vocal],axis=0)-np.median(envelope[other],axis=0))
        if shift < .14 and decay < 3 and separation < 6:
            continue
        voicing = np.median(periodic[vocal])-np.median(periodic[other])
        strength = max(0,decay)/7 + max(0,separation)/13 + 4*shift + max(0,voicing)
        if strength >= .85:
            candidates[round(times[k])] = float(strength)/(1+abs(times[k]-hint)/130)
    return candidates


def _options(incumbent, guide, features, displaced):
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read

    a,b = incumbent
    x,y = guide['startMs'],guide['endMs']
    times,level,periodic,contrast,envelope = _read(features)
    if x < times[0] or y > times[-1]:
        return []
    body = (times >= x+40) & (times <= y-40)
    if not body.any() or np.mean((periodic[body]>=.4) & (contrast[body]>.32)) < .4:
        return []
    starts = _transitions(times,level,periodic,contrast,envelope,x,entrance=True)
    ends = _transitions(times,level,periodic,contrast,envelope,y,entrance=False)
    if abs(a-x) <= 300:
        starts[a] = max(1, starts.get(a, 0))
    if abs(b-y) <= 130:
        ends[b] = max(1, ends.get(b, 0))
    return [(left,right,onset+release) for left,onset in starts.items() for right,release in ends.items()
            if (left,right) != incumbent and left+120 <= right
            and max(abs(left-a),abs(right-b)) < LIMIT_MS
            and (displaced or right-left > b-a)]


def _choose(base, choices):
    # Prefer a complete sequence of measured alternatives. An unresolved
    # candidate can retain its incumbent without moving an unrelated word.
    states = {0: ((0,0.), [])}
    for k, (a,b) in enumerate(base):
        proposals = [(a,b,0),*choices.get(k, ())]
        following = {}
        for start,end,strength in proposals:
            parents = [s for cursor,s in states.items() if cursor <= start]
            if not parents:
                continue
            (coverage,score),path = max(parents,key=lambda state:state[0])
            rank = coverage + int(strength>0),score+strength
            if end not in following or rank > following[end][0]:
                following[end] = rank,path + [(start,end)]
        if not following:
            return {}
        states = following
    _,path = max(states.values(),key=lambda state:state[0])
    return {k:new for k,(old,new) in enumerate(zip(base,path,strict=True)) if new != old}


def complete_adjacent_words(track, lines, requests, observations, protected, digest):
    from stemwise_engine.nemo_nfa_forced_alignment import ACOUSTIC_TIMESTEP_MS
    from stemwise_engine.recording_release_repair import _ordered_changes
    from stemwise_engine.vocal_span_recovery import _crop, dispute

    words = [((i,j),word['timing'],g) for i,(line,guide) in enumerate(zip(track['lines'],lines,strict=True))
             for j,(word,g) in enumerate(zip(line['words'],guide['words'],strict=True))]
    positions = {pos:k for k,(pos,_,_) in enumerate(words)}
    base = [(t['startMs'],t['endMs']) for _,t,_ in words]
    choices = {}
    for request in requests:
        i = request['index']
        for target in request['targets']:
            pos = i,target['index']
            k = positions[pos]
            _,timing,guide = words[k]
            a,b = base[k]
            if (pos in protected or b-a != ACOUSTIC_TIMESTEP_MS
                    or [a,b] != target['selected'] or not dispute(timing,guide)):
                continue
            features = observations[str(i)]['spectrum']
            choices[k] = _options((a,b),guide,features,False)
            if k+1 == len(words) or not choices[k]:
                continue
            nxt,following,next_guide = words[k+1]
            if (nxt not in protected and following['status']=='estimated'
                    and guide['startMs'] < following['startMs'] < guide['endMs']
                    and 300 < next_guide['startMs']-following['startMs'] < LIMIT_MS
                    and following['endMs'] >= next_guide['startMs']-BAR
                    and abs(next_guide['startMs']-guide['endMs']) <= BAR
                    and _crop(next_guide,0) and _crop(next_guide,1)):
                next_trace = observations.get(str(nxt[0]), {}).get('spectrum',features)
                choices.setdefault(k+1,_options(base[k+1],next_guide,next_trace,True))
    if not choices:
        return
    accepted = _ordered_changes(base,_choose(base,choices))
    for k,(a,b) in accepted.items():
        (i,j),timing,_ = words[k]
        timing.update(startMs=a,endMs=b,basis='Estimated from distinct local vocal transitions on this rendition.')
        protected[i,j] = (a,b,digest)
    for i in {words[k][0][0] for k in accepted}:
        line = track['lines'][i]
        line['timing'].update(startMs=line['words'][0]['timing']['startMs'],
                              endMs=max(w['timing']['endMs'] for w in line['words']))
