"""Bound a repeat's end on its own vocals after the existing repeat recognizer has found its words."""
import json
from functools import lru_cache
from pathlib import Path
import sys

from stemwise_engine import endpoint_vowel_release as model, recording_repeat_words as repeat
from stemwise_engine.common import atomic_write_json, heavy_worker_stage, read_bounded_json, run_process_tree, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.timing_estimate_policy import LIMIT_MS
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


def _tail(events,word):
    """Recognize the medial /li/ and terminal liquid in this particular articulated word."""
    from stemwise_engine.multilingual_release_observer import _nucleus
    guide=word['guide']
    seq=[(t,_nucleus(p)) for t,p,q in events if guide['startMs']<=t<guide['endMs'] and q>=.5]
    hits=[seq[j+2][0] for j in range(len(seq)-2)
          if seq[j][1]=='l' and seq[j+1][1] in {'i','ɪ'} and seq[j+2][1] in {'l','n'}]
    return hits[0] if len(hits)==1 else None


def _witness(trace,word):
    a,b=word['guide']['startMs'],word['guide']['endMs']
    view=trace['views']; expected=trace['pronunciation']
    if expected[-2:] not in (['l','h'],['aː','h']):
        return False
    return (all(repeat._articulated(expected,view.get(k,()),a,b) and _tail(view[k],word) is not None
                for k in ('vocal','lead'))
            and not any(repeat._articulated(expected,view.get(k,()),a,b) for k in ('backing','choir')))


def _requests(groups,observations):
    requests=[]
    for group in groups:
        for i,word in enumerate(group):
            if (word['locked'] or not word['disputedRelease'] or not all(repeat._tight(word['guide'],k) for k in (0,1))
                    or abs(word['a']-word['guide']['startMs'])>300
                    or word['guide']['endMs']-word['b']<=model.AGREEMENT_MS):
                continue
            matches=[k for k,t in enumerate(observations) if _witness(t,word)]
            if len(matches)!=1:
                continue
            neighbor=group[i+1] if i+1<len(group) else None
            requests.append({'word':word,'neighbor':neighbor,'observation':matches[0]})
    return requests


def _vowel_exit(runs,terminal,hint):
    # Find a long, uninterrupted open vowel followed by a rest on this role.
    # Very short uncertain frames can separate stable acoustic tokens.
    results=[]
    for i,(a,b,p) in enumerate(runs):
        if p!='SP' or b-a<model.AGREEMENT_MS or abs(a-hint)>400:
            continue
        body=[]
        for x,y,ph in reversed(runs[:i]):
            if ph not in {'aa','ah','ae','ax','ao','uh','aw'} and y-x>=30:
                break
            body.append((x,y,ph))
        if (body and abs(body[-1][0]-terminal)<=300 and a-body[-1][0]>=300 and
                sum(y-x for x,y,ph in body if ph in model.VOWELS)>=.85*(a-body[-1][0])):
            results.append(a)
    return results[0] if len(results)==1 else None


def _physical(features,edge):
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read
    clock,level,periodic,exposed,_=_read(features)
    core=(clock>=edge-300)&(clock<=edge-120)
    fading=(clock>=edge+40)&(clock<=edge+110)
    if min(core.sum(),fading.sum())<6:
        return False
    return (np.median(periodic[core])>=.55 and np.median(exposed[core])>=.4 and
            np.median(level[core])-np.median(level[fading])>=6)


def _attack(neighbor,trace,features):
    from stemwise_engine.multilingual_release_observer import _nucleus,VOWELS
    from stemwise_engine.vocal_release_transition import decide_attack
    if not neighbor or neighbor['locked'] or not repeat._tight(neighbor['guide'],0):
        return None
    guide=neighbor['guide']; edges=[]
    for role in ('lead','vocal'):
        events=trace['views'].get(role,())
        if not repeat._articulated(trace['pronunciation'],events,guide['startMs'],guide['endMs']):
            return None
        candidates=[a for (a,p,prob),(b,q,score) in zip(events,events[1:])
                    if abs(a-guide['startMs'])<=model.AGREEMENT_MS and _nucleus(p) in VOWELS and prob>=.2
                    and q=='l' and score>=.5 and 0<b-a<=200]
        if len(candidates)!=1:
            return None
        core=candidates[0]
        measured=decide_attack(features[role],core-12,core+22,guide['startMs'])
        if measured is None:
            return None
        edges.append(measured)
    if max(edges)-min(edges)>model.AGREEMENT_MS:
        return None
    # Return the actual full-vocal attack. Its earlier lexical core only located it.
    return edges[1]


def select(request,trace,evidence):
    w=request['word'];g=w['guide']
    if not _witness(trace,w) or len(evidence['runs'])!=2:
        return None
    edges=[]
    for window in evidence['runs']:
        for role in ('vocal','lead'):
            e=_vowel_exit(window[role],_tail(trace['views'][role],w),g['endMs'])
            if e is None or not _physical(evidence[role],e):
                return None
            edges.append(e)
    if max(edges)-min(edges)>2*model.AGREEMENT_MS or w['b']>=min(edges)-model.AGREEMENT_MS:
        return None
    nxt=request['neighbor']
    attack=_attack(nxt,trace,evidence) if nxt else None
    limit=max(edges)+2*model.AGREEMENT_MS
    if attack is not None:
        limit=min(limit,attack)
    # Discard exits inside the sustained vowel; new claims still require
    # an actual retained time from the word on this recording.
    early=min(edges)-model.AGREEMENT_MS
    native=[(a,b) for a,b in w['candidates'] if abs(a-w['a'])<=300 and early<=b<=limit]
    possibilities=[b for a,b in native]+[g['endMs'],*g['vocalCropRangeMs'][1],*edges]
    if min(possibilities)<early or max(possibilities)>limit or max(possibilities)-min(possibilities)>=LIMIT_MS:
        return None
    # A still plausible later native exit outside this compact envelope
    # remains a competitor unless a separately measured new attack precedes it.
    if any(b>limit and (attack is None or b<attack) for a,b in w['candidates'] if abs(a-w['a'])<=300):
        return None
    end=min(native,key=lambda p:abs(p[1]-g['endMs']))[1] if native else g['endMs']
    if not any(w['a']<=x<y<=end for x,y in w['candidates']):
        return None
    result={'end':end}
    if nxt and nxt['a']<end:
        if attack is None or attack<=end:
            return None
        pairs=[(x,y) for x,y in nxt['candidates'] if attack<=x<=attack+model.AGREEMENT_MS and x<y]
        if not pairs:
            return None
        x,y=min(pairs,key=lambda p:p[0]-attack)
        result['next']=[attack,max(nxt['b'],y)]
    return result


@lru_cache(maxsize=1)
def classifier(project):
    """Share this pinned session only for the lifetime of one spawned numerical worker."""
    from stemwise_engine.arabic_coda_release import _classifier
    return _classifier(project)


def _observe(requests,observations,files,project,duration):
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _read_role
    from stemwise_engine.vocal_release_transition import extract
    classify=classifier(Path(project))
    output=[]
    for req in requests:
        w=req['word']; n=req['neighbor']; guide=w['guide']
        end=guide['endMs']
        near=n['guide']['startMs'] if n else end+300
        if near-end>2000:
            near=end+300
        packet={'runs':[]}
        for before,after in [(1000,500),(1500,850)]:
            a=max(0,(w['a']-before)//10*10)
            b=min(duration//10*10,(near+after)//10*10)
            if not 500<b-a<=30000:
                break
            runs={}
            for role in ('vocal','lead'):
                pcm=_read_role(files[role][0],a,b,duration)
                runs[role]=classify(pcm,a)
            packet['runs'].append(runs)
        if len(packet['runs'])==2:
            a=max(0,end//10*10-1000)
            b=min(duration,max(end+450,near+350))
            waves={role:resample_poly(_read_role(files[role][0],a,b,duration),160,441)
                   for role in ('vocal','lead','backing')}
            for role in ('vocal','lead'):
                packet[role]=extract(waves[role],waves['backing'],16000,a)
        output.append(packet)
    return output


def _apply(track,requests,observations,evidence,protected):
    from stemwise_engine.recording_release_repair import _ordered_changes
    flat=[((i,j),w['timing']) for i,l in enumerate(track['lines']) for j,w in enumerate(l['words'])]
    index={pos:k for k,(pos,_) in enumerate(flat)}
    base=[(w['startMs'],w['endMs']) for _,w in flat]
    measured={}
    for req,packet in zip(requests,evidence,strict=True):
        w=req['word'];pos=w['line'],w['index'];k=index[pos]
        if pos in protected or flat[k][1]['status']!='estimated' or base[k]!=(w['a'],w['b']):
            continue
        result=select(req,observations[req['observation']],packet)
        if not result:
            continue
        proposals={k:(base[k][0],result['end'])}
        if result.get('next'):
            n=req['neighbor'];nxt=n['line'],n['index']
            if (index[nxt]!=k+1 or nxt in protected or flat[k+1][1]['status']!='estimated' or
                    base[k+1]!=(n['a'],n['b'])):
                continue
            proposals[k+1]=tuple(result['next'])
        if _ordered_changes(base,proposals)!=proposals:
            continue
        for slot,(a,b) in proposals.items():
            (i,j),timing=flat[slot]
            base[slot]=a,b
            timing.update(startMs=a,endMs=b,basis='Estimated from the performed repeat and its measured vocal transition.')
            line=track['lines'][i]
            line['timing'].update(startMs=line['words'][0]['timing']['startMs'],
                                  endMs=max(t['timing']['endMs'] for t in line['words']))
        measured[pos]=base[k]
    return measured


def _run(request):
    files=request['files']
    if any(sha256_file(Path(path))!=sha for path,sha in files.values()):
        raise ValueError('Post-repeat vocal measurement bytes changed')
    result={}
    for name,requests in request['routes'].items():
        observer=_observe if name=='held' else _role_observer().observe
        result[name]=observer(requests,request['observations'],files,request['project'],request['duration'])
    if any(sha256_file(Path(path))!=sha for path,sha in files.values()):
        raise ValueError('Post-repeat vocal measurement changed while running')
    return result


def _role_observer():
    from stemwise_engine import role_repeat_boundaries
    return role_repeat_boundaries


def recover(workspace,state,original,admission,audio,track,choices,protected,confirmed,repeat_ref,
            *,ref=None,prepare=False,config=None):
    """Read the validated upstream observations. Recompute only independent new boundary evidence."""
    from stemwise_engine import arabic_coda_release
    folder=workspace.job_dir(state['videoId'])
    if not repeat_ref:
        if ref:
            raise ValueError('Post-repeat evidence has no upstream recording')
        return None
    source=folder/repeat_ref['path']
    if sha256_file(source)!=repeat_ref['sha256']:
        raise ValueError('Upstream repeated-word recording observation changed')
    prior=read_bounded_json(source,max_bytes=MAX_JSON_BYTES,root=folder)
    observations=prior['observations']
    available=repeat._inputs(folder,audio,original,admission.source_physical_indices)
    if not available:
        if ref:
            raise ValueError('Post-repeat input recording evidence is missing')
        return None
    guide,lines,files=available
    groups=repeat._groups(track,lines,state,original,admission,folder,audio,protected,choices)
    routes={}
    held=_requests(groups,observations)
    if held:
        routes['held']=held
    if groups:
        from stemwise_engine import spoken_targets
        indices=admission.source_physical_indices
        plan=(spoken_targets.load(workspace.root.parent/'jobs'/folder.name,folder.name,original,indices,audio['sha256'])
              if admission.spoken_targets else None)
        if (plan.reference if plan else None)!=admission.spoken_targets:
            raise ValueError('Post-repeat pronunciation changed')
        spoken=plan.word_lines(indices) if plan else [[w['text'] for w in line['words']] for line in lines]
        parts=_role_observer().requests(groups,observations,lines,spoken)
        if parts:
            routes['parts']=parts
    if not routes:
        if ref:
            raise ValueError('Post-repeat boundary owners changed')
        return None
    if not {'lead','vocal','backing'}<=files.keys():
        if ref:
            raise ValueError('Post-repeat role sources are missing')
        return None
    binding={'recording':audio['sha256'],'guide':guide,'upstream':repeat_ref['sha256'],'base':repeat._digest(track),
             'requests':repeat._digest(routes),'model':[model.MODEL,model.VOCAB],
             'source':semantic_source_identity(Path(__file__),portable=True),
             'features':semantic_source_identity(Path(arabic_coda_release.__file__),('_classifier','_read_role'),portable=True)}
    if 'parts' in routes:
        roles=_role_observer()
        binding['parts']=[roles.ASSETS,semantic_source_identity(Path(roles.__file__),portable=True),
                          *[semantic_source_identity(Path(p),portable=True) for p in roles.DEPENDENCIES]]
    path=folder/('repeat-boundaries-'+repeat._digest(binding)+'.private.json')
    if ref and ref.get('path')!=path.name:
        raise ValueError('Post-repeat boundaries belong to a different recording or plan')
    if not path.is_file():
        if ref:
            raise ValueError('Post-repeat measurements are missing')
        if not prepare:
            return None
        project=Path(config['projectDir'])
        request=dict(routes=routes,observations=observations,files=files,project=str(project),duration=audio['durationMs'])
        with environment_lease('main',builder=project),heavy_worker_stage({'projectDir':str(project)}):
            proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
                input=json.dumps(request),text=True,capture_output=True,encoding='utf-8',
                timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(proc,code='repeat-boundary-observation-failed',
                               diagnostic_path=folder/'repeat-boundary.failure.private.txt')
        atomic_write_json(path,dict(binding=binding,evidence=parse_worker_json(proc.stdout)),root=folder)
    current={'path':path.name,'sha256':sha256_file(path)}
    data=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder)
    if (ref and ref!=current) or data['binding']!=binding or data['evidence'].keys()!=routes.keys():
        raise ValueError('Post-repeat timing observations changed')
    for name,requests in routes.items():
        apply=_apply if name=='held' else _role_observer().apply
        measured=apply(track,requests,observations,data['evidence'][name],protected)
        for pos,(a,b) in measured.items():
            confirmed[pos]=(a,b,current['sha256'])
        protected.update(confirmed)
    return current


if __name__=='__main__':
    print(json.dumps(_run(json.load(sys.stdin))))
