"""Real immutable bridge bindings and CLI orchestration; expensive song work is substituted."""
import contextlib
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.bridge_song_dispatch import _command, advance, read_context
from stemwise_engine import bridge_delivery, recording_workflow, workflow_command
from stemwise_engine.workflow_records import FutureSongError


class BridgeSongDispatchTest(unittest.TestCase):
    def test_parser_failure_is_a_typed_dispatch_failure(self):
        result = _command(Path('unused-config.json'), 'youtube:abcdefghijk',
                          'abcdefghijk', 'unsupported-action', 8192)
        self.assertEqual(result['state'], 'DISPATCH_FAILED')
        self.assertEqual(result['failure']['error'], 'bridge-song-command-invalid')

    def setUp(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        self.addCleanup(lambda: self.assertIs(recording_workflow.FutureSongWorkflow, FutureSongWorkflow))
        self.addCleanup(lambda: self.assertIs(workflow_command.FutureSongWorkflow, FutureSongWorkflow))
        temporary = tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.guide = self.root / 'SEND_TO_AGENTS.md'
        self.guide.write_bytes(b'current frozen engine instructions\n')
        self.context = {'formatVersion': 1, 'kind': 'stemwise.add-song', 'requestId': 'a' * 32,
                        'videoId': 'abcdefghijk', 'sourceUrl': 'https://www.youtube.com/watch?v=abcdefghijk',
                        'guideSha256': hashlib.sha256(self.guide.read_bytes()).hexdigest()}
        self.path = self.root / 'stemwise-song.json'
        self.path.write_text(json.dumps(self.context), encoding='utf-8')

    def test_declared_context_and_exact_guide_reject_changed_or_malformed_input(self):
        self.assertEqual(read_context(self.path, self.guide), self.context)
        for change in ({'videoId': 'other______'}, {'extra': 'command'}, {'formatVersion': True},
                       {'requestId': '../outside'}, {'sourceUrl': 'http://youtube.com/watch?v=abcdefghijk'}):
            with self.subTest(change=change):
                self.path.write_text(json.dumps(self.context | change), encoding='utf-8')
                with self.assertRaises((ValueError, FutureSongError)):
                    read_context(self.path, self.guide)
        self.path.write_text(json.dumps(self.context), encoding='utf-8')
        self.guide.write_bytes(b'changed instructions')
        with self.assertRaisesRegex(FutureSongError, 'mismatch'):
            read_context(self.path, self.guide)

    def workflow(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
        workflow.workspace_root = workflow.root = workflow.pc_side_root = self.root
        workflow.jobs = self.root / 'jobs'
        workflow.source_context = None
        workflow.config = {}
        workflow._require_platform_content_allowed = lambda *_: None
        return workflow

    def factory(self, workflow):
        from stemwise_engine.future_song import FutureSongWorkflow
        class Factory:
            def __new__(cls, *_args, **_kwargs):
                return workflow
            _workflow_operation_failure_code = staticmethod(FutureSongWorkflow._workflow_operation_failure_code)
        return Factory

    def test_disconnect_restart_observes_the_same_accepted_job_without_retrying_acquisition(self):
        workflow = self.workflow()
        calls = []
        def acquire(url, *, max_file_bytes):
            calls.append((url, max_file_bytes))
            path = workflow._job_path('abcdefghijk')
            path.parent.mkdir(parents=True)
            path.write_text('{"state":"ANALYZING"}', encoding='utf-8')
            raise OSError('connection lost after durable acquisition admission')
        workflow.new = acquire
        workflow.status = lambda *_a, **_k: json.loads(workflow._job_path('abcdefghijk').read_text(encoding='utf-8'))
        factory = self.factory(workflow)
        with patch('stemwise_engine.future_song.FutureSongWorkflow', factory), \
                patch('stemwise_engine.workflow_command.FutureSongWorkflow', factory), \
                patch('stemwise_engine.delivery_selection.catalogue', return_value={}), \
                patch('stemwise_engine.workflow_command.model_operation_slot', side_effect=lambda *_: contextlib.nullcontext()):
            first = advance(self.root / 'config.json', read_context(self.path, self.guide), 8192)
            self.assertEqual(first['status'], 'failed')
            second = advance(self.root / 'config.json', read_context(self.path, self.guide), 8192)
            self.assertEqual(second['engine']['state'], 'ANALYZING')
            self.assertEqual(second['status'], 'working')
            from stemwise_engine.common import exclusive_process_lock
            lock = self.root / 'Engine telemetry/youtube-abcdefghijk/bridge' / (self.context['requestId'] + '.lock')
            with exclusive_process_lock(lock):
                current = advance(self.root / 'config.json', self.context, 8192, inspect_only=True)
                self.assertEqual(current['engine']['state'], 'ANALYZING')
            self.assertEqual(calls, [('https://www.youtube.com/watch?v=abcdefghijk', 8192)])
            before = workflow._job_path('abcdefghijk').read_bytes()
            for context, budget in ((self.context | {'guideSha256': 'b' * 64}, 8192), (self.context, 16384)):
                with self.assertRaisesRegex(FutureSongError, 'request-conflict'):
                    advance(self.root / 'config.json', context, budget)
            with patch('stemwise_engine.delivery_selection.catalogue', return_value={'abcdefghijk': 'qobuz:7'}), \
                    patch('stemwise_engine.recording_workflow.RecordingWorkflow', return_value=workflow):
                with self.assertRaisesRegex(FutureSongError, 'request-conflict'):
                    advance(self.root / 'config.json', self.context, 8192)
            self.assertEqual(before, workflow._job_path('abcdefghijk').read_bytes())

    def test_completed_dispatch_uses_actual_bundle_verification_and_rejects_changed_bytes(self):
        from test_bridge_delivery import BridgeDeliveryTest
        fixture = BridgeDeliveryTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        workflow = fixture.workflow
        workflow.status = lambda _video, *, verify_delivery=False: fixture.job
        workflow.source_context = None
        workflow._job_path('abcdefghijk').parent.mkdir(parents=True, exist_ok=True)
        workflow._job_path('abcdefghijk').write_text('{}', encoding='utf-8')
        factory = self.factory(workflow)
        with patch('stemwise_engine.future_song.FutureSongWorkflow', factory), \
                patch('stemwise_engine.workflow_command.FutureSongWorkflow', factory):
            result = advance(workflow.config_path, self.context, fixture.path.stat().st_size)
            self.assertEqual((result['status'], result['code']), ('completed', 'stemwise_song_ready'))
            self.assertEqual(result['package']['sha256'], hashlib.sha256(fixture.path.read_bytes()).hexdigest())
            opened_media = []
            original_open = Path.open
            def inspect_open(path, *args, **kwargs):
                if path == fixture.path:
                    opened_media.append(path)
                return original_open(path, *args, **kwargs)
            with patch.object(Path, 'open', inspect_open):
                for _ in range(3):
                    inspected = advance(workflow.config_path, self.context, fixture.path.stat().st_size,
                                        inspect_only=True)
                    self.assertEqual(inspected['code'], 'stemwise_delivery_verification_required')
                    self.assertNotIn('package', inspected)
            self.assertEqual(opened_media, [])
            fixture.path.write_bytes(b'changed package')
            with self.assertRaises((ValueError, OSError, FutureSongError)):
                advance(workflow.config_path, self.context, result['package']['size'])

    def test_completed_dispatch_rejects_selection_changed_after_command_without_rebinding(self):
        workflow = self.workflow()
        video = self.context['videoId']
        job = workflow._job_path(video)
        job.parent.mkdir(parents=True)
        job.write_text('{}', encoding='utf-8')
        workflow.status = lambda *_a, **_k: {'state': 'DELIVERED'}
        workflow.finalize = lambda *_: {'state': 'DELIVERED', 'phoneDeliveryAllowed': True}
        workflow.video_id = video
        selected = {video: 'youtube:' + video}
        old_key, new_key = selected[video], 'qobuz:7'
        command_done, replacement_done = threading.Event(), threading.Event()
        publication = self.root/'selected-package.zip'
        publication.write_bytes(b'valid package A')
        errors = []
        def replace_selection():
            try:
                if not command_done.wait(5):
                    raise TimeoutError('command did not reach description')
                selected[video] = new_key
                publication.write_bytes(b'valid package B')
            except BaseException as error:
                errors.append(error)
            finally:
                replacement_done.set()
        def describe(*_args):
            command_done.set()
            if not replacement_done.wait(5):
                raise TimeoutError('replacement did not finish')
            return {'recordingKey': selected[video], 'package': {'sha256': hashlib.sha256(publication.read_bytes()).hexdigest()}}
        worker = threading.Thread(target=replace_selection)
        worker.start()
        factory = self.factory(workflow)
        try:
            with patch('stemwise_engine.future_song.FutureSongWorkflow', factory), \
                    patch('stemwise_engine.workflow_command.FutureSongWorkflow', factory), \
                    patch('stemwise_engine.recording_workflow.RecordingWorkflow', return_value=workflow), \
                    patch('stemwise_engine.delivery_selection.catalogue', return_value=selected), \
                    patch('stemwise_engine.workflow_command.model_operation_slot', side_effect=lambda *_: contextlib.nullcontext()), \
                    patch.object(bridge_delivery, 'describe', side_effect=describe):
                with self.assertRaisesRegex(FutureSongError, 'bridge-song-request-conflict'):
                    advance(self.root/'config.json', self.context, 8192)
                binding = self.root/'Engine telemetry'/('youtube-'+video)/'bridge'/(self.context['requestId']+'.json')
                self.assertEqual(json.loads(binding.read_bytes())['recordingKey'], old_key)
                self.assertEqual(publication.read_bytes(), b'valid package B')
                with self.assertRaisesRegex(FutureSongError, 'bridge-song-request-conflict'):
                    advance(self.root/'config.json', self.context, 8192)
                fresh = advance(self.root/'config.json', self.context | {'requestId': 'b'*32}, 8192)
                self.assertEqual((fresh['status'], fresh['recordingKey']), ('completed', new_key))
        finally:
            command_done.set()
            worker.join(5)
        self.assertFalse(worker.is_alive())
        self.assertEqual(errors, [])

    def test_leading_hyphen_identity_survives_new_inspect_status_and_finalize(self):
        video_id = '-bcdefghijk'
        context = self.context | {'videoId': video_id,
            'sourceUrl': 'https://www.youtube.com/watch?v=' + video_id}
        workflow = self.workflow()
        job_path = workflow._job_path(video_id)
        calls = []
        def new(url, *, max_file_bytes):
            calls.append(('new', url, max_file_bytes))
            job_path.parent.mkdir(parents=True, exist_ok=True)
            job_path.write_text('{"state":"ANALYZING"}', encoding='utf-8')
            return {'state': 'ANALYZING'}
        def status(selected, **_kwargs):
            calls.append(('status', selected))
            return json.loads(job_path.read_text(encoding='utf-8'))
        def finalize(selected):
            calls.append(('finalize', selected))
            return {'state': 'WAITING_FOR_EDITORIAL'}
        workflow.new, workflow.status, workflow.finalize = new, status, finalize
        factory = self.factory(workflow)
        with patch('stemwise_engine.future_song.FutureSongWorkflow', factory), \
                patch('stemwise_engine.workflow_command.FutureSongWorkflow', factory), \
                patch('stemwise_engine.delivery_selection.catalogue', return_value={}), \
                patch('stemwise_engine.workflow_command.model_operation_slot', side_effect=lambda *_: contextlib.nullcontext()):
            self.assertEqual(advance(self.root / 'config.json', context, 8192)['status'], 'working')
            self.assertEqual(advance(self.root / 'config.json', context, 8192,
                                     inspect_only=True)['engine']['state'], 'ANALYZING')
            job_path.write_text('{"state":"VALIDATED"}', encoding='utf-8')
            self.assertEqual(advance(self.root / 'config.json', context, 8192)['status'], 'waiting')
        self.assertEqual(calls, [('new', context['sourceUrl'], 8192),
            ('status', video_id), ('status', video_id), ('finalize', video_id)])

    def test_native_alias_dispatch_uses_canonical_cli_owner_and_requested_delivery(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
        workflow.__dict__.update(self.workflow().__dict__)
        workflow.key, workflow.video_id = 'qobuz:7', 'lmnopqrstuv'
        path = workflow._job_path(workflow.video_id)
        calls = []
        def new(url, *, max_file_bytes):
            self.assertEqual(url, 'https://www.youtube.com/watch?v=' + workflow.video_id)
            calls.append(('new', workflow.video_id))
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{"state":"ANALYZING"}', encoding='utf-8')
            return {'state': 'ANALYZING'}
        def status(owner, **kwargs):
            self.assertEqual(owner, workflow.video_id)
            calls.append(('status', owner))
            return json.loads(path.read_bytes())
        def finalize(owner):
            self.assertEqual(owner, workflow.video_id)
            calls.append(('finalize', owner))
            path.write_text('{"state":"DELIVERED","phoneDeliveryAllowed":true}', encoding='utf-8')
            return {'state': 'DELIVERED', 'phoneDeliveryAllowed': True}
        workflow.new, workflow.status, workflow.finalize = new, status, finalize
        config = self.root / 'config.json'
        with patch('stemwise_engine.future_song.FutureSongWorkflow', self.factory(workflow)), \
                patch('stemwise_engine.recording_workflow.RecordingWorkflow', return_value=workflow), \
                patch('stemwise_engine.delivery_selection.catalogue', return_value={self.context['videoId']: workflow.key}), \
                patch('stemwise_engine.workflow_command.model_operation_slot', side_effect=lambda *_: contextlib.nullcontext()), \
                patch('stemwise_engine.bridge_delivery.describe', return_value={'recordingKey': workflow.key,
                    'package': {'name': self.context['videoId'] + '.stemwise.zip'}}) as describe:
            self.assertEqual(advance(config, self.context, 8192)['status'], 'working')
            self.assertEqual(advance(config, self.context, 8192, inspect_only=True)['status'], 'working')
            self.assertEqual(advance(config, self.context, 8192)['status'], 'working')
            path.write_text('{"state":"VALIDATED"}', encoding='utf-8')
            for inspect in (False, False, True):
                result = advance(config, self.context, 8192, inspect_only=inspect)
                self.assertEqual(result['status'], 'waiting' if inspect else 'completed')
                self.assertEqual(result['videoId'], self.context['videoId'])
                if inspect:
                    self.assertEqual(result['code'], 'stemwise_delivery_verification_required')
                    self.assertNotIn('package', result)
                else:
                    self.assertEqual(result['package']['name'], self.context['videoId'] + '.stemwise.zip')
            self.assertEqual(describe.call_count, 2)
            describe.assert_called_with(config, self.context['videoId'], 8192)
        self.assertEqual(calls[0], ('new', workflow.video_id))
        self.assertEqual(sum(action == 'finalize' for action, _ in calls), 2)
        self.assertTrue(all(owner == workflow.video_id for _, owner in calls))
