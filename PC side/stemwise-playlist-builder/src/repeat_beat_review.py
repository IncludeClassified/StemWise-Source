"""Order existing release candidates for listening using other vocal renditions on the same beat grid."""
from bisect import bisect_right
from statistics import median

from stemwise_engine.alignment_endpoints import OWSM_CTC_FAMILY, WORD_START_AGREEMENT_MS
from stemwise_engine.endpoint_vowel_release import AGREEMENT_MS
from stemwise_engine.stanza_timing import same_rendition
from stemwise_engine.word_timing_review import RELEASE_FLAGS


def recording_clock(document, checkpoint, child_sha, audio_sha, duration):
    stage=checkpoint.get('stages',{}).get('analysis',{})
    return bool(child_sha and audio_sha and checkpoint.get('status')=='complete'
                and checkpoint.get('childSha256')==child_sha and document.get('analysis',{}).get('durationMs')==duration
                and stage.get('pcmSha256',audio_sha)==audio_sha)


def checked_beats(document, checkpoint, child_sha, audio_sha, duration):
    stage = checkpoint.get('stages', {}).get('analysis', {})
    analysis = document.get('analysis', {})
    rhythm = analysis.get('rhythm', {})
    complete = {tool['name'] for tool in analysis.get('toolRuns', []) if tool.get('status') == 'complete'}
    if (not recording_clock(document,checkpoint,child_sha,audio_sha,duration)
            or stage.get('status') != 'complete' or stage.get('pcmSha256') != audio_sha or rhythm.get('status') != 'complete'
            or not {'beat-this-full', 'stemwise-rhythm-normalizer'} <= complete):
        return []
    beats = [r['timeMs'] for r in rhythm.get('beats', [])]
    if (len(beats) < 3 or any(type(t) is not int or not 0 <= t <= duration for t in beats)
            or any(l >= r for l, r in zip(beats, beats[1:]))):
        return []
    return beats


def _beat_at(beats, t):
    j=bisect_right(beats,t)-1
    return j+(t-beats[j])/(beats[j+1]-beats[j])


def _project(beats,start,gap):
    position=_beat_at(beats,start)+gap
    j=int(position)
    if 0<=j<len(beats)-1:
        return round(beats[j]+(position-j)*(beats[j+1]-beats[j]))
    return None


def _length(beats, start, end):
    if not beats[0] <= start < end < beats[-1]:
        return None
    left, right = (bisect_right(beats, t)-1 for t in (start, end))
    # A skipped beat and an actual abrupt tempo change are indistinguishable
    # locally. Gradual tempo changes keep their own measured interbeat times.
    spans = [beats[i+1]-beats[i] for i in range(max(0,left-1), min(len(beats)-1,right+2))]
    if any(max(a,b) >= 2*min(a,b) for a,b in zip(spans,spans[1:])):
        return None
    return _beat_at(beats,end)-_beat_at(beats,start)


def _vocal_bounds(word, edge):
    if word.get('vocalContrast') not in ([True,True], (True,True)):
        return None
    ranges=word.get('vocalCropRangeMs')
    if not isinstance(ranges,list) or len(ranges)!=2 or not isinstance(ranges[edge],list) or len(ranges[edge])!=2:
        return None
    lo,hi=ranges[edge]
    t=word[('startMs','endMs')[edge]]
    if type(lo) is not int or type(hi) is not int or lo>hi or max(t,hi)-min(t,lo)>AGREEMENT_MS:
        return None
    return min(t,lo),max(t,hi)


def rank_ends(beats, published, single, summary, anchors, flags, spoken=None, beat_in_bar=None):
    """Same wording, distinct physical templates, and unanimous ordering; no timing is modified."""
    if not beats or not single or not published or len(single)!=len(published['lines']):
        return []
    result=[]
    positions=sorted({(f['line']-1,f['wordIndex']-1) for f in flags if f['reason'] in RELEASE_FLAGS})
    for i,k in positions:
        if k<1:
            continue
        line=published['lines'][i]
        selected=line['words'][k]
        own_start=selected.get('timing',selected)['startMs']
        prior=line['words'][k-1]
        start=prior.get('timing',prior)['startMs']
        row=summary['occurrenceCandidates'][i]
        families=same_rendition(row['familyCandidates'],anchors.get(i),anchors.values())
        ends={}
        for family,obs in families.items():
            words=obs.get('words',[])
            if family==OWSM_CTC_FAMILY or len(words)!=len(line['words']):
                continue
            w=words[k]
            if (own_start < w['endMs'] and abs(w['startMs']-own_start)<=WORD_START_AGREEMENT_MS and
                    abs(words[k-1]['startMs']-start)<=WORD_START_AGREEMENT_MS):
                ends.setdefault(w['endMs'],[]).append(family)
        lengths={t:_length(beats,start,t) for t in ends}
        if len(lengths)<2 or None in lengths.values():
            continue
        donors=[]
        entrances=[]
        for n,other in enumerate(single):
            if n==i or other['text']!=line['text'] or spoken and spoken[n]!=spoken[i]:
                continue
            early=_vocal_bounds(other['words'][k-1],0)
            late=_vocal_bounds(other['words'][k],1)
            if not early or not late or not (late[1]<start or max(ends)<early[0]):
                continue
            lo,hi=_length(beats,early[1],late[0]),_length(beats,early[0],late[1])
            if lo is not None and hi is not None:
                donors.append((n,early[0],late[1],lo,hi))
                entry=_vocal_bounds(other['words'][k],0)
                if entry:
                    left,right=_length(beats,early[1],entry[0]),_length(beats,early[0],entry[1])
                    if left is not None and right is not None:
                        gap=_length(beats,other['words'][k-1]['startMs'],other['words'][k]['startMs'])
                        entrances.append((n,gap,left,right))
        if len(donors)<2 or any(a[2]>=b[1] for a,b in zip(donors,donors[1:])):
            continue
        low=min(d[3] for d in donors)
        high=max(d[4] for d in donors)
        if high-low>.5:
            continue
        typical=median((d[3]+d[4])/2 for d in donors)
        ordered=sorted(lengths,key=lambda t:abs(lengths[t]-typical))
        first=ordered[0]
        if any(abs(lengths[first]-edge)>=abs(lengths[t]-edge) for t in ordered[1:] for edge in (low,high)):
            continue  # Nearby alternatives trade places within the observed range.
        expected_ms=_project(beats,start,typical)
        if expected_ms is None:
            continue
        onset={}
        if len(entrances)>=2 and max(v[3] for v in entrances)-min(v[2] for v in entrances)<=.5:
            gaps=[v[1] for v in entrances]
            onset_ms=_project(beats,start,median(gaps))
            if onset_ms is not None and start<onset_ms<expected_ms:
                onset={'predictedStartMs':onset_ms,'startEstimateLines':[v[0]+1 for v in entrances],
                       'observedStartGapBeats':[round(min(gaps),3),round(max(gaps),3)]}
        def describe(t,elapsed):
            index=bisect_right(beats,t)
            nearest=min(beats[index-1:index+1],key=lambda beat:abs(beat-t))
            return {'endMs':t,'elapsedBeats':round(elapsed,2),'fromPredictedMs':t-expected_ms,
                    'nearestBeatMs':nearest,'beatInBar':(beat_in_bar or {}).get(nearest),'fromBeatMs':t-nearest}
        preview=single[i]['words'][k]
        guide_delta=_length(beats,start,preview['endMs'])
        guide=(describe(preview['endMs'],guide_delta) if guide_delta is not None
               and abs(preview['startMs']-own_start)<=WORD_START_AGREEMENT_MS else None)
        result.append({'line':i+1,'wordIndex':k+1,'word':single[i]['words'][k]['text'],
            'fromWord':single[i]['words'][k-1]['text'],'fromStartMs':start,
            'vocalEstimateLines':[d[0]+1 for d in donors],
            'observedElapsedBeats':[round(low,2),round(high,2)],
            'predictedElapsedBeats':round(typical,2),'predictedEndMs':expected_ms,**onset,
            'targetPreviewEstimate':guide,
            'listenInOrder':[dict(describe(t,lengths[t]),families=sorted(ends[t])) for t in ordered]})
    return result
