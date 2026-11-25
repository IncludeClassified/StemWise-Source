"""Resolve disputed estimates using the independently recognized word on its actual vocal part."""
from pathlib import Path
import json
import sys
import unicodedata
from itertools import groupby

from stemwise_engine import endpoint_vowel_release as phones, lead_vowel_observer as lead
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.arabic_role_letters import ArabicLetters, ASSETS
from stemwise_engine.common import heavy_worker_stage, read_bounded_json, run_process_tree, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyric_preview.reading_review import _bound_json
from stemwise_engine.multilingual_release_observer import _pronunciation
from stemwise_engine.recording_repeat_words import _inputs, _tight
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.timing_estimate_policy import LIMIT_MS
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success

STOPS={'ك':'k','ت':'t','ق':'q','ب':'b','د':'d'}


def _base(text):
    # Acoustic grapheme skeleton only. Display, spoken marks and word identity
    # are bound independently, and the actual pronounced terminal is checked.
    return ''.join(c for c in text if unicodedata.category(c).startswith('L'))


def _stop_limit(spoken,reading):
    stop=STOPS.get(_base(spoken)[-1:])
    if not stop or not reading:
        return 0
    if reading[-1]==stop:
        return LIMIT_MS-1
    if (len(reading)>=2 and reading[-2]==stop and reading[-1] in {'a','i','u'}
            and not unicodedata.category(spoken[-1]).startswith('M')):
        # Unmarked Arabic can retain a short inflection after the written
        # stop. Bound the ENTIRE possible vowel between the recorded stop and
        # the distinct next glide before selecting any acoustic estimate.
        return phones.AGREEMENT_MS
    return 0


def _eligible(t):
    if (t['language']!='ar' or not t['releaseDisputed'] or t['locked']
            or not t.get('previous') or not t.get('following')):
        return False
    last=_base(t['spoken'])[-1:]
    if last not in STOPS and not (last=='ه' and _base(t['following']['spoken']).startswith('ي')):
        return False
    # Sub-second competitors already have a calibrated selection path. Avoid
    # adding a new decoder dependency to recordings whose remaining words never need it.
    ends=[c['endMs'] for c in t['candidates'] if c['family']!='owsm-ctc'
          and abs(c['startMs']-t['startMs'])<=WORD_START_AGREEMENT_MS]
    return len(ends)>=2 and max(ends)-min(ends)>=LIMIT_MS


def attach(folder,audio,original,admission,targets):
    wanted=[t for t in targets if _eligible(t)]
    if not wanted:
        return None
    saved=_inputs(folder,audio,original,admission.source_physical_indices)
    if not saved or 'lead' not in saved[2] or 'choir' not in saved[2]:
        return None
    timing,lines,paths=saved
    part=Path(paths['lead'][0]).parent
    view=part.parent
    rec=read_bounded_json(view.parent/'review.private.json',max_bytes=MAX_JSON_BYTES,root=view.parent)
    activity=_bound_json(view/'backing-activity.private.json',rec['backingActivitySha256'],view)
    ids={}
    for role in ('lead','choir'):
        digest=activity['readings'].get(role)
        if not digest:
            continue
        raw=_bound_json(part/(role+'-words.private.json'),digest,part)
        if raw['binding']['sha256']!=paths[role][1]:
            raise ValueError('Arabic word witness belongs to another vocal role')
        for t in wanted:
            if len(lead._triplets(t,raw['words']))!=1:
                continue
            row=lines[t['line']]
            word=row['words'][t['wordIndex']]
            if word['text']!=t['text'] or not all(_tight(word,j) for j in (0,1)):
                continue
            c=t.setdefault('roleCoda',{'guide':[word['startMs'],word['endMs']],
                             'crops':word['vocalCropRangeMs'][1],'lineText':row['text'],'roles':[]})
            c['roles'].append(role)
            ids['coda'+role.title()+'Words']=digest
    return {**ids,'codaGuide':timing} if ids else None


def _groups(events):
    groups=[]
    word=[]
    for a,b,c,p in events:
        if c=='|' and p>=.5:
            if word:
                groups.append(word)
            word=[]
        elif len(c)==1 and _base(c) and p>=.2:
            word.append((a,b,c,p))
    if word:
        groups.append(word)
    return groups


def _stop_bracket(t,events):
    word=_base(t['spoken'])
    following=_base(t['following']['spoken'])
    if len(word)<3 or len(following)<2:
        return None
    found=[]
    groups=_groups(events)
    for current,nxt in zip(groups,groups[1:]):
        if (''.join(x[2] for x in current)!=word or len(nxt)<2 or
                min(x[3] for x in current)<.5 or nxt[0][2]!=following[0] or nxt[0][3]<.5
                or abs(current[0][0]-t['startMs'])>WORD_START_AGREEMENT_MS):
            continue
        successor=nxt[1:]
        if successor[0][2] in 'اوي' and successor[0][2]!=following[1]:
            successor=successor[1:]
        if not successor or successor[0][2]!=following[1] or successor[0][3]<.5:
            continue
        found.append((current[-1][1],nxt[0][0]))
    return found[0] if len(found)==1 else None


def _root_level(t,events):
    # A sung aspirate can be absent in the character transcription. Its
    # actual breath after the vowel must be measured separately on this role.
    root=_base(t['spoken'])[:-1]
    if len(root)<3 or len(set(root))<2:
        return 0
    near=[g for g in _groups(events) if abs(g[0][0]-t['startMs'])<=WORD_START_AGREEMENT_MS]
    roots=[]
    for g in near:
        chars=''.join(x[2] for x in g)
        if chars.startswith(root) and all(x[3]>=.35 for x in g[:len(root)]):
            roots.append(2)
        elif chars.startswith(root[:2]) and len(chars)>=len(root):
            roots.append(1)
    return roots[0] if len(roots)==1 else 0


def _breath_clock(t,runs):
    start=t['startMs']
    found=[]
    for i,(a,b,p) in enumerate(runs):
        if p!='SP' or b-a<phones.AGREEMENT_MS or not start<a<t['following']['startMs']:
            continue
        after=next(((x,ph) for x,y,ph in runs[i+1:] if y-x>=20 and ph!='SP'),None)
        if not after or after[1]!='y' or abs(after[0]-t['following']['startMs'])>WORD_START_AGREEMENT_MS:
            continue
        tail=[]
        for x,y,ph in reversed(runs[:i]):
            if ph not in lead.OPEN|{'aw'} and y-x>=30:
                break
            tail.append((x,y,ph))
        if (not tail or a-tail[-1][0]<300 or tail[-1][0]<start-100 or
                sum(y-x for x,y,ph in tail if ph in lead.OPEN)<.8*(a-tail[-1][0])):
            continue
        found.append((a,after[0]))
    return found[0] if len(found)==1 else None


def _coda(t,tr):
    if tr['spoken']!=t['spoken'] or tr['wordId']!=t['wordId'] or tr['role'] not in t['roleCoda']['roles']:
        raise ValueError('Arabic coda belongs to another vocal word')
    if len(tr['letters'])!=2 or not tr['reading']:
        return None
    reading=tr['reading']
    stop_limit=_stop_limit(t['spoken'],reading)
    if stop_limit:
        windows=[_stop_bracket(t,e) for e in tr['letters']]
        if None in windows:
            return None
        a,b=max(x[0] for x in windows),min(x[1] for x in windows)
        guide=t['roleCoda'];end=guide['guide'][1]
        eligible=[c['endMs'] for c in t['candidates'] if c['family']!='owsm-ctc'
                  and abs(c['startMs']-t['startMs'])<=WORD_START_AGREEMENT_MS and a<=c['endMs']<=b]
        if (a<b and b-a<=stop_limit and all(a<=v<=b for v in [end,*guide['crops'],*eligible])
                and all(t['startMs']<v for v in [end,*guide['crops']])):
            return end  # Retained target-vocal measurement, bounded between the actual named gestures.
    elif reading[-2:]==['aː','h'] and tr['nextReading'][:1]==['j']:
        levels=[_root_level(t,e) for e in tr['letters']]
        if min(levels)<1:
            return None
        if max(levels)<2:
            peer=tr.get('peer')
            if not peer or not _matches_peer(t,peer):
                return None
        windows=[_breath_clock(t,runs) for runs in tr['phones']]
        if len(windows)!=2 or None in windows or any(abs(a-b)>phones.AGREEMENT_MS for a,b in zip(*windows)):
            return None
        ends=[lead._breath_end(tr['overBacking'],tr['overOther'],w) for w in windows]
        if None not in ends and max(ends)-min(ends)<=phones.AGREEMENT_MS:
            return ends[0]
    return None


def _matches_peer(t,peer):
    source=peer['target']
    if (t['wordId']==source['wordId'] or t['spoken']!=source['spoken'] or
            t['roleCoda']['lineText']!=source['roleCoda']['lineText'] or t['wordIndex']!=source['wordIndex'] or
            t['line']==source['line'] or t['previous']['spoken']!=source['previous']['spoken'] or
            t['following']['spoken']!=source['following']['spoken'] or
            min(_root_level(source,e) for e in peer['letters'])<1 or
            max(_root_level(source,e) for e in peer['letters'])<2):
        return False
    a,b=t['roleCoda']['guide'];c,d=source['roleCoda']['guide']
    cuts=peer['matches']
    if max(a,c)<min(b,d) or len(cuts)!=3:
        return False
    return min(score for score,_ in cuts)>=.7 and max(lag for _,lag in cuts)-min(lag for _,lag in cuts)<=20


def replay(t,trace):
    decisions=[]
    overlap=None
    for one in trace.get('coda',[]):
        end=_coda(t,one)
        if end is not None:
            decisions.append({'endMs':end})
    if 'crossLane' in trace:
        from stemwise_engine import role_phrase_overlap
        d=role_phrase_overlap.replay(t,trace['crossLane'])
        if d is not None:
            overlap=d if isinstance(d,dict) else None
            decisions.append({'endMs':d} if type(d) is int else d)
    if not decisions:
        return None
    result={}
    for key in ('startMs','endMs'):
        values=[d[key] for d in decisions if d.get(key) is not None]
        if values:
            if max(values)-min(values)>phones.AGREEMENT_MS:
                return None
            result[key]=values[0]
    if (overlap and overlap.get('otherSingerWordIds') and result.get('endMs')==overlap.get('endMs')):
        result['otherSingerWordIds']=overlap['otherSingerWordIds']
    return result or None


def _read_role(path,a,b,duration):
    import numpy as np
    import soundfile as sf
    with sf.SoundFile(path) as f:
        if (f.samplerate,f.channels)!=(44100,2) or abs(1000*len(f)/44100-duration)>2:
            raise ValueError('Arabic role clocks differ from the recording')
        first,last=round(a*44100/1000),round(b*44100/1000)
        f.seek(first)
        samples=f.read(last-first,dtype='float32',always_2d=True)
        if len(samples)!=last-first or not np.isfinite(samples).all():
            raise ValueError('Incomplete Arabic role samples')
        return samples.mean(1)


def _classifier(project):
    import numpy as np
    import onnxruntime as ort
    model=Path(project).parent/'model-cache'/phones.MODEL_DIR
    if (ort.__version__!='1.23.2' or sha256_file(model/'model.onnx')!=phones.MODEL or
            sha256_file(model/'vocab.json')!=phones.VOCAB):
        raise ValueError('Arabic coda phone observer changed')
    classes={k.removeprefix('en/'):v for k,v in json.loads((model/'vocab.json').read_bytes())['vocab'].items()
             if k=='SP' or k.startswith('en/')}
    opt=ort.SessionOptions()
    opt.intra_op_num_threads=3
    opt.inter_op_num_threads=1
    session=ort.InferenceSession(str(model/'model.onnx'),opt,providers=['CPUExecutionProvider'])
    def detect(pcm,origin):
        logits,=session.run(['ph_frame_logits'],{'waveform':pcm[None,:]})
        count=len(pcm)//441
        if not count<=logits.shape[-1]<=count+1 or not np.isfinite(logits).all():
            raise ValueError('Arabic coda phone frame clock changed')
        runs=[]
        for p,g in groupby(np.array(list(classes))[logits[0,list(classes.values()),:count].argmax(0)]):
            end=origin+10*sum(1 for _ in g)
            runs.append([origin,end,str(p)])
            origin=end
        return runs
    return detect


def _correspondence(t,peer,streams):
    from stemwise_engine.recording_phrase_placement import _voice,correlate_word
    a,b=t['roleCoda']['guide'];c,d=peer['roleCoda']['guide']
    width=min(b-a,d-c)
    if max(a,c)<min(b,d) or width<1200:
        return []
    result=[]
    for fraction in (.1,.45,.76):
        offset=round(width*fraction)
        size=min(400,width-offset)
        cut=c+offset
        near=a+offset
        if min(cut,near)<80:
            return []
        raw=_voice(streams,cut,cut+size)
        actual=_voice(streams,near-80,near+size+80)
        score,index=correlate_word(raw,actual)
        result.append([round(score,5),round(index/8-80,3)])
    return result


def _observe(context,targets):
    from contextlib import ExitStack
    from scipy.signal import resample_poly
    import soundfile as sf
    from stemwise_engine import vocal_release_transition as spectrum
    for role in ('lead','choir','vocal','backing'):
        if sha256_file(Path(context[role]))!=context[role+'Sha256']:
            raise ValueError('Arabic coda acoustic bytes changed')
    letters=ArabicLetters(context['project'])
    sounds=None
    pending=[]
    results={}
    duration=context['durationMs']
    for t in targets:
        if t.get('crossLane'):
            from stemwise_engine import role_phrase_overlap
            overlap=role_phrase_overlap.analyze(t,context,letters)
            if overlap:
                results[t['wordId']]={'crossLane':overlap}
        if not t.get('roleCoda'):
            continue
        reading=_pronunciation(t['spoken'],'ar')
        nxt=_pronunciation(t['following']['spoken'],t['following']['language'])
        breath=reading[-2:]==['aː','h'] and nxt[:1]==['j']
        if not breath and not _stop_limit(t['spoken'],reading):
            continue
        for role in t['roleCoda']['roles']:
            tr={'wordId':t['wordId'],'spoken':t['spoken'],'role':role,'reading':reading,'nextReading':nxt,'letters':[]}
            if breath:
                tr['phones']=[]
                if sounds is None:
                    sounds=_classifier(context['project'])
            for pre,post in [(1500,600),(3500,1600)]:
                a=max(0,(t['startMs']-pre)//10*10)
                b=min(duration//10*10,(t['following']['startMs']+post)//10*10)
                if not 500<b-a<=30000:
                    break
                pcm=_read_role(context[role],a,b,duration)
                tr['letters'].append(letters(pcm,44100,a))
                if breath:
                    tr['phones'].append(sounds(pcm,a))
            if len(tr['letters'])!=2:
                continue
            if breath:
                windows=[_breath_clock(t,runs) for runs in tr['phones']]
                if None in windows or min(_root_level(t,e) for e in tr['letters'])<1:
                    continue
                a=max(0,min(x for x,y in windows)-400)
                b=min(duration,max(y for x,y in windows)+240)
                other='choir' if role=='lead' else 'lead'
                waves={k:resample_poly(_read_role(context[k],a,b,duration),160,441) for k in (role,'backing',other)}
                tr['overBacking']=spectrum.extract(waves[role],waves['backing'],16000,a)
                tr['overOther']=spectrum.extract(waves[role],waves[other],16000,a)
            pending.append((t,tr))
    with ExitStack() as opened:
        streams={}
        for t,tr in pending:
            if 'phones' in tr and max(_root_level(t,e) for e in tr['letters'])==1:
                candidates=[]
                for peer,other in pending:
                    if (peer['line']==t['line'] or peer['spoken']!=t['spoken'] or other['role']!=tr['role'] or
                            peer['wordIndex']!=t['wordIndex'] or peer['roleCoda']['lineText']!=t['roleCoda']['lineText'] or
                            max(_root_level(peer,e) for e in other['letters'])<2):
                        continue
                    for key in (tr['role'],'backing'):
                        if key not in streams:
                            streams[key]=opened.enter_context(sf.SoundFile(context[key]))
                    matches=_correspondence(t,peer,[streams[tr['role']],streams['backing']])
                    evidence={'target':peer,'letters':other['letters'],'matches':matches}
                    if _matches_peer(t,evidence):
                        candidates.append(evidence)
                if candidates:
                    tr['peer']=max(candidates,key=lambda x:min(m[0] for m in x['matches']))
            if _coda(t,tr) is not None:
                results.setdefault(t['wordId'],{}).setdefault('coda',[]).append(tr)
    return results


def observe(context,targets):
    wanted=[t for t in targets if t.get('roleCoda') or t.get('crossLane')]
    if not wanted:
        return {}
    project=Path(context['project'])
    with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
        proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
             input=json.dumps([context,wanted]),capture_output=True,text=True,encoding='utf-8',
             timeout=context['timeoutSeconds'])
    require_worker_success(proc,code='arabic-coda-observer-failed',
                           diagnostic_path=Path(context['cacheDir'])/'arabic-coda.failure.private.txt')
    return parse_worker_json(proc.stdout)


if __name__=='__main__':
    print(json.dumps(_observe(*json.load(sys.stdin))))
