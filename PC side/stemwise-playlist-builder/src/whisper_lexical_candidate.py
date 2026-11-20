"""Private untimed text after one proven empty-DTW failure; never timing evidence."""
import copy
import hashlib
from importlib.metadata import distribution, version
from pathlib import Path

from stemwise_engine.common import (atomic_write_json, read_bounded_json,
    read_bounded_regular_bytes, parse_strict_json_bytes, sha256_file)
from stemwise_engine.model_registry import model_assets, runtime_profile
from stemwise_engine.provenance import semantic_source_identity

KIND = 'faster-whisper-untimed-lexical'
FORMAT_VERSION = 1


def is_empty_dtw(error):
    """Recognize the actual pinned vendor leaf and empty DTW, not IndexError generally."""
    if not isinstance(error, IndexError):
        return False
    from faster_whisper import WhisperModel
    traceback = error.__traceback__
    while traceback is not None and traceback.tb_next is not None:
        traceback = traceback.tb_next
    if traceback is None or traceback.tb_frame.f_code is not WhisperModel.find_alignment.__code__:
        return False
    values = traceback.tb_frame.f_locals
    return (isinstance(values.get('alignments'), list) and not values['alignments']
        and getattr(values.get('text_indices'), 'size', None) == 0
        and getattr(values.get('time_indices'), 'size', None) == 0
        and getattr(values.get('jumps'), 'shape', None) == (1,)
        and bool(values['jumps'][0]) and bool(values.get('text_token')))


def producer_identity():
    """Pure local source/package identity; passive reads never initialize numerical libraries."""
    source = Path(__file__).parent
    return {'helperSha256': semantic_source_identity(Path(__file__)),
        'ownerSha256': semantic_source_identity(source/'lyrics_alignment.py', ('FasterWhisperEvidence',)),
        'pinsSha256': semantic_source_identity(source/'model-route-registry.json'),
        'vendorSha256': sha256_file(distribution('faster-whisper').locate_file('faster_whisper/transcribe.py')),
        'packages': {name: version(name) for name in ('faster-whisper', 'ctranslate2', 'numpy', 'av', 'tokenizers')}}


def provenance(timed_provenance, language):
    value = copy.deepcopy(timed_provenance)
    value['decoder']['wordTimestamps'] = False
    value['producer'] = producer_identity()
    value['language'] = language
    return value


def cache_path(evidence_root, route, language, precision):
    routes = {'mix', 'htdemucs-vocals', *model_assets('lyrics_align_pc')['ROFORMER_ROUTES']}
    if route not in routes or type(precision) is not bool:
        raise ValueError('Invalid private lexical route')
    if language is not None and (not isinstance(language, str) or not language.isalpha() or len(language) not in (2, 3)):
        raise ValueError('Invalid private lexical language')
    return Path(evidence_root)/f'whisper-lexical-{route}-{language or "auto"}-{"precision" if precision else "standard"}.private.json'


def validate(value, audio_sha):
    if (not isinstance(value, dict) or set(value) != {'formatVersion', 'kind', 'videoId', 'provenance',
            'trigger', 'segments', 'transcription', 'timingAuthority', 'publicationAuthority'}
            or type(value['formatVersion']) is not int or value['formatVersion'] != FORMAT_VERSION
            or value['kind'] != KIND or value['timingAuthority'] is not False
            or value['publicationAuthority'] is not False):
        raise ValueError('Invalid private untimed lexical observation')
    from stemwise_engine.common import validate_video_id
    validate_video_id(value['videoId'])
    pins = model_assets('lyrics_align_pc')
    supplied = value['provenance']
    if (not isinstance(supplied, dict) or supplied.get('audio', {}).get('sha256') != audio_sha
            or supplied.get('modelSnapshot') != pins['PINNED_WHISPER_SNAPSHOT']
            or supplied.get('modelSha256') != pins['PINNED_WHISPER_SHA256']
            or type(supplied.get('durationMs')) is not int or supplied['durationMs'] <= 0
            or supplied.get('producer') != producer_identity()
            or supplied.get('fasterWhisperVersion') != runtime_profile('lyrics')['packages']['faster-whisper']
            or any(supplied['producer']['packages'][name] != runtime_profile('lyrics')['packages'][name]
                for name in supplied['producer']['packages'])
            or supplied.get('device') not in {'cpu', 'cuda'}
            or supplied.get('language') is not None and (not isinstance(supplied['language'], str)
                or not supplied['language'].isalpha() or len(supplied['language']) not in (2, 3))
            or supplied.get('producerRuntime', {}).get('device') != supplied['device']
            or supplied['producerRuntime'].get('ctranslate2') != runtime_profile('lyrics')['packages']['ctranslate2']):
        raise ValueError('Private lexical producer/audio changed')
    decoder = supplied.get('decoder', {})
    precision = decoder.get('beamSize') == 10
    expected = {'beamSize': 10 if precision else 5, 'wordTimestamps': False,
        'conditionOnPreviousText': False, 'vadFilter': False, 'temperature': 0.0, 'promptMode': 'withheld'}
    if precision:
        expected.update(patience=1.5, repetitionPenalty=1.05, hotwords=False)
    if decoder != expected:
        raise ValueError('Private lexical decoder changed')
    trigger = value['trigger']
    if (not isinstance(trigger, dict) or set(trigger) != {'reason', 'context', 'failure'}
            or trigger['reason'] != 'empty-dtw-word-alignment'
            or not isinstance(trigger['context'], dict)
            or set(trigger['context']) != {'audioSha256', 'durationMs', 'draftSha256', 'metadataSha256', 'familyId', 'routeId'}
            or trigger['context']['familyId'] != 'faster-whisper'
            or trigger['context']['durationMs'] != supplied['durationMs']
            or trigger['context']['routeId'] not in {'mix', 'htdemucs-vocals', *pins['ROFORMER_ROUTES']}
            or trigger['context']['routeId'] != supplied['audio'].get('routeId', 'mix')
            or not isinstance(trigger['failure'], dict)
            or set(trigger['failure']) != {'familyId', 'routeId', 'code', 'diagnostics'}
            or any(trigger['failure'][key] != trigger['context'][key] for key in ('familyId', 'routeId'))):
        raise ValueError('Private lexical failure binding changed')
    segments = value['segments']
    if not isinstance(segments, list) or not segments:
        raise ValueError('Private lexical observation is empty')
    previous = 0
    for segment in segments:
        if (not isinstance(segment, dict) or set(segment) != {'id', 'text', 'tokens'}
                or type(segment['id']) is not int or segment['id'] <= previous
                or not isinstance(segment['text'], str)
                or not isinstance(segment['tokens'], list) or not segment['tokens']
                or any(type(token) is not int or token < 0 for token in segment['tokens'])):
            raise ValueError('Private lexical raw segment changed')
        previous = segment['id']
    if (not isinstance(value['transcription'], str) or not value['transcription'].strip()
            or value['transcription'] != ''.join(segment['text'] for segment in segments)):
        raise ValueError('Private lexical raw text changed')
    return value


def validate_trigger(value, job_dir, *, audio_sha=None):
    """Retain the original failure context even after editorial-only changes."""
    from stemwise_engine.private_diagnostics import validate_references
    trigger = value['trigger']
    if audio_sha is not None and trigger['context']['audioSha256'] != audio_sha:
        raise ValueError('Private lexical failure belongs to another Original')
    references = validate_references(trigger['failure']['diagnostics'])
    if not references:
        raise ValueError('Private lexical timing failure is unavailable')
    for reference in references:
        raw = read_bounded_regular_bytes(Path(job_dir)/reference['file'], max_bytes=12000, root=Path(job_dir))
        if len(raw) != reference['byteCount'] or hashlib.sha256(raw).hexdigest() != reference['sha256']:
            raise ValueError('Private lexical timing failure changed')
        context = parse_strict_json_bytes(raw.split(b'\n', 1)[0])
        if not isinstance(context, dict) or any(context.get(key) != item for key, item in trigger['context'].items()):
            raise ValueError('Private lexical timing failure binding changed')


def load_cached(path, expected_provenance, job_dir, video_id, *, audio_sha):
    if not Path(path).is_file():
        return None
    try:
        value = read_bounded_json(path, max_bytes=16*1024*1024, root=Path(path).parent)
        validate(value, expected_provenance['audio']['sha256'])
        validate_trigger(value, job_dir, audio_sha=audio_sha)
        if value['provenance'] != expected_provenance or value['videoId'] != video_id:
            return None
        return value
    except (OSError, ValueError, KeyError, TypeError):
        return None


def recover(*, path, video_id, audio, expected_provenance, model, trigger, job_dir, require_inputs):
    """A new full unprompted pass; no failed-run splicing or fabricated word times."""
    from stemwise_engine.alignment_audio import file_identity
    from stemwise_engine.ctc_worker import execution_deadline
    pins = model_assets('lyrics_align_pc')
    if (model._model is None or model.model_name != 'large-v3' or model.snapshot_path.name != pins['PINNED_WHISPER_SNAPSHOT']
            or model.device != expected_provenance['device'] or model.compute_type != expected_provenance['computeType']):
        raise ValueError('Private lexical model owner differs')
    identity = file_identity(audio)
    if any(identity[key] != expected_provenance['audio'].get(key) for key in ('sha256', 'byteCount', 'stream')):
        raise ValueError('Private lexical audio changed')
    decoder = expected_provenance['decoder']
    options = {'language': expected_provenance['language'], 'initial_prompt': None, 'beam_size': decoder['beamSize'],
        'word_timestamps': False, 'condition_on_previous_text': False, 'vad_filter': False, 'temperature': 0.0}
    if decoder['beamSize'] == 10:
        options.update(patience=1.5, repetition_penalty=1.05)
    segments = []
    with execution_deadline(model.timeout_seconds):
        generated, _ = model._model.transcribe(str(audio), **options)
        for segment in generated:
            segments.append({'id': segment.id, 'text': segment.text, 'tokens': list(segment.tokens)})
    value = {'formatVersion': FORMAT_VERSION, 'kind': KIND, 'videoId': video_id,
        'provenance': expected_provenance, 'trigger': trigger, 'segments': segments,
        'transcription': ''.join(segment['text'] for segment in segments),
        'timingAuthority': False, 'publicationAuthority': False}
    validate(value, identity['sha256'])
    validate_trigger(value, job_dir)
    require_inputs()
    if file_identity(audio) != identity:
        raise ValueError('Private lexical audio changed during recognition')
    atomic_write_json(path, value, root=Path(path).parent)
    return value


def recognized(value, audio_sha, *, candidate_only):
    if not candidate_only:
        raise ValueError('Untimed lexical observations cannot establish content or word timing')
    return validate(value, audio_sha)['transcription']
