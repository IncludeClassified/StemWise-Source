"""Estimate a phrase-final sung release after independent recognition of its physical line."""
from itertools import groupby
import json
from pathlib import Path
import sys

from stemwise_engine import endpoint_vowel_release as phones
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.common import heavy_worker_stage, read_bounded_json, run_process_tree, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyric_comparison import units
from stemwise_engine.lyric_preview.reading_review import _bound_json
from stemwise_engine.multilingual_release_observer import _pronunciation
from stemwise_engine.recording_repeat_words import _inputs, _tight
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.timing_estimate_policy import LIMIT_MS
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


def _eligible(t):
    ends=[c['endMs'] for c in t['candidates'] if c['family']!='owsm-ctc'
          and abs(c['startMs']-t['startMs'])<=WORD_START_AGREEMENT_MS]
    return (t['language']=='ar' and t['releaseDisputed'] and not t['locked'] and ends
            and max(ends)-min(ends)>=LIMIT_MS)


def _recognition(line, transcript, selected):
    """A whole preceding lexical context and the final word must identify one local rendition."""
    written=line['words']
    if len(written)<3 or not all(_tight(written[-1],i) for i in (0,1)):
        return None
    wanted=[units(w['text'],'ar') for w in written]
    if any(len(w)!=1 for w in wanted) or len({w[0] for w in wanted})<2:
        return None
    last=written[-1]
    if not last['startMs']-phones.AGREEMENT_MS<=selected<last['endMs']:
        return None
    hits=[]
    for i in range(len(transcript)-len(written)+1):
        group=transcript[i:i+len(written)]
        if ([units(w['text'],'ar') for w in group]!=wanted
                or abs(group[-1]['startMs']-last['startMs'])>WORD_START_AGREEMENT_MS
                or any(a['startMs']>=b['startMs'] for a,b in zip(group,group[1:]))):
            continue
        # The preceding observed words must also belong to this physical line.
        # Free-recognition ends can span later stanzas; they never measure this release.
        if all(w['startMs']<=g['endMs'] and w['endMs']>=g['startMs']
               for w,g in zip(group[:-1],written[:-1])):
            hits.append((group,transcript[i+len(group)] if i+len(group)<len(transcript) else None))
    return hits[0] if len(hits)==1 else None


def attach(folder, audio, original, admission, targets):
    wanted=[t for t in targets if _eligible(t)]
    if not wanted:
        return None
    saved=_inputs(folder,audio,original,admission.source_physical_indices)
    if not saved:
        return None
    timing,lines,files=saved
    if 'lead' not in files or 'choir' not in files:
        return None
    part=Path(files['lead'][0]).parent
    view=part.parent
    review=read_bounded_json(view.parent/'review.private.json',max_bytes=MAX_JSON_BYTES,root=view.parent)
    activity=_bound_json(view/'backing-activity.private.json',review['backingActivitySha256'],view)
    digest=activity['readings'].get('mix')
    if not digest:
        return None
    mix=_bound_json(part/'mix-words.private.json',digest,part)
    if mix['binding']['sha256']!=audio['sha256']:
        raise ValueError('Phrase-final recognition belongs to a different Original')
    for t in wanted:
        line=lines[t['line']]
        if t['wordIndex']!=len(line['words'])-1 or line['words'][-1]['text']!=t['text']:
            continue
        observed=_recognition(line,mix['words'],t['startMs'])
        if observed:
            w=line['words'][-1]
            following=t.get('following') or {}
            acoustic_next=observed[1] or {}
            matches_next=(following.get('language')=='ar' and
                          units(acoustic_next.get('text',''),'ar')==units(following['text'],'ar'))
            t['finalPhrase']={'wordId':w['wordId'],'hint':w['endMs'],'crops':w['vocalCropRangeMs'][1],
                              'nextRecognized':matches_next}
    if any('finalPhrase' in t for t in wanted):
        return {'phraseRecognitionSha256':digest,'phraseTimingSha256':timing}
    return None


def _landmark(runs, hint, nucleus, next_phone):
    """Find a stable named vowel ending in a real rest or the following named consonant."""
    shapes={'aː':{'aa','ae','ah','ao'},'uː':{'uw','uh'},'iː':{'iy','ih'}}
    core=shapes[nucleus]
    found=[]
    for i,(a,b,p) in enumerate(runs):
        if p not in core or b-a<2*phones.AGREEMENT_MS or not hint-phones.AGREEMENT_MS<=b<=hint+phones.AGREEMENT_MS:
            continue
        for x,y,q in runs[i+1:]:
            if x>hint+2*phones.AGREEMENT_MS:
                break
            if q=='SP' and y-x>=phones.AGREEMENT_MS:
                found.append((a,x,'rest'))
                break
            if q==next_phone and y-x>=60:
                found.append((a,x,'handoff'))
                break
            if q not in core|{'uw','uh','SP'} and y-x>=30:
                break
    return found[0] if len(found)==1 else None


def _measure(features, landmark, hint):
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectrum
    begin,edge,kind=landmark
    a,b=(max(begin,edge-260),edge-180) if kind=='rest' else (max(begin,edge-140),edge-70)
    if b-a<30:
        return None
    if kind=='rest':
        return spectrum.decide(features,a,b,hint)
    time,level,periodic,contrast,env=spectrum._read(features)
    core=spectrum._core(time,a,b)
    if len(core)<3 or np.median(periodic[core])<.65 or np.median(contrast[core])<.4:
        return None
    shape=np.median(env[core],axis=0)
    shape/=max(np.linalg.norm(shape),1.e-12)
    idx=np.flatnonzero((time>=b)&(time<=edge+phones.AGREEMENT_MS))
    if len(idx)<7:
        return None
    bottom=idx[np.argmin(level[idx])]
    before=idx[idx<=bottom]
    after=np.flatnonzero((time>time[bottom])&(time<=time[bottom]+100))
    if not len(before) or not len(after) or abs(time[bottom]-edge)>phones.AGREEMENT_MS:
        return None
    # Remove power attributable to the accompanying signal. A drop in source
    # contrast alone cannot end a word: the isolated lead itself must also
    # fade and reattack with a measurably different spectral shape.
    exposed=level+10*np.log10(np.maximum(contrast,1.e-12))
    core_level=np.median(level[core])
    floor=np.median(exposed[core])-18
    quiet=before[(exposed[before]<=floor)&(level[before]<=core_level-6)]
    if (not len(quiet) or exposed[bottom]>floor or level[bottom]>core_level-12
            or np.max(level[after])-level[bottom]<9
            or np.median(1-env[after]@shape)<3*max(.01,np.max(1-env[core]@shape))
            or not np.all((periodic[before]>=.4)|(exposed[before]<=floor))):
        return None
    # Locate the end of the fading word before the separate consonant reattack.
    # The CTC transition and the next word clock select neither output frame.
    first=quiet[0]
    still=before[exposed[before]>floor]
    if (np.max(exposed[first:bottom+1])>floor+3 or not len(still) or np.median(env[still]@shape)<.8):
        return None
    return int(round(time[first]))


def _rest_has_coda(features,edge,end):
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectrum
    time,level,periodic,contrast,envelope=spectrum._read(features)
    core=spectrum._core(time,max(edge[0],edge[1]-260),edge[1]-180)
    if len(core)<3:
        return True
    high=(envelope[:,8:]**2).sum(axis=1)
    # Look through the measured rest. A distinct breath on the same lead can
    # survive the vowel, even when the frame classifier calls it silence.
    after=(time>=end)&(time<=edge[1]+2*phones.AGREEMENT_MS)
    breath=(after&(periodic<.5)&(high>max(.15,3*np.median(high[core])))
            &(level>np.median(level[core])-24)&(contrast>=.25))
    return bool(np.any(np.convolve(breath.astype(int),np.ones(3,dtype=int),'valid')==3))


def replay(t,trace):
    if trace['wordId']!=t['wordId'] or trace['spoken']!=t['spoken'] or t['language']!='ar':
        raise ValueError('Phrase release belongs to another word')
    guide=t.get('finalPhrase')
    if not guide or trace['guide']!=guide or len(trace['contexts'])!=2:
        return None
    reading=trace['reading']
    vowels={'aː','uː','iː'}
    nucleus=(reading[-1] if reading and reading[-1] in vowels else
             reading[-2] if len(reading)>1 and reading[-1]=='h' and reading[-2] in vowels else None)
    if not nucleus:
        return None
    next_phone=trace['nextPhone'] if guide['nextRecognized'] else None
    edges=[_landmark(runs,guide['hint'],nucleus,next_phone) for runs in trace['contexts']]
    if None in edges or edges[0][2]!=edges[1][2] or abs(edges[0][1]-edges[1][1])>phones.AGREEMENT_MS:
        return None
    values=[_measure(trace[view],edge,guide['hint']) for edge in edges for view in ('lead','vocal')]
    if None in values or max(values)-min(values)>phones.AGREEMENT_MS:
        return None
    end=values[0]
    if reading[-1]=='h' and edges[0][2]=='rest' and any(_rest_has_coda(trace['lead'],e,end) for e in edges):
        return None
    if max(abs(end-x) for x in guide['crops'])>phones.AGREEMENT_MS or not t['startMs']<end:
        return None
    return end


def _observe(context,targets):
    import numpy as np
    import onnxruntime as ort
    import soundfile as sf
    from scipy.signal import resample_poly
    from stemwise_engine import vocal_release_transition as spectrum
    from stemwise_engine.lead_vowel_observer import LANDMARKS
    model=Path(context['project']).parent/'model-cache'/phones.MODEL_DIR
    if (ort.__version__!='1.23.2' or sha256_file(model/'model.onnx')!=phones.MODEL or
            sha256_file(model/'vocab.json')!=phones.VOCAB):
        raise ValueError('Phrase-final acoustic assets changed')
    if any(sha256_file(Path(context[k]))!=context[k+'Sha256'] for k in ('lead','choir','vocal','backing')):
        raise ValueError('Phrase-final acoustic source changed')
    vocab=json.loads((model/'vocab.json').read_bytes())['vocab']
    classes={k.removeprefix('en/'):v for k,v in vocab.items() if k=='SP' or k.startswith('en/')}
    names=np.array(list(classes))
    opt=ort.SessionOptions()
    opt.intra_op_num_threads=3
    opt.inter_op_num_threads=1
    session=None
    results={}
    for t in targets:
        guide=t['finalPhrase']
        reading=_pronunciation(t['spoken'],'ar')
        if not any(reading[-len(seq):]==seq for v in ('aː','uː','iː') for seq in ([v],[v,'h'])):
            continue
        nextword=t.get('following') or {}
        following=_pronunciation(nextword.get('spoken',''),nextword.get('language')) if guide['nextRecognized'] else []
        hint=guide['hint']
        second=hint//1000*1000
        left=max(0,second-5000)
        right=min(context['durationMs']//10*10,second+3900)
        if right-hint<1000 or hint-left<3000:
            continue
        trace={'wordId':t['wordId'],'spoken':t['spoken'],'guide':guide,'reading':reading,
               'nextPhone':LANDMARKS.get(following[0]) if following else None,'contexts':[]}
        pcm={}
        for role in ('lead','choir','vocal','backing'):
            with sf.SoundFile(context[role]) as f:
                if (f.samplerate!=44100 or f.channels!=2 or
                        abs(len(f)*1000/44100-context['durationMs'])>2):
                    raise ValueError('Phrase-final acoustic clocks differ')
                f.seek(left*44100//1000)
                samples=f.read((right-left)*44100//1000,dtype='float32',always_2d=True)
                if len(samples)!=(right-left)*44100//1000 or not np.isfinite(samples).all():
                    raise ValueError('Incomplete phrase-final samples')
                pcm[role]=samples.mean(1)
        if session is None:
            session=ort.InferenceSession(str(model/'model.onnx'),opt,providers=['CPUExecutionProvider'])
        for trim in (0,2000):
            a=left+trim
            inset=trim*44100//1000
            wave=pcm['lead'][inset:len(pcm['lead'])-inset]
            logits,=session.run(['ph_frame_logits'],{'waveform':wave[None,:]})
            count=len(wave)//441
            if not count<=logits.shape[-1]<=count+1 or not np.isfinite(logits).all():
                raise ValueError('Phrase-final classifier clock changed')
            runs=[]
            for name,group in groupby(names[logits[0,list(classes.values()),:count].argmax(0)]):
                b=a+sum(1 for _ in group)*phones.FRAME_MS
                if b>=hint-1200 and a<=hint+500:
                    runs.append([a,b,str(name)])
                a=b
            trace['contexts'].append(runs)
        origin=max(left,hint//10*10-800)
        stop=min(right,hint//10*10+500)
        short={k:resample_poly(x[(origin-left)*44100//1000:(stop-left)*44100//1000],160,441)
               for k,x in pcm.items()}
        # The accompaniment and the simultaneous choir can each mask the lead.
        # Use the weaker measured band contrast on each frame, retaining the
        # same lead power/envelope. The full vocal view guards against isolation losses.
        f1=spectrum.extract(short['lead'],short['backing'],16000,origin)
        f2=spectrum.extract(short['lead'],short['choir'],16000,origin)
        for a,b in zip(f1['frames'],f2['frames'],strict=True):
            a[2]=min(a[2],b[2])
        trace['lead']=f1
        trace['vocal']=spectrum.extract(short['vocal'],short['backing'],16000,origin)
        if replay(t,trace) is not None:
            results[t['wordId']]=trace
    return results


def observe(context,targets):
    wanted=[t for t in targets if t.get('finalPhrase')]
    if not wanted:
        return {}
    project=Path(context['project'])
    with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
        proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
             input=json.dumps([context,wanted]),capture_output=True,text=True,encoding='utf-8',
             timeout=context['timeoutSeconds'])
    require_worker_success(proc,code='phrase-final-release-failed',
                           diagnostic_path=Path(context['cacheDir'])/'phrase-final-release.failure.private.txt')
    return parse_worker_json(proc.stdout)


if __name__=='__main__':
    print(json.dumps(_observe(*json.load(sys.stdin))))
