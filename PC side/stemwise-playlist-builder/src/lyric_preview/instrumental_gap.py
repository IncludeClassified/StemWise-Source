"""Remeasure guide-only words at a verified gap or an artificial crop edge."""
from contextlib import ExitStack
import numpy as np
import soundfile as sf

from .candidates import BAR, FRAME, _observations
from .search import CONTEXT_MARGIN_MS, read
from .song_preview_result import support, write_cues
from stemwise_engine.common import sha256_file
from stemwise_engine.lyric_comparison import units


def ending_matches(lines, transcript, language):
    """Keep a following ad-lib or a different number of closing refrains unresolved."""
    phrase=units(lines[-1]['text'],language)
    if len(set(phrase))<2:
        return False
    count=0
    for line in reversed(lines):
        if units(line['text'],language)!=phrase:
            break
        count+=1
    heard=units(transcript,language)
    suffix=phrase*count
    return heard[-len(suffix):]==suffix and heard[-len(suffix)-len(phrase):-len(suffix)]!=phrase


def recover_final(song,files,output,signature,packets,recording):
    """Reopen a censored terminal word. The recording edge is a listening limit only."""
    from .song_preview import collect_views
    if not recording:
        return
    result=read(output/'word-timing.private.json')
    words=[w for line in result['lines'] for w in line['words']]
    word=words[-1]
    if (word['timing']!='estimated' or word['startSource']!='whole-recording forced estimate'
            or not ending_matches(result['lines'],recording[1],song['language'])):
        return
    count=len(words)
    walls=[]
    with ExitStack() as stack:
        raw={v:stack.enter_context(np.load(packets[v],allow_pickle=False)) for v in ('mix','lead')}
        for slot,(_,stop,a,b) in enumerate(signature['plans']):
            if stop==count and b+BAR<song['duration'] and all(
                    abs(a+1000*data[f'{v}t{slot}'][-1,1]-b)<=FRAME for v,data in raw.items()):
                walls.append(b)
    if not walls:
        return
    plans=[]
    for size in (3,6):
        first=max(0,count-size)
        start=max(0,words[first]['startMs']-CONTEXT_MARGIN_MS)
        plan=[first,count,start,song['duration']]
        if plan not in plans and song['duration']-start<=30000:
            plans.append(plan)
    if len(plans)!=2 or plans[0][2]==plans[1][2]:
        return
    folder=output/'final-word'
    folder.mkdir(exist_ok=True)
    plan,paths=collect_views(song,files,folder,windows=plans)
    measurements=[]
    with ExitStack() as stack:
        raw={v:stack.enter_context(np.load(p,allow_pickle=False)) for v,p in paths.items()}
        for slot,bounds in enumerate(plans):
            rows,points={},{}
            for view,data in raw.items():
                packet={f'{view}{k}0':data[f'{view}{k}{slot}'] for k in ('t','p')}
                rows[view],points[view]=_observations(packet,view,dict(plan,base=song['base'],plans=[bounds]))
            measurements.append((rows,points))
    observed=[p for _,points in measurements for view in ('mix','lead') for p in points[view][-1]]
    if len(observed)!=4 or np.ptp(observed,axis=0).max()>BAR:
        return
    center=np.median(observed,axis=0)
    vocals=[p for _,points in measurements for p in points['lead'][-1]]
    pair=min(vocals,key=lambda p:np.abs(np.asarray(p)-center).sum())
    if pair[0]<words[-2]['endMs'] or pair[1]<=max(walls):
        return
    evidence=[support(pair,-1,rows,points) for rows,points in measurements]
    if not all(all(e['vocalContrast']) for e in evidence):
        return
    prior=word['vocalContrast']
    word['finalWordRecovery']={'previousMs':[word['startMs'],word['endMs']],'censoredAtMs':walls,
        'observationsSha256':sha256_file(folder/'observations.private.json'),'transcriptSha256':sha256_file(recording[0])}
    word.update(startMs=pair[0],endMs=pair[1],startSource='local full-tail vocal measurement',
        endSource='local full-tail vocal measurement',vocalContrast=[True,True],
        massWithin100ms={v:np.mean([e['massWithin100ms'][v] for e in evidence],axis=0).tolist() for v in files},
        vocalCropRangeMs=np.stack([np.min(vocals,axis=0),np.max(vocals,axis=0)],axis=1).tolist())
    summary=result['summary']
    summary['wholeRecordingGuideOnly']-=1
    summary['startDistinctFromBacking']+=int(not prior[0])
    summary['endDistinctFromBacking']+=int(not prior[1])
    summary['bothDistinctFromBacking']+=int(not all(prior))
    write_cues(output,result)


def quiet_gap(vocal_power, backing_power):
    """Locate search context. Only a sustained vocal drop with audible backing qualifies."""
    if len(vocal_power)<CONTEXT_MARGIN_MS//BAR or len(vocal_power)!=len(backing_power):
        return None
    opening = max(vocal_power[:5])
    if opening <= 0:
        return None
    quiet = (vocal_power < opening/100) & (vocal_power < backing_power/100)
    width = CONTEXT_MARGIN_MS//BAR
    runs = np.flatnonzero(np.convolve(quiet.astype(int), np.ones(width, int), 'valid') == width)
    return int(runs[0])*BAR if len(runs) else None


def power_blocks(path, start, end):
    with sf.SoundFile(path) as stream:
        block = round(stream.samplerate*BAR/1000)
        stream.seek(round(stream.samplerate*start/1000))
        data = stream.read((end-start)//BAR*block, always_2d=True, dtype='float32')
    return np.mean(data[:len(data)//block*block].reshape(-1,block,data.shape[1])**2, axis=(1,2))


def measured_release(observations, onset, quiet, previous_end):
    """Require one release cluster in both views and both contexts, clear of the backing."""
    candidates = []
    for rows, points in observations:
        locally_supported = []
        for a,b in points['lead'][-1]:
            # The held word must contain the retained onset and clear its lexical neighbor.
            # A quiet block nominates a region; its first frame is not a word boundary.
            if (previous_end<=a<=onset+BAR and onset<b and
                    quiet-CONTEXT_MARGIN_MS<=b<quiet+CONTEXT_MARGIN_MS//2):
                evidence = support((a,b),-1,rows,points)
                if all(evidence['vocalContrast']):
                    locally_supported.append((a,b))
        if not locally_supported:
            return None
        candidates.extend(locally_supported)
    if len(observations)!=2 or max(b for _,b in candidates)-min(b for _,b in candidates)>BAR:
        return None
    end=min(candidates, key=lambda pair:sum(abs(pair[1]-b) for _,b in candidates))[1]
    return end if all(support((onset,end),-1,*obs)['vocalContrast'][1] for obs in observations) else None


def recover(song, files, sheet, output, *, replay_guide=False):
    from .song_preview import collect_views
    from .supplied_song import native_song
    result = read(output/'word-timing.private.json')
    words = [w for line in result['lines'] for w in line['words']]
    targets = [i for i,w in enumerate(words) if w['startSource']=='whole-recording forced estimate'
               and 'interludeRelease' not in w and 'heldVowelRelease' not in w
               and w['endMs']-w['startMs']>=CONTEXT_MARGIN_MS and w['timing']=='estimated']
    if not targets:
        return
    vocal = None
    for i in targets:
        word=words[i]
        onset,old_end=word['startMs'],word['endMs']
        first=next(l['first'] for l in song['lines'] if l['first']<=i<l['stop'])
        if i-first<2:
            continue
        gap=quiet_gap(*(power_blocks(files[view],onset,old_end) for view in ('lead','backing')))
        if gap is None:
            continue
        if vocal is None:
            vocal = native_song(files['lead'],sheet,song['language'],output/'vocal-guide',replay_guide=replay_guide)
            if (vocal['master']!=song['vocal'] or abs(vocal['stream']['frameCount']/vocal['stream']['sampleRate']
                    - song['stream']['frameCount']/song['stream']['sampleRate'])>1/vocal['stream']['sampleRate']
                    or [(w['text'],w['startUtf16'],w['endUtf16']) for w in vocal['words']]
                    != [(w['text'],w['startUtf16'],w['endUtf16']) for w in words]):
                raise ValueError('Vocal gap search inputs changed')
        if abs(vocal['base'][i][0]-onset)>BAR:
            continue
        quiet=onset+gap
        measurements=[]
        records=[]
        for neighbors,extra in ((2,0),(4,CONTEXT_MARGIN_MS//2)):
            left=max(first,i-neighbors)
            base=[[w['startMs'],w['endMs']] for w in words[left:i]]+[[onset,quiet+extra]]
            if quiet+extra+CONTEXT_MARGIN_MS-base[0][0]+CONTEXT_MARGIN_MS>30000:
                break
            part={**song,'words':song['words'][left:i+1],'base':base}
            folder=output/'interlude'/word['wordId']/str(neighbors)
            folder.mkdir(parents=True,exist_ok=True)
            plan,paths=collect_views(part,files,folder)
            rows,points={},{}
            for view,path in paths.items():
                with np.load(path,allow_pickle=False) as packet:
                    rows[view],points[view]=_observations(packet,view,{**plan,'base':base})
            measurements.append((rows,points))
            records.append({'path':(folder/'observations.private.json').relative_to(output).as_posix(),
                            'sha256':sha256_file(folder/'observations.private.json')})
        end=measured_release(measurements,onset,quiet,words[i-1]['endMs'])
        if end is None or not onset < end < old_end:
            continue
        word['interludeRelease']={'originalEndMs':old_end,'vocalGuideSha256':vocal['guideSha256'],
            'quietSearchMs':quiet,'observations':records}
        word['endMs']=end
        word['endSource']='local vocal release before instrumental gap'
        end_support=[support((onset,end),-1,*obs) for obs in measurements]
        prior_contrast=word['vocalContrast'][1]
        word['vocalContrast'][1]=True
        for view,mass in word['massWithin100ms'].items():
            mass[1]=float(np.mean([sample['massWithin100ms'][view][1] for sample in end_support]))
        raw_ends=[pair[1] for _,points in measurements for pair in points['lead'][-1]]
        word['vocalCropRangeMs'][1]=[min(raw_ends),max(raw_ends)]
        result['summary']['endDistinctFromBacking']+=int(not prior_contrast)
        result['summary']['bothDistinctFromBacking']+=int(word['vocalContrast'][0] and not prior_contrast)
        result['summary']['interludeReleases']=result['summary'].get('interludeReleases',0)+1
        result['summary']['wholeRecordingGuideOnly']-=1
    if result['summary'].get('interludeReleases'):
        write_cues(output,result)
        print('Remeasured',result['summary']['interludeReleases'],'release(s) before an instrumental gap',flush=True)
