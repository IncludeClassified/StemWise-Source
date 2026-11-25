"""Measure a recognized repeat's final vocal decay on an exposed lead rendition."""
from pathlib import Path


def _nucleus(trace, guide):
    from stemwise_engine import multilingual_release_observer as phones
    expected=trace['pronunciation']
    # An unmarked Arabic spelling can encode the final liquid and /h/ without
    # spelling its sung vowel. Require the actual medial /li/ plus liquid and
    # open-vowel sequence in this rendition. No guessed vowel from spelling.
    if expected[-2:]!=['l','h']:
        return None
    start,end=guide['startMs'],guide['endMs']
    events=[(t,phones._nucleus(p),prob) for t,p,prob in trace['views']['vocal'] if start<=t<end]
    cores=[]
    for i,(t,p,prob) in enumerate(events):
        if i<3 or p not in {'a','ɑ','ə','ʌ','æ'} or prob<.20 or events[i-1][1] not in {'l','n'}:
            continue
        if not any(l[1]=='l' and v[1] in {'i','ɪ'} and l[2]>=.5 and v[2]>=.5
                   for l,v in zip(events[:i-1],events[1:i-1])):
            continue
        if any(q>=.20 and ph!=p for _,ph,q in events[i+1:]):
            continue  # an audible coda, a competing word, or a different singer
        cores.append(t)
    return cores[0] if len(cores)==1 else None


def observe(trace,group,files):
    from stemwise_engine import recording_repeat_words as repeat
    from stemwise_engine import vocal_release_transition as spectral
    import soundfile as sf
    from scipy.signal import resample_poly
    if not {'vocal','backing','lead','choir'}<=files.keys():
        return {}
    captured={}
    for w in group:
        g=w['guide']
        if (w['locked'] or not w.get('disputedRelease') or abs(w['a']-g['startMs'])>300
                or not all(repeat._tight(g,k) for k in (0,1))):
            continue
        if not repeat._articulated(trace['pronunciation'],trace['views']['vocal'],g['startMs'],g['endMs']):
            continue
        core=_nucleus(trace,g)
        if core is None:
            continue
        left=max(0,core//10*10-100)
        right=(g['endMs']+600)//10*10
        if right-left>10000:
            continue
        samples={}
        for role,(filename,_) in files.items():
            with sf.SoundFile(Path(filename)) as stream:
                sr=stream.samplerate
                a,b=round(left*sr/1000),round(right*sr/1000)
                if b>len(stream):
                    break
                stream.seek(a)
                x=stream.read(b-a,dtype='float32',always_2d=True)
                samples[role]=resample_poly(x.mean(1),16000,sr)
        if set(samples)!=set(files):
            continue
        features={r:spectral.extract(samples[r],samples['backing'],16000,left) for r in ('vocal','lead','choir')}
        captured[g['wordId']]=features
    return captured


def release(trace,word):
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectral
    from stemwise_engine.phrase_final_release import _rest_has_coda
    g=word['guide']
    ft=trace.get('exposedCodas',{}).get(g['wordId'])
    if not ft:
        return None
    core=_nucleus(trace,g)
    if core is None:
        return None
    # No alternate reading may intervene between the observed core and
    # physical quiet, including a new word just beyond the search guide.
    rival=next((t for t,_,p in trace['views']['vocal'] if t>core and p>=.20),g['endMs']+450)
    edges=[]
    for view in ('vocal','lead'):
        edge=spectral.decide(ft[view],core-10,core+45,g['endMs'],following_phone_ms=rival)
        if edge is None or _rest_has_coda(ft[view],(core-10,edge,'rest'),edge):
            return None
        edges.append(edge)
    if max(edges)-min(edges)>100:
        return None
    edge=edges[0]
    clock,lead,*_=spectral._read(ft['lead'])
    other_clock,choir,*_=spectral._read(ft['choir'])
    if not np.array_equal(clock,other_clock):
        return None
    before=(clock>=core-10)&(clock<=edge-60)
    tail=(clock>=edge)&(clock<edge+100)
    if (not before.any() or tail.sum()<6 or np.percentile(lead[before]-choir[before],10)<12
            or np.max(choir[tail])>np.median(lead[(clock>=core-10)&(clock<=core+45)])-18):
        return None
    return edge
