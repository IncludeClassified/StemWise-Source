"""Explicit private Sylla observations; current content and timing authority stay separate."""
from pathlib import Path
import hashlib

from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.common import (WorkerTemporaryDirectory, atomic_write_json,
    heavy_worker_stage, json_bytes, parse_strict_json_bytes, read_bounded_json,
    read_bounded_regular_bytes, sha256_file, validate_generated_path)
from stemwise_engine.runtime_environment import environment_python
from stemwise_engine.worker_protocol import parse_worker_json, require_worker_success


def _inputs(workflow, video_id):
    from stemwise_engine.alignment_audio import file_identity
    from stemwise_engine.lyric_content import require_workspace_content
    from stemwise_engine.lyrics_alignment import (AlignmentWorkspace, _plain_original,
        build_lexical_occurrence_mapping, canonical_language, lexical_mapping_identity)
    from stemwise_engine.lyrics_align_pc import _forced_alignment_request
    from stemwise_engine.lyrics_tokenization import display_words
    from stemwise_engine.lyrics_normalize import physical_lines
    from stemwise_engine.spoken_targets import load as load_spoken_targets
    from stemwise_engine.workflow_records import ContentEvidenceUnavailable, LyricsContentBlocker

    workspace = AlignmentWorkspace(workflow.root, workspace_root=workflow.workspace_root)
    try:
        state = read_bounded_json(workspace.state_path(video_id), max_bytes=MAX_JSON_BYTES, root=workspace.jobs)
        sources = tuple(state['contentAdmission']['sourcePhysicalIndices'])
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ContentEvidenceUnavailable() from error
    canonical = workspace.current_backup_path(video_id)
    raw = read_bounded_regular_bytes(canonical, max_bytes=MAX_JSON_BYTES, root=workspace.canonical_plain)
    original = _plain_original(parse_strict_json_bytes(raw))
    job = workflow._load_job(video_id)
    compiled, _, _ = workflow._preflight_drafts(job, roles=frozenset({'original'}))
    if _plain_original(compiled) != original:
        raise LyricsContentBlocker('content-canonical-differs')
    audio = workflow._completed_analysis_audio(workflow._pipeline(), video_id)
    admission = require_workspace_content(workspace, video_id, sources,
        original=original, audio_sha=audio['sha256'])
    languages = [original.get('language'), *original.get('componentLanguages', [])]
    import unicodedata
    if (any(canonical_language(language) == 'ar' for language in languages if isinstance(language, str))
            or any(unicodedata.category(character)[0] in {'L', 'M'}
                and 'ARABIC' in unicodedata.name(character, '') for character in original['text'])):
        raise ValueError('sylla-arabic-unsupported')
    mapping = build_lexical_occurrence_mapping(video_id, original, sources)
    mapping_identity = lexical_mapping_identity(mapping)
    if workspace.mapping_path(video_id).exists():
        existing, _ = workflow._lexical_mapping(video_id)
        if lexical_mapping_identity(existing) != mapping_identity:
            raise LyricsContentBlocker('content-occurrence-uncertain')
    language = canonical_language(original.get('language'))
    spoken = load_spoken_targets(workflow._job_dir(video_id), video_id, original, sources, audio['sha256'])
    if spoken is not None:
        mapping['_spokenTargets'] = spoken
    elif not isinstance(language, str) or language in {'und', 'mul'}:
        raise LyricsContentBlocker('pronunciation-support-unavailable')
    words = [display_words(physical_lines(original['text'])[source]) for source in sources]
    request, _, _, _ = _forced_alignment_request(video_id, list(sources), mapping, words,
        duration_ms=audio['durationMs'])
    mapping.pop('_spokenTargets', None)
    identity = file_identity(Path(audio['path']))
    if identity['sha256'] != audio['sha256'] or identity['byteCount'] != audio['byteCount']:
        raise ValueError('sylla-recording-changed')
    snapshot = {'jobSha256': sha256_file(workflow._job_path(video_id)),
        'stateSha256': sha256_file(workspace.state_path(video_id)),
        'canonicalSha256': hashlib.sha256(raw).hexdigest(),
        'reviewInputs': workflow._review_inputs(job, original_only=True),
        'draftMetadataSha256': sha256_file(workflow._job_dir(video_id)/'drafts/lyrics.meta.json'),
        'admission': admission.to_record(), 'audio': identity, 'mapping': mapping_identity,
        'request': request, 'language': language}
    return snapshot, audio, mapping


def collect(workflow, job):
    from stemwise_engine import sylla_forced_alignment as worker
    from stemwise_engine.ctc_worker import canonical_sha256
    from stemwise_engine.source_acquisition import _publish_source_candidate
    video_id = job['videoId']
    snapshot, audio, mapping = _inputs(workflow, video_id)
    project = Path(workflow.config['projectDir'])
    model = project.parent/'model-cache/lyrics-sylla/sylla-600m-v1'/worker.MODEL_SPEC['revision']
    model_record = worker.model_identity(model)
    vocabulary = read_bounded_json(model/'vocab.json', max_bytes=MAX_JSON_BYTES, root=model)
    request, language = snapshot['request'], snapshot['language']
    worker.target_admission(request, language, vocabulary)
    producer = worker.producer_identity()
    directory = validate_generated_path(workflow._job_dir(video_id), workflow._job_dir(video_id)/'sylla-candidate')
    directory.mkdir(parents=True, exist_ok=True)
    emission_cache_dir = validate_generated_path(directory, directory/'emissions')
    try:
        emission_cache_dir.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass  # The worker can still produce a candidate when optional cache storage is unavailable.
    python = environment_python('main', project)
    command = [str(python), '-B', '-m', 'stemwise_engine.sylla_forced_alignment']
    timeout = int(workflow.config.get('analysisTimeoutMinutes', 180))*60
    ledger = workflow._song_ledger(video_id)
    with ledger.active(), heavy_worker_stage(workflow.config):
        completed = workflow._run_primary_worker('sylla-runtime:'+canonical_sha256(producer),
            {'producer': producer, 'model': model_record}, command+['--runtime-info', '--device', 'auto'],
            text=True, capture_output=True, check=False, timeout=min(timeout, 300))
        require_worker_success(completed, code='sylla-runtime-failed', diagnostic_path=directory/'runtime.failure.private.txt')
        runtime = parse_worker_json(completed.stdout)
        worker._validate_runtime(runtime)
        binding = {'scope': 'private-shadow-evaluation', 'audio': snapshot['audio'], 'requestSha256': canonical_sha256(request),
            'mapping': snapshot['mapping'],
            'model': model_record, 'producer': producer, 'runtime': runtime,
            'decoder': worker.DECODER, 'normalization': worker.NORMALIZATION_VERSION}
        key = canonical_sha256(binding)
        path = validate_generated_path(directory, directory/(key+'.private.json'))

        def validate(value):
            if (not isinstance(value, dict) or value.get('corroborationAuthority') is not False
                    or value.get('supportedAuthority') is not False or value.get('publicationAuthority') is not False):
                raise ValueError('sylla-shadow-authority-invalid')
            worker.validate_result(value, request, audio['sha256'], model_record, vocabulary,
                expected_runtime=runtime, audio_stream=snapshot['audio']['stream'], language=language)
            return value

        reused = path.exists()
        if reused:
            packet = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=directory)
            if (not isinstance(packet, dict) or set(packet) != {'formatVersion', 'binding', 'request', 'mapping', 'result'}
                    or packet['formatVersion'] != 1 or packet['binding'] != binding
                    or packet['request'] != request or packet['mapping'] != mapping):
                raise ValueError('sylla-candidate-cache-changed')
            validate(packet['result'])
        else:
            with WorkerTemporaryDirectory(prefix='sylla-', dir=directory) as temporary:
                request_path, output_path = Path(temporary)/'request.json', Path(temporary)/'result.json'
                atomic_write_json(request_path, request)
                completed = workflow._run_primary_worker('sylla-candidate:'+key, binding,
                    command+['--audio', str(audio['path']), '--request', str(request_path), '--output', str(output_path),
                        '--model', str(model), '--language', language, '--device', 'auto',
                        '--emission-cache-dir', str(emission_cache_dir)],
                    text=True, capture_output=True, check=False, timeout=timeout)
                require_worker_success(completed, code='sylla-candidate-failed', diagnostic_path=directory/'worker.failure.private.txt')
                result = validate(read_bounded_json(output_path, max_bytes=MAX_JSON_BYTES, root=Path(temporary)))
                packet = {'formatVersion': 1, 'binding': binding, 'request': request, 'mapping': mapping, 'result': result}
                if worker.model_identity(model) != model_record or worker.producer_identity() != producer:
                    raise ValueError('sylla-candidate-producer-changed')
                path = _publish_source_candidate(path, json_bytes(packet))
        if _inputs(workflow, video_id)[0] != snapshot:
            raise ValueError('sylla-candidate-inputs-changed')
        retained = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=directory)
        if retained != packet:
            raise ValueError('sylla-candidate-readback-changed')
        validate(retained['result'])
    return {'videoId': video_id, 'state': job['state'], 'status': 'private-shadow-timing-candidate',
        'candidate': {'path': str(path), 'sha256': sha256_file(path)}, 'reused': reused,
        'supportedAuthority': False, 'publicationAuthority': False, 'corroborationAuthority': False}
