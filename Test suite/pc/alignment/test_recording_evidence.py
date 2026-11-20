"""Pre-review comparison stops on uncertain content and preserves owner inputs."""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout, contextmanager, ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf

from test_future_song import WORKSPACE
from stemwise_engine.common import atomic_write_json, sha256_file
from stemwise_engine.workflow_records import LyricsContentBlocker
import stemwise_engine.recording_evidence as evidence
import stemwise_engine.lyrics_align_pc as lyrics_align_pc
import stemwise_engine.alignment_audio as alignment_audio
import stemwise_engine.alignment_models as alignment_models
REAL_WHISPER_TRANSCRIPTION = lyrics_align_pc._load_or_transcribe


class RecordingEvidenceTests(unittest.TestCase):
    @contextmanager
    def recovery_routes(self, *, cached=False, failure=None, separation_failure=None, qwen_language='en',
                        probe_runtime=False, probe_failure=None):
        """Replace model/PCM boundaries while exercising actual recovery orchestration/publication."""
        workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
        directory = workspace.job_dir('AAAAAAAAAAA')
        directory.mkdir(parents=True, exist_ok=True)
        identity = alignment_audio.file_identity(self.audio)
        identity['canonicalTimeTransform'] = alignment_audio.identity_transform(identity['stream'])
        draft, draft_sha, metadata, metadata_sha = evidence.read_recording_draft(self.job)
        original = {'text': draft, 'language': 'en'}
        primary = directory/'qwen-structure-en.private.json'
        words = [{'raw': 'unresolved', 'start': 0, 'end': .1}]
        atomic_write_json(primary, {'text': 'unresolved', 'words': words,
            'provenance': {'audio': identity, 'durationMs': 1000}})
        separator = Path(self.config['projectDir']).parent/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'
        separator.parent.mkdir(parents=True, exist_ok=True)
        separator.write_bytes(b'fixture separator boundary')
        qwen_snapshot = (Path(self.config['projectDir']).parent/'model-cache/lyrics-alignment'/
            'models--Qwen--Qwen3-ASR-1.7B-hf'/'snapshots'/lyrics_align_pc.QWEN3_ASR_REVISION)
        qwen_snapshot.mkdir(parents=True, exist_ok=True)
        routes = ('htdemucs-vocals', *lyrics_align_pc.endpoints.ROFORMER_ROUTES)
        identities = {route: {**identity, 'routeId': route,
            'sourceViewId': ('mix' if route == 'htdemucs-vocals' else
                lyrics_align_pc.endpoints.ROFORMER_ROUTES[route]['sourceViewId']),
            'sourceAudioSha256': identity['sha256']} for route in routes}
        calls, materialized = [], []
        runtime_state = SimpleNamespace(whisper_live=False, lease_live=False, cached=cached)
        events, reports, supplied_reports = [], [], []
        def whisper_get():
            events.append(('whisper-get', runtime_state.lease_live))
            runtime_state.whisper_live = True
        def whisper_close():
            events.append(('whisper-close', runtime_state.lease_live))
            runtime_state.whisper_live = False
        owner = SimpleNamespace(compute_type='float16', get=Mock(side_effect=whisper_get),
            close=Mock(side_effect=whisper_close))
        @contextmanager
        def environment_lease(name, *, builder):
            self.assertEqual(name, 'main')
            self.assertEqual(builder, Path(self.config['projectDir']))
            self.assertFalse(runtime_state.lease_live)
            runtime_state.lease_live = True
            events.append(('lease-enter',))
            try:
                yield
            finally:
                events.append(('lease-exit',))
                runtime_state.lease_live = False
        def qualified_runtime(*args, **kwargs):
            events.append(('runtime-probe', runtime_state.whisper_live, runtime_state.lease_live))
            if probe_failure is not None:
                raise probe_failure
            profile = alignment_models.runtime_profile('main')
            report = {'workerFormatVersion': lyrics_align_pc.QWEN3_ASR_FORMAT_VERSION,
                'engine': lyrics_align_pc.QWEN3_ASR_ENGINE, 'decoder': lyrics_align_pc.QWEN3_ASR_DECODER,
                'supportedLanguages': ['en'], 'runtime': {'runtimeVersion': 1,
                    'python': profile['python'], 'implementation': 'CPython', 'platform': 'fixture-linux',
                    'machine': 'x86_64', 'torch': profile['packages']['torch'],
                    'transformers': profile['packages']['transformers'], 'requestedDevice': 'cuda',
                    'resolvedDevice': 'cuda', 'cudaAvailable': True, 'cudaDeviceName': 'fixture-gpu',
                    'cudaRuntime': 'fixture-cuda', 'cudnn': 'fixture-cudnn'}}
            reports.append(report)
            return report
        def views_close():
            events.append(('views-close', runtime_state.lease_live))
            owner.close()
        def get_audio(route):
            materialized.append(route)
            if separation_failure is not None and route == 'htdemucs-vocals':
                raise separation_failure
            return self.audio, identities[route], {'routeId': route}
        views = SimpleNamespace(get_endpoint_audio=Mock(side_effect=get_audio), outputs={},
            runtime_cache={}, close=Mock(side_effect=views_close))
        def write(path, route, qwen=False):
            atomic_write_json(path, {'videoId': 'AAAAAAAAAAA', 'text': 'unresolved', 'words': words,
                'provenance': {'audio': identities.get(route, identity), 'durationMs': 1000,
                    'decodingObservation': path.name}})
        def whisper(**kwargs):
            route = kwargs.get('route_id', 'htdemucs-vocals' if kwargs.get('separate_vocals') else 'mix')
            precision = kwargs.get('precision', route not in ('mix', 'htdemucs-vocals'))
            calls.append(('faster-whisper', route, precision))
            if failure is not None:
                failure('faster-whisper', route, precision)
            kwargs['get_model']()
            if route == 'htdemucs-vocals':
                kwargs['vocals_provider']()
            elif route != 'mix':
                kwargs['get_audio']()
            variant = ('precision-' if precision else '')+'unprompted-'+('mix' if route == 'mix' else 'vocals')
            if route not in ('mix', 'htdemucs-vocals'):
                variant = 'roformer-kim-vocals' if route == routes[1] else 'roformer-sucial-dereverb-v2'
            write(workspace.transcript_path('AAAAAAAAAAA', 'en', variant), route)
            return words, {}, False
        def qwen(**kwargs):
            route = kwargs['audio_view']
            events.append(('qwen-call', runtime_state.whisper_live, runtime_state.lease_live))
            if probe_runtime:
                report = kwargs.get('runtime_report')
                if report is None:
                    # The unchanged loader probes before both cache lookup and before_run.
                    report = lyrics_align_pc._qwen3_asr_runtime_provenance(
                        lyrics_align_pc.environment_python('main', Path(self.config['projectDir'])),
                        Path(self.config['projectDir'])/'src/qwen3_asr_transcribe.py',
                        device='cuda', timeout_seconds=60)
                supplied_reports.append(report)
            calls.append(('qwen3-asr', route, kwargs.get('cache_only', False)))
            if failure is not None:
                failure('qwen3-asr', route, kwargs.get('cache_only', False))
            if kwargs.get('cache_only') and not runtime_state.cached:
                raise FileNotFoundError('fixture cache miss')
            self.assertIs(kwargs['attempt_cache'], views.runtime_cache)
            write(directory/f'qwen-structure-{route}-{qwen_language or "auto"}.private.json', route, True)
            return 'unresolved', {'language': qwen_language}, runtime_state.cached
        with ExitStack() as stack:
            for target, name, options in (
                (alignment_models, 'WhisperModelOwner', {'return_value': owner}),
                (alignment_audio, 'AlignmentAudioViews', {'return_value': views}),
                (alignment_audio, 'read_audio_provenance', {'return_value': {'mix': identity, 'audioViews': identities}}),
                (alignment_models, 'require_pinned_file', {}),
                (alignment_models, 'require_roformer_wheel', {}),
                (alignment_models, 'roformer_assets', {}),
                (lyrics_align_pc, '_verify_whisper_snapshot', {}),
                (lyrics_align_pc, '_qwen3_asr_runtime_provenance', {'side_effect': qualified_runtime}),
                (lyrics_align_pc, 'environment_lease', {'side_effect': environment_lease}),
                (lyrics_align_pc, 'environment_python', {'return_value': self.root/'fixture-python'}),
                (lyrics_align_pc, '_load_or_transcribe', {'side_effect': whisper}),
                (lyrics_align_pc, '_load_or_transcribe_roformer', {'side_effect': whisper}),
                (evidence, '_load_or_run_qwen3_structure_transcript', {'side_effect': qwen}),
                (evidence, '_recognized', {'side_effect': lambda cache, *args, **kwargs: cache.get('text', cache['words'])}),
            ):
                stack.enter_context(patch.object(target, name, **options))
            stack.enter_context(patch('stemwise_engine.workflow_status.candidate_recognition_sha', return_value=self.audio_sha))
            yield SimpleNamespace(workspace=workspace, primary=primary, identity=identity, original=original,
                draft_sha=draft_sha, metadata_sha=metadata_sha, calls=calls, materialized=materialized,
                owner=owner, views=views, runtime_state=runtime_state, events=events,
                reports=reports, supplied_reports=supplied_reports, qwen_snapshot=qwen_snapshot)

    def collect_recovery(self, fixture, **kwargs):
        return evidence.collect_disagreement_candidate(self.config, fixture.workspace, 'AAAAAAAAAAA',
            self.audio, fixture.identity, fixture.original, fixture.draft_sha, fixture.metadata_sha,
            fixture.primary, {'known': 'scope'}, **kwargs)

    def test_recovery_expands_qualified_routes_with_shared_views_and_owner(self):
        with self.recovery_routes() as fixture:
            before = self.snapshot()
            packet = self.collect_recovery(fixture)
            self.assertEqual(len(packet['candidates']), 9)
            self.assertEqual([call for call in fixture.calls if call[0] == 'faster-whisper'], [
                ('faster-whisper', 'mix', False), ('faster-whisper', 'mix', True),
                ('faster-whisper', 'htdemucs-vocals', False), ('faster-whisper', 'htdemucs-vocals', True),
                ('faster-whisper', 'kimberley-jensen-roformer-vocals', True),
                ('faster-whisper', 'sucial-v2-roformer-dry-vocals', True)])
            alignment_audio.AlignmentAudioViews.assert_called_once()
            alignment_models.WhisperModelOwner.assert_called_once()
            self.assertEqual(fixture.owner.close.call_count, 4)
            self.assertEqual({path: path.read_bytes() for path in before}, before)
            self.assertTrue(all(candidate['publicationAuthority'] is False for candidate in packet['candidates']))
            for candidate in packet['candidates']:
                self.assertEqual((self.job/candidate['candidate']['path']).read_text(encoding='utf-8'), 'unresolved\n')

    def test_qwen_recovery_retires_whisper_before_runtime_and_cache_work(self):
        with self.recovery_routes(cached=True, probe_runtime=True) as fixture:
            packet = self.collect_recovery(fixture)
            self.assertEqual(len(packet['candidates']), 9)
            transitions = [event for event in fixture.events if event[0] in ('runtime-probe', 'qwen-call')]
            self.assertTrue(transitions)
            self.assertTrue(all(event[1] is False for event in transitions),
                'Whisper still owns its heavy stage during Qwen runtime/cache work: '+str(transitions))
            first = next(index for index, event in enumerate(fixture.events)
                if event[0] in ('runtime-probe', 'qwen-call'))
            self.assertIn('whisper-close', [event[0] for event in fixture.events[:first]])
            self.assertEqual([event for event in fixture.events if event[0] == 'views-close'],
                [('views-close', True)])

    def test_qwen_recovery_reuses_one_qualified_runtime_under_live_environment_lease(self):
        with self.recovery_routes(probe_runtime=True) as fixture:
            self.collect_recovery(fixture)
            self.assertEqual(len(fixture.reports), 1)
            self.assertEqual(len(fixture.supplied_reports), 6)
            self.assertEqual(set(fixture.reports[0]),
                {'workerFormatVersion', 'engine', 'runtime', 'decoder', 'supportedLanguages'})
            self.assertTrue(all(report is fixture.reports[0] for report in fixture.supplied_reports))
            self.assertTrue(all(event[2] is True for event in fixture.events
                if event[0] in ('runtime-probe', 'qwen-call')))
            self.assertEqual(fixture.events[-1], ('lease-exit',))
            self.assertFalse(fixture.runtime_state.lease_live)
            # Reuse is local to this invocation; a later invocation qualifies its runtime again.
            fixture.runtime_state.cached = True
            prior_calls = len(fixture.supplied_reports)
            self.collect_recovery(fixture)
            self.assertEqual(len(fixture.reports), 2)
            self.assertIsNot(fixture.reports[0], fixture.reports[1])
            self.assertEqual(len(fixture.supplied_reports)-prior_calls, 3)
            self.assertTrue(all(report is fixture.reports[1] for report in fixture.supplied_reports[prior_calls:]))
            self.assertEqual([event for event in fixture.events if event[0] == 'views-close'],
                [('views-close', True), ('views-close', True)])
            self.assertEqual(fixture.events[-1], ('lease-exit',))
        from stemwise_engine.private_diagnostics import retain_error
        error = RuntimeError('fixture qualified runtime is unavailable')
        with self.recovery_routes(probe_runtime=True, probe_failure=error) as fixture, patch(
                'stemwise_engine.private_diagnostics.retain_error', wraps=retain_error) as retained:
            packet = self.collect_recovery(fixture, diagnostic=True)
            self.assertEqual(lyrics_align_pc._qwen3_asr_runtime_provenance.call_count, 1)
            self.assertEqual(len(packet['candidates']), 6)
            self.assertEqual(len(packet['routeFailures']), 3)
            self.assertEqual({row['familyId'] for row in packet['routeFailures']}, {'qwen3-asr'})
            self.assertTrue(all(call.args[1] is error for call in retained.call_args_list))
            self.assertEqual(retained.call_count, 3)
            self.assertFalse(any(call[0] == 'qwen3-asr' for call in fixture.calls))
            self.assertEqual(fixture.events[-1], ('lease-exit',))
            self.assertFalse(fixture.runtime_state.lease_live)

    def test_qwen_recovery_runtime_lease_retires_on_fatal_and_stays_lazy_without_qwen(self):
        from stemwise_engine.common import WorkerRetirementError
        error = WorkerRetirementError('fixture runtime probe did not retire')
        with self.recovery_routes(probe_runtime=True, probe_failure=error) as fixture:
            with self.assertRaises(WorkerRetirementError) as raised:
                self.collect_recovery(fixture, diagnostic=True)
            self.assertIs(raised.exception, error)
            self.assertEqual([event for event in fixture.events if event[0] == 'runtime-probe'],
                [('runtime-probe', False, True)])
            self.assertEqual([event for event in fixture.events if event[0] == 'views-close'],
                [('views-close', True)])
            self.assertEqual(fixture.events[-1], ('lease-exit',))
            self.assertFalse(fixture.runtime_state.lease_live)
            self.assertEqual(len(fixture.calls), 4)
        error = WorkerRetirementError('fixture Whisper retirement is unknown')
        with self.recovery_routes(probe_runtime=True) as fixture:
            fixture.owner.close.side_effect = [error, None]
            with self.assertRaises(WorkerRetirementError) as raised:
                self.collect_recovery(fixture, diagnostic=True)
            self.assertIs(raised.exception, error)
            self.assertEqual(fixture.reports, [])
            self.assertEqual(len(fixture.calls), 4)
            self.assertFalse(any(event[0].startswith('lease-') for event in fixture.events))
        with self.recovery_routes(probe_runtime=True) as fixture:
            fixture.qwen_snapshot.rmdir()
            packet = self.collect_recovery(fixture)
            self.assertEqual(len(packet['candidates']), 6)
            self.assertEqual(fixture.reports, [])
            self.assertFalse(any(event[0].startswith('lease-') for event in fixture.events))
            self.assertFalse(fixture.runtime_state.lease_live)

    def test_qwen_recovery_cache_hit_does_not_materialize_audio(self):
        with self.recovery_routes(cached=True, qwen_language=None) as fixture, patch.object(lyrics_align_pc,
                '_load_or_transcribe_roformer', side_effect=lambda **kwargs: fixture.workspace.transcript_path(
                    'AAAAAAAAAAA', 'en', 'roformer-kim-vocals')):
            # Whisper can reuse its own cache; Qwen must not request PCM on its cache hit.
            with patch.object(lyrics_align_pc, '_load_or_transcribe', side_effect=RuntimeError('cache-boundary refusal')):
                packet = self.collect_recovery(fixture, diagnostic=True)
            self.assertEqual(fixture.materialized, [])
            self.assertEqual(len(packet['candidates']), 3)
            self.assertTrue(all(call[2] for call in fixture.calls if call[0] == 'qwen3-asr'))
            self.assertTrue(all('auto' in candidate['recognition']['path'] for candidate in packet['candidates']))

    def test_recovery_partial_failures_preserve_independent_candidates_and_diagnostics(self):
        failure = RuntimeError('fixture separator failure')
        with self.recovery_routes(separation_failure=failure) as fixture:
            packet = self.collect_recovery(fixture, diagnostic=True)
            self.assertEqual(fixture.materialized.count('htdemucs-vocals'), 1)
            self.assertEqual(len(packet['candidates']), 6)
            self.assertEqual(len(packet['routeFailures']), 3)
            for row in packet['routeFailures']:
                reference = row['diagnostics'][0]
                body = (self.job/reference['file']).read_bytes()
                header = json.loads(body.split(b'\n', 1)[0])
                self.assertEqual(header['familyId'], row['familyId'])
                self.assertEqual(header['routeId'], 'htdemucs-vocals')
                self.assertEqual(header['audioSha256'], self.audio_sha)
            from stemwise_engine.private_diagnostics import retain_error
            with patch('stemwise_engine.private_diagnostics.retain_error', side_effect=OSError('fixture disk unavailable')):
                with self.assertRaises(RuntimeError) as raised:
                    self.collect_recovery(fixture)
            self.assertIs(raised.exception, failure)
            self.assertEqual(len(raised.exception.partial_candidates['candidates']), 6)
            self.assertNotIn('routeFailures', raised.exception.partial_candidates)
        with self.recovery_routes() as fixture, patch.object(alignment_models, 'roformer_assets',
                side_effect=ValueError('fixture corrupt pinned separator')) as assets:
            packet = self.collect_recovery(fixture, diagnostic=True)
            self.assertEqual(len(packet['candidates']), 5)
            self.assertEqual(len(packet['routeFailures']), 4)
            self.assertEqual({row['routeId'] for row in packet['routeFailures']},
                set(lyrics_align_pc.endpoints.ROFORMER_ROUTES))
            assets.assert_called_once()
            self.assertEqual(set(fixture.materialized), {'htdemucs-vocals'})
        empty_failure = RuntimeError('fixture every recognition failed')
        def fail_all(*args):
            raise empty_failure
        with self.recovery_routes(failure=fail_all) as fixture:
            with self.assertRaises(RuntimeError) as raised:
                self.collect_recovery(fixture)
            self.assertIs(raised.exception, empty_failure)
            self.assertFalse(hasattr(raised.exception, 'partial_candidates'))

    def test_recovery_fatal_failure_stops_other_routes(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.provider_operations import ProviderCancelled
        for error in (WorkerRetirementError('fixture retirement'), SongAttemptBlocked('fixture ownership'),
                ProviderCancelled('fixture cancelled'), LyricsContentBlocker('recording-adjudication-input-changed')):
            def fail(family, route, precision):
                raise error
            with self.subTest(error=type(error).__name__), self.recovery_routes(failure=fail) as fixture:
                with self.assertRaises(type(error)) as raised:
                    self.collect_recovery(fixture, diagnostic=True)
                self.assertIs(raised.exception, error)
                self.assertEqual(len(fixture.calls), 1)
                self.assertEqual(fixture.materialized, [])
                fixture.owner.close.assert_called_once()

    def test_recover_content_uses_retained_primary_without_approving_unchanged_rejection(self):
        with self.recovery_routes(cached=True) as fixture:
            inputs = evidence.recording_review_input_reference(self.job, fixture.primary.parent, self.audio_sha,
                {'path': fixture.primary.name, 'sha256': sha256_file(fixture.primary)})
            job = {'videoId': 'AAAAAAAAAAA', 'recordingEvidenceInputs': inputs,
                'lyricContentLimitation': {'scope': {'frozen': 'dispute'}}, 'state': 'BLOCKED',
                'unchangedFailure': True, 'contentResolution': None}
            atomic_write_json(self.job/'job.json', job)
            retained = self.snapshot()
            output = io.StringIO()
            with patch.object(evidence, 'load_config', return_value=self.config), redirect_stdout(output):
                code = evidence.main(['--recover-content', 'config.json', str(self.root), 'AAAAAAAAAAA',
                    str(self.audio), self.audio_sha])
            result = json.loads(output.getvalue())
            self.assertEqual(code, 0)
            self.assertEqual(set(result), {'status', 'recordingInputs', 'recoveryCandidates'})
            self.assertEqual(result['status'], 'content-recovery-complete')
            self.assertEqual(result['recordingInputs'], inputs)
            self.assertEqual(result['recoveryCandidates']['primaryRecognition'], inputs['recognition'])
            self.assertEqual(result['recoveryCandidates']['scope'], {'frozen': 'dispute'})
            self.assertTrue(all(call[1] != 'mix' for call in fixture.calls if call[0] == 'qwen3-asr'))
            self.assertEqual({path: path.read_bytes() for path in retained}, retained)

    def test_vocal_disagreement_reuses_real_caches_and_passive_readback_binds_the_original(self):
        from lyric_content_fixtures import write_recognition
        from stemwise_engine.lyrics_alignment import WordTimestamp
        from stemwise_engine.workflow_status import validate_recovery_candidates
        import builtins
        self.config['analysisTimeoutMinutes'] = 1
        (self.job/'drafts/original.txt').write_text('one two\nthree four\n', encoding='utf-8')
        workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
        primary = write_recognition(workspace.job_dir('AAAAAAAAAAA'), 'wrong two three four', (0,), self.audio_sha)
        primary.rename(primary.with_name('qwen-structure-en.private.json'))
        separator = Path(self.config['projectDir']).parent/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'
        separator.parent.mkdir(parents=True)
        separator.write_bytes(b'fixture model boundary')
        vocals = self.root/'vocals.wav'
        sf.write(vocals, np.full((48000, 2), .1), 48000, subtype='FLOAT')
        retained = self.snapshot()
        def transcribe(audio, **kwargs):
            self.assertEqual(kwargs['prompt'], '')
            text = 'one two three four' if audio == vocals else 'one too three four'
            return [WordTimestamp(word=word, raw=word, start=i*.2, end=i*.2+.1)
                    for i, word in enumerate(text.split())], {}
        model = SimpleNamespace(transcribe=Mock(side_effect=transcribe))
        owner = SimpleNamespace(compute_type='float16', get=Mock(return_value=model), close=Mock())
        real_sha = lyrics_align_pc._sha256_file
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=('wrong two three four', {'language': 'en'}, True)), \
                patch('stemwise_engine.worker_runtime_probe.ctranslate2_runtime_identity', return_value={'device': 'fixture'}), \
                patch.object(lyrics_align_pc, '_verify_whisper_snapshot'), \
                patch.object(alignment_models, 'WhisperModelOwner', return_value=owner), \
                patch.object(alignment_models, 'require_pinned_file'), \
                patch.object(lyrics_align_pc, '_sha256_file', side_effect=lambda p:
                    alignment_models.PINNED_DEMUCS_SHA256 if p == separator else real_sha(p)), \
                patch.object(lyrics_align_pc, 'separate_vocals_demucs', return_value=vocals) as separate, \
                patch.object(lyrics_align_pc, '_load_or_transcribe', wraps=REAL_WHISPER_TRANSCRIPTION):
            with self.assertRaises(LyricsContentBlocker) as raised:
                self.compare()
            packet = raised.exception.recovery_candidates
            with self.assertRaises(LyricsContentBlocker) as resumed:
                self.compare()
            self.assertEqual(packet, resumed.exception.recovery_candidates)
        self.assertEqual(model.transcribe.call_count, 4)
        separate.assert_called_once()
        self.assertEqual(owner.close.call_count, 3)
        self.assertEqual(packet['vocalRecovery']['status'], 'complete')
        self.assertEqual(len(packet['candidates']), 4)
        self.assertEqual({p: p.read_bytes() for p in retained}, retained)
        alignment_root = workspace.job_dir('AAAAAAAAAAA')
        records = [json.loads((alignment_root/c['recognition']['path']).read_bytes()) for c in packet['candidates']]
        self.assertEqual(records[0]['provenance']['audio']['sha256'], self.audio_sha)
        self.assertEqual(records[2]['provenance']['audio']['sha256'], sha256_file(vocals))
        self.assertTrue(all(c['audioSha256'] == self.audio_sha and c['publicationAuthority'] is False
                            for c in packet['candidates']))
        original_import = builtins.__import__
        def passive_import(name, *args, **kwargs):
            if name in {'stemwise_engine.recording_evidence', 'stemwise_engine.lyrics_align_pc',
                    'stemwise_engine.alignment_audio', 'stemwise_engine.alignment_models'} or name.split('.')[0] in {
                    'torch', 'numpy', 'faster_whisper', 'ctranslate2'}:
                raise AssertionError('Passive inspection imported numerical code: '+name)
            return original_import(name, *args, **kwargs)
        with patch.object(builtins, '__import__', side_effect=passive_import):
            validate_recovery_candidates(self.job, alignment_root, packet,
                video_id='AAAAAAAAAAA', audio_sha=self.audio_sha, duration_ms=1000)
            provenance = alignment_root/'audio-provenance.private.json'
            changed = json.loads(provenance.read_bytes())
            changed['audioViews']['htdemucs-vocals']['sourceAudioSha256'] = 'f'*64
            atomic_write_json(provenance, changed)
            self.assertIs(validate_recovery_candidates(self.job, alignment_root, packet,
                video_id='AAAAAAAAAAA', audio_sha=self.audio_sha, duration_ms=1000), packet)
            # The immutable observation survives changes to the materialized-view index;
            # its own exact chain remains mandatory even with a newly matching file hash.
            altered = json.loads(json.dumps(packet))
            reference = altered['candidates'][2]['audioViewWitness']
            witness_path = alignment_root/reference['path']
            witness = json.loads(witness_path.read_bytes())
            witness['audioViews']['htdemucs-vocals']['sourceAudioSha256'] = 'f'*64
            atomic_write_json(witness_path, witness)
            reference['sha256'] = sha256_file(witness_path)
            with self.assertRaisesRegex(ValueError, 'not bound to the Original'):
                validate_recovery_candidates(self.job, alignment_root, altered,
                    video_id='AAAAAAAAAAA', audio_sha=self.audio_sha, duration_ms=1000)

    def test_optional_vocal_failure_keeps_mix_candidate_but_retirement_still_stops(self):
        from lyric_content_fixtures import write_recognition
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.lyrics_alignment import WordTimestamp
        workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
        primary = write_recognition(workspace.job_dir('AAAAAAAAAAA'), 'wrong two three four', (0,), self.audio_sha)
        primary.rename(primary.with_name('qwen-structure-en.private.json'))
        separator = Path(self.config['projectDir']).parent/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'
        separator.parent.mkdir(parents=True)
        separator.write_bytes(b'fixture model boundary')
        model = SimpleNamespace(transcribe=Mock(return_value=([
            WordTimestamp(word=w, raw=w, start=i*.2, end=i*.2+.1)
            for i, w in enumerate('one too three four'.split())], {})))
        owner = SimpleNamespace(compute_type='float16', get=Mock(return_value=model), close=Mock())
        def run(**kwargs):
            if kwargs['separate_vocals']:
                raise failure
            return REAL_WHISPER_TRANSCRIPTION(**kwargs)
        for failure in (RuntimeError('optional separation failed'), WorkerRetirementError('retirement unknown')):
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                        return_value=('wrong two three four', {'language': 'en'}, True)), \
                    patch('stemwise_engine.worker_runtime_probe.ctranslate2_runtime_identity', return_value={'device': 'fixture'}), \
                    patch.object(lyrics_align_pc, '_verify_whisper_snapshot'), \
                    patch.object(alignment_models, 'WhisperModelOwner', return_value=owner), \
                    patch.object(alignment_models, 'require_pinned_file'), \
                    patch.object(lyrics_align_pc, '_load_or_transcribe', side_effect=run):
                expected = type(failure) if isinstance(failure, WorkerRetirementError) else LyricsContentBlocker
                with self.assertRaises(expected) as raised:
                    self.compare()
                if expected is LyricsContentBlocker:
                    self.assertEqual(len(raised.exception.recovery_candidates['candidates']), 2)
                    self.assertIs(raised.exception.recovery_error, failure)
                else:
                    self.assertIs(raised.exception, failure)

    def test_disagreement_collects_unprompted_full_original_candidate_without_approving_it(self):
        self._assert_disagreement_candidate()

    def test_zero_duration_recognition_is_retained_only_as_an_unapproved_candidate(self):
        self._assert_disagreement_candidate(zero_duration=True)

    def test_overlapping_recognition_is_retained_only_as_an_unapproved_candidate(self):
        self._assert_disagreement_candidate(overlap=True)

    def test_reference_repeat_proposal_survives_normal_task_and_status_without_adoption(self):
        self._assert_disagreement_candidate(recognized='one two three four four', expected_adaptation='review-required')

    def test_matching_primary_still_recovers_a_retained_independent_disagreement(self):
        self._assert_disagreement_candidate(primary_matches=True)

    def _assert_disagreement_candidate(self, *, zero_duration=False, overlap=False, primary_matches=False, recognized='one too three four', expected_adaptation='unresolved'):
        from lyric_content_fixtures import write_recognition
        from stemwise_engine.lyrics_alignment import WordTimestamp
        self.config['analysisTimeoutMinutes'] = 1
        (self.job/'drafts/original.txt').write_text('one two\nthree four\n', encoding='utf-8')
        workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
        primary_text = 'one two three four' if primary_matches else 'wrong two three four'
        primary = write_recognition(workspace.job_dir('AAAAAAAAAAA'), primary_text, (0,), self.audio_sha)
        primary = primary.rename(primary.with_name('qwen-structure-en.private.json'))
        retained = self.snapshot()
        model = SimpleNamespace(transcribe=Mock(return_value=(
            [WordTimestamp(word=word, raw=word, start=.05 if overlap and i == 1 else i*.2,
                end=i*.2 if zero_duration and i == 1 else i*.2+.1)
                for i, word in enumerate(recognized.split())], {})))
        owner = SimpleNamespace(compute_type='float16', get=Mock(return_value=model), close=Mock())
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=(primary_text, {'language': 'en'}, True)), \
                patch('stemwise_engine.lyrics_alignment.resolve_compute_type', return_value='float16'), \
                patch('stemwise_engine.worker_runtime_probe.ctranslate2_runtime_identity', return_value={'device': 'fixture'}), \
                patch.object(lyrics_align_pc, '_verify_whisper_snapshot'), \
                patch.object(alignment_models, 'WhisperModelOwner', return_value=owner), \
                patch.object(lyrics_align_pc, '_load_or_transcribe', wraps=REAL_WHISPER_TRANSCRIPTION) as independent:
            if primary_matches:
                original = {'text': 'one two\nthree four\n', 'language': 'en'}
                _, draft_sha, _, metadata_sha = evidence.read_recording_draft(self.job)
                identity = alignment_audio.file_identity(self.audio)
                identity['canonicalTimeTransform'] = alignment_audio.identity_transform(identity['stream'])
                evidence.collect_disagreement_candidate(self.config, workspace, 'AAAAAAAAAAA', self.audio,
                    identity, original, draft_sha, metadata_sha, primary, {})
            with self.assertRaises(LyricsContentBlocker) as raised:
                self.compare()
            self.assertTrue(hasattr(raised.exception, 'recovery_candidates'),
                'Imperfect raw timestamps must not discard unapproved lexical recovery.')
            first = raised.exception.recovery_candidates
            with self.assertRaises(LyricsContentBlocker) as resumed:
                self.compare()
            self.assertEqual(resumed.exception.recovery_candidates, first)
            if primary_matches:
                disputed = workspace.transcript_path('AAAAAAAAAAA', 'en', 'unprompted-mix')
                review = {'formatVersion': 2, 'videoId': 'AAAAAAAAAAA', 'audioSha256': self.audio_sha,
                    'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                    'recognition': {'path': disputed.name, 'sha256': sha256_file(disputed)},
                    'reviewer': 'Known-answer fixture', 'completedAt': '2026-10-24T00:00:00Z',
                    'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0,
                        'startWordIndex': 1, 'endWordIndex': 2,
                        'recognizedStartWordIndex': 1, 'recognizedEndWordIndex': 2,
                        'startMs': 200, 'endMs': 300, 'decision': 'confirmed-asr-substitution',
                        'heardText': 'two', 'rationale': 'Independent known-answer recording fixture.'}]}
                atomic_write_json(self.job/evidence.ADJUDICATION_FILE, review)
                self.assertEqual(self.compare()['contentResolution']['spanCount'], 1)
                self.assertEqual(evidence.adjudicate_retained_recording(self.config, self.root,
                    'AAAAAAAAAAA', {'sha256': self.audio_sha, 'durationMs': 1000})['spanCount'], 1)
                with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                        return_value=('wrong two three four', {'language': 'en'}, True)):
                    with self.assertRaises(LyricsContentBlocker):
                        self.compare()
                (self.job/evidence.ADJUDICATION_FILE).unlink()
                retained_bytes = disputed.read_bytes()
                for changed_field in ('audio', 'duration'):
                    changed = json.loads(retained_bytes)
                    if changed_field == 'audio':
                        changed['provenance']['audio']['sha256'] = 'f'*64
                    else:
                        changed['provenance']['durationMs'] = 1100
                    atomic_write_json(disputed, changed)
                    try:
                        self.assertTrue(self.compare()['structureSupport']['accepted'])
                    finally:
                        disputed.write_bytes(retained_bytes)
        self.assertEqual(independent.call_count, 2, 'cache-forward replay must not re-enter either healthy timed profile')
        self.assertFalse(independent.call_args.kwargs['use_approved_prompt'])
        self.assertFalse(independent.call_args.kwargs['separate_vocals'])
        self.assertEqual(independent.call_args.kwargs['analysis_audio'], self.audio)
        self.assertEqual(owner.get.call_count, 2)
        self.assertEqual(model.transcribe.call_count, 2)
        self.assertEqual(model.transcribe.call_args.kwargs['prompt'], '')
        self.assertEqual(owner.close.call_count, 3 if primary_matches else 2)
        recovery = raised.exception.recovery_candidates
        self.assertFalse(recovery['publicationAuthority'])
        self.assertEqual(recovery['status'], 'pending-recording-review')
        candidate = self.job/recovery['candidates'][0]['candidate']['path']
        self.assertEqual(candidate.read_text(encoding='utf-8'), recognized+'\n')
        adaptation = recovery['candidates'][0]['referenceAdaptation']
        self.assertEqual(adaptation['status'], expected_adaptation)
        self.assertIs(adaptation['publicationAuthority'], False)
        self.assertIs(adaptation['timingAuthority'], False)
        projected = recovery['candidates'][0].get('referenceCandidate')
        if expected_adaptation == 'review-required':
            self.assertEqual((self.job/projected['path']).read_text(encoding='utf-8'), 'one two\nthree four four\n')
        else:
            self.assertIsNone(projected)
        if zero_duration or overlap:
            reference = recovery['candidates'][0]['recognition']
            cache = json.loads((workspace.job_dir('AAAAAAAAAAA')/reference['path']).read_bytes())
            if zero_duration:
                self.assertEqual(cache['words'][1]['start'], cache['words'][1]['end'])
            else:
                self.assertLess(cache['words'][1]['start'], cache['words'][0]['end'])
            with self.assertRaisesRegex(ValueError, 'invalid recognized words'):
                evidence._recognized(cache, self.audio_sha)
        self.assertEqual({path: path.read_bytes() for path in retained}, retained)
        from test_future_song import workflow_fixture
        import builtins
        workflow = workflow_fixture(self.root)
        workflow.root, workflow.jobs = self.root, self.root/'jobs'
        runtime = workflow.runtime
        atomic_write_json(runtime/'work/checkpoints/AAAAAAAAAAA.json', {'status': 'complete',
            'originalAudio': {'sha256': self.audio_sha, 'durationMs': 1000}})
        job = {'videoId': 'AAAAAAAAAAA', 'state': 'WAITING_FOR_EDITORIAL', 'deliveryAllowed': True,
               'recordingRecoveryCandidates': recovery}
        original_import = builtins.__import__
        def read_only_import(name, *args, **kwargs):
            if name in {'stemwise_engine.recording_evidence', 'stemwise_engine.lyrics_align_pc',
                    'stemwise_engine.alignment_models'} or name.split('.')[0] in {'torch', 'numpy', 'faster_whisper', 'ctranslate2'}:
                raise AssertionError('Passive inspection imported numerical code: '+name)
            return original_import(name, *args, **kwargs)
        with patch.object(builtins, '__import__', side_effect=read_only_import):
            status = workflow.summary(job, verify_delivery=False)
            self.assertEqual(status['recordingRecoveryCandidates']['status'], 'pending-recording-review')
            self.assertEqual(status['recordingRecoveryCandidates']['candidates'][0]['referenceAdaptation'], adaptation)
            self.assertIn(b'Unapproved recording disagreement candidates', workflow._task_payload(job, b'Task'))
            if projected is not None:
                projection_path = self.job/projected['path']
                projection_bytes = projection_path.read_bytes()
                projection_path.write_bytes(b'changed proposal\n')
                self.assertEqual(workflow.summary(job, verify_delivery=False)['recordingRecoveryCandidates']['status'], 'unavailable')
                projection_path.write_bytes(projection_bytes)
            import copy
            changed = copy.deepcopy(job)
            changed['recordingRecoveryCandidates']['candidates'][0]['referenceAdaptation']['status'] = 'accepted'
            self.assertEqual(workflow.summary(changed, verify_delivery=False)['recordingRecoveryCandidates']['status'], 'unavailable')
            self.assertNotIn('recordingRecoveryCandidates', workflow.summary(
                {key: value for key, value in job.items() if key != 'recordingRecoveryCandidates'}, verify_delivery=False))
            candidate.write_bytes(b'owner edit\n')
            self.assertEqual(workflow.summary(job, verify_delivery=False)['recordingRecoveryCandidates']['status'], 'unavailable')
            self.assertIn(b'unavailable', workflow._task_payload(job, b'Task'))

    def test_optional_disagreement_failure_preserves_blocker_but_terminal_or_unknown_work_stops(self):
        from lyric_content_fixtures import write_recognition
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.alignment_attempt import SongAttemptBlocked
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline, ProviderStageDeadline
        self.config['analysisTimeoutMinutes'] = 1
        workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
        primary = write_recognition(workspace.job_dir('AAAAAAAAAAA'), 'wrong two three four', (0,), self.audio_sha)
        primary.rename(primary.with_name('qwen-structure-en.private.json'))
        before = self.snapshot()
        for failure in (RuntimeError('optional decoder failure'), ProviderStageDeadline('route timed out'),
                WorkerRetirementError('unknown retirement'), ProviderCancelled('parent stop'),
                ProviderDeadline('parent deadline'), SongAttemptBlocked('accepted work remains unknown')):
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                        return_value=('wrong two three four', {'language': 'en'}, True)), \
                    patch.object(lyrics_align_pc, '_load_or_transcribe', side_effect=failure):
                expected = LyricsContentBlocker if type(failure) in {RuntimeError, ProviderStageDeadline} else type(failure)
                with self.assertRaises(expected) as raised:
                    self.compare()
                if expected is LyricsContentBlocker:
                    self.assertEqual(raised.exception.reasons, ('content-sequence-uncertain',))
                    self.assertTrue(hasattr(raised.exception, 'recording_inputs'))
                else:
                    self.assertIs(raised.exception, failure)
                self.assertEqual({path: path.read_bytes() for path in before}, before)
        atomic_write_json(self.job/'drafts/lyrics.meta.json', {'originalLanguage': 'xx'})
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=('wrong two three four', {'language': 'en'}, True)), \
                patch.object(lyrics_align_pc, '_load_or_transcribe') as independent:
            with self.assertRaises(LyricsContentBlocker):
                self.compare()
            independent.assert_not_called()

    def test_physical_lines_reach_comparison_and_retained_adjudication_unchanged(self):
        import copy
        from lyric_content_fixtures import write_recognition
        from stemwise_engine.lyric_content import require_recording_adjudication
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable
        for separator in ('\u2028', '\u2029', '\u0085', '\n', '\r\n', '\r'):
            for gap in (0, 1, 2):
                with self.subTest(separator=repr(separator), gap=gap):
                    review_path = self.job/evidence.ADJUDICATION_FILE
                    review_path.unlink(missing_ok=True)
                    text = 'alpha' + separator + 'beta' + ('\n'*gap + 'gamma' if gap else '')
                    (self.job/'drafts/original.txt').write_bytes(text.encode('utf-8'))
                    draft, draft_sha, _, metadata_sha = evidence.read_recording_draft(self.job)
                    original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en', 'text': draft}
                    unicode_separator = separator in ('\u2028', '\u2029', '\u0085')
                    indices = [0] if unicode_separator else [0, 1]
                    if gap:
                        indices.append(gap if len(indices) == 1 else gap+1)
                    words = ['alpha', 'beta'] + (['gamma'] if gap else [])
                    before = self.snapshot()
                    with self.subTest(route='complete'), patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                            return_value=(' '.join(words), {'language': 'en'}, True)):
                        result = self.compare()
                        self.assertTrue(result['structureSupport']['accepted'])
                        self.assertEqual(result['structureSupport']['source_physical_indices'], tuple(indices))
                    self.assertEqual(self.snapshot(), before)
                    for omitted in range(len(words)):
                        with self.subTest(omitted=omitted), patch.object(evidence,
                                '_load_or_run_qwen3_structure_transcript',
                                return_value=(' '.join(words[:omitted]+words[omitted+1:]), {'language': 'en'}, True)):
                            with self.assertRaises(LyricsContentBlocker):
                                self.compare()
                    recognized = ' '.join(word for word in words if word != 'beta')
                    workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
                    cache = write_recognition(workspace.job_dir('AAAAAAAAAAA'), original, indices,
                        self.audio_sha, recognized=recognized)
                    target = cache.with_name('qwen-structure-en.private.json')
                    cache.replace(target)
                    performed, source, first = (0, 0, 1) if unicode_separator else (1, 1, 0)
                    review = {'formatVersion': 2, 'videoId': 'AAAAAAAAAAA', 'audioSha256': self.audio_sha,
                        'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                        'recognition': {'path': target.name, 'sha256': sha256_file(target)},
                        'reviewer': 'Fixture operator', 'completedAt': '2026-10-24T00:00:00Z',
                        'spans': [{'performedIndex': performed, 'sourcePhysicalIndex': source,
                            'startWordIndex': first, 'endWordIndex': first+1,
                            'recognizedStartWordIndex': 1, 'recognizedEndWordIndex': 1,
                            'startMs': 100, 'endMs': 200, 'decision': 'confirmed-asr-omission',
                            'heardText': 'beta', 'rationale': 'Known recording span.'}]}
                    atomic_write_json(review_path, review)
                    before = self.snapshot()
                    with self.subTest(route='reviewed-omission'), patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                            return_value=(recognized, {'language': 'en'}, True)) as transcribe:
                        self.assertEqual(self.compare()['contentResolution']['spanCount'], 1)
                    self.assertTrue(transcribe.call_args.kwargs['cache_only'])
                    audio = {'sha256': self.audio_sha, 'durationMs': 1000}
                    with self.subTest(route='retained-review'):
                        self.assertEqual(evidence.adjudicate_retained_recording(self.config, self.root,
                            'AAAAAAAAAAA', audio)['spanCount'], 1)
                    self.assertEqual(self.snapshot(), before)
                    arguments = dict(video_id='AAAAAAAAAAA', original=original, transcript=recognized,
                        audio_sha=self.audio_sha, duration_ms=1000, draft_sha=draft_sha,
                        metadata_sha=metadata_sha, evidence_path=target, evidence_root=target.parent)
                    for invalid in ([], [-1], [True], [len(draft)+1], ['0'], None):
                        with self.subTest(invalid=invalid), self.assertRaises(LyricsContentBlocker):
                            require_recording_adjudication(review, indices=invalid, **arguments)
                    changed = copy.deepcopy(review)
                    changed['spans'][0]['heardText'] = 'wrong'
                    atomic_write_json(review_path, changed)
                    with self.assertRaises(LyricsContentBlocker):
                        evidence.adjudicate_retained_recording(self.config, self.root, 'AAAAAAAAAAA', audio)
                    changed = copy.deepcopy(review)
                    changed['draftSha256'] = 'f'*64
                    atomic_write_json(review_path, changed)
                    with self.assertRaises(LyricsContentBlocker):
                        evidence.adjudicate_retained_recording(self.config, self.root, 'AAAAAAAAAAA', audio)
                    atomic_write_json(review_path, review)
                    target.write_bytes(target.read_bytes()+b' ')
                    with self.assertRaises(ContentEvidenceUnavailable):
                        evidence.adjudicate_retained_recording(self.config, self.root, 'AAAAAAAAAAA', audio)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = {'projectDir': str(self.root / 'PC side/builder')}
        self.job = self.root / 'jobs/AAAAAAAAAAA'
        (self.job / 'drafts').mkdir(parents=True)
        self.words = 'one two three four five six\nseven eight nine ten eleven twelve\n'
        (self.job / 'drafts/original.txt').write_text(self.words, encoding='utf-8')
        atomic_write_json(self.job / 'drafts/lyrics.meta.json', {'originalLanguage': 'en'})
        self.authority = self.job / 'agent-review.json'
        self.authority.write_text('{"authorityOutcome":"authority-unresolved"}', encoding='utf-8')
        self.audio = self.root / 'mix.wav'
        sf.write(self.audio, np.full((48000, 2), .2), 48000, subtype='FLOAT')
        self.audio_sha = sha256_file(self.audio)
        self.run_process = self.probe_only
        probe = patch.object(alignment_audio, 'run_process_tree', side_effect=self.probe_only)
        probe.start()
        self.addCleanup(probe.stop)
        independent = patch.object(lyrics_align_pc, '_load_or_transcribe', side_effect=RuntimeError('inert independent recognizer boundary'))
        independent.start()
        self.addCleanup(independent.stop)
        compute = patch('stemwise_engine.lyrics_alignment.resolve_compute_type', return_value='float16')
        compute.start()
        self.addCleanup(compute.stop)

    def snapshot(self):
        return {path: path.read_bytes() for path in (*self.job.rglob('*'), self.audio) if path.is_file()}

    def test_sequence_disagreement_is_unresolved_content_not_unavailable_lyrics(self):
        from stemwise_engine.alignment_attempt import SongAttemptLedger
        original = (self.job/'drafts/original.txt').read_bytes()
        ledger = SongAttemptLedger(self.job/'song-attempt.private.json',
            material_inputs={'audioSha256': self.audio_sha})
        with ledger.active(), patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=('different performed words', {'language': 'en'}, False)) as recognize:
            with self.assertRaises(LyricsContentBlocker):
                self.compare()
        ledger.refresh()
        self.assertEqual(ledger.record['enhancementDisposition']['code'], 'lyrics-content-unresolved')
        self.assertEqual(recognize.call_count, 1)
        self.assertEqual((self.job/'drafts/original.txt').read_bytes(), original)
        self.assertEqual(sha256_file(self.audio), self.audio_sha)

    def compare(self, **kwargs):
        return evidence.compare(self.config, self.root, 'AAAAAAAAAAA', self.audio, self.audio_sha, **kwargs)

    def probe_only(self, command, **options):
        self.assertTrue(Path(command[0]).name.startswith('ffprobe'), 'No additional model process is permitted')
        # External ffprobe is not part of these content/state checks.
        return SimpleNamespace(returncode=0, stdout=json.dumps({"streams": [{
            "sample_rate": "48000", "channels": 2, "duration_ts": 48000, "time_base": "1/48000",
        }]}))

    def test_supported_exact_mix_uses_one_unprompted_recognition_and_preserves_authority(self):
        before = self.snapshot()
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                          return_value=(self.words, {'language': 'en'}, True)) as recognize:
            result = self.compare()
        recognize.assert_called_once()
        request = recognize.call_args.kwargs
        self.assertNotIn('text', request)
        self.assertNotIn('prompt', request)
        self.assertNotIn('audio_view', request)
        self.assertEqual(request['audio_identity']['sha256'], self.audio_sha)
        self.assertTrue(result['structureSupport']['accepted'])
        self.assertEqual(result['structureSupport']['metrics']['direct_line_count'], 2)
        self.assertEqual(result['additionalEvidence'], [])
        self.assertTrue(result['reusedTranscript'])
        self.assertTrue(result['privateTranscript'].endswith('qwen-structure-en.private.json'))
        self.assertNotIn(self.words, json.dumps(result))
        self.assertEqual(self.snapshot(), before)

    def test_incomplete_wrong_extra_or_repeated_words_block_before_scoring_or_more_models(self):
        for transcript in ('one two three', self.words.replace('seven', 'PRIVATE wrong wording'),
                           self.words + 'extra word', self.words * 2):
            with self.subTest(transcript=transcript):
                before = self.snapshot()
                with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                        return_value=(transcript, {}, True)) as recognize, patch.object(
                        evidence, 'score_structure_witness', wraps=evidence.score_structure_witness) as score, patch.object(
                        alignment_audio, 'run_process_tree', side_effect=self.probe_only) as worker:
                    with self.assertRaises(LyricsContentBlocker) as caught:
                        self.compare()
                recognize.assert_called_once()
                score.assert_not_called()
                self.assertEqual(worker.call_count, 1)
                self.assertNotIn('PRIVATE', str(caught.exception))
                self.assertEqual(self.snapshot(), before)

    def test_missing_pronunciation_marks_are_not_treated_as_timing(self):
        (self.job / 'drafts/original.txt').write_text('café bright river voices waking daylight\n', encoding='utf-8')
        before = self.snapshot()
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=('cafe bright river voices waking daylight', {}, True)) as recognize, patch.object(
                evidence, 'score_structure_witness', wraps=evidence.score_structure_witness) as score:
            with self.assertRaises(LyricsContentBlocker):
                self.compare()
        recognize.assert_called_once()
        score.assert_not_called()
        self.assertEqual(self.snapshot(), before)

    def test_accepted_lrc_original_uses_parsed_occurrence_lines_without_rewriting_owner_input(self):
        original = self.job / 'drafts/original.txt'
        original.unlink()
        lrc = self.job / 'drafts/original.lrc'
        lines = self.words.splitlines()
        lrc.write_text(f'[ar:Fixture]\n\n[00:00.00]{lines[0]}\n[00:01.00]{lines[1]}\n', encoding='utf-8')
        before = self.snapshot()
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript', return_value=(self.words, {}, True)):
            result = self.compare()
        self.assertTrue(result['structureSupport']['accepted'])
        self.assertEqual(result['structureSupport']['metrics']['line_count'], 2)
        self.assertEqual(self.snapshot(), before)
        self.assertFalse(original.exists())

    def test_auto_recognition_reports_actual_mix_cache_without_retagging_bosnian(self):
        atomic_write_json(self.job / 'drafts/lyrics.meta.json', {'originalLanguage': 'bs'})
        before = self.snapshot()
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript',
                return_value=(self.words, {'language': None}, True)) as recognize:
            result = self.compare()
        recognize.assert_called_once()
        self.assertEqual(recognize.call_args.kwargs['language'], 'bs')
        self.assertEqual(Path(result['privateTranscript']).name, 'qwen-structure.private.json')
        self.assertEqual(result['additionalEvidence'], [])
        self.assertEqual(self.snapshot(), before)

    def test_kurdish_latin_review_uses_existing_native_mix_recognizer_and_keeps_authority(self):
        import stemwise_engine.mms_asr as mms_asr
        from stemwise_engine.lyrics_alignment import WordTimestamp
        from test_mms_asr import TEST_RUNTIME
        atomic_write_json(self.job / 'drafts/lyrics.meta.json', {'originalLanguage': 'ku', 'originalScript': 'Latn'})
        words = [WordTimestamp(word=word, raw=word, start=i*.04, end=(i+1)*.04)
                 for i, word in enumerate(self.words.split())]
        before = self.snapshot()
        with patch.object(mms_asr, 'runtime_provenance', return_value=TEST_RUNTIME), patch.object(
                mms_asr, 'load_or_transcribe', return_value=(words, {'familyId': 'mms-asr'}, True)) as native, patch.object(
                evidence, '_load_or_run_qwen3_structure_transcript', side_effect=AssertionError('No Qwen fallback')) as qwen:
            result = self.compare()
        native.assert_called_once()
        self.assertEqual(native.call_args.kwargs['adapter'], 'kmr-script_latin')
        self.assertEqual(native.call_args.kwargs['route'], 'mix')
        self.assertEqual(native.call_args.kwargs['audio_identity']['sha256'], self.audio_sha)
        self.assertFalse(native.call_args.kwargs['rerun'])
        self.assertTrue(result['structureSupport']['accepted'])
        self.assertEqual(result['structureSupport']['family'], 'mms-asr')
        self.assertTrue(result['privateTranscript'].endswith('native-asr-kmr-script_latin-mix.private.json'))
        qwen.assert_not_called()
        self.assertEqual(self.snapshot(), before)



    def test_initial_or_concurrent_pcm_change_rejects_without_rewriting_owner_inputs(self):
        before = {path: raw for path, raw in self.snapshot().items() if path != self.audio}
        sf.write(self.audio, np.full((48000, 2), .4), 48000, subtype='FLOAT')
        changed = self.audio.read_bytes()
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript') as recognize:
            with self.assertRaisesRegex(ValueError, 'audio changed'):
                self.compare()
        recognize.assert_not_called()
        self.assertEqual(self.audio.read_bytes(), changed)
        self.audio_sha = sha256_file(self.audio)
        def recognize(**_arguments):
            sf.write(self.audio, np.full((48000, 2), .6), 48000, subtype='FLOAT')
            return self.words, {}, False
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript', side_effect=recognize) as worker:
            with self.assertRaisesRegex(ValueError, 'audio changed during'):
                self.compare()
        worker.assert_called_once()
        self.assertNotEqual(sha256_file(self.audio), self.audio_sha)
        self.assertEqual({path: path.read_bytes() for path in before}, before)

    def test_recognition_failure_propagates_without_success_or_authority_changes(self):
        before = self.snapshot()
        failure = RuntimeError('private recognizer failure')
        with patch.object(evidence, '_load_or_run_qwen3_structure_transcript', side_effect=failure) as recognize:
            with self.assertRaises(RuntimeError) as caught:
                self.compare()
        self.assertIs(caught.exception, failure)
        recognize.assert_called_once()
        self.assertEqual(self.snapshot(), before)

    def test_real_mix_cache_reader_reuses_bound_transcript_and_rejects_changed_worker_identity(self):
        from test_qwen3_asr_transcribe import output_fixture, generation_fixture
        from process_fixture import prepare_runtime_layout
        project = Path(self.config['projectDir'])
        prepare_runtime_layout(project)
        for name in ('.venv/bin/python', 'src/qwen3_asr_transcribe.py'):
            path = project / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('# synthetic worker dependency\n', encoding='utf-8')
        calls = []
        def worker(argv, **options):
            if '--request' not in argv:
                return self.run_process(argv, **options)
            request = json.loads(Path(argv[argv.index('--request') + 1]).read_text(encoding='utf-8'))
            output = output_fixture(request)
            output['result'] = generation_fixture(self.words, request['language'])
            Path(argv[argv.index('--output') + 1]).write_text(json.dumps(output), encoding='utf-8')
            calls.append(request)
            return SimpleNamespace(returncode=0)
        before = self.snapshot()
        with patch.object(lyrics_align_pc, '_qwen3_asr_snapshot', return_value=self.root), patch.object(
                lyrics_align_pc, '_qwen3_asr_runtime_provenance', return_value={'synthetic': True, 'supportedLanguages': ['en']}), patch.object(alignment_models, 'roformer_offline_env', return_value={}), patch.object(
                lyrics_align_pc, 'run_process_tree', side_effect=worker):
            first = self.compare()
            self.assertFalse(first['reusedTranscript'])
            cache = Path(first['privateTranscript'])
            raw = cache.read_bytes()
            second = self.compare()
            self.assertTrue(second['reusedTranscript'])
            self.assertEqual(cache.read_bytes(), raw)
            self.assertEqual(len(calls), 1)
            invalid = json.loads(raw)
            invalid['workerOutput']['audio']['sha256'] = 'f' * 64
            cache.write_text(json.dumps(invalid), encoding='utf-8')
            repaired = self.compare()
            self.assertFalse(repaired['reusedTranscript'])
            self.assertEqual(len(calls), 2)
            self.assertTrue(repaired['structureSupport']['accepted'])
            self.assertTrue(all(call['audio']['sha256'] == self.audio_sha for call in calls))
        self.assertEqual(self.snapshot(), before)

    def test_explicit_retained_omission_review_resolves_only_its_recording_evidence_blocker(self):
        import copy
        from contextlib import ExitStack
        from process_fixture import prepare_runtime_layout
        from test_future_song import workflow_fixture
        from test_qwen3_asr_transcribe import output_fixture, generation_fixture
        from stemwise_engine.lyric_content import require_exact_sequence, require_recording_adjudication
        from stemwise_engine.editorial_review import POLICY_VERSION
        project = Path(self.config['projectDir'])
        (project / '.venv/bin').mkdir(parents=True)
        (project / '.venv/bin/python').touch()
        (project / 'src').mkdir()
        source = Path(lyrics_align_pc.__file__).parent / 'qwen3_asr_transcribe.py'
        (project / 'src/qwen3_asr_transcribe.py').write_bytes(source.read_bytes())
        self.config['outputDir'] = str(self.root / 'runtime')
        atomic_write_json(self.job / 'drafts/lyrics.meta.json', {
            'videoId': 'AAAAAAAAAAA', 'originalLanguage': 'en', 'originalScript': 'Latn',
            'sources': {'original': {'name': 'Source'}}})
        atomic_write_json(self.job / 'job.json', {'videoId': 'AAAAAAAAAAA',
            'policyVersion': POLICY_VERSION, 'state': 'WAITING_FOR_EDITORIAL', 'deliveryAllowed': False})
        workflow = workflow_fixture(self.root)
        prepare_runtime_layout(project)
        workflow.root, workflow.config = self.root, self.config
        workflow.runtime = self.root / 'runtime'
        workflow.config_path = self.root / 'config.json'
        workflow._job_dir = lambda _: self.job
        workflow._windows_path = lambda path: str(path)
        workflow._runtime_config = lambda: workflow.config
        workflow._song_policy_matches = lambda _: True
        workflow._write_task = lambda _: None
        workflow._pipeline = lambda: SimpleNamespace(load_checkpoint=lambda _: {
            'originalAudio': {'path': str(self.audio), 'sha256': self.audio_sha, 'durationMs': 1000}})
        def review_inputs(job, *, original_only):
            self.assertEqual(job['videoId'], 'AAAAAAAAAAA')
            self.assertIs(original_only, True)
            _, draft_sha, _, metadata_sha = evidence.read_recording_draft(self.job)
            return {'audioSha256': self.audio_sha, 'draftSha256': draft_sha,
                'originalMetadataSha256': metadata_sha, 'sourcesSha256': sha256_file(self.job/'sources.json'),
                'plainDraftLayout': {}}
        workflow._review_inputs = review_inputs
        workflow._alignment_worker_environment = lambda: {}
        audio_identity = {'path': str(self.audio), 'sha256': self.audio_sha, 'durationMs': 1000}
        workflow._completed_analysis_audio = lambda *_: audio_identity
        calls = []
        omitted = self.words.replace('one two three', 'one three')
        runtime = output_fixture({'audio': {'sha256': self.audio_sha, 'byteCount': 1},
            'language': 'en', 'model': {'revision': 'fixture', 'snapshotSha256': 'f'*64}})['runtime']
        def recognize(argv, **options):
            request = json.loads(Path(argv[argv.index('--request') + 1]).read_text(encoding='utf-8'))
            output = output_fixture(request)
            output['result'] = generation_fixture(omitted, request['language'])
            Path(argv[argv.index('--output') + 1]).write_text(json.dumps(output), encoding='utf-8')
            calls.append(request)
            return SimpleNamespace(returncode=0)
        def child(argv, **options):
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(argv[1:3], ['-m', 'stemwise_engine.recording_evidence'])
                code = evidence.main(argv[3:])
            return SimpleNamespace(returncode=code, stdout=output.getvalue(), stderr='')
        owner_before = {p: p.read_bytes() for p in (self.audio, self.authority,
            self.job / 'drafts/original.txt', self.job / 'drafts/lyrics.meta.json')}
        atomic_write_json(self.job/'sources.json', {'videoId': 'AAAAAAAAAAA', 'lyricEvidenceSources': []})
        with ExitStack() as stack:
            stack.enter_context(patch('stemwise_engine.source_acquisition.discover_counterpart_candidates', return_value={
                'decision': 'not-found', 'searchStatus': 'complete', 'surfaceErrors': [], 'candidates': [],
                'surfaceOutcomes': {surface: {'status': 'successful-empty'} for surface in ('songs', 'videos')}}))
            for target, name, options in (
                (lyrics_align_pc, '_qwen3_asr_snapshot', {'return_value': self.root}),
                (lyrics_align_pc, '_qwen3_asr_runtime_provenance', {'return_value': {'runtime': runtime, 'supportedLanguages': ['en']}}),
                (alignment_models, 'roformer_offline_env', {'return_value': {}}),
                (lyrics_align_pc, 'run_process_tree', {'side_effect': recognize}),
                (evidence, 'load_config', {'return_value': self.config})):
                stack.enter_context(patch.object(target, name, **options))
            stack.enter_context(patch('stemwise_engine.future_song.run_process_tree', side_effect=child))
            limited = workflow.recording_evidence('AAAAAAAAAAA')
            self.assertTrue(limited['reviewRequired'])
            blocked = workflow._load_job('AAAAAAAAAAA')
            self.assertIn('content-sequence-uncertain', blocked['lyricContentLimitation']['reasons'])
            self.assertNotIn('outcome', blocked)
            exposed = workflow._status_summary(blocked, verify_delivery=False)['recordingEvidenceInputs']
            self.assertEqual(exposed, blocked['recordingEvidenceInputs'])
            self.assertNotIn(self.words, json.dumps(exposed))
            type(workflow)._write_task(workflow, blocked)
            task = (self.job / 'AGENT_TASK.txt').read_text(encoding='utf-8')
            self.assertIn(exposed['recognition']['sha256'], task)
            self.assertIn(exposed['draftSha256'], task)
            self.assertIn(exposed['metadataSha256'], task)
            cache = evidence.AlignmentWorkspace(self.root, workspace_root=self.root).job_dir(
                'AAAAAAAAAAA') / 'qwen-structure-en.private.json'
            retained = cache.read_bytes()
            _, draft_sha, _, metadata_sha = evidence.read_recording_draft(self.job)
            review = {'formatVersion': 2, 'videoId': 'AAAAAAAAAAA', 'audioSha256': self.audio_sha,
                'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                'recognition': {'path': cache.name, 'sha256': sha256_file(cache)},
                'reviewer': 'Fixture operator', 'completedAt': '2026-10-24T00:00:00Z',
                'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0, 'startWordIndex': 1,
                    'endWordIndex': 2, 'recognizedStartWordIndex': 1, 'recognizedEndWordIndex': 1, 'startMs': 100, 'endMs': 200,
                    'decision': 'confirmed-asr-omission', 'heardText': 'two',
                    'rationale': 'The omitted word is independently audible in this exact recording span.'}]}
            review_path = self.job / evidence.ADJUDICATION_FILE
            atomic_write_json(review_path, review)
            result = workflow.recording_evidence('AAAAAAAAAAA')
            self.assertTrue(result['reusedTranscript'])
            self.assertEqual(result['contentResolution']['kind'], 'explicit-recording-span-adjudication')
            self.assertNotIn('structureSupport', result)
            self.assertTrue(result['reviewRequired'])
            resolved = workflow._load_job('AAAAAAAAAAA')
            self.assertEqual(resolved['state'], 'WAITING_FOR_EDITORIAL')
            self.assertFalse(resolved['deliveryAllowed'])
            self.assertNotIn('outcome', resolved)
            self.assertNotIn('lyricContentLimitation', resolved)
            self.assertEqual(resolved['recordingContentEvidence']['evidence'],
                {'path': cache.name, 'sha256': sha256_file(cache)})
            self.assertEqual(resolved['recordingEvidence']['status'], 'complete')
            self.assertEqual(len(calls), 1, 'Explicit review must consume retained ASR, never rerun it')
            self.assertEqual(cache.read_bytes(), retained)
            self.assertEqual({p: p.read_bytes() for p in owner_before}, owner_before)
            # The exact decision supplies content admission, never edited ASR or timing authority.
            with self.assertRaises(LyricsContentBlocker):
                require_exact_sequence({'text': self.words, 'language': 'en'}, [0, 1], omitted, None, None)
            from stemwise_engine.lyric_content import confirm_content, require_content, require_admitted_sequence
            original = {'text': self.words.rstrip('\n'), 'language': 'en'}
            admission = confirm_content(original, (0, 1), 'c'*64, self.audio_sha,
                evidence_path=cache, evidence_root=cache.parent, adjudication_job_dir=self.job)
            self.assertEqual(admission.adjudication['sha256'], sha256_file(review_path))
            verified = require_content(admission.to_record(), original, (0, 1), 'c'*64,
                self.audio_sha, cache.parent, adjudication_job_dir=self.job)
            require_admitted_sequence(verified, original, (0, 1), omitted,
                evidence_path=cache, evidence_root=cache.parent, adjudication_job_dir=self.job)
            from stemwise_engine.lyrics_alignment import StructureWitness, StructureWitnessMetrics
            structure = StructureWitness('qwen3-asr', 'qwen3-asr-unprompted-mix', True, (), (0, 1),
                StructureWitnessMetrics(12, 11, 11, 2, 2, 2, 11/12, 1., 1., 1., 100.))
            def qualified_score(text, sources, transcript):
                self.assertEqual((text, tuple(sources), transcript), (original['text'], (0, 1), omitted))
                return structure
            workspace = evidence.AlignmentWorkspace(self.root, workspace_root=self.root)
            with patch.object(lyrics_align_pc, 'score_structure_witness', side_effect=qualified_score) as score:
                scores, _, _ = lyrics_align_pc._score_qwen_structure_routes(original, {(0, 1)}, ('en',),
                    lambda _: (omitted, {}, True), admission=verified, workspace=workspace,
                    video_id='AAAAAAAAAAA', evidence_path=cache)
                self.assertEqual(scores, [structure])
                score.assert_called_once()
                with self.assertRaises(LyricsContentBlocker):
                    lyrics_align_pc._score_qwen_structure_routes(original, {(0, 1)}, ('en',),
                        lambda _: (omitted, {}, True), admission=verified, workspace=workspace,
                        video_id='AAAAAAAAAAA', evidence_path=cache.with_name('unrelated.json'))
            with self.assertRaises(LyricsContentBlocker):
                require_admitted_sequence(verified, original, (0, 1), omitted,
                    evidence_path=cache.with_name('another-recognizer.json'),
                    evidence_root=cache.parent, adjudication_job_dir=self.job)
            # Occurrence/structure uncertainty in this stage is not resolved by a word review.
            for reason in ('content-occurrence-uncertain', 'content-support-uncertain'):
                workflow._record_validation_outcome('AAAAAAAAAAA', LyricsContentBlocker(reason))
                stopped = workflow._load_job('AAAAAAAAAAA')
                stopped['outcome']['sourceStage'] = 'recording-evidence'
                workflow._save_job(stopped)
                before_recovery = workflow._load_job('AAAAAAAAAAA')
                with self.subTest(reason=reason), self.assertRaisesRegex(RuntimeError, 'inert independent recognizer boundary'):
                    workflow.recording_evidence('AAAAAAAAAAA')
                after_recovery = workflow._load_job('AAAAAAAAAAA')
                for key in ('outcome', 'lyricContentLimitation', 'deliveryAllowed'):
                    self.assertEqual(after_recovery.get(key), before_recovery.get(key))
            # A later unrelated unresolved alignment cannot be erased by this earlier review.
            workflow._record_validation_outcome('AAAAAAAAAAA', LyricsContentBlocker('content-sequence-uncertain'))
            before_recovery = workflow._load_job('AAAAAAAAAAA')
            with self.assertRaisesRegex(RuntimeError, 'inert independent recognizer boundary'):
                workflow.recording_evidence('AAAAAAAAAAA')
            after_recovery = workflow._load_job('AAAAAAAAAAA')
            for key in ('outcome', 'lyricContentLimitation', 'deliveryAllowed'):
                self.assertEqual(after_recovery.get(key), before_recovery.get(key))
            self.assertEqual(len(calls), 1)
            self.assertEqual(cache.read_bytes(), retained)
            # Explicit uncertainty, wrong words, occurrence identity and stale bindings remain blocking.
            for key, value in (('decision', 'uncertain'), ('heardText', 'wrong'),
                               ('sourcePhysicalIndex', 1), ('endMs', 1001)):
                invalid = copy.deepcopy(review)
                invalid['spans'][0][key] = value
                atomic_write_json(review_path, invalid)
                with self.subTest(key=key), self.assertRaises(LyricsContentBlocker):
                    self.compare()
            atomic_write_json(review_path, review)
            for transcript in (omitted.replace('seven', 'wrong'), omitted + ' one two three', omitted * 2):
                with self.subTest(transcript=transcript), self.assertRaises(LyricsContentBlocker):
                    require_recording_adjudication(review, video_id='AAAAAAAAAAA',
                        original={'text': self.words, 'language': 'en'}, indices=[0, 1], transcript=transcript,
                        audio_sha=self.audio_sha, duration_ms=1000, draft_sha=draft_sha,
                        metadata_sha=metadata_sha, evidence_path=cache, evidence_root=cache.parent)
            self.assertEqual(len(calls), 1)
            self.assertEqual(cache.read_bytes(), retained)
            cache.unlink()
            from stemwise_engine.workflow_records import ContentEvidenceUnavailable
            with self.assertRaises(ContentEvidenceUnavailable):
                self.compare()
            self.assertEqual(len(calls), 1)
            self.assertEqual(workflow._recording_evidence_inputs(blocked), {'status': 'unavailable'})

    def test_cli_content_block_is_body_free_nonzero_and_exact_mix_remains_successful(self):
        before = self.snapshot()
        for text, expected in (('PRIVATE uncertain words', 1), (self.words, 0)):
            output = io.StringIO()
            with patch.object(evidence, 'load_config', return_value=self.config), patch.object(
                    evidence, '_load_or_run_qwen3_structure_transcript', return_value=(text, {}, True)), redirect_stdout(output):
                code = evidence.main(['config.json', str(self.root), 'AAAAAAAAAAA', str(self.audio), self.audio_sha])
            self.assertEqual(code, expected)
            result = json.loads(output.getvalue())
            if expected:
                self.assertEqual(result['status'], 'lyrics-content-unresolved')
                self.assertNotIn('structureSupport', result)
                self.assertNotIn('PRIVATE', output.getvalue())
            else:
                self.assertTrue(result['structureSupport']['accepted'])
            self.assertEqual(self.snapshot(), before)


if __name__ == '__main__':
    unittest.main()
