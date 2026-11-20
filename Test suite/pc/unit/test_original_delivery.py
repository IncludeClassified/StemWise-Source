"""Phone bundles include real audio without changing validated metadata."""
import copy
import hashlib
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock, patch

from test_packages import package_config, song_document, PNG_1X1
from stemwise_engine.workflow_context import report_control_path
from stemwise_engine.packages import create_child_zip
from stemwise_engine.common import run_process_tree, sha256_file
from stemwise_engine.original_delivery import create_bundle, read_bundle, prepare_original, verify_original_delivery


class OriginalDeliveryTest(unittest.TestCase):
    def setUp(self):
        work = Path(__file__).resolve().parents[2] / 'work'
        self.temporary = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = package_config(self.root)
        self.document = song_document('abcdefghijk')
        self.cover = self.root / 'cover.png'
        self.cover.write_bytes(PNG_1X1)
        self.child = self.root / 'metadata.zip'
        create_child_zip(self.child, self.document, self.cover, self.config)
        self.audio = self.root / 'source.flac'
        run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                          'sine=frequency=440:duration=1', '-c:a', 'flac', str(self.audio)],
                         capture_output=True, check=True, timeout=15)

    def test_bundle_preserves_exact_metadata_and_actual_flac(self):
        output = self.root / 'delivery.zip'
        audio, identity = prepare_original(self.audio, self.document, self.root)
        create_bundle(output, self.child, audio, identity, self.config, self.root)
        document, cover, actual = read_bundle(output, self.config, metadata=self.child)
        self.assertEqual(document, self.document)
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(actual, identity)
        with zipfile.ZipFile(output) as bundle, zipfile.ZipFile(self.child) as original:
            self.assertEqual(set(bundle.namelist()), {'song.json', 'cover.png', 'original.flac'})
            self.assertEqual(bundle.read('song.json'), original.read('song.json'))
            self.assertEqual(bundle.read('original.flac'), self.audio.read_bytes())

    def test_native_hash_and_duration_fail_before_replacing_output(self):
        document = copy.deepcopy(self.document)
        document['source']['recording'] = {'sha256': '0' * 64, 'byteCount': self.audio.stat().st_size}
        with self.assertRaisesRegex(ValueError, 'binding'):
            prepare_original(self.audio, document, self.root)
        document = copy.deepcopy(self.document)
        document['analysis']['durationMs'] = 1100
        with self.assertRaisesRegex(ValueError, 'duration'):
            prepare_original(self.audio, document, self.root)

    def test_unknown_files_duplicates_and_changed_metadata_reject(self):
        audio, identity = prepare_original(self.audio, self.document, self.root)
        output = self.root / 'delivery.zip'
        create_bundle(output, self.child, audio, identity, self.config, self.root)
        with zipfile.ZipFile(output, 'a') as archive:
            archive.writestr('extra.txt', 'not allowed')
        with self.assertRaises(ValueError):
            read_bundle(output, self.config, metadata=self.child)

    def test_changed_metadata_and_duplicate_original_reject(self):
        audio, identity = prepare_original(self.audio, self.document, self.root)
        other = self.root / 'other.zip'
        changed = copy.deepcopy(self.document)
        changed['metadata']['title'] = 'Different recording'
        create_child_zip(other, changed, self.cover, self.config)
        output = self.root / 'delivery.zip'
        create_bundle(output, other, audio, identity, self.config, self.root)
        with self.assertRaisesRegex(ValueError, 'metadata differs'):
            read_bundle(output, self.config, metadata=self.child)
        with zipfile.ZipFile(output, 'a') as archive:
            archive.writestr('original.opus', b'OggSOpusHead')
        with self.assertRaises(ValueError):
            read_bundle(output, self.config)

    def test_byte_limit_rejects_before_destination_changes(self):
        audio, identity = prepare_original(self.audio, self.document, self.root)
        output = self.root / 'delivery.zip'
        output.write_bytes(b'owner file')
        with patch('stemwise_engine.original_delivery.MAX_ORIGINAL_BYTES', self.audio.stat().st_size - 1):
            with self.assertRaises(ValueError):
                create_bundle(output, self.child, audio, identity, self.config, self.root)
        self.assertEqual(output.read_bytes(), b'owner file')

    def test_false_opus_extension_and_mismatched_audio_reject(self):
        bogus = self.root / 'false.opus'
        bogus.write_bytes(b'not audio')
        with self.assertRaises((ValueError, subprocess.CalledProcessError)):
            prepare_original(bogus, self.document, self.root)
        audio, identity = prepare_original(self.audio, self.document, self.root)
        destination = self.root / 'delivery.zip'
        destination.write_bytes(b'keep me')
        with self.assertRaises(ValueError):
            create_bundle(destination, self.child, audio, {**identity, 'sha256': '0' * 64}, self.config, self.root)
        self.assertEqual(destination.read_bytes(), b'keep me')

    def test_opus_fallback_is_actual_opus_and_bounded(self):
        source = self.root / 'source.wav'
        run_process_tree(['ffmpeg', '-v', 'error', '-i', str(self.audio), str(source)],
                         capture_output=True, check=True, timeout=15)
        audio, identity = prepare_original(source, self.document, self.root)
        self.assertEqual(identity['name'], 'original.opus')
        self.assertIn(b'OpusHead', audio.read_bytes()[:64])
        self.assertEqual(identity['sha256'], sha256_file(audio))
        another = self.root / 'another'
        another.mkdir()
        _, repeated = prepare_original(source, self.document, another)
        self.assertEqual(identity, repeated, 'Recovery must reproduce the same Opus bytes')

    def test_webm_discard_padding_preserves_every_decoded_sample(self):
        source = self.root / 'source.webm'
        run_process_tree(['ffmpeg', '-v', 'error', '-i', str(self.audio),
                          '-c:a', 'libopus', str(source)],
                         capture_output=True, check=True, timeout=15)
        audio, identity = prepare_original(source, self.document, self.root)
        def pcm_hash(path):
            return run_process_tree(['ffmpeg', '-v', 'error', '-i', str(path),
                '-map', '0:a:0', '-c:a', 'pcm_s24le', '-f', 'hash', '-hash', 'sha256', '-'],
                capture_output=True, check=True, timeout=15).stdout
        self.assertEqual(pcm_hash(audio), pcm_hash(source))
        self.assertIn(identity['name'], ('original.flac', 'original.opus'))
        another = self.root / 'repeated'
        another.mkdir()
        self.assertEqual(prepare_original(source, self.document, another)[1], identity)

    def test_short_codec_tail_is_preserved_but_missing_audio_is_rejected(self):
        source = self.root / 'padded.wav'
        run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                          'sine=frequency=440:duration=1.058', str(source)],
                         capture_output=True, check=True, timeout=15)
        audio, identity = prepare_original(source, self.document, self.root)
        from stemwise_engine.original_delivery import _decoded_duration
        self.assertEqual(_decoded_duration(audio), 1058)
        self.assertEqual(identity['name'], 'original.opus')
        document = copy.deepcopy(self.document)
        document['analysis']['durationMs'] = 1200
        with self.assertRaisesRegex(ValueError, 'duration'):
            prepare_original(source, document, self.root)

    def _delivered(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        runtime, ready = self.root / 'runtime', self.root / 'ready'
        (runtime / 'children').mkdir(parents=True)
        ready.mkdir()
        child = runtime / 'children/abcdefghijk.stemwise.zip'
        child.write_bytes(self.child.read_bytes())
        destination = ready / child.name
        original, identity = prepare_original(self.audio, self.document, self.root)
        create_bundle(destination, child, original, identity, self.config, self.root)
        job = {'videoId': 'abcdefghijk', 'state': 'DELIVERED',
               'delivery': {'sha256': sha256_file(destination), 'bytes': destination.stat().st_size, 'original': identity},
               'validation': {'childSha256': sha256_file(child)}}
        saved = []
        from workflow_fixture import initialized_workflow
        workflow = initialized_workflow(self.root)
        workflow.__dict__.update(runtime=runtime, ready=ready, workspace_root=self.root,
            _runtime_config=lambda: self.config,
            _validated_snapshot_is_current=lambda *a, **k: True,
            _save_job=lambda value: saved.append(copy.deepcopy(value)))
        workflow.report_store.commit_finalization_run = Mock(return_value=('f' * 32, 'e' * 64))
        workflow.pc_side_root = self.root / 'PC side'
        selection = workflow.pc_side_root / 'qobuz-comparison/comparison-results/library-files.json'
        selection.parent.mkdir(parents=True)
        selection.write_text(json.dumps({'playlist':[{'videoId':'abcdefghijk','source':'youtube'}]}), encoding='utf-8')
        return workflow, job, child, destination, saved

    def _validated(self, name):
        from stemwise_engine.future_song import FutureSongWorkflow
        from workflow_fixture import initialized_workflow
        workflow = initialized_workflow(self.root / name)
        workflow.runtime = workflow.workspace_root / 'runtime'
        workflow.ready = workflow.workspace_root / 'Ready to Import'
        workflow.history = workflow.workspace_root / 'rollback'
        workflow.config['tempDir'] = str(workflow.workspace_root / 'analysis')
        workflow.pc_side_root = workflow.workspace_root / 'PC side'
        selection = workflow.pc_side_root / 'qobuz-comparison/comparison-results/library-files.json'
        selection.parent.mkdir(parents=True)
        selection.write_text(json.dumps({'playlist': [{'videoId': 'abcdefghijk', 'source': 'youtube'}]}), encoding='utf-8')
        child = workflow.runtime / 'children/abcdefghijk.stemwise.zip'
        child.parent.mkdir(parents=True)
        child.write_bytes(self.child.read_bytes())
        job_path = workflow.workspace_root / 'job.json'
        from stemwise_engine.youtube import decode_analysis_audio
        reviewed = decode_analysis_audio(self.audio, workflow.workspace_root / 'reviewed-audio', timeout_seconds=15)
        report = workflow.workspace_root / 'validation.json'
        report.write_text(json.dumps({'retainedAnalysisAudio': {
            'sha256': sha256_file(reviewed), 'byteCount': reviewed.stat().st_size, 'durationMs': 1000}}), encoding='utf-8')
        job_path.write_text(json.dumps({'videoId': 'abcdefghijk', 'state': 'VALIDATED',
            'validation': {'childSha256': sha256_file(child), 'report': str(report)}}), encoding='utf-8')
        # Substitute numerical review authority, keeping actual staging, media and publication.
        workflow._run_mutating_operation = lambda video, action, callback: callback()
        workflow._load_job = lambda video: json.loads(job_path.read_bytes())
        workflow._save_job = lambda value: job_path.write_text(json.dumps(value), encoding='utf-8')
        workflow._validated_snapshot_is_current = Mock(return_value=True)
        workflow._runtime_config = lambda: self.config
        workflow._windows_path = str
        workflow.report_store.commit_finalization_run = Mock(return_value=('f' * 32, 'e' * 64))
        workflow._write_task = Mock()
        workflow._remove_retained_analysis_audio = Mock()
        workflow._cleanup_completed_endpoint_contexts = Mock()
        workflow.summary = lambda value: copy.deepcopy(value)
        return workflow, job_path, child, workflow.ready / child.name

    def test_failed_original_preparation_never_publishes_metadata_only_or_changes_prior_delivery(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                workflow, job_path, child, destination = self._validated(str(existing))
                if existing:
                    destination.parent.mkdir(parents=True)
                    destination.write_bytes(b'previous owner delivery')
                before = job_path.read_bytes(), destination.read_bytes() if existing else None
                with patch('stemwise_engine.original_delivery.selected_original', side_effect=ValueError('provider failure')):
                    with self.assertRaisesRegex(ValueError, 'provider failure'):
                        workflow.finalize('abcdefghijk')
                self.assertEqual(job_path.read_bytes(), before[0])
                self.assertEqual(destination.read_bytes() if destination.exists() else None, before[1])
                workflow.report_store.commit_finalization_run.assert_not_called()
                workflow._remove_retained_analysis_audio.assert_not_called()

    def test_first_finalize_publishes_complete_audio_bundle_with_separate_private_hash(self):
        workflow, job_path, child, destination = self._validated('complete')
        private = child.read_bytes()
        with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio):
            result = workflow.finalize('abcdefghijk')
        document, cover, original = read_bundle(destination, self.config, metadata=child)
        self.assertEqual(document, self.document)
        self.assertEqual(child.read_bytes(), private)
        self.assertEqual(result['delivery']['original'], original)
        self.assertEqual(result['delivery']['sha256'], sha256_file(destination))
        self.assertEqual(result['validation']['childSha256'], sha256_file(child))
        self.assertNotEqual(result['delivery']['sha256'], result['validation']['childSha256'])
        self.assertEqual(workflow.report_store.commit_finalization_run.call_args.kwargs['package_sha256'], sha256_file(destination))

    def test_standalone_youtube_finalizes_with_absent_or_empty_selection_plan(self):
        for plan in ('absent', 'empty'):
            with self.subTest(plan=plan):
                workflow, _, child, destination = self._validated(plan)
                selection = workflow.pc_side_root / 'qobuz-comparison/comparison-results/library-files.json'
                if plan == 'absent':
                    selection.unlink()
                else:
                    selection.write_text('{"playlist":[]}', encoding='utf-8')
                with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio):
                    result = workflow.finalize('abcdefghijk')
                self.assertEqual(result['state'], 'DELIVERED')
                self.assertEqual(read_bundle(destination, self.config, metadata=child)[0], self.document)

    def test_source_context_controls_public_delivery_when_an_unrelated_key_exists(self):
        workflow, _, child, destination = self._validated('explicit-source')
        workflow.key = 'unrelated diagnostic key'
        with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio):
            result = workflow.finalize('abcdefghijk')
        original = read_bundle(destination, self.config, metadata=child)[2]
        self.assertEqual(result['delivery']['original'], original)

    def test_existing_rollback_must_be_regular_before_hashing(self):
        from types import SimpleNamespace
        from stemwise_engine.original_delivery import retain_replacement_rollback
        from stemwise_engine.packages import PackageValidationError
        workflow = SimpleNamespace(workspace_root=self.root, history=self.root / 'history')
        previous = self.root / 'abcdefghijk.stemwise.zip'
        previous.write_bytes(self.child.read_bytes())
        digest, replacement = sha256_file(previous), 'e' * 64
        token = hashlib.sha256(b'stemwise-delivery-rollback\0' + digest.encode('ascii')
            + b'\0' + replacement.encode('ascii')).hexdigest()[:24]
        rollback = workflow.history / 'abcdefghijk' / f'{token}.{previous.name}'
        rollback.mkdir(parents=True)
        with self.assertRaisesRegex(PackageValidationError, 'regular file'):
            retain_replacement_rollback(workflow, previous, replacement, self.root, previous_sha256=digest)
        self.assertEqual(sha256_file(previous), digest)

    def test_public_commit_and_readback_failures_retain_previous_package_and_allow_retry(self):
        import stemwise_engine.original_delivery as original_delivery
        for boundary in ('commit', 'readback'):
            with self.subTest(boundary=boundary):
                workflow, job_path, _, destination = self._validated(boundary)
                old = copy.deepcopy(self.document)
                old['generatedAt'] = '2020-01-01T00:00:00Z'
                metadata = workflow.workspace_root / 'previous.zip'
                create_child_zip(metadata, old, self.cover, self.config)
                audio, identity = prepare_original(self.audio, old, workflow.workspace_root)
                create_bundle(destination, metadata, audio, identity, self.config, workflow.workspace_root)
                before, job_before = destination.read_bytes(), job_path.read_bytes()
                replace, read = original_delivery.durable_replace, original_delivery.read_bundle
                def interrupt_commit(source, target):
                    if boundary == 'commit' and target == destination:
                        raise OSError('public commit unavailable')
                    return replace(source, target)
                def interrupt_readback(path, *args, **kwargs):
                    if boundary == 'readback' and path == destination:
                        raise OSError('public readback unavailable')
                    return read(path, *args, **kwargs)
                with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio):
                    with patch('stemwise_engine.original_delivery.durable_replace', side_effect=interrupt_commit), \
                            patch('stemwise_engine.original_delivery.read_bundle', side_effect=interrupt_readback):
                        with self.assertRaisesRegex(OSError, f'public {boundary} unavailable'):
                            workflow.finalize('abcdefghijk')
                    self.assertEqual(job_path.read_bytes(), job_before)
                    self.assertTrue(any(path.read_bytes() == before for path in workflow.history.rglob('*.zip')))
                    if boundary == 'commit':
                        self.assertEqual(destination.read_bytes(), before)
                    workflow.report_store.commit_finalization_run.assert_not_called()
                    self.assertEqual(workflow.finalize('abcdefghijk')['state'], 'DELIVERED')
                self.assertEqual(read_bundle(destination, self.config)[0], self.document)
                self.assertFalse(list(workflow.history.rglob('*.zip')))

    def test_selection_changed_during_youtube_staging_preserves_prior_package_and_job(self):
        self.assert_selection_change_preserves_prior([{'videoId': 'abcdefghijk', 'source': 'qobuz', 'trackId': 11}])

    def test_selection_removed_during_youtube_staging_preserves_prior_package_and_job(self):
        self.assert_selection_change_preserves_prior([{'videoId': 'lmnopqrstuv', 'source': 'youtube'}])

    def assert_selection_change_preserves_prior(self, playlist):
        import stemwise_engine.original_delivery as original_delivery
        workflow, job_path, child, destination = self._validated('selection-change')
        workflow.pc_side_root = workflow.workspace_root / 'PC side'
        selection = workflow.pc_side_root / 'qobuz-comparison/comparison-results/library-files.json'
        selection.parent.mkdir(parents=True, exist_ok=True)
        selection.write_text(json.dumps({'playlist': [{'videoId': 'abcdefghijk', 'source': 'youtube'}]}), encoding='utf-8')
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b'prior owner publication')
        before = destination.read_bytes(), job_path.read_bytes()
        create = original_delivery.create_bundle
        def change_selection(*args):
            create(*args)
            selection.write_text(json.dumps({'playlist': playlist}), encoding='utf-8')
        with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio), \
                patch('stemwise_engine.original_delivery.create_bundle', side_effect=change_selection):
            with self.assertRaisesRegex(ValueError, 'recording'):
                workflow.finalize('abcdefghijk')
        self.assertEqual((destination.read_bytes(), job_path.read_bytes()), before)

    def test_equal_duration_different_original_cannot_replace_reviewed_pcm(self):
        workflow, job_path, _, destination = self._validated('different-source')
        wrong = self.root / 'different-source.flac'
        run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
            'sine=frequency=880:duration=1', '-c:a', 'flac', str(wrong)],
            capture_output=True, check=True, timeout=15)
        destination.parent.mkdir(parents=True)
        destination.write_bytes(b'prior owner delivery')
        before = destination.read_bytes(), job_path.read_bytes()
        with patch('stemwise_engine.original_delivery.selected_original', return_value=wrong):
            with self.assertRaisesRegex(ValueError, 'reviewed analysis PCM'):
                workflow.finalize('abcdefghijk')
        self.assertEqual((destination.read_bytes(), job_path.read_bytes()), before)

    def test_source_changed_after_pcm_comparison_cannot_be_published(self):
        import stemwise_engine.original_delivery as original_delivery
        workflow, job_path, _, destination = self._validated('source-race')
        wrong = self.root / 'late-replacement.flac'
        run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
            'sine=frequency=880:duration=1', '-c:a', 'flac', str(wrong)],
            capture_output=True, check=True, timeout=15)
        prepare = original_delivery.prepare_original
        def replace_source(source, *args):
            source.write_bytes(wrong.read_bytes())
            return prepare(source, *args)
        before = job_path.read_bytes()
        with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio), \
                patch('stemwise_engine.original_delivery.prepare_original', side_effect=replace_source):
            with self.assertRaisesRegex(ValueError, 'changed after reviewed PCM'):
                workflow.finalize('abcdefghijk')
        self.assertEqual(job_path.read_bytes(), before)
        self.assertFalse(destination.exists())

    def test_artwork_only_finalize_reuses_previous_original_without_acquisition(self):
        workflow, job_path, child, destination = self._validated('artwork-reuse')
        with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio):
            workflow.finalize('abcdefghijk')
        previous = json.loads(job_path.read_bytes())
        original = read_bundle(destination, self.config)[2]
        updated = copy.deepcopy(self.document)
        updated['generatedAt'] = '2026-10-22T00:00:00Z'
        updated['artwork']['source']['name'] = 'Reviewed replacement artwork'
        create_child_zip(child, updated, self.cover, self.config)
        previous.update(state='VALIDATED', artworkRepair={
            'childSha256': sha256_file(child), 'previousDelivery': {
                'sha256': previous['delivery']['sha256'], 'original': original}})
        previous.pop('delivery')
        job_path.write_text(json.dumps(previous), encoding='utf-8')
        with patch('stemwise_engine.original_delivery.selected_original', side_effect=AssertionError('Do not reacquire reviewed Original')):
            workflow.finalize('abcdefghijk')
        self.assertEqual(read_bundle(destination, self.config)[2], original)

    def test_crash_after_complete_bundle_promotion_reuses_bytes_without_a_history_copy(self):
        workflow, job_path, child, destination = self._validated('crash')
        before = job_path.read_bytes()
        with patch('stemwise_engine.original_delivery.selected_original', return_value=self.audio):
            workflow.report_store.commit_finalization_run.side_effect = OSError('interrupted after promotion')
            with self.assertRaisesRegex(OSError,'interrupted after promotion'):
                workflow.finalize('abcdefghijk')
            read_bundle(destination,self.config,metadata=child)
            self.assertEqual(job_path.read_bytes(),before)
            delivered = destination.read_bytes(),destination.stat().st_mtime_ns
            workflow.report_store.commit_finalization_run.side_effect = None
            workflow.finalize('abcdefghijk')
        self.assertEqual((destination.read_bytes(),destination.stat().st_mtime_ns),delivered)
        self.assertFalse(list(workflow.history.rglob('*.zip')))





    def _artwork_delivery(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        from types import SimpleNamespace
        workflow, job, child, destination, _ = self._delivered()
        workflow.root = self.root / 'state'
        workflow.jobs = workflow.root / 'jobs'
        workflow.owner = workflow.root / 'owner-input'
        workflow.pc_side_root = self.root / 'PC side'
        workflow.reports = workflow.pc_side_root / 'reports'
        workflow.config = self.config
        workflow.config_path = self.root / 'config.json'
        workflow.config_path.write_text('{}', encoding='utf-8')
        job.update(policy={'schemaSha256':'c' * 64}, durationSeconds=1,
            validation={**job['validation'], 'artworkSha256': hashlib.sha256(PNG_1X1).hexdigest(),
                'reportSha256':'1' * 64, 'runId':'2' * 32, 'runSha256':'3' * 64,
                'alignmentRunId':'6' * 32, 'alignmentRunSha256':'7' * 64})
        job['delivery'].update(runId='4' * 32, runSha256='5' * 64)
        job_path = workflow._job_path(job['videoId'])
        job_path.parent.mkdir(parents=True)
        workflow._save_job = lambda value: job_path.write_text(json.dumps(value), encoding='utf-8')
        workflow._save_job(job)
        workflow._load_job = lambda _video: json.loads(job_path.read_bytes())
        pointer = report_control_path(workflow.report_store.ensure_report_identity(job['videoId']), 'current-run.json')
        from stemwise_engine.common import atomic_write_json
        atomic_write_json(pointer, {'runId':'4' * 32, 'runSha256':'5' * 64})
        from stemwise_engine.pipeline import StemwisePipeline
        pipeline_config = {**self.config, 'projectDir': str(self.root / 'PC side/builder'),
            'outputDir': str(self.root / 'pipeline-output'), 'tempDir': str(self.root / 'pipeline-temp')}
        with patch.dict('os.environ', {'STEMWISE_ALLIN1_PYTHON': str(self.root / 'runtime/python')}):
            pipeline = StemwisePipeline(pipeline_config)
        pipeline.children_dir = child.parent
        pipeline.child_path = lambda _: child
        pipeline.save_checkpoint({'videoId': job['videoId'], 'status': 'complete',
                                  'childSha256': sha256_file(child)})
        workflow._pipeline = lambda: pipeline
        workflow._reviewed_artwork_source = lambda *_: 'BBBBBBBBBBB'
        workflow._artwork_source_info = lambda _: {'duration':1}
        def cover(_info, source_id, path, _pipeline):
            from PIL import Image
            Image.new('RGB', (1,1), 'blue' if source_id == 'BBBBBBBBBBB' else 'green').save(path)
            value = copy.deepcopy(self.document['artwork'])
            value['source']['url'] = f'https://www.youtube.com/watch?v={source_id}'
            return value
        workflow._create_selected_artwork = cover
        workflow._write_task = Mock()
        workflow.summary = copy.deepcopy
        def coherent(value, *, allow_finalization_pointer=False, **_):
            from stemwise_engine.original_delivery import _artwork_original
            from stemwise_engine.packages import read_child
            from stemwise_engine.workflow_review import finalization_pointer_matches
            repair = value.get('artworkRepair')
            expected = value['delivery'] if value['state'] == 'DELIVERED' else repair
            public = value.get('delivery') or repair['previousDelivery']
            child_sha = sha256_file(child)
            if child_sha != (repair['childSha256'] if repair else value['validation']['childSha256']):
                return False
            pointer_matches = json.loads(pointer.read_bytes()) == {
                key: expected[key] for key in ('runId', 'runSha256')}
            if value['state'] == 'DELIVERED':
                return pointer_matches and sha256_file(destination) == public['sha256']
            document, cover = read_child(child, self.config)
            workflow._validated_artwork_repair(value, child_document=document,
                child_sha256=child_sha, artwork_sha256=hashlib.sha256(cover).hexdigest(),
                require_current_pointer=False)
            if not pointer_matches and not (allow_finalization_pointer and finalization_pointer_matches(
                    workflow._snapshot_context(), value, child, child_sha, value['policy']['schemaSha256'])):
                return False
            # Finalize retains the exact old public package until its new job receipt commits.
            with tempfile.TemporaryDirectory(dir=self.root) as directory:
                _artwork_original(workflow, value, document, Path(directory), destination)
            if sha256_file(destination) != public['sha256']:
                read_bundle(destination, self.config, metadata=child, original=public['original'])
            return True
        workflow._validated_snapshot_is_current = coherent
        return workflow, child, destination, pointer, job_path

    def _repeatable_artwork_delivery(self):
        from stemwise_engine.future_song import extract_video_id
        workflow, child, destination, pointer, job_path = self._artwork_delivery()
        workflow._run_mutating_operation = lambda _video, _action, callback: callback()
        workflow.history = self.root / 'history'
        workflow._remove_retained_analysis_audio = Mock()
        workflow._cleanup_completed_endpoint_contexts = Mock()
        workflow._reviewed_artwork_source = lambda _job, url: extract_video_id(url)
        del workflow.report_store.commit_finalization_run
        return workflow, child, destination, pointer, job_path

    def test_two_artwork_revisions_bind_current_parent_and_preserve_original_audio(self):
        workflow, child, destination, _, job_path = self._repeatable_artwork_delivery()
        previous = json.loads(job_path.read_bytes())
        original = read_bundle(destination, self.config)[2]
        for source in ('BBBBBBBBBBB', 'CCCCCCCCCCC'):
            old_child_sha = sha256_file(child)
            result = workflow._select_artwork_action('abcdefghijk', f'https://youtu.be/{source}')
            self.assertEqual(result['state'], 'VALIDATED')
            repair = result['artworkRepair']
            self.assertEqual(repair['previousChildSha256'], old_child_sha)
            self.assertEqual(repair['validationChildSha256'], previous['validation']['childSha256'])
            from stemwise_engine.packages import read_child
            document, cover = read_child(child, self.config)
            self.assertEqual(workflow._validated_artwork_repair(result, child_document=document,
                child_sha256=sha256_file(child), artwork_sha256=hashlib.sha256(cover).hexdigest(),
                require_current_pointer=True), repair)
            if previous.get('artworkRepair'):
                self.assertEqual(repair['previousRepair'], {key: previous['artworkRepair'][key]
                                                           for key in ('runId', 'runSha256')})
            with patch('stemwise_engine.original_delivery.selected_original', side_effect=AssertionError('must reuse Original')):
                previous = workflow.finalize('abcdefghijk')
            self.assertEqual(previous['state'], 'DELIVERED')
            self.assertEqual(read_bundle(destination, self.config, metadata=child)[2], original)

    def test_second_artwork_revision_interruption_retains_last_delivered_package(self):
        import stemwise_engine.future_song as future_song
        class PowerLoss(BaseException): pass
        for boundary in ('pointer', 'child', 'job'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(dir=self.root) as directory:
                original_root, self.root = self.root, Path(directory)
                try:
                    workflow, child, destination, pointer, job_path = self._repeatable_artwork_delivery()
                    workflow._select_artwork_action('abcdefghijk', 'https://youtu.be/BBBBBBBBBBB')
                    workflow.finalize('abcdefghijk')
                    before = destination.read_bytes()
                    prior_child = child.read_bytes()
                    commit, replace, save = workflow.report_store.commit_artwork_repair_run, workflow._pipeline().commit_child_edit, workflow._save_job
                    reached = False
                    def commit_then_stop(*args, **kwargs):
                        nonlocal reached
                        result = commit(*args, **kwargs)
                        if boundary == 'pointer': reached = True; raise PowerLoss()
                        return result
                    def replace_then_stop(video, candidate, **kwargs):
                        nonlocal reached
                        replace(video, candidate, **kwargs)
                        if boundary == 'child': reached = True; raise PowerLoss()
                    def save_then_stop(value):
                        nonlocal reached
                        save(value)
                        if boundary == 'job' and value['state'] == 'VALIDATED': reached = True; raise PowerLoss()
                    with patch.object(workflow.report_store, 'commit_artwork_repair_run', side_effect=commit_then_stop), \
                            patch.object(workflow._pipeline(), 'commit_child_edit', side_effect=replace_then_stop), \
                            patch.object(workflow, '_save_job', side_effect=save_then_stop):
                        with self.assertRaises(PowerLoss):
                            workflow._select_artwork_action('abcdefghijk', 'https://youtu.be/CCCCCCCCCCC')
                    self.assertTrue(reached)
                    recovered = workflow._recover_artwork_repair(workflow._load_job('abcdefghijk'))
                    self.assertEqual(destination.read_bytes(), before)
                    if boundary != 'job': self.assertEqual(child.read_bytes(), prior_child)
                    self.assertEqual(recovered['state'], 'VALIDATED' if boundary == 'job' else 'DELIVERED')
                    self.assertTrue(workflow._validated_snapshot_is_current(recovered))
                    self.assertFalse(list(job_path.parent.glob('artwork-repair.rollback.*')))
                finally:
                    self.root = original_root

    def test_artwork_replace_failure_restores_pointer_and_permits_retry(self):
        import stemwise_engine.future_song as future_song
        workflow, child, destination, pointer, job_path = self._artwork_delivery()
        before = [path.read_bytes() for path in (child, destination, pointer, job_path)]
        replace = workflow._pipeline().commit_child_edit
        def locked(video, candidate, **kwargs):
            if kwargs['stage'] == 'artwork-review':
                raise PermissionError('private child locked')
            return replace(video, candidate, **kwargs)
        with patch.object(workflow._pipeline(), 'commit_child_edit', side_effect=locked):
            with self.assertRaisesRegex(PermissionError, 'locked'):
                workflow._select_artwork_action('abcdefghijk', 'https://youtu.be/BBBBBBBBBBB')
        self.assertEqual([path.read_bytes() for path in (child,destination,pointer,job_path)], before)
        self.assertTrue(workflow._validated_snapshot_is_current(workflow._load_job('abcdefghijk')))
        self.assertEqual(workflow._select_artwork_action('abcdefghijk', 'https://youtu.be/BBBBBBBBBBB')['state'], 'VALIDATED')

    def test_artwork_interruption_recovers_each_publication_boundary(self):
        import stemwise_engine.future_song as future_song
        class PowerLoss(BaseException):
            pass
        for boundary in ('pointer', 'child', 'job'):
            with self.subTest(boundary=boundary):
                # Each scenario owns fresh durable state.
                with tempfile.TemporaryDirectory(dir=self.root) as name:
                    original_root = self.root
                    self.root = Path(name)
                    try:
                        workflow, child, destination, pointer, job_path = self._artwork_delivery()
                        commit, replace, save = workflow.report_store.commit_artwork_repair_run, workflow._pipeline().commit_child_edit, workflow._save_job
                        def commit_then_stop(*args, **kwargs):
                            result = commit(*args, **kwargs)
                            if boundary == 'pointer': raise PowerLoss()
                            return result
                        def replace_then_stop(video, candidate, **kwargs):
                            replace(video, candidate, **kwargs)
                            if boundary == 'child': raise PowerLoss()
                        def save_then_stop(value):
                            save(value)
                            if boundary == 'job' and value.get('artworkRepair'): raise PowerLoss()
                        with patch.object(workflow.report_store, 'commit_artwork_repair_run', side_effect=commit_then_stop), \
                                patch.object(workflow._pipeline(), 'commit_child_edit', side_effect=replace_then_stop), \
                                patch.object(workflow, '_save_job', side_effect=save_then_stop):
                            with self.assertRaises(PowerLoss):
                                workflow._select_artwork_action('abcdefghijk', 'https://youtu.be/BBBBBBBBBBB')
                        recovered = workflow._recover_artwork_repair(workflow._load_job('abcdefghijk'))
                        self.assertTrue(workflow._validated_snapshot_is_current(recovered))
                        self.assertEqual(recovered['state'], 'VALIDATED' if boundary == 'job' else 'DELIVERED')
                        self.assertFalse(list(job_path.parent.glob('artwork-repair.rollback.*')))
                    finally:
                        self.root = original_root
