"""Qualify the declared finite workloads through the existing Focused/Perfetto runner."""
import argparse
import json
import math
from pathlib import Path
import shutil
import sys
import tempfile

import audio_evidence as evidence
from owned_process import run as run_owned


def profiler(root):
    return evidence.runner_module('candidate_playback_profiler', root / 'Test suite/device/profile_playback.py')


def copy_device_records(source, destination):
    for path in source.rglob('*'):
        if path.is_file() and (path.name in {'run.json', 'selection.json'} or
                               path.suffix == '.xml' and 'results' in path.relative_to(source).parts):
            target = destination / path.relative_to(source)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)


def workload_metrics(actual, workload):
    trace_name = lambda name: ('StemWise.test:' + name.split('#', 1)[1])[:127]
    primary = trace_name(workload['test'])
    allowed = {primary, *(trace_name(name) for name in workload.get('additionalTests', []))}
    if any(item.get('workload') not in allowed for item in actual['metrics']):
        raise ValueError('AE-15 trace contains an undeclared or different workload')
    selected = [item for item in actual['metrics'] if item['workload'] == primary]
    identities = {item['id'] for item in selected}
    if len(identities) != 1 or len({item['metric'] for item in selected}) != len(selected):
        raise ValueError('AE-15 exact declared trace workload is absent, duplicated or ambiguous')
    return {item['metric']: item for item in selected}, actual['environment'][next(iter(identities))]


def require_profile_route(metrics, route):
    if route != 'built-in-speaker':
        raise ValueError('AE-15 unsupported physical output route profile')
    if (any(name not in metrics for name in ('platform_route_type_min', 'platform_route_type_max')) or
            any(float(metrics[name]['value']) != 2 for name in ('platform_route_type_min', 'platform_route_type_max'))):
        raise ValueError('AE-15 actual presented output route is not Android TYPE_BUILTIN_SPEAKER')


def validate_physical(report, plan, root=evidence.ROOT):
    if (report.get('schemaVersion') != 1 or report.get('sourceSha256') != plan['sourceSha256'] or
            report.get('sourceEndSha256') != plan['sourceSha256'] or
            report.get('profile') != plan['candidateProfile'] or
            report.get('deviceProfile') != plan['deviceProfile']['identity']):
        raise ValueError('AE-15 wrong physical workload, source or exact device')
    for name in ('auditApk', 'auditTestApk'):
        evidence.checked_file(report[name], root)
    expected = [item['test'] for item in plan['candidateProfile']['workloads']]
    if len(report['workloads']) != len(expected):
        raise ValueError('AE-15 missing or unexpected physical workload execution')
    records = []
    profile = profiler(root)
    for row, workload in zip(report['workloads'], plan['candidateProfile']['workloads']):
        if row['test'] != workload['test']:
            raise ValueError('AE-15 physical workload selection changed')
        invocation = evidence.load_json(evidence.checked_file(row['invocation'], root))
        command = invocation['command']
        if (invocation.get('status') != 'PASS' or '-ProfilePerformance' not in command or
                command[command.index('-Class') + 1] != workload['test']):
            raise ValueError('AE-15 profiling did not execute the declared exact workload')
        device = evidence.checked_file(row['deviceRun'], root)
        selected_plan = {**plan, 'required': {'device': [workload['test'], *workload.get('additionalTests', [])]}}
        evidence.device_records(device, selected_plan, root)
        actual_run = evidence.load_json(device)
        if actual_run.get('runId') != invocation.get('runId'):
            raise ValueError('AE-15 profiling borrowed a different device operation receipt')
        identities = {item['kind']: item['sha256'].lower() for item in actual_run['artifacts']
                      if item['role'] == 'qualification'}
        if identities != {'application': report['auditApk']['sha256'], 'androidTest': report['auditTestApk']['sha256']}:
            raise ValueError('AE-15 physical tests consumed a different Development APK pair')
        stored = evidence.load_json(evidence.checked_file(row['performance'], root))
        trace = evidence.checked_file(row['trace'], root)
        processor = evidence.checked_file(row['traceProcessor'], root)
        # Reuse the actual SQL analysis; editable measurementValid flags cannot qualify a trace.
        with tempfile.TemporaryDirectory(prefix='audio-trace-readback-', dir=root / 'Test suite/work') as directory:
            profile.analyze(processor, trace, Path(directory), True)
            actual = evidence.load_json(Path(directory) / 'performance.json')
        if actual != stored:
            raise ValueError('AE-15 physical metrics differ from the retained actual trace')
        metrics, environment = workload_metrics(actual, workload)
        require_profile_route(metrics, plan['candidateProfile'].get('route'))
        required = ('callback_execution_ms', 'callback_headroom_min_ms', 'callback_physical_runs',
                    'callback_deadline_misses', 'platform_queue_min_frames', 'platform_queue_max_frames',
                    'platform_xrun_max', 'invalid_platform_observations')
        if any(name not in metrics for name in required):
            raise ValueError('AE-11 missing actual render timing, sink reserve or continuity observation')
        value = lambda name: float(metrics[name]['value'])
        if (any(not math.isfinite(value(name)) for name in required) or
                value('callback_deadline_misses') != plan['candidateProfile']['maximumCallbackDeadlineMisses'] or
                value('platform_xrun_max') != plan['candidateProfile']['maximumXrunCount'] or
                value('invalid_platform_observations') != 0 or value('platform_queue_min_frames') < 0 or
                value('callback_headroom_min_ms') < 0 or
                value('callback_physical_runs') < workload['minimumPhysicalRuns'] or
                float(metrics['callback_execution_ms']['max_ms']) < 0):
            raise ValueError('AE-11 measured physical workload violated its existing healthy output contracts')
        for after in (environment['after'],):
            for field, name in (('sample_rate_hz', 'sampleRateHz'), ('buffer_size_frames', 'bufferFrames')):
                if after['output.' + field] != plan['candidateProfile'][name]:
                    raise ValueError('AE-15 actual profiling output differs from the declared supported configuration')
        records.append((row['test'], 'passed'))
    return evidence.reconcile(expected, records)


def run(plan, output, root=evidence.ROOT):
    evidence.validate_current_plan(plan, root)
    if not plan.get('deviceProfile'):
        raise ValueError('An exact approved physical device profile is required')
    previous = evidence.load_json(output) if output.is_file() else None
    workspace = Path(tempfile.mkdtemp(prefix='audio-physical-', dir=root / 'Test suite/work'))
    report = {'schemaVersion': 1, 'sourceSha256': plan['sourceSha256'],
              'profile': plan['candidateProfile'], 'deviceProfile': plan['deviceProfile']['identity'],
              'workspace': workspace.relative_to(root).as_posix(), 'workloads': [],
              'supersededWorkspaces': ([*previous.get('supersededWorkspaces', []), previous['workspace']]
                                       if previous and previous.get('workspace') else [])}
    processor = root / 'Test suite/work/tools/trace_processor'
    try:
        for workload in plan['candidateProfile']['workloads']:
            destination = workspace / workload['id']
            command = [sys.executable, '-B', str(root / 'Test suite/device/profile_playback.py'),
                       '--serial', plan['deviceProfile']['identity']['serial'],
                       '--device-profile', str(root / plan['deviceProfile']['path']),
                       '--class', workload['test'], '--trace-processor', str(processor),
                       '--output', str(destination), '--keep-trace']
            result = run_owned(command, timeout=workload['timeoutSeconds'] + 180,
                               output_log=workspace / (workload['id'] + '.log'))
            if result.returncode:
                raise ValueError('Physical workload failed: ' + workload['test'] + '\n' + result.stdout + result.stderr)
            copy_device_records(root / 'Test suite/evidence/current-Focused', destination / 'device')
            device_run_path = destination / 'device/run.json'
            device_run = evidence.load_json(device_run_path)
            retained = {item['kind']: item for item in device_run['audioArtifacts']}
            for name, kind in (('auditApk', 'application'), ('auditTestApk', 'androidTest')):
                apk = evidence.checked_file(retained[kind], root)
                target = workspace / (name + '.apk')
                if target.is_file() and evidence.digest(target) != evidence.digest(apk):
                    raise ValueError('Physical workloads rebuilt a different Development APK')
                if not target.is_file():
                    shutil.copyfile(apk, target)
                report[name] = evidence.file_binding(target, root)
            device_run['audioArtifacts'] = [dict(report[name], kind=kind) for name, kind in
                                           (('auditApk', 'application'), ('auditTestApk', 'androidTest'))]
            previous_root = root / 'Test suite/evidence/current-Focused'
            for entry in device_run['audioExecutions']:
                relative = (root / entry['path']).relative_to(previous_root)
                entry['path'] = (destination / 'device' / relative).relative_to(root).as_posix()
            for entry in device_run['audioBuildEvidence']:
                source = evidence.checked_file(entry, root)
                relative = source.relative_to(previous_root)
                target = destination / 'device' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                entry['path'] = target.relative_to(root).as_posix()
            device_run_path.write_text(json.dumps(device_run, indent=2) + '\n', encoding='utf-8')
            report['workloads'].append({'test': workload['test'],
                'invocation': evidence.file_binding(destination / 'invocation.json', root),
                'deviceRun': evidence.file_binding(destination / 'device/run.json', root),
                'performance': evidence.file_binding(destination / 'performance.json', root),
                'trace': evidence.file_binding(destination / 'playback.pftrace', root),
                'traceProcessor': evidence.file_binding(processor, root)})
            for log in (destination / 'tester.log', workspace / (workload['id'] + '.log')):
                log.unlink(missing_ok=True)
    finally:
        report['sourceEndSha256'] = evidence.source_identity(root)
        output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    checked = validate_physical(report, plan, root)
    for name in report['supersededWorkspaces']:
        old = (root / name).resolve()
        work = (root / 'Test suite/work').resolve()
        if old.parent == work and old.name.startswith('audio-physical-') and old != workspace and old.is_dir():
            shutil.rmtree(old)
    report['supersededWorkspaces'] = []
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return checked


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(run(evidence.load_json(args.plan), args.output))
