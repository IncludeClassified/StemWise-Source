"""Find an audible phrase-final release after a vocal match to a separate rendition."""
from contextlib import ExitStack
from functools import lru_cache
from pathlib import Path

from stemwise_engine.common import sha256_file
from stemwise_engine.lyric_preview.candidates import BAR
from stemwise_engine.recording_phrase_placement import _voice, correlate_word
from stemwise_engine.recording_repeat_words import _digest, _inputs, _tight


def _matches(source, target, read, duration):
    """Check two neighboring words, lexical entry and the final word's tail."""
    for k, (left, right) in enumerate(zip(source[-3:], target[-3:], strict=True)):
        a, b = left['startMs'], left['endMs']
        x, y = right['startMs'], right['endMs']
        if min(b-a, y-x) < 180:
            return False
        onset = min(40, (b-a-150)//2)
        points = [(a+onset, x+round(onset*(y-x)/(b-a)))]
        if k == 2:
            if min(b-a, y-x) < 350:
                return False
            points.append((b-250, y-250))
        for start, center in points:
            low, high = max(0,center-220), min(duration,center+370)
            reference = read(start,start+150)
            actual = read(low,high)
            score, offset = correlate_word(reference,actual)
            if score < (.5 if k==2 else .35) or abs(low+offset/8-center) > BAR:
                return False
    return True


def _measure(files, guide, duration):
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _read_role
    from stemwise_engine.instrumental_break import _release
    from stemwise_engine.vocal_release_transition import extract

    hint = guide['endMs']
    left, right = max(0,hint-550)//10*10, min(duration,hint+600)//10*10
    lead,vocal,backing = (resample_poly(_read_role(files[role][0],left,right,duration),160,441)
                          for role in ('lead','vocal','backing'))
    ends = [_release(extract(voice,backing,16000,left),hint,hint,require_periodic=False)
            for voice in (lead,vocal)]
    return ends[0] if None not in ends and abs(ends[0]-ends[1])<=BAR else None


def recover(workspace,state,original,admission,audio,track,protected,*,ref=None):
    from stemwise_engine.arabic_coda_release import STOPS, _base
    from stemwise_engine.word_timing_review import RELEASE_FLAGS, VOCAL_SPAN_DISAGREEMENT, inspect
    import soundfile as sf

    def unavailable():
        if ref is not None:
            raise ValueError('Repeated phrase release lost its recorded evidence')
        return None

    if original.get('language')!='ar' or admission.spoken_targets:
        return unavailable()
    folder = workspace.job_dir(state['videoId'])
    saved = _inputs(folder,audio,original,admission.source_physical_indices)
    if not saved:
        return unavailable()
    guide,lines,files = saved
    report = inspect(original,track,state['endpointEvidence'],{},audio['durationMs'],None,single=lines)
    flags = {}
    for f in report['publicationFlags']:
        flags.setdefault((f['line']-1,f['wordIndex']-1),set()).add(f['reason'])
    candidates = []
    for i,line in enumerate(lines):
        index = len(line['words'])-1
        word = line['words'][index]
        timing = track['lines'][i]['words'][index]['timing']
        reasons = flags.get((i,index))
        if ((i,index) in protected or timing['status']!='estimated' or not reasons
                or reasons-(RELEASE_FLAGS|{VOCAL_SPAN_DISAGREEMENT}) or index<2
                or _base(word['text'])[-1:] not in STOPS or not all(_tight(word,k) for k in (0,1))
                or abs(timing['startMs']-word['startMs'])>300):
            continue
        peers = [j for j,p in enumerate(lines) if j!=i and p['text']==line['text']
                 and len(p['words'])==len(line['words']) and all(_tight(p['words'][-1],k) for k in (0,1))
                 and (p['words'][-1]['endMs']<=line['words'][0]['startMs']
                      or line['words'][-1]['endMs']<=p['words'][0]['startMs'])]
        if peers:
            candidates.append((i,index,timing,peers))
    if not candidates or not {'lead','vocal','backing'} <= files.keys():
        return unavailable()
    digests = {r:files[r][1] for r in ('lead','vocal','backing')}
    if any(sha256_file(Path(files[r][0]))!=value for r,value in digests.items()):
        raise ValueError('Repeated phrase release audio changed')
    measured = []
    duration = audio['durationMs']
    with ExitStack() as stack:
        streams = [stack.enter_context(sf.SoundFile(files[r][0])) for r in ('lead','backing')]

        @lru_cache(maxsize=64)
        def read(a,b):
            return _voice(streams,a,b)

        for i,index,timing,peers in candidates:
            target = lines[i]['words']
            end = _measure(files,target[-1],duration)
            fence = track['lines'][i+1]['words'][0]['timing']['startMs'] if i+1<len(lines) else duration
            if end is None or not timing['startMs']<end or end+BAR>fence:
                continue
            controls = [lines[j]['words'] for j in (i-1,i+1) if 0<=j<len(lines)
                        and lines[j]['text']!=lines[i]['text'] and len(lines[j]['words'])>=3]
            if not controls:
                continue
            for j in peers:
                source = lines[j]['words']
                if _matches(source,target,read,duration) and not any(_matches(source,c,read,duration) for c in controls):
                    measured.append({'line':i,'wordIndex':index,'donorLine':j,'startMs':timing['startMs'],
                                     'previousEndMs':timing['endMs'],'endMs':end})
                    break
    if not measured:
        return unavailable()
    result = {'recording':audio['sha256'],'guide':guide,'sourceSha256':digests,'boundaries':measured}
    if ref is not None and ref!=result:
        raise ValueError('Repeated phrase release evidence changed')
    seal = _digest(result)
    for r in measured:
        i,index = r['line'],r['wordIndex']
        line = track['lines'][i]
        w = line['words'][index]['timing']
        w.update(endMs=r['endMs'],basis='Estimated from the owned phrase release on this recording.')
        line['timing']['endMs'] = max(word['timing']['endMs'] for word in line['words'])
        protected[i,index] = (r['startMs'],r['endMs'],seal)
    return result
