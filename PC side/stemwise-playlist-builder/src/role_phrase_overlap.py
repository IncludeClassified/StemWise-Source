"""Measure a recognized lead phrase ending while a separately recognized choir answers."""
import unicodedata
from pathlib import Path

from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS
from stemwise_engine.common import read_bounded_json
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyric_comparison import units
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
from stemwise_engine.lyric_preview.reading_review import _bound_json
from stemwise_engine.recording_repeat_words import _inputs


def _word(text):
    parts=units(text,'ar')
    return parts[0] if len(parts)==1 else ''


def _spelling(word):
    # Only for locating an already admitted word on a vocal role. Keep the
    # reviewed display, pronunciation and occurrence unchanged.
    return ''.join(ch for ch in word if not unicodedata.combining(ch))


def _line_hit(line, readings, selected, *, response=False):
    expected=[_word(w['text']) for w in line]
    if len(expected)<3 or '' in expected or len(set(expected))<2:
        return None
    hits=[]
    for i in range(len(readings)-len(line)+1):
        group=readings[i:i+len(line)]
        last=group[-1]
        if not response and not last['startMs']<=selected<last['endMs']:
            continue
        if response and not (group[0]['startMs']-300<=selected<=group[1]['endMs']+300):
            continue
        actual=[_word(w['text']) for w in group]
        matched=[a==b or a==_spelling(b) for a,b in zip(actual,expected)]
        if not all(matched) and not (response and len(line)>=4 and matched[0] and matched[-1] and sum(matched)==len(line)-1):
            continue
        if any(b['startMs']<a['endMs'] or a['endMs']<=a['startMs'] for a,b in zip(group,group[1:])):
            continue
        hits.append((matched,readings[i+len(line)] if i+len(line)<len(readings) else None))
    return hits[0] if len(hits)==1 else None


def attach(folder,audio,original,admission,targets):
    wanted=[t for t in targets if t['releaseDisputed'] and t['language']=='ar' and not t['locked']]
    if not wanted:
        return None
    assets=_inputs(folder,audio,original,admission.source_physical_indices)
    if not assets:
        return None
    digest,lines,files=assets
    if 'lead' not in files or 'choir' not in files:
        return None
    part=Path(files['lead'][0]).parent
    view=part.parent
    rec=read_bounded_json(view.parent/'review.private.json',max_bytes=MAX_JSON_BYTES,root=view.parent)
    act=_bound_json(view/'backing-activity.private.json',rec['backingActivitySha256'],view)
    readings={}
    for role in ('lead','choir','mix'):
        sha=act['readings'].get(role)
        if sha:
            raw=_bound_json(part/(role+'-words.private.json'),sha,part)
            expected=audio['sha256'] if role=='mix' else files[role][1]
            if raw['binding']['sha256']!=expected:
                raise ValueError('Response recognition belongs to another vocal source')
            readings[role]=raw['words']
    mapping=build_lexical_occurrence_mapping(folder.name,original,admission.source_physical_indices)['performedOccurrences']
    for t in wanted:
        i=t['line']
        if i+1>=len(lines) or not t.get('following'):
            continue
        line,response=lines[i]['words'],lines[i+1]['words']
        current,following=mapping[i]['words'],mapping[i+1]['words']
        if (t['wordIndex']!=len(line)-1 or not response or len(current)!=len(line) or len(following)!=len(response)
                or t['wordId']!=current[-1]['occurrenceWordId'] or following[0]['occurrenceWordId']!=t['following']['wordId']
                or _word(t['text'])!=_word(line[-1]['text'])):
            continue
        lead=_line_hit(line,readings.get('lead',[]),t['startMs'])
        choir=_line_hit(response,readings.get('choir',[]),t['following']['startMs'],response=True)
        mix=_line_hit(response,readings.get('mix',[]),t['following']['startMs'],response=True)
        if not lead or not (choir or mix):
            continue
        if _line_hit(response,readings.get('lead',[]),t['following']['startMs'],response=True):
            continue
        t['crossLane']={'response':[{'wordId':own['occurrenceWordId'],'text':w['text'],'startMs':w['startMs'],
                                    'endMs':w['endMs']} for w,own in zip(response,following,strict=True)],
                        'directChoir':choir[0] if choir else [False]*len(response),
                        'responseMatch':(choir or mix)[0], 'nextLead':lead[1]}
    return ({'crossLaneTimingSha256':digest,'crossLaneReadings':act['readings']}
            if any(t.get('crossLane') for t in wanted) else None)


def _letters(events):
    return [(a,b,ch) for a,b,ch,prob in events if prob>=.35 and len(ch)==1 and
            unicodedata.category(ch).startswith('L')]


def _sequence(wanted,events):
    result=[]
    for a,b,ch in events:
        if ch==wanted[len(result)]:
            result.append((a,b))
            if len(result)==len(wanted):
                return result
    return []


def _lead_core(t,events):
    # The final consonant may be unreleased. Require the actual lead to
    # articulate the preceding consonants, including a later syllable after
    # its onset. A sustained instrumental note cannot provide these events.
    consonants=[ch for ch in _spelling(_word(t['text'])) if ch not in 'اٰىويأإآ']
    if len(consonants)<4 or len(set(consonants[:-1]))<2:
        return None
    first=t['startMs'];obs=_letters(events)
    limit=t['crossLane']['response'][-1]['endMs']
    next_lead=t['crossLane']['nextLead']
    if next_lead:
        limit=min(limit,next_lead['endMs'])
    local=[(a,b,p) for a,b,p in obs if first-WORD_START_AGREEMENT_MS<=a<limit]
    prefix=_sequence(consonants[:-1],local)
    if (not prefix or abs(prefix[0][0]-first)>WORD_START_AGREEMENT_MS or
            prefix[-1][0]-prefix[0][0]<200 or
            _sequence(consonants[:-1],[(a,b,p) for a,b,p in local if a>=prefix[-1][1]])):
        return None
    return prefix[-1][1]


def _choir_owners(t,trace,end):
    info=t['crossLane'];response=info['response'];own=_letters(trace['leadLetters']);choir=_letters(trace['choirLetters'])
    owners=[];anchored=0
    for k,w in enumerate(response):
        start=w['startMs'];width=min(w['endMs']+180,response[k+1]['startMs']+100 if k+1<len(response) else w['endMs']+180)
        if start>end+WORD_START_AGREEMENT_MS:
            break
        wanted=[p for p in _spelling(_word(w['text'])) if p not in 'ىأإآ']
        if len(wanted)<2:
            return []
        in_word=lambda es: [(a,b,p) for a,b,p in es if start-220<=a<width]
        role_events=in_word(choir);opposing=in_word(own)
        signatures=[[wanted[0],wanted[-1]],wanted[:2],wanted[-2:]]
        distinct=any(len(set(s))>=2 and _sequence(s,role_events) and not _sequence(s,opposing) for s in signatures)
        if distinct:
            anchored+=1
        elif not info['directChoir'][k]:
            return []
        if not info['responseMatch'][k] and not distinct:
            return []
        owners.append(w['wordId'])
    return owners if anchored>=2 else []


def within_response_word(t,trace,index,selected):
    """A retained start may be late inside the same distinctly articulated choir word."""
    w=t['crossLane']['response'][index]
    if not w['startMs']<=selected<w['endMs']:
        return False
    spelling=_spelling(_word(w['text']))
    if len(set(spelling[:2]))!=2:
        return False
    groups=[[]]
    for a,b,ch,prob in trace['choirLetters']:
        if ch=='|' and prob>=.5:
            groups.append([])
        elif prob>=.35 and len(ch)==1 and unicodedata.category(ch).startswith('L'):
            groups[-1].append((a,b,ch))
    hits=[g for g in groups if len(g)>=3 and g[0][2]+g[1][2]==spelling[:2]
          and abs(g[0][0]-w['startMs'])<=WORD_START_AGREEMENT_MS and g[-1][1]<=w['endMs']+100]
    return len(hits)==1 and hits[0][0][0]<=selected<hits[0][-1][1]


def _decays(t,trace,core):
    """Find distinct exposed-to-quiet transitions after the recognized articulations."""
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectral
    one,two=trace['leadOverChoir'],trace['leadOverBacking']
    clock,level,periodic,choir,shape=spectral._read(one)
    other,_,_,backing,_=spectral._read(two)
    if not np.array_equal(clock,other):
        raise ValueError('Response and lead clocks differ')
    later=t['crossLane']['nextLead']
    limit=min(later['startMs'] if later else clock[-1],t['crossLane']['response'][-1]['endMs']+300)
    hits=[]
    for i in np.flatnonzero((clock>=core+160)&(clock<limit)):
        if i<32 or i+15>=len(clock):
            continue
        before=slice(i-30,i-16);after=slice(i,i+12)
        base=np.median(level[before])
        if (min(np.median(choir[before]),np.median(backing[before]))<.4
                or np.median(periodic[before])<.6 or np.max(level[after])>base-16
                or np.median(level[after])>base-21 or min(np.median(choir[after]),np.median(backing[after]))>.25):
            continue
        # Agreement between a single separated voice against two possible
        # contaminants tests masking; these are not two independent singers.
        edge=round(clock[i]);vals=[spectral.decide(f,edge-390,edge-270,edge) for f in (one,two)]
        if None not in vals and max(vals)-min(vals)<=spectral.FRAME_MS:
            hits.append(vals[0])
    if not hits or max(hits)-min(hits)>100:
        return None
    return int(np.median(hits)) if int(np.median(hits)) in hits else min(hits)


def _next_recognized(t,trace,after):
    next_word=t['crossLane']['nextLead']
    if not next_word:
        return None
    spelling=_spelling(_word(next_word['text']))
    if len(set(spelling))<2:
        return None
    furthest=next_word['endMs']+WORD_START_AGREEMENT_MS
    seq=_sequence(spelling,[(a,b,p) for a,b,p in _letters(trace['leadLetters'])
                            if after<a<=furthest and a>=next_word['startMs']-WORD_START_AGREEMENT_MS])
    return seq[0][0] if seq else None


def _partly_masked(t,trace,core):
    """Rank a physical lead fade when choir leakage intermittently masks the last syllable."""
    import numpy as np
    from stemwise_engine import vocal_release_transition as spectral
    from stemwise_engine.timing_estimate_policy import LIMIT_MS
    following=_next_recognized(t,trace,core)
    if following is None:
        return None
    events=trace['leadLetters']
    spaces=[a for a,b,ch,p in events if core+100<a<following and ch=='|' and p>=.8]
    if len(spaces)!=1:
        return None
    delimiter=spaces[0]
    # The burst that follows an initial fade might be a weak coda. A
    # background-only gap or a second lead word cannot close this estimate.
    if any(p not in 'اويى' for a,b,p in _letters(events) if core+80<a<delimiter):
        return None
    clock,level,periodic,choir,env=spectral._read(trace['leadOverChoir'])
    other,_,_,backing,_=spectral._read(trace['leadOverBacking'])
    if not np.array_equal(clock,other):
        raise ValueError('Masked lead and choir observations use different clocks')
    choices=[]
    last_quiet=None
    for i in np.flatnonzero((clock>=max(core+300,delimiter-LIMIT_MS))&(clock<delimiter)):
        if i<50 or i+12>=len(clock):
            continue
        region=np.arange(i-50,i-15)
        exposed=(choir[region]>=.4)&(backing[region]>=.4)&(periodic[region]>=.65)
        body=region[exposed]
        if len(body)<8:
            continue
        base=np.median(level[body]);shape=np.median(env[body],axis=0)
        shape/=max(np.linalg.norm(shape),1.e-12)
        if np.median(env[body]@shape)<.85:
            continue
        quiet=slice(i,i+10)
        if (np.max(level[quiet])>base-18 or max(np.median(choir[quiet]),np.median(backing[quiet]))>.15
                or np.max(level[region[-1]:i])<=base-18):
            continue
        # The first quiet frame is measured in the lead, with two contrasts.
        # Retain the plausible uncertainty up to the independently decoded
        # phrase separator; it ranks this ordinary estimate, never sets it.
        prior=region[exposed&(level[region]>=base-6)&(env[region]@shape>=.8)]
        if len(prior)<5 or delimiter-clock[prior[-1]]>=LIMIT_MS:
            continue
        ends=[c['endMs'] for c in t['candidates'] if c['family']!='owsm-ctc' and
              abs(c['startMs']-t['startMs'])<=WORD_START_AGREEMENT_MS and c['endMs']>=clock[prior[-1]]]
        if max([delimiter,*ends])-min([clock[prior[-1]],*ends])>=LIMIT_MS:
            continue
        if last_quiet is None or clock[i]-last_quiet>spectral.HOP_MS:
            choices.append(int(clock[i]))
        last_quiet=clock[i]
    return choices[0] if len(choices)==1 else None


def replay(t,trace):
    if trace['wordId']!=t['wordId'] or trace['binding']!=t['crossLane']:
        raise ValueError('Crossed response belongs to another physical lyric')
    core=_lead_core(t,trace['leadLetters'])
    if core is None or _lead_core(t,trace['choirLetters']) is not None:
        return None
    end=_decays(t,trace,core)
    if end is None:
        end=_partly_masked(t,trace,core)
    if end is None or end<=t['following']['startMs']:
        return None
    # A very late native contender can be excluded only when a subsequent
    # recognized word on the *same* lead demonstrably falls inside it.
    far=[c['endMs'] for c in t['candidates'] if c['family']!='owsm-ctc' and c['endMs']>=end+1000
         and abs(c['startMs']-t['startMs'])<=WORD_START_AGREEMENT_MS]
    if far:
        next_phone=_next_recognized(t,trace,end)
        if next_phone is None or next_phone>=min(far):
            return None
    owners=_choir_owners(t,trace,end)
    if not owners:
        return None
    return {'endMs':end,'otherSingerWordIds':owners}


def analyze(t,context,letters):
    import soundfile as sf
    from scipy.signal import resample_poly
    from stemwise_engine import vocal_release_transition as spectral
    info=t['crossLane'];start=max(0,(t['startMs']//1000-1)*1000)
    stop=min(context['durationMs'],((info['response'][-1]['endMs']+1800)//500)*500)
    if stop<=start+1000 or stop-start>30000:
        return None
    pcm={}
    for role in ('lead','choir','backing'):
        with sf.SoundFile(context[role]) as f:
            if f.samplerate!=44100 or f.channels!=2 or abs(1000*len(f)/f.samplerate-context['durationMs'])>2:
                raise ValueError('Response source uses a different recording clock')
            a=round(start*f.samplerate/1000);n=round(stop*f.samplerate/1000)-a
            f.seek(a);v=f.read(n,always_2d=True,dtype='float32')
            if len(v)!=n:
                raise ValueError('Incomplete response source window')
            pcm[role]=resample_poly(v.mean(1),160,441)
    trace={'wordId':t['wordId'],'binding':info,'leadLetters':letters(pcm['lead'],16000,start),
           'choirLetters':letters(pcm['choir'],16000,start)}
    if _lead_core(t,trace['leadLetters']) is None:
        return None
    for key,role in [('leadOverChoir','choir'),('leadOverBacking','backing')]:
        trace[key]=spectral.extract(pcm['lead'],pcm[role],16000,start)
    return trace if replay(t,trace) is not None else None
