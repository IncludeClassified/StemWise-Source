import itertools
import importlib.metadata
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

from stemwise_engine.endpoint_ctc_evidence import boundary_distributions, select_candidate, _assess, verify_selections
from stemwise_engine.endpoint_vocal_evidence import ROUTES
import stemwise_engine.endpoint_ctc_evidence as ctc
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'Test suite/pc/unit'))
from lyric_content_fixtures import retained_content
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
from stemwise_engine.workflow_records import LyricsContentBlocker


class CtcBoundaryTests(unittest.TestCase):
    def test_bound_review_candidates_retain_raw_support_without_text(self):
        from stemwise_engine.common import json_bytes, sha256_file, atomic_write_json
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        from stemwise_engine.workflow_review import ReviewConstructionService
        from stemwise_engine.workflow_diagnostics import _occurrence_candidate_diagnostics
        from stemwise_engine.workflow_records import FutureSongError
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            video = 'TESTVID0001'
            original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                        'language': 'en', 'script': 'Latn', 'text': 'hello'}
            lyrics = {'tracks': [original]}
            mapping = build_lexical_occurrence_mapping(video, original, (0,))
            mapped = mapping['performedOccurrences'][0]
            word_id = mapped['words'][0]['occurrenceWordId']
            families = {'mms-fa': {'route': 'fixture-mms', 'startMs': 1000, 'endMs': 2000,
                'confidence': .9, 'words': [{'occurrenceWordId': word_id,
                    'startMs': 1000, 'endMs': 2000, 'confidence': .01, 'acousticTokenCount': 1}]},
                'language-wav2vec2': {'route': 'fixture-wav', 'startMs': 1000, 'endMs': 2000,
                    'words': [{'occurrenceWordId': word_id, 'startUtf16': 0, 'endUtf16': 5,
                               'startMs': 1000, 'endMs': 2000}]}}
            evidence = {'occurrenceCandidates': [{'occurrenceId': mapped['occurrenceId'],
                'performedIndex': 0, 'sourcePhysicalIndex': 0, 'familyCandidates': families,
                'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'}}]}
            evidence['occurrenceCandidates'] = _occurrence_candidate_diagnostics(evidence['occurrenceCandidates'])
            occurrences = ReviewConstructionService.endpoint_review_occurrences(evidence, mapping, 3000)
            self.assertEqual(occurrences[0]['candidateFamilies'], families)
            canonical = root / 'lyrics-alignment/canonical-plain' / (video + '.json')
            canonical.parent.mkdir(parents=True)
            canonical.write_bytes(json_bytes(lyrics))
            admission, _ = retained_content(root/'lyrics-alignment/jobs'/video, original, (0,),
                sha256_file(canonical), 'a'*64)
            declaration = {'videoId': video, 'canonicalPlainSha256': sha256_file(canonical),
                'analysisAudio': {'sha256': 'a'*64, 'durationMs': 3000},
                'contentAdmission': admission.to_record(), 'occurrences': occurrences}
            plan = ctc._plan(declaration, mapping, evidence, lyrics,
                evidence_root=root/'lyrics-alignment/jobs'/video)[0]
            self.assertEqual(plan['candidateFamilies'], families)
            scratch = root/'scratch'
            scratch.mkdir()
            ctc._write_context_request(scratch, plan)
            changed = copy.deepcopy(plan)
            changed['candidateFamilies']['mms-fa']['words'][0]['confidence'] = .9
            with self.assertRaises(ValueError):
                ctc._write_context_request(scratch, changed)
            records = [{'route': route, 'shiftMs': shift, 'decisions': {
                boundary: {'result': 'supported', 'candidateMs': time}
                for boundary, time in (('startMs', 1000), ('endMs', 2000))}}
                for route in ROUTES for shift in (-5, 0, 5)]
            attempt = root/'endpoint-review/attempts'/('b'*32)
            attempt.mkdir(parents=True)
            manifest = {'method': ctc.METHOD, 'scorerVersion': ctc.SCORER_VERSION,
                'reviewRequired': True, 'declarationSha256': 'c'*64,
                'analysisAudio': declaration['analysisAudio'], 'canonicalPlainSha256': sha256_file(canonical),
                'occurrences': [{'occurrenceId': mapped['occurrenceId'], 'inference': {'records': records},
                    'assessment': ctc._assess(records, plan['candidateFamilies'])}]}
            directory = ctc._directory(attempt)
            directory.mkdir(parents=True)
            path = directory/'manifest.json'
            atomic_write_json(path, manifest)
            binding = {'method': ctc.METHOD, 'manifestSha256': sha256_file(path)}
            verified = ctc.verify_packet(attempt, declaration, 'c'*64, root/'unused-audio', binding)
            self.assertEqual(verified['occurrences'][0]['assessment']['startMs']['supportedFamilies'],
                             ['language-wav2vec2'])
            changed = copy.deepcopy(declaration)
            changed['occurrences'][0]['candidateFamilies']['mms-fa']['words'][0]['confidence'] = .9
            with self.assertRaises(ValueError):
                ctc.verify_packet(attempt, changed, 'c'*64, root/'unused-audio', binding)
            for target in ('line', 'word'):
                bad = copy.deepcopy(evidence)
                candidate = bad['occurrenceCandidates'][0]['familyCandidates']['mms-fa']
                (candidate if target == 'line' else candidate['words'][0])['text'] = 'unreviewed text'
                with self.assertRaises(FutureSongError):
                    ReviewConstructionService.endpoint_review_occurrences(bad, mapping, 3000)

    def setUp(self):
        self.runtime = {'torch': 'fixture', 'cudaRuntime': '12.8', 'cudnn': 90000,
            'cudaDevice': {'name': 'GPU A', 'capability': [8, 9], 'totalMemoryBytes': 16*1024**3, 'uuid': 'a'*32}}
        for name in ('torch_runtime_identity', 'selected_torch_runtime'):
            active = patch('stemwise_engine.worker_runtime_probe.' + name, return_value=self.runtime)
            active.start()
            self.addCleanup(active.stop)

    def test_retry_cache_tracks_observed_cuda_producer(self):
        import stemwise_engine.worker_runtime_probe as probe
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            scratch = Path(name)
            self._prepared_context(scratch)
            with patch.object(ctc, 'infer_records', side_effect=lambda *a, **k: {
                    'records': [probe.torch_runtime_identity()],
                    'runtime': {**probe.torch_runtime_identity(), 'transformers': importlib.metadata.version('transformers')}}) as native:
                ctc._infer(scratch, scratch)
                first = (scratch/'inference.json').read_bytes()
                ctc._infer(scratch, scratch)
                self.assertEqual(native.call_count, 1)
                self.assertEqual((scratch/'inference.json').read_bytes(), first)
                with patch.object(probe, 'torch_runtime_identity', return_value={**self.runtime, 'cudaDevice': {**self.runtime['cudaDevice'], 'uuid': 'b'*32}}):
                    ctc._infer(scratch, scratch)
                    self.assertEqual(native.call_count, 2)
                    self.assertNotEqual((scratch/'inference.json').read_bytes(), first)

    def test_packet_reuse_requires_current_observed_producer(self):
        import soundfile as sf
        import stemwise_engine.worker_runtime_probe as probe
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            root = Path(name)
            audio, canonical = root/'audio.wav', root/'canonical.json'
            sf.write(audio, np.zeros((4800, 2)), 48000, subtype='FLOAT')
            canonical.write_text('{}', encoding='utf-8')
            declaration = {'canonicalPlainSha256': ctc.sha256_file(canonical), 'videoId': 'TESTVID0001',
                'analysisAudio': {'sha256': ctc.sha256_file(audio)}, 'occurrences': [{'occurrenceId': 'one',
                    'candidateFamilies': {'candidate': {'startMs': 10, 'endMs': 90}}}]}
            plan = {**declaration['occurrences'][0], 'language': 'en', 'startMs': 0, 'endMs': 100}
            def native(*args, **kwargs):
                return {'runtime': {**probe.torch_runtime_identity(), 'transformers': importlib.metadata.version('transformers')}, 'records': [
                    {'route': route, 'shiftMs': shift, 'decisions': {boundary: {'result': 'abstain'}
                        for boundary in ('startMs', 'endMs')}} for route in ROUTES for shift in (-5, 0, 5)]}
            def worker(project, mode, contexts, timeout, **kwargs):
                for scratch in contexts:
                    views = {route: {'path': 'source.wav', 'provenance': {'sha256': ctc.sha256_file(scratch/'source.wav'),
                        'byteCount': (scratch/'source.wav').stat().st_size}} for route in ROUTES}
                    (scratch/'prepared.json').write_text(json.dumps({'modelPath': str(root), 'model': {'snapshot': 'fixture'},
                        'views': views}), encoding='utf-8')
                    ctc._infer(project, scratch)
            arguments = (root/'endpoint-review/attempt', declaration, 'bound-declaration', audio,
                {'projectDir': str(root), 'analysisTimeoutMinutes': 1}, {}, {}, canonical)
            with patch.object(ctc, '_plan', return_value=[plan]), patch.object(ctc, '_invoke', side_effect=worker) as invoke, \
                    patch.object(ctc, 'infer_records', side_effect=native) as inference, \
                    patch.object(ctc, 'require_endpoint_content'), patch.object(ctc, 'needs_timing_recovery'):
                ctc.write_packet(*arguments)
                path = ctc._directory(arguments[0])/'manifest.json'
                first = path.read_bytes()
                ctc.write_packet(*arguments)
                self.assertEqual(invoke.call_count, 1)
                self.assertEqual(path.read_bytes(), first)
                alternate = {**self.runtime, 'cudaDevice': {**self.runtime['cudaDevice'], 'uuid': 'b'*32}}
                with patch.object(probe, 'selected_torch_runtime', return_value={**self.runtime, 'cudnn': 90001}), \
                        patch.object(probe, 'torch_runtime_identity', return_value=alternate):
                    with self.assertRaisesRegex(ValueError, 'packet worker used a different'):
                        ctc.write_packet(*arguments)
                self.assertEqual(path.read_bytes(), first)
                with patch.object(probe, 'selected_torch_runtime', return_value=alternate), \
                        patch.object(probe, 'torch_runtime_identity', return_value=alternate):
                    ctc.write_packet(*arguments)
                    self.assertEqual(inference.call_count, 2)
                    self.assertEqual(json.loads(path.read_bytes())['producerRuntime'], alternate)
                    self.assertNotEqual(path.read_bytes(), first)
                retained = path.read_bytes()
                third = {**self.runtime, 'cudaDevice': {**self.runtime['cudaDevice'], 'uuid': 'c'*32}}
                with patch.object(probe, 'selected_torch_runtime', return_value=third), \
                        patch.object(probe, 'torch_runtime_identity', return_value=third), \
                        patch.object(ctc, 'infer_records', side_effect=RuntimeError('replacement failed')):
                    with self.assertRaisesRegex(RuntimeError, 'replacement failed'):
                        ctc.write_packet(*arguments)
                self.assertEqual(path.read_bytes(), retained)

    def _prepared_context(self, scratch, *, request=None, model=None):
        scratch.mkdir(parents=True, exist_ok=True)
        views = {}
        for route in ROUTES:
            path = scratch/(route+'.wav')
            path.write_bytes(b'exact separated source')
            views[route] = {'path': path.name, 'provenance': {
                'byteCount': path.stat().st_size, 'sha256': ctc.sha256_file(path)}}
        (scratch/'source.wav').write_bytes(b'exact crop')
        (scratch/'request.private.json').write_text(json.dumps(request or {'language': 'en'}), encoding='utf-8')
        (scratch/'prepared.json').write_text(json.dumps({'modelPath': str(scratch),
            'model': model or {'snapshot': 'current'}, 'views': views}), encoding='utf-8')

    def test_retry_reuses_exact_success_and_executes_seventeen_contexts_serially(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            root = Path(name)
            contexts = [root/str(index) for index in range(17)]
            for path in contexts:
                self._prepared_context(path)
            calls, active, maximum = [], 0, 0
            fail = True
            def infer(scratch, **options):
                nonlocal active, maximum
                calls.append(scratch.name)
                active += 1
                maximum = max(active, maximum)
                try:
                    if scratch == contexts[1] and fail:
                        raise RuntimeError('isolated second-context failure')
                    return {'records': [{'source': scratch.name}],
                        'runtime': {**self.runtime, 'transformers': importlib.metadata.version('transformers')}}
                finally:
                    active -= 1
            with patch.object(ctc, 'infer_records', side_effect=infer), \
                    patch('stemwise_engine.provenance.endpoint_producer_identity', return_value={'source': 'current'}):
                with self.assertRaisesRegex(RuntimeError, 'second-context'):
                    ctc._infer_many(root, contexts, 5)
                self.assertTrue((contexts[0]/'inference.json').is_file())
                fail = False
                calls.clear()
                ctc._infer_many(root, contexts, 5)
                self.assertEqual(calls, [str(index) for index in range(1, 17)])
                (contexts[-1]/'request.private.json').write_text('{"language":"fr"}', encoding='utf-8')
                calls.clear()
                ctc._infer_many(root, contexts, 5)
                self.assertEqual(calls, ['16'])
            self.assertEqual(maximum, 1)

    def _admit(self, root, declaration, mapping, evidence, lyrics):
        video = 'TESTVID0001'
        canonical = root / 'lyrics-alignment/canonical-plain' / (video + '.json')
        canonical.parent.mkdir(parents=True)
        canonical.write_text(json.dumps(lyrics), encoding='utf-8')
        declaration.update(videoId=video, canonicalPlainSha256=ctc.sha256_file(canonical))
        declaration['analysisAudio'].setdefault('sha256', 'a' * 64)
        targets = {row['performedIndex'] for row in declaration['occurrences']}
        for index, row in enumerate(evidence['occurrenceCandidates']):
            row.setdefault('publishDecision', {'result': 'unresolved', 'code': 'line-end-disagreement'}
                if index in targets else {'result': 'line-only'})
        evidence_root = canonical.parent.parent / 'jobs' / video
        admission, _ = retained_content(evidence_root, lyrics['tracks'][0],
            tuple(row['sourcePhysicalIndex'] for row in mapping['performedOccurrences']),
            declaration['canonicalPlainSha256'], declaration['analysisAudio']['sha256'])
        declaration['contentAdmission'] = admission.to_record()
        return canonical, evidence_root

    def _plan_with_content(self, declaration, mapping, evidence, lyrics):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            declaration, mapping, evidence, lyrics = copy.deepcopy((declaration, mapping, evidence, lyrics))
            _, evidence_root = self._admit(Path(name), declaration, mapping, evidence, lyrics)
            return ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=evidence_root)

    def test_combined_supervisor_uses_main_runtime_and_both_stage_budgets(self):
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            project = root / 'builder'
            prepare_runtime_layout(project)
            paths = [root / 'a', root / 'b']
            for path in paths:
                path.mkdir()
                (path / 'review.json').write_bytes(b'pending review')
            observed = []
            def launch(command, **kwargs):
                observed.append((command, kwargs))
                return SimpleNamespace(returncode=1, stderr='bounded private cause')
            with patch.object(ctc, 'run_process_tree', side_effect=launch):
                from stemwise_engine.worker_protocol import WorkerProtocolError
                with self.assertRaisesRegex(WorkerProtocolError, 'ctc-evidence-worker-failed') as raised:
                    ctc._invoke(project, 'prepare-infer-many', paths, 60, alignment=False)
            from stemwise_engine.common import WorkerRetirementError
            from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
            for disposition, error in (('cancelled', ProviderCancelled), ('parent-deadline', ProviderDeadline),
                                       ('retirement-failed', WorkerRetirementError)):
                marker = 'STEMWISE_WORKER_FAILURE='+json.dumps({'formatVersion': 1,
                    'code': 'nested-stop', 'disposition': disposition})
                with self.subTest(disposition=disposition), patch.object(ctc, 'run_process_tree',
                        return_value=SimpleNamespace(returncode=0, stdout='', stderr=marker)), self.assertRaises(error):
                    ctc._invoke(project, 'prepare-infer-many', paths, 60, alignment=False)
            command, options = observed[0]
            self.assertEqual(command[0], str(project / '.venv/bin/python'))
            self.assertEqual(command[1:3], ['-m', 'stemwise_engine.endpoint_ctc_evidence'])
            self.assertEqual(command[3:], ['prepare-infer-many', str(project), '60'])
            self.assertEqual(json.loads(options['input']), list(map(str, paths)))
            self.assertEqual(options['timeout'], 240)
            self.assertEqual(options['env']['CUBLAS_WORKSPACE_CONFIG'], ':4096:8')
            self.assertEqual((paths[0] / raised.exception.diagnostics[0]['file']).read_text(), 'bounded private cause')
            self.assertTrue(all((p / 'review.json').read_bytes() == b'pending review' for p in paths))

    def test_combined_worker_restores_torch_settings_and_preserves_recovery_on_failure(self):
        import torch
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        settings = lambda: (torch.backends.cudnn.benchmark, torch.backends.cudnn.deterministic,
            torch.are_deterministic_algorithms_enabled(), torch.is_deterministic_algorithms_warn_only_enabled())
        original = settings()
        for failure in (None, 'prepare', 'infer'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(dir=work) as name:
                root = Path(name)
                paths = [root / 'a', root / 'b']
                for path in paths:
                    path.mkdir()
                    (path / 'review.json').write_bytes(b'pending review')
                def prepare(project, scratches, *, local):
                    self.assertEqual((project, scratches, local), (root, paths, True))
                    torch.backends.cudnn.benchmark = not original[0]
                    torch.backends.cudnn.deterministic = not original[1]
                    torch.use_deterministic_algorithms(not original[2], warn_only=not original[3])
                    (paths[0] / 'prepared.json').write_bytes(b'completed separation')
                    if failure == 'prepare':
                        raise RuntimeError('preparation failed')
                def infer(project, scratches, timeout):
                    self.assertEqual((project, scratches, timeout), (root, paths, 7))
                    self.assertEqual(settings(), original)
                    (paths[0] / 'inference.json').write_bytes(b'fresh numeric result')
                    if failure == 'infer':
                        raise RuntimeError('inference failed')
                with patch.object(ctc, '_prepare_many', side_effect=prepare), patch.object(
                        ctc, '_infer_many', side_effect=infer) as inference:
                    if failure:
                        with self.assertRaisesRegex(RuntimeError, failure + '.*failed' if failure == 'infer' else 'preparation failed'):
                            ctc._prepare_infer_many(root, paths, 7)
                    else:
                        ctc._prepare_infer_many(root, paths, 7)
                    self.assertEqual(inference.call_count, 0 if failure == 'prepare' else 1)
                self.assertEqual(settings(), original)
                self.assertEqual((paths[0] / 'prepared.json').read_bytes(), b'completed separation')
                self.assertTrue(all((p / 'review.json').read_bytes() == b'pending review' for p in paths))
                self.assertFalse((root / 'manifest.json').exists())
        for paths, timeout in (([], 1), ([root, root], 1), ([root], 0), ([root], True)):
            with self.assertRaises(ValueError):
                ctc._prepare_infer_many(root, paths, timeout)

    def test_local_preparation_refreshes_retained_models_and_revalidates_every_separator(self):
        import stemwise_engine.endpoint_vocal_evidence as vocal
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            paths = [root / 'retained', root / 'new']
            for path in paths:
                path.mkdir()
                (path / 'request.private.json').write_text('{"language":"en"}')
            views = {r: {'path': r + '.wav', 'provenance': {'sha256': r}} for r in ROUTES}
            (paths[0] / 'prepared.json').write_text(json.dumps({'modelPath': 'old', 'model': {}, 'views': views}))
            outputs = [{r: (path / v['path'], v['provenance']) for r, v in views.items()} for path in paths]
            selector = SimpleNamespace(_wav2vec2_model_assets=lambda *_: (root / 'current', {'snapshot': 'current'}))
            with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': selector}), patch.object(
                    ctc, 'load_config', return_value={'analysisTimeoutMinutes': 2}) as config, patch.object(
                    vocal, '_separate_many_local', return_value=outputs) as local, patch.object(
                    vocal, '_separate_many', side_effect=AssertionError('must not launch another process')):
                ctc._prepare_many(root, paths, local=True)
            local.assert_called_once_with(config.return_value, [(path / 'source.wav', path) for path in paths], 120)
            for path in paths:
                self.assertEqual(json.loads((path / 'prepared.json').read_text()), {
                    'modelPath': str(root / 'current'), 'model': {'snapshot': 'current'}, 'views': views})

    def test_unencodable_target_returns_capability_before_acoustic_inference(self):
        from test_wav2vec2_forced_alignment import FakePhonemeTokenizer
        tokenizer = FakePhonemeTokenizer({"word": "ts\u02b0 |"})
        tokenizer.vocabulary.update({"t": 6, "s": 7})
        request = {"lines": [{"lineId": "line", "sourcePhysicalIndex": 0,
                    "words": [{"wordId": "word", "acousticText": "word"}]}]}
        with tempfile.TemporaryDirectory() as temp:
            scratch = Path(temp)
            (scratch / "tokenizer_config.json").write_text(json.dumps({
                "tokenizer_class": "Wav2Vec2PhonemeCTCTokenizer"}))
            (scratch / "request.private.json").write_text(json.dumps({
                "language": "hy", "targetLine": 0, "request": request}))
            self._prepared_context(scratch, request=json.loads((scratch/'request.private.json').read_bytes()),
                model={"phonemizerLanguage": "hy", "targetKind": "phoneme"})
            with patch("transformers.Wav2Vec2PhonemeCTCTokenizer.from_pretrained", return_value=tokenizer), \
                 patch("transformers.AutoModelForCTC.from_pretrained", side_effect=AssertionError("model must not load")) as model, \
                 patch.object(ctc, "_vocal_samples", side_effect=AssertionError("audio must not open")) as audio:
                ctc._infer(scratch, scratch)
            self.assertEqual(json.loads((scratch / "inference.json").read_bytes()), {
                "formatVersion": 1, "engine": "wav2vec2-target-capability", "supported": False,
                "requestSha256": hashlib.sha256(json.dumps(request, ensure_ascii=False,
                    sort_keys=True, separators=(",", ":")).encode()).hexdigest()})
            model.assert_not_called()
            audio.assert_not_called()

    def test_unsupported_capability_blocks_without_changing_pending_review(self):
        import soundfile as sf
        for fault in (None, "stale", "version", "supported", "numeric-supported", "extra", "runtime", "skip"):
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                declaration, mapping, evidence, lyrics = self._context_fixture()
                declaration["analysisAudio"]["durationMs"] //= 50
                for occurrence in evidence["occurrenceCandidates"]:
                    for candidate in occurrence["familyCandidates"].values():
                        for boundary in ("startMs", "endMs"):
                            candidate[boundary] //= 50
                declaration["occurrences"] = [{"performedIndex": 2, "occurrenceId": "2",
                    "candidateFamilies": evidence["occurrenceCandidates"][2]["familyCandidates"]}]
                canonical = root / "canonical.json"
                canonical.write_text(json.dumps(lyrics))
                declaration["canonicalPlainSha256"] = ctc.sha256_file(canonical)
                audio = root / "source.wav"
                sf.write(audio, np.zeros((declaration["analysisAudio"]["durationMs"] * 48, 2)),
                         48000, subtype="FLOAT")
                declaration['analysisAudio']['sha256'] = ctc.sha256_file(audio)
                canonical, _ = self._admit(root, declaration, mapping, evidence, lyrics)
                attempt = root / "endpoint-review/attempt"
                attempt.mkdir(parents=True)
                review = attempt / "review.json"
                original = b'{"outcome":"pending","occurrences":[]}'
                review.write_bytes(original)
                before = copy.deepcopy((declaration, mapping, evidence))
                def invoke(_project, mode, scratch, _timeout, **_kwargs):
                    if mode == 'prepare-infer-many':
                        for path in scratch:
                            self.assertFalse((path / 'inference.json').exists())
                            invoke(_project, 'prepare', path, _timeout, **_kwargs)
                            (path / 'inference.json').unlink()
                        for path in scratch:
                            invoke(_project, 'infer', path, _timeout, **_kwargs)
                        return
                    if mode in ('infer-many', 'prepare-many'):
                        for path in scratch:
                            invoke(_project, 'infer' if mode == 'infer-many' else 'prepare', path, _timeout, **_kwargs)
                        return
                    if mode == "prepare":
                        (scratch / "prepared.json").write_text("retained vocal views")
                        (scratch / 'inference.json').write_text('{"stale":"result"}')
                        return
                    if fault == 'skip':
                        return
                    if fault == "runtime":
                        raise RuntimeError("synthetic acoustic worker failure")
                    request = json.loads((scratch / "request.private.json").read_bytes())["request"]
                    packet = {"formatVersion": 1, "engine": "wav2vec2-target-capability",
                        "supported": False, "requestSha256": hashlib.sha256(json.dumps(request,
                            ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()}
                    if fault == "stale": packet["requestSha256"] = "0" * 64
                    elif fault == "version": packet["formatVersion"] = True
                    elif fault == "supported": packet["supported"] = True
                    elif fault == "numeric-supported": packet["supported"] = 0
                    elif fault == "extra": packet["records"] = []
                    (scratch / "inference.json").write_text(json.dumps(packet))
                with patch.object(ctc, "_invoke", side_effect=invoke):
                    args = (attempt, declaration, "declaration", audio,
                            {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1},
                            mapping, evidence, canonical)
                    if fault is None:
                        with self.assertRaises(LyricsContentBlocker) as blocked:
                            ctc.write_packet(*args)
                        self.assertEqual(blocked.exception.reasons, ('pronunciation-capability-unavailable',))
                        self.assertFalse((ctc._directory(attempt) / 'manifest.json').exists())
                    else:
                        with self.assertRaises((ValueError, RuntimeError, KeyError, FileNotFoundError)):
                            ctc.write_packet(*args)
                        self.assertFalse((ctc._directory(attempt) / "manifest.json").exists())
                self.assertEqual(review.read_bytes(), original)
                self.assertEqual((declaration, mapping, evidence), before)
                self.assertEqual((ctc._directory(attempt) / "context-000/prepared.json").read_text(),
                                 "retained vocal views")

    def test_stored_ctc_packet_requires_the_current_scorer_version(self):
        work = Path(__file__).resolve().parents[3]/'Test suite/work'
        for version in (None, True, 5, 6, 7, 8, 9, 10):
            with self.subTest(version=version), tempfile.TemporaryDirectory(dir=work) as name:
                attempt = Path(name)/'endpoint-review/one'
                directory = ctc._directory(attempt)
                directory.mkdir(parents=True)
                path = directory/'manifest.json'
                manifest = {'method': ctc.METHOD, 'reviewRequired': True,
                            'scorerVersion': version, 'occurrences': [{'occurrenceId': 'one'}]}
                path.write_text(json.dumps(manifest), encoding='utf-8')
                before = path.read_bytes()
                binding = {'method': ctc.METHOD, 'manifestSha256': ctc.sha256_file(path)}
                if type(version) is int and version == 10:
                    self.assertEqual(ctc.verify_stored_packet(attempt, binding), manifest)
                else:
                    with self.assertRaisesRegex(ValueError, 'invalid CTC manifest'):
                        ctc.verify_stored_packet(attempt, binding)
                self.assertEqual(path.read_bytes(), before)

    def test_multilingual_context_uses_only_confirmed_local_language(self):
        from stemwise_engine.lyrics_tokenization import display_words
        text = "نور صباح\nذكر جميل\nسلام عليكم\nsawubona inkosi\nصباح جديد"
        original = {"kind": "original", "id": "original", "language": "mul",
                    "componentLanguages": ["ar", "zu"], "text": text, "format": "plain"}
        lines = text.splitlines()
        mapping = {"performedOccurrences": [{"occurrenceId": str(i), "sourcePhysicalIndex": i,
            "words": [{"occurrenceWordId": f"{i}-{j}"} for j, _ in enumerate(display_words(line))]}
            for i, line in enumerate(lines)]}
        families = [{"mms-fa": {"startMs": 1000 + i * 4000, "endMs": 2000 + i * 4000}}
                    for i in range(5)]
        declaration = {"analysisAudio": {"durationMs": 22000}, "occurrences": [
            {"performedIndex": i, "occurrenceId": str(i), "candidateFamilies": families[i]}
            for i in (1, 3)]}
        evidence = {"occurrenceCandidates": [{"familyCandidates": f} for f in families]}
        lyrics = {"tracks": [original]}
        before = json.dumps((lyrics, mapping, evidence, declaration))
        plans = self._plan_with_content(declaration, mapping, evidence, lyrics)
        self.assertEqual(plans[0]["language"], "ar")
        self.assertNotIn("unsupported", plans[0])
        self.assertEqual([word["acousticLanguage"] for line in plans[0]["request"]["lines"]
                          for word in line["words"]], ["ar"] * 6)
        self.assertEqual(plans[1]["language"], "mul")
        self.assertEqual(plans[1]["unsupported"], "mixed-language-context-needs-supported-word-bindings")
        self.assertEqual(json.dumps((lyrics, mapping, evidence, declaration)), before)
        retained = plans[0]
        for fault in (None, "word", "candidate", "language"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temp:
                scratch = Path(temp)
                request = scratch / "request.private.json"
                request.write_text(json.dumps(retained))
                before_request = request.read_bytes()
                (scratch / "prepared.json").write_text("retained vocal views")
                changed = copy.deepcopy(plans[0])
                if fault == "word": changed["request"]["lines"][0]["words"][0]["acousticText"] = "wrong"
                elif fault == "candidate": changed["candidateFamilies"]["mms-fa"]["endMs"] += 1
                elif fault == "language": changed["request"]["lines"][0]["words"][0]["acousticLanguage"] = "fa"
                if fault:
                    with self.assertRaises(ValueError): ctc._write_context_request(scratch, changed)
                    self.assertEqual(request.read_bytes(), before_request)
                else:
                    ctc._write_context_request(scratch, changed)
                    self.assertEqual(json.loads(request.read_text()), plans[0])
                self.assertEqual((scratch / "prepared.json").read_text(), "retained vocal views")
        for components in (["ar", "ku"], ["fa", "zu"]):
            with self.subTest(components=components):
                original["componentLanguages"] = components
                ambiguous_before = json.dumps((lyrics, mapping, evidence, declaration))
                with patch.object(ctc, '_plan', wraps=ctc._plan) as planner, \
                        self.assertRaises(LyricsContentBlocker) as caught:
                    self._plan_with_content(declaration, mapping, evidence, lyrics)
                self.assertEqual(caught.exception.reasons, ('pronunciation-support-unavailable',))
                planner.assert_not_called()
                self.assertEqual(json.dumps((lyrics, mapping, evidence, declaration)), ambiguous_before)

    def test_rebound_language_tag_variants_keep_exact_metadata_and_route_current_contexts(self):
        from stemwise_engine.endpoint_owsm_ctc import _plans
        from stemwise_engine.lyrics_tokenization import display_words
        hashes = set()
        for language in ('mul', 'MUL', 'mul-Latn', 'en', 'EN', 'en-US'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                multilingual = language in {'mul', 'MUL', 'mul-Latn'}
                text = ('نور صباح\nذكر جميل\nsawubona inkosi' if multilingual else
                        'hello friend\nmorning light\ngood evening')
                original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                    'language': language, 'text': text}
                if multilingual:
                    original['componentLanguages'] = ['ar', 'zu']
                lyrics = {'tracks': [original]}
                mapping = {'performedOccurrences': [{'occurrenceId': str(i), 'sourcePhysicalIndex': i,
                    'words': [{'occurrenceWordId': f'{i}-{j}'} for j, _ in enumerate(display_words(line))]}
                    for i, line in enumerate(text.splitlines())]}
                families = [{'mms-fa': {'startMs': 1000+i*4000, 'endMs': 2000+i*4000}} for i in range(3)]
                declaration = {'analysisAudio': {'durationMs': 12000}, 'occurrences': [
                    {'performedIndex': 0, 'occurrenceId': '0', 'candidateFamilies': families[0]}]}
                evidence = {'occurrenceCandidates': [{'occurrenceId': str(index),
                    'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'},
                    'familyCandidates': family} for index, family in enumerate(families)]}
                canonical, _ = self._admit(Path(name), declaration, mapping, evidence, lyrics)
                hashes.add(declaration['canonicalPlainSha256'])
                before = copy.deepcopy((lyrics, mapping, evidence, declaration)), canonical.read_bytes()
                plans, routes = _plans(declaration, canonical, mapping, evidence)
                self.assertEqual(plans[0]['language'], 'ar' if multilingual else language)
                self.assertEqual(routes, [['ar' if multilingual else 'en']])
                self.assertEqual(((lyrics, mapping, evidence, declaration), canonical.read_bytes()), before)
        self.assertEqual(len(hashes), 6)

    def test_stereo_selection_uses_transcript_likelihood_not_energy(self):
        # Substitute acoustic emissions, not the CTC score or selection.
        high = np.log(np.array([[.98, .01, .01], [.01, .98, .01],
                                [.01, .01, .98], [.98, .01, .01]]))
        low = np.log(np.tile([.98, .01, .01], (4, 1)))
        x = np.arange(400, dtype=np.float64) / 400
        samples = np.column_stack((10*x, x))
        before = samples.copy()
        expected_views = {"left": 10*x, "right": x, "mid": 5.5*x, "side": 4.5*x}
        for expected in ("right", "left", "mid", "side"):
            seen = []
            def infer(signal):
                name = next(k for k, v in expected_views.items()
                            if np.allclose(signal, v[160:-160]))
                seen.append(name)
                return high if name == expected else low
            name, signal, logp, scores = ctc._select_acoustic_representation(
                samples, [1, 2], 0, infer)
            self.assertEqual(name, expected)
            self.assertEqual(seen, ["left", "right", "mid", "side"])
            np.testing.assert_allclose(signal, expected_views[expected], rtol=1e-15, atol=1e-15)
            np.testing.assert_array_equal(logp, high)
            self.assertGreater(scores[expected], max(v for k, v in scores.items() if k != expected))
        np.testing.assert_array_equal(samples, before)
        calls = []
        name, _, _, scores = ctc._select_acoustic_representation(
            samples[:, :1], [1, 2], 0, lambda signal: calls.append(signal) or high)
        self.assertEqual(name, "mono")
        self.assertEqual(len(calls), 1)
        self.assertEqual(set(scores), {"mono"})

    def test_inference_locks_selected_representation_for_all_perturbations(self):
        import torch
        x = np.arange(2000, dtype=np.float64) / 2000
        samples = np.column_stack((10*x, x))
        expected = [10*x[160:-160], x[160:-160], 5.5*x[160:-160], 4.5*x[160:-160],
                    .9*x[80:-160], 1.1*x[240:-160]]
        high = np.full((4, 5), .01)
        high[np.arange(4), [0, 1, 2, 0]] = .96
        low = np.tile([.96, .01, .01, .01, .01], (4, 1))
        calls = []
        def emitted(waveform, *_):
            signal = waveform[0].numpy()
            call = len(calls) % 6
            np.testing.assert_allclose(signal, expected[call], rtol=1e-6, atol=1e-7)
            calls.append(call)
            return torch.from_numpy(np.log(high if call in (1, 4, 5) else low))
        tokenizer = SimpleNamespace(encoder={"<pad>": 0, "H": 1, "I": 2, "|": 3, "<unk>": 4},
            pad_token_id=0, unk_token_id=4, all_special_ids=[0, 4], word_delimiter_token_id=3)
        tokenizer.get_vocab = lambda: tokenizer.encoder
        model = SimpleNamespace(config=SimpleNamespace(pad_token_id=0, conv_kernel=[400], conv_stride=[320]))
        model.to = lambda *_: model
        model.eval = lambda: model
        with tempfile.TemporaryDirectory() as temp:
            scratch = Path(temp)
            plan = {"language": "en", "startMs": 0, "endMs": 125, "targetLine": 0,
                "request": {"lines": [{"lineId": "line", "sourcePhysicalIndex": 0,
                    "words": [{"wordId": "word", "acousticText": "Hi"}]}]},
                "candidateFamilies": {"candidate": {"startMs": 32, "endMs": 72}}}
            (scratch / "request.private.json").write_text(json.dumps(plan))
            (scratch / "prepared.json").write_text(json.dumps({"views": {
                r: {"path": f"{i}.wav", "provenance": {}} for i, r in enumerate(ROUTES)}}))
            with patch("transformers.Wav2Vec2CTCTokenizer.from_pretrained", return_value=tokenizer), patch(
                "transformers.Wav2Vec2FeatureExtractor.from_pretrained", return_value=SimpleNamespace(sampling_rate=16000)
            ), patch("transformers.AutoModelForCTC.from_pretrained", return_value=model) as loader, patch.object(
                ctc, "_vocal_samples", return_value=samples
            ), patch.object(ctc, "_bounded_emissions", side_effect=emitted):
                cache = {}
                assets = (scratch, {"language": "en", "targetKind": "grapheme"})
                result = ctc.infer_records(scratch, assets, model_cache=cache)
                again = ctc.infer_records(scratch, assets, model_cache=cache)
                self.assertEqual(loader.call_count, 1)
                self.assertEqual(result, again)
                switched = ctc.infer_records(scratch, (scratch, {**assets[1], 'modelSha256': 'changed'}), model_cache=cache)
                self.assertEqual(loader.call_count, 2)
                self.assertEqual(switched['records'], result['records'])
                self.assertEqual(switched['model']['modelSha256'], 'changed')
                self.assertNotIn('modelSha256', result['model'])
                self.assertEqual(len(cache), 1)
        self.assertEqual(calls, list(range(6))*9)
        self.assertEqual(len(result["records"]), 9)
        self.assertEqual({r["representation"] for r in result["records"]}, {"right"})
        self.assertEqual({(r["route"], r["shiftMs"], r["gain"]) for r in result["records"]},
                         {(r, s, g) for r in ROUTES for s, g in ((0, 1.), (-5, .9), (5, 1.1))})
        self.assertEqual({v["selected"] for v in result["representations"].values()}, {"right"})

    def test_batch_groups_models_and_retains_per_context_results_on_failure(self):
        for failure in (False, True):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                paths = []
                for index, language in enumerate(('ar', 'en', 'ar')):
                    path = root / f'context-{index}'
                    path.mkdir()
                    (path / 'prepared.json').write_text(json.dumps({'modelPath': str(root / language),
                        'model': {'phonemizerLanguage': language}}))
                    (path / 'request.private.json').write_text(json.dumps({'language': language}))
                    paths.append(path)
                seen, caches = [], []
                def infer(_project, path, *, model_cache):
                    seen.append(path)
                    caches.append(model_cache)
                    if failure and path == paths[1]:
                        raise RuntimeError('failed last context')
                    (path / 'inference.json').write_text(json.dumps({'context': path.name}))
                with patch.object(ctc, '_infer', side_effect=infer):
                    if failure:
                        with self.assertRaisesRegex(RuntimeError, 'failed last context'):
                            ctc._infer_many(root, paths)
                    else:
                        ctc._infer_many(root, paths)
                self.assertEqual(seen, [paths[0], paths[2], paths[1]])
                self.assertTrue(all(cache is caches[0] for cache in caches))
                for path in paths:
                    if failure and path == paths[1]:
                        self.assertFalse((path / 'inference.json').exists())
                    else:
                        self.assertEqual(json.loads((path / 'inference.json').read_bytes()), {'context': path.name})

    def test_batch_deadline_rejects_the_context_and_restores_signal_handler(self):
        import signal
        with tempfile.TemporaryDirectory() as temp:
            scratch = Path(temp)
            (scratch / 'prepared.json').write_text(json.dumps({'modelPath': str(scratch), 'model': {}}))
            (scratch / 'request.private.json').write_text('{"language":"en"}')
            handler = signal.getsignal(signal.SIGALRM)
            def expired(*args, **kwargs):
                signal.raise_signal(signal.SIGALRM)
                self.fail('An expired context cannot return success')
            with patch.object(ctc, '_infer', side_effect=expired):
                with self.assertRaisesRegex(TimeoutError, 'context exceeded'):
                    ctc._infer_many(scratch, [scratch], timeout_seconds=1)
            self.assertIs(signal.getsignal(signal.SIGALRM), handler)
            self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0, 0))
            self.assertFalse((scratch / 'inference.json').exists())
            for contexts, deadline in (([], 1), ([scratch, scratch], 1), ([scratch], 0)):
                with self.assertRaises(ValueError):
                    ctc._infer_many(scratch, contexts, timeout_seconds=deadline)

    def _context_fixture(self):
        words = ["zero", "before", "target", "after", "extra", "outside"]
        lyrics = {"tracks": [{"kind": "original", "format": "plain", "language": "en", "text": "\n".join(words)}]}
        mapping = {"performedOccurrences": [
            {"occurrenceId": str(i), "sourcePhysicalIndex": i, "words": [{"occurrenceWordId": f"w{i}"}]}
            for i in range(6)]}
        spans = [((9000, 9500), (9000, 9600)), ((10000, 20000), (10000, 20000)),
                 ((21000, 25000), (21000, 25000)), ((26000, 30000), (45000, 50000)),
                 ((31000, 34000), (36000, 38000)), ((37000, 39000), (52000, 53000))]
        evidence = {"occurrenceCandidates": [{"familyCandidates": {
            family: {"startMs": start, "endMs": end}
            for family, (start, end) in zip(("mms-fa", "language-wav2vec2"), pair)}} for pair in spans]}
        declaration = {"analysisAudio": {"durationMs": 60000}, "occurrences": [
            {"performedIndex": 2, "occurrenceId": "2", "candidateFamilies": evidence["occurrenceCandidates"][2]["familyCandidates"]}]}
        return declaration, mapping, evidence, lyrics

    def test_context_includes_other_performed_lines_inside_the_audio_crop(self):
        declaration, mapping, evidence, lyrics = self._context_fixture()
        plan = self._plan_with_content(declaration, mapping, evidence, lyrics)[0]
        self.assertEqual((plan["startMs"], plan["endMs"]), (8500, 51500))
        self.assertEqual([line["lineId"] for line in plan["request"]["lines"]], ["0", "1", "2", "3", "4"])
        self.assertEqual(plan["targetLine"], 2)
        self.assertEqual(plan["candidateFamilies"], declaration["occurrences"][0]["candidateFamilies"])


    def test_context_expansion_reuses_audio_but_rejects_changed_binding_or_words(self):
        lines = [{"lineId": str(i), "words": [{"wordId": f"w{i}", "acousticText": str(i)}]} for i in range(5)]
        original = {"startMs": 100, "endMs": 500, "language": "en", "occurrenceId": "2",
                    "candidateFamilies": {"a": {"startMs": 200, "endMs": 300}},
                    "targetLine": 1, "request": {"lines": lines[1:4]}}
        expanded = {**original, "targetLine": 2, "request": {"lines": lines}}
        for fault in (None, "crop", "words", "candidates", "order", "target"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temp:
                scratch = Path(temp)
                request = scratch / "request.private.json"
                request.write_text(json.dumps(original))
                before = request.read_bytes()
                (scratch / "prepared.json").write_text("retained vocal views")
                changed = copy.deepcopy(expanded)
                if fault == "crop": changed["endMs"] += 1
                elif fault == "words": changed["request"]["lines"][2]["words"][0]["acousticText"] = "wrong"
                elif fault == "candidates": changed["candidateFamilies"]["a"]["endMs"] += 1
                elif fault == "order": changed["request"]["lines"][1:4] = reversed(changed["request"]["lines"][1:4])
                elif fault == "target": changed["targetLine"] = 3
                if fault:
                    with self.assertRaises(ValueError):
                        ctc._write_context_request(scratch, changed)
                    self.assertEqual(request.read_bytes(), before)
                else:
                    ctc._write_context_request(scratch, changed)
                    self.assertEqual(json.loads(request.read_text()), expanded)
                self.assertEqual((scratch / "prepared.json").read_text(), "retained vocal views")

    def test_native_english_targets_preserve_uppercase_model_token_ids(self):
        # The English snapshot has no tokenizer_class and has uppercase letters.
        # Keep approved words unchanged and map their normalized form to those IDs.
        from stemwise_engine.wav2vec2_forced_alignment import _target_plan
        vocabulary = {"<pad>": 0, "<s>": 1, "</s>": 2, "<unk>": 3, "|": 4,
                      "H": 5, "I": 6, "'": 7, "M": 8}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for filename, value in {
                "config.json": {"model_type": "wav2vec2"},
                "tokenizer_config.json": {"do_lower_case": False},
                "vocab.json": vocabulary,
                "preprocessor_config.json": {"feature_size": 1, "sampling_rate": 16000, "padding_value": 0.},
                "request.private.json": {"language": "en", "targetLine": 0, "request": {"lines": [
                    {"lineId": "line", "sourcePhysicalIndex": 0, "words": [
                        {"wordId": "one", "acousticText": "Hi"}, {"wordId": "two", "acousticText": "I'm"}]}]}},
                "prepared.json": {},
            }.items():
                (root / filename).write_text(json.dumps(value))
            observed = []
            def target(*args, **kwargs):
                result = _target_plan(*args, **kwargs)
                observed.append(result[0])
                return result
            request_path = root/'request.private.json'
            for language in ('en', 'EN', 'en-US'):
                request = json.loads(request_path.read_text(encoding='utf-8'))
                request['language'] = language
                request_path.write_text(json.dumps(request), encoding='utf-8')
                before = request_path.read_bytes()
                observed.clear()
                with self.subTest(language=language), patch(
                    'stemwise_engine.wav2vec2_forced_alignment._target_plan', side_effect=target), patch(
                    "transformers.AutoModelForCTC.from_pretrained", side_effect=RuntimeError("stop before inference")
                ):
                    with self.assertRaisesRegex(RuntimeError, "stop before inference"):
                        ctc.infer_records(root, (root, {"language": "en", "targetKind": "grapheme"}))
                self.assertEqual(observed, [[5, 6, 4, 6, 7, 8]])
                self.assertEqual(request_path.read_bytes(), before)
            vocabulary["h"] = 9
            (root / "vocab.json").write_text(json.dumps(vocabulary))
            with self.assertRaisesRegex(ValueError, "conflicting case variants"):
                ctc.infer_records(root, (root, {"language": "en", "targetKind": "grapheme"}))

    def test_long_context_keeps_all_candidates_and_bounded_contiguous_emissions(self):
        import torch
        import stemwise_engine.wav2vec2_forced_alignment as acoustic
        lyrics = {"tracks": [{"kind": "original", "format": "plain", "language": "en", "text": "before\ntarget\nafter"}]}
        mapping = {"performedOccurrences": [
            {"occurrenceId": str(i), "sourcePhysicalIndex": i, "words": [{"occurrenceWordId": f"w{i}"}]}
            for i in range(3)
        ]}
        families = {"near": {"startMs": 78420, "endMs": 81580},
                    "far": {"startMs": 78432, "endMs": 112475}}
        declaration = {"analysisAudio": {"durationMs": 200000}, "occurrences": [
            {"occurrenceId": "1", "performedIndex": 1, "candidateFamilies": families}]}
        evidence = {"occurrenceCandidates": [
            {"familyCandidates": {"near": {"startMs": 70000, "endMs": 75000}}},
            {"familyCandidates": families},
            {"familyCandidates": {"near": {"startMs": 114000, "endMs": 118000}}},
        ]}
        plan = self._plan_with_content(declaration, mapping, evidence, lyrics)[0]
        self.assertNotIn("unsupported", plan)
        self.assertEqual((plan["startMs"], plan["endMs"]), (68500, 119500))
        self.assertEqual(plan["candidateFamilies"], families)
        self.assertEqual([v["lineId"] for v in plan["request"]["lines"]], ["0", "1", "2"])
        calls = []
        def model_window(wave, start, end, *_):
            calls.append((start, end))
            # Actual 400-sample receptive field and 320-sample convolution stride.
            return torch.arange(start // 320, (end - 400) // 320 + 1)[:, None].float()
        samples = 65013 * 16
        with patch.object(acoustic, "_infer_window", side_effect=model_window):
            emitted = ctc._bounded_emissions(torch.zeros((1, samples)), None, SimpleNamespace(config=SimpleNamespace(vocab_size=1)), None, None, 320)
        self.assertEqual(calls, [(0, 480000), (320000, 800000), (640000, samples)])
        np.testing.assert_array_equal(emitted[:, 0], np.arange((samples-400)//320+1))
        mass = np.zeros(len(emitted)+1)
        mass[1750] = 1
        decision = select_candidate(mass, [35000, 35040], origin_ms=0, step_ms=20)
        self.assertEqual(decision["candidateMs"], 35000)

    def test_retry_refreshes_model_through_separator_runtime_admission(self):
        with tempfile.TemporaryDirectory() as temp:
            scratch = Path(temp)
            (scratch / "request.private.json").write_text(json.dumps({"language": "ar"}))
            views = {route: {"path": route + ".wav", "provenance": {"sha256": route}} for route in ROUTES}
            prepared = scratch / "prepared.json"
            prepared.write_text(json.dumps({"modelPath": "old-model", "model": {"snapshot": "old"}, "views": views}))
            selector = SimpleNamespace(_wav2vec2_model_assets=lambda config, language: (scratch / "current-model", {"snapshot": "current"}))
            outputs = {r: (scratch / v['path'], v['provenance']) for r, v in views.items()}
            with patch.dict(sys.modules, {'stemwise_engine.lyrics_align_pc': selector}), patch.object(ctc, "load_config", return_value={}), patch('stemwise_engine.endpoint_vocal_evidence._separate', return_value=outputs) as separate:
                ctc._prepare(scratch, scratch)
                refreshed = json.loads(prepared.read_text())
                self.assertEqual(refreshed["modelPath"], str(scratch / "current-model"))
                self.assertEqual(refreshed["model"], {"snapshot": "current"})
                self.assertEqual(refreshed["views"], views)
                separate.assert_called_once()
                refreshed["views"].pop(ROUTES[-1])
                prepared.write_text(json.dumps(refreshed))
                ctc._prepare(scratch, scratch)
                self.assertEqual(json.loads(prepared.read_text())['views'], views)

    def test_small_result_survives_scratch_cleanup_without_replaying_audio(self):
        with tempfile.TemporaryDirectory() as temp:
            attempt = Path(temp) / "endpoint-review" / "one"
            attempt.mkdir(parents=True)
            review = attempt / "review.json"
            review.write_text("pending")
            directory = ctc._directory(attempt)
            scratch = directory / "context-000"
            scratch.mkdir(parents=True)
            (scratch / "source.wav").write_bytes(b"disposable")
            for name in ('context-999', 'context-1000'):
                extra = directory/name
                extra.mkdir()
                (extra/'source.wav').write_bytes(b'disposable')
            unrelated = directory/'context-1000-notes'
            unrelated.mkdir()
            (unrelated/'notes').write_bytes(b'preserved')
            families = {"mms-fa": {"startMs": 80, "endMs": 160,
                "words": [{"startMs": 80, "endMs": 160, "confidence": .9, "acousticTokenCount": 1}]}}
            decisions = {b: {"result": "supported", "candidateMs": time}
                         for b, time in (("startMs", 80), ("endMs", 160))}
            records = [{"route": r, "shiftMs": s, "decisions": decisions} for r in ROUTES for s in (-5, 0, 5)]
            declaration = {"analysisAudio": {"sha256": "source"}, "canonicalPlainSha256": "text",
                           "occurrences": [{"occurrenceId": "one", "candidateFamilies": families}]}
            manifest = {**declaration, "method": ctc.METHOD, "scorerVersion": ctc.SCORER_VERSION, "reviewRequired": True, "declarationSha256": "declaration",
                        "occurrences": [{"occurrenceId": "one", "inference": {"records": records},
                                         "assessment": _assess(records, families)}]}
            path = directory / "manifest.json"
            path.write_text(json.dumps(manifest))
            binding = {"method": ctc.METHOD, "manifestSha256": ctc.sha256_file(path)}
            holding = Path(temp) / "Pending deletion"
            ctc._clean_scratch(directory, Path(temp))
            self.assertFalse(scratch.exists())
            self.assertFalse(holding.exists())
            self.assertFalse(list(Path(temp).glob('ctc-*')), 'cleanup must not relocate disposable audio')
            self.assertEqual({p.name for p in directory.iterdir()}, {'manifest.json', 'context-1000-notes'})
            self.assertEqual((unrelated/'notes').read_bytes(), b'preserved')
            self.assertEqual(review.read_text(), "pending")
            self.assertEqual(ctc.verify_packet(attempt, declaration, "declaration", Path("unused.wav"), binding), manifest)
            with self.assertRaisesRegex(ValueError, "bindings are stale"):
                ctc.verify_packet(attempt, declaration, "different-song", Path("unused.wav"), binding)

    def test_forward_backward_matches_exhaustive_ctc_paths(self):
        rng = np.random.default_rng(91)
        p = rng.uniform(.1, 1, (5, 3))
        p /= p.sum(axis=1, keepdims=True)
        for targets in ([1], [1, 2], [1, 1], [1, 2, 1]):
            for first, last in ((0, len(targets)-1), (len(targets)-1, len(targets)-1)):
                expected_start, expected_end = np.zeros(6), np.zeros(6)
                total = 0
                for path in itertools.product(range(3), repeat=5):
                    runs, start = [], 0
                    for token, group in itertools.groupby(path):
                        end = start + len(list(group))
                        if token != 0:
                            runs.append((token, start, end))
                        start = end
                    if [r[0] for r in runs] != targets:
                        continue
                    weight = float(np.prod([p[t, token] for t, token in enumerate(path)]))
                    total += weight
                    expected_start[runs[first][1]] += weight
                    expected_end[runs[last][2]] += weight
                actual_start, actual_end = boundary_distributions(np.log(p), targets, 0, first, last)
                np.testing.assert_allclose(actual_start, expected_start/total, atol=1e-12)
                np.testing.assert_allclose(actual_end, expected_end/total, atol=1e-12)
                self.assertAlmostEqual(actual_start.sum(), 1)
                self.assertAlmostEqual(actual_end.sum(), 1)
        with self.assertRaises(ValueError):
            boundary_distributions(np.log(p[:1]), [1, 1], 0, 0, 1)

    def test_untranscribed_edge_audio_does_not_capture_lexical_boundaries(self):
        blank = [.97, .01, .01, .01]
        first = [.01, .97, .01, .01]
        final = [.01, .01, .97, .01]
        # An unrelated acoustic class dominates; the final target is merely
        # likelier than blank, which must not stretch it through the outro.
        tail = [.01, .001, .1, .889]
        head = [.01, .1, .001, .889]
        for frames, expected in (
            ([blank, first, final] + [tail] * 5, (1, 3)),
            ([head] * 5 + [first, final, blank], (5, 7)),
            ([blank, first] + [final] * 4 + [tail] * 2, (1, 6)),
            ([blank, first, final], (1, 3)),
        ):
            with self.subTest(expected=expected):
                logp = np.log(np.array(frames))
                original = logp.copy()
                onset, offset = boundary_distributions(
                    logp, [1, 2], 0, 0, 1, allow_edge_audio=True)
                self.assertEqual((int(onset.argmax()), int(offset.argmax())), expected)
                self.assertAlmostEqual(onset.sum(), 1)
                self.assertAlmostEqual(offset.sum(), 1)
                np.testing.assert_array_equal(logp, original)

    def test_candidate_selection_never_invents_subframe_precision(self):
        mass = np.array([.01, .01, .01, .01, .9, .02, .02, .02])
        result = select_candidate(mass, [80, 102], origin_ms=0, step_ms=20)
        self.assertEqual(result["candidateMs"], 80)
        self.assertEqual(select_candidate(mass, [80, 82], origin_ms=0, step_ms=20)["reason"], "candidates-share-acoustic-frame")
        self.assertEqual(select_candidate(mass, [20, 40], origin_ms=0, step_ms=20)["result"], "abstain")
        with self.assertRaises(ValueError):
            select_candidate(mass, [-200, 40], origin_ms=0, step_ms=20)

    def test_every_view_and_perturbation_must_support_the_family(self):
        families = {"mms-fa": {"startMs": 80, "endMs": 160,
            "words": [{"startMs": 80, "endMs": 160, "confidence": .9, "acousticTokenCount": 1}]},
            "owsm-ctc": {"startMs": 102, "endMs": 182}}
        records = [{"route": r, "shiftMs": s, "decisions": {
            boundary: {"result": "supported", "candidateMs": time}
            for boundary, time in (("startMs", 80), ("endMs", 160))}}
                   for r in ROUTES for s in (-5, 0, 5)]
        assessment = _assess(records, families)
        manifest = {"occurrences": [{"occurrenceId": "one", "assessment": assessment}]}
        selection = [{"occurrenceId": "one", "startCandidateFamily": "mms-fa", "endCandidateFamily": "mms-fa"}]
        verify_selections(manifest, selection)
        records[-1]["decisions"]["endMs"] = {"result": "abstain", "candidateMs": None}
        manifest["occurrences"][0]["assessment"] = _assess(records, families)
        with self.assertRaises(ValueError):
            verify_selections(manifest, selection)

    def test_ctc_assessment_keeps_raw_boundary_roles_through_selection(self):
        families = {family: {'startMs': 1000, 'endMs': 2000,
            'words': [{'startMs': 1000, 'endMs': 1500, 'confidence': .01,
                       'acousticTokenCount': 1},
                      {'startMs': 1500, 'endMs': 2000, 'confidence': .9,
                       'acousticTokenCount': 1}]}
            for family in ('mms-fa', 'language-wav2vec2', 'owsm-ctc')}
        records = [{'route': route, 'shiftMs': shift, 'decisions': {
            boundary: {'result': 'supported', 'candidateMs': time}
            for boundary, time in (('startMs', 1000), ('endMs', 2000))}}
            for route in ROUTES for shift in (-5, 0, 5)]
        assessment = _assess(records, families)
        self.assertEqual(assessment['startMs']['supportedFamilies'], ['language-wav2vec2', 'owsm-ctc'])
        self.assertEqual(assessment['endMs']['supportedFamilies'], ['language-wav2vec2', 'mms-fa'])
        manifest = {'occurrences': [{'occurrenceId': 'one', 'assessment': assessment}]}
        for start, end in (('mms-fa', 'language-wav2vec2'), ('language-wav2vec2', 'owsm-ctc')):
            with self.assertRaises(ValueError):
                verify_selections(manifest, [{'occurrenceId': 'one',
                    'startCandidateFamily': start, 'endCandidateFamily': end}])
        only_lexical = _assess(records, {'owsm-ctc': families['owsm-ctc']})
        self.assertEqual(only_lexical['endMs']['result'], 'abstain')

    def test_candidates_outside_the_frame_grid_abstain_without_clipping(self):
        mass = np.array([.01, .01, .01, .01, .9, .02, .02, .02])
        original = mass.copy()
        for times, origin in (([0, 80], 12.46875), ([80, 200], 0), ([0], 12.46875)):
            with self.subTest(times=times, origin=origin):
                result = select_candidate(mass, times, origin_ms=origin, step_ms=20)
                self.assertEqual(result["result"], "abstain")
                self.assertEqual(result["reason"], "candidate-outside-acoustic-context")
                self.assertIsNone(result["candidateMs"])
                self.assertEqual(result["compatibleCandidatesMs"], [])
                self.assertEqual(result["candidateScores"], [])
                self.assertIsNone(result["peakMs"])
                self.assertIsNone(result["peakMass"])
                np.testing.assert_array_equal(mass, original)

    def test_outside_frame_abstention_cannot_become_a_candidate_prediction(self):
        from stemwise_engine.endpoint_candidate_prediction import assess as predict
        mass = np.array([.99, .0025, .0025, .0025, .0025])
        families = {"mms-fa": {"startMs": 0, "endMs": 0},
                    "language-wav2vec2": {"startMs": 80, "endMs": 80}}
        # This scalar frame-grid probe supplies the otherwise independently
        # admitted MMS boundary provenance; transport interval checks are separate.
        families['mms-fa']['words'] = [{'confidence': .9, 'acousticTokenCount': 1}]
        decision = select_candidate(mass, [0, 80], origin_ms=12.46875, step_ms=20)
        records = [{"route": route, "shiftMs": shift, "originMs": 12.46875,
                    "stepMs": 20,
                    "decisions": {boundary: dict(decision) for boundary in ("startMs", "endMs")}}
                   for route in ROUTES for shift in (-5, 0, 5)]
        leaked_peaks = copy.deepcopy(records)
        for record in leaked_peaks:
            for boundary in ("startMs", "endMs"):
                record["decisions"][boundary].update(peakMs=12.46875, peakMass=.99)
        self.assertTrue(all(value["result"] == "supported"
                            for value in predict(leaked_peaks, families).values()))
        for assess in (_assess, predict):
            self.assertTrue(all(value["result"] == "abstain"
                                for value in assess(records, families).values()))
        manifest = {"occurrences": [{"occurrenceId": "edge", "assessment": _assess(records, families)}]}
        with self.assertRaisesRegex(ValueError, "abstained"):
            verify_selections(manifest, [{"occurrenceId": "edge",
                                         "startCandidateFamily": "mms-fa", "endCandidateFamily": "mms-fa"}])

    def test_quantized_ties_do_not_veto_a_unique_consistent_choice(self):
        mass = np.array([.01, .01, .01, .01, .9, .02, .02, .02])
        families = {"a": {"startMs": 80, "endMs": 80}, "b": {"startMs": 95, "endMs": 95}}
        unique = select_candidate(mass, [80, 95], origin_ms=0, step_ms=20)
        tied = select_candidate(mass, [80, 95], origin_ms=6, step_ms=20)
        self.assertEqual(tied["compatibleCandidatesMs"], [80, 95])
        records = [{"route": r, "shiftMs": s, "decisions": {b: tied if s == -5 else unique for b in families["a"]}}
                   for r in ROUTES for s in (-5, 0, 5)]
        self.assertEqual(_assess(records, families)["endMs"]["candidateMs"], 80)
        for record in records:
            record["decisions"]["endMs"] = tied
        self.assertEqual(_assess(records, families)["endMs"]["result"], "abstain")
        # A tie away from the actual peak supplies no support at all.
        weak_tie = select_candidate(mass, [20, 22], origin_ms=0, step_ms=20)
        self.assertEqual(weak_tie["compatibleCandidatesMs"], [])
        records[0]["decisions"]["endMs"] = unique
        records[1]["decisions"]["endMs"] = weak_tie
        self.assertEqual(_assess(records, families)["endMs"]["result"], "abstain")
        records[1]["decisions"]["endMs"] = select_candidate(mass, [80, 95], origin_ms=15, step_ms=20)
        self.assertEqual(records[1]["decisions"]["endMs"]["candidateMs"], 95)
        self.assertEqual(_assess(records, families)["endMs"]["result"], "abstain")


if __name__ == "__main__":
    unittest.main()
