"""Profile one Focused selection through Perfetto; existing test gates remain authoritative."""
import argparse
import csv
import io
import json
import math
import os
import re
import shutil
import sys
import time
from pathlib import Path
from uuid import uuid4

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'host'))
from owned_process import run as run_owned, OwnedProcessError
PACKAGE = 'app.stemwise.development'
ENVIRONMENT_FIELDS = frozenset({'thermal_status', 'power_save', 'battery_temperature_deci_c',
                                'battery_level', 'battery_scale'})
OUTPUT_FIELDS = frozenset({'sample_rate_hz', 'frames_per_burst', 'buffer_capacity_frames',
                          'buffer_size_frames', 'backend', 'performance_mode',
                          'xrun_count', 'physical_run', 'observation_kind', 'observation_received_nanos'})
ROUTE_METRICS = frozenset({'platform_route_id_min', 'platform_route_id_max',
                           'platform_route_type_min', 'platform_route_type_max', 'invalid_platform_routes'})
OBSERVATION_METRICS = frozenset(
    prefix + field + '.' + phase
    for phase in ('before', 'after')
    for prefix, fields in (('StemWise.environment.', ENVIRONMENT_FIELDS),
                           ('StemWise.output.', OUTPUT_FIELDS | {'captured'}))
    for field in fields)
CONFIG = '''duration_ms: 600000
buffers { size_kb: 131072 fill_policy: RING_BUFFER }
buffers { size_kb: 1024 fill_policy: RING_BUFFER }
write_into_file: true
file_write_period_ms: 250
flush_period_ms: 1000
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_waking"
  ftrace_events: "sched/sched_switch"
  ftrace_events: "sched/sched_process_exit"
  ftrace_events: "task/task_newtask"
  ftrace_events: "task/task_rename"
  atrace_categories: "audio"
  atrace_apps: "app.stemwise.development"
} } }
data_sources { config { name: "linux.process_stats" target_buffer: 1 process_stats_config {
  scan_all_processes_on_start: true
} } }
'''


def validate_metrics(rows):
    paired = {'StemWise.cpu_ms', 'StemWise.bytes-allocated', 'StemWise.gc-count',
              'StemWise.blocking-gc-count'}
    fields = {'id','workload','metric','samples','value','max_ms','over_10ms','over_50ms'}
    callback = {'callback_execution_ms', 'callback_start_gap_ms', 'callback_physical_runs',
                'callback_queue_min_frames', 'callback_requested_frames', 'callback_physical_frames',
                'callback_valid_frames', 'callback_playing', 'callback_deadline_misses'}
    extra = callback | OBSERVATION_METRICS | ROUTE_METRICS | {'retained_body_copy_bytes', 'StemWise.audio.retained_body_copy',
                        'excluded_callback_spans', 'invalid_audio_spans', 'callback_headroom_min_ms',
                        'platform_queue_min_frames', 'platform_queue_max_frames', 'platform_xrun_max',
                        'invalid_platform_observations'}
    metrics = {}
    sample_counts = {}
    def number(value, name, integral=False, allow_negative=False):
        if isinstance(value, bool):
            raise ValueError('Invalid metric number: ' + name)
        try:
            parsed = float(value)
        except (ValueError, TypeError) as error:
            raise ValueError('Invalid metric number: ' + name) from error
        if not math.isfinite(parsed) or (parsed < 0 and not allow_negative) or (integral and not parsed.is_integer()):
            raise ValueError('Nonfinite, negative or fractional metric: ' + name)
        return parsed
    # An open Perfetto slice has dur=-1; diagnose the capture boundary before its absent counters.
    for row in rows:
        if isinstance(row, dict) and row.get('metric') == 'wall_ms':
            try:
                complete = number(row.get('value'), 'wall_ms') > 0
            except ValueError:
                complete = False
            if not complete:
                raise ValueError('Incomplete or truncated workload span; numerical conclusions rejected: ' + str(row.get('id')))
    for row in rows:
        if not isinstance(row, dict) or set(row) - fields or not {'id','metric','samples','value'} <= set(row):
            raise ValueError('Invalid metric record shape.')
        identity, metric = row['id'], row['metric']
        if not isinstance(identity, str) or not identity or not isinstance(metric, str):
            raise ValueError('Invalid metric identity.')
        if metric not in paired | extra | {'wall_ms','scheduler_wakeups','native_heap_end_bytes','incomplete_cache_spans'} and not metric.startswith('StemWise.cache.'):
            raise ValueError('Unknown metric: ' + metric)
        key = (identity, metric)
        if key in metrics:
            raise ValueError('Duplicate metric: ' + str(key))
        samples = number(row['samples'], metric + '.samples', integral=True)
        integral = metric in (paired - {'StemWise.cpu_ms'}) | (extra - {
            'callback_execution_ms', 'callback_start_gap_ms', 'StemWise.audio.retained_body_copy',
            'callback_headroom_min_ms'}) | {
                'scheduler_wakeups','native_heap_end_bytes','incomplete_cache_spans'}
        value = number(row['value'], metric, integral=integral,
            allow_negative=(metric in ('callback_headroom_min_ms', 'platform_queue_min_frames') or
                            metric.startswith('StemWise.environment.battery_temperature_deci_c.') or
                            metric.rsplit('.', 1)[0] == 'StemWise.output.frames_per_burst'))
        for field in ('max_ms','over_10ms','over_50ms'):
            if row.get(field) not in (None, '', 'NULL', '[NULL]'):
                count = number(row[field], metric + '.' + field, integral=field != 'max_ms')
                if field != 'max_ms' and count > samples:
                    raise ValueError('Metric count exceeds samples: ' + metric)
        if (metric == 'wall_ms' and (value <= 0 or samples != 1)
                or metric in paired and samples != 2
                or metric == 'scheduler_wakeups' and value != samples):
            raise ValueError('Missing/inconsistent metric samples: ' + metric)
        if metric == 'incomplete_cache_spans':
            raise ValueError('Cache I/O crosses a workload boundary; numerical conclusions rejected.')
        if metric == 'invalid_audio_spans':
            raise ValueError('Incomplete or inconsistent native audio observations; numerical conclusions rejected.')
        if metric == 'invalid_platform_observations' and value != 0:
            raise ValueError('invalid_platform_observations: incomplete or inconsistent platform evidence.')
        metrics[key] = value
        sample_counts[key] = samples
    workloads = {identity for identity, metric in metrics if metric == 'wall_ms'}
    if not workloads or any(identity not in workloads for identity, _ in metrics):
        raise ValueError('Missing or incomplete workload spans.')
    for identity in workloads:
        if any((identity, metric) not in metrics for metric in ROUTE_METRICS):
            raise ValueError('Missing actual per-run output route observations: ' + identity)
        route = lambda metric: metrics[(identity, metric)]
        route_samples = {sample_counts[(identity, metric)] for metric in ROUTE_METRICS - {'invalid_platform_routes'}}
        if (route('invalid_platform_routes') != 0 or len(route_samples) != 1 or min(route_samples) <= 0 or
                route('platform_route_id_min') <= 0 or route('platform_route_type_min') <= 0 or
                route('platform_route_id_min') != route('platform_route_id_max') or
                route('platform_route_type_min') != route('platform_route_type_max')):
            raise ValueError('Missing, changed or unbound presented output route: ' + identity)
        if any((identity, metric) not in metrics for metric in paired | {'scheduler_wakeups'}):
            raise ValueError('Missing paired counter or scheduler observation: ' + identity)
        if any((identity, metric) not in metrics for metric in callback):
            raise ValueError('Missing actual native callback observations: ' + identity)
        count = sample_counts[(identity, 'callback_execution_ms')]
        runs = metrics[(identity, 'callback_physical_runs')]
        if (count <= 1 or not 0 < runs < count or
                metrics[(identity, 'callback_requested_frames')] < count or
                metrics[(identity, 'callback_start_gap_ms')] <= 0 or
                sample_counts[(identity, 'callback_physical_runs')] != runs or
                sample_counts[(identity, 'callback_start_gap_ms')] != count-runs or
                any(sample_counts[(identity, metric)] != count for metric in callback - {
                    'callback_physical_runs', 'callback_start_gap_ms'}) or
                not metrics[(identity, 'callback_valid_frames')] <=
                    metrics[(identity, 'callback_physical_frames')] <= metrics[(identity, 'callback_requested_frames')] or
                metrics[(identity, 'callback_playing')] > count or
                metrics[(identity, 'callback_deadline_misses')] > count):
            raise ValueError('Inconsistent callback run, frame or deadline samples: ' + identity)
        copy_metrics = {'retained_body_copy_bytes', 'StemWise.audio.retained_body_copy'}
        present = {metric for metric in copy_metrics if (identity, metric) in metrics}
        if present and (present != copy_metrics or
                sample_counts[(identity, 'StemWise.audio.retained_body_copy')] <= 0 or
                len({sample_counts[(identity, metric)] for metric in present}) != 1):
            raise ValueError('Missing retained-body copy bytes or duration: ' + identity)
        for phase in ('before', 'after'):
            required = {'StemWise.environment.' + field + '.' + phase for field in ENVIRONMENT_FIELDS}
            captured = 'StemWise.output.captured.' + phase
            if any((identity, metric) not in metrics or sample_counts[(identity, metric)] != 1
                   for metric in required | {captured}):
                raise ValueError('Missing actual workload-boundary environment: ' + identity + '/' + phase)
            def observed(prefix, field):
                return metrics[(identity, prefix + field + '.' + phase)]
            if (observed('StemWise.environment.', 'thermal_status') > 6 or
                    observed('StemWise.environment.', 'power_save') not in (0, 1) or
                    observed('StemWise.environment.', 'battery_scale') <= 0 or
                    observed('StemWise.environment.', 'battery_level') > observed('StemWise.environment.', 'battery_scale') or
                    metrics[(identity, captured)] not in (0, 1)):
                raise ValueError('Invalid actual environment observation: ' + identity + '/' + phase)
            output = {'StemWise.output.' + field + '.' + phase for field in OUTPUT_FIELDS}
            supplied = {metric for metric in output if (identity, metric) in metrics}
            if metrics[(identity, captured)] == 0:
                if supplied or phase == 'after':
                    raise ValueError('Actual running output configuration was not captured: ' + identity + '/' + phase)
                continue
            if supplied != output or any(sample_counts[(identity, metric)] != 1 for metric in output):
                raise ValueError('Incomplete actual output configuration: ' + identity + '/' + phase)
            backend = observed('StemWise.output.', 'backend')
            adapter_fields_valid = (backend == 3 and
                observed('StemWise.output.', 'frames_per_burst') == -1 and
                observed('StemWise.output.', 'performance_mode') in (0, 1))
            if (observed('StemWise.output.', 'sample_rate_hz') <= 0 or
                    observed('StemWise.output.', 'buffer_size_frames') <= 0 or
                    observed('StemWise.output.', 'buffer_size_frames') > observed('StemWise.output.', 'buffer_capacity_frames') or
                    not adapter_fields_valid or
                    observed('StemWise.output.', 'observation_kind') not in (0, 1) or
                    observed('StemWise.output.', 'observation_received_nanos') <= 0 or
                    (phase == 'after' and observed('StemWise.output.', 'physical_run') <= 0)):
                raise ValueError('Invalid accepted output parameters: ' + identity + '/' + phase)
    if not any(value > 0 for (identity, metric), value in metrics.items() if metric == 'scheduler_wakeups'):
        raise ValueError('No scheduler wakeups observed in this capture.')


def run_selection(command, log, timeout=540):
    output_log = getattr(log, 'name', None)
    try:
        result = run_owned(command, timeout=timeout, **({'output_log': output_log} if output_log else {}))
    except OwnedProcessError as error:
        if log is not None and not output_log:
            log.write(error.stdout + '\nSTANDARD ERROR\n' + error.stderr)
            log.flush()
        raise
    if log is not None and not output_log:
        log.write(result.stdout + '\nSTANDARD ERROR\n' + result.stderr)
        log.flush()
    return result.returncode


def perfetto_identity(text, pid, remote):
    if text.strip() == 'ABSENT':
        return None
    stat, separator, command = text.partition('\n')
    closing = stat.rfind(')')
    if not separator or closing < 0 or stat.split(' ', 1)[0] != pid:
        raise ValueError('Cannot establish Perfetto process identity')
    fields = stat[closing + 2:].split()
    tokens = tuple(command.rstrip('\r\n').split('\0'))
    if len(fields) <= 19 or not fields[19].isdecimal() or remote not in tokens or not any(Path(token).name == 'perfetto' for token in tokens):
        raise ValueError('Unexpected Perfetto process identity')
    return (fields[19], tokens)


def read_perfetto_identity(adb, pid, remote):
    if not pid.isdecimal():
        raise ValueError('Invalid Perfetto PID')
    # Existence and both identity fields are read in one bounded device operation.
    script = f'if [ -e /proc/{pid}/stat ]; then cat /proc/{pid}/stat; cat /proc/{pid}/cmdline; else printf ABSENT; fi'
    result = run_owned(adb + ['shell', script], timeout=15, check=True)
    return perfetto_identity(result.stdout, pid, remote)


def stop_perfetto(adb, pid, remote, expected):
    identity = read_perfetto_identity(adb, pid, remote)
    if identity is None:
        return
    if identity != expected:
        raise RuntimeError('Perfetto process ownership changed; remote trace retained')
    run_owned(adb + ['shell', 'kill', '-TERM', pid], timeout=15, check=True)
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        identity = read_perfetto_identity(adb, pid, remote)
        if identity is None:
            return
        if identity != expected:
            raise RuntimeError('Perfetto process ownership changed during shutdown; remote trace retained')
        time.sleep(.2)
    raise RuntimeError('Perfetto did not flush; remote trace retained')


def find_perfetto_pid(adb, remote):
    # The invocation owns a unique trace path, including when startup loses its PID reply.
    script = ("for p in $(pidof perfetto); do "
              "if tr '\\0' '\\n' </proc/$p/cmdline | grep -Fx -- '" + remote +
              "' >/dev/null; then echo $p; fi; done")
    result = run_owned(adb + ['shell', script], timeout=15, check=True)
    pids = result.stdout.split()
    if len(pids) > 1 or any(not pid.isdecimal() for pid in pids):
        raise RuntimeError('Cannot establish unique Perfetto startup ownership')
    return pids[0] if pids else None


def analyze(processor, trace, output, test_gates_passed):
    command = [sys.executable, str(processor), 'query', str(trace)]
    result = run_owned(command + ['-f', str(HERE / 'playback_performance.sql')], timeout=180, check=True)
    (output / 'trace-analysis.log').write_text(result.stderr, encoding='utf-8')
    rows = list(csv.DictReader(io.StringIO(result.stdout)))
    # Dropped packets/ftrace events invalidate numerical claims, even when the app tests pass.
    loss = run_owned(command + ["SELECT name,value FROM stats WHERE value>0 AND "
                          "(severity='data_loss' OR name GLOB '*overrun*' OR name GLOB '*dropped*')"],
                          timeout=180, check=True)
    losses = list(csv.DictReader(io.StringIO(loss.stdout)))
    report = {'schemaVersion': 2, 'scope': 'whole app process including test setup and cleanup',
              'wakeups': 'sched_waking events targeting this process; not device suspend wakeups',
              'cacheIo': 'wall time inside synchronous read/write, including scheduling and retry waits',
              'callbacks': 'complete native callback observations; start gaps exclude the first observation per physical run/thread and workload',
              'outputObservations': 'kind 0: app-owned snapshot; kind 1: last test-owned final snapshot after output retirement, before native owner deletion. Receipt nanos use System.nanoTime; they are not a live after-test sample or a DAC timestamp.',
              'retainedBodyCopy': 'successful logical PCM bytes appended to spare reservoirs; duration includes the bounded transfer work',
              'metrics': rows, 'dataLoss': losses, 'measurementValid': False,
              'testGatesPassed': test_gates_passed}
    try:
        if losses:
            raise ValueError('Trace data loss; numerical conclusions rejected.')
        validate_metrics(rows)
        report['environment'] = {}
        for row in rows:
            if row['metric'] not in OBSERVATION_METRICS:
                continue
            name, phase = row['metric'].rsplit('.', 1)
            phase_values = report['environment'].setdefault(row['id'], {}).setdefault(phase, {})
            phase_values[name.removeprefix('StemWise.')] = int(float(row['value']))
        report['measurementValid'] = True
    except ValueError as error:
        report['invalidMeasurementReason'] = str(error)
        raise
    finally:
        (output / 'performance.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report['environment']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--device-profile', type=Path, required=True)
    parser.add_argument('--class', dest='selector', required=True)
    parser.add_argument('--trace-processor', type=Path, required=True,
                        help='Official get.perfetto.dev/trace_processor Python wrapper')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keep-trace', action='store_true', help='Retain raw Perfetto data after a successful analysis')
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('Use Windows Python with this PowerShell/ADB workspace runner.')
    if not re.fullmatch(r'[A-Za-z0-9._:-]+', args.serial):
        parser.error('Invalid device serial.')
    version = run_owned([sys.executable, str(args.trace_processor.resolve()), '--version'], timeout=30, check=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    adb = [shutil.which('adb') or 'adb', '-s', args.serial]
    remote = '/data/misc/perfetto-traces/stemwise-' + uuid4().hex + '.pftrace'
    trace = output / 'playback.pftrace'
    configuration = output / 'config.pbtxt'
    configuration.write_text(CONFIG, encoding='utf-8')
    remote_config = '/data/misc/perfetto-configs/' + Path(remote).name + '.config'
    run_id = 'PROFILE-' + uuid4().hex[:12]
    command = [shutil.which('pwsh') or 'powershell', '-NoProfile', '-NonInteractive', '-File',
               str(HERE.parent / 'Run-Tester.ps1'), '-Mode', 'Focused', '-Serial', args.serial,
               '-DeviceProfile', str(args.device_profile.resolve()), '-Class', args.selector,
               '-ProfilePerformance', '-RunId', run_id]
    invocation = {'command': command, 'runId': run_id,
        'traceProcessorVersion': version.stdout.strip(), 'status': 'RUNNING', 'failureKind': None}
    exit_code = None
    pid = None
    expected_identity = None
    start_attempted = False
    failure = None
    try:
        (output / 'invocation.json').write_text(json.dumps(invocation, indent=2), encoding='utf-8')
        run_owned(adb + ['push', str(configuration), remote_config], timeout=30, check=True)
        start_attempted = True
        started = run_owned(adb + ['shell', 'perfetto', '--background-wait', '--txt',
            '-c', remote_config, '-o', remote], timeout=30, check=True)
        pid = started.stdout.strip()
        if not pid.isdecimal():
            pid = None
            raise ValueError('Unexpected Perfetto startup reply')
        expected_identity = read_perfetto_identity(adb, pid, remote)
        if expected_identity is None:
            raise RuntimeError('Perfetto exited before ownership was established')
        run_owned(adb + ['shell', 'rm', remote_config], timeout=15, check=True)
        with (output / 'tester.log').open('w', encoding='utf-8') as log:
            exit_code = run_selection(command, log)
        invocation['status'] = 'TEST_GATES_PASSED' if exit_code == 0 else 'FAIL'
        if exit_code:
            invocation['failureKind'] = 'test-failure'
    except BaseException as error:
        failure = error
        invocation.update(status='FAIL', failureKind=getattr(error, 'failure_kind', 'infrastructure'),
                          failure=str(error))
    finally:
        if start_attempted:
            try:
                pid = pid or find_perfetto_pid(adb, remote)
                if pid is not None:
                    expected_identity = expected_identity or read_perfetto_identity(adb, pid, remote)
                    if expected_identity is not None:
                        stop_perfetto(adb, pid, remote, expected_identity)
                        run_owned(adb + ['pull', remote, str(trace)], timeout=120, check=True)
                        run_owned(adb + ['shell', 'rm', remote], timeout=15, check=True)
            except BaseException as error:
                invocation.update(status='FAIL', failureKind='cleanup', cleanupFailure=str(error))
                if failure is None:
                    failure = error
        try:
            run_owned(adb + ['shell', 'rm', '-f', remote_config], timeout=15, check=True)
        except BaseException as error:
            invocation.update(status='FAIL', failureKind='cleanup', configCleanupFailure=str(error))
            if failure is None:
                failure = error
        (output / 'invocation.json').write_text(json.dumps(invocation, indent=2), encoding='utf-8')
    if failure is not None:
        raise failure
    try:
        try:
            invocation['environment'] = analyze(args.trace_processor.resolve(), trace, output, exit_code == 0)
        except Exception as error:
            if not exit_code:
                raise
            invocation['analysisFailure'] = str(error)
        if exit_code:
            raise RuntimeError('The existing Focused test gates failed; see tester.log.')
        if not args.keep_trace:
            for disposable in (trace, output / 'tester.log'):
                try:
                    disposable.unlink()
                except OSError as error:
                    warning = f'{disposable}: {error}'
                    invocation.setdefault('cleanupWarnings', []).append(warning)
                    print('WARNING: disposable profiling file remains: ' + warning, file=sys.stderr)
        invocation['status'] = 'PASS'
    except BaseException as error:
        invocation.update(status='FAIL', failureKind=invocation['failureKind'] or
            getattr(error, 'failure_kind', 'invalid-measurement'), failure=str(error))
        raise
    finally:
        (output / 'invocation.json').write_text(json.dumps(invocation, indent=2), encoding='utf-8')
    print('PASS: test gates and profiling completeness; ' + str(output / 'performance.json'))


if __name__ == '__main__':
    main()
