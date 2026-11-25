"""Measure an exposed Arabic open-vowel release after recording-local lead recognition."""
from itertools import groupby
import json
from pathlib import Path
import re
import sys

from stemwise_engine import endpoint_vowel_release as phones
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.common import (heavy_worker_stage, read_bounded_json, run_process_tree, sha256_file,
                                    validate_generated_path)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyric_comparison import units
from stemwise_engine.lyric_preview.reading_review import _bound_json
from stemwise_engine.multilingual_release_observer import _pronunciation
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


ASSETS = (phones.MODEL, phones.VOCAB, 'onnxruntime-1.23.2-CPU')
# The pinned frame classifier has English phone classes. Use only these directly
# observable acoustic landmarks in a word independently recognized on the lead.
LANDMARKS = {'ʃ':'sh','s':'s','f':'f','l':'l','m':'m','n':'n','r':'r','b':'b',
             'd':'d','t':'t','k':'k','z':'z','j':'y','iː':'iy','uː':'uw'}
OPEN = {'aa','ae','ao','ah','ax'}


def inputs(folder, audio):
    """Resolve the saved lead, choir and natural lead transcript on this exact recording."""
    root=folder/'vocal-overlap'
    try:
        rec=read_bounded_json(root/'review.private.json',max_bytes=MAX_JSON_BYTES,root=root)
        if not rec.get('backingActivitySha256') or not re.fullmatch('[a-f0-9]{64}',rec['folder']):
            return None
        view=validate_generated_path(root,root/rec['folder'])
        activity=_bound_json(view/'backing-activity.private.json',rec['backingActivitySha256'],view)
        if (activity['recordingSha256']!=audio['sha256'] or activity['durationMs']!=audio['durationMs']
                or not activity.get('readings',{}).get('lead')):
            return None
        part=validate_generated_path(view,view/activity.get('pairDirectory','vocal-parts'))
        pair=_bound_json(part/'pair.private.json',activity['pairSha256'],part)
        words=part/'lead-words.private.json'
        reading=_bound_json(words,activity['readings']['lead'],part)
        if pair['binding']['originalSha256']!=audio['sha256'] or reading['binding']['sha256']!=pair['lead']:
            return None
        ids={'leadSha256':pair['lead'],'choirSha256':pair['choir'],'leadAsrSha256':activity['readings']['lead']}
        if not all(re.fullmatch('[a-f0-9]{64}',s) for s in ids.values()):
            return None
        paths={'lead':str(part/'lead.wav'),'choir':str(part/'choir.wav'),'leadAsr':str(words)}
        return (ids,paths) if all(Path(path).is_file() for path in paths.values()) else None
    except (OSError, ValueError, KeyError, TypeError):
        return None


def _triplets(target, words):
    owners=(target.get('previous'),target,target.get('following'))
    if any(not w or w['language']!='ar' for w in owners):
        return []
    wanted=[units(w['text'],'ar') for w in owners]
    if any(len(unit)!=1 for unit in wanted):
        return []
    start=target['startMs']
    return [triple for triple in zip(words,words[1:],words[2:])
            if triple[1]['startMs']<=start<triple[1]['endMs']
            and all(0<=w['startMs']<w['endMs'] for w in triple)
            and triple[0]['endMs']<=triple[1]['startMs']<triple[1]['endMs']<=triple[2]['startMs']
            and [units(w['text'],'ar') for w in triple]==wanted]


def _edge(target, reading, following, runs, *, fading=False):
    """An uninterrupted final nasal-to-vowel sequence must end in a real rest."""
    if (len(reading)<4 or reading[-1]!='aː' or reading[-2] not in {'n','m'}
            or not following or following[0] not in LANDMARKS or reading[0] not in LANDMARKS):
        return None
    start=target['startMs']
    bound=target['following']['startMs']
    if not any(c['family'] not in {'owsm-ctc','mms-fa'} and abs(c['startMs']-start)<=phones.AGREEMENT_MS
               for c in target['candidates']):
        return None
    # At least one earlier interior phone besides the initial and the final
    # consonant must be distinguishable in this physical rendition.
    mids={LANDMARKS[p] for p in reading[1:-2] if p in LANDMARKS}-{LANDMARKS[reading[0]],reading[-2]}
    entry=[i for i,(a,b,p) in enumerate(runs) if p==LANDMARKS[reading[0]]
           and a<=start+phones.AGREEMENT_MS and b>=start-phones.AGREEMENT_MS and b-a>=20]
    if not entry or not mids:
        return None
    found=[]
    for i,(a,b,ph) in enumerate(runs):
        if i<=entry[0] or ph!='SP' or b-a<phones.AGREEMENT_MS or not start<a<bound:
            continue
        next_phones=[p for l,r,p in runs[i+1:] if l<bound+WORD_START_AGREEMENT_MS and r>bound-WORD_START_AGREEMENT_MS
                     and p!='SP']
        if not next_phones or next_phones[0]!=LANDMARKS[following[0]]:
            continue
        prefix=runs[entry[0]:i]
        nasal=next((k for k in range(len(prefix)-1,-1,-1) if prefix[k][2]==reading[-2]
                    and prefix[k][1]-prefix[k][0]>=20),None)
        if nasal is None or not any(p in mids and y-x>=30 for x,y,p in prefix[:nasal]):
            continue
        if any(p=='SP' and y-x>=phones.AGREEMENT_MS for x,y,p in prefix):
            continue
        tail=prefix[nasal+1:]
        # A weak, dying vowel can flicker between its vowel class and rest.
        # Permit this only in the bounded estimate path, directly before the
        # final rest. The recording must also show that the voice decayed.
        if not tail or any(p not in OPEN and y-x>=30 and not (
                fading and p=='SP' and y-x<phones.AGREEMENT_MS and x>=a-2*phones.AGREEMENT_MS
                and 0<k<len(tail)-1 and tail[k-1][2] in OPEN and tail[k+1][2] in OPEN)
                for k,(x,y,p) in enumerate(tail)):
            continue
        if a-tail[0][0]<3*phones.AGREEMENT_MS or sum(y-x for x,y,p in tail if p in OPEN)<.8*(a-tail[0][0]):
            continue
        found.append(a)
    return found[0] if len(found)==1 else None


def _breath_window(target, reading, following, runs):
    """Locate a sustained open vowel before a possible glottal coda and separate next glide."""
    if len(reading)<3 or reading[-2:]!=['aː','h'] or reading[-3] not in LANDMARKS or following[:1]!=['j']:
        return None
    start = target['startMs']
    if not any(c['family'] not in ('mms-fa','owsm-ctc') and abs(c['startMs']-start)<=WORD_START_AGREEMENT_MS
               for c in target['candidates']):
        return None
    found=[]
    for i,(a,b,p) in enumerate(runs):
        if p!='SP' or b-a<phones.AGREEMENT_MS or not start<a<target['following']['startMs']:
            continue
        consonants=[j for j,(x,y,ph) in enumerate(runs[:i]) if ph==LANDMARKS[reading[-3]]
                    and y-x>=20 and y>=start-phones.AGREEMENT_MS and x<=start+2*WORD_START_AGREEMENT_MS]
        if not consonants:
            continue
        body=runs[consonants[-1]+1:i]
        if (not body or a-body[0][0]<3*phones.AGREEMENT_MS
                or any(ph not in OPEN|{'aw'} and y-x>=30 for x,y,ph in body)):
            continue
        after=next(((x,ph) for x,y,ph in runs[i+1:] if y-x>=20 and ph!='SP'),None)
        if after and after[1]=='y' and abs(after[0]-target['following']['startMs'])<=WORD_START_AGREEMENT_MS:
            found.append((a,after[0]))
    return found[0] if len(found)==1 else None


def _breath_end(backing, choir, window):
    """Follow the aspirate's own noise crest and decay after the independently located voiced nucleus."""
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read
    a,b=window
    time,level,periodic,over_backing,env=_read(backing)
    t,_,_,over_choir,_=_read(choir)
    if not np.array_equal(time,t):
        raise ValueError('Glottal coda comparisons use different recording clocks')
    vowel=(time>=a-250)&(time<=a-150)
    if (np.count_nonzero(vowel)<6 or np.median(periodic[vowel])<.65
            or min(np.median(over_backing[vowel]),np.median(over_choir[vowel]))<.4):
        return None
    # The same lead is compared to two possible sources of masking. Neither
    # a falling vowel level nor a classifier silence label measures the /h/.
    high=(env[:,8:]**2).sum(axis=1)  # above 2.14 kHz
    hdb=level+10*np.log10(np.maximum(high,1.e-12))
    after=(time>=a-30)&(time<b)
    aspirated=(after & (periodic<.50) & (high>max(.15,3*np.median(high[vowel])))
               & (over_backing>=.25) & (over_choir>=.4))
    if not np.any(np.convolve(aspirated.astype(int), np.ones(3,dtype=int),'valid')==3):
        return None
    at=np.flatnonzero(aspirated)
    peak=at[np.argmax(hdb[at])]
    onset=np.flatnonzero((time>=a-60)&(time<time[at[0]])&(periodic>=.60))
    if not len(onset) or hdb[peak]-np.min(hdb[onset])<6:
        return None
    # A sharp sibilant or percussive hit preceded by a separate excitation
    # cannot be treated as the final glottal continuation of this vowel.
    if (time[at[0]]-time[onset[-1]]>120 or np.median(high[at])>.85
            or np.min(level[onset[-1]:at[0]+1])<level[peak]-12):
        return None
    quiet=after & (hdb<=hdb[peak]-15) & (level<=np.median(level[vowel])-18)
    for i in range(peak+1,len(time)-3):
        if time[i+2]>=b:
            break
        if quiet[i:i+3].all():
            rest=(time>=time[i])&(time<b)
            if np.any(hdb[rest]>hdb[peak]-9):
                return None  # Renewed breath before the next consonant.
            return int(round(time[i]))
        if periodic[i]>.65 and level[i]>np.median(level[vowel])-9:
            break
    # A next glide's classifier entrance can precede the actual new acoustic
    # attack. Test for the end of the aspirate at the measured energy trough,
    # with a separate rising, voiced low-frequency onset on the far side.
    # No part of the following phone supplies the timestamp itself.
    level_floor=np.median(level[vowel])
    valley=np.flatnonzero((time>time[peak])&(time>=b-80)&(time<=b+40)
                          &(hdb<=hdb[peak]-15)&(level<=level_floor-15)&(high<=.15))
    if len(valley)<2 or np.any(np.diff(valley)>1):
        return None
    region=(time>time[valley[-1]])&(time<=b+120)
    # A choir may join the following word. Require the new attack to be
    # audible on the lead, without requiring the choir to stay quiet after it.
    glide=region&(periodic>=.65)&(high<=.14)&(over_backing>=.4)
    if (not np.any(glide&(over_choir>=.4)) or
            not np.any(np.convolve(glide.astype(int),np.ones(2,dtype=int),'valid')==2)):
        return None
    first=np.flatnonzero(glide)[0]
    if (np.max(level[glide])-np.min(level[valley])<9 or
            np.max(hdb[peak:valley[0]+1]-np.minimum.accumulate(hdb[peak:valley[0]+1]))>6 or
            np.any(high[valley[0]:first]>.25)):
        return None
    return int(round(time[valley[0]]))


def _masked_fade(target, trace, edges):
    """Bound an exposed lead vowel that becomes masked while fading into a real rest."""
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectrum
    from stemwise_engine.timing_estimate_policy import LIMIT_MS
    clock,level,periodic,over_choir,envelope=spectrum._read(trace['leadOverChoir'])
    other,_,_,over_backing,_=spectrum._read(trace['leadOverBacking'])
    if not np.array_equal(clock,other):
        raise ValueError('Lead vowel comparisons use different recording clocks')
    readings=[]
    for edge in edges:
        upper=spectrum.decide(trace['leadOverChoir'],edge-270,edge-150,edge,
                              following_phone_ms=target['following']['startMs'])
        if upper is None:
            return None
        core=(clock>=edge-270)&(clock<=edge-150)
        if np.count_nonzero(core)<6 or min(np.median(over_backing[core]),np.median(over_choir[core]))<.6:
            return None
        shape=np.median(envelope[core],axis=0)
        shape/=max(np.linalg.norm(shape),1e-12)
        relative=level-np.median(level[core])
        near=(clock>=edge-270)&(clock<=upper)
        exposed=near&(envelope@shape>=.8)&(periodic>=.6)&(relative>=-9)&(over_backing>=.4)&(over_choir>=.4)
        if not np.any(exposed):
            return None
        last=np.flatnonzero(exposed)[-1]
        cutoff=min(target['following']['startMs']-60,upper+100)
        tail=(clock>clock[last])&(clock<=cutoff)
        quiet=(clock>=upper)&(clock<=cutoff)
        if (np.count_nonzero(quiet)<6 or np.any(relative[quiet]>-15) or np.any(relative[tail]>-6)
                or not np.any(tail&(over_backing<.25)&(over_choir>.4)&(relative<=-10)&(relative>=-18))):
            return None
        # Earlier timers are cut inside the independently recognized final
        # nasal-to-vowel progression and the still exposed same lead. Keep
        # every equivalent or later native competitor in the estimate range.
        ends=[c['endMs'] for c in target['candidates'] if c['family']!='owsm-ctc'
              and abs(c['startMs']-target['startMs'])<=WORD_START_AGREEMENT_MS and c['endMs']>=clock[last]]
        values=[clock[last],edge,upper,*ends]
        if max(values)-min(values)>=LIMIT_MS:
            return None
        readings.append(upper)
    if max(readings)-min(readings)<=phones.AGREEMENT_MS:
        return readings[0]
    return None


def replay(target, trace):
    from stemwise_engine import vocal_release_transition as spectrum
    if (target['wordId']!=trace['wordId'] or target['language']!='ar' or trace['spoken']!=target['spoken']
            or not target.get('following') or trace['following']!=target['following']['spoken']):
        raise ValueError('Lead observation belongs to another performed word')
    if len(_triplets(target,trace['triplet']))!=1 or len(trace['contexts'])!=2:
        return None
    if trace['pronunciation'][-2:]==['aː','h']:
        windows=[_breath_window(target,trace['pronunciation'],trace['nextPhones'],runs) for runs in trace['contexts']]
        if None in windows or any(abs(a-b)>phones.AGREEMENT_MS for a,b in zip(*windows)):
            return None
        measured=[_breath_end(trace['leadOverBacking'],trace['leadOverChoir'],w) for w in windows]
        return measured[0] if None not in measured and abs(measured[0]-measured[1])<=phones.AGREEMENT_MS else None
    edges=[_edge(target,trace['pronunciation'],trace['nextPhones'],runs) for runs in trace['contexts']]
    if None in edges:
        fading=[_edge(target,trace['pronunciation'],trace['nextPhones'],runs,fading=True) for runs in trace['contexts']]
        if None not in fading and max(fading)-min(fading)<=phones.AGREEMENT_MS:
            return _masked_fade(target,trace,fading)
        return None
    if max(edges)-min(edges)>phones.AGREEMENT_MS:
        return None
    results=[]
    for edge in edges:
        # Use the model's sustained-vowel exit only to locate a recording
        # transition. Measure that transition independently on both contrasts.
        for key in ('leadOverBacking','leadOverChoir'):
            end=spectrum.decide(trace[key],edge-270,edge-150,edge,
                                following_phone_ms=target['following']['startMs'])
            if end is None:
                return None
            results.append(end)
    return results[0] if max(results)-min(results)<=phones.AGREEMENT_MS else None


def _observe(context, targets):
    import numpy as np
    import onnxruntime as ort
    import soundfile as sf
    from scipy.signal import resample_poly
    from stemwise_engine import vocal_release_transition as spectrum

    roles=('lead','choir','backing')
    for key in (*roles,'leadAsr'):
        if sha256_file(Path(context[key]))!=context[key+'Sha256']:
            raise ValueError('Lead-release source changed')
    reading=read_bounded_json(Path(context['leadAsr']),max_bytes=MAX_JSON_BYTES,root=Path(context['leadAsr']).parent)
    if reading['binding']['sha256']!=context['leadSha256']:
        raise ValueError('Word recognition belongs to a different lead recording')
    model=Path(context['project']).parent/'model-cache'/phones.MODEL_DIR
    if (ort.__version__!='1.23.2' or sha256_file(model/'model.onnx')!=phones.MODEL
            or sha256_file(model/'vocab.json')!=phones.VOCAB):
        raise ValueError('Lead-release acoustic model changed')
    vocab=json.loads((model/'vocab.json').read_bytes())['vocab']
    classes={k.removeprefix('en/'):v for k,v in vocab.items() if k=='SP' or k.startswith('en/')}
    labels=np.array(list(classes))
    options=ort.SessionOptions()
    options.intra_op_num_threads=3
    options.inter_op_num_threads=1
    session=None
    observed={}
    for target in targets:
        triples=_triplets(target,reading['words'])
        if len(triples)!=1:
            continue
        spoken=_pronunciation(target['spoken'],'ar')
        nxt=_pronunciation(target['following']['spoken'],'ar')
        if len(spoken)<4 or spoken[-2:] not in (['n','aː'],['m','aː'],['aː','h']) or not nxt:
            continue
        a=max(0,(target['startMs']-1500)//10*10)
        b=min(context['durationMs']//10*10,(target['following']['startMs']+600)//10*10)
        if b-a>30000 or b-a<=1000 or a==0:
            continue
        pcm={}
        for key in roles:
            with sf.SoundFile(context[key]) as stream:
                if (stream.samplerate!=44100 or stream.channels!=2 or
                        abs(1000*len(stream)/44100-context['durationMs'])>2):
                    raise ValueError('Lead-release stems use a different recording clock')
                stream.seek(a*44100//1000)
                samples=stream.read((b-a)*44100//1000,always_2d=True,dtype='float32')
                if len(samples)!=(b-a)*44100//1000 or not np.isfinite(samples).all():
                    raise ValueError('Invalid acoustic samples in the lead-release window')
                pcm[key]=samples.mean(1)
        if session is None:
            session=ort.InferenceSession(str(model/'model.onnx'),options,providers=['CPUExecutionProvider'])
        trace={'wordId':target['wordId'],'spoken':target['spoken'],'following':target['following']['spoken'],
               'triplet':triples[0],'pronunciation':spoken,'nextPhones':nxt,'contexts':[]}
        for trim in (0,700):
            waveform=pcm['lead'][trim*44100//1000:]
            logits,=session.run(['ph_frame_logits'],{'waveform':waveform[None,:]})
            count=(b-a-trim)//10
            if not count<=logits.shape[-1]<=count+1 or not np.isfinite(logits).all():
                raise ValueError('Invalid frames in the lead-release window')
            seq=labels[logits[0,list(classes.values()),:count].argmax(0)]
            cursor=a+trim
            runs=[]
            for ph,grp in groupby(seq):
                end=cursor+sum(1 for _ in grp)*phones.FRAME_MS
                runs.append([cursor,end,str(ph)])
                cursor=end
            trace['contexts'].append(runs)
        locate = _breath_window if spoken[-2:]==['aː','h'] else _edge
        if any((locate(target,spoken,nxt,runs,fading=True) if locate is _edge else
                locate(target,spoken,nxt,runs)) is None for runs in trace['contexts']):
            continue
        mono={key:resample_poly(wav,160,441) for key,wav in pcm.items()}
        for key,other in [('leadOverBacking','backing'),('leadOverChoir','choir')]:
            trace[key]=spectrum.extract(mono['lead'],mono[other],16000,a)
        if replay(target,trace) is not None:
            observed[target['wordId']]=trace
    return observed


def observe(context,targets):
    wanted=[t for t in targets if t.get('language')=='ar' and t.get('releaseDisputed') and not t.get('locked')]
    if not wanted:
        return {}
    project=Path(context['project'])
    with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
        proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
                              input=json.dumps([context,wanted]),capture_output=True,text=True,encoding='utf-8',
                              timeout=context['timeoutSeconds'])
    require_worker_success(proc,code='arabic-lead-release-failed',
                           diagnostic_path=Path(context['cacheDir'])/'arabic-lead-release.failure.private.txt')
    return parse_worker_json(proc.stdout)


if __name__=='__main__':
    print(json.dumps(_observe(*json.load(sys.stdin))))
