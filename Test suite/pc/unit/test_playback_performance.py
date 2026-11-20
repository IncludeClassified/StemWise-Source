from pathlib import Path
import runpy
import sqlite3
import subprocess
import unittest
from unittest.mock import Mock, patch

DEVICE = Path(__file__).resolve().parents[2] / 'device'
PROFILE = runpy.run_path(str(DEVICE / 'profile_playback.py'))
VALIDATE = PROFILE['validate_metrics']

CALLBACK_METRICS = (
    ('callback_execution_ms', 2, .06), ('callback_start_gap_ms', 1, 5),
    ('callback_physical_runs', 1, 1), ('callback_queue_min_frames', 2, 256),
    ('callback_requested_frames', 2, 512), ('callback_physical_frames', 2, 480),
    ('callback_valid_frames', 2, 448), ('callback_playing', 2, 2),
    ('callback_deadline_misses', 2, 0))
ROUTE_METRICS = (('platform_route_id_min', 2, 7), ('platform_route_id_max', 2, 7),
                 ('platform_route_type_min', 2, 2), ('platform_route_type_max', 2, 2),
                 ('invalid_platform_routes', 0, 0))


def callback_rows():
    return [dict(id='1', metric=name, samples=samples, value=value)
            for name, samples, value in CALLBACK_METRICS + ROUTE_METRICS]


def observation_rows():
    rows = []
    for phase in ('before', 'after'):
        for field, value in {'thermal_status': 2, 'power_save': 0,
                             'battery_temperature_deci_c': -30, 'battery_level': 42, 'battery_scale': 100}.items():
            rows.append(dict(id='1', metric='StemWise.environment.' + field + '.' + phase, samples=1, value=value))
        rows.append(dict(id='1', metric='StemWise.output.captured.' + phase, samples=1,
                         value=0 if phase == 'before' else 1))
    for field, value in {'sample_rate_hz': 48000, 'frames_per_burst': -1,
                         'buffer_capacity_frames': 2048, 'buffer_size_frames': 512,
                         'backend': 3, 'performance_mode': 1,
                         'xrun_count': 0, 'physical_run': 2,
                         'observation_kind': 0, 'observation_received_nanos': 100_000}.items():
        rows.append(dict(id='1', metric='StemWise.output.' + field + '.after', samples=1, value=value))
    return rows


class PlaybackPerformanceTest(unittest.TestCase):
    def test_truncated_span_is_rejected_before_missing_counter_values(self):
        rows = [dict(id='2077', workload='StemWise.test:loopMatrixAndHundredMultiStemWrapsRemainCoordinated',
                     metric=metric, samples=samples, value=value)
                for metric, samples, value in (('native_heap_end_bytes', '1', '[NULL]'),
                                               ('scheduler_wakeups', '0', '0'),
                                               ('wall_ms', '1', '-0.000001'))]
        with self.assertRaisesRegex(ValueError, 'Incomplete or truncated workload span'):
            VALIDATE(rows)

    def test_audio_track_configuration_keeps_unavailable_fields_explicit(self):
        rows = [dict(id='1', metric='wall_ms', samples=1, value=100),
                dict(id='1', metric='scheduler_wakeups', samples=1, value=1)]
        rows += [dict(id='1', metric=name, samples=2, value=0) for name in
                 ('StemWise.cpu_ms', 'StemWise.bytes-allocated', 'StemWise.gc-count',
                  'StemWise.blocking-gc-count')]
        values = {'backend': 3, 'frames_per_burst': -1, 'performance_mode': 1,
                  'buffer_size_frames': 2048, 'observation_kind': 1}
        rows += callback_rows() + [dict(row, value=values.get(row['metric'].removeprefix('StemWise.output.').removesuffix('.after'), row['value']))
                                   for row in observation_rows()]
        VALIDATE(rows)
        # Normal mode is a valid actual selection when Android declines the advisory fast mode.
        VALIDATE([dict(row, value=0) if row['metric'] == 'StemWise.output.performance_mode.after'
                  else row for row in rows])
        for field, value in (('backend', 2), ('frames_per_burst', 0),
                             ('performance_mode', 12), ('performance_mode', 2), ('xrun_count', -1), ('sample_rate_hz', 0),
                             ('physical_run', 0), ('observation_kind', 2), ('observation_received_nanos', 0)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                VALIDATE([dict(row, value=value) if row['metric'] == 'StemWise.output.' + field + '.after'
                          else row for row in rows])
        expected_fields = {'sample_rate_hz', 'frames_per_burst', 'buffer_capacity_frames',
                           'buffer_size_frames', 'backend', 'performance_mode', 'xrun_count',
                           'physical_run', 'observation_kind', 'observation_received_nanos'}
        for field in expected_fields:
            with self.subTest(missing_output=field), self.assertRaisesRegex(ValueError, 'Incomplete actual output configuration'):
                VALIDATE([row for row in rows if row['metric'] != 'StemWise.output.' + field + '.after'])

    def test_metrics_reject_missing_pairs_wakeups_and_incomplete_io(self):
        rows = [{'id': '1', 'metric': 'wall_ms', 'value': '100', 'samples': '1'},
                {'id': '1', 'metric': 'scheduler_wakeups', 'value': '10', 'samples': '10'}]
        rows += [{'id': '1', 'metric': name, 'value': '0', 'samples': '2'} for name in
                 ('StemWise.cpu_ms', 'StemWise.bytes-allocated', 'StemWise.gc-count',
                  'StemWise.blocking-gc-count')]
        rows += callback_rows() + observation_rows()
        VALIDATE(rows)
        for bad in ([], rows[:-1], [r for r in rows if r['metric'] != 'scheduler_wakeups'],
                    rows + [{'id': '1', 'metric': 'incomplete_cache_spans', 'value': '1'}],
                    [dict(r, samples='1') for r in rows],
                    [dict(r, value='-1') if r['metric']=='StemWise.cpu_ms' else r for r in rows]):
            with self.assertRaises(ValueError):
                VALIDATE(bad)

    def test_measurements_reject_nonfinite_duplicate_and_nonintegral_records(self):
        rows = [{'id': '1', 'metric': 'wall_ms', 'value': '100', 'samples': '1'},
                {'id': '1', 'metric': 'scheduler_wakeups', 'value': '10', 'samples': '10'}]
        rows += [{'id': '1', 'metric': name, 'value': '0', 'samples': '2'} for name in
                 ('StemWise.cpu_ms', 'StemWise.bytes-allocated', 'StemWise.gc-count',
                  'StemWise.blocking-gc-count')]
        rows += callback_rows() + observation_rows()
        for target, field, value in [('wall_ms','value','nan'), ('StemWise.cpu_ms','value','inf'),
                ('StemWise.gc-count','value','0.5'), ('scheduler_wakeups','samples','2.5')]:
            with self.subTest(target=target, value=value):
                bad = [dict(row, **{field:value}) if row['metric']==target else dict(row) for row in rows]
                with self.assertRaises(ValueError):
                    VALIDATE(bad)
        for target in ('wall_ms','StemWise.cpu_ms','scheduler_wakeups'):
            with self.subTest(duplicate=target):
                with self.assertRaises(ValueError):
                    VALIDATE(rows + [dict(next(row for row in rows if row['metric']==target))])
        with self.assertRaises(ValueError):
            VALIDATE(rows + [{'id':'orphan','metric':'scheduler_wakeups','value':'1','samples':'1'}])
        VALIDATE(rows)

    def test_runner_failure_preserves_partial_streams_and_classification(self):
        import io
        error = PROFILE['OwnedProcessError']({'message':'deadline', 'failureKind':'timeout',
            'stdout':'partial-out', 'stderr':'partial-err'})
        log = io.StringIO()
        runner = Mock(side_effect=error)
        with patch.dict(PROFILE['run_selection'].__globals__, run_owned=runner):
            with self.assertRaises(PROFILE['OwnedProcessError']):
                PROFILE['run_selection'](['runner','arg'], log, timeout=540)
        runner.assert_called_once_with(['runner','arg'], timeout=540)
        self.assertIn('partial-out',log.getvalue())
        self.assertIn('partial-err',log.getvalue())
        self.assertEqual('timeout',error.failure_kind)

    def test_streamed_runner_failure_preserves_the_full_file_log(self):
        import tempfile
        with tempfile.TemporaryDirectory(dir=DEVICE.parent / 'work') as directory:
            path = Path(directory) / 'tester.log'
            output = 'FULL-START\n' + 'x' * (2 * 1024 * 1024) + '\nFULL-END\n'
            error = PROFILE['OwnedProcessError']({'message':'deadline', 'failureKind':'timeout',
                'stdout':'bounded-out-tail', 'stderr':'bounded-err-tail'})
            def fail(command, *, timeout, output_log):
                self.assertEqual(path, Path(output_log))
                with Path(output_log).open('a', encoding='utf-8') as streamed:
                    streamed.write(output)
                raise error
            with path.open('w', encoding='utf-8') as log, \
                    patch.dict(PROFILE['run_selection'].__globals__, run_owned=fail):
                with self.assertRaises(PROFILE['OwnedProcessError']) as failure:
                    PROFILE['run_selection'](['runner'], log)
            self.assertIs(error, failure.exception)
            self.assertEqual(output, path.read_text(encoding='utf-8'))

    def test_native_metrics_require_actual_runs_frames_and_paired_copy_measurements(self):
        rows = [dict(id='1', metric='wall_ms', samples=1, value=100),
                dict(id='1', metric='scheduler_wakeups', samples=1, value=1)]
        rows += [dict(id='1', metric=name, samples=2, value=0) for name in
                 ('StemWise.cpu_ms', 'StemWise.bytes-allocated', 'StemWise.gc-count',
                  'StemWise.blocking-gc-count')]
        rows += callback_rows() + observation_rows()
        VALIDATE(rows)
        for metric, field, value in (
                ('callback_physical_runs', 'value', 2),
                ('callback_start_gap_ms', 'samples', 2),
                ('callback_requested_frames', 'value', 1),
                ('callback_valid_frames', 'value', 481),
                ('callback_deadline_misses', 'value', 3),
                ('callback_execution_ms', 'samples', 0)):
            with self.subTest(metric=metric), self.assertRaises(ValueError):
                VALIDATE([dict(row, **{field: value}) if row['metric'] == metric else dict(row) for row in rows])
        copies = [dict(id='1', metric='StemWise.audio.retained_body_copy', samples=1, value=.3),
                  dict(id='1', metric='retained_body_copy_bytes', samples=1, value=4096)]
        VALIDATE(rows + copies)
        for invalid in (rows[:-1], rows + copies[:1], rows + copies[1:],
                        rows + [dict(id='1', metric='invalid_audio_spans', samples=1, value=1)]):
            with self.assertRaises(ValueError):
                VALIDATE(invalid)
        for metric, value in (('StemWise.environment.power_save.after', 2),
                              ('StemWise.environment.thermal_status.before', 7),
                              ('StemWise.environment.battery_level.after', 101),
                              ('StemWise.output.buffer_size_frames.after', 2049),
                              ('StemWise.output.xrun_count.after', -1),
                              ('StemWise.output.physical_run.after', 0)):
            with self.subTest(environment=metric), self.assertRaises(ValueError):
                VALIDATE([dict(row, value=value) if row['metric'] == metric else dict(row) for row in rows])
        with self.assertRaisesRegex(ValueError, 'not captured'):
            VALIDATE([dict(row, value=0) if row['metric'] == 'StemWise.output.captured.after' else dict(row)
                      for row in rows if not (row['metric'].startswith('StemWise.output.') and
                                             row['metric'].endswith('.after') and row['metric'] != 'StemWise.output.captured.after')])

    def test_perfetto_cleanup_rejects_reused_birth_before_signalling(self):
        expected=('100',('perfetto','-o','/trace'))
        changed=('101',expected[1])
        runner=Mock()
        reader=Mock(return_value=changed)
        with patch.dict(PROFILE['stop_perfetto'].__globals__, read_perfetto_identity=reader, run_owned=runner):
            with self.assertRaisesRegex(RuntimeError,'ownership changed'):
                PROFILE['stop_perfetto'](['adb'],'123','/trace',expected)
        runner.assert_not_called()

    def test_perfetto_identity_checks_birth_and_exact_output_argument(self):
        fields=['S'] + ['0'] * 18 + ['100'] + ['0'] * 5
        text='123 (perfetto name) ' + ' '.join(fields) + '\nperfetto\0-o\0/trace\0'
        self.assertEqual(('100',('perfetto','-o','/trace','')),PROFILE['perfetto_identity'](text,'123','/trace'))
        for changed in (text.replace('/trace','/trace-other'),text.replace('100','not-a-birth'),text.replace('123 (','124 (')):
            with self.assertRaises(ValueError):
                PROFILE['perfetto_identity'](changed,'123','/trace')

    def test_profile_reports_timeout_test_failure_and_changed_birth_cleanup(self):
        import contextlib
        import io
        import json
        import sys
        import tempfile
        from types import SimpleNamespace
        for scenario in ('pass','test-failure','test-failure-before-output','timeout','changed-birth','cleanup-denied'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(dir=DEVICE.parent/'work') as directory:
                root=Path(directory)
                output=root/'capture'
                state={'alive':True, 'identityReads':0}
                commands=[]
                def run(command, *, timeout, check=False, output_log=None):
                    commands.append((command,timeout))
                    text=''
                    if '--version' in command:
                        text='fixture-version'
                    elif 'query' in command:
                        text='name,value\n'
                        if '-f' in command:
                            rows=[('wall_ms',1,100),('scheduler_wakeups',1,1)] + list(CALLBACK_METRICS + ROUTE_METRICS) + [
                                (row['metric'],row['samples'],row['value']) for row in observation_rows()] + [(name,2,0) for name in
                                ('StemWise.cpu_ms','StemWise.bytes-allocated','StemWise.gc-count','StemWise.blocking-gc-count')]
                            if scenario == 'test-failure-before-output':
                                rows = [row for row in rows if row[0] not in {
                                    metric[0] for metric in CALLBACK_METRICS + ROUTE_METRICS} and
                                    (not row[0].startswith('StemWise.output.') or '.captured.' in row[0])]
                                rows = [(name, samples, 0 if name == 'StemWise.output.captured.after' else value)
                                        for name, samples, value in rows]
                            text='id,metric,samples,value\n' + ''.join(f'1,{name},{samples},{value}\n' for name,samples,value in rows)
                    elif 'push' in command:
                        self.assertEqual('/data/misc/perfetto-configs', str(Path(command[-1]).parent).replace('\\', '/'))
                        sent_config = Path(command[-2]).read_text(encoding='utf-8')
                        self.assertIn('duration_ms: 600000', sent_config)
                        self.assertNotIn('max_file_size_bytes', sent_config)
                    elif '--background-wait' in command:
                        text='123'
                    elif command[-1].startswith('if [ -e /proc/'):
                        state['identityReads']+=1
                        remote=next(item for recorded,_ in commands for item in recorded if item.startswith('/data/misc/perfetto-traces/') and item.endswith('.pftrace'))
                        birth='101' if scenario=='changed-birth' and state['identityReads']>1 else '100'
                        fields=['S']+['0']*18+[birth]+['0']*5
                        text='123 (perfetto) '+' '.join(fields)+'\nperfetto\0-o\0'+remote+'\0' if state['alive'] else 'ABSENT'
                    elif 'kill' in command:
                        state['alive']=False
                    elif 'pull' in command:
                        Path(command[-1]).write_bytes(b'fixture trace')
                    elif '-Mode' in command:
                        self.assertEqual(output / 'tester.log', Path(output_log))
                        if scenario=='timeout':
                            raise PROFILE['OwnedProcessError']({'message':'fixture deadline','failureKind':'timeout','stdout':'partial','stderr':'warning'})
                        Path(output_log).write_text('tester output\nSTANDARD ERROR\ntester warning', encoding='utf-8')
                        return subprocess.CompletedProcess(command,7 if scenario.startswith('test-failure') else 0,'tester output','tester warning')
                    return subprocess.CompletedProcess(command,0,text,'')
                argv=['profile_playback.py','--serial','fixture','--device-profile',str(root/'profile.json'),
                    '--class','app.test#method','--trace-processor',str(root/'processor.py'),'--output',str(output)]
                original_unlink = Path.unlink
                def unlink(path, *args, **kwargs):
                    if scenario == 'cleanup-denied' and path.name in ('playback.pftrace', 'tester.log'):
                        raise PermissionError('fixture deletion denied')
                    return original_unlink(path, *args, **kwargs)
                with patch.object(sys,'argv',argv), patch.dict(PROFILE['main'].__globals__,run_owned=run,os=SimpleNamespace(name='nt')), patch.object(Path, 'unlink', unlink), contextlib.redirect_stdout(io.StringIO()):
                    if scenario in ('pass', 'cleanup-denied'):
                        PROFILE['main']()
                    else:
                        with self.assertRaisesRegex(RuntimeError, 'existing Focused test gates failed' if
                                                    scenario.startswith('test-failure') else ''):
                            PROFILE['main']()
                invocation=json.loads((output/'invocation.json').read_text())
                selections=[command for command,_ in commands if '-Mode' in command]
                self.assertEqual([invocation['command']], selections)
                self.assertNotIn('-LongOutputWarmGrace', selections[0])
                self.assertNotIn('-MinifiedAudioCandidate', selections[0])
                self.assertEqual('app.test#method', selections[0][selections[0].index('-Class') + 1])
                self.assertEqual('PASS' if scenario in ('pass', 'cleanup-denied') else 'FAIL',invocation['status'])
                self.assertEqual({'pass':None,'cleanup-denied':None,'timeout':'timeout','test-failure':'test-failure',
                                  'test-failure-before-output':'test-failure','changed-birth':'cleanup'}[scenario],invocation['failureKind'])
                if scenario == 'test-failure-before-output':
                    self.assertIn('existing Focused test gates failed', invocation['failure'])
                    self.assertIn('Missing actual per-run output route observations', invocation['analysisFailure'])
                    performance = json.loads((output / 'performance.json').read_text(encoding='utf-8'))
                    self.assertFalse(performance['testGatesPassed'])
                    self.assertFalse(performance['measurementValid'])
                    self.assertEqual(invocation['analysisFailure'], performance['invalidMeasurementReason'])
                if scenario == 'cleanup-denied':
                    self.assertTrue(any('tester.log' in warning and 'fixture deletion denied' in warning
                                        for warning in invocation['cleanupWarnings']))
                if scenario in ('pass', 'test-failure'):
                    observed = invocation['environment']['1']
                    self.assertEqual(0, observed['before']['output.captured'])
                    self.assertNotIn('output.buffer_size_frames', observed['before'])
                    self.assertEqual(-30, observed['before']['environment.battery_temperature_deci_c'])
                    self.assertEqual(512, observed['after']['output.buffer_size_frames'])
                    self.assertEqual(0, observed['after']['output.xrun_count'])
                if scenario=='changed-birth':
                    self.assertFalse(any('kill' in command for command,_ in commands))
                self.assertTrue(all(0<deadline<=540 for _,deadline in commands))

    def test_sql_attributes_process_and_time_window_and_retains_long_io(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.create_function('EXTRACT_ARG', 2, lambda args, key: args)
        db.executescript('''
CREATE TABLE process(upid, name);
CREATE TABLE process_track(id, upid);
CREATE TABLE slice(id, name, ts, dur, track_id);
CREATE TABLE thread_track(id, utid);
CREATE TABLE thread(utid, tid, upid, start_ts, end_ts);
CREATE TABLE ftrace_event(ts, name, arg_set_id);
CREATE TABLE process_counter_track(id, upid, name);
CREATE TABLE counter(track_id, ts, value);
INSERT INTO process VALUES(1,'app.stemwise.development'),(2,'other');
INSERT INTO process_track VALUES(1,1);
INSERT INTO thread VALUES(1,42,1,0,1000000000),(2,43,2,0,1000000000);
INSERT INTO thread_track VALUES(2,1),(3,2);
INSERT INTO slice VALUES(1,'StemWise.test:one',100,100000000,1),
 (2,'StemWise.cache.read',200,50000000,2),
 (3,'StemWise.cache.read',99999999,1000,2),
 (4,'StemWise.cache.read',200,90000000,3);
INSERT INTO ftrace_event VALUES(150,'sched_waking',42),(155,'sched_waking',43),
 (100000101,'sched_waking',42);
INSERT INTO process_counter_track VALUES(8,1,'StemWise.gc-count');
INSERT INTO counter VALUES(8,110,10),(8,100000099,9);
''')
        statements = (DEVICE / 'playback_performance.sql').read_text().replace(
            'CREATE PERFETTO TABLE', 'CREATE TABLE').split(';')
        result = None
        for statement in statements:
            if statement.strip():
                result = db.execute(statement)
        rows = {row[2]: row for row in result.fetchall()}
        self.assertEqual(1, rows['scheduler_wakeups'][4])
        self.assertEqual((1, 50.0, 50.0, 1, 1), rows['StemWise.cache.read'][3:])
        self.assertEqual(1, rows['incomplete_cache_spans'][4])
        self.assertEqual(-1, rows['StemWise.gc-count'][4])

    def test_sql_preserves_actual_callback_epochs_frames_and_copy_cost(self):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.create_function('EXTRACT_ARG', 2, lambda args, key: args)
        db.executescript('''
CREATE TABLE process(upid, name);
CREATE TABLE process_track(id, upid);
CREATE TABLE slice(id, name, ts, dur, track_id);
CREATE TABLE thread_track(id, utid);
CREATE TABLE thread(utid, tid, upid, start_ts, end_ts);
CREATE TABLE ftrace_event(ts, name, arg_set_id);
CREATE TABLE process_counter_track(id, upid, name);
CREATE TABLE counter(track_id, ts, value);
INSERT INTO process VALUES(1,'app.stemwise.development'),(2,'other');
INSERT INTO process_track VALUES(1,1);
INSERT INTO thread VALUES(1,42,1,0,1000000000),(2,43,2,0,1000000000);
INSERT INTO thread_track VALUES(2,1),(3,2);
INSERT INTO slice VALUES(1,'StemWise.test:one',100,100000000,1),
 (8,'StemWise.audio.callback',99,1000,2),
 (9,'StemWise.audio.retained_body_copy',70000000,300000,2);
''')
        names = ('run', 'start_gap_us', 'queue_frames', 'playing', 'execution_us',
                 'deadline_us', 'requested_frames', 'physical_frames', 'valid_frames', 'sequence',
                 'generation_at_render_decision', 'generation_at_completion')
        for track, name in enumerate(names, 20):
            db.execute('INSERT INTO process_counter_track VALUES(?,?,?)', (track, 1, 'StemWise.audio.callback.' + name))
        # A 60ms physical pause separates runs. Its first callback gap cannot count as a late start.
        for identity, timestamp, values in (
                (2, 1000000, (1, 0, 512, 1, 30, 5000, 256, 256, 256, 1, 19, 19)),
                (3, 3000000, (1, 2000, 256, 1, 20, 5000, 256, 256, 240, 2, 19, 20)),
                (4, 63000000, (2, 0, 128, 1, 10, 5000, 256, 256, 256, 3, 20, 20)),
                (5, 65000000, (2, 2000, 0, 0, 40, 5000, 256, 0, 0, 4, 20, 20))):
            db.execute('INSERT INTO slice VALUES(?,?,?,?,?)',
                       (identity, 'StemWise.audio.callback', timestamp, 100000, 2))
            db.executemany('INSERT INTO counter VALUES(?,?,?)',
                           [(track, timestamp + 100 + track, value) for track, value in enumerate(values, 20)])
        db.execute('INSERT INTO process_counter_track VALUES(40,1,?)', ('StemWise.audio.retained_body_bytes',))
        db.execute('INSERT INTO counter VALUES(40,70200000,4096)')
        db.execute('INSERT INTO process_counter_track VALUES(50,1,?)', ('StemWise.environment.phase',))
        for phase, timestamp, identity in (('before', 20000, 11), ('after', 90000000, 12)):
            db.execute('INSERT INTO slice VALUES(?,?,?,?,2)',
                       (identity, 'StemWise.audio.environment', timestamp, 10000))
            db.execute('INSERT INTO counter VALUES(50,?,?)', (timestamp+1, 1 if phase == 'before' else 2))
            for row in observation_rows():
                if not row['metric'].endswith('.' + phase):
                    continue
                name = row['metric'].rsplit('.', 1)[0]
                found = db.execute('SELECT id FROM process_counter_track WHERE name=?', (name,)).fetchone()
                track = found[0] if found else 100 + db.execute('SELECT COUNT(*) FROM process_counter_track').fetchone()[0]
                if not found:
                    db.execute('INSERT INTO process_counter_track VALUES(?,?,?)', (track, 1, name))
                db.execute('INSERT INTO counter VALUES(?,?,?)', (track, timestamp+2, row['value']))
        statements = (DEVICE / 'playback_performance.sql').read_text(encoding='utf-8').replace(
            'CREATE PERFETTO TABLE', 'CREATE TABLE').split(';')
        def analyze_rows():
            for name, in db.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall():
                if name not in {'process', 'process_track', 'slice', 'thread_track', 'thread',
                                'ftrace_event', 'process_counter_track', 'counter'}:
                    db.execute('DROP TABLE ' + name)
            for statement in statements:
                if statement.strip():
                    result = db.execute(statement)
            return {row[2]: row for row in result.fetchall()}
        rows = analyze_rows()
        self.assertEqual((4, .1, .04, 0, 0), rows['callback_execution_ms'][3:])
        self.assertEqual((2, 4.0, 2.0, 0, 0), rows['callback_start_gap_ms'][3:])
        self.assertEqual(2, rows['callback_physical_runs'][4])
        self.assertEqual(0, rows['callback_queue_min_frames'][4])
        self.assertEqual(1024, rows['callback_requested_frames'][4])
        self.assertEqual(768, rows['callback_physical_frames'][4])
        self.assertEqual(752, rows['callback_valid_frames'][4])
        self.assertEqual(3, rows['callback_playing'][4])
        self.assertEqual((1, .3, .3, 0, 0), rows['StemWise.audio.retained_body_copy'][3:])
        self.assertEqual(4096, rows['retained_body_copy_bytes'][4])
        self.assertEqual(1, rows['excluded_callback_spans'][4])
        self.assertEqual(0, rows['StemWise.output.captured.before'][4])
        self.assertNotIn('StemWise.output.buffer_size_frames.before', rows)
        self.assertEqual(512, rows['StemWise.output.buffer_size_frames.after'][4])
        self.assertEqual(-30, rows['StemWise.environment.battery_temperature_deci_c.before'][4])
        self.assertNotIn('invalid_audio_spans', rows)
        self.assertEqual([(1, 1), (1, 2), (2, 3), (2, 4)], db.execute(
            'SELECT physical_run,callback_sequence FROM callback_samples ORDER BY ts').fetchall())
        self.assertEqual([(19, 19), (19, 20), (20, 20), (20, 20)], db.execute(
            'SELECT generation_at_render_decision,generation_at_completion FROM callback_samples ORDER BY ts').fetchall())
        # Preserve the distinction between starvation before adoption and recovery on the new owner.
        for timestamp, values in ((3000000, {22: 192, 27: 192, 28: 192, 31: 19}),
                                  (63000000, {22: 0, 27: 0, 28: 0, 30: 19, 31: 19}),
                                  (65000000, {22: 256, 23: 1, 27: 256, 28: 256})):
            for track, value in values.items():
                db.execute('UPDATE counter SET value=? WHERE track_id=? AND ts=?',
                           (value, track, timestamp + 100 + track))
        self.assertNotIn('invalid_audio_spans', analyze_rows())
        self.assertEqual([(1, 19, 19, 256), (2, 19, 19, 192), (3, 19, 19, 0), (4, 20, 20, 256)], db.execute(
            'SELECT callback_sequence,generation_at_render_decision,generation_at_completion,physical_frames '
            'FROM callback_samples ORDER BY ts').fetchall())
        for track in (30, 31):
            for invalid in (0, -1, 19.5):
                db.execute('UPDATE counter SET value=? WHERE track_id=? AND ts=?',
                           (invalid, track, 1000100 + track))
                self.assertEqual(1, analyze_rows()['invalid_audio_spans'][4])
            db.execute('UPDATE counter SET value=19 WHERE track_id=? AND ts=?', (track, 1000100 + track))
        db.execute('DELETE FROM counter WHERE track_id=31 AND ts=1000131')
        self.assertEqual(1, analyze_rows()['invalid_audio_spans'][4])
        db.execute('INSERT INTO counter VALUES(31,1000131,19)')
        db.execute('UPDATE counter SET value=1 WHERE track_id=29 AND ts=3000129')
        self.assertEqual(1, analyze_rows()['invalid_audio_spans'][4])
        db.execute('UPDATE counter SET value=9 WHERE track_id=29 AND ts=3000129')
        self.assertNotIn('invalid_audio_spans', analyze_rows(), 'strict increase permits an unobserved callback ticket')
        db.execute('UPDATE counter SET value=2 WHERE track_id=29 AND ts=3000129')
        # Remove one actual field, then use the same SQL consumer: absence cannot become success.
        db.execute('DELETE FROM counter WHERE track_id=28 AND ts<2000000')
        bad_rows = analyze_rows()
        self.assertEqual(1, bad_rows['invalid_audio_spans'][4])
        db.execute('INSERT INTO counter VALUES(28,1000128,256)')
        db.execute("INSERT INTO slice VALUES(10,'StemWise.audio.callback',1050000,100000,3)")
        self.assertNotIn('invalid_audio_spans', analyze_rows())
        db.execute('UPDATE slice SET track_id=2 WHERE id=10')
        self.assertEqual(2, analyze_rows()['invalid_audio_spans'][4],
                         'overlap invalidates the otherwise complete callback too; process counters cannot identify its producer')

    def test_sql_requires_actual_stable_route_for_each_presenting_run(self):
        import audio_physical_evidence as physical
        def query(change):
            with sqlite3.connect(':memory:') as db:
                db.create_function('EXTRACT_ARG', 2, lambda args, key: args)
                db.executescript('''
CREATE TABLE process(upid,name); CREATE TABLE process_track(id,upid);
CREATE TABLE slice(id,name,ts,dur,track_id); CREATE TABLE thread_track(id,utid);
CREATE TABLE thread(utid,tid,upid,start_ts,end_ts); CREATE TABLE ftrace_event(ts,name,arg_set_id);
CREATE TABLE process_counter_track(id,upid,name); CREATE TABLE counter(track_id,ts,value);
INSERT INTO process VALUES(1,'app.stemwise.development'); INSERT INTO process_track VALUES(1,1);
INSERT INTO thread VALUES(1,42,1,0,1000000),(2,43,1,0,1000000);
INSERT INTO thread_track VALUES(2,1),(3,2);
INSERT INTO slice VALUES(1,'StemWise.test:route',100,100000,1);
''')
                names = ('run', 'start_gap_us', 'queue_frames', 'playing', 'execution_us', 'deadline_us',
                         'requested_frames', 'physical_frames', 'valid_frames', 'sequence',
                         'generation_at_render_decision', 'generation_at_completion')
                db.executemany('INSERT INTO process_counter_track VALUES(?,1,?)',
                               [(index, 'StemWise.audio.callback.' + name) for index, name in enumerate(names, 20)])
                for index, timestamp in enumerate((1000, 4000, 7000, 9000), 1):
                    db.execute('INSERT INTO slice VALUES(?,?,?,?,2)',
                               (index + 1, 'StemWise.audio.callback', timestamp, 100))
                    values = (1 if index <= 2 else 2, 0 if index in (1, 3) else 2000,
                              512, 1, 20, 5000, 256, 256, 256, index, 19, 19)
                    db.executemany('INSERT INTO counter VALUES(?,?,?)',
                                   [(track, timestamp + 1, value) for track, value in enumerate(values, 20)])
                fields = ('session', 'raw_head', 'submitted', 'raw_xruns', 'play_state', 'route_id', 'route_type')
                db.executemany('INSERT INTO process_counter_track VALUES(?,1,?)',
                               [(track, 'StemWise.audio.track.platform_' + name) for track, name in enumerate(fields, 50)])
                # Head-zero PLAYING snapshots genuinely have no route yet; both presenting runs must later prove it.
                samples = [(1100, 11, 0, 0, 0), (2500, 11, 8, 7, 2), (4500, 11, 16, 7, 2),
                           (7100, 12, 0, 0, 0), (8000, 12, 8, 7, 2), (9500, 12, 16, 7, 2)]
                for index, (timestamp, session, head, route_id, route_type) in enumerate(samples, 20):
                    if change == 'missing-run' and timestamp >= 8000:
                        continue
                    if change == 'unknown' and timestamp >= 8000:
                        route_id = route_type = 0
                    if change == 'wrong-type' and head > 0:
                        route_type = 8
                    if change == 'changed-id' and timestamp == 9500:
                        route_id = 8
                    if change == 'changed-type' and timestamp == 9500:
                        route_type = 8
                    if change == 'changed-session' and timestamp == 9500:
                        session = 13
                    if change == 'stale-session' and timestamp >= 8000:
                        route_id = route_type = 0  # Earlier session 11's speaker samples cannot qualify session 12.
                    if change == 'backward-head' and timestamp == 9500:
                        head = 4
                    submitted = head + 16
                    if change == 'head-wrap' and timestamp >= 7100:
                        head = 4294967288 if timestamp == 7100 else head
                        submitted = head + 16 + (4294967296 if timestamp > 7100 else 0)
                        route_id, route_type = 7, 2
                    track = 3 if change == 'other-thread' and timestamp >= 8000 else 2
                    db.execute('INSERT INTO slice VALUES(?,?,?,?,?)',
                               (index, 'StemWise.audio.track.platform_observation', timestamp, 100, track))
                    values = (session, head, submitted, 0, 3, route_id, route_type)
                    db.executemany('INSERT INTO counter VALUES(?,?,?)',
                                   [(counter, timestamp + 1, value) for counter, value in enumerate(values, 50)
                                    if not (change == 'missing-counter' and timestamp >= 8000 and counter == 55)
                                    and not (change in ('missing-xrun', 'missing-and-duplicate')
                                             and timestamp == 9500 and counter == 53)])
                    if change in ('duplicate-counter', 'missing-and-duplicate') and timestamp == 9500:
                        db.execute('INSERT INTO counter VALUES(56,?,?)', (timestamp + 2, route_type))
                    if change == 'null-xrun' and timestamp == 9500:
                        db.execute('UPDATE counter SET value=NULL WHERE track_id=53 AND ts=?', (timestamp + 1,))
                for statement in (DEVICE / 'playback_performance.sql').read_text(encoding='utf-8').replace(
                        'CREATE PERFETTO TABLE', 'CREATE TABLE').split(';'):
                    if statement.strip():
                        result = db.execute(statement)
                return {row[2]: dict(id='1', metric=row[2], samples=row[3], value=row[4]) for row in result.fetchall()}
        healthy = query('healthy')
        self.assertEqual(0, healthy['invalid_platform_routes']['value'])
        self.assertEqual(7, healthy['platform_route_id_min']['value'])
        self.assertEqual(2, healthy['platform_route_type_max']['value'])
        def measurement(route):
            rows = callback_rows() + observation_rows() + [dict(id='1', metric='wall_ms', samples=1, value=100),
                    dict(id='1', metric='scheduler_wakeups', samples=1, value=1)]
            rows += [dict(id='1', metric=name, samples=2, value=0) for name in
                     ('StemWise.cpu_ms', 'StemWise.bytes-allocated', 'StemWise.gc-count', 'StemWise.blocking-gc-count')]
            return [route[row['metric']] if row['metric'] in PROFILE['ROUTE_METRICS'] else row for row in rows] + [
                route['invalid_platform_observations']]
        VALIDATE(measurement(healthy))
        physical.require_profile_route(healthy, 'built-in-speaker')
        wrapped = query('head-wrap')
        self.assertEqual(0, wrapped['invalid_platform_observations']['value'])
        VALIDATE(measurement(wrapped))
        physical.require_profile_route(wrapped, 'built-in-speaker')
        for change in ('missing-run', 'unknown', 'changed-id', 'changed-type', 'changed-session',
                       'stale-session', 'other-thread'):
            with self.subTest(change=change), self.assertRaisesRegex(ValueError, 'route'):
                VALIDATE(measurement(query(change)))
        for change in ('missing-counter', 'missing-xrun', 'duplicate-counter', 'missing-and-duplicate',
                       'null-xrun', 'backward-head'):
            with self.subTest(change=change):
                malformed = query(change)
                self.assertEqual(2 if change == 'missing-counter' else 1,
                                 malformed['invalid_platform_observations']['value'])
                with self.assertRaisesRegex(ValueError, 'invalid_platform_observations'):
                    VALIDATE(measurement(malformed))
        wrong = query('wrong-type')
        VALIDATE(measurement(wrong))  # A coherent route is still unsuitable for the declared speaker profile.
        with self.assertRaisesRegex(ValueError, 'TYPE_BUILTIN_SPEAKER'):
            physical.require_profile_route(wrong, 'built-in-speaker')


if __name__ == '__main__':
    unittest.main()
