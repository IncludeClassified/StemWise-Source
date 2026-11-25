"""Private pre-editorial recognition, executed in the existing alignment environment."""
from __future__ import annotations

import json
import hashlib
import os
import sys
import stemwise_engine.mms_asr as mms_asr
from dataclasses import asdict, replace
from pathlib import Path

from stemwise_engine.common import (load_config, read_bounded_json, read_bounded_regular_bytes,
    parse_strict_json_bytes, validate_video_id, validate_generated_path)
from stemwise_engine.lyrics_align_pc import _load_or_run_qwen3_structure_transcript, QWEN3_ASR_SOURCE_ROUTES
from stemwise_engine.alignment_audio import file_identity, identity_transform
from stemwise_engine.alignment_publication import qwen_mix_cache_name
from stemwise_engine.lyrics_alignment import (AlignmentWorkspace, score_structure_witness,
                              canonical_language, transcription_languages)
from stemwise_engine.lyrics_intake import read_recording_draft
from stemwise_engine.lyrics_normalize import performed_line_indices
from stemwise_engine.lyric_content import (require_exact_sequence, require_recording_adjudication,
                           validate_recording_adjudication_record, recording_review_input_reference,
                           ADJUDICATION_FILE, _recognized, _recognized_text)
from stemwise_engine.workflow_records import LyricsContentBlocker, ContentEvidenceUnavailable


def stage_audio_candidate(job_dir, evidence_root, video_id, audio_identity, reference, *, audio_view_witness=None):
    """Retain recognizer wording for review; this grants no content or timing authority."""
    from stemwise_engine.source_acquisition import _publish_source_candidate
    video_id = validate_video_id(video_id)
    if (not isinstance(reference, dict) or set(reference) != {'path', 'sha256'}
            or not isinstance(reference['path'], str)):
        raise ValueError('Audio candidate recognition reference is invalid')
    evidence_root, job_dir = Path(evidence_root), Path(job_dir)
    path = evidence_root / reference['path']
    raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=evidence_root)
    if hashlib.sha256(raw).hexdigest() != reference['sha256']:
        raise ValueError('Audio candidate recognition changed')
    cache = parse_strict_json_bytes(raw)
    provenance = cache['provenance']
    from stemwise_engine.workflow_status import candidate_recognition_sha, read_audio_view_witness
    recognized_sha = candidate_recognition_sha(provenance, audio_identity['sha256'],
        audio_identity['durationMs'], evidence_root,
        witness=read_audio_view_witness(audio_view_witness, evidence_root))
    text = _recognized_text(_recognized(cache, recognized_sha, candidate_only=True))
    duration = provenance.get('durationMs', provenance.get('audio', {}).get('stream', {}).get('durationMs'))
    if duration != audio_identity['durationMs'] or not isinstance(text, str) or not text.strip():
        raise ContentEvidenceUnavailable('content-evidence-unavailable')
    payload = (text.replace('\r\n', '\n').replace('\r', '\n').strip() + '\n').encode('utf-8')
    candidates = validate_generated_path(job_dir, job_dir/'candidates')
    candidates.mkdir(parents=True, exist_ok=True)
    candidate = _publish_source_candidate(candidates/'audio-first-lyrics.txt', payload)
    if read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=evidence_root) != raw:
        raise ContentEvidenceUnavailable('content-evidence-changed')
    return {'formatVersion': 1, 'videoId': video_id, 'audioSha256': audio_identity['sha256'],
            'durationMs': duration, 'recognition': dict(reference),
            'candidate': {'path': candidate.relative_to(job_dir).as_posix(),
                          'sha256': hashlib.sha256(payload).hexdigest()},
            **({'audioViewWitness': audio_view_witness} if audio_view_witness is not None else {}),
            'status': 'pending-recording-review', 'publicationAuthority': False}


def propose(config, root, video_id, audio, expected_sha):
    """Use the existing unprompted recognizer when retrieval has supplied no original."""
    video_id = validate_video_id(video_id)
    workspace = AlignmentWorkspace(root, workspace_root=Path(config['projectDir']).parent.parent)
    identity = file_identity(audio)
    if identity['sha256'] != expected_sha:
        raise ValueError('Audio candidate recording changed')
    identity['canonicalTimeTransform'] = identity_transform(identity['stream'])
    _, _, reused = _load_or_run_qwen3_structure_transcript(config=config, video_id=video_id,
        language=None, audio=audio, audio_identity=identity,
        model_cache=Path(config['projectDir']).parent/'model-cache/lyrics-alignment',
        workspace=workspace, temp_dir=workspace.job_dir(video_id)/'recording-candidate',
        device='cuda', timeout_seconds=int(config.get('analysisTimeoutMinutes', 180))*60)
    path = workspace.job_dir(video_id)/'qwen-structure.private.json'
    raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=workspace.job_dir(video_id))
    if file_identity(audio) != {key: value for key, value in identity.items() if key != 'canonicalTimeTransform'}:
        raise ValueError('Audio candidate recording changed during recognition')
    return {'recognition': {'path': path.name, 'sha256': hashlib.sha256(raw).hexdigest()},
            'reusedTranscript': reused}


def _adjudication(root, video_id):
    job_dir = root / 'jobs' / video_id
    path = job_dir / ADJUDICATION_FILE
    if not path.exists() and not path.is_symlink():
        return None
    raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=job_dir)
    value = validate_recording_adjudication_record(parse_strict_json_bytes(raw), video_id)
    return value, hashlib.sha256(raw).hexdigest()


def _selected_reference_decision(root, video_id, original, indices, identity):
    from stemwise_engine import source_recording_content
    directory = root/'jobs'/video_id
    if not (directory/'sources.json').is_file():
        return None
    try:
        selected = source_recording_content.selection(directory)
        if selected is None:
            return None
        return source_recording_content.create(job_dir=directory,
            evidence_root=root/'lyrics-alignment/jobs'/video_id, video_id=video_id,
            original=original, indices=indices, audio_sha=identity['sha256'],
            duration_ms=identity.get('durationMs', identity.get('stream', {}).get('durationMs')),
            selected_source=selected)
    except (FileNotFoundError, ValueError) as error:
        from stemwise_engine.private_diagnostics import retain_error
        try:
            retain_error(directory/'source-recording-content.failure.private.txt', error,
                context={'stage': 'selected-reference-recording-assessment', 'audioSha256': identity['sha256']})
        except Exception as diagnostic_error:
            from stemwise_engine.provider_operations import raise_if_operation_stopped
            from stemwise_engine.common import WorkerRetirementError
            raise_if_operation_stopped(diagnostic_error)
            if isinstance(diagnostic_error, WorkerRetirementError):
                raise
            print('selected-reference-assessment-diagnostic-unavailable', file=sys.stderr)
        return None


def adjudicate_retained_recording(config, root, video_id, audio_identity):
    """Revalidate explicit review inputs; the normal recognizer still verifies its producer."""
    value = _adjudication(root, video_id)
    if value is None:
        raise LyricsContentBlocker('recording-adjudication-unavailable')
    review, review_sha = value
    job_dir = root / 'jobs' / video_id
    draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
    workspace = AlignmentWorkspace(root, workspace_root=Path(config['projectDir']).parent.parent)
    path = workspace.job_dir(video_id) / review['recognition']['path']
    try:
        cache = read_bounded_json(path, max_bytes=16*1024*1024, root=workspace.job_dir(video_id))
        transcript = _recognized_text(_recognized(cache, audio_identity['sha256']))
        if not isinstance(transcript, str):
            raise ValueError('Invalid retained recognition')
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise ContentEvidenceUnavailable('content-evidence-invalid') from error
    original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'text': draft,
        'language': metadata.get('originalLanguage') or 'und', 'script': metadata.get('originalScript'),
        'componentLanguages': metadata.get('componentLanguages', [])}
    from stemwise_engine.spoken_targets import load
    indices = list(performed_line_indices(draft))
    spoken = load(job_dir, video_id, original, indices, audio_identity['sha256'])
    if spoken is not None:
        original = {**original, '_spokenTargets': spoken}
    resolution = require_recording_adjudication(review, video_id=video_id,
        original=original, indices=indices,
        transcript=transcript, audio_sha=audio_identity['sha256'],
        duration_ms=audio_identity['durationMs'], draft_sha=draft_sha, metadata_sha=metadata_sha,
        evidence_path=path, evidence_root=workspace.job_dir(video_id))
    return {**resolution, 'reviewSha256': review_sha}


def _native_transcript(request, adapter):
    job_dir = request['workspace'].job_dir(request['video_id'])
    job_dir.mkdir(parents=True, exist_ok=True)
    words, provenance, reused = mms_asr.load_or_transcribe(
        project=Path(request['config']['projectDir']), model_cache=request['model_cache'],
        job_dir=job_dir, audio=request['audio'],
        audio_identity=request['audio_identity'], duration_ms=request['audio_identity']['stream']['durationMs'],
        route=request.get('audio_view', 'mix'), device=request['device'], timeout=request['timeout_seconds'],
        env=os.environ.copy(), rerun=False, adapter=adapter, cache_only=request.get('cache_only', False))
    return ' '.join(word.raw for word in words), provenance, reused


def collect_disagreement_candidate(config, workspace, video_id, audio, identity, original,
                                   draft_sha, metadata_sha, primary, scope, *, diagnostic=False):
    """Retain independent full-recording views without granting content authority."""
    from faster_whisper.tokenizer import _LANGUAGE_CODES
    from stemwise_engine import lyrics_align_pc as align
    from stemwise_engine.alignment_models import WhisperModelOwner
    from stemwise_engine.source_acquisition import _publish_source_candidate
    from stemwise_engine.lyrics_alignment import resolve_compute_type
    from stemwise_engine import alignment_audio as audio_store
    from stemwise_engine.common import WorkerRetirementError, diagnostic_code, raise_with_cleanup_failure
    from stemwise_engine.alignment_attempt import SongAttemptBlocked
    from stemwise_engine.provider_operations import raise_if_operation_stopped
    from contextlib import ExitStack
    from types import SimpleNamespace
    job_dir = workspace.root.parent/'jobs'/video_id
    evidence_root = workspace.job_dir(video_id)
    primary_raw = read_bounded_regular_bytes(primary, max_bytes=16*1024*1024, root=evidence_root)
    _recognized(parse_strict_json_bytes(primary_raw), identity['sha256'])
    languages = original.get('componentLanguages') or [original.get('language')]
    if not isinstance(languages, list) or not languages or any(
            not isinstance(language, str) or canonical_language(language) not in _LANGUAGE_CODES for language in languages):
        return None
    language = canonical_language(languages[0]) if len(languages) == 1 else None
    model_cache = Path(config['projectDir']).parent/'model-cache/lyrics-alignment'
    owner = WhisperModelOwner(config={**config, 'analysisTimeoutMinutes': config.get('analysisTimeoutMinutes', 180)},
        model_name=align.DEFAULT_MODEL, snapshot=align._whisper_snapshot_path(model_cache),
        device='cuda', compute_type=resolve_compute_type('cuda'))
    def get_model():
        align._verify_whisper_snapshot(model_cache)
        return owner.get()
    def require_inputs():
        if (read_recording_draft(job_dir)[1::2] != (draft_sha, metadata_sha)
                or file_identity(audio) != {key: value for key, value in identity.items() if key != 'canonicalTimeTransform'}
                or read_bounded_regular_bytes(primary, max_bytes=16*1024*1024, root=evidence_root) != primary_raw):
            raise LyricsContentBlocker('recording-adjudication-input-changed')
    frozen_primary = primary if diagnostic else _publish_source_candidate(
        evidence_root/'content-recovery-primary.private.json', primary_raw)
    result = {'formatVersion': 1, 'videoId': video_id, 'audioSha256': identity['sha256'],
        'durationMs': identity['stream']['durationMs'], 'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
        'primaryRecognition': {'path': frozen_primary.relative_to(evidence_root).as_posix(),
                               'sha256': hashlib.sha256(primary_raw).hexdigest()},
        'scope': scope, 'candidates': [], 'status': 'pending-recording-review',
        'publicationAuthority': False}
    errors, failed_views = [], {}
    qwen_runtime, qwen_runtime_error, runtime_lease_started = None, None, False
    runtime_lifetime = ExitStack()
    qwen_available = (model_cache/'models--Qwen--Qwen3-ASR-1.7B-hf'/'snapshots'/align.QWEN3_ASR_REVISION).is_dir()
    duration = identity['stream']['durationMs']
    timeout = int(config.get('analysisTimeoutMinutes', 180))*60
    views = audio_store.AlignmentAudioViews(config={**config, 'analysisTimeoutMinutes': timeout//60},
        args=SimpleNamespace(device='cuda'), workspace=workspace, video_id=video_id, duration_ms=duration,
        expected_mix_sha256=identity['sha256'], analysis_audio=audio,
        temp_root=evidence_root/'recording-content-recovery', release_model=owner.close,
        pinned_models={'demucsModelSha256': align.models.PINNED_DEMUCS_SHA256}, state={},
        runtime_cache={}, route_records={}, failure_codes={}, separate_vocals=align.separate_vocals_demucs,
        run_roformer=align._run_roformer_separation, worker_duration=align._worker_pcm_duration_ms)
    def fatal(error):
        raise_if_operation_stopped(error)
        if isinstance(error, (WorkerRetirementError, SongAttemptBlocked, LyricsContentBlocker)):
            raise error
    def get_audio(route):
        if route in failed_views:
            raise failed_views[route]
        try:
            if route == 'htdemucs-vocals':
                align.models.require_pinned_file(model_cache.parent/'torch/hub/checkpoints/955717e8-8726e21a.th',
                    align.models.PINNED_DEMUCS_SHA256, 'HTDemucs')
            else:
                align.models.require_roformer_wheel(Path(config['projectDir']).parent)
                align.models.roformer_assets(Path(config['projectDir']).parent, route)
            return views.get_endpoint_audio(route)
        except Exception as error:
            fatal(error)
            failed_views[route] = error
            # A failed cascade parent cannot be retried by another dependent view.
            for dependency in align.endpoints.ROFORMER_ROUTES:
                if dependency not in views.outputs:
                    source = align.endpoints.ROFORMER_ROUTES[dependency]['sourceViewId']
                    if dependency == route or source in failed_views:
                        failed_views[dependency] = error
            raise
    descriptors, retained_observations, completed_routes, candidate_order = [], set(), set(), {}
    def retain_candidate(path, family, route, variant, *, view_witness=None):
        from stemwise_engine.workflow_status import candidate_recognition_sha, recognition_view_witness
        from stemwise_engine.lyric_content import reference_adaptation
        raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=evidence_root)
        cache = parse_strict_json_bytes(raw)
        digest = hashlib.sha256(raw).hexdigest()
        if digest in retained_observations:
            return
        if view_witness is None:
            stored = audio_store.read_audio_provenance(workspace, video_id, duration,
                expected_mix_sha256=identity['sha256'])
            current = list(stored.get('audioViews', {}).values()) if isinstance(stored, dict) else []
            view_witness = recognition_view_witness(cache['provenance'], identity['sha256'], duration,
                evidence_root, mix=identity, descriptors=[*descriptors, *current])
        recognized_sha = candidate_recognition_sha(cache['provenance'], identity['sha256'], duration,
            evidence_root, witness=view_witness)
        words = _recognized(cache, recognized_sha, candidate_only=True)
        require_inputs()
        witness_reference = None
        if view_witness is not None:
            witness_raw = (json.dumps(view_witness, sort_keys=True, ensure_ascii=False,
                separators=(',', ':'))+'\n').encode('utf-8')
            witness_path = _publish_source_candidate(evidence_root/f'content-recovery-view-{digest}.private.json', witness_raw)
            witness_reference = {'path': witness_path.relative_to(evidence_root).as_posix(),
                'sha256': hashlib.sha256(witness_raw).hexdigest()}
        frozen = (path if path.name.startswith('content-recovery-') else
            _publish_source_candidate(evidence_root/f'content-recovery-{family}-{route}-{variant}.private.json', raw))
        reference = {'path': frozen.relative_to(evidence_root).as_posix(), 'sha256': hashlib.sha256(raw).hexdigest()}
        candidate = stage_audio_candidate(job_dir, evidence_root, video_id,
            {'sha256': identity['sha256'], 'durationMs': duration}, reference, audio_view_witness=witness_reference)
        adaptation = reference_adaptation(original, words)
        if adaptation is not None:
            candidate['referenceAdaptation'], projected = adaptation
            if projected is not None:
                payload = projected.encode('utf-8')
                projected_path = _publish_source_candidate(job_dir/'candidates/recording-performed-lyrics.txt', payload)
                candidate['referenceCandidate'] = {'path': projected_path.relative_to(job_dir).as_posix(),
                    'sha256': hashlib.sha256(payload).hexdigest()}
        result['candidates'].append(candidate)
        candidate_order[id(candidate)] = (('mix', 'htdemucs-vocals', *align.endpoints.ROFORMER_ROUTES).index(route),
            family == 'qwen3-asr', cache['provenance'].get('decoder', {}).get('beamSize') == 10 or 'precision' in variant, digest)
        retained_observations.add(digest)
    def attempt(family, route, action, *, lexical_precision=None):
        require_inputs()
        try:
            action()
        except Exception as error:
            fatal(error)
            require_inputs()
            errors.append(error)
            from stemwise_engine.private_diagnostics import retain_error, validate_references
            failure = None
            try:
                reference = retain_error(job_dir/f'recording-recovery-{family}-{route}.failure.private.txt', error,
                    context={'stage': 'independent-content-recognition', 'audioSha256': identity['sha256'],
                        'durationMs': duration, 'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                        'familyId': family, 'routeId': route})
                diagnostics = validate_references([reference])
                failure = {'familyId': family, 'routeId': route,
                    'code': str(diagnostic_code(error)), 'diagnostics': diagnostics}
                result.setdefault('routeFailures', []).append(failure)
            except Exception as retention_error:
                fatal(retention_error)
            if lexical_precision is not None and failure is not None:
                from stemwise_engine import whisper_lexical_candidate as lexical
                if lexical.is_empty_dtw(error):
                    attempt(family, route, lambda: recover_lexical(route, lexical_precision, failure))
    def lexical_provenance(route, precision, audio_identity):
        from stemwise_engine import whisper_lexical_candidate as lexical
        return lexical.provenance(align._whisper_cache_provenance(
            audio=align._cache_audio_identity(audio_identity), duration_ms=duration,
            model_name=align.DEFAULT_MODEL, model_cache=model_cache, device='cuda',
            compute_type=owner.compute_type, precision=precision, prompted=False), language)
    def recover_lexical(route, precision, failure):
        from stemwise_engine import whisper_lexical_candidate as lexical
        selected_audio, selected_identity = (audio, identity) if route == 'mix' else get_audio(route)[:2]
        path = lexical.cache_path(evidence_root, route, language, precision)
        lexical.recover(path=path, video_id=video_id, audio=selected_audio,
            expected_provenance=lexical_provenance(route, precision, selected_identity), model=get_model(),
            trigger={'reason': 'empty-dtw-word-alignment', 'failure': failure,
                'context': {'audioSha256': identity['sha256'], 'durationMs': duration,
                    'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                    'familyId': 'faster-whisper', 'routeId': route}},
            job_dir=job_dir, require_inputs=require_inputs)
        retain_candidate(path, 'faster-whisper', route, path.stem)
    def whisper(route, precision):
        if ('faster-whisper', route, precision) in completed_routes:
            return
        from stemwise_engine import whisper_lexical_candidate as lexical
        lexical_path = lexical.cache_path(evidence_root, route, language, precision)
        if lexical_path.is_file():
            stored = audio_store.read_audio_provenance(workspace, video_id, duration,
                expected_mix_sha256=identity['sha256'])
            selected_identity = (identity if route == 'mix' else
                stored.get('audioViews', {}).get(route) if isinstance(stored, dict) else None)
            if isinstance(selected_identity, dict) and lexical.load_cached(lexical_path,
                    lexical_provenance(route, precision, selected_identity), job_dir, video_id,
                    audio_sha=identity['sha256']) is not None:
                retain_candidate(lexical_path, 'faster-whisper', route, lexical_path.stem)
                return
        if route in ('mix', 'htdemucs-vocals'):
            align._load_or_transcribe(video_id=video_id, original=original, workspace=workspace,
                model_name=align.DEFAULT_MODEL, model_cache=model_cache, language=language,
                separate_vocals=route != 'mix', project_dir=Path(config['projectDir']), device='cuda',
                compute_type=owner.compute_type, duration_ms=duration, get_model=get_model,
                temp_root=evidence_root/'recording-content-recovery', timeout_seconds=timeout, rerun=False,
                precision=precision, use_approved_prompt=False, expected_mix_sha256=identity['sha256'],
                analysis_audio=audio, vocals_provider=lambda: get_audio(route)[0])
            variant = ('precision-' if precision else '')+'unprompted-'+('mix' if route == 'mix' else 'vocals')
        else:
            align._load_or_transcribe_roformer(video_id=video_id, original=original, route_id=route,
                workspace=workspace, model_name=align.DEFAULT_MODEL, model_cache=model_cache, language=language,
                device='cuda', compute_type=owner.compute_type, duration_ms=duration, get_model=get_model,
                get_audio=lambda: get_audio(route), rerun=False, expected_mix_sha256=identity['sha256'])
            variant = 'roformer-kim-vocals' if route == 'kimberley-jensen-roformer-vocals' else 'roformer-sucial-dereverb-v2'
        retain_candidate(workspace.transcript_path(video_id, language, variant), 'faster-whisper', route, variant)
    def qualified_qwen_runtime():
        nonlocal qwen_runtime, qwen_runtime_error, runtime_lease_started
        owner.close()
        if qwen_runtime_error is not None:
            raise qwen_runtime_error
        if not runtime_lease_started:
            runtime_lifetime.enter_context(align.environment_lease('main', builder=Path(config['projectDir'])))
            runtime_lease_started = True
        if qwen_runtime is None:
            project = Path(config['projectDir'])
            try:
                qwen_runtime = align._qwen3_asr_runtime_provenance(
                    align.environment_python('main', project), project/'src/qwen3_asr_transcribe.py',
                    device='cuda', timeout_seconds=timeout)
            except Exception as error:
                qwen_runtime_error = error
                raise
        return qwen_runtime
    def qwen(route):
        if ('qwen3-asr', route, None) in completed_routes:
            return
        qualified_qwen_runtime()
        request = dict(config=config, video_id=video_id, language=language, model_cache=model_cache,
            workspace=workspace, temp_dir=evidence_root/'recording-content-recovery', device='cuda',
            timeout_seconds=timeout, separate_language_cache=True, audio_view=route, before_run=owner.close,
            attempt_cache=views.runtime_cache, runtime_report=qwen_runtime)
        stored = audio_store.read_audio_provenance(workspace, video_id, duration, expected_mix_sha256=identity['sha256'])
        derived = stored.get('audioViews', {}).get(route) if isinstance(stored, dict) else None
        reused = False
        if isinstance(derived, dict):
            try:
                _, provenance, _ = _load_or_run_qwen3_structure_transcript(
                    **request, audio=audio, audio_identity=derived, cache_only=True)
                reused = True
            except FileNotFoundError:
                pass
        if not reused:
            derived_audio, derived, _ = get_audio(route)
            _, provenance, _ = _load_or_run_qwen3_structure_transcript(
                **request, audio=derived_audio, audio_identity=derived)
        hint = provenance.get('language') or 'auto'
        retain_candidate(evidence_root/f'qwen-structure-{route}-{hint}.private.json', 'qwen3-asr', route, hint)
    pending = None
    try:
        # Freeze retained observations before a missing sibling can rebuild their shared view index.
        from stemwise_engine.workflow_status import read_audio_view_witness, recognition_view_witness
        from stemwise_engine.lyric_content import _recognition_family
        paths = set(evidence_root.glob('content-recovery-*.private.json'))
        for route in ('mix', 'htdemucs-vocals', *align.endpoints.ROFORMER_ROUTES):
            for precision in (False, True):
                if route not in ('mix', 'htdemucs-vocals') and not precision:
                    continue
                variant = (('precision-' if precision else '')+'unprompted-'+('mix' if route == 'mix' else 'vocals')
                    if route in ('mix', 'htdemucs-vocals') else
                    'roformer-kim-vocals' if route == 'kimberley-jensen-roformer-vocals' else 'roformer-sucial-dereverb-v2')
                paths.add(workspace.transcript_path(video_id, language, variant))
            if route != 'mix':
                paths.update(evidence_root.glob(f'qwen-structure-{route}-*.private.json'))
            paths.update(evidence_root.glob(f'whisper-lexical-{route}-*.private.json'))
        references = {}
        job_path = job_dir/'job.json'
        if job_path.is_file():
            previous = read_bounded_json(job_path, max_bytes=16*1024*1024, root=job_dir).get('recordingRecoveryCandidates', {})
            for candidate in previous.get('candidates', []):
                reference = candidate.get('recognition', {})
                if isinstance(reference.get('path'), str):
                    path = evidence_root/reference['path']
                    paths.add(path)
                    references[path] = candidate
        observations = []
        stored = audio_store.read_audio_provenance(workspace, video_id, duration,
            expected_mix_sha256=identity['sha256'])
        if isinstance(stored, dict):
            descriptors.extend(stored.get('audioViews', {}).values())
        for path in sorted(paths):
            require_inputs()
            try:
                raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024, root=evidence_root)
                candidate = references.get(path, {})
                if candidate and hashlib.sha256(raw).hexdigest() != candidate['recognition']['sha256']:
                    continue
                cache = parse_strict_json_bytes(raw)
                provenance = cache['provenance']
                if cache.get('videoId') != video_id or not isinstance(provenance, dict):
                    continue
                family = 'faster-whisper' if cache.get('kind') == 'faster-whisper-untimed-lexical' else _recognition_family(cache)
                route = provenance.get('audio', {}).get('routeId', provenance.get('audioView', 'mix'))
                if family not in {'faster-whisper', 'qwen3-asr'} or route not in {'mix', 'htdemucs-vocals', *align.endpoints.ROFORMER_ROUTES}:
                    continue
                if family == 'qwen3-asr' and route == 'mix':
                    continue
                precision = None if family == 'qwen3-asr' else {5: False, 10: True}.get(provenance.get('decoder', {}).get('beamSize'))
                if family == 'faster-whisper' and precision is None:
                    continue
                _recognized(cache, provenance['audio']['sha256'], candidate_only=True)
                if family == 'qwen3-asr':
                    try:
                        runtime = qualified_qwen_runtime()
                        requested = language if language in runtime['supportedLanguages'] else None
                        fulfills_request = provenance.get('language') == requested and provenance.get('runtime') == runtime
                    except Exception as error:
                        fatal(error)
                        fulfills_request = False
                else:
                    expected = align._whisper_cache_provenance(audio=provenance['audio'], duration_ms=duration,
                        model_name=align.DEFAULT_MODEL, model_cache=model_cache, device='cuda',
                        compute_type=owner.compute_type, precision=precision, prompted=False)
                    if cache.get('kind') == 'faster-whisper-untimed-lexical':
                        from stemwise_engine.whisper_lexical_candidate import provenance as lexical_identity
                        expected = lexical_identity(expected, language)
                    fulfills_request = (provenance.get('language', cache.get('language')) == language
                        and provenance == expected)
                if cache.get('kind') == 'faster-whisper-untimed-lexical':
                    from stemwise_engine.whisper_lexical_candidate import validate_trigger
                    validate_trigger(cache, job_dir, audio_sha=identity['sha256'])
                witness_reference = candidate.get('audioViewWitness')
                if witness_reference is None:
                    interrupted = evidence_root/f'content-recovery-view-{hashlib.sha256(raw).hexdigest()}.private.json'
                    if interrupted.is_file():
                        witness_raw = read_bounded_regular_bytes(interrupted, max_bytes=16*1024*1024, root=evidence_root)
                        witness_reference = {'path': interrupted.name, 'sha256': hashlib.sha256(witness_raw).hexdigest()}
                witness = read_audio_view_witness(witness_reference, evidence_root)
                if witness is not None:
                    descriptors.extend(witness['audioViews'].values())
                descriptors.append(provenance['audio'])
                observations.append((path, family, route, precision, witness, cache, fulfills_request))
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                continue  # A stale optional cache cannot replace a qualified observation.
        for path, family, route, precision, witness, cache, fulfills_request in observations:
            try:
                if witness is None:
                    witness = recognition_view_witness(cache['provenance'], identity['sha256'], duration,
                        evidence_root, mix=identity, descriptors=descriptors)
                variant = (cache['provenance'].get('language') or 'auto') if family == 'qwen3-asr' else (
                    path.stem if cache.get('kind') == 'faster-whisper-untimed-lexical' else
                    ('precision-' if precision else '')+'unprompted-'+('mix' if route == 'mix' else 'vocals')
                    if route in ('mix', 'htdemucs-vocals') else
                    'roformer-kim-vocals' if route == 'kimberley-jensen-roformer-vocals' else 'roformer-sucial-dereverb-v2')
                retain_candidate(path, family, route, variant, view_witness=witness)
                if fulfills_request:
                    completed_routes.add((family, route, precision))
            except Exception as error:
                fatal(error)
        for precision in (False, True):
            attempt('faster-whisper', 'mix', lambda: whisper('mix', precision), lexical_precision=precision)
        separator = model_cache.parent/'torch/hub/checkpoints/955717e8-8726e21a.th'
        def has_vocal_observation():
            return any(candidate_order[id(candidate)][0] == 1 for candidate in result['candidates'])
        if not separator.is_file():
            fulfilled = all(('faster-whisper', 'htdemucs-vocals', precision) in completed_routes for precision in (False, True))
            if qwen_available:
                fulfilled = fulfilled and ('qwen3-asr', 'htdemucs-vocals', None) in completed_routes
            result['vocalRecovery'] = ({'status': 'complete'} if fulfilled else
                {'status': 'partial' if has_vocal_observation() else 'not-ready', 'reason': 'pinned-htdemucs-unavailable'})
        else:
            vocal_errors = len(errors)
            for precision in (False, True):
                attempt('faster-whisper', 'htdemucs-vocals', lambda: whisper('htdemucs-vocals', precision), lexical_precision=precision)
            if qwen_available:
                attempt('qwen3-asr', 'htdemucs-vocals', lambda: qwen('htdemucs-vocals'))
            result['vocalRecovery'] = {'status': ('failed' if not has_vocal_observation() else
                'partial' if len(errors) > vocal_errors else 'complete')}
        for route in align.endpoints.ROFORMER_ROUTES:
            definition = align.endpoints.ROFORMER_ROUTES[route]
            assets = model_cache.parent/'lyrics-roformer'/definition['directory']
            stored = audio_store.read_audio_provenance(workspace, video_id, duration,
                expected_mix_sha256=identity['sha256'])
            retained_view = isinstance(stored, dict) and route in stored.get('audioViews', {})
            if not retained_view and not all(path.is_file() for path in (
                    assets/definition['checkpoint'], assets/definition['config'],
                    model_cache.parent/'python-wheels/melband_roformer_infer-0.1.5-py3-none-any.whl')):
                continue
            attempt('faster-whisper', route, lambda: whisper(route, True), lexical_precision=True)
            if qwen_available:
                attempt('qwen3-asr', route, lambda: qwen(route))
        from stemwise_engine import nemo_lexical_candidate
        if nemo_lexical_candidate.eligible(original):
            def native_lexical():
                from stemwise_engine.alignment_phases import AudioIdentity
                owner.close()
                project = Path(config['projectDir'])
                stream = identity['stream']
                native_identity = AudioIdentity(identity['sha256'], identity['byteCount'],
                    stream['sampleRate'], stream['channelCount'], stream['frameCount']).to_record()
                with align.environment_lease(nemo_lexical_candidate.ENVIRONMENT, builder=project):
                    nemo_lexical_candidate.load_or_transcribe(project=project, job_dir=evidence_root,
                        video_id=video_id, audio=audio, audio_identity=native_identity, original=original,
                        timeout_seconds=timeout, require_inputs=require_inputs)
                retain_candidate(evidence_root/'nemo-lexical-ar-mix.private.json',
                    nemo_lexical_candidate.FAMILY, 'mix', 'ctc')
            attempt(nemo_lexical_candidate.FAMILY, 'mix', native_lexical)
        result['candidates'].sort(key=lambda candidate: candidate_order[id(candidate)])
        require_inputs()
        if errors and (not diagnostic or not result['candidates']):
            if result['candidates']:
                errors[0].partial_candidates = result
            raise errors[0]
    except BaseException as error:
        pending = error
        raise
    finally:
        try:
            try:
                views.close()
            except BaseException as error:
                raise_with_cleanup_failure(pending, error)
        finally:
            cleanup_failure = sys.exc_info()[1]
            try:
                runtime_lifetime.close()
            except BaseException as error:
                raise_with_cleanup_failure(cleanup_failure, error)
    return result


def recover_content(config, root, video_id, audio, expected_sha):
    """Collect private alternatives for the exact retained dispute without adjudicating it."""
    from stemwise_engine.lyric_content import validate_recording_review_inputs
    video_id = validate_video_id(video_id)
    job_dir = root/'jobs'/video_id
    workspace = AlignmentWorkspace(root, workspace_root=Path(config['projectDir']).parent.parent)
    job_raw = read_bounded_regular_bytes(job_dir/'job.json', max_bytes=16*1024*1024, root=job_dir)
    job = parse_strict_json_bytes(job_raw)
    inputs = validate_recording_review_inputs(job_dir, workspace.job_dir(video_id), job.get('recordingEvidenceInputs'))
    if inputs['audioSha256'] != expected_sha:
        raise LyricsContentBlocker('recording-adjudication-input-changed')
    draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
    original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'text': draft,
        'language': metadata.get('originalLanguage') or 'und', 'script': metadata.get('originalScript'),
        'componentLanguages': metadata.get('componentLanguages', [])}
    identity = file_identity(audio)
    if identity['sha256'] != expected_sha:
        raise LyricsContentBlocker('recording-adjudication-input-changed')
    identity['canonicalTimeTransform'] = identity_transform(identity['stream'])
    primary = workspace.job_dir(video_id)/inputs['recognition']['path']
    scope = job.get('lyricContentLimitation', {}).get('scope',
        job.get('recordingRecoveryCandidates', {}).get('scope', {}))
    _recover_local_discrepancies(config, workspace, video_id, audio, identity, original,
        list(performed_line_indices(draft)),
        draft_sha, metadata_sha, primary, scope)
    packet = collect_disagreement_candidate(config, workspace, video_id, audio, identity, original,
        draft_sha, metadata_sha, primary, scope, diagnostic=True)
    if packet is None or not packet['candidates']:
        raise ContentEvidenceUnavailable('content-evidence-unavailable')
    if (read_bounded_regular_bytes(job_dir/'job.json', max_bytes=16*1024*1024, root=job_dir) != job_raw
            or validate_recording_review_inputs(job_dir, workspace.job_dir(video_id), inputs) != inputs):
        raise LyricsContentBlocker('recording-adjudication-input-changed')
    return {'status': 'content-recovery-complete', 'recordingInputs': inputs, 'recoveryCandidates': packet}


def _recover_local_discrepancies(config, workspace, video_id, audio, identity, original,
                                 indices, draft_sha, metadata_sha, contrary, scope):
    from stemwise_engine import local_recovery_evidence
    from stemwise_engine.alignment_attempt import SongAttemptBlocked
    from stemwise_engine.common import WorkerRetirementError
    from stemwise_engine.provider_operations import raise_if_operation_stopped
    from stemwise_engine.private_diagnostics import retain_error

    evidence = workspace.job_dir(video_id)
    request = evidence/local_recovery_evidence.INPUT
    if not request.exists() and not request.is_symlink():
        return
    try:
        local_recovery_evidence.run(config, workspace, video_id, audio, identity, original,
                                    indices, draft_sha, metadata_sha, contrary, scope)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
        raise_if_operation_stopped(error)
        if isinstance(error, (WorkerRetirementError, SongAttemptBlocked, LyricsContentBlocker)):
            raise
        try:
            retain_error(evidence/'local-lyric-recovery.failure.private.txt', error,
                context={'stage': 'local-lyric-recovery', 'audioSha256': identity['sha256'],
                         'draftSha256': draft_sha, 'metadataSha256': metadata_sha})
        except (OSError, ValueError, RuntimeError) as retention_error:
            raise_if_operation_stopped(retention_error)
            if isinstance(retention_error, (WorkerRetirementError, SongAttemptBlocked)):
                raise
            print('optional-local-lyric-recovery-diagnostics-unavailable', file=sys.stderr)


def compare(config: dict, root: Path, video_id: str, audio: Path, expected_sha: str) -> dict:
    video_id = validate_video_id(video_id)
    workspace = AlignmentWorkspace(root, workspace_root=Path(config["projectDir"]).parent.parent)
    job_dir = root / "jobs" / video_id
    draft, draft_sha, metadata, metadata_sha = read_recording_draft(job_dir)
    language = canonical_language(metadata.get("originalLanguage"))
    original = {"id": "original", "kind": "original", "format": "plain", "text": draft,
                "language": metadata.get("originalLanguage") or "und",
                "script": metadata.get("originalScript"),
                "componentLanguages": metadata.get("componentLanguages", [])}
    identity = file_identity(audio)
    if identity["sha256"] != expected_sha:
        raise ValueError("recording evidence audio changed")
    identity["canonicalTimeTransform"] = identity_transform(identity["stream"])
    from stemwise_engine.spoken_targets import load as load_spoken_targets
    indices = list(performed_line_indices(draft))
    spoken = load_spoken_targets(job_dir, video_id, original, indices, expected_sha)
    if spoken is not None:
        original = {**original, '_spokenTargets': spoken}
    from stemwise_engine.alignment_attempt import current_song_ledger
    ledger = current_song_ledger()
    request = dict(
        config=config, video_id=video_id, language=language, audio=audio, audio_identity=identity,
        model_cache=Path(config["projectDir"]).parent / "model-cache/lyrics-alignment",
        workspace=workspace, temp_dir=workspace.job_dir(video_id) / "recording-review",
        device="cuda", timeout_seconds=int(config.get("analysisTimeoutMinutes", 180))*60,
        separate_language_cache=True,
    )
    request['attempt_cache'] = {}
    adapter = None
    if language in {'ku', 'zu'}:
        adapters = mms_asr.adapters_for(original)
        if not adapters:
            raise ValueError('Native recognition requires the supported declared script')
        adapter = adapters[0]
    def transcribe(**arguments):
        return (_native_transcript(arguments, adapter) if adapter else
                _load_or_run_qwen3_structure_transcript(**arguments))
    def transcript_path(view, hint=language):
        if adapter:
            name = f'native-asr-{adapter}-{view}.private.json'
        elif view == 'mix':
            name = f'qwen-structure-{hint}.private.json' if hint else 'qwen-structure.private.json'
        else:
            name = f'qwen-structure-{view}-{hint or "auto"}.private.json'
        return workspace.job_dir(video_id) / name
    def score(text, view, sources=None):
        return replace(score_structure_witness(spoken.matching_text() if spoken else draft,
                       indices if sources is None else sources, text),
                       family='mms-asr' if adapter else 'qwen3-asr',
                       route=f'mms-asr:{adapter}:{view}' if adapter else QWEN3_ASR_SOURCE_ROUTES[view])
    adjudication = _adjudication(root, video_id)
    if adjudication is not None:
        request['cache_only'] = True  # A review of retained ASR cannot start replacement inference.
        if language is None:
            reviewed_path = adjudication[0]['recognition']['path']
            request['language'] = next((hint for hint in transcription_languages(original)
                if qwen_mix_cache_name(hint) == reviewed_path), None)
    try:
        transcript, provenance, reused = transcribe(**request)
    except (OSError, ValueError) as error:
        if adjudication is not None:
            raise ContentEvidenceUnavailable() from error
        raise
    indices = list(performed_line_indices(draft))
    # Pre-editorial drafts do not yet have an immutable canonical JSON binding.
    resolution = None
    primary_path = transcript_path('mix', provenance.get('language', request['language']))
    path = primary_path
    source_decision = (_selected_reference_decision(root, video_id, original, indices, identity)
        if adjudication is None else None)
    if adjudication is None:
        try:
            require_exact_sequence(original, indices, transcript, None, None)
            secondary = workspace.transcript_path(video_id, language, 'unprompted-mix')
            if secondary.is_file() and source_decision is None:
                try:
                    cache = read_bounded_json(secondary, max_bytes=16*1024*1024,
                        root=workspace.job_dir(video_id))
                    if cache.get('provenance', {}).get('durationMs') != identity['stream']['durationMs']:
                        raise ValueError('Retained recognition duration differs')
                    words = _recognized(cache, expected_sha)
                except (OSError, ValueError, KeyError, TypeError, AttributeError):
                    print('optional-retained-content-cache-ineligible', file=sys.stderr)
                else:
                    path = secondary
                    require_exact_sequence(original, indices, words, None, None)
        except LyricsContentBlocker as error:
            if path.is_file():
                _recover_local_discrepancies(config, workspace, video_id, audio, identity, original,
                    indices, draft_sha, metadata_sha, path, getattr(error, 'discrepancy_scope', {}))
                try:
                    recovery = collect_disagreement_candidate(config, workspace, video_id, audio, identity,
                        original, draft_sha, metadata_sha, path, getattr(error, 'discrepancy_scope', {}))
                    if recovery is not None:
                        error.recovery_candidates = recovery
                except Exception as optional_error:
                    from stemwise_engine.provider_operations import raise_if_operation_stopped
                    from stemwise_engine.alignment_attempt import SongAttemptBlocked
                    from stemwise_engine.common import WorkerRetirementError, diagnostic_code
                    raise_if_operation_stopped(optional_error)
                    if isinstance(optional_error, (WorkerRetirementError, SongAttemptBlocked, LyricsContentBlocker)):
                        raise
                    if hasattr(optional_error, 'partial_candidates'):
                        error.recovery_candidates = optional_error.partial_candidates
                    error.recovery_diagnostic = diagnostic_code(optional_error)
                    error.recovery_error = optional_error
            from stemwise_engine.lyric_content import investigate_retained_content
            try:
                resolution = (investigate_retained_content(original, indices, expected_sha,
                    identity['stream']['durationMs'], evidence_root=workspace.job_dir(video_id), contrary_path=path)
                    if path.is_file() else None)
            except (OSError, ValueError, KeyError, TypeError):
                print('optional-content-investigation-unavailable', file=sys.stderr)
                resolution = None
            if resolution is not None:
                error.content_investigation = resolution
                resolution = None
            if ledger is not None:
                ledger.record['enhancementDisposition'] = {'code': 'lyrics-content-unresolved',
                    'reason': 'recording-sequence-not-established',
                    'scope': getattr(error, 'discrepancy_scope', {})}
                ledger.save()
            try:
                raw = read_bounded_regular_bytes(path, max_bytes=16*1024*1024,
                                                root=workspace.job_dir(video_id))
                error.recording_inputs = recording_review_input_reference(job_dir, workspace.job_dir(video_id), expected_sha,
                    {'path': path.name, 'sha256': hashlib.sha256(raw).hexdigest()})
            except (OSError, ValueError, TypeError):
                pass  # Preserve the content rejection even when no usable reference can be exposed.
            if hasattr(error, 'recovery_error'):
                from stemwise_engine.private_diagnostics import retain_error
                try:
                    reference = retain_error(job_dir/'recording-recovery.failure.private.txt', error.recovery_error,
                        context={'stage': 'independent-content-recognition', 'audioSha256': expected_sha,
                            'durationMs': identity['stream']['durationMs'], 'draftSha256': draft_sha,
                            'metadataSha256': metadata_sha,
                            'primaryRecognition': getattr(error, 'recording_inputs', {}).get('recognition')})
                    error.recovery_private_diagnostics = [reference]
                except Exception as retention_error:
                    raise_if_operation_stopped(retention_error)
                    if isinstance(retention_error, (WorkerRetirementError, SongAttemptBlocked)):
                        raise
                    error.recovery_private_diagnostics = []
            raise
    else:
        review, review_sha = adjudication
        reviewed_transcript = transcript
        if review['recognition']['path'] != path.relative_to(workspace.job_dir(video_id)).as_posix():
            require_exact_sequence(original, indices, transcript, None, None)
            path = workspace.job_dir(video_id)/review['recognition']['path']
            reviewed_transcript = _recognized_text(_recognized(read_bounded_json(path,
                max_bytes=16*1024*1024, root=workspace.job_dir(video_id)), expected_sha))
        resolution = require_recording_adjudication(review, video_id=video_id,
            original=original, indices=indices, transcript=reviewed_transcript,
            audio_sha=expected_sha, duration_ms=identity['stream']['durationMs'],
            draft_sha=draft_sha, metadata_sha=metadata_sha,
            evidence_path=path, evidence_root=workspace.job_dir(video_id))
        resolution['reviewSha256'] = review_sha
    result = {
        "reusedTranscript": reused,
        "privateTranscript": str(primary_path),
        "additionalEvidence": [],
    }
    if source_decision is not None:
        result['sourceRecordingDecision'] = source_decision
        result['privateTranscript'] = str(workspace.job_dir(video_id)/source_decision['path'])
    if resolution is None:
        result['structureSupport'] = asdict(score(transcript, 'mix'))
        if not result["structureSupport"]["accepted"]:
            raise LyricsContentBlocker("content-support-uncertain")
    else:
        result['contentResolution'] = resolution
        if _adjudication(root, video_id) != adjudication:
            raise LyricsContentBlocker('recording-adjudication-changed')
    if read_recording_draft(job_dir)[1::2] != (draft_sha, metadata_sha):
        raise LyricsContentBlocker('recording-adjudication-input-changed')
    if file_identity(audio)["sha256"] != expected_sha:
        raise ValueError("recording evidence audio changed during recognition")
    return result


def main(argv=None):
    arguments = list(sys.argv[1:] if argv is None else argv)
    candidate = arguments[:1] == ['--candidate']
    recovery = arguments[:1] == ['--recover-content']
    config, root, video_id, audio, expected_sha = arguments[1:] if candidate or recovery else arguments
    try:
        result = (propose if candidate else recover_content if recovery else compare)(
            load_config(Path(config)), Path(root), video_id, Path(audio), expected_sha)
    except LyricsContentBlocker as error:
        result = {'status': 'lyrics-content-unresolved', 'reasons': list(error.reasons)}
        if hasattr(error, 'recording_inputs'):
            result['recordingInputs'] = error.recording_inputs
        if hasattr(error, 'discrepancy_scope'):
            result['discrepancyScope'] = error.discrepancy_scope
        if hasattr(error, 'content_investigation'):
            result['contentInvestigation'] = error.content_investigation
        if hasattr(error, 'recovery_candidates'):
            result['recoveryCandidates'] = error.recovery_candidates
        if hasattr(error, 'recovery_diagnostic'):
            result['recoveryDiagnostic'] = error.recovery_diagnostic
            result['recoveryPrivateDiagnostics'] = error.recovery_private_diagnostics
        print(json.dumps(result))
        return 1
    except ContentEvidenceUnavailable as error:
        print(json.dumps({'status': 'content-evidence-unavailable', 'reasons': list(error.reasons)}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    from stemwise_engine.worker_protocol import run_worker_main
    raise SystemExit(run_worker_main(main))
