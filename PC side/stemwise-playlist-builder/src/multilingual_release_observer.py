"""Locate a word's last phone on the isolated vocal before measuring its release."""
import json
from pathlib import Path
import subprocess
import sys
import unicodedata
from itertools import groupby

from stemwise_engine.common import heavy_worker_stage, run_process_tree
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success

SNAPSHOT = 'models--facebook--wav2vec2-xlsr-53-espeak-cv-ft/snapshots/2c733782da5604684829819a5eb744c193fe9398'
ASSETS = {
    'pytorch_model.bin': '04366b6c8d24099ef313cf02f0e58d26f5dddfda16edbfc8eb2c713d94a9f551',
    'vocab.json': 'd732ab2456c0c017930001dc9af0b41b3b93d25b2eb9740bf9d925508d7d87d0',
    'config.json': '4609fb49b7e1d28aecb2840da1926c40bd915bc6f1120a940afacf7159bbfb13',
    'preprocessor_config.json': 'a2254a5b58f72cd4de3632f8eee64f3f098b7c1402128d2f419e7d00ae13e335',
}
LANGUAGES = {'ar', 'tr', 'ur'}
VOWELS = frozenset('aɑɒæɐɛeɜəɪiɨɯouʊɔœøʌøyɶ')


def _nucleus(phone):
    # A short CTC event can locate the nucleus of a long or nasal vowel. Its
    # length and nasal tail are still measured on the recording.
    sound = unicodedata.normalize('NFD', phone).replace('ː', '').replace('\u0303', '')
    # The observer has tonal variants in its output inventory. These three
    # reviewed languages do not use lexical tones. Keep diphthongs distinct.
    return sound.rstrip('12345')


def _pronunciation(text, language):
    if not text or language not in LANGUAGES:
        return []
    result = subprocess.run(['espeak-ng', '-q', '--ipa', '--sep=_', '-v', language, '--stdin'],
                            input=text, capture_output=True, text=True, check=True, timeout=10)
    return result.stdout.strip().replace('ˈ', '').replace('ˌ', '').replace(' ', '_').split('_')


def _core(target, trace):
    expected = [_nucleus(p) for p in trace['pronunciation'] if p]
    if len(expected) < 2:
        return None
    start = target['startMs']
    following = target.get('following') or {}
    window_end = following.get('startMs', trace['rightMs'])
    # Neighbor bounds limit ownership. They cannot supply a measured end.
    events = [(a, b, _nucleus(phone)) for a, b, phone in trace['events']
              if start-100 <= (a+b)//2 < window_end]
    expected = [p for p, _ in groupby(expected)]
    if len(expected) < 2:
        return None
    # Either the actual final transition is present or multiple preceding
    # phones identify its context across a single omitted CTC event.
    cores = []
    for i, (a,b,phone) in enumerate(events):
        if not i or phone != expected[-1]:
            continue
        adjacent = events[i-1][2] == expected[-2]
        omitted = (i >= 2 and len(expected) >= 4
                   and [e[2] for e in events[i-2:i]] == expected[-4:-2])
        if adjacent or omitted:
            cores.append((a,b))
    if len(cores) != 1:
        return None
    return cores[0], expected[-1], window_end


def _frication(features, a, b, right):
    """A consonant's distinctive vocal noise band must survive up to its decay."""
    import numpy as np
    from stemwise_engine.vocal_release_transition import _read, GUARD_FRAMES
    clock, level, periodic, contrast, envelope = _read(features)
    core = np.flatnonzero((clock >= a) & (clock <= b))
    if not len(core) or np.median(contrast[core]) < .4 or np.median(periodic[core]) > .65:
        return None
    shape = np.median(envelope[core], axis=0)
    shape /= max(np.linalg.norm(shape), 1.e-12)
    relative = level - np.median(level[core])
    for i in range(core[-1]+1, len(clock)-GUARD_FRAMES+1):
        if clock[i+GUARD_FRAMES-1] >= right:
            break
        if (relative[i:i+GUARD_FRAMES] < -18).all():
            return int(round(clock[i]))
        if relative[i] > -12 and (contrast[i] < .25 or periodic[i] > .7 or envelope[i] @ shape < .8):
            break  # A new excitation intervened before the putative release.
    return None


def _release(target, trace):
    from stemwise_engine import vocal_release_transition as spectral
    if (trace.get('wordId') != target['wordId'] or trace.get('spoken') != target['spoken']
            or trace.get('language') != target['language'] or target['language'] not in LANGUAGES):
        return None
    core = _core(target, trace)
    if not core:
        return None
    (a, b), phone, right = core
    hints = {target['endMs'], *(c['endMs'] for c in target['candidates'] if c['family'] != 'owsm-ctc')}
    guide = target.get('guide')
    if guide:
        hints.add(guide['endMs'])
    results = set()
    if phone in VOWELS or phone in {'m','n','ŋ','l','r','ɾ','ɹ'}:
        for hint in hints:
            if a < hint <= right:
                end = spectral.decide(trace['spectrum'], a, b, hint, following_phone_ms=right)
                if (end is not None and b <= end <= right
                        and not any(b <= onset < end and _nucleus(observed) != phone
                                    for onset, _, observed in trace['events'])):
                    results.add(end)
    elif phone in {'h','ħ','f','v','x','ɣ','s','z','ʃ','ʒ','θ','ð'}:
        end = _frication(trace['spectrum'],a,b,right)
        if end is not None and not any(b < onset < end for onset, _, _ in trace['events']):
            results.add(end)
    return min(results) if results and max(results)-min(results) <= 40 else None


def replay(target, trace):
    if (trace.get('wordId') != target['wordId'] or trace.get('spoken') != target['spoken']
            or trace.get('language') != target['language']):
        raise ValueError('The phonetic observation belongs to a different word')
    from stemwise_engine.adjacent_release_onset import onset
    start = onset(target, trace)
    end = _release(target, trace) if target.get('releaseDisputed', True) else None
    measured = {}
    if start is not None:
        measured['startMs'] = start
    if end is not None:
        measured['endMs'] = end
    return measured or None


def _observe(context, targets):
    import soundfile as sf
    import torch
    import torchaudio.functional as af
    from transformers import Wav2Vec2FeatureExtractor, Wav2Vec2ForCTC
    from stemwise_engine import vocal_release_transition as spectral
    from stemwise_engine.alignment_models import require_pinned_file
    from stemwise_engine.common import sha256_file
    model_path = Path(context['project']).parent/'model-cache'/'lyrics-alignment'/SNAPSHOT
    for filename, digest in ASSETS.items():
        require_pinned_file(model_path/filename, digest, filename)
    if any(sha256_file(Path(context[k])) != context[k+'Sha256'] for k in ('vocal','backing')):
        raise ValueError('Vocal or backing source changed')
    torch.set_num_threads(min(4, torch.get_num_threads()))
    model = Wav2Vec2ForCTC.from_pretrained(model_path, local_files_only=True).eval()
    inputs = Wav2Vec2FeatureExtractor.from_pretrained(model_path, local_files_only=True)
    labels = {v:k for k,v in json.loads((model_path/'vocab.json').read_bytes()).items()}
    observations = {}
    with sf.SoundFile(context['vocal']) as voice, sf.SoundFile(context['backing']) as backing:
        rate = voice.samplerate
        if rate != backing.samplerate or len(voice) != len(backing) or abs(1000*len(voice)/rate-context['durationMs']) > 2:
            raise ValueError('Mismatched vocal observation clocks')
        for target in targets:
            left = max(0, target['startMs']-1000)
            previous = target.get('previous')
            repeated = (previous is not None and previous['language'] == target['language']
                        and previous['spoken'] == target['spoken'] and not target.get('locked'))
            if repeated:
                left = max(0, min(left, previous['startMs']-500))
            following = target.get('following') or {}
            right = min(context['durationMs'], following.get('endMs', context['durationMs'])+500)
            # In a long instrumental gap, this fixed-context phone classifier
            # abstains rather than truncating the word or forcing a label.
            if right-left > 30000 or right-left < 400:
                continue
            clips = []
            for stream in (voice, backing):
                start, stop = round(left*rate/1000), round(right*rate/1000)
                stream.seek(start)
                mono = stream.read(stop-start, dtype='float32', always_2d=True).mean(axis=1)
                clips.append(af.resample(torch.from_numpy(mono), rate, 16000))
            with torch.inference_mode():
                data = inputs(clips[0].numpy(), sampling_rate=16000, return_tensors='pt')
                posterior = model(data.input_values).logits[0].softmax(dim=-1)
                raw_prob, raw_label = posterior.max(dim=-1)
                # Sum allophones of the same nucleus before comparing with
                # blank and every rival. This is only an acoustic core locator.
                groups = {}
                for token, phone in labels.items():
                    groups.setdefault(_nucleus(phone), []).append(token)
                names = list(groups)
                totals = torch.stack([posterior[:, ix].sum(-1) for ix in groups.values()], dim=-1)
                prob, lab = totals.max(dim=-1)
            events = []
            for i, (probability, token) in enumerate(zip(prob.tolist(), lab.tolist())):
                phone = names[token]
                if phone.startswith('<') or probability <= .5:
                    continue
                # 400-sample receptive field, 320-sample stride. Anchor to its
                # centre; the CTC exit is never used as the word boundary.
                center = left+20*i+12
                if events and events[-1][2] == phone and events[-1][1] == center-10:
                    events[-1][1] += 20
                else:
                    events.append([center-10, center+10, phone])
            lexical_events = []
            for token, entries in groupby(enumerate(zip(raw_label.tolist(), raw_prob.tolist())),
                                          key=lambda item: item[1][0]):
                if token < 4:
                    continue
                runs = list(entries)
                peak = max(pair[1] for _,pair in runs)
                if peak >= .05:
                    lexical_events.append([left+20*runs[0][0]+2, left+20*runs[-1][0]+22,
                                           labels[token], round(peak,5)])
            trace = {'wordId':target['wordId'], 'spoken':target['spoken'], 'language':target['language'],
                     'pronunciation':_pronunciation(target['spoken'], target['language']),
                     'leftMs':left, 'rightMs':right, 'events':events, 'lexicalEvents':lexical_events}
            if not repeated and not _core(target, trace):
                continue
            pcm = [c.numpy() for c in clips]
            trace['spectrum'] = spectral.extract(*pcm, 16000, left)
            if replay(target, trace) is not None:
                observations[target['wordId']] = trace
    return observations


def observe(context, targets):
    wanted = [t for t in targets if t.get('language') in LANGUAGES and t.get('spoken')]
    project = Path(context['project'])
    if not wanted or not (project.parent/'model-cache'/'lyrics-alignment'/SNAPSHOT/'pytorch_model.bin').is_file():
        return {}
    with environment_lease('main', builder=project), heavy_worker_stage({'projectDir':str(project)}):
        proc = run_process_tree([str(environment_python('main', project)), '-B','-m',
                                'stemwise_engine.multilingual_release_observer'],
            input=json.dumps([context,wanted]), text=True, encoding='utf-8', capture_output=True,
            timeout=context['timeoutSeconds'])
    require_worker_success(proc, code='multilingual-release-observation-failed',
                           diagnostic_path=Path(context['cacheDir'])/'multilingual-release.failure.private.txt')
    return parse_worker_json(proc.stdout)


if __name__ == '__main__':
    print(json.dumps(_observe(*json.load(sys.stdin))))
