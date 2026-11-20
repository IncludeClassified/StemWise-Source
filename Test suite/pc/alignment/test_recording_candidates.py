"""Audio-first candidates remain private, source-bound and unapproved."""
import hashlib
import json
import copy
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
from contextlib import ExitStack

import test_available_completion as completion_fixtures
from workflow_fixture import nested_case
from lyric_content_fixtures import write_recognition
from stemwise_engine import recording_evidence
from stemwise_engine.common import WorkerRetirementError, sha256_file


class RecordingCandidateTests(unittest.TestCase):
    def blocked_diagnostic_fixture(self):
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.lyric_content import recording_review_input_reference
        from stemwise_engine.lyrics_alignment import TRANSCRIPT_CACHE_VERSION
        from stemwise_engine.lyrics_align_pc import _whisper_recognition_cache_identity
        from stemwise_engine.workflow_records import LyricsContentBlocker
        retained, _, _ = self.rejected_words_fixture()
        audio = self.workflow._completed_analysis_audio(self.workflow._pipeline(), self.video)
        primary = write_recognition(self.evidence, 'contrary lyric words', (0,), audio['sha256'])
        reference = {'path': primary.name, 'sha256': sha256_file(primary)}
        inputs = recording_review_input_reference(self.directory, self.evidence, audio['sha256'], reference)
        recognized = self.evidence/'transcript.unprompted-mix.en.private.json'
        atomic_write_json(recognized, {'videoId': self.video, 'cacheVersion': TRANSCRIPT_CACHE_VERSION,
            'cacheIdentity': _whisper_recognition_cache_identity(),
            'promptSha256': hashlib.sha256(b'').hexdigest(),
            'provenance': {'audio': {'sha256': audio['sha256']}, 'durationMs': audio['durationMs'],
                'decoder': {'promptMode': 'withheld'}},
            'words': [{'raw': word, 'start': i*.2, 'end': i*.2+.1}
                for i, word in enumerate('different lyric words'.split())]})
        candidate = recording_evidence.stage_audio_candidate(self.directory, self.evidence, self.video, audio,
            {'path': recognized.name, 'sha256': sha256_file(recognized)})
        _, draft_sha, _, metadata_sha = read_recording_draft(self.directory)
        packet = {'formatVersion': 1, 'videoId': self.video, 'audioSha256': audio['sha256'],
            'durationMs': audio['durationMs'], 'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
            'primaryRecognition': reference, 'scope': {}, 'candidates': [candidate],
            'status': 'pending-recording-review', 'publicationAuthority': False}
        self.workflow._record_validation_outcome(self.video,
            LyricsContentBlocker('content-sequence-uncertain'), source_stage='recording-evidence')
        job = self.workflow._load_job(self.video)
        job['deliveryAllowed'] = False
        job['lyricContentLimitation'] = {'reasons': ['content-sequence-uncertain'], 'scope': {'retained': True}}
        self.workflow._save_job(job)
        state = self.workflow._alignment_state_path(self.video)
        atomic_write_json(state, {'status': 'lyrics-content-unresolved', 'contentDispute': {'retained': True},
            'contentAdmission': {'retained': 'prior owner'}, 'contentBinding': {'audioSha256': audio['sha256']}})
        retained.update({state: state.read_bytes(), primary: primary.read_bytes()})
        return retained, job, {'status': 'content-recovery-complete',
            'recordingInputs': inputs, 'recoveryCandidates': packet}

    def test_blocked_recording_diagnostics_preserve_content_rejection(self):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        retained, before, response = self.blocked_diagnostic_fixture()
        def worker(*args, **kwargs):
            self.assertEqual(args[2][3], '--recover-content')
            return subprocess.CompletedProcess(args[2], 0, json.dumps(response), '')
        with patch.object(self.workflow, '_run_primary_worker', side_effect=worker) as process, \
                patch.object(self.workflow, '_align_future_lyrics', side_effect=AssertionError('diagnostics cannot align')):
            result = self.workflow.recording_evidence(self.video)
        process.assert_called_once()
        current = self.workflow._load_job(self.video)
        self.assertEqual(current['outcome'], before['outcome'])
        self.assertEqual(current['lyricContentLimitation'], before['lyricContentLimitation'])
        self.assertEqual(current['state'], before['state'])
        self.assertFalse(current['deliveryAllowed'])
        self.assertEqual(current['recordingRecoveryCandidates'], response['recoveryCandidates'])
        self.assertEqual(current['recordingEvidenceInputs'], response['recordingInputs'])
        self.assertIs(current['recordingRecoveryCandidates']['publicationAuthority'], False)
        for field in ('contentResolution', 'recordingContentEvidence', 'validation', 'delivery'):
            self.assertNotIn(field, current)
        self.assertEqual(result['status'], 'content-recovery-complete')
        with self.assertRaises(LyricsContentBlocker):
            self.workflow._require_changed_lyrics_content(current)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_blocked_recording_diagnostics_reject_changed_original_or_audio(self):
        from stemwise_engine.workflow_records import FutureSongError
        retained, before, response = self.blocked_diagnostic_fixture()
        for changed in ('draftSha256', 'audioSha256'):
            with self.subTest(changed=changed):
                original = response['recoveryCandidates'][changed]
                response['recoveryCandidates'][changed] = 'f'*64
                try:
                    with patch.object(self.workflow, '_run_primary_worker', return_value=subprocess.CompletedProcess(
                            [], 0, json.dumps(response), '')):
                        with self.assertRaises(ValueError):
                            self.workflow.recording_evidence(self.video)
                    current = self.workflow._load_job(self.video)
                    self.assertEqual(current['outcome'], before['outcome'])
                    self.assertNotIn('recordingEvidenceInputs', current)
                    self.assertNotIn('recordingRecoveryCandidates', current)
                    self.assertNotIn('recordingContentEvidence', current)
                finally:
                    response['recoveryCandidates'][changed] = original
        wrong = {**response, 'recordingInputs': {**response['recordingInputs'], 'audioSha256': 'f'*64}}
        with patch.object(self.workflow, '_run_primary_worker', return_value=subprocess.CompletedProcess(
                [], 0, json.dumps(wrong), '')):
            with self.assertRaises((ValueError, FutureSongError)):
                self.workflow.recording_evidence(self.video)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_blocked_recording_diagnostics_cannot_return_content_authority(self):
        from stemwise_engine.workflow_records import FutureSongError
        retained, before, response = self.blocked_diagnostic_fixture()
        for changed in ({'contentResolution': {'approved': True}}, {'status': 'complete'}, {}):
            with self.subTest(changed=changed):
                body = {**response, **changed}
                code = 1 if not changed else 0
                with patch.object(self.workflow, '_run_primary_worker', return_value=subprocess.CompletedProcess(
                        [], code, json.dumps(body), '')):
                    with self.assertRaises(FutureSongError):
                        self.workflow.recording_evidence(self.video)
                current = self.workflow._load_job(self.video)
                self.assertEqual(current['outcome'], before['outcome'])
                self.assertEqual(current['lyricContentLimitation'], before['lyricContentLimitation'])
                self.assertNotIn('recordingContentEvidence', current)
                self.assertNotIn('recordingRecoveryCandidates', current)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_invalid_recording_diagnostic_packet_preserves_prior_primary_candidates_and_failure(self):
        from stemwise_engine.workflow_records import FutureSongError
        retained, _, response = self.blocked_diagnostic_fixture()
        previous_primary = self.evidence/'previous-primary.private.json'
        previous_primary.write_bytes((self.evidence/response['recordingInputs']['recognition']['path']).read_bytes())
        prior = self.workflow._load_job(self.video)
        prior['recordingEvidenceInputs'] = {**response['recordingInputs'],
            'recognition': {'path': previous_primary.name, 'sha256': sha256_file(previous_primary)}}
        prior['recordingRecoveryCandidates'] = copy.deepcopy(response['recoveryCandidates'])
        packet = response['recoveryCandidates']
        prior['recordingRecoveryFailure'] = {key: packet[key] for key in (
            'formatVersion', 'videoId', 'audioSha256', 'durationMs', 'draftSha256', 'metadataSha256', 'primaryRecognition')}
        prior['recordingRecoveryFailure'].update(stage='independent-content-recognition',
            status='retryable-technical', code='fixture-failure', privateDiagnostics=[])
        self.workflow._save_job(prior)
        for fault in ('malformed-packet', 'different-primary'):
            with self.subTest(fault=fault):
                body = copy.deepcopy(response)
                if fault == 'malformed-packet':
                    body['recoveryCandidates']['candidates'] = []
                else:
                    body['recoveryCandidates']['primaryRecognition'] = prior['recordingEvidenceInputs']['recognition']
                with patch.object(self.workflow, '_run_primary_worker', return_value=subprocess.CompletedProcess(
                        [], 0, json.dumps(body), '')):
                    with self.assertRaises((ValueError, FutureSongError)):
                        self.workflow.recording_evidence(self.video)
                current = self.workflow._load_job(self.video)
                for field in ('recordingEvidenceInputs', 'recordingRecoveryCandidates', 'recordingRecoveryFailure',
                              'outcome', 'lyricContentLimitation'):
                    self.assertEqual(current[field], prior[field], field)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_blocked_recording_diagnostic_failure_keeps_substantive_outcome_and_newer_state(self):
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        retained, before, _ = self.blocked_diagnostic_fixture()
        def worker(*args, **kwargs):
            current = self.workflow._load_job(self.video)
            current['diagnosticFailureContext'] = 'newer unrelated state'
            self.workflow._save_job(current)
            if failure is RuntimeError:
                raise RuntimeError('fixture diagnostic failure')
            return subprocess.CompletedProcess(args[2], 1, json.dumps({
                'status': 'content-evidence-unavailable', 'reasons': ['content-evidence-unavailable']}), '')
        for failure in (ContentEvidenceUnavailable, RuntimeError):
            with self.subTest(failure=failure.__name__), \
                    patch.object(self.workflow, '_run_primary_worker', side_effect=worker):
                with self.assertRaises(failure):
                    self.workflow.recording_evidence(self.video)
            current = self.workflow._load_job(self.video)
            self.assertEqual(current['outcome'], before['outcome'])
            self.assertEqual(current['lyricContentLimitation'], before['lyricContentLimitation'])
            self.assertEqual(current['diagnosticFailureContext'], 'newer unrelated state')
            self.assertEqual(current['recordingEvidence']['status'], 'failed')
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_optional_recovery_cli_failure_survives_parent_reload_and_clears_only_on_bound_success(self):
        import io
        import builtins
        from contextlib import redirect_stdout
        from types import SimpleNamespace
        from unittest.mock import Mock
        from stemwise_engine import alignment_models, lyrics_align_pc
        from stemwise_engine.lyrics_alignment import WordTimestamp
        retained, _, _ = self.rejected_words_fixture()
        real_transcribe = lyrics_align_pc._load_or_transcribe
        words = [WordTimestamp(word=word, raw=word, start=i*.2, end=i*.2+.1)
            for i, word in enumerate('alternate lyric wording'.split())]
        model = SimpleNamespace(transcribe=Mock(side_effect=[RuntimeError('PRIVATE decoder lyric prose https://private.invalid'),
            RuntimeError('PRIVATE first precision decoder cause'),
            RuntimeError('PRIVATE second decoder cause'),
            RuntimeError('PRIVATE second precision decoder cause'),
            (words, {}), (words, {})]))
        owner = SimpleNamespace(compute_type='float16', get=Mock(return_value=model), close=Mock())
        def primary(**request):
            cache = write_recognition(self.evidence, 'different lyric words', (0,), sha256_file(self.fixture.audio))
            cache.replace(self.evidence/'qwen-structure-en.private.json')
            return 'different lyric words', {'language': 'en'}, True
        def worker(*args, **kwargs):
            output = io.StringIO()
            with redirect_stdout(output):
                code = recording_evidence.main(args[2][3:])
            self.assertNotIn('PRIVATE', output.getvalue())
            return subprocess.CompletedProcess(args[2], code, output.getvalue(), '')
        empty = {'decision': 'not-found', 'searchStatus': 'complete', 'surfaceErrors': [], 'candidates': [],
            'surfaceOutcomes': {surface: {'status': 'successful-empty'} for surface in ('songs', 'videos')}}
        with patch.object(self.workflow, '_run_primary_worker', side_effect=worker), \
                patch.object(recording_evidence, 'load_config', return_value=self.workflow.config), \
                patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript', side_effect=primary), \
                patch.object(lyrics_align_pc, '_load_or_transcribe', wraps=real_transcribe), \
                patch.object(lyrics_align_pc, '_verify_whisper_snapshot'), \
                patch('stemwise_engine.lyrics_alignment.resolve_compute_type', return_value='float16'), \
                patch('stemwise_engine.worker_runtime_probe.ctranslate2_runtime_identity', return_value={'device': 'fixture'}), \
                patch.object(alignment_models, 'WhisperModelOwner', return_value=owner), \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value=empty):
            first = self.workflow.recording_evidence(self.video)
            self.assertEqual(model.transcribe.call_count, 2)
            self.assertEqual(first['recoveryDiagnostic'], 'RuntimeError')
            current = self.workflow._load_job(self.video)
            failure = current['recordingRecoveryFailure']
            self.assertEqual(failure['code'], 'RuntimeError')
            self.assertEqual(failure['status'], 'retryable-technical')
            reference, = failure['privateDiagnostics']
            cause = (self.directory/reference['file']).read_bytes()
            self.assertIn(b'PRIVATE decoder lyric prose', cause)
            self.assertLessEqual(len(cause), 12000)
            self.assertEqual(hashlib.sha256(cause).hexdigest(), reference['sha256'])
            limitation = current['lyricContentLimitation']
            original_import = builtins.__import__
            def passive_import(name, *args, **kwargs):
                if name in {'stemwise_engine.recording_evidence', 'stemwise_engine.lyrics_align_pc',
                        'stemwise_engine.alignment_models'} or name.split('.')[0] in {'torch', 'numpy', 'faster_whisper', 'ctranslate2'}:
                    raise AssertionError('Passive optional failure inspection imported numerical code')
                return original_import(name, *args, **kwargs)
            with patch.object(builtins, '__import__', side_effect=passive_import):
                self.assertEqual(self.workflow.summary(current)['recordingRecoveryFailure']['code'], 'RuntimeError')
                task = self.workflow._task_payload(current, b'Task')
                self.assertIn(b'RuntimeError', task)
                self.assertNotIn(b'PRIVATE', task)
            self.assertIn(b'RuntimeError', (self.directory/'AGENT_TASK.txt').read_bytes())
            self.assertNotIn('PRIVATE', json.dumps(current))
            with patch('stemwise_engine.private_diagnostics.retain_error', side_effect=OSError('diagnostic storage unavailable')):
                unavailable = self.workflow.recording_evidence(self.video)
            self.assertEqual(model.transcribe.call_count, 4)
            self.assertEqual(unavailable['status'], 'lyrics-content-unresolved')
            self.assertEqual(unavailable['recoveryDiagnostic'], 'RuntimeError')
            current = self.workflow._load_job(self.video)
            failure = current['recordingRecoveryFailure']
            self.assertEqual(failure['privateDiagnostics'], [])
            self.assertEqual(current['lyricContentLimitation'], limitation)
            original = self.directory/'drafts/original.txt'
            original_bytes = original.read_bytes()
            original.write_bytes(b'changed owner lyric words\n')
            self.assertEqual(self.workflow.summary(current)['recordingRecoveryFailure']['status'], 'stale')
            second = self.workflow.recording_evidence(self.video)
            self.assertIn('recoveryCandidates', second)
            self.assertEqual(len(second['recoveryCandidates']['candidates']), 2)
            self.assertEqual(model.transcribe.call_count, 6)
            current = self.workflow._load_job(self.video)
            self.assertEqual(current['recordingRecoveryFailure'], failure)
            self.assertEqual(self.workflow.summary(current)['recordingRecoveryFailure']['status'], 'stale')
            original.write_bytes(original_bytes)
            self.workflow.recording_evidence(self.video)
            self.assertEqual(model.transcribe.call_count, 6)
            current = self.workflow._load_job(self.video)
            self.assertNotIn('recordingRecoveryFailure', current)
            self.assertEqual(current['lyricContentLimitation'], limitation)
            self.assertNotIn('recordingRecoveryFailure', self.workflow.summary(current))
            self.assertNotIn(b'RuntimeError', self.workflow._task_payload(current, b'Task'))
            self.assertNotIn(b'RuntimeError', (self.directory/'AGENT_TASK.txt').read_bytes())
        self.assertEqual([call.kwargs['precision'] for call in model.transcribe.call_args_list],
            [False, True, False, True, False, True])
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def setUp(self):
        self.fixture = nested_case(self, completion_fixtures.AvailableCompletionTests)
        self.workflow = self.fixture.workflow
        self.video = self.fixture.video
        self.directory = self.workflow._job_dir(self.video)
        self.evidence = self.workflow._alignment_state_path(self.video).parent
        environment = patch.object(self.workflow, '_alignment_worker_environment', return_value={})
        environment.start()
        self.addCleanup(environment.stop)

    def worker(self, *args, **kwargs):
        path = write_recognition(self.evidence, 'heard words', (0,),
                                 sha256_file(self.fixture.audio))
        reference = {'path': path.name, 'sha256': sha256_file(path)}
        return subprocess.CompletedProcess(args[2], 0, json.dumps({'recognition': reference}), '')

    def acquisition_boundaries(self):
        boundaries = ExitStack()
        boundaries.enter_context(patch.object(self.workflow, '_song_info', return_value={
            'id': self.video, 'title': 'Synthetic song', 'uploader': 'Fixture artist', 'duration': 1}))
        boundaries.enter_context(patch.object(self.workflow, '_collect_normal_candidates', return_value={
            'checked': True, 'candidateCount': 0, 'usableLyricCandidateCount': 0, 'records': []}))
        boundaries.enter_context(patch('stemwise_engine.source_acquisition.acquire_exact_item_draft',
            return_value={'videoId': self.video, 'decision': 'not-found'}))
        boundaries.enter_context(patch('stemwise_engine.source_acquisition.discover_counterpart_candidates',
            return_value={'decision': 'not-found', 'candidateCount': 0}))
        process = boundaries.enter_context(patch.object(self.workflow, '_process_song', return_value='complete'))
        return boundaries, process

    def test_acquisition_exhaustion_automatically_retains_unapproved_candidate_through_outer_save(self):
        self.check_acquisition_candidate(scaffold=False)

    def test_acquisition_preserves_metadata_and_companion_scaffold_when_audio_candidate_is_needed(self):
        self.check_acquisition_candidate(scaffold=True)

    def test_initial_counterpart_progress_survives_later_terminal_failure_and_resumes_without_repeating_source(self):
        from stemwise_engine.common import atomic_write_json
        (self.directory/'sources.json').unlink()
        counterpart, other = 'BBBBBBBBBBB', 'CCCCCCCCCCC'
        records = []
        for selected in (counterpart, other):
            path = self.directory/'candidates'/selected/'body.txt'
            path.parent.mkdir(parents=True)
            path.write_bytes(('pending '+selected).encode())
            records.append({'videoId': selected, 'associationStatus': 'pending-agent-review',
                'youtubeMusic': {'checked': True, 'result': {'decision': 'not-found'}},
                'normalYouTube': {'checked': True, 'records': [{'path': str(path),
                    'sha256': sha256_file(path), 'lyricUse': 'potential-body'}]}})
        discovery = {'decision': 'candidates-found', 'searchStatus': 'complete',
            'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
            'candidates': [{'videoId': counterpart}, {'videoId': other}], 'surfaceErrors': []}
        boundaries, process = self.acquisition_boundaries()
        job = self.workflow._load_job(self.video)
        terminal = WorkerRetirementError('unconfirmed retirement')
        with boundaries, patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value=discovery), \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', side_effect=[records[0], terminal, records[1]]) as collect, \
                patch.object(self.workflow, '_run_primary_worker') as acoustic:
            with self.assertRaises(WorkerRetirementError) as raised:
                self.workflow.acquisition(self.video).run(job)
            self.assertIs(raised.exception, terminal)
            retained = json.loads((self.directory/'sources.json').read_bytes())
            self.assertEqual(retained['lyricEvidenceSources'][-1]['videoId'], counterpart)
            self.assertEqual(retained['counterpartDiscovery']['stagingStatus'], 'in-progress')
            self.assertFalse(self.workflow._automatic_source_research_complete(retained))
            self.workflow.acquisition(self.video).run(job)
            acoustic.assert_not_called()
        self.assertEqual([call.args[2] for call in collect.call_args_list], [counterpart, other, other])
        current = json.loads((self.directory/'sources.json').read_bytes())
        self.assertEqual([record['videoId'] for record in current['lyricEvidenceSources']], [self.video, counterpart, other])
        self.assertEqual(current['counterpartDiscovery']['stagingStatus'], 'staged-pending-agent-review')

    def check_acquisition_candidate(self, *, scaffold):
        companions = self.directory/'drafts'
        companions.mkdir()
        protected = {self.fixture.child: self.fixture.child.read_bytes(),
                     self.fixture.audio: self.fixture.audio.read_bytes()}
        if scaffold:
            from stemwise_engine.common import atomic_write_json
            metadata = companions/'lyrics.meta.json'
            atomic_write_json(metadata, {'videoId': self.video, 'originalLanguage': 'en',
                'originalScript': 'Latn', 'sources': {'original': {'name': 'Owner'}}})
            companion = companions/'translation-en.txt'
            companion.write_bytes(b'owner companion bytes')
            protected.update({metadata: metadata.read_bytes(), companion: companion.read_bytes()})
        boundaries, process = self.acquisition_boundaries()
        job = self.workflow._load_job(self.video)
        with boundaries, patch.object(self.workflow, '_run_primary_worker', side_effect=self.worker) as worker:
            self.workflow.acquisition(self.video).run(job)
            self.workflow._save_job(job)
        worker.assert_called_once()
        process.assert_called_once()
        current = self.workflow._load_job(self.video)
        self.assertEqual(job['audioFirstCandidate'], current['audioFirstCandidate'])
        self.assertFalse(current['audioFirstCandidate']['publicationAuthority'])
        self.assertEqual(current['recordingEvidence']['status'], 'complete')
        self.assertFalse((companions/'original.txt').exists())
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)

    def test_automatic_candidate_failure_retries_its_stage_without_repeating_analysis(self):
        from stemwise_engine.worker_protocol import WorkerProtocolError
        from stemwise_engine.workflow_states import JobProgress
        boundaries, process = self.acquisition_boundaries()
        job = self.workflow._load_job(self.video)
        failure = WorkerProtocolError('recording-candidate-failed')
        with boundaries, patch.object(self.workflow, '_run_primary_worker', side_effect=failure):
            with self.assertRaises(WorkerProtocolError) as raised:
                self.workflow.acquisition(self.video).run(job)
        self.assertIs(raised.exception, failure)
        self.assertEqual(process.call_count, 1)
        current = self.workflow._load_job(self.video)
        self.assertEqual(JobProgress.current(current).rule.retry_action, 'RecordingEvidence')
        self.assertEqual(current['outcome']['sourceStage'], 'recording-evidence')
        self.assertNotIn('audioFirstCandidate', current)
        with patch.object(self.workflow, '_run_primary_worker', side_effect=self.worker) as worker, \
                patch.object(self.workflow, '_process_song') as process:
            self.workflow.retry(self.video)
        worker.assert_called_once()
        process.assert_not_called()
        current = self.workflow._load_job(self.video)
        self.assertIn('audioFirstCandidate', current)
        self.assertEqual(current['recordingEvidence']['status'], 'complete')
        self.assertEqual(current['state'], 'NEEDS_LYRICS')
        self.assertNotIn('outcome', current)

    def test_automatic_recovered_analysis_keeps_typed_content_evidence_failure_through_retry(self):
        import io
        from contextlib import redirect_stdout
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        from stemwise_engine.common import atomic_write_json
        self.rejected_words_fixture()
        (self.directory/'drafts/original.txt').write_text('owner lyric words now\n', encoding='utf-8')
        sources = json.loads((self.directory/'sources.json').read_bytes())
        sources['containsLyrics'] = False
        atomic_write_json(self.directory/'sources.json', sources)
        job = self.workflow._load_job(self.video)
        job['state'] = 'ANALYZING'
        self.workflow._save_job(job)
        job['state'] = 'ANALYSIS_FAILED'
        self.workflow._save_job(job)
        calls = []
        def primary(**request):
            calls.append(request)
            if len(calls) == 1:
                raise ContentEvidenceUnavailable('content-evidence-changed')
            path = write_recognition(self.evidence, 'owner lyric words now', (0,), sha256_file(self.fixture.audio))
            path.replace(self.evidence/'qwen-structure-en.private.json')
            return 'owner lyric words now', {'language': 'en'}, True
        def worker(*args, **kwargs):
            output = io.StringIO()
            with redirect_stdout(output):
                code = recording_evidence.main(args[2][3:])
            return subprocess.CompletedProcess(args[2], code, output.getvalue(), '')
        with patch.object(self.workflow, '_run_primary_worker', side_effect=worker), \
                patch.object(recording_evidence, 'load_config', return_value=self.workflow.config), \
                patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript', side_effect=primary), \
                patch.object(self.workflow, '_process_song') as analysis:
            with self.assertRaises(ContentEvidenceUnavailable) as raised:
                self.workflow.acquisition(self.video).run(job, force_analysis=True)
            self.assertEqual(raised.exception.reasons, ('content-evidence-changed',))
            current = self.workflow._load_job(self.video)
            self.assertEqual(current['outcome']['reasons'], ['content-evidence-changed'])
            self.assertEqual(job['outcome'], current['outcome'])
            self.assertEqual(current['outcome']['sourceStage'], 'recording-evidence')
            self.assertEqual(current['state'], 'RETRYABLE_TECHNICAL')
            self.assertEqual(current['recordingEvidence']['status'], 'failed')
            result = self.workflow.retry(self.video)
            self.assertTrue(result['reviewRequired'])
            analysis.assert_not_called()
        self.assertEqual(len(calls), 2)
        current = self.workflow._load_job(self.video)
        self.assertNotIn('outcome', current)
        self.assertEqual(current['recordingEvidence']['status'], 'complete')
        self.assertEqual((self.directory/'drafts/original.txt').read_bytes(), b'owner lyric words now\n')

    def test_automatic_candidate_terminal_and_unknown_failures_stop_without_alternatives(self):
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for failure in (WorkerRetirementError('unconfirmed'), ProviderCancelled('cancelled'),
                ProviderDeadline('parent-deadline'), SongAttemptBlocked('accepted-unknown')):
            with self.subTest(disposition=type(failure).__name__):
                boundaries, process = self.acquisition_boundaries()
                job = self.workflow._load_job(self.video)
                with boundaries, patch.object(self.workflow, '_run_primary_worker', side_effect=failure) as worker:
                    with self.assertRaises(type(failure)) as raised:
                        self.workflow.acquisition(self.video).run(job)
                self.assertIs(raised.exception, failure)
                worker.assert_called_once()
                process.assert_called_once()
                current = self.workflow._load_job(self.video)
                self.assertNotIn('audioFirstCandidate', current)
                self.assertNotIn('outcome', current)
                self.assertNotEqual(current['state'], 'ANALYSIS_FAILED')

    def test_scaffold_checks_preserve_unknown_files_and_delegate_unsettled_pair_ownership(self):
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.source_acquisition import preserved_local_draft_acquisition
        from stemwise_engine.workflow_records import FutureSongError
        draft = self.directory/'drafts'
        draft.mkdir()
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': self.video, 'originalLanguage': 'en',
            'originalScript': 'Latn', 'sources': {'original': {'name': 'Owner'}}})
        unknown = draft/'owner-notes.txt'
        unknown.write_bytes(b'preserve owner input')
        before = {path: path.read_bytes() for path in draft.iterdir()}
        with self.assertRaises(FutureSongError):
            preserved_local_draft_acquisition({'videoId': self.video}, draft)
        self.assertEqual({path: path.read_bytes() for path in before}, before)
        marker = draft/'.acquired-pair.json'
        marker.write_bytes(b'{}')
        self.assertIsNone(preserved_local_draft_acquisition({'videoId': self.video}, draft))
        self.assertEqual(marker.read_bytes(), b'{}')

    def rejected_words_fixture(self):
        from stemwise_engine.common import atomic_write_json
        draft = self.directory/'drafts'
        draft.mkdir()
        (draft/'original.txt').write_text('owner lyric words\n', encoding='utf-8')
        (draft/'translation-en.txt').write_bytes(b'owner companion')
        atomic_write_json(draft/'lyrics.meta.json', {'videoId': self.video,
            'originalLanguage': 'en', 'originalScript': 'Latn', 'sources': {'original': {'name': 'Owner'}}})
        sources = {'videoId': self.video, 'exactSurfaceChecks': {}, 'lyricEvidenceSources': [
            {'videoId': self.video, 'youtubeMusic': {'checked': True}, 'normalYouTube': {'checked': True}}]}
        atomic_write_json(self.directory/'sources.json', sources)
        from stemwise_engine.workflow_review import review_template
        atomic_write_json(self.directory/'agent-review.json', review_template(self.video, sources))
        retained = {path: path.read_bytes() for path in draft.iterdir()}
        counterpart = 'BBBBBBBBBBB'
        candidate = self.directory/'candidates'/counterpart/'lyrics.txt'
        candidate.parent.mkdir(parents=True)
        candidate.write_bytes(b'new unapproved source wording')
        collected = {'videoId': counterpart, 'associationStatus': 'pending-agent-review',
            'relationshipToPackage': 'automatically-discovered-counterpart',
            'youtubeMusic': {'checked': True, 'result': {'decision': 'not-found'}},
            'normalYouTube': {'checked': True, 'records': [{'path': str(candidate),
                'sha256': sha256_file(candidate), 'lyricUse': 'potential-body'}],
                'candidateCount': 1, 'usableLyricCandidateCount': 1}}
        return retained, counterpart, collected

    def test_unchecked_or_malformed_staged_surfaces_are_repaired_without_losing_body(self):
        import copy
        from stemwise_engine.source_acquisition import source_record_complete
        retained, counterpart, collected = self.rejected_words_fixture()
        for field, value in (('youtubeMusic', None), ('youtubeMusic', []),
                ('youtubeMusic', {'checked': False, 'result': {'decision': 'not-found'}}),
                ('youtubeMusic', {'checked': True, 'result': []}),
                ('normalYouTube', {'checked': True, 'records': [False]}),
                ('normalYouTube', {'checked': True}), ('normalYouTube', {'records': []})):
            with self.subTest(field=field, value=value):
                broken = copy.deepcopy(collected)
                broken[field] = value
                self.assertFalse(source_record_complete(broken, self.directory))
        self.assertTrue(source_record_complete(collected, self.directory))
        sources = json.loads((self.directory/'sources.json').read_bytes())
        sources['lyricEvidenceSources'].append({**collected, 'youtubeMusic': []})
        (self.directory/'sources.json').write_text(json.dumps(sources), encoding='utf-8')
        with patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', return_value=collected) as collect:
            self.workflow.add_lyric_source(self.video, f'https://youtu.be/{counterpart}')
        collect.assert_called_once()
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_research_completion_rejects_owner_skip_but_keeps_actual_acquired_provider_record(self):
        import copy
        _, _, collected = self.rejected_words_fixture()
        sources = {'exactSurfaceChecks': {key: copy.deepcopy(collected[key])
            for key in ('youtubeMusic', 'normalYouTube')}, 'lyricEvidenceSources': [copy.deepcopy(collected)]}
        self.assertTrue(self.workflow._automatic_source_research_complete(sources))
        sources['exactSurfaceChecks']['youtubeMusic']['result']['decision'] = 'skipped-existing'
        self.assertFalse(self.workflow._automatic_source_research_complete(sources))
        sources['exactSurfaceChecks']['youtubeMusic']['result']['decision'] = 'not-found'
        sources['lyricEvidenceSources'][0]['youtubeMusic']['result']['decision'] = 'skipped-existing'
        self.assertFalse(self.workflow._automatic_source_research_complete(sources))
        candidate = collected['normalYouTube']['records'][0]
        sources['lyricEvidenceSources'][0]['youtubeMusic']['result'] = {'decision': 'acquired',
            'candidatePath': candidate['path'], 'sha256': candidate['sha256']}
        self.assertTrue(self.workflow._automatic_source_research_complete(sources))

    def test_acquired_lyrics_can_complete_research_with_failed_optional_auto_captions(self):
        import copy
        from stemwise_engine.source_acquisition import source_record_complete
        _, _, collected = self.rejected_words_fixture()
        candidate = collected['normalYouTube']['records'][0]
        collected['youtubeMusic']['result'] = {'decision': 'acquired',
            'candidatePath': candidate['path'], 'sha256': candidate['sha256']}
        failure = {'type': 'automatic-caption', 'automatic': True, 'language': 'af',
            'status': 'unavailable', 'reason': 'ProviderTransientError'}
        collected['normalYouTube']['records'].append(failure)
        def sources(record):
            return {'exactSurfaceChecks': {key: copy.deepcopy(record[key])
                for key in ('youtubeMusic', 'normalYouTube')}, 'lyricEvidenceSources': [record]}
        before = copy.deepcopy(collected)
        self.assertTrue(self.workflow._automatic_source_research_complete(sources(collected)))
        self.assertEqual(collected, before)
        self.assertFalse(source_record_complete(collected, self.directory),
            'Failed captions must remain retryable during candidate acquisition.')
        for field, value in (('type', 'manual-subtitle'), ('automatic', False), ('reason', '')):
            with self.subTest(field=field):
                broken = copy.deepcopy(collected)
                broken['normalYouTube']['records'][-1][field] = value
                self.assertFalse(self.workflow._automatic_source_research_complete(sources(broken)))
        collected['youtubeMusic']['result'] = {'decision': 'not-found'}
        self.assertFalse(self.workflow._automatic_source_research_complete(sources(collected)),
            'Optional failures cannot establish exhaustive lyric absence.')

    def test_rejected_retrieved_words_continue_discovery_without_overwriting_owner_inputs(self):
        retained, counterpart, collected = self.rejected_words_fixture()
        rejected = subprocess.CompletedProcess([], 1, json.dumps({'status': 'lyrics-content-unresolved',
            'reasons': ['content-sequence-uncertain']}), '')
        with patch.object(self.workflow, '_run_primary_worker', return_value=rejected), \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value={
                    'decision': 'candidates-found', 'searchStatus': 'complete',
                    'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
                    'recommendedVideoId': counterpart}) as discover, \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', return_value=collected) as collect:
            result = self.workflow.recording_evidence(self.video)
            self.workflow.recording_evidence(self.video)
        discover.assert_called_once()
        collect.assert_called_once()
        self.assertTrue(result['reviewRequired'])
        current = json.loads((self.directory/'sources.json').read_bytes())
        self.assertEqual(current['lyricEvidenceSources'][-1]['videoId'], counterpart)
        self.assertEqual(current['lyricEvidenceSources'][-1]['associationStatus'], 'pending-agent-review')
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_validate_content_rejection_continues_discovery_without_new_audio_work(self):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        retained, counterpart, collected = self.rejected_words_fixture()
        rejection = LyricsContentBlocker('content-sequence-uncertain')
        with patch.object(self.workflow, '_validate_impl', side_effect=rejection), \
                patch.object(self.workflow, '_run_primary_worker') as acoustic, \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value={
                    'decision': 'candidates-found', 'searchStatus': 'complete',
                    'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
                    'recommendedVideoId': counterpart}) as discover, \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', return_value=collected):
            with self.assertRaises(LyricsContentBlocker) as raised:
                self.workflow._validate_enhancements_action(self.video)
        self.assertIs(raised.exception, rejection)
        discover.assert_called_once()
        acoustic.assert_not_called()
        sources = json.loads((self.directory/'sources.json').read_bytes())
        self.assertEqual(sources['lyricEvidenceSources'][-1]['videoId'], counterpart)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_partial_lookup_keeps_staged_evidence_and_retries_only_incomplete_discovery(self):
        from stemwise_engine.workflow_records import FutureSongError
        retained, counterpart, collected = self.rejected_words_fixture()
        rejected = subprocess.CompletedProcess([], 1, json.dumps({'status': 'lyrics-content-unresolved',
            'reasons': ['content-sequence-uncertain']}), '')
        positive = {'decision': 'candidates-found', 'searchStatus': 'complete',
                    'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
                    'recommendedVideoId': counterpart,
            'candidates': [{'videoId': counterpart}], 'candidateCount': 1}
        with patch.object(self.workflow, '_run_primary_worker', return_value=rejected), \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', side_effect=[
                    {**positive, 'surfaceErrors': ['videos:ProviderError']},
                    {**positive, 'surfaceErrors': []}]) as discover, \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', return_value=collected) as collect:
            with self.assertRaisesRegex(FutureSongError, 'source-discovery-incomplete'):
                self.workflow.recording_evidence(self.video)
            first = json.loads((self.directory/'sources.json').read_bytes())
            self.assertEqual(first['lyricEvidenceSources'][-1]['videoId'], counterpart)
            self.assertEqual(first['counterpartDiscovery']['surfaceErrors'], ['videos:ProviderError'])
            result = self.workflow.recording_evidence(self.video)
        self.assertTrue(result['reviewRequired'])
        self.assertEqual(discover.call_count, 2)
        collect.assert_called_once()
        current = json.loads((self.directory/'sources.json').read_bytes())
        self.assertEqual(current['counterpartDiscovery']['surfaceErrors'], [])
        self.assertEqual(sum(record['videoId'] == counterpart for record in current['lyricEvidenceSources']), 1)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_staged_provider_failure_keeps_valid_body_then_repairs_the_exact_source(self):
        from stemwise_engine.workflow_records import FutureSongError
        retained, counterpart, collected = self.rejected_words_fixture()
        partial = {**collected, 'youtubeMusic': {'checked': True, 'result': {'decision': 'api-error'}}}
        rejected = subprocess.CompletedProcess([], 1, json.dumps({'status': 'lyrics-content-unresolved',
            'reasons': ['content-sequence-uncertain']}), '')
        with patch.object(self.workflow, '_run_primary_worker', return_value=rejected), \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value={
                    'decision': 'candidates-found', 'searchStatus': 'complete',
                    'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
                    'recommendedVideoId': counterpart, 'surfaceErrors': []}) as discover, \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', side_effect=[partial, collected]) as collect:
            with self.assertRaisesRegex(FutureSongError, 'source-discovery-incomplete'):
                self.workflow.recording_evidence(self.video)
            first = json.loads((self.directory/'sources.json').read_bytes())
            self.assertEqual(first['lyricEvidenceSources'][-1]['videoId'], counterpart)
            self.assertEqual(first['counterpartDiscovery']['stagingStatus'], 'staging-failed')
            self.workflow.recording_evidence(self.video)
        self.assertEqual(discover.call_count, 2)
        self.assertEqual(collect.call_count, 2)
        current = json.loads((self.directory/'sources.json').read_bytes())
        self.assertEqual(sum(record['videoId'] == counterpart for record in current['lyricEvidenceSources']), 1)
        self.assertNotEqual(current['counterpartDiscovery']['stagingStatus'], 'staging-failed')
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_completed_discovery_rechecks_and_repairs_missing_staged_candidate_bytes(self):
        retained, counterpart, collected = self.rejected_words_fixture()
        candidate = Path(collected['normalYouTube']['records'][0]['path'])
        candidate_bytes = candidate.read_bytes()
        rejected = subprocess.CompletedProcess([], 1, json.dumps({'status': 'lyrics-content-unresolved',
            'reasons': ['content-sequence-uncertain']}), '')
        def collect(*args, **options):
            candidate.write_bytes(candidate_bytes)
            return collected
        with patch.object(self.workflow, '_run_primary_worker', return_value=rejected), \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value={
                    'decision': 'candidates-found', 'searchStatus': 'complete',
                    'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
                    'recommendedVideoId': counterpart, 'surfaceErrors': []}) as discover, \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', side_effect=collect) as stage:
            self.workflow.recording_evidence(self.video)
            candidate.unlink()
            self.workflow.recording_evidence(self.video)
        self.assertEqual(discover.call_count, 2)
        self.assertEqual(stage.call_count, 2)
        self.assertEqual(candidate.read_bytes(), candidate_bytes)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_multiple_plausible_counterparts_are_staged_independently_and_reused(self):
        import copy
        retained, counterpart, collected = self.rejected_words_fixture()
        other = 'CCCCCCCCCCC'
        second = copy.deepcopy(collected)
        second['videoId'] = other
        discovery = {'decision': 'candidates-found', 'searchStatus': 'complete',
            'surfaceOutcomes': {surface: {'status': 'successful-results'} for surface in ('songs', 'videos')},
            'candidates': [{'videoId': counterpart}, {'videoId': other}], 'surfaceErrors': []}
        rejected = subprocess.CompletedProcess([], 1, json.dumps({'status': 'lyrics-content-unresolved',
            'reasons': ['content-sequence-uncertain']}), '')
        with patch.object(self.workflow, '_run_primary_worker', return_value=rejected), \
                patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value=discovery) as discover, \
                patch('stemwise_engine.source_acquisition.collect_cross_id_evidence', side_effect=[collected, second]) as collect:
            self.workflow.recording_evidence(self.video)
            self.workflow.recording_evidence(self.video)
        self.assertEqual(collect.call_count, 2)
        discover.assert_called_once()
        sources = json.loads((self.directory/'sources.json').read_bytes())
        self.assertEqual(sources['counterpartDiscovery']['stagedVideoIds'], [counterpart, other])
        self.assertEqual([record['associationStatus'] for record in sources['lyricEvidenceSources'][1:]],
                         ['pending-agent-review', 'pending-agent-review'])
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_public_recording_evidence_proposes_and_resumes_without_approving_or_repeating(self):
        with patch.object(self.workflow, '_run_primary_worker', side_effect=self.worker) as worker:
            result = self.workflow.recording_evidence(self.video)
            self.assertEqual(worker.call_count, 1)
            self.assertIn('--candidate', worker.call_args.args[2])
        self.assertEqual(result['recordingEvidence']['status'], 'complete')
        self.assertNotEqual(result['progress']['outcome'], 'active')
        task = (self.directory/'AGENT_TASK.txt').read_text(encoding='utf-8')
        self.assertIn('-Action Validate', task)
        self.assertEqual(task.count('-Action Status'), 1)
        candidate = result['audioFirstCandidate']
        self.assertEqual(candidate['status'], 'pending-recording-review')
        self.assertFalse(candidate['publicationAuthority'])
        path = self.fixture.root/candidate['candidate']['path']
        self.assertEqual(path.read_text(encoding='utf-8'), 'heard words\n')
        original = path.read_bytes()
        with patch.object(self.workflow, '_run_primary_worker') as worker:
            resumed = self.workflow.recording_evidence(self.video)
            worker.assert_not_called()
        self.assertEqual(resumed['audioFirstCandidate'], candidate)
        self.assertEqual(resumed['recordingEvidence']['status'], 'complete')
        self.assertEqual(path.read_bytes(), original)
        self.assertTrue(result['reviewRequired'])
        self.assertFalse((self.directory/'drafts/original.txt').exists())
        self.assertNotIn('recordingContentEvidence', self.workflow._load_job(self.video))
        self.assertIn('Unapproved audio-first lyric candidate',
                      (self.directory/'AGENT_TASK.txt').read_text(encoding='utf-8'))

    def test_changed_candidate_blocks_replay_and_keeps_edited_bytes(self):
        with patch.object(self.workflow, '_run_primary_worker', side_effect=self.worker):
            result = self.workflow.recording_evidence(self.video)
        path = self.fixture.root/result['audioFirstCandidate']['candidate']['path']
        path.write_text('owner edit\n', encoding='utf-8')
        with patch.object(self.workflow, '_run_primary_worker') as worker:
            with self.assertRaisesRegex(ValueError, 'audio-first-candidate-changed'):
                self.workflow.recording_evidence(self.video)
            worker.assert_not_called()
        self.assertEqual(path.read_text(encoding='utf-8'), 'owner edit\n')
        status = self.workflow.summary(self.workflow._load_job(self.video))
        self.assertEqual(status['audioFirstCandidate']['status'], 'unavailable')

    def test_fatal_candidate_worker_stops_before_accepting_intact_result(self):
        def fatal(*args, **kwargs):
            result = self.worker(*args, **kwargs)
            result.returncode = 70
            return result
        with patch.object(self.workflow, '_run_primary_worker', side_effect=fatal):
            with self.assertRaises(WorkerRetirementError):
                self.workflow.recording_evidence(self.video)
        self.assertNotIn('audioFirstCandidate', self.workflow._load_job(self.video))
        self.assertFalse((self.directory/'candidates/audio-first-lyrics.txt').exists())

    def test_recording_bookkeeping_never_masks_parent_stop_or_new_retirement_failure(self):
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for primary, reporting, expected in (
                (ProviderCancelled('stop'), OSError('report failed'), 'primary'),
                (ProviderDeadline('stop'), OSError('report failed'), 'primary'),
                (ProviderCancelled('stop'), WorkerRetirementError('new owner'), 'reporting'),
                (ProviderDeadline('stop'), WorkerRetirementError('new owner'), 'reporting'),
                (WorkerRetirementError('old owner'), OSError('report failed'), 'primary'),
                (RuntimeError('ordinary failure'), WorkerRetirementError('new owner'), 'reporting')):
            with self.subTest(primary=type(primary).__name__, reporting=type(reporting).__name__):
                save = self.workflow._save_job
                calls = []
                def report(job):
                    calls.append(job)
                    if len(calls) == 2:
                        raise reporting
                    return save(job)
                def fail(video_id, *, on_start):
                    on_start(self.workflow._load_job(video_id))
                    raise primary
                wanted = primary if expected == 'primary' else reporting
                with patch.object(self.workflow, '_recording_evidence_action', side_effect=fail), \
                        patch.object(self.workflow, '_save_job', side_effect=report):
                    with self.assertRaises(type(wanted)) as raised:
                        self.workflow._run_recording_evidence(self.video)
                self.assertIs(raised.exception, wanted)

    def test_candidate_rejects_wrong_audio_and_changed_recognition(self):
        audio = self.workflow._completed_analysis_audio(self.fixture.pipeline, self.video)
        path = write_recognition(self.evidence, 'heard words', (0,), 'b'*64)
        reference = {'path': path.name, 'sha256': sha256_file(path)}
        with self.assertRaises(ValueError):
            recording_evidence.stage_audio_candidate(self.directory, self.evidence, self.video, audio, reference)
        path.write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'recognition changed'):
            recording_evidence.stage_audio_candidate(self.directory, self.evidence, self.video, audio, reference)
        self.assertFalse((self.directory/'candidates').exists())

    def test_audio_first_producer_uses_unprompted_auto_language_and_bound_existing_loader(self):
        calls = []
        def recognize(**request):
            calls.append(request)
            path = write_recognition(self.evidence, 'heard words', (0,), sha256_file(self.fixture.audio))
            (self.evidence/'qwen-structure.private.json').write_bytes(path.read_bytes())
            return 'heard words', {}, False
        with patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript', side_effect=recognize):
            result = recording_evidence.propose(self.workflow.config, self.workflow.root, self.video,
                self.fixture.audio, sha256_file(self.fixture.audio))
        self.assertEqual(len(calls), 1)
        self.assertIsNone(calls[0]['language'])
        self.assertEqual(calls[0]['audio_identity']['sha256'], sha256_file(self.fixture.audio))
        self.assertNotIn('original', calls[0])
        path = self.evidence/result['recognition']['path']
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), result['recognition']['sha256'])


if __name__ == '__main__':
    unittest.main()
