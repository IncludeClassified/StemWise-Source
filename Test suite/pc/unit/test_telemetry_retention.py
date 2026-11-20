"""Linux PC telemetry retention and publication ownership regressions."""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.workflow_context import report_control_path
from stemwise_engine.workflow_records import OperationStart, ReportBindings
from stemwise_engine.workflow_reports import ImmutableReportStore


def fixture_workspace(name):
    from stemwise_engine.common import atomic_write_json
    root = Path(name)
    atomic_write_json(root/'PC side/stemwise-playlist-builder/config.json',
        {'futureRootWindows': 'future-songs', 'pcSideRootWindows': '..'})
    return root


class TelemetryRetentionTest(unittest.TestCase):
    def test_aggregate_budget_keeps_active_references_across_sources(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            videos = ('a1B2c3D4e5F', 'b1B2c3D4e5F')
            keep, remove = [workspace/f'Engine telemetry/youtube-{video}/reports/runs/{index:032x}.json'
                for index, video in enumerate(videos, 1)]
            for path in (keep, remove): atomic_write_json(path, {'status':'complete'})
            job = workspace/f'PC side/stemwise-playlist-builder/future-songs/policy-state/current/jobs/{videos[0]}/job.json'
            atomic_write_json(job, {'videoId':videos[0], 'run':keep.relative_to(workspace).as_posix()})
            retention.cleanup(workspace, max_total_bytes=0)
            self.assertTrue(keep.is_file())
            self.assertFalse(remove.exists())

    def test_relocated_current_job_protects_its_exact_old_run(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            builder = workspace/'PC side/stemwise-playlist-builder'
            atomic_write_json(builder/'config.json', {'futureRootWindows': 'relocated-state', 'pcSideRootWindows': '..'})
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            keep, remove = [source/'reports/runs'/f'{i:032x}.json' for i in (1, 2)]
            for path in (keep, remove):
                atomic_write_json(path, {'status': 'complete'})
            atomic_write_json(builder/'relocated-state/policy-state/current/jobs/a1B2c3D4e5F/job.json',
                {'videoId': 'a1B2c3D4e5F', 'run': keep.relative_to(workspace).as_posix()})
            retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertTrue(keep.is_file())
            self.assertFalse(remove.exists())

    def test_missing_bare_words_avoid_traversal_without_losing_exact_references(self):
        import os
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json, sha256_file
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            video = 'a1B2c3D4e5F'
            source = workspace/'Engine telemetry'/f'youtube-{video}'
            candidates = [source/'reports/runs'/f'{index:032x}.json' for index in range(1, 7)]
            for index, path in enumerate(candidates):
                atomic_write_json(path, {'status': 'complete', 'uniqueContent': index})
            before = {path: path.read_bytes() for path in candidates}
            job = workspace/f'PC side/stemwise-playlist-builder/future-songs/policy-state/current/jobs/{video}/job.json'
            job.parent.mkdir(parents=True)
            (job.parent/'retained-link').symlink_to(candidates[4])
            missing = {f'missing-free-word-{index}' for index in range(32)}
            atomic_write_json(job, {'videoId': video, 'runId': candidates[0].stem,
                'sha256': sha256_file(candidates[1]),
                'relative': os.path.relpath(candidates[2], job.parent), 'linked': 'retained-link',
                'ordinaryWords': sorted(missing), 'freeText': 'x'*1024})
            atomic_write_json(source/'reports/runs/current.json', {'bareReport': candidates[3].name})
            resolved_missing = []
            real_resolve = Path.resolve
            def resolve(path, *args, **kwargs):
                if path.parent == job.parent and path.name in missing:
                    resolved_missing.append(path)
                return real_resolve(path, *args, **kwargs)
            with patch.object(Path, 'resolve', resolve):
                result = retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual(result['removedCount'], 1)
            self.assertEqual(result['protectedCount'], 5)
            self.assertEqual({path: path.read_bytes() for path in candidates[:5]},
                {path: before[path] for path in candidates[:5]})
            self.assertFalse(candidates[5].exists())
            self.assertEqual(resolved_missing, [],
                'Missing nonpath words must not trigger full parent-component resolution')

    def test_bare_leaf_inspection_error_preserves_every_retention_candidate(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            video = 'a1B2c3D4e5F'
            source = workspace/'Engine telemetry'/f'youtube-{video}'
            candidates = [source/'reports/runs'/f'{index:032x}.json' for index in (1, 2)]
            for index, path in enumerate(candidates):
                atomic_write_json(path, {'status': 'complete', 'uniqueContent': index})
            before = {path: path.read_bytes() for path in candidates}
            job = workspace/f'PC side/stemwise-playlist-builder/future-songs/policy-state/current/jobs/{video}/job.json'
            atomic_write_json(job, {'videoId': video, 'reference': 'inspection-denied'})
            denied = job.parent/'inspection-denied'
            real_lstat = Path.lstat
            def lstat(path, *args, **kwargs):
                if path == denied:
                    raise PermissionError('Fixture leaf inspection denied')
                return real_lstat(path, *args, **kwargs)
            with patch.object(Path, 'lstat', lstat):
                with self.assertRaisesRegex(PermissionError, 'Fixture leaf inspection denied'):
                    retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual({path: path.read_bytes() for path in candidates}, before)

    def test_exact_report_path_does_not_protect_other_run_basenames(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            paths = [source/'reports/runs'/f'{index:032x}'/'run.json' for index in (1, 2, 3)]
            for path in paths:
                atomic_write_json(path, {'status': 'complete'})
            atomic_write_json(source/'current.json', {'report': paths[2].relative_to(workspace).as_posix()})
            result = retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual(result['removedCount'], 2)
            self.assertEqual([path.is_file() for path in paths], [False, False, True])

    def test_failed_process_inspection_preserves_launcher_bytes(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        from unittest.mock import patch
        import subprocess
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            directory = source/'operations'/('a'*32)
            atomic_write_json(directory/'run.launch.json', {'formatVersion': 3,
                'operationId': directory.name, 'launchState': 'launched', 'processId': 12,
                'processStartTimeUtc': '2026-10-25T00:00:00.1234567Z'})
            (directory/'stdout.log').write_bytes(b'unresolved output')
            before = {path: path.read_bytes() for path in directory.iterdir()}
            with patch.object(retention.shutil, 'which', return_value='powershell.exe'), \
                    patch.object(retention.subprocess, 'run', side_effect=subprocess.TimeoutExpired('probe', 15)):
                with self.assertRaisesRegex(ValueError, 'deadline'):
                    retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual({path: path.read_bytes() for path in directory.iterdir()}, before)

    def test_exact_dead_launch_enters_quota_without_success_and_references_survive(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json, read_json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            for index in (1, 2, 3):
                operation = f'{index:032x}'
                directory = source/'operations'/operation
                atomic_write_json(directory/'run.launch.json', {'formatVersion': 3,
                    'operationId': operation, 'launchState': 'launched', 'processId': index,
                    'processStartTimeUtc': '2026-10-25T00:00:00Z'})
                (directory/'stdout.log').write_bytes(b'private recovery tail')
                if index == 3:
                    atomic_write_json(directory/'run.interrupted.json', {'formatVersion': 1,
                        'operationId': operation, 'status': 'interrupted',
                        'reason': 'exact-supervisor-not-active', 'processId': index,
                        'processStartTimeUtc': '2026-10-25T00:00:00Z', 'observedAt': '2026-10-25T00:00:01Z'})
            job = workspace/'PC side/stemwise-playlist-builder/future-songs/policy-state/current/jobs/a1B2c3D4e5F/job.json'
            atomic_write_json(job, {'operationId': f'{2:032x}'})
            with patch.object(retention, '_launcher_alive', side_effect=lambda value: value['processId'] == 3):
                preview = retention.cleanup_source(workspace, source, apply=False, max_count=10)
                self.assertEqual(preview['unreferencedCount'], 1)
                self.assertEqual(len(list(source.rglob('*.interrupted.json'))), 1,
                    'dry run cannot publish reconciliation records')
                result = retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual(result['removedCount'], 1)
            self.assertFalse((source/'operations'/f'{1:032x}').exists())
            retained = source/'operations'/f'{2:032x}'
            self.assertEqual(read_json(retained/'run.interrupted.json')['status'], 'interrupted')
            self.assertNotIn('exitCode', read_json(retained/'run.interrupted.json'))
            self.assertTrue((retained/'stdout.log').is_file())
            self.assertTrue((source/'operations'/f'{3:032x}'/'stdout.log').is_file())
            self.assertFalse(list(source.rglob('*.completion.json')))

    def test_retention_requires_a_real_matching_outcome_before_retiring_operation_start(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            reports = source/'reports'
            store = ImmutableReportStore(workspace, 65536)
            bindings = ReportBindings('a'*64, 'b'*64, 'c'*64)
            unknown = OperationStart('1'*32, 'a1B2c3D4e5F', '2'*32, 'Validate', 1, 'NEW', bindings)
            complete = OperationStart('3'*32, 'a1B2c3D4e5F', '4'*32, 'Validate', 2, 'NEW', bindings)
            for record in (unknown.to_record(), complete.to_record(), complete.finish('5'*32, 3, 'NEW', 'success').to_record()):
                store.commit(reports, record, current_pointer='current-operation.json')
            malformed = reports/'runs'/('6'*32)/'run.json'
            atomic_write_json(malformed, {'operation': 'workflow-operation', 'phase': 'start', 'status': 'started'})
            retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertTrue((reports/'runs'/unknown.run_id/'run.json').is_file())
            self.assertTrue(malformed.is_file())
            store.commit(reports, unknown.finish('7'*32, 4, 'NEW', 'success').to_record(),
                         current_pointer='current-operation.json')
            retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertFalse((reports/'runs'/complete.run_id).exists())
            self.assertFalse((reports/'runs'/('5'*32)).exists())
            self.assertTrue((reports/'runs'/unknown.run_id/'run.json').is_file())
            self.assertTrue(malformed.is_file())

    def test_running_records_and_publication_gap_keep_exact_source_ownership(self):
        import stemwise_engine.telemetry_retention as retention
        import threading
        from stemwise_engine.common import atomic_write_json, ProcessLockBusyError
        from unittest.mock import patch
        import stemwise_engine.workflow_reports as workflow_reports
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            reports = source/'reports'
            store = ImmutableReportStore(workspace, 65536)
            def record(index):
                return {'runId': f'{index:032x}', 'videoId': 'a1B2c3D4e5F',
                    'operation': 'package-finalization', 'status': 'complete'}
            store.commit(reports, record(1))
            store.commit(reports, record(2))
            running = source/'acoustic/producer/runs'/('3'*32+'.json')
            atomic_write_json(running, {'status': 'running', 'sourceRunId': f'{1:032x}'})
            uncertain = []
            for index, state in enumerate((None, 'unknown', 'queued', 'accepted'), 10):
                path = running.parent/f'{index:032x}.json'
                atomic_write_json(path, {} if state is None else {'status': state})
                uncertain.append(path)
            pending = source/'reports/runs'/('4'*32)/'run.json'
            atomic_write_json(pending, {'runId': '4'*32, 'status': 'running'})
            retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertTrue((reports/'runs'/f'{1:032x}'/'run.json').is_file())
            self.assertTrue(running.is_file())
            self.assertTrue(pending.is_file())
            self.assertTrue(all(path.is_file() for path in uncertain))
            errors = []
            actual_write = workflow_reports.atomic_write_json
            def publish_pointer(path, value, **kwargs):
                def cleanup_elsewhere():
                    try:
                        retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
                    except BaseException as error:
                        errors.append(error)
                thread = threading.Thread(target=cleanup_elsewhere)
                thread.start()
                thread.join(2)
                self.assertFalse(thread.is_alive(), 'cleanup must fail boundedly while publication owns its lease')
                self.assertTrue((reports/'runs'/f'{5:032x}'/'run.json').is_file())
                return actual_write(path, value, **kwargs)
            with patch.object(workflow_reports, 'atomic_write_json', side_effect=publish_pointer):
                store.commit(reports, record(5))
            self.assertEqual(len(errors), 1)
            self.assertIsInstance(errors[0], ProcessLockBusyError)

    def test_retention_bounds_retries_and_keeps_live_review_validation_and_unknown_launch_chains(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json, read_json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            video = 'a1B2c3D4e5F'
            source = workspace/'Engine telemetry'/f'youtube-{video}'
            reports = source/'reports'
            job = workspace/f'PC side/stemwise-playlist-builder/future-songs/policy-state/current/jobs/{video}/job.json'
            store = ImmutableReportStore(workspace, 65536)
            def publish(index, **fields):
                run = f'{index:032x}'
                store.commit(reports, {'runId': run, 'videoId': video,
                    'operation': 'package-finalization', 'status': 'complete', **fields})
                return run
            first = publish(1)
            review = publish(2, sourceAlignmentRunId=first)
            validation = publish(3)
            artwork = publish(4, sourceRunId=review)
            unknown = publish(5)
            accepted = publish(6)
            atomic_write_json(job, {'videoId': video, 'state': 'LINE_UNRESOLVED',
                'endpointReview': {'attemptId': 'a'*32, 'sourceAlignmentRunId': review},
                'validation': {'runId': validation}, 'artworkRepair': {'runId': artwork}})
            for operation, run, phase in (('b'*32, unknown, 'pending'), ('c'*32, accepted, 'launched')):
                directory = source/'operations'/operation
                atomic_write_json(directory/'launch.launch.json', {'operationId': operation,
                    'launchState': phase, 'sourceRunId': run})
                (directory/'stdout.log').write_bytes(b'live or uncertain accepted work')
            for index in range(10, 22):
                latest = publish(index)
            self.assertEqual(len(list((reports/'runs').iterdir())), 18, 'ordinary appends must not scan or prune history')
            retention.cleanup_source(workspace, source, max_count=2)
            remaining = {path.name for path in (reports/'runs').iterdir()}
            self.assertTrue({first, review, validation, artwork, unknown, accepted, latest} <= remaining)
            self.assertLessEqual(len(remaining - {first, review, validation, artwork, unknown, accepted, latest}), 2)
            self.assertEqual(read_json(report_control_path(reports, 'current-run.json'))['runId'], latest)
            self.assertTrue((source/'operations'/('b'*32)/'stdout.log').is_file())
            self.assertTrue((source/'operations'/('c'*32)/'stdout.log').is_file())
            # Byte admission independently bounds even a single oversized, unreferenced record.
            result = retention.cleanup_source(workspace, source, max_count=100, max_bytes=0)
            self.assertEqual((result['unreferencedCount'], result['unreferencedBytes']), (0, 0))
            self.assertEqual({path.name for path in (reports/'runs').iterdir()},
                             {first, review, validation, artwork, unknown, accepted, latest})

    def test_retention_keeps_latest_completed_launcher_and_fails_closed_on_bad_authority(self):
        import stemwise_engine.telemetry_retention as retention
        from stemwise_engine.common import atomic_write_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            source = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            for index in range(3):
                operation = f'{index:032x}'
                directory = source/'operations'/operation
                atomic_write_json(directory/'run.launch.json', {'operationId': operation, 'launchState': 'launched'})
                atomic_write_json(directory/'run.completion.json', {'operationId': operation,
                    'completedAt': '2026-10-25T00:00:00Z', 'exitCode': 1})
                (directory/'stderr.log').write_bytes(b'known failed launcher')
            result = retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual(result['removedCount'], 2)
            self.assertTrue((source/'operations'/f'{2:032x}'/'stderr.log').is_file())
            bad = source/'current-operation.json'
            bad.write_bytes(b'{')
            before = {path: path.read_bytes() for path in source.rglob('*') if path.is_file()}
            with self.assertRaises(ValueError):
                retention.cleanup_source(workspace, source, max_count=0, max_bytes=0)
            self.assertEqual({path: path.read_bytes() for path in source.rglob('*') if path.is_file()}, before)


if __name__ == '__main__':
    unittest.main()
