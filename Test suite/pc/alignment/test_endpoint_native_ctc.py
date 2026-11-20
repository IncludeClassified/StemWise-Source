import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace

import stemwise_engine.endpoint_native_ctc as native
import stemwise_engine.endpoint_ctc_evidence as ctc
import stemwise_engine.endpoint_candidate_prediction as prediction
import test_endpoint_content_admission as content_fixtures
from stemwise_engine.endpoint_vocal_evidence import ROUTES


def inputs():
    families = {"mms-fa": {"startMs": 1000, "endMs": 2180,
                    "words": [{"startMs": 1000, "endMs": 2180,
                               "confidence": .9, "acousticTokenCount": 1}]},
                "language-wav2vec2": {"startMs": 1040, "endMs": 2180},
                "owsm-ctc": {"startMs": 1020, "endMs": 1768},
                "nemo-nfa": {"startMs": 1010, "endMs": 2000}}
    records = []
    for route in ROUTES:
        for shift, gain in ((-5, .9), (0, 1.), (5, 1.1)):
            origin = 10 + shift
            scores = [{"candidateMs": time, "frameEdge": round((time-origin)/80),
                       "mass": .7 if time == 2000 else .001}
                      for time in sorted({v["endMs"] for v in families.values()})]
            records.append({"route": route, "shiftMs": shift, "gain": gain,
                "originMs": origin, "stepMs": 80, "decisions": {"endMs": {
                    "frameStepMs": 80, "peakMs": origin+25*80,
                    "peakMass": .7, "candidateScores": scores}}})
    return families, records


def save_previous(attempt, declaration, entries=None):
    attempt.mkdir(parents=True, exist_ok=True)
    (attempt/'declaration.json').write_text(json.dumps(declaration), encoding='utf-8')
    entries = entries if entries is not None else [{'occurrenceId': row['occurrenceId'],
        'assessment': ctc._assess([], row['candidateFamilies'])} for row in declaration['occurrences']]
    manifest = {**declaration, 'method': ctc.METHOD, 'scorerVersion': ctc.SCORER_VERSION,
        'reviewRequired': True, 'declarationSha256': native.sha256_file(attempt/'declaration.json'), 'occurrences': entries}
    directory = ctc._directory(attempt)
    directory.mkdir(parents=True, exist_ok=True)
    (directory/'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
    return ctc._summary(directory, manifest)


class NativeCtcTests(unittest.TestCase):
    def test_native_assessment_cannot_count_lexical_ends_or_weak_mms_start(self):
        families, records = inputs()
        families['mms-fa']['words'] = [{'startMs': 1000, 'endMs': 2180,
            'confidence': .01, 'acousticTokenCount': 1}]
        rejected = native.assess(records, families, 'line-end-disagreement')
        self.assertEqual(rejected['startMs']['result'], 'abstain')
        self.assertEqual(rejected['endMs']['result'], 'abstain')
        families['mms-fa']['words'][0]['confidence'] = .9
        accepted = native.assess(records, families, 'line-end-disagreement')
        self.assertEqual(accepted['startMs']['supportedFamilies'], ['mms-fa'])
        self.assertEqual(accepted['endMs']['supportedFamilies'], ['nemo-nfa'])

    def test_mixed_packet_preserves_bound_support_through_new_partial_complete_and_cached_results(self):
        families, records = inputs()
        for resolved, complete in ((False, False), (True, False), (False, True)):
            with self.subTest(resolved=resolved, complete=complete), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                declaration, mapping, evidence, lyrics, canonical, audio, _, _ = \
                    content_fixtures.EndpointContentAdmissionTests().fixture(root)
                declaration['occurrences'] = [{'performedIndex': i, 'occurrenceId': str(i),
                    'candidateFamilies': families} for i in (0, 1)]
                plans = [{'language': 'ar', 'occurrenceId': str(i), 'candidateFamilies': families} for i in (0, 1)]
                evidence = {'occurrenceCandidates': [{'occurrenceId': str(i),
                    'publishDecision': {'code': 'line-end-disagreement'}} for i in (0, 1)]}
                attempt = root/'endpoint-review/one'
                contexts, entries = [], []
                for index, plan in enumerate(plans):
                    scratch = ctc._directory(attempt)/f'context-{index:03d}'
                    scratch.mkdir(parents=True)
                    for filename, value in (('request.private.json', plan), ('prepared.json', {})):
                        (scratch/filename).write_text(json.dumps(value), encoding='utf-8')
                    (scratch/'source.wav').write_bytes(b'retained exact context')
                    contexts.append(scratch)
                    prior_records = [{'route': route, 'shiftMs': shift, 'decisions': {
                        'startMs': {'result': 'supported', 'candidateMs': 1040},
                        'endMs': {'result': 'supported' if index == 0 or complete else 'abstain',
                                  'candidateMs': families['mms-fa']['endMs'] if index == 0 or complete else None}}}
                        for route in ROUTES for shift in (-5, 0, 5)]
                    entries.append({'occurrenceId': str(index), 'inference': {'records': prior_records},
                                    'assessment': ctc._assess(prior_records, families)})
                parent_path = ctc._directory(attempt)/'manifest.json'
                previous = save_previous(attempt, declaration, entries)
                declaration_sha = native.sha256_file(attempt/'declaration.json')
                parent_before = parent_path.read_bytes()
                # Native recovery normally follows candidate prediction. Retain a
                # genuinely inconclusive candidate packet and validate the chain.
                if resolved:
                    candidate = {**declaration, 'method': prediction.METHOD, 'predictionVersion': 2,
                        'contextVersion': ctc.SCORER_VERSION, 'declarationSha256': declaration_sha,
                        'previousEvidence': {key: previous[key] for key in ('method', 'manifestSha256')},
                        'occurrences': [{'occurrenceId': entry['occurrenceId'], 'language': 'ar',
                            'candidateFamilies': families, 'inference': {'model': prediction.MODEL_IDENTITY, 'records': []},
                            'assessment': entry['assessment']} for entry in entries]}
                    candidate_path = prediction._directory(attempt)/'manifest.json'
                    candidate_path.parent.mkdir(parents=True)
                    candidate_path.write_text(json.dumps(candidate), encoding='utf-8')
                    previous = prediction._summary(candidate_path, candidate)
                supplied = copy.deepcopy(previous)
                supplied['occurrences'][0]['assessment'] = ctc._assess([], families)
                config = {'projectDir': str(root), 'analysisTimeoutMinutes': 1}
                if complete:
                    with patch.object(native, '_contexts', return_value=(plans, contexts)), \
                            patch.object(native, 'numerical_model', side_effect=AssertionError('already supported')), \
                            patch.object(native, '_infer', side_effect=AssertionError('already supported')):
                        self.assertEqual(native.write_packet(attempt, declaration, declaration_sha, audio, config,
                            mapping, evidence, canonical, supplied),
                            {**supplied, 'occurrences': entries, 'reviewRequired': False})
                    self.assertFalse((native._directory(attempt)/'manifest.json').exists())
                    self.assertEqual(parent_path.read_bytes(), parent_before)
                    continue
                def infer(project, selected, timeout, *, replay=False):
                    self.assertEqual((project, timeout), (root, 60))
                    self.assertEqual(selected, [contexts[1]])
                    return [{'model': native.numerical_model(), 'records': records if resolved else []}
                            for _ in selected]
                with patch.object(native, '_contexts', return_value=(plans, contexts)), \
                        patch.object(native, '_infer', side_effect=infer) as inference:
                    result = native.write_packet(attempt, declaration, declaration_sha, audio, config,
                        mapping, evidence, canonical, supplied)
                    self.assertEqual(result['occurrences'][0]['assessment'], entries[0]['assessment'])
                    self.assertEqual(result['occurrences'][1]['assessment']['startMs'], entries[1]['assessment']['startMs'])
                    self.assertEqual(result['occurrences'][1]['assessment']['endMs']['result'],
                                     'supported' if resolved else 'abstain')
                    self.assertEqual(result['reviewRequired'], not resolved)
                    binding = {key: result[key] for key in ('method', 'manifestSha256')}
                    stored = native.verify_packet(attempt, declaration, declaration_sha, audio,
                        binding, config, mapping, evidence, canonical, replay=True)
                    self.assertEqual(stored['previousEvidence'], {key: previous[key] for key in binding})
                    self.assertEqual(stored['occurrences'][0], candidate['occurrences'][0] if resolved else entries[0])
                    self.assertEqual(native.write_packet(attempt, declaration, declaration_sha, audio, config,
                        mapping, evidence, canonical, previous), result)
                    self.assertEqual(inference.call_count, 2)
                    manifest_path = native._directory(attempt)/'manifest.json'
                    retained = manifest_path.read_bytes()
                    broken = json.loads(retained)
                    broken['occurrences'][0]['assessment']['startMs']['candidateMs'] = 1000
                    manifest_path.write_text(json.dumps(broken), encoding='utf-8')
                    with self.assertRaisesRegex(ValueError, 'inherited occurrence changed'):
                        native.verify_stored_packet(attempt, {'method': native.METHOD,
                            'manifestSha256': native.sha256_file(manifest_path)})
                    manifest_path.write_bytes(retained)
                    parent_path.write_bytes(parent_before+b' ')
                    with self.assertRaises(ValueError):
                        native.verify_stored_packet(attempt, binding)
                    self.assertEqual(manifest_path.read_bytes(), retained)
                    parent_path.write_bytes(parent_before)
                    declaration_path = attempt/'declaration.json'
                    declared = declaration_path.read_bytes()
                    for changed in (b'{}', None):
                        if changed is None:
                            declaration_path.unlink()
                        else:
                            declaration_path.write_bytes(changed)
                        with self.assertRaises((ValueError, OSError)):
                            native.verify_stored_packet(attempt, binding)
                        self.assertEqual(manifest_path.read_bytes(), retained)
                        declaration_path.write_bytes(declared)
                self.assertEqual(parent_path.read_bytes(), parent_before)

    def setUp(self):
        self.device = {'name': 'Known fixture CUDA device', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        device = patch.object(native.nfa, 'cuda_device_identity', return_value=self.device)
        device.start()
        self.addCleanup(device.stop)

    def test_inference_rejects_terminal_packet_even_with_valid_zero_exit_output(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from stemwise_engine import endpoint_context_cache
        for disposition, error in (('cancelled', ProviderCancelled), ('parent-deadline', ProviderDeadline),
                                   ('retirement-failed', WorkerRetirementError)):
            with self.subTest(disposition=disposition), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                marker = 'STEMWISE_WORKER_FAILURE='+json.dumps({'formatVersion': 1,
                    'code': 'nested-stop', 'disposition': disposition})
                result = SimpleNamespace(returncode=0, stderr=marker,
                    stdout=json.dumps([{'model': native.numerical_model(), 'records': []}]))
                with patch.object(endpoint_context_cache, 'read_context_result', return_value=None), \
                        patch.object(native, 'run_process_tree', return_value=result), \
                        self.assertRaises(error):
                    native._infer(root, [root], 1)

    def test_delivered_pending_review_keeps_exact_contexts_for_native_validation(self):
        from workflow_fixture import initialized_workflow
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as directory:
            root = Path(directory)
            workflow = initialized_workflow(root)
            video, attempt_id = 'abcdefghijk', 'a'*32
            attempt = workflow._endpoint_review_attempt_dir(video, attempt_id)
            scratch = native.ctc_directory(attempt)/'context-000'
            scratch.mkdir(parents=True)
            audio = root/'analysis.wav'
            samples = native.np.column_stack((native.np.linspace(-.2, .3, 4800),
                                             native.np.linspace(.1, -.4, 4800))).astype('float32')
            native.sf.write(audio, samples, 48000, subtype='FLOAT')
            native.sf.write(scratch/'source.wav', samples, 48000, subtype='FLOAT')
            plan = {'language': 'ar', 'occurrenceId': 'one', 'startMs': 0, 'endMs': 100}
            (scratch/'request.private.json').write_text(json.dumps(plan), encoding='utf-8')
            (scratch/'prepared.json').write_text(json.dumps({'views': {route: {
                'path': 'source.wav', 'provenance': {'sha256': native.sha256_file(scratch/'source.wav'),
                    'byteCount': (scratch/'source.wav').stat().st_size}} for route in ROUTES}}), encoding='utf-8')
            canonical = root/'canonical.json'
            canonical.write_text('{}', encoding='utf-8')
            declaration = {'videoId': video, 'canonicalPlainSha256': native.sha256_file(canonical),
                           'analysisAudio': {'sha256': native.sha256_file(audio)}}
            evidence = {'occurrenceCandidates': [{'occurrenceId': 'one',
                'publishDecision': {'code': 'line-end-disagreement'}}]}
            job = {'videoId': video, 'state': 'DELIVERED', 'endpointReview': {'attemptId': attempt_id}}
            original = {path.name: path.read_bytes() for path in scratch.iterdir()}
            with patch.object(native, '_plan', return_value=[plan]), \
                    patch.object(native, '_infer', side_effect=AssertionError('validation must reuse contexts')):
                self.assertEqual(native._contexts(attempt, declaration, audio, {}, evidence, canonical)[1], [scratch])
                workflow._cleanup_completed_endpoint_contexts(job)
                self.assertEqual(native._contexts(attempt, declaration, audio, {}, evidence, canonical)[1], [scratch])
                for name in ('request.private.json', 'prepared.json', 'source.wav'):
                    with self.subTest(changed=name):
                        (scratch/name).write_bytes(b'changed retained input')
                        with self.assertRaises((ValueError, OSError, RuntimeError)):
                            native._contexts(attempt, declaration, audio, {}, evidence, canonical)
                        (scratch/name).write_bytes(original[name])
                job['endpointReview']['result'] = 'accepted'
                workflow._cleanup_completed_endpoint_contexts(job)
                self.assertFalse(scratch.exists())
                with self.assertRaises((ValueError, OSError)):
                    native._contexts(attempt, declaration, audio, {}, evidence, canonical)

    def test_arabic_alias_preparation_preserves_reviewed_language_and_context_binding(self):
        from lyric_content_fixtures import retained_content
        for language in ('ar', 'AR', 'ar-SA', 'en'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as directory:
                root = Path(directory)
                declaration, mapping, evidence, lyrics, canonical, audio, recognition, _ = \
                    content_fixtures.EndpointContentAdmissionTests().fixture(root)
                original = lyrics['tracks'][0]
                original.update(language=language, text='قبل\nنور\nبعد')
                canonical.write_text(json.dumps(lyrics), encoding='utf-8')
                samples = native.np.column_stack((native.np.linspace(-.2, .3, 192000),
                    native.np.linspace(.1, -.4, 192000))).astype('float32')
                native.sf.write(audio, samples, 48000, subtype='FLOAT')
                declaration['canonicalPlainSha256'] = native.sha256_file(canonical)
                declaration['analysisAudio']['sha256'] = native.sha256_file(audio)
                admission, _ = retained_content(recognition.parent, original, (0, 1, 2),
                    declaration['canonicalPlainSha256'], declaration['analysisAudio']['sha256'])
                declaration['contentAdmission'] = admission.to_record()
                for row, owner in zip(evidence['occurrenceCandidates'], mapping['performedOccurrences']):
                    row['occurrenceId'] = owner['occurrenceId']
                evidence['occurrenceCandidates'][1]['publishDecision']['code'] = 'line-end-disagreement'
                plans = native._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
                self.assertEqual(plans[0]['language'], language)
                attempt = root/'endpoint-review/attempt'
                scratch = native.ctc_directory(attempt)/'context-000'
                scratch.mkdir(parents=True)
                plan = plans[0]
                (scratch/'request.private.json').write_text(json.dumps(plan), encoding='utf-8')
                native.sf.write(scratch/'source.wav', samples[plan['startMs']*48:plan['endMs']*48],
                    48000, subtype='FLOAT')
                (scratch/'prepared.json').write_text(json.dumps({'views': {route: {
                    'path': 'source.wav', 'provenance': {'sha256': native.sha256_file(scratch/'source.wav'),
                        'byteCount': (scratch/'source.wav').stat().st_size}} for route in ROUTES}}), encoding='utf-8')
                paths = [canonical, audio, recognition, *scratch.iterdir()]
                before = {path: path.read_bytes() for path in paths}
                result = native._contexts(attempt, declaration, audio, mapping, evidence, canonical)
                self.assertEqual(result, (plans, [scratch]) if language != 'en' else ([], []))
                self.assertEqual({path: path.read_bytes() for path in paths}, before)

    def test_inference_expands_context_and_target_owners_preserving_audio_and_rejects_silence(self):
        class Word:
            def __init__(self, text, index):
                self.text, self.s_start, self.s_end = text, index*2+1, index*2+1
        class Segment:
            words_and_tokens = [Word(text, index) for index, text in enumerate(['عام', 'ألفان', 'نور', 'نور'])]
        utterance = SimpleNamespace(segments_and_tokens=[Segment()], token_ids_with_blanks=[0, 1]*4+[0])
        model = SimpleNamespace(eval=lambda: model, change_attention_model=lambda **_: None,
            tokenizer=SimpleNamespace(ids_to_tokens=lambda ids: ["نور" for _ in ids]))
        modules = {"torch": SimpleNamespace(device=lambda value: value),
            "nemo.collections.asr.models": SimpleNamespace(
                ASRModel=SimpleNamespace(restore_from=lambda *_a, **_k: model),
                EncDecHybridRNNTCTCModel=type("Hybrid", (), {}))}
        left = native.np.linspace(-.5, .5, 4410, dtype="float32")
        for language, silent in (('ar', False), ('AR', False), ('ar-SA', False), ('ar', True), ('en', False)):
            with self.subTest(language=language, silent=silent), tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as directory:
                scratch = Path(directory)
                samples = native.np.column_stack((left, -left))
                if silent:
                    samples.fill(0)
                plan = {"language": language, "startMs": 0, "endMs": 100, "targetLine": 1,
                    "request": {"lines": [{"lineId": "before", "sourcePhysicalIndex": 0,
                        "words": [{"wordId": "before-word", "acousticText": "عام ألفان"}]},
                        {"lineId": "target", "sourcePhysicalIndex": 1,
                        "words": [{"wordId": "target-word", "acousticText": "نور نور"}]}]},
                    "candidateFamilies": {"one": {"startMs": 10, "endMs": 80}}}
                (scratch / "request.private.json").write_text(json.dumps(plan), encoding="utf-8")
                native.sf.write(scratch / "audio.wav", samples, 44100, subtype="FLOAT")
                (scratch / "source.wav").write_bytes(b"bound input fixture")
                (scratch / "prepared.json").write_text(json.dumps({"views": {
                    route: {"path": "audio.wav", "provenance": {"sha256": native.sha256_file(scratch / "audio.wav"),
                        "byteCount": (scratch / "audio.wav").stat().st_size}} for route in ROUTES}}), encoding="utf-8")
                before = {path: path.read_bytes() for path in scratch.iterdir()}
                captured = []
                def infer(paths, *_args, **_kwargs):
                    self.assertEqual(_kwargs['gt_text_batch'], ['عام ألفان نور نور'])
                    waveform, rate = native.sf.read(paths[0], dtype="float32")
                    self.assertEqual(rate, 44100)
                    shift, gain = ((-5, .9), (0, 1.), (5, 1.1))[len(captured) % 3]
                    expected = left[round((10+shift)*44.1):-441]*gain
                    native.np.testing.assert_allclose(waveform, expected, atol=1e-7)
                    captured.append(waveform)
                    class Logp:
                        def __getitem__(self, _key):
                            return SimpleNamespace(numpy=lambda: native.np.zeros((10, 3)))
                    return Logp(), None, [10], None, [utterance], .08
                modules["nemo.collections.asr.parts.utils.aligner_utils"] = SimpleNamespace(
                    get_batch_variables=infer, Word=Word, Segment=Segment)
                with (patch.dict(sys.modules, modules),
                      patch.multiple(native.nfa, _bound_file=lambda *_: None,
                          _verify_source_checkout=lambda *_: None, _runtime_identity=lambda: {"cudaDevice": self.device}),
                      patch.object(native, "_vocal_samples", return_value=samples) as decode,
                      patch.object(native, "boundary_distributions", return_value=({}, {})) as boundaries,
                      patch.object(native, "select_candidate", return_value={})):
                    if language == 'en':
                        with self.assertRaisesRegex(ValueError, 'needs Arabic'):
                            native.infer_contexts(scratch, [scratch])
                        self.assertEqual(captured, [])
                        decode.assert_not_called()
                    elif silent:
                        with self.assertRaisesRegex(ValueError, "uninformative"):
                            native.infer_contexts(scratch, [scratch])
                        self.assertEqual(captured, [])
                    else:
                        native.infer_contexts(scratch, [scratch])
                        self.assertEqual(len(captured), 9)
                        self.assertEqual(decode.call_count, 3)
                        self.assertTrue(all(call.args[3:5] == (2, 3) for call in boundaries.call_args_list))
                        self.assertEqual(json.loads((scratch/'request.private.json').read_bytes()), plan)
                    self.assertEqual({path: path.read_bytes() for path in before}, before)

    def test_unknown_backend_tokens_cannot_stand_in_for_approved_words(self):
        utterance = SimpleNamespace(token_ids_with_blanks=[9, 2, 9, 3, 9])
        tokenizer = SimpleNamespace(ids_to_tokens=lambda ids: ["known", "<unk>"])
        with self.assertRaisesRegex(ValueError, "cannot represent every approved target"):
            native._target_ids(utterance, tokenizer)
        tokenizer.ids_to_tokens = lambda ids: ["known", "piece"]
        self.assertEqual(native._target_ids(utterance, tokenizer), [2, 3])

    def test_publication_replays_acoustics_instead_of_trusting_a_fabricated_packet(self):
        families, records = inputs()
        entry = {"occurrenceId": "1", "candidateFamilies": families,
            "reason": "line-end-disagreement", "inference": {"model": native.numerical_model(), "records": records},
            "assessment": native.assess(records, families, "line-end-disagreement")}
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            root = Path(name)
            declaration, mapping, evidence, lyrics, canonical, audio, recognition, _ = \
                content_fixtures.EndpointContentAdmissionTests().fixture(root)
            declaration['occurrences'][0]['candidateFamilies'] = families
            for row in evidence['occurrenceCandidates']:
                row['familyCandidates'] = families
            evidence['occurrenceCandidates'][1]['publishDecision']['code'] = 'line-end-disagreement'
            def contexts(*_args):
                plans = native._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
                return plans, [root]
            attempt = root / "endpoint-review/attempt"
            path = native._directory(attempt) / "manifest.json"
            path.parent.mkdir(parents=True)
            parent = save_previous(attempt, declaration)
            declaration_sha = native.sha256_file(attempt/'declaration.json')
            manifest = {"method": native.METHOD, "model": native.numerical_model(),
                "analysisAudio": declaration['analysisAudio'], 'declarationSha256': declaration_sha,
                'canonicalPlainSha256': declaration['canonicalPlainSha256'],
                'previousEvidence': {key: parent[key] for key in ('method', 'manifestSha256')},
                "occurrences": [entry]}
            path.write_text(json.dumps(manifest), encoding="utf-8")
            before = path.read_bytes()
            binding = {"method": native.METHOD, "manifestSha256": native.sha256_file(path)}
            weak = copy.deepcopy(records)
            weak[0]["decisions"]["endMs"]["peakMass"] = .1
            config = {"projectDir": str(root), "analysisTimeoutMinutes": 1}
            # Source admission has its own canonical integration check. This test
            # isolates the real publication decision against changed model output.
            with patch.object(native, "write_packet"), patch.object(native, "_contexts", side_effect=contexts):
                with patch.object(native, "_infer", return_value=[{"records": weak}]) as replay:
                    with self.assertRaisesRegex(ValueError, "did not reproduce"):
                        native.verify_packet(attempt, declaration, declaration_sha, audio, binding, config, mapping, evidence, canonical, replay=True)
                    replay.assert_called_once()
                self.assertEqual(path.read_bytes(), before)
                with patch.object(native, "_infer", side_effect=AssertionError('ordinary approval replayed models')):
                    native.verify_packet(attempt, declaration, declaration_sha, audio, binding, config, mapping, evidence, canonical)
                with patch.object(native, "_infer", return_value=[{"records": records}]) as replay:
                    result = native.verify_packet(attempt, declaration, declaration_sha, audio, binding, config, mapping, evidence, canonical, replay=True)
                    self.assertEqual(result["occurrences"][0]["assessment"]["endMs"]["candidateMs"], 2000)
                    replay.assert_called_once()

    def test_stored_manifest_inspection_never_probes_current_gpu(self):
        families, records = inputs()
        model = native.numerical_model()
        entry = {'occurrenceId': '1', 'candidateFamilies': families, 'reason': 'line-end-disagreement',
            'inference': {'model': model, 'records': records},
            'assessment': native.assess(records, families, 'line-end-disagreement')}
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            attempt = Path(name)/'reviews/attempt'
            path = native._directory(attempt)/'manifest.json'
            path.parent.mkdir(parents=True)
            declaration = {'analysisAudio': {}, 'canonicalPlainSha256': 'approved',
                'occurrences': [{'occurrenceId': '1', 'candidateFamilies': families}]}
            parent = save_previous(attempt, declaration)
            manifest = {'method': native.METHOD, 'model': model,
                'analysisAudio': declaration['analysisAudio'], 'canonicalPlainSha256': declaration['canonicalPlainSha256'],
                'declarationSha256': native.sha256_file(attempt/'declaration.json'),
                'previousEvidence': {key: parent[key] for key in ('method', 'manifestSha256')},
                'occurrences': [entry]}
            path.write_text(json.dumps(manifest), encoding='utf-8')
            binding = {'method': native.METHOD, 'manifestSha256': native.sha256_file(path)}
            with patch.object(native.nfa, 'cuda_device_identity', side_effect=AssertionError('GPU initialized')):
                self.assertEqual(native.verify_stored_packet(attempt, binding)['model'], model)
            entry['inference']['model'] = {**model, 'cudaDevice': {**self.device, 'name': 'Different GPU'}}
            path.write_text(json.dumps(manifest), encoding='utf-8')
            binding['manifestSha256'] = native.sha256_file(path)
            with self.assertRaisesRegex(ValueError, 'different model or device'):
                native.verify_stored_packet(attempt, binding)

    def test_native_grid_selects_existing_ending_and_preserves_agreeing_start(self):
        families, records = inputs()
        result = native.assess(records, families, "line-end-disagreement")
        self.assertEqual(result["startMs"]["candidateMs"], 1000)
        self.assertEqual(result["startMs"]["supportedFamilies"], ["mms-fa"])
        self.assertEqual(result["endMs"]["candidateMs"], 2000)
        self.assertEqual(result["endMs"]["supportedFamilies"], ["nemo-nfa"])
        self.assertEqual(result["endMs"]["predictionRangeMs"], [1965, 2055])

    def test_missing_weak_fabricated_or_wrong_grid_results_cannot_approve(self):
        families, records = inputs()
        for fault in ("missing", "duplicate", "weak", "fake-pass", "grid", "scores", "nan", "shift"):
            with self.subTest(fault=fault):
                changed = copy.deepcopy(records)
                decision = changed[0]["decisions"]["endMs"]
                if fault == "missing": changed.pop()
                elif fault == "duplicate": changed[-1] = copy.deepcopy(changed[0])
                elif fault == "weak": decision["peakMass"] = .49
                elif fault == "fake-pass": decision.update(result="supported", candidateMs=2000, peakMass=.1)
                elif fault == "grid": changed[0]["stepMs"] = 20
                elif fault == "scores": decision["candidateScores"][1]["mass"] = .001
                elif fault == "nan": decision["peakMs"] = float("nan")
                else: changed[0]["gain"] = 1
                self.assertEqual(native.assess(changed, families, "line-end-disagreement")["endMs"]["result"], "abstain")

    def test_uncertain_start_unsupported_identity_or_ambiguous_region_stays_pending(self):
        families, records = inputs()
        for reason in ("unsafe-occurrence-identity", "low-forced-line-support", "line-start-disagreement"):
            self.assertEqual(native.assess(records, families, reason)["startMs"]["result"], "abstain")
        changed = copy.deepcopy(families)
        changed["mms-fa"]["startMs"] = 600
        self.assertEqual(native.assess(records, changed, "line-end-disagreement")["startMs"]["result"], "abstain")
        changed = {"mms-fa": families["mms-fa"], "nemo-nfa": families["nemo-nfa"]}
        self.assertEqual(native.assess(records, changed, "line-end-disagreement")["endMs"]["result"], "abstain")


if __name__ == "__main__":
    unittest.main()
