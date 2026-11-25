"""Compare the audible instrumental attacks with each disputed vocal boundary."""
from bisect import bisect_left, bisect_right
from contextlib import ExitStack
from math import gcd
from pathlib import Path
import re

from stemwise_engine.common import read_bounded_json, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.endpoint_vowel_release import AGREEMENT_MS


RATE = 16000
HOP = 64           # 4 ms on the shared clock.
FRAME = 512        # 32 ms short-time spectral window.


def _attacks(signal):
    import numpy as np
    from scipy.signal import find_peaks
    frames=np.lib.stride_tricks.sliding_window_view(signal,FRAME)[::HOP]
    amp=np.abs(np.fft.rfft((frames-frames.mean(axis=1,keepdims=True))*np.hanning(FRAME),axis=1))
    freq=np.fft.rfftfreq(FRAME,1/RATE)
    novelty=[]
    for low,high in ((35,220),(220,5000)):
        band=amp[:,(freq>=low)&(freq<high)]
        # Detect new spectral energy across 16 ms. Both body and attack must rise.
        flux=np.linalg.norm(np.maximum(band[4:]-band[:-4],0),axis=1)
        novelty.append(np.r_[np.zeros(4),flux])
    score=np.sqrt(novelty[0]*novelty[1])
    typical=np.median(score)
    floor=typical+3*np.median(np.abs(score-typical))
    peaks,_=find_peaks(score,distance=round(.060*RATE/HOP),height=max(floor,1.e-4),
                       prominence=max(floor-typical,1.e-4))
    return score,novelty[0],peaks


def compare(mix,backing,vocal,origin_ms,window):
    """Find transients without any lyric, word-time candidate or beat times as detector inputs."""
    import numpy as np
    from scipy.signal import correlate, correlation_lags
    if not(len(mix)==len(backing)==len(vocal)) or len(mix)<4*FRAME:
        return {'status':'different-clocks'}
    master=mix[::4]; summed=(backing+vocal)[::4]
    scale=np.linalg.norm(master)*np.linalg.norm(summed)
    if scale<1.e-8:
        return {'status':'no-measurable-signal'}
    cross=correlate(master,summed,method='fft')/scale
    lags=correlation_lags(len(master),len(summed))
    local=np.abs(lags)<=round(.1*RATE/4)
    best=np.flatnonzero(local)[np.argmax(cross[local])]
    lag_ms=lags[best]*4000/RATE
    if abs(lag_ms)>1 or cross[best]<.8:
        return {'status':'different-clocks'}
    mscore,_,ma=_attacks(mix)
    bscore,bass,ba=_attacks(backing)
    _,voice_bass,_=_attacks(vocal)
    events=[]
    for p in ba:
        nearby=ma[np.abs(ma-p)<=4]
        if not len(nearby):
            continue
        q=int(nearby[np.argmax(mscore[nearby])])
        # A coincident consonant on the vocal stem cannot establish an instrumental hit.
        if bass[p]<=np.max(voice_bass[max(0,p-4):p+5]):
            continue
        t=round(origin_ms+1000*(FRAME/2+p*HOP)/RATE)
        if window[0]<=t<window[1]:
            events.append((t,float(bscore[p]),float(mscore[q])))
    packet={'status':'no-corroborated-impact','commonClockLagMs':round(float(lag_ms),2),
            'clockCorrelation':round(float(cross[best]),3),'transients':[]}
    if not events:
        return packet
    bm=max(e[1] for e in events)
    mm=max(e[2] for e in events)
    hits=[{'peakMs':t,'relativeBackingFlux':round(b/bm,3),'relativeMixFlux':round(m/mm,3),
           'strength':min(b/bm,m/mm)} for t,b,m in events]
    hits.sort(key=lambda h:-h['strength'])
    # Keep substantial physical accents and call one distinctive only if its
    # change is at least twice the nearest competitor in this bar.
    hits=[h for h in hits if h['strength']>=hits[0]['strength']/2]
    packet.update(status='distinctive-impact' if len(hits)==1 else 'competing-impacts',
                  transients=[{k:v for k,v in h.items() if k!='strength'} for h in hits])
    return packet


def _measure(points,beat_events,workflow,job,folder,audio):
    if not points:
        return
    times=[beat['timeMs'] for beat in beat_events]
    down=[beat['timeMs'] for beat in beat_events if beat.get('isDownbeat')]
    for point in points:
        t=point['selectedMs']
        grid=None
        if times and times[0]<=t<=times[-1]:
            ix=bisect_left(times,t)
            near=min([j for j in (ix-1,ix) if 0<=j<len(times)],key=lambda j:abs(times[j]-t))
            grid={'timeMs':times[near],'beatInBar':beat_events[near].get('beatInBar'),'fromSelectedMs':times[near]-t}
        point.update(status='unavailable-sources-or-clock',nearestGridBeat=grid)
    if folder is None:
        return
    try:
        import numpy as np
        import soundfile as sf
        from scipy.signal import resample_poly
    except ImportError:
        return
    try:
        pair=read_bounded_json(folder/'stem-pair.private.json',max_bytes=MAX_JSON_BYTES,root=folder)
        if pair['recordingSha256']!=audio['sha256']:
            return
        sources={}
        for kind,prefix in (('vocal','vocals'),('backing','backing')):
            digest=pair[kind+'Sha256']
            if not re.fullmatch('[a-f0-9]{64}',digest) or pair[kind]!=f'{prefix}-{digest}.wav':
                return
            sources[kind]=(folder/pair[kind],digest)
        recording=job.get('recordingInput') or {}
        if recording:
            sources['mix']=(workflow.workspace_root/recording['file'],recording['sha256'])
        else:
            sources['mix']=(workflow.analysis_temp/job['videoId']/'analysis.wav',audio['sha256'])
        if any(sha256_file(path)!=digest for path,digest in sources.values()):
            return
        cached={}
        with ExitStack() as stack:
            streams={k:stack.enter_context(sf.SoundFile(p)) for k,(p,_) in sources.items()}
            if any(abs(len(s)*1000/s.samplerate-audio['durationMs'])>2 for s in streams.values()):
                return
            for point in points:
                selected=point['selectedMs']
                center=bisect_right(down,selected)-1
                if 0<=center<len(down)-1:
                    left,right=down[center:center+2]
                    # Keep a hit on the other side of a bar line in reach of
                    # the same vocal candidate. All points in a bar reuse it.
                    left=max(0,left-AGREEMENT_MS)
                    right=min(audio['durationMs'],right+AGREEMENT_MS)
                    point['windowKind']='local-bar'
                else:
                    left,right=max(0,selected-1500),min(audio['durationMs'],selected+1500)
                    point['windowKind']='local-audio-context'
                if (left,right) not in cached:
                    start=max(0,left-80)
                    stop=min(audio['durationMs'],right+80)
                    clips={}
                    for k,stream in streams.items():
                        rate=stream.samplerate
                        lo,hi=round(start*rate/1000),round(stop*rate/1000)
                        stream.seek(lo)
                        pcm=stream.read(hi-lo,always_2d=True,dtype='float32')
                        if len(pcm)!=hi-lo or not np.isfinite(pcm).all():
                            return
                        divisor=gcd(RATE,rate)
                        clips[k]=resample_poly(pcm.mean(1),RATE//divisor,rate//divisor)[:(stop-start)*RATE//1000]
                    cached[left,right]=compare(clips['mix'],clips['backing'],clips['vocal'],start,(left,right))
                measured=dict(cached[left,right])
                hits=measured.pop('transients',[])
                if hits:
                    def offsets(h):
                        details=dict(h,fromSelectedMs=h['peakMs']-selected)
                        if 'predictedMs' in point:
                            details['fromPredictedMs']=h['peakMs']-point['predictedMs']
                        return details
                    measured['strongest']=offsets(hits[0])
                    measured['nearestToSelected']=offsets(min(hits,key=lambda h:abs(h['peakMs']-selected)))
                    measured['nearbyPeakMs']=[h['peakMs'] for h in hits if abs(h['peakMs']-selected)<=AGREEMENT_MS]
                    if 'predictedMs' in point:
                        measured['nearestToPredicted']=offsets(min(hits,key=lambda h:abs(h['peakMs']-point['predictedMs'])))
                    measured['otherPeakMs']=[h['peakMs'] for h in hits[1:]]
                point.update(measured,windowMs=[left,right],mixSha256=sources['mix'][1],
                    backingSha256=sources['backing'][1],vocalSha256=sources['vocal'][1])
    except (KeyError,ValueError,TypeError,RuntimeError,OSError):
        return  # This optional acoustic listening aid cannot alter the word review.


def _folder(workflow,job,audio):
    root=workflow._alignment_state_path(job['videoId']).parent/'vocal-overlap'
    path=root/'review.private.json'
    try:
        if path.is_file():
            rec=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=root)
            if rec['binding']['audioSha256']==audio['sha256'] and re.fullmatch('[a-f0-9]{64}',rec['folder']):
                return root/rec['folder']
    except (KeyError,ValueError,TypeError,OSError):
        return None
    return None


def automatic(workflow,job,audio,published,summary,anchors,flags,beat_events,repeat_hints,clock_verified):
    from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS, OWSM_CTC_FAMILY
    from stemwise_engine.stanza_timing import same_rendition
    from stemwise_engine.lyrics_tokenization import display_words
    from stemwise_engine.word_timing_review import RELEASE_FLAGS
    flat=[]
    for i,line in enumerate(published['lines'],1):
        for j,((_,_,text),w) in enumerate(zip(display_words(line['text']),line['words'],strict=True),1):
            timing=w.get('timing',w)
            flat.append(dict(line=i,wordIndex=j,word=text,startMs=timing['startMs'],endMs=timing['endMs']))
    indices={(w['line'],w['wordIndex']):n for n,w in enumerate(flat)}
    reasons={}
    for f in flags:
        reasons.setdefault((f['line'],f['wordIndex']),set()).add(f['reason'])
    requested,points=[],[]
    repeats={(r['line'],r['wordIndex']):r for r in repeat_hints if 'predictedStartMs' in r}
    family_cache={}
    edge_cache={}
    def native_times(item,edge):
        pos=item['line'],item['wordIndex']
        if (pos,edge) in edge_cache:
            return edge_cache[pos,edge]
        key=edge+'Ms'
        native={item[key]:[]}
        if pos[0] not in family_cache:
            row=summary['occurrenceCandidates'][pos[0]-1]
            family_cache[pos[0]]=same_rendition(row['familyCandidates'],anchors.get(pos[0]-1),anchors.values())
        for family,obs in family_cache[pos[0]].items():
            ws=obs.get('words',[])
            if len(ws)!=len(published['lines'][pos[0]-1]['words']) or edge=='end' and family==OWSM_CTC_FAMILY:
                continue
            w=ws[pos[1]-1]
            if abs(w['startMs']-item['startMs'])<=WORD_START_AGREEMENT_MS:
                native.setdefault(w[key],[]).append(family)
        edge_cache[pos,edge]=native
        return native
    for pos,why in reasons.items():
        n=indices[pos]
        item=flat[n]
        for edge in ('start','end'):
            key=edge+'Ms'
            selected=item[key]
            context=(why <= RELEASE_FLAGS|{'differs-from-retained-word-end'} and edge=='start'
                     or why=={'differs-from-retained-word-start'} and edge=='end')
            group={'line':pos[0],'wordIndex':pos[1],'word':item['word'],'boundary':edge,'selectedMs':selected,
                   'reviewRole':'opposite-boundary-context' if context else 'flagged-boundary'}
            candidates=[]
            for t,sources in native_times(item,edge).items():
                point={'selectedMs':t,'families':sorted(sources)}
                if pos in repeats and t==selected and edge=='start':
                    point['predictedMs']=repeats[pos]['predictedStartMs']
                points.append(point)
                candidates.append(point)
            rivals=[]
            for word in flat[max(0,n-1):n+2]:
                for other in ('start','end'):
                    if word is item and edge==other:
                        continue
                    for t in native_times(word,other):
                        rivals.append({k:word[k] for k in ('line','wordIndex','word')} | {'boundary':other,'atMs':t})
            requested.append((group,candidates,rivals))
    if clock_verified:
        _measure(points,beat_events,workflow,job,_folder(workflow,job,audio),audio)
    else:
        for point in points:
            point['status']='unverified-recording-clock'
    for group,candidates,rivals in requested:
        selected=candidates[0]
        pos=group['line'],group['wordIndex']
        if group['boundary']=='start' and pos in repeats:
            repeats[pos]['instrumentalOnset']=dict(selected,boundary='start')
        group['selectedObservation']=selected
        rankings=[]
        for p in candidates:
            hit=p.get('nearestToSelected',{})
            competing=[r for r in rivals if hit and abs(r['atMs']-hit['peakMs'])<=AGREEMENT_MS]
            rankings.append({'atMs':p['selectedMs'],'families':p['families'],'status':p['status'],
                'gridBeatMs':(p.get('nearestGridBeat') or {}).get('timeMs'),'impactMs':hit.get('peakMs'),
                'impactMinusBoundaryMs':hit.get('fromSelectedMs'),'competingBoundaries':competing,
                'nearbyPeakMs':p.get('nearbyPeakMs',[])})
        matching=[p for p in rankings if p['families'] and p['nearbyPeakMs']]
        unique={t for p in matching for t in p['nearbyPeakMs']}
        if len(unique)==1 and not any(p['competingBoundaries'] for p in matching):
            group['association']='candidate-for-listening'
            group['listenFirstMs']=[p['atMs'] for p in sorted(matching,key=lambda p:abs(p['impactMinusBoundaryMs']))]
        else:
            group['association']='unassigned'
        group['nativeComparisons']=rankings
    return {'purpose':'listening-only','boundaries':[g for g,_,_ in requested]}


def inspect_word(workflow,video_id,line_number,word_number,boundary):
    """Inspect the explicitly selected start/end in the bound child, including words without repeats."""
    from stemwise_engine.lyrics_tokenization import display_words
    from stemwise_engine.packages import read_child
    from stemwise_engine.repeat_beat_review import checked_beats,recording_clock
    if boundary not in ('start','end') or line_number<1 or word_number<1:
        raise ValueError('Choose a one-based line, word and an explicit start or end')
    job=workflow._load_job(video_id)
    validation=job.get('validation',{})
    child=workflow.runtime/'children'/f'{video_id}.stemwise.zip'
    digest=sha256_file(child)
    if digest!=validation.get('childSha256'):
        raise ValueError('Selected validated child changed')
    doc,_=read_child(child,workflow._runtime_config())
    if (doc['source']['videoId']!=video_id or job.get('recordingInput') and
            doc['source'].get('recording')!=job['recordingInput']):
        raise ValueError('Child belongs to a different recording')
    cp=workflow.runtime/'work/checkpoints'/f'{video_id}.json'
    checkpoint=read_bounded_json(cp,max_bytes=MAX_JSON_BYTES,root=cp.parent)
    audio=checkpoint['originalAudio']
    if checkpoint['videoId']!=video_id or audio['sha256']!=validation.get('audioSha256'):
        raise ValueError('Selected validated recording changed')
    track=next((t for t in (doc.get('lyrics') or {}).get('tracks',[]) if t['kind']=='original'),{})
    lines=track.get('lines',[])
    if line_number>len(lines) or word_number>len(lines[line_number-1].get('words',[])):
        raise ValueError('Selected word is absent from the validated child')
    line=lines[line_number-1]
    word=line['words'][word_number-1]
    selected=word.get('timing',word).get(boundary+'Ms')
    if type(selected) is not int:
        raise ValueError('This word has no selected '+boundary)
    point={'line':line_number,'wordIndex':word_number,'word':display_words(line['text'])[word_number-1][2],
           'lineText':line['text'],'boundary':boundary,'selectedMs':selected,
           'childSha256':digest,'recordingSha256':audio['sha256'],'purpose':'listening-only'}
    if not recording_clock(doc,checkpoint,digest,audio['sha256'],audio['durationMs']):
        return dict(point,status='unverified-recording-clock')
    beats=checked_beats(doc,checkpoint,digest,audio['sha256'],audio['durationMs'])
    events=doc['analysis']['rhythm']['beats'] if beats else []
    _measure([point],events,workflow,job,_folder(workflow,job,audio),audio)
    return point


def _main():
    import argparse
    import json
    from stemwise_engine.future_song import FutureSongWorkflow
    from stemwise_engine.recording_workflow import RecordingWorkflow
    parser=argparse.ArgumentParser(description=inspect_word.__doc__)
    parser.add_argument('--config',type=Path,required=True)
    source=parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--recording-key')
    source.add_argument('--video-id')
    parser.add_argument('--line',type=int,required=True)
    parser.add_argument('--word',type=int,required=True)
    parser.add_argument('--boundary',choices=('start','end'),required=True)
    args=parser.parse_args()
    config=args.config.resolve()
    if args.recording_key:
        workflow=RecordingWorkflow(config,args.recording_key,verify_media=False)
        vid=workflow.video_id
    else:
        workflow=FutureSongWorkflow(config,verify_media=False)
        vid=args.video_id
    try:
        result=inspect_word(workflow,vid,args.line,args.word,args.boundary)
    except (OSError,ValueError) as error:
        parser.exit(1,str(error)+'\n')
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':
    _main()
