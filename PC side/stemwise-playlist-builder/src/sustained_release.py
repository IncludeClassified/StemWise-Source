"""Recover a held terminal vowel from the recording when token exits agree too early."""
import json
from pathlib import Path

from stemwise_engine.common import atomic_write_json, read_bounded_json, run_process_tree, sha256_file
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.endpoint_vowel_release import MODEL, MODEL_DIR, VOCAB, LEXICON, VOWELS
from stemwise_engine.timing_estimate_policy import LIMIT_MS
from stemwise_engine.vocal_span_estimate import _transitions
from stemwise_engine.vocal_release_transition import _read


PUBLISHED_RELEASE_PRODUCERS = (
    '780e0c6d47443d2936ea88bb1aae87e4643d900a02a6cbff5608bece3d44e1e8',
    'd3c512e2a2c41f70a847a8b06b194764911ff469c33f7eb88d6ee4ee5898405d',
)


def _connected_vowel_exit(acoustic, frames, end, fence, vowel_coverage):
    import numpy as np

    times, level, periodic, contrast, envelope = acoustic
    articulation = {'l', 'm', 'n', 'r', 'w', 'y', 'b', 'd', 'g', 'p', 't', 'k', 's', 'z', 'sh', 'zh'}
    first = None
    edges = {}
    for a, b, phone in frames:
        if first is not None and a > first+90:
            break
        if phone not in articulation or b-a < 30 or not end+100 < a < fence-100:
            continue
        movement = sum(max(0, min(a+200, y)-max(a, x)) for x, y, p in frames if p in articulation)
        if movement < 70 or vowel_coverage(a) < .8:
            continue
        bridge = (times >= end-40) & (times <= a-40)
        if (not bridge.any() or np.mean(contrast[bridge] >= .32) < .85
                or any(p == 'SP' and min(y, a)-max(x, end) >= 70 for x, y, p in frames)):
            return None
        if first is None:
            first = a
        candidates = _transitions(*acoustic, a, entrance=False)
        for t, strength in candidates.items():
            before = (times >= t-100) & (times <= t-25)
            after = (times >= t+25) & (times <= t+90)
            if (max(a-45, end+100) <= t <= min(first+95, fence-80) and strength >= 1.5
                    and min(before.sum(), after.sum()) >= 4
                    and np.median(periodic[before]) >= .36
                    and np.linalg.norm(np.median(envelope[before], axis=0)
                                       - np.median(envelope[after], axis=0)) >= .28):
                edges[t] = max(strength, edges.get(t, 0))
    return max(edges, key=edges.get, default=None)


def _edge(features, frames, start, end, guide_end, fence, *, vowel_only=False):
    import numpy as np

    acoustic = _read(features)
    times, level, periodic, contrast, _ = acoustic
    core = (times >= max(start,end-80)) & (times <= end+60)
    if core.sum()<3 or np.median(contrast[core])<.28:
        return None

    def vowel_coverage(right):
        left = max(start,end-40)
        return sum(max(0,min(right,b)-max(left,a)) for a,b,ph in frames if ph in VOWELS)/(right-left)

    if vowel_only:
        connected = _connected_vowel_exit(acoustic, frames, end, fence, vowel_coverage)
        if connected is not None:
            return connected

    def measure(hint, low, high, *, rest):
        before = (times >= hint-115) & (times <= hint-25)
        after = (times >= hint+20) & (times <= hint+90)
        if min(before.sum(),after.sum())<4 or np.median(contrast[before])<.32 or np.median(periodic[before])<.39:
            return None
        if rest and np.median(level[before])-np.median(level[after])<6:
            return None
        candidates = _transitions(*acoustic,hint,entrance=False)
        return max((e for e,s in candidates.items() if low<=e<=high and s>=(2.4 if rest else 1.25)),
                   key=candidates.get,default=None)

    for i,(a,b,ph) in enumerate(frames):
        if ph!='SP' or b-a<90 or not end+100<a<fence or vowel_coverage(a)<.67:
            continue
        if any(y-x>=140 and y>end+70 and x>max(start,end-100) and p not in VOWELS|{'hh'}
               for x,y,p in frames[:i]):
            continue
        edge=measure(a,end+100,min(fence,a+65),rest=True)
        if edge is not None:
            return edge
    # A connected handoff needs a local vocal boundary. The following word
    # limits where we search, including when its attack sits against the release.
    if end+125<guide_end<=fence+100 and vowel_coverage(guide_end)>=.65:
        return measure(guide_end,max(end+100,guide_end-65),min(fence,guide_end+65),rest=False)
    return None


def measured_end(target, trace, *, vowel_only=False):
    a,b=target['interval']
    fence=target['fence']
    if fence-b<140:
        return None
    edges={role:_edge(features,trace['frames'][role],a,b,target['guideEnd'],fence,vowel_only=vowel_only)
           for role,features in trace['features'].items()}
    all_vocal=edges.get('vocal')
    if all_vocal is None:
        return None
    # The two views share source samples. Their consistency guards the singer's
    # attribution and leaves the boundary an estimate.
    if any(edge is not None and abs(edge-all_vocal)<=85 for role,edge in edges.items() if role!='vocal'):
        return all_vocal
    return None


def _terminal_breath_end(target, trace):
    roles = ('vocal', 'lead')
    if any(role not in trace['features'] for role in roles):
        return None
    release = measured_end(target, {key: {role: trace[key][role] for role in roles}
                                    for key in ('features', 'frames')})
    if release is None or abs(release - target['guideEnd']) > 65:
        return None
    for role in roles:
        for left, right, phone in trace['frames'][role]:
            after_release = min(right, target['fence']) - max(left, release)
            if phone == 'SP' and after_release >= 90:
                break
            if phone == 'hh' and after_release >= 20:
                return None
    return release


def _release_into_rest(features, phones, interval, hint, fence):
    import numpy as np

    time, level, periodic, _, _ = _read(features)
    start, old_end = interval
    candidates = []
    for left, right, phone in phones:
        if (phone != 'SP' or min(right, fence) - left < 200 or abs(left - hint) > 100
                or left <= old_end + 100):
            continue
        if any(label == 'SP' and min(b, left) - max(a, start) >= 90
               for a, b, label in phones if b <= left):
            continue
        core_start, core_end = left - 300, left - 100
        vowel_ms = sum(max(0, min(core_end, b) - max(core_start, a))
                       for a, b, label in phones if label in VOWELS)
        core = (time >= core_start) & (time < core_end)
        if vowel_ms < 120 or core.sum() < 12 or np.median(periodic[core]) < .55:
            continue
        floor = np.median(level[core]) - 18
        steady_rest = level[(time >= left + 20) & (time <= left + 150)]
        if len(steady_rest) < 10 or np.median(steady_rest) > floor:
            continue
        for k in np.flatnonzero((time >= left - 80) & (time <= left + 70)):
            if k + 5 <= len(time) and level[k] <= floor and np.median(level[k:k+5]) <= floor:
                candidates.append(round(time[k]))
                break
    return candidates[0] if len(candidates) == 1 else None


def _masked_breath_end(target, trace):
    # The same owned vocal must fade in both isolated views while backing may persist.
    ends = [_release_into_rest(trace['features'][role], trace['frames'][role],
                              target['interval'], target['guideEnd'], target['fence'])
            for role in ('lead', 'vocal') if role in trace['features']]
    if len(ends) == 2 and None not in ends and abs(ends[0] - ends[1]) <= 65:
        return max(ends)
    return None


def _targets(track, lines, admission, original, folder, audio, protected, equivalent, *, allow_masked=True):
    from stemwise_engine import spoken_targets
    from stemwise_engine.alignment_endpoints import _component_phonemizer_languages
    from stemwise_engine.arabic_coda_release import _base
    from stemwise_engine.lyrics_tokenization import display_words
    from stemwise_engine.recording_repeat_words import _tight

    indices=admission.source_physical_indices
    plan=spoken_targets.load(folder.parents[2]/'jobs'/folder.name,folder.name,original,indices,audio['sha256'])
    if (plan.reference if plan else None)!=admission.spoken_targets:
        raise ValueError('Sustained release pronunciation changed')
    display=[display_words(l['text']) for l in lines]
    languages=_component_phonemizer_languages({**original,'_spokenTargets':plan},display)
    if not languages:
        return []
    spoken=plan.word_lines(indices) if plan else [[w[2] for w in l] for l in display]
    words=[]
    for i,(line,guide) in enumerate(zip(track['lines'],lines,strict=True)):
        for k,(word,g) in enumerate(zip(line['words'],guide['words'],strict=True)):
            words.append(((i,k),word['timing'],g,spoken[i][k],languages[i][k].split('-')[0]))
    beat_choices={(r['line']-1,r['wordIndex']-1) for r in (equivalent or {}).get('words',())
                  if r.get('reason')=='local-instrumental-attack'}
    requests=[]
    for k,(position,t,g,reading,language) in enumerate(words):
        a,b=t['startMs'],t['endMs']
        stop=words[k+1][1]['startMs'] if k+1<len(words) else audio['durationMs']
        line, index = position
        crops = g.get('vocalCropRangeMs') or []
        masked_breath = (allow_masked and language == 'ar' and _base(reading).endswith('ه')
                         and index == len(lines[line]['words'])-1
                         and _tight(g, 0) and not _tight(g, 1)
                         and len(crops) == 2 and crops[1] and max(abs(t-g['endMs']) for t in crops[1]) <= 100)
        if masked_breath:
            stop = min(stop, g['endMs'] + 650)
        if (position in protected or position in beat_choices or t['status']!='estimated'
                or language not in {'en','ar','tr','ur'}
                or not 0<=a<b<=stop or stop-b<140 or abs(a-g['startMs'])>300
                or stop-a>8000):
            continue
        if g['endMs']-b>150 or position[1]==0 and stop-b>=300:
            requests.append({'position':list(position),'wordId':g['wordId'],'text':reading,'language':language,
                             'interval':[a,b],'fence':stop,'guideEnd':g['endMs'],
                             **({'maskedBreath':True} if masked_breath else {})})
    return requests


def _observations(request):
    from functools import lru_cache
    import re
    from scipy.signal import resample_poly
    from stemwise_engine.arabic_coda_release import _classifier, _read_role
    from stemwise_engine.multilingual_release_observer import _pronunciation, _nucleus, VOWELS as IPA_VOWELS
    from stemwise_engine.vocal_release_transition import extract

    files=request['files']
    if any(sha256_file(Path(path))!=digest for path,digest in files.values()):
        raise ValueError('Sustained release audio changed')
    project=Path(request['project'])
    lexicon=project.parent/'model-cache'/MODEL_DIR/'ds_cmudict-07b.txt'
    if sha256_file(lexicon)!=LEXICON:
        raise ValueError('Sustained release pronunciation dictionary changed')
    english={}
    for row in lexicon.read_text(encoding='utf-8').splitlines():
        text,phones=row.split('\t',1)
        english.setdefault(re.sub(r'\(\d+\)$','',text),[]).append(phones.split())
    pronounce=lru_cache(maxsize=None)(_pronunciation)
    classify=_classifier(project)
    duration=request['durationMs']
    breath_words = set(request.get('terminalBreathWords', ()))
    result={}
    for word in request['targets']:
        language=word['language']
        vowel_only=False
        terminal_breath = False
        masked_breath = word.get('maskedBreath', False)
        if language=='en':
            pronunciations=english.get(word['text'].lower().replace('’',"'"),())
            if (not pronunciations or any(p[-1] not in VOWELS for p in pronunciations)
                    or word['guideEnd']-word['interval'][1]<=150 and any(len(p)!=1 for p in pronunciations)):
                continue
            vowel_only=all(len(p)==1 for p in pronunciations)
        else:
            form=[_nucleus(p) for p in pronounce(word['text'],language) if p]
            terminal_breath = (language == 'ar' and (masked_breath or word['wordId'] in breath_words) and len(form) >= 2
                               and form[-1] == 'h' and form[-2] in IPA_VOWELS)
            if (not form or (form[-1] not in IPA_VOWELS and not terminal_breath)
                    or (masked_breath and not terminal_breath) or word['guideEnd']-word['interval'][1]<=150):
                continue
        origin=max(0,(word['interval'][0]-420)//10*10)
        limit=min(duration//10*10,(max(word['fence'],word['guideEnd'])+450)//10*10)
        backing=resample_poly(_read_role(files['backing'][0],origin,limit,duration),160,441)
        frames={};features={}
        for role in ('vocal','lead','choir'):
            if role not in files:
                continue
            samples=_read_role(files[role][0],origin,limit,duration)
            frames[role]=classify(samples,origin)
            features[role]=extract(resample_poly(samples,160,441),backing,16000,origin)
        trace = {'frames': frames, 'features': features}
        if not terminal_breath:
            edge = measured_end(word, trace, vowel_only=vowel_only)
        elif masked_breath:
            edge = _masked_breath_end(word, trace)
        else:
            edge = _terminal_breath_end(word, trace)
        if edge is not None:
            result[word['wordId']]=edge
    if any(sha256_file(Path(path))!=digest for path,digest in files.values()):
        raise ValueError('Sustained release audio changed during measurement')
    return result


def recover(workspace,state,original,admission,audio,track,protected,equivalent=None,*,ref=None,config=None):
    from stemwise_engine import recording_repeat_words as repeat
    from stemwise_engine.common import heavy_worker_stage
    from stemwise_engine.provenance import semantic_source_identity
    from stemwise_engine.runtime_environment import environment_lease, environment_python
    from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success

    folder=workspace.job_dir(state['videoId'])
    inputs=repeat._inputs(folder,audio,original,admission.source_physical_indices)
    if not inputs or not {'lead','choir'}.intersection(inputs[2]):
        if ref:
            raise ValueError('The measured sustained-release vocal roles are unavailable')
        return None
    guide,lines,files=inputs
    targets=_targets(track,lines,admission,original,folder,audio,protected,equivalent)
    if not targets:
        if ref:
            raise ValueError('The measured sustained-release word scope changed')
        return None
    dependencies=('sustained_release','vocal_span_estimate','vocal_release_transition','arabic_coda_release')
    binding=dict(recording=audio['sha256'],durationMs=audio['durationMs'],guide=guide,mapping=state['mappingSha256'],
                 content=admission.evidence_sha256,assets=[MODEL,VOCAB,LEXICON],
                 producer=[semantic_source_identity(Path(__file__).with_name(m+'.py'),portable=True) for m in dependencies],
                 views={r:f[1] for r,f in files.items()},targets=repeat._digest(targets))
    path=folder/f'sustained-word-ends-{repeat._digest(binding)}.private.json'
    if ref and ref.get('path') != path.name:
        # A published measurement replays only its exact bound producer and inputs.
        targets = _targets(track, lines, admission, original, folder, audio, protected, equivalent, allow_masked=False)
        binding['targets'] = repeat._digest(targets)
        for producer in PUBLISHED_RELEASE_PRODUCERS:
            binding['producer'][0] = producer
            path = folder/f'sustained-word-ends-{repeat._digest(binding)}.private.json'
            if ref.get('path') == path.name:
                break
    if ref and (ref.get('path')!=path.name or not path.is_file() or sha256_file(path)!=ref.get('sha256')):
        raise ValueError('Recorded sustained-release evidence changed')
    if not path.is_file():
        if not config:
            return None
        project=Path(config['projectDir'])
        terminal_breath_words = []
        for target in targets:
            if target['language'] != 'ar':
                continue
            line, word = target['position']
            guide_words = lines[line]['words']
            if word != len(guide_words)-1 or not repeat._tight(guide_words[word], 0):
                continue
            if repeat._tight(guide_words[word], 1):
                terminal_breath_words.append(target['wordId'])
        request=dict(files=files,targets=targets,durationMs=audio['durationMs'],project=str(project),
                     terminalBreathWords=terminal_breath_words)
        with environment_lease('main',builder=project),heavy_worker_stage(config):
            proc=run_process_tree([str(environment_python('main',project)),'-B','-m',__name__],
                 input=json.dumps(request),capture_output=True,text=True,encoding='utf-8',
                 timeout=int(config['analysisTimeoutMinutes'])*60)
        require_worker_success(proc,code='sustained-release-measurement-failed',
                               diagnostic_path=folder/'sustained-release.failure.private.txt')
        atomic_write_json(path,dict(binding=binding,ends=parse_worker_json(proc.stdout)),root=folder,pretty=False)
    packet=read_bounded_json(path,max_bytes=MAX_JSON_BYTES,root=folder)
    if packet['binding']!=binding or set(packet['ends'])-{t['wordId'] for t in targets}:
        raise ValueError('A sustained release belongs to another recording or word')
    exact=dict(path=path.name,sha256=sha256_file(path))
    for target in targets:
        if target['wordId'] not in packet['ends']:
            continue
        e=packet['ends'][target['wordId']]
        a,b=target['interval']
        if type(e) is not int or not b<e<=target['fence']:
            raise ValueError('Invalid sustained vocal release boundary')
        i,k=target['position']
        line=track['lines'][i]
        line['words'][k]['timing'].update(endMs=e,basis='Estimated from the held vowel on this rendition’s isolated vocal.')
        line['timing']['endMs']=max(w['timing']['endMs'] for w in line['words'])
        protected[i,k]=(a,e,exact['sha256'])
    return exact


if __name__=='__main__':
    import sys
    print(json.dumps(_observations(json.load(sys.stdin))))
