"""Remeasure collapsed word estimates on a previously identified lead or responding choir."""
import json
from pathlib import Path
from statistics import median
import sys

from stemwise_engine import endpoint_vowel_release as phones
from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.common import atomic_write_json, heavy_worker_stage, read_bounded_json, run_process_tree, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.recording_repeat_words import _digest, _inputs
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.timing_estimate_policy import LIMIT_MS
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


VOWELS=phones.VOWELS
OPEN={'ah','aa','ao','aw','ax','ae','eh','ow'}
LIQUID={'n','m','l'}
VELAR={'g','k'}
AGREEMENT=phones.AGREEMENT_MS


def _bands(rows,labels):
    """Consecutive compatible phone frames, tolerating one unstable frame."""
    out=[]
    for a,b,ph in rows:
        if ph in labels:
            if out and a-out[-1][1]<=10:
                out[-1][1]=b
            else:
                out.append([a,b])
    return out


def _near(bands,hint,edge,minimum=30):
    near=[(abs(p[edge]-hint),p) for p in bands if p[1]-p[0]>=minimum and abs(p[edge]-hint)<=WORD_START_AGREEMENT_MS]
    return min(near)[1] if near else None


def _breath_dip(features,near):
    """Find loss and independent recovery of harmonic voice around an observed aspirate."""
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read
    ts,level,period,_,_=_read(features)
    left=(ts>=near-120)&(ts<=near-65)
    right=(ts>=near+65)&(ts<=near+120)
    center=(ts>=near-45)&(ts<=near+45)
    if min(left.sum(),right.sum(),center.sum())<4:
        return None
    low=min(np.median(level[left]),np.median(level[right]))
    if min(np.median(period[left]),np.median(period[right]))<.6:
        return None
    trough=np.where(center&(level<low-6))[0]
    if len(trough)<3 or np.any(np.diff(trough)>1):
        return None
    k=trough[np.argmin(period[trough])]
    if period[k]>.5:
        return None
    rising=np.where((ts>ts[k])&(ts<=near+120)&(period>=.6)&(level>=low-6))[0]
    return [int(ts[trough[0]]),int(ts[trough[-1]]),int(ts[rising[0]])] if len(rising) else None


def _physical(spoken,pron,runs,guide,local):
    from stemwise_engine.multilingual_release_observer import _nucleus
    p=[_nucleus(x) for x in pron if x]
    if len(p)<2:
        return None
    a,b=guide
    if p[0] in ('j','w'):
        onset=_near(_bands(runs,{'y'} if p[0]=='j' else {'w','ow'}),a,0)
    elif p[:2]==['ʔ','a'] and p[2:4]==['l','l']:
        # Connected Arabic can assimilate its initial glottal vowel into the
        # preceding word. Find a fresh nasal/lateral gesture after that vowel.
        onset=_near(_bands(runs,LIQUID),a,0,50)
    elif p[0]=='ʕ' and p[1]=='a':
        onset=_near(_bands(runs,OPEN-{'ow'}),a,0,80)
    else:
        return None
    if not onset:
        return None
    if p[-1]=='h' and p[-2]=='a':
        # The aspirate can be transcribed as a short friction or lost in a
        # direct transition. Only bound this estimate when the owned sustained
        # vowel ends close to the complete local word measurements.
        nuclei=[v for v in _bands(runs,OPEN-{'ow'}) if v[0]>=onset[0]+40 and v[1]-v[0]>=100]
        nucleus=_near(nuclei,b,1,100)
        if not nucleus or not all(abs(nucleus[1]-y)<=AGREEMENT for x,y in local):
            return None
        e=nucleus[1]
        tail=next(((x,y,ph) for x,y,ph in runs if x==e),None)
        if tail and tail[2] in {'hh','th'} and tail[1]-tail[0]<=AGREEMENT:
            e=tail[1]
    elif p[-1]=='m':
        end=_near(_bands(runs,{'m','n'}),b,1,60)
        if not end or not any(ph=='s' and y-x>=40 and onset[0]<x<end[0] for x,y,ph in runs):
            return None
        e=end[1]
    elif p[-1]=='k' or (p[-2:]==['k','a'] and not spoken.endswith('َ')):
        end=_near(_bands(runs,VELAR),b,1,30)
        if not end or not any(ph in {'iy','ih','eh'} and y-x>=30 and onset[1]-40<=x<end[0] for x,y,ph in runs):
            return None
        e=end[1]
    elif len(p)==2 and p[0]=='j' and p[1]=='a':
        end=next((v for v in _bands(runs,VOWELS) if -10<=v[0]-onset[1]<=50 and v[1]-v[0]>=100),None)
        if not end or abs(end[1]-b)>WORD_START_AGREEMENT_MS:
            return None
        e=end[1]
    else:
        return None
    return (onset[0],e) if onset[0]<e else None


def _interval(word,guide,accepted,trace,raw,*,latest=None):
    local=[r[word] for r in trace['decodes']]
    if len(local)!=2 or len(trace['runs'])!=2:
        return None
    windows=[_physical(guide['spoken'],trace['pron'][word],v,(guide['a'],guide['b']),local) for v in trace['runs']]
    dip=trace.get('dips',{}).get(str(word))
    if dip and sum(w is None for w in windows)==1 and trace['pron'][word][-1:]==['h']:
        hit=next(w for w in windows if w)
        missing=windows.index(None)
        runs=trace['runs'][missing]
        starts=_near(_bands(runs,LIQUID),guide['a'],0,50)
        merged=[(a,b) for a,b in _bands(runs,OPEN) if a<hit[1]-100 and b>hit[1]+AGREEMENT]
        if (starts and merged and any(p=='hh' and abs(y-hit[1])<=20 for x,y,p in trace['runs'][1-missing])
                and max(abs(y-hit[1]) for x,y in local)<=AGREEMENT and dip[0]<=hit[1]<=dip[1]):
            # This context merged the two vowels. The separated part's physical
            # dip, named aspirate and two uncensored local exits bound the end.
            windows[missing]=(starts[0],local[missing][1])
    if not all(windows) or any(abs(x-y)>AGREEMENT for x,y in zip(*windows)):
        return None
    onset,end=[round(median(v)) for v in zip(*windows)]
    # Guides locate an independent physical rendition; they cannot clear a
    # wrong-role decode or replace a completed vocal edge.
    for k,hint in enumerate((guide['a'],guide['b'])):
        alternatives=[p[k] for p in windows+local]+[hint]
        if max(alternatives)-min(alternatives)>=LIMIT_MS:
            return None
    if trace['pron'][word][-1:] == ['h']:
        # A sustained vowel often has a short following aspirate that the
        # broad frame classifier absorbs into its next label. Bound the coda
        # by the measured vowel/frication and the full role-local word exits.
        exits=[w[1] for w in windows+local]
        if max(exits)-min(exits)>AGREEMENT:
            return None
        if min(exits)<=guide['b']<=max(exits):
            exits.append(guide['b'])
        viable=[e for e in exits if latest is None or e<=latest]
        if not viable:
            return None
        end=min(viable,key=lambda e:abs(e-guide['b']))
    # Prefer the retained measured native exit when it lies inside the
    # observed edge resolution; this avoids changing an adjacent word for
    # a few milliseconds of acoustically indistinguishable closure.
    ends=[y for x,y in raw if abs(x-guide['a'])<=WORD_START_AGREEMENT_MS and abs(y-end)<=40 and (latest is None or y<=latest)
          and y>=onset and abs(y-accepted[1])>AGREEMENT]
    if ends:
        end=min(ends,key=lambda y:abs(y-end))
    if not onset<end or end-onset<=accepted[1]-accepted[0]:
        return None
    return onset,end


def _roles(folder,track,admission,original,protected,prior,files,audio,guide):
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
    from stemwise_engine.lyric_preview.reading_review import _bound_json
    from stemwise_engine.role_phrase_overlap import _line_hit
    mapping=build_lexical_occurrence_mapping(folder.name,original,admission.source_physical_indices)['performedOccurrences']
    ids={w['occurrenceWordId']:(i,j) for i,line in enumerate(mapping) for j,w in enumerate(line['words'])}
    view=Path(files['lead'][0]).parent.parent
    header=read_bounded_json(view.parent/'review.private.json',max_bytes=MAX_JSON_BYTES,root=view.parent)
    act=_bound_json(view/'backing-activity.private.json',header['backingActivitySha256'],view)
    if act['recordingSha256']!=audio['sha256']:
        raise ValueError('Vocal part observations belong to another recording')
    readings={}
    for role in ('lead','choir'):
        digest=act['readings'].get(role)
        if digest:
            part=Path(files[role][0]).parent
            data=_bound_json(part/(role+'-words.private.json'),digest,part)
            if data['binding']['sha256']!=files[role][1]:
                raise ValueError('Vocal part recognition source changed')
            readings[role]=data['words']
    roles={}; overlapping={}
    for owner,tr in prior.get('arabicCoda',{}).items():
        cross=tr.get('crossLane')
        if not cross or owner not in ids:
            continue
        i,j=ids[owner]
        if (i,j) not in protected or i+1>=len(track['lines']):
            continue
        target=cross['binding']['response']
        g=guide[i+1]['words']; line=mapping[i+1]['words']
        if len(g)!=len(target) or [w['wordId'] for w in target]!=[w['occurrenceWordId'] for w in line]:
            raise ValueError('Choir response changed physical word owners')
        if not all(w['text']==v['text'] and (w['startMs'],w['endMs'])==(v['startMs'],v['endMs']) for w,v in zip(g,target)):
            raise ValueError('Choir response changed on the recording clock')
        roles[i+1]='choir'
        overlapping[i+1]=(i,j)
        # The next phrase can hand back to the lead. Require an identified
        # unique full phrase on this part and its absence on the choir.
        if i+2<len(guide):
            nxt=guide[i+2]['words']; a=nxt[0]['startMs']
            if (_line_hit(nxt,readings.get('lead',[]),a,response=True)
                    and not _line_hit(nxt,readings.get('choir',[]),a,response=True)):
                roles[i+2]='lead'
    known_responses={guide[i]['text'] for i,role in roles.items() if role=='choir'}
    for i,line in enumerate(guide):
        if i in roles or line['text'] not in known_responses:
            continue
        words=line['words']; a,b=words[0]['startMs'],words[-1]['endMs']
        hit=_line_hit(words,readings.get('choir',[]),a,response=True)
        if not hit or not all(hit[0]) or _line_hit(words,readings.get('lead',[]),a,response=True):
            continue
        # A matching chorus elsewhere supplies wording only. On this physical
        # rendition the isolated choir must independently sing every word and
        # the simultaneous lead must have separately recognized other lyrics.
        expected={w['text'] for w in words}
        if any(w['text'] not in expected and w['startMs']<b and a<w['endMs'] for w in readings.get('lead',[])):
            roles[i]='choir'
    return roles,overlapping


def _requests(state,track,guide,roles,protected,original,admission,folder,audio):
    from stemwise_engine import spoken_targets
    from stemwise_engine.word_timing_review import inspect,VOCAL_SPAN_DISAGREEMENT
    from stemwise_engine.alignment_endpoints import _component_phonemizer_languages
    from stemwise_engine.lyrics_tokenization import display_words
    flags=inspect(original,track,state['endpointEvidence'],{},audio['durationMs'],None,single=guide)['publicationFlags']
    bad={(f['line']-1,f['wordIndex']-1) for f in flags if f['reason']==VOCAL_SPAN_DISAGREEMENT}
    plan=(spoken_targets.load(folder.parents[2]/'jobs'/folder.name,folder.name,original,admission.source_physical_indices,
                               audio['sha256']) if admission.spoken_targets else None)
    if (plan.reference if plan else None)!=admission.spoken_targets:
        raise ValueError('Role word pronunciation changed')
    display=[display_words(l['text']) for l in guide]
    spoken=plan.word_lines(admission.source_physical_indices) if plan else [[w[2] for w in l] for l in display]
    langs=_component_phonemizer_languages({**original,'_spokenTargets':plan},display)
    out=[]
    for i,role in sorted(roles.items()):
        ids=[j for k,j in sorted(bad) if k==i and (k,j) not in protected]
        if not ids or not langs or any(lang.split('-')[0]!='ar' for lang in langs[i]):
            continue
        line=guide[i]['words']
        words=[dict(id=w['wordId'],spoken=s,a=w['startMs'],b=w['endMs']) for w,s in zip(line,spoken[i],strict=True)]
        if words[-1]['b']-words[0]['a']<=26000:
            out.append(dict(line=i,role=role,words=words,targets=ids))
    return out


def _observe(request):
    import os
    from stemwise_engine import endpoint_mms_prediction as mms
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    from stemwise_engine.arabic_coda_release import _classifier,_read_role
    from stemwise_engine.role_repeat_boundaries import _decodes
    from stemwise_engine.multilingual_release_observer import _pronunciation
    from stemwise_engine.vocal_release_transition import extract
    from scipy.signal import resample_poly
    project=Path(request['project']); os.environ['TORCH_HOME']=str(project.parent/'model-cache/torch')
    dense=_classifier(project); local=mms.load_model(project)
    duration=request['duration'];streams={}; result=[]
    for req in request['requests']:
        path,digest=request['files'][req['role']]
        if req['role'] not in streams:
            if sha256_file(Path(path))!=digest:
                raise ValueError('Role audio changed before measurement')
            streams[req['role']]=StreamedMonoAudio(Path(path),16000)
        words=req['words']
        decodes=_decodes(local,streams[req['role']],words,duration)
        runs=[]
        for pad in (350,650):
            a=max(0,(words[0]['a']-pad)//10*10);b=min(duration//10*10,(words[-1]['b']+pad)//10*10)
            runs.append(dense(_read_role(path,a,b,duration),a))
        pron=[_pronunciation(w['spoken'],'ar') for w in words]
        dips={}
        other='choir' if req['role']=='lead' else 'lead'
        other_path,other_sha=request['files'][other]
        if sha256_file(Path(other_path))!=other_sha:
            raise ValueError('Opposing vocal part changed before measurement')
        for j in req['targets']:
            if pron[j][-1:]!=['h']:
                continue
            near=round(median([row[j][1] for row in decodes]))
            a=max(0,(near-500)//10*10);b=min(duration,(near+500)//10*10)
            pcm=[resample_poly(_read_role(view_path,a,b,duration),160,441) for view_path in (path,other_path)]
            dip=_breath_dip(extract(*pcm,16000,a),near)
            if dip:
                dips[str(j)]=dip
        result.append(dict(decodes=decodes,runs=runs,pron=pron,dips=dips))
    if any(sha256_file(Path(request['files'][r][0]))!=request['files'][r][1] for r in streams):
        raise ValueError('Role audio changed during measurement')
    return result


def _apply(track,requests,traces,overlapping,protected,state,digest):
    from stemwise_engine.recording_release_repair import _ordered_changes
    for req,tr in zip(requests,traces,strict=True):
        i=req['line'];line=track['lines'][i]; words=line['words']
        base=[(w['timing']['startMs'],w['timing']['endMs']) for w in words]
        native=state['endpointEvidence']['occurrenceCandidates'][i]['familyCandidates']
        def observations(j):
            return [(ws[j]['startMs'],ws[j]['endMs']) for name,c in native.items()
                    if name!='owsm-ctc' and len(ws:=c.get('words',[]))==len(words)]
        proposals={}; helpers={}
        for j in req['targets']:
            if (i,j) in protected or words[j]['timing']['status']!='estimated':
                continue
            dip=tr.get('dips',{}).get(str(j))
            latest=base[j+1][0] if j+1<len(words) and j+1 not in req['targets'] and not dip else None
            interval=_interval(j,req['words'][j],base[j],tr,observations(j),latest=latest)
            if not interval:
                continue
            a,b=interval
            if dip and j+1<len(words) and j+1 not in req['targets'] and b>base[j+1][0]:
                if (i,j+1) in protected or words[j+1]['timing']['status']!='estimated':
                    continue
                # The new vowel attack was measured independently on the
                # actual part. Choose its own complete word from the two
                # local contexts; never place its onset at our chosen end.
                nexts=[v[j+1] for v in tr['decodes']]
                viable=[x for x,y in nexts if b<=x<y<=base[j+1][1] and abs(x-dip[2])<=AGREEMENT]
                if (not viable or max(x for x,y in nexts)-min(x for x,y in nexts)>WORD_START_AGREEMENT_MS
                        or abs(dip[2]-base[j+1][0])>WORD_START_AGREEMENT_MS):
                    continue
                helpers[j+1]=(min(viable,key=lambda x:abs(x-dip[2])),base[j+1][1])
            # If the current preceding word crowds this onset, replace only
            # its end, using that word's OWN complete, still-contained native
            # span and the terminal vowel exit on this singer.
            if j and base[j-1][1]>a and j-1 not in req['targets']:
                prior=req['words'][j-1]; pron=tr['pron'][j-1]
                if (i,j-1) in protected or words[j-1]['timing']['status']!='estimated' or pron[-1] not in {'a','aː'}:
                    continue
                bounds=[_near(_bands(runs,OPEN),prior['b'],1,30) for runs in tr['runs']]
                variants=[end for start,end in observations(j-1) if base[j-1][0]<=start<end<=a and
                          all(v and abs(v[1]-end)<=AGREEMENT for v in bounds)]
                if not variants:
                    continue
                helpers[j-1]=(base[j-1][0],max(variants))
            proposals[j]=interval
        changes=_ordered_changes(base,{**helpers,**proposals})
        # A partial or conflicting rewrite of a phrase must leave its source
        # words untouched. The adjacent lead that previously authorized this
        # choir response keeps both edges and can coexist on a separate part.
        if not changes or changes.keys()!=(helpers.keys()|proposals.keys()):
            continue
        previous=track['lines'][i-1]['words'] if i else []
        following=track['lines'][i+1]['words'] if i+1<len(track['lines']) else []
        if any(a<prev['timing']['startMs'] or (a<prev['timing']['endMs'] and a<base[j][0] and
               (i-1,k)!=overlapping.get(i)) for j,(a,b) in changes.items() for k,prev in enumerate(previous)):
            continue
        if any(following and b>following[0]['timing']['startMs'] and b>base[j][1] for j,(a,b) in changes.items()):
            continue
        for j,(a,b) in changes.items():
            words[j]['timing'].update(startMs=a,endMs=b,basis='Estimated from the performed word on its identified vocal part.')
            if j in proposals:
                protected[i,j]=(a,b,digest)
        line['timing'].update(startMs=words[0]['timing']['startMs'],endMs=max(w['timing']['endMs'] for w in words))


def recover(workspace,state,original,admission,audio,track,choices,protected,*,ref=None,config=None,repair_ref=None):
    folder=workspace.job_dir(state['videoId'])
    if not repair_ref:
        if ref: raise ValueError('Role span lost its prior vocal ownership')
        return None
    prior=folder/repair_ref['path']
    if sha256_file(prior)!=repair_ref['sha256']:
        raise ValueError('Role span prior vocal ownership changed')
    prior=read_bounded_json(prior,max_bytes=MAX_JSON_BYTES,root=folder)['observations']
    if not any(v.get('crossLane') for v in prior.get('arabicCoda',{}).values()):
        if ref: raise ValueError('Role span no longer has identified vocal roles')
        return None
    available=_inputs(folder,audio,original,admission.source_physical_indices)
    if not available or not {'lead','choir'}<=available[2].keys():
        if ref: raise ValueError('Role span separated vocals missing')
        return None
    guide_id,guide,files=available
    roles,overlap=_roles(folder,track,admission,original,protected,prior,files,audio,guide)
    reqs=_requests(state,track,guide,roles,protected,original,admission,folder,audio)
    if not reqs:
        if ref: raise ValueError('Role span target occurrence changed')
        return None
    from stemwise_engine import (arabic_coda_release,role_repeat_boundaries,multilingual_release_observer,
                                 endpoint_mms_prediction,role_phrase_overlap,vocal_release_transition)
    from stemwise_engine.model_registry import model_assets
    bind={'recording':audio['sha256'],'guide':guide_id,'scope':_digest(reqs),'roles':{v:files[v][1] for v in roles.values()},
          'upstream':repair_ref['sha256'],'base':_digest(track),'source':semantic_source_identity(Path(__file__),portable=True),
          'observers':[semantic_source_identity(Path(m.__file__),names,portable=True) for m,names in
                       ((arabic_coda_release,('_classifier','_read_role')),(role_repeat_boundaries,('_decodes',)),
                        (multilingual_release_observer,('_pronunciation','_nucleus')),
                        (role_phrase_overlap,('_word','_spelling','_line_hit')))],
          'mms':semantic_source_identity(Path(endpoint_mms_prediction.__file__),portable=True),
          'physical':semantic_source_identity(Path(vocal_release_transition.__file__),portable=True),
          'assets':[phones.MODEL,phones.VOCAB,model_assets('lyrics_align_pc')['PINNED_MMS_SHA256']]}
    path=folder/('role-word-spans-'+_digest(bind)+'.private.json')
    if ref and (path.name!=ref['path'] or not path.is_file() or sha256_file(path)!=ref['sha256']):
        raise ValueError('Role span evidence changed')
    if not path.is_file():
        if config is None: return None
        project=Path(config['projectDir'])
        body=dict(project=str(project),requests=reqs,duration=audio['durationMs'],files=files)
        with environment_lease('main',builder=project),heavy_worker_stage({'projectDir':str(project)}):
            proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],input=json.dumps(body),
                text=True,capture_output=True,encoding='utf-8',timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(proc,code='role-span-observation-failed',diagnostic_path=folder/'role-span.failure.private.txt')
        atomic_write_json(path,dict(binding=bind,observations=parse_worker_json(proc.stdout)),root=folder)
    saved=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder)
    if saved['binding']!=bind: raise ValueError('Role span evidence belongs to another occurrence')
    current={'path':path.name,'sha256':sha256_file(path)}
    _apply(track,reqs,saved['observations'],overlap,protected,state,current['sha256'])
    return current


if __name__=='__main__':
    print(json.dumps(_observe(json.load(sys.stdin))))
