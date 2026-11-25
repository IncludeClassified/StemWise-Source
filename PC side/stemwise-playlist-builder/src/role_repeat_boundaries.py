"""Localize collapsed repetitions on the physical performing part and measure its sung transitions."""
from pathlib import Path
import unicodedata

from stemwise_engine import recording_repeat_words as repeat
from stemwise_engine.model_registry import model_assets
from stemwise_engine.timing_estimate_policy import LIMIT_MS

_pins = model_assets('lyrics_align_pc')
ASSETS = {name: _pins[name] for name in ('PINNED_WHISPER_SHA256','PINNED_WHISPER_SNAPSHOT','PINNED_MMS_SHA256')}
DEPENDENCIES = tuple(str(Path(__file__).with_name(f)) for f in (
    'endpoint_mms_prediction.py','arabic_coda_release.py','stanza_timing.py'))
OPEN = {'aa','ae','ah','ao','aw','ax','er','eh','ey','ih','iy','ay','oy','ow','uh','uw'}


def requests(groups, observations, lines, spoken):
    from stemwise_engine.word_timing_review import _shared_repeat_placements
    found=[]
    for group in groups:
        owned={(w['line'],w['index']):w for w in group}
        incumbents={p:(w['a'],w['b']) for p,w in owned.items()}
        conflicts=list(_shared_repeat_placements(lines,incumbents,spoken))
        if not conflicts:
            continue
        affected={pos[0] for positions,_,_ in conflicts for pos in positions}
        unit=[[a,j] for (a,j),w in owned.items() if a in affected and not w['locked']]
        for i in sorted(affected):
            words=[w for w in group if w['line']==i and not w['locked']]
            if not words or i+1>=len(lines):
                continue
            seq=[(i,k) for k in range(len(lines[i]['words']))]
            seq.extend((i+1,k) for k in range(min(2,len(lines[i+1]['words']))))
            context=[dict(id=lines[a]['words'][j]['wordId'],spoken=spoken[a][j],
                          a=lines[a]['words'][j]['startMs'],b=lines[a]['words'][j]['endMs']) for a,j in seq]
            if context[-1]['b']-context[0]['a']>26000:
                continue
            # Match upstream observations by their physical clock. Removing another
            # resolved group from this projection must never shift array ownership.
            lo,hi=words[0]['guide']['startMs'],words[-1]['guide']['endMs']
            candidates=[]
            for j,t in enumerate(observations):
                times=[ms for row in t['views'].values() for ms,_,prob in row]
                if times and min(times)<lo+500 and max(times)>hi-1200 and max(times)>=lo and min(times)<hi:
                    candidates.append(j)
            if len(candidates)==1:
                found.append(dict(line=i,words=words,context=context,observation=candidates[0],unit=unit,
                                  lineCount=len(lines[i]['words'])))
    return found


def _plain(text):
    return ''.join(c for c in unicodedata.normalize('NFC',text.casefold()) if c.isalpha())


def _partial(events,expected,a,b):
    """Two different target consonants flanking a vowel on the actual vocal part."""
    consonants=[repeat._class(ph) for ph in expected if ph!='ʔ' and repeat._class(ph)!='V']
    observed=[repeat._class(ph) for t,ph,p in events if a-50<=t<b and p>=.20]
    for i,left in enumerate(consonants):
        for right in consonants[i+1:]:
            if left==right or left not in observed:
                continue
            after=observed[observed.index(left)+1:]
            if 'V' in after and right in after[after.index('V')+1:]:
                return True
    return False


def _recognized(text, observed, start, end):
    """An unprompted reading must contain one exact lexical instance on this rendition."""
    expected=_plain(text)
    if not expected:
        return False
    hits=[]
    for i,(t,a,b,prob) in enumerate(observed):
        value=''
        for u,lo,hi,p in observed[i:]:
            value+=_plain(u)
            if not expected.startswith(value) or p<.7:
                break
            if value==expected:
                if start<=(a+hi)//2<end:
                    hits.append((a,hi))
                break
    return len(hits)==1


def _readings(model, pcm, lang, origin):
    from scipy.signal import resample_poly
    signal=resample_poly(pcm,160,441).astype('float32')
    segments,_=model.transcribe(signal,language=lang,beam_size=5,word_timestamps=True,
            condition_on_previous_text=False,vad_filter=False,temperature=0.)
    return [[w.word.strip(),origin+round(1000*w.start),origin+round(1000*w.end),round(w.probability,4)]
            for seg in segments for w in seg.words or [] if w.word.strip() and w.end>w.start]


def _decodes(model, stream, context, duration):
    from stemwise_engine import endpoint_mms_prediction as mms
    words=[{'wordId':w['id'],'acousticText':w['spoken']} for w in context]
    plan=mms.target_plan({'lines':[dict(lineId='context',sourcePhysicalIndex=0,words=words)]},None,model.vocab)
    decodes=[]
    for pad in (350,650):
        a=max(0,context[0]['a']-pad)
        b=min(duration,context[-1]['b']+pad)
        pcm=stream.read(a*16,min(stream.sample_count,b*16)).numpy()[0]
        times,_=mms.align_signal(model,pcm,plan,a)
        if [w['wordId'] for w in times]!=[w['id'] for w in context]:
            raise ValueError('Context decode changed repeat word ownership')
        decodes.append([[round(w['startMs']),round(w['endMs'])] for w in times])
    return decodes


def _register_tail(pcm, origin, core, next_onset):
    """Track the low voice after its observed aspiration amid a separately continuing high part."""
    import numpy as np
    from scipy.signal import find_peaks
    if not 600<=next_onset-core<=2600:
        return None
    n=round(.15*44100); freqs=np.fft.rfftfreq(16384,1/44100)
    frames=[];win=np.hanning(n)
    for t in range(core-160,next_onset+201,30):
        left=round((t-origin)*44.1)-n//2
        if left<0 or left+n>len(pcm):
            return None
        mag=20*np.log10(np.maximum(1.e-8,abs(np.fft.rfft(win*pcm[left:left+n],16384))/(n/4)))
        frames.append((t,mag))
    seed=np.median([v for t,v in frames if t<=core],axis=0)
    candidates=np.where((freqs>=85)&(freqs<=260))[0]
    def band(spec,hz,spread):
        return float(np.max(spec[np.abs(freqs-hz)<=spread]))
    def low_score(f):
        return float(np.median([band(seed,f*h,max(5.,.03*f*h)) for h in (1,2,3)]))
    # Start from the low harmonic family with independently detected partials.
    peaks=find_peaks(seed,prominence=3)[0]
    fundamental=max((freqs[j] for j in peaks if j in candidates),key=low_score,default=None)
    if fundamental is None:
        return None
    hz=float(fundamental)
    def levels(s):
        lower=[band(s,hz*h,max(8.,.06*hz*h)) for h in (1,2,3)]
        upper=[band(s,hz*h,max(10.,.06*hz*h)) for h in (4,8)]
        return np.median(lower),min(upper)
    baseline,_=levels(seed)
    rows=[(t,*levels(v)) for t,v in frames]
    high_crosses=[u for t,l,u in rows if next_onset-100<=t<=next_onset+100 and u>baseline-9]
    if len(high_crosses)<5:
        return None
    for k,(t,l,u) in enumerate(rows):
        if t<=core or t-core>=LIMIT_MS or t+120>=next_onset:
            continue
        run=rows[k:k+5]
        if (len(run)==5 and all(lo<baseline-6 and up>lo+6 for _,lo,up in run)
                and u>baseline-5):
            return [core,t,hz]
    return None


def observe(requests,observations,files,project,duration):
    import os
    from faster_whisper import WhisperModel
    from stemwise_engine import endpoint_mms_prediction as mms, lyrics_align_pc
    from stemwise_engine.acoustic_inputs import StreamedMonoAudio
    from stemwise_engine.arabic_coda_release import _read_role
    from stemwise_engine.held_repeat_release import classifier
    from stemwise_engine.lyrics_alignment import resolve_compute_type
    project=Path(project)
    os.environ['TORCH_HOME']=str(project.parent/'model-cache/torch')
    dense=classifier(project)
    local=mms.load_model(project)
    whisper=None
    rows=[]
    try:
        for req in requests:
            ctx=req['context']; i=req['lineCount']; view={}
            trace=observations[req['observation']]
            for role in ('lead','choir'):
                if role not in files:
                    continue
                path=Path(files[role][0]); events=trace['views'].get(role,())
                target=req['words']
                if any(repeat._articulated(trace['pronunciation'],trace['views'].get('backing',()),
                                  w['guide']['startMs'],w['guide']['endMs']) for w in target):
                    continue
                hits=[repeat._articulated(trace['pronunciation'],events,w['guide']['startMs'],w['guide']['endMs'])
                      for w in target]
                # The assigned part must contain actual lexical evidence. Only open
                # recognition on its own PCM may rescue an incomplete phone signature.
                recognized=[]
                if not all(hits):
                    if whisper is None:
                        snapshot,_=lyrics_align_pc._verify_whisper_snapshot(project.parent/'model-cache/lyrics-alignment')
                        whisper=WhisperModel(str(snapshot),device='cuda',compute_type=resolve_compute_type('cuda'),
                                             local_files_only=True)
                    a=max(0,(ctx[0]['a']-250)//10*10); b=min(duration//10*10,(ctx[i-1]['b']+350)//10*10)
                    recognized=_readings(whisper,_read_role(path,a,b,duration),target[0]['language'],a)
                    hits=[ok or (_recognized(w['spoken'],recognized,w['guide']['startMs'],w['guide']['endMs'])
                              and _partial(events,trace['pronunciation'],w['guide']['startMs'],w['guide']['endMs']))
                          for ok,w in zip(hits,target,strict=True)]
                if not any(hits):
                    continue
                stream=StreamedMonoAudio(path,16000)
                decodes=_decodes(local,stream,ctx,duration)
                runs=[]; registers={}
                for pad in (350,650):
                    a=max(0,(ctx[0]['a']-pad)//10*10);b=min(duration//10*10,(ctx[-1]['b']+pad)//10*10)
                    pcm=_read_role(path,a,b,duration)
                    runs.append(dense(pcm,a))
                for ok,w in zip(hits,target,strict=True):
                    j=w['index']
                    if not ok or j+1>=len(ctx):
                        continue
                    next_onset=decodes[0][j+1][0]
                    h=[ms for ms,ph,prob in events if ph in ('h','ɦ','ħ') and prob>=.45
                       and w['guide']['startMs']+(w['guide']['endMs']-w['guide']['startMs'])//2<ms<next_onset]
                    if h and trace['pronunciation'][-1] in ('h','ɦ','ħ'):
                        span=_register_tail(pcm,a,h[-1],next_onset)
                        if span:
                            registers[str(j)]=span
                view[role]=dict(hits=hits,heard=recognized,decodes=decodes,runs=runs,registers=registers)
            rows.append(view)
    finally:
        if whisper is not None:
            whisper.model.unload_model()
    return rows


def _physical_exit(runs,core,near,next_start,events):
    """Measure a sustained final syllable into a rest or a new articulation."""
    # The actual next word may start well after the search observation. Its
    # vowel/consonant landmarks constrain a transition; its onset is no exit.
    end=min(near+900,next_start+100)
    local=[(a,b,p) for a,b,p in runs if core-100<b and a<end]
    for pos,(a,b,p) in enumerate(local):
        if p!='SP' or b-a<100 or a<core+80 or abs(a-near)>=LIMIT_MS:
            continue
        prior=[(x,y,ph) for x,y,ph in local[:pos] if a-500<y<=a]
        if sum(min(a,y)-max(a-500,x) for x,y,ph in prior if ph in OPEN)<200:
            continue
        if any(x>core+100 and ph not in OPEN|{'SP','h','hh','l','n','w'} and y-x>100 for x,y,ph in local[:pos]):
            continue
        return a
    entrances=[(t,p) for t,p,q in events if next_start-200<=t<=next_start+180 and q>=.20]
    if not entrances:
        return None
    # The repeated word can directly replace a long stable vowel. Follow a
    # real change of class into the next articulated core, allowing pickups.
    next_core=min(t for t,p in entrances if t>=next_start-150) if any(t>=next_start-150 for t,p in entrances) else None
    if next_core is None:
        return None
    for x,_,ph in local:
        if not next_core-225<=x<min(next_core,next_start) or ph in OPEN or x<=core:
            continue
        before=sum(max(0,min(x,b)-max(x-100,a)) for a,b,p in local if p in OPEN)
        after=sum(max(0,min(x+70,b)-max(x,a)) for a,b,p in local if p not in OPEN)
        if before>=80 and after>=50:
            return x
    candidates=[]
    for k,(a,b,p) in enumerate(local):
        if p not in OPEN or b-a<180 or not core<b or not next_core-375<=b<=next_core+30:
            continue
        later=[(x,y,ph) for x,y,ph in runs if b<=x<=next_core+150 and y-x>=30]
        if (later and later[0][2]!=p and later[0][0]-b<=80
                and not any(ph==p and y-x>=80 for x,y,ph in later if x<next_core)):
            candidates.append(b)
    return max(candidates) if candidates else None


def _word_interval(req,word,wi,view,events):
    from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
    if not view['hits'][wi] or not repeat._tight(word['guide'],0):
        return None
    j=word['index']; guide=word['guide']; entry=guide['startMs']; hint=guide['endMs']
    native=[row[j] for row in view['decodes']]
    starts=[a for a,b in native]
    if max(starts)-min(starts)>100 or any(abs(x-entry)>WORD_START_AGREEMENT_MS for x in starts):
        return None
    x=min(starts,key=lambda n:abs(n-word['a']))
    observed_entry=any(abs(t-word['a'])<=100 and p in {'a','ɛ','e','l','ʔ'} and q>=.25
                       for t,p,q in events)
    if ((abs(x-word['a'])<=100 or observed_entry and abs(x-word['a'])<=WORD_START_AGREEMENT_MS)
            and abs(entry-word['a'])<=WORD_START_AGREEMENT_MS):
        x=word['a']
    nexts=[row[j+1][0] for row in view['decodes']]
    if max(nexts)-min(nexts)>WORD_START_AGREEMENT_MS:
        return None
    neighbor_guide=req['context'][j+1]['a']
    following=min(nexts)
    if abs(following-neighbor_guide)>WORD_START_AGREEMENT_MS:
        # A rival forced onto this singer's remaining vowel is a known failure
        # when the next word is a different part. Use the physical guide only
        # to keep listening for a real rest: it cannot supply its timestamp.
        following=neighbor_guide
    # Upper-register bleed from another part can continue across the next
    # lyric. A verified aspirate and separate low-register loss bound this word.
    register=view['registers'].get(str(j))
    if register:
        low,high,f0=register
        chosen=[b for _,b in native if low-50<=b<=high]
        if 0<high-low<LIMIT_MS and len(chosen)==len(native):
            return x,max(low,min(chosen))
    core=[t for t,p,q in events if x+150<=t<=hint and q>=.20 and
          repeat.phones._nucleus(p) in {'i','ɪ','l','n','a','ə','ʌ','h','o'}]
    if not core:
        return None
    physical=[_physical_exit(runs,core[-1],hint,following,events) for runs in view['runs']]
    if all(p is not None for p in physical) and max(physical)-min(physical)<=100:
        return x,round(sum(physical)/len(physical))
    # Independent lexical/role acceptance has placed the word here. A
    # previously selected end and both uncensored context decodes can settle
    # a strictly subsecond dispute if no later articulated coda contradicts it.
    ends=[b for _,b in native]
    if (word['b']>x and abs(word['b']-hint)<LIMIT_MS and max(ends+[word['b']])-min(ends+[word['b']])<LIMIT_MS
            and max(ends+[word['b']])<following and core[-1]<=word['b']):
        return x,word['b']
    return None


def apply(track,requests,observations,traces,protected):
    from stemwise_engine.recording_release_repair import _ordered_changes
    flat=[((i,j),w['timing']) for i,line in enumerate(track['lines']) for j,w in enumerate(line['words'])]
    lookup={pos:n for n,(pos,t) in enumerate(flat)}
    base=[(t['startMs'],t['endMs']) for _,t in flat]
    proposals={}; pending={}
    for req,views in zip(requests,traces,strict=True):
        trace=observations[req['observation']]
        for wi,w in enumerate(req['words']):
            pos=(w['line'],w['index']);k=lookup[pos]
            if pos in protected or w['locked'] or flat[k][1]['status']!='estimated' or base[k]!=(w['a'],w['b']):
                continue
            results=[]
            for role,obs in views.items():
                interval=_word_interval(req,w,wi,obs,trace['views'][role])
                if interval and interval[0]<interval[1]:
                    results.append((role,interval,obs))
            if not results:
                continue
            if (max(iv[0] for _,iv,_ in results)-min(iv[0] for _,iv,_ in results)>100
                    or max(iv[1] for _,iv,_ in results)-min(iv[1] for _,iv,_ in results)>100):
                continue
            _,interval,obs=results[0]
            proposals[k]=interval
            # The same independently admitted preceding word can overlap the
            # corrected entrance. Both of its own uncensored, role-local,
            # complete contextual observations must remain within the owner's
            # subsecond estimate tolerance; never trim it to the next onset.
            right=interval[0]
            earlier={}
            for j in range(w['index']-1,-1,-1):
                slot=k-w['index']+j
                previous=flat[slot][0]
                if slot in proposals or base[slot][1]<=right:
                    break
                candidates=[tuple(row[j]) for row in obs['decodes']]
                if (previous in protected or flat[slot][1]['status']!='estimated'
                        or not all(0<a<b<=right and abs(a-base[slot][0])<=300 and abs(b-base[slot][1])<LIMIT_MS
                                   for a,b in candidates)
                        or max(a for a,b in candidates)-min(a for a,b in candidates)>100
                        or max(b for a,b in candidates)-min(b for a,b in candidates)>100):
                    earlier.clear()
                    break
                current=min(candidates,key=lambda v:abs(v[0]-base[slot][0]))
                earlier[slot]=(current,k)
                right=current[0]
            pending.update(earlier)
    units={tuple(lookup[tuple(p)] for p in req['unit']) for req in requests}
    # An isolated successful member cannot erase a physical-repeat conflict
    # while leaving another word stranded with its old exit.
    def incomplete(changes):
        return {k for unit in units if not all(k in changes for k in unit) for k in unit}
    def discard(partial):
        for k in partial:
            proposals.pop(k,None)
        for k,(_,owner) in list(pending.items()):
            if owner in partial:
                pending.pop(k)
    discard(incomplete(proposals))
    while True:
        accepted=_ordered_changes(base,{**{k:v[0] for k,v in pending.items()},**proposals})
        partial=incomplete(accepted).intersection(proposals)
        if not partial:
            break
        discard(partial)
    measured={}
    for k,(a,b) in accepted.items():
        if k in pending and pending[k][1] not in accepted:
            continue
        (i,j),word=flat[k]
        word.update(startMs=a,endMs=b,basis='Estimated from the performed vocal part and recording-local word transitions.')
        line=track['lines'][i]
        line['timing'].update(startMs=line['words'][0]['timing']['startMs'],
                              endMs=max(w['timing']['endMs'] for w in line['words']))
        if k in proposals:
            measured[i,j]=(a,b)
    return measured
