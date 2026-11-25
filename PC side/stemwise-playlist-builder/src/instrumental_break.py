"""Reject word intervals that continue across a recording-confirmed instrumental break."""
from pathlib import Path

from stemwise_engine.common import sha256_file
from stemwise_engine.lyric_preview.instrumental_gap import power_blocks, quiet_gap
from stemwise_engine.lyric_preview.candidates import BAR

FLAG = 'word-crosses-instrumental-break'


def _candidates(track, lines):
    if not track or track.get('format')!='regional' or len(track['lines'])!=len(lines):
        return
    for i,(line,guide) in enumerate(zip(track['lines'],lines,strict=True)):
        if line['text']!=guide['text'] or len(line['words'])!=len(guide['words']):
            return
        for k,(word,g) in enumerate(zip(line['words'],guide['words'],strict=True)):
            t=word['timing']
            a,b=t.get('startMs'),t.get('endMs')
            crops=g.get('vocalCropRangeMs')
            if (type(a) is int and type(b) is int and b-g['endMs']>=1800
                    and a<g['endMs'] and abs(a-g['startMs'])<=300
                    and g.get('vocalContrast')==[True,True] and crops and crops[1]
                    and max(abs(g['endMs']-x) for x in crops[1])<=BAR
                    and (word['startUtf16'],word['endUtf16'])==(g['startUtf16'],g['endUtf16'])):
                yield i,k,a,b,g['endMs']


def scan(workspace, state, original, indices, audio, track, lines):
    """Return a checked break on the original clock. No numerical worker runs here."""
    from stemwise_engine.recording_repeat_words import _inputs

    candidates=list(_candidates(track,lines))
    if not candidates:
        return (),{}
    available=_inputs(workspace.job_dir(state['videoId']),audio,original,indices)
    if not available:
        return (),{}
    _,checked,files=available
    if checked!=lines:
        raise ValueError('Instrumental-break lyric timing guide changed')
    if any(sha256_file(Path(files[r][0]))!=files[r][1] for r in ('vocal','backing')):
        raise ValueError('Instrumental-break vocal sources changed')
    breaks=[]
    for i,k,a,b,hint in candidates:
        # Only read the region around the independently located release. An
        # arbitrarily long candidate must never allocate a whole-song array.
        left=max(a,hint-1000)
        right=min(b,hint+3000)
        voice,backing=(power_blocks(files[r][0],left,right) for r in ('vocal','backing'))
        if len(voice)<5 or len(backing)!=len(voice):
            continue
        gap=quiet_gap(voice,backing)
        if gap is not None and abs(left+gap-hint)<=300:
            breaks.append({'line':i,'wordIndex':k,'startMs':a,'endMs':b,
                           'guideEndMs':hint,'instrumentalMs':left+gap})
    return breaks,files


def _release(features, hint, gap):
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
        if (np.median(contrast[before])>=.45 and np.median(periodic[before])>=.45
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
    breaks,files=scan(workspace,state,original,indices,audio,track,lines)
    changes=[]
    for row in breaks:
        i,k=row['line'],row['wordIndex']
        timing=track['lines'][i]['words'][k]['timing']
        if (i,k) in protected or timing['status']!='estimated':
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
           'sourceSha256':{r:files[r][1] for r in ('vocal','backing')}}
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
