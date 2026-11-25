"""Remeasure a phrase-final aspiration obscured by another vocal layer."""
import json
from pathlib import Path

from stemwise_engine.common import heavy_worker_stage, run_process_tree, sha256_file
from stemwise_engine.endpoint_vowel_release import AGREEMENT_MS
from stemwise_engine.lyric_comparison import units
from stemwise_engine.lyric_preview.reading_review import _bound_json
from stemwise_engine.multilingual_release_observer import _pronunciation
from stemwise_engine.phrase_final_release import _eligible, _landmark
from stemwise_engine.recording_repeat_words import _inputs, _tight
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


def _matches(line, transcript, target, following):
    written = [units(word['text'], 'ar') for word in line]
    if len(written)<3 or any(len(word)!=1 for word in written) or len(set(written))<2:
        return False
    wanted = [*written, units(following['text'], 'ar')]
    count = len(wanted)
    hits = 0
    for i in range(len(transcript)-count+1):
        group = transcript[i:i+count]
        if ([units(word['text'], 'ar') for word in group]!=wanted
                or any(a['startMs']>=b['startMs'] for a,b in zip(group,group[1:]))):
            continue
        if (group[-2]['startMs']<=target['startMs']<group[-2]['endMs']
                and group[-1]['startMs']<=following['startMs']<group[-1]['endMs']
                and all(w['startMs']<g['endMs'] and w['endMs']>g['startMs'] for w,g in zip(group,line))):
            hits += 1
    return hits==1


def attach(folder, audio, original, admission, targets, lead_path, lead_sha):
    from stemwise_engine.arabic_coda_release import _base

    wanted = [t for t in targets if _eligible(t) and (t.get('following') or {}).get('language')=='ar'
              and _base(t['spoken']).endswith('ه')]
    if not wanted:
        return None
    saved = _inputs(folder,audio,original,admission.source_physical_indices)
    if not saved:
        return None
    timing, lines, _ = saved
    lead = Path(lead_path)
    words = _bound_json(lead,lead_sha,lead.parent)['words']
    for target in wanted:
        line = lines[target['line']]['words']
        final = line[-1]
        if (target['wordIndex']==len(line)-1 and final['text']==target['text']
                and final['startMs']-AGREEMENT_MS<=target['startMs']<final['endMs']
                and all(_tight(final,i) for i in (0,1)) and _matches(line,words,target,target['following'])):
            target['phraseFinalCoda'] = {'wordId':final['wordId'],'hint':final['endMs']}
    return timing if any('phraseFinalCoda' in t for t in wanted) else None


def _aspirate_end(trace, edge, stop):
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read

    def runs(mask):
        indices = np.flatnonzero(mask)
        return [r for r in np.split(indices,np.flatnonzero(np.diff(indices)>1)+1) if len(r)>=3]

    clock,level,periodic,_,envelope = _read(trace['lead'])
    body,vowel_exit,kind = edge
    if kind!='rest' or not vowel_exit<stop<=clock[-1]:
        return None
    core = (clock>=max(body,vowel_exit-260)) & (clock<=vowel_exit-140)
    if core.sum()<6 or np.median(periodic[core])<.65:
        return None
    highs = {}
    for role in ('lead','vocal','choir','backing'):
        time,power,_,_,bands = _read(trace[role])
        if not np.array_equal(clock,time):
            raise ValueError('Coda audio views use different sample clocks')
        highs[role] = power+10*np.log10(np.maximum((bands[:,8:]**2).sum(axis=1),1e-12))
    lead,vocal = highs['lead'],highs['vocal']
    exposed = (lead>=np.maximum(highs['choir'],highs['backing'])+6) & (np.abs(vocal-lead)<=3)
    region = (clock>=vowel_exit-50) & (clock<stop)
    ratio = (envelope[:,8:]**2).sum(axis=1)
    breath = region & (periodic<.55) & (ratio>max(.15,3*np.median(ratio[core]))) & exposed
    pulses = runs(breath)
    if len(pulses)!=1:
        return None
    pulse = pulses[0]
    crest = pulse[np.argmax(lead[pulse])]
    rising, falling = lead[pulse[0]:crest+1], lead[crest:pulse[-1]+1]
    if np.any(np.maximum.accumulate(rising)-rising>6) or np.any(falling-np.minimum.accumulate(falling)>6):
        return None
    linked = (clock>=vowel_exit) & (clock<clock[crest])
    if linked.any() and np.min(level[linked])<level[crest]-6:
        return None
    quiet = runs(region & (clock>max(clock[pulse[-1]],vowel_exit)) & (lead<=lead[crest]-15) & (vocal<=vocal[crest]-15))
    if len(quiet)!=1 or np.any(breath[quiet[0][0]:]):
        return None
    return int(round(clock[quiet[0][0]]))


def replay(target, trace):
    guide = target.get('phraseFinalCoda')
    if trace['wordId']!=target['wordId'] or trace['guide']!=guide:
        raise ValueError('Coda measurement belongs to another occurrence')
    stop = target['following']['startMs']
    edges = [_landmark(runs,guide['hint'],trace['vowel'],None) for runs in trace['contexts']]
    if len(edges)!=2 or None in edges or abs(edges[0][1]-edges[1][1])>AGREEMENT_MS:
        return None
    ends = [_aspirate_end(trace['features'],edge,stop) for edge in edges]
    if None not in ends and max(ends)-min(ends)<=AGREEMENT_MS and target['endMs']<ends[0]<stop:
        return ends[0]
    return None


def _observe(context, targets):
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _classifier, _read_role
    from stemwise_engine.vocal_release_transition import extract
    roles = ('lead','vocal','choir','backing')
    if any(sha256_file(Path(context[r]))!=context[r+'Sha256'] for r in roles):
        raise ValueError('Coda measurement audio changed')
    classify = _classifier(Path(context['project']))
    results = {}
    for target in targets:
        guide = target['phraseFinalCoda']
        pron = _pronunciation(target['spoken'],'ar')
        if len(pron)<2 or pron[-1]!='h' or pron[-2] not in {'aː','uː','iː'}:
            continue
        hint = guide['hint']//10*10
        start = max(0,(hint//1000-4)*1000)
        end = min((hint//1000+3)*1000,context['durationMs']//10*10)
        if end-start<3000:
            continue
        lead = _read_role(context['lead'],start,end,context['durationMs'])
        contexts = [classify(lead,start),classify(lead[44100:-44100],start+1000)]
        left,right = max(start,hint-800),min(end,hint+500)
        pcm = {role:resample_poly(_read_role(context[role],left,right,context['durationMs']),160,441)
               for role in roles}
        trace = {'wordId':target['wordId'],'guide':guide,'vowel':pron[-2],'contexts':contexts,
                 'features':{role:extract(pcm[role],pcm['backing'],16000,left) for role in roles}}
        if replay(target,trace) is not None:
            results[target['wordId']] = trace
    if any(sha256_file(Path(context[r]))!=context[r+'Sha256'] for r in roles):
        raise ValueError('Coda measurement audio changed')
    return results


def observe(context, targets):
    wanted = [t for t in targets if 'phraseFinalCoda' in t]
    project = Path(context['project'])
    with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
        proc = run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
             input=json.dumps([context,wanted]),capture_output=True,text=True,encoding='utf-8',
             timeout=context['timeoutSeconds'])
    require_worker_success(proc,code='phrase-final-coda-failed',
                           diagnostic_path=Path(context['cacheDir'])/'phrase-final-coda.failure.private.txt')
    return parse_worker_json(proc.stdout)


if __name__=='__main__':
    import sys
    print(json.dumps(_observe(*json.load(sys.stdin))))
