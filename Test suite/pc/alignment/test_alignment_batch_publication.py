"""Repaired windows must retain exact request authority before cache publication."""
import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import ModuleType
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
import stemwise_engine.alignment_audio as alignment_audio
import stemwise_engine.lyrics_align_pc as alignment
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, build_lexical_occurrence_mapping
from stemwise_engine.lyrics_tokenization import display_words
from stemwise_engine.ctc_worker import canonical_time_transform, chunk_time_map
from stemwise_engine.provenance import CTC_SOURCE_FILES


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


def install_worker_sources(project):
    source = ROOT / 'PC side/stemwise-playlist-builder/src'
    for name in ('mms_forced_alignment.py', 'wav2vec2_forced_alignment.py', 'common.py', *CTC_SOURCE_FILES):
        (project / 'src' / name).write_bytes((source / name).read_bytes())


class AlignmentBatchPublicationTests(unittest.TestCase):
    def test_serialized_fatal_loader_stops_cascade_without_replacing_retained_state(self):
        from stemwise_engine.common import WorkerRetirementError
        original = {'id': 'original', 'kind': 'original', 'language': 'en',
                    'script': 'Latn', 'format': 'plain', 'text': 'we sing'}
        mapping = build_lexical_occurrence_mapping('KbKqkR3Q6SA', original, [0])
        for family in ('mms', 'wav2vec2'):
            for present in (False, True):
                with self.subTest(family=family, output=present), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    root, calls = Path(name), []
                    project, temporary = root/'builder', root/'temp'
                    prepare_runtime_layout(project)
                    (project/'src').mkdir(parents=True)
                    (project/'.venv/bin').mkdir(parents=True)
                    (project/'.venv/bin/python').touch()
                    install_worker_sources(project)
                    temporary.mkdir()
                    audio = root/'audio.wav'
                    audio.write_bytes(b'owned exact audio')
                    identity = {'sha256': alignment._sha256_file(audio), 'byteCount': audio.stat().st_size,
                                'stream': {'sampleRate': 16000, 'channelCount': 1,
                                           'frameCount': 16000, 'durationMs': 1000}}
                    identity['canonicalTimeTransform'] = alignment_audio.identity_transform(identity['stream'])
                    workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)
                    retained = workspace.job_dir('KbKqkR3Q6SA')/'review.private.json'
                    retained.parent.mkdir(parents=True)
                    retained.write_bytes(b'{"review":"retained prior authority"}')
                    before = {path: path.read_bytes() for path in (audio, retained)}
                    outputs = []

                    def numerical(command, **kwargs):
                        output = Path(command[command.index('--output')+1])
                        if '--check-targets' in command:
                            request = json.loads(Path(command[command.index('--request')+1]).read_bytes())
                            output.write_text(json.dumps({'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                                'requestSha256': alignment._alignment_request_sha256(request), 'supported': True}), encoding='utf-8')
                            return subprocess.CompletedProcess(command, 0, '', '')
                        manifest = json.loads(Path(command[command.index('--batch')+1]).read_bytes())
                        output = Path(manifest['contexts'][0]['output'])
                        if present:
                            output.write_bytes(b'{"numerical":"must not be published"}')
                        outputs.append(output)
                        return subprocess.CompletedProcess(command, 70, '',
                            'original unretired worker\nSTEMWISE_WORKER_FAILURE={"formatVersion":1,"code":"alignment-model-retirement-failed"}\n')

                    def evaluate(route):
                        calls.append(route)
                        if route == 'next':
                            return True, 'fallback must never run'
                        common = dict(video_id='KbKqkR3Q6SA', sources=[0], duration_ms=1000,
                            workspace=workspace, temp_dir=temporary, device='cpu', timeout_seconds=5,
                            occurrence_mapping=mapping, display_word_lines=[display_words('we sing')],
                            config={'projectDir': str(project), 'tempDir': str(temporary)})
                        if family == 'mms':
                            return True, alignment._load_or_run_mms_alignment(**common,
                                variant='public-endpoints-v3', acoustic_track_kind='original',
                                helper=project/'src/mms_forced_alignment.py', mms_python=project/'.venv/bin/python',
                                vocals_audio=audio, provenance={'audio': identity})
                        return True, alignment._load_or_run_wav2vec2_alignment(**common,
                            language='en', audio=audio, audio_identity=identity)

                    with patch('stemwise_engine.alignment_model_queue.prepare_emissions'), \
                            patch.object(alignment, '_run_numerical_process', side_effect=numerical), \
                            patch.object(alignment_audio, 'file_identity', return_value=identity), \
                            patch.object(alignment, '_wav2vec2_model_assets', return_value=(root/'model', {})), \
                            patch.object(alignment, '_wav2vec2_runtime_provenance', return_value={}):
                        with self.assertRaises(WorkerRetirementError) as raised:
                            alignment._run_conditional_cascade(('first', 'next'), evaluate)
                    self.assertEqual(raised.exception.returncode, 70)
                    self.assertEqual(calls, ['first'])
                    self.assertEqual({path: path.read_bytes() for path in before}, before)
                    self.assertEqual(len(outputs), 1)
                    self.assertEqual(outputs[0].exists(), present)
                    if present:
                        self.assertEqual(outputs[0].read_bytes(), b'{"numerical":"must not be published"}')
                    self.assertFalse(list(workspace.job_dir('KbKqkR3Q6SA').glob('*public-endpoints*.private.json')))

    def test_native_mms_cache_rejoins_requested_mix_plan_without_repair_or_rewrite(self):
        from types import SimpleNamespace
        import stemwise_engine.alignment_endpoints as endpoints
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyrics_alignment import snapshot_canonical_plain
        sys.path.insert(0, str(ROOT / 'Test suite/pc/unit'))
        from lyric_content_fixtures import retained_content

        video_id, mix_duration = 'KbKqkR3Q6SA', 3000
        original = {'id': 'original', 'kind': 'original', 'language': 'en',
                    'script': 'Latn', 'format': 'plain', 'text': 'a\nb\nc'}
        mapping = build_lexical_occurrence_mapping(video_id, original, [0, 1, 2])
        words = [display_words(word) for word in 'abc']
        segments = [{'segmentIndex': i, 'startLineIndex': i, 'endLineIndex': i + 1,
                     'windowStartMs': [0, 1600, 2200][i],
                     'windowEndMs': [1600, 2200, mix_duration][i]} for i in range(3)]
        for delta in (-1, 0, 1):
            native_duration = mix_duration + delta
            original_duration = native_duration - 1 if delta <= 0 else native_duration + 1
            for family in (endpoints.WAV2VEC2_FAMILY, endpoints.UNPROMPTED_WHISPER_FAMILY):
                with self.subTest(delta=delta, family=family), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as folder:
                    base = Path(folder)
                    project = base / 'builder'
                    prepare_runtime_layout(project)
                    (project / 'src').mkdir(parents=True)
                    install_worker_sources(project)
                    for path in (project / '.venv/bin/python', base / 'model-cache/torch/hub/checkpoints/model.pt',
                                 base / 'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th'):
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(b'fixture dependency')
                    audio = base / 'native.wav'
                    audio.write_bytes(b'bound acoustic fixture')
                    identity = {'sha256': alignment._sha256_file(audio), 'byteCount': audio.stat().st_size,
                                'stream': {'sampleRate': 48000, 'channelCount': 2,
                                           'frameCount': native_duration * 48, 'durationMs': native_duration}}
                    workspace = AlignmentWorkspace(base / 'alignment', workspace_root=base)
                    _, canonical_sha = snapshot_canonical_plain(workspace, video_id,
                        json.dumps({'tracks': [original]}).encode('utf-8'))
                    admission, _ = retained_content(workspace.job_dir(video_id), original, [0, 1, 2], canonical_sha, 'a' * 64)
                    state = {'canonicalPlainSha256': canonical_sha, 'contentBinding': admission.binding,
                             'contentAdmission': admission.to_record(), 'pinnedModels': {}}
                    atomic_write_json(workspace.state_path(video_id), state)
                    temporary = base / 'temp'
                    temporary.mkdir()
                    numerical_calls, mms_calls = [], []

                    def numerical(**kwargs):
                        request = kwargs['request']
                        self.assertEqual(request['durationMs'], native_duration)
                        self.assertEqual(request['segments'][:-1], segments[:-1])
                        self.assertEqual(request['segments'][-1], {**segments[-1], 'windowEndMs': native_duration})
                        numerical_calls.append(copy.deepcopy(request))
                        self.assertEqual(len(numerical_calls), 1, 'A valid native cache must not launch numerics again')
                        lines = [{'lineIndex': i, 'sourcePhysicalIndex': i, 'lineId': line['lineId'],
                                  'startMs': start, 'endMs': end, 'confidence': .9, 'minimumWordConfidence': .9,
                                  'displayWordCount': 1, 'acousticTokenCount': 1, 'romanizedWordCount': 1,
                                  'words': [{'wordId': line['words'][0]['wordId'], 'startMs': start,
                                             'endMs': end, 'confidence': .9, 'acousticTokenCount': 1,
                                             'romanizedWordCount': 1}]}
                                 for i, (line, start, end) in enumerate(zip(request['lines'],
                                     [300, 1870, 2500], [500, 1930, native_duration]))]
                        value = {'formatVersion': 5, 'engine': 'torchaudio-mms-fa', 'sampleRate': 16000,
                                 'durationMs': native_duration, 'emissionFrameCount': 60,
                                 'canonicalTimeTransform': canonical_time_transform(source_sample_rate=48000,
                                     source_channel_count=2, source_frame_count=native_duration * 48,
                                     target_sample_rate=16000, target_frame_count=native_duration * 16,
                                     chunk_target_frames=480000,
                                     chunks=[chunk_time_map(0, native_duration * 16, 0, 60)]),
                                 'segmentCount': 3, 'segmentBoundaryGuardMs': 250, 'displayWordCount': 3,
                                 'acousticTokenCount': 3, 'romanizedWordCount': 3, 'lineCount': 3,
                                 'confidence': {key: .9 for key in ('wordP10', 'wordP50', 'wordP90', 'lineP10', 'lineP50', 'lineP90')},
                                 'lines': lines}
                        Path(kwargs['output_path']).write_text(json.dumps(value), encoding='utf-8')
                        return subprocess.CompletedProcess([], 0, '', ''), request, None

                    def mms(**kwargs):
                        mms_calls.append(copy.deepcopy(kwargs['segments']))
                        self.assertLessEqual(len(mms_calls), 2, 'EOF rounding cannot oscillate the caller plan')
                        return alignment._load_final_mms_alignment(**kwargs)

                    def unexpected(*args, **kwargs):
                        self.fail('An unchanged requested plan must not launch another verifier or report recovery failure')

                    verifier = tuple({'startMs': start, 'endMs': end} for start, end in
                                     ((300, 500), (1870, 1930), (2500, min(native_duration, original_duration))))
                    stage = endpoints.EndpointAlignmentStage(
                        request=alignment.EndpointAlignmentRequest(
                            context=alignment.AlignmentContext(config={'projectDir': str(project), 'analysisTimeoutMinutes': 1},
                                video_id=video_id, workspace=workspace, temp_root=temporary, device='cpu',
                                duration_ms=original_duration, endpoint_duration_ms=mix_duration),
                            text=alignment.EndpointText(original=original, alignment=SimpleNamespace(source_physical_indices=(0, 1, 2)),
                                lexical_mapping=mapping, sources=[0, 1, 2], source_occurrences=(0, 1, 2),
                                display_word_lines=words, acoustic_language_lines=[]),
                            evidence=alignment.EndpointEvidence(baseline_segments=segments, seeded_mms_segments=None,
                                verifier_candidates=verifier, verifier_family=family, verifier_reused=True,
                                wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
                                reviewed_lrc_starts={}, route_names={}, content_admission=admission.to_record()),
                            audio=alignment.EndpointAudio(expected_mix_sha256='a' * 64, analysis_audio=audio,
                                canonical_mix=unexpected, get_endpoint_audio=lambda _: (audio, identity, {}))),
                        services=alignment.EndpointServices(
                            workers=alignment.EndpointWorkers(load_mms=mms, load_wav2vec2=unexpected,
                                load_owsm=unexpected, load_nfa=unexpected, read_audio_provenance=unexpected,
                                verifier_candidates=unexpected, needs_local_verifier=unexpected),
                            events=alignment.AlignmentEvents(attempt=unexpected, failure=unexpected, roformer=unexpected),
                            progress=alignment.AlignmentProgress(state=state, endpoint_attempts=[], checkpoint=unexpected),
                            release_model=unexpected, runtime_probe_cache={}))
                    with patch.object(alignment, '_mms_runtime_provenance', return_value={}), \
                            patch.object(alignment, '_forced_alignment_provenance', side_effect=lambda **kw: {'audio': kw['audio_identity']}), \
                            patch.object(alignment_audio, 'read_audio_provenance', return_value={'audioViews': {'htdemucs-vocals': identity}}), \
                            patch.object(alignment_audio, 'file_identity', return_value=identity), \
                            patch.object(alignment, '_run_alignment_worker_batch', side_effect=numerical):
                        cache = alignment._forced_alignment_cache_path(workspace, video_id, 'public-endpoints-v3-anchored')
                        first_bytes = None
                        for reused in (False, True):
                            paired, actual_segments, actual_reused = stage.repair_anchored('htdemucs-vocals', copy.deepcopy(segments), verifier)
                            self.assertIsNotNone(paired)
                            self.assertEqual(actual_segments, segments)
                            self.assertEqual(actual_reused, reused)
                            forced, actual_verifier = paired.require_plan(segments)
                            self.assertEqual(forced['resolvedSegments'], segments)
                            self.assertEqual(actual_verifier, verifier)
                            self.assertEqual(forced['lines'][-1]['endMs'], min(native_duration, original_duration))
                            self.assertEqual(forced['lines'][-1]['words'][0]['endMs'], min(native_duration, original_duration))
                            raw = json.loads(cache.read_bytes())
                            self.assertEqual(raw['durationMs'], native_duration)
                            self.assertEqual(raw['resolvedSegments'][:-1], segments[:-1])
                            self.assertEqual(raw['resolvedSegments'][-1], {**segments[-1], 'windowEndMs': native_duration})
                            self.assertEqual(raw['lines'][-1]['endMs'], native_duration)
                            self.assertEqual(raw['lines'][-1]['words'][0]['endMs'], native_duration)
                            if first_bytes is None:
                                first_bytes = cache.read_bytes()
                                audio.unlink()  # The second call must use the retained cache without acoustic input.
                            self.assertEqual(cache.read_bytes(), first_bytes)
                        self.assertEqual(mms_calls, [segments, segments])
                        self.assertEqual(len(numerical_calls), 1)

    @unittest.skipUnless(sys.platform == 'linux', 'cold queue admission requires its real kernel lease')
    def test_cold_parent_admits_exact_preparation_diagnostic_before_numerical_launch(self):
        import os
        import wave
        import stemwise_engine.analysis_queue as analysis_queue
        import stemwise_engine.alignment_model_queue as alignment_model_queue
        original = {'id': 'original', 'kind': 'original', 'language': 'en',
                    'script': 'Latn', 'format': 'plain', 'text': 'we sing'}
        mapping = build_lexical_occurrence_mapping('KbKqkR3Q6SA', original, [0])
        request = alignment._forced_alignment_request('KbKqkR3Q6SA', [0], mapping,
            [display_words('we sing')], duration_ms=1000)[0]
        for family in ('mms', 'wav2vec2'):
            with self.subTest(family=family), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as folder:
                root = Path(folder)
                project = root / 'pc/builder'
                (project / 'src').mkdir(parents=True)
                install_worker_sources(project)
                audio = root / 'audio.wav'
                with wave.open(str(audio), 'wb') as stream:
                    stream.setnchannels(2)
                    stream.setsampwidth(2)
                    stream.setframerate(48000)
                    stream.writeframes(b'\0\0\0\0' * 48000)
                attempt = root / 'attempt'
                attempt.mkdir()
                request_path, output_path = attempt / 'request.json', attempt / 'output.json'
                request_path.write_text(json.dumps(request))
                review = root / 'review.json'
                review.write_text('{"outcome":"pending"}')
                before = review.read_bytes()
                diagnostic = root / 'numeric.failure.private.txt'
                expected = root / 'numeric.failure.private.prepare.failure.private.txt'
                dispatch = analysis_queue._dispatch
                admitted, launched = [], []
                probed = []
                def observe_admission(*args, **kwargs):
                    preparation = kwargs['local_request']['preparation']
                    self.assertEqual(Path(preparation['diagnosticPath']), expected)
                    admitted.append(preparation['family'])
                    return dispatch(*args, **kwargs)
                def numerical_failure(command, **kwargs):
                    if '--check-targets' in command:
                        self.assertEqual(admitted, [])
                        bound = json.loads(Path(command[command.index('--request')+1]).read_bytes())
                        Path(command[command.index('--output')+1]).write_text(json.dumps({
                            'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                            'requestSha256': digest(bound), 'supported': True}), encoding='utf-8')
                        probed.append(command)
                        return subprocess.CompletedProcess(command, 0, '', '')
                    self.assertEqual(admitted, [family], 'Numerics cannot precede actual queue admission')
                    self.assertIn('--batch', command)
                    launched.append(command)
                    return subprocess.CompletedProcess(command, 17, '', 'controlled numerical failure')
                # The queue, its diagnostic schema and authenticated lone admission are real.
                # Only the later numerical process is substituted; failure cannot publish timing.
                with patch.dict(sys.modules, {'stemwise_engine.alignment_model_queue': alignment_model_queue}), \
                        patch.object(analysis_queue, '_dispatch', side_effect=observe_admission), \
                        patch.object(alignment, 'run_process_tree', side_effect=numerical_failure), \
                        patch.object(alignment, '_run_numerical_process', side_effect=numerical_failure, create=True):
                    completed, final_request, recovery = alignment._run_alignment_worker_batch(
                        config={'projectDir': str(project), 'tempDir': str(attempt)}, python=Path(sys.executable),
                        helper=project / 'src' / f'{family}_forced_alignment.py', audio=audio,
                        audio_sha256=alignment._sha256_file(audio), request=request, request_path=request_path,
                        output_path=output_path, device='cpu', timeout_seconds=5, cache_key='a' * 64,
                        producer={'model': 'queue-contract-fixture'}, environment=os.environ.copy(),
                        failure_path=diagnostic, repair=True,
                        model=root / 'model' if family == 'wav2vec2' else None,
                        language='en' if family == 'wav2vec2' else None)
                self.assertEqual(completed.returncode, 17)
                self.assertEqual(len(launched), 1)
                self.assertEqual(len(probed), int(family == 'wav2vec2'))
                self.assertEqual(final_request, request)
                self.assertIsNone(recovery)
                self.assertFalse(output_path.exists())
                self.assertFalse(list(attempt.glob('emissions-*.pt')))
                self.assertFalse(list((project / 'runtime/acoustic-queue').glob('*/request.json')))
                self.assertEqual(review.read_bytes(), before)





    def test_ctc_cache_requires_exact_current_helpers_and_keeps_stored_identity(self):
        current = {'mms_forced_alignment.py':'a'*64,'owsm_ctc_forced_alignment.py':'b'*64}
        stored = dict(current)
        self.assertTrue(alignment._ctc_cache_helpers_match(stored,current))
        for changed in ({**current,'mms_forced_alignment.py':'c'*64}, {'mms_forced_alignment.py':'a'*64}, {}):
            self.assertFalse(alignment._ctc_cache_helpers_match(changed,current))
        self.assertEqual(stored,current)

    def test_bound_merge_reply_rejects_stale_partial_or_arbitrary_repairs(self):
        request = {"formatVersion": 5, "durationMs": 3000,
            "lines": [{"lineId": str(i), "words": [{"wordId": str(i), "acousticText": word}]} for i, word in enumerate("abc")],
            "segments": [{"segmentIndex": i, "startLineIndex": i, "endLineIndex": i+1,
                "windowStartMs": [0, 1600, 2200][i], "windowEndMs": [1600, 2200, 3000][i]} for i in range(3)]}
        segments = [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 2,
                     "windowStartMs": 0, "windowEndMs": 2200},
                    {"segmentIndex": 1, "startLineIndex": 2, "endLineIndex": 3,
                     "windowStartMs": 2200, "windowEndMs": 3000}]
        resolved = {**request, "segments": segments}
        manifest = {"formatVersion": 1, "repairSegments": True,
                    "contexts": [{"contextId": "0", "audioSha256": "a" * 64,
                                  "requestSha256": digest(request)}]}
        row = {**manifest["contexts"][0], "resolvedRequestSha256": digest(resolved),
               "resolvedSegments": segments, "mergeSteps": [{"segmentIndex": 1, "boundary": "start"}]}
        reply = {"formatVersion": 1, "batchSha256": digest(manifest), "contexts": [row]}
        actual, recovery = alignment._read_alignment_batch_reply(manifest, reply, request)
        self.assertEqual(actual, resolved)
        self.assertEqual(alignment._resolved_alignment_request(request, recovery, allow_repair=True), resolved)
        self.assertEqual(alignment._resolved_alignment_request(resolved, recovery, allow_repair=True), resolved)
        for fault in ("batch", "partial", "duplicate", "context", "audio", "request", "resolved", "arbitrary", "exterior", "words", "baseline"):
            with self.subTest(fault=fault):
                damaged, current, expected = copy.deepcopy(reply), copy.deepcopy(request), copy.deepcopy(manifest)
                if fault == "batch": damaged["batchSha256"] = "f" * 64
                elif fault == "partial": damaged["contexts"] = []
                elif fault == "duplicate": damaged["contexts"] *= 2
                elif fault in ("context", "audio", "request", "resolved"):
                    key = {"context": "contextId", "audio": "audioSha256", "request": "requestSha256", "resolved": "resolvedRequestSha256"}[fault]
                    damaged["contexts"][0][key] = "f" * 64
                elif fault == "arbitrary": damaged["contexts"][0]["resolvedSegments"][0]["windowEndMs"] = 2100
                elif fault == "exterior": damaged["contexts"][0]["mergeSteps"] = [{"segmentIndex": 0, "boundary": "start"}]
                elif fault == "words": current["lines"][0]["words"][0]["acousticText"] = "different"
                elif fault == "baseline":
                    expected["repairSegments"] = False
                    damaged["batchSha256"] = digest(expected)
                with self.assertRaises(ValueError):
                    alignment._read_alignment_batch_reply(expected, damaged, current)

    def test_native_loaders_publish_only_validated_repaired_results_and_reuse_them(self):
        import wave
        original = {"id": "original", "kind": "original", "language": "en", "script": "Latn",
                    "format": "plain", "text": "a\nb\nc"}
        mapping = build_lexical_occurrence_mapping("KbKqkR3Q6SA", original, [0, 1, 2])
        words = [display_words(word) for word in "abc"]
        initial_segments = [{"segmentIndex": i, "startLineIndex": i, "endLineIndex": i+1,
            "windowStartMs": [0, 1600, 2200][i], "windowEndMs": [1600, 2200, 3000][i]} for i in range(3)]
        for family in ("mms", "wav"):
            with self.subTest(family=family), tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as folder:
                base = Path(folder)
                project = base / "builder"
                prepare_runtime_layout(project)
                (project / "src").mkdir(parents=True)
                (project / ".venv/bin").mkdir(parents=True)
                (project / ".venv/bin/python").write_bytes(b"python")
                install_worker_sources(project)
                audio = base / "audio.wav"
                with wave.open(str(audio), "wb") as sound:
                    sound.setnchannels(2)
                    sound.setsampwidth(2)
                    sound.setframerate(48000)
                    sound.writeframes(b"\0\0\0\0" * 144000)
                workspace = AlignmentWorkspace(base / "alignment", workspace_root=base)
                identity = alignment_audio.write_audio_provenance(workspace, "KbKqkR3Q6SA",
                    duration_ms=3000, mix_audio=audio)["mix"]
                temporary = base / "temp"
                temporary.mkdir()
                config = {"projectDir": str(project), "tempDir": str(temporary)}
                queue = ModuleType("alignment_model_queue")
                queue.prepare_emissions = lambda **kwargs: None
                calls = []
                real_process = alignment.run_process_tree
                def worker(command, **kwargs):
                    if '--check-targets' in command:
                        request = json.loads(Path(command[command.index('--request')+1]).read_bytes())
                        Path(command[command.index('--output')+1]).write_text(json.dumps({
                            'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                            'requestSha256': alignment._alignment_request_sha256(request),
                            'supported': True}), encoding='utf-8')
                        return subprocess.CompletedProcess(command, 0, '', '')
                    if "--batch" not in command:
                        return real_process(command, **kwargs)
                    self.assertIn("--batch", command)
                    batch_path = Path(command[command.index("--batch") + 1])
                    manifest = json.loads(batch_path.read_bytes())
                    context = manifest["contexts"][0]
                    request = json.loads(Path(context["request"]).read_bytes())
                    resolved_segments = [{"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 2,
                            "windowStartMs": 0, "windowEndMs": 2200},
                        {"segmentIndex": 1, "startLineIndex": 2, "endLineIndex": 3,
                            "windowStartMs": 2200, "windowEndMs": 3000}]
                    resolved = {**request, "segments": resolved_segments}
                    lines = [{"lineIndex": i, "sourcePhysicalIndex": i, "lineId": line["lineId"],
                        "startMs": start, "endMs": end, "confidence": .9, "minimumWordConfidence": .9,
                        "displayWordCount": 1, "acousticTokenCount": 1, "words": [{"wordId": line["words"][0]["wordId"],
                            "startMs": start, "endMs": end, "confidence": .9, "acousticTokenCount": 1}]}
                        for i, (line, start, end) in enumerate(zip(request["lines"], [300, 1500, 2500], [500, 1700, 2700]))]
                    numeric = {"formatVersion": 5 if family == "mms" else 4,
                        "engine": "torchaudio-mms-fa" if family == "mms" else "wav2vec2-ctc-forced-alignment",
                        "sampleRate": 16000, "durationMs": 3000, "emissionFrameCount": 60,
                        "canonicalTimeTransform": canonical_time_transform(source_sample_rate=48000,
                            source_channel_count=2, source_frame_count=144000, target_sample_rate=16000,
                            target_frame_count=48000, chunk_target_frames=480000,
                            chunks=[chunk_time_map(0, 48000, 0, 60)]),
                        "segmentCount": 2, "segmentBoundaryGuardMs": 250, "displayWordCount": 3, "acousticTokenCount": 3, "lineCount": 3,
                        "confidence": {key: .9 for key in ("wordP10", "wordP50", "wordP90", "lineP10", "lineP50", "lineP90")},
                        "lines": lines}
                    if family == 'mms':
                        numeric['romanizedWordCount'] = 3
                        for line in lines:
                            line['romanizedWordCount'] = 1
                            line['words'][0]['romanizedWordCount'] = 1
                    if family == "wav": numeric["requestFormatVersion"] = 5
                    Path(context["output"]).write_text(json.dumps(numeric))
                    row = {"contextId": "0", "audioSha256": context["audioSha256"],
                        "requestSha256": digest(request), "resolvedRequestSha256": digest(resolved),
                        "resolvedSegments": resolved_segments, "mergeSteps": [{"segmentIndex": 1, "boundary": "start"}]}
                    Path(command[command.index("--output") + 1]).write_text(json.dumps({
                        "formatVersion": 1, "batchSha256": digest(manifest), "contexts": [row]}))
                    calls.append(command)
                    return subprocess.CompletedProcess(command, 0, "", "")
                def load(selected_audio):
                    common = dict(video_id="KbKqkR3Q6SA", sources=[0, 1, 2], duration_ms=3000,
                        workspace=workspace, temp_dir=temporary, device="cpu", timeout_seconds=5,
                        occurrence_mapping=mapping, display_word_lines=words, segments=initial_segments)
                    if family == "mms":
                        return alignment._load_or_run_mms_alignment(**common, config=config,
                            variant="public-endpoints-v3-anchored", acoustic_track_kind="original",
                            helper=project / "src/mms_forced_alignment.py", mms_python=project / ".venv/bin/python",
                            vocals_audio=selected_audio, provenance={"audio": identity})
                    return alignment._load_or_run_wav2vec2_alignment(**common, config=config, language="en",
                        audio=selected_audio, audio_identity=identity, request_variant="anchored")
                with patch.dict(sys.modules, {'stemwise_engine.alignment_model_queue': queue}), \
                        patch.object(alignment, "run_process_tree", side_effect=worker), \
                        patch.object(alignment, "_wav2vec2_model_assets", return_value=(base / "model", {})), \
                        patch.object(alignment, "_wav2vec2_runtime_provenance", return_value={}):
                    first = load(audio)
                    self.assertFalse(first[1])
                    self.assertEqual(len(first[0]["resolvedSegments"]), 2)
                    second = load(None)
                    self.assertTrue(second[1], "the original request must reuse its validated resolved plan")
                    self.assertEqual(first[0], second[0])
                    self.assertEqual(len(calls), 1)
                    cache_files = list(workspace.job_dir("KbKqkR3Q6SA").glob("*-anchored.private.json"))
                    self.assertEqual(len(cache_files), 1)
                    cache = cache_files[0]
                    valid = cache.read_bytes()
                    for fault in ("word", "merge", "resolved"):
                        damaged = json.loads(valid)
                        if fault == "word": damaged["lines"][0]["words"][0]["wordId"] = "unapproved"
                        elif fault == "merge": damaged["alignmentRequestRecovery"]["mergeSteps"][0]["segmentIndex"] = 0
                        else: damaged["resolvedSegments"][0]["windowEndMs"] = 2000
                        raw = json.dumps(damaged).encode()
                        cache.write_bytes(raw)
                        with self.assertRaises(FileNotFoundError):
                            load(None)
                        self.assertEqual(cache.read_bytes(), raw)
                    cache.write_bytes(valid)


if __name__ == "__main__":
    unittest.main()
