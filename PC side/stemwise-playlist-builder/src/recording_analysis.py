"""Canonical acoustic analysis of wanted native recordings; no phone publication."""
import argparse
import hashlib
import json
import os
import time
import uuid
import re
import sys
import subprocess
from contextlib import nullcontext
from pathlib import Path
from typing import Any

from stemwise_engine.common import (atomic_write_json, diagnostic_code, exclusive_process_lock,
                    load_config, manifested_temporary_directory, remove_manifested_directory,
                    read_bounded_json, read_bounded_regular_bytes, parse_strict_json_bytes,
                    resolve_path, sha256_file, utc_now, validate_generated_path,
                    process_lock_descriptor, json_bytes)
from stemwise_engine.contract import MAX_JSON_BYTES, require_usable_analysis as require_analysis
from stemwise_engine.analyzers import _allin1_python
from stemwise_engine.pipeline import run_analysis_worker
from stemwise_engine.provenance import (collect_acoustic_provenance, provenance_fingerprint, numerical_source_identity)
from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
from stemwise_engine.workflow_context import telemetry_job_root
from stemwise_engine.recording_source import selected_recording

from stemwise_media.compare import probe

ENGINE_VERSION = 2




def acoustic_producer_identity(config, *, observe_execution=True):
    return {'engineVersion': ENGINE_VERSION, 'sources': numerical_source_identity(),
            'runtime': collect_acoustic_provenance(allin1_python=_allin1_python(Path(config['projectDir'])),
                observe_execution=observe_execution)}


def selected_input(config, key, *, producer_identity=None, verify_media=True):
    workspace, pc, audio, identity = selected_recording(config, key, verify_media=verify_media)
    track_id = identity['trackId']
    producer_identity = producer_identity or acoustic_producer_identity(config)
    # Other producers remain untouched and never become current by relabeling.
    root = validate_generated_path(workspace,
        pc / 'generated-packages/recording-analysis' / f'qobuz-{track_id}' / identity['sha256']
        / f'v{ENGINE_VERSION}-{provenance_fingerprint(producer_identity)}',
        workspace_root=workspace)
    return workspace, pc, root, audio, identity




def matching_analysis(config, wav, duration, producer) -> dict[str, Any] | None:
    """Reuse a verified native result only for byte-identical decoded PCM."""
    project = Path(config['projectDir']).resolve()
    workspace = project.parent.parent
    pc = resolve_path(config['pcSideRootWindows'], base=project)
    cache = validate_generated_path(workspace, pc / 'generated-packages/recording-analysis',
                                    workspace_root=workspace)
    pcm = {'sha256': sha256_file(wav), 'byteCount': wav.stat().st_size,
           'durationMs': duration, 'sampleRate': 48000, 'channels': 2}
    paths = sorted(cache.glob(f'qobuz-*/*/v{ENGINE_VERSION}-{provenance_fingerprint(producer)}/job.json'))
    for path in paths:
        try:
            job = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=cache)
            if (not isinstance(job, dict) or job.get('state') != 'ANALYSIS_COMPLETE'
                    or job.get('pcm') != pcm or job.get('producerIdentity') != producer
                    or not isinstance(job.get('input'), dict)):
                continue
            key = job['input'].get('recordingKey')
            if not isinstance(key, str) or re.fullmatch(r'qobuz:[1-9][0-9]*', key) is None:
                continue
            # Use normal source/producer/cache admission; a matching JSON claim is insufficient.
            admitted = status(config, key, producer_identity=job['producerIdentity'])
            if (admitted['state'] != 'ANALYSIS_COMPLETE' or admitted['pcm'] != pcm
                    or admitted['producerIdentity'] != producer
                    or admitted['job'] != path.relative_to(workspace).as_posix()):
                continue
            raw = read_bounded_regular_bytes(workspace / admitted['analysis'], max_bytes=MAX_JSON_BYTES, root=cache)
            if hashlib.sha256(raw).hexdigest() != job.get('analysisSha256'):
                continue
            analysis = parse_strict_json_bytes(raw)
            require_analysis(analysis, duration)
        except (OSError, ValueError):
            # A damaged unrelated entry is a miss, never a reason to trust it or stop this recording.
            continue
        _finish_completed_cleanup(config, key, admitted)
        return analysis
    return None


def status(config, key, *, producer_identity=None):
    producer = producer_identity or acoustic_producer_identity(config, observe_execution=False)
    workspace, _, root, _, identity = selected_input(config, key, producer_identity=producer,
                                                      verify_media=producer_identity is not None)
    if producer_identity is None and not (root/'job.json').exists():
        # Inspection selects a stored execution tuple; operation admission supplies its live tuple.
        candidates = []
        for candidate in root.parent.glob(f'v{ENGINE_VERSION}-*/job.json'):
            try:
                saved = read_bounded_json(candidate, max_bytes=MAX_JSON_BYTES, root=root.parent)
                if not isinstance(saved, dict):
                    continue
                stored = saved.get('producerIdentity', {})
                if not isinstance(stored, dict) or not isinstance(stored.get('runtime'), dict):
                    continue
                base = {**stored, 'runtime': {name:value for name,value in stored['runtime'].items() if name != 'execution'}}
                if (base == producer and saved.get('input') == identity
                        and candidate.parent.name == f'v{ENGINE_VERSION}-{provenance_fingerprint(stored)}'):
                    candidates.append((candidate.stat().st_mtime_ns, stored, candidate.parent))
            except InterruptedError:
                raise
            except (OSError, ValueError):
                continue
        if candidates:
            _, producer, root = max(candidates, key=lambda item:item[0])
    path = root / 'job.json'
    result = {'recordingKey': key, 'state': 'NOT_STARTED',
              'phoneDeliveryAllowed': False, 'lyricsTimed': False,
              'job': path.relative_to(workspace).as_posix(), 'producerIdentity': producer}
    if not path.exists():
        return result
    job = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=root)
    if (job.get('input') != identity or job.get('engineVersion') != ENGINE_VERSION
            or job.get('producerIdentity') != producer):
        raise ValueError('Recording analysis identity differs')
    result['state'] = job['state']
    if job['state'] == 'ANALYSIS_COMPLETE':
        digest = job['analysisSha256']
        if not isinstance(digest, str) or re.fullmatch('[0-9a-f]{64}', digest) is None:
            raise ValueError('Completed analysis digest is invalid')
        analysis_path = root / f'analysis-{digest}.json'
        if sha256_file(analysis_path) != job['analysisSha256']:
            raise ValueError('Completed recording analysis was changed')
        analysis = read_bounded_json(analysis_path, max_bytes=MAX_JSON_BYTES, root=root)
        pcm = job['pcm']
        if (not isinstance(pcm, dict) or pcm.get('sampleRate') != 48000 or pcm.get('channels') != 2
                or type(pcm.get('durationMs')) is not int
                or abs(pcm['durationMs'] - identity['durationMs']) > 1):
            raise ValueError('Completed PCM duration/format differs')
        require_analysis(analysis, pcm['durationMs'])
        require_execution_binding(analysis, producer)
        result.update(analysis=analysis_path.relative_to(workspace).as_posix(),
                      analysisStatus=analysis['status'], pcm=job['pcm'])
    if 'error' in job:
        result['error'] = job['error']
    if 'toolStatuses' in job:
        result['toolStatuses'] = job['toolStatuses']
    if 'reason' in job:
        result['reason'] = job['reason']
    if 'telemetryError' in job:
        result['telemetryError'] = job['telemetryError']
    if 'latestRunFile' in job:
        result['latestRunFile'] = job['latestRunFile']
        try:
            telemetry = _telemetry_root(workspace, key, identity, producer)
            path = validate_generated_path(telemetry, workspace / job['latestRunFile'], workspace_root=workspace)
            if path.parent != telemetry / 'runs' or re.fullmatch(r'[0-9a-f]{32}\.json', path.name) is None:
                raise ValueError('Analysis telemetry path differs')
            run = read_bounded_json(path, max_bytes=MAX_JSON_BYTES, root=telemetry)
            if (not isinstance(run, dict) or run.get('input') != identity
                    or run.get('producerSha256') != provenance_fingerprint(producer)):
                raise ValueError('Analysis telemetry binding differs')
            result['latestRun'] = run
        except (OSError, ValueError, TypeError) as error:
            result['latestRunError'] = diagnostic_code(error)
    if 'cleanupPending' in job:
        result['cleanupPending'] = True
    return result


def require_execution_binding(analysis, producer):
    execution = producer.get('runtime', {}).get('execution')
    if execution is not None:
        expected = json.dumps(execution['main'], sort_keys=True, separators=(',', ':'))
        for tool in analysis['toolRuns']:
            if tool['name'] in {'beat-this-full', 's-key'} and tool['status'] == 'complete' and tool.get('profile') != expected:
                raise ValueError('Acoustic result execution device differs from its producer')


def _write_completed_analysis(root, audio, identity, wav, duration, analysis, producer, *, cleanup_pending=None, latest_run_file=None):
    require_analysis(analysis, duration)
    require_execution_binding(analysis, producer)
    metadata = probe(wav)
    if (metadata['codec_name'] != 'pcm_f32le' or int(metadata['sample_rate']) != 48000
            or metadata['channels'] != 2 or probe_duration_ms(wav) != duration
            or abs(duration - identity['durationMs']) > 1
            or sha256_file(audio) != identity['sha256']):
        raise ValueError('Completed analysis does not match the selected native PCM')
    pcm = {'sha256': sha256_file(wav), 'byteCount': wav.stat().st_size,
           'durationMs': duration, 'sampleRate': 48000, 'channels': 2}
    digest = hashlib.sha256(json_bytes(analysis, pretty=True)).hexdigest()
    analysis_path = root / f'analysis-{digest}.json'
    atomic_write_json(analysis_path, analysis)
    atomic_write_json(root / 'job.json', {
        'formatVersion': 1, 'engineVersion': ENGINE_VERSION, 'state': 'ANALYSIS_COMPLETE',
        'input': identity, 'producerIdentity': producer, 'pcm': pcm, 'updatedAt': utc_now(),
        'analysisSha256': sha256_file(analysis_path),
        'toolStatuses': {run['name']: run['status'] for run in analysis['toolRuns']},
        **({'latestRunFile': latest_run_file} if latest_run_file is not None else {}),
        **({'cleanupPending': str(cleanup_pending)} if cleanup_pending is not None else {})})


def _finish_completed_cleanup(config, key, current):
    if not current.get('cleanupPending'):
        return
    workspace, pc, _, _, identity = selected_input(config, key, producer_identity=current['producerIdentity'])
    root = validate_generated_path(workspace, workspace / current['job']).parent
    with (nullcontext() if process_lock_descriptor(root / 'workflow.lock') is not None
          else exclusive_process_lock(root / 'workflow.lock')):
        job = read_bounded_json(root / 'job.json', max_bytes=MAX_JSON_BYTES, root=root)
        if 'cleanupPending' not in job:
            return
        temporary_root = pc / 'temporary-analysis/recordings'
        path = validate_generated_path(temporary_root, Path(job['cleanupPending']), workspace_root=workspace)
        if re.fullmatch(rf'qobuz-{identity["trackId"]}-[0-9a-f]{{32}}', path.name) is None:
            raise ValueError('Completed analysis cleanup belongs to another recording')
        remove_manifested_directory(temporary_root, path,
            owner={'kind':'recording-analysis','recordingKey':key}, workspace_root=workspace)
        del job['cleanupPending']
        atomic_write_json(root / 'job.json', job)


def cache_completed_analysis(config, key, wav, duration, analysis, expected_input, expected_producer):
    """Publish an editorial analyzer result into the same exact-input acoustic cache."""
    producer = acoustic_producer_identity(config)
    _, _, root, audio, identity = selected_input(config, key, producer_identity=producer)
    if identity != expected_input or producer != expected_producer:
        raise ValueError('Selected native recording or acoustic producer changed before cache publication')
    root.mkdir(parents=True, exist_ok=True)
    with (nullcontext() if process_lock_descriptor(root / 'workflow.lock') is not None
          else exclusive_process_lock(root / 'workflow.lock')):
        current = status(config, key, producer_identity=producer)
        if current['producerIdentity'] != producer:
            raise ValueError('Acoustic producer changed before cache publication')
        if current['state'] == 'ANALYSIS_COMPLETE':
            if (current['pcm']['sha256'] != sha256_file(wav)
                    or current['pcm']['byteCount'] != wav.stat().st_size
                    or current['pcm']['durationMs'] != duration):
                raise ValueError('Completed acoustic cache belongs to different PCM')
            return
        _write_completed_analysis(root, audio, identity, wav, duration, analysis, producer)


def _telemetry_root(workspace, key, identity, producer):
    return validate_generated_path(workspace,
        telemetry_job_root(workspace, key) / 'acoustic' / identity['sha256']
        / f'v{ENGINE_VERSION}-{provenance_fingerprint(producer)}', workspace_root=workspace)


def _record_run_failure(run_path, run, error, started):
    from stemwise_engine.private_diagnostics import preserve_failure, reference
    run.update(status='failed' if isinstance(error, Exception) else 'interrupted', completedAt=utc_now(),
               elapsedMs=round((time.monotonic()-started)*1000))
    diagnostic = run_path.with_suffix('.failure.private.txt')
    try:
        preserve_failure(diagnostic, error, getattr(error, 'stderr', None))
        run['privateDiagnostic'] = reference(diagnostic, {'sha256': sha256_file(diagnostic), 'byteCount': diagnostic.stat().st_size})
    except BaseException as diagnostic_error:
        run['diagnosticError'] = diagnostic_code(diagnostic_error)
    if hasattr(error, 'returncode') or isinstance(error, subprocess.TimeoutExpired):
        run['workerExitCode'] = getattr(error, 'returncode', None)
        run['workerFailure'] = getattr(error, 'worker_reason',
            'worker-timeout' if isinstance(error, subprocess.TimeoutExpired) else 'worker-exit')


def analyze(config, key, *, repair=False):
    from stemwise_engine.telemetry_retention import publication
    workspace = Path(config['projectDir']).resolve().parent.parent
    with publication(workspace, telemetry_job_root(workspace, key)):
        return _analyze(config, key, repair=repair)


def _analyze(config, key, *, repair=False):
    started = time.monotonic()
    producer = acoustic_producer_identity(config)
    workspace, pc, root, audio, identity = selected_input(config, key, producer_identity=producer)
    root.mkdir(parents=True, exist_ok=True)
    run = {'startedAt': utc_now(), 'status': 'running', 'producerSha256': provenance_fingerprint(producer),
           'input': identity, 'cacheReused': False, 'cacheReason': 'not-checked', 'stages': {}}
    token = os.environ.get('STEMWISE_LAUNCH_TOKEN', '')
    if re.fullmatch(r'[0-9a-f]{32}', token):
        run['operationId'] = token
    run_path = _telemetry_root(workspace, key, identity, producer) / 'runs' / f'{uuid.uuid4().hex}.json'
    run_file = run_path.relative_to(workspace).as_posix()
    active_stage = None
    stage_started = started
    telemetry_failed = False
    def persist_run():
        nonlocal telemetry_failed
        try:
            atomic_write_json(run_path, run)
        except Exception as error:
            if not telemetry_failed:
                print('recording-analysis-diagnostic-write-failed', file=sys.stderr)
            telemetry_failed = True
            return diagnostic_code(error)

    def record_failure(error, *, completed):
        _record_run_failure(run_path, run, error, started)
        if active_stage is not None:
            run['stages'][active_stage].update(status=run['status'], elapsedMs=round((time.monotonic()-stage_started)*1000))
        telemetry_error = persist_run()
        if telemetry_error is not None:
            error.telemetry_error = telemetry_error
        try:
            failure_job = read_bounded_json(root / 'job.json', max_bytes=MAX_JSON_BYTES, root=root) if completed else job
            if not completed:
                failure_job.update(state='ANALYSIS_FAILED', error=diagnostic_code(error),
                                   reason=diagnostic_code(error), updatedAt=utc_now())
            failure_job['latestRunFile'] = run_file
            if hasattr(error, 'telemetry_error'):
                failure_job['telemetryError'] = error.telemetry_error
            atomic_write_json(root / 'job.json', failure_job)
        except BaseException as telemetry_error:
            error.telemetry_error = diagnostic_code(telemetry_error)

    with exclusive_process_lock(root / 'workflow.lock'):
        current = status(config, key, producer_identity=producer)
        previous_run = current.get('latestRun')
        if isinstance(previous_run, dict) and previous_run.get('status') == 'running':
            previous_run.update(status='interrupted', completedAt=utc_now(), reason='previous-worker-retired')
            for stage in previous_run.get('stages', {}).values():
                if stage.get('status') == 'running':
                    stage['status'] = 'interrupted'
            try:
                atomic_write_json(workspace/current['latestRunFile'], previous_run)
            except Exception:
                print('recording-analysis-diagnostic-write-failed', file=sys.stderr)
        repair_from = None
        if repair and current['state'] == 'ANALYSIS_COMPLETE':
            from stemwise_engine.analysis_numerics import repair_components
            previous = read_bounded_json(workspace/current['analysis'], max_bytes=MAX_JSON_BYTES, root=root)
            if repair_components(previous, current['pcm']['durationMs']):
                repair_from = workspace/current['analysis']
        if current['state'] == 'ANALYSIS_COMPLETE' and repair_from is None:
            run.update(cacheReused=True, cacheReason='complete-recording-cache', pcm=current['pcm'])
            persist_run()
            job = read_bounded_json(root / 'job.json', max_bytes=MAX_JSON_BYTES, root=root)
            job['latestRunFile'] = run_file
            job.pop('telemetryError', None)
            atomic_write_json(root / 'job.json', job)
            try:
                _finish_completed_cleanup(config, key, current)
            except BaseException as error:
                record_failure(error, completed=True)
                raise
            run.update(status='complete', completedAt=utc_now(), elapsedMs=round((time.monotonic()-started)*1000))
            job = read_bounded_json(root / 'job.json', max_bytes=MAX_JSON_BYTES, root=root)
            persist_run()
            job['latestRunFile'] = run_file
            atomic_write_json(root / 'job.json', job)
            return {**status(config, key, producer_identity=producer), 'reused': True}
        job = {'formatVersion': 1, 'engineVersion': ENGINE_VERSION,
               'state': 'ANALYZING', 'input': identity, 'producerIdentity': producer,
               'updatedAt': utc_now(), 'latestRunFile': run_file}
        persist_run()
        if repair_from is None:
            atomic_write_json(root / 'job.json', job)
        completed = repair_from is not None
        try:
            temporary_root = validate_generated_path(workspace,
                pc / 'temporary-analysis/recordings', workspace_root=workspace)
            with manifested_temporary_directory(temporary_root, prefix=f'qobuz-{identity["trackId"]}-',
                    owner={'kind': 'recording-analysis', 'recordingKey': key}, workspace_root=workspace) as temporary:
                timeout = int(config['analysisTimeoutMinutes']) * 60
                active_stage, stage_started = 'decode', time.monotonic()
                run['stages']['decode'] = {'status': 'running', 'startedAt': utc_now()}
                persist_run()
                from stemwise_engine.analysis_resources import require_analysis_capacity
                run['resourceAdmission'] = require_analysis_capacity(identity['durationMs'], scratch_root=temporary)
                persist_run()
                wav = decode_analysis_audio(audio, temporary, timeout_seconds=timeout)
                duration = probe_duration_ms(wav)
                # Resampling can move rounded duration by one millisecond.
                if abs(duration - identity['durationMs']) > 1 or sha256_file(audio) != identity['sha256']:
                    raise ValueError('Selected recording changed or decode was truncated')
                run['pcm'] = {'sha256': sha256_file(wav), 'byteCount': wav.stat().st_size,
                              'durationMs': duration, 'sampleRate': 48000, 'channels': 2}
                if repair_from is not None and run['pcm'] != current['pcm']:
                    raise ValueError('Repair PCM differs from the committed analysis')
                run['stages']['decode'].update(status='complete', elapsedMs=round((time.monotonic()-stage_started)*1000))
                active_stage, stage_started = 'analysis', time.monotonic()
                run['stages']['analysis'] = {'status': 'running', 'startedAt': utc_now()}
                persist_run()
                match = matching_analysis(config, wav, duration, producer) if repair_from is None else None
                reused = match is not None
                analysis = match
                run.update(cacheReused=reused,
                           cacheReason='independent-pcm-cache' if reused else 'no-matching-producer-pcm-cache')
                persist_run()
                if not reused:
                    from stemwise_engine.private_diagnostics import analysis_diagnostics
                    with analysis_diagnostics(run_path.parent/'causes', workspace_root=workspace) as private_causes:
                        analysis = run_analysis_worker(wav, duration, Path(config['projectDir']), timeout,
                            **({'repair_from': repair_from} if repair_from is not None else {}))
                    run.update(private_causes)
                job['toolStatuses'] = {row['name']: row['status'] for row in analysis.get('toolRuns', [])}
                require_analysis(analysis, duration)
                if sha256_file(audio) != identity['sha256'] or acoustic_producer_identity(config) != producer:
                    raise ValueError('Selected recording or acoustic producer changed during analysis')
                run['stages']['analysis'].update(status='complete', elapsedMs=round((time.monotonic()-stage_started)*1000))
                active_stage = None
                run.update(status='complete', completedAt=utc_now(), elapsedMs=round((time.monotonic()-started)*1000))
                persist_run()
                _write_completed_analysis(root, audio, identity, wav, duration, analysis, producer,
                    cleanup_pending=temporary, latest_run_file=run_file)
                completed = True
            _finish_completed_cleanup(config, key, status(config, key, producer_identity=producer))
        except BaseException as error:
            record_failure(error, completed=completed)
            raise
    return {**status(config, key, producer_identity=producer), 'reused': reused}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('command', choices=['analyze', 'repair', 'status'])
    parser.add_argument('--recording-key', required=True)
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config)
        if args.command in {'analyze', 'repair'}:
            from stemwise_engine.campaign_operations import model_operation_slot
            with model_operation_slot(config):
                result = analyze(config, args.recording_key, repair=args.command == 'repair')
        else:
            result = status(config, args.recording_key)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0
    except Exception as error:
        failure = {'error': diagnostic_code(error), 'reason': str(error)[:300]}
        if hasattr(error, 'returncode'):
            failure['workerExitCode'] = error.returncode
        if hasattr(error, 'worker_reason'):
            failure['workerFailure'] = error.worker_reason
        if hasattr(error, 'telemetry_error'):
            failure['telemetryError'] = error.telemetry_error
        print(json.dumps(failure, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
