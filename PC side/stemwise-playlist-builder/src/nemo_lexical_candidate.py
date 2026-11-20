"""Private, unprompted Arabic CTC text; never content or timing authority."""
from contextlib import redirect_stdout
import argparse
import copy
import json
import os
from pathlib import Path
import sys
import traceback
import unicodedata

from stemwise_engine import nemo_nfa_forced_alignment as nfa
from stemwise_engine.alignment_phases import AudioIdentity
from stemwise_engine.common import (WorkerTemporaryDirectory, atomic_write_json, heavy_worker_stage,
    read_bounded_json, sha256_file, validate_generated_path, validate_video_id)
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.worker_protocol import WorkerReasonError, parse_worker_json, require_worker_success, run_worker_main

KIND = 'nemo-ctc-untimed-lexical'
FORMAT_VERSION = 1
FAMILY = 'nemo-arabic-fastconformer'
ENVIRONMENT = 'nemo-speech-3.0.0'
DECODER = {'head': 'ctc', 'strategy': 'greedy', 'promptMode': 'withheld', 'wordTimestamps': False,
    'batchSize': 1, 'localAttention': [64, 64], 'channelMix': 'informative-recording-view-float32-v1'}
MODEL = {'repository': 'nvidia/stt_ar_fastconformer_hybrid_large_pcd_v1.0',
    'revision': nfa.MODEL_REVISION, 'sha256': nfa.MODEL_SHA256, 'byteCount': nfa.MODEL_BYTE_COUNT,
    'class': 'nemo.collections.asr.models.hybrid_rnnt_ctc_bpe_models.EncDecHybridRNNTCTCBPEModel'}


def eligible(original):
    if (not isinstance(original, dict) or original.get('language') != 'ar' or original.get('script') != 'Arab'
            or not isinstance(original.get('text'), str) or not original['text'].strip()
            or not isinstance(original.get('componentLanguages', []), list)
            or any(language != 'ar' for language in original.get('componentLanguages', []))):
        return False
    letters = [char for char in unicodedata.normalize('NFKC', original['text']) if char.isalpha()]
    return bool(letters) and all('ARABIC' in unicodedata.name(char, '') for char in letters)


def producer_identity():
    source = Path(__file__).parent
    return {'sourceRevision': nfa.NEMO_SOURCE_REVISION, 'helpers': {
        name: semantic_source_identity(source/name, portable=True) for name in
        ('nemo_lexical_candidate.py', 'nemo_nfa_forced_alignment.py', 'acoustic_inputs.py',
         'ctc_worker.py', 'worker_runtime_probe.py', 'model_registry.py', 'model-route-registry.json')}}


def _validate_runtime(value):
    if (not isinstance(value, dict) or set(value) != {'python', 'nemoToolkit', 'torch', 'cudaRuntime',
            'cudaDevice', 'deviceIndex', 'torchProducer'}
            or type(value['deviceIndex']) is not int or value['deviceIndex'] != 0
            or any(value[key] != expected for key, expected in (
                ('python', nfa.PYTHON_VERSION), ('nemoToolkit', nfa.NEMO_VERSION),
                ('torch', nfa.TORCH_VERSION), ('cudaRuntime', nfa.CUDA_VERSION)))):
        raise ValueError('Arabic lexical runtime differs from its pins')
    nfa.validate_cuda_device(value['cudaDevice'], 'nemo_nfa_forced_alignment')
    observed = value['torchProducer']
    if (not isinstance(observed, dict) or set(observed) != {'device', 'torch', 'cudaDriver',
            'cudaRuntime', 'cudnn', 'uuid', 'name', 'capability', 'totalMemoryBytes'}
            or observed['device'] != 'cuda' or observed['torch'] != value['torch']
            or observed['cudaRuntime'] != value['cudaRuntime']
            or not isinstance(observed['uuid'], str) or not observed['uuid'].startswith('GPU-')
            or len(observed['uuid']) <= 4 or type(observed['cudaDriver']) is not int or observed['cudaDriver'] <= 0
            or not isinstance(observed['cudnn'], str) or not observed['cudnn'].isdecimal()
            or int(observed['cudnn']) <= 0
            or {key: observed[key] for key in ('name', 'capability', 'totalMemoryBytes')} != value['cudaDevice']):
        raise ValueError('Arabic lexical observed CUDA producer is invalid or inconsistent')
    return value


def _runtime_identity():
    import torch
    from stemwise_engine.worker_runtime_probe import torch_runtime_identity
    selected = torch.cuda.current_device()
    if selected != 0:
        raise WorkerReasonError('nemo-lexical-device-mismatch', 'Arabic lexical worker requires CUDA ordinal zero')
    value = {**nfa._runtime_identity(), 'deviceIndex': selected, 'torchProducer': torch_runtime_identity()}
    if torch.cuda.current_device() != selected:
        raise WorkerReasonError('nemo-lexical-device-mismatch')
    return _validate_runtime(value)


def _validate_provenance(value):
    if (not isinstance(value, dict) or set(value) != {'familyId', 'language', 'routeId', 'audio',
            'model', 'runtime', 'producer', 'decoder'} or value['familyId'] != FAMILY
            or value['language'] != 'ar' or value['routeId'] != 'mix' or value['model'] != MODEL
            or value['decoder'] != DECODER or value['producer'] != producer_identity()):
        raise ValueError('Arabic lexical producer changed')
    audio = value['audio']
    stream = audio['stream']
    if (AudioIdentity(audio['sha256'], audio['byteCount'], stream['sampleRate'], stream['channelCount'],
            stream['frameCount']).to_record() != audio or stream['channelCount'] not in (1, 2)):
        raise ValueError('Arabic lexical Original identity changed')
    _validate_runtime(value['runtime'])
    return value


def validate(value, audio_sha):
    if (not isinstance(value, dict) or set(value) != {'formatVersion', 'kind', 'videoId', 'provenance',
            'rawOutput', 'transcription', 'contentAuthority', 'timingAuthority', 'publicationAuthority'}
            or type(value['formatVersion']) is not int or value['formatVersion'] != FORMAT_VERSION
            or value['kind'] != KIND or any(value[key] is not False for key in
                ('contentAuthority', 'timingAuthority', 'publicationAuthority'))):
        raise ValueError('Invalid private Arabic lexical candidate')
    validate_video_id(value['videoId'])
    provenance = _validate_provenance(value['provenance'])
    raw = value['rawOutput']
    if (provenance['audio']['sha256'] != audio_sha or not isinstance(raw, list) or len(raw) != 1
            or not isinstance(raw[0], dict) or set(raw[0]) != {'text', 'tokens'}
            or not isinstance(raw[0]['text'], str) or not raw[0]['text'].strip()
            or not isinstance(raw[0]['tokens'], list) or not raw[0]['tokens']
            or any(type(token) is not int or token < 0 for token in raw[0]['tokens'])
            or value['transcription'] != raw[0]['text']):
        raise ValueError('Arabic lexical raw output or recording changed')
    return value


def recognized(value, audio_sha, *, candidate_only=False):
    if not candidate_only:
        raise ValueError('Untimed Arabic lexical candidates cannot establish content or word timing')
    return validate(value, audio_sha)['transcription']


def _bound_audio(audio, expected):
    import soundfile as sf
    info = sf.info(audio)
    actual = AudioIdentity(sha256_file(audio), Path(audio).stat().st_size,
        info.samplerate, info.channels, info.frames).to_record()
    if info.format not in {'WAV', 'WAVEX', 'RF64'} or actual != expected or info.channels not in (1, 2):
        raise ValueError('Arabic lexical full Original PCM changed')


def _local_runtime(project):
    runtime = worker_python(project).parents[2]
    nfa._bound_file(str(runtime/'uv.lock'), nfa.NEMO_LOCK_SHA256, nfa.NEMO_LOCK_BYTE_COUNT, 'lock')
    nfa._bound_file(str(runtime/'tools/nemo_forced_aligner/align.py'),
        nfa.NFA_ALIGN_SHA256, nfa.NFA_ALIGN_BYTE_COUNT, 'aligner')
    nfa._verify_source_checkout(runtime)
    return runtime


def worker_python(project):
    return Path(project).resolve().parent/'runtime'/ENVIRONMENT/'.venv/bin/python'


def _model_path(project):
    model = Path(project).resolve().parent/'model-cache/lyrics-alignment'/(
        'models--nvidia--stt_ar_fastconformer_hybrid_large_pcd_v1.0')/'snapshots'/nfa.MODEL_REVISION/nfa.MODEL_ARTIFACT
    return nfa._bound_file(str(model), nfa.MODEL_SHA256, nfa.MODEL_BYTE_COUNT, 'model')


def _environment(runtime):
    return {**os.environ, 'PYTHONPATH': str(runtime), 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
        'HF_DATASETS_OFFLINE': '1', 'TOKENIZERS_PARALLELISM': 'false', 'WANDB_MODE': 'disabled'}


def load_or_transcribe(*, project, job_dir, video_id, audio, audio_identity, original,
                       timeout_seconds, require_inputs, runtime_report=None):
    """Own the supervised worker and heavy lease; caller owns the native environment lease."""
    from stemwise_engine.common import run_process_tree
    if not eligible(original):
        raise ValueError('Arabic lexical recovery requires pure Arabic words and declared script')
    validate_video_id(video_id)
    if type(timeout_seconds) is not int or timeout_seconds <= 0:
        raise ValueError('Arabic lexical worker needs a positive stage deadline')
    project, job_dir, audio = Path(project).resolve(), Path(job_dir), Path(audio).resolve(strict=True)
    audio_identity = copy.deepcopy(audio_identity)
    require_inputs()
    _bound_audio(audio, audio_identity)
    runtime = _local_runtime(project)
    python = worker_python(project)
    if runtime_report is None:
        completed = run_process_tree([str(python), '-B', '-m', 'stemwise_engine.nemo_lexical_candidate',
            '--project', str(project), '--runtime-info'], capture_output=True, text=True, check=False,
            timeout=timeout_seconds, env=_environment(runtime))
        require_worker_success(completed, code='nemo-lexical-runtime-failed',
            diagnostic_path=job_dir/'nemo-lexical-runtime-error.private.txt')
        runtime_report = parse_worker_json(completed.stdout)
    provenance = copy.deepcopy({'familyId': FAMILY, 'language': 'ar', 'routeId': 'mix', 'audio': audio_identity,
        'model': MODEL, 'runtime': _validate_runtime(runtime_report), 'producer': producer_identity(), 'decoder': DECODER})
    _validate_provenance(provenance)
    path = validate_generated_path(job_dir, job_dir/'nemo-lexical-ar-mix.private.json')
    if path.is_file():
        saved = None
        try:
            saved = validate(read_bounded_json(path, max_bytes=16*1024*1024, root=job_dir), audio_identity['sha256'])
        except (OSError, ValueError, KeyError, TypeError):
            pass
        if saved is not None and saved['videoId'] == video_id and saved['provenance'] == provenance:
            require_inputs()
            _bound_audio(audio, audio_identity)
            return saved, True
    with WorkerTemporaryDirectory(prefix='nemo-lexical-', dir=job_dir) as name:
        request_path = Path(name)/'request.private.json'
        request = {'formatVersion': FORMAT_VERSION, 'videoId': video_id, 'projectDir': str(project),
            'audioPath': str(audio), 'provenance': provenance, 'timeoutSeconds': timeout_seconds}
        atomic_write_json(request_path, request, root=Path(name))
        with heavy_worker_stage({'projectDir': str(project)}):
            completed = run_process_tree([str(python), '-B', '-m', 'stemwise_engine.nemo_lexical_candidate',
                '--request', str(request_path), '--request-sha256', sha256_file(request_path)],
                capture_output=True, text=True, check=False, timeout=timeout_seconds, env=_environment(runtime))
        require_worker_success(completed, code='nemo-lexical-worker-failed',
            diagnostic_path=job_dir/'nemo-lexical-error.private.txt')
        candidate = validate(parse_worker_json(completed.stdout), audio_identity['sha256'])
        if candidate['provenance'] != provenance or candidate['videoId'] != video_id:
            raise ValueError('Arabic lexical worker used different inputs')
        require_inputs()
        _bound_audio(audio, audio_identity)
        _local_runtime(project)
        if producer_identity() != provenance['producer']:
            raise ValueError('Arabic lexical source changed during inference')
        atomic_write_json(path, candidate, root=job_dir)
        return candidate, False


def run_request(path, request_sha):
    from stemwise_engine.acoustic_inputs import require_waveform_allocation
    from stemwise_engine.ctc_worker import execution_deadline, retire_model
    from stemwise_engine.worker_protocol import fatal_model_failure
    if sha256_file(path) != request_sha:
        raise ValueError('Arabic lexical request changed')
    request = read_bounded_json(path, max_bytes=16*1024*1024, root=path.parent)
    if (set(request) != {'formatVersion', 'videoId', 'projectDir', 'audioPath', 'provenance', 'timeoutSeconds'}
            or type(request['formatVersion']) is not int or request['formatVersion'] != FORMAT_VERSION
            or type(request['timeoutSeconds']) is not int or request['timeoutSeconds'] <= 0):
        raise ValueError('Arabic lexical worker request is invalid')
    validate_video_id(request['videoId'])
    provenance = _validate_provenance(request['provenance'])
    audio, project = Path(request['audioPath']), Path(request['projectDir'])
    if not audio.is_absolute() or not project.is_absolute():
        raise ValueError('Arabic lexical worker needs absolute local paths')
    _bound_audio(audio, provenance['audio'])
    _local_runtime(project)
    model_path = _model_path(project)
    runtime = _runtime_identity()
    if runtime != provenance['runtime']:
        raise ValueError('Arabic lexical worker runtime changed')
    require_waveform_allocation(audio, 16000, source_arrays=2, target_arrays=3, recording=True)
    with WorkerTemporaryDirectory(prefix='mono-', dir=path.parent) as name:
        mono = Path(name)/'original.wav'
        nfa._prepare_mono_audio(audio, mono)
        import torch
        from nemo.collections.asr.models import ASRModel, EncDecHybridRNNTCTCModel
        state, model = {}, None
        try:
            with execution_deadline(request['timeoutSeconds']):
                try:
                    model = ASRModel.restore_from(str(model_path), map_location=torch.device('cuda:0')).eval()
                except BaseException:
                    fatal_model_failure('nemo-lexical-model-initialization-failed')
                state.update(model=model, cuda_model=True)
                if (not isinstance(model, EncDecHybridRNNTCTCModel)
                        or type(model).__module__+'.'+type(model).__name__ != MODEL['class']):
                    raise ValueError('Arabic lexical model has no qualified CTC head')
                model.change_decoding_strategy(decoder_type='ctc')
                if model.cur_decoder != 'ctc' or model.ctc_decoding.cfg.get('strategy') != 'greedy':
                    raise ValueError('Arabic lexical CTC decoding strategy changed')
                model.change_attention_model(self_attention_model='rel_pos_local_attn', att_context_size=[64, 64])
                if (model.encoder.self_attention_model != 'rel_pos_local_attn'
                        or model.encoder.att_context_size != [64, 64]):
                    raise ValueError('Arabic lexical local attention was not applied')
                output = model.transcribe(audio=[str(mono)], batch_size=1, return_hypotheses=False,
                    timestamps=False, verbose=False)
                if model.cur_decoder != 'ctc' or model.ctc_decoding.compute_timestamps is not False:
                    raise ValueError('Arabic lexical decoder changed during transcription')
                try:
                    if not isinstance(output, list) or len(output) != 1:
                        raise ValueError('Expected one complete-recording hypothesis')
                    hypothesis = output[0]
                    tokens = hypothesis.y_sequence.tolist() if hasattr(hypothesis.y_sequence, 'tolist') else hypothesis.y_sequence
                    raw = [{'text': hypothesis.text, 'tokens': tokens}]
                except (AttributeError, TypeError, ValueError) as error:
                    raise WorkerReasonError('nemo-lexical-output-invalid') from error
        except BaseException as error:
            traceback.clear_frames(error.__traceback__)
            raise
        finally:
            model = output = hypothesis = None
            if state:
                try:
                    with execution_deadline(30):
                        retire_model(state)
                except BaseException:
                    fatal_model_failure('nemo-lexical-model-retirement-failed')
        _bound_audio(audio, provenance['audio'])
        _local_runtime(project)
        _model_path(project)
        if (sha256_file(path) != request_sha or producer_identity() != provenance['producer']
                or _runtime_identity() != runtime):
            raise ValueError('Arabic lexical inputs changed during inference')
    candidate = {'formatVersion': FORMAT_VERSION, 'kind': KIND, 'videoId': request['videoId'],
        'provenance': provenance, 'rawOutput': raw, 'transcription': raw[0]['text'],
        'contentAuthority': False, 'timingAuthority': False, 'publicationAuthority': False}
    try:
        return validate(candidate, provenance['audio']['sha256'])
    except (ValueError, KeyError, TypeError) as error:
        raise WorkerReasonError('nemo-lexical-output-invalid') from error


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--runtime-info', action='store_true')
    mode.add_argument('--request', type=Path)
    parser.add_argument('--request-sha256')
    parser.add_argument('--project', type=Path)
    args = parser.parse_args()
    with redirect_stdout(sys.stderr):
        if args.runtime_info:
            _local_runtime(args.project)
            value = _runtime_identity()
        else:
            value = run_request(args.request, args.request_sha256)
    print(json.dumps(value, ensure_ascii=False, allow_nan=False))


if __name__ == '__main__':
    raise SystemExit(run_worker_main(main))
