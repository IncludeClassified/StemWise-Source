"""Complete Original word timing gates delivery; retained progress never invents authority."""
import copy
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'Test suite/pc/unit'))
from stemwise_engine.workflow_context import report_control_path
from stemwise_engine.common import atomic_write_json, create_manifested_directory, sha256_file
from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
from stemwise_engine.packages import create_child_zip, read_child
from stemwise_engine.provenance import provenance_fingerprint
from test_packages import song_document, PNG_1X1


class AvailableCompletionTests(unittest.TestCase):
    def test_delivered_ordinary_display_review_renews_without_capability_fields_or_inference(self):
        import json
        from stemwise_engine.original_delivery import read_bundle, delivered_capability_inputs
        self.current_display_fixture()
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('retained words must not infer')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            ordinary = self.workflow._load_job(self.video)
            self.assertNotIn('inputSha256', ordinary['validation'])
            self.assertNotIn('lyricsSha256', ordinary['validation'])
            self.assertEqual(ordinary['displayReview']['result'], 'accepted-display-only')
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
            destination = self.workflow.ready/self.child.name
            prior_bytes = destination.read_bytes()
            prior, cover, original = read_bundle(destination, self.pipeline.config)
            historical = copy.deepcopy(self.workflow._load_job(self.video))
            historical.pop('displayReview')
            self.assertEqual(delivered_capability_inputs(self.workflow, historical)[2], destination)
            for field in ('displayOriginalSha256', 'alignmentRunSha256'):
                wrong = copy.deepcopy(historical)
                wrong['validation'][field] = '0'*64
                with self.assertRaises(ValueError):
                    delivered_capability_inputs(self.workflow, wrong)
                self.assertEqual(destination.read_bytes(), prior_bytes)
            proof = self.workflow.report_store.report_root(self.video)/'runs'/historical['validation']['runId']/'run.json'
            proof_bytes = proof.read_bytes()
            try:
                proof.write_bytes(b'{}')
                with self.assertRaisesRegex(ValueError, 'Previous display validation owner changed'):
                    delivered_capability_inputs(self.workflow, historical)
            finally:
                proof.write_bytes(proof_bytes)
            review_path = self.workflow._job_dir(self.video)/'agent-review.json'
            review = json.loads(review_path.read_bytes())
            review['notes'] = 'Renewed exact unchanged recording and words.'
            atomic_write_json(review_path, review)
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertEqual(destination.read_bytes(), prior_bytes)
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
            renewed, renewed_cover, renewed_original = read_bundle(destination, self.pipeline.config)
            self.assertEqual(renewed['lyrics'], prior['lyrics'])
            self.assertEqual(renewed['analysis'], prior['analysis'])
            self.assertEqual(renewed['source'], prior['source'])
            self.assertEqual((renewed_cover, renewed_original), (cover, original))
            completed = self.workflow._load_job(self.video)
            self.assertEqual(completed['validation']['kind'], 'capability-completion')
            self.assertEqual(delivered_capability_inputs(self.workflow, completed)[2], destination)
            self.assertTrue(self.workflow._validated_snapshot_is_current(
                completed, require_delivery=True))

    def owner_prediction_fixture(self, *, required_companions=False):
        import json
        self.current_display_fixture(required_companions=required_companions)
        draft = self.workflow._job_dir(self.video)/'drafts'
        review = json.loads((draft.parent/'agent-review.json').read_bytes())
        compiled, _, _ = self.workflow._preflight_drafts(self.workflow._load_job(self.video))
        original = compiled['tracks'][0]
        # Lookup is deliberately unfinished. It cannot overrule explicit owner wording authority.
        review.update(authorityOutcome='pending', unresolvedMaterialIssues=['source lookup pending'])
        atomic_write_json(draft.parent/'agent-review.json', review)
        job = self.workflow._load_job(self.video)
        job.pop('displayReview', None)
        self.workflow._save_job(job)
        inputs = self.workflow._owner_verification_inputs(job)
        self.assertEqual(inputs['template']['decision'], 'pending')
        proof = {**inputs['template'], 'reviewer': 'Independent known-answer owner fixture',
            'completedAt': '2026-11-13T19:00:00Z', 'decision': inputs['requiredDecision'],
            'timing': inputs['requiredTiming'], 'rationale': 'The complete words and both occurrences were independently checked.'}
        atomic_write_json(draft.parent/inputs['path'], proof)
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        self.workflow._owner_prediction_inputs(job, audio)
        if required_companions:
            self.approve_current_companions(draft, review)
        return draft, review, original, proof

    @contextmanager
    def owner_prediction_model_boundary(self, *, missing_end=False):
        """Public orchestration and real worker CLI/cache; only numerical execution is inert."""
        import io
        import json
        import subprocess
        from types import SimpleNamespace
        from contextlib import ExitStack, redirect_stdout, redirect_stderr
        from test_lyrics_alignment import mms_timeline
        from stemwise_engine.runtime_environment import environment_python
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        import stemwise_engine.lyrics_align_pc as worker
        project = Path(self.workflow.config['projectDir'])
        for runtime in ('main', 'lyrics'):
            python = environment_python(runtime, project)
            python.parent.mkdir(parents=True, exist_ok=True)
            python.touch()
        for name in ('ctc_worker.py', 'acoustic_inputs.py', 'worker_protocol.py', 'worker_publication.py'):
            shutil.copyfile(ROOT/'PC side/stemwise-playlist-builder/src'/name, project/'src'/name)
        model = project.parent/'model-cache/torch/hub/checkpoints/model.pt'
        model.parent.mkdir(parents=True, exist_ok=True)
        model.write_bytes(b'inert known numerical model')
        runtime = {'runtimeVersion': 1, 'bundle': 'torchaudio.pipelines.MMS_FA',
            'supportedRequestVersions': [worker.MMS_REQUEST_VERSION], 'requestedDevice': 'cuda',
            'resolvedDevice': 'cuda', 'cudaAvailable': True, **worker.models.mms_runtime_pins(project)}
        def numerical(**kwargs):
            job = self.workflow._load_job(self.video)
            compiled, _, _ = self.workflow._preflight_drafts(job, roles=frozenset({'original'}))
            original = compiled['tracks'][0]
            mapping = build_lexical_occurrence_mapping(self.video, original, (0, 1))
            self.assertEqual(kwargs['request']['segments'], [{'segmentIndex': 0, 'startLineIndex': 0,
                'endLineIndex': 2, 'windowStartMs': 0, 'windowEndMs': 1000}])
            lines = []
            for index, (occurrence, intervals) in enumerate(zip(mapping['performedOccurrences'],
                    (((100, 250), (300, 450)), ((500, 650), (700, 900))))):
                words = [{'wordId': word['occurrenceWordId'], 'startMs': start, 'endMs': end,
                    'confidence': .02, 'acousticTokenCount': 1, 'romanizedWordCount': 1}
                    for word, (start, end) in zip(occurrence['words'], intervals)]
                lines.append({'lineIndex': index, 'sourcePhysicalIndex': index,
                    'lineId': occurrence['occurrenceId'], 'startMs': intervals[0][0], 'endMs': intervals[-1][1],
                    'confidence': .02, 'minimumWordConfidence': .02, 'displayWordCount': 2,
                    'acousticTokenCount': 2, 'romanizedWordCount': 2, 'words': words})
            forced = {'formatVersion': 5, 'engine': 'torchaudio-mms-fa', **mms_timeline(1000),
                'segmentCount': 1, 'segmentBoundaryGuardMs': 250, 'displayWordCount': 4,
                'acousticTokenCount': 4, 'romanizedWordCount': 4, 'lineCount': 2,
                'confidence': {key: .02 for key in ('wordP10','wordP50','wordP90','lineP10','lineP50','lineP90')},
                'lines': lines}
            if missing_end:
                forced['lines'][1]['words'][1].pop('endMs')
            atomic_write_json(kwargs['output_path'], forced)
            return SimpleNamespace(returncode=0, stdout='', stderr=''), kwargs['request'], None
        def dispatch(command, **_options):
            self.assertEqual(command[1:4], ['-m', 'stemwise_engine.lyrics_align_pc', '--config'])
            self.assertEqual(command[5], 'owner-word-prediction')
            stdout, stderr = io.StringIO(), io.StringIO()
            with redirect_stdout(stdout), redirect_stderr(stderr):
                code = worker.main(command[3:])
            return subprocess.CompletedProcess(command, code, stdout.getvalue(), stderr.getvalue())
        with ExitStack() as stack:
            stack.enter_context(patch.object(FutureSongWorkflow, '_alignment_worker_environment', return_value={}))
            stack.enter_context(patch.object(worker, 'PINNED_MMS_SHA256', sha256_file(model)))
            stack.enter_context(patch.object(worker, '_run_numerical_process', return_value=
                SimpleNamespace(returncode=0, stdout=json.dumps(runtime), stderr='')))
            boundary = stack.enter_context(patch.object(worker, '_run_alignment_worker_batch', side_effect=numerical))
            dispatch_mock = stack.enter_context(patch('stemwise_engine.future_song.run_process_tree', side_effect=dispatch))
            yield boundary, dispatch_mock

    def test_owner_verified_prediction_public_validate_finalize_and_passive_revocation(self):
        import json
        from stemwise_engine.original_delivery import read_bundle
        from stemwise_engine.editorial_admission import available_lyrics
        draft, review, original, proof = self.owner_prediction_fixture(required_companions=True)
        source_bytes = self.audio.read_bytes()
        with self.owner_prediction_model_boundary() as (numerical, dispatch):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            job = self.workflow._load_job(self.video)
            self.assertNotIn('displayReview', job)
            self.assertEqual(job['ownerWordPrediction']['status'], 'owner-predicted-words')
            self.assertEqual(job['validation']['capabilities']['timing'], 'estimated')
            packet = json.loads((draft.parent/'companion-review-inputs.json').read_bytes())
            self.assertEqual(packet['contentEvidence']['ownerVerification'],
                job['validation']['capabilities']['contentAdmission']['ownerVerification'])
            document, _ = read_child(self.child, self.pipeline.config)
            self.assertEqual(document['lyrics']['status'], 'complete')
            self.assertEqual([track['format'] for track in document['lyrics']['tracks']], ['regional']*3)
            words = [word for line in document['lyrics']['tracks'][0]['lines'] for word in line['words']]
            self.assertEqual([(word['timing']['startMs'], word['timing']['endMs']) for word in words],
                [(100, 250), (300, 450), (500, 650), (700, 900)])
            self.assertTrue(all(word['timing']['status'] == 'estimated' for word in words))
            self.assertEqual(self.audio.read_bytes(), source_bytes)
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
            destination = self.workflow.ready/self.child.name
            public_bytes = destination.read_bytes()
            published, _, identity = read_bundle(destination, self.pipeline.config)
            self.assertEqual(published['lyrics'], document['lyrics'])
            self.assertFalse(self.audio.exists())
            from stemwise_engine.metadata_research import metadata_sha256
            review['metadataReview'].update(originalMetadataSha256=metadata_sha256(published['metadata']),
                title='Owner-reviewed metadata renewal')
            atomic_write_json(draft.parent/'agent-review.json', review)
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertEqual(destination.read_bytes(), public_bytes)
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
            renewed, _, renewed_identity = read_bundle(destination, self.pipeline.config)
            self.assertEqual(renewed['metadata']['title'], 'Owner-reviewed metadata renewal')
            self.assertEqual((renewed['lyrics'], renewed_identity), (published['lyrics'], identity))
            self.assertEqual((numerical.call_count, dispatch.call_count), (1, 1))
            public_bytes = destination.read_bytes()
            with patch('stemwise_engine.lyrics_align_pc._run_numerical_process', side_effect=AssertionError('passive runtime')):
                current = self.workflow._load_job(self.video)
                self.assertTrue(self.workflow._validated_snapshot_is_current(current, require_delivery=True))
                self.assertEqual(self.workflow.status(self.video, verify_delivery=True)['state'], 'DELIVERED')
                with patch('stemwise_engine.original_delivery.read_bundle', side_effect=AssertionError('fast media read')), \
                        patch('stemwise_engine.lyrics_align_pc.audio_store.file_identity', side_effect=AssertionError('fast PCM probe')):
                    self.assertEqual(self.workflow.status(self.video, verify_delivery=False)['state'], 'DELIVERED')
                proof['rationale'] += ' Clarified explicit owner review.'
                atomic_write_json(draft.parent/'owner-verified-lyrics.json', proof)
                self.assertFalse(self.workflow._validated_snapshot_is_current(current, require_delivery=True))
                self.assertEqual(self.workflow.status(self.video, verify_delivery=False)['state'], 'STATUS_ERROR')
                self.assertEqual(self.workflow.status(self.video, verify_delivery=True)['state'], 'STATUS_ERROR')
                with self.assertRaises(FutureSongError):
                    self.workflow.finalize(self.video)
            self.assertEqual(destination.read_bytes(), public_bytes)
            self.assertEqual(numerical.call_count, 1)
            self.assertEqual(dispatch.call_count, 1)
            from stemwise_engine.workflow_records import WorkflowInputError
            with self.assertRaises(WorkflowInputError) as refused:
                self.workflow.validate(self.video)
            self.assertEqual(refused.exception.failure_details['reasonCode'], 'review-stale')
            self.assertEqual((numerical.call_count, dispatch.call_count), (1, 2), 'Exact numerical cache must survive owner rationale changes')
            self.assertEqual(destination.read_bytes(), public_bytes)
            renewed_job = self.workflow._load_job(self.video)
            self.assertNotEqual(renewed_job['ownerWordPrediction']['admission'], job['ownerWordPrediction']['admission'])
            for kind in ('candidate', 'mapping', 'forced'):
                self.assertEqual(renewed_job['ownerWordPrediction'][kind], job['ownerWordPrediction'][kind])
            self.approve_current_companions(draft, review)
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
            final, _, final_identity = read_bundle(destination, self.pipeline.config)
            self.assertEqual((final['lyrics'], final_identity), (renewed['lyrics'], renewed_identity))
            self.assertEqual((numerical.call_count, dispatch.call_count), (1, 2))
            self.assertFalse(self.audio.exists())
            for kind in ('candidate', 'mapping', 'forced', 'admission'):
                ref = job['ownerWordPrediction'][kind]
                self.assertEqual(sha256_file(self.workflow._alignment_state_path(self.video).parent/ref['path']), ref['sha256'])

    def test_owner_prediction_incomplete_words_and_terminal_stops_never_publish(self):
        from workflow_fixture import nested_case
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        for terminal in (ProviderCancelled('stop'), ProviderDeadline('stop'), WorkerRetirementError('stop'),
                SongAttemptBlocked('unknown accepted work')):
            with self.subTest(terminal=type(terminal).__name__):
                case = nested_case(self, AvailableCompletionTests)
                case.owner_prediction_fixture()
                before = case.child.read_bytes(), case.audio.read_bytes()
                with patch.object(case.workflow, '_validate_action_normal', side_effect=terminal), \
                        patch.object(case.workflow, '_run_primary_worker') as dispatch:
                    with self.assertRaises(type(terminal)) as raised:
                        case.workflow.validate(case.video)
                self.assertIs(raised.exception, terminal)
                dispatch.assert_not_called()
                self.assertEqual((case.child.read_bytes(), case.audio.read_bytes()), before)
                self.assertFalse((case.workflow.ready/case.child.name).exists())
        self.owner_prediction_fixture()
        before = self.child.read_bytes(), self.audio.read_bytes()
        with self.owner_prediction_model_boundary(missing_end=True) as (numerical, _):
            with self.assertRaises(Exception) as raised:
                self.workflow.validate(self.video)
        from stemwise_engine.worker_protocol import WorkerProtocolError
        self.assertIsInstance(raised.exception, WorkerProtocolError)
        self.assertEqual(numerical.call_count, 1)
        self.assertNotIn('ownerWordPrediction', self.workflow._load_job(self.video))
        self.assertEqual((self.child.read_bytes(), self.audio.read_bytes()), before)
        self.assertFalse((self.workflow.ready/self.child.name).exists())

    def test_owner_prediction_restores_only_exact_selected_native_pcm(self):
        from workflow_fixture import nested_case, initialized_workflow
        from test_recording_workflow import RecordingWorkflowTest
        from stemwise_engine.recording_workflow import RecordingWorkflow
        import stemwise_engine.youtube as youtube
        native = nested_case(self, RecordingWorkflowTest)
        base = initialized_workflow(native.workspace)
        workflow = RecordingWorkflow(base.config_path, 'qobuz:11')
        job = workflow._base_job(workflow.video_id, 'https://youtu.be/'+workflow.video_id)
        source_bytes = native.audio.read_bytes()
        reference = youtube.decode_analysis_audio(native.audio, native.workspace/'reference-pcm', timeout_seconds=10)
        audio = {'sha256': sha256_file(reference), 'byteCount': reference.stat().st_size, 'durationMs': 1000}
        with patch('stemwise_engine.original_delivery.extract_original', side_effect=AssertionError('native enrichment ZIP has no Original')):
            with workflow._owner_prediction_audio(job, audio) as (pcm, root):
                self.assertEqual(pcm, root/workflow.video_id/'analysis.wav')
                self.assertEqual(pcm.read_bytes(), reference.read_bytes())
                restored = root
            self.assertFalse(restored.exists())
            with self.assertRaisesRegex(FutureSongError, 'cannot reproduce the bound analysis PCM'):
                with workflow._owner_prediction_audio(job, {**audio, 'sha256': '0'*64}):
                    self.fail('changed PCM cannot reach prediction')
            decode = youtube.decode_analysis_audio
            def replace_during_decode(*args, **kwargs):
                pcm = decode(*args, **kwargs)
                native.audio.write_bytes(source_bytes+b'changed native source')
                return pcm
            try:
                with patch.object(youtube, 'decode_analysis_audio', side_effect=replace_during_decode):
                    with self.assertRaisesRegex(FutureSongError, 'Selected native recording changed during'):
                        with workflow._owner_prediction_audio(job, audio):
                            self.fail('changed source cannot reach prediction')
            finally:
                native.audio.write_bytes(source_bytes)
        self.assertEqual(native.audio.read_bytes(), source_bytes)
        self.assertFalse(list(workflow.analysis_temp.glob('owner-prediction-*')))

    @contextmanager
    def candidate_model_boundary(self):
        """Exercise real acquisition/ledger/publication with a known numerical result."""
        import json
        import subprocess
        from lyric_content_fixtures import write_recognition
        def worker(command, **_kwargs):
            self.assertEqual(command[1:4], ['-m', 'stemwise_engine.recording_evidence', '--candidate'])
            candidate_root, video, audio, expected_sha = Path(command[-4]), command[-3], Path(command[-2]), command[-1]
            self.assertEqual(sha256_file(audio), expected_sha)
            cache = write_recognition(candidate_root/'lyrics-alignment/jobs'/video, 'heard words', (0,), expected_sha)
            return subprocess.CompletedProcess(command, 0,
                json.dumps({'recognition': {'path': cache.name, 'sha256': sha256_file(cache)}}), '')
        with patch.object(FutureSongWorkflow, '_alignment_worker_environment', return_value={}), \
                patch('stemwise_engine.future_song.run_process_tree', side_effect=worker) as dispatch:
            yield dispatch

    def test_capability_backup_and_rollback_use_artifact_limits(self):
        import json
        import zipfile
        from stemwise_engine.contract import MAX_JSON_BYTES
        self.workflow.report_store.ensure_report_identity(self.video)
        paths = self.workflow._capability_validation_paths(self.video)
        large_json = json.dumps({'value': 'x'*64000}).encode('utf-8')
        with zipfile.ZipFile(paths[0], 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('song.json', large_json)
        for path in paths[1:]:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(large_json)
        before = {path: path.read_bytes() for path in paths}
        self.assertLess(len(before[paths[0]]), 4096)
        self.assertGreater(len(large_json), 4096)
        self.workflow.config['childZipMaxBytes'] = 4096
        with patch.object(self.workflow, '_validate_available_capabilities_action', return_value={'state': 'VALIDATED'}):
            self.assertEqual(self.workflow._validate_available_capabilities({'videoId': self.video})['state'], 'VALIDATED')
        with patch.object(self.workflow, '_validate_available_capabilities_action', side_effect=RuntimeError('interrupted')), \
                patch.object(self.workflow, '_recover_capability_validation'):
            with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                self.workflow._validate_available_capabilities({'videoId': self.video})
        for path in paths:
            path.write_bytes(b'replacement')
        with patch('stemwise_engine.pipeline.StemwisePipeline._recover_analysis_replacement'):
            self.workflow.config['childZipMaxBytes'] = 1
            with self.assertRaises(ValueError):
                self.workflow._recover_capability_validation(self.video)
            self.assertEqual({path: path.read_bytes() for path in paths}, dict.fromkeys(paths, b'replacement'))
            self.workflow.config['childZipMaxBytes'] = 2048
            self.workflow._recover_capability_validation(self.video)
        self.assertEqual({path: path.read_bytes() for path in paths}, before)
        for index, limit in ((0, 2048), (1, MAX_JSON_BYTES)):
            with self.subTest(oversize_artifact=index):
                paths[index].write_bytes(b'x'*(limit+1))
                baseline = {path: path.read_bytes() for path in paths}
                with patch.object(self.workflow, '_validate_available_capabilities_action') as action:
                    with self.assertRaises(ValueError):
                        self.workflow._validate_available_capabilities({'videoId': self.video})
                    action.assert_not_called()
                self.assertEqual({path: path.read_bytes() for path in paths}, baseline)
                paths[index].write_bytes(before[paths[index]])
                self.workflow._recover_capability_validation(self.video)

    def test_interrupted_native_display_recovery_preserves_reviewed_metadata(self):
        import json
        from workflow_fixture import nested_case, initialized_workflow
        from test_recording_workflow import RecordingWorkflowTest
        from stemwise_engine.recording_workflow import RecordingWorkflow
        from stemwise_engine.metadata_research import metadata_sha256
        from stemwise_engine.youtube import decode_analysis_audio
        from stemwise_engine.packages import read_child
        native = nested_case(self, RecordingWorkflowTest)
        native_raw = native.audio.read_bytes()
        catalogue_path = native.library/'catalogue.json'
        catalogue = json.loads(catalogue_path.read_bytes())
        catalogue['ownerTrackMetadata'] = {'11': {'title': 'Native', 'artist': 'Artist', 'album': 'Catalogue album'}}
        atomic_write_json(catalogue_path, catalogue)
        selection_path = native.library/'comparison-results/library-files.json'
        selected = json.loads(selection_path.read_bytes())
        selected['catalogueSha256'] = sha256_file(catalogue_path)
        atomic_write_json(selection_path, selected)
        self.root = native.workspace
        base = initialized_workflow(self.root)
        self.workflow = RecordingWorkflow(base.config_path, 'qobuz:11')
        self.video = self.workflow.video_id
        self.pipeline = self.workflow._pipeline()
        job = self.workflow._base_job(self.video, f'https://youtu.be/{self.video}')
        self.workflow._save_job(job)
        job['state'] = 'WAITING_FOR_EDITORIAL'
        self.workflow._save_job(job)
        self.child = self.pipeline.child_path(self.video)
        cover = self.root/'native-cover.png'
        cover.write_bytes(PNG_1X1)
        document = song_document(self.video)
        document['generator']['version'] = self.workflow.config['generatorVersion']
        document['source'] = self.pipeline._source_identity(self.video)
        create_child_zip(self.child, document, cover, self.pipeline.config)
        temporary = self.workflow.analysis_temp/self.video
        create_manifested_directory(temporary.parent, temporary,
            owner={'kind': 'analysis-temporary', 'videoId': self.video}, workspace_root=self.root)
        self.audio = decode_analysis_audio(native.audio, temporary, timeout_seconds=10)
        state = self.pipeline._new_state({'videoId': self.video}, 'absent')
        state.update(status='complete', childSha256=sha256_file(self.child),
            originalAudio={'sha256': sha256_file(self.audio), 'byteCount': self.audio.stat().st_size,
                'durationMs': 1000, 'retained': True})
        self.pipeline.save_checkpoint(state)
        draft, review, original, _ = self.companion_publication_fixture()
        document, _ = read_child(self.child, self.pipeline.config)
        review['metadataReview'] = {'formatVersion': 1, 'videoId': self.video,
            'originalMetadataSha256': metadata_sha256(document['metadata']),
            'sourcesSha256': sha256_file(draft.parent/'sources.json'), 'title': 'Reviewed native title',
            'artists': ['Reviewed native artist'], 'evidenceUrls': [self.workflow._artwork_source_url(self.video)],
            'recordingIdentityConfirmed': True}
        self.approve_current_companions(draft, review)
        job = self.workflow._load_job(self.video)
        self.workflow._apply_metadata_review(job, review, json.loads((draft.parent/'sources.json').read_bytes()), self.pipeline)
        pointer = job['displayReview']
        alignment_state = json.loads(self.workflow._alignment_state_path(self.video).read_bytes())
        run = json.loads((self.workflow.report_store.report_root(self.video)/'runs'/pointer['alignmentRunId']/'run.json').read_bytes())
        alignment_state['attemptId'] = 'b'*32
        atomic_write_json(self.workflow._alignment_state_path(self.video), alignment_state)
        artifacts = {**run['artifacts'], 'alignmentStateSha256': sha256_file(self.workflow._alignment_state_path(self.video))}
        run_sha = self.workflow.report_store.commit_alignment_run(job, run_id='b'*32,
            editorial_sha256=self.workflow._editorial_sha(job), state=alignment_state, status='complete', artifacts=artifacts)
        job['artworkReview'] = {'result': 'accepted', 'sourceVideoId': self.video,
            'sourceUrl': self.workflow._artwork_source_url(self.video), 'artworkSha256': hashlib.sha256(PNG_1X1).hexdigest(),
            'reviewedAt': '2026-11-05T00:00:00Z'}
        self.workflow.review_construction.create_display_review_attempt(job, original_text=original['text'],
            editorial_sha256=self.workflow._editorial_sha(job),
            analysis_audio=self.workflow._completed_analysis_audio(self.pipeline, self.video),
            alignment_state=alignment_state, alignment_run_sha256=run_sha, alignment_artifacts=artifacts)
        save = self.workflow._save_job
        def interrupt_after_display_save(value):
            save(value)
            raise KeyboardInterrupt('interrupted after persisted display attempt')
        with patch.object(self.workflow, '_save_job', side_effect=interrupt_after_display_save):
            with self.assertRaises(KeyboardInterrupt):
                self.workflow._save_job(job)
        before, _ = read_child(self.child, self.pipeline.config)
        pcm = self.audio.read_bytes()
        self.workflow = RecordingWorkflow(base.config_path, 'qobuz:11')
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('must reuse alignment')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        after, _ = read_child(self.child, self.pipeline.config)
        self.assertEqual(after['metadata'], before['metadata'])
        self.assertEqual(after['analysis'], before['analysis'])
        self.assertEqual(self.audio.read_bytes(), pcm)
        review_path = draft.parent/'agent-review.json'
        approved = review_path.read_bytes()
        review['metadataReview']['sourcesSha256'] = '0'*64
        atomic_write_json(review_path, review)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertFalse((self.workflow.ready/self.child.name).exists())
        self.assertEqual(read_child(self.child, self.pipeline.config)[0]['metadata'], before['metadata'])
        review_path.write_bytes(approved)
        with patch('stemwise_engine.native_delivery.publish', side_effect=OSError('phone publication interrupted')):
            with self.assertRaisesRegex(OSError, 'phone publication interrupted'):
                self.workflow.finalize(self.video)
        self.workflow.finalize(self.video)
        published, _ = read_child(self.workflow.ready/self.child.name, self.pipeline.config)
        self.assertEqual(published['metadata'], before['metadata'])
        self.assertEqual(published['analysis'], before['analysis'])
        self.assertEqual(native.audio.read_bytes(), native_raw)
        public_raw = (self.workflow.ready/self.child.name).read_bytes()
        atomic_write_json(review_path, review)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertEqual((self.workflow.ready/self.child.name).read_bytes(), public_raw)
        review_path.write_bytes(approved)
        self.assertEqual(self.workflow.validate(self.video)['state'], 'DELIVERED')
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        self.assertEqual((self.workflow.ready/self.child.name).read_bytes(), public_raw)

    def test_public_new_retry_reacquires_after_pre_download_failure(self):
        import stemwise_engine.youtube as youtube
        import stemwise_engine.source_acquisition as acquisition
        from workflow_fixture import initialized_workflow
        from test_pipeline_hardening import create_cover, youtube_metadata
        workflow = initialized_workflow(self.root/'fresh')
        wav, original = self.audio.read_bytes(), (self.audio.parent/'source.flac').read_bytes()
        calls = []
        def download(video, temporary, **kwargs):
            calls.append(video)
            if len(calls) <= 2:
                (temporary/'incomplete.partial').write_bytes(b'incomplete')
                raise TimeoutError('controlled acquisition interruption')
            (temporary/'source.flac').write_bytes(original)
            (temporary/'analysis.wav').write_bytes(wav)
            atomic_write_json(temporary/'original-source.json', {'videoId': video, 'file': 'source.flac',
                'sha256': sha256_file(temporary/'source.flac'), 'byteCount': len(original)})
            return {'id': video}, temporary/'analysis.wav'
        with patch.object(youtube, 'fetch_video_info', return_value={'id': self.video, 'duration': 1.0}), \
                patch.object(youtube, 'metadata_from_info', return_value=youtube_metadata(self.video)), \
                patch.object(youtube, 'create_cover_png', side_effect=create_cover), \
                patch.object(youtube, 'download_analysis_audio', side_effect=download), \
                patch('stemwise_engine.pipeline.StemwisePipeline._analysis', return_value=song_document(self.video)['analysis']), \
                patch('stemwise_engine.pipeline.enrich_metadata', side_effect=lambda value,*a,**kw:(value,{})), \
                patch.object(acquisition, 'collect_normal_youtube_candidates', return_value={'records': [], 'usableLyricCandidateCount': 0}), \
                patch.object(acquisition, 'acquire_exact_item_draft', return_value={'decision': 'not-found'}), \
                patch.object(acquisition, 'discover_counterpart_candidates', return_value={}), \
                self.candidate_model_boundary() as candidate_worker:
            with self.assertRaisesRegex(FutureSongError, 'PC analysis did not complete'):
                workflow.new(f'https://youtu.be/{self.video}')
            workflow = FutureSongWorkflow(workflow.config_path)
            with self.assertRaisesRegex(FutureSongError, 'PC analysis did not complete'):
                workflow.retry(self.video)
            workflow = FutureSongWorkflow(workflow.config_path)
            result = workflow.retry(self.video)
            self.assertEqual(result['state'], 'NEEDS_LYRICS')
        candidate_worker.assert_called_once()
        self.assertEqual(len(calls), 3)
        document, cover = read_child(workflow._pipeline().child_path(self.video), workflow._pipeline().config)
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(document['analysis'], song_document(self.video)['analysis'])
        self.assertFalse(list(workflow.analysis_temp.rglob('incomplete.partial')))

    def test_public_retry_after_interruption_before_prepared_input_checkpoint(self):
        self._retry_interrupted_prepared_inputs('artwork')

    def test_public_retry_after_interruption_before_refreshed_producer_checkpoint(self):
        self._retry_interrupted_prepared_inputs('analysis')

    def _retry_interrupted_prepared_inputs(self, boundary):
        import json
        import sys
        import stemwise_engine.youtube as youtube
        import stemwise_engine.source_acquisition as acquisition
        from stemwise_engine.common import run_process_tree
        from test_pipeline_hardening import create_cover, youtube_metadata
        job = self.workflow._load_job(self.video)
        job['state'] = 'ANALYZING'
        self.workflow._save_job(job)
        self.child.unlink()
        self.pipeline.checkpoint_path(self.video).unlink()
        script = '''import os,sys
from pathlib import Path
from unittest.mock import patch
from stemwise_engine.future_song import FutureSongWorkflow
from test_packages import song_document
from test_pipeline_hardening import create_cover, youtube_metadata
import stemwise_engine.pipeline as module
import stemwise_engine.youtube as youtube
config, video, audio, boundary = sys.argv[1:]
analyzed = False
def producer(**_):
 return {'fixture': 'no-model-analysis' if analyzed else 'before-model-asset-discovery'}
with patch.object(module, 'collect_provenance', side_effect=producer):
 workflow = FutureSongWorkflow(Path(config))
 owner = workflow._pipeline()
 save = owner.save_checkpoint
 def checkpoint(state):
  if state.get('stages', {}).get(boundary, {}).get('status') == 'complete':
   os._exit(37)
  return save(state)
 def numerics(*_):
  global analyzed
  analyzed = True
  owner._provenance_needs_refresh = True
  return song_document(video)['analysis']
 with patch.object(youtube, 'fetch_video_info', return_value={'id': video, 'duration': 1.0}), \
      patch.object(youtube, 'metadata_from_info', return_value=youtube_metadata(video)), \
      patch.object(youtube, 'create_cover_png', side_effect=create_cover), \
      patch.object(youtube, 'download_analysis_audio', return_value=({'id': video}, Path(audio))), \
      patch.object(module, 'enrich_metadata', side_effect=lambda value,*a,**kw:(value,{})), \
      patch.object(owner, '_analysis', side_effect=numerics), \
      patch.object(owner, 'save_checkpoint', side_effect=checkpoint):
  owner.process_song({'videoId': video})
raise AssertionError('interruption boundary was not reached')
'''
        interrupted = run_process_tree([sys.executable, '-B', '-c', script,
            str(self.workflow.config_path), self.video, str(self.audio), boundary],
            timeout=60, text=True, capture_output=True,
            env={**os.environ, 'PYTHONPATH': os.pathsep.join(filter(None,
                (str(ROOT/'Test suite/pc/unit'), os.environ.get('PYTHONPATH'))))})
        self.assertEqual(interrupted.returncode, 37, interrupted.stderr)
        self.assertTrue(self.audio.is_file())
        if boundary == 'analysis':
            phase = json.loads((self.audio.parent/'initial-phase.json').read_bytes())
            self.assertEqual(phase['analysis'], song_document(self.video)['analysis'])
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(youtube, 'fetch_video_info',
                    side_effect=OSError('provider unavailable') if boundary == 'analysis' else None,
                    return_value={'id': self.video, 'duration': 1.0}), \
                patch.object(youtube, 'metadata_from_info', return_value=youtube_metadata(self.video)), \
                patch.object(youtube, 'create_cover_png',
                    side_effect=OSError('artwork unavailable') if boundary == 'analysis' else create_cover), \
                patch.object(youtube, 'download_analysis_audio',
                    side_effect=AssertionError('must reuse decoded Original') if boundary == 'analysis' else None,
                    return_value=({'id': self.video}, self.audio)), \
                patch('stemwise_engine.pipeline.StemwisePipeline._analysis',
                    side_effect=AssertionError('must reuse numerical result') if boundary == 'analysis' else None,
                    return_value=song_document(self.video)['analysis']), \
                patch('stemwise_engine.pipeline.enrich_metadata', side_effect=lambda value, *a, **kw: (value, {})), \
                patch.object(acquisition, 'acquire_exact_item_draft', return_value={'decision': 'not-found'}), \
                patch.object(acquisition, 'discover_counterpart_candidates', return_value={}), \
                self.candidate_model_boundary() as candidate_worker:
            self.assertEqual(self.workflow.retry(self.video)['state'], 'NEEDS_LYRICS')
        candidate_worker.assert_called_once()
        document, cover = read_child(self.child, self.pipeline.config)
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(document['analysis'], song_document(self.video)['analysis'])

    def test_public_retry_reuses_prepared_artwork_and_completed_analysis_without_providers(self):
        import stemwise_engine.youtube as youtube
        import stemwise_engine.source_acquisition as acquisition
        from test_pipeline_hardening import create_cover, youtube_metadata
        job = self.workflow._load_job(self.video)
        job['state'] = 'ANALYZING'
        self.workflow._save_job(job)
        self.child.unlink()
        self.pipeline.checkpoint_path(self.video).unlink()
        with patch.object(youtube, 'fetch_video_info', return_value={'id': self.video, 'duration': 1.0}), \
                patch.object(youtube, 'metadata_from_info', return_value=youtube_metadata(self.video)), \
                patch.object(youtube, 'create_cover_png', side_effect=create_cover), \
                patch.object(youtube, 'download_analysis_audio', return_value=({'id': self.video}, self.audio)), \
                patch('stemwise_engine.pipeline.enrich_metadata', side_effect=lambda value, *a, **kw: (value, {})), \
                patch.object(self.pipeline, '_analysis', return_value=song_document(self.video)['analysis']), \
                patch('stemwise_engine.pipeline.create_child_zip', side_effect=OSError('first package write')):
            self.assertEqual(self.pipeline.process_song({'videoId': self.video}), 'failed')
        job = self.workflow._load_job(self.video)
        job.update(state='ANALYSIS_FAILED', error='OSError')
        self.workflow._save_job(job)
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(youtube, 'fetch_video_info', side_effect=OSError('metadata unavailable')), \
                patch.object(youtube, 'create_cover_png', side_effect=OSError('artwork unavailable')), \
                patch.object(youtube, 'download_analysis_audio', side_effect=AssertionError('must reuse Original')), \
                patch('stemwise_engine.pipeline.StemwisePipeline._analysis', side_effect=AssertionError('must reuse analysis')), \
                patch('stemwise_engine.pipeline.enrich_metadata', side_effect=lambda value, *a, **kw: (value, {})), \
                patch.object(acquisition, 'acquire_exact_item_draft', return_value={'decision': 'not-found'}), \
                patch.object(acquisition, 'discover_counterpart_candidates', return_value={}), \
                self.candidate_model_boundary() as candidate_worker:
            result = self.workflow.retry(self.video)
        candidate_worker.assert_called_once()
        self.assertEqual(result['state'], 'NEEDS_LYRICS')
        document, cover = read_child(self.child, self.pipeline.config)
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(document['analysis'], song_document(self.video)['analysis'])
        prepared_cover = self.audio.parent/'cover.png'
        prepared_cover.write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'Prepared artwork changed'):
            self.workflow._song_info(self.video)

    def test_public_retry_preserves_receipted_original_after_first_decode_failure(self):
        import json
        import subprocess
        import stemwise_engine.youtube as youtube
        import stemwise_engine.source_acquisition as acquisition
        import stemwise_media.quick_batch as media
        from test_pipeline_hardening import create_cover, youtube_metadata
        job = self.workflow._load_job(self.video)
        job['state'] = 'ANALYZING'
        self.workflow._save_job(job)
        compressed = (self.audio.parent/'source.flac').read_bytes()
        self.child.unlink()
        self.pipeline.checkpoint_path(self.video).unlink()
        for name in ('source.flac', 'analysis.wav', 'original-source.json'):
            (self.audio.parent/name).unlink()
        process = youtube.run_process_tree
        decode = youtube.decode_analysis_audio
        def download(command, **kwargs):
            if '_download-attempt' not in command:
                return process(command, **kwargs)
            (self.audio.parent/'source.flac').write_bytes(compressed)
            atomic_write_json(self.audio.parent/'download-info.json', {'id': self.video})
            return subprocess.CompletedProcess(command, 0, '', '')
        with patch.object(media, 'source_file', return_value=None), \
                patch.object(media, 'require_youtube_audio_allowed'), \
                patch.object(youtube, 'fetch_video_info', return_value={'id': self.video, 'duration': 1.0}), \
                patch.object(youtube, 'metadata_from_info', return_value=youtube_metadata(self.video)), \
                patch.object(youtube, 'create_cover_png', side_effect=create_cover), \
                patch.object(youtube, 'run_process_tree', side_effect=download) as downloads, \
                patch.object(youtube, 'decode_analysis_audio', side_effect=OSError('first decode')):
            self.assertEqual(self.pipeline.process_song({'videoId': self.video}), 'failed')
        self.assertTrue((self.audio.parent/'download-request.json').is_file())
        self.assertEqual(youtube.completed_analysis_download(self.video, self.audio.parent)[0].read_bytes(), compressed)
        job = self.workflow._load_job(self.video)
        job.update(state='ANALYSIS_FAILED', error='OSError')
        self.workflow._save_job(job)
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(media, 'source_file', return_value=None), \
                patch.object(media, 'require_youtube_audio_allowed'), \
                patch.object(youtube, 'fetch_video_info', side_effect=OSError('provider unavailable')), \
                patch.object(youtube, 'create_cover_png', side_effect=OSError('artwork unavailable')), \
                patch.object(youtube, 'run_process_tree', side_effect=lambda command, **kw: process(command, **kw)
                    if '_download-attempt' not in command else self.fail('completed Original was downloaded twice')), \
                patch('stemwise_engine.pipeline.StemwisePipeline._analysis', return_value=song_document(self.video)['analysis']), \
                patch('stemwise_engine.pipeline.enrich_metadata', side_effect=lambda value, *a, **kw: (value, {})), \
                patch.object(acquisition, 'acquire_exact_item_draft', return_value={'decision': 'not-found'}), \
                patch.object(acquisition, 'discover_counterpart_candidates', return_value={}), \
                self.candidate_model_boundary() as candidate_worker:
            self.assertEqual(self.workflow.retry(self.video)['state'], 'NEEDS_LYRICS')
        candidate_worker.assert_called_once()
        self.assertEqual((self.audio.parent/'source.flac').read_bytes(), compressed)
        self.assertEqual(downloads.call_count, 1)

    def test_public_validate_recovers_abrupt_primary_exit_without_deleting_outputs(self):
        import os
        import subprocess
        ledger = self.workflow._song_ledger(self.video)
        code = ('import sys,os; sys.path.insert(0,sys.argv[1]); from stemwise_engine.alignment_attempt import SongAttemptLedger; ledger=SongAttemptLedger(sys.argv[2]); active=ledger.active(); active.__enter__(); primary=ledger.primary("alignment",{"draft":"old"}); primary.__enter__(); os._exit(19)')
        result = subprocess.run([sys.executable, '-B', '-c', code,
            str(ROOT/'PC side/stemwise-playlist-builder/src'), str(ledger.path)], timeout=10)
        self.assertEqual(result.returncode, 19)
        before = self.audio.read_bytes()
        with self.assertRaisesRegex(FutureSongError, r'^validation failed \(ValueError\)$') as refused:
            self.workflow.validate(self.video)
        self.assertIs(type(refused.exception.__cause__), ValueError)
        from stemwise_engine.workflow_records import WorkflowInputError
        fallback = refused.exception.__context__
        self.assertIs(type(fallback), FutureSongError)
        self.assertEqual(str(fallback), 'lyrics.meta.json is missing; editorial work is incomplete')
        self.assertIsInstance(fallback.__context__, WorkflowInputError)
        self.assertEqual(fallback.__context__.failure_details['reasonCode'], 'lyrics-content-unresolved')
        ledger.refresh()
        self.assertEqual(ledger.record['generation'], 2)
        self.assertEqual(ledger.record['generations'][0]['status'], 'abandoned')
        self.assertEqual(ledger.record['generations'][0]['primaryActions']['alignment']['status'], 'unknown')
        self.assertEqual(self.audio.read_bytes(), before)

    def test_public_validate_runs_corrected_inputs_in_a_new_primary_generation(self):
        ledger = self.workflow._song_ledger(self.video)
        draft = self.workflow._job_dir(self.video)/'drafts'
        draft.mkdir()
        original = draft/'original.txt'
        original.write_text('old wording', encoding='utf-8')
        with ledger.primary('alignment', {'draft': 'old wording'}):
            pass
        original.write_text('corrected wording', encoding='utf-8')
        def enhancement(video_id):
            self.workflow._run_primary_worker('alignment', {'draft': original.read_text(encoding='utf-8')},
                ['substituted-model-boundary'])
            return self.workflow._validate_available_capabilities(self.workflow._load_job(video_id))
        with patch.object(self.workflow, '_validate_enhancements_action', side_effect=enhancement), \
                patch('stemwise_engine.future_song.run_process_tree') as worker:
            with self.assertRaisesRegex(FutureSongError, '^lyrics.meta.json is missing; editorial work is incomplete$'):
                self.workflow.validate(self.video)
        worker.assert_called_once()
        ledger.refresh()
        self.assertEqual(ledger.record['generation'], 2)
        self.assertNotEqual(ledger.record['primaryActions']['alignment']['requestSha256'],
            ledger.record['generations'][0]['primaryActions']['alignment']['requestSha256'])
        self.assertEqual(original.read_text(encoding='utf-8'), 'corrected wording')

    def setUp(self):
        import numpy as np
        import soundfile as sf
        runtime = patch.dict(os.environ, {'STEMWISE_ALLIN1_PYTHON': ''})
        runtime.start()
        self.addCleanup(runtime.stop)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # The numerical producer is an unavailable external boundary in this CPU fixture.
        producer = {'fixture': 'no-model-analysis'}
        self.producer = patch('stemwise_engine.pipeline.collect_provenance', return_value=producer)
        self.producer.start()
        self.addCleanup(self.producer.stop)
        # Source recovery remains real; only its external search is inert here.
        discovery = patch('stemwise_engine.source_acquisition.discover_counterpart_candidates',
            side_effect=lambda *_args, **_kwargs: {'candidates': [], 'recommendedVideoId': None,
                'searchStatus': 'complete', 'surfaceOutcomes': {
                    surface: {'status': 'successful-empty'} for surface in ('songs', 'videos')}})
        discovery.start()
        self.addCleanup(discovery.stop)
        from workflow_fixture import initialized_workflow
        self.workflow = initialized_workflow(self.root)
        self.video = 'AAAAAAAAAAA'
        job = self.workflow._base_job(self.video, f'https://youtu.be/{self.video}')
        self.workflow._save_job(job)
        job['state'] = 'WAITING_FOR_EDITORIAL'
        self.workflow._save_job(job)
        self.pipeline = self.workflow._pipeline()
        self.child = self.pipeline.child_path(self.video)
        self.child.parent.mkdir(parents=True, exist_ok=True)
        cover = self.root/'cover.png'
        cover.write_bytes(PNG_1X1)
        document = song_document(self.video)
        document['generator']['version'] = self.workflow.config['generatorVersion']
        create_child_zip(self.child, document, cover, self.pipeline.config)
        temporary = self.workflow.analysis_temp/self.video
        temporary.parent.mkdir(parents=True, exist_ok=True)
        create_manifested_directory(temporary.parent, temporary,
            owner={'kind': 'analysis-temporary', 'videoId': self.video}, workspace_root=self.root)
        samples = np.sin(np.arange(48000)/41.).astype('float32')*.1
        original = temporary/'source.flac'
        sf.write(original, np.column_stack((samples, -samples)), 48000, subtype='PCM_24')
        from stemwise_engine.youtube import decode_analysis_audio
        self.audio = decode_analysis_audio(original, temporary, timeout_seconds=10)
        atomic_write_json(temporary/'original-source.json', {'videoId': self.video, 'file': original.name,
            'sha256': sha256_file(original), 'byteCount': original.stat().st_size})
        self.pipeline.save_checkpoint({'videoId': self.video, 'status': 'complete',
            'generatorVersion': self.workflow.config['generatorVersion'], 'provenanceSha256': provenance_fingerprint(producer),
            'childSha256': sha256_file(self.child), 'originalAudio': {'sha256': sha256_file(self.audio),
                'byteCount': self.audio.stat().st_size, 'durationMs': 1000, 'retained': True}})
        atomic_write_json(self.workflow._job_dir(self.video)/'sources.json', {'videoId': self.video})

    def supported_original(self, *, complete_words=True, required_companions=False):
        """Publish known-answer numerical output through the real artifact/run owners."""
        import json
        import stemwise_engine.mms_asr as mms_asr
        from stemwise_engine.alignment_phases import AlignmentPublicationRequest, EndpointSelectionResult
        from stemwise_engine.alignment_publication import AlignmentPublisher
        from stemwise_engine.lyrics_alignment import (ALIGNMENT_VERSION, AlignmentWorkspace, AlignmentMetrics,
            AlignmentResult, build_lexical_occurrence_mapping, build_synced_sidecar, snapshot_canonical_plain)
        from lyric_content_fixtures import write_recognition
        from stemwise_engine.lyric_content import confirm_content
        from stemwise_engine.common import json_bytes
        project = self.root/'PC side/stemwise-playlist-builder'
        for name in ('contract.py', 'lyrics_align_pc.py', 'lyrics_alignment.py', 'lyrics_tokenization.py',
                'lyrics_unicode.py', 'alignment_attempt.py', 'mms_forced_alignment.py',
                'owsm_ctc_forced_alignment.py', 'qwen3_asr_transcribe.py', 'wav2vec2_forced_alignment.py',
                'roformer_separation.py', 'song_enrichment_v2.py', 'mms_asr.py'):
            shutil.copyfile(ROOT/'PC side/stemwise-playlist-builder/src'/name, project/'src'/name)
        job = self.workflow._load_job(self.video)
        draft = self.workflow._job_dir(self.video)/'drafts'
        draft.mkdir()
        (draft/'original.txt').write_text('bonjour monde\nsalut monde\n' if required_companions
            else 'morning world\nhello world\n', encoding='utf-8')
        if required_companions:
            (draft/'translation-en.txt').write_text('hello world\nhello world\n', encoding='utf-8')
            (draft/'transliteration-ipa.txt').write_text('bɔ̃ʒuʁ mɔ̃d\nsaly mɔ̃d\n', encoding='utf-8')
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': self.video,
            'originalLanguage': 'fr' if required_companions else 'en',
            'originalScript': 'Latn', 'sources': {'original': {'name': 'Authorized untimed fixture'},
                'translationEn': {'name': 'Fixture translation'}, 'ipa': {'name': 'Fixture IPA'}}})
        lyrics, _, _ = self.workflow._preflight_drafts(job)
        original = lyrics['tracks'][0]
        raw = json_bytes(lyrics, pretty=True)
        owner = self.workflow._owner_sidecar_path(self.video)
        owner.parent.mkdir(parents=True, exist_ok=True)
        owner.write_bytes(raw)
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.root)
        snapshot_canonical_plain(workspace, self.video, raw)
        recognition = write_recognition(workspace.job_dir(self.video), original, (0, 1), sha256_file(self.audio))
        cache = json.loads(recognition.read_bytes())
        cache['provenance'].update(modelRevision=mms_asr.REVISION,
            assets=mms_asr.ASSETS_BY_ADAPTER[mms_asr.ADAPTER])
        atomic_write_json(recognition, cache)
        admission = confirm_content(original, (0, 1), hashlib.sha256(raw).hexdigest(),
            sha256_file(self.audio), evidence_path=recognition, evidence_root=workspace.job_dir(self.video))
        duration = self.pipeline.load_checkpoint(self.video)['originalAudio']['durationMs']
        state = {'videoId': self.video, 'attemptId': 'a'*32, 'alignmentVersion': ALIGNMENT_VERSION,
            'canonicalPlainSha256': hashlib.sha256(raw).hexdigest(), 'durationMs': duration,
            'contentAdmission': admission.to_record(), 'contentBinding': admission.binding}
        result = AlignmentResult(accepted=True, reasons=(), starts_ms=(100, 500),
            source_physical_indices=(0, 1), metrics=AlignmentMetrics(lyric_word_count=4,
                transcript_word_count=4, matched_word_count=4, directly_matched_line_count=2,
                line_count=2, lyric_word_coverage=1., transcript_word_coverage=1.,
                direct_line_coverage=1., average_word_score=100., timed_span_ratio=.8))
        timings = [{'startMs': 100, 'endMs': 450, 'words': [
            {'startUtf16': 0, 'endUtf16': 7, 'startMs': 100, 'endMs': 250},
            {'startUtf16': 8, 'endUtf16': 13, 'startMs': 300, 'endMs': 450}]},
            {'startMs': 500, 'endMs': 900, **({'words': [
                {'startUtf16': 0, 'endUtf16': 5, 'startMs': 500, 'endMs': 650},
                {'startUtf16': 6, 'endUtf16': 11, 'startMs': 700, 'endMs': 900}]} if complete_words else {})}]
        candidate = build_synced_sidecar(lyrics, result, duration, line_timings=timings)
        mapping = build_lexical_occurrence_mapping(self.video, original, (0, 1))
        from stemwise_engine.alignment_endpoints import _refine_public_timings, WAV2VEC2_ROUTE
        from stemwise_engine.lyrics_tokenization import display_word_ranges
        forced, verifier = [], []
        for index, (occurrence, timing, text) in enumerate(zip(mapping['performedOccurrences'], timings,
                original['text'].splitlines())):
            intervals = ((100, 250), (300, 450)) if index == 0 else ((500, 650), (700, 900))
            words = [{'wordId': word['occurrenceWordId'], 'startMs': start, 'endMs': end,
                      'confidence': .9 if complete_words or index == 0 else 0., 'acousticTokenCount': 1}
                     for word, (start, end) in zip(occurrence['words'], intervals)]
            forced.append({'lineId': occurrence['occurrenceId'], 'sourcePhysicalIndex': index,
                           'startMs': timing['startMs'], 'endMs': timing['endMs'], 'confidence': .9, 'words': words})
            verifier.append({**forced[-1], 'route': WAV2VEC2_ROUTE, 'words': [
                {**word, 'startUtf16': start, 'endUtf16': end}
                for word, (start, end) in zip(words, display_word_ranges(text))]})
        produced_timings, summary = _refine_public_timings({'lines': forced}, tuple(verifier))
        self.assertEqual(produced_timings, timings)
        self.assertEqual(summary['resultClass'], 'committed-words' if complete_words else 'committed-mixed')
        state['endpointRouteAttempts'] = [{'routeId': 'mix', 'reused': False, 'endpointEvidence': summary}]
        request = AlignmentPublicationRequest(workspace=workspace, video_id=self.video, sidecar_path=owner,
            original_raw=raw, expected_current_raw=raw, candidate=candidate, alignment=result,
            lexical_mapping=mapping, public_line_timings=timings, summary={'accepted': True},
            endpoint_selection=EndpointSelectionResult(timings=tuple(timings),
                summary=summary, reused=False, attempt_index=0), no_commit=False)
        AlignmentPublisher().publish(request, state)
        if not complete_words:
            (draft/'translation-en.txt').unlink(missing_ok=True)
            (draft/'transliteration-ipa.txt').unlink(missing_ok=True)
            return workspace, original, candidate
        _, identity = self.workflow._lexical_mapping(self.video)
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        artifacts = {'sourcesSha256': sha256_file(draft.parent/'sources.json'),
            'sidecarSha256': sha256_file(owner), 'alignmentCandidateSha256': sha256_file(workspace.candidate_path(self.video)),
            'mappingArtifactSha256': identity['mappingArtifactSha256'],
            'alignmentStateSha256': sha256_file(workspace.state_path(self.video)),
            'analysisAudioSha256': audio['sha256'], 'artworkSha256': audio['artworkSha256']}
        editorial_sha = self.workflow._capability_input_sha(self.video)
        run_sha = self.workflow.report_store.commit_alignment_run(job, run_id='a'*32, editorial_sha256=editorial_sha,
            state=state, status='complete', artifacts=artifacts)
        self.workflow.review_construction.create_display_review_attempt(job, original_text=original['text'],
            editorial_sha256=editorial_sha, analysis_audio=audio, alignment_state=state,
            alignment_run_sha256=run_sha, alignment_artifacts=artifacts)
        self.workflow._save_job(job)
        self.workflow._write_task(job)
        (draft/'translation-en.txt').unlink(missing_ok=True)
        (draft/'transliteration-ipa.txt').unlink(missing_ok=True)
        if required_companions:
            self.assertNotEqual(self.workflow._capability_input_sha(self.video), editorial_sha)
        return workspace, original, candidate

    def test_mixed_word_and_line_timing_preserves_progress_without_completion(self):
        workspace, original, candidate = self.supported_original(complete_words=False)
        protected = {path: path.read_bytes() for path in (self.audio, self.child,
            workspace.candidate_path(self.video), workspace.state_path(self.video))}
        with self.assertRaisesRegex(FutureSongError, r'^validation failed \(ValueError\)$') as refused:
            self.workflow.validate(self.video)
        self.assertIs(type(refused.exception.__cause__), ValueError)
        from stemwise_engine.workflow_records import WorkflowInputError
        self.assertIsInstance(refused.exception.__context__, WorkflowInputError)
        self.assertEqual(refused.exception.__context__.failure_details['reasonCode'], 'line-unresolved')
        self.assertNotEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertFalse((self.workflow.ready/self.child.name).exists())
        current = self.workflow._load_job(self.video)
        for claimed in ('VALIDATED', 'DELIVERED'):
            summary = self.workflow.summary({**current, 'state': claimed}, verify_delivery=False)
            self.assertEqual(summary['state'], 'STATUS_ERROR')
            self.assertEqual(summary['statusError'], 'lyrics-content-unresolved')
            self.assertEqual(summary['failureDetails']['reasonCode'], 'lyrics-content-unresolved')
            self.assertEqual(summary['failureDetails']['affectedInput'],
                'current Original lyrics, performed occurrences and word endpoints')
            self.assertNotIn('delivery', summary)

    def test_failed_completion_preserves_originating_blocker_and_durable_reason(self):
        from workflow_fixture import nested_case
        from stemwise_engine.workflow_records import (ContentEvidenceUnavailable, LyricsContentBlocker,
            FutureSongOutcomeError, WorkflowInputError)
        native = ValueError('controlled native evidence failure')
        for primary, native_cause in ((ContentEvidenceUnavailable('content-evidence-changed'), None),
                        (LyricsContentBlocker('content-sequence-uncertain'), None),
                        (FutureSongOutcomeError('LINE_UNRESOLVED', 'line-unresolved'), None),
                        (FutureSongError('controlled current primary failure'), native)):
            with self.subTest(cause=type(primary).__name__):
                case = nested_case(self, AvailableCompletionTests)
                case.limited_recording('one two three', 'one two three')
                atomic_write_json(case.workflow._alignment_state_path(case.video), {
                    'status': 'line-unresolved', 'endpointEvidence': {
                        'resultClass': 'committed-lines', 'lineOnlyCount': 1}})
                protected = case.child.read_bytes(), case.audio.read_bytes()
                def reject(video_id):
                    if isinstance(primary, FutureSongOutcomeError):
                        case.workflow._record_validation_outcome(video_id, primary)
                    else:
                        case.workflow._record_validation_failure(video_id, 'future-song-error')
                    if native_cause is None:
                        raise primary
                    try:
                        raise native_cause
                    except ValueError:
                        raise primary from native_cause
                with patch.object(case.workflow, '_validate_enhancements_action', side_effect=reject):
                    with self.assertRaises(type(primary)) as raised:
                        case.workflow.validate(case.video)
                self.assertIs(raised.exception, primary)
                self.assertIs(primary.__cause__, native_cause)
                self.assertIsInstance(primary.__context__, WorkflowInputError)
                self.assertEqual(primary.__context__.failure_details['reasonCode'], 'line-unresolved')
                job = case.workflow._load_job(case.video)
                if isinstance(primary, FutureSongOutcomeError):
                    self.assertEqual(job['state'], primary.state)
                    self.assertEqual(job['outcome']['code'], primary.code)
                else:
                    self.assertEqual(job['state'], 'VALIDATION_FAILED')
                    self.assertEqual(job['error'], 'future-song-error')
                if hasattr(primary, 'reasons'):
                    self.assertEqual(job['outcome']['reasons'], list(primary.reasons))
                self.assertNotIn('validation', job)
                self.assertEqual((case.child.read_bytes(), case.audio.read_bytes()), protected)
                self.assertFalse((case.workflow.ready/case.child.name).exists())

    def current_display_fixture(self, *, required_companions=False):
        import json
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.metadata_research import metadata_sha256
        draft, review, original, _ = self.companion_publication_fixture(required_companions=required_companions)
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.root)
        state = json.loads(workspace.state_path(self.video).read_bytes())
        document, _ = read_child(self.child, self.pipeline.config)
        review['metadataReview'] = {'formatVersion': 1, 'videoId': self.video,
            'originalMetadataSha256': metadata_sha256(document['metadata']),
            'sourcesSha256': sha256_file(draft.parent/'sources.json'),
            'title': document['metadata']['title'], 'artists': document['metadata']['artists'],
            'evidenceUrls': [self.workflow._artwork_source_url(self.video)], 'recordingIdentityConfirmed': True}
        atomic_write_json(draft.parent/'agent-review.json', review)
        job = self.workflow._load_job(self.video)
        pointer = job['displayReview']
        run = json.loads((self.workflow.report_store.report_root(self.video)/'runs'/pointer['alignmentRunId']/'run.json').read_bytes())
        job['artworkReview'] = {'result': 'accepted', 'sourceVideoId': self.video,
            'sourceUrl': self.workflow._artwork_source_url(self.video),
            'artworkSha256': hashlib.sha256(PNG_1X1).hexdigest(), 'reviewedAt': '2026-11-13T00:00:00Z'}
        editorial_sha = self.workflow._editorial_sha(job)
        state['attemptId'] = 'b'*32
        atomic_write_json(workspace.state_path(self.video), state)
        artifacts = {**run['artifacts'], 'alignmentStateSha256': sha256_file(workspace.state_path(self.video))}
        run_sha = self.workflow.report_store.commit_alignment_run(job, run_id=state['attemptId'],
            editorial_sha256=editorial_sha, state=state, status='complete', artifacts=artifacts)
        self.workflow.review_construction.create_display_review_attempt(job, original_text=original['text'],
            editorial_sha256=editorial_sha,
            analysis_audio=self.workflow._completed_analysis_audio(self.pipeline, self.video),
            alignment_state=state, alignment_run_sha256=run_sha, alignment_artifacts=artifacts)
        self.workflow._save_job(job)
        checkpoint = self.pipeline.load_checkpoint(self.video)
        checkpoint['ownerLyricsSha256'] = 'absent'
        self.pipeline.save_checkpoint(checkpoint)
        proof = workspace.job_dir(self.video)/state['contentAdmission']['evidence']['path']
        return workspace, state, proof

    def test_direct_display_review_rechecks_current_numerical_producer(self):
        from stemwise_engine.workflow_records import WorkflowInputError
        workspace, _, _ = self.current_display_fixture()
        job = self.workflow._load_job(self.video)
        helper = Path(self.workflow.config['projectDir'])/'src/lyrics_alignment.py'
        before = helper.read_bytes()
        protected = {path: path.read_bytes() for path in (self.child, self.audio,
            workspace.candidate_path(self.video), workspace.state_path(self.video),
            self.workflow._owner_sidecar_path(self.video))}
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('display readback cannot infer')):
            helper.write_bytes(before + b'\n# Documentation clarification only.\n')
            self.assertEqual(self.workflow._validate_display_review(job, read_only=True)['displayReviewCheck'], 'pass')
            helper.write_bytes(before + b'\nCHANGED_EXECUTABLE_POLICY = True\n')
            with self.assertRaises(WorkflowInputError) as refused:
                self.workflow._validate_display_review(job, read_only=True)
            self.assertEqual(refused.exception.failure_details['reasonCode'], 'line-unresolved')
            self.assertEqual(refused.exception.failure_details['affectedInput'], 'Original word timing')
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)

    def test_ordinary_display_snapshot_rechecks_current_original_before_and_after_delivery(self):
        from stemwise_engine.editorial_admission import available_lyrics
        from workflow_fixture import nested_case
        for delivered in (False, True):
            with self.subTest(delivered=delivered):
                case = nested_case(self, lambda: type(self)('runTest'))
                workspace, _, _ = case.current_display_fixture()
                with patch.object(case.workflow, '_run_primary_worker',
                        side_effect=AssertionError('snapshot checks cannot infer')):
                    self.assertEqual(case.workflow.validate(case.video)['state'], 'VALIDATED')
                    helper = Path(case.workflow.config['projectDir'])/'src/lyrics_alignment.py'
                    raw = helper.read_bytes()
                    first, rest = raw.split(b'\n', 1)
                    helper.write_bytes(first.replace(b'"""Align', b'"""Documented: align')
                        + b'\n' + rest + b'\n# Documentation clarification only.\n')
                    audio = case.workflow._completed_analysis_audio(case.pipeline, case.video)
                    expected_state = 'DELIVERED' if delivered else 'VALIDATED'
                    if delivered:
                        self.assertEqual(case.workflow.finalize(case.video)['state'], 'DELIVERED')
                    self.assertEqual(case.workflow.validate(case.video)['state'], expected_state)
                    job = case.workflow._load_job(case.video)
                    self.assertTrue(case.workflow._validated_snapshot_is_current(job, require_delivery=delivered))
                    projection, capabilities = available_lyrics(case.workflow, job, audio)
                    changed = copy.deepcopy(projection)
                    changed['tracks'][0]['lines'][0]['words'][0]['startMs'] += 1
                    with patch('stemwise_engine.editorial_admission.available_lyrics',
                            return_value=(changed, capabilities)):
                        changed_is_current = case.workflow._validated_snapshot_is_current(job, require_delivery=delivered)
                    public = case.workflow.ready/case.child.name
                    published = public.read_bytes() if delivered else None
                    protected = {path: path.read_bytes() for path in (case.child, case.audio,
                        workspace.state_path(case.video), workspace.candidate_path(case.video),
                        case.workflow._owner_sidecar_path(case.video)) if path.is_file()}
                    helper.write_bytes(raw + b'\nCHANGED_EXECUTABLE_POLICY = True\n')
                    _, current = available_lyrics(case.workflow, job, audio)
                    self.assertEqual((current['content'], current['timing']), ('admitted', 'plain'))
                    with self.assertRaises(FutureSongError):
                        case.workflow.finalize(case.video)
                    self.assertEqual(public.read_bytes() if public.exists() else None, published)
                    with self.assertRaises(FutureSongError):
                        case.workflow.validate(case.video)
                    self.assertEqual(public.read_bytes() if public.exists() else None, published)
                    self.assertEqual({path: path.read_bytes() for path in protected}, protected)
                    self.assertFalse(changed_is_current,
                        'A complete but different current Original cannot reuse the old snapshot')

    def test_current_changed_content_evidence_keeps_technical_cause_through_validation(self):
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        workspace, state, proof = self.current_display_fixture()
        proof.write_bytes(proof.read_bytes()+b'\n')
        protected = {path: path.read_bytes() for path in (self.child, self.audio, proof,
            workspace.candidate_path(self.video), workspace.state_path(self.video))}
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            with self.assertRaises(ContentEvidenceUnavailable) as caught:
                self.workflow.validate(self.video)
        self.assertEqual(caught.exception.reasons, ('content-evidence-changed',))
        self.assertEqual(caught.exception.binding, state['contentBinding'])
        job = self.workflow._load_job(self.video)
        self.assertEqual(job['state'], 'RETRYABLE_TECHNICAL')
        self.assertEqual(job['outcome']['reasons'], ['content-evidence-changed'])
        self.assertEqual(job['outcome']['contentBinding'], state['contentBinding'])
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        for verify_delivery in (False, True):
            status = self.workflow.status(self.video, verify_delivery=verify_delivery)
            self.assertEqual(status['state'], 'RETRYABLE_TECHNICAL')
            self.assertEqual(status['outcome']['reasons'], ['content-evidence-changed'])
            self.assertIn('recognition evidence', status['latestOperation']['failureDetails']['nextAction'])

    def test_validated_original_only_rejects_later_changed_content_proof_in_all_completion_consumers(self):
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        workspace, state, proof = self.current_display_fixture()
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        job = self.workflow._load_job(self.video)
        self.assertTrue(self.workflow._validated_snapshot_is_current(job))
        proof.write_bytes(proof.read_bytes()+b'\n')
        protected = {path: path.read_bytes() for path in (self.child, self.audio, proof,
            workspace.candidate_path(self.video), workspace.state_path(self.video))}
        self.assertFalse(self.workflow._validated_snapshot_is_current(job))
        for verify_delivery in (False, True):
            status = self.workflow.status(self.video, verify_delivery=verify_delivery)
            self.assertEqual(status['state'], 'STATUS_ERROR')
            self.assertEqual(status['statusError'], 'retryable-technical')
            self.assertIn('recognition evidence', status['failureDetails']['nextAction'])
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertFalse((self.workflow.ready/self.child.name).exists())
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            with self.assertRaises(ContentEvidenceUnavailable) as caught:
                self.workflow.validate(self.video)
        self.assertEqual(caught.exception.reasons, ('content-evidence-changed',))
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)

    def test_accepted_display_timing_survives_later_companion_rejection(self):
        import json
        from stemwise_engine.metadata_research import metadata_sha256
        draft, review, original, candidate = self.companion_publication_fixture()
        document, _ = read_child(self.child, self.pipeline.config)
        review['metadataReview'] = {'formatVersion': 1, 'videoId': self.video,
            'originalMetadataSha256': metadata_sha256(document['metadata']),
            'sourcesSha256': sha256_file(draft.parent/'sources.json'),
            'title': document['metadata']['title'], 'artists': document['metadata']['artists'],
            'evidenceUrls': [self.workflow._artwork_source_url(self.video)], 'recordingIdentityConfirmed': True}
        self.approve_current_companions(draft, review)
        job = self.workflow._load_job(self.video)
        pointer = job['displayReview']
        state = json.loads(self.workflow._alignment_state_path(self.video).read_bytes())
        run = json.loads((self.workflow.report_store.report_root(self.video)/'runs'/pointer['alignmentRunId']/'run.json').read_bytes())
        job['artworkReview'] = {'result': 'accepted', 'sourceVideoId': self.video,
            'sourceUrl': self.workflow._artwork_source_url(self.video),
            'artworkSha256': hashlib.sha256(PNG_1X1).hexdigest(), 'reviewedAt': '2026-11-04T00:00:00Z'}
        self.workflow.review_construction.create_display_review_attempt(job, original_text=original['text'],
            editorial_sha256=self.workflow._editorial_sha(job),
            analysis_audio=self.workflow._completed_analysis_audio(self.pipeline, self.video),
            alignment_state=state, alignment_run_sha256=pointer['alignmentRunSha256'],
            alignment_artifacts=run['artifacts'])
        self.workflow._save_job(job)
        checkpoint = self.pipeline.load_checkpoint(self.video)
        checkpoint['ownerLyricsSha256'] = 'absent'
        self.pipeline.save_checkpoint(checkpoint)
        self.workflow._validate_display_review(job, read_only=True)
        self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        self.assertEqual(self.workflow._load_job(self.video)['displayReview']['result'], 'accepted-display-only')
        expected, _ = read_child(self.child, self.pipeline.config)
        expected_original = expected['lyrics']['tracks'][0]
        protected = {path: path.read_bytes() for path in (self.child, self.audio,
            self.workflow._alignment_state_path(self.video),
            self.workflow._alignment_state_path(self.video).with_name('candidate.private.lyrics.json'))}
        for name, kind in (('translation-en.txt', 'translation'), ('transliteration-ipa.txt', 'transliteration')):
            with self.subTest(kind=kind):
                body = (draft/name).read_bytes()
                (draft/name).unlink()
                try:
                    self.workflow = FutureSongWorkflow(self.workflow.config_path)
                    with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no fresh inference')):
                        with self.assertRaisesRegex(FutureSongError, 'required companion'):
                            self.workflow.validate(self.video)
                    from stemwise_engine.editorial_admission import available_lyrics
                    projection, _ = available_lyrics(self.workflow, self.workflow._load_job(self.video),
                        self.workflow._completed_analysis_audio(self.pipeline, self.video))
                    self.assertEqual(projection['tracks'][0], expected_original)
                    self.assertNotIn(kind, [track['kind'] for track in projection['tracks']])
                    self.assertEqual({path: path.read_bytes() for path in protected}, protected)
                    for verify_delivery in (False, True):
                        status = self.workflow.status(self.video, verify_delivery=verify_delivery)
                        self.assertEqual(status['state'], 'STATUS_ERROR')
                        self.assertEqual(status['statusError'], 'review-stale')
                        self.assertIn('IPA' if kind == 'transliteration' else 'Translation',
                            status['failureDetails']['affectedInput'])
                        self.assertIn('Preserve complete Original word timing and its quality labels.',
                            status['failureDetails']['nextAction'])
                    with self.assertRaises(FutureSongError):
                        self.workflow.finalize(self.video)
                finally:
                    (draft/name).write_bytes(body)
        self.approve_current_companions(draft, review)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no fresh inference')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        destination = self.workflow.ready/self.child.name
        public_bytes = destination.read_bytes()
        companion = draft/'translation-en.txt'
        companion_bytes = companion.read_bytes()
        destination.unlink()
        companion.unlink()
        try:
            status = self.workflow.status(self.video, verify_delivery=False)
            self.assertEqual(status['state'], 'STATUS_ERROR')
            self.assertEqual(status['statusError'], 'delivery-file-unavailable')
        finally:
            destination.write_bytes(public_bytes)
            companion.write_bytes(companion_bytes)
        self.assertEqual(destination.read_bytes(), public_bytes)

    def companion_publication_fixture(self, *, required_companions=True):
        import json
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.workflow_review import review_template
        music = {'checked': True, 'result': {'decision': 'not-found'}}
        normal = {'checked': True, 'records': []}
        sources = {'videoId': self.video,
            'exactSurfaceChecks': {'youtubeMusic': music, 'normalYouTube': normal},
            'lyricEvidenceSources': [{'videoId': self.video, 'youtubeMusic': music, 'normalYouTube': normal}]}
        atomic_write_json(self.workflow._job_dir(self.video)/'sources.json', sources)
        workspace, original, candidate = self.supported_original(required_companions=required_companions)
        draft = self.workflow._job_dir(self.video)/'drafts'
        if required_companions:
            (draft/'translation-en.txt').write_text('hello world\nhello world\n', encoding='utf-8')
            (draft/'transliteration-ipa.txt').write_text('bɔ̃ʒuʁ mɔ̃d\nsaly mɔ̃d\n', encoding='utf-8')
        review = review_template(self.video, sources)
        review.update(authorityOutcome='resolved', reviewer='Fixture reviewer',
            completedAt='2026-10-31T00:00:00Z', sourceDisposition='accepted-exact-recording',
            recordingOrSourceAssociationReviewed=True, youtubeSurfaceVariantsReviewed=True,
            publicSourceResearchComplete=True, originalReviewed=True, unresolvedMaterialIssues=[])
        job = self.workflow._load_job(self.video)
        _, original_sha, _, metadata_sha = read_recording_draft(draft.parent)
        state = json.loads(workspace.state_path(self.video).read_bytes())
        job['recordingEvidenceInputs'] = {'audioSha256': sha256_file(self.audio),
            'draftSha256': original_sha, 'metadataSha256': metadata_sha,
            'recognition': state['contentAdmission']['evidence']}
        self.workflow._save_job(job)
        review['recordingReview'] = {'formatVersion': 2, 'audioSha256': sha256_file(self.audio),
            'draftSha256': self.workflow._recording_draft_sha(job),
            'sourcesSha256': sha256_file(draft.parent/'sources.json'), 'recordingCompared': True,
            'versionSpecificWordingReviewed': True, 'performedOrderReviewed': True,
            'sourceAssociations': [{'videoId': self.video, 'relation': 'exact-recording'}],
            'publicSources': []}
        atomic_write_json(draft.parent/'agent-review.json', review)
        return draft, review, original, candidate

    def assert_validated_companions(self, expected_original, expected_kinds):
        import json
        audio_before = self.audio.read_bytes()
        original_identity = json.loads((self.audio.parent/'original-source.json').read_bytes())
        if expected_kinds != ['original', 'translation', 'transliteration']:
            self.assert_companion_package(expected_original, expected_kinds)
            return original_identity
        job = self.workflow._load_job(self.video)
        job_dir = self.workflow._job_dir(self.video)
        self.workflow._require_recording_review(job,
            json.loads((job_dir/'agent-review.json').read_bytes()),
            json.loads((job_dir/'sources.json').read_bytes()),
            self.workflow._completed_analysis_audio(self.pipeline, self.video))
        self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        document, cover = read_child(self.child, self.pipeline.config)
        self.assertEqual([track['kind'] for track in document['lyrics']['tracks']], expected_kinds)
        self.assertEqual(document['lyrics']['tracks'][0], expected_original)
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(self.audio.read_bytes(), audio_before)
        return original_identity

    def assert_companion_package(self, expected_original, expected_kinds):
        from stemwise_engine.original_delivery import read_bundle
        from stemwise_engine.editorial_admission import available_lyrics
        if expected_kinds != ['original', 'translation', 'transliteration']:
            job = self.workflow._load_job(self.video)
            projection, _ = available_lyrics(self.workflow, job,
                self.workflow._completed_analysis_audio(self.pipeline, self.video))
            self.assertEqual([track['kind'] for track in projection['tracks']], expected_kinds)
            self.assertEqual(projection['tracks'][0], expected_original)
            state = self.workflow._alignment_state_path(self.video)
            protected = {path: path.read_bytes() for path in (self.child, self.audio, state,
                state.with_name('candidate.private.lyrics.json'))}
            with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no fresh inference')):
                with self.assertRaisesRegex(FutureSongError, 'required companion'):
                    self.workflow.validate(self.video)
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
            self.assertFalse((self.workflow.ready/self.child.name).exists())
            self.assertEqual({path: path.read_bytes() for path in protected}, protected)
            return
        audio_before = self.audio.read_bytes()
        original_identity = self.assert_validated_companions(expected_original, expected_kinds)
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        document, cover, identity = read_bundle(self.workflow.ready/self.child.name, self.pipeline.config,
            metadata=self.child)
        self.assertEqual([track['kind'] for track in document['lyrics']['tracks']], expected_kinds)
        self.assertEqual(document['lyrics']['tracks'][0], expected_original)
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(identity['name'], 'original.flac')
        self.assertEqual(identity['sha256'], original_identity['sha256'])
        self.assertEqual(identity['byteCount'], original_identity['byteCount'])
        if expected_kinds == ['original', 'translation', 'transliteration']:
            self.assertFalse(self.audio.exists(), 'Complete source and companion evidence releases disposable PCM')
        else:
            self.assertEqual(self.audio.read_bytes(), audio_before)
        self.assertTrue(self.workflow._validated_snapshot_is_current(
            self.workflow._load_job(self.video), require_delivery=True))

    def test_unreviewed_translation_blocks_completion_without_erasing_original(self):
        draft, _, _, candidate = self.companion_publication_fixture()
        (draft/'translation-en.txt').write_text('goodbye world\nhello world\n', encoding='utf-8')
        self.assert_companion_package(candidate['tracks'][0], ['original'])

    def test_unreviewed_ipa_blocks_completion_without_erasing_original(self):
        draft, _, _, candidate = self.companion_publication_fixture()
        (draft/'transliteration-ipa.txt').write_text('adjø mɔ̃d\nsaly mɔ̃d\n', encoding='utf-8')
        self.assert_companion_package(candidate['tracks'][0], ['original'])

    def test_malformed_translation_blocks_completion_without_erasing_original(self):
        draft, _, _, candidate = self.companion_publication_fixture()
        (draft/'translation-en.txt').write_bytes(b'\xff\n')
        self.assert_companion_package(candidate['tracks'][0], ['original'])

    def test_malformed_companion_roles_preserve_supported_original_and_audio(self):
        draft, review, _, candidate = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        for role, valid in (('translation-en', 'hello world\nhello world\n'),
                ('transliteration-ipa', 'bɔ̃ʒuʁ mɔ̃d\nsaly mɔ̃d\n')):
            path = draft/(role + '.txt')
            for fault in ('invalid-utf8', 'stanza-shape', 'duplicate-txt-lrc'):
                with self.subTest(role=role, fault=fault):
                    try:
                        if fault == 'invalid-utf8':
                            path.write_bytes(b'\xff\n')
                        elif fault == 'stanza-shape':
                            path.write_text(valid.replace('\n', '\n\n', 1), encoding='utf-8')
                        else:
                            path.with_suffix('.lrc').write_text('[00:00.10]a\n[00:00.50]b\n', encoding='utf-8')
                        other = 'transliteration' if role == 'translation-en' else 'translation'
                        self.assert_validated_companions(candidate['tracks'][0], ['original', other])
                    finally:
                        path.write_text(valid, encoding='utf-8')
                        path.with_suffix('.lrc').unlink(missing_ok=True)

    def approve_current_companions(self, draft, review):
        from stemwise_engine.companion_review import companion_binding, REVIEW_CHECKS
        from stemwise_engine.editorial_admission import available_lyrics
        from stemwise_engine.lyrics_intake import read_recording_draft
        job = self.workflow._load_job(self.video)
        if self.audio.exists():
            audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        else:
            from stemwise_engine.original_delivery import delivered_capability_inputs
            audio = delivered_capability_inputs(self.workflow, job)[0]
        compiled, _, _ = self.workflow._preflight_drafts(job)
        original = next(track for track in compiled['tracks'] if track['kind'] == 'original')
        _, original_sha, _, metadata_sha = read_recording_draft(draft.parent)
        _, capabilities = available_lyrics(self.workflow, job, audio)
        reviews = {}
        for kind, name, convention in (
                ('translation', 'translation-en.txt', 'faithful-readable-English/1'),
                ('transliteration', 'transliteration-ipa.txt', 'performed-broad-IPA/1')):
            candidate = next(track for track in compiled['tracks'] if track['kind'] == kind)
            binding = companion_binding(self.video, original, candidate,
                original_input_sha256=original_sha, candidate_input_sha256=sha256_file(draft/name),
                original_metadata_sha256=metadata_sha, audio_sha256=audio['sha256'],
                sources_sha256=sha256_file(draft.parent/'sources.json'),
                content_admission=capabilities['contentAdmission'])
            reviews[kind] = {'formatVersion': 1, 'binding': binding, 'reviewer': 'Fixture reviewer',
                'completedAt': '2026-10-31T00:00:00Z', 'decision': 'admit',
                'coveredOccurrenceIds': binding['occurrenceIds'], 'convention': convention,
                'checks': {name: 'reviewed' for name in REVIEW_CHECKS[kind]}, 'issues': [],
                'evidence': [{'path': 'sources.json', 'sha256': sha256_file(draft.parent/'sources.json')}],
                'rationale': 'Synthetic known-answer fixture, not quality qualification.'}
        review['companionReviews'] = reviews
        atomic_write_json(draft.parent/'agent-review.json', review)

    def test_translation_edit_blocks_completion_and_preserves_original_and_ipa(self):
        draft, review, _, candidate = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        (draft/'translation-en.txt').write_text('goodbye world\nhello world\n', encoding='utf-8')
        self.assert_companion_package(candidate['tracks'][0], ['original', 'transliteration'])

    def test_ipa_edit_blocks_completion_and_preserves_original_and_translation(self):
        draft, review, _, candidate = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        (draft/'transliteration-ipa.txt').write_text('adjø mɔ̃d\nsaly mɔ̃d\n', encoding='utf-8')
        self.assert_companion_package(candidate['tracks'][0], ['original', 'translation'])

    def test_companion_edit_after_validation_blocks_finalize_and_preserves_previous_package(self):
        draft, review, _, candidate = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        self.assert_companion_package(candidate['tracks'][0], ['original', 'translation', 'transliteration'])
        destination = self.workflow.ready/self.child.name
        prior = destination.read_bytes()
        from stemwise_engine.original_delivery import read_bundle
        original = read_bundle(destination, self.pipeline.config)[2]
        (draft/'translation-en.txt').write_text('greetings world\nhello world\n', encoding='utf-8')
        self.approve_current_companions(draft, review)
        self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        self.assertEqual(destination.read_bytes(), prior)
        (draft/'translation-en.txt').write_text('goodbye world\nhello world\n', encoding='utf-8')
        self.assertFalse(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video)))
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertEqual(destination.read_bytes(), prior)
        self.assertEqual(read_bundle(destination, self.pipeline.config)[2], original)

    def test_strict_and_partial_companion_routes_share_current_role_decisions(self):
        import json
        from stemwise_engine.editorial_admission import available_lyrics
        draft, review, original, candidate = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        job = self.workflow._load_job(self.video)
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        strict, _, _ = self.workflow._validate_drafts(job)
        preflight = self.workflow._preflight_drafts(job)[0]
        partial, _ = available_lyrics(self.workflow, job, audio)
        from stemwise_engine.lyrics_alignment import align_known_structure, build_synced_sidecar
        from syncalong.transcribe import WordTimestamp
        from syncalong.textnorm import normalize
        ref = job['recordingEvidenceInputs']['recognition']
        recognized = json.loads((self.workflow._alignment_state_path(self.video).parent/ref['path']).read_bytes())
        words = [WordTimestamp(word=normalize(word['raw']), raw=word['raw'], start=word['start'], end=word['end'])
            for word in recognized['workerOutput']['words']]
        alignment = align_known_structure(original['text'], (0, 1), words, audio['durationMs'])
        self.assertTrue(alignment.accepted, alignment.reasons)
        endpoints = [{key: copy.deepcopy(line[key]) for key in ('startMs', 'endMs', 'words')}
            for line in candidate['tracks'][0]['lines']]
        rebuilt = build_synced_sidecar(strict, alignment, audio['durationMs'], line_timings=endpoints)
        self.assertEqual(rebuilt['tracks'][1:], partial['tracks'][1:])
        self.assertEqual([track['format'] for track in preflight['tracks'][1:]], ['plain', 'plain'])
        self.assertEqual([track['format'] for track in strict['tracks'][1:]], ['plain', 'plain'])
        for supplied, timed in zip(strict['tracks'][1:], partial['tracks'][1:]):
            self.assertEqual(timed['format'], 'synced')
            self.assertEqual([line['text'] for line in timed['lines']], supplied['text'].split('\n'))
            self.assertEqual([(line['startMs'], line['endMs']) for line in timed['lines']],
                [(line['startMs'], line['endMs']) for line in candidate['tracks'][0]['lines']])
        self.assertEqual([track['kind'] for track in strict['tracks']],
            ['original', 'translation', 'transliteration'])
        from stemwise_engine.editorial_admission import require_completed_lyrics
        from stemwise_engine.workflow_records import WorkflowInputError
        plain_roles = {**partial, 'tracks': [partial['tracks'][0], *strict['tracks'][1:]]}
        with self.assertRaises(WorkflowInputError) as refused:
            require_completed_lyrics(plain_roles, duration_ms=audio['durationMs'])
        self.assertEqual(refused.exception.failure_details['reasonCode'], 'line-unresolved')
        self.assertEqual(refused.exception.failure_details['affectedInput'], 'Translation, IPA')
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('complete Original must not infer again')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        job = self.workflow._load_job(self.video)
        _, capabilities = available_lyrics(self.workflow, job, audio)
        packet_path = draft.parent/'companion-review-inputs.json'
        packet_bytes = packet_path.read_bytes()
        packet = json.loads(packet_bytes)
        self.assertEqual(packet['original'], original)
        self.assertEqual(packet['recordingIdentity'], {key: audio[key]
            for key in ('sha256', 'byteCount', 'durationMs')})
        self.assertEqual(packet['sourcesSha256'], sha256_file(draft.parent/'sources.json'))
        self.assertEqual([line['text'] for line in packet['physicalLines']], original['text'].split('\n'))
        self.assertEqual([line['physicalIndex'] for line in packet['physicalLines']], [0, 1])
        self.assertEqual([line['occurrenceId'] for line in packet['physicalLines']],
            review['companionReviews']['translation']['binding']['occurrenceIds'])
        self.assertEqual(len({line['lineId'] for line in packet['physicalLines']}), 2)
        for kind, slot in packet['roles'].items():
            self.assertEqual(slot['binding'], capabilities['companionAdmission'][kind]['binding'])
            self.assertEqual(slot['candidate'], next(track for track in strict['tracks'] if track['kind'] == kind))
            self.assertNotIn('decision', slot)
            self.assertNotIn('reviewer', slot)
        review_path = draft.parent/'agent-review.json'
        review_bytes = review_path.read_bytes()
        role_bytes = {name: (draft/name).read_bytes() for name in ('translation-en.txt', 'transliteration-ipa.txt')}
        edited = copy.deepcopy(review)
        edited['companionReviews']['translation'].update(completedAt='2026-10-31T00:00:01Z',
            rationale='Reviewed synthetic meaning fixture; no candidate change.')
        try:
            atomic_write_json(review_path, edited)
            edited_bytes = review_path.read_bytes()
            self.workflow._write_task(job)
            self.assertEqual(packet_path.read_bytes(), packet_bytes)
            self.assertEqual(review_path.read_bytes(), edited_bytes)
            status = self.workflow._review_inputs(job)
            self.assertEqual(status['originalContent'], 'admitted')
            _, current_capabilities = available_lyrics(self.workflow, job, audio)
            self.assertEqual(status['companionAdmission'], current_capabilities['companionAdmission'])
            self.assertEqual({value['state'] for value in status['companionAdmission'].values()}, {'admitted'})
            self.assertNotEqual(status['companionAdmission']['translation']['reviewSha256'],
                capabilities['companionAdmission']['translation']['reviewSha256'])
            serialized = json.dumps(status, ensure_ascii=False)
            for body in ('bonjour monde', 'hello world', 'bɔ̃ʒuʁ'):
                self.assertNotIn(body, serialized)
        finally:
            review_path.write_bytes(review_bytes)
        for kind, name, changed in (
                ('translation', 'translation-en.txt', 'goodbye world\nhello world\n'),
                ('transliteration', 'transliteration-ipa.txt', 'adjø mɔ̃d\nsaly mɔ̃d\n')):
            path = draft/name
            original_bytes = path.read_bytes()
            try:
                path.write_text(changed, encoding='utf-8')
                with self.subTest(kind=kind):
                    strict, _, _ = self.workflow._validate_drafts(job)
                    self.assertNotIn(kind, [track['kind'] for track in strict['tracks']])
                    partial, capabilities = available_lyrics(self.workflow, job, audio)
                    self.assertEqual(capabilities['companionAdmission'][kind]['state'], 'stale')
                    self.assertEqual(partial['tracks'][0], candidate['tracks'][0])
                    self.assertEqual([track['kind'] for track in partial['tracks']],
                        ['original', 'transliteration' if kind == 'translation' else 'translation'])
            finally:
                path.write_bytes(original_bytes)
        review.pop('companionReviews')
        atomic_write_json(draft.parent/'agent-review.json', review)
        strict, _, _ = self.workflow._validate_drafts(job)
        self.assertEqual([track['kind'] for track in strict['tracks']], ['original'])
        partial, capabilities = available_lyrics(self.workflow, job, audio)
        self.assertEqual([track['kind'] for track in partial['tracks']], ['original'])
        self.assertEqual(partial['tracks'][0], candidate['tracks'][0])
        self.assertEqual({value['state'] for value in capabilities['companionAdmission'].values()}, {'unreviewed'})
        for stem in ('translation-en', 'transliteration-ipa'):
            (draft/f'{stem}.txt').unlink()
            (draft/f'{stem}.lrc').write_text('invalid optional timing input\n', encoding='utf-8')
        strict, _, anchors = self.workflow._validate_drafts(job)
        self.assertEqual([track['kind'] for track in strict['tracks']], ['original'])
        self.assertEqual(anchors, {'sourceFiles': [], 'startsMs': []})
        for name, raw in role_bytes.items():
            (draft/name).with_suffix('.lrc').unlink()
            (draft/name).write_bytes(raw)
        review_path.write_bytes(review_bytes)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('role approval must not infer again')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        from stemwise_engine.original_delivery import read_bundle
        delivered, _, _ = read_bundle(self.workflow.ready/self.child.name, self.pipeline.config)
        self.assertEqual(delivered['lyrics']['tracks'][0], candidate['tracks'][0])
        for kind, name in (('translation', 'translation-en.txt'), ('transliteration', 'transliteration-ipa.txt')):
            role = next(track for track in delivered['lyrics']['tracks'] if track['kind'] == kind)
            self.assertEqual(role['format'], 'synced')
            self.assertEqual([line['text'] for line in role['lines']], role_bytes[name].decode('utf-8').strip().split('\n'))
            self.assertEqual([(line['startMs'], line['endMs']) for line in role['lines']],
                [(line['startMs'], line['endMs']) for line in candidate['tracks'][0]['lines']])

    def test_supported_original_timing_tamper_or_stale_producer_downgrades_to_plain(self):
        import json
        from stemwise_engine.editorial_admission import available_lyrics
        workspace, original, candidate = self.supported_original()
        job = self.workflow._load_job(self.video)
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        self.assertEqual(available_lyrics(self.workflow, job, audio)[0]['tracks'][0]['format'], 'synced')
        state = workspace.state_path(self.video)
        helper = self.root/'PC side/stemwise-playlist-builder/src/lyrics_align_pc.py'
        mapping = state.with_name('mapping.private.json')
        for path in (workspace.candidate_path(self.video), state, helper, mapping):
            before = path.read_bytes()
            if path == helper:
                changed = before.replace(b'MMS_REQUEST_VERSION = 5', b'MMS_REQUEST_VERSION = 6', 1)
                self.assertNotEqual(changed, before)
                path.write_bytes(changed)
            else:
                value = json.loads(before)
                if path == mapping:
                    value['numeric']['startsMs'][0] += 1
                elif path == state:
                    value['durationMs'] = 999
                else:
                    value['tracks'][0]['lines'][0]['words'][0]['startMs'] += 1
                atomic_write_json(path, value)
            with self.subTest(path=path.name):
                projection, capabilities = available_lyrics(self.workflow, job, audio)
                self.assertEqual(projection['tracks'], [original])
                self.assertEqual(capabilities['timing'], 'plain')
            path.write_bytes(before)
        changed = copy.deepcopy(original)
        changed['source']['name'] = 'Different submitted source context'
        self.assertIsNone(self.workflow._available_original_timing(job, changed, audio))

    def test_missing_lyrics_without_exhaustive_evidence_cannot_complete(self):
        protected = {path: path.read_bytes() for path in (self.audio, self.child)}
        with self.assertRaisesRegex(FutureSongError, r'^validation failed \(ValueError\)$') as refused:
            self.workflow.validate(self.video)
        self.assertIs(type(refused.exception.__cause__), ValueError)
        from stemwise_engine.workflow_records import WorkflowInputError
        fallback = refused.exception.__context__
        self.assertIs(type(fallback), FutureSongError)
        self.assertEqual(str(fallback), 'lyrics.meta.json is missing; editorial work is incomplete')
        self.assertIsInstance(fallback.__context__, WorkflowInputError)
        self.assertEqual(fallback.__context__.failure_details['reasonCode'], 'lyrics-content-unresolved')
        job = self.workflow._load_job(self.video)
        self.assertNotEqual(job['state'], 'VALIDATED')
        self.assertNotIn('validation', job)
        self.assertFalse(self.workflow._validated_snapshot_is_current(job))
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertFalse((self.workflow.ready/self.child.name).exists())

    def test_changed_performed_sequence_cannot_reuse_complete_word_timing(self):
        from stemwise_engine.editorial_admission import available_lyrics
        workspace, original, candidate = self.supported_original()
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        job = self.workflow._load_job(self.video)
        publication = self.workflow.ready/self.child.name
        protected = {path: path.read_bytes() for path in (publication, self.child,
            workspace.candidate_path(self.video), workspace.state_path(self.video))}
        draft = self.workflow._job_dir(self.video)/'drafts/original.txt'
        before = draft.read_bytes()
        for changed in ('morning\nhello world\n', 'morning world\nmorning world\n',
                        'hello world\nmorning world\n', 'morning world\n'):
            with self.subTest(sequence=changed):
                draft.write_text(changed, encoding='utf-8')
                projection, capabilities = available_lyrics(self.workflow, job, audio)
                self.assertNotEqual(capabilities['content'], 'admitted')
                with self.assertRaises(FutureSongError):
                    self.workflow.validate(self.video)
                with self.assertRaises(FutureSongError):
                    self.workflow.finalize(self.video)
                self.assertEqual({path: path.read_bytes() for path in protected}, protected)
                self.assertEqual(self.workflow.status(self.video)['state'], 'STATUS_ERROR')
        draft.write_bytes(before)


    def test_pending_endpoint_review_cannot_authorize_unavailable_completion(self):
        job = self.workflow._load_job(self.video)
        job['endpointReview'] = {'attemptId': 'a'*32}
        attempt = self.workflow._endpoint_review_attempt_dir(self.video, 'a'*32)
        attempt.mkdir(parents=True)
        decision = attempt/'review.json'
        atomic_write_json(decision, {'outcome': 'pending'})
        self.workflow._save_job(job)
        protected = {path: path.read_bytes() for path in (self.audio, self.child, decision)}
        for _ in range(2):
            with self.assertRaisesRegex(FutureSongError, '^lyrics.meta.json is missing; editorial work is incomplete$'):
                self.workflow._validate_available_capabilities(self.workflow._load_job(self.video))
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
            self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        self.assertNotEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')
        self.assertFalse((self.workflow.ready/self.child.name).exists())


    def test_changed_original_blocks_package_and_preserves_prior_child(self):
        before = self.child.read_bytes()
        self.audio.write_bytes(b'wrong authorized recording')
        with self.assertRaises(FutureSongError):
            self.workflow.validate(self.video)
        self.assertEqual(self.child.read_bytes(), before)
        self.assertNotEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')

    def test_admitted_plain_with_stale_timing_cannot_complete(self):
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, snapshot_canonical_plain
        from lyric_content_fixtures import retained_content
        job = self.workflow._load_job(self.video)
        draft = self.workflow._job_dir(self.video)/'drafts'
        draft.mkdir()
        (draft/'original.txt').write_text('bonjour le monde\n', encoding='utf-8')
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': self.video, 'originalLanguage': 'fr',
            'originalScript': 'Latn', 'sources': {'original': {'name': 'Authorized untimed fixture'}}})
        lyrics, _, _ = self.workflow._preflight_drafts(job)
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.root)
        from stemwise_engine.common import json_bytes
        snapshot_canonical_plain(workspace, self.video, json_bytes(lyrics))
        canonical = workspace.current_backup_path(self.video)
        original = lyrics['tracks'][0]
        admission, _ = retained_content(workspace.job_dir(self.video), original, (0,),
            sha256_file(canonical), sha256_file(self.audio))
        workspace.write_state(self.video, {'canonicalPlainSha256': sha256_file(canonical),
            'contentAdmission': admission.to_record(), 'contentBinding': admission.binding,
            'status': 'committed', 'acousticProducerIdentity': {'stale': True}})
        candidate = copy.deepcopy(lyrics)
        candidate['tracks'][0].pop('text')
        candidate['tracks'][0].update(format='synced', lines=[{'text': 'bonjour le monde',
            'startMs': 100, 'endMs': 900, 'words': [
                {'startUtf16': 0, 'endUtf16': 7, 'startMs': 100, 'endMs': 300},
                {'startUtf16': 8, 'endUtf16': 10, 'startMs': 350, 'endMs': 450},
                {'startUtf16': 11, 'endUtf16': 16, 'startMs': 500, 'endMs': 900}]}])
        atomic_write_json(workspace.candidate_path(self.video), candidate)
        protected = {path: path.read_bytes() for path in (self.audio, self.child, workspace.candidate_path(self.video))}
        with self.assertRaisesRegex(FutureSongError, r'^validation failed \(ValueError\)$') as refused:
            self.workflow.validate(self.video)
        self.assertIs(type(refused.exception.__cause__), ValueError)
        from stemwise_engine.workflow_records import WorkflowInputError
        self.assertIsInstance(refused.exception.__context__, WorkflowInputError)
        self.assertEqual(refused.exception.__context__.failure_details['reasonCode'], 'line-unresolved')
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        self.assertFalse(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video)))


    def test_readback_failure_preserves_previous_publication(self):
        self.supported_original()
        import soundfile as sf
        from stemwise_engine.original_delivery import create_bundle, read_bundle
        prior = self.root/'Ready to Import'/self.child.name
        prior.parent.mkdir(parents=True, exist_ok=True)
        original = self.root/'prior-original.flac'
        samples, rate = sf.read(self.audio, dtype='float32', always_2d=True)
        sf.write(original, samples, rate, subtype='PCM_24')
        identity = {'name': 'original.flac', 'sha256': sha256_file(original), 'byteCount': original.stat().st_size}
        create_bundle(prior, self.child, original, identity, self.pipeline.config, self.root)
        read_bundle(prior, self.pipeline.config, metadata=self.child, original=identity)
        before = prior.read_bytes()
        real = read_child
        calls = 0
        def readback(path, config):
            nonlocal calls
            result = real(path, config)
            if path == self.child:
                calls += 1
                if calls >= 2:
                    raise FutureSongError('injected package readback failure')
            return result
        with patch('stemwise_engine.future_song.read_child', side_effect=readback):
            with self.assertRaises(FutureSongError):
                self.workflow._validate_available_capabilities(self.workflow._load_job(self.video))
        self.assertEqual(prior.read_bytes(), before)
        read_bundle(prior, self.pipeline.config, original=identity)
        self.assertNotEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')

    def test_recording_receipt_preserves_plain_progress_without_completion(self):
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.lyrics_intake import read_recording_draft
        from lyric_content_fixtures import write_recognition
        draft = self.workflow._job_dir(self.video)/'drafts'
        draft.mkdir()
        (draft/'original.txt').write_text('one two three\n', encoding='utf-8')
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': self.video, 'originalLanguage': 'en',
            'originalScript': 'Latn', 'sources': {'original': {'name': 'Authorized untimed fixture'}}})
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.root)
        evidence = write_recognition(workspace.job_dir(self.video), 'one two three', (0,), sha256_file(self.audio))
        _, draft_sha, _, metadata_sha = read_recording_draft(draft.parent)
        job = self.workflow._load_job(self.video)
        job['recordingContentEvidence'] = {'audioSha256': sha256_file(self.audio), 'draftSha256': draft_sha,
            'metadataSha256': metadata_sha, 'evidence': {'path': evidence.name, 'sha256': sha256_file(evidence)}}
        self.workflow._save_job(job)
        from stemwise_engine.editorial_admission import available_lyrics
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        projection, capabilities = available_lyrics(self.workflow, job, audio)
        self.assertEqual(capabilities['content'], 'admitted')
        self.assertEqual(projection['tracks'][0]['text'], 'one two three')
        protected = self.child.read_bytes(), self.audio.read_bytes(), evidence.read_bytes()
        with self.assertRaisesRegex(FutureSongError, r'^validation failed \(ValueError\)$') as refused:
            self.workflow.validate(self.video)
        self.assertIs(type(refused.exception.__cause__), ValueError)
        from stemwise_engine.workflow_records import WorkflowInputError
        self.assertIsInstance(refused.exception.__context__, WorkflowInputError)
        self.assertEqual(refused.exception.__context__.failure_details['reasonCode'], 'line-unresolved')
        self.assertEqual((self.child.read_bytes(), self.audio.read_bytes(), evidence.read_bytes()), protected)
        self.assertFalse((draft.parent/'agent-review.json').exists())
        self.assertFalse(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video)))


    def limited_recording(self, text, recognized, *, audio_sha=None):
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.lyrics_intake import read_recording_draft
        from lyric_content_fixtures import write_recognition
        draft = self.workflow._job_dir(self.video)/'drafts'
        draft.mkdir(exist_ok=True)
        (draft/'original.txt').write_text(text.rstrip('\n') + '\n', encoding='utf-8')
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': self.video, 'originalLanguage': 'en',
            'originalScript': 'Latn', 'sources': {'original': {'name': 'Exact recording source'}}})
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.root)
        audio_sha = audio_sha or sha256_file(self.audio)
        evidence = write_recognition(workspace.job_dir(self.video), text, tuple(range(len(text.splitlines()))),
            audio_sha, recognized=recognized)
        _, draft_sha, _, metadata_sha = read_recording_draft(draft.parent)
        job = self.workflow._load_job(self.video)
        job['recordingEvidenceInputs'] = {'audioSha256': audio_sha, 'draftSha256': draft_sha,
            'metadataSha256': metadata_sha, 'recognition': {'path': evidence.name, 'sha256': sha256_file(evidence)}}
        self.workflow._save_job(job)
        return evidence

    def test_uncertain_line_preserves_exact_excerpt_without_completion(self):
        from stemwise_engine.editorial_admission import available_lyrics
        evidence = self.limited_recording('alpha beta\nwrong\nomega tail', 'alpha beta other omega tail')
        job = self.workflow._load_job(self.video)
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        projection, capabilities = available_lyrics(self.workflow, job, audio)
        self.assertEqual(capabilities['content'], 'partial')
        self.assertEqual(capabilities['contentExcerpt']['sourcePhysicalIndices'], [0, 2])
        self.assertEqual(projection['tracks'][0]['text'], 'alpha beta\nomega tail')
        protected = {path: path.read_bytes() for path in (self.child, self.audio, evidence)}
        with self.assertRaisesRegex(FutureSongError, r'^validation failed \(ValueError\)$') as refused:
            self.workflow.validate(self.video)
        self.assertIs(type(refused.exception.__cause__), ValueError)
        from stemwise_engine.workflow_records import WorkflowInputError, LyricsContentBlocker
        blocker = refused.exception.__context__
        self.assertIsInstance(blocker, LyricsContentBlocker)
        self.assertEqual(blocker.reasons, ('content-sequence-uncertain',))
        from stemwise_engine.common import json_bytes
        current = self.workflow._preflight_drafts(job, roles=frozenset({'original'}))[0]
        self.assertEqual(blocker.binding, {'sourcePhysicalIndices': [0, 1, 2],
            'audioSha256': audio['sha256'], 'canonicalPlainSha256': hashlib.sha256(json_bytes(current)).hexdigest()})
        self.assertIsInstance(blocker.__context__, WorkflowInputError)
        self.assertEqual(blocker.__context__.failure_details['reasonCode'], 'line-unresolved')
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        self.assertFalse((self.workflow.ready/self.child.name).exists())



    def test_partial_plain_withholds_all_possible_repeat_and_adjacent_insertion_owners(self):
        from stemwise_engine.editorial_admission import available_lyrics
        self.limited_recording('echo\necho\nsafe', 'echo safe')
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        projection, capabilities = available_lyrics(self.workflow, self.workflow._load_job(self.video), audio)
        self.assertEqual(projection['tracks'][0]['text'], 'safe')
        self.assertEqual(capabilities['contentExcerpt']['sourcePhysicalIndices'], [2])
        draft = self.workflow._job_dir(self.video)/'drafts'
        shutil.rmtree(draft)
        self.limited_recording('alpha\nbeta\ngamma\ndelta', 'alpha extra beta gamma delta')
        projection, capabilities = available_lyrics(self.workflow, self.workflow._load_job(self.video), audio)
        self.assertEqual(projection['tracks'][0]['text'], 'gamma\ndelta')
        self.assertEqual(capabilities['contentExcerpt']['sourcePhysicalIndices'], [2, 3])

    def test_partial_plain_rejects_different_recording_duration_and_no_exact_lines(self):
        import json
        from stemwise_engine.editorial_admission import available_lyrics
        evidence = self.limited_recording('alpha beta\nwrong\nomega tail', 'alpha beta other omega tail')
        job = self.workflow._load_job(self.video)
        audio = self.workflow._completed_analysis_audio(self.pipeline, self.video)
        cache = json.loads(evidence.read_bytes())
        cache['provenance']['durationMs'] = cache['workerOutput']['durationMs'] = 2000
        atomic_write_json(evidence, cache)
        job['recordingEvidenceInputs']['recognition']['sha256'] = sha256_file(evidence)
        projection, capabilities = available_lyrics(self.workflow, job, audio)
        self.assertEqual(projection['tracks'], [])
        self.assertEqual(capabilities['content'], 'unavailable')
        shutil.rmtree(self.workflow._job_dir(self.video)/'drafts')
        self.limited_recording('red red', 'blue')
        projection, capabilities = available_lyrics(self.workflow, self.workflow._load_job(self.video), audio)
        self.assertEqual(projection['tracks'], [])
        self.assertEqual(capabilities['content'], 'unavailable')

    def test_incomplete_refresh_preserves_qualified_prior_delivery(self):
        self.supported_original()
        pcm_sha = sha256_file(self.audio)
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        publication = self.workflow.ready/self.child.name
        protected = publication.read_bytes(), self.child.read_bytes()
        self.limited_recording('alpha beta\nwrong\nomega tail', 'alpha beta other omega tail', audio_sha=pcm_sha)
        for _ in range(2):
            with self.assertRaises(FutureSongError):
                self.workflow.validate(self.video)
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
            self.assertEqual((publication.read_bytes(), self.child.read_bytes()), protected)
        self.assertEqual(self.workflow.status(self.video)['state'], 'STATUS_ERROR')


    def test_pending_display_review_resumes_after_capability_completion(self):
        import json
        self.supported_original()
        self.workflow.validate(self.video)
        job = self.workflow._load_job(self.video)
        pointer = copy.deepcopy(job['displayReview'])
        candidate = self.workflow._alignment_state_path(self.video).with_name('candidate.private.lyrics.json')
        candidate_raw = candidate.read_bytes()
        body = self.workflow._display_review_attempt_dir(self.video, pointer['attemptId'])/'original.txt'
        body.write_text('morning world!\nhello world\n', encoding='utf-8')
        self.assertTrue(self.workflow._is_display_only_validation(job))
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('must reuse alignment')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        document, _ = read_child(self.child, self.pipeline.config)
        self.assertEqual(document['lyrics']['tracks'][0]['lines'][0]['text'], 'morning world!')
        self.assertEqual(candidate.read_bytes(), candidate_raw)
        self.assertEqual(self.workflow._load_job(self.video)['displayReview'], pointer)
        preserved = self.child.read_bytes()
        body.write_text('different words\nhello world\n', encoding='utf-8')
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('must not realign')):
            with self.assertRaises(FutureSongError):
                self.workflow.validate(self.video)
        self.assertEqual(self.child.read_bytes(), preserved)
        self.assertEqual(candidate.read_bytes(), candidate_raw)

    def test_stale_capability_applies_bound_metadata_review_before_finalize(self):
        from stemwise_engine.metadata_research import metadata_sha256
        from stemwise_engine.original_delivery import read_bundle
        draft, review, _, _ = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        self.workflow.validate(self.video)
        document, _ = read_child(self.child, self.pipeline.config)
        review['metadataReview'] = {'formatVersion': 1, 'videoId': self.video,
            'originalMetadataSha256': metadata_sha256(document['metadata']),
            'sourcesSha256': sha256_file(draft.parent/'sources.json'),
            'title': 'Corrected reviewed title', 'artists': ['Corrected reviewed artist'],
            'evidenceUrls': [self.workflow._artwork_source_url(self.video)], 'recordingIdentityConfirmed': True}
        protected = self.child.read_bytes()
        sources_sha = review['metadataReview']['sourcesSha256']
        review['metadataReview']['sourcesSha256'] = '0'*64
        atomic_write_json(draft.parent/'agent-review.json', review)
        with self.assertRaises((FutureSongError, ValueError)):
            self.workflow.validate(self.video)
        self.assertEqual(self.child.read_bytes(), protected)
        review['metadataReview']['sourcesSha256'] = sources_sha
        atomic_write_json(draft.parent/'agent-review.json', review)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('metadata must not infer')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        corrected, _ = read_child(self.child, self.pipeline.config)
        self.assertEqual(corrected['metadata']['title'], 'Corrected reviewed title')
        self.assertEqual(corrected['metadata']['artists'], ['Corrected reviewed artist'])
        self.workflow.finalize(self.video)
        published, _, _ = read_bundle(self.workflow.ready/self.child.name, self.pipeline.config)
        self.assertEqual(published['metadata'], corrected['metadata'])
        public = self.workflow.ready/self.child.name
        before_renewal = public.read_bytes()
        review['metadataReview'].update(originalMetadataSha256=metadata_sha256(published['metadata']),
            title='Renewed reviewed title', artists=['Renewed reviewed artist'])
        atomic_write_json(draft.parent/'agent-review.json', review)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('metadata must not infer')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        self.assertEqual(public.read_bytes(), before_renewal)
        self.workflow.finalize(self.video)
        renewed, _, _ = read_bundle(public, self.pipeline.config)
        self.assertEqual((renewed['metadata']['title'], renewed['metadata']['artists']),
            ('Renewed reviewed title', ['Renewed reviewed artist']))

    def test_prose_helper_changes_preserve_synced_delivery_but_execution_changes_do_not(self):
        from stemwise_engine.alignment_attempt import AttemptInputs
        self.supported_original()
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        job = self.workflow._load_job(self.video)
        source = self.root/'PC side/stemwise-playlist-builder/src'
        capture = lambda: AttemptInputs.capture('b'*32, 'e'*64,
            self.workflow._job_dir(self.video)/'sources.json', self.workflow.config_path, source)
        before = capture()
        helper = source/'lyrics_alignment.py'
        raw = helper.read_text(encoding='utf-8')
        helper.write_text(raw + '\n# Documentation clarification only.\n', encoding='utf-8')
        self.assertEqual(capture(), before)
        self.assertTrue(self.workflow._validated_snapshot_is_current(job, require_delivery=True))
        public = self.workflow.ready/self.child.name
        published = public.read_bytes()
        self.assertEqual(self.workflow.validate(self.video)['state'], 'DELIVERED')
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        self.assertEqual(public.read_bytes(), published)
        helper.write_text(raw + '\nCHANGED_EXECUTABLE_POLICY = True\n', encoding='utf-8')
        self.assertNotEqual(capture(), before)
        self.assertFalse(self.workflow._validated_snapshot_is_current(job, require_delivery=True))
        with self.assertRaises(FutureSongError):
            self.workflow.validate(self.video)
        self.assertEqual(public.read_bytes(), published)

    def test_synced_capability_artwork_repair_keeps_historical_timing_binding(self):
        from PIL import Image
        from stemwise_engine.original_delivery import read_bundle
        self.supported_original()
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        public = self.workflow.ready/self.child.name
        before, _, original = read_bundle(public, self.pipeline.config)
        def supplied_artwork(info, source_id, output, pipeline):
            Image.new('RGB', (2, 2), (29, 87, 143)).save(output, format='PNG')
            return {'file': 'cover.png', 'kind': 'youtube-thumbnail',
                'source': {'name': 'Reviewed fixture', 'url': self.workflow._artwork_source_url(self.video)}}
        with patch.object(self.workflow, '_reviewed_artwork_source', return_value=self.video), \
                patch.object(self.workflow, '_artwork_source_info', return_value={'duration': 1.0}), \
                patch.object(self.workflow, '_create_selected_artwork', side_effect=supplied_artwork):
            self.workflow.select_artwork(self.video, self.workflow._artwork_source_url(self.video))
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        after, cover, identity = read_bundle(public, self.pipeline.config)
        self.assertEqual(after['lyrics'], before['lyrics'])
        self.assertEqual((after['analysis'], identity), (before['analysis'], original))
        self.assertNotEqual(cover, PNG_1X1)

    def test_same_payload_review_renewal_rebinds_completed_delivery(self):
        from stemwise_engine.original_delivery import read_bundle
        draft, review, _, _ = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        destination = self.workflow.ready/self.child.name
        before, cover, original = read_bundle(destination, self.pipeline.config)
        old_validation = copy.deepcopy(self.workflow._load_job(self.video)['validation'])
        review['companionReviews']['translation']['completedAt'] = '2026-11-05T00:00:00Z'
        review['companionReviews']['translation']['rationale'] = 'Exact bound candidate reviewed again.'
        atomic_write_json(draft.parent/'agent-review.json', review)
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        after, after_cover, after_original = read_bundle(destination, self.pipeline.config)
        self.assertEqual(after['lyrics'], before['lyrics'])
        self.assertEqual((after_cover, after_original), (cover, original))
        self.assertNotEqual(self.workflow._load_job(self.video)['validation']['inputSha256'], old_validation['inputSha256'])
        self.assertTrue(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video), require_delivery=True))

    @patch('stemwise_engine.recording_analysis.collect_acoustic_provenance',
        new=lambda **options: {'packages': {'torch': {'version': 'fixture'}},
            **({'execution': {'main': {'device': 'cpu', 'torch': 'fixture'}}}
               if options.get('observe_execution', False) else {})})
    def test_native_private_original_waits_for_bound_companions_without_repeating_inference(self):
        from stemwise_engine.recording_workflow import RecordingWorkflow
        from stemwise_engine.original_delivery import read_bundle
        from qobuz_fixtures import write_download
        from stemwise_engine.recording_analysis import acoustic_producer_identity
        library = self.root/'PC side/qobuz-comparison'
        original_path = library/'audio/qobuz/11/source.flac'
        original_path.parent.mkdir(parents=True)
        shutil.copyfile(self.audio.parent/'source.flac', original_path)
        write_download(original_path.parent, {'qobuzTrackId': 11, 'file': original_path.name})
        atomic_write_json(library/'catalogue.json', {'rows': [{'videoId': self.video, 'position': 1,
            'candidates': [{'id': 11, 'title': 'Fixture', 'artist': 'Fixture artist'}]}]})
        (library/'comparison-results').mkdir()
        atomic_write_json(library/'comparison-results/library-files.json', {
            'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/source.flac', 'title': 'Fixture', 'artist': 'Fixture artist'}],
            'playlist': [{'videoId': self.video, 'source': 'qobuz', 'trackId': 11}],
            'catalogueSha256': sha256_file(library/'catalogue.json')})
        checkpoint = self.pipeline.load_checkpoint(self.video)
        wav = self.audio.read_bytes()
        self.workflow = RecordingWorkflow(self.workflow.config_path, 'qobuz:11')
        job = self.workflow._base_job(self.video, f'https://youtu.be/{self.video}')
        self.workflow._save_job(job)
        job['state'] = 'WAITING_FOR_EDITORIAL'
        self.workflow._save_job(job)
        self.pipeline = self.workflow._pipeline()
        self.child = self.pipeline.child_path(self.video)
        self.child.parent.mkdir(parents=True, exist_ok=True)
        document = song_document(self.video)
        document['source']['recording'] = self.workflow.recording.identity
        document['generator']['version'] = self.workflow.config['generatorVersion']
        create_child_zip(self.child, document, self.root/'cover.png', self.pipeline.config)
        temporary = self.workflow.analysis_temp/self.video
        temporary.parent.mkdir(parents=True, exist_ok=True)
        create_manifested_directory(temporary.parent, temporary,
            owner={'kind': 'analysis-temporary', 'videoId': self.video}, workspace_root=self.root)
        self.audio = temporary/'analysis.wav'
        self.audio.write_bytes(wav)
        checkpoint.update(childSha256=sha256_file(self.child), recordingInput=self.workflow.recording.identity,
            acousticProducerIdentity=acoustic_producer_identity(self.pipeline.config))
        self.pipeline.save_checkpoint(checkpoint)
        atomic_write_json(self.workflow._job_dir(self.video)/'sources.json', {'videoId': self.video})
        draft, review, _, _ = self.companion_publication_fixture()
        public = self.root/'Ready to Import'/self.child.name
        before, cover = read_child(self.child, self.pipeline.config)
        private = self.workflow._alignment_state_path(self.video)
        retained = {path: path.read_bytes() for path in (private,
            private.with_name('candidate.private.lyrics.json'), self.audio, self.child)}
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            with self.assertRaisesRegex(FutureSongError, 'required companion'):
                self.workflow.validate(self.video)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertFalse(public.exists())
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)
        self.approve_current_companions(draft, review)
        self.workflow = RecordingWorkflow(self.workflow.config_path, 'qobuz:11')
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertFalse(public.exists())
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        after, after_cover, after_original = read_bundle(public, self.pipeline.config)
        self.assertEqual([track['kind'] for track in after['lyrics']['tracks']], ['original', 'translation', 'transliteration'])
        self.assertEqual((after['analysis'], after_cover), (before['analysis'], cover))
        self.assertEqual(after_original['sha256'], sha256_file(original_path))
        self.assertEqual(after_original['byteCount'], original_path.stat().st_size)
        self.assertTrue(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video), require_delivery=True))

    def test_staged_refresh_accepts_next_review_without_retired_pcm(self):
        from stemwise_engine.original_delivery import read_bundle
        draft, review, _, _ = self.companion_publication_fixture()
        self.approve_current_companions(draft, review)
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        # Complete admitted Original evidence does not need the partial-content retention exemption.
        job = self.workflow._load_job(self.video)
        job.pop('recordingEvidenceInputs', None)
        self.workflow._save_job(job)
        self.workflow._remove_retained_analysis_audio(self.video)
        self.assertFalse(self.audio.exists())
        destination = self.workflow.ready/self.child.name
        before, cover, original = read_bundle(destination, self.pipeline.config)
        prior_sha = sha256_file(destination)
        (draft/'translation-en.txt').write_text('good day world\nhello world\n', encoding='utf-8')
        from stemwise_engine.workflow_records import WorkflowInputError
        with self.assertRaises(WorkflowInputError) as refused:
            self.workflow.validate(self.video)
        self.assertEqual(refused.exception.failure_details['reasonCode'], 'review-stale')
        self.assertIn('Translation', refused.exception.failure_details['affectedInput'])
        self.assertEqual(sha256_file(destination), prior_sha)
        audio = self.workflow._load_job(self.video)['validation']['audioSha256']
        from stemwise_engine.companion_review import companion_binding
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.editorial_admission import available_lyrics
        job = self.workflow._load_job(self.video)
        compiled, _, _ = self.workflow._preflight_drafts(job)
        source, draft_sha, _, metadata_sha = read_recording_draft(draft.parent)
        _, capabilities = available_lyrics(self.workflow, job, {'sha256': audio, 'durationMs': 1000})
        renewed = review['companionReviews']['translation']
        renewed['binding'] = companion_binding(self.video, next(t for t in compiled['tracks'] if t['kind'] == 'original'),
            next(t for t in compiled['tracks'] if t['kind'] == 'translation'), original_input_sha256=draft_sha,
            candidate_input_sha256=sha256_file(draft/'translation-en.txt'), original_metadata_sha256=metadata_sha,
            audio_sha256=audio, sources_sha256=sha256_file(draft.parent/'sources.json'), content_admission=capabilities['contentAdmission'])
        renewed['coveredOccurrenceIds'] = renewed['binding']['occurrenceIds']
        atomic_write_json(draft.parent/'agent-review.json', review)
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('no inference')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        after, after_cover, after_original = read_bundle(destination, self.pipeline.config)
        translation = next(t for t in after['lyrics']['tracks'] if t['kind'] == 'translation')
        self.assertEqual(translation['format'], 'synced')
        self.assertEqual([line['text'] for line in translation['lines']], ['good day world', 'hello world'])
        self.assertEqual([(line['startMs'], line['endMs']) for line in translation['lines']],
            [(line['startMs'], line['endMs']) for line in after['lyrics']['tracks'][0]['lines']])
        self.assertEqual((after['analysis'], after_cover, after_original), (before['analysis'], cover, original))

    def test_initial_capability_artwork_repair_after_pcm_retirement(self):
        self.supported_original()
        from PIL import Image
        from stemwise_engine.original_delivery import read_bundle
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        self.assertFalse(self.audio.exists())
        destination = self.workflow.ready/self.child.name
        before, _, original = read_bundle(destination, self.pipeline.config)
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        def supplied_artwork(info, source_id, output, pipeline):
            Image.new('RGB', (2, 2), (29, 87, 143)).save(output, format='PNG')
            return {'file': 'cover.png', 'kind': 'youtube-thumbnail',
                'source': {'name': 'Reviewed fixture', 'url': self.workflow._artwork_source_url(self.video)}}
        with patch.object(self.workflow, '_reviewed_artwork_source', return_value=self.video), \
                patch.object(self.workflow, '_artwork_source_info', return_value={'duration': 1.0}), \
                patch.object(self.workflow, '_create_selected_artwork', side_effect=supplied_artwork):
            self.workflow.select_artwork(self.video, self.workflow._artwork_source_url(self.video))
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        after, cover, identity = read_bundle(destination, self.pipeline.config)
        self.assertEqual((after['analysis'], after['lyrics'], identity), (before['analysis'], before['lyrics'], original))
        self.assertNotEqual(cover, PNG_1X1)
        self.assertFalse((self.workflow._job_dir(self.video)/'artwork-repair.rollback.zip').exists())

    def test_artwork_repair_cannot_authorize_incomplete_lyrics_refresh(self):
        before, original, _ = self.prepare_refreshed_artwork()
        self.workflow.finalize(self.video)
        destination = self.workflow.ready/self.child.name
        protected = destination.read_bytes(), self.child.read_bytes()
        pcm_sha = self.workflow._load_job(self.video)['validation']['audioSha256']
        self.limited_recording('alpha beta\nother word\nomega tail', 'alpha beta other word omega tail', audio_sha=pcm_sha)
        with self.assertRaises(FutureSongError):
            self.workflow.validate(self.video)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertEqual((destination.read_bytes(), self.child.read_bytes()), protected)


    def test_normal_validate_finalize_reads_back_one_audio_bearing_package(self):
        self.supported_original()
        from stemwise_engine.original_delivery import read_bundle
        self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        result = self.workflow.finalize(self.video)
        self.assertEqual(result['state'], 'DELIVERED')
        destination = self.workflow.ready/self.child.name
        document, cover, identity = read_bundle(destination, self.pipeline.config, metadata=self.child)
        self.assertEqual(document['lyrics']['status'], 'complete')
        self.assertEqual(cover, PNG_1X1)
        self.assertEqual(identity['name'], 'original.flac')
        ledger = self.workflow._song_ledger(self.video)
        receipt = ledger.record['observedFinalization']
        self.assertEqual(receipt['packageSha256'], sha256_file(destination))
        self.assertEqual(receipt['runSha256'], self.workflow._load_job(self.video)['delivery']['runSha256'])
        self.assertGreater(receipt['maxInputBytes'], 0)
        self.assertGreater(receipt['seconds'], 0)
        self.assertTrue(self.workflow._validated_snapshot_is_current(
            self.workflow._load_job(self.video), require_delivery=True))

    def test_prior_action_metadata_handoff_survives_status_then_finalize(self):
        from stemwise_engine.common import json_bytes
        from stemwise_engine.original_delivery import read_bundle
        from workflow_fixture import nested_case
        for replacement_action in (False, True):
            with self.subTest(replacement_action=replacement_action):
                case = nested_case(self, AvailableCompletionTests)
                previous = case.workflow._effective_policy({})
                if not replacement_action:
                    previous['privateReports']['workflowOperationLedger']['actions'].remove('ReplaceAnalysis')
                previous_sha = hashlib.sha256(json_bytes(previous)).hexdigest()
                job = case.workflow._load_job(case.video)
                job['policy']['lyricsPolicySha256'] = previous_sha
                case.workflow._save_job(job)
                case.supported_original()
                self.assertEqual(case.workflow.validate(case.video)['state'], 'VALIDATED')
                before = case.workflow._load_job(case.video, require_active_policy=False)
                current = FutureSongWorkflow(case.workflow.config_path)
                status = current.status(case.video)
                self.assertIn('Finalize', status['permittedActions'])
                self.assertEqual(current.finalize(case.video)['state'], 'DELIVERED')
                delivered = current._load_job(case.video)
                self.assertEqual(delivered['policy'], before['policy'])
                self.assertEqual(delivered['validation'], before['validation'])
                destination = current.ready/case.child.name
                document, _, identity = read_bundle(destination, case.pipeline.config, metadata=case.child)
                self.assertEqual(document['lyrics']['status'], 'complete')
                self.assertEqual(identity['name'], 'original.flac')
                self.assertTrue(current._validated_snapshot_is_current(delivered, require_delivery=True))

    def test_staged_artwork_review_renewal_preserves_recording_without_retired_pcm(self):
        self.check_artwork_review_renewal(completed=False)


    def test_completed_artwork_review_renewal_preserves_pending_and_accepted_display(self):
        from workflow_fixture import nested_case
        for accepted in (False, True):
            with self.subTest(accepted=accepted):
                case = nested_case(self, AvailableCompletionTests)
                case.check_artwork_review_renewal(completed=True, accepted=accepted)

    def check_artwork_review_renewal(self, *, completed, accepted=False):
        import json
        from PIL import Image
        from stemwise_engine.original_delivery import read_bundle
        from stemwise_engine.metadata_research import metadata_sha256
        draft, review, original, _ = self.companion_publication_fixture()
        document, _ = read_child(self.child, self.pipeline.config)
        review['metadataReview'] = {'formatVersion': 1, 'videoId': self.video,
            'originalMetadataSha256': metadata_sha256(document['metadata']),
            'sourcesSha256': sha256_file(draft.parent/'sources.json'),
            'title': document['metadata']['title'], 'artists': document['metadata']['artists'],
            'evidenceUrls': [self.workflow._artwork_source_url(self.video)], 'recordingIdentityConfirmed': True}
        self.approve_current_companions(draft, review)
        if accepted:
            job = self.workflow._load_job(self.video)
            pointer = job['displayReview']
            state = json.loads(self.workflow._alignment_state_path(self.video).read_bytes())
            run = json.loads((self.workflow.report_store.report_root(self.video)/'runs'/pointer['alignmentRunId']/'run.json').read_bytes())
            job['artworkReview'] = {'result': 'accepted', 'sourceVideoId': self.video,
                'sourceUrl': self.workflow._artwork_source_url(self.video),
                'artworkSha256': hashlib.sha256(PNG_1X1).hexdigest(), 'reviewedAt': '2026-11-05T00:00:00Z'}
            self.workflow.review_construction.create_display_review_attempt(job, original_text=original['text'],
                editorial_sha256=self.workflow._editorial_sha(job),
                analysis_audio=self.workflow._completed_analysis_audio(self.pipeline, self.video),
                alignment_state=state, alignment_run_sha256=pointer['alignmentRunSha256'], alignment_artifacts=run['artifacts'])
            self.workflow._save_job(job)
            checkpoint = self.pipeline.load_checkpoint(self.video)
            checkpoint['ownerLyricsSha256'] = 'absent'
            self.pipeline.save_checkpoint(checkpoint)
            self.workflow.validate(self.video)
            self.assertEqual(self.workflow._load_job(self.video)['displayReview']['result'], 'accepted-display-only')
        self.workflow.validate(self.video)
        job = self.workflow._load_job(self.video)
        job.pop('recordingEvidenceInputs', None)
        self.workflow._save_job(job)
        self.workflow.finalize(self.video)
        self.assertFalse(self.audio.exists())
        destination = self.workflow.ready/self.child.name
        before, _, original_identity = read_bundle(destination, self.pipeline.config)
        def artwork(info, source_id, output, pipeline):
            Image.new('RGB', (2, 2), (45, 95, 145)).save(output, format='PNG')
            return {'file': 'cover.png', 'kind': 'youtube-thumbnail',
                'source': {'name': 'Reviewed fixture', 'url': self.workflow._artwork_source_url(source_id)}}
        # A different inspected source may supply different artwork for the same recording.
        job = self.workflow._load_job(self.video)
        job['durationSeconds'] = 1.0
        self.workflow._save_job(job)
        artwork_video = 'BBBBBBBBBBB'
        with patch.object(self.workflow, '_reviewed_artwork_source', return_value=artwork_video), \
                patch.object(self.workflow, '_artwork_source_info', return_value={'duration': 1.0}), \
                patch.object(self.workflow, '_create_selected_artwork', side_effect=artwork):
            self.workflow.select_artwork(self.video, self.workflow._artwork_source_url(artwork_video))
        if completed:
            self.workflow.finalize(self.video)
        public_raw = destination.read_bytes()
        review['companionReviews']['translation']['completedAt'] = '2026-11-05T12:00:00Z'
        review['companionReviews']['translation']['rationale'] = 'Reviewed exact current translation again.'
        atomic_write_json(draft.parent/'agent-review.json', review)
        self.workflow = FutureSongWorkflow(self.workflow.config_path)
        destination.write_bytes(b'damaged retained publication')
        with self.assertRaises((FutureSongError, ValueError)):
            self.workflow.validate(self.video)
        self.assertEqual(destination.read_bytes(), b'damaged retained publication')
        destination.write_bytes(public_raw)
        if not completed:
            baseline = {path: path.read_bytes() if path.exists() else None
                for path in self.workflow._capability_validation_paths(self.video) if path != self.workflow._job_path(self.video)}
            previous_input = self.workflow._load_job(self.video)['validation']['inputSha256']
            save = self.workflow._save_job
            def fail_validation_commit(job):
                if job.get('validation', {}).get('inputSha256') != previous_input:
                    raise OSError('interrupted capability renewal commit')
                save(job)
            with patch.object(self.workflow, '_save_job', side_effect=fail_validation_commit):
                with self.assertRaisesRegex(OSError, 'interrupted capability renewal commit'):
                    self.workflow.validate(self.video)
            self.assertEqual({path: path.read_bytes() if path.exists() else None for path in baseline}, baseline)
            self.assertEqual(destination.read_bytes(), public_raw)
            self.workflow = FutureSongWorkflow(self.workflow.config_path)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=AssertionError('must reuse numerical results')):
            self.assertEqual(self.workflow.validate(self.video)['state'], 'VALIDATED')
        self.assertEqual(destination.read_bytes(), public_raw, 'Private revalidation must preserve the published ZIP')
        projected, _ = read_child(self.child, self.pipeline.config)
        self.assertEqual(projected['lyrics']['tracks'][0], before['lyrics']['tracks'][0])
        self.assertEqual(projected['lyrics']['tracks'][0]['format'], 'synced')
        self.workflow.finalize(self.video)
        after, cover, identity = read_bundle(destination, self.pipeline.config)
        self.assertEqual((after['analysis'], identity), (before['analysis'], original_identity))
        self.assertEqual(after['lyrics']['tracks'][0], before['lyrics']['tracks'][0])
        self.assertNotEqual(cover, PNG_1X1)
        self.assertTrue(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video), require_delivery=True))
        if completed and accepted:
            job = self.workflow._load_job(self.video)
            repair = job['capabilityRefresh']['previousArtworkRepair']
            report = self.workflow.report_store.report_root(self.video)/'runs'/repair['runId']/'run.json'
            retained_report = report.read_bytes()
            report.write_bytes(retained_report + b' ')
            public = destination.read_bytes()
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
            self.assertEqual(destination.read_bytes(), public)
            report.write_bytes(retained_report)
            review['companionReviews']['translation']['completedAt'] = '2026-11-05T13:00:00Z'
            atomic_write_json(draft.parent/'agent-review.json', review)
            self.workflow.validate(self.video)
            self.workflow.finalize(self.video)
            repeated, repeated_cover, repeated_original = read_bundle(destination, self.pipeline.config)
            self.assertEqual((repeated['lyrics']['tracks'][0], repeated_cover, repeated_original),
                (before['lyrics']['tracks'][0], cover, original_identity))

    def prepare_refreshed_artwork(self):
        from PIL import Image
        from stemwise_engine.original_delivery import read_bundle
        self.supported_original()
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        destination = self.workflow.ready/self.child.name
        before, _, original = read_bundle(destination, self.pipeline.config)
        previous_sha = sha256_file(destination)
        source_url = self.workflow._artwork_source_url(self.video)
        def supplied_artwork(info, source_id, output, pipeline):
            Image.new('RGB', (2, 2), (29, 87, 143)).save(output, format='PNG')
            return {'file': 'cover.png', 'kind': 'youtube-thumbnail',
                'source': {'name': 'Reviewed supplied artwork', 'url': source_url}}
        with patch.object(self.workflow, '_reviewed_artwork_source', return_value=self.video), \
                patch.object(self.workflow, '_artwork_source_info', return_value={'duration': 1.0}), \
                patch.object(self.workflow, '_create_selected_artwork', side_effect=supplied_artwork):
            self.workflow.select_artwork(self.video, source_url)
        job = self.workflow._load_job(self.video)
        self.assertEqual(job['state'], 'VALIDATED')
        self.assertEqual(sha256_file(destination), previous_sha)
        self.assertTrue(self.workflow._validated_snapshot_is_current(job))
        return before, original, previous_sha

    def test_refreshed_delivery_can_replace_reviewed_artwork_without_changing_audio_or_analysis(self):
        from stemwise_engine import original_delivery
        before, original, previous_sha = self.prepare_refreshed_artwork()
        destination = self.workflow.ready/self.child.name
        job = self.workflow._load_job(self.video)
        frozen_inputs = self.workflow._capability_input_sha(self.video)
        publish = original_delivery.publish_bundle
        observed = []
        def publish_and_inspect(*args, **kwargs):
            digest = publish(*args, **kwargs)
            self.assertEqual(self.workflow._capability_input_sha(self.video), frozen_inputs)
            _, _, retained, _ = original_delivery.delivered_capability_inputs(self.workflow, job)
            self.assertEqual(sha256_file(retained), previous_sha)
            self.assertEqual(retained.parent, self.workflow.history/self.video)
            retained_raw = retained.read_bytes()
            try:
                retained.write_bytes(b'unrelated rollback bytes')
                with self.assertRaisesRegex(ValueError, 'Previously published Original is unavailable'):
                    original_delivery.delivered_capability_inputs(self.workflow, job)
            finally:
                retained.write_bytes(retained_raw)
            wrong = copy.deepcopy(job)
            wrong['artworkRepair']['previousDelivery']['original']['sha256'] = '0'*64
            from stemwise_engine.workflow_records import ArtworkUnavailableError
            with self.assertRaisesRegex(ArtworkUnavailableError, 'artwork repair run is missing or changed'):
                original_delivery.delivered_capability_inputs(self.workflow, wrong)
            self.assertEqual(sha256_file(destination), digest)
            observed.append(retained)
            return digest
        with patch.object(original_delivery, 'publish_bundle', side_effect=publish_and_inspect):
            self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        self.assertEqual(len(observed), 1)
        updated, cover, updated_original = original_delivery.read_bundle(destination, self.pipeline.config)
        self.assertEqual(updated_original, original)
        self.assertEqual(updated['analysis'], before['analysis'])
        self.assertEqual(updated['lyrics'], before['lyrics'])
        self.assertEqual(updated['source'], before['source'])
        self.assertEqual(updated['metadata'], before['metadata'])
        self.assertNotEqual(cover, PNG_1X1)
        self.assertTrue(self.workflow._validated_snapshot_is_current(self.workflow._load_job(self.video), require_delivery=True))

    def test_refreshed_artwork_finalization_rejects_concurrent_inputs_and_restores_public_package(self):
        from stemwise_engine import original_delivery
        _, original, previous_sha = self.prepare_refreshed_artwork()
        destination = self.workflow.ready/self.child.name
        inputs = self.workflow._job_dir(self.video)/'sources.json'
        publish = original_delivery.publish_bundle
        def publish_and_edit(*args, **kwargs):
            digest = publish(*args, **kwargs)
            atomic_write_json(inputs, {'videoId': self.video, 'concurrentEdit': 'owner input remains unapproved'})
            return digest
        with patch.object(original_delivery, 'publish_bundle', side_effect=publish_and_edit):
            with self.assertRaisesRegex(FutureSongError, 'Editorial inputs or authority changed during finalization'):
                self.workflow.finalize(self.video)
        self.assertEqual(sha256_file(destination), previous_sha)
        self.assertEqual(original_delivery.read_bundle(destination, self.pipeline.config)[2], original)
        self.assertEqual(self.workflow._load_job(self.video)['state'], 'VALIDATED')
        self.assertIn(b'owner input remains unapproved', inputs.read_bytes())
        self.assertTrue(any((self.workflow.history/self.video).glob(f'*.{destination.name}')))

    def test_retired_resource_receipts_do_not_gate_legacy_enhancements(self):
        directory = self.workflow._job_dir(self.video)
        atomic_write_json(directory/'recovery-costs.private.json', {'version': 1,
            'measurements': [{'path': 'obsolete-cost-not-required.json', 'sha256': 'f'*64}]})
        original_audio = self.audio.read_bytes()
        self.audio.write_bytes(b'wrong mandatory recording')
        with patch.object(self.workflow, '_validate_enhancements_action') as unavailable:
            with self.assertRaises(FutureSongError):
                self.workflow.validate(self.video)
            unavailable.assert_not_called()
        self.audio.write_bytes(original_audio)
        def enhancements(_):
            self.assertGreater(self.workflow._song_ledger(self.video).record['analysisInputBytes'], 0)
            return {'state': 'WAITING_FOR_EDITORIAL'}
        with patch.object(self.workflow, '_validate_enhancements_action', side_effect=enhancements):
            with self.assertRaisesRegex(FutureSongError, '^lyrics.meta.json is missing; editorial work is incomplete$'):
                self.workflow.validate(self.video)

    def test_capability_delivery_rejects_tampered_bindings_and_alternate_source(self):
        self.supported_original()
        self.workflow.validate(self.video)
        self.workflow.finalize(self.video)
        job = self.workflow._load_job(self.video)
        for key, value in (('bytes', job['delivery']['bytes']+1), ('path', 'wrong destination'),
                ('runId', 'f'*32), ('runSha256', 'f'*64), ('readyToImport', False),
                ('reviewEvidenceRetained', False)):
            with self.subTest(key=key):
                changed = copy.deepcopy(job)
                changed['delivery'][key] = value
                self.assertFalse(self.workflow._validated_snapshot_is_current(changed, require_delivery=True))
        for key, value in (('runId', 'e'*32), ('runSha256', 'e'*64), ('validatedAt', 'edited time'),
                ('report', 'wrong validation path')):
            with self.subTest(validation=key):
                changed = copy.deepcopy(job)
                changed['validation'][key] = value
                self.assertFalse(self.workflow._validated_snapshot_is_current(changed, require_delivery=True))
        alternate = self.root/'wrong-original.zip'
        alternate.write_bytes(b'wrong publication source')
        self.assertFalse(self.workflow._validated_snapshot_is_current(job, require_delivery=True,
            delivery_source=alternate))
        pointer = report_control_path(self.workflow.report_store.report_root(self.video), 'current-run.json')
        retained = pointer.read_bytes()
        atomic_write_json(pointer, {'runId': 'e'*32, 'runSha256': 'e'*64})
        self.assertFalse(self.workflow._validated_snapshot_is_current(job, require_delivery=True))
        pointer.write_bytes(retained)
        published = self.workflow.ready/self.child.name
        before = published.read_bytes()
        changed = copy.deepcopy(job)
        changed['delivery']['bytes'] += 1
        self.workflow._save_job(changed)
        with self.assertRaises(FutureSongError):
            self.workflow.finalize(self.video)
        self.assertEqual(published.read_bytes(), before)

    def test_capability_finalization_pointer_recovers_before_job_commit(self):
        self.supported_original()
        self.workflow.validate(self.video)
        save = self.workflow._save_job
        def interrupt(job):
            if job['state'] == 'DELIVERED':
                raise OSError('interrupted after finalization publication')
            return save(job)
        with patch.object(self.workflow, '_save_job', side_effect=interrupt):
            with self.assertRaisesRegex(OSError, 'interrupted after finalization'):
                self.workflow.finalize(self.video)
        job = self.workflow._load_job(self.video)
        self.assertEqual(job['state'], 'VALIDATED')
        published = self.workflow.ready/self.child.name
        before = published.read_bytes()
        self.assertFalse(self.workflow._validated_snapshot_is_current(job))
        self.assertTrue(self.workflow._validated_snapshot_is_current(job, allow_finalization_pointer=True))
        self.workflow._song_ledger(self.video).stop('technical-failure', 'unknown-enhancement-owner')
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        self.assertEqual(published.read_bytes(), before)

    def test_capability_delivered_pending_cleanup_requires_explicit_recovery(self):
        self.supported_original()
        self.workflow.validate(self.video)
        with patch.object(self.workflow, '_remove_retained_analysis_audio',
                side_effect=OSError('interrupted before retained audio cleanup')):
            with self.assertRaisesRegex(OSError, 'interrupted before retained audio cleanup'):
                self.workflow.finalize(self.video)
        job = self.workflow._load_job(self.video)
        self.assertEqual(job['state'], 'DELIVERED')
        self.assertTrue(self.audio.exists())
        self.assertFalse(self.workflow._validated_snapshot_is_current(job, require_delivery=True))
        self.assertTrue(self.workflow._validated_snapshot_is_current(job, require_delivery=True,
            allow_pending_delivery_cleanup=True))
        self.workflow._song_ledger(self.video).stop('technical-failure', 'unknown-enhancement-owner')
        self.assertEqual(self.workflow.finalize(self.video)['state'], 'DELIVERED')
        self.assertFalse(self.audio.exists())


    def test_ordinary_recovery_finite_recipes_cache_first_and_unknown_not_replayed(self):
        from contextlib import ExitStack
        from importlib import import_module
        from types import SimpleNamespace
        from stemwise_engine.common import read_bounded_json
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.worker_protocol import WorkerProtocolError
        workspace, original, candidate = self.supported_original()
        job = self.workflow._load_job(self.video)
        mapping, _ = self.workflow._lexical_mapping(self.video)
        state = read_bounded_json(workspace.state_path(self.video), max_bytes=1024*1024)
        canonical = workspace.current_backup_path(self.video)
        declaration = {'contentAdmission': state['contentAdmission'],
            'canonicalPlainSha256': sha256_file(canonical), 'analysisAudio': {'sha256': sha256_file(self.audio)}}
        evidence = {'occurrenceCandidates': [{**{key: row[key] for key in
            ('occurrenceId', 'performedIndex', 'sourcePhysicalIndex')},
            'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'}}
            for row in mapping['performedOccurrences']]}
        attempt = self.workflow._job_dir(self.video)/'finite-recovery'
        names = ('endpoint_ctc_evidence', 'endpoint_repeat_alignment', 'endpoint_candidate_prediction',
                 'endpoint_native_ctc', 'endpoint_owsm_ctc')
        modules = [import_module('stemwise_engine.' + name) for name in names]
        calls = []
        first_failure = True
        def write(module, *args):
            nonlocal first_failure
            calls.append(module.METHOD)
            if module is modules[0] and first_failure:
                first_failure = False
                raise WorkerProtocolError('ctc-worker-failed')
            decision = ({'result': 'supported'} if module is modules[-1] else
                {'result': 'abstain', 'reason': 'ctc-views-or-perturbations-inconclusive'})
            packet = {'method': module.METHOD, 'occurrences': [{'occurrenceId': row['occurrenceId'],
                'assessment': {'startMs': decision, 'endMs': decision}} for row in mapping['performedOccurrences']]}
            path = module._directory(attempt)/'manifest.json'
            atomic_write_json(path, packet)
            return {**packet, 'manifestSha256': sha256_file(path)}
        def adapter(method):
            module = next(module for module in modules if module.METHOD == method)
            return SimpleNamespace(verify=lambda *args, **kwargs:
                read_bounded_json(module._directory(attempt)/'manifest.json', max_bytes=1024*1024))
        args = (attempt, declaration, 'b'*64, self.audio, self.pipeline.config, mapping, evidence, canonical)
        before = self.child.read_bytes()
        with ExitStack() as stack:
            for module in modules:
                stack.enter_context(patch.object(module, 'write_packet', side_effect=lambda *args, module=module: write(module, *args)))
            stack.enter_context(patch('stemwise_engine.endpoint_dispatch.evidence_adapter', side_effect=adapter))
            run = lambda: self.workflow._recover_endpoint_packet(self.workflow._load_job(self.video), args)
            with self.assertRaises(WorkerProtocolError):
                self.workflow._run_mutating_operation(self.video, 'Validate', run)
            ledger = self.workflow._song_ledger(self.video)
            prior_generation = ledger.record['generation']
            self.assertEqual([row['status'] for row in ledger.record['actions'].values()], ['failed'])
            self.assertEqual(self.workflow._run_mutating_operation(self.video, 'Validate', run)['method'], modules[-1].METHOD)
            self.assertEqual(self.workflow._song_ledger(self.video).record['generation'], prior_generation+1)
            self.assertEqual(calls, [modules[0].METHOD, *[module.METHOD for module in modules]])
            calls.clear()
            ledger = self.workflow._song_ledger(self.video)
            for row in ledger.record['actions'].values():
                row['status'] = 'unknown'
            ledger.save()
            self.assertEqual(self.workflow._recover_endpoint_packet(job, args)['method'], modules[-1].METHOD)
            self.assertEqual(calls, [])
            (modules[-1]._directory(attempt)/'manifest.json').unlink()
            with self.assertRaises(SongAttemptBlocked):
                self.workflow._recover_endpoint_packet(job, args)
            self.assertEqual(calls, [])
        self.assertEqual(self.child.read_bytes(), before)

    def test_canonical_endpoint_adapters_enforce_assessments_and_automatic_reviewer(self):
        self._check_endpoint_adapters(public=False)

    def test_resolved_line_endpoint_preserves_progress_without_completion(self):
        self._check_endpoint_adapters(public=True)


    def test_aac_pending_review_retains_exact_pcm_without_publication(self):
        from stemwise_engine.common import run_process_tree
        from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
        from stemwise_engine.waveform import build_waveform
        temporary = self.audio.parent
        fallback = temporary/'source.m4a'
        run_process_tree(['ffmpeg', '-v', 'error', '-nostdin', '-i', str(temporary/'source.flac'),
                          '-c:a', 'aac', '-b:a', '192k', str(fallback)],
                         capture_output=True, check=True, timeout=15)
        decode_analysis_audio(fallback, temporary, timeout_seconds=15)
        duration = probe_duration_ms(self.audio)
        atomic_write_json(temporary/'original-source.json', {'videoId': self.video, 'file': fallback.name,
            'sha256': sha256_file(fallback), 'byteCount': fallback.stat().st_size})
        document, cover = read_child(self.child, self.pipeline.config)
        document['analysis']['durationMs'] = duration
        document['analysis']['waveform'] = build_waveform(self.audio, duration_ms=duration)
        cover_path = self.root/'cover.png'
        cover_path.write_bytes(cover)
        create_child_zip(self.child, document, cover_path, self.pipeline.config)
        checkpoint = self.pipeline.load_checkpoint(self.video)
        checkpoint.update(childSha256=sha256_file(self.child), originalAudio={'sha256': sha256_file(self.audio),
            'byteCount': self.audio.stat().st_size, 'durationMs': duration, 'retained': True})
        self.pipeline.save_checkpoint(checkpoint)
        (temporary/'source.flac').unlink()
        self._check_endpoint_adapters(public=True)

    def test_webm_pending_review_retains_exact_pcm_without_publication(self):
        from stemwise_engine.common import run_process_tree
        from stemwise_engine.waveform import build_waveform
        from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
        temporary = self.audio.parent
        source = temporary/'source.webm'
        run_process_tree(['ffmpeg', '-v', 'error', '-nostdin', '-i', str(temporary/'source.flac'),
            '-c:a', 'libopus', str(source)], capture_output=True, check=True, timeout=15)
        decode_analysis_audio(source, temporary, timeout_seconds=15)
        duration = probe_duration_ms(self.audio)
        atomic_write_json(temporary/'original-source.json', {'videoId': self.video, 'file': source.name,
            'sha256': sha256_file(source), 'byteCount': source.stat().st_size})
        document, cover = read_child(self.child, self.pipeline.config)
        document['analysis']['durationMs'] = duration
        document['analysis']['waveform'] = build_waveform(self.audio, duration_ms=duration)
        cover_path = self.root/'cover.png'
        cover_path.write_bytes(cover)
        create_child_zip(self.child, document, cover_path, self.pipeline.config)
        checkpoint = self.pipeline.load_checkpoint(self.video)
        checkpoint.update(childSha256=sha256_file(self.child), originalAudio={'sha256': sha256_file(self.audio),
            'byteCount': self.audio.stat().st_size, 'durationMs': duration, 'retained': True})
        self.pipeline.save_checkpoint(checkpoint)
        (temporary/'source.flac').unlink()
        self._check_endpoint_adapters(public=True)


    def _check_endpoint_adapters(self, *, public):
        import json
        from dataclasses import replace
        from stemwise_engine import endpoint_ctc_evidence as ctc, endpoint_repeat_alignment as repeat
        from stemwise_engine.endpoint_dispatch import evidence_adapter
        from stemwise_engine.workflow_records import EndpointReviewSelectionError
        from stemwise_engine.workflow_review import EndpointReviewService, EndpointReviewRequest, ReviewCompleted
        workspace, _original, _candidate = self.supported_original()
        workflow = self.workflow
        job = workflow._load_job(self.video)
        job.update(state='LINE_UNRESOLVED', outcome={'code': 'line-unresolved'})
        mapping, mapping_identity = workflow._lexical_mapping(self.video)
        state_path = workspace.state_path(self.video)
        state = json.loads(state_path.read_bytes())
        occurrences = [{**{key: row[key] for key in ('occurrenceId', 'performedIndex', 'sourcePhysicalIndex')},
            'publishDecision': {'result': 'unresolved', 'code': 'line-end-disagreement'},
            'familyCandidates': {'mms-fa': {'startMs': 100+400*index, 'endMs': 350+400*index},
                'language-wav2vec2': {'startMs': 130+400*index, 'endMs': 380+400*index}}}
            for index, row in enumerate(mapping['performedOccurrences'])]
        evidence = {'resultClass': 'line-unresolved', 'occurrenceCandidates': occurrences}
        diagnostics = {'contentAdmission': state['contentAdmission'], 'metrics': {
            'lyric_word_count': 4, 'transcript_word_count': 4, 'matched_word_count': 4,
            'directly_matched_line_count': 2, 'line_count': 2, 'lyric_word_coverage': 1.,
            'transcript_word_coverage': 1., 'direct_line_coverage': 1.,
            'average_word_score': 100., 'timed_span_ratio': .65}}
        blocker = {'reason': 'exhausted-endpoint-cascade', 'alignmentAttemptId': state['attemptId'],
            'bindings': {'alignmentRunSha256': 'b'*64, 'alignmentStateSha256': sha256_file(state_path)}}
        editorial_sha = workflow._capability_input_sha(self.video)
        construction = workflow.review_construction
        construction.context = replace(construction.context, editorial_sha=lambda _: editorial_sha)
        # The prior numerical failure is a known answer; review construction and all packet gates are real.
        with patch.object(construction, 'endpoint_review_source', return_value=(blocker, diagnostics, evidence)), \
                patch.object(construction, 'failed_endpoint_diagnostics', return_value=(diagnostics, evidence)), \
                patch.object(workflow, '_editorial_sha', return_value=editorial_sha):
            pointer = construction.create_endpoint_review_attempt(job)
            attempt = workflow._endpoint_review_attempt_dir(self.video, pointer['attemptId'])
            declaration = json.loads((attempt/'declaration.json').read_bytes())
            review_path = attempt/'review.json'
            pending = json.loads(review_path.read_bytes())
            service = EndpointReviewService(workflow._endpoint_review_context(),
                workflow._review_operations(), workflow._review_policy())
            args = (attempt, declaration, pointer['declarationSha256'], self.audio)
            ctc_path, repeat_path = ctc._directory(attempt)/'manifest.json', repeat._directory(attempt)/'manifest.json'

            def publish_packet(*, abstain=False):
                entries = []
                for index, occurrence in enumerate(declaration['occurrences']):
                    times = {'startMs': 100+400*index, 'endMs': 350+400*index}
                    decisions = {boundary: {'result': 'supported', 'candidateMs': time}
                                 for boundary, time in times.items()}
                    assessment = {boundary: {'result': 'abstain' if abstain else 'supported',
                        'candidateMs': None if abstain else time, 'candidatesAgree': False,
                        'supportedFamilies': [] if abstain else ['mms-fa'],
                        'reason': 'ctc-views-or-perturbations-inconclusive' if abstain else 'stable-ctc-support-review-required'}
                        for boundary, time in times.items()}
                    entry = {'occurrenceId': occurrence['occurrenceId'], 'assessment': assessment}
                    if not abstain:
                        entry['inference'] = {'records': [{'route': route, 'shiftMs': shift, 'decisions': decisions}
                            for route in ctc.ROUTES for shift in (-5, 0, 5)]}
                    entries.append(entry)
                atomic_write_json(ctc_path, {'method': ctc.METHOD, 'scorerVersion': ctc.SCORER_VERSION,
                    'reviewRequired': True, 'declarationSha256': pointer['declarationSha256'],
                    'analysisAudio': declaration['analysisAudio'], 'canonicalPlainSha256': declaration['canonicalPlainSha256'],
                    'occurrences': entries})
                ctc_binding = {'method': ctc.METHOD, 'manifestSha256': sha256_file(ctc_path)}
                atomic_write_json(repeat_path, {'method': repeat.METHOD, 'predictionVersion': repeat.PREDICTION_VERSION,
                    'ctcEvidence': ctc_binding, 'occurrences': [{'occurrenceId': row['occurrenceId'],
                        'plan': None, 'records': [], 'assessment': row['assessment']} for row in entries]})
                return ctc_binding, {'method': repeat.METHOD, 'manifestSha256': sha256_file(repeat_path)}

            def resolved_review(binding, *, family='mms-fa', reviewer=None):
                review = copy.deepcopy(pending)
                review.update(outcome='resolved', completedAt='2026-10-27T12:00:00Z',
                    reviewer=reviewer or binding['method'], audioEvidence=binding)
                for selection in review['occurrences']:
                    selection.update(startCandidateFamily=family, endCandidateFamily=family)
                atomic_write_json(review_path, review)

            protected = [workflow._owner_sidecar_path(self.video), state_path, workspace.candidate_path(self.video),
                         workspace.mapping_path(self.video), self.child]
            before = {path: path.read_bytes() for path in protected}
            for fault, module, reason in (('abstain', ctc, 'endpoint-review-ctc-evidence-abstained'),
                    ('unsupported-family', ctc, 'endpoint-review-ctc-evidence-abstained'),
                    ('reviewer', repeat, 'endpoint-review-metadata-invalid')):
                with self.subTest(fault=fault):
                    bindings = publish_packet(abstain=fault == 'abstain')
                    binding = bindings[module is repeat]
                    resolved_review(binding, family='language-wav2vec2' if fault == 'unsupported-family' else 'mms-fa',
                        reviewer='Independent Reviewer' if fault == 'reviewer' else None)
                    adapter = evidence_adapter(binding['method'])
                    self.assertIs(adapter.module, module)
                    adapter.verify(*args, binding, config=workflow.config, mapping=mapping,
                        evidence=evidence, canonical=workspace.current_backup_path(self.video))
                    try:
                        with self.assertRaisesRegex(EndpointReviewSelectionError, reason):
                            service.validate(EndpointReviewRequest(job=job))
                        self.assertEqual({path: path.read_bytes() for path in protected}, before)
                    finally:
                        for path, raw in before.items():
                            path.write_bytes(raw)
            _ctc_binding, binding = publish_packet()
            if public:
                atomic_write_json(review_path, pending)
                workflow._save_job(job)
                with self.assertRaisesRegex(FutureSongError, 'lyrics-completion-required'):
                    workflow.validate(self.video)
                self.assertNotEqual(workflow._load_job(self.video)['state'], 'VALIDATED')
                self.assertFalse((workflow.ready/self.child.name).exists())
                self.assertTrue(self.audio.exists())
            resolved_review(binding)
            if public:
                with patch.object(workflow, '_run_primary_worker', side_effect=AssertionError('no fresh inference')):
                    with self.assertRaisesRegex(FutureSongError, 'lyrics-completion-required'):
                        workflow.validate(self.video)
                self.assertEqual(workflow._load_job(self.video)['endpointReview']['result'], 'accepted')
            else:
                result = service.validate(EndpointReviewRequest(job=job))
                self.assertIsInstance(result, ReviewCompleted)
                self.assertIsNotNone(result.publication)
            lines = json.loads(workflow._owner_sidecar_path(self.video).read_bytes())['tracks'][0]['lines']
            self.assertEqual([(row['startMs'], row['endMs']) for row in lines], [(100, 350), (500, 750)])
            self.assertTrue(all('words' not in row for row in lines))
            self.assertEqual(self.child.read_bytes(), before[self.child])
            if public:
                with self.assertRaises(FutureSongError):
                    workflow.finalize(self.video)
                self.assertFalse((workflow.ready/self.child.name).exists())
                self.assertTrue(self.audio.exists())



if __name__ == '__main__':
    unittest.main()
