"""Locate repeated stanzas on the vocals, then measure words on each rendition."""
from contextlib import ExitStack
import hashlib
import json

import numpy as np
from scipy import signal

from .candidates import BAR, FRAME
from .layout import stanza_groups
from .overlapping_refrain import decode_lane
from .refrain_acoustics import articulation
from .search import CONTEXT_MARGIN_MS, read
from .separated_refrain import mono16
from .song_preview_result import support, write_cues
from stemwise_engine.common import sha256_file


def match_stanzas(voice, backing, anchors, owners, size):
    """Two existing physical renditions must retrieve the same ordered entrances."""
    def trace(features, template):
        return sum(signal.correlate(f,t,'valid',method='fft') for f,t in zip(features,template))/template.shape[1]

    matches=[]
    for ordinal,lo,hi in anchors:
        template=voice[:,lo:hi]
        if hi>voice.shape[1] or hi-lo<2:
            continue
        scores=trace(voice,template)
        peaks=signal.find_peaks(np.r_[-np.inf,scores,-np.inf],distance=hi-lo)[0]-1
        ranked=sorted(peaks,key=lambda k:scores[k],reverse=True)
        if len(ranked)<=len(owners):
            continue
        targets=np.sort(ranked[:len(owners)])
        if abs(targets[ordinal]-lo)*FRAME>BAR:
            continue
        other=np.delete(scores[targets],ordinal)
        outsider=scores[ranked[len(owners)]]
        if (np.isclose(other.min(),outsider) or other.min()-outsider<=np.ptp(other) or
                np.any(scores[targets]<=np.maximum(trace(backing,template)[targets],trace(voice,template[:,::-1])[targets]))):
            continue
        # Adjacent written copies give an observed vocal phrase length. This
        # nominates listening windows only; no word inherits a clock offset.
        adjacent=[b-a for i,a,b,j in zip(owners,targets,targets[1:],owners[1:]) if j-i==size]
        span=int(np.median(adjacent))
        if span<=0 or span*FRAME+2*CONTEXT_MARGIN_MS>30000 or targets[-1]+span>voice.shape[1]:
            continue
        sound=True
        edges=np.linspace(0,span,size+1,dtype=int)
        for left,right in zip(edges,edges[1:]):
            phrase=voice[:,lo+left:lo+right]
            if not phrase.size:
                sound=False
                break
            for target in targets:
                actual=voice[:,target+left:target+right]
                if actual.shape!=phrase.shape:
                    sound=False
                    break
                score=(actual*phrase).sum()/(right-left)
                control=max(outsider,(actual*phrase[:,::-1]).sum()/(right-left),
                            (backing[:,target+left:target+right]*phrase).sum()/(right-left))
                if score<=control:
                    sound=False
                    break
            if not sound:
                break
        if sound:
            matches.append((targets,span))
    if len(matches)<2 or np.max(np.ptp([row[0] for row in matches],axis=0))*FRAME>BAR:
        return None
    return (np.median([m[0] for m in matches],axis=0).astype(int)*FRAME).tolist(),int(np.median([m[1] for m in matches]))*FRAME


def target_measurements(packets, plans, slots):
    start=min(plans[s][2] for s in slots)//FRAME
    stop=max(plans[s][3] for s in slots)//FRAME
    lo,hi=plans[slots[0]][:2]
    scores=np.zeros((2*(hi-lo),stop-start+1))
    rows,points={},{}
    for view,packet in packets.items():
        rows[view]=[[] for _ in range(hi-lo)]
        points[view]=[set() for _ in range(hi-lo)]
        for slot in slots:
            _,_,a,_=plans[slot]
            p=packet[f'{view}p{slot}'].astype('float32')
            p[:,:,[0,-1]]=0
            if view!='backing':
                first=a//FRAME-start
                scores[:,first:first+p.shape[-1]]+=p.reshape(len(scores),-1)
            times=packet[f'{view}t{slot}']
            for i,(word,pair) in enumerate(zip(p,times,strict=True)):
                rows[view][i].append((a,np.pad(word.cumsum(axis=-1,dtype='float64'),((0,0),(1,0)))))
                points[view][i].add(tuple(round(a+1000*float(t)) for t in pair))
    return [(start,row) for row in scores],rows,points


def recover_stanzas(song, files, output):
    groups=list(stanza_groups(song['lines']))
    if not groups:
        return
    result=read(output/'word-timing.private.json')
    lines=result['lines']
    features=None
    sections=[]
    for starts,size in groups:
        anchors=[]
        for i,start in enumerate(starts):
            words=lines[start]['words']
            if all(all(w['vocalContrast']) for w in words):
                anchors.append((i,round(words[0]['startMs']/FRAME),round(words[-1]['endMs']/FRAME)))
        if len(anchors)<2:
            continue
        if features is None:
            features=[articulation(mono16(files[k])) for k in ('lead','backing')]
        matched=match_stanzas(*features,anchors,starts,size)
        if matched is None:
            continue
        targets,duration=matched
        sections.extend((i,i+size,t,t+duration) for i,t in zip(starts,targets,strict=True))
    changes=time_sections(song,files,output,result,sorted(sections))
    if not changes:
        return
    starts={entry['firstLine']-1 for entry in changes['placements']}
    transitions=[]
    for first,_,start,_ in sorted(sections):
        if first not in starts:
            continue
        left=first
        while left and any(w['endMs']-w['startMs']<=FRAME for w in lines[left-1]['words']):
            if start-lines[left-1]['words'][0]['startMs']+2*CONTEXT_MARGIN_MS>30000:
                break
            left-=1
        if left<first and lines[left]['words'][0]['startMs']<start:
            transitions.append((left,first,lines[left]['words'][0]['startMs'],start))
    if transitions:
        adjacent=time_sections(song,files,output,result,transitions,adjacent=True)
        if adjacent:
            changes['adjacentRepairs']=adjacent
    result['repeatedStanzas']=changes
    words=[w for line in lines for w in line['words'] if w['timing']=='estimated']
    result['summary'].update(bothDistinctFromBacking=sum(all(w['vocalContrast']) for w in words),
        startDistinctFromBacking=sum(w['vocalContrast'][0] for w in words),endDistinctFromBacking=sum(w['vocalContrast'][1] for w in words),
        wholeRecordingGuideOnly=sum(w['startSource']=='whole-recording forced estimate' for w in words),
        paddedEdgeWords=sum(w['startSource']=='padded acoustic window' for w in words))
    write_cues(output,result)


def time_sections(song,files,output,result,sections,*,adjacent=False):
    from .song_preview import collect_views
    if not sections:
        return None
    lines=result['lines']
    plans=[]
    for left,right,a,b in sections:
        for pad in (CONTEXT_MARGIN_MS//2,CONTEXT_MARGIN_MS):
            plans.append([song['lines'][left]['first'],song['lines'][right-1]['stop'],
                          max(0,(a-pad)//FRAME*FRAME),min(song['duration'],(b+pad)//FRAME*FRAME)])
    folder=output/'repeated-stanzas'/hashlib.sha256(json.dumps(plans).encode()).hexdigest()[:16]
    folder.mkdir(parents=True,exist_ok=True)
    try:
        signature,paths=collect_views(song,files,folder,windows=plans)
    except ValueError as error:
        if 'does not fit one Whisper window' in str(error):
            return None
        raise
    key=hashlib.sha256(json.dumps(signature,sort_keys=True).encode()).hexdigest()
    changes=[]
    with ExitStack() as stack:
        packets={k:stack.enter_context(np.load(p,allow_pickle=False)) for k,p in paths.items()}
        if any(str(packet['binding'])!=key for packet in packets.values()):
            raise ValueError('Refrain measurements belong to different inputs')
        blocks=[]
        for i,section in enumerate(sections):
            if not blocks or blocks[-1][-1][1][1]!=section[0]:
                blocks.append([])
            blocks[-1].append((i,section))
        for block in blocks:
            first,last=block[0][1][0],block[-1][1][1]
            lower=lines[first-1]['words'][-1]['endMs'] if first else 0
            upper=lines[last]['words'][0]['startMs'] if last<len(lines) else song['duration']
            measurements=[]
            owners=[]
            section_offsets=[]
            for i,(left,right,a,b) in block:
                section_offsets.append((len(owners),a))
                timing,rows,points=target_measurements(packets,plans,[2*i,2*i+1])
                measurements.extend(timing)
                words=[w for line in lines[left:right] for w in line['words']]
                owners.extend((w,j,rows,points) for j,w in enumerate(words))
            clock=decode_lane(measurements,lower,upper)
            if (not clock or any(w['timing']!='estimated' or w.get('heldVowelRelease') or w.get('boundaryReview')
                                 for w,_,_,_ in owners) or
                    any(abs(clock[j][0]-a)>CONTEXT_MARGIN_MS for j,a in section_offsets) or
                    any(end-start<=FRAME for start,end in clock)):
                continue
            for k,(w,j,rows,points) in enumerate(owners):
                pair=clock[k]
                old=(w['startMs'],w['endMs'])
                if (all(w['vocalContrast']) and max(abs(a-b) for a,b in zip(old,pair))<=BAR and
                        (clock[k-1][1] if k else lower)<=old[0] and old[1]<=(clock[k+1][0] if k+1<len(clock) else upper)
                        and all(support(old,j,rows,points)['vocalContrast'])):
                    pair=clock[k]=old
                w.update(startMs=pair[0],endMs=pair[1],startSource='adjacent vocal window' if adjacent else 'repeated stanza target vocals',
                         **support(pair,j,rows,points))
            for _,(left,right,a,_) in block:
                entry={'firstLine':left+1,'lastLine':right}
                if not adjacent:
                    entry['vocalMatchMs']=a
                changes.append(entry)
    if changes:
        index=folder/'observations.private.json'
        return {'placements':changes,'observations':index.relative_to(output).as_posix(),'observationsSha256':sha256_file(index)}
    return None
