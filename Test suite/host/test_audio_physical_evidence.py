from pathlib import Path
import json
import sqlite3
import tempfile
import unittest
from unittest import mock

import audio_evidence as evidence
import audio_physical_evidence as physical


class AudioPhysicalEvidenceTest(unittest.TestCase):
    def test_declared_speaker_profile_requires_actual_android_route_type(self):
        metrics = {name: {'value': 2} for name in ('platform_route_type_min', 'platform_route_type_max')}
        physical.require_profile_route(metrics, 'built-in-speaker')
        for route in (None, 'bluetooth', 'unknown'):
            with self.subTest(route=route), self.assertRaisesRegex(ValueError, 'unsupported'):
                physical.require_profile_route(metrics, route)
        for invalid in ({}, {'platform_route_type_min': {'value': 2}},
                        dict(metrics, platform_route_type_max={'value': 8})):
            with self.subTest(metrics=invalid), self.assertRaisesRegex(ValueError, 'TYPE_BUILTIN_SPEAKER'):
                physical.require_profile_route(invalid, 'built-in-speaker')

    def test_trace_capture_cannot_borrow_a_different_device_job(self):
        with tempfile.TemporaryDirectory(dir=evidence.SUITE / 'work') as directory:
            root = Path(directory)
            apk, invocation, run = root / 'audit.apk', root / 'invocation.json', root / 'run.json'
            apk.write_bytes(b'fixture')
            invocation.write_text(json.dumps({'status': 'PASS', 'runId': 'current-profile',
                'command': ['-ProfilePerformance', '-Class', 'engine#actual']}), encoding='utf-8')
            run.write_text(json.dumps({'runId': 'different-device-job'}), encoding='utf-8')
            plan = {'sourceSha256': 'current', 'deviceProfile': {'identity': {'serial': 'approved'}},
                    'candidateProfile': {'workloads': [{'test': 'engine#actual'}]}}
            report = {'schemaVersion': 1, 'sourceSha256': 'current', 'sourceEndSha256': 'current',
                      'profile': plan['candidateProfile'], 'deviceProfile': {'serial': 'approved'},
                      'auditApk': evidence.file_binding(apk, root), 'auditTestApk': evidence.file_binding(apk, root),
                      'workloads': [{'test': 'engine#actual', 'invocation': evidence.file_binding(invocation, root),
                                     'deviceRun': evidence.file_binding(run, root)}]}
            with mock.patch.object(physical, 'profiler'), mock.patch.object(evidence, 'device_records'):
                with self.assertRaisesRegex(ValueError, 'different device operation'):
                    physical.validate_physical(report, plan, root)

    def test_only_the_declared_trace_group_can_supply_workload_metrics(self):
        workload = {'test': 'engine#busy', 'additionalTests': ['engine#fault']}
        row = {'id': '1', 'workload': 'StemWise.test:busy', 'metric': 'callback_deadline_misses', 'value': 1}
        actual = {'metrics': [row, dict(row, id='2', workload='StemWise.test:fault', value=0)],
                  'environment': {'1': {'after': {}}, '2': {'after': {}}}}
        metrics, _ = physical.workload_metrics(actual, workload)
        self.assertEqual(1, metrics['callback_deadline_misses']['value'])
        for rows in ([dict(row, workload='StemWise.test:unrelated')],
                     [actual['metrics'][1]], [row, dict(row, id='3')]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                physical.workload_metrics(dict(actual, metrics=rows), workload)

    def test_actual_sql_reserve_uses_exact_track_instance_and_unwraps_only_real_head_wrap(self):
        def query(backward_reset=False):
            with sqlite3.connect(':memory:') as db:
                db.create_function('EXTRACT_ARG', 2, lambda args, key: args)
                db.executescript('''
                    CREATE TABLE process(upid,name); CREATE TABLE process_track(id,upid);
                    CREATE TABLE slice(id,name,ts,dur,track_id); CREATE TABLE thread_track(id,utid);
                    CREATE TABLE thread(utid,tid,upid,start_ts,end_ts);
                    CREATE TABLE ftrace_event(ts,name,arg_set_id);
                    CREATE TABLE process_counter_track(id,upid,name); CREATE TABLE counter(track_id,ts,value);
                    INSERT INTO process VALUES(1,'app.stemwise.development');
                    INSERT INTO process_track VALUES(1,1); INSERT INTO thread_track VALUES(2,1);
                    INSERT INTO thread VALUES(1,42,1,0,1000000000);
                    INSERT INTO slice VALUES(1,'StemWise.test:one',100,100000000,1);
                ''')
                fields = ('session', 'raw_head', 'submitted', 'raw_xruns', 'play_state', 'route_id', 'route_type')
                db.executemany('INSERT INTO process_counter_track VALUES(?,1,?)',
                               [(track, 'StemWise.audio.track.platform_' + name)
                                for track, name in enumerate(fields, 20)])
                samples = [(11, 4294967290, 4294967306, 0, 3, 7, 2), (11, 4, 4294967332, 0, 3, 7, 2),
                           (12, 1, 21, 0, 3, 7, 2)]
                if backward_reset:
                    samples.append((12, 0, 22, 0, 3, 7, 2))
                for index, values in enumerate(samples, 2):
                    timestamp = index * 1000
                    db.execute('INSERT INTO slice VALUES(?,?,?,?,2)',
                               (index, 'StemWise.audio.track.platform_observation', timestamp, 100))
                    db.executemany('INSERT INTO counter VALUES(?,?,?)',
                                   [(track, timestamp + track, value) for track, value in enumerate(values, 20)])
                for statement in (evidence.ROOT / 'Test suite/device/playback_performance.sql').read_text(
                        encoding='utf-8').replace('CREATE PERFETTO TABLE', 'CREATE TABLE').split(';'):
                    if statement.strip():
                        result = db.execute(statement)
                return {row[2]: row[4] for row in result.fetchall()}
        actual = query()
        self.assertEqual(16, actual['platform_queue_min_frames'])
        self.assertEqual(32, actual['platform_queue_max_frames'])
        self.assertEqual(0, actual['invalid_platform_observations'])
        self.assertEqual(1, query(True)['invalid_platform_observations'])

    def test_flag_only_or_missing_finite_workload_cannot_qualify(self):
        with tempfile.TemporaryDirectory(dir=evidence.SUITE / 'work') as directory:
            root = Path(directory)
            apk = root / 'candidate.apk'
            apk.write_bytes(b'not qualification')
            plan = {'sourceSha256': 'current', 'candidateProfile': {'workloads': [{'test': 'actual'}]},
                    'deviceProfile': {'identity': {'serial': 'approved'}}}
            report = {'schemaVersion': 1, 'sourceSha256': 'current', 'sourceEndSha256': 'current',
                      'profile': plan['candidateProfile'], 'deviceProfile': {'serial': 'approved'},
                      'measurementValid': True, 'workloads': [],
                      'auditApk': evidence.file_binding(apk, root), 'auditTestApk': evidence.file_binding(apk, root)}
            with self.assertRaisesRegex(ValueError, 'missing or unexpected physical workload'):
                physical.validate_physical(report, plan, root)
            report['sourceEndSha256'] = 'changed'
            with self.assertRaisesRegex(ValueError, 'source'):
                physical.validate_physical(report, plan, root)
