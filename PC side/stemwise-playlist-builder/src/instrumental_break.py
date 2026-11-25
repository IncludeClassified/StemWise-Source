"""Reject word intervals that continue across a recording-confirmed instrumental break."""
from pathlib import Path

from stemwise_engine.common import read_bounded_json, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.lyric_preview.instrumental_gap import power_blocks, quiet_gap
from stemwise_engine.lyric_preview.candidates import BAR
from stemwise_engine.timing_estimate_policy import LIMIT_MS

FLAG = 'word-crosses-instrumental-break'


def _candidates(track, lines, *, allow_terminal):
    if not track or track.get('format')!='regional' or len(track['lines'])!=len(lines):
        return
    for i,(line,guide) in enumerate(zip(track['lines'],lines,strict=True)):
        if line['text']!=guide['text'] or len(line['words'])!=len(guide['words']):
            return
        for k,(word,g) in enumerate(zip(line['words'],guide['words'],strict=True)):
            t=word['timing']
            a,b=t.get('startMs'),t.get('endMs')
            crops=g.get('vocalCropRangeMs')
            if (type(a) is int and type(b) is int and a<g['endMs'] and abs(a-g['startMs'])<=300
                    and crops and crops[1]
                    and max(abs(g['endMs']-x) for x in crops[1])<=BAR
                    and (word['startUtf16'],word['endUtf16'])==(g['startUtf16'],g['endUtf16'])):
                overhang = b-g['endMs']
                ordinary = overhang>=1800 and g.get('vocalContrast')==[True,True]
                final = (allow_terminal and i==len(lines)-1 and k==len(guide['words'])-1
                         and t['status']=='estimated' and overhang>=LIMIT_MS)
                if ordinary or final:
                    yield i,k,a,b,g['endMs'],ordinary,final


def _terminal_phrase_matches(readings, guide, language):
    from stemwise_engine.lyric_comparison import units

    target = guide[-3:]
    expected = [units(word['text'], language) for word in target]
    if len(expected) != 3 or len(set(expected)) < 2 or any(len(word) != 1 for word in expected):
        return 0
    matches = 0
    for index in range(len(readings)-2):
        group = readings[index:index+3]
        if all(units(word['text'], language) == written
               and word['startMs'] < anchor['endMs'] and anchor['startMs'] < word['endMs']
               for word, written, anchor in zip(group, expected, target, strict=True)) and all(
                   left['endMs'] <= right['startMs'] for left,right in zip(group,group[1:])):
            matches += 1
    return matches


def _lead_break(folder, files, line, row, bounds, backing, duration):
    """Recover this singer's word before a backed break that overlaps another voice."""
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _read_role
    from stemwise_engine.vocal_release_transition import extract

    left, right = bounds
    a, b, hint = row['startMs'], row['endMs'], row['guideEndMs']
    lead = Path(files['lead'][0])
    if sha256_file(lead) != files['lead'][1]:
        raise ValueError('Instrumental-break lead source changed')
    gap = quiet_gap(power_blocks(lead,left,right),backing)
    if gap is None:
        return None
    gap += left
    start,end = max(a,hint-500)//10*10,min(b,hint+500)//10*10
    if not start < hint < end or not start < gap < b:
        return None
    voice, without_voice = (resample_poly(_read_role(path,start,end,duration),160,441)
                            for path in (lead, files['backing'][0]))
    measured = _release(extract(voice,without_voice,16000,start),hint,gap)
    if measured is None or not a<measured<b:
        return None
    signatures = _lead_readings(folder,lead,line)
    return {'instrumentalMs':gap,'leadEndMs':measured,'roleReadingsSha256':signatures} if signatures else None


def _lead_readings(folder, lead, line):
    from stemwise_engine.lyric_preview.reading_review import _bound_json

    review = folder/'vocal-overlap/review.private.json'
    policy = _bound_json(lead.parent.parent/'backing-activity.private.json',
                         read_bounded_json(review,max_bytes=MAX_JSON_BYTES,root=review.parent)['backingActivitySha256'],lead.parent.parent)
    signatures = policy.get('readings',{})
    if not all(signatures.get(role) for role in ('lead','choir','backing')):
        return None
    matches = {}
    for role in ('lead','choir','backing'):
        reading = _bound_json(lead.parent/f'{role}-words.private.json',signatures[role],lead.parent)
        matches[role] = _terminal_phrase_matches(reading['words'],line['words'],'tr')
    if matches != {'lead': 1, 'choir': 0, 'backing': 0}:
        return None
    return {r:signatures[r] for r in matches}


def _terminal_break(folder, files, line, row, left, duration):
    """Confirm a terminal release against a sustained backed rest past the old interval."""
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _read_role
    from stemwise_engine.vocal_release_transition import extract

    a,b,hint = (row[key] for key in ('startMs','endMs','guideEndMs'))
    paths = [Path(files[role][0]) for role in ('lead','vocal','backing')]
    if sha256_file(paths[0]) != files['lead'][1]:
        raise ValueError('Instrumental-break lead source changed')
    right = min(duration,hint+3000)
    lead,vocal,backing = (power_blocks(path,left,right) for path in paths)
    lead_gap,vocal_gap = quiet_gap(lead,backing),quiet_gap(vocal,backing)
    if lead_gap is None or vocal_gap is None or abs(lead_gap-vocal_gap)>BAR:
        return None
    gap = left+min(lead_gap,vocal_gap)
    if abs(gap-hint)>300 or not a<gap<b:
        return None
    start,end = max(a,gap-500)//10*10,min(duration,gap+500)//10*10
    lead,vocal,backing = (resample_poly(_read_role(path,start,end,duration),160,441) for path in paths)
    ends = [_release(extract(voice,backing,16000,start),gap,gap,require_periodic=False)
            for voice in (lead,vocal)]
    if any(t is None or not a<t<b for t in ends) or abs(ends[0]-ends[1])>BAR:
        return None
    signatures = _lead_readings(folder,paths[0],line)
    return {'instrumentalMs':gap,'terminalEndMs':ends[0],'roleReadingsSha256':signatures} if signatures else None


def scan(workspace, state, original, indices, audio, track, lines, *, allow_lead=True, allow_terminal=True):
    """Return a checked break on the original clock. No numerical worker runs here."""
    from stemwise_engine.recording_repeat_words import _inputs

    turkish = original.get('language')=='tr'
    candidates=list(_candidates(track,lines,allow_terminal=allow_terminal and turkish))
    if not candidates:
        return (),{}
    folder=workspace.job_dir(state['videoId'])
    available=_inputs(folder,audio,original,indices)
    if not available:
        return (),{}
    _,checked,files=available
    if checked!=lines:
        raise ValueError('Instrumental-break lyric timing guide changed')
    if any(sha256_file(Path(files[r][0]))!=files[r][1] for r in ('vocal','backing')):
        raise ValueError('Instrumental-break vocal sources changed')
    breaks=[]
    for i,k,a,b,hint,ordinary,final in candidates:
        # Only read the region around the independently located release. An
        # arbitrarily long candidate must never allocate a whole-song array.
        left=max(a,hint-1000)
        row={'line':i,'wordIndex':k,'startMs':a,'endMs':b,'guideEndMs':hint}
        if final and 'lead' in files:
            terminal = _terminal_break(folder,files,lines[i],row,left,audio['durationMs'])
            if terminal:
                breaks.append({**row,**terminal})
                continue
        if not ordinary:
            continue
        right=min(b,hint+3000)
        voice,backing=(power_blocks(files[r][0],left,right) for r in ('vocal','backing'))
        if len(voice)<5 or len(backing)!=len(voice):
            continue
        gap=quiet_gap(voice,backing)
        if gap is not None and abs(left+gap-hint)<=300:
            breaks.append({**row,'instrumentalMs':left+gap})
        elif (allow_lead and turkish and 'lead' in files
              and k==len(lines[i]['words'])-1
              and track['lines'][i]['words'][k]['timing']['status']=='estimated'):
            lead=_lead_break(folder,files,lines[i],row,(left,right),backing,audio['durationMs'])
            if lead:
                breaks.append({**row,**lead})
    return breaks,files


def _release(features, hint, gap, *, require_periodic=True):
    import numpy as np
    from stemwise_engine.vocal_span_estimate import _transitions
    from stemwise_engine.vocal_release_transition import _read

    time,level,periodic,contrast,envelope=_read(features)
    scores=_transitions(time,level,periodic,contrast,envelope,hint,entrance=False)
    eligible={}
    for t,strength in scores.items():
        if t>gap+BAR:
            continue
        before=(time>=t-100)&(time<=t-30)
        after=(time>=t+30)&(time<=t+130)
        if (np.median(contrast[before])>=.45 and (not require_periodic or np.median(periodic[before])>=.45)
                and np.median(level[before])-np.median(level[after])>=10
                and np.median(contrast[after])<=.25):
            eligible[t]=strength
    return max(eligible,key=eligible.get,default=None)


def recover(workspace,state,original,admission,audio,track,protected,*,ref=None):
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _read_role
    from stemwise_engine.recording_repeat_words import _digest, _inputs
    from stemwise_engine.vocal_release_transition import extract

    folder=workspace.job_dir(state['videoId'])
    indices=admission.source_physical_indices
    view=_inputs(folder,audio,original,indices)
    if not view:
        if ref:
            raise ValueError('Instrumental-break measurements lost their recording')
        return None
    guide,lines,_=view
    breaks,files=scan(workspace,state,original,indices,audio,track,lines,
                      allow_lead=ref is None or 'lead' in (ref.get('sourceSha256') or {}),
                      allow_terminal=ref is None or any('terminalEndMs' in row for row in ref['boundaries']))
    changes=[]
    for row in breaks:
        i,k=row['line'],row['wordIndex']
        timing=track['lines'][i]['words'][k]['timing']
        if (i,k) in protected or timing['status']!='estimated':
            continue
        measured = row.get('leadEndMs',row.get('terminalEndMs'))
        if measured is not None:
            changes.append({**row,'measuredEndMs':measured})
            continue
        hint=row['guideEndMs']
        a=max(row['startMs'],hint-500)//10*10
        b=min(row['endMs'],hint+500)//10*10
        backing=resample_poly(_read_role(files['backing'][0],a,b,audio['durationMs']),160,441)
        voice=resample_poly(_read_role(files['vocal'][0],a,b,audio['durationMs']),160,441)
        end=_release(extract(voice,backing,16000,a),hint,row['instrumentalMs'])
        if end is not None and row['startMs']<end<row['endMs']:
            changes.append({**row,'measuredEndMs':end})
    if not changes:
        if ref:
            raise ValueError('Instrumental-break release measurements no longer match')
        return None
    exact={'recording':audio['sha256'],'guide':guide,'boundaries':changes,
           'sourceSha256':{r:files[r][1] for r in ('vocal','backing','lead')
                           if r!='lead' or any('roleReadingsSha256' in row for row in changes)}}
    if ref is not None and ref!=exact:
        raise ValueError('Instrumental-break evidence changed')
    for row in changes:
        i,k=row['line'],row['wordIndex']
        line=track['lines'][i]
        e=row['measuredEndMs']
        line['words'][k]['timing'].update(endMs=e,basis='Estimated from the vocal release before the instrumental break.')
        line['timing']['endMs']=max(w['timing']['endMs'] for w in line['words'])
        protected[i,k]=(row['startMs'],e,_digest(exact))
    return exact
