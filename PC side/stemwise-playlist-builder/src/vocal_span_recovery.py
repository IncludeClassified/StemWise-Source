"""Compare a selected estimate with recording-bound observations of the complete word."""
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS


def _crop(word, edge):
    ranges=word.get('vocalCropRangeMs') or []
    mass=word.get('massWithin100ms') or {}
    if (len(ranges)!=2 or not isinstance(ranges[edge],list) or len(ranges[edge])!=2 or
            any(type(x) is not int for x in ranges[edge]) or
            any(not isinstance(mass.get(role),list) or len(mass[role])!=2 for role in ('mix','lead','backing'))):
        return None
    a,b=ranges[edge]
    voice,mix,back=(mass[role][edge] for role in ('lead','mix','backing'))
    if (not all(isinstance(p,(int,float)) and 0<=p<=1 for p in (voice,mix,back)) or not 0<=a<=b or
            b-a>WORD_START_AGREEMENT_MS or min(voice,mix)<=back+.1):
        return None
    return a,b


def dispute(timing,guide):
    """Return the listening extent for a contradicted estimate, never a replacement time."""
    if not timing or timing.get('status')!='estimated' or not guide:
        return None
    a,b=timing['startMs'],timing['endMs']
    x,y=guide['startMs'],guide['endMs']
    if not 0<=a<b or not 0<=x<y:
        return None
    first,last=_crop(guide,0),_crop(guide,1)
    if not first and not last:
        return None
    # A real word can be very short. Require a contradictory vocal span at
    # least twice as long and a displacement stable across the vocal crops.
    short=(y-x)>=2*(b-a) and (first and a-first[1]>100 or last and last[0]-b>100)
    displaced=first and b+100<first[0] or last and a>last[1]+100
    if short or displaced:
        return min(a,x,first[0] if first else x),max(b,y,last[1] if last else y)
    return None
# These are acoustic classes used to compare a sustained articulation with a
# different excitation. They never change the spelling or pronunciation plan.
_FRAME_GROUPS = {
    'open': 'aa ae ah ao aw ax ay eh er ow oy',
    'front': 'ih iy ey', 'round': 'uh uw', 'nasal': 'm n ng',
    'liquid': 'l r w y', 'stop': 'b d dx g k p t', 'noise': 'ch dh f hh jh s sh th v z zh',
    'rest': 'SP',
}
_GROUP = {phone: group for group, phones in _FRAME_GROUPS.items() for phone in phones.split()}
_VOWELS = {'open', 'front', 'round'}


def _terminal(reading):
    from stemwise_engine.multilingual_release_observer import _nucleus
    phones = [_nucleus(p).replace('̪', '') for p in reading if p]
    vowels = {'front': 'iɪeɛ', 'round': 'uʊouɯ', 'open': 'aɑɒæɐəɜʌɔ'}
    for i in range(len(phones)-1, -1, -1):
        for kind, sounds in vowels.items():
            if phones[i] in sounds:
                coda = phones[i+1:]
                return kind, coda
    return None, []


def _stable_runs(raw):
    # Fuse isolated 10–20 ms classification flicker between like sounds.
    out = []
    for a, b, p in raw:
        group = _GROUP.get(p, p)
        if out and out[-1][2] == group:
            out[-1][1] = b
        elif len(out)>=2 and out[-1][1]-out[-1][0]<=20 and out[-2][2]==group:
            out.pop()
            out[-1][1]=b
        else:
            out.append([a,b,group])
    return out


def _span(req, reading, local, frames, acoustic):
    """Find a sustained terminal on the target PCM; return only measured boundaries."""
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read
    word, guide, native = req['selected'], req['guide'], req['native']
    a,b = word
    x,y = guide
    lo,hi = local
    group,coda = _terminal(reading)
    # The independent decoder must place this word in this physical window. A
    # retained native core and an unconstrained line decode can disagree on a
    # coda, but neither may identify a different physical word here.
    if not group or lo>=y or hi<=x or min(hi,y)-max(lo,x)<40:
        return None
    census = [p for p in native if min(p[1],y)-max(p[0],x)>=40]
    if not census and not req['flanked']:
        return None
    # A full coda must be accounted for by an audible consonant or by local
    # CTC landing at that exit. Vowels alone cannot prove an arbitrary coda.
    terminal = {'h':'noise', 'ħ':'noise', 'd':'stop', 't':'stop', 'k':'stop',
                'r':'liquid', 'j':'liquid', 'n':'nasal', 'm':'nasal'}
    if any(p not in terminal for p in coda) or len(coda)>1:
        return None
    time,level,periodic,contrast,_ = _read(acoustic)
    runs=_stable_runs(frames)
    candidates=[]
    for k,(start,end,label) in enumerate(runs[:-1]):
        if label!=group or end-start<70 or end<hi-300 or start>y or end<x:
            continue
        after=runs[k+1]
        if after[1]-after[0]<40:
            continue
        terminal_end=end
        if coda and after[2]==terminal[coda[0]]:
            if k+2==len(runs) or runs[k+2][1]-runs[k+2][0]<40:
                continue
            terminal_end=after[1]
        elif coda and (abs(hi-end)>180 or after[2] in _VOWELS):
            continue
        if abs(terminal_end-y)>250 or terminal_end<b+100:
            continue
        core=(time>=max(start,end-120))&(time<end-15)
        if sum(core)<3 or np.median(contrast[core])<.3 or np.median(periodic[core])<.5:
            continue
        # With two plausible exits substantially apart, this frame trace
        # cannot choose the word end.
        candidates.append(terminal_end)
    if not candidates or max(candidates)-min(candidates)>140:
        return None
    end=min(candidates,key=lambda t:abs(t-hi))
    # A native onset is already a measurement, and is preserved when it
    # falls near the local onset. Otherwise use the separately decoded onset
    # only with a second native entrance or flanking physical word cores.
    start=a
    if abs(a-lo)>100 and abs(lo-x)<=180 and (req['flanked'] or
            any(abs(lo-p[0])<=100 for p in census)):
        start=lo
    if not (x-180<=start<y and start<end and end-start>b-a):
        return None
    return (start,end)


def _vowel_decay(request, pronunciation, local, phones, features):
    """Measure when an identified vowel yields to the backing on the same recording."""
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read
    target, coda = _terminal(pronunciation)
    a,b=request['selected']
    x,y=request['guide']
    if (coda or not target or not request['flanked'] or not x-150<a<y or
            min(local[1],y)-max(local[0],x)<40):
        return None
    time,level,periodic,contrast,_=_read(features)
    found=[]
    for start,end,label in _stable_runs(phones):
        if (label!=target or min(y,end)-max(start,b)<40 or
                max(min(hi,end)-max(lo,start) for lo,hi in (local,(a-150,b+150)))<25):
            continue
        # A source-retained vowel can become masked while a classifier still
        # predicts its timbre on residual music. Find the actual drop, even
        # if its position differs from the first frame of a new phone class.
        region=(time>=max(b+90,y-220,start+80)) & (time<=min(end+30,y+220))
        for k in np.flatnonzero(region):
            if k<12 or k+10>=len(time):
                continue
            before=slice(k-12,k-3)
            after=slice(k,k+8)
            reference=np.median(level[before])
            if (np.median(periodic[before])>=.5 and np.median(contrast[before])>=.55 and
                    contrast[k-1]>=.2 and contrast[k]<.2 and
                    np.percentile(contrast[after],85)<.2 and
                    np.median(level[k:k+3])<=reference-6 and np.median(level[after])<=reference-8):
                found.append(round(time[k]))
    return (a,min(found)) if found and max(found)-min(found)<100 else None


def _decodes(req, signal, model):
    from stemwise_engine import endpoint_mms_prediction as mms
    a,b=req['window']
    tokens, words, owners=mms.target_plan({'lines':[req['line']]},None,model.vocab)
    pcm=signal.read(a*16,min(signal.sample_count,b*16)).numpy()[0]
    emissions,edges=mms.prediction_emissions(model,pcm,a)
    original,_=mms.decode(emissions,tokens,owners,words,edges,{})
    bounds,latest={},{}
    for j,(word,anchor) in enumerate(zip(req['line']['words'],req['anchors'],strict=True)):
        if anchor is None:
            continue
        # A retained nucleus is an interior search anchor. Give the decoder
        # room on both sides; an adjacent anchor supplies no published end.
        previous=req['anchors'][j-1] if j else None
        following=req['anchors'][j+1] if j+1<len(req['anchors']) else None
        lo=max(a,anchor-400,previous if previous is not None else a)
        hi=min(b,following if following is not None else b)
        if hi-lo<100 or not lo<=anchor<hi:
            continue
        bounds[word['wordId']]=(lo,hi)
        latest[word['wordId']]=min(hi,anchor+100)
    fenced=[]
    if bounds:
        try:
            fenced,_=mms.decode(emissions,tokens,owners,words,edges,bounds,latest_entries=latest)
        except ValueError as err:
            if str(err)!='multilingual prediction cannot retain every target within qualified windows':
                raise
    return original,fenced


def _observe(context, requests):
    import os
    from pathlib import Path
    from contextlib import ExitStack
    from math import gcd
    from functools import lru_cache
    import soundfile as sf
    from scipy.signal import resample_poly
    from stemwise_engine.common import sha256_file
    from stemwise_engine.arabic_coda_release import _classifier
    from stemwise_engine.multilingual_release_observer import _pronunciation
    from stemwise_engine import endpoint_mms_prediction as mms, vocal_release_transition
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    project=Path(context['project'])
    paths=context['files']
    if any(sha256_file(Path(path))!=digest for path,digest in paths.values()):
        raise ValueError('Sung span input audio changed')
    classify=_classifier(project)
    os.environ['TORCH_HOME']=str(project.parent/'model-cache/torch')
    signal=StreamedMonoAudio(Path(paths['vocal'][0]),mms.RATE)
    model=mms.load_model(project)
    pronounce=lru_cache(maxsize=None)(_pronunciation)
    result={}
    with ExitStack() as stack:
        streams=[stack.enter_context(sf.SoundFile(paths[role][0])) for role in ('vocal','backing')]
        if any(abs(len(s)*1000/s.samplerate-context['durationMs'])>2 for s in streams):
            raise ValueError('Sung span vocal clock differs')
        for r in requests:
            decoded,fenced=_decodes(r,signal,model)
            a,b=r['frameWindow']
            signals=[]
            analysis=None
            for s in streams:
                start,stop=round(a*s.samplerate/1000),round(b*s.samplerate/1000)
                s.seek(start)
                mono=s.read(stop-start,dtype='float32',always_2d=True).mean(1)
                if len(mono)!=stop-start:
                    raise ValueError('Incomplete sung span vocal crop')
                if analysis is None:
                    div=gcd(s.samplerate,44100)
                    analysis=resample_poly(mono,44100//div,s.samplerate//div)
                div=gcd(s.samplerate,16000)
                signals.append(resample_poly(mono,16000//div,s.samplerate//div))
            frames=classify(analysis,a)
            spectrum=vocal_release_transition.extract(*signals,16000,a)
            observed={}
            for t in r['targets']:
                j=t['index']
                w=decoded[j]
                if w['wordId']!=t['wordId']:
                    raise ValueError('Sung span decoder word ownership differs')
                entry=dict(reading=pronounce(t['spoken'],t['language']),
                    local=[round(w['startMs']),round(w['endMs'])])
                if fenced:
                    second=fenced[j]
                    if second['wordId']!=t['wordId']:
                        raise ValueError('Bound sung span word ownership differs')
                    pair=[round(second['startMs']),round(second['endMs'])]
                    fence_values=[k for k in r['anchors'] if k is not None]
                    if all(min(abs(v-q) for v in pair)>25 for q in fence_values if q!=r['anchors'][j]):
                        entry['fenced']=pair
                observed[t['wordId']]=entry
            result[str(r['index'])]=dict(words=observed,frames=frames,spectrum=spectrum)
    if any(sha256_file(Path(path))!=digest for path,digest in paths.values()):
        raise ValueError('Sung span recording changed during measurement')
    return {'lines':result,'model':mms.numerical_model(mms.MODEL)}


def _requests(track, lines, mapping, readings, languages, choices, protected, duration):
    from stemwise_engine.nemo_nfa_forced_alignment import ACOUSTIC_TIMESTEP_MS
    from stemwise_engine.lyrics_tokenization import display_words
    from stemwise_engine.multilingual_release_observer import LANGUAGES
    words={}
    for i,(line,guide,owner) in enumerate(zip(track['lines'],lines,mapping,strict=True)):
        if line['text']!=guide['text']:
            raise ValueError('Sung span line ownership differs')
        for j,(w,g,token,display) in enumerate(zip(line['words'],guide['words'],owner['words'],
                                                         display_words(line['text']),strict=True)):
            bounds=(w['startUtf16'],w['endUtf16'],g['text'])
            if bounds!=tuple(display) or bounds[:2]!=(g['startUtf16'],g['endUtf16']):
                raise ValueError('Sung span display-word ownership differs')
            t=w['timing']
            pairs=choices.get((i+1,j+1),{})
            native=[[c['word']['startMs'],c['word']['endMs']] for f,c in sorted(pairs.items())
                    if f!='owsm-ctc' and c['wordId']==token['occurrenceWordId']]
            n=pairs.get('nemo-nfa',{}).get('word') or {}
            a,b=t['startMs'],t['endMs']; x,y=g['startMs'],g['endMs']
            one=(n.get('startMs'),n.get('endMs'))==(a,b) and b-a==ACOUSTIC_TIMESTEP_MS
            displaced=(b<x or y<a) and bool(dispute(t,g))
            short=(one and y-x>=2*(b-a)) or (b-a<=120 and bool(dispute(t,g)))
            words[i,j]=dict(index=j,wordId=token['occurrenceWordId'],spoken=readings[i][j],
                language=languages[i][j],guide=[x,y],guideWord=g,selected=[a,b],native=native,
                target=t['status']=='estimated' and (i,j) not in protected and (short or displaced)
                       and languages[i][j] in LANGUAGES)
    ordered=list(words.values())
    by_line={}
    for k,((i,j),w) in enumerate(words.items()):
        if not w.pop('target'):
            continue
        flanks=[{key:t[key] for key in ('index','wordId','guide','native')} for q in (k-1,k+1)
                if 0<=q<len(ordered) for t in [ordered[q]]]
        w['flanks']=flanks
        w['flanked']=bool(flanks) and all(any(min(b,t['guide'][1])-max(a,t['guide'][0])>=40
                                                for a,b in t['native']) for t in flanks)
        by_line.setdefault(i,[]).append(w)
    requests=[]
    for i,targets in by_line.items():
        windows=lines[i]['words']
        a,b=windows[0]['startMs'],windows[-1]['endMs']
        if not 0<=a<b<=duration or b-a>30000:
            continue
        entry=mapping[i]
        owner=dict(lineId=entry['occurrenceId'],sourcePhysicalIndex=entry['sourcePhysicalIndex'],
                   words=[dict(wordId=w['occurrenceWordId'],acousticText=t)
                          for w,t in zip(entry['words'],readings[i],strict=True)])
        anchors=[]
        for j in range(len(windows)):
            t=words[i,j]
            x,y=t['guide']
            possible=[(c,d) for c,d in t['native'] if x-100<=c<y and min(d,y)-max(c,x)>=40]
            # Select an acoustic interior, keeping it clear of both ends.
            core=min(possible,key=lambda p:abs(p[0]-x)) if possible else None
            anchors.append((max(x,core[0])+min(y,core[1]))//2 if core else None)
        requests.append(dict(index=i,window=[max(0,a-250),min(duration,b+250)],anchors=anchors,
                 frameWindow=[max(0,(a-600)//10*10),min(duration,(b+700)//10*10)],
                 line=owner,targets=targets))
    return requests


def _apply(track, requests, observations, protected, digest):
    from stemwise_engine.recording_release_repair import _ordered_changes
    from stemwise_engine.vocal_span_estimate import estimate
    flat=[((i,j),w['timing']) for i,line in enumerate(track['lines']) for j,w in enumerate(line['words'])]
    indices={pos:k for k,(pos,t) in enumerate(flat)}
    base=[(t['startMs'],t['endMs']) for _,t in flat]
    changes={}
    alternatives={}
    for req in requests:
        i=req['index']
        trace=observations[str(i)]
        readings=trace['words']
        if set(readings)!={t['wordId'] for t in req['targets']}:
            raise ValueError('Sung span evidence belongs to another word')
        for t in req['targets']:
            pos=i,t['index']
            if pos in protected:
                continue
            k=indices[pos]
            owned=readings[t['wordId']]
            local=owned['local']
            value=_span(t,owned['reading'],local,trace['frames'],trace['spectrum'])
            if value is None:
                value=estimate(t,local,trace['frames'],trace['spectrum'])
            if value is None and 'fenced' in owned:
                local=owned['fenced']
                value=_span(t,owned['reading'],local,trace['frames'],trace['spectrum'])
                if value is None:
                    value=estimate(t,local,trace['frames'],trace['spectrum'])
            decay=_vowel_decay(t,owned['reading'],local,trace['frames'],trace['spectrum'])
            if value is None:
                value=decay
            if value is None:
                continue
            a,b=value
            if not 0<=a<b or (a,b)==base[k]:
                continue
            changes[k]=(a,b)
            # A nearby local exit is a separate real measurement. It can
            # resolve a collision without moving a locked neighboring word.
            alternatives[k]=[decay] if decay and abs(decay[1]-b)<=150 else []
            if (abs(local[1]-b)<=100 and local[1]-a>base[k][1]-base[k][0]
                    and local[1]>base[k][1]+100):
                alternatives[k].append((a,local[1]))
            elif (base[k][1]<t['guide'][0] and t['flanked'] and abs(local[1]-b)<1000 and
                    any(abs(local[0]-u)<=300 and abs(local[1]-v)<=100 for u,v in t['native'])):
                # The old interval was on another sound. Two complete vocal
                # observations locate this later word, while the phone class
                # can continue into a similar vowel in the following word.
                alternatives[k].append(tuple(local))
            if a!=base[k][0] and t['guide'][0]-100<=base[k][0]<t['guide'][1] and base[k][0]<b:
                alternatives[k].append((base[k][0],b))
    accepted=_ordered_changes(base,changes)
    # Recover an individually feasible measured alternative after testing the
    # simultaneous changes first. All original locks still participate.
    for k in sorted(changes.keys()-accepted.keys()):
        for proposal in alternatives[k]:
            attempt={**accepted,k:proposal}
            if _ordered_changes(base,attempt)==attempt:
                accepted=attempt
                break
    for k,(a,b) in accepted.items():
        (i,j),t=flat[k]
        t.update(startMs=a,endMs=b,basis='Estimated from the complete word on this rendition’s isolated vocals.')
        protected[i,j]=(a,b,digest)
    for i in {flat[k][0][0] for k in accepted}:
        row=track['lines'][i]
        row['timing'].update(startMs=row['words'][0]['timing']['startMs'],
                             endMs=max(w['timing']['endMs'] for w in row['words']))


def recover(workspace,state,original,admission,audio,track,choices,protected,*,ref=None,config=None):
    import json
    from pathlib import Path
    from stemwise_engine import (spoken_targets, recording_repeat_words as repeat, endpoint_vowel_release as phones,
            endpoint_mms_prediction as mms, stanza_timing, arabic_coda_release, vocal_release_transition,
            multilingual_release_observer, vocal_span_estimate)
    from stemwise_engine.common import atomic_write_json, heavy_worker_stage, read_bounded_json, run_process_tree, sha256_file
    from stemwise_engine.contract import MAX_JSON_BYTES
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine.runtime_environment import environment_lease, environment_python
    from stemwise_engine.worker_protocol import require_worker_success,parse_worker_json
    folder=workspace.job_dir(state['videoId'])
    indices=admission.source_physical_indices
    # A layered vocal needs attribution to the actual singer. A separate
    # role observer owns it. This route consumes the reviewed multilingual plan.
    if not admission.spoken_targets:
        if ref:
            raise ValueError('Sung span pronunciation plan is missing')
        return None
    saved=repeat._inputs(folder,audio,original,indices)
    review=folder/'vocal-overlap/review.private.json'
    if not saved or read_bounded_json(review,max_bytes=MAX_JSON_BYTES,root=review.parent)['status']!='single':
        if ref:
            raise ValueError('Sung span recording or single-vocal ownership changed')
        return None
    guide,lines,files=saved
    plan=spoken_targets.load(workspace.root.parent/'jobs'/folder.name,folder.name,original,indices,audio['sha256'])
    if not plan or plan.reference!=admission.spoken_targets:
        raise ValueError('Sung span reviewed pronunciation changed')
    mapping=build_lexical_occurrence_mapping(folder.name,original,indices)['performedOccurrences']
    reqs=_requests(track,lines,mapping,plan.word_lines(indices),plan.language_lines(indices),choices,protected,audio['durationMs'])
    if not reqs:
        if ref:
            raise ValueError('Sung span target selection changed')
        return None
    files={k:files[k] for k in ('vocal','backing')}
    binding=dict(recording=audio['sha256'],guide=guide,views={k:v[1] for k,v in files.items()},
                 mapping=state['mappingSha256'],content=admission.evidence_sha256,pronunciation=admission.spoken_targets,
                 request=repeat._digest(reqs),model=mms.MODEL,phones=[phones.MODEL,phones.VOCAB],
                 runtime=state['pinnedRuntime'],
                 producer=[semantic_source_identity(Path(p),names,portable=True) for p,names in [
                    (__file__,('_observe','_decodes')),(mms.__file__,None),
                    (arabic_coda_release.__file__,('_classifier',)),(vocal_release_transition.__file__,None),
                    (multilingual_release_observer.__file__,('_pronunciation',))]])
    path=folder/('vocal-span-'+repeat._digest(binding)+'.private.json')
    if ref and ref.get('path')!=path.name:
        raise ValueError('Sung span target or recording changed')
    if not path.is_file():
        if ref or not config:
            raise ValueError('Sung span recording observations unavailable')
        project=Path(config['projectDir'])
        data=dict(project=str(project),durationMs=audio['durationMs'],files=files)
        with environment_lease('main',builder=project),heavy_worker_stage(config):
            proc=run_process_tree([str(environment_python('main',project)),'-B','-m','stemwise_engine.vocal_span_recovery'],
                 input=json.dumps([data,reqs]),capture_output=True,text=True,encoding='utf-8',
                 timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(proc,code='sung-span-measurement-failed',diagnostic_path=folder/'vocal-span.failure.private.txt')
        atomic_write_json(path,dict(binding=binding,observations=parse_worker_json(proc.stdout)),root=folder,pretty=False)
    packet=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder)
    digest=sha256_file(path)
    reference=dict(path=path.name,sha256=digest)
    observed=packet['observations']
    mms.validate_recorded_model(observed['model'],mms.MODEL)
    if packet['binding']!=binding or (ref and ref!=reference) or set(observed['lines'])!={str(r['index']) for r in reqs}:
        raise ValueError('Sung span acoustic evidence changed')
    _apply(track,reqs,observed['lines'],protected,digest)
    vocal_span_estimate.complete_adjacent_words(track,lines,reqs,observed['lines'],protected,digest)
    return reference


if __name__=='__main__':
    import json
    import sys
    print(json.dumps(_observe(*json.load(sys.stdin))))
