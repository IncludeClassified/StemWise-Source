"""Private estimates for vowel-final words whose two timing routes disagree."""
import hashlib
from itertools import groupby
import json
from pathlib import Path
import re
import sys

from stemwise_engine.common import (atomic_write_json, heavy_worker_stage, read_bounded_regular_bytes,
                                    run_process_tree, sha256_file)
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


MODEL = '4722c13e1d5c1d740ec69c9121f5ec37a88d534aba7637bafb480208be170183'
VOCAB = '0c581e79b2feaea6c5615e1c2620cabfc7a5f07cea74df5c331e5c81210f8115'
LEXICON = '8c9301c6981039e23fbfaa3c8e09e83b2eca2bd969f2acc475ec491cbaac3de6'
MODEL_DIR = 'hubertfa/v0.0.7/48bd6dbcc293e47cc6cbcc556baf575e3d91d61d0ac88ddeccd6098f8f346fa2'
VOWELS = frozenset('aa ae ah ao aw ax ay eh er ey ih iy ow oy uh uw'.split())
FRAME_MS = 10
AGREEMENT_MS = 100


def english_track(original):
    return original.get('language') == 'en' and set(original.get('componentLanguages') or ()) <= {'en'}


def vocal_word_ends(rows):
    """Get the independent full-word estimate, bound to each performed occurrence."""
    result = {}
    for row in rows:
        candidate = row.get('familyCandidates', {}).get('mms-fa') or {}
        if candidate.get('route') == 'torchaudio-mms-fa-on-htdemucs-vocals':
            for word in candidate.get('words', ()):
                result[word['occurrenceWordId']] = (word['startMs'],word['endMs'])
    return result


def plan(words, guides, duration):
    candidates = []
    for i, word in enumerate(words):
        if not word or word['timing'] != 'estimated' or word['wordId'] not in guides:
            continue
        following = words[i+1] if i+1 < len(words) else {'startMs':duration}
        if not following or (i and not words[i-1]):
            continue
        start, end = word['startMs'], word['endMs']
        guide_start, guide_end = guides[word['wordId']]
        stop = following['startMs']
        if (0 <= start < end < stop <= duration and 0 <= guide_start < guide_end <= stop
                and guide_start >= (words[i-1]['endMs'] if i and words[i-1] else 0)
                and guide_end-end > AGREEMENT_MS and stop-end >= 2*AGREEMENT_MS):
            candidates.append({'wordId':word['wordId'],'text':word['text'],'startMs':start,'endMs':end,
                               'stopMs':stop,'guideStartMs':guide_start,'guideEndMs':guide_end})
    return candidates


def terminal_vowels(path, candidates):
    wanted = {w['text'].casefold().replace('’',"'") for w in candidates}
    endings = {w:set() for w in wanted}
    for line in path.read_text(encoding='utf-8').splitlines():
        entry, phones = line.split('\t',1)
        key = re.sub(r'\(\d+\)$','',entry)
        if key in endings:
            endings[key].add(phones.split()[-1])
    return {key:next(iter(values)) for key,values in endings.items()
            if len(values)==1 and values <= VOWELS}


def held_release(lead, backing, old, stop, vowel):
    """Walk from the estimated end. A following word only limits the search."""
    stable = AGREEMENT_MS//FRAME_MS
    rounded = ('en/ow','en/ao') if vowel=='ow' else ('en/'+vowel,)
    continuing = (*rounded,'en/ax') if vowel=='ow' else rounded
    if not 0 <= old < stop <= len(lead) or len(lead)!=len(backing) or lead[old] not in rounded:
        return None
    quiet = other = 0
    for i in range(old,stop):
        phone = lead[i]
        quiet = quiet+1 if phone=='SP' else 0
        other = other+1 if phone not in continuing else 0
        if quiet==stable:
            edge = i+1-quiet
            if (edge >= old+stable and all(p in rounded for p in lead[edge-stable:edge])
                    and all(p not in rounded for p in backing[edge-stable:edge])):
                return edge
        if other>=stable:
            break
    return None


def _runs(frames):
    return [[name,sum(1 for _ in run)] for name,run in groupby(frames.tolist())]


def _observe(request, folder, vocal, backing):
    import numpy as np
    import onnxruntime as ort
    import soundfile as sf

    if ort.__version__ != '1.23.2':
        raise ValueError('Unqualified acoustic runtime')
    files = {'model.onnx':MODEL, 'vocab.json':VOCAB,'ds_cmudict-07b.txt':LEXICON}
    if any(sha256_file(folder/name)!=digest for name,digest in files.items()):
        raise ValueError('Vowel model or pronunciation dictionary changed')
    endings = terminal_vowels(folder/'ds_cmudict-07b.txt',request['candidates'])
    vocab = json.loads((folder/'vocab.json').read_bytes())['vocab']
    classes = {k:v for k,v in vocab.items() if k=='SP' or k.startswith('en/')}
    labels,indices = np.array(list(classes)),list(classes.values())
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    options.inter_op_num_threads = 1
    session = None
    results = {}
    for path,key in ((vocal,'vocal'),(backing,'backing')):
        if sha256_file(path)!=request[key]:
            raise ValueError('Unexpected stem for vowel recovery')
    with sf.SoundFile(vocal) as lead, sf.SoundFile(backing) as accompaniment:
        if any((s.samplerate,s.channels)!=(44100,2) for s in (lead,accompaniment)) or len(lead)!=len(accompaniment):
            raise ValueError('Expected a native 44.1 kHz stereo separation')
        duration = len(lead)*1000//44100
        if abs(duration-request['durationMs'])>1:
            raise ValueError('Stem clock differs from the timed recording')
        for word in request['candidates']:
            vowel = endings.get(word['text'].casefold().replace('’',"'"))
            if vowel is None:
                continue
            origin = word['endMs']//1000*1000
            # Beyond this clock the guide prefers the old release, regardless
            # of the phones. The nearest competing word is another hard bound.
            limit = min(word['stopMs'],2*word['guideEndMs']-word['endMs']+AGREEMENT_MS)
            # Independent context placements on the same model. Crop edges can
            # censor a release, so never use the last frames as a measured exit.
            spans = [(max(0,origin-left),min(duration,origin+right)//10*10)
                     for left,right in ((5000,7000),(2700,5100))]
            if len(set(spans))!=2:
                continue
            exits = []
            traces = []
            for a,b in spans:
                stop = min(limit,duration//FRAME_MS*FRAME_MS-AGREEMENT_MS)
                if stop-word['endMs']<2*AGREEMENT_MS:
                    break
                if session is None:
                    session = ort.InferenceSession(str(folder/'model.onnx'),options,providers=['CPUExecutionProvider'])
                tracks = []
                for s in (lead,accompaniment):
                    pieces = []
                    left,right = a,b
                    while True:
                        s.seek(left*44100//1000)
                        count = (right-left)*44100//1000
                        pcm = s.read(count,dtype='float32',always_2d=True).mean(axis=1)
                        if len(pcm)!=count or not np.isfinite(pcm).all():
                            raise ValueError('Invalid stem samples')
                        logits, = session.run(['ph_frame_logits'],{'waveform':pcm[None,:]})
                        n = count//441
                        if not n <= logits.shape[-1] <= n+1 or not np.isfinite(logits).all():
                            raise ValueError('Invalid phoneme clock')
                        core = labels[logits[0,indices,:n].argmax(axis=0)]
                        # Keep 1 s of listening context on both sides of a seam.
                        start_index = 0 if not pieces else 1000//FRAME_MS
                        if right>=stop+AGREEMENT_MS:
                            pieces.append(core[start_index:])
                            break
                        pieces.append(core[start_index:-1000//FRAME_MS])
                        left = right-2000
                        right = min(duration//10*10,left+(b-a))
                    tracks.append(np.concatenate(pieces))
                edge = held_release(*tracks,(word['endMs']-a)//FRAME_MS,(stop-a)//FRAME_MS,vowel)
                if edge is None:
                    break
                exits.append(a+FRAME_MS*edge)
                first = (word['endMs']-a)//FRAME_MS
                traces.append({'startMs':a+FRAME_MS*first,'phones':[
                    _runs(track[first:edge+AGREEMENT_MS//FRAME_MS]) for track in tracks]})
            if len(exits)!=2 or max(exits)-min(exits)>AGREEMENT_MS:
                continue
            # A vocal stem includes backing singers. Even a clear vowel release
            # must beat the incumbent on the separate word-alignment evidence.
            guide = word['guideEndMs']
            if any(abs(e-guide)>=abs(word['endMs']-guide) for e in exits):
                continue
            results[word['wordId']] = {**word,'estimatedEndMs':min(exits),'cropEndsMs':exits,
                                        'finalVowel':vowel,'traces':traces}
    return results


def _key(request):
    return hashlib.sha256(json.dumps(request,sort_keys=True).encode()).hexdigest()


def _producer():
    return {'code':semantic_source_identity(Path(__file__),('terminal_vowels','held_release','_runs','_observe'),portable=True),
            'frameMs':FRAME_MS,'agreementMs':AGREEMENT_MS,'vowels':sorted(VOWELS)}


def _trace_confirms(observed, old):
    if len(observed['traces'])!=2:
        return False
    for trace, end in zip(observed['traces'],observed['cropEndsMs'],strict=True):
        start = trace['startMs']
        if not 0 <= observed['endMs']-start < FRAME_MS or not start <= old < end:
            return False
        count = (end-start+AGREEMENT_MS)//FRAME_MS
        tracks = []
        for runs in trace['phones']:
            if (any(type(n) is not int or n<1 for _,n in runs) or sum(n for _,n in runs)!=count):
                return False
            tracks.append([p for p,n in runs for _ in range(n)])
        if len(tracks)!=2:
            return False
        edge = held_release(*tracks,(old-start)//FRAME_MS,count,observed['finalVowel'])
        if edge is None or start+edge*FRAME_MS!=end:
            return False
    return True


def read_estimates(cache, ref, master, duration):
    if not isinstance(ref,dict) or set(ref)!={'path','sha256'} or not isinstance(ref['path'],str):
        raise ValueError('Invalid vowel evidence reference')
    name = ref['path']
    if not re.fullmatch(r'held-vowel-[0-9a-f]{64}\.private\.json',name):
        raise ValueError('Invalid vowel evidence reference')
    raw = read_bounded_regular_bytes(cache/name,max_bytes=16*1024*1024,root=cache)
    if hashlib.sha256(raw).hexdigest()!=ref['sha256']:
        raise ValueError('Vowel evidence changed')
    saved = parse_worker_json(raw,max_bytes=16*1024*1024)
    req = saved['request']
    if (name!=f'held-vowel-{_key(req)}.private.json' or req['recording']!=master or req['durationMs']!=duration
            or (req['model'],req['vocab'],req['lexicon'],req['producer'],req['runtime']) !=
               (MODEL,VOCAB,LEXICON,_producer(),'onnxruntime-1.23.2-CPU')):
        raise ValueError('Wrong recording or producer for vowel evidence')
    return _validate_estimates(req,saved['estimates'])


def _validate_estimates(request, estimates):
    by_id = {w['wordId']:w for w in request['candidates']}
    for word_id, observed in estimates.items():
        w = by_id[word_id]
        ends = observed['cropEndsMs']
        if (not 0<=w['startMs']<w['endMs']<w['stopMs']<=request['durationMs']
                or any(observed[k]!=v for k,v in w.items()) or len(ends)!=2 or max(ends)-min(ends)>AGREEMENT_MS
                or observed['finalVowel'] not in VOWELS or observed['estimatedEndMs']!=min(ends)
                or not all(type(e) is int and w['endMs']<e<w['stopMs'] and
                    abs(e-w['guideEndMs'])<w['guideEndMs']-w['endMs'] for e in ends)
                or not _trace_confirms(observed,w['endMs'])):
            raise ValueError('Invalid vowel estimate for its physical word')
    return estimates


def refine_estimated_track(candidate, mapping, estimates, duration):
    """Only extend an existing estimate inside the same observed physical hold."""
    original = next(t for t in candidate['tracks'] if t['kind']=='original')
    if not english_track(original):
        return False
    from stemwise_engine.lyrics_tokenization import display_words
    words = []
    for line,owner in zip(original['lines'],mapping['performedOccurrences'],strict=True):
        for word,token,(_,_,text) in zip(line['words'],owner['words'],display_words(line['text']),strict=True):
            words.append((word['timing'],token['occurrenceWordId'],text,line))
    # A different timer can locate a neighbor sooner. Recheck both flanks and
    # the independent guide on the clock of the actual playback candidate.
    current = [dict(wordId=wid,text=text,startMs=t['startMs'],endMs=t['endMs'],timing=t['status'])
               for t,wid,text,_ in words]
    guides = {wid:(r['guideStartMs'],r['guideEndMs']) for wid,r in estimates.items()}
    next_starts = {w['wordId']:w['stopMs'] for w in plan(current,guides,duration)}
    changed = False
    for timing,word_id,text,line in words:
        r = estimates.get(word_id)
        if word_id not in next_starts or r['text']!=text or line['timing']['status']!='estimated':
            continue
        old,new = timing['endMs'],r['estimatedEndMs']
        # A different incumbent can reuse only the continuous tail we actually
        # examined. Do not extend a word across an unobserved gap or another word.
        if (max(timing['startMs'],r['startMs'])>=min(old,r['guideEndMs'])
                or not r['endMs']<=old<new<=next_starts[word_id]
                or any(abs(e-r['guideEndMs'])>=abs(old-r['guideEndMs']) for e in r['cropEndsMs'])
                or not _trace_confirms(r,old)):
            continue
        timing.update(endMs=new,basis='Estimated acoustic word timing with a sustained final vowel on the isolated vocals.')
        if timing is line['words'][-1]['timing']:
            line['timing']['endMs'] = new
            line['timing']['basis'] = timing['basis']
        changed = True
    return changed


def estimate(project, cache, duration, master, pair, candidates, timeout):
    """One supervised acoustic worker per song. Cache by actual inputs and producer."""
    vocal,backing,vocal_sha = pair
    folder = project.parent/'model-cache'/MODEL_DIR
    if not candidates or not (folder/'model.onnx').is_file() or not backing.is_file():
        return {}, None
    request = {'candidates':candidates,'durationMs':duration,'recording':master,'vocal':vocal_sha,
               'backing':sha256_file(backing),'model':MODEL,'vocab':VOCAB,'lexicon':LEXICON,
               'producer':_producer(),'runtime':'onnxruntime-1.23.2-CPU'}
    target = cache/f'held-vowel-{_key(request)}.private.json'
    if target.is_file():
        saved = json.loads(target.read_bytes())
        if saved['request']!=request or sha256_file(vocal)!=vocal_sha:
            raise ValueError('Cached vowel observation differs from the recording or request')
    else:
        with environment_lease('main',builder=project), heavy_worker_stage({'projectDir':str(project)}):
            proc = run_process_tree([str(environment_python('main',project)),'-B','-m',
                'stemwise_engine.endpoint_vowel_release',str(folder),str(vocal),str(backing)],
                input=json.dumps(request),capture_output=True,text=True,encoding='utf-8',timeout=timeout)
        require_worker_success(proc,code='private-vowel-release-failed',diagnostic_path=cache/'held-vowel.failure.private.txt')
        saved = {'request':request,'estimates':_validate_estimates(request,parse_worker_json(proc.stdout))}
        atomic_write_json(target,saved)
    reference = {'path':target.name,'sha256':sha256_file(target)}
    return read_estimates(cache,reference,master,duration), reference


if __name__=='__main__':
    print(json.dumps(_observe(json.load(sys.stdin),*(Path(s) for s in sys.argv[1:]))))
