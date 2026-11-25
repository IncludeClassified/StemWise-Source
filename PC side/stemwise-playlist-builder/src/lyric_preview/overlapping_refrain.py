"""Private two-part decoding of a recurring solo refrain and a concurrent repeated stanza."""
import copy
import hashlib
import importlib.util
import math
from pathlib import Path

import numpy as np
from scipy import ndimage, signal
import soundfile as sf

from .search import CONTEXT_MARGIN_MS, PROJECT, read
from .candidates import BAR
from stemwise_engine.common import atomic_write_json, heavy_worker_stage, load_config, sha256_file
from stemwise_engine.lyric_comparison import units
from stemwise_engine.provenance import semantic_source_identity
from .layout import find_passages
from .refrain_acoustics import articulation, corresponding_windows, recurrence, concurrent_regions


def spatial_views(vocal):
    stereo,rate = sf.read(vocal,always_2d=True,dtype='float32')
    if stereo.shape[1]!=2:
        return None
    divisor = math.gcd(rate,16000)
    stereo = signal.resample_poly(stereo,16000//divisor,rate//divisor,axis=0)
    mid = stereo.mean(axis=1)
    side = (stereo[:,0]-stereo[:,1])/2
    if not np.any(side):
        return None
    def spectrum(x):
        return signal.stft(x,16000,nperseg=1024,noverlap=768)[2]
    m,s = spectrum(mid),spectrum(side)
    pm = ndimage.uniform_filter(abs(m)**2,size=(3,5))
    ps = ndimage.uniform_filter(abs(s)**2,size=(3,5))
    center = np.maximum(pm-ps,0)/(pm+ps+1e-12)
    def waveform(x):
        return signal.istft(x,16000,nperseg=1024,noverlap=768)[1][:len(mid)]
    return {'center':waveform(center*m),'wide':waveform((1-center)*(m+s))}


def harmonic_features(samples):
    frequencies,_,spectrum = signal.stft(samples,16000,nperseg=2048,noverlap=1728)
    magnitude = abs(spectrum)
    notes = 55*2**(np.arange(165)/24)
    harmonics = sum(np.log1p(2500*magnitude[np.minimum(np.rint(notes*k*2048/16000).astype(int),
        len(frequencies)-1)])/np.sqrt(k) for k in range(1,7))
    peaks = np.maximum(0,harmonics-ndimage.gaussian_filter1d(harmonics,4,axis=0))
    return (peaks/(1e-4+np.linalg.norm(peaks,axis=0))).astype('float32')


def recognized_count(transcription,song,title,choir,token):
    language = song['language']
    before = list(units(song['lines'][title[0]-1]['text'],language))
    after = units(song['lines'][choir[-1]+1]['text'],language) if choir[-1]+1<len(song['lines']) else ()
    prefix_count = 0
    while before and before[-1]==token:
        before.pop()
        prefix_count+=1
    if not before:
        return None
    heard = units(transcription,language)
    counts=[]
    for i,w in enumerate(heard[:-1]):
        if w!=before[-1] or heard[i+1]!=token:
            continue
        j=i+1
        while j<len(heard) and heard[j]==token:
            j+=1
        if (after and j<len(heard) and heard[j]==after[0]) or (not after and j==len(heard)):
            counts.append(j-i-1-prefix_count)
    return counts[0] if len(counts)==1 else None


def locate(features, reference, search, expected):
    # Each source performance searches the whole song independently. Test against
    # every disjoint non-target region, including instrumentals on this clock.
    traces=[]
    for start,end in reference:
        template=features[:,round(start/20):round(end/20)]
        if template.shape[1]<2:
            return []
        length=template.shape[1]
        def match(query):
            return sum(signal.correlate(f,g,'valid',method='fft') for f,g in zip(features,query))/length
        forward=np.maximum.reduce([match(np.roll(template,shift,axis=0)) for shift in range(-6,7,2)])
        reverse=match(template[:,::-1])
        frames=np.arange(len(forward))*20
        inside=(frames>=search[0]) & (frames+length*20<=search[1])
        outside=(frames+length*20<search[0]) | (frames>search[1])
        for a,b in reference:
            outside &= (frames+length*20<a) | (frames>b)
        if not outside.any():
            return []
        threshold=float(forward[outside].max())
        peaks=signal.find_peaks(forward,distance=length)[0]
        peaks=[int(k) for k in peaks if inside[k] and forward[k]>max(threshold,reverse[k])]
        if not peaks or (expected is not None and len(peaks)!=expected):
            return []
        traces.append([(k*20,float(forward[k]),threshold) for k in peaks])
    if len({len(trace) for trace in traces})!=1:
        return []
    result=[]
    for same in zip(*traces,strict=True):
        starts=[p[0] for p in same]
        if max(starts)-min(starts)>CONTEXT_MARGIN_MS:
            return []
        result.append({'startMs':int(np.median(starts)),'matches':same})
    return result


def role_scores(model_path, views, windows, language, hypotheses):
    """Compare identical readings on different portions of the stereo vocal stem."""
    import torch
    from transformers import AutoModelForSpeechSeq2Seq, AutoProcessor
    from stemwise_engine.ctc_worker import retire_model
    processor=AutoProcessor.from_pretrained(str(model_path),local_files_only=True)
    model=AutoModelForSpeechSeq2Seq.from_pretrained(str(model_path),local_files_only=True,
        dtype=torch.bfloat16,attn_implementation='sdpa').to('cuda').eval()
    eos=model.generation_config.eos_token_id
    targets=[torch.tensor([processor.tokenizer.encode(t,add_special_tokens=False)+
        [eos if isinstance(eos,int) else eos[0]]],device='cuda') for t in hypotheses]
    scores={}
    try:
        with torch.inference_mode():
            for view in ('center','wide'):
                scores[view]=[]
                for start,end in windows:
                    buf=views[view][start*16:end*16]
                    inp=processor.apply_transcription_request(audio=buf,language=language,prompt=None,return_tensors='pt')
                    inp={k:v.to('cuda',dtype=torch.bfloat16) if v.is_floating_point() else v.to('cuda') for k,v in inp.items()}
                    row=[]
                    for target in targets:
                        data=dict(inp)
                        data['input_ids']=torch.cat([inp['input_ids'],target],dim=1)
                        data['attention_mask']=torch.ones_like(data['input_ids'])
                        logits=model(**data,use_cache=False).logits[0,inp['input_ids'].shape[1]-1:-1]
                        row.append(float(logits.float().log_softmax(-1).gather(1,target.T).mean()))
                    scores[view].append(row)
    finally:
        del model
        retire_model({'cuda_model':True})
    return scores


def decode_lane(measurements, lower, upper):
    """Ordered start/end peaks within one voice. Voices never constrain one another."""
    if not measurements:
        return []
    first=max(lower//20,min(a for a,_ in measurements))
    last=min(upper//20,max(a+len(row)-1 for a,row in measurements))
    if last<=first:
        return []
    n=last-first+1
    totals=np.zeros(n)
    history=[]
    for k,(start,prob) in enumerate(measurements):
        score=np.full(n,-np.inf)
        a=max(start,first)
        e=min(last+1,start+len(prob))
        if e<=a:
            return []
        val=prob[a-start:e-start]
        score[a-first:e-first]=np.log(np.maximum(val,1e-30))
        score[:max(0,math.ceil(lower/20)-first)]=-np.inf
        if k:
            # Equality is permissible between words, never within a word.
            best=np.maximum.accumulate(totals)
            prev=np.maximum.accumulate(np.where(totals>np.r_[-np.inf,best[:-1]],np.arange(n),0))
            if k%2:
                best=np.r_[-np.inf,best[:-1]]
                prev=np.r_[0,prev[:-1]]
            history.append(prev)
            score+=best
        totals=score
    if not np.isfinite(totals).any():
        return []
    index=int(totals.argmax())
    path=[index]
    for prev in reversed(history):
        index=int(prev[index])
        path.append(index)
    path=[(first+i)*20 for i in reversed(path)]
    return list(zip(path[::2],path[1::2]))


def local_word_candidates(timer, vocal, language, words, guides, j, responses):
    """Measure the same owner in nested crops, alone and with its actual neighbors."""
    a,b=guides[j]
    candidates=[]
    evidence=[]
    observations=[]
    left,right=max(0,j-1),min(len(words),j+2)
    for first,past in dict.fromkeys([(j,j+1),(left,right),(left,j+1),(j,right)]):
        text=words[first:past]
        windows=[]
        for padding in (CONTEXT_MARGIN_MS//3,CONTEXT_MARGIN_MS*2//3):
            start=max(0,(guides[first][0]-padding)//20)*20
            end=min(len(vocal)//320,math.ceil((guides[past-1][1]+padding)/20))*20
            if 0<end-start<=30000:
                windows.append((start,end))
        if len(set(windows))!=2:
            continue
        key=(first,past,tuple(windows))
        if key not in responses:
            responses[key]=timer.align_batch([vocal[s*16:e*16] for s,e in windows],[text]*2,[language]*2)
        runs=responses[key]
        points=[]
        for response,(s,e) in zip(runs,windows,strict=True):
            if isinstance(response,Exception):
                raise response
            if [w['word'] for w in response]!=text:
                raise ValueError('Local vocal timer changed a word owner')
            word=response[j-first]
            left=s+round(1000*word['start'])
            right=s+round(1000*word['end'])
            if s+20<left<right<e-20 and left<b and a<right:
                points.append((left,right))
                observations.append(((first,past),(s,e),(left,right)))
        evidence.append({'wordRange':[first,past],'bounds':windows,'observed':points})
        if len(points)==2 and max(abs(a-b) for a,b in zip(*points))<=BAR:
            candidates.extend(points)
    # An adjacent word can expose an edge that the other crop censors. Keep
    # actual target estimates reproduced on different crops with different text.
    for context,window,pair in observations:
        if any(context!=other and window!=crop and max(abs(a-b) for a,b in zip(pair,point))<=BAR
               for other,crop,point in observations):
            candidates.append(pair)
    return list(dict.fromkeys(candidates)),evidence


def refine_collapsed(timer,views,plans,language,clock,starts,earlier,span,lower,upper):
    repairs=[]
    for role,view in [('lead','center'),('choir','wide')]:
        owners=[p[3] for p in plans if p[0]==role]
        n=len(owners[0])
        groups=[clock[role][k:k+n] for k in range(0,len(clock[role]),n)]
        bad={i for i,g in enumerate(groups) if any(b-a<=40 for a,b in g)}
        if not bad:
            continue
        donors=list(earlier) if role=='lead' else [(starts[role][i],g) for i,g in enumerate(groups) if i not in bad]
        if len(donors)<2:
            continue
        features=harmonic_features(views[view]) if role=='lead' else articulation(views[view])
        for i in sorted(bad):
            projected=[corresponding_windows(features,origin,starts[role][i],span,g,pitched=role=='lead')
                       for origin,g in donors]
            guides=np.median(projected,axis=0).astype(int).tolist()
            if any(b<=a for a,b in guides):
                continue
            # Choose actual observations from the target. Respect ordering within
            # this voice while the other voice remains free to overlap.
            candidates=[]
            crops=[]
            responses={}
            for j,original in enumerate(groups[i]):
                points,reads=local_word_candidates(timer,views[view],language,owners[i],guides,j,responses)
                candidates.append([original,*points])
                crops.append(reads)
            left=groups[i-1][-1][1] if i else lower
            right=groups[i+1][0][0] if i+1<len(groups) else upper
            paths=[(0.,left,[])]
            for j,items in enumerate(candidates):
                next_paths=[]
                for pair in items:
                    if pair[1]>right:
                        continue
                    prior=[p for p in paths if p[1]<=pair[0]]
                    if prior:
                        cost,_,path=min(prior,key=lambda p:p[0])
                        # Deviation ranks estimates; the mapped guide is never emitted.
                        loss=sum(abs(v-g) for v,g in zip(pair,guides[j]))
                        next_paths.append((cost+loss,pair[1],path+[pair]))
                paths=next_paths
            if paths:
                before=list(groups[i])
                groups[i]=min(paths,key=lambda p:p[0])[2]
                repairs.append({'voice':role,'group':i,'searchGuide':guides,'before':before,
                                'after':groups[i],'localCrops':crops})
        clock[role]=[pair for group in groups for pair in group]
    return repairs


def load_vocal_part_timer():
    from stemwise_engine.local_recovery_models import _MODELS
    directory,revision,hashes=_MODELS['time']
    model=PROJECT.parent/'model-cache'/directory/revision
    if any(sha256_file(model/name)!=digest for name,digest in hashes.items()):
        raise ValueError('Layer timer files differ from installed pins')
    spec=importlib.util.spec_from_file_location('stemwise_layer_timer',model/'align.py')
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.ForcedAligner(str(model),device='cuda'),revision


def measure_timer(views, plans, language, starts, earlier, span, lower, upper):
    from stemwise_engine.ctc_worker import retire_model
    timer,revision=load_vocal_part_timer()
    original=timer._times
    raw=[]
    def capture(logits):
        raw.append(logits.float().softmax(-1).cpu().numpy())
        return original(logits)
    timer._times=capture
    try:
        measurements={'lead':[],'choir':[]}
        concurrent={(s,e) for role,s,e,_ in plans if role=='choir'}
        for role,start,end,words in plans:
            view='center' if role=='lead' else 'wide'
            if role=='lead' and (start,end) not in concurrent and 'solo' in views:
                view='solo'
            a,b=start//20,end//20
            if not 0 <= a < b or b-a>1500:
                raise ValueError('Vocal passage exceeds the timer window')
            raw.clear()
            result=timer.align(views[view][a*320:b*320],words,language)
            if isinstance(result,Exception):
                raise result
            if [w['word'] for w in result]!=words or len(raw)!=1 or len(raw[0])!=2*len(words):
                raise ValueError('Unexpected vocal timing owners')
            for prob in raw[0]:
                measurements[role].append((a+1,prob[1:b-a].copy()))
        clock={role:decode_lane(data,lower,upper) for role,data in measurements.items()}
        timer._times=original
        repairs=refine_collapsed(timer,views,plans,language,clock,starts,earlier,span,lower,upper) if all(clock.values()) else []
        return clock,revision,repairs
    finally:
        # The instance hook must not keep a bound reference to its own CUDA model.
        del timer._times
        original = None
        del timer
        retire_model({'cuda_model':True})


def layer_preview(song, output, audio, vocal, backing, observation):
    from stemwise_engine import lyrics_align_pc as pc, qwen3_asr_transcribe as qwen
    from stemwise_engine.runtime_environment import environment_lease
    from .song_preview_result import write_cues
    cfg=load_config(PROJECT/'config.json')
    source=read(observation)
    if sha256_file(audio)!=song['master'] or sha256_file(vocal)!=song['vocal'] or sha256_file(backing)!=song['backing']:
        raise ValueError('Vocal parts refer to another recording')
    from .supplied_song import recognition_language
    model_language=recognition_language(song)
    qwen._validated_output(source,{'audio':{'sha256':song['master'],'byteCount':audio.stat().st_size},
        'language':model_language,'model':{'revision':pc.QWEN3_ASR_REVISION,
        'snapshotSha256':pc.QWEN3_ASR_SNAPSHOT_SHA256}})
    result=read(output/'word-timing.private.json')
    result['sheetComparison']=song['sheetComparison']
    from stemwise_engine.vocal_parts import separate_vocal_parts, separator_installation
    from .separated_refrain import mono16, part_observations, supported_count
    pair=None
    layouts=list(find_passages(song))
    if len(layouts)!=1:
        return 'No unique repeated lead and choir layout'
    ref,title,choir,unit,block,token=layouts[0]
    lines=result['lines']
    blocks=[lines[k:k+unit] for k in ref[::unit]]
    reference=[(g[0]['words'][0]['startMs'],g[-1]['words'][-1]['endMs']) for g in blocks]
    if any(not all(w['vocalContrast']) for group in blocks for line in group for w in line['words']):
        return 'Earlier refrain lacks vocal timing contrast'
    lead_text=[w['text'] for line in blocks[0] for w in line['words']]
    count=recognized_count(source['result']['transcription'],song,title,choir,token)
    installation = separator_installation(PROJECT)
    if installation == 'ready':
        part_folder,pair=separate_vocal_parts(cfg,song['master'],vocal,song['vocal'],output)
    elif installation in {'partial', 'invalid'} and not count:
        return f'Optional vocal separator installation is {installation}'
    if (not pair and not count) or (count is not None and (count%len(lead_text) or count<len(title)//unit*len(lead_text))):
        return 'Whole-recording recognition does not establish a usable repeat count'
    spatial=spatial_views(vocal)
    following = lines[choir[-1]+1]['words'][0] if choir[-1]+1<len(lines) else None
    boundary = following['startMs'] if following else song['duration']
    search=(lines[title[0]-1]['words'][0]['startMs'],following['endMs'] if following else boundary)
    expected=count//len(lead_text) if count else None
    spatial_matches=locate(harmonic_features(spatial['center']),reference,search,expected) if spatial else []
    score_file=output/'vocal-role-scores.private.json'
    views=spatial
    matches=spatial_matches
    part_evidence={}
    if pair:
        tracks={role:mono16(part_folder/(role+'.wav')) for role in ('lead','choir')}
        views={'center':tracks['lead'],'wide':tracks['choir']}
        matches=locate(harmonic_features(tracks['lead']),reference,search,expected)
        if not matches:
            return 'The separated lead did not establish distinct refrain locations'
        part_evidence={'pair':f'{part_folder.name}/pair.private.json','leadSha256':pair['lead'],'choirSha256':pair['choir'],
                       'locations':matches,'recognition':f'{part_folder.name}/recognition.private.json',
                       'readings':f'{part_folder.name}/readings.private.json'}
        if spatial_matches and len(matches)==len(spatial_matches) and all(abs(m['startMs']-s['startMs'])<=CONTEXT_MARGIN_MS
                for m,s in zip(matches,spatial_matches,strict=True)):
            matches=spatial_matches  # Reuse the existing physical search windows where both signals locate them.
        score_file=part_folder/'readings.private.json'
    if not matches:
        return 'Target refrains did not beat the other locations in this recording'
    span=int(np.median([end-start for start,end in reference]))
    starts=[*[point[0] for point in reference],*[m['startMs'] for m in matches]]
    windows=[(max(0,a-500),min(song['duration'],a+span+800)) for a in starts]
    if pair:
        hearing=part_observations(part_folder,pair,tracks,windows,model_language)
        count=supported_count(count,hearing['lead'][len(reference):],token,song['language'],len(lead_text))
        if count is None or count<len(title)//unit*len(lead_text):
            return 'The performed repeat count is unresolved on the separated lead'
    backing_views=spatial_views(backing)
    backing_track=backing_views['wide'] if backing_views else mono16(backing)
    if len(backing_track)!=len(views['wide']):
        return 'No clock-matched stereo accompaniment control'
    similarity={view:recurrence(views[view],starts,span) for view in ('center','wide')}
    similarity['accompaniment']=recurrence(backing_track,starts,span)
    del backing_views,backing_track
    choir_text=[w['text'] for i in choir[:block] for w in lines[i]['words']]
    hypotheses=[' '.join(choir_text),' '.join(reversed(choir_text)),' '.join(lead_text),'']
    from stemwise_engine.ctc_worker import execution_deadline
    with (environment_lease('main',builder=PROJECT),heavy_worker_stage(cfg),
          execution_deadline(cfg['analysisTimeoutMinutes']*60)):
        binding={'model':source['model'],'language':model_language,'windows':windows,'hypotheses':hypotheses,
                 'viewHashes':{key:hashlib.sha256(views[key].tobytes()).hexdigest() for key in ('center','wide')},
                 'runtime':qwen._runtime_identity('cuda'),
                 'scorer':semantic_source_identity(Path(__file__),('role_scores',),portable=True)}
        saved=read(score_file) if score_file.exists() else None
        if saved is not None and saved['binding']!=dict(binding,windows=[list(w) for w in windows]):
            raise ValueError('Saved vocal-role scores belong to different inputs. Use another output folder.')
        if saved:
            scores=saved['scores']
        else:
            model_path=pc._qwen3_asr_snapshot(PROJECT.parent/'model-cache/lyrics-alignment')
            scores=role_scores(model_path,views,windows,model_language,hypotheses)
            atomic_write_json(score_file,{'binding':binding,'scores':scores})
        selected=concurrent_regions(scores,similarity,len(reference),len(choir)//block)
        if not selected:
            return 'Choir reading did not separate from the solo controls'
        plans=[]
        for i,match in enumerate(matches):
            start=match['startMs']
            end=matches[i+1]['startMs'] if i+1<len(matches) else boundary
            plans.append(('lead',max(0,start-CONTEXT_MARGIN_MS),min(song['duration'],end+CONTEXT_MARGIN_MS),lead_text))
            if i in selected:
                plans.append(('choir',max(0,start-CONTEXT_MARGIN_MS),min(song['duration'],end+CONTEXT_MARGIN_MS),choir_text))
        lower=lines[title[0]-1]['words'][-1]['endMs']
        upper=boundary
        group_starts={'lead':[m['startMs'] for m in matches],'choir':[matches[i]['startMs'] for i in selected]}
        earlier=[(a,[(w['startMs'],w['endMs']) for line in group for w in line['words']])
                 for (a,_),group in zip(reference,blocks,strict=True)]
        if pair and spatial:
            views['solo']=spatial['center']
        clock,timer,repairs=measure_timer(views,plans,song['language'],group_starts,earlier,span,lower,upper)
    if any(not v for v in clock.values()):
        return 'A vocal part has no complete ordered estimate'
    leads=[]
    for cycle in range(len(matches)):
        for k in range(unit):
            i=title[0]+cycle*unit+k
            original=lines[i] if i<=title[-1] else lines[title[k]]
            line=copy.deepcopy(original)
            if i>title[-1]:
                seed=f"{song['master']}/{lines[title[0]]['occurrenceId']}/{cycle}/{k}"
                line['occurrenceId']='predicted-voice-'+hashlib.sha256(seed.encode()).hexdigest()[:24]
                for j,w in enumerate(line['words']):
                    w['wordId']=f"{line['occurrenceId']}-{j}"
            leads.append(line)
    choirs=[copy.deepcopy(lines[i]) for i in choir]
    for role,part in [('lead',leads),('choir',choirs)]:
        words=[w for line in part for w in line['words']]
        for w,(a,b) in zip(words,clock[role],strict=True):
            for key in list(w):
                if key not in ('wordId','text','startUtf16','endUtf16'):
                    del w[key]
            w.update(startMs=a,endMs=b,timing='estimated',startSource='local vocal-part timer')
            if b-a<=40:
                w['boundaryReview']='unresolved at the timer resolution'
        for line in part:
            line['voice']=role
    parts=sorted([*leads,*choirs],key=lambda line:line['words'][0]['startMs'])
    result['lines']=lines[:title[0]]+parts+lines[choir[-1]+1:]
    flattened=[w for line in result['lines'] for w in line['words']]
    if len({w['wordId'] for w in flattened})!=len(flattened):
        raise ValueError('Duplicate physical word owner')
    result['summary']={'lines':len(result['lines']),'words':len(flattened),'estimated':len(flattened),
                       'leadRefrainWords':count,'choirWords':len(clock['choir']),
                       'unresolvedVocalPartIntervals':sum('boundaryReview' in w for w in flattened)}
    result['voiceEvidence']={'singleVoiceSha256':sha256_file(output/'word-timing.private.json'),
        'transcriptSha256':sha256_file(observation),'vocalSha256':song['vocal'],'model':source['model'],
        'timer':timer,'referenceIntervals':reference,'search':search,'locations':matches,
        **({'vocalParts':part_evidence} if pair else {}),
        'choirLocations':selected,'scoreWindows':windows,'hypotheses':hypotheses,'rawScores':scores,
        'recurrence':{k:v.tolist() for k,v in similarity.items()},'timingWindows':plans,'localRemeasurements':repairs}
    write_cues(output,result,layered=True)
    return None
