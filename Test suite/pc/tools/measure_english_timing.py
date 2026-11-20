"""Explicit private native-model measurement; measurements alone qualify no model.

Run measurements/assessment in managed main; the guarded comparison uses managed
lyrics with main Sylla subprocesses. Annotation boundaries are scorer inputs only.
MMS/Wav use their unchanged bounded batch workers; OWSM/Sylla stay serial.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
from pathlib import Path

from stemwise_engine.common import (atomic_write_bytes, atomic_write_json, heavy_worker_stage, WorkerTemporaryDirectory,
    read_bounded_json, read_bounded_regular_bytes, run_process_tree, sha256_file)
from stemwise_engine.ctc_worker import canonical_sha256
from stemwise_engine.contract import MAX_JSON_BYTES
from stemwise_engine.runtime_environment import environment_lease, environment_python
from stemwise_engine.worker_protocol import (parse_worker_json, require_worker_success, raise_if_worker_stopped,
    raise_worker_failure, WorkerProcessError, WorkerReasonError)

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT/'PC side/stemwise-playlist-builder'
FAMILIES = ('mms', 'wav2vec2', 'owsm', 'sylla')
SYLLA_PLAN_SHA256 = '7906942aed0ec642675fb78c550febc973c25df77d9ce1c6b2d2ef701b284613'
GUARDED_PAIR = ('hila-give-me-the-same', 'ridgway-fire-inside')
GUARDED_SCOPE = 'private-whisper-guarded-sylla-development-comparison'


def member(root, name):
    path = (root/name).resolve(strict=True)
    path.relative_to(root.resolve(strict=True))
    if not path.is_file():
        raise ValueError('Measurement input is not a file')
    return path


def model_plan(case, root):
    """The worker side receives only exact audio, plain text and full-crop geometry."""
    from stemwise_engine.alignment_audio import file_identity, identity_transform
    from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping, lexical_mapping_identity
    from stemwise_engine.lyrics_align_pc import _forced_alignment_request
    from stemwise_engine.lyrics_normalize import physical_lines
    from stemwise_engine.lyrics_tokenization import display_words
    audio, lyrics = member(root, case['audio']), member(root, case['modelInputLyrics'])
    raw = read_bounded_regular_bytes(lyrics, max_bytes=MAX_JSON_BYTES, root=root)
    identity = file_identity(audio)
    if (identity['sha256'] != case['audioSha256'] or hashlib.sha256(raw).hexdigest() != case['modelInputLyricsSha256']
            or identity['stream'] != {'sampleRate': case['sampleRate'], 'channelCount': case['channels'],
                'frameCount': case['frames'], 'durationMs': case['durationMs']}
            or case['language'] != 'en'):
        raise ValueError('Measurement audio/text identity changed')
    identity['canonicalTimeTransform'] = identity_transform(identity['stream'])
    text = raw.decode('utf-8')
    original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'script': 'Latn', 'text': text}
    sources = [i for i, line in enumerate(physical_lines(text)) if line.strip()]
    # Private request namespace only: no workflow job, content approval or public song.
    video = hashlib.sha256(case['id'].encode('utf-8')).hexdigest()[:11]
    mapping = build_lexical_occurrence_mapping(video, original, sources)
    words = [display_words(physical_lines(text)[i]) for i in sources]
    request, _, _, _ = _forced_alignment_request(video, sources, mapping, words,
        duration_ms=identity['stream']['durationMs'])
    return {'id': case['id'], 'videoId': video, 'audio': str(audio), 'audioIdentity': identity,
        'lyrics': str(lyrics), 'lyricsSha256': hashlib.sha256(raw).hexdigest(),
        'mapping': mapping, 'mappingIdentity': lexical_mapping_identity(mapping),
        'request': request, 'displayWords': words}


def check_inputs(plan):
    from stemwise_engine.alignment_audio import file_identity
    actual = file_identity(Path(plan['audio']))
    if (actual != {key: value for key, value in plan['audioIdentity'].items() if key != 'canonicalTimeTransform'}
            or sha256_file(Path(plan['lyrics'])) != plan['lyricsSha256']):
        raise ValueError('Measurement input changed during execution')


def validate_native(plan, value, family):
    from stemwise_engine import lyrics_align_pc as align
    if family not in {'mms', 'wav2vec2'}:
        raise ValueError('Not a native batch family')
    request = plan['request']
    align._validate_numeric_mms_result(value, expected_sources=[line['sourcePhysicalIndex'] for line in request['lines']],
        duration_ms=request['durationMs'], expected_line_ids=[line['lineId'] for line in request['lines']],
        expected_word_ids=[[word['wordId'] for word in line['words']] for line in request['lines']],
        allow_tied_starts=family == 'mms', expected_format_version=5 if family == 'mms' else 4,
        expected_engine='torchaudio-mms-fa' if family == 'mms' else 'wav2vec2-ctc-forced-alignment',
        expected_segments=request['segments'])
    return value


def score(case, plan, lines):
    """Reuse the joint metric on a complete native projection, never claim an export."""
    from stemwise_engine.lyrics_normalize import physical_lines
    from stemwise_engine.lyrics_qualification import joint_word_metrics
    from stemwise_engine.lyrics_tokenization import display_words
    text = Path(plan['lyrics']).read_text(encoding='utf-8')
    physical = physical_lines(text)
    reference = {'recordingSha256': plan['audioIdentity']['sha256'],
        'lexicalAuthoritySha256': plan['mappingIdentity']['lexicalAuthoritySha256'],
        'durationMs': plan['request']['durationMs'], 'occurrences': []}
    required, observed, starts, ends, sustained = {}, [], [], [], []
    if len(case['lines']) != len(plan['request']['lines']) or len(lines) != len(case['lines']):
        raise ValueError('Measurement line coverage differs')
    for annotated, target, actual in zip(case['lines'], plan['request']['lines'], lines):
        display = physical[target['sourcePhysicalIndex']]
        ranges = display_words(display)
        if (annotated['text'] != display or actual['lineId'] != target['lineId']
                or len(annotated['words']) != len(ranges) or len(actual['words']) != len(ranges)
                or [(word['startUtf16'], word['endUtf16']) for word in annotated['words']]
                    != [(a, b) for a, b, _ in ranges]):
            raise ValueError('Measurement reference/observed word ownership differs')
        line_id = hashlib.sha256(target['lineId'].encode('utf-8')).hexdigest()
        expected_words, actual_words = [], []
        for label, wanted, word, (a, b, _) in zip(annotated['words'], target['words'], actual['words'], ranges):
            if word['wordId'] != wanted['wordId']:
                raise ValueError('Measurement observed word ID differs')
            owned = {'wordIdSha256': hashlib.sha256(wanted['wordId'].encode('utf-8')).hexdigest(),
                'startUtf16': a, 'endUtf16': b}
            if not 0 <= label['startMs'] < label['endMs'] <= reference['durationMs']:
                raise ValueError('Measurement reference boundary is invalid')
            expected_words.append({**owned, 'startMs': label['startMs'], 'endMs': label['endMs']})
            actual_words.append({**owned, 'startMs': word['startMs'], 'endMs': word['endMs']})
            starts.append(abs(word['startMs']-label['startMs']))
            ends.append(abs(word['endMs']-label['endMs']))
            if label['endMs']-label['startMs'] >= 1000:
                sustained.append((starts[-1], ends[-1]))
        required[line_id] = {'text': display, 'words': [
            {key: word[key] for key in ('wordIdSha256', 'startUtf16', 'endUtf16')} for word in expected_words]}
        reference['occurrences'].append({'occurrenceIdSha256': line_id, 'startMs': expected_words[0]['startMs'],
            'endMs': expected_words[-1]['endMs'], 'words': expected_words})
        observed.append({'occurrenceIdSha256': line_id, 'startMs': actual['startMs'],
            'endMs': actual['endMs'], 'words': actual_words})
    # Complete_export here enables the existing arithmetic for the validated native
    # projection. No package was built or admitted; that distinction is explicit below.
    metrics = joint_word_metrics(reference, {**reference, 'occurrences': observed}, required, complete_export=True)
    from stemwise_engine.alignment_endpoints import WORD_START_AGREEMENT_MS, WORD_END_AGREEMENT_MS
    count = sum(len(line['words']) for line in required.values())
    held_hits = sum(start <= WORD_START_AGREEMENT_MS and end <= WORD_END_AGREEMENT_MS
                    for start, end in sustained)
    return {'joint': metrics, 'wordCount': count,
        'jointWordRate': metrics['verifiedWords']/count,
        'onsetsWithin300Ms': sum(value <= WORD_START_AGREEMENT_MS for value in starts),
        'offsetsWithin400Ms': sum(value <= WORD_END_AGREEMENT_MS for value in ends),
        'onsetWithinToleranceRate': sum(value <= WORD_START_AGREEMENT_MS for value in starts)/count,
        'offsetWithinToleranceRate': sum(value <= WORD_END_AGREEMENT_MS for value in ends)/count,
        'sustainedWordCount': len(sustained),
        'sustainedWordsWithinTolerance': held_hits,
        'sustainedJointWordRate': held_hits/len(sustained) if sustained else None,
        'publicExportVerified': False, 'qualificationClaim': False}


def aggregate(cases, rows):
    """Missing, failed and abstained observations keep every reference word."""
    total = measured = joint = starts = ends = held = held_hits = failures = 0
    for case in cases:
        labels = [word for line in case['lines'] for word in line['words']]
        total += len(labels)
        held += sum(word['endMs']-word['startMs'] >= 1000 for word in labels)
        metrics = rows.get(case['id'], {}).get('metrics')
        if metrics is None:
            failures += 1
            continue
        if metrics['wordCount'] != len(labels):
            raise ValueError('Measurement denominator differs from the complete reference')
        measured += metrics['wordCount']
        joint += metrics['joint']['verifiedWords']
        starts += metrics['onsetsWithin300Ms']
        ends += metrics['offsetsWithin400Ms']
        held_hits += metrics['sustainedWordsWithinTolerance']
    return {'wordCount': total, 'measuredWordCount': measured, 'jointWithinTolerance': joint,
        'onsetsWithin300Ms': starts, 'offsetsWithin400Ms': ends,
        'jointWordRate': joint/total if total else None,
        'onsetRate': starts/total if total else None, 'offsetRate': ends/total if total else None,
        'sustainedWordCount': held, 'sustainedWordsWithinTolerance': held_hits,
        'sustainedJointWordRate': held_hits/held if held else None,
        'technicalFailureCases': failures}


def assess_sylla(reference, report_path, frozen_plan):
    """Recompute the frozen measurement criteria; integration authority is separate."""
    from stemwise_engine import sylla_forced_alignment as worker
    from stemwise_engine.common import diagnostic_code
    from stemwise_engine.future_song import FutureSongWorkflow
    policy_raw = read_bounded_regular_bytes(frozen_plan, max_bytes=MAX_JSON_BYTES, root=frozen_plan.parent)
    if hashlib.sha256(policy_raw).hexdigest() != SYLLA_PLAN_SHA256:
        raise ValueError('Sylla qualification plan differs from the predeclared plan')
    policy = parse_worker_json(policy_raw)
    reference_raw = read_bounded_regular_bytes(reference, max_bytes=MAX_JSON_BYTES, root=reference.parent)
    collection = parse_worker_json(reference_raw)
    cases = collection['musical']
    if (not isinstance(cases, list) or len(cases) != len(policy['heldOutIds'])
            or {case['id'] for case in cases} != set(policy['heldOutIds'])
            or any(case.get('heldOutByUs') is not True or case.get('language') != 'en' for case in cases)
            or sum(len(line['words']) for case in cases for line in case['lines']) != policy['expectedWordCount']):
        raise ValueError('Sylla held-out identities or complete word denominator changed')
    reference_sha = hashlib.sha256(reference_raw).hexdigest()
    if reference_sha != policy['referenceSha256']:
        raise ValueError('Sylla held-out reference differs from the frozen annotations')
    report_raw = read_bounded_regular_bytes(report_path, max_bytes=MAX_JSON_BYTES, root=report_path.parent)
    report = parse_worker_json(report_raw)
    if report['referenceSha256'] != reference_sha:
        raise ValueError('Sylla measurement reference changed')
    recorded = report.get('families', {}).get('sylla', {})
    if (not isinstance(recorded, dict)
            or set(recorded)-set(policy['heldOutIds'])-{'aggregate', 'technicalFailure'}):
        raise ValueError('Sylla measurement contains undeclared cases')
    model = PROJECT.parent/'model-cache/lyrics-sylla/sylla-600m-v1'/worker.MODEL_SPEC['revision']
    model_error = None
    try:
        model_record = worker.model_identity(model)
        vocabulary = read_bounded_json(model/'vocab.json', max_bytes=MAX_JSON_BYTES, root=model)
        producer = worker.producer_identity()
    except Exception as error:
        FutureSongWorkflow._raise_if_prediction_stopped(error)
        model_error = error
    rows, failures, observed_tuple = {}, [], None
    for case in cases:
        identity = case['id']
        try:
            if model_error is not None:
                raise model_error
            plan = model_plan(case, reference.parent)
            row = recorded.get(identity)
            if not isinstance(row, dict) or not isinstance(row.get('output'), str):
                raise ValueError('Sylla observation is missing or abstained')
            path = member(ROOT, row['output'])
            raw = read_bounded_regular_bytes(path, max_bytes=MAX_JSON_BYTES, root=path.parent)
            if hashlib.sha256(raw).hexdigest() != row.get('outputSha256'):
                raise ValueError('Sylla measured output changed')
            value = parse_worker_json(raw)
            binding_path = path.with_name('binding.private.json')
            binding_raw = read_bounded_regular_bytes(binding_path, max_bytes=MAX_JSON_BYTES, root=path.parent)
            binding = parse_worker_json(binding_raw)
            if binding != {'audio': plan['audioIdentity'], 'request': plan['request'], 'model': model_record,
                    'producer': producer, 'runtime': value['runtime'], 'decoder': worker.DECODER}:
                raise ValueError('Sylla measured input/model/runtime binding changed')
            worker.validate_result(value, plan['request'], plan['audioIdentity']['sha256'], model_record,
                vocabulary, binding['runtime'], plan['audioIdentity']['stream'], language='en')
            actual_tuple = {key: binding[key] for key in ('model', 'producer', 'runtime', 'decoder')}
            if observed_tuple is not None and actual_tuple != observed_tuple:
                raise ValueError('Sylla held-out observations mix numerical producer/runtime tuples')
            observed_tuple = actual_tuple
            metrics = score(case, plan, value['lines'])
            check_inputs(plan)
            rows[identity] = {'output': row['output'], 'outputSha256': row['outputSha256'],
                'bindingSha256': hashlib.sha256(binding_raw).hexdigest(), 'metrics': metrics}
            if metrics['joint']['verifiedWords']/metrics['wordCount'] < policy['minimum']['perRecordingJointWordRate']:
                failures.append(identity+':per-recording-joint-rate')
        except Exception as error:
            FutureSongWorkflow._raise_if_prediction_stopped(error)
            rows[identity] = {'technicalFailure': diagnostic_code(error)}
            failures.append(identity+':missing-or-invalid-observation')
    totals = aggregate(cases, rows)
    for name in ('jointWordRate', 'onsetRate', 'offsetRate', 'sustainedJointWordRate'):
        if totals[name] is None or totals[name] < policy['minimum'][name]:
            failures.append(name)
    if totals['technicalFailureCases'] > policy['maximum']['technicalFailures']:
        failures.append('technicalFailures')
    if sha256_file(reference) != reference_sha or sha256_file(frozen_plan) != SYLLA_PLAN_SHA256:
        raise ValueError('Sylla evaluation inputs changed during assessment')
    return {'scope': policy['scope'], 'planSha256': SYLLA_PLAN_SHA256, 'referenceSha256': reference_sha,
        'measurementSha256': hashlib.sha256(report_raw).hexdigest(), 'cases': rows, 'aggregate': totals, 'failures': failures,
        'observedProducer': observed_tuple,
        'qualificationClaim': False, 'publicExportVerified': False,
        'integrationGatesEvaluated': False, 'trainingOverlap': policy['trainingOverlap']}


def batch_rows(manifest, reply, plans):
    """Validate the real multi-context envelope, then use the ordinary row validator."""
    from stemwise_engine.lyrics_align_pc import _read_alignment_batch_reply
    if (not isinstance(reply, dict) or set(reply) != {'formatVersion', 'batchSha256', 'contexts'}
            or reply['formatVersion'] != 1 or reply['batchSha256'] != canonical_sha256(manifest)
            or not isinstance(reply['contexts'], list) or len(reply['contexts']) > len(manifest['contexts'])):
        raise ValueError('Measurement batch reply changed')
    for context, row, plan in zip(manifest['contexts'], reply['contexts'], plans):
        projected_manifest = {**manifest, 'contexts': [context]}
        projected_reply = {**reply, 'batchSha256': canonical_sha256(projected_manifest), 'contexts': [row]}
        _read_alignment_batch_reply(projected_manifest, projected_reply, plan['request'])
        yield context, plan


def native_batch(plans, family, directory, config, timeout, device, *, on_result=None):
    from stemwise_engine import lyrics_align_pc as align
    from stemwise_engine import ctc_worker
    project = Path(config['projectDir'])
    python = environment_python('main', project)
    helper = project/'src'/('mms_forced_alignment.py' if family == 'mms' else 'wav2vec2_forced_alignment.py')
    runtime = (align._mms_runtime_provenance if family == 'mms' else align._wav2vec2_runtime_provenance)(
        python, helper, device=device, timeout_seconds=timeout, diagnostic_path=directory/'runtime.failure.private.txt')
    if family == 'mms':
        from stemwise_engine import alignment_models as models
        model = project.parent/'model-cache/torch/hub/checkpoints/model.pt'
        models.require_pinned_file(model, align.PINNED_MMS_SHA256, 'MMS_FA checkpoint')
        model_record = {'sha256': align.PINNED_MMS_SHA256}
        helpers = {helper.name: ctc_worker.helper_source_identity(helper)}
        arguments = []
    else:
        model, model_record = align._wav2vec2_model_assets(config, 'en')
        helpers = align._wav2vec2_helper_hashes(project)
        arguments = ['--model', str(model), '--language', model_record.get('phonemizerLanguage') or 'en']
    pending, results = [], {}
    for plan in plans:
        check_inputs(plan)
        binding = {'audio': plan['audioIdentity'], 'request': plan['request'], 'model': model_record,
            'runtime': runtime, 'helpers': helpers, 'device': device}
        folder = directory/canonical_sha256(binding)
        folder.mkdir(parents=True, exist_ok=True)
        packet_path = folder/'result.private.json'
        if packet_path.exists():
            packet = read_bounded_json(packet_path, max_bytes=MAX_JSON_BYTES, root=folder)
            if packet['binding'] != binding:
                raise ValueError('Measurement native cache binding changed')
            results[plan['id']] = (validate_native(plan, packet['result'], family), True, str(packet_path))
            if on_result is not None:
                on_result(plan, results[plan['id']])
            continue
        request_path = folder/'request.private.json'
        atomic_write_json(request_path, plan['request'])
        if family == 'wav2vec2':
            capability = align._run_numerical_process([str(python), '-B', '-m', 'stemwise_engine.'+helper.stem,
                '--check-targets', '--request', str(request_path), '--output', str(folder/'capability.private.json'),
                *arguments], builder=project, timeout=min(timeout, 120), capture_output=True, text=True, check=False)
            require_worker_success(capability, code='measurement-target-capability', diagnostic_path=directory/'failure.private.txt')
            value = read_bounded_json(folder/'capability.private.json', max_bytes=MAX_JSON_BYTES, root=folder)
            if value != {'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                    'requestSha256': canonical_sha256(plan['request']), 'supported': True}:
                raise ValueError('Measurement static target capability differs')
        # The key binds the actual producer/audio/runtime; annotation times never enter it.
        emission_key = align._emission_cache_key({'audio': plan['audioIdentity'], 'model': model_record,
            'runtime': runtime}, helpers, device)
        context = {'contextId': plan['id'], 'audio': plan['audio'], 'audioSha256': plan['audioIdentity']['sha256'],
            'request': str(request_path), 'requestSha256': canonical_sha256(plan['request']),
            'output': str(folder/'native.private.json'), 'emissionCacheKey': emission_key}
        pending.append((context, plan, binding, packet_path))
    if pending:
        manifest = {'formatVersion': 1, 'timeoutSeconds': timeout, 'repairSegments': False,
            'contexts': [item[0] for item in pending]}
        batch_path = directory/('batch-'+canonical_sha256(manifest)+'.json')
        reply_path = batch_path.with_suffix('.reply.json')
        atomic_write_json(batch_path, manifest)
        reply_path.unlink(missing_ok=True)
        completed = align._run_numerical_process([str(python), '-B', '-m', 'stemwise_engine.'+helper.stem,
            '--batch', str(batch_path), '--output', str(reply_path), '--project', str(project), '--device', device,
            *arguments], builder=project, timeout=None, capture_output=True, text=True, check=False)
        raise_if_worker_stopped(completed, code='measurement-native-stopped', diagnostic_path=directory/'failure.private.txt')
        reply = read_bounded_json(reply_path, max_bytes=MAX_JSON_BYTES, root=directory) if reply_path.exists() else None
        if reply is not None:
            for index, (context, plan) in enumerate(batch_rows(manifest, reply, [item[1] for item in pending])):
                result = validate_native(plan, read_bounded_json(Path(context['output']), max_bytes=MAX_JSON_BYTES,
                    root=directory), family)
                check_inputs(plan)
                _, _, binding, packet_path = pending[index]
                atomic_write_json(packet_path, {'binding': binding, 'result': result})
                results[plan['id']] = (result, False, str(packet_path))
                if on_result is not None:
                    on_result(plan, results[plan['id']])
        require_worker_success(completed, code='measurement-native-failed', diagnostic_path=directory/'failure.private.txt')
        if len(results) != len(plans):
            raise ValueError('Measurement native batch is incomplete')
    return results


def whisper_guarded_plans(cases, plans, directory, config, timeout, device):
    """Use normal timed ASR only to propose guarded worker windows."""
    from stemwise_engine import lyrics_align_pc as align, alignment_audio, alignment_models
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace, resolve_compute_type
    from stemwise_engine.future_song import FutureSongWorkflow
    from stemwise_engine.common import diagnostic_code
    directory.mkdir(parents=True, exist_ok=True)
    workspace = AlignmentWorkspace(directory, workspace_root=ROOT)
    model_cache = Path(config['projectDir']).parent/'model-cache/lyrics-alignment'
    owner = alignment_models.WhisperModelOwner(config=config, model_name=align.DEFAULT_MODEL,
        snapshot=align._whisper_snapshot_path(model_cache), device=device,
        compute_type=resolve_compute_type(device))
    def get_model():
        align._verify_whisper_snapshot(model_cache)
        return owner.get()
    guarded, metadata = [], {}
    with environment_lease('lyrics', builder=Path(config['projectDir'])):
        try:
            for case, plan in zip(cases, plans, strict=True):
                check_inputs(plan)
                text = Path(plan['lyrics']).read_text(encoding='utf-8')
                original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                    'language': 'en', 'script': 'Latn', 'text': text}
                duration = plan['request']['durationMs']
                row = {'contentAuthority': False, 'wordTimingAuthority': False}
                segments = plan['request']['segments']
                try:
                    alignment_audio.write_audio_provenance(workspace, plan['videoId'], duration_ms=duration,
                        mix_audio=Path(plan['audio']), expected_mix_sha256=plan['audioIdentity']['sha256'])
                    words, _, reused = align._load_or_transcribe(video_id=plan['videoId'], original=original,
                        workspace=workspace, model_name=align.DEFAULT_MODEL, model_cache=model_cache, language='en',
                        separate_vocals=False, project_dir=Path(config['projectDir']), device=device,
                        compute_type=owner.compute_type, duration_ms=duration, get_model=get_model,
                        temp_root=directory/'temporary', timeout_seconds=timeout, rerun=False,
                        precision=True, use_approved_prompt=False,
                        expected_mix_sha256=plan['audioIdentity']['sha256'], analysis_audio=Path(plan['audio']))
                    segments = align._build_initial_alignment_segments(text,
                        [line['sourcePhysicalIndex'] for line in plan['request']['lines']], plan['mapping'], duration,
                        free_family_candidates={}, transcripts=[words],
                        transcript_labels=['faster-whisper-precision-unprompted-mix:en'])
                    path = workspace.transcript_path(plan['videoId'], 'en', 'precision-unprompted-mix')
                    row.update(transcript={'path': str(path.relative_to(ROOT)), 'sha256': sha256_file(path)},
                        cacheReused=reused, wordCount=len(words))
                except Exception as error:
                    FutureSongWorkflow._raise_if_prediction_stopped(error)
                    segments = plan['request']['segments']
                    row['technicalFailure'] = diagnostic_code(error)
                    from stemwise_engine.private_diagnostics import retain_error
                    try:
                        retained = retain_error(directory/(plan['id']+'.whisper-planning.failure.private.txt'), error,
                            context={'caseId': plan['id'], 'audioSha256': plan['audioIdentity']['sha256'],
                                     'stage': 'whisper-guarded-planning'})
                        row['diagnostic'] = {'path': str((directory/retained['file']).relative_to(ROOT)),
                            'sha256': retained['sha256'], 'byteCount': retained['byteCount']}
                    except (OSError, ValueError) as retention_error:
                        row['diagnosticRetentionFailure'] = diagnostic_code(retention_error)
                changed = copy.deepcopy(plan)
                changed['request']['segments'] = segments
                from stemwise_engine.ctc_worker import validate_request
                validate_request(changed['request'])
                if ({key: value for key, value in changed['request'].items() if key != 'segments'}
                        != {key: value for key, value in plan['request'].items() if key != 'segments'}):
                    raise ValueError('Guarded planning changed the complete target')
                check_inputs(plan)
                row.update(requestSha256=canonical_sha256(changed['request']),
                    fallback=segments == plan['request']['segments'])
                guarded.append(changed)
                metadata[case['id']] = row
        finally:
            owner.close()
    return guarded, metadata


def sylla_emission_record(plan, value, directory):
    from stemwise_engine import sylla_forced_alignment as worker
    from stemwise_engine.ctc_worker import cache_file_identity
    binding = worker.emission_binding(plan['audioIdentity'], value['model'], value['runtime'], value['producer'])
    path = directory/'emissions'/('emissions-'+canonical_sha256(binding)+'.pt')
    if path.is_symlink() or not path.is_file():
        raise ValueError('Paired Sylla acoustic cache is missing or is not a regular file')
    before = cache_file_identity(path)
    digest = sha256_file(path)
    if before[2] <= 0 or cache_file_identity(path) != before:
        raise ValueError('Paired Sylla acoustic cache changed during readback')
    return {'path': str(path.relative_to(ROOT)), 'sha256': digest, 'byteCount': before[2]}


def compare_whisper_guards(cases, plans, reference, frozen_plan, output, config, timeout, device):
    """A fixed development pair compares requests under one actual producer."""
    from stemwise_engine.common import diagnostic_code
    from stemwise_engine.future_song import FutureSongWorkflow
    reference_sha = sha256_file(reference)
    if (sha256_file(frozen_plan) != SYLLA_PLAN_SHA256
            or read_bounded_json(frozen_plan, max_bytes=MAX_JSON_BYTES, root=frozen_plan.parent)['referenceSha256'] != reference_sha):
        raise ValueError('Guarded comparison reference differs from the frozen inputs')
    path = output/'measurement.private.json'
    if path.exists():
        previous = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=output)
        if previous.get('scope') != GUARDED_SCOPE or previous.get('referenceSha256') != reference_sha:
            raise ValueError('Guarded comparison cannot overwrite another measurement')
    output.mkdir(parents=True, exist_ok=True)
    guarded, planning = whisper_guarded_plans(cases, plans, output/'whisper-planning', config, timeout, device)
    report = {'scope': GUARDED_SCOPE, 'splitForCandidate': 'development', 'qualificationClaim': False,
        'publicExportVerified': False, 'referenceSha256': reference_sha, 'planning': planning,
        'wordStartToleranceMs': 300, 'wordEndToleranceMs': 400, 'variants': {}}
    directory = output/'sylla'
    directory.mkdir(exist_ok=True)
    observed_tuple, geometry, failed = None, {}, False
    baseline_values, acoustic_cache = {}, {}
    for variant, selected in (('full-recording', plans), ('whisper-guarded', guarded)):
        rows = report['variants'][variant] = {}
        for case_index, (case, plan) in enumerate(zip(cases, selected, strict=True)):
            initial_request = plan['request']
            resolved_request, merge_steps, merge_diagnostics = initial_request, None, []
            try:
                check_inputs(plan)
                if variant == 'whisper-guarded' and (case['id'] not in acoustic_cache
                        or sylla_emission_record(plan, baseline_values[case['id']], directory) != acoustic_cache[case['id']]):
                    raise ValueError('Guarded Sylla request has no unchanged baseline acoustic cache')
                if variant == 'whisper-guarded':
                    from stemwise_engine.ctc_worker import decode_repaired
                    def decode(prepared):
                        nonlocal resolved_request
                        resolved_request = {**initial_request, 'segments': prepared['segments']}
                        current = {**plan, 'request': resolved_request}
                        if sylla_emission_record(current, baseline_values[case['id']], directory) != acoustic_cache[case['id']]:
                            raise ValueError('Sylla repair has no unchanged baseline acoustic cache')
                        try:
                            result = serial_model(current, 'sylla', directory, config, timeout, device, redecode=True)
                        except Exception as error:
                            FutureSongWorkflow._raise_if_prediction_stopped(error)
                            if sylla_emission_record(current, baseline_values[case['id']], directory) != acoustic_cache[case['id']]:
                                raise ValueError('Sylla repair changed the baseline acoustic cache') from error
                            code = error.code if isinstance(error, WorkerProcessError) else getattr(error, 'reason_code', '')
                            if re.fullmatch(r'wav2vec2-segment-(?:too-short:\d+|boundary-guard:\d+:(?:start|end))', code):
                                from stemwise_engine.private_diagnostics import retain_error
                                try:
                                    references = getattr(error, 'private_diagnostics', ())
                                    if not references:
                                        references = [retain_error(directory/(case['id']+'.guard.failure.private.txt'), error,
                                            context={'caseId': case['id'], 'requestSha256': canonical_sha256(resolved_request)})]
                                    merge_diagnostics.extend({'path': str((directory/ref['file']).relative_to(ROOT)),
                                        'sha256': ref['sha256'], 'byteCount': ref['byteCount'],
                                        'requestSha256': canonical_sha256(resolved_request),
                                        'segments': copy.deepcopy(resolved_request['segments']), 'reasonCode': code}
                                        for ref in references)
                                except (OSError, ValueError):
                                    pass  # Optional cause retention cannot suppress the native guard.
                                if isinstance(error, WorkerProcessError):
                                    raise WorkerReasonError(code) from error
                            raise
                        if sylla_emission_record(current, result[0], directory) != acoustic_cache[case['id']]:
                            raise ValueError('Sylla repair changed the baseline acoustic cache')
                        return result
                    (value, reused, packet), resolved_request, merge_steps = decode_repaired({}, initial_request, decode, True)
                    plan = {**plan, 'request': resolved_request}
                else:
                    value, reused, packet = serial_model(plan, 'sylla', directory, config, timeout, device, redecode=True)
                    merge_steps = []
                actual_tuple = {key: value[key] for key in ('model', 'producer', 'runtime', 'decoder')}
                if observed_tuple is not None and actual_tuple != observed_tuple:
                    raise ValueError('Guarded comparison producer/runtime changed')
                observed_tuple = actual_tuple
                transform = value['canonicalTimeTransform']
                if case['id'] in geometry and geometry[case['id']] != transform:
                    raise ValueError('Guarded comparison native time geometry changed')
                geometry[case['id']] = transform
                if variant == 'whisper-guarded':
                    cache = sylla_emission_record(plan, value, directory)
                    if cache != acoustic_cache[case['id']]:
                        raise ValueError('Guarded Sylla request changed the baseline acoustic cache')
                metrics = score(case, plan, value['lines'])
                check_inputs(plan)
                rows[case['id']] = {'output': str(Path(packet).relative_to(ROOT)),
                    'outputSha256': sha256_file(Path(packet)), 'cacheReused': reused, 'heldOutByUs': False,
                    'requestSha256': canonical_sha256(plan['request']), 'metrics': metrics,
                    'initialRequestSha256': canonical_sha256(initial_request), 'initialSegments': initial_request['segments'],
                    'resolvedRequestSha256': canonical_sha256(resolved_request), 'resolvedSegments': resolved_request['segments'],
                    'mergeSteps': merge_steps, 'mergeDiagnostics': merge_diagnostics,
                    'resolvedFallbackToFull': variant == 'whisper-guarded'
                        and resolved_request['segments'] == plans[case_index]['request']['segments']}
                if variant == 'full-recording':
                    baseline_values[case['id']] = value
                    try:
                        cache = sylla_emission_record(plan, value, directory)
                        acoustic_cache[case['id']] = cache
                        rows[case['id']]['emissionCache'] = cache
                    except (OSError, ValueError) as cache_error:
                        rows[case['id']]['comparisonFailure'] = diagnostic_code(cache_error)
                        failed = True
                else:
                    rows[case['id']]['emissionCache'] = cache
            except Exception as error:
                FutureSongWorkflow._raise_if_prediction_stopped(error)
                rows[case['id']] = {'technicalFailure': diagnostic_code(error),
                    'initialRequestSha256': canonical_sha256(initial_request), 'initialSegments': initial_request['segments'],
                    'lastAttemptedRequestSha256': canonical_sha256(resolved_request),
                    'lastAttemptedSegments': resolved_request['segments'], 'mergeDiagnostics': merge_diagnostics}
                failed = True
            if sha256_file(reference) != reference_sha:
                raise ValueError('Guarded comparison reference changed during execution')
            rows['aggregate'] = aggregate(cases, rows)
            atomic_write_json(path, report)
    report['observedProducer'] = observed_tuple
    atomic_write_json(path, report)
    return report, failed


def serial_model(plan, family, directory, config, timeout, device, *, redecode=False):
    from stemwise_engine import lyrics_align_pc as align
    from stemwise_engine.lyrics_alignment import AlignmentWorkspace
    project = Path(config['projectDir'])
    check_inputs(plan)
    if family == 'owsm':
        workspace = AlignmentWorkspace(directory, workspace_root=ROOT)
        with environment_lease('espnet-202511-owsm', builder=project):
            candidates, reused = align._load_or_run_owsm_ctc_alignment(config=config, video_id=plan['videoId'],
                language='en', sources=[line['sourcePhysicalIndex'] for line in plan['request']['lines']],
                duration_ms=plan['request']['durationMs'], workspace=workspace, audio=Path(plan['audio']),
                audio_identity=plan['audioIdentity'], temp_dir=directory/plan['id'], timeout_seconds=timeout,
                occurrence_mapping=plan['mapping'], display_word_lines=plan['displayWords'])
        path = workspace.job_dir(plan['videoId'])/'owsm-ctc-public-endpoints-v1-en.private.json'
        result = {'lines': list(candidates)}
    elif family == 'sylla':
        from stemwise_engine import sylla_forced_alignment as worker
        model = project.parent/'model-cache/lyrics-sylla/sylla-600m-v1'/worker.MODEL_SPEC['revision']
        vocabulary = read_bounded_json(model/'vocab.json', max_bytes=MAX_JSON_BYTES, root=model)
        model_record = worker.model_identity(model)
        command = [str(environment_python('main', project)), '-B', '-m', 'stemwise_engine.sylla_forced_alignment']
        with environment_lease('main', builder=project), heavy_worker_stage(config):
            probe = run_process_tree([*command, '--runtime-info', '--device', device], capture_output=True,
                text=True, check=False, timeout=min(timeout, 300))
            require_worker_success(probe, code='measurement-sylla-runtime', diagnostic_path=directory/'failure.private.txt')
            runtime = parse_worker_json(probe.stdout)
            worker._validate_runtime(runtime)
            binding = {'audio': plan['audioIdentity'], 'request': plan['request'], 'model': model_record,
                'producer': worker.producer_identity(), 'runtime': runtime, 'decoder': worker.DECODER}
            folder = directory/canonical_sha256(binding)
            folder.mkdir(parents=True, exist_ok=True)
            path = folder/'result.private.json'
            binding_path = folder/'binding.private.json'
            existing = path.exists()
            if binding_path.exists() and read_bounded_json(binding_path, max_bytes=MAX_JSON_BYTES, root=folder) != binding:
                raise ValueError('Measurement Sylla result binding changed')
            if existing:
                result = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=folder)
                worker.validate_result(result, plan['request'], plan['audioIdentity']['sha256'], model_record,
                    vocabulary, runtime, plan['audioIdentity']['stream'], language='en')
            reused = existing and not redecode
            if not reused:
                request_path = folder/'request.private.json'
                atomic_write_json(request_path, plan['request'])
                with WorkerTemporaryDirectory(prefix='sylla-measurement-', dir=folder) as temporary:
                    staged = Path(temporary)/'result.private.json'
                    completed = run_process_tree([*command, '--audio', plan['audio'], '--request', str(request_path),
                        '--output', str(staged), '--model', str(model), '--language', 'en', '--device', device,
                        '--emission-cache-dir', str(directory/'emissions')],
                        capture_output=True, text=True, check=False, timeout=timeout)
                    raise_if_worker_stopped(completed, code='measurement-sylla-failed', diagnostic_path=directory/'failure.private.txt')
                    if completed.returncode != 0 or not staged.is_file():
                        raise_worker_failure('Measurement Sylla decode failed', completed, staged,
                            diagnostic_path=directory/'failure.private.txt')
                    raw = read_bounded_regular_bytes(staged, max_bytes=MAX_JSON_BYTES, root=Path(temporary))
                    result = parse_worker_json(raw)
                    worker.validate_result(result, plan['request'], plan['audioIdentity']['sha256'], model_record,
                        vocabulary, runtime, plan['audioIdentity']['stream'], language='en')
                check_inputs(plan)
            if worker.model_identity(model) != model_record or worker.producer_identity() != binding['producer']:
                raise ValueError('Measurement Sylla producer changed')
            if not binding_path.exists():
                atomic_write_json(binding_path, binding)
            if not reused:
                atomic_write_bytes(path, raw, root=folder)
    else:
        raise ValueError('Unsupported serial measurement family')
    check_inputs(plan)
    return result, reused, str(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=ROOT/'Test suite/fixtures/lyrics-timing/English/timing-reference.json')
    parser.add_argument('--output', type=Path, default=ROOT/'Test suite/work/english-timing-measurement')
    parser.add_argument('--family', choices=FAMILIES, action='append')
    parser.add_argument('--device', choices=('cuda',), default='cuda')
    parser.add_argument('--timeout-seconds', type=int, default=10800)
    parser.add_argument('--run-models', action='store_true')
    parser.add_argument('--assess-sylla', action='store_true')
    parser.add_argument('--compare-whisper-guards', action='store_true')
    parser.add_argument('--qualification-plan', type=Path,
        default=ROOT/'Test suite/fixtures/lyrics-timing/English/sylla-qualification-plan.json')
    args = parser.parse_args(argv)
    collection = read_bounded_json(args.reference, max_bytes=MAX_JSON_BYTES, root=args.reference.parent)
    cases = collection['musical']
    if (not isinstance(cases, list) or not cases or len({case['id'] for case in cases}) != len(cases)
            or not 0 < args.timeout_seconds <= 86400):
        raise ValueError('Expected distinct predeclared inputs and a valid stage watchdog')
    if args.compare_whisper_guards:
        if args.assess_sylla or args.family not in (None, ['sylla']):
            raise ValueError('Guarded development comparison is Sylla-only, never qualification assessment')
        if (sha256_file(args.qualification_plan) != SYLLA_PLAN_SHA256
                or read_bounded_json(args.qualification_plan, max_bytes=MAX_JSON_BYTES,
                    root=args.qualification_plan.parent)['referenceSha256'] != sha256_file(args.reference)):
            raise ValueError('Guarded comparison reference differs from the frozen inputs')
        by_id = {case['id']: case for case in cases}
        if not set(GUARDED_PAIR) <= set(by_id):
            raise ValueError('Guarded comparison requires the predeclared HILA/Ridgway pair')
        cases = [by_id[identity] for identity in GUARDED_PAIR]
    if args.assess_sylla:
        if args.run_models:
            raise ValueError('Qualification assessment never runs models')
        assessed = assess_sylla(args.reference, args.output/'measurement.private.json', args.qualification_plan)
        print(json.dumps(assessed, ensure_ascii=False, allow_nan=False))
        return int(bool(assessed['failures']))
    plans = [model_plan(case, args.reference.parent) for case in cases]
    if not args.run_models:
        print(json.dumps({'scope': GUARDED_SCOPE if args.compare_whisper_guards else 'private-native-measurement-plan',
            'modelsRun': False, 'qualificationClaim': False, 'cases': [
            {'id': plan['id'], 'audioSha256': plan['audioIdentity']['sha256'],
                'requestSha256': canonical_sha256(plan['request']), 'segments': plan['request']['segments'],
                'wordCount': sum(len(line['words']) for line in plan['request']['lines'])} for plan in plans]}))
        return 0
    output = args.output.resolve()
    output.relative_to((ROOT/'Test suite/work').resolve())
    output.mkdir(parents=True, exist_ok=True)
    reference_sha = sha256_file(args.reference)
    config = read_bounded_json(PROJECT/'config.json', max_bytes=MAX_JSON_BYTES, root=PROJECT)
    config.update(projectDir=str(PROJECT), tempDir=str(output))
    environment = {'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
        'TORCH_HOME': str(PROJECT.parent/'model-cache/torch')}
    report = {'scope': 'predeclared-English-singing-inputs', 'qualificationClaim': False,
        'audioView': 'mix', 'mmsScope': 'full-mix-diagnostic-only', 'productionMmsVocalRouteEvaluated': False,
        'referenceSha256': reference_sha, 'annotationEndSemantics': 'Published ends; phonetic/reverb-tail convention unknown',
        'wordStartToleranceMs': 300, 'wordEndToleranceMs': 400, 'families': {}}
    previous = {key: os.environ.get(key) for key in environment}
    os.environ.update(environment)
    failed = False
    try:
        if args.compare_whisper_guards:
            report, failed = compare_whisper_guards(cases, plans, args.reference, args.qualification_plan,
                output, {**config, 'analysisTimeoutMinutes': (args.timeout_seconds+59)//60},
                args.timeout_seconds, args.device)
            print(json.dumps(report, ensure_ascii=False, allow_nan=False))
            return int(failed)
        for family in args.family or FAMILIES:
            directory = output/family
            directory.mkdir(exist_ok=True)
            rows = report['families'][family] = {}
            case_by_id = {case['id']: case for case in cases}
            def persist():
                rows['aggregate'] = aggregate(cases, rows)
                atomic_write_json(output/'measurement.private.json', report)
            def retain(plan, result):
                case = case_by_id[plan['id']]
                check_inputs(plan)
                value, reused, path = result
                metrics = score(case, plan, value['lines'])
                rows[plan['id']] = {'output': str(Path(path).relative_to(ROOT)),
                    'outputSha256': sha256_file(Path(path)), 'cacheReused': reused,
                    'heldOutByUs': case['heldOutByUs'], 'metrics': metrics}
                persist()
            width = 4 if family in {'mms', 'wav2vec2'} else 1
            for start in range(0, len(plans), width):
                group = plans[start:start+width]
                try:
                    if width == 4:
                        results = native_batch(group, family, directory, config,
                            args.timeout_seconds, args.device, on_result=retain)
                        for plan in group:
                            if plan['id'] not in rows:
                                retain(plan, results[plan['id']])
                    else:
                        plan = group[0]
                        retain(plan, serial_model(plan, family, directory,
                            config, args.timeout_seconds, args.device))
                except Exception as error:
                    from stemwise_engine.future_song import FutureSongWorkflow
                    from stemwise_engine.common import diagnostic_code
                    FutureSongWorkflow._raise_if_prediction_stopped(error)
                    code = diagnostic_code(error)
                    rows['technicalFailure'] = code
                    for plan in group:
                        if plan['id'] not in rows:
                            rows[plan['id']] = {'technicalFailure': code}
                    persist()
                    failed = True
            if sha256_file(args.reference) != reference_sha:
                raise ValueError('Measurement annotations changed during execution')
            persist()
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
    print(json.dumps(report, ensure_ascii=False, allow_nan=False))
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
