"""Private evidence of distinct backing words, including a collapsed line entrance."""
import numpy as np
from scipy import signal

from stemwise_engine.common import atomic_write_json, heavy_worker_stage, load_config, sha256_file
from stemwise_engine.lyric_comparison import units
from stemwise_engine.runtime_environment import environment_lease
from stemwise_engine.vocal_parts import separate_vocal_parts, separator_installation
from .backing_activity import exposed_frames, read_parts
from .candidates import BAR, FRAME
from .overlapping_refrain import load_vocal_part_timer, local_word_candidates
from .refrain_acoustics import articulation
from .search import PROJECT, read
from .separated_refrain import mono16
from .song_preview_result import write_cues


def span(w):
    return w['startMs'],w['endMs']


def common(a,b):
    return min(a[1],b[1])-max(a[0],b[0])


def heard(readings,text,window,language):
    target=units(text,language)
    return bool(target) and any(units(w['text'],language)==target and common(window,span(w))>=BAR for w in readings)


def exposed(mask,window):
    a,b=window
    frames=mask[(a+FRAME-1)//FRAME:b//FRAME]
    count=BAR//FRAME
    return len(frames)>=count and np.convolve(frames.astype(int),np.ones(count,dtype=int),'valid').max()>=count


def independent_words(lines,readings,mask,language):
    words=[w for line in lines for w in line['words']]
    pairs=[]
    for residual in readings['choir']:
        r=span(residual)
        text=residual['text']
        if (not exposed(mask,r) or not heard(readings['mix'],text,r,language)
                or any(heard(readings[role],text,r,language) for role in ('lead','backing'))):
            continue
        owners=[w for w in words if heard([w],text,r,language)]
        if len(owners)!=1:
            continue
        for lead in readings['lead']:
            overlap=(max(r[0],lead['startMs']),min(r[1],lead['endMs']))
            if (common(r,span(lead))<BAR or not exposed(mask,overlap) or units(lead['text'],language)==units(text,language)
                    or any(heard(readings[role],lead['text'],overlap,language) for role in ('backing','choir'))):
                continue
            others=[w for w in words if heard([w],lead['text'],span(lead),language)]
            if len(others)==1:
                pairs.append({'lead':others[0]['wordId'],'choir':owners[0]['wordId'],'listeningWindowMs':list(overlap)})
    return pairs


def collapsed_entrances(lines,plan,packet):
    first=0
    for i,line in enumerate(lines):
        j=first
        first+=len(line['words'])
        if not i or len(line['words'])<2:
            continue
        tail,opening=lines[i-1]['words'][-1],line['words'][0]
        if (opening['endMs']-opening['startMs']>2*FRAME or opening['timing']!='estimated'
                or tail.get('vocalContrast')!=[True,True] or tail['endMs']-tail['startMs']<=BAR):
            continue
        pairs=[]
        for slot,(lo,hi,a,b) in enumerate(plan['plans']):
            if not lo<=j-1<j<hi:
                continue
            old,new=np.rint(a+1000*packet[f'mixt{slot}'][j-1-lo:j+1-lo]).astype(int).tolist()
            if (max(abs(x-y) for x,y in zip(old,span(tail)))<=BAR and a+FRAME<min(old[0],new[0])
                    and max(old[1],new[1])<b-FRAME and common(new,span(tail))>=BAR
                    and new[1]<=line['words'][1]['startMs'] and new[0]>=lines[i-1]['words'][0]['startMs']):
                pairs.append(new)
        if pairs and np.ptp(pairs,axis=0).max()<=BAR:
            yield i,tuple(map(int,np.median(pairs,axis=0)))


def matching_role(features,templates,search,candidate):
    """Two distinct recorded renditions must find the word on the same part and in the mix."""
    a,b=search[0]//FRAME,search[1]//FRAME
    matches=[]
    for template in templates:
        left,right=span(template)
        x=features['vocal'][:,round(left/FRAME):round(right/FRAME)]
        n=x.shape[1]
        if n<2 or b-a<n or abs(n*FRAME-(candidate[1]-candidate[0]))>2*BAR:
            continue
        def trace(role,query):
            return sum(signal.correlate(y,t,'valid') for y,t in zip(features[role][:,a:b],query))/n
        traces={role:trace(role,x) for role in ('lead','choir','mix','backing')}
        role=max(('lead','choir'),key=lambda r:traces[r].max())
        other='lead' if role=='choir' else 'choir'
        k=int(traces[role].argmax())
        if (traces[role][k]<=max(0.,traces[other].max(),traces['backing'].max(),trace(role,x[:,::-1]).max())
                or abs(int(traces['mix'].argmax())-k)*FRAME>BAR
                or traces['mix'][k]<=max(0.,traces['backing'][k],trace('mix',x[:,::-1])[k])):
            return None
        matches.append({'role':role,'templateId':template['wordId'],'locatedMs':(a+k)*FRAME,
                        'mixLocatedMs':(a+int(traces['mix'].argmax()))*FRAME,
                        'similarity':float(traces[role][k]),'otherPart':float(traces[other].max()),
                        'accompaniment':float(traces['backing'].max())})
    if len({m['templateId'] for m in matches})<2 or len({m['role'] for m in matches})!=1:
        return None
    starts=[m['locatedMs'] for m in matches]
    if max(starts)-min(starts)>BAR or abs(np.median(starts)-candidate[0])>BAR:
        return None
    return matches


def inspect_backing(song,output,audio,vocal,backing,signature,packets):
    single=output/'word-timing.private.json'
    result=read(single)
    language=song['language']
    config=load_config(PROJECT/'config.json')
    installation = separator_installation(PROJECT)
    if installation != 'ready':
        if installation in {'partial', 'invalid'}:
            import sys
            print(f'Optional vocal separator installation is {installation}; check its checkpoint and config.', file=sys.stderr)
        return None,False
    if (sha256_file(audio)!=song['master'] or result['recordingSha256']!=song['master']
            or sha256_file(backing)!=song['backing']):
        raise ValueError('Backing analysis and word preview must use the same recording and stems')
    folder,pair=separate_vocal_parts(config,song['master'],vocal,song['vocal'],output)
    paths={'lead':folder/'lead.wav','choir':folder/'choir.wav','backing':backing,'mix':audio}
    tracks={r:mono16(p) for r,p in paths.items() if r!='mix'}
    mask=exposed_frames(tracks['lead'],tracks['choir'],tracks['backing'])
    profile={'recordingSha256':song['master'],'singleVoiceSha256':sha256_file(single),
             'pairDirectory':folder.name,'pairSha256':sha256_file(folder/'pair.private.json'),'backingSha256':song['backing'],
             'durationMs':result['durationMs'],'exposedFrameMs':FRAME,
             'exposedFrames':int(mask.sum()),'distinctWordPairs':[],'handoffs':[]}
    if exposed(mask,(0,result['durationMs'])):
        readings=read_parts(folder,paths,None if language=='mul' else language,config)
        profile['readings']={r:sha256_file(folder/(r+'-words.private.json')) for r in paths}
        lines=result['lines']
        profile['distinctWordPairs']=independent_words(lines,readings,mask,language)
        with np.load(packets['mix'],allow_pickle=False) as raw:
            candidates=list(collapsed_entrances(lines,signature,raw))
        features=None
        for i,proposed in candidates:
            before,line=lines[i-1],lines[i]
            tail,opening=before['words'][-1],line['words'][0]
            overlap=(max(tail['startMs'],proposed[0]),min(tail['endMs'],proposed[1]))
            if (not exposed(mask,overlap) or units(tail['text'],language)==units(opening['text'],language)
                    or heard(readings['backing'],opening['text'],proposed,language)):
                continue
            templates=[l['words'][0] for l in lines[:i-1] if l['text']==line['text']
                       and l['words'][0].get('vocalContrast')==[True,True]]
            if len(templates)<2:
                continue
            if features is None:
                features={r:articulation(wave) for r,wave in tracks.items()}
                features.update(vocal=articulation(mono16(vocal)),mix=articulation(mono16(audio)))
            search=(max(before['words'][-2]['endMs'] if len(before['words'])>1 else 0,
                        min(tail['startMs'],proposed[0])-BAR),line['words'][1]['startMs'])
            matches=matching_role(features,templates,search,proposed)
            if not matches:
                continue
            donors={m['templateId'] for m in matches}
            if not any(w['wordId'] in donors and heard(readings['mix'],w['text'],span(w),language) for w in templates):
                continue
            role=matches[0]['role']
            other='lead' if role=='choir' else 'choir'
            if (not heard(readings[other],tail['text'],span(tail),language)
                    or any(heard(readings[r],tail['text'],span(tail),language) for r in (role,'backing'))):
                continue
            row={'opening':opening['wordId'],'outgoing':tail['wordId'],'openingPart':role,
                 'mixInterval':list(proposed),'templateMatches':matches,'timed':False}
            profile['handoffs'].append(row)
            from stemwise_engine.ctc_worker import execution_deadline, retire_model
            with (environment_lease('main',builder=PROJECT),heavy_worker_stage(config),
                  execution_deadline(config['analysisTimeoutMinutes']*60)):
                timer,row['timerRevision']=load_vocal_part_timer()
                try:
                    for key,part,words,j,guide in [('opening',role,line['words'],0,proposed),
                                                  ('outgoing',other,before['words'],-1,span(tail))]:
                        bounds=[span(w) for w in words]
                        bounds[j]=guide
                        points,_=local_word_candidates(timer,tracks[part],language,[w['text'] for w in words],
                                                       bounds,j%len(words),{})
                        # The serial mix timer can delay the opening. Check its onset against
                        # the actual articulation matches on both the part and the full mix.
                        starts=([np.median([m[field] for m in matches]) for field in ('locatedMs','mixLocatedMs')]
                                if key=='opening' else [guide[0]])
                        row[key+'Measurements']=[list(p) for p in points if abs(p[1]-guide[1])<=BAR
                                                 and all(abs(p[0]-s)<=BAR for s in starts)]
                finally:
                    del timer
                    retire_model({'cuda_model':True})
            if not row['outgoingMeasurements'] or not row['openingMeasurements']:
                continue
            valid=[p for p in row['openingMeasurements'] if search[0]<=p[0]<p[1]<=search[1] and common(p,span(tail))>=BAR]
            if not valid or np.ptp(valid,axis=0).max()>BAR:
                continue
            start,end=min(valid,key=lambda p:sum(abs(x-y) for x,y in zip(p,proposed)))
            row['timed']=True
            tail['voice']=other
            for key in ('vocalContrast','massWithin100ms','vocalCropRangeMs','boundaryReview'):
                opening.pop(key,None)
            opening.update(voice=role,startMs=start,endMs=end,startSource='separate vocal part')
    positive=bool(profile['distinctWordPairs'] or profile['handoffs'])
    profile['status']=('distinct-backing-words' if positive else 'residual-words-unresolved' if
                       exposed(mask,(0,result['durationMs'])) else 'no-distinct-backing-detected')
    file=output/'backing-activity.private.json'
    atomic_write_json(file,profile)
    layered=any(row['timed'] for row in profile['handoffs'])
    if layered:
        result['voiceEvidence']={'singleVoiceSha256':profile['singleVoiceSha256'],'backingActivitySha256':sha256_file(file)}
        write_cues(output,result,layered=True)
    return file,layered
