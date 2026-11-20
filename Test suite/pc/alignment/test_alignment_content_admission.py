"""Real coordinator admission; expensive recognizers are replaced at their output boundary."""
import hashlib
import io
import json
import sys
import tempfile
import unittest
from contextlib import ExitStack, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Test suite/pc/unit'))
import stemwise_engine.worker_protocol as worker_protocol
import stemwise_engine.alignment_endpoints as endpoints
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.alignment_audio as alignment_audio
import stemwise_engine.lyrics_align_pc as worker
import stemwise_engine.recording_evidence as recording_evidence
from lyric_content_fixtures import write_recognition, retained_content
from stemwise_engine.workflow_records import LyricsContentBlocker


class AlignmentContentAdmissionTests(unittest.TestCase):
    def test_already_synced_noop_preserves_committed_content_and_endpoint_state(self):
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult, build_synced_sidecar
        from stemwise_engine.lyric_content import require_workspace_content
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            video = 'abcdefghijk'
            original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                'source': {'name': 'Owner'}, 'text': 'one two three'}
            lyrics = {'status': 'partial', 'reason': 'Timing pending', 'tracks': [original]}
            raw = json.dumps(lyrics).encode('utf-8')
            workspace = worker.AlignmentWorkspace(root/'agent', workspace_root=root)
            _, canonical = worker.snapshot_canonical_plain(workspace, video, raw)
            admission, evidence = retained_content(workspace.job_dir(video), original, (0,), canonical)
            aligned = AlignmentResult(True, (), (100,), (0,), AlignmentMetrics(3, 3, 3, 1, 1, 1., 1., 1., 100., .8))
            synced = build_synced_sidecar(lyrics, aligned, 1000, line_timings=[{'startMs': 100, 'endMs': 900}])
            self.assertEqual(synced['status'], 'complete')
            sidecars = root/'owner'
            sidecars.mkdir()
            sidecar = sidecars/(video+'.lyrics.json')
            worker.atomic_write_json(sidecar, synced)
            workspace.write_state(video, {'videoId': video, 'position': 1, 'status': 'line-verified',
                'canonicalPlainSha256': canonical, 'contentAdmission': admission.to_record(),
                'contentBinding': admission.binding,
                'endpointEvidence': {'resultClass': 'line-verified', 'reasons': []}})
            before = {path: path.read_bytes() for path in (workspace.state_path(video), evidence,
                workspace.current_backup_path(video), workspace.backup_pointer_path(video), sidecar)}
            require_workspace_content(workspace, video, (0,), original=original, audio_sha='a'*64)
            args = SimpleNamespace(from_position=None, to_position=None, device='cpu', compute_type='float32',
                model='large-v3', realign_synced=False)
            with patch.dict(sys.modules, {'ctranslate2': SimpleNamespace(get_supported_compute_types=lambda _: {'float32'})}), \
                    patch.object(worker, '_verify_alignment_runtime', side_effect=AssertionError('no-op loaded runtime')), \
                    redirect_stdout(io.StringIO()):
                report = worker._align({'projectDir': str(root/'builder'), 'tempDir': str(root/'temp')}, args,
                    selected_override=[{'position': 1, 'videoId': video}],
                    paths_override=(sidecars, root/'runtime', root/'models', workspace))
            self.assertEqual(report['counts']['alreadySynced'], 1)
            self.assertEqual({path: path.read_bytes() for path in before}, before)
            require_workspace_content(workspace, video, (0,), original=original, audio_sha='a'*64)

    def test_recognition_phase_admission_recovery_uses_exact_retained_evidence(self):
        from stemwise_engine.alignment_attempt import RecognitionAdmission, RecognitionAdmissionRequest
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root/'agent', workspace_root=root)
            original = {'kind': 'original', 'language': 'en', 'script': 'Latn', 'text': 'one two three'}
            request = RecognitionAdmissionRequest('abcdefghijk', json.dumps(original).encode(),
                (0,), 'c'*64, 'a'*64)
            state = {'videoId': request.video_id}
            phase = RecognitionAdmission(request, workspace, state)
            path = workspace.job_dir(request.video_id)/'native-asr-fixture-mix.private.json'
            self.assertFalse(phase.cached(path))
            write_recognition(path.parent, original, (0,), recognized='one wrong three')
            with self.assertRaises(LyricsContentBlocker):
                phase.admit(path)
            self.assertNotIn('contentAdmission', state)
            write_recognition(path.parent, original, (0,))
            self.assertTrue(phase.cached(path))
            persisted = json.loads(workspace.state_path(request.video_id).read_bytes())
            self.assertEqual(persisted['contentAdmission'], state['contentAdmission'])
            self.assertNotIn('contentDispute', persisted)


    def test_recovery_discrepancy_positions_are_not_physical_source_indices(self):
        from stemwise_engine.alignment_attempt import RecognitionAdmission, RecognitionAdmissionRequest, ContentDispute
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.workflow_diagnostics import _alignment_diagnostics
        for indices, recognized in (((2, 0), 'two wrong'), ((2, 0, 2), 'two wrong two')):
            with self.subTest(indices=indices), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                workspace = AlignmentWorkspace(Path(name)/'agent', workspace_root=Path(name))
                original = {'kind': 'original', 'language': 'en', 'script': 'Latn', 'text': 'one\n\ntwo'}
                request = RecognitionAdmissionRequest('abcdefghijk', json.dumps(original).encode(), indices, 'c'*64, 'a'*64)
                state = {}
                phase = RecognitionAdmission(request, workspace, state)
                path = write_recognition(workspace.job_dir(request.video_id), original, indices, recognized=recognized)
                with self.assertRaises(LyricsContentBlocker):
                    phase.admit(path)
                dispute = ContentDispute.from_record(state['contentDispute'], performed_count=len(indices))
                self.assertEqual(dispute.scope['possiblePerformedIndices'], [1])
                invalid = dispute.to_record()
                invalid['scope']['possiblePerformedIndices'] = [len(indices)]
                with self.assertRaises(ValueError):
                    ContentDispute.from_record(invalid, performed_count=len(indices))
                write_recognition(path.parent, original, indices)
                admission = phase.admit(path)
                self.assertEqual(_alignment_diagnostics(state)['contentAdmission'], admission.to_record())
                self.assertNotIn('contentDispute', state)

    def run_coordinator(self, recognized, *, lexical_result=None, prior_blocked=False, lyrics_value=None,
                        endpoint_boundary=False, runtime_forbidden=False, caption_words=None, qwen_failure=None,
                        original_fields=None, native_failure=None, retained_cache=None, observations=None,
                        diagnostic_causes=(), fallback_recognition=False, caption_expired=False,
                        reviewed_difference=None, reviewed_qwen=None, review_reference_name=None,
                        structure_route_failure=False, spoken_review=None, retry_spoken_review=None):
        # This simulated recognizer has no installed numerical stack to fingerprint.
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name, patch.object(
                worker, '_whisper_recognition_cache_identity', return_value={
                    'recognitionCacheVersion': 1, 'producer': 'known-answer-whisper-fixture'}):
            root = Path(name)
            if qwen_failure is not None or caption_words is not None or reviewed_qwen is not None:
                sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
                from process_fixture import prepare_runtime_layout
                prepare_runtime_layout(root/'builder')
            video = 'abcdefghijk'
            original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                        'language': 'en', 'script': 'Latn', 'source': {'name': 'Owner'},
                        'text': 'one two three four\nfive six seven eight'}
            original.update(original_fields or {})
            raw = json.dumps(lyrics_value or {'status': 'partial', 'reason': 'Timing pending', 'tracks': [original]}).encode()
            sidecars = root / 'owner'
            sidecars.mkdir()
            sidecar = sidecars / (video + '.lyrics.json')
            sidecar.write_bytes(raw)
            workspace = worker.AlignmentWorkspace(root / 'agent', workspace_root=root)
            if spoken_review is not None:
                spoken_review(workspace.root.parent/'jobs'/video, original)
            prior_raw = None
            if prior_blocked:
                workspace.write_state(video, {'videoId': video, 'status': 'lyrics-content-unresolved',
                    'reasons': ['content-sequence-uncertain'], 'canonicalPlainSha256': hashlib.sha256(raw).hexdigest(),
                    'contentBinding': {'canonicalPlainSha256': hashlib.sha256(raw).hexdigest(),
                        'audioSha256': 'a'*64, 'sourcePhysicalIndices': [0, 1]}})
                prior_raw = workspace.state_path(video).read_bytes()
            audio = root / 'temp' / video / 'analysis.wav'
            audio.parent.mkdir(parents=True)
            audio.write_bytes(b'known PCM boundary')
            produced, native_calls = [], []
            retained_path = retained_raw = None
            retained_family = retained_cache[0] if retained_cache else None
            def write_whisper(text):
                from stemwise_engine.lyrics_alignment import transcript_to_json
                # Substitute only SyncAlong's external DTO; exercise the real cache serializer.
                values = [dict(raw=word, word=word, start=i*.2, end=i*.2+.1)
                          for i, word in enumerate(text.split())]
                words = [SimpleNamespace(**value, to_dict=lambda value=value: dict(value))
                         for value in values]
                path = workspace.transcript_path(video, 'en', 'unprompted-mix')
                path.parent.mkdir(parents=True, exist_ok=True)
                payload = transcript_to_json(video, words, model='large-v3+unprompted', language='en',
                    prompt_sha256=hashlib.sha256(b'').hexdigest(), evidence={},
                    provenance={'decoder': {'promptMode': 'withheld'}, 'audio': {'sha256': 'a'*64},
                                'durationMs': 5000}, cache_identity=worker._whisper_recognition_cache_identity())
                worker.atomic_write_json(path, payload)
                return path, words
            if retained_cache:
                family, corruption = retained_cache
                if family == 'whisper':
                    retained_path, _ = write_whisper(original['text'])
                else:
                    source = write_recognition(workspace.job_dir(video), original, (0, 1), adapter=worker.mms_asr.ADAPTER)
                    retained_path = source.with_name(f'native-asr-{worker.mms_asr.ADAPTER}-mix.private.json')
                    source.replace(retained_path)
                if corruption == 'truncated':
                    retained_path.write_bytes(b'{truncated retained recognition')
                elif corruption == 'stale':
                    value = json.loads(retained_path.read_bytes())
                    if family == 'whisper':
                        value['cacheIdentity'] = {'obsolete': True}
                    else:
                        value['provenance']['helpers']['mms_asr.py'] = '0'*64
                    retained_path.write_text(json.dumps(value), encoding='utf-8')
                elif corruption in {'wrong-word', 'repeated-extra'}:
                    value = json.loads(retained_path.read_bytes())
                    if corruption == 'wrong-word':
                        value['workerOutput']['words'][1]['raw'] = 'wrong'
                    else:
                        # A valid recognizer's extra performed words remain contrary evidence.
                        value['workerOutput']['durationMs'] += 200
                        value['provenance']['durationMs'] += 200
                        end = value['workerOutput']['words'][-1]['end']
                        value['workerOutput']['words'].append({'raw': 'one', 'start': end+.01, 'end': end+.1})
                    retained_path.write_text(json.dumps(value), encoding='utf-8')
                retained_raw = retained_path.read_bytes()
            def recognize(**request):
                native_calls.append(request['adapter'])
                if retained_path is not None:
                    self.assertEqual(retained_path.read_bytes(), retained_raw,
                                     'Keep prior evidence until the replacement producer publishes')
                if native_failure is not None:
                    raise native_failure
                path = write_recognition(request['job_dir'], original, (0, 1), recognized=recognized, adapter=request['adapter'])
                expected = request['job_dir'] / f"native-asr-{request['adapter']}-mix.private.json"
                path.replace(expected)
                produced.append((expected, expected.read_bytes()))
                return [SimpleNamespace(raw=word, word=word, start=i*.2, end=i*.2+.1)
                        for i, word in enumerate(recognized.split())], {}, True
            def recognize_whisper(**request):
                if retained_path is not None:
                    self.assertEqual(retained_path.read_bytes(), retained_raw)
                if observations is not None:
                    observations['loaderCalls'] = observations.get('loaderCalls', 0) + 1
                path, words = write_whisper(recognized)
                produced.append((path, path.read_bytes()))
                return words, {}, False
            actual_qwen = worker._load_or_run_qwen3_structure_transcript
            qwen_runtime = {'supportedLanguages': ['en']}
            mix_identity = {'sha256': 'a'*64, 'byteCount': audio.stat().st_size,
                'stream': {'sampleRate': 16000, 'channelCount': 2, 'frameCount': 80000, 'durationMs': 5000}}
            if reviewed_difference is not None or reviewed_qwen is not None:
                import stemwise_engine.lyric_content as lyric_content
                from stemwise_engine.lyrics_intake import read_recording_draft
                review_job = workspace.root.parent / 'jobs' / video
                drafts = review_job / 'drafts'
                drafts.mkdir(parents=True)
                (drafts / 'original.txt').write_text(original['text'] + '\n', encoding='utf-8')
                (drafts / 'lyrics.meta.json').write_text(json.dumps({'originalLanguage': 'en'}), encoding='utf-8')
                _, draft_sha, _, metadata_sha = read_recording_draft(review_job)
                if reviewed_qwen is None:
                    path = write_recognition(workspace.job_dir(video), original, (0, 1),
                        recognized=recognized, adapter=worker.mms_asr.ADAPTER)
                    expected = path.with_name(f'native-asr-{worker.mms_asr.ADAPTER}-mix.private.json')
                    path.replace(expected)
                else:
                    from test_qwen3_asr_transcribe import output_fixture, generation_fixture
                    project = root / 'builder'
                    (project / '.venv/bin').mkdir(parents=True)
                    (project / '.venv/bin/python').touch()
                    (project / 'src').mkdir()
                    (project / 'src/qwen3_asr_transcribe.py').write_bytes(
                        Path(worker.__file__).with_name('qwen3_asr_transcribe.py').read_bytes())
                    def qwen_process(argv, **options):
                        request = json.loads(Path(argv[argv.index('--request') + 1]).read_bytes())
                        output = output_fixture(request)
                        output['result'] = generation_fixture(reviewed_qwen, request['language'])
                        qwen_runtime['runtime'] = output['runtime']
                        Path(argv[argv.index('--output') + 1]).write_text(json.dumps(output), encoding='utf-8')
                        return SimpleNamespace(returncode=0)
                    with patch.object(worker, '_qwen3_asr_snapshot', return_value=root), \
                            patch.object(alignment_audio, 'file_identity', return_value=mix_identity), \
                            patch.object(alignment_models, 'roformer_offline_env', return_value={}), \
                            patch.object(worker, 'run_process_tree', side_effect=qwen_process):
                        # The real producer validates and publishes the original recognizer output.
                        actual_qwen(config={'projectDir': str(project)}, video_id=video, language='en',
                            audio=audio, audio_identity=mix_identity, model_cache=root / 'models',
                            workspace=workspace, temp_dir=root / 'qwen-producer', device='cpu',
                            timeout_seconds=60, runtime_report=qwen_runtime, separate_language_cache=True)
                    expected = workspace.job_dir(video) / 'qwen-structure-en.private.json'
                    produced.append((expected, expected.read_bytes()))
                review = {'formatVersion': 2, 'videoId': video, 'audioSha256': 'a'*64,
                    'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                    'recognition': {'path': review_reference_name or expected.name,
                        'sha256': hashlib.sha256(expected.read_bytes()).hexdigest()},
                    'reviewer': 'Known-answer independent operator', 'completedAt': '2026-10-24T00:00:00Z',
                    'spans': [{'performedIndex': 0, 'sourcePhysicalIndex': 0, 'startWordIndex': 1,
                        'endWordIndex': 2, 'recognizedStartWordIndex': 1, 'recognizedEndWordIndex': 1,
                        'startMs': 100, 'endMs': 200, 'heardText': 'two',
                        'decision': 'confirmed-asr-omission', 'rationale': 'Exact known recording content.',
                        **(reviewed_difference or {})}]}
                worker.atomic_write_json(review_job / lyric_content.ADJUDICATION_FILE, review)
                if prior_blocked:
                    prior = json.loads(prior_raw)
                    from stemwise_engine.alignment_attempt import RecognitionAdmission, RecognitionAdmissionRequest
                    phase_state = {}
                    phase = RecognitionAdmission(RecognitionAdmissionRequest(video, json.dumps(original).encode(),
                        (0, 1), hashlib.sha256(raw).hexdigest(), 'a'*64), workspace, phase_state)
                    review_path = review_job / lyric_content.ADJUDICATION_FILE
                    retained_review = review_path.read_bytes()
                    review_path.unlink()
                    try:
                        with self.assertRaises(LyricsContentBlocker):
                            phase.admit(expected)
                    finally:
                        review_path.write_bytes(retained_review)
                    prior['contentDispute'] = phase_state['contentDispute']
                    workspace.write_state(video, prior)
            args = SimpleNamespace(from_position=None, to_position=None, model='large-v3', device='cpu',
                compute_type='float32', expected_mix_sha256='a'*64, analysis_audio=None,
                editorial_sha256=None, rerun_transcript=retained_cache is not None, no_commit=False, realign_synced=False,
                qwen_structure_witness=qwen_failure is not None or caption_words is not None or reviewed_qwen is not None)
            expensive = {name: Mock(side_effect=AssertionError('content cannot reach recovery')) for name in (
                '_load_or_run_mms_alignment', '_load_final_mms_alignment', '_load_or_run_wav2vec2_alignment',
                '_load_or_transcribe', '_run_roformer_separation', 'separate_vocals_demucs')}
            verifier_candidates = Mock(side_effect=worker_protocol.WorkerProcessError(
                'known candidate boundary', 'fixture-verifier-candidate-boundary'))
            if endpoint_boundary:
                expensive['_load_or_run_wav2vec2_alignment'] = verifier_candidates
            if native_failure is not None:
                expensive['_load_or_transcribe'] = Mock(side_effect=RuntimeError('injected fallback recognition boundary'))
                expensive['_load_or_transcribe_roformer'] = Mock(side_effect=RuntimeError('injected recovery recognition boundary'))
            if retained_family == 'whisper' or fallback_recognition:
                expensive['_load_or_transcribe'] = Mock(side_effect=recognize_whisper)
            caption = None
            if caption_words is not None and caption_words != 'absent':
                caption = ([SimpleNamespace(raw=word, word=word, start=i*.2, end=i*.2+.1)
                            for i,word in enumerate(caption_words.split())], {'captionKind': 'automatic'}, True)
            qwen = Mock(side_effect=qwen_failure) if qwen_failure else Mock(return_value=(
                original['text'].replace('\n', ' '), {'language': 'en', 'modelRevision': 'fixture',
                'modelSnapshotSha256': 'f'*64, 'helperSha256': 'e'*64, 'runtime': {}, 'decoder': {}}, True))
            if reviewed_qwen is not None:
                qwen = Mock(side_effect=lambda **arguments: actual_qwen(**arguments, cache_only=True))
            runtime = Mock(side_effect=AssertionError('inadmissible input must not initialize runtime')) if runtime_forbidden else Mock(return_value={})
            capabilities = Mock(return_value={'float32'})
            caption_loader = worker._load_caption
            with ExitStack() as stack:
                if structure_route_failure:
                    stack.enter_context(patch.object(worker, '_needs_additional_structural_evidence', return_value=True))
                # The coordinator uses the real resolver; CPU CI substitutes only
                # the unavailable CTranslate2 hardware-capability boundary.
                stack.enter_context(patch.dict(sys.modules, {'ctranslate2': SimpleNamespace(
                    get_supported_compute_types=capabilities)}))
                stack.enter_context(patch.multiple(worker,
                    _verify_alignment_runtime=runtime,
                    _verify_pinned_stack=Mock(return_value={}),
                    _load_reviewed_lrc_starts=Mock(return_value={}),
                    internal_repeat_structure_kind=Mock(return_value='none'),
                    _load_caption=Mock(side_effect=caption_loader) if caption_expired else Mock(return_value=caption),
                    _qwen3_asr_runtime_provenance=Mock(return_value=qwen_runtime),
                    _load_or_run_qwen3_structure_transcript=qwen,
                    validate_child_zip=Mock(return_value={'analysis': {'durationMs': 5000}}),
                    **expensive))
                if caption_expired:
                    from stemwise_engine.provider_operations import ProviderStageDeadline
                    stack.enter_context(patch.object(worker, '_current_cache_identity', return_value={}))
                    stack.enter_context(patch.object(worker, 'download_youtube_caption_evidence',
                        side_effect=ProviderStageDeadline('provider-stage-deadline')))
                stack.enter_context(patch.multiple(alignment_audio,
                    write_audio_provenance=Mock(return_value={'mix': mix_identity, 'audioViews': {}}),
                    read_audio_provenance=Mock(return_value=None)))
                if reviewed_qwen is not None:
                    stack.enter_context(patch.object(worker, '_qwen3_asr_snapshot', return_value=root))
                    stack.enter_context(patch.object(worker, 'run_process_tree',
                        side_effect=AssertionError('Reviewed Qwen must reuse its retained producer output')))
                if retained_family == 'whisper':
                    stack.enter_context(patch.object(worker.mms_asr, 'adapters_for', return_value=()))
                elif original_fields is None or spoken_review is not None or retry_spoken_review is not None:
                    stack.enter_context(patch.object(worker.mms_asr, 'adapters_for', return_value=(worker.mms_asr.ADAPTER,)))
                stack.enter_context(patch.object(worker.mms_asr, 'runtime_provenance', return_value={}))
                stack.enter_context(patch.object(worker.mms_asr, 'load_or_transcribe', side_effect=recognize))
                stack.enter_context(patch.object(alignment_models, 'roformer_offline_env', return_value={}))
                if lexical_result is not None:
                    def align_known(text, sources, words, duration):
                        self.assertEqual(text, 'in twenty twenty six one two\nthree four five six'
                            if spoken_review is not None or retry_spoken_review is not None else original['text'])
                        self.assertEqual(tuple(sources), (0, 1))
                        self.assertEqual(' '.join(word.raw for word in words), recognized)
                        self.assertEqual(duration, 5000)
                        return lexical_result
                    stack.enter_context(patch.object(worker, 'align_known_structure', side_effect=align_known))
                if retained_cache is not None or fallback_recognition:
                    stack.enter_context(patch.object(worker, 'align_known_structure',
                        side_effect=RuntimeError('known post-content timing boundary')))
                if endpoint_boundary:
                    stack.enter_context(patch.object(worker, 'whisper_word_candidates', return_value=(None, None)))
                    stack.enter_context(patch.object(worker, '_phonemizer_languages', return_value=frozenset({'en', 'en-us'})))
                    stack.enter_context(patch.object(worker, '_wav2vec2_model_assets', return_value=(
                        root / 'external-model', {'repository': 'known-answer-verifier'})))
                    publication = stack.enter_context(patch.object(worker.AlignmentPublisher, 'publish'))
                    if caption_words is not None and caption_words != 'absent':
                        def optional_caption_candidates(text, sources, transcripts, duration, **kwargs):
                            self.assertEqual(text, original['text'])
                            self.assertEqual(tuple(sources), (0, 1))
                            self.assertEqual([' '.join(word.raw for word in words) for words in transcripts], [caption_words])
                            return (None, None)  # Optional timing evidence abstains; required CTC still runs.
                        stack.enter_context(patch('stemwise_engine.lyrics_alignment.whisper_word_candidates',
                            side_effect=optional_caption_candidates))
                if retry_spoken_review is not None:
                    first = worker._align({'projectDir': str(root/'builder'), 'tempDir': str(root/'temp'),
                        'analysisTimeoutMinutes': 1}, args, selected_override=[{'position': 1, 'videoId': video}],
                        paths_override=(sidecars, root/'runtime', root/'models', workspace),
                        temp_root_override=root/'attempt')
                    stopped = json.loads(workspace.state_path(video).read_bytes())
                    self.assertEqual(stopped['reasons'], ['pronunciation-support-unavailable'])
                    self.assertEqual(stopped['contentBinding']['audioSha256'], 'a'*64)
                    self.assertEqual(first['counts']['blocked'], 1)
                    retry_spoken_review(workspace.root.parent/'jobs'/video, original)
                    with patch.object(worker, '_align', return_value={'recognitionAdmissionReached': True}) as future:
                        result = worker._run_future_alignment_attempt({'analysisTimeoutMinutes': 1}, SimpleNamespace(model='fixture',
                            canonical_plain_sha256=stopped['contentBinding']['canonicalPlainSha256'],
                            expected_mix_sha256='a'*64), video_id=video, attempt_id='b'*32,
                            workspace_root=root, generated_root=root/'generated', future_workspace=workspace,
                            future_paths=(sidecars, root/'runtime', root/'models', workspace))
                        self.assertEqual(result, {'recognitionAdmissionReached': True})
                        future.assert_called_once()
                report = worker._align({'projectDir': str(root/'builder'), 'tempDir': str(root/'temp'),
                    'analysisTimeoutMinutes': 1}, args, selected_override=[{'position': 1, 'videoId': video}],
                    paths_override=(sidecars, root/'runtime', root/'models', workspace),
                    temp_root_override=root/'attempt')
            self.assertEqual(capabilities.call_count, 2 if retry_spoken_review is not None else 1)
            capabilities.assert_called_with('cpu')
            state = json.loads(workspace.state_path(video).read_bytes())
            for cause in diagnostic_causes:
                references = [reference for event in state.get('routeAttempts', ())
                              for reference in event.get('privateDiagnostics', ())]
                matching = []
                for reference in references:
                    body = (workspace.job_dir(video) / reference['file']).read_bytes()
                    self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
                    if cause.encode() in body:
                        context = json.loads(body.splitlines()[0])
                        if context['route'].startswith('mms-asr:'):
                            self.assertEqual(context['model'], worker.mms_asr.MODEL)
                        matching.append(reference)
                self.assertTrue(matching, f'No retained route reference resolves cause {cause!r}')
                self.assertNotIn(cause, json.dumps(state))
            if observations is not None:
                observations.update(nativeCalls=len(native_calls), old=retained_raw,
                    current=retained_path.read_bytes() if retained_path is not None else None)
            if runtime_forbidden:
                runtime.assert_not_called()
            if prior_raw is not None and reviewed_difference is None:
                self.assertEqual(workspace.state_path(video).read_bytes(), prior_raw)
                self.assertEqual(produced, [])
            self.assertEqual(sidecar.read_bytes(), raw)
            for path, evidence in produced:
                self.assertEqual(path.read_bytes(), evidence)
            for mock in expensive.values():
                if structure_route_failure and mock is expensive['_load_or_transcribe']:
                    self.assertGreater(mock.call_count, 0)
                elif retained_family == 'whisper' and mock is expensive['_load_or_transcribe']:
                    self.assertEqual(mock.call_count, 1)
                elif native_failure is not None and mock is expensive['_load_or_transcribe']:
                    self.assertGreater(mock.call_count, 0, state)
                elif endpoint_boundary and mock is expensive['_load_or_run_wav2vec2_alignment']:
                    failures = [workspace.job_dir(video).joinpath(reference['file']).read_text(encoding='utf-8')
                        for reference in state.get('privateDiagnostics', [])]
                    failures.extend(workspace.job_dir(video).joinpath(reference['file']).read_text(encoding='utf-8')
                        for reference in state.get('routeAttempts', [{}])[-1].get('privateDiagnostics', []))
                    self.assertEqual(mock.call_count, 0 if qwen_failure else 1, (state, failures))
                else:
                    mock.assert_not_called()
            if native_failure is not None:
                self.assertTrue(native_calls)
            if caption_words is not None or qwen_failure is not None or reviewed_qwen is not None:
                qwen.assert_called_once()
            if endpoint_boundary and qwen_failure is None:
                publication.assert_not_called()
                verifier_candidates.assert_called_once()
            return report, state

    def test_failed_structural_whisper_route_keeps_cause_without_undefined_guard(self):
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult
        accepted = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(8, 8, 8, 2, 2, 1., 1., 1., 100., .8))
        report, state = self.run_coordinator('one two three four five six seven eight',
            lexical_result=accepted, structure_route_failure=True)
        self.assertIn('unpromptedFallbackError', state)
        self.assertNotIn('NameError', json.dumps(state))
        self.assertEqual(report['counts']['failed'], 1)

    def test_mandatory_qwen_consumes_its_exact_reviewed_cache_before_independent_timing(self):
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult, StructureWitness, StructureWitnessMetrics
        exact = 'one two three four five six seven eight'
        omitted = 'one three four five six seven eight'
        accepted = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(8, 8, 8, 2, 2, 1., 1., 1., 100., .8))
        def known_structure(text, sources, transcript):
            self.assertEqual((text, tuple(sources), transcript),
                ('one two three four\nfive six seven eight', (0, 1), omitted))
            return StructureWitness('qwen3-asr', 'qwen3-asr-unprompted-mix', True, (), (0, 1),
                StructureWitnessMetrics(8, 7, 7, 2, 2, 2, 7/8, 1., 1., 1., 100.))
        with patch.object(worker, 'score_structure_witness', side_effect=known_structure) as score:
            report, state = self.run_coordinator(exact, lexical_result=accepted,
                endpoint_boundary=True, reviewed_qwen=omitted)
        self.assertEqual(report['counts']['failed'], 1, state)
        self.assertNotEqual(state['status'], 'lyrics-content-unresolved', state)
        self.assertEqual(state['structureWitness']['status'], 'success')
        self.assertTrue(state['structureWitness']['cacheReused'])
        self.assertIn('adjudication', state['structureWitness']['contentAdmission'])
        from stemwise_engine.workflow_reports import alignment_run_record, ImmutableReportStore
        record, evidence = alignment_run_record({}, run_id='a'*32, video_id='abcdefghijk',
            state=state, checkpoint_key={}, checkpoint_raw=b'{}', completed_at=1,
            status='failed', run_artifacts={}, failure_code='retryable-technical',
            producing_inputs=None, observed_input_changes={})
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            ImmutableReportStore(root, 65536).commit(root/'reports', record, evidence=evidence)
            self.assertEqual(evidence['diagnostics']['structureWitness']['contentAdmission'],
                state['structureWitness']['contentAdmission'])
        score.assert_called_once()

    def test_recording_reviewed_numeral_reaches_independent_timing_with_unchanged_display(self):
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult
        from stemwise_engine import spoken_targets
        def review(root, original):
            root.mkdir(parents=True)
            evidence = root/'review.txt'
            evidence.write_bytes(b'Known-answer listening review of the exact displayed year and every spoken word.')
            rows = [(0, 0, 2, 'in', 'in'), (0, 3, 7, '2026', 'twenty twenty six'),
                (0, 8, 11, 'one', 'one'), (0, 12, 15, 'two', 'two'),
                (1, 0, 5, 'three', 'three'), (1, 6, 10, 'four', 'four'),
                (1, 11, 15, 'five', 'five'), (1, 16, 19, 'six', 'six')]
            value = {'formatVersion': 1, 'binding': spoken_targets.binding('abcdefghijk', original, (0, 1), 'a'*64),
                'spans': [dict(sourcePhysicalIndex=source, startUtf16=start, endUtf16=end,
                    displayText=display, spokenText=spoken, language='en') for source, start, end, display, spoken in rows]}
            candidate = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
            value['review'] = {'candidateSha256': hashlib.sha256(candidate).hexdigest(),
                'reviewer': 'known-answer-listener', 'completedAt': '2026-11-01T00:00:00Z', 'decision': 'admit',
                'checks': dict.fromkeys(spoken_targets.CHECKS, 'reviewed'), 'rationale': 'The exact year is performed as reviewed.',
                'evidence': [{'path': evidence.name, 'sha256': hashlib.sha256(evidence.read_bytes()).hexdigest()}]}
            (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value), encoding='utf-8')
        accepted = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(10, 10, 10, 2, 2, 1., 1., 1., 100., .8))
        report, state = self.run_coordinator('in twenty twenty six one two three four five six',
            original_fields={'text': 'in 2026 one two\nthree four five six'}, lexical_result=accepted,
            endpoint_boundary=True, retry_spoken_review=review)
        self.assertNotEqual(state['status'], 'lyrics-content-unresolved', state)
        self.assertIn('spokenTargets', state['contentAdmission'])
        self.assertEqual(report['counts']['failed'], 1)

    def test_unrelated_review_never_regenerates_valid_contrary_recognition(self):
        observed = {}
        report, state = self.run_coordinator('one three four five six seven eight',
            reviewed_difference={}, review_reference_name='qwen-structure-en.private.json', observations=observed)
        self.assertEqual(report['counts']['blocked'], 1, state)
        self.assertEqual(state['reasons'], ['recording-adjudication-stale'])
        self.assertEqual(observed['nativeCalls'], 0)

    def test_reviewed_recognizer_differences_reach_independent_timing_without_changing_words(self):
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult
        accepted = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(8, 8, 8, 2, 2, 1., 1., 1., 100., .8))
        exact = 'one two three four five six seven eight'
        cases = (
            ('one three four five six seven eight', {}),
            ('one wrong three four five six seven eight', {
                'decision': 'confirmed-asr-substitution', 'recognizedEndWordIndex': 2}),
            ('one extra two three four five six seven eight', {
                'decision': 'confirmed-asr-insertion', 'endWordIndex': 1,
                'recognizedEndWordIndex': 2, 'heardText': ''}))
        for words, span in cases:
            with self.subTest(words=words):
                report, state = self.run_coordinator(words, reviewed_difference=span,
                    lexical_result=accepted, endpoint_boundary=True, prior_blocked=True)
                self.assertEqual(report['counts']['failed'], 1, state)
                self.assertNotEqual(state['status'], 'lyrics-content-unresolved', state)
                self.assertIn('adjudication', state['contentAdmission'])
        for invalid in ({'decision': 'uncertain'}, {'heardText': 'wrong'}, {'sourcePhysicalIndex': 1}):
            with self.subTest(invalid=invalid):
                report, state = self.run_coordinator(cases[0][0], reviewed_difference=invalid)
                self.assertEqual(report['counts']['blocked'], 1, state)
                self.assertNotIn('contentAdmission', state)


    def test_retry_regenerates_truncated_or_stale_native_recognition_without_editing_lyrics(self):
        for corruption in ('truncated', 'stale'):
            with self.subTest(corruption=corruption):
                observed = {}
                _, state = self.run_coordinator('one two three four five six seven eight',
                    retained_cache=('native', corruption), observations=observed)
                self.assertEqual(observed['nativeCalls'], 1)
                self.assertNotEqual(observed['current'], observed['old'])
                self.assertIn('contentAdmission', state)
        for contrary in ('wrong-word', 'repeated-extra'):
            with self.subTest(contrary=contrary):
                observed = {}
                _, state = self.run_coordinator('one two three four five six seven eight',
                    retained_cache=('native', contrary), observations=observed)
                self.assertEqual(observed['nativeCalls'], 0)
                self.assertEqual(observed['current'], observed['old'])
                self.assertEqual(state['status'], 'lyrics-content-unresolved')
                self.assertNotIn('contentAdmission', state)

    def test_retry_regenerates_truncated_or_stale_whisper_recognition_without_editing_lyrics(self):
        for corruption in ('truncated', 'stale'):
            with self.subTest(corruption=corruption):
                observed = {}
                _, state = self.run_coordinator('one two three four five six seven eight',
                    retained_cache=('whisper', corruption), observations=observed)
                self.assertEqual(observed['loaderCalls'], 1)
                self.assertNotEqual(observed['current'], observed['old'])
                self.assertIn('contentAdmission', state)
        _, state = self.run_coordinator('one two three four five six seven eight',
            native_failure=RuntimeError('native recognizer original cause'), fallback_recognition=True,
            diagnostic_causes=('native recognizer original cause',))
        self.assertIn('contentAdmission', state)
        self.assertTrue(any(event['routeId'].startswith('faster-whisper:') and event['status'] == 'success'
                            for event in state['routeAttempts']))

    def test_diagnostic_io_failure_keeps_real_recognition_fallback_and_terminal_report(self):
        from stemwise_engine import private_diagnostics
        with patch.object(private_diagnostics, 'retain', side_effect=OSError('diagnostic storage unavailable')):
            report, state = self.run_coordinator('one two three four five six seven eight',
                native_failure=worker_protocol.WorkerProcessError('native fixture', 'cuda-out-of-memory'),
                fallback_recognition=True)
        self.assertIn('contentAdmission', state)
        self.assertEqual(report['counts']['failed'], 1)
        self.assertTrue(any(event.get('code') == 'cuda-out-of-memory' for event in state['routeAttempts']))
        self.assertNotIn('privateDiagnostics', state)
        self.assertIn('retryable-technical', state['status'])

    def test_distinct_failed_recognition_routes_keep_resolvable_original_causes(self):
        self.run_coordinator('one two three four five six seven eight',
            native_failure=RuntimeError('native recognizer original cause'),
            diagnostic_causes=('native recognizer original cause', 'injected fallback recognition boundary'))

    def test_optional_caption_mismatch_cannot_veto_mandatory_qwen_and_endpoint_checks(self):
        from stemwise_engine.lyrics_alignment import (AlignmentMetrics, AlignmentResult,
                                      StructureWitness, StructureWitnessMetrics)
        accepted = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(8, 8, 8, 2, 2, 1., 1., 1., 100., .8))
        exact = 'one two three four five six seven eight'
        def known_witness(text, sources, transcript):
            # Syncalong scoring belongs to the dedicated lyrics runtime. This
            # CPU contract fixture admits only these independently fixed inputs;
            # the real content comparison runs before this numerical boundary.
            self.assertEqual(text, 'one two three four\nfive six seven eight')
            self.assertEqual(tuple(sources), (0, 1))
            self.assertEqual(transcript, exact)
            return StructureWitness('qwen3-asr', 'qwen3-asr-unprompted-mix', True, (), (0, 1),
                StructureWitnessMetrics(8, 8, 8, 2, 2, 2, 1., 1., 1., 1., 100.))
        for caption in ('absent', exact, 'one two wrong four five six seven eight'):
            with self.subTest(caption=caption), patch.object(
                    worker, 'score_structure_witness', side_effect=known_witness) as scorer:
                report, state = self.run_coordinator(exact, lexical_result=accepted,
                    endpoint_boundary=True, caption_words=caption)
                scorer.assert_called_once_with('one two three four\nfive six seven eight', (0, 1), exact)
                self.assertEqual(report['counts']['failed'], 1)
                self.assertEqual(state['structureWitness']['status'], 'success')
                self.assertTrue(any('fixture-verifier-candidate-boundary' in str(event)
                                    for event in state['routeAttempts']) or state['status'] == 'retryable-technical')
                rejected = [event for event in state['routeAttempts'] if event.get('code') == 'caption-content-mismatch']
                self.assertEqual(bool(rejected), 'wrong' in caption)
                self.assertEqual('youtube-caption:automatic' in state['attemptedEvidence'], caption == exact)

    def test_complete_and_unavailable_sidecars_do_not_initialize_alignment_stack(self):
        from test_song_enrichment_v2 import valid_document
        report, state = self.run_coordinator('', lyrics_value=valid_document()['lyrics'], runtime_forbidden=True)
        self.assertEqual(state['status'], 'already-synced')
        self.assertEqual(report['counts']['alreadySynced'], 1)
        report, state = self.run_coordinator('', lyrics_value={
            'status': 'unavailable', 'reason': 'No reviewed words', 'tracks': []}, runtime_forbidden=True)
        self.assertEqual(state['status'], 'lyrics-content-unresolved')
        self.assertEqual(report['counts']['blocked'], 1)

    def test_partial_and_wrong_content_stop_before_any_force_or_prediction(self):
        for words in ('one two three four five six seven', 'one two wrong four five six seven eight'):
            with self.subTest(words=words):
                report, state = self.run_coordinator(words)
                self.assertEqual(state['status'], 'lyrics-content-unresolved')
                self.assertEqual(report['counts']['blocked'], 1)
                self.assertEqual(state['reasons'], ['content-sequence-uncertain'])
                self.assertNotIn('contentAdmission', state)
                self.assertEqual(state['contentBinding']['sourcePhysicalIndices'], [0, 1])

    def test_accepted_aggregate_cannot_override_partial_content(self):
        result = SimpleNamespace(accepted=True)
        report, _ = self.run_coordinator('one two three', lexical_result=result)
        self.assertEqual(report['counts']['blocked'], 1)

    def test_unchanged_direct_align_preserves_known_content_stop_without_recognition(self):
        report, state = self.run_coordinator('one two three four five six seven eight', prior_blocked=True)
        self.assertEqual(report['counts']['blocked'], 1)
        self.assertEqual(state['reasons'], ['content-sequence-uncertain'])

    def test_unavailable_and_malformed_original_are_blocked_instead_of_successfully_skipped(self):
        for value in ({'status': 'unavailable', 'reason': 'No approved content', 'tracks': []},
                {'status': 'partial', 'reason': 'Unknown content', 'tracks': [{'kind': 'original', 'text': 42}]}):
            with self.subTest(value=value):
                report, state = self.run_coordinator('', lyrics_value=value)
                self.assertEqual(report['counts']['blocked'], 1)
                self.assertEqual(state['status'], 'lyrics-content-unresolved')

    def test_unknown_alignment_failure_blocks_despite_exact_recognition(self):
        report, state = self.run_coordinator('one two three four five six seven eight',
            lexical_result=SimpleNamespace(accepted=False, reasons=('ambiguous-pronunciation',)))
        self.assertEqual(report['counts']['blocked'], 1)
        self.assertEqual(state['reasons'], ['failure-not-confirmed-timing-only'])

    def test_numeral_pronunciation_blocks_before_runtime_or_recognition(self):
        for text in ('42', 'B52', '١٢', '24/7'):
            with self.subTest(text=text):
                report, state = self.run_coordinator(text, original_fields={'text': text}, runtime_forbidden=True)
                self.assertEqual(report['counts']['blocked'], 1, state)
                self.assertEqual(state['reasons'], ['pronunciation-support-unavailable'])

    def test_optional_caption_expiry_still_reaches_current_endpoint_boundary(self):
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult
        accepted = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(8, 8, 8, 2, 2, 1., 1., 1., 100., .8))
        for expired in (False, True):
            with self.subTest(expired=expired):
                report, state = self.run_coordinator('one two three four five six seven eight',
                    lexical_result=accepted, endpoint_boundary=True, caption_expired=expired)
                self.assertEqual(report['counts']['failed'], 1, state)
                self.assertNotEqual(state['status'], 'lyrics-content-unresolved')

    def test_qwen_mismatch_and_occurrence_ambiguity_never_try_another_route(self):
        for sequences, text in (({(0,)}, 'wrong content'), ({(0,), (1,)}, 'one two')):
            recognize = Mock(return_value=(text, {}, True))
            with self.subTest(sequences=sequences), self.assertRaises(LyricsContentBlocker):
                worker._score_qwen_structure_routes({'text': 'one two\none two'}, sequences,
                                                     ('en', 'auto'), recognize)
            recognize.assert_called_once_with('en')

    def test_cascade_does_not_consume_content_stop(self):
        evaluate = Mock(side_effect=LyricsContentBlocker('content-support-uncertain'))
        with self.assertRaises(LyricsContentBlocker):
            worker._run_conditional_cascade(('first', 'second'), evaluate)
        evaluate.assert_called_once_with('first')

    def test_failed_or_uncertain_required_witness_cannot_hide_behind_accepted_alignment(self):
        alignment = SimpleNamespace(accepted=True, reasons=())
        with self.assertRaises(worker_protocol.WorkerProcessError):
            worker._require_completed_structure_witness(alignment, {'status': 'failure', 'failureCode': 'fixture-failure'})
        for witness in ({'status': 'started'}, {'status': 'ambiguous'},
                {'status': 'success', 'selectedSequenceSupported': False},
                {'status': 'success', 'selectedSequenceSupported': True, 'selectedSequenceEveryLineDirect': False}):
            with self.subTest(witness=witness), self.assertRaises(LyricsContentBlocker):
                worker._require_completed_structure_witness(alignment, witness)
        worker._require_completed_structure_witness(alignment, {'status': 'success',
            'selectedSequenceSupported': True, 'selectedSequenceEveryLineDirect': True})


    def test_recording_evidence_child_transports_body_free_block(self):
        stdout = io.StringIO()
        with patch.object(recording_evidence, 'load_config', return_value={}), \
                patch.object(recording_evidence, 'compare', side_effect=LyricsContentBlocker('content-sequence-uncertain')), \
                redirect_stdout(stdout):
            code = recording_evidence.main(['config', 'root', 'abcdefghijk', 'audio', 'a'*64])
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(stdout.getvalue()), {'status': 'lyrics-content-unresolved',
                                                        'reasons': ['content-sequence-uncertain']})

    def test_recording_evidence_mismatch_stops_before_scoring_or_other_models(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            drafts = root/'jobs/abcdefghijk/drafts'
            drafts.mkdir(parents=True)
            owner = drafts/'original.txt'
            owner.write_text('one two three four', encoding='utf-8')
            (drafts/'lyrics.meta.json').write_text(json.dumps({'originalLanguage': 'en',
                'originalScript': 'Latn'}), encoding='utf-8')
            optional = root/'lyrics-alignment/jobs/abcdefghijk/qwen-structure-en.private.json'
            optional.parent.mkdir(parents=True)
            optional.write_bytes(b'{broken optional cache')
            with patch.object(recording_evidence, 'file_identity', return_value={'sha256': 'a'*64,
                    'stream': {'sampleRate': 16000, 'channelCount': 2, 'frameCount': 16000, 'durationMs': 1000}}), \
                    patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript',
                        return_value=('one wrong three four', {'language': 'en'}, True)) as recognize, \
                    patch.object(recording_evidence, 'score_structure_witness') as score:
                with self.assertRaises(LyricsContentBlocker) as raised:
                    recording_evidence.compare({'projectDir': str(root/'PC side/builder')}, root,
                                               'abcdefghijk', root/'audio.wav', 'a'*64)
                self.assertEqual(raised.exception.reasons, ('content-sequence-uncertain',))
            recognize.assert_called_once()
            score.assert_not_called()
            self.assertEqual(owner.read_text(encoding='utf-8'), 'one two three four')

    def endpoint_stage(self, root):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                    'language': 'en', 'text': 'one two three four'}
        raw = json.dumps({'tracks': [original]}).encode()
        workspace = worker.AlignmentWorkspace(root/'agent', workspace_root=root)
        video = 'abcdefghijk'
        _, canonical = worker.snapshot_canonical_plain(workspace, video, raw)
        admission, _ = retained_content(workspace.job_dir(video), original, (0,), canonical)
        state = {'canonicalPlainSha256': canonical, 'contentAdmission': admission.to_record(),
                 'contentBinding': admission.binding, 'pinnedModels': {}}
        workspace.write_state(video, state)
        workers = worker.EndpointWorkers(load_mms=Mock(return_value=({'lines': [{}]}, True)),
            load_wav2vec2=Mock(), load_owsm=Mock(), load_nfa=Mock(), read_audio_provenance=Mock(),
            verifier_candidates=Mock(), needs_local_verifier=Mock(return_value=True))
        stage = endpoints.EndpointAlignmentStage(request=worker.EndpointAlignmentRequest(
            context=worker.AlignmentContext(config={'analysisTimeoutMinutes': 1}, video_id=video,
                workspace=workspace, temp_root=root, device='cpu', duration_ms=1000, endpoint_duration_ms=1000),
            text=worker.EndpointText(original=original, alignment=SimpleNamespace(source_physical_indices=(0,)),
                lexical_mapping={}, sources=[0], source_occurrences=(0,), display_word_lines=[], acoustic_language_lines=None),
            evidence=worker.EndpointEvidence(baseline_segments=[], seeded_mms_segments=None,
                verifier_candidates=({},), verifier_family=endpoints.WAV2VEC2_FAMILY, verifier_reused=True,
                wav2vec2_language='en', free_family_candidates={}, independent_inputs={'en': ([0], {}, [])},
                reviewed_lrc_starts={}, route_names={'htdemucs-vocals': 'mms'}, content_admission=admission.to_record()),
            audio=worker.EndpointAudio(expected_mix_sha256='a'*64, analysis_audio=None,
                canonical_mix=Mock(), get_endpoint_audio=Mock())),
            services=worker.EndpointServices(workers=workers,
                events=worker.AlignmentEvents(attempt=Mock(), failure=Mock(), roformer=Mock()),
                progress=worker.AlignmentProgress(state=state, endpoint_attempts=[], checkpoint=Mock()),
                release_model=Mock(), runtime_probe_cache={}))
        return stage, workers

    def test_weak_timing_can_use_real_independent_consensus_only_for_current_exact_occurrences(self):
        from dataclasses import replace
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            stage, _ = self.endpoint_stage(Path(name))
            mapping = worker.build_lexical_occurrence_mapping('abcdefghijk', stage.request.text.original,
                                                              (0,))
            stage.request = replace(stage.request, text=replace(stage.request.text, lexical_mapping=mapping))
            occurrence = mapping['performedOccurrences'][0]
            line_id = occurrence['occurrenceId']
            forced = {'lines': [{'lineId': line_id, 'sourcePhysicalIndex': 0, 'startMs': 100, 'endMs': 900,
                'confidence': .01, 'words': []}]}
            witness = {'lineId': line_id, 'sourcePhysicalIndex': 0, 'startMs': 110, 'endMs': 890,
                       'route': endpoints.WAV2VEC2_ROUTE}
            baseline = endpoints._refine_public_timings(forced, (witness,), {},
                include_unresolved=True)
            self.assertEqual(baseline[1]['unresolvedLineReasons'], {'low-forced-line-support': 1})
            stage.require_timing_recovery(baseline)
            with patch.object(stage, 'load_independent_witness', return_value=(({**witness, 'route': worker.owsm_ctc.ROUTE},), True)):
                result, used, _ = stage.apply_independent_consensus(baseline)
            self.assertTrue(used)
            self.assertEqual(result[1]['resultClass'], 'committed-lines')
            self.assertEqual(result[0], [{'startMs': 110, 'endMs': 890}])
            baseline[1]['occurrenceCandidates'][0]['occurrenceId'] = 'another-occurrence'
            with self.assertRaises(LyricsContentBlocker):
                stage.require_timing_recovery(baseline)

    def test_weak_timing_bootstrap_keeps_current_occurrence_maps(self):
        from dataclasses import replace
        for missing_verifier in (False, True):
            with self.subTest(missing_verifier=missing_verifier), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                stage, _ = self.endpoint_stage(Path(name))
                mapping = worker.build_lexical_occurrence_mapping('abcdefghijk', stage.request.text.original, (0,))
                occurrence = mapping['performedOccurrences'][0]
                line = {'lineId': occurrence['occurrenceId'], 'sourcePhysicalIndex': 0,
                    'startMs': 100, 'endMs': 900, 'confidence': .9 if missing_verifier else .01, 'words': []}
                verifier = None if missing_verifier else {'lineId': line['lineId'], 'sourcePhysicalIndex': 0,
                    'startMs': 110, 'endMs': 890, 'route': endpoints.WAV2VEC2_ROUTE}
                stage.request = replace(stage.request, text=replace(stage.request.text, lexical_mapping=mapping),
                    evidence=replace(stage.request.evidence, verifier_candidates=(verifier,)))
                baseline = endpoints._refine_public_timings({'lines': [line]}, (verifier,), include_unresolved=True)
                windows = [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                            'windowStartMs': 0, 'windowEndMs': 1000}]
                with patch.object(stage, 'load_baseline_mms', return_value=({'lines': [line]}, True)), \
                        patch.object(stage, 'load_route_wav2vec2', return_value=((verifier,), True, windows)), \
                        patch.object(stage, 'apply_independent_consensus', side_effect=lambda result: (result, False, True)), \
                        patch.object(endpoints, '_build_bootstrap_segments', return_value=windows), \
                        patch.object(stage, 'repair_anchored', return_value=(None, windows, True)) as repair:
                    stage.evaluate_endpoint_route_pass('htdemucs-vocals')
                repair.assert_called_once()
                self.assertEqual(baseline[1]['occurrenceCandidates'][0]['occurrenceId'], occurrence['occurrenceId'])

    def test_unknown_forced_support_blocks_before_consensus_bootstrap_or_next_route(self):
        for reason in ('low-forced-line-support', 'unsafe-occurrence-identity', 'missing-word-support', 'unknown'):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                stage, workers = self.endpoint_stage(Path(name))
                summary = {'resultClass': 'line-unresolved', 'occurrenceCandidates': [
                    {'publishDecision': {'result': 'unresolved', 'code': reason}}]}
                with patch.object(endpoints, '_refine_public_timings', return_value=([], summary)), \
                        patch.object(endpoints, '_build_bootstrap_segments') as predict, \
                        patch.object(stage, 'apply_independent_consensus') as consensus:
                    with self.assertRaises(LyricsContentBlocker):
                        worker._run_conditional_cascade(('htdemucs-vocals', 'kimberley-jensen-roformer-vocals'),
                                                        stage.evaluate_endpoint_route)
                workers.load_mms.assert_called_once()
                workers.load_wav2vec2.assert_not_called()
                workers.load_owsm.assert_not_called()
                workers.needs_local_verifier.assert_not_called()
                predict.assert_not_called()
                consensus.assert_not_called()

    def test_confirmed_content_with_boundary_failure_can_reach_timing_consensus(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            stage, _ = self.endpoint_stage(Path(name))
            baseline = ([], {'resultClass': 'line-unresolved', 'occurrenceCandidates': [
                {'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'}}]})
            with patch.object(stage, 'load_independent_witness', return_value=(({},), True)) as load, \
                    patch.object(endpoints, '_apply_independent_endpoint_consensus',
                        return_value=([], {'resultClass': 'committed-lines'})):
                result, used, reused = stage.apply_independent_consensus(baseline)
            load.assert_called_once_with(endpoints.OWSM_CTC_FAMILY)
            self.assertTrue(used and reused)
            self.assertEqual(result[1]['resultClass'], 'committed-lines')

    def test_technical_evidence_retry_reaches_fresh_attempt_without_editing_approved_text(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            workspace = worker.AlignmentWorkspace(root / 'agent', workspace_root=root)
            prior = {'status': 'lyrics-content-unresolved', 'reasons': ['content-evidence-unavailable'],
                'contentBinding': {'canonicalPlainSha256': 'c'*64, 'audioSha256': 'a'*64,
                                   'sourcePhysicalIndices': [0]}}
            workspace.write_state('abcdefghijk', prior)
            evidence = workspace.job_dir('abcdefghijk') / 'recognition.private.json'
            evidence.write_bytes(b'{damaged retained evidence')
            with patch.object(worker, '_align', return_value={'freshAttempt': True}) as align:
                result = worker._run_future_alignment_attempt({'analysisTimeoutMinutes': 1}, SimpleNamespace(model='fixture',
                    canonical_plain_sha256='c'*64, expected_mix_sha256='a'*64, rerun_transcript=True),
                    video_id='abcdefghijk', attempt_id='b'*32, workspace_root=root,
                    generated_root=root / 'generated', future_workspace=workspace,
                    future_paths=(root, root, root, workspace))
            self.assertEqual(result, {'freshAttempt': True})
            self.assertTrue(align.call_args.args[1].rerun_transcript)
            self.assertEqual(evidence.read_bytes(), b'{damaged retained evidence')

    def test_unchanged_future_attempt_blocker_survives_before_any_state_or_scratch_write(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            workspace = worker.AlignmentWorkspace(root/'agent', workspace_root=root)
            prior = {'status': 'lyrics-content-unresolved', 'reasons': ['content-sequence-uncertain'],
                'contentBinding': {'canonicalPlainSha256': 'c'*64, 'audioSha256': 'a'*64,
                                   'sourcePhysicalIndices': [0]}, 'retainedNegativeWitness': 'kept'}
            workspace.write_state('abcdefghijk', prior)
            raw = workspace.state_path('abcdefghijk').read_bytes()
            with patch.object(worker, '_align') as align, self.assertRaises(LyricsContentBlocker):
                worker._run_future_alignment_attempt({}, SimpleNamespace(model='fixture',
                    canonical_plain_sha256='c'*64, expected_mix_sha256='a'*64), video_id='abcdefghijk',
                    attempt_id='b'*32, workspace_root=root, generated_root=root/'generated',
                    future_workspace=workspace, future_paths=(root, root, root, workspace))
            align.assert_not_called()
            self.assertEqual(workspace.state_path('abcdefghijk').read_bytes(), raw)
            self.assertFalse((root/'generated').exists())

    def test_unknown_unbound_negative_evidence_remains_blocked_but_input_repair_is_admissible(self):
        with self.assertRaises(LyricsContentBlocker):
            worker._require_no_unchanged_content_block({'status': 'lyrics-content-unresolved',
                'reasons': ['content-sequence-uncertain'], 'contentBinding': None}, 'c'*64, 'a'*64)
        worker._require_no_unchanged_content_block({'status': 'lyrics-content-unresolved',
            'reasons': ['content-text-invalid'], 'contentBinding': None}, 'c'*64, 'a'*64)
        for malformed in ('private lyric words', ['invalid reason with words']):
            with self.subTest(reasons=malformed), self.assertRaises(LyricsContentBlocker):
                worker._require_no_unchanged_content_block({'status': 'lyrics-content-unresolved',
                    'reasons': malformed, 'contentBinding': None}, 'c'*64, 'a'*64)

    def test_status_reports_durable_content_block_without_reclassifying_it_as_corrupt(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            workspace = worker.AlignmentWorkspace(root/'agent', workspace_root=root)
            workspace.write_state('abcdefghijk', {'status': 'lyrics-content-unresolved',
                'reasons': ['content-sequence-uncertain']})
            report = worker._job_summary(workspace)
            self.assertEqual(report['statusCounts'], {'lyrics-content-unresolved': 1})
            self.assertEqual(report['reasonCounts'], {'content-sequence-uncertain': 1})

    def test_publication_missing_admission_does_not_write_candidate_or_owner(self):
        from stemwise_engine.alignment_publication import AlignmentPublisher
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            raw = json.dumps({'tracks': [{'kind': 'original', 'text': 'one two'}]}).encode()
            owner = root/'owner.json'
            owner.write_bytes(raw)
            request = worker.AlignmentPublicationRequest(workspace=worker.AlignmentWorkspace(root/'agent', workspace_root=root),
                video_id='abcdefghijk', sidecar_path=owner, original_raw=raw, expected_current_raw=raw,
                candidate={}, alignment=SimpleNamespace(accepted=True, source_physical_indices=(0,)),
                lexical_mapping=None, public_line_timings=None, summary={}, endpoint_selection=None, no_commit=False)
            save, commit = Mock(), Mock()
            publisher = AlignmentPublisher()
            with patch('stemwise_engine.alignment_publication.save_alignment_artifacts', save), \
                    patch('stemwise_engine.alignment_publication.commit_candidate', commit):
                with self.assertRaises(LyricsContentBlocker):
                    publisher.publish(request, {'canonicalPlainSha256': hashlib.sha256(raw).hexdigest(),
                        'contentBinding': {'audioSha256': 'a'*64}})
                save.assert_not_called()
                commit.assert_not_called()
                self.assertEqual(owner.read_bytes(), raw)

    def test_required_negative_witness_preserves_prior_candidate_before_any_publication_write(self):
        from dataclasses import replace
        from stemwise_engine.alignment_publication import AlignmentPublisher
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            stage, _ = self.endpoint_stage(root)
            state = stage.services.progress.state
            workspace = stage.request.context.workspace
            video = stage.request.context.video_id
            raw = workspace.current_backup_path(video).read_bytes()
            owner = root/'owner.json'
            owner.write_bytes(raw)
            previous = workspace.candidate_path(video)
            previous.write_bytes(b'prior useful candidate')
            request = worker.AlignmentPublicationRequest(workspace=workspace, video_id=video,
                sidecar_path=owner, original_raw=raw, expected_current_raw=raw,
                candidate={}, alignment=SimpleNamespace(accepted=True, source_physical_indices=(0,)),
                lexical_mapping=None, public_line_timings=None, summary={}, endpoint_selection=None, no_commit=True)
            save, commit = Mock(return_value=None), Mock()
            publisher = AlignmentPublisher()
            with patch('stemwise_engine.alignment_publication.save_alignment_artifacts', save), \
                    patch('stemwise_engine.alignment_publication.commit_candidate', commit):
                state['structureWitness'] = {'status': 'failure', 'failureCode': 'fixture-failure'}
                with self.assertRaises(worker_protocol.WorkerProcessError):
                    publisher.publish(request, state)
                save.assert_not_called()
                commit.assert_not_called()
                self.assertEqual(previous.read_bytes(), b'prior useful candidate')
                self.assertEqual(owner.read_bytes(), raw)
                state['structureWitness'] = {'status': 'success', 'selectedSequenceSupported': True,
                                             'selectedSequenceEveryLineDirect': True}
                self.assertEqual(publisher.publish(request, state).status, 'candidate')
                save.assert_called_once()
                commit.assert_not_called()
