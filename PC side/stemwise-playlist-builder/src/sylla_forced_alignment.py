"""Pinned Sylla lead-head timing candidates; no content or publication authority."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import importlib.util
import json
import os
import pickle
import platform
import sys
import unicodedata
from pathlib import Path

from stemwise_engine import ctc_worker as ctc
from stemwise_engine.common import WorkerRetirementError, raise_with_cleanup_failure, source_duration_ms
from stemwise_engine.acoustic_inputs import resampled_sample_count
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.worker_protocol import WorkerReasonError, read_worker_json, run_worker_main
from stemwise_engine.worker_publication import publish_json

FORMAT_VERSION = 1
ENGINE = 'sylla-custom-ctc-timing-candidate'
NORMALIZATION_VERSION = 'experimental-targets.v2'
MODEL_SPEC = read_worker_json(Path(__file__).with_name('sylla-model.json'))
SAMPLE_RATE = 16000
DECODER = {'id': 'sylla-custom-ctc-v1', 'sampleRate': SAMPLE_RATE,
    'coreSeconds': 20, 'contextSeconds': 5, 'dtype': 'float16',
    'channels': 'arithmetic-mean', 'resampling': 'torchaudio-default',
    'frameCrop': 'proportional-integer-half-up', 'timeProjection': 'shared-rational-chunk-map',
    'trailingAudio': 'shared-competing-wildcard-at-recording-eof', 'head': 'lead'}


class TargetAdmissionError(WorkerReasonError):
    def __init__(self, records, reason='sylla-target-unsupported'):
        self.records = records
        super().__init__(reason, json.dumps({'reason': reason, 'targetAdmission': records}, ensure_ascii=False))


def target_record(original, language, vocabulary):
    """Evaluated v2 normalization; display text is retained in every record."""
    if not isinstance(original, str) or not original or any(c.isspace() for c in original):
        raise ValueError('Expected one nonempty original word')
    if not isinstance(language, str) or not language.strip():
        raise ValueError('Sylla language is malformed')
    target = unicodedata.normalize('NFKC', original)
    steps = []
    if target != original:
        steps.append('NFKC')
    if language.strip().split('-', 1)[0].lower() == 'tr':
        target = target.replace('I', 'ı').replace('İ', 'i')
        steps.append('Turkish-locale casing')
    lowered = target.lower()
    if lowered != target:
        steps.append('lowercase')
    target = lowered
    cleaned = ''.join(c for c in target if not unicodedata.category(c).startswith('P'))
    if cleaned != target:
        steps.append('punctuation removed for acoustic target only')
    target = cleaned
    unknown = [{'targetIndex': i, 'character': c, 'codepoint': f'U+{ord(c):04X}',
        'unicodeName': unicodedata.name(c, 'UNKNOWN')} for i, c in enumerate(target) if c not in vocabulary]
    unknown.extend({'targetIndex': i, 'character': c, 'codepoint': f'U+{ord(c):04X}',
        'unicodeName': unicodedata.name(c, 'UNKNOWN'), 'reason': 'reserved delimiter cannot be a lexical target'}
        for i, c in enumerate(target) if c == '|')
    return {'version': NORMALIZATION_VERSION, 'originalText': original, 'language': language,
        'targetText': target, 'normalizationSteps': steps, 'unknownCharacters': unknown,
        'admitted': bool(target) and not unknown}


def target_admission(request, language, vocabulary):
    ctc.validate_request(request)
    if not isinstance(language, str) or not language.strip():
        raise ValueError('Sylla language is malformed')
    if (not isinstance(vocabulary, dict) or vocabulary.get('<blank>') != 0 or vocabulary.get('|') != 1
            or any(not isinstance(key, str) or type(value) is not int or value < 0
                   for key, value in vocabulary.items()) or len(set(vocabulary.values())) != len(vocabulary)):
        raise ValueError('Sylla vocabulary is malformed')
    records, lines, words = [], set(), set()
    components = request.get('componentLanguages', [])
    if not isinstance(components, list) or any(not isinstance(item, str) or not item.strip() for item in components):
        raise ValueError('Sylla component languages are malformed')
    arabic_languages = {'ar', 'ara', 'arb', 'arabic'}
    arabic = any(item.strip().split('-', 1)[0].lower() in arabic_languages for item in (language, *components))
    for line in request['lines']:
        if line['lineId'] in lines:
            raise ValueError('Sylla line ID is duplicated')
        lines.add(line['lineId'])
        for word in line['words']:
            if word['wordId'] in words:
                raise ValueError('Sylla word ID is duplicated')
            words.add(word['wordId'])
            owner = word.get('acousticLanguage', language)
            if not isinstance(owner, str) or not owner.strip():
                raise ValueError('Sylla word language is malformed')
            text = word['acousticText']
            try:
                record = target_record(text, owner, vocabulary)
            except ValueError:
                record = {'version': NORMALIZATION_VERSION, 'originalText': text, 'language': owner,
                    'targetText': None, 'normalizationSteps': [], 'unknownCharacters': [],
                    'admitted': False, 'reason': 'unsupported-word-shape'}
            records.append({'lineId': line['lineId'], 'sourcePhysicalIndex': line['sourcePhysicalIndex'],
                'wordId': word['wordId'], **record})
            arabic = arabic or owner.strip().split('-', 1)[0].lower() in arabic_languages or any(
                unicodedata.category(c).startswith(('L', 'M')) and 'ARABIC' in unicodedata.name(c, '') for c in text)
    if arabic:
        raise TargetAdmissionError(records, 'sylla-arabic-route-unqualified')
    if any(not row['admitted'] for row in records):
        raise TargetAdmissionError(records)
    return records


def producer_identity():
    return {**{name: semantic_source_identity(Path(__file__).with_name(name)) for name in (
        'sylla_forced_alignment.py', 'sylla-model.json', 'ctc_worker.py', 'acoustic_inputs.py',
        'worker_publication.py', 'worker_runtime_probe.py')}, 'common.source_duration_ms': semantic_source_identity(
            Path(__file__).with_name('common.py'), ('source_duration_ms',))}


def _require_shadow_policy():
    if (MODEL_SPEC.get('enabledByDefault') is not False or MODEL_SPEC.get('automaticEnabled') is not False
            or MODEL_SPEC.get('scope') != 'private-shadow-evaluation'):
        raise ValueError('Sylla is restricted to disabled private shadow evaluation')


def _file_record(path):
    before = ctc.cache_file_identity(path)
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(4*1024*1024), b''):
            digest.update(block)
    if ctc.cache_file_identity(path) != before:
        raise WorkerReasonError('sylla-input-changed')
    return {'name': path.name, 'byteCount': before[2], 'sha256': digest.hexdigest()}


def model_identity(model_path):
    _require_shadow_policy()
    root = Path(model_path).resolve(strict=True)
    if not root.is_dir() or root.name != MODEL_SPEC['revision']:
        raise ValueError('Sylla model is not the pinned local revision')
    expected = MODEL_SPEC['files']
    if {path.name for path in root.iterdir() if path.is_file()} != {row['name'] for row in expected}:
        raise ValueError('Sylla model file set differs from the lead-head snapshot')
    files = [_file_record(root/row['name']) for row in expected]
    if files != expected:
        raise ValueError('Sylla model asset bytes differ from the pinned snapshot')
    return {'repository': MODEL_SPEC['repository'], 'revision': MODEL_SPEC['revision'],
        'snapshotSha256': ctc.canonical_sha256(files), 'files': files}


def _validate_runtime(runtime):
    from stemwise_engine.model_registry import runtime_profile
    expected = MODEL_SPEC['runtime']
    _require_shadow_policy()
    profile = runtime_profile('main')
    if (not isinstance(runtime, dict) or set(runtime) != {'runtimeVersion', 'python', 'packages',
            'implementationSha256', 'requestedDevice', 'resolvedDevice', 'cudaAvailable',
            'cudaDeviceName', 'cudaDeviceCapability', 'cudaRuntime', 'dtype', 'torchThreads',
            'deviceIndex', 'torchProducer'} or type(runtime.get('runtimeVersion')) is not int
            or runtime['runtimeVersion'] != 1
            or profile['python'] != expected['python']
            or any(profile['packages'].get(name) != version for name, version in expected['packages'].items())
            or runtime.get('python') != expected['python'] or runtime.get('packages') != expected['packages']
            or runtime.get('implementationSha256') != expected['implementationSha256']
            or runtime.get('cudaRuntime') != expected['cudaRuntime'] or runtime.get('dtype') != 'float16'
            or runtime.get('requestedDevice') not in ('auto', 'cuda') or runtime.get('resolvedDevice') != 'cuda'
            or runtime.get('cudaAvailable') is not True or not isinstance(runtime.get('cudaDeviceName'), str)
            or not runtime['cudaDeviceName'] or not isinstance(runtime.get('cudaDeviceCapability'), list)
            or len(runtime['cudaDeviceCapability']) != 2
            or any(type(item) is not int or item < 0 for item in runtime['cudaDeviceCapability'])
            or type(runtime['deviceIndex']) is not int or runtime['deviceIndex'] != 0
            or type(runtime['torchThreads']) is not int or runtime['torchThreads'] <= 0):
        raise WorkerReasonError('sylla-runtime-unqualified')
    observed = runtime['torchProducer']
    if (not isinstance(observed, dict) or set(observed) != {'device', 'torch', 'cudaDriver',
            'cudaRuntime', 'cudnn', 'uuid', 'name', 'capability', 'totalMemoryBytes'}
            or observed['device'] != 'cuda' or observed['torch'] != runtime['packages']['torch']
            or observed['cudaRuntime'] != runtime['cudaRuntime']
            or not isinstance(observed['uuid'], str) or not observed['uuid'].startswith('GPU-')
            or len(observed['uuid']) <= 4 or type(observed['cudaDriver']) is not int or observed['cudaDriver'] <= 0
            or not isinstance(observed['cudnn'], str) or not observed['cudnn'].isdecimal()
            or int(observed['cudnn']) <= 0
            or observed['name'] != runtime['cudaDeviceName']
            or observed['capability'] != runtime['cudaDeviceCapability']
            or type(observed['totalMemoryBytes']) is not int or observed['totalMemoryBytes'] <= 0):
        raise WorkerReasonError('sylla-runtime-unqualified')
    return runtime


def runtime_info(device_name='auto'):
    import torch
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    available = bool(torch.cuda.is_available())
    resolved = ('cuda' if available else 'cpu') if device_name == 'auto' else device_name
    selected = torch.cuda.current_device() if available else None
    if selected != 0:
        raise WorkerReasonError('sylla-runtime-unqualified', 'Sylla requires CUDA ordinal zero')
    observed = torch_runtime_identity()
    if torch.cuda.current_device() != selected:
        raise WorkerReasonError('sylla-runtime-unqualified', 'Sylla CUDA ordinal changed during observation')
    runtime = {'runtimeVersion': 1, 'python': platform.python_version(),
        'packages': {name: importlib.metadata.version(name) for name in MODEL_SPEC['runtime']['packages']},
        'implementationSha256': {name: _file_record(Path(importlib.util.find_spec(name).origin))['sha256']
            for name in MODEL_SPEC['runtime']['implementationSha256']},
        'requestedDevice': device_name, 'resolvedDevice': resolved, 'cudaAvailable': available,
        'cudaDeviceName': str(torch.cuda.get_device_name(0)) if available else None,
        'cudaDeviceCapability': list(torch.cuda.get_device_capability(0)) if available else None,
        'cudaRuntime': torch.version.cuda, 'dtype': 'float16', 'torchThreads': torch.get_num_threads(),
        'deviceIndex': selected, 'torchProducer': observed}
    return _validate_runtime(runtime)


def _load_model(model_path, state):
    import torch
    from transformers import SeamlessM4TFeatureExtractor, Wav2Vec2BertForCTC
    state['cuda_model'] = True
    state['model'] = Wav2Vec2BertForCTC.from_pretrained(
        str(model_path), local_files_only=True, dtype=torch.float16).to('cuda').eval()
    if {parameter.dtype for parameter in state['model'].parameters()} != {torch.float16}:
        raise WorkerReasonError('sylla-runtime-unqualified')
    extractor = SeamlessM4TFeatureExtractor.from_pretrained(str(model_path), local_files_only=True)
    if extractor.sampling_rate != SAMPLE_RATE:
        raise ValueError('Sylla feature extractor is not 16 kHz')
    return extractor


def _forward_window(samples, extractor, model):
    import torch
    inputs = extractor(samples.numpy(), sampling_rate=SAMPLE_RATE, return_tensors='pt')
    inputs = {key: value.to('cuda', dtype=torch.float16) if value.is_floating_point() else value.to('cuda')
        for key, value in inputs.items()}
    with torch.inference_mode():
        return model(**inputs).logits[0].float().log_softmax(-1).cpu()


def _emissions(audio, extractor, model, vocabulary_size):
    import torch
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET
    blocks, maps, observed, cursor = [], [], [], 0
    core, context = DECODER['coreSeconds']*SAMPLE_RATE, DECODER['contextSeconds']*SAMPLE_RATE
    for start in range(0, len(audio), core):
        end = min(len(audio), start+core)
        a, b = max(0, start-context), min(len(audio), end+context)
        retained = audio.numel()*audio.element_size()+sum(block.numel()*block.element_size() for block in blocks)
        CURRENT_BUDGET.require(retained_bytes=retained+(b-a)*4+((b-a)//160+2)*vocabulary_size*4)
        logits = _forward_window(audio[a:b], extractor, model)
        if (logits.ndim != 2 or logits.shape[1] != vocabulary_size or not len(logits)
                or not torch.isfinite(logits).all()):
            raise ValueError('Sylla emissions are malformed')
        first = ((start-a)*len(logits)+(b-a)//2)//(b-a)
        last = ((end-a)*len(logits)+(b-a)//2)//(b-a)
        kept = logits[first:last].clone()
        if not len(kept):
            raise ValueError('Sylla retained emission window is empty')
        maps.append(ctc.chunk_time_map(start, end, cursor, cursor+len(kept)))
        observed.append({'inputStartSample': a, 'inputEndSample': b, 'coreStartSample': start,
            'coreEndSample': end, 'rawEmissionFrames': len(logits),
            'retainedFirstFrame': first, 'retainedEndFrame': last})
        blocks.append(kept)
        cursor += len(kept)
    CURRENT_BUDGET.require(retained_bytes=audio.numel()*audio.element_size()+
        2*sum(block.numel()*block.element_size() for block in blocks))
    return torch.cat(blocks), maps, observed


def _decode(request, admission, vocabulary, emission, maps):
    import torchaudio
    duration, segments = ctc.validate_request(request)
    by_word = {row['wordId']: row for row in admission}
    lines = []
    for segment in segments:
        selected = request['lines'][segment['startLineIndex']:segment['endLineIndex']]
        targets, ranges = [], []
        for line in selected:
            for word in line['words']:
                if targets:
                    targets.append(vocabulary['|'])
                first = len(targets)
                targets.extend(vocabulary[c] for c in by_word[word['wordId']]['targetText'])
                ranges.append((first, len(targets)))
        emission_start, emission_end = ctc.window_to_emission_frames(
            segment['windowStartMs'], segment['windowEndMs'], duration, SAMPLE_RATE, maps)
        paths, scores = ctc.align_ctc(emission[emission_start:emission_end], targets,
            blank=vocabulary['<blank>'], allow_trailing_audio=segment['windowEndMs'] == duration)
        spans = torchaudio.functional.merge_tokens(paths[0], scores[0].exp(), blank=vocabulary['<blank>'])
        if [int(span.token) for span in spans] != targets:
            raise WorkerReasonError('sylla-target-mismatch')
        words = []
        for first, last in ranges:
            start = ctc.emission_boundary_to_target_frame(emission_start+int(spans[first].start), maps)
            end = ctc.emission_boundary_to_target_frame(emission_start+int(spans[last-1].end), maps)
            start_ms, end_ms = (start*1000+8000)//SAMPLE_RATE, (end*1000+8000)//SAMPLE_RATE
            if end == maps[-1]['targetEndFrame']:
                end_ms = ctc.project_eof_end(start_ms, end_ms, duration)
            words.append({'startMs': start_ms, 'endMs': end_ms})
        cursor, segment_lines = 0, []
        for index, line in enumerate(selected, segment['startLineIndex']):
            count = len(line['words'])
            timed = [{'wordId': word['wordId'], **timing}
                for word, timing in zip(line['words'], words[cursor:cursor+count])]
            segment_lines.append({'lineIndex': index, 'lineId': line['lineId'],
                'sourcePhysicalIndex': line['sourcePhysicalIndex'], 'startMs': timed[0]['startMs'],
                'endMs': timed[-1]['endMs'], 'words': timed})
            cursor += count
        ctc.validate_segment_boundary(segment_lines, segment, duration)
        lines.extend(segment_lines)
    ctc.validate_global_results(lines, request['lines'], duration)
    return lines


def _validate_emission_windows(windows, chunks, target_frames):
    if not isinstance(windows, list) or not isinstance(chunks, list) or not chunks or len(windows) != len(chunks):
        raise ValueError('Sylla emission windows are incomplete')
    context, core = DECODER['contextSeconds']*SAMPLE_RATE, DECODER['coreSeconds']*SAMPLE_RATE
    for window, chunk in zip(windows, chunks):
        start, end = chunk['targetStartFrame'], chunk['targetEndFrame']
        count = window.get('rawEmissionFrames') if isinstance(window, dict) else None
        if (type(start) is not int or type(end) is not int or not 0 <= start < end <= target_frames
                or type(count) is not int or count <= 0 or end != min(target_frames, start+core)):
            raise ValueError('Sylla emission window geometry changed')
        a, b = max(0, start-context), min(target_frames, end+context)
        first = ((start-a)*count+(b-a)//2)//(b-a)
        last = ((end-a)*count+(b-a)//2)//(b-a)
        if (window != {'inputStartSample': a, 'inputEndSample': b, 'coreStartSample': start,
                'coreEndSample': end, 'rawEmissionFrames': count,
                'retainedFirstFrame': first, 'retainedEndFrame': last}
                or last-first != chunk['emissionEndFrame']-chunk['emissionStartFrame']):
            raise ValueError('Sylla emission crop differs from its exact chunk map')


class _EmissionCacheMiss(Exception):
    pass


def emission_binding(audio_identity, model_record, runtime, producer):
    """Pure numerical cache identity, independent of lyric targets and request windows."""
    stream = audio_identity['stream']
    return {'audio': {'sha256': audio_identity['sha256'], 'byteCount': audio_identity['byteCount'],
        **{key: stream[key] for key in ('sampleRate', 'channelCount', 'frameCount')}},
        'model': model_record, 'runtime': runtime, 'producer': producer, 'decoder': dict(DECODER)}


def _cached_emissions(directory, binding, sample_count, vocabulary_size, produce):
    if directory is None:
        return (*produce(), None)
    import torch
    directory = Path(directory)
    key = ctc.canonical_sha256(binding)
    path = directory/f'emissions-{key}.pt'
    core = DECODER['coreSeconds']*SAMPLE_RATE
    try:
        before = ctc.cache_file_identity(path)
        saved = torch.load(path, map_location='cpu', weights_only=True, mmap=True)
        if (not isinstance(saved, dict) or set(saved) != {'key', 'sampleCount', 'emission', 'chunks',
                'chunkSamples', 'binding', 'emissionWindows'} or saved['key'] != key
                or saved['binding'] != binding or saved['sampleCount'] != sample_count
                or ctc.canonical_sha256(saved['binding']) != key
                or type(saved['sampleCount']) is not int or type(saved['chunkSamples']) is not int
                or saved['chunkSamples'] != core):
            raise ValueError('Sylla emission cache identity changed')
        _validate_emission_windows(saved['emissionWindows'], saved['chunks'], sample_count)
        def miss():
            raise _EmissionCacheMiss()
        emission, chunks, _ = ctc.cached_emissions(path, key, sample_count, vocabulary_size, miss)
    except (_EmissionCacheMiss, OSError, EOFError, pickle.UnpicklingError, RuntimeError, ValueError, KeyError, TypeError):
        pass  # Missing or incomplete optional cache proof requires fresh numerical work.
    else:
        if ctc.cache_file_identity(path) != before:
            raise WorkerReasonError('sylla-emission-cache-changed')
        return emission, chunks, saved['emissionWindows'], (path, before)
    saved = None  # Release a rejected mmap before replacing its cache file.
    emission, chunks, windows = produce()
    _validate_emission_windows(windows, chunks, sample_count)
    temporary = path.with_suffix('.tmp')
    def diagnose(error):
        from stemwise_engine.private_diagnostics import retain_error
        try:
            retain_error(directory/'emissions.failure.private.txt', error)
        except (OSError, ValueError):
            pass
    try:
        directory.mkdir(parents=True, exist_ok=True)
        torch.save({'key': key, 'sampleCount': sample_count, 'emission': emission, 'chunks': chunks,
            'chunkSamples': core, 'binding': binding, 'emissionWindows': windows}, temporary)
        os.replace(temporary, path)
    except (OSError, RuntimeError) as error:
        diagnose(error)
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError as error:
            diagnose(error)
    return emission, chunks, windows, None


def validate_result(value, request, audio_sha256, model_record, vocabulary, expected_runtime=None,
                    audio_stream=None, language=None):
    keys = {'formatVersion', 'engine', 'status', 'audioSha256', 'requestSha256', 'model', 'runtime',
        'producer', 'decoder', 'normalization', 'canonicalTimeTransform', 'targetAdmission', 'lines',
        'supportedAuthority', 'publicationAuthority', 'corroborationAuthority', 'emissionWindows'}
    if (not isinstance(value, dict) or set(value) != keys or type(value['formatVersion']) is not int
            or value['formatVersion'] != FORMAT_VERSION or value['engine'] != ENGINE or value['status'] != 'candidate'
            or value['audioSha256'] != audio_sha256 or value['requestSha256'] != ctc.canonical_sha256(request)
            or value['supportedAuthority'] is not False or value['publicationAuthority'] is not False
            or value['corroborationAuthority'] is not False
            or value['producer'] != producer_identity() or value['decoder'] != DECODER
            or value['normalization'] != {'id': NORMALIZATION_VERSION,
                'sourceSha256': producer_identity()['sylla_forced_alignment.py']}
            or value['model'] != model_record or model_record != {'repository': MODEL_SPEC['repository'],
                'revision': MODEL_SPEC['revision'], 'files': MODEL_SPEC['files'],
                'snapshotSha256': ctc.canonical_sha256(MODEL_SPEC['files'])}):
        raise ValueError('Sylla candidate producer/input identity changed')
    _validate_runtime(value['runtime'])
    if expected_runtime is not None and value['runtime'] != expected_runtime:
        raise ValueError('Sylla observed runtime differs from qualification')
    if language is None:
        raise ValueError('Sylla candidate validation requires the requested language')
    expected_admission = [{**row, 'requestLanguage': language} for row in target_admission(request, language, vocabulary)]
    if value['targetAdmission'] != expected_admission:
        raise ValueError('Sylla target admission differs from the unchanged request')
    duration, segments = ctc.validate_request(request)
    transform = value['canonicalTimeTransform']
    source_rate, frames, channels = (transform[key] for key in ('sourceSampleRate', 'sourceFrameCount', 'sourceChannelCount'))
    if (any(type(item) is not int or item <= 0 for item in (source_rate, frames, channels)) or channels not in (1, 2)
            or transform['targetSampleRate'] != SAMPLE_RATE or transform['chunkTargetFrames'] != DECODER['coreSeconds']*SAMPLE_RATE
            or source_duration_ms(frames, source_rate) != duration
            or transform['targetFrameCount'] != resampled_sample_count(frames, source_rate, SAMPLE_RATE)):
        raise ValueError('Sylla audio geometry changed')
    if audio_stream is not None and (source_rate, channels, frames) != tuple(
            audio_stream[key] for key in ('sampleRate', 'channelCount', 'frameCount')):
        raise ValueError('Sylla audio geometry differs from the actual recording')
    chunks = transform['chunks']
    target_cursor = emission_cursor = 0
    for chunk in chunks:
        if chunk != ctc.chunk_time_map(target_cursor, chunk['targetEndFrame'], emission_cursor, chunk['emissionEndFrame']):
            raise ValueError('Sylla chunk map is discontinuous')
        target_cursor, emission_cursor = chunk['targetEndFrame'], chunk['emissionEndFrame']
    if not chunks or target_cursor != transform['targetFrameCount']:
        raise ValueError('Sylla chunk map does not cover the recording')
    _validate_emission_windows(value['emissionWindows'], chunks, target_cursor)
    if transform != ctc.canonical_time_transform(source_sample_rate=source_rate, source_channel_count=channels,
            source_frame_count=frames, target_sample_rate=SAMPLE_RATE, target_frame_count=target_cursor,
            chunk_target_frames=DECODER['coreSeconds']*SAMPLE_RATE, chunks=chunks):
        raise ValueError('Sylla canonical time transform changed')
    lines = value['lines']
    ctc.validate_global_results(lines, request['lines'], duration)
    for line in lines:
        if (set(line) != {'lineIndex', 'lineId', 'sourcePhysicalIndex', 'startMs', 'endMs', 'words'}
                or line['startMs'] != line['words'][0]['startMs'] or line['endMs'] != line['words'][-1]['endMs']
                or any(set(word) != {'wordId', 'startMs', 'endMs'} for word in line['words'])):
            raise ValueError('Sylla line hull differs from its complete words')
    for segment in segments:
        ctc.validate_segment_boundary(lines[segment['startLineIndex']:segment['endLineIndex']], segment, duration)
    return value


def run(audio_path, request_path, output_path, model_path, language, device_name='auto', *, emission_cache_dir=None):
    import torch
    import torchaudio
    import soundfile as sf
    from stemwise_engine.acoustic_inputs import CURRENT_BUDGET, require_waveform_allocation
    audio_path, request_path, output_path, model_path = map(Path, (audio_path, request_path, output_path, model_path))
    if (output_path.resolve() in (audio_path.resolve(), request_path.resolve())
            or output_path.resolve().is_relative_to(model_path.resolve())):
        raise ValueError('Sylla output would overwrite an input')
    if emission_cache_dir is not None:
        emission_cache_dir = Path(emission_cache_dir).resolve()
        if (emission_cache_dir.is_relative_to(model_path.resolve())
                or emission_cache_dir in (audio_path.resolve(), request_path.resolve(), output_path.resolve())):
            raise ValueError('Sylla emission cache would overwrite an input')
    request_record = _file_record(request_path)
    request = read_worker_json(request_path)
    if _file_record(request_path) != request_record:
        raise WorkerReasonError('sylla-input-changed')
    producer = producer_identity()
    model_record = model_identity(model_path)
    vocabulary = read_worker_json(model_path/'vocab.json')
    admission = target_admission(request, language, vocabulary)
    runtime = runtime_info(device_name)
    source_record = _file_record(audio_path)
    info = sf.info(audio_path)
    sample_count = resampled_sample_count(info.frames, info.samplerate, SAMPLE_RATE)
    duration = source_duration_ms(info.frames, info.samplerate)
    if duration != request['durationMs']:
        raise ValueError('Sylla request duration differs from the actual recording')
    binding = emission_binding({'sha256': source_record['sha256'], 'byteCount': source_record['byteCount'],
        'stream': {'sampleRate': info.samplerate, 'channelCount': info.channels, 'frameCount': info.frames}},
        model_record, runtime, producer)
    if emission_cache_dir is not None:
        cache_path = emission_cache_dir/f'emissions-{ctc.canonical_sha256(binding)}.pt'
        if cache_path.resolve() in (audio_path.resolve(), request_path.resolve(), output_path.resolve()):
            raise ValueError('Sylla emission cache would overwrite an input or result')

    def check_inputs():
        if (_file_record(audio_path) != source_record or _file_record(request_path) != request_record
                or model_identity(model_path) != model_record or producer_identity() != producer):
            raise WorkerReasonError('sylla-input-changed')

    def produce():
        require_waveform_allocation(audio_path, SAMPLE_RATE, source_arrays=2, target_arrays=3, recording=True)
        weight_bytes = next(row['byteCount'] for row in model_record['files'] if row['name'] == 'model.safetensors')
        CURRENT_BUDGET.require_recording(retained_bytes=2*weight_bytes+sample_count*4+
            2*((sample_count+159)//160+2)*len(vocabulary)*4)
        raw, rate = sf.read(audio_path, dtype='float32', always_2d=True)
        if not torch.isfinite(torch.from_numpy(raw)).all():
            raise ValueError('Sylla audio is nonfinite')
        audio = torch.from_numpy(raw.mean(axis=1))
        del raw
        if rate != SAMPLE_RATE:
            audio = torchaudio.functional.resample(audio, rate, SAMPLE_RATE)
        if len(audio) != sample_count:
            raise ValueError('Sylla request duration differs from the actual recording')
        state, pending = {}, None
        try:
            extractor = _load_model(model_path, state)
            emission, maps, windows = _emissions(audio, extractor, state['model'], len(vocabulary))
            if runtime_info(device_name) != runtime:
                raise WorkerReasonError('sylla-runtime-changed')
        except BaseException as error:
            pending = error
            raise
        finally:
            try:
                ctc.retire_model(state)
            except BaseException as error:
                retirement = WorkerRetirementError('Sylla model retirement is unconfirmed')
                retirement.__cause__ = error
                raise_with_cleanup_failure(pending, retirement)
        check_inputs()
        return emission, maps, windows

    emission, maps, windows, cached = _cached_emissions(emission_cache_dir, binding, sample_count, len(vocabulary), produce)
    if cached is not None and runtime_info(device_name) != runtime:
        raise WorkerReasonError('sylla-runtime-changed')
    lines = _decode(request, admission, vocabulary, emission, maps)
    transform = ctc.canonical_time_transform(source_sample_rate=info.samplerate, source_channel_count=info.channels,
        source_frame_count=info.frames, target_sample_rate=SAMPLE_RATE, target_frame_count=sample_count,
        chunk_target_frames=DECODER['coreSeconds']*SAMPLE_RATE, chunks=maps)
    value = {'formatVersion': FORMAT_VERSION, 'engine': ENGINE, 'status': 'candidate',
        'audioSha256': source_record['sha256'], 'requestSha256': ctc.canonical_sha256(request),
        'model': model_record, 'runtime': runtime, 'producer': producer, 'decoder': dict(DECODER),
        'normalization': {'id': NORMALIZATION_VERSION, 'sourceSha256': producer['sylla_forced_alignment.py']},
        'canonicalTimeTransform': transform, 'targetAdmission': [{**row, 'requestLanguage': language} for row in admission],
        'emissionWindows': windows, 'lines': lines, 'supportedAuthority': False,
        'publicationAuthority': False, 'corroborationAuthority': False}
    validate_result(value, request, source_record['sha256'], model_record, vocabulary, runtime,
        {'sampleRate': info.samplerate, 'channelCount': info.channels, 'frameCount': info.frames}, language=language)
    check_inputs()
    if cached is not None and ctc.cache_file_identity(cached[0]) != cached[1]:
        raise WorkerReasonError('sylla-emission-cache-changed')
    publish_json(output_path, value)
    return value


def main(argv=None):
    parser = argparse.ArgumentParser()
    for name in ('audio', 'request', 'output', 'model'):
        parser.add_argument('--'+name, type=Path)
    parser.add_argument('--language')
    parser.add_argument('--emission-cache-dir', type=Path)
    parser.add_argument('--device', choices=('auto', 'cuda', 'cpu'), default='auto')
    parser.add_argument('--runtime-info', action='store_true')
    args = parser.parse_args(argv)
    if args.runtime_info:
        print(json.dumps(runtime_info(args.device)))
        return 0
    if any(getattr(args, name) is None for name in ('audio', 'request', 'output', 'model', 'language')):
        parser.error('--audio, --request, --output, --model and --language are required')
    run(args.audio, args.request, args.output, args.model, args.language, args.device,
        emission_cache_dir=args.emission_cache_dir)
    return 0


if __name__ == '__main__':
    raise SystemExit(run_worker_main(main))
