"""Recover an estimated repeated word only after locating distinct articulations in the actual vocals."""
from itertools import groupby
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

from stemwise_engine import multilingual_release_observer as phones
from stemwise_engine.common import (atomic_write_json, heavy_worker_stage, json_bytes, read_bounded_json,
                                    run_process_tree, sha256_file)
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.provenance import semantic_source_identity


def _digest(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def _class(phone):
    p = unicodedata.normalize('NFD', phones._nucleus(phone)).replace('̪', '').replace('ʰ','')
    if p in phones.VOWELS:
        return 'V'
    for name, sounds in (('nasal','mnŋɲ'), ('oral-stop','td'), ('labial','pb'),
                         ('lateral','lɫ'), ('fricative','hħxχʃs')):
        if p in sounds and len(p) == 1:
            return name
    return p


def _articulated(expected, events, start, end):
    """An independent open-phone reading must contain an ordered, articulated lexical prefix."""
    want = [_class(p) for p in expected if p and p != 'ʔ']
    if not want or any('(' in p for p in want):
        return False
    observed = [(a,_class(p),prob) for a,p,prob in events if start-50 <= a < end and prob >= .05]
    consonants = [p for p,_ in groupby(w for w in want if w!='V')]
    if len(consonants) < 2:
        return False
    # A short word requires its two independently articulated consonants and
    # both syllable nuclei. A sustained vowel cannot fulfill this signature.
    if len(consonants) == 2:
        if want.count('V') < 2 or len(set(consonants)) != 2:
            return False
        signature = want
    else:
        # A coda can be devoiced or masked. Require four ordered consonants,
        # including three distinct articulations, and an interior vowel.
        prefix = consonants[:4]
        if len(prefix) < 4 or len(set(prefix)) < 3:
            return False
        signature = prefix
    def matches(offset):
        at = offset
        landmarks = []
        for p in signature:
            while at<len(observed) and (observed[at][1]!=p or
                  observed[at][2] < (.05 if p=='V' else .25)):
                at += 1
            if at==len(observed):
                return None
            landmarks.append(at)
            at += 1
        left,right=landmarks[0],landmarks[-1]
        if (observed[left][0]-start > min(450,(end-start)//2)
                or not any(t=='V' for _,t,_ in observed[left+1:right])):
            return None
        return right+1
    first = matches(0)
    # No interval may contain two distinct complete articulations. Test the
    # remaining prefix on its own local entrance, independent of its clock.
    if first is None:
        return False
    left = first
    for ph in signature:
        while left<len(observed) and (observed[left][1]!=ph or observed[left][2] < (.05 if ph=='V' else .25)):
            left += 1
        if left==len(observed):
            return True
        left += 1
    return False


def _tight(word,edge):
    value=word['startMs' if edge==0 else 'endMs']
    mass=word.get('massWithin100ms') or {}
    crop=word.get('vocalCropRangeMs') or []
    contrast=word.get('vocalContrast') or []
    if len(crop)!=2 or not crop[edge] or len(contrast)!=2 or contrast[edge] is not True:
        return False
    a,b=crop[edge]
    if not all(isinstance(mass.get(k),list) and len(mass[k])==2 for k in ('mix','lead','backing')):
        return False
    mix,voice,back=(mass[k][edge] for k in ('mix','lead','backing'))
    return a<=b and max(abs(a-value),abs(b-value))<=100 and 0<=back<min(voice,mix)<=max(voice,mix)<=1


def _inputs(folder, audio, original, indices):
    from stemwise_engine.lyric_preview import job as preview
    root=folder/'vocal-overlap'
    if not (root/'review.private.json').is_file():
        return None
    read=lambda f: read_bounded_json(f,max_bytes=MAX_JSON_BYTES,root=f.parent)
    rec=read(root/'review.private.json')
    current=preview._inputs(folder.parents[2]/'jobs'/folder.name,audio['sha256'],placement_only=True)
    if not current or rec.get('binding')!=current[1] or rec.get('status') not in {'single','layered'}:
        return None
    if not re.fullmatch('[a-f0-9]{64}',rec['folder']):
        raise ValueError('Invalid repeated-word vocal folder')
    view=root/rec['folder']
    digest=rec['timingSha256']
    if rec['status']=='layered':
        voices=preview._checked_timing(view,current[1],'voice-timing.private.json',digest)
        digest=voices['voiceEvidence']['singleVoiceSha256']
    preview._single_timing(view,current[1],digest)
    packet=preview._checked_timing(view,current[1],'word-timing.private.json',digest)
    sheet=preview._sheet_lines(original['text'])
    if (packet['durationMs']!=audio['durationMs'] or [l['sourceLine'] for l in sheet]!=list(indices)
            or [l['text'] for l in sheet]!=[l['text'] for l in packet['lines']]):
        raise ValueError('Repeated-word guidance does not belong to the performed sequence')
    pair=read(view/'stem-pair.private.json')
    if pair['recordingSha256']!=audio['sha256'] or pair['vocal']!='vocals-'+pair['vocalSha256']+'.wav':
        raise ValueError('Repeated-word recording changed')
    files={'vocal':[str(view/pair['vocal']),pair['vocalSha256']],
           'backing':[str(view/pair['backing']),pair['backingSha256']]}
    if rec.get('backingActivitySha256'):
        from stemwise_engine.lyric_preview.reading_review import _bound_json
        activity=_bound_json(view/'backing-activity.private.json',rec['backingActivitySha256'],view)
        parts=view/activity.get('pairDirectory','vocal-parts')
        if parts.resolve().parent!=view.resolve():
            raise ValueError('Invalid repeated-word vocal part directory')
        roles=_bound_json(parts/'pair.private.json',activity['pairSha256'],parts)
        if (activity['recordingSha256']!=audio['sha256'] or activity['durationMs']!=audio['durationMs']
                or roles['binding']['originalSha256']!=audio['sha256'] or roles['binding']['vocalsSha256']!=pair['vocalSha256']):
            raise ValueError('Repeated-word vocal parts belong to a different source')
        for role in ('lead','choir'):
            if (parts/(role+'.wav')).is_file():
                files[role]=[str(parts/(role+'.wav')),roles[role]]
    if not all(re.fullmatch('[a-f0-9]{64}',sha) for _,sha in files.values()) or pair['backing']!='backing-'+pair['backingSha256']+'.wav':
        raise ValueError('Invalid repeated-word acoustic inputs')
    return digest,packet['lines'],files


def _groups(track, single, state, original, admission, folder, audio, confirmed, native):
    from stemwise_engine import spoken_targets
    from stemwise_engine.alignment_endpoints import _component_phonemizer_languages
    from stemwise_engine.lyrics_tokenization import display_words
    from stemwise_engine.word_timing_review import RELEASE_FLAGS, inspect
    indices=admission.source_physical_indices
    plan=spoken_targets.load(folder.parents[2]/'jobs'/folder.name,folder.name,original,indices,audio['sha256']) if admission.spoken_targets else None
    if (plan.reference if plan else None)!=admission.spoken_targets:
        raise ValueError('Repeated word pronunciation changed')
    displays=[display_words(line['text']) for line in single]
    languages=_component_phonemizer_languages({**original,'_spokenTargets':plan},displays)
    if not languages:
        return []
    spoken=plan.word_lines(indices) if plan else [[w[2] for w in line] for line in displays]
    review=inspect(original,track,state['endpointEvidence'],{},audio['durationMs'],None,single=single,
                   spoken_owners=spoken,resolved_releases=confirmed)
    disputed={(f['line']-1,f['wordIndex']-1) for f in review['publicationFlags']
              if f['reason'] in RELEASE_FLAGS or f['reason']=='suspected-shared-repeat-occurrence'}
    disputed_releases={(f['line']-1,f['wordIndex']-1) for f in review['publicationFlags'] if f['reason'] in RELEASE_FLAGS}
    words=[]
    for i,(line,selected,row) in enumerate(zip(single,track['lines'],state['endpointEvidence']['occurrenceCandidates'],strict=True)):
        for j,(guide,word) in enumerate(zip(line['words'],selected['words'],strict=True)):
            t=word['timing']
            candidates=[[source['word']['startMs'],source['word']['endMs']]
                        for source in native.get((i+1,j+1),{}).values()]
            words.append(dict(line=i,index=j,language=languages[i][j].split('-')[0],spoken=spoken[i][j],text=guide['text'],
                         a=t['startMs'],b=t['endMs'],guide=guide,candidates=sorted(candidates),
                         disputedRelease=(i,j) in disputed_releases,
                         locked=(i,j) in confirmed or t['status']=='supported' or row['publishDecision']['result']=='words'))
    groups=[]
    for (text,spoken_word,language),items in groupby(words,key=lambda w:(w['text'],w['spoken'],w['language'])):
        group=list(items)
        if len(group)<2 or language not in phones.LANGUAGES:
            continue
        if not disputed.intersection((w['line'],w['index']) for w in group):
            continue
        for w in group:
            g=w['guide']
            w['misplaced']=not(g['startMs']-100<=w['a']<g['endMs']) or abs(g['startMs']-w['a'])>=1000
        # Restrict work to a wrong rendition or a materially disputed boundary.
        if not any(not w['locked'] and (w['misplaced'] or abs(w['guide']['endMs']-w['b'])>=1000) for w in group):
            continue
        if any(w['locked'] and w['misplaced'] for w in group):
            continue
        groups.append(group)
    return groups


def _observe(context, groups):
    import soundfile as sf
    import torch
    from scipy.signal import resample_poly
    from transformers import Wav2Vec2FeatureExtractor,Wav2Vec2ForCTC
    from stemwise_engine.alignment_models import require_pinned_file
    path=Path(context['project']).parent/'model-cache/lyrics-alignment'/phones.SNAPSHOT
    for name,digest in phones.ASSETS.items():
        require_pinned_file(path/name,digest,name)
    torch.set_num_threads(min(4,torch.get_num_threads()))
    net=Wav2Vec2ForCTC.from_pretrained(path,local_files_only=True).eval()
    fe=Wav2Vec2FeatureExtractor.from_pretrained(path,local_files_only=True)
    vocab={v:k for k,v in json.loads((path/'vocab.json').read_bytes()).items()}
    output=[]
    for group in groups:
        first,last=group[0]['guide'],group[-1]['guide']
        a=max(0,first['startMs']-500)
        b=min(context['durationMs'],last['endMs']+500)
        trace={'pronunciation':phones._pronunciation(group[0]['spoken'],group[0]['language']),'views':{}}
        if 400<=b-a<=30000:
            for view,(file,_) in context['files'].items():
                with sf.SoundFile(file) as f:
                    sr=f.samplerate
                    if abs(len(f)/sr*1000-context['durationMs'])>2:
                        raise ValueError('Repeated-word recording clock changed')
                    left,right=round(a*sr/1000),round(b*sr/1000)
                    f.seek(left)
                    y=f.read(right-left,dtype='float32',always_2d=True).mean(1)
                x=resample_poly(y,16000,sr)
                with torch.inference_mode():
                    post=net(fe(x,sampling_rate=16000,return_tensors='pt').input_values).logits[0].softmax(-1)
                maxima,labels=post.max(dim=-1)
                events=[]
                for token, cluster in groupby(enumerate(labels.tolist()),lambda p:p[1]):
                    indices=[i for i,_ in cluster]
                    peak=float(maxima[indices].max())
                    if token>3 and peak>=.05:
                        t=indices[int(maxima[indices].argmax())]
                        events.append([a+t*20+12,vocab[token],round(peak,5)])
                trace['views'][view]=events
            _terminal_spectra(trace,group,context['files'],a,b)
            from stemwise_engine import repeated_word_coda
            codas=repeated_word_coda.observe(trace,group,context['files'])
            if codas:
                trace['exposedCodas']=codas
        output.append(trace)
    return output


def _terminal_spectra(trace, group, files, a, b):
    """Retain only the relevant target-side spectral measurements, independent of the search timer."""
    import soundfile as sf
    from scipy.signal import resample_poly
    from stemwise_engine import vocal_release_transition as spectral
    expected=trace['pronunciation']
    if not expected or _class(expected[-1])!='V':
        return
    def mono(path):
        with sf.SoundFile(path) as f:
            sr=f.samplerate
            left,right=round(a*sr/1000),round(b*sr/1000)
            f.seek(left)
            return resample_poly(f.read(right-left,dtype='float32',always_2d=True).mean(1),16000,sr)
    backing=mono(files['backing'][0])
    trace['spectra']={}
    for view in ('vocal','lead','choir'):
        events=trace['views'].get(view,())
        if any(_articulated(expected,events,w['guide']['startMs'],w['guide']['endMs']) for w in group):
            trace['spectra'][view]=spectral.extract(mono(files[view][0]),backing,16000,a)


def _terminal_vowel(events, expected, spectrum, word):
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectral
    g=word['guide']
    a,b=g['startMs'],g['endMs']
    if not expected or _class(expected[-1])!='V' or len(expected)<2:
        return None
    sound=phones._nucleus(expected[-1])
    nuclei=[(ms,i) for i,(ms,p,prob) in enumerate(events) if a<=ms<b and i
            and phones._nucleus(p)==sound and prob>=.20
            and _class(events[i-1][1])==_class(expected[-2]) and events[i-1][0]>=a]
    if len(nuclei)!=1:
        return None
    core,i=nuclei[0]
    other=next((ms for ms,p,prob in events[i+1:] if phones._nucleus(p)!=sound and prob>=.2),b+400)
    release=spectral.decide(spectrum,core-10,core+45,b,following_phone_ms=other)
    if release is not None:
        return release
    # A soft periodic residue may remain detectable during a real stop closure.
    # Require the identified vowel to lose both actual level and dominance,
    # a decaying attack-free slope, and a sustained low interval beforehand.
    clock,level,periodic,contrast,env=spectral._read(spectrum)
    core_frames=(clock>=core-10)&(clock<=core+45)
    if core_frames.sum()<3 or np.median(periodic[core_frames])<.55 or np.median(contrast[core_frames])<.4:
        return None
    base=np.median(level[core_frames])
    shape=np.median(env[core_frames],axis=0)
    shape/=max(np.linalg.norm(shape),1.e-12)
    for k in np.flatnonzero((clock>=core+45)&(clock<=min(b+400,other))):
        guard=slice(k,k+spectral.GUARD_FRAMES)
        if k<6 or k+spectral.GUARD_FRAMES>len(clock) or clock[k+spectral.GUARD_FRAMES-1]>=other:
            break
        if np.any(level[guard]>base-18) or np.max(contrast[guard])>.45:
            continue
        slope=level[k-6:k+1]
        if (abs(clock[k]-b)>400 or slope[0]-slope[-1]<8 or np.max(np.diff(slope))>2
                or np.median(env[k-10:k-5]@shape)<.70):
            continue
        # An intervening silence/new phrase breaks the link with the nucleus.
        prior=(clock>=core+45)&(clock<clock[k-6])
        if (level[prior]<base-18).sum()>=spectral.GUARD_FRAMES:
            return None
        return int(round(clock[k]))
    return None


def _measured_interval(word, trace):
    from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
    g=word['guide']
    start,end=g['startMs'],g['endMs']
    for view in ('vocal','lead','choir'):
        if view not in trace.get('spectra',{}):
            continue
        events=trace['views'][view]
        if not _articulated(trace['pronunciation'],events,start,end):
            continue
        release=_terminal_vowel(events,trace['pronunciation'],trace['spectra'][view],word)
        if release is None:
            continue
        # Keep a complete native word. An early CTC exit can terminate the
        # lexical core; only an actual measured release may extend that end.
        eligible=[(x,y) for x,y in word['candidates'] if abs(x-start)<=WORD_START_AGREEMENT_MS and x<y<=release]
        if eligible:
            x,y=min(eligible,key=lambda xy:(abs(xy[0]-start),abs(xy[1]-release)))
            if abs(word['a']-start)<=100 and word['a']<=x:
                x=word['a']
            return x,release
    return None


def _native(word):
    from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS, WORD_END_AGREEMENT_MS
    g=word['guide']
    a,b=g['startMs'],g['endMs']
    # The private crop is a search and sanity comparison. Each replacement
    # is an actual start/end from a replayed native word on this rendition.
    candidates={(x,y) for x,y in word['candidates'] if abs(x-a)<=WORD_START_AGREEMENT_MS
                and abs(y-b)<=WORD_END_AGREEMENT_MS}
    if not candidates:
        return None
    ranked=sorted(candidates,key=lambda stamps:(abs(stamps[0]-a)+abs(stamps[1]-b),stamps))
    if len(ranked)>1 and (max(x for x,_ in ranked)-min(x for x,_ in ranked)>WORD_START_AGREEMENT_MS
                           or max(y for _,y in ranked)-min(y for _,y in ranked)>WORD_END_AGREEMENT_MS):
        return None
    x,y=ranked[0]
    # Preserve a previously plausible native edge while keeping a complete
    # observed word within any composite estimate.
    if abs(word['a']-a)<=100 and word['a']<=x:
        x=word['a']
    if abs(word['b']-b)<=100 and word['b']>=y:
        y=word['b']
    return x,y


def _apply(track, groups, observations):
    from stemwise_engine.recording_release_repair import _ordered_changes
    from stemwise_engine import repeated_word_coda
    flat=[((i,j),w['timing']) for i,l in enumerate(track['lines']) for j,w in enumerate(l['words'])]
    index={p:k for k,(p,t) in enumerate(flat)}
    base=[(t['startMs'],t['endMs']) for _,t in flat]
    confirmed={}
    def apply(k,a,b):
        (i,j),timing=flat[k]
        base[k]=a,b
        timing.update(startMs=a,endMs=b,basis='Estimated from recording-local repeated articulation and vocal boundary measurements.')
        line=track['lines'][i]
        line['timing'].update(startMs=line['words'][0]['timing']['startMs'],
                              endMs=max(w['timing']['endMs'] for w in line['words']))
    for group,trace in zip(groups,observations,strict=True):
        proposed={}
        measured_ends={}
        for word in group:
            g=word['guide']
            a,b=g['startMs'],g['endMs']
            hit=lambda events:_articulated(trace['pronunciation'],events,a,b)
            backing=trace['views'].get('backing')
            if backing is None or hit(backing) or not any(hit(events) for k,events in trace['views'].items() if k!='backing'):
                break
            if word['locked']:
                continue
            old_a,old_b=word['a'],word['b']
            if abs(a-old_a)<=100 and abs(b-old_b)<=100:
                continue
            measured=_measured_interval(word,trace)
            native=measured or _native(word)
            if not native:
                break
            x,y=native
            if (x!=old_a and not _tight(g,0)) or (y!=old_b and not _tight(g,1)):
                break
            key=word['line'],word['index']
            proposed[index[key]]=(x,y)
            if measured is not None:
                measured_ends[key]=(x,y)
        else:
            accepted=_ordered_changes(base,proposed)
            if accepted==proposed:
                for k,(a,b) in accepted.items():
                    if base[k]!=(a,b):
                        apply(k,a,b)
                confirmed.update(measured_ends)
        # A release for an already correctly located word can be recovered
        # without moving a neighbor or pretending the whole group is resolved.
        for word in group:
            key=word['line'],word['index']
            g=word['guide']
            if word['locked'] or not word.get('disputedRelease') or key in confirmed:
                continue
            k=index[key]
            a,b=base[k]
            if (abs(a-g['startMs'])>300 or any(g['startMs']<=x<g['endMs'] for m,(x,_) in enumerate(base) if m!=k)
                    or _articulated(trace['pronunciation'],trace['views'].get('backing',()),g['startMs'],g['endMs'])):
                continue
            end=repeated_word_coda.release(trace,word)
            if (end is not None and any(a<=x<y<=end for x,y in word['candidates'])
                    and _ordered_changes(base,{k:(a,end)})=={k:(a,end)}):
                if b!=end:
                    apply(k,a,end)
                confirmed[key]=(a,end)
    return confirmed


def recover(workspace,state,original,admission,audio,track,confirmed,native,*,ref=None,prepare=False,config=None,
            confirmed_measurements=None):
    folder=workspace.job_dir(state['videoId'])
    found=_inputs(folder,audio,original,admission.source_physical_indices)
    groups=_groups(track,found[1],state,original,admission,folder,audio,confirmed,native) if found else []
    if not groups:
        if ref is not None:
            raise ValueError('Repeated-word plan changed')
        return None
    guide,_,files=found
    from stemwise_engine import phrase_final_release,repeated_word_coda,vocal_release_transition
    binding={'audio':audio['sha256'],'durationMs':audio['durationMs'],'guide':guide,'scope':_digest(groups),
             'mapping':state['mappingSha256'],'content':admission.evidence_sha256,'base':_digest(track),
             'roles':{k:sha for k,(_,sha) in files.items()},'assets':phones.ASSETS,
             'code':semantic_source_identity(Path(__file__),portable=True),
             'spectrum':semantic_source_identity(Path(vocal_release_transition.__file__),portable=True),
             'exposedCoda':semantic_source_identity(Path(repeated_word_coda.__file__),portable=True),
             'codaScreen':semantic_source_identity(Path(phrase_final_release.__file__),('_rest_has_coda',),portable=True),
             'phonetics':semantic_source_identity(Path(phones.__file__),('_nucleus','_pronunciation'),portable=True)}
    path=folder/('repeated-word-vocals-'+_digest(binding)+'.private.json')
    if ref is not None and ref.get('path')!=path.name:
        raise ValueError('Repeated-word evidence belongs to a different measurement')
    if not path.is_file():
        if ref is not None:
            raise ValueError('Repeated-word evidence missing')
        if not prepare:
            return None
        from stemwise_engine.runtime_environment import environment_lease,environment_python
        from stemwise_engine.worker_protocol import parse_worker_json,require_worker_success
        project=Path(config['projectDir'])
        if not (project.parent/'model-cache/lyrics-alignment'/phones.SNAPSHOT/'pytorch_model.bin').is_file():
            return None
        if any(sha256_file(Path(file))!=sha for file,sha in files.values()):
            raise ValueError('Repeated-word source bytes changed')
        context=dict(files=files,project=str(project),durationMs=audio['durationMs'])
        with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
            proc=run_process_tree([str(environment_python('main',project)),'-B','-m','stemwise_engine.recording_repeat_words'],
                input=json.dumps([context,groups]),text=True,encoding='utf-8',capture_output=True,
                timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(proc,code='repeated-word-observation-failed',
                               diagnostic_path=folder/'repeated-word-observation.failure.private.txt')
        observations=parse_worker_json(proc.stdout)
        if any(sha256_file(Path(file))!=sha for file,sha in files.values()):
            raise ValueError('Repeated-word source bytes changed during measurement')
        atomic_write_json(path,dict(binding=binding,observations=observations),root=folder)
    packet=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder)
    current={'path':path.name,'sha256':sha256_file(path)}
    if packet['binding']!=binding or ref is not None and ref!=current:
        raise ValueError('Repeated-word evidence changed')
    measurements=_apply(track,groups,packet['observations'])
    if confirmed_measurements is not None:
        confirmed_measurements.update({key:(*bounds,current['sha256']) for key,bounds in measurements.items()})
    return current


if __name__=='__main__':
    print(json.dumps(_observe(*json.load(sys.stdin))))
