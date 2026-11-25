"""Numerical adapters for private lyric recovery using existing local weights."""
from contextlib import redirect_stdout
from functools import lru_cache
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import subprocess
import sys

from stemwise_engine.common import (WorkerRetirementError, heavy_worker_stage,
    raise_with_cleanup_failure, run_process_tree, sha256_file)
from stemwise_engine.local_lyric_recovery import RATE, LocalEvidenceUnavailable
from stemwise_engine.runtime_environment import environment_python

WHISPER_REVISION = '06f233fe06e710322aca913c1bc4249a0d71fce1'
SCICOM_REVISION = '618763b1ed3ae17cb9a2062d6d4d0de436828c7a'
_MODELS = {
    'score': ('private-fusealign-whisper-large-v3', WHISPER_REVISION, {
        'model.safetensors': 'a8e94b85976e5864ba3e9525c7e6c83b2a1eca42d4b797a0c7c24d778e40fd95',
        'added_tokens.json': '3c51f66c4c21f9e126970078f11ae77a78c74aee8df606ee9daba86e467108e0',
        'config.json': 'ad0e8d1e46f4d01f7861a21509e5d0f977d6cc1f367a370603c92541d819807b',
        'generation_config.json': 'fbdfa70135de9b1d31553393f14e80aaeb1936ea36576b2ba864055943c09d23',
        'merges.txt': '2df2990a395e35e8dfbc7511e08c12d56018d8d04691e0133e5d63b21e154dc6',
        'normalizer.json': 'bf1c507dc8724ca9cf9903640dacfb69dae2f00edee4f21ceba106a7392f26dd',
        'preprocessor_config.json': '7ccc62c6f2765af1f3b46c00c9b5894426835a05021c8b9c01eecb6dfb542711',
        'special_tokens_map.json': '1c70773c078cb2ca96e0fcff113102f1d3e2b1504272c3bb63b035d4a6700d87',
        'tokenizer_config.json': '844b642c73a91359722f47b35705f7174686df33d252695d8572cf9ac03a6389',
        'tokenizer.json': '6d8cbd7cd0d8d5815e478dac67b85a26bbe77c1f5e0c6d76d1ce2abc0e5f21ca',
        'vocab.json': 'e2aa043ef015641d363d8288e7c241c85e36a5c761fb303598e0710233344387',
    }),
    'time': ('private-scicom-boundaries', SCICOM_REVISION, {
        'align.py': '8f7a3590aa3a83dbc9cc33db3d6051b1c9b94f1212c353655c96fc841c910ee0',
        'config.json': '753ce016113a20e47bf2357015c7e743b54d95e83735840087e357364aac6f80',
        'generation_config.json': 'dfeb49b46b22a8865fa3ced126d1b9a77d51c11b333c81bb470c3be0faf43d17',
        'model.safetensors': 'bde1fae2a852251beced4bb83bde054063585245c08e8bf7f7faa9f9e66948b6',
        'preprocessor_config.json': '02051956d42fbab481cba9b17d28c2019c5e15cefde01699a0253e75a130d648',
        'tokenizer.json': '5c1bf30c9e716e1477bedef846b01be0013daecb89e9e3ef7ab89b23c178df1b',
        'tokenizer_config.json': '92ff3cb7465543df83c27ff81a6aa2e55db3ca3435157feac80afe612a128976',
    }),
}


def model_binding():
    from stemwise_engine.provenance import semantic_source_identity

    return {'models': {kind: {'revision': revision, 'files': files}
                       for kind, (_, revision, files) in _MODELS.items()},
            'producerSha256': semantic_source_identity(Path(__file__)),
            'plannerSha256': semantic_source_identity(Path(__file__).with_name('local_lyric_recovery.py'))}


@lru_cache(maxsize=1024)
def phones(word, language):
    try:
        result = subprocess.run(['espeak-ng', '-q', '--ipa', '-v', language, '--', word],
                                encoding='utf-8', capture_output=True, timeout=10)
    except (FileNotFoundError, subprocess.TimeoutExpired) as error:
        raise LocalEvidenceUnavailable('no usable pronunciation comparison') from error
    if result.returncode or not result.stdout.strip():
        raise LocalEvidenceUnavailable('no usable pronunciation comparison')
    return result.stdout.strip()


def verify_view_in_main(project, audio, view, identity, view_sha, diagnostic_path, timeout):
    from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success

    result = run_process_tree([str(environment_python('main', project)), '-B', '-m',
        'stemwise_engine.local_recovery_models', '--view'], input=json.dumps({
            'original': str(audio), 'view': str(view), 'originalSha256': identity['sha256'],
            'viewSha256': view_sha, 'stream': identity['stream']}),
        capture_output=True, text=True, encoding='utf-8', timeout=timeout)
    require_worker_success(result, code='local-lyric-view-verification-failed', diagnostic_path=diagnostic_path)
    return parse_worker_json(result.stdout)


class LocalModels:
    def __init__(self, project, path, recording_sha, view, *, timeout, diagnostic_path=None):
        self.project, self.path, self.recording_sha = project, path, recording_sha
        self.view, self.timeout = view, timeout
        self.diagnostic_path = diagnostic_path
        self.observations = []

    def _invoke(self, request):
        from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success

        folder, revision, files = _MODELS[request['kind']]
        directory = self.project.parent/'model-cache'/folder/revision
        if not all((directory/name).is_file() for name in files):
            raise LocalEvidenceUnavailable('pinned private acoustic assets unavailable')
        env = {**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
               'TOKENIZERS_PARALLELISM': 'false', 'PYTHONDONTWRITEBYTECODE': '1'}
        try:
            with heavy_worker_stage({'projectDir': str(self.project)}, wait_seconds=0):
                result = run_process_tree([str(environment_python('main', self.project)), '-B',
                    '-m', 'stemwise_engine.local_recovery_models', str(directory)], env=env,
                    input=json.dumps(request), capture_output=True, text=True, encoding='utf-8',
                    timeout=self.timeout*len(request.get('locations',[request])))
        except subprocess.TimeoutExpired as error:
            raise LocalEvidenceUnavailable('private acoustic request expired') from error
        require_worker_success(result, code='local-lyric-recovery-worker-failed', diagnostic_path=self.diagnostic_path)
        return parse_worker_json(result.stdout)

    def request(self, kind, audio_sha, bounds, language, text):
        if audio_sha != self.recording_sha or language not in {'en', 'tr', 'ar'}:
            raise ValueError('private acoustic request owner differs')
        return {'kind': kind, 'recordingSha256': audio_sha, 'audio': str(self.path),
                   'viewSha256': self.view['sha256'], 'frameCount': self.view['frameCount'],
                   'bounds': list(bounds), 'language': language, 'text': list(text)}
    def record(self, request, digest, observation):
        if observation.get('request') != request:
            raise ValueError('private acoustic result request differs')
        if observation.get('cropPcmSha256') != digest:
            raise ValueError('private acoustic result crop identity differs')
        observation['request'] = {k: v for k, v in request.items() if k != 'audio'}
        self.observations.append(observation)
        return (LocalEvidenceUnavailable(observation['unavailable']) if 'unavailable' in observation
                else observation['result'])

    def call(self, kind, audio_sha, bounds, language, text):
        request = self.request(kind,audio_sha,bounds,language,text)
        digest = crop_pcm_sha(request)
        result = self.record(request,digest,self._invoke(request))
        if isinstance(result,LocalEvidenceUnavailable):
            raise result
        return result

    def verify_locations(self,audio_sha,windows,language,options):
        """One admitted worker and one Whisper load per set of competing physical locations."""
        texts=[' '.join(phrase) for phrase in options]
        requests=[self.request('score',audio_sha,bounds,language,texts) for bounds in windows]
        if not requests:
            return []
        if len(requests)==1:
            return [self.verify(audio_sha,windows[0],language,(None,None),options)]
        digests=[crop_pcm_sha(request) for request in requests]
        result=self._invoke({'kind':'score','locations':requests})
        scores=[]
        for request,digest,observation in zip(requests,digests,result['locations'],strict=True):
            values=self.record(request,digest,observation)
            scores.append(values if isinstance(values,LocalEvidenceUnavailable) else dict(zip(options,values,strict=True)))
        return scores

    def verify(self, audio_sha, bounds, language, flanks, options):
        if None in flanks:
            alternatives = tuple(' '.join(w for w in (flanks[0], *phrase, flanks[1]) if w is not None)
                                 for phrase in options)
        else:
            alternatives = tuple(' '.join((flanks[0], *phrase, flanks[1])) for phrase in options)
        scores = self.call('score', audio_sha, bounds, language, alternatives)
        return dict(zip(options, scores, strict=True))

    def time(self, audio_sha, bounds, language, words):
        times = self.call('time', audio_sha, bounds, language, words)
        if times is None:
            return None
        def interval(raw):
            start, end = raw
            if not all(type(t) in (int, float) and math.isfinite(t) for t in raw):
                raise ValueError('nonfinite private timer output')
            duration = (bounds[1]-bounds[0])/RATE
            if not 0 < start < min(end, duration):
                return None
            return (bounds[0]+round(start*RATE),
                    bounds[0]+round(end*RATE) if end < duration else None)
        return [interval(pair) for pair in times]


def _load_score(folder):
    import torch
    from transformers import WhisperProcessor, WhisperForConditionalGeneration

    proc = WhisperProcessor.from_pretrained(folder, local_files_only=True)
    model = WhisperForConditionalGeneration.from_pretrained(folder, local_files_only=True,
        dtype=torch.float16, attn_implementation='sdpa').to('cuda').eval()
    return proc,model


def _score(pcm, lang, texts, loaded):
    import torch
    proc,model=loaded
    tok = proc.tokenizer
    prompt = [model.config.decoder_start_token_id] + [tok.convert_tokens_to_ids('<|'+s+'|>')
              for s in (lang, 'transcribe', 'notimestamps')]
    if any(i == tok.unk_token_id for i in prompt):
        raise LocalEvidenceUnavailable('unsupported verifier language')
    features = proc.feature_extractor(pcm, sampling_rate=RATE, return_tensors='pt').input_features
    memory = model.model.encoder(input_features=features.to('cuda', torch.float16)).last_hidden_state
    values = []
    for text in texts:
        # On a flankless crop the empty reading means termination at the
        # transcription prompt. A space would instead score a printable token.
        ids = tok(' '+text, add_special_tokens=False).input_ids if text else [tok.eos_token_id]
        if not ids or len(ids)+len(prompt) > model.config.max_target_positions:
            raise LocalEvidenceUnavailable('verifier target exceeds model context')
        labels = torch.tensor([ids], device='cuda')
        decoder_ids = torch.tensor([prompt+ids[:-1]], device='cuda')
        state = model.model.decoder(input_ids=decoder_ids, encoder_hidden_states=memory,
                                    use_cache=False).last_hidden_state
        probs = model.proj_out(state[:,len(prompt)-1:]).float().log_softmax(-1)
        values.append(float(probs.gather(-1, labels.unsqueeze(-1)).mean().item()))
    return values


def _time(pcm, lang, words, folder):
    import torch

    spec = importlib.util.spec_from_file_location('private_scicom_release', folder/'align.py')
    official = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(official)
    aligner = official.ForcedAligner(str(folder), device='cuda')
    if aligner._lang(lang) is None or len(aligner._sequence(words, lang)[0]) > official.MAX_DEC:
        raise LocalEvidenceUnavailable('timer target exceeds language or decoder context')
    if (aligner.order, aligner.radius, aligner.dtype) != ('post', 2, torch.bfloat16):
        raise ValueError('unexpected private timer configuration')
    raw = []
    native_times = aligner._times
    def capture(logits):
        value = native_times(logits)
        raw.append(value.copy())
        return value
    aligner._times = capture
    exported = aligner.align(pcm, words, language=lang)
    if isinstance(exported, Exception):
        raise exported
    if [item['word'] for item in exported] != words or len(raw) != 1 or raw[0].shape != (2*len(words),):
        raise ValueError('private timer changed word ownership')
    return raw[0].reshape(-1, 2).tolist()


def _read_crop(request):
    import numpy as np
    import soundfile as sf
    audio = Path(request['audio'])
    if sha256_file(audio) != request['viewSha256']:
        raise ValueError('decoded view changed before private recovery')
    a, b = request['bounds']
    with sf.SoundFile(audio) as signal:
        if ((signal.samplerate, signal.channels, len(signal)) != (RATE, 1, request['frameCount'])
                or any(type(n) is not int for n in (a,b))
                or not 0 <= a < b <= len(signal) or b-a > 30*RATE
                or request['language'] not in {'en', 'tr', 'ar'}):
            raise ValueError('invalid private acoustic crop')
        signal.seek(a)
        pcm = signal.read(b-a, dtype='float32')
    if len(pcm) != b-a or not np.isfinite(pcm).all() or sha256_file(audio) != request['viewSha256']:
        raise ValueError('decoded crop changed during private recovery')
    return pcm, hashlib.sha256(pcm.astype('<f4', copy=False).tobytes()).hexdigest()


def crop_pcm_sha(request):
    return _read_crop(request)[1]


def main():
    request = json.load(sys.stdin)
    if sys.argv[1] == '--view':
        from stemwise_engine.local_recovery_evidence import verify_view

        references = [(Path(request[key]), request[key+'Sha256']) for key in ('original', 'view')]
        if any(sha256_file(path) != digest for path, digest in references):
            raise ValueError('local lyric recovery view input changed')
        output = verify_view(references[0][0], references[1][0], request['stream'])
        if any(sha256_file(path) != digest for path, digest in references):
            raise ValueError('local lyric recovery view input changed')
        print(json.dumps(output, allow_nan=False))
        return 0

    import torch
    from importlib.metadata import version
    from stemwise_engine.ctc_worker import retire_model

    folder = Path(sys.argv[1])
    _, revision, files = _MODELS[request['kind']]
    actual_files = {p.name for p in folder.iterdir() if p.is_file()}
    if (folder.name != revision or actual_files-{'README.md', '.gitattributes'} != set(files)
            or any(sha256_file(folder/name) != digest for name, digest in files.items())):
        raise ValueError('pinned private acoustic assets changed')
    locations=request.get('locations')
    if locations is not None and (request['kind']!='score' or not locations or
            any(item['kind']!='score' or item['language']!=locations[0]['language'] or
                item['text']!=locations[0]['text'] or item['audio']!=locations[0]['audio'] or
                item['viewSha256']!=locations[0]['viewSha256'] or item['recordingSha256']!=locations[0]['recordingSha256']
                for item in locations)):
        raise ValueError('private location batch belongs to different inputs')
    runtime={'device':torch.cuda.get_device_name(0),'cuda':torch.version.cuda,
             'packages':{name:version(name) for name in ('torch','numpy','transformers','soundfile')}}
    torch.set_num_threads(3)
    pending = None
    loaded=None
    outputs=[]
    try:
        with torch.inference_mode(), redirect_stdout(sys.stderr):
            if request['kind']=='score':
                loaded=_load_score(folder)
            for item in (locations if locations is not None else [request]):
                pcm,digest=_read_crop(item)
                output={'request':item,'cropPcmSha256':digest,'runtime':runtime}
                try:
                    output['result']=(_score(pcm,item['language'],item['text'],loaded) if loaded is not None else
                                     _time(pcm,item['language'],item['text'],folder))
                except LocalEvidenceUnavailable as error:
                    output['unavailable']=str(error)
                outputs.append(output)
    except BaseException as error:
        pending = error
        raise
    finally:
        del loaded
        try:
            retire_model({'cuda_model': True})
        except Exception as error:
            failure = WorkerRetirementError('private local lyric recovery model retirement unconfirmed')
            failure.__cause__ = error
            raise_with_cleanup_failure(pending, failure)
    print(json.dumps({'locations':outputs} if locations is not None else outputs[0], ensure_ascii=False, allow_nan=False))
    return 0


if __name__ == '__main__':
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
