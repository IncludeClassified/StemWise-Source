"""Remeasure an independently recognized English ending on the separated recording."""
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.common import parse_strict_json_bytes, read_bounded_regular_bytes
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.endpoint_vowel_release import AGREEMENT_MS, VOWELS
from stemwise_engine.lyric_comparison import units
import hashlib


def attach_witnesses(folder, state, audio, targets):
    """Bind an unprompted mix reading to the already admitted word and a neighboring word."""
    from stemwise_engine.source_recording_content import _diagnostic_whisper
    name = 'transcript.en.unprompted-mix.private.json'
    matches = [r for r in state.get('cacheBindings', {}).get('records', [])
               if r['familyId'] == 'whisper-asr' and r['cacheId'] == name]
    if len(matches) != 1:
        return
    raw = read_bounded_regular_bytes(folder/name, max_bytes=MAX_JSON_BYTES, root=folder)
    if hashlib.sha256(raw).hexdigest() != matches[0]['sha256']:
        raise ValueError('English lexical witness changed')
    packet = parse_strict_json_bytes(raw)
    provenance = packet['provenance']
    if (packet['videoId'] != state['videoId'] or packet['model'] != 'large-v3+unprompted'
            or provenance.get('audioView', 'mix') != 'mix'
            or {'routeId','sourceViewId','sourceAudioSha256'}.intersection(provenance['audio'])):
        return
    rows = _diagnostic_whisper(packet, audio['sha256'], audio['durationMs'], 'en')
    readings = [units(r['raw'], 'en') for r in rows]
    for word in targets:
        if word['language'] != 'en' or not word['releaseDisputed']:
            continue
        expected = units(word['spoken'], 'en')
        found = []
        for i, row in enumerate(rows):
            if (readings[i] != expected
                    or row['start']*1000 > word['startMs']+WORD_START_AGREEMENT_MS
                    or row['end']*1000 < word['startMs']-WORD_START_AGREEMENT_MS):
                continue
            flank = any(0 <= i+step < len(rows) and word[key] and word[key]['language'] == 'en'
                        and readings[i+step] == units(word[key]['spoken'], 'en')
                        for step,key in ((-1,'previous'),(1,'following')))
            if flank:
                found.append({'sha256':matches[0]['sha256'], 'index':i,
                              'next':rows[i+1]['raw'] if i+1 < len(rows) else None})
        if len(found) == 1:
            word['mixWitness'] = found[0]


def _nucleus(form, phone):
    if phone == form:
        return True
    # Unstressed final r may be nonrhotic; a sung schwa may lower or open.
    return phone in {'ax':('ah','eh','ae'), 'er':('ax','er','r','_r'),
                     'aa':('ah','aa'), 'ah':('ax','ah'), 'ow':('ao','ow')}.get(form, ())


def _exit(features, runs, backing, i, form, following):
    """Observe a last vowel, an optional physically audible stop burst, and a quiet guard."""
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectrum
    end = runs[i][1]
    # A stop may have an unreleased closure, or a separable aperiodic release after voicing.
    stop = form[-1] in ('p','t','k','b','d','g')
    if len(form) < 2 and stop:
        return None
    nucleus = form[-2] if stop else form[-1]
    if nucleus not in VOWELS:
        return None
    j = i
    while j > 0:
        a,b,p = runs[j]
        if b-a >= 60 and _nucleus(nucleus,p):
            break
        if (b-a > 30 and not (p in ('r','_r','er','aa','ax','ah') and nucleus in ('aa','ah','ax'))
                or end-a > WORD_START_AGREEMENT_MS):
            return None
        j -= 1
    left, right, phone = runs[j]
    if not _nucleus(nucleus,phone) or right-left < 60 or end-right > 300:
        return None
    mirrored = any(lo < right and hi > max(left,right-AGREEMENT_MS) and ph == phone for lo,hi,ph in backing)
    if mirrored and not stop:
        return None
    time, level, periodic, contrast, envelope = spectrum._read(features)
    # The word's own phone supplies the body level, even when its pitch changes during the hold.
    core = (time >= left) & (time <= right) & (periodic >= .55) & (contrast >= .4)
    if core.sum() < spectrum.GUARD_FRAMES:
        return None
    reference = np.median(level[core])
    if not stop:
        physical=[]
        for fraction in (.25,.5,.75):
            center=left+fraction*(right-left)
            at=spectrum.decide(features,center-25,center+25,end,following_phone_ms=following)
            if at is not None and abs(at-end)<=AGREEMENT_MS:
                physical.append(at)
        if physical and max(physical)-min(physical)<=AGREEMENT_MS:
            return sorted(physical)[len(physical)//2]
    hi_band = np.sum(envelope[:,-4:]**2, axis=1)
    # Find the first stable loss of this voiced tail around the separately observed phone exit.
    quiet = (level <= reference-18) & ((periodic < .5) | (contrast < .25))
    if stop:
        noise = (periodic < .5) & (contrast >= .4) & (hi_band > 2*np.median(hi_band[core]))
        quiet &= ~noise
    for k in np.flatnonzero((time >= end-AGREEMENT_MS) & (time <= end+WORD_START_AGREEMENT_MS)):
        if k < spectrum.GUARD_FRAMES or k+spectrum.GUARD_FRAMES > len(time):
            continue
        if following is not None and time[k+spectrum.GUARD_FRAMES-1] >= following:
            break
        if not quiet[k:k+spectrum.GUARD_FRAMES].all():
            continue
        if mirrored:
            # A vowel also heard instrumentally cannot identify an exit. A distinct
            # foreground stop release can: require most excitation in the vocal stem
            # on both the identified vowel and a sustained aperiodic coda.
            coda = (time >= end) & (time < time[k]) & noise
            if (np.median(contrast[core]) <= .5 or coda.sum() < spectrum.GUARD_FRAMES
                    or np.median(contrast[coda]) <= .5):
                return None
        if time[k] > end+AGREEMENT_MS:
            if not stop:
                return None
            # A later burst needs a continuous vocal aperiodic coda. A new breath after
            # a quiet gap is a different event and cannot extend the word.
            tail = (time >= end) & (time < time[k])
            if noise[tail].sum() < spectrum.GUARD_FRAMES or any(
                    quiet[n:n+spectrum.GUARD_FRAMES].all() for n in np.flatnonzero(tail)[:-spectrum.GUARD_FRAMES]):
                return None
        return int(round(time[k]))
    return None


def _handoff(features, runs, index, form, following_forms):
    """Look for one physical spectral change inside localized adjacent vowel classes."""
    from stemwise_engine import vocal_release_transition as spectrum
    left, end, phone = runs[index]
    if form[-1] != phone or phone not in VOWELS or end-left < AGREEMENT_MS:
        return None
    successors = {v[0] for v in following_forms if len(v)==1 and v[0] in VOWELS and v[0]!=phone}
    if not successors:
        return None
    # The lexical change supplies two different owners. Frame labels locate only their
    # nuclei, and a third excitation or a continuous unresolvable glide vetoes the change.
    next_start, next_end, nxt = runs[index+1]
    if nxt == phone or not any(_nucleus(wanted,nxt) for wanted in successors):
        return None
    limit = next_end
    for a,b,p in runs[index+2:]:
        if a >= end+2*AGREEMENT_MS or p == phone or (p == 'SP' and b-a > 20):
            break
        if p != 'SP' and not any(_nucleus(wanted,p) for wanted in successors):
            break
        limit = b
    points=[]
    # Independently sample 32ms cores across the last and first 150ms of the two phones.
    # Require every resolvable pair to indicate the same physical switch.
    for a in range(max(left+20,end-150), end-20, 30):
        for b in range(next_start+20,min(limit-20,next_start+151),30):
            edge = spectrum.decide_transition(features,a-16,a+16,b-16,b+16,end)
            if edge is not None:
                points.append(edge)
    if len(points) < 2 or max(points)-min(points)>AGREEMENT_MS:
        return None
    return sorted(points)[len(points)//2]


def measure(target, trace, view, runs):
    if not target.get('mixWitness') or 'spectrum' not in trace:
        return None
    vocals,backing = runs(view['vocal']),runs(view['backing'])
    following = target['following']['startMs'] if target['following'] else None
    features = trace['spectrum']
    found=set()
    for i,(a,b,phone) in enumerate(vocals[:-1]):
        if b <= target['startMs'] or following is not None and b > following:
            continue
        if any(p=='SP' and y-max(x,target['startMs']) >= AGREEMENT_MS
               for x,y,p in vocals[:i] if y>target['startMs']):
            continue  # A new vocal phrase cannot supply the release of this word.
        x,y,nxt = vocals[i+1]
        for form in trace['pronunciations']:
            if nxt == 'SP' and y-x >= AGREEMENT_MS:
                edge = _exit(features,vocals,backing,i,form,following)
            elif (following is not None and abs(b-following) <= WORD_START_AGREEMENT_MS
                    and trace.get('observedFollowingForms') and
                    any(v in trace['followingPronunciations'] for v in trace['observedFollowingForms'])
                    and not any(lo<b and hi>max(a,b-AGREEMENT_MS) and ph==phone for lo,hi,ph in backing)):
                edge = _handoff(features,vocals,i,form,trace['followingPronunciations'])
            else:
                continue
            if edge is not None:
                found.add(edge)
    if not found or max(found)-min(found)>AGREEMENT_MS:
        return None
    return min(found)
