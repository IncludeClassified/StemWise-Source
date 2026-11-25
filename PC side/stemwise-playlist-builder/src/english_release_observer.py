"""Observe an English word's final phones on the recording’s vocal and instrumental stems."""
from itertools import groupby
import json
from pathlib import Path
import re
import sys

from stemwise_engine import endpoint_vowel_release as vowel
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.common import heavy_worker_stage, run_process_tree, sha256_file
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success

FRAME = vowel.FRAME_MS
BAR = vowel.AGREEMENT_MS
RUNTIME = ('1.23.2','2.2.6','0.13.1')
# Full, reduced and rhotic variants. Diphthong nuclei can change while a singer sustains them.
PHONES = {'ow':{'ow','ao'}, 'ax':{'ax','ah'}, 'ah':{'ah','ax'}, 'aa':{'aa','ah'},
          'er':{'er','_r','r'}, 'r':{'r','_r'}, 't':{'t','dx'}, 'd':{'d','dx'}}
VOWELS = vowel.VOWELS


def _hears(expected, actual):
    return actual in PHONES.get(expected, (expected,))


def _word_at(phones, first, last, pronunciation, start):
    """Require the pronounced onset, intervening phones and coda before a physical transition."""
    if not pronunciation or first>last or not _hears(pronunciation[-1],phones[last][2]):
        return False
    onset = next((k for k in range(first,last+1) if _hears(pronunciation[0],phones[k][2])
                  and phones[k][0] <= start+WORD_START_AGREEMENT_MS and phones[k][1] >= start-BAR), None)
    if onset is None:
        return False
    remaining = iter(pronunciation[1:])
    needed = next(remaining,None)
    matched = onset
    for k in range(onset+1,last+1):
        a,b,phone=phones[k]
        if phone=='SP' and b-a>=BAR:
            return False
        if needed is not None and _hears(needed,phone):
            matched=k
            needed=next(remaining,None)
        elif b-a>=BAR and phone not in VOWELS and phone not in ('r','_r') and not _hears(pronunciation[0],phone):
            return False
    if needed is not None:
        return False
    allowed_vowel = pronunciation[-1] in VOWELS
    return all((b-a<BAR or _hears(pronunciation[-1],ph) or allowed_vowel and ph in VOWELS)
               for a,b,ph in phones[matched:last+1])


def _runs(packet):
    a=packet['origin']
    result=[]
    for ph, frames in packet['frames']:
        if type(frames)!=int or frames<=0 or not isinstance(ph,str):
            raise ValueError('Invalid English word-release frame observation')
        b=a+frames*FRAME
        result.append((a,b,ph))
        a=b
    return result


def _release(target, variants, following_variants, view):
    vocals = _runs(view['vocal'])
    backing = _runs(view['backing'])
    if vocals[0][0]!=backing[0][0] or vocals[-1][1]!=backing[-1][1]:
        raise ValueError('English release views use different recording clocks')
    start=target['startMs']
    following = target['following']
    last = following['startMs'] if following else vocals[-1][1]
    first = next((k for k,(_,b,_) in enumerate(vocals) if b>=start-WORD_START_AGREEMENT_MS),0)
    found=set()
    for k in range(first,len(vocals)-1):
        a,end,ph=vocals[k]
        if not start<end<=last or end-a<2*FRAME:
            continue
        after_start,after_end,after=vocals[k+1]
        quiet = after=='SP' and after_end-after_start>=BAR
        if not quiet:
            if not following or abs(end-last)>WORD_START_AGREEMENT_MS or after=='SP':
                continue
            # Direct word-to-word transition needs the following lexical onset on these frames.
            present = [v for v in following_variants if _hears(v[0],after) and not _hears(v[0],ph)]
            if not present or not any(len(v)>1 and any(_hears(v[1],n) and x<last+WORD_START_AGREEMENT_MS
                       for x,_,n in vocals[k+2:]) for v in present):
                continue
        for v in variants:
            if not _word_at(vocals,first,k,v,start):
                continue
            if any(_hears(v[-1],other) and lo<end and hi>max(a,end-BAR) for lo,hi,other in backing):
                continue
            found.add(end)
    if not found or max(found)-min(found)>BAR:
        return None
    return min(found)


def replay(target, trace):
    if trace['model']!=vowel.MODEL or trace['lexicon']!=vowel.LEXICON or trace['spoken']!=target['spoken']:
        raise ValueError('English release measurement has a different word or model')
    if target['language']!='en' or len(trace['contexts'])!=2:
        return None
    offsets = [_release(target, trace['pronunciations'],trace['followingPronunciations'], v)
               for v in trace['contexts']]
    if None not in offsets and max(offsets)-min(offsets)<=BAR:
        return min(offsets)
    from stemwise_engine import english_release_tail
    physical = [english_release_tail.measure(target,trace,view,_runs) for view in trace['contexts']]
    evidence = [edge for edge in offsets+physical if edge is not None]
    if None in physical or max(evidence)-min(evidence)>BAR:
        return None
    return min(physical)


def _observe(context, targets):
    import numpy as np
    import soundfile as sf
    import onnxruntime as ort
    model = Path(context['project']).parent/'model-cache'/vowel.MODEL_DIR
    for file,digest in (('model.onnx',vowel.MODEL),('vocab.json',vowel.VOCAB),('ds_cmudict-07b.txt',vowel.LEXICON)):
        if sha256_file(model/file)!=digest:
            raise ValueError('English release model or dictionary differs')
    if (ort.__version__,np.__version__,sf.__version__)!=RUNTIME:
        raise ValueError('Unqualified English release runtime')
    lex={}
    for row in (model/'ds_cmudict-07b.txt').read_text(encoding='utf-8').splitlines():
        key, phones = row.split('\t',1)
        lex.setdefault(re.sub(r'\(\d+\)$','',key), []).append(phones.split())
    vocab=json.loads((model/'vocab.json').read_bytes())['vocab']
    classes = {k:v for k,v in vocab.items() if k=='SP' or k.startswith('en/')}
    labels=np.array([k.removeprefix('en/') for k in classes])
    indices=list(classes.values())
    options=ort.SessionOptions()
    options.intra_op_num_threads=4
    options.inter_op_num_threads=1
    session=ort.InferenceSession(str(model/'model.onnx'),options,providers=['CPUExecutionProvider'])
    result={}
    with sf.SoundFile(context['vocal']) as vocals, sf.SoundFile(context['backing']) as backing:
        if (len(vocals)!=len(backing) or abs(len(vocals)*1000//44100-context['durationMs'])>1
                or any((s.samplerate,s.channels)!=(44100,2) for s in (vocals,backing))):
            raise ValueError('Unexpected vocal release stem geometry')
        for target in targets:
            spoken=target['spoken'].casefold().replace('’',"'")
            variants=lex.get(spoken,())
            if not variants:
                continue
            nxt=target['following']
            next_forms=lex.get(nxt['spoken'].casefold().replace('’',"'"),[]) if nxt and nxt['language']=='en' else []
            region_end=nxt['startMs'] if nxt else max([target['endMs'],*[w['endMs'] for w in target['candidates']]])
            crops=[(max(0,(target['startMs']-left)//FRAME*FRAME),
                    min(context['durationMs']//FRAME*FRAME,(region_end+right)//FRAME*FRAME))
                   for left,right in ((1700,1100),(2900,1900))]
            if len(set(crops))!=2:
                continue
            views=[]
            for a,b in crops:
                # Use only uncensored, word-local windows inside the model context.
                if b-a>30000 or b<=a or b-a<2*BAR:
                    break
                view={}
                for name,stream in (('vocal',vocals),('backing',backing)):
                    stream.seek(a*44100//1000)
                    count=(b-a)*44100//1000
                    samples=stream.read(count,dtype='float32',always_2d=True)
                    if len(samples)!=count or not np.isfinite(samples).all():
                        raise ValueError('Incomplete English release PCM')
                    logits,=session.run(['ph_frame_logits'],{'waveform':samples.mean(1)[None,:]})
                    frame_count=count//441
                    if not frame_count<=logits.shape[-1]<=frame_count+1 or not np.isfinite(logits).all():
                        raise ValueError('Invalid English release emission geometry')
                    phones=labels[logits[0,indices,:frame_count].argmax(0)]
                    view[name]={'origin':a,'frames':[[ph, sum(1 for _ in group)] for ph,group in groupby(phones.tolist())]}
                views.append(view)
            if len(views)==2:
                trace={'model':vowel.MODEL,'lexicon':vowel.LEXICON,'spoken':target['spoken'],
                       'pronunciations':variants,'followingPronunciations':next_forms,'contexts':views}
                witness=target.get('mixWitness')
                if witness and replay(target,trace) is None:
                    from stemwise_engine import vocal_release_transition
                    a=max(0,target['startMs']-400)
                    b=min(context['durationMs'],region_end+500)
                    frame=a*44100//1000
                    count=b*44100//1000-frame
                    samples=[]
                    for stream in (vocals,backing):
                        stream.seek(frame)
                        samples.append(stream.read(count,dtype='float32',always_2d=True).mean(1))
                    trace['spectrum']=vocal_release_transition.extract(*samples,44100,frame*1000/44100)
                    observed=(witness.get('next') or '').strip().strip('.,!?').casefold()
                    trace['observedFollowingForms']=lex.get(observed,[])
                result[target['wordId']]=trace
    return result


def observe(context, targets):
    english=[t for t in targets if t['language']=='en' and t['releaseDisputed']]
    project=Path(context['project'])
    if not english or not (project.parent/'model-cache'/vowel.MODEL_DIR/'model.onnx').is_file():
        return {}
    with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
        proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
            input=json.dumps([context,english]),capture_output=True,text=True,encoding='utf-8',
            timeout=context['timeoutSeconds'])
    require_worker_success(proc,code='disputed-english-release-failed',
                           diagnostic_path=Path(context['cacheDir'])/'word-release-observer.failure.private.txt')
    return parse_worker_json(proc.stdout)


if __name__=='__main__':
    print(json.dumps(_observe(*json.load(sys.stdin))))
