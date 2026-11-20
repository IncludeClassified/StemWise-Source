import sys
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


@contextmanager
def damaged_diagnostic_target(kind):
    from unittest.mock import patch
    from stemwise_engine import private_diagnostics as diagnostics
    retain = diagnostics.retain
    targets = []
    def damage(path, body, **options):
        target, _ = retain(path, body, **options)
        if kind == 'directory':
            target.unlink()
            target.mkdir()
        else:
            target.write_bytes(b'damaged diagnostic')
        targets.append(target)
        return retain(path, body, **options)
    with patch.object(diagnostics, 'retain', side_effect=damage):
        yield targets
    for target in targets:
        if kind == 'directory':
            assert target.is_dir(), 'optional capture replaced an unusable target'
        else:
            assert target.read_bytes() == b'damaged diagnostic', 'optional capture replaced a mismatched target'


class PrivateDiagnosticsTests(unittest.TestCase):
    def test_recording_capture_preserves_primary_when_retention_or_pointer_save_fails(self):
        from unittest.mock import patch
        from workflow_fixture import initialized_workflow
        from stemwise_engine.future_song import FutureSongError
        from stemwise_engine.common import WorkerRetirementError, sha256_file
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from stemwise_engine import private_diagnostics
        for secondary in (None, 'retention', 'pointer'):
            for primary in (FutureSongError('recognizer refused exact input'),
                            WorkerRetirementError('unretired recognizer'),
                            ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
                with self.subTest(secondary=secondary, primary=type(primary).__name__), \
                        tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    workflow = initialized_workflow(Path(name))
                    video = 'abcdefghijk'
                    job = workflow._base_job(video, f'https://youtu.be/{video}')
                    workflow._save_job(job)
                    job['state'] = 'WAITING_FOR_EDITORIAL'
                    workflow._save_job(job)
                    draft = workflow._job_dir(video)/'drafts/original.txt'
                    draft.parent.mkdir(parents=True)
                    draft.write_text('retained exact wording', encoding='utf-8')
                    save = workflow._save_job
                    def save_pointer(current):
                        if secondary == 'pointer' and 'recordingEvidenceDiagnostics' in current:
                            raise OSError('optional pointer unavailable')
                        return save(current)
                    retain = private_diagnostics.retain
                    def capture(*args, **kwargs):
                        if secondary == 'retention':
                            raise OSError('optional diagnostic unavailable')
                        return retain(*args, **kwargs)
                    with patch.object(workflow, '_require_selected_audio'), \
                            patch.object(workflow, '_require_changed_lyrics_content'), \
                            patch.object(workflow, '_preflight_drafts'), \
                            patch.object(workflow, '_review_inputs', return_value={'retained': 'input'}), \
                            patch.object(workflow, '_alignment_worker_environment', return_value={}), \
                            patch.object(workflow, '_pipeline'), \
                            patch.object(workflow, '_completed_analysis_audio', return_value={
                                'path': draft, 'sha256': 'a'*64, 'durationMs': 1000}), \
                            patch.object(workflow, '_run_primary_worker', side_effect=primary) as worker, \
                            patch.object(workflow, '_save_job', side_effect=save_pointer), \
                            patch.object(private_diagnostics, 'retain', side_effect=capture) as diagnostic:
                        with self.assertRaises(type(primary)) as raised:
                            workflow._run_recording_evidence(video)
                    self.assertIs(raised.exception, primary)
                    worker.assert_called_once()
                    current = workflow._load_job(video)
                    self.assertEqual(current['state'], 'WAITING_FOR_EDITORIAL')
                    self.assertEqual(current['recordingEvidence']['status'], 'failed')
                    self.assertEqual(draft.read_text(encoding='utf-8'), 'retained exact wording')
                    if isinstance(primary, FutureSongError):
                        diagnostic.assert_called_once()
                        if secondary is None:
                            reference, = current['recordingEvidenceDiagnostics']
                            path = workflow._job_dir(video)/reference['file']
                            self.assertEqual(sha256_file(path), reference['sha256'])
                            self.assertIn(b'recognizer refused exact input', path.read_bytes())
                        else:
                            self.assertNotIn('recordingEvidenceDiagnostics', current)
                    else:
                        diagnostic.assert_not_called()

    def test_endpoint_capture_preserves_primary_and_a_valid_utf8_tail(self):
        from unittest.mock import patch
        from workflow_fixture import initialized_workflow
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = initialized_workflow(Path(name))
            video = 'abcdefghijk'
            job = workflow._base_job(video, f'https://youtu.be/{video}')
            job['endpointReview'] = {'result': 'pending'}
            workflow._save_job(job)
            workflow._alignment_state_path(video).parent.mkdir(parents=True, exist_ok=True)
            for primary in (WorkerRetirementError('native owner'), ProviderCancelled('cancelled'),
                            ProviderDeadline('parent deadline'), RuntimeError('ordinary')):
                with self.subTest(primary=type(primary).__name__), \
                        patch.object(workflow, '_require_selected_audio'), \
                        patch.object(workflow, '_validate_endpoint_review', side_effect=primary), \
                        patch('stemwise_engine.future_song.atomic_write_bytes', side_effect=OSError('diagnostic unavailable')):
                    with self.assertRaises(type(primary)) as raised:
                        workflow.endpoint_evidence(video)
                    self.assertIs(raised.exception, primary)
            primary = RuntimeError('é'*7000)
            with patch.object(workflow, '_require_selected_audio'), \
                    patch.object(workflow, '_validate_endpoint_review', side_effect=primary):
                with self.assertRaises(RuntimeError) as raised:
                    workflow.endpoint_evidence(video)
            self.assertIs(raised.exception, primary)
            raw = workflow._alignment_state_path(video).with_name('endpoint.failure.private.txt').read_bytes()
            self.assertLessEqual(len(raw), 12000)
            self.assertTrue(raw.decode('utf-8').endswith('é\n'))

    def test_worker_parent_stop_requires_typed_packet_and_stage_deadline_stays_local(self):
        import io
        from contextlib import redirect_stderr
        from types import SimpleNamespace
        from unittest.mock import patch
        from stemwise_engine import worker_protocol as protocol, private_diagnostics
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline, ProviderStageDeadline
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline'),
                        ProviderStageDeadline('route deadline'), protocol.WorkerReasonError('provider-operation-deadline')):
            output = io.StringIO()
            with redirect_stderr(output):
                protocol.emit_worker_failure(failure)
            expected = (type(failure) if isinstance(failure, (ProviderCancelled, ProviderDeadline))
                        and not isinstance(failure, ProviderStageDeadline) else protocol.WorkerProtocolError)
            for entry in ('raise_worker_failure', 'require_worker_success'):
                with self.subTest(failure=type(failure).__name__, entry=entry), \
                        patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic unavailable')):
                    completed = SimpleNamespace(returncode=1, stderr=output.getvalue())
                    with self.assertRaises(expected) as raised:
                        if entry == 'raise_worker_failure':
                            protocol.raise_worker_failure('worker', completed, Path('unused.json'))
                        else:
                            protocol.require_worker_success(completed, code='worker-failed', diagnostic_path=Path('unused.txt'))
                    if expected is not protocol.WorkerProtocolError:
                        self.assertEqual(raised.exception.returncode, 1)
        for stderr in ('ProviderCancelled: incidental wording',
                'STEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"provider-operation-deadline","disposition":"stage-deadline"}',
                'STEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"provider-operation-deadline","disposition":[]}'):
            with self.subTest(stderr=stderr), \
                    patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic unavailable')):
                with self.assertRaises(protocol.WorkerProtocolError):
                    protocol.require_worker_success(SimpleNamespace(returncode=1, stderr=stderr),
                        code='worker-failed', diagnostic_path=Path('unused.txt'))
        output = io.StringIO()
        with redirect_stderr(output):
            protocol.emit_worker_failure(ProviderCancelled('cancelled'))
        with patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic unavailable')):
            with self.assertRaises(ProviderCancelled):
                protocol.require_worker_success(SimpleNamespace(returncode=0, stderr=output.getvalue()),
                    code='worker-failed', diagnostic_path=Path('unused.txt'))

    def test_serialized_retirement_is_fatal_with_or_without_worker_output(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine import worker_protocol as protocol, private_diagnostics
        packet = b'original retirement cause\nSTEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"alignment-model-retirement-failed"}\n'
        for entry in ('raise_worker_failure', 'require_worker_success'):
            for present, stderr in ((False, packet), (True, packet), (False, b''), (True, b'')):
                with self.subTest(entry=entry, output=present, packet=bool(stderr)), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    output, diagnostic = Path(name)/'output.json', Path(name)/'failure.private.txt'
                    if present:
                        output.write_bytes(b'{"prior":"valid result"}')
                    completed = SimpleNamespace(returncode=70, stderr=stderr)
                    with patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic disk unavailable')):
                        with self.assertRaises(WorkerRetirementError) as raised:
                            if entry == 'raise_worker_failure':
                                protocol.raise_worker_failure('native', completed, output, diagnostic_path=diagnostic)
                            else:
                                protocol.require_worker_success(completed, code='native-failed', diagnostic_path=diagnostic)
                    error = raised.exception
                    self.assertEqual(error.returncode, 70)
                    self.assertEqual(error.code, 'alignment-model-retirement-failed' if stderr else 'process-exit')
                    self.assertEqual(protocol.technical_diagnostic_code(error), error.code)
                    self.assertEqual(error.stderr, stderr)
                    self.assertEqual(output.exists(), present)
                    if present:
                        self.assertEqual(output.read_bytes(), b'{"prior":"valid result"}')

    def test_acoustic_cause_shape_and_current_owner_remain_required(self):
        from stemwise_engine.private_diagnostics import analysis_diagnostics, harvest_analysis_causes
        cause = {'errorType': 'RuntimeError', 'body': 'Optional acoustic cause'}
        with self.assertRaisesRegex(ValueError, 'current job owner'):
            harvest_analysis_causes({'_privateDiagnostics': [cause]})
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for causes in (cause, [cause]*33, [{'errorType': 'RuntimeError'}],
                           [{**cause, 'errorType': 'invalid diagnostic code'}],
                           [{**cause, 'body': 'é'*6001}]):
                with self.subTest(causes=type(causes).__name__), \
                        analysis_diagnostics(root/'causes', workspace_root=root) as detail:
                    with self.assertRaisesRegex(ValueError, 'invalid|malformed'):
                        harvest_analysis_causes({'_privateDiagnostics': causes})
                    self.assertEqual(detail, {})

    def test_required_retention_rejects_an_existing_damaged_content_address(self):
        from stemwise_engine.private_diagnostics import retain
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            path = Path(name)/'required.private.txt'
            retained, _ = retain(path, b'required retained input')
            retained.write_bytes(b'damaged required record')
            with self.assertRaisesRegex(ValueError, 'identity changed'):
                retain(path, b'required retained input')
            self.assertEqual(retained.read_bytes(), b'damaged required record')

    def test_retained_byte_tail_is_valid_bounded_utf8(self):
        from stemwise_engine.private_diagnostics import retain
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            path, identity = retain(Path(name) / 'cause.private.txt', ('é' * 6000 + '!').encode('utf-8'))
            raw = path.read_bytes()
            self.assertLessEqual(len(raw), 12000)
            self.assertTrue(raw.decode('utf-8').endswith('!'))
            self.assertEqual(identity['byteCount'], len(raw))

    def test_already_truncated_private_cause_does_not_mask_original_code(self):
        from stemwise_engine.private_diagnostics import failure_from_file
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            path = Path(name) / 'cause.private.txt'
            path.write_bytes(b'\xa9 original child cause')
            code = failure_from_file('worker-exit', path, None)
            self.assertEqual(code, 'worker-exit')
            self.assertIn('original child cause', code.diagnostics[0]['body'])

    def test_preparation_failure_writes_valid_utf8_with_original_classification(self):
        from stemwise_engine.analysis_queue import _preparation_failure
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            path = root / 'cause.private.txt'
            error = RuntimeError('é' * 6000 + '!')
            code = _preparation_failure({'preparation': {'diagnosticPath': str(path)}}, error, root)
            self.assertEqual(code, 'native-emission-preparation-failed')
            self.assertLessEqual(path.stat().st_size, 12000)
            self.assertTrue(path.read_text(encoding='utf-8').endswith('!'))

    def test_retention_failure_keeps_worker_code_and_bounded_stderr(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from stemwise_engine.worker_protocol import raise_worker_failure, WorkerProcessError, technical_diagnostic_code
        from stemwise_engine import private_diagnostics
        packet = b'x'*100000 + b'\nSTEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"cuda-out-of-memory"}\n'
        with patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic disk unavailable')):
            with self.assertRaises(WorkerProcessError) as failure:
                raise_worker_failure('native', SimpleNamespace(returncode=1, stderr=packet), Path('unused.json'))
            error = failure.exception
            self.assertEqual(error.code, 'cuda-out-of-memory')
            self.assertLessEqual(len(error.stderr), 64*1024)
            self.assertIn(b'cuda-out-of-memory', error.stderr)
            self.assertEqual(technical_diagnostic_code(error, Path('unused.txt')), 'cuda-out-of-memory')

    def test_failed_fallback_diagnostic_does_not_block_healthy_route(self):
        from unittest.mock import patch
        from stemwise_engine.lyrics_align_pc import _run_conditional_cascade
        from stemwise_engine import private_diagnostics
        calls, failures = [], []
        def evaluate(route):
            calls.append(route)
            if route == 'first':
                raise ValueError('original numerical failure')
            return True, 'healthy independent result'
        with patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic disk unavailable')):
            result = _run_conditional_cascade(('first', 'second'), evaluate,
                diagnostic_path=Path('unused.txt'), on_failure=lambda route, code: failures.append(code))
        self.assertEqual(result, ('second', 'healthy independent result'))
        self.assertEqual(calls, ['first', 'second'])
        self.assertEqual(str(failures[0]), 'ValueError')
        self.assertIn('original numerical failure', failures[0].private_causes[0]['body'])

    def test_batch_diagnostics_and_attempt_retention_do_not_replace_original_error(self):
        from unittest.mock import patch
        from stemwise_engine import private_diagnostics as diagnostics
        from stemwise_engine.worker_protocol import WorkerReasonError
        error = WorkerReasonError('cuda-out-of-memory', 'original numerical cause')
        error.stderr = b'original child stderr'
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, \
                patch.object(diagnostics, 'atomic_write_bytes', side_effect=OSError('diagnostic disk unavailable')), \
                patch.object(diagnostics, 'emit_analysis_stage_failure') as emit:
            root = Path(name)
            diagnostics.retain_allin1_failure(error, root, {'index': 2})
            diagnostics.retain_demix_failure(error, root, {'index': 2})
            self.assertEqual(emit.call_count, 2)
            self.assertIs(emit.call_args.args[0], error)
            self.assertEqual(error.reason_code, 'cuda-out-of-memory')
            self.assertIn(b'original numerical cause', error.stderr)
            self.assertIn(b'original child stderr', error.stderr)
            diagnostics.preserve_failure(root/'attempt.private.txt', error, b'exact child stderr')
            self.assertIn(b'exact child stderr', error.stderr)
            # Required retained records retain their strict I/O boundary.
            with self.assertRaises(OSError):
                diagnostics.retain(root/'required.private.txt', b'required retained input')
