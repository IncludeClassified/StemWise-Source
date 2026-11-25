"""Locate a shifted chorus line by matching vocal recordings, then decode it on its own clock."""
import hashlib
import json
from pathlib import Path

from stemwise_engine.common import (atomic_write_json, heavy_worker_stage, json_bytes, read_bounded_json,
                                    run_process_tree, sha256_file)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS, WORD_END_AGREEMENT_MS

def _digest(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def correlate_word(source, search):
    """Normalized correlation of the actual vocal samples; return score and position."""
    import numpy as np
    from scipy.signal import correlate
    if not len(source) or len(source)>len(search):
        return 0.,0
    energy=np.cumsum(np.r_[0.,search.astype('float64')**2])
    norm=np.sqrt(np.maximum(0.,energy[len(source):]-energy[:-len(source)])*np.dot(source,source))
    correlation=correlate(search,source,'valid',method='fft')/np.maximum(norm,1.e-8)
    index=int(np.argmax(correlation))
    return float(correlation[index]),index


def _voice(streams,start,end):
    """Suppress frequency bins also present in the backing before seeking a repeated vocal."""
    import numpy as np
    from math import gcd
    from scipy.signal import resample_poly, stft, istft
    views=[]
    for stream in streams:
        rate=stream.samplerate
        a,b=round(start*rate/1000),round(end*rate/1000)
        stream.seek(a)
        y=stream.read(b-a,dtype='float32',always_2d=True)
        if len(y)!=b-a:
            raise ValueError('Repeated vocal context ended before its recording bound')
        g=gcd(rate,8000)
        mono=resample_poly(y.mean(1),8000//g,rate//g)[:(end-start)*8]
        f,_,z=stft(mono,fs=8000,nperseg=256,noverlap=192)
        views.append(z)
    voice,back=views
    mask=(abs(voice)>2*abs(back))&(f[:,None]>240)&(f[:,None]<3800)
    return istft(voice*mask,fs=8000,nperseg=256,noverlap=192)[1]


def match_line(lines,index,donors,read_audio,duration):
    """A different lyric in the same local arrangement is the physical no-match control."""
    words=lines[index]
    start,end=words[0][0],words[-1][1]
    left=max(0,start-500)
    right=min(duration,end+500)
    if end-start<700 or len(words)<3:
        return None
    local=read_audio(left,right)
    found=[]
    for donor in donors:
        other=lines[donor]
        if len(words)!=len(other):
            continue
        a,b=other[0][0],other[-1][1]
        if max(a,start)<min(b,end) or abs((end-start)-(b-a))>500:
            continue
        span=read_audio(a,b)
        score,offset=correlate_word(span,local)
        if score<.4:
            continue
        offset=left+round(offset/8)
        controls=[]
        for k in (index-1,index+1):
            if 0<=k<len(lines) and k not in donors:
                lo,hi=lines[k][0][0],lines[k][-1][1]
                if hi-lo+1000>=b-a:
                    controls.append(correlate_word(span,read_audio(max(0,lo-500),min(duration,hi+500)))[0])
        if not controls or score<2*max(controls):
            continue
        matches=[]
        for (wstart,wend),(ta,tb) in zip(other,words):
            length=min(wend-wstart,310)
            if length<100:
                continue
            source=span[(wstart-a)*8:(wstart-a+length)*8]
            nominal=offset+wstart-a
            lo=max(left,nominal-130)
            hi=min(right,nominal+length+130)
            corr,j=correlate_word(source,local[(lo-left)*8:(hi-left)*8])
            clock=lo+round(j/8)
            if corr>=.35 and abs(clock-nominal)<=100 and ta-200<=clock<tb:
                matches.append((len(matches),clock,length))
            else:
                break
        if len(matches)==len(words):
            found.append((score,donor,matches))
    if not found:
        return None
    best=max(found,key=lambda entry:entry[0])
    # Different donor crops can begin on different parts of the same syllable.
    # Require every fragment to share physical samples with the best match.
    for _, _, matches in found:
        for (_, a, width), (_, b, other_width) in zip(best[2], matches):
            overlap = min(a+width,b+other_width)-max(a,b)
            if overlap < min(100, min(width,other_width)//2):
                return None
    return dict(donor=best[1],correlation=round(best[0],4),anchors=[m[1] for m in best[2]])


def _decode(context,requests):
    import os
    import soundfile as sf
    from functools import lru_cache
    from contextlib import ExitStack
    from stemwise_engine import endpoint_mms_prediction as mms
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    project=Path(context['project'])
    files=context['files']
    if any(sha256_file(Path(path))!=digest for path,digest in files.values()):
        raise ValueError('Repeated vocal or backing bytes changed')
    duration=context['durationMs']
    os.environ['TORCH_HOME']=str(project.parent/'model-cache/torch')
    result={}
    with ExitStack() as opened:
        streams=[opened.enter_context(sf.SoundFile(files[role][0])) for role in ('vocal','backing')]
        if any(abs(1000*len(s)/s.samplerate-duration)>2 for s in streams):
            raise ValueError('Repeated vocal clocks differ')
        @lru_cache(maxsize=32)
        def read_audio(a,b):
            return _voice(streams,a,b)
        model=None
        signal=StreamedMonoAudio(Path(files['vocal'][0]),16000)
        for req in requests:
            i=req['index']
            match=match_line(context['windows'],i,req['donors'],read_audio,duration)
            if not match:
                continue
            if model is None:
                model=mms.load_model(project)
            words=list(req['words'])
            anchors=list(match['anchors'])
            target_lines=[{'lineId':req['owner'],'sourcePhysicalIndex':req['physical'],'words':words.copy()}]
            start,end=context['windows'][i][0][0],context['windows'][i][-1][1]
            # Give the following words their own lexical states. Without a
            # competing reading the last vowel can consume a later singer or
            # syllable despite an accurately located line.
            after=req.get('following')
            if after:
                j=i+1
                neighbor=match_line(context['windows'],j,after['donors'],read_audio,duration)
                if neighbor and anchors[-1]<neighbor['anchors'][0] and context['windows'][j][0][0]-end<1000:
                    continuation=after['words'][:2]
                    words.extend(continuation)
                    anchors.extend(neighbor['anchors'][:len(continuation)])
                    target_lines.append(dict(lineId=after['owner'],sourcePhysicalIndex=after['physical'],words=continuation))
                    end=context['windows'][j][len(continuation)-1][1]
            target=mms.target_plan({'lines':target_lines},None,model.vocab)
            decodes=[]
            continuation_decodes=[]
            for padding in (360,620):
                a,b=max(0,start-padding),min(duration,end+padding)
                bounded={}
                latest={}
                for j,w in enumerate(words):
                    # Each lexical path may enter ahead of the matched attack and sustain
                    # after it. The next matched interior constrains search only.
                    lo=max(a,anchors[j]-WORD_START_AGREEMENT_MS)
                    hi=min(b,anchors[j+1]+100) if j+1<len(words) and j!=len(req['words'])-1 else b
                    bounded[w['wordId']]=(lo,hi)
                    latest[w['wordId']]=anchors[j]+WORD_START_AGREEMENT_MS
                pcm=signal.read(a*16,min(b*16,signal.sample_count)).numpy()[0]
                observed,_=mms.align_signal(model,pcm,target,a,bounds=bounded,latest_entries=latest)
                decodes.append([[round(w['startMs']),round(w['endMs'])] for w in observed[:len(req['words'])]])
                continuation_decodes.append([[round(w['startMs']),round(w['endMs'])]
                                            for w in observed[len(req['words']):]])
            result[str(i)]={**match,'decodes':decodes,'continuation':continuation_decodes}
    return {'lines':result,'model':mms.numerical_model(mms.MODEL) if result else None}


def _apply(track,requests,traces,confirmed,choices=None,all_native=None):
    from stemwise_engine.recording_release_repair import _ordered_changes
    from stemwise_engine.timing_estimate_policy import LIMIT_MS
    flat=[((i,j),w['timing']) for i,l in enumerate(track['lines']) for j,w in enumerate(l['words'])]
    indices={pos:k for k,(pos,_) in enumerate(flat)}
    base=[(w['startMs'],w['endMs']) for _,w in flat]
    changes={}
    alternatives={}
    for req in requests:
        i=req['index']
        trace=traces.get(str(i))
        if not trace:
            continue
        if trace['donor'] not in req['donors'] or len(trace['anchors'])!=len(req['words']):
            raise ValueError('Vocal template belongs to another rendition')
        if len(trace['decodes'])!=2 or any(len(crop)!=len(req['words']) for crop in trace['decodes']):
            raise ValueError('Local chorus decodes differ from the reviewed words')
        for j,anchor in enumerate(trace['anchors']):
            k=indices[i,j]
            old=base[k]
            if (i,j) in confirmed or flat[k][1]['status']!='estimated':
                continue
            one,two=(d[j] for d in trace['decodes'])
            if max(abs(one[0]-two[0]),abs(one[1]-two[1]))>=LIMIT_MS:
                continue
            if min(abs(pair[0]-anchor) for pair in (one,two))>WORD_START_AGREEMENT_MS:
                continue
            # Keep measured boundaries inside the recovered lexical event. A lexical
            # CTC core alone can underestimate a release; prefer a native alternative
            # where its onset and both local decodes identify the same performance.
            local=[tuple(p) for p in (one,two) if abs(p[0]-anchor)<=WORD_START_AGREEMENT_MS]
            if not local:
                continue
            pos=(i+1,j+1)
            candidates=[tuple(p) for p in req['native'][j]]
            def same_entrance(p):
                return (abs(p[0]-anchor)<=WORD_START_AGREEMENT_MS
                        and all(abs(p[0]-q[0])<=WORD_START_AGREEMENT_MS for q in local))
            candidates=[p for p in candidates if same_entrance(p)]
            native=[p for p in candidates if all(abs(p[1]-q[1])<LIMIT_MS for q in local)]
            disputed = j in req.get('disputed', ())
            if disputed and same_entrance(old) and native:
                # A previous line-level search cut can discard a good word inside
                # the selected rendition. Restore its packet only after matching
                # the vocal fragment and re-decoding this word on the target clock.
                ends = [p[1] for p in [old,*local,*candidates]]
                if max(ends)-min(ends)>=LIMIT_MS:
                    continue
                if choices is not None and all_native is not None:
                    for family, candidate in all_native.get(pos,{}).items():
                        pair=(candidate['word']['startMs'],candidate['word']['endMs'])
                        if pair in native:
                            choices.setdefault(pos,{})[family]=candidate
                # The existing start is already inside the matched vocal event.
                # Rank the complete-word exits against the target-specific decodes.
                chosen=min(native,key=lambda p:max(abs(p[1]-q[1]) for q in local))
                changes[k]=(old[0],chosen[1])
                continue
            if old in native and same_entrance(old):
                continue
            corroborated=[p for p in native if all(abs(p[1]-q[1])<=WORD_END_AGREEMENT_MS for q in local)]
            chosen=min(corroborated or local,key=lambda p:(max(abs(p[1]-q[1]) for q in local),abs(p[0]-anchor)))
            if j+1<len(trace['anchors']) and chosen[1]>trace['anchors'][j+1]+100:
                chosen=min(local,key=lambda p:abs(p[0]-anchor))
            changes[k]=chosen
            alternatives[k]=[*local,*native]
            predecessor=traces.get(str(i-1),{})
            for linked in predecessor.get('continuation',()):
                if j<len(linked):
                    pair=tuple(linked[j])
                    if same_entrance(pair) and all(abs(pair[1]-p[1])<LIMIT_MS for p in local):
                        alternatives[k].append(pair)
    # At a line break, two independently cropped readings can each assign
    # the same sound to their edge word. Prefer a pair actually decoded in
    # the same context before resorting to the older, misplaced intervals.
    for k in sorted(changes):
        if k==0 or k-1 not in changes:
            continue
        allowed=max(0,base[k-1][1]-base[k][0])
        previous,chosen=changes[k-1],changes[k]
        if previous[1]-chosen[0]<=allowed:
            continue
        viable=[(p,q) for p in [previous,*alternatives.get(k-1,())]
                for q in [chosen,*alternatives.get(k,())]
                if p[0]<=q[0] and p[1]-q[0]<=allowed
                and (k<2 or changes.get(k-2,base[k-2])[1]-p[0]<=max(0,base[k-2][1]-base[k-1][0]))]
        if viable:
            changes[k-1],changes[k]=min(viable,key=lambda pq:sum(abs(left-right)
                for row,original in zip(pq,(previous,chosen)) for left,right in zip(row,original)))
    accepted=_ordered_changes(base,changes)
    for k,(a,b) in accepted.items():
        if (a,b)==base[k]:
            continue
        (i,j),w=flat[k]
        w.update(startMs=a,endMs=b,basis='Estimated on the target vocals after matching a separate recorded rendition.')
        line=track['lines'][i]
        line['timing'].update(startMs=line['words'][0]['timing']['startMs'],
                              endMs=max(v['timing']['endMs'] for v in line['words']))


def recover(workspace,state,original,admission,audio,track,choices,confirmed,*,all_native=None,ref=None,config=None):
    from stemwise_engine import spoken_targets
    from stemwise_engine.lyric_preview.job import recording_entrances
    from stemwise_engine.recording_repeat_words import _inputs
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
    from stemwise_engine.endpoint_mms_prediction import MODEL, validate_recorded_model
    from stemwise_engine.runtime_environment import environment_lease,environment_python
    from stemwise_engine.worker_protocol import require_worker_success, parse_worker_json
    from stemwise_engine.word_timing_review import inspect, RELEASE_FLAGS
    vid=state['videoId']; folder=workspace.job_dir(vid); sources=admission.source_physical_indices
    spoken=(spoken_targets.load(workspace.root.parent/'jobs'/vid,vid,original,sources,audio['sha256'])
            if admission.spoken_targets else None)
    starts=recording_entrances(workspace,vid,audio['sha256'],original,sources,audio['durationMs'],spoken=spoken)
    if len(starts)<2:
        if ref:
            raise ValueError('Bound chorus placements are missing')
        return None
    found=_inputs(folder,audio,original,sources)
    if not found:
        if ref:
            raise ValueError('Bound chorus audio is missing')
        return None
    guide,lines,files=found
    mapping=build_lexical_occurrence_mapping(vid,original,sources)['performedOccurrences']
    readings=spoken.word_lines(sources) if spoken else [[w['text'] for w in l['words']] for l in lines]
    langs=spoken.language_lines(sources) if spoken else [[] for _ in sources]
    windows=[[[w['startMs'],w['endMs']] for w in l['words']] for l in lines]
    report=inspect(original,track,state['endpointEvidence'],starts,audio['durationMs'],None,resolved_releases=confirmed)
    disputed={(f['line']-1,f['wordIndex']-1) for f in report['publicationFlags'] if f['reason'] in RELEASE_FLAGS}
    raw_choices=all_native if all_native is not None else choices
    def phrase(i):
        owner=mapping[i]
        donors=[j for j,l in enumerate(lines) if j!=i and l['text']==lines[i]['text'] and
                readings[j]==readings[i] and langs[j]==langs[i] and
                (windows[j][-1][1]<=windows[i][0][0] or windows[i][-1][1]<=windows[j][0][0])]
        return dict(index=i,donors=donors,owner=owner['occurrenceId'],physical=owner['sourcePhysicalIndex'],
                    words=[dict(wordId=w['occurrenceWordId'],acousticText=text)
                           for w,text in zip(owner['words'],readings[i],strict=True)])
    reqs=[]
    for i,(line,wins) in enumerate(zip(track['lines'],windows,strict=True)):
        if state['endpointEvidence']['occurrenceCandidates'][i]['publishDecision']['result']=='words':
            continue
        review=[j for j in range(len(wins)) if (i,j) in disputed and (i,j) not in confirmed]
        shifted=any(abs(w['timing']['startMs']-a)>WORD_START_AGREEMENT_MS for w,(a,b) in zip(line['words'],wins,strict=True))
        if line['timing']['status']!='estimated' or not (shifted or review):
            continue
        req=phrase(i)
        if not req['donors']:
            continue
        if i+1<len(lines) and windows[i+1][0][0]>=wins[0][0]:
            req['following']=phrase(i+1)
        native=[[[c['word']['startMs'],c['word']['endMs']] for c in raw_choices.get((i+1,j+1),{}).values()]
                for j in range(len(wins))]
        reqs.append(dict(req,native=native,disputed=review))
    if not reqs:
        if ref:
            raise ValueError('Bound chorus requests changed')
        return None
    files={k:files[k] for k in ('vocal','backing')}
    measurements=[{k:v for k,v in r.items() if k not in ('native','disputed')} for r in reqs]
    binding=dict(recording=audio['sha256'],guide=guide,content=admission.evidence_sha256,mapping=state['mappingSha256'],
                 views={k:v[1] for k,v in files.items()},request=_digest([measurements,windows]),
                 recipe=semantic_source_identity(Path(__file__),('correlate_word','_voice','match_line','_decode'),portable=True),
                 model=MODEL,
                 decoder=semantic_source_identity(Path(__file__).with_name('endpoint_mms_prediction.py'),portable=True),
                 runtime=state['pinnedRuntime'])
    name='phrase-placement-'+_digest(binding)+'.private.json'
    path=folder/name
    if ref and ref['path']!=name:
        raise ValueError('Bound chorus placement request differs')
    if not path.is_file():
        if ref or not config:
            raise ValueError('Bound chorus placement observations unavailable')
        project=Path(config['projectDir'])
        with environment_lease('main',builder=project),heavy_worker_stage(config):
            proc=run_process_tree([str(environment_python('main',project)),'-B','-m','stemwise_engine.recording_phrase_placement'],
                                  input=json.dumps([dict(durationMs=audio['durationMs'],files=files,windows=windows,project=str(project)),measurements]),
                                  capture_output=True,text=True,timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(proc,code='chorus-placement-measurement-failed',diagnostic_path=folder/'chorus-placement.failure.private.txt')
        atomic_write_json(path,dict(binding=binding,observations=parse_worker_json(proc.stdout)),root=folder)
    observed=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder)
    current=dict(path=name,sha256=sha256_file(path))
    if observed['binding']!=binding or ref is not None and current['sha256']!=ref['sha256']:
        raise ValueError('Bound chorus observations changed')
    packet=observed['observations']
    if packet['lines']:
        validate_recorded_model(packet['model'], MODEL)
    _apply(track,reqs,packet['lines'],confirmed,choices,all_native)
    # A failed ordering or a censored local decode can leave the old word
    # on another sound. Make that visible even if native timers agreed there.
    from stemwise_engine.timing_estimate_policy import LIMIT_MS
    misplaced=[]
    for key, trace in packet['lines'].items():
        i=int(key)
        for j,anchor in enumerate(trace['anchors']):
            selected=track['lines'][i]['words'][j]['timing']
            local=[d[j] for d in trace['decodes']]
            if (selected['status']=='estimated' and all(abs(p[0]-anchor)<=WORD_START_AGREEMENT_MS for p in local)
                    and all(abs(selected['startMs']-p[0])>=LIMIT_MS for p in local)
                    and all(selected['endMs']<=p[0] or p[1]<=selected['startMs'] for p in local)):
                misplaced.append([i,j,min(p[0] for p in local),max(p[1] for p in local)])
    current['misplacedWords']=misplaced
    if ref is not None and current!=ref:
        raise ValueError('Bound chorus placement decisions changed')
    return current


if __name__=='__main__':
    import sys
    print(json.dumps(_decode(*json.load(sys.stdin))))
