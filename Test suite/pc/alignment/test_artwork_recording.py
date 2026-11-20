from __future__ import annotations

import copy
import base64
import hashlib
import io
import json
import os
import subprocess
import tempfile
import unittest
import wave
from contextlib import contextmanager, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'unit'))

from test_future_song import WORKSPACE, FutureSongError, workflow_fixture


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class ArtworkRecordingTests(unittest.TestCase):
    @contextmanager
    def _recording_worker(self, workflow):
        import stemwise_engine.recording_evidence as recording_evidence
        import stemwise_engine.lyrics_align_pc as alignment
        from stemwise_engine.common import atomic_write_json, sha256_file
        from stemwise_engine.lyric_content import _recognized
        from stemwise_engine.provenance import semantic_source_identity
        from test_qwen3_asr_transcribe import output_fixture, generation_fixture
        audio = workflow.workspace_root / 'audio.wav'
        with wave.open(str(audio), 'wb') as stream:
            stream.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            stream.writeframes(b'\x01\x00' * 16000)
        workflow._completed_analysis_audio = Mock(return_value={
            'path': str(audio), 'sha256': sha256_file(audio), 'byteCount': audio.stat().st_size})

        def recognize(**request):
            text = 'one two three four five six'
            language, identity = request['language'], request['audio_identity']
            model = {'revision': alignment.QWEN3_ASR_REVISION,
                     'snapshotSha256': alignment.QWEN3_ASR_SNAPSHOT_SHA256}
            output = output_fixture({'audio': identity, 'language': language, 'model': model})
            output['result'] = generation_fixture(text, language)
            provenance = {'audio': identity, 'language': language,
                'modelRevision': model['revision'], 'modelSnapshotSha256': model['snapshotSha256'],
                'helperSha256': semantic_source_identity(Path(alignment.__file__).with_name('qwen3_asr_transcribe.py')),
                'runtime': {'runtime': output['runtime']}, 'decoder': alignment.QWEN3_ASR_DECODER}
            cache = {'cacheVersion': alignment.QWEN3_ASR_CACHE_VERSION,
                'videoId': request['video_id'], 'provenance': provenance, 'workerOutput': output}
            retained = request['workspace'].job_dir(request['video_id']) / f'qwen-structure-{language}.private.json'
            atomic_write_json(retained, cache)
            self.assertEqual(_recognized(json.loads(retained.read_bytes()), identity['sha256']), text)
            return text, provenance, False

        def compare(command, **_kwargs):
            self.assertEqual(command[1:3], ['-m', 'stemwise_engine.recording_evidence'])
            output = io.StringIO()
            with redirect_stdout(output):
                code = recording_evidence.main(command[3:])
            return SimpleNamespace(returncode=code, stdout=output.getvalue(), stderr='')

        with patch('stemwise_engine.recording_evidence._load_or_run_qwen3_structure_transcript',
                   side_effect=recognize) as recognizer, patch(
                'stemwise_engine.future_song.run_process_tree', side_effect=compare) as process:
            yield process, recognizer

    def test_public_retry_repeats_failed_recording_evidence_without_approving_or_aligning(self):
        from test_future_song import WORKSPACE
        from stemwise_engine.common import exclusive_process_lock
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        import stemwise_engine.editorial_review as editorial_review
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow, job, directory = self._pre_alignment_failed_workflow(Path(name))
            job['policyVersion'] = editorial_review.POLICY_VERSION
            (directory/'job.json').write_text(json.dumps(job), encoding='utf-8')
            workflow.config_path.write_text(json.dumps(workflow.config), encoding='utf-8')
            workflow.policy_dir = Path(name)/'policy'
            workflow.policy_dir.mkdir()
            (workflow.policy_dir/'lyrics-policy.json').write_bytes(b'{}')
            workflow.owner, workflow.reports = Path(name)/'owner', Path(name)/'reports'
            workflow._song_policy_matches = Mock(return_value=True)
            workflow._load_job = type(workflow)._load_job.__get__(workflow)
            workflow._run_mutating_operation = type(workflow)._run_mutating_operation.__get__(workflow)
            workflow._write_task = Mock()
            workflow._validate_action = Mock(side_effect=AssertionError('recognition retry must not align'))
            with exclusive_process_lock(directory/'workflow.lock'):
                pass
            before = self._job_file_snapshot(Path(name), directory)
            with self._recording_worker(workflow) as (process, recognizer):
                recognize = recognizer.side_effect
                recognizer.side_effect = ContentEvidenceUnavailable()
                with self.assertRaises(ContentEvidenceUnavailable):
                    workflow.recording_evidence('AAAAAAAAAAA')
                failed = json.loads((directory / 'job.json').read_bytes())
                self.assertEqual(failed['state'], 'RETRYABLE_TECHNICAL')
                recognizer.side_effect = recognize
                result = workflow.retry('AAAAAAAAAAA')
            self.assertEqual(process.call_count, 2)
            self.assertEqual(result['state'], 'WAITING_FOR_EDITORIAL')
            self.assertTrue(result['reviewRequired'])
            operation = workflow.report_store.latest_workflow_operation('AAAAAAAAAAA')
            self.assertEqual((operation['action'], operation['status']), ('Retry', 'complete'))
            recovered = workflow._load_job('AAAAAAAAAAA')
            self.assertNotIn('outcome', recovered)
            self.assertEqual(recovered['progress'], {'stage': 'editorial', 'outcome': 'pending'})
            after = self._job_file_snapshot(Path(name), directory)
            ledger = json.loads(after.pop((directory/'song-attempt.private.json').relative_to(name)))
            self.assertEqual(ledger['primaryActions']['recording-evidence']['status'], 'complete')
            self.assertIsNone(ledger['activeSince'])
            self.assertNotIn('disposition', ledger)
            self.assertEqual(before, after)

    @staticmethod
    def _metadata(workflow, job_dir, language='en'):
        workflow._runtime_config = lambda: workflow.config
        (job_dir / 'drafts/lyrics.meta.json').write_text(json.dumps({
            'videoId': 'AAAAAAAAAAA', 'originalLanguage': language, 'originalScript': 'Latn',
            'sources': {'original': {'name': 'Source'}},
        }), encoding='utf-8')

    def _pre_alignment_failed_workflow(self, root):
        job = {
            "videoId": "AAAAAAAAAAA",
            "state": "VALIDATION_FAILED",
            "error": "future-song-error",
        }
        workflow = workflow_fixture(root)
        workflow.root = root
        job_dir = root / "jobs/AAAAAAAAAAA"
        (job_dir / "drafts").mkdir(parents=True)
        (job_dir / "drafts/original.txt").write_bytes(b"one two three four five six\n")
        self._metadata(workflow, job_dir)
        for name in ("agent-review.json", "checkpoint.json"):
            (job_dir / name).write_bytes(b"preserved pending work")
        (job_dir / 'job.json').write_text(json.dumps(job), encoding='utf-8')
        workflow._job_dir = lambda _: job_dir
        workflow._load_job = Mock(return_value=job)
        workflow._pipeline = Mock()
        workflow._review_inputs = Mock(return_value={'audioSha256': 'a' * 64, 'draftSha256': 'd' * 64, 'originalMetadataSha256': 'e' * 64, 'sourcesSha256': 'f' * 64, 'plainDraftLayout': {}})
        workflow._alignment_worker_environment = Mock(return_value={})
        workflow._completed_analysis_audio = Mock(
            return_value={"path": str(root / "audio.wav"), "sha256": "a" * 64}
        )
        return workflow, job, job_dir

    @staticmethod
    def _job_file_snapshot(root, job_dir):
        return {
            path.relative_to(root): path.read_bytes()
            for path in job_dir.rglob("*")
            if path.is_file() and path.name != "job.json"
        }

    def test_recording_evidence_rejects_unavailable_drafts_before_runtime_and_preserves_review(self):
        from test_future_song import WORKSPACE

        for case in ('missing', 'directory', 'empty', 'invalid-utf8'):
            with self.subTest(case=case), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                root = Path(name)
                workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
                draft = job_dir / 'drafts/original.txt'
                if case in ('missing', 'directory'):
                    draft.unlink()
                    if case == 'directory':
                        draft.mkdir()
                else:
                    draft.write_bytes(b'\n \n' if case == 'empty' else b'\xff')
                before = self._job_file_snapshot(root, job_dir)
                job_before = copy.deepcopy(job)
                with patch('stemwise_engine.future_song.run_process_tree') as process:
                    with self.assertRaisesRegex(FutureSongError, 'recording-evidence-draft-unavailable') as caught:
                        workflow.recording_evidence('AAAAAAAAAAA')
                self.assertEqual(workflow._workflow_operation_failure_code(caught.exception),
                                 'recording-evidence-draft-unavailable')
                process.assert_not_called()
                workflow._alignment_worker_environment.assert_not_called()
                workflow._pipeline.assert_not_called()
                self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'updatedAt', 'progress'}}, job_before)
                self.assertEqual(before, self._job_file_snapshot(root, job_dir))
        self.assertEqual(workflow._workflow_operation_failure_code(FutureSongError("private draft fragment")),
                         "future-song-error")

    def test_recording_evidence_allows_only_pre_alignment_validation_failures(self):
        from test_future_song import WORKSPACE

        with tempfile.TemporaryDirectory(dir=WORKSPACE / "Test suite/work") as name:
            root = Path(name)
            workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
            before = self._job_file_snapshot(root, job_dir)
            job_before = copy.deepcopy(job)
            with self._recording_worker(workflow) as (process, _recognizer):
                result = workflow.recording_evidence("AAAAAAAAAAA")
            process.assert_called_once()
            self.assertEqual(result["state"], "VALIDATION_FAILED")
            self.assertTrue(result["reviewRequired"])
            self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'recordingContentEvidence', 'updatedAt', 'progress'}}, job_before)
            evidence = job['recordingContentEvidence']
            retained = workflow._alignment_state_path(job['videoId']).parent/evidence['evidence']['path']
            self.assertEqual(evidence['evidence']['sha256'], hashlib.sha256(retained.read_bytes()).hexdigest())
            self.assertEqual(evidence['audioSha256'], hashlib.sha256((root/'audio.wav').read_bytes()).hexdigest())
            self.assertNotIn('validation', job)
            self.assertNotIn('delivery', job)
            self.assertEqual(before, self._job_file_snapshot(root, job_dir))

        for marker in (
            {"lastAlignmentAttemptId": "a" * 32},
            {"validation": {"runId": "a" * 32}},
            *({key: None} for key in (
                "activeAlignmentAttemptId", "lastAlignmentAttemptId", "displayReview",
                "endpointReview", "validation", "delivery", "outcome",
            )),
        ):
            with self.subTest(marker=next(iter(marker))), tempfile.TemporaryDirectory(
                dir=WORKSPACE / "Test suite/work"
            ) as name:
                root = Path(name)
                workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
                job.update(marker)
                before = self._job_file_snapshot(root, job_dir)
                job_before = copy.deepcopy(job)
                with patch('stemwise_engine.future_song.run_process_tree', return_value=SimpleNamespace(
                        returncode=0, stdout='{}', stderr='')) as process:
                    with self.assertRaisesRegex(FutureSongError, "pending editorial"):
                        workflow.recording_evidence("AAAAAAAAAAA")
                process.assert_not_called()
                self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'updatedAt', 'progress'}}, job_before)
                self.assertEqual(before, self._job_file_snapshot(root, job_dir))

        with tempfile.TemporaryDirectory(dir=WORKSPACE / "Test suite/work") as name:
            root = Path(name)
            workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
            state_path = workflow._alignment_state_path("AAAAAAAAAAA")
            state_path.parent.mkdir(parents=True)
            state_path.write_text("{}", encoding="utf-8")
            state_before = state_path.read_bytes()
            before = self._job_file_snapshot(root, job_dir)
            job_before = copy.deepcopy(job)
            with patch('stemwise_engine.future_song.run_process_tree') as process:
                with self.assertRaisesRegex(FutureSongError, "pending editorial"):
                    workflow.recording_evidence("AAAAAAAAAAA")
            process.assert_not_called()
            self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'updatedAt', 'progress'}}, job_before)
            self.assertEqual(state_path.read_bytes(), state_before)
            self.assertEqual(before, self._job_file_snapshot(root, job_dir))

        for state in ('RETRYABLE_TECHNICAL', 'AUTHORITY_UNRESOLVED'):
            with self.subTest(state=state), tempfile.TemporaryDirectory(
                    dir=WORKSPACE / 'Test suite/work') as name:
                root = Path(name)
                workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
                job['state'] = state
                before = self._job_file_snapshot(root, job_dir)
                with self._recording_worker(workflow) as (process, _recognizer):
                    result = workflow.recording_evidence('AAAAAAAAAAA')
                process.assert_called_once()
                self.assertEqual(result['state'], state)
                self.assertTrue(result['reviewRequired'])
                self.assertNotIn('validation', job)
                self.assertNotIn('delivery', job)
                self.assertEqual(before, self._job_file_snapshot(root, job_dir))

        for state in ('LINE_UNRESOLVED', 'VALIDATED', 'DELIVERED'):
            with self.subTest(state=state), tempfile.TemporaryDirectory(
                    dir=WORKSPACE / 'Test suite/work') as name:
                root = Path(name)
                workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
                job['state'] = state
                before = self._job_file_snapshot(root, job_dir)
                job_before = copy.deepcopy(job)
                with patch('stemwise_engine.future_song.run_process_tree', return_value=SimpleNamespace(
                        returncode=0, stdout='{}', stderr='')) as process:
                    with self.assertRaisesRegex(FutureSongError, 'pending editorial'):
                        workflow.recording_evidence('AAAAAAAAAAA')
                process.assert_not_called()
                self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'updatedAt', 'progress'}}, job_before)
                self.assertEqual(before, self._job_file_snapshot(root, job_dir))

    def test_recording_evidence_accepts_recovered_lyrics_without_approving_or_changing_state(self):
        from test_future_song import WORKSPACE

        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            root = Path(name)
            workflow, job, job_dir = self._pre_alignment_failed_workflow(root)
            job.update(state='NEEDS_LYRICS')
            job.pop('error')
            self._metadata(workflow, job_dir, 'sq')
            before = self._job_file_snapshot(root, job_dir)
            job_before = copy.deepcopy(job)
            with self._recording_worker(workflow) as (process, _recognizer):
                result = workflow.recording_evidence('AAAAAAAAAAA')
            process.assert_called_once()
            self.assertTrue(result['structureSupport']['accepted'])
            self.assertTrue(result['reviewRequired'])
            self.assertEqual(result['state'], 'NEEDS_LYRICS')
            self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'recordingContentEvidence', 'updatedAt', 'progress'}}, job_before)
            self.assertEqual(before, self._job_file_snapshot(root, job_dir))
            job_before = {key:value for key,value in copy.deepcopy(job).items() if key not in {'recordingEvidence', 'updatedAt', 'progress'}}
            (job_dir / 'drafts/original.txt').write_bytes(b'\n \n')
            before = self._job_file_snapshot(root, job_dir)
            with patch('stemwise_engine.future_song.run_process_tree') as process:
                with self.assertRaisesRegex(FutureSongError, 'recording-evidence-draft-unavailable'):
                    workflow.recording_evidence('AAAAAAAAAAA')
            process.assert_not_called()
            self.assertEqual({key:value for key,value in job.items() if key not in {'recordingEvidence', 'updatedAt', 'progress'}}, job_before)
            self.assertEqual(before, self._job_file_snapshot(root, job_dir))

    def test_recording_evidence_retains_hash_bound_private_causes_without_changing_authority(self):
        from test_future_song import WORKSPACE
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as directory:
            root = Path(directory)
            workflow = workflow_fixture(root)
            workflow.root = root
            workflow.runtime = root / 'runtime'
            workflow.reports = root / 'reports'
            workflow.owner = root / 'owner'
            workflow.policy = {}
            workflow.policy_dir = root / 'policy'
            workflow.policy_dir.mkdir()
            (workflow.policy_dir / 'lyrics-policy.json').write_text('{}', encoding='utf-8')
            workflow.config_path.write_text(json.dumps(workflow.config), encoding='utf-8')
            workflow._run_mutating_operation = type(workflow)._run_mutating_operation.__get__(workflow)
            job_dir = root / 'jobs/AAAAAAAAAAA'
            (job_dir / 'drafts').mkdir(parents=True)
            (job_dir / 'drafts/original.txt').write_text('one two three four five six\n', encoding='utf-8')
            self._metadata(workflow, job_dir)
            job = {'videoId': 'AAAAAAAAAAA', 'state': 'WAITING_FOR_EDITORIAL', 'deliveryAllowed': False}
            workflow._job_dir = lambda _: job_dir
            workflow._job_path = lambda _: job_dir / 'job.json'
            workflow._load_job = Mock(return_value=job)
            workflow._pipeline = Mock()
            workflow._review_inputs = Mock(return_value={'audioSha256': 'a' * 64, 'draftSha256': 'd' * 64, 'originalMetadataSha256': 'e' * 64, 'sourcesSha256': 'f' * 64, 'plainDraftLayout': {}})
            workflow._alignment_worker_environment = Mock(return_value={})
            workflow._completed_analysis_audio = Mock(return_value={'path': str(root / 'audio.wav'), 'sha256': 'a' * 64})
            (job_dir / 'job.json').write_text(json.dumps(job), encoding='utf-8')
            for name in ('agent-review.json', 'checkpoint.json', 'child.zip'):
                (job_dir / name).write_bytes(b'preserved pending work')
            before = {p: p.read_bytes() for p in job_dir.rglob('*') if p.is_file() and p.name != 'job.json'}
            retained = {}
            def diagnostic():
                record = json.loads((job_dir / 'job.json').read_bytes())
                reference, = record['recordingEvidenceDiagnostics']
                status = workflow.summary(record, verify_delivery=False)
                self.assertEqual(status['recordingEvidence']['status'], 'failed')
                operation = workflow.report_store.latest_workflow_operation('AAAAAAAAAAA')
                self.assertEqual((operation['action'], operation['status']), ('RecordingEvidence', 'failed'))
                self.assertGreaterEqual(status['recordingEvidence']['durationMs'], 0)
                public_reference, = status['recordingEvidence']['privateDiagnostics']
                self.assertEqual(public_reference['sha256'], reference['sha256'])
                path = root / public_reference['file']
                body = path.read_bytes()
                self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
                self.assertEqual(len(body), reference['byteCount'])
                self.assertLessEqual(len(body), 12000)
                self.assertEqual(record['state'], 'WAITING_FOR_EDITORIAL')
                for prior, prior_bytes in retained.items():
                    self.assertEqual(prior.read_bytes(), prior_bytes)
                retained[path] = body
                return body
            private_cause = 'private recognizer context\n' + 'x' * 13000 + '\nactual child failure'
            with self._recording_worker(workflow) as (process, _recognizer):
                compare = process.side_effect
                process.side_effect = None
                process.return_value = SimpleNamespace(returncode=2, stdout='', stderr=private_cause)
                with self.assertRaisesRegex(FutureSongError, 'recognizer failed') as caught:
                    workflow.recording_evidence('AAAAAAAAAAA')
                self.assertEqual(diagnostic(), private_cause.encode()[-12000:])
                self.assertNotIn('actual child failure', str(caught.exception))
                process.return_value = SimpleNamespace(returncode=2, stdout='', stderr='new child cause')
                with self.assertRaises(FutureSongError):
                    workflow.recording_evidence('AAAAAAAAAAA')
                self.assertTrue(diagnostic().endswith(b'new child cause'))
                process.side_effect = subprocess.TimeoutExpired('recognizer', 3, stderr=b'private deadline cause')
                with self.assertRaises(subprocess.TimeoutExpired):
                    workflow.recording_evidence('AAAAAAAAAAA')
                self.assertTrue(diagnostic().endswith(b'private deadline cause'))
                process.side_effect = None
                process.return_value = SimpleNamespace(returncode=0, stdout='not-json', stderr='warning')
                with self.assertRaises(json.JSONDecodeError):
                    workflow.recording_evidence('AAAAAAAAAAA')
                self.assertIn(b'JSONDecodeError', diagnostic())
                process.side_effect = compare
                result = workflow.recording_evidence('AAAAAAAAAAA')
                self.assertTrue(result['reviewRequired'])
                recovered = json.loads((job_dir / 'job.json').read_bytes())
                self.assertNotIn('recordingEvidenceDiagnostics', recovered)
                status = workflow.summary(recovered, verify_delivery=False)
                self.assertEqual(status['recordingEvidence']['status'], 'complete')
                self.assertNotIn('privateDiagnostics', status['recordingEvidence'])
                self.assertEqual(workflow.report_store.latest_workflow_operation('AAAAAAAAAAA')['status'], 'complete')
                self.assertNotIn('child cause', json.dumps(result))
            self.assertEqual(len(retained), 4)
            self.assertEqual(job['state'], 'WAITING_FOR_EDITORIAL')
            self.assertEqual(before, {p: p.read_bytes() for p in before})
            for path, body in retained.items():
                self.assertEqual(path.read_bytes(), body)

    def test_recording_evidence_runs_before_approval_and_durably_blocks_content_without_changing_authority(self):
        import io
        import wave
        from contextlib import redirect_stdout
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.editorial_review import POLICY_VERSION
        import stemwise_engine.recording_evidence as recording_evidence
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as directory:
            root = Path(directory)
            workflow = workflow_fixture(root)
            workflow.root = root
            workflow.pc_side_root = root
            workflow.config_path = root / 'config.json'
            workflow.config = {**workflow.config, 'outputDir': str(root / 'runtime')}
            libraries = [Path(workflow.config['projectDir']) / '.venv-lyrics-align/lib/python3.10/site-packages'
                         / 'nvidia' / package / 'lib' for package in ('cublas', 'cudnn', 'cuda_nvrtc')]
            for path in libraries:
                path.mkdir(parents=True)
            job_dir = root / 'jobs/AAAAAAAAAAA'
            job_dir.mkdir(parents=True)
            workflow._job_dir = lambda _: job_dir
            workflow._song_policy_matches = lambda _: True
            workflow._write_task = Mock()
            job = {'videoId': 'AAAAAAAAAAA', 'policyVersion': POLICY_VERSION,
                   'state': 'WAITING_FOR_EDITORIAL', 'deliveryAllowed': False}
            atomic_write_json(job_dir / 'job.json', job)
            workflow._pipeline = Mock()
            workflow._review_inputs = Mock(return_value={'audioSha256': 'a' * 64, 'draftSha256': 'd' * 64, 'originalMetadataSha256': 'e' * 64, 'sourcesSha256': 'f' * 64, 'plainDraftLayout': {}})
            (job_dir / 'drafts').mkdir()
            (job_dir / 'drafts/original.txt').write_text('one two three four five\n\none two three four five\n', encoding='utf-8')
            self._metadata(workflow, job_dir)
            for name in ('agent-review.json', 'checkpoint.json', 'child.zip'):
                (job_dir / name).write_bytes(b'unchanged pending state')
            audio_path = root / 'audio.wav'
            with wave.open(str(audio_path), 'wb') as output:
                output.setnchannels(2)
                output.setsampwidth(2)
                output.setframerate(48000)
                output.writeframes(b'\x01\x00' * 96000)
            audio = {'path': str(audio_path), 'sha256': hashlib.sha256(audio_path.read_bytes()).hexdigest(),
                     'byteCount': audio_path.stat().st_size}
            before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*')
                      if p.is_file() and p != job_dir / 'job.json'}
            atomic_write_json(job_dir/'sources.json', {'videoId': job['videoId'], 'lyricEvidenceSources': []})
            workflow._completed_analysis_audio = Mock(return_value=audio)
            def child_process(args, **_kwargs):
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(args[1:3], ['-m', 'stemwise_engine.recording_evidence'])
                    code = recording_evidence.main(args[3:])
                return SimpleNamespace(returncode=code, stdout=output.getvalue(), stderr='')
            with patch('stemwise_engine.future_song.run_process_tree', side_effect=child_process) as process, patch(
                    'stemwise_engine.recording_evidence.load_config', return_value=workflow.config), patch(
                    'stemwise_engine.recording_evidence.file_identity', return_value={**audio, 'stream': {
                        'sampleRate': 48000, 'channelCount': 2, 'frameCount': 48000, 'durationMs': 1000}}), patch(
                    'stemwise_engine.recording_evidence._load_or_run_qwen3_structure_transcript',
                    return_value=('unrelated spoken material', {}, False)) as recognize, patch(
                    'stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value={
                        'decision': 'not-found', 'searchStatus': 'complete', 'surfaceErrors': [], 'candidates': [],
                        'surfaceOutcomes': {surface: {'status': 'successful-empty'} for surface in ('songs', 'videos')}}) as discover, patch.dict(
                    os.environ, {'LD_LIBRARY_PATH': '/existing/runtime/lib'}):
                limited = workflow.recording_evidence('AAAAAAAAAAA')
                self.assertEqual(limited['status'], 'lyrics-content-unresolved')
                self.assertTrue(limited['reviewRequired'])
                discover.assert_called_once()
                self.assertEqual(json.loads((job_dir/'sources.json').read_bytes())['counterpartDiscovery']['searchStatus'], 'complete')
                persisted = workflow._load_job('AAAAAAAAAAA')
                self.assertEqual(persisted['state'], 'WAITING_FOR_EDITORIAL')
                self.assertTrue(persisted['lyricContentLimitation']['reasons'])
                self.assertFalse(persisted['deliveryAllowed'])
                self.assertNotIn('outcome', persisted)
                self.assertNotIn('recordingContentEvidence', persisted)
                self.assertNotIn('unrelated', json.dumps(persisted))
                self.assertEqual(recognize.call_args.kwargs['audio'], audio_path)
                self.assertEqual(recognize.call_args.kwargs['language'], 'en')
                transform = recognize.call_args.kwargs['audio_identity']['canonicalTimeTransform']
                self.assertEqual(transform['offsetFrames'], 0)
                self.assertEqual(transform['inputFrameCount'], transform['outputFrameCount'])
                self.assertIn('env', process.call_args.kwargs)
                expected_libraries = [str(path) for path in libraries]
                if os.environ.get('LD_LIBRARY_PATH'):
                    expected_libraries.append(os.environ['LD_LIBRARY_PATH'])
                self.assertEqual(process.call_args.kwargs['env']['LD_LIBRARY_PATH'], os.pathsep.join(expected_libraries))
                self.assertEqual(process.call_args.kwargs['env']['TORCH_HOME'],
                                 str(Path(workflow.config['projectDir']).parent / 'model-cache/torch'))
                self.assertEqual(before, {name: (root / name).read_bytes() for name in before})
                # Independent rejection cases start from the original pending fixture.
                atomic_write_json(job_dir / 'job.json', job)
                with patch('stemwise_engine.recording_evidence.file_identity', return_value={**audio, 'sha256': 'b' * 64}):
                    with self.assertRaisesRegex(ValueError, 'audio changed'):
                        workflow.recording_evidence('AAAAAAAAAAA')
                self.assertEqual(recognize.call_count, 1)
                self.assertEqual(before, {name: (root / name).read_bytes() for name in before})
                process.side_effect = None
                process.return_value = SimpleNamespace(returncode=2, stdout='', stderr='')
                with self.assertRaisesRegex(FutureSongError, 'recognizer failed'):
                    workflow.recording_evidence('AAAAAAAAAAA')
                self.assertEqual(before, {name: (root / name).read_bytes() for name in before})
                atomic_write_json(job_dir / 'job.json', {**job, 'state': 'DELIVERED'})
                with self.assertRaisesRegex(FutureSongError, 'editorial'):
                    workflow.recording_evidence('AAAAAAAAAAA')
                atomic_write_json(job_dir / 'job.json', job)
                libraries[-1].rmdir()
                process.reset_mock()
                with self.assertRaisesRegex(FutureSongError, 'CUDA libraries are incomplete'):
                    workflow.recording_evidence('AAAAAAAAAAA')
                process.assert_not_called()
                self.assertEqual(before, {name: (root / name).read_bytes() for name in before})

    def test_selected_artwork_rejects_current_nonrecording_source_relations(self):
        source_id = "BBBBBBBBBBB"
        source_url = f"https://www.youtube.com/watch?v={source_id}"
        cover = b"reviewed-cover"
        for relation in (None, "exact-recording", "text-reference", "different-version", "unusable"):
            with self.subTest(relation=relation), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                workflow = workflow_fixture(root)
                workflow._job_dir = lambda _: root
                workflow._runtime_config = Mock(return_value={})
                authority = {"lyricEvidenceVideoIds": [source_id]}
                if relation is not None:
                    authority["recordingReview"] = {"sourceAssociations": [{"videoId": source_id, "relation": relation}]}
                (root / "agent-review.json").write_text(json.dumps(authority), encoding="utf-8")
                (root / "sources.json").write_text(json.dumps({"lyricEvidenceSources": [{"videoId": source_id}]}), encoding="utf-8")
                job = {"videoId": "AAAAAAAAAAA", "artworkReview": {
                    "result": "accepted", "sourceVideoId": source_id, "sourceUrl": source_url,
                    "artworkSha256": hashlib.sha256(cover).hexdigest(), "reviewedAt": "2026-09-05T00:00:00Z",
                }}
                pipeline = SimpleNamespace(child_path=lambda _: Path("child.zip"), load_checkpoint=lambda _: None)
                with patch('stemwise_engine.future_song.read_child', return_value=({"artwork": {"source": {"url": source_url}}}, cover)):
                    if relation == "exact-recording":
                        workflow._require_reviewed_artwork(job, pipeline)
                    else:
                        with self.assertRaises(FutureSongError):
                            workflow._require_reviewed_artwork(job, pipeline)

    def test_artwork_rejects_active_or_already_aligned_jobs_before_source_or_package_changes(self):
        for state in ('ANALYZING', 'ALIGNING_LYRICS', 'LINE_UNRESOLVED',
                      'WAITING_FOR_DISPLAY_REVIEW', 'VALIDATED', 'VALIDATION_FAILED'):
            with self.subTest(state=state), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                workflow = workflow_fixture(Path(name))
                job = {'videoId': 'AAAAAAAAAAA', 'state': state}
                before = copy.deepcopy(job)
                workflow._load_job = Mock(return_value=job)
                workflow._reviewed_artwork_source = Mock(side_effect=AssertionError('forbidden artwork state reached source selection'))
                workflow._pipeline = Mock()
                with self.assertRaisesRegex(FutureSongError, 'only before the first alignment'):
                    workflow._select_artwork_action('AAAAAAAAAAA', 'https://youtu.be/AAAAAAAAAAA')
                workflow._reviewed_artwork_source.assert_not_called()
                workflow._pipeline.assert_not_called()
                self.assertEqual(job, before)

    def test_selecting_known_different_version_artwork_rejects_before_fetch(self):
        scratch = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(scratch.cleanup)
        workflow = workflow_fixture(Path(scratch.name))
        workflow._load_job = Mock(return_value={"videoId": "AAAAAAAAAAA", "state": "WAITING_FOR_EDITORIAL"})
        workflow._validate_authority_outcome = Mock(return_value=(
            "resolved", {
                "lyricEvidenceVideoIds": ["BBBBBBBBBBB"],
                "recordingReview": {"sourceAssociations": [{"videoId": "BBBBBBBBBBB", "relation": "different-version"}]},
            }, {"lyricEvidenceSources": [{"videoId": "BBBBBBBBBBB"}]},
        ))
        workflow._pipeline = Mock(side_effect=AssertionError("Rejected source reached package mutation"))
        with patch('stemwise_engine.youtube.fetch_video_info') as fetch:
            with self.assertRaisesRegex(FutureSongError, "exact recording"):
                workflow._select_artwork_action("AAAAAAAAAAA", "https://youtu.be/BBBBBBBBBBB")
            fetch.assert_not_called()

    def test_counterpart_artwork_requires_finite_positive_recording_durations(self):
        for source_duration, package_duration in (
            (None, 180), (float("nan"), 180), ("180", 180), (180, None),
            (180, float("nan")), (0, 0), (True, True),
        ):
            with self.subTest(source=source_duration, package=package_duration), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                workflow = workflow_fixture(Path(name))
                workflow.runtime = Path(name)/"unused-staging"
                job = {"videoId": "AAAAAAAAAAA", "state": "WAITING_FOR_EDITORIAL",
                       "durationSeconds": package_duration}
                workflow._load_job = Mock(return_value=job)
                workflow._validate_authority_outcome = Mock(return_value=(
                    "resolved", {"lyricEvidenceVideoIds": ["BBBBBBBBBBB"], "recordingReview": {
                        "sourceAssociations": [{"videoId": "BBBBBBBBBBB", "relation": "exact-recording"}]}},
                    {"lyricEvidenceSources": [{"videoId": "BBBBBBBBBBB"}]},
                ))
                workflow._pipeline = Mock(return_value=SimpleNamespace(child_path=lambda _: Path("child.zip"), load_checkpoint=lambda _: None))
                workflow._runtime_config = Mock(return_value={})
                with patch('stemwise_engine.future_song.read_child', return_value=(
                    {"source": {"videoId": "AAAAAAAAAAA"}}, b"old-cover"
                )), patch('stemwise_engine.youtube.fetch_video_info', return_value={"duration": source_duration}), \
                     patch('stemwise_engine.youtube.create_cover_png', side_effect=AssertionError("Unverified duration reached artwork generation")) as render:
                    with self.assertRaisesRegex(FutureSongError, "duration"):
                        workflow._select_artwork_action("AAAAAAAAAAA", "https://youtu.be/BBBBBBBBBBB")
                    render.assert_not_called()
                self.assertFalse(workflow.runtime.exists())

    def test_artwork_revision_keeps_real_child_checkpoint_valid_for_display_packaging_without_inference(self):
        from stemwise_engine.common import sha256_file
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import create_child_zip, read_child, validate_child_zip
        from test_packages import package_config, song_document, PNG_1X1
        from test_future_song import WORKSPACE
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name, \
                patch('stemwise_engine.pipeline.collect_provenance',
                    return_value={'fixture': 'artwork-numerical-producer'}):
            root = Path(name)
            config = package_config(root)
            config['projectDir'] = str(root / 'PC side/builder')
            workflow = workflow_fixture(root)
            workflow.runtime = Path(config['outputDir'])
            workflow.jobs = root / 'jobs'
            workflow.config = config
            pipeline = StemwisePipeline(config)
            video = 'AAAAAAAAAAA'
            child = pipeline.child_path(video)
            cover = root / 'cover.png'
            cover.write_bytes(PNG_1X1)
            previous = '2099-01-01T00:00:00Z'
            document = song_document(video)
            document['generatedAt'] = previous
            create_child_zip(child, document, cover, config)
            state = pipeline._new_state({'videoId': video}, 'absent')
            state.update(status='complete', childSha256=sha256_file(child))
            pipeline.save_checkpoint(state)
            job = {'videoId': video, 'state': 'WAITING_FOR_EDITORIAL', 'durationSeconds': 1}
            job_dir = workflow._job_dir(video)
            job_dir.mkdir(parents=True)
            (job_dir / 'job.json').write_text(json.dumps(job), encoding='utf-8')
            authority = {'lyricEvidenceVideoIds': [video], 'recordingReview': {
                'sourceAssociations': [{'videoId': video, 'relation': 'exact-recording'}]}}
            sources = {'lyricEvidenceSources': [{'videoId': video}]}
            (job_dir / 'agent-review.json').write_text(json.dumps(authority), encoding='utf-8')
            (job_dir / 'sources.json').write_text(json.dumps(sources), encoding='utf-8')
            workflow._load_job = Mock(return_value=job)
            workflow._validate_authority_outcome = Mock(return_value=('resolved', authority, sources))
            workflow._pipeline = Mock(return_value=pipeline)
            workflow._runtime_config = lambda: config
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow.summary = lambda current: {'state': current['state']}
            def create_cover(_info, _id, destination, **_options):
                from PIL import Image
                Image.new('RGB', (1, 1), 'blue').save(destination, format='PNG')
                return document['artwork']
            with patch('stemwise_engine.youtube.fetch_video_info', return_value={'duration': 1}), \
                    patch('stemwise_engine.youtube.create_cover_png', side_effect=create_cover), \
                    patch('stemwise_engine.future_song.utc_now', return_value=previous), \
                    patch.object(pipeline, '_analysis', side_effect=AssertionError('Artwork reran inference')):
                workflow._select_artwork_action(video, f'https://youtu.be/{video}')
                actual, actual_cover = read_child(child, config)
                self.assertEqual(actual['generatedAt'], '2099-01-01T00:00:01Z')
                self.assertNotEqual(actual_cover, PNG_1X1)
                self.assertEqual(pipeline.load_checkpoint(video)['childSha256'], sha256_file(child))
                validate_child_zip(child, config, expected_video_id=video)
                workflow._require_reviewed_artwork(job, pipeline)
                # DisplayReview uses this same producer/consumer path to refresh its package.
                self.assertEqual(pipeline.process_song({'videoId': video}), 'skipped')
                self.assertEqual(pipeline.load_checkpoint(video)['stages']['artwork-review']['status'], 'complete')

    def test_real_artwork_edit_then_validate_consumes_display_review_with_matching_checkpoint(self):
        self._real_artwork_validate_display_review(correct_metadata=False)

    def test_reviewed_metadata_correction_reaches_display_review_with_matching_checkpoint(self):
        self._real_artwork_validate_display_review(correct_metadata=True)

    def _real_artwork_validate_display_review(self, *, correct_metadata):
        from test_future_song_display_review import DisplayReviewCase, VIDEO_ID, ROOT
        from test_packages import package_config, song_document, PNG_1X1
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import create_child_zip, read_child, validate_child_zip
        from stemwise_engine.common import sha256_file, atomic_write_json
        from stemwise_engine.metadata_research import metadata_sha256
        from stemwise_engine.workflow_review import review_template
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name, \
                patch('stemwise_engine.pipeline.collect_provenance',
                    return_value={'fixture': 'artwork-numerical-producer'}):
            root = Path(name)
            case = DisplayReviewCase(root)
            workflow, job = case.workflow, case.job
            workflow._save_job = type(workflow)._save_job.__get__(workflow)
            config = package_config(root)
            config.update(projectDir=str(root / 'PC side/builder'), outputDir=str(workflow.runtime),
                          lyricsInputDir=str(workflow.owner))
            workflow.config = config
            workflow._runtime_config = lambda: config
            pipeline = StemwisePipeline(config)
            workflow._pipeline = lambda: pipeline
            child = pipeline.child_path(VIDEO_ID)
            cover = root / 'cover.png'
            cover.write_bytes(PNG_1X1)
            document = song_document(VIDEO_ID)
            from stemwise_engine.lyrics_alignment import reproject_display_only
            from test_future_song_display_review import PLAIN_TEXT
            document['lyrics'] = reproject_display_only(case.aligned, PLAIN_TEXT, case.mapping, 10000)
            document['analysis']['durationMs'] = 10000
            document['analysis']['waveform']['levels'][0].update(frameCount=200,
                data=base64.b64encode(bytes(1200)).decode('ascii'))
            document['analysis']['toolRuns'] = [dict(run, status='complete', version='1')
                for run in document['analysis']['toolRuns']]
            for run in document['analysis']['toolRuns']:
                run.pop('reason', None)
            create_child_zip(child, document, cover, config)
            state = pipeline._new_state({'videoId': VIDEO_ID}, sha256_file(case.sidecar))
            state.update(status='complete', childSha256=sha256_file(child))
            pipeline.save_checkpoint(state)
            job.update(state='WAITING_FOR_EDITORIAL', durationSeconds=10)
            job.pop('artworkReview')
            job_path = workflow._job_dir(VIDEO_ID) / 'job.json'
            atomic_write_json(job_path, job)
            source_url = f'https://www.youtube.com/watch?v={VIDEO_ID}'
            sources = {'lyricEvidenceSources': [{'videoId': VIDEO_ID}]}
            atomic_write_json(case.sources, sources)
            sources_sha = sha256_file(case.sources)
            metadata = document['metadata']
            reviewed_title = 'Reviewed exact recording title' if correct_metadata else metadata['title']
            recording_review = review_template(VIDEO_ID, sources)['recordingReview']
            recording_review.update(audioSha256='a' * 64, draftSha256=workflow._recording_draft_sha(job),
                sourcesSha256=sources_sha, recordingCompared=True,
                versionSpecificWordingReviewed=True, performedOrderReviewed=True,
                sourceAssociations=[{'videoId': VIDEO_ID, 'relation': 'exact-recording'}])
            authority = {'lyricEvidenceVideoIds': [VIDEO_ID], 'recordingReview': recording_review,
                'metadataReview': {'formatVersion': 1, 'videoId': VIDEO_ID,
                    'originalMetadataSha256': metadata_sha256(metadata), 'sourcesSha256': sources_sha,
                    'title': reviewed_title, 'artists': metadata['artists'], 'evidenceUrls': [source_url],
                    'recordingIdentityConfirmed': True}}
            atomic_write_json(workflow._job_dir(VIDEO_ID) / 'agent-review.json', authority)
            workflow._validate_authority_outcome = Mock(return_value=('resolved', authority, sources))
            def create_cover(_info, _id, destination, **_options):
                from PIL import Image
                Image.new('RGB', (1, 1), 'blue').save(destination, format='PNG')
                return document['artwork']
            with patch('stemwise_engine.youtube.fetch_video_info', return_value={'duration': 10}), \
                    patch('stemwise_engine.youtube.create_cover_png', side_effect=create_cover), \
                    patch.object(pipeline, '_analysis', side_effect=AssertionError('Editorial review reran inference')):
                workflow._select_artwork_action(VIDEO_ID, source_url)
                _, case.cover = read_child(child, config)
                artwork_sha = hashlib.sha256(case.cover).hexdigest()
                case.alignment_artifacts.update(sourcesSha256=sources_sha, artworkSha256=artwork_sha)
                workflow._completed_analysis_audio.return_value['artworkSha256'] = artwork_sha
                if correct_metadata:
                    previous_child_sha = sha256_file(child)
                    workflow._require_recording_review(job, authority, sources,
                        workflow._completed_analysis_audio(pipeline, VIDEO_ID))
                    workflow._apply_metadata_review(job, authority, sources, pipeline)
                    corrected, corrected_cover = read_child(child, config)
                    self.assertNotEqual(reviewed_title, metadata['title'])
                    self.assertEqual(corrected['metadata']['title'], reviewed_title)
                    self.assertEqual(corrected_cover, case.cover)
                    self.assertNotEqual(sha256_file(child), previous_child_sha)
                    self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)['childSha256'], sha256_file(child))
                    self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)['stages']['metadata-review']['status'], 'complete')
                job['state'] = 'ALIGNING_LYRICS'
                workflow._save_job(job)
                case.begin()
                self.assertEqual(job['state'], 'WAITING_FOR_DISPLAY_REVIEW')
                self.assertEqual(workflow.validate(VIDEO_ID), {'state': 'VALIDATED'})
                workflow._align_future_lyrics.assert_not_called()
                final = validate_child_zip(child, config, expected_video_id=VIDEO_ID)
                self.assertEqual(final['lyrics'], json.loads(case.sidecar.read_bytes()))
                self.assertEqual(final['metadata']['title'], reviewed_title)
                self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)['childSha256'], sha256_file(child))
                self.assertEqual(job['validation']['childSha256'], sha256_file(child))
                self.assertEqual(job['validation']['artworkSha256'], artwork_sha)
                persisted = json.loads(job_path.read_bytes())
                self.assertEqual(persisted['state'], 'VALIDATED')
                self.assertEqual(persisted['validation']['childSha256'], sha256_file(child))


if __name__ == "__main__":
    unittest.main()
