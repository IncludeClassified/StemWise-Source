"""Private, opt-in inputs and durable span decisions for recording comparison."""
from fractions import Fraction
from functools import cache
import hashlib
from importlib.metadata import version
import math
from pathlib import Path
from subprocess import TimeoutExpired
from zipfile import BadZipFile, ZipFile

from stemwise_engine.acoustic_inputs import CURRENT_BUDGET, StreamedMonoAudio, resampled_sample_count
from stemwise_engine.common import (atomic_write_json, json_bytes, parse_strict_json_bytes,
    read_bounded_regular_bytes, sha256_file, validate_generated_path)
from stemwise_engine.local_lyric_recovery import (HOP, RATE, LocalEvidenceUnavailable,
    PriorPhrase, Stream, Token, key, owners, physical_id, plan, recover_regions)
from stemwise_engine.local_recovery_models import SCICOM_REVISION

INPUT = 'local-lyric-recovery.inputs.private.json'
MAX_JSON = 16 * 1024 * 1024
VIEW_BLOCK = 65536
WHISPER_ARCHIVE_PRODUCER = '2512fb579601bea940205abac73126ef36735fe0839d5ad0ccb09999f4ad116b'
WHISPER_WEIGHTS = '69f74147e3334731bc3a76048724833325d2ec74642fb52620eda87352e3d4f1'
CTC_WEIGHTS = '4293e75411c7fca47d553a0237fefe37694bf004253e9e4fe6fd7c84483d5b9d'
CTC_PRODUCER = 'b3ecd20b0755bc41cfdb596eac9020010bbbed4ce01d94a9fae96abb722c3251'
CTC_CONFIGS = {
    'vocab.json': '2d9fa2ffb8a0b24ddc33cd30b6a7217dcda08a0a55115d50df9cd9ee23aae6ff',
    'config.json': 'e6783eb91bda47dac26e32c7ed0abf8cbaf8d558b28fa3e85788030b76b11e55',
    'preprocessor_config.json': '8e6281aad64f97e40534135a59dcc5d33571efae376f2a25adf5551951897ab4',
}
SCICOM_MANIFEST = '1dc05949029a8b4160ffe05f696877c81760622dda4415c97a6923239046013a'


def _digest(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def _pcm_sha(pcm):
    return hashlib.sha256(pcm.astype('<f4', copy=False).tobytes()).hexdigest()


class _Inputs:
    def __init__(self, root):
        self.root = Path(root).resolve(strict=True)
        self.references = {}

    def _remember(self, path, digest):
        name = path.relative_to(self.root).as_posix()
        if self.references.setdefault(name, digest) != digest:
            raise ValueError('local lyric recovery input changed while being read')

    def json(self, path):
        raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON, root=self.root)
        self._remember(path, hashlib.sha256(raw).hexdigest())
        return parse_strict_json_bytes(raw)

    def reference(self, ref, *, document=False):
        if (not isinstance(ref, dict) or set(ref) != {'path', 'sha256'}
                or not isinstance(ref['path'], str) or Path(ref['path']).is_absolute()):
            raise ValueError('invalid local lyric recovery reference')
        path = validate_generated_path(self.root, self.root/ref['path'])
        if document:
            value = self.json(path)
        else:
            value = path
            self._remember(path, sha256_file(path))
        if self.references[path.relative_to(self.root).as_posix()] != ref['sha256']:
            raise ValueError('local lyric recovery input hash differs')
        return value

    def require_current(self):
        for name, digest in self.references.items():
            if sha256_file(validate_generated_path(self.root, self.root/name)) != digest:
                raise ValueError('local lyric recovery input changed')


def verify_view(audio, path, stream):
    import numpy as np
    import soundfile as sf
    from stemwise_engine.provenance import semantic_source_identity

    native_rate, channels, native_frames = (stream[k] for k in ('sampleRate', 'channelCount', 'frameCount'))
    frames = resampled_sample_count(native_frames, native_rate, RATE)
    with sf.SoundFile(audio) as original, sf.SoundFile(path) as decoded:
        if (original.samplerate, original.channels, len(original)) != (native_rate, channels, native_frames):
            raise ValueError('local lyric recovery Original clock differs')
        if (decoded.samplerate, decoded.channels, len(decoded)) != (RATE, 1, frames):
            raise ValueError('local lyric recovery decoded view clock differs')
        source = None if (native_rate, channels) == (RATE, 1) else StreamedMonoAudio(audio, RATE)
        pcm_hash = hashlib.sha256()
        for start in range(0, frames, VIEW_BLOCK):
            stop = min(start+VIEW_BLOCK, frames)
            actual = decoded.read(stop-start, dtype='float32')
            expected = original.read(stop-start, dtype='float32') if source is None else source.read(start, stop).numpy()[0]
            if not np.isfinite(actual).all() or not np.array_equal(expected, actual):
                raise ValueError('local lyric recovery decoded PCM differs from Original')
            pcm_hash.update(actual.astype('<f4', copy=False).tobytes())
    ratio = Fraction(RATE, native_rate)
    packages = ('numpy', 'soundfile') if source is None else ('numpy', 'soundfile', 'torch', 'torchaudio')
    return {'pcmF32leSha256': pcm_hash.hexdigest(),
        'sampleRate': RATE, 'frameCount': frames, 'channelCount': 1,
        'sourceSampleRate': native_rate, 'sourceFrameCount': native_frames,
        'sourceChannelCount': channels, 'offsetSamples': 0,
        'resamplingRatio': [ratio.numerator, ratio.denominator],
        'blockFrames': VIEW_BLOCK,
        'representation': source.representation if source is not None else 'mono',
        'producerSha256': semantic_source_identity(Path(__file__).with_name('acoustic_inputs.py')),
        'packages': {name: version(name) for name in packages}}


def _view(inputs, audio, identity, view_ref, project, diagnostic_path, timeout):
    from stemwise_engine.local_recovery_models import verify_view_in_main

    path = inputs.reference(view_ref)
    stream = identity['stream']
    if (stream['sampleRate'], stream['channelCount']) == (RATE, 1):
        view = verify_view(audio, path, stream)
    else:
        try:
            view = verify_view_in_main(project, audio, path, identity, view_ref['sha256'], diagnostic_path, timeout)
        except TimeoutExpired as error:
            raise LocalEvidenceUnavailable('private decoded view verification expired') from error
    return path, {**view, 'sha256': view_ref['sha256']}


def _whisper(inputs, ref, identity, view, video_id):
    from stemwise_engine.lyric_content import _recognized

    cache = inputs.reference(ref, document=True)
    if 'provenance' in cache:
        from stemwise_engine.lyrics_align_pc import (PINNED_WHISPER_SNAPSHOT,
            _whisper_decoder_config, _whisper_manifest_sha256)

        provenance = cache['provenance']
        decoder = provenance.get('decoder', {})
        stream = provenance.get('audio', {}).get('stream', {})
        # Native cache times can be used only when Whisper actually saw these exact bytes.
        if (cache.get('videoId') != video_id or provenance.get('modelSha256') != WHISPER_WEIGHTS
                or provenance.get('modelSnapshot') != PINNED_WHISPER_SNAPSHOT
                or provenance.get('modelManifestSha256') != _whisper_manifest_sha256()
                or provenance.get('audio', {}).get('routeId', 'mix') != 'mix'
                or provenance.get('durationMs') != identity['stream']['durationMs']
                or (stream.get('sampleRate'), stream.get('frameCount'), stream.get('channelCount'))
                    != (RATE, view['frameCount'], 1)
                or decoder != _whisper_decoder_config(precision=decoder.get('beamSize') == 10, prompted=False)):
            raise ValueError('local lyric recovery Whisper model/clock differs')
        raw = _recognized(cache, view['sha256'], candidate_only=True)
        words = tuple(Token(w['raw'].strip(), round(w['start']*RATE), round(w['end']*RATE)) for w in raw)
        identity = {'kind': 'engine-whisper', 'provenance': provenance, 'producer': cache['cacheIdentity']}
    else:
        # Exact retained unprompted producer from the verified prototype.
        if (cache['audioSha256'], cache['weights'], cache['frames'], cache['code']) != (
                view['sha256'], WHISPER_WEIGHTS, view['frameCount'], WHISPER_ARCHIVE_PRODUCER):
            raise ValueError('local lyric recovery archived Whisper model/clock differs')
        words = tuple(Token(w['word'], w['a'], w['b']) for w in cache['words'])
        identity = {'kind': 'archived-whisper', 'weightsSha256': cache['weights'],
                    'producerSha256': cache['code'], 'language': cache['language']}
    return words, identity


def _free_words(ids, vocab, frames):
    inverse = {i: char for char, i in vocab.items()}
    pieces, first, last, previous = [], 0, 0, None
    for frame, token in enumerate(ids):
        char = inverse[int(token)]
        if char == '|' and pieces:
            yield Token(''.join(pieces), first, last)
            pieces = []
        elif char not in ('|', '<blank>'):
            if not pieces:
                first = frame*HOP
            if token != previous:
                pieces.append(char)
            last = min((frame+1)*HOP, frames)
        previous = token
    if pieces:
        yield Token(''.join(pieces), first, last)


def _passage_vocals(inputs,evidence,job,identity,video_id,spec,frames):
    """Use observed words from the engine's existing stems as text alternatives only."""
    from stemwise_engine.lyric_content import _recognized
    from stemwise_engine.workflow_status import candidate_recognition_sha, read_audio_view_witness
    streams, failures = [], []
    duration = identity['stream']['durationMs']

    def add(observation, witness):
        prov = observation['provenance']
        if (observation.get('videoId') != video_id or not witness
                or prov.get('durationMs') != duration
                or prov.get('audio', {}).get('routeId') in (None, 'mix')):
            raise ValueError('missing derived source-view binding')
        sha = candidate_recognition_sha(prov, identity['sha256'], duration, evidence, witness=witness)
        words = _recognized(observation, sha, candidate_only=True)
        if not isinstance(words,list) or not words:
            raise ValueError('vocal observation has no word clock')
        tokens = tuple(Token(w['raw'].strip(),round(w['start']*RATE),round(w['end']*RATE)) for w in words)
        if any(not 0<=w.a<=w.b<=frames for w in tokens):
            raise ValueError('vocal observation outside Original clock')
        if tokens not in streams:
            streams.append(tokens)
    if 'vocalCandidate' in spec:
        candidates = [spec['vocalCandidate']]
    elif (job/'job.json').is_file():
        try:
            raw = read_bounded_regular_bytes(job/'job.json', max_bytes=MAX_JSON, root=job)
            packet = parse_strict_json_bytes(raw).get('recordingRecoveryCandidates', {})
            candidates = [v for v in packet.get('candidates', []) if v.get('videoId') == video_id
                          and v.get('audioSha256') == identity['sha256'] and v.get('audioViewWitness')]
        except (OSError, ValueError, AttributeError, TypeError) as error:
            candidates = []
            failures.append(str(error))
    else:
        candidates = []
    def artifact(ref):
        if not isinstance(ref, dict) or set(ref) != {'path', 'sha256'} or Path(ref['path']).is_absolute():
            raise ValueError('invalid derived recognition reference')
        path = validate_generated_path(evidence, evidence/ref['path'], workspace_root=inputs.root)
        return {'path': path.relative_to(inputs.root).as_posix(), 'sha256': ref['sha256']}
    for item in candidates:
        try:
            observation = inputs.reference(artifact(item['recognition']), document=True)
            ref = item['audioViewWitness']
            inputs.reference(artifact(ref))
            add(observation, read_audio_view_witness(ref, evidence))
        except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
            failures.append(str(error))
    # A time-bearing vocal cache can also predate candidate staging. Its current
    # source descriptor must pass the engine's Original and derived-view checks.
    proof = evidence/'audio-provenance.private.json'
    if 'vocalCandidate' not in spec and proof.is_file():
        for path in sorted(evidence.glob('transcript.*unprompted*private.json')):
            try:
                observation = inputs.json(path)
                if observation.get('provenance', {}).get('audio', {}).get('routeId') not in (None, 'mix'):
                    add(observation, inputs.json(proof))
            except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
                failures.append(str(error))
    return tuple(streams),failures


def _music_ctc(inputs, refs, view, *, passage=None, characters=None):
    import numpy as np

    meta = inputs.reference(refs['receipt'], document=True)
    emissions = inputs.reference(refs['emissions'])
    vocab = inputs.reference(refs['vocab'], document=True)
    frames = view['frameCount']
    if (meta['audioSha256'], meta['frames'], meta['modelSha256'], meta['codeSha256'],
            meta['emissionsSha256'], meta['configs'], refs['vocab']['sha256'], meta['convention'], meta['dtype']) != (
            view['sha256'], frames, CTC_WEIGHTS, CTC_PRODUCER, refs['emissions']['sha256'], CTC_CONFIGS,
            CTC_CONFIGS['vocab.json'], 'source bin left edge; 320 samples; zero context beyond file only; no offset authority',
            'float16 log posterior from fp32'):
        raise ValueError('local lyric recovery CTC producer, view or clock differs')
    if (len(vocab) != 921 or set(vocab.values()) != set(range(921))
            or (vocab['<blank>'], vocab['|'], vocab['<unk>']) != (0, 1, 2)):
        raise ValueError('local lyric recovery CTC vocabulary differs')
    ticks = (frames+HOP-1)//HOP
    CURRENT_BUDGET.require_recording(ticks*(921*2+24)+512*921*4)
    try:
        with ZipFile(emissions) as archive:
            sizes = {item.filename: item.file_size for item in archive.infolist()}
            expected = {'logp.npy': ticks*921*2, 'frame_samples.npy': ticks*8}
            if (len(archive.infolist()) != 2 or set(sizes) != set(expected)
                    or any(not n < sizes[name] <= n+1024 for name, n in expected.items())):
                raise ValueError('local lyric recovery CTC payload layout differs')
    except BadZipFile as error:
        raise ValueError('local lyric recovery CTC data is not an NPZ') from error
    with np.load(emissions, allow_pickle=False) as payload:
        coords, logp = payload['frame_samples'], payload['logp']
        if (coords.dtype != np.dtype('<i8') or logp.dtype != np.dtype('<f2')
                or logp.shape != (ticks, 921) or not np.array_equal(coords, np.arange(0, frames, HOP))):
            raise ValueError('local lyric recovery CTC frame data differs')
        for start in range(0, ticks, 512):
            block = logp[start:start+512].astype(np.float32)
            if (not np.isfinite(block).all() or (block > 0).any()
                    or np.max(np.abs(np.exp(block, out=block).sum(axis=1)-1.0)) > .003):
                raise ValueError('local lyric recovery CTC log posterior differs')
        if passage is not None:
            from stemwise_engine.local_passage_recovery import alternate_spellings
            passage.alternatives = alternate_spellings(logp,vocab)
        ids = logp.argmax(axis=1)
        if characters is not None:
            from itertools import groupby
            inverse = {value: symbol for symbol, value in vocab.items()}
            first = 0
            for token, group in groupby(ids):
                stop = first + sum(1 for _ in group)
                symbol = inverse[int(token)]
                if symbol not in ('<blank>', '|'):
                    characters.append((symbol, first*HOP, min(stop*HOP,frames)))
                first = stop
        return tuple(_free_words(ids, vocab, frames)), meta


def _prior(inputs, spec, audio, view, path):
    """Replay an Original-view baseline only after verifying the native run and crop."""
    import soundfile as sf

    result_path = validate_generated_path(inputs.root, inputs.root/spec['result']['path'])
    result = inputs.reference(spec['result'], document=True)
    index = inputs.reference(spec['index'], document=True)
    supervisor = inputs.json(result_path.with_name('supervisor.private.json'))
    request_path = result_path.with_name('request.private.json')
    request = inputs.json(request_path)
    request_hash = inputs.references[request_path.relative_to(inputs.root).as_posix()]
    submitted = inputs.reference(request['inputs'], document=True)
    if (submitted['schemaVersion'] != 1 or submitted['sampleRate'] != RATE
            or supervisor['returncode'] != 0 or supervisor['childTreeRetired'] is not True
            or supervisor['resultSha256'] != spec['result']['sha256']
            or result['requestSha256'] != request_hash or supervisor['requestSha256'] != request_hash
            or result['planSha256'] != request['inputs']['sha256']
            or result['manifest'] != request['manifest']
            or (result['status'], result['modelRetired'], result['revision'], result['manifest']['sha256'],
                result['dtype'], result['timestampOrder'], result['timestampRadius'])
                != ('complete', True, SCICOM_REVISION, SCICOM_MANIFEST, 'bfloat16', 'post', 2)):
        raise ValueError('local lyric recovery cached Scicom run differs')
    by_id = {row['id']: row for row in result['cases']}
    cases = {row['id']: row for row in submitted['cases']}
    if (len(by_id) != len(result['cases']) or len(cases) != len(submitted['cases'])
            or set(cases) != set(by_id)):
        raise ValueError('local lyric recovery cached Scicom case set differs')
    with sf.SoundFile(path) as signal:
        for placement in index['rows']:
            if placement['track'] != spec['track'] or placement['route'] != 'baseline':
                continue
            case_id = placement['new'] or placement['old']
            row = by_id.get(case_id)
            if row is None or row['status'] != 'observed':
                continue
            offset, length = placement['offset'], row['frames']
            if (any(row[k] != v for k, v in cases[case_id].items())
                    or row['words'] != placement['words'] or row['timeOriginSample'] != 0
                    or type(offset) is not int or not 0 <= offset < offset+length <= view['frameCount']):
                raise ValueError('local lyric recovery cached Scicom occurrence differs')
            signal.seek(offset)
            if _pcm_sha(signal.read(length, dtype='float32')) != row['inputF32leSha256']:
                raise ValueError('local lyric recovery cached timer PCM belongs to another crop')
            spans, raw_spans = [], []
            for word, raw, exported in zip(row['words'], row['rawPreClamp'], row['exported'], strict=True):
                if word != raw['word'] or word != exported['word']:
                    raise ValueError('local lyric recovery cached Scicom word differs')
                start, end = raw['start'], raw['end']
                if (0 < start < end < length/RATE and
                        (round(start, 3), round(end, 3)) == (exported['start'], exported['end'])
                        and offset < (a := offset+round(exported['start']*RATE)) <
                        (b := offset+round(exported['end']*RATE)) < offset+length):
                    spans.append((a, b))
                    raw_spans.append((offset+math.floor(start*RATE), offset+math.ceil(end*RATE)))
                else:
                    spans.append(None)
                    raw_spans.append(None)
            yield PriorPhrase(audio, row['language'], tuple(row['words']), tuple(spans),
                f"{spec['result']['sha256']}#{case_id}", tuple(raw_spans))


def _locked(rows, cores, audio, frames):
    """Locks constrain private proposals only. A user-supplied lock grants no authority."""
    by_clock = {(word.a, word.b): word for word in cores}
    locked = {}
    for row in rows:
        a, b = row['core']
        start, end = row['interval']
        lang = row['language']
        core = by_clock.get((a, b))
        if (core is None or lang not in {'en','tr','ar'} or key(core.text, lang) != key(row['word'], lang)
                or any(type(n) is not int for n in (a,b,start,end))
                or not 0 <= start < end <= frames or max(start,a) >= min(end,b)
                or row['role'] not in {'supported','estimated'}):
            raise ValueError('local lyric recovery retained interval owner differs')
        owner = physical_id(audio, core, lang)
        if owner in locked:
            raise ValueError('duplicated local lyric recovery retained interval')
        locked[owner] = (start, end)
    return locked


def _location_transcript(inputs, evidence, contrary, identity, video_id, language):
    """Prefer the compared Original observation, then its existing language-specific mix cache."""
    from stemwise_engine.lyric_content import _recognized
    from stemwise_engine.lyrics_align_pc import _cache_audio_identity

    paths = (contrary, evidence/f'qwen-structure-{language}.private.json', evidence/'qwen-structure.private.json')
    for path in dict.fromkeys(paths):
        if not path.is_file():
            continue
        observation = inputs.json(path)
        prov = observation.get('provenance') if isinstance(observation,dict) else None
        if (isinstance(prov,dict) and observation.get('videoId') == video_id and 'modelRevision' in prov
                and prov.get('language') in (None,language) and prov.get('audioView', 'mix') == 'mix'
                and prov.get('audio') == _cache_audio_identity(identity)):
            words = _recognized(observation, identity['sha256'], candidate_only=True)
            if not isinstance(words,str):
                raise ValueError('passage comparison requires the independent Original transcript')
            return words.split()
    return []


def run(config, workspace, video_id, audio, identity, original, indices, draft_sha, metadata_sha,
        contrary, scope):
    """Compare never consumes these candidates as content or endpoint authority."""
    from stemwise_engine.alignment_audio import file_identity
    from stemwise_engine.lyrics_intake import read_recording_draft
    from stemwise_engine.local_recovery_models import LocalModels, phones, model_binding
    from stemwise_engine.spoken_targets import load as load_spoken_targets

    evidence = workspace.job_dir(video_id)
    request_path = evidence/INPUT
    if not request_path.exists() and not request_path.is_symlink():
        return
    inputs = _Inputs(workspace.workspace_root)
    request = inputs.json(request_path)
    expected = {'formatVersion': 1, 'videoId': video_id, 'audioSha256': identity['sha256'],
                'draftSha256': draft_sha, 'metadataSha256': metadata_sha}
    if (not isinstance(request, dict)
            or any(request.get(name) != value for name, value in expected.items())
            or type(request.get('formatVersion')) is not int
            or set(request) - {*expected, 'decodedView', 'whisper', 'musicCtc', 'prior', 'locked','passageRecovery'}):
        raise ValueError('local lyric recovery request belongs to different inputs')
    job_dir = workspace.root.parent/'jobs'/video_id
    spoken = original.get('_spokenTargets')
    if spoken is None:
        spoken = load_spoken_targets(job_dir, video_id, original, indices, identity['sha256'])
    # Compare is a pre-editorial route. When an endpoint owner already exists, defer to it.
    def require_no_endpoint_owner():
        if any(path.exists() or path.is_symlink() for path in (
                workspace.state_path(video_id), workspace.candidate_path(video_id),
                evidence/'owner-word-prediction.private.json')):
            raise LocalEvidenceUnavailable('existing endpoint state requires its owning recovery route')
    require_no_endpoint_owner()
    contrary_bytes = read_bounded_regular_bytes(contrary, max_bytes=MAX_JSON, root=evidence)
    project = Path(config['projectDir'])
    diagnostic = evidence/'local-lyric-recovery-worker.failure.private.txt'
    timeout = int(config.get('analysisTimeoutMinutes', 180))*60
    view_path, view = _view(inputs, audio, identity, request['decodedView'], project, diagnostic, timeout)
    heard, whisper = _whisper(inputs, request['whisper'], identity, view, video_id)
    passage = None
    if 'passageRecovery' in request:
        from stemwise_engine.local_passage_recovery import Passage
        spec = request['passageRecovery']
        if (not isinstance(spec,dict) or set(spec)-{'vocalCandidate','openEdges','placement','unmarkedPlacement'}
                or ({'placement','unmarkedPlacement'}.intersection(spec) and spec.get('openEdges',True) is not True)
                or type(spec.get('openEdges',True)) is not bool):
            raise ValueError('invalid private passage proposal input')
        passage = Passage(open_edges=spec.get('openEdges',True))
    characters = [] if passage is not None and request.get('prior') else None
    cores, ctc = _music_ctc(inputs, request['musicCtc'], view, passage=passage, characters=characters)
    audio_sha, frames = identity['sha256'], view['frameCount']
    locked = _locked(request.get('locked', []), cores, audio_sha, frames)
    prior = tuple(phrase for spec in request.get('prior', [])
                  for phrase in _prior(inputs, spec, audio_sha, view, view_path))
    p = plan(owners(original, indices, spoken), Stream(audio_sha, frames, 'whisper', heard),
             Stream(audio_sha, frames, 'music-ctc', cores))
    models = LocalModels(project, view_path, audio_sha, view, timeout=timeout, diagnostic_path=diagnostic)
    base_binding = {**expected, 'decodedView': view, 'recognition': {'whisper': whisper, 'musicCtc': ctc},
        'contrarySha256': hashlib.sha256(contrary_bytes).hexdigest(), 'inputs': dict(inputs.references),
        'performedSources': list(indices), 'discrepancyScope': scope, 'models': model_binding(),
        'spokenTargets': spoken.reference if spoken else None}
    binding = base_binding
    if passage is not None:
        from stemwise_engine.provenance import semantic_source_identity
        passage.vocals, vocal_failures = _passage_vocals(
            inputs, evidence, job_dir, identity, video_id, spec, frames)
        binding = {**base_binding, 'inputs': dict(inputs.references), 'passageRecovery': {
            'plannerSha256': semantic_source_identity(Path(__file__).with_name('local_passage_recovery.py')),
            'timedVocalViews': len(passage.vocals), 'vocalRefusals': vocal_failures}}
        languages = {o.language for o in p.sheet}
        if prior and characters and len(languages) == 1 and languages <= {'en','tr','ar'}:
            from stemwise_engine.local_passage_drift import competing_locations
            try:
                words = _location_transcript(inputs, evidence, contrary, identity, video_id, p.sheet[0].language)
                if words:
                    passage.drift = competing_locations(p, prior, words, characters, locked)
                    binding['passageRecovery']['driftPlannerSha256'] = semantic_source_identity(
                        Path(__file__).with_name('local_passage_drift.py'))
            except (OSError, ValueError, KeyError, TypeError) as error:
                binding['passageRecovery']['driftRefusal'] = str(error)
            binding['inputs'] = dict(inputs.references)
        if passage.open_edges:
            from stemwise_engine.local_open_passage import locator_windows
            passage.locations = {}
            for field, mode in (('placement','marked-strict'),('unmarkedPlacement','unmarked')):
                if field not in spec:
                    continue
                try:
                    extra = locator_windows(inputs.reference(spec[field],document=True),ctc,view['frameCount'],mode=mode)
                    for reading,bounds in extra.items():
                        passage.locations.setdefault(reading,set()).update(bounds)
                except (OSError,ValueError,KeyError,TypeError) as error:
                    binding['passageRecovery'][field+'Refusal'] = str(error)
                binding['inputs'] = dict(inputs.references)
            binding['passageRecovery']['openPlannerSha256'] = semantic_source_identity(
                Path(__file__).with_name('local_open_passage.py'))
    recovery_root = validate_generated_path(evidence, evidence/'local-lyric-recovery',
                                            workspace_root=workspace.workspace_root)
    namespace = validate_generated_path(recovery_root, recovery_root/_digest(binding),
                                        workspace_root=workspace.workspace_root)
    allowed_lines = ((scope.get('possiblePerformedIndices') or None)
                     if isinstance(scope, dict) and scope.get('complete') is True else None)

    def span_path(span):
        return namespace/(_digest(span)+'.private.json')

    def read_previous(path, span):
        try:
            raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON, root=evidence)
            packet = parse_strict_json_bytes(raw)
            content, timing = packet['content'], packet['timing']
            if (packet['publicationAuthority'] is not False or content['publicationAuthority'] is not False
                    or timing['authority'] != 'private-estimate'
                    or timing['locked'] != request.get('locked', [])
                    or _digest(packet['binding']) != path.parent.name
                    or any(content[k] != v for k, v in span.items())):
                return None
            parts = timing.get('wordBoundaries', ())
            return packet['binding'], parts if isinstance(parts, list) else ()
        except (OSError, ValueError, KeyError, TypeError):
            return None

    request_name = request_path.relative_to(inputs.root).as_posix()

    def without_switch_request(record):
        return {**record, 'inputs': {k: v for k, v in record['inputs'].items() if k != request_name}}

    @cache
    def prior_namespaces():
        return tuple(recovery_root.iterdir()) if recovery_root.is_dir() else ()

    def load_previous(span):
        path = span_path(span)
        exact = read_previous(path, span)
        if exact is not None:
            return exact[1]
        # A private passage switch can reuse today's independently checked starts.
        # Check recording, inputs, locks and current model/producer identity.
        if passage is None:
            return ()
        found = []
        allowed = _digest(without_switch_request(base_binding))
        for directory in prior_namespaces():
            old = read_previous(directory/path.name, span)
            if (old is not None and isinstance(old[0], dict) and isinstance(old[0].get('inputs'), dict)
                    and request_name in old[0]['inputs']
                    and _digest(without_switch_request(old[0])) == allowed):
                parts = [{k: v for k, v in part.items() if k != 'reason'}
                         for part in old[1] if isinstance(part, dict)]
                if parts and parts not in found:
                    found.append(parts)
        return found[0] if len(found) == 1 else ()

    def persist(row):
        require_no_endpoint_owner()
        if (read_recording_draft(job_dir)[1::2] != (draft_sha, metadata_sha)
                or file_identity(audio) != {k: v for k, v in identity.items() if k != 'canonicalTimeTransform'}
                or read_bounded_regular_bytes(contrary, max_bytes=MAX_JSON, root=evidence) != contrary_bytes):
            raise ValueError('local lyric recovery recording or draft changed')
        if spoken:
            spoken.require_current()
        inputs.require_current()
        span = {k: row[k] for k in ('performedLines', 'sheetRange', 'recognitionRanges')}
        content = {k: v for k, v in row.items()
                   if k not in ('additions', 'withheld', 'timingSources', 'wordBoundaries')}
        if passage and passage.drift:
            lo, hi = row['sheetRange']
            comparisons = [item for item in passage.drift if max(lo,item['sheetRange'][0]) < min(hi,item['sheetRange'][1])]
            if comparisons:
                content['competingLocations'] = comparisons
        timing = {'authority': 'private-estimate', 'additions': row['additions'],
                  'withheld': row['withheld'], 'sources': row.get('timingSources', []),
                  'locked': request.get('locked', []), 'observations': models.observations}
        if 'wordBoundaries' in row:
            timing['wordBoundaries'] = row['wordBoundaries']
        atomic_write_json(span_path(span), {'binding': binding, 'content': content, 'timing': timing,
            'publicationAuthority': False}, root=evidence, workspace_root=workspace.workspace_root)
        models.observations.clear()

    recover_regions(p, models.verify, models.time, locked=locked, phones=phones, prior=prior,
                    performed_lines=allowed_lines, persist=persist, load_previous=load_previous,passage=passage,
                    verify_locations=models.verify_locations)
