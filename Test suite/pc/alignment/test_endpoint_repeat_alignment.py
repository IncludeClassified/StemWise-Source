import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "Test suite/pc/unit"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
from lyric_content_fixtures import retained_content
from stemwise_engine.workflow_records import LyricsContentBlocker
from stemwise_engine.endpoint_repeat_alignment import assess, transfer, reference_plan
import stemwise_engine.endpoint_repeat_alignment as repeat
import stemwise_engine.endpoint_ctc_evidence as ctc


def bind_content(root, declaration, *, lyrics=None, mapping=None, evidence=None):
    lyrics = lyrics or {'tracks': [{'kind': 'original', 'format': 'plain', 'language': 'en',
                                  'text': 'one two three\none two three'}]}
    mapping = mapping or {'performedOccurrences': [{'sourcePhysicalIndex': i} for i in (0, 1)]}
    evidence = evidence or {'occurrenceCandidates': [
        {'publishDecision': {'result': 'line-only'}},
        {'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'}}]}
    video = 'TESTVID0001'
    canonical = root / 'lyrics-alignment/canonical-plain' / (video + '.json')
    canonical.parent.mkdir(parents=True, exist_ok=True)
    canonical.write_text(json.dumps(lyrics), encoding='utf-8')
    declaration.update(videoId=video, canonicalPlainSha256=repeat.sha256_file(canonical))
    declaration.setdefault('analysisAudio', {})['sha256'] = 'a' * 64
    admission, _ = retained_content(canonical.parent.parent / 'jobs' / video, lyrics['tracks'][0],
        tuple(row['sourcePhysicalIndex'] for row in mapping['performedOccurrences']),
        declaration['canonicalPlainSha256'], declaration['analysisAudio']['sha256'])
    declaration['contentAdmission'] = admission.to_record()
    return canonical, mapping, evidence


def prediction_case():
    """Three known word intervals, repeated five seconds later; family b starts early."""
    words = [{"occurrenceWordId": f'word-{i}', "startMs": s, "endMs": e,
              "confidence": .8, "acousticTokenCount": 1}
             for i, (s, e) in enumerate([(1000, 1500), (1700, 2200), (2500, 2900)])]
    reference = {"selectedPublisherFamily": "mms-fa", "publishDecision": {"result": "words"},
                 "familyCandidates": {
                     "mms-fa": {"route": "mms", "confidence": .8, "startMs": 1000, "endMs": 2900, "words": words},
                     "language-wav2vec2": {"route": "wav2vec2-ctc-forced-on-mix", "confidence": .8,
                         "startMs": 1020, "endMs": 2920, "words": [
                             {**word, "startMs": word['startMs']+20, "endMs": word['endMs']+20,
                              "startUtf16": left, "endUtf16": right}
                             for word, (left, right) in zip(words, ((0, 3), (4, 7), (8, 13)))]}}}
    target_words = [{**word, "startMs": word['startMs']+5000, "endMs": word['endMs']+5000} for word in words]
    verifier_words = copy.deepcopy(target_words)
    verifier_words[0]['startMs'] = 5600
    verifier_words[-1]['endMs'] = 7920
    target = {"publishDecision": {"result": "unresolved"}, "familyCandidates": {
        "mms-fa": {"startMs": 6000, "endMs": 7900, "words": target_words},
        "language-wav2vec2": {"startMs": 5600, "endMs": 7920, "words": verifier_words}}}
    base = {"startMs": {"result": "abstain", "candidateMs": None, "supportedFamilies": [],
                        "candidatesAgree": False,
                        "reason": "ctc-views-or-perturbations-inconclusive"},
            "endMs": {"result": "supported", "candidateMs": 7900, "supportedFamilies": ["mms-fa"],
                      "candidatesAgree": False,
                      "reason": "stable-ctc-support-review-required"}}
    records = [{"channel": c, "shiftMs": s, "mappedMs": [6000+s, 6020+s, 7900+s, 7920+s, 6000, 6500, 6700, 7200, 7500, 7900]}
               for c in (0, 1) for s in (-5, 0, 5)]
    return {"reference": reference, "target": target}, base, records


class RepeatPredictionTests(unittest.TestCase):
    def test_repeat_reference_uses_eligible_evidence_and_keeps_strong_dissent(self):
        from types import SimpleNamespace
        from stemwise_engine import alignment_endpoints as endpoints, mms_forced_alignment as mms
        from stemwise_engine import owsm_ctc_forced_alignment as owsm
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
                    'text': 'one two three\none two three'}
        lyrics = {'tracks': [original]}
        mapping = build_lexical_occurrence_mapping('TESTVID0001', original, (0, 1))
        owners = mapping['performedOccurrences']
        plans = [{'lineId': row['occurrenceId'], 'lineIndex': index, 'sourcePhysicalIndex': index,
                  'startWord': index*3, 'wordCount': 3, 'words': [
                      {'wordId': word['occurrenceWordId'], 'startWord': index*3+j, 'wordCount': 1}
                      for j, word in enumerate(row['words'])]} for index, row in enumerate(owners)]
        verifier = tuple({'lineId': row['occurrenceId'], 'route': endpoints.WAV2VEC2_ROUTE,
            'startMs': 1000+index*5000, 'endMs': 3000+index*5000, 'confidence': .8,
            'words': [{'wordId': word['occurrenceWordId'], 'startMs': start+index*5000,
                'endMs': end+index*5000, 'confidence': .8, 'acousticTokenCount': 1,
                'startUtf16': left, 'endUtf16': right}
                for word, (start, end), (left, right) in zip(row['words'],
                    ((1000, 1500), (1700, 2200), (2500, 3000)), ((0, 3), (4, 7), (8, 13)))]}
            for index, row in enumerate(owners))
        request_line = {'lineId': owners[0]['occurrenceId'], 'sourcePhysicalIndex': 0,
            'words': [{'wordId': word['occurrenceWordId'], 'acousticText': text}
                      for word, text in zip(owners[0]['words'], ('one', 'two', 'three'))]}
        token_plan = [{'lineId': owners[0]['occurrenceId'], 'tokenIds': [7, 8, 9], 'words': [
            {'wordId': word['occurrenceWordId'], 'targetStart': j, 'targetEnd': j+1}
            for j, word in enumerate(owners[0]['words'])]}]
        for first_onset in (50, 70):
            witness = owsm._lexical_records([request_line], token_plan, [
                SimpleNamespace(token=token, start=start, end=start+1, score=-.1)
                for token, start in zip((7, 8, 9), (first_onset, 85, 125))], 20., 10000)[0]
            witness.update(route=owsm.ROUTES_BY_LANGUAGE['en'],
                           endpointSemantics='first-to-last-approved-lexical-ctc-token-span-v1')
            acoustic = [{'startMs': word['startMs'], 'endMs': word['endMs'],
                         'confidence': .01 if index == 1 and j == 0 else .8,
                         'tokenCount': 1}
                        for index, line in enumerate(verifier) for j, word in enumerate(line['words'])]
            baseline = endpoints._refine_public_timings({'lines': mms._aggregate_records(plans, acoustic)},
                                                        verifier, include_unresolved=True)
            _, summary = endpoints._apply_independent_endpoint_consensus(baseline,
                {endpoints.OWSM_CTC_FAMILY: (witness, None)}, include_unresolved=True)
            reference = summary['occurrenceCandidates'][0]
            evidence = {'occurrenceCandidates': summary['occurrenceCandidates']}
            before = copy.deepcopy(evidence)
            with self.subTest(first_onset=first_onset):
                self.assertEqual(set(endpoints._qualified_word_candidates(reference)),
                                 {'mms-fa', endpoints.WAV2VEC2_FAMILY})
                planned = reference_plan({'performedIndex': 1}, mapping, evidence, lyrics)
                if first_onset == 50:
                    self.assertEqual(reference['publishDecision']['result'], 'words')
                    self.assertIsNotNone(planned)
                    self.assertEqual(planned['reference']['selectedPublisherFamily'], reference['selectedPublisherFamily'])
                    self.assertEqual(set(planned['reference']['familyCandidates']),
                                     {'mms-fa', endpoints.WAV2VEC2_FAMILY})
                    self.assertIn(endpoints.OWSM_CTC_FAMILY, reference['familyCandidates'])
                else:
                    self.assertEqual(reference['publishDecision']['result'], 'unresolved')
                    self.assertIsNone(planned)
                    stale = copy.deepcopy(evidence)
                    stale['occurrenceCandidates'][0].update(publishDecision={'result': 'words'},
                                                           selectedPublisherFamily='mms-fa')
                    stale['occurrenceCandidates'][0].pop('qualifiedEndpointContradictions', None)
                    stale['occurrenceCandidates'][0].pop('qualifiedWordContradictions', None)
                    self.assertIsNone(reference_plan({'performedIndex': 1}, mapping, stale, lyrics))
                self.assertEqual(evidence, before)

    def test_no_eligible_repeat_publishes_and_reuses_bound_abstention_without_inference(self):
        for case, lines in (('unique', ['one two three', 'four five six']),
                            ('short', ['one two', 'one two']),
                            ('missing-reference', ['one two three', 'one two three'])):
            with self.subTest(case=case), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                attempt = root/'endpoint-review/one'
                families = {key: {'startMs': 1000, 'endMs': 2000,
                    'words': [{} for _ in lines[0].split()]} for key in ('a', 'b')}
                evidence = {'occurrenceCandidates': [
                    {'publishDecision': {'result': 'unresolved' if case == 'missing-reference' else 'words',
                                         'code': 'line-start-disagreement'},
                     'selectedPublisherFamily': 'a', 'familyCandidates': families},
                    {'publishDecision': {'result': 'unresolved', 'code': 'line-start-disagreement'},
                     'familyCandidates': families}]}
                declaration = {'occurrences': [{'occurrenceId': 'target', 'performedIndex': 1,
                    'candidateFamilies': families}]}
                canonical, mapping, evidence = bind_content(root, declaration,
                    lyrics={'tracks': [{'kind': 'original', 'format': 'plain', 'language': 'en',
                                        'text': '\n'.join(lines)}]}, evidence=evidence)
                base = ctc._assess([], families)
                manifest = {**declaration, 'method': ctc.METHOD, 'scorerVersion': ctc.SCORER_VERSION,
                    'reviewRequired': True, 'declarationSha256': 'declared',
                    'occurrences': [{'occurrenceId': 'target', 'assessment': base}]}
                directory = ctc._directory(attempt)
                directory.mkdir(parents=True)
                (directory/'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
                packet = ctc._summary(directory, manifest)
                before = (directory/'manifest.json').read_bytes()
                with patch.object(ctc, '_invoke', side_effect=AssertionError('no eligible repeat')), \
                        patch.object(repeat, 'run_process_tree', side_effect=AssertionError('no eligible repeat')):
                    result = repeat.write_packet(attempt, declaration, 'declared', root/'absent.wav',
                        {'projectDir': str(root)}, mapping, evidence, canonical, packet)
                    binding = {key: result[key] for key in ('method', 'manifestSha256')}
                    stored = repeat.verify_packet(attempt, declaration, 'declared', root/'absent.wav',
                        binding, mapping, evidence, canonical)
                    self.assertEqual(stored['occurrences'], [{'occurrenceId': 'target',
                        'plan': None, 'records': [], 'assessment': base}])
                    self.assertTrue(result['reviewRequired'])
                    self.assertEqual(repeat.write_packet(attempt, declaration, 'declared', root/'absent.wav',
                        {'projectDir': str(root)}, mapping, evidence, canonical, packet), result)
                self.assertEqual((directory/'manifest.json').read_bytes(), before)
                self.assertFalse((repeat._directory(attempt)/'repeat-request.private.json').exists())

    def test_low_memory_rejects_crop_before_pcm_decode_or_model_loading(self):
        from stemwise_engine.acoustic_inputs import AcousticBudget, AcousticBudgetExceeded
        class Source:
            samplerate, frames, channels = 48000, 48000*15, 2
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def __len__(self): return self.frames
            def read(self, *args, **kwargs): raise AssertionError('must reject before PCM read')
        source = Source()
        plan, _, _ = prediction_case()
        with patch('soundfile.SoundFile', return_value=source), \
                patch.object(repeat, 'CURRENT_BUDGET', AcousticBudget(available_bytes=1)):
            with self.assertRaises(AcousticBudgetExceeded):
                repeat.infer(Path('source.wav'), 'unavailable-model', plan)

    def test_retired_repeat_versions_reject_before_model_construction(self):
        from types import SimpleNamespace
        def forbidden(*args, **kwargs):
            self.fail('unsupported repeat packets cannot construct a model')
        transformers = SimpleNamespace(AutoModelForCTC=SimpleNamespace(from_pretrained=forbidden),
            Wav2Vec2FeatureExtractor=SimpleNamespace(from_pretrained=forbidden))
        with patch.dict(sys.modules, {'transformers': transformers}):
            for version in (1, 2, 3, True, 4.0, None):
                with self.subTest(version=version):
                    with self.assertRaisesRegex(ValueError, 'unsupported repeat prediction version'):
                        repeat.infer(Path('absent.wav'), 'absent-model', {}, prediction_version=version)
                    with self.assertRaisesRegex(ValueError, 'unsupported repeat prediction version'):
                        repeat.infer_many(Path('absent.wav'), [], 1, prediction_version=version)

    def test_repeat_model_reuse_keeps_every_channel_shift_and_retires_changed_identity(self):
        import soundfile as sf
        import torch
        from types import SimpleNamespace
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            source = root / 'source.wav'
            samples = .1 * np.sin(np.arange(48000 * 15) / 19.)
            sf.write(source, np.column_stack((samples, -samples)), 48000, subtype='FLOAT')
            model = SimpleNamespace(config=SimpleNamespace(conv_kernel=[400], conv_stride=[320], pad_token_id=0))
            model.to = lambda *_: model
            model.eval = lambda: model
            plan, _, _ = prediction_case()
            seen = []
            def emissions(wave, *_):
                seen.append(tuple(wave.shape))
                p = np.random.default_rng(91).uniform(.01, 1, (wave.shape[1] // 320, 8))
                p /= p.sum(axis=1, keepdims=True)
                return torch.from_numpy(np.log(p))
            with patch('transformers.Wav2Vec2FeatureExtractor.from_pretrained',
                    return_value=SimpleNamespace(sampling_rate=16000)), patch(
                    'transformers.AutoModelForCTC.from_pretrained', return_value=model) as loader, patch.object(
                    ctc, '_bounded_emissions', side_effect=emissions):
                cache = {}
                first = repeat.infer(source, str(root / 'model'), plan, prediction_version=4,
                                     model_cache=cache, model_identity={'snapshot': 'a'})
                second = repeat.infer(source, str(root / 'model'), plan, prediction_version=4,
                                      model_cache=cache, model_identity={'snapshot': 'a'})
                self.assertEqual(loader.call_count, 1)
                third = repeat.infer(source, str(root / 'model'), plan, prediction_version=4,
                                     model_cache=cache, model_identity={'snapshot': 'b'})
                self.assertEqual(loader.call_count, 2)
                self.assertEqual(first, second)
                self.assertEqual(first, third)
                self.assertEqual(len(cache), 1)
            self.assertEqual(len(seen), 36)
            self.assertEqual({(r['channel'], r['shiftMs']) for r in first},
                             {(c, s) for c in (0, 1) for s in (-5, 0, 5)})

    def test_complete_edge_crops_run_and_truncated_targets_abstain_before_model_load(self):
        import soundfile as sf
        import torch
        from types import SimpleNamespace
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            audio = Path(name) / 'audio.wav'
            model = SimpleNamespace(config=SimpleNamespace(conv_kernel=[400], conv_stride=[320], pad_token_id=0))
            model.to = lambda *_: model
            model.eval = lambda: model
            seen = []
            def emissions(wave, *_):
                seen.append(wave.shape[-1])
                return torch.full((max(2, (wave.shape[1]-400)//320+1), 3), -np.log(3))
            with patch('transformers.Wav2Vec2FeatureExtractor.from_pretrained',
                    return_value=SimpleNamespace(sampling_rate=16000)), patch(
                    'transformers.AutoModelForCTC.from_pretrained', return_value=model) as loader, patch.object(
                    ctc, '_bounded_emissions', side_effect=emissions), patch.object(repeat, 'transfer', return_value=[1.]):
                for duration, first, last in ((180000, 170000, 179500), (1000, 0, 990), (1000, 10, 500)):
                    sf.write(audio, np.zeros((duration*16, 2), dtype=np.float32), 16000, subtype='FLOAT')
                    candidate = {'selectedPublisherFamily': 'a', 'familyCandidates': {
                        'a': {'startMs': first, 'endMs': last, 'words': [{'startMs': first, 'endMs': last}]}}}
                    plan = {'reference': candidate, 'target': copy.deepcopy(candidate)}
                    with self.subTest(duration=duration, first=first):
                        seen.clear()
                        self.assertEqual(len(repeat.infer(audio, str(Path(name)/'model'), plan)), 6)
                        expected = ([255920, 255920, 255840, 255840, 255760, 255760]
                                    if duration == 180000 else [16000, 16000, 16000, 16000, 15920, 15920])
                        self.assertEqual(seen, expected*2)
                    plan['target']['familyCandidates']['a']['endMs'] = duration + 1
                    loader.reset_mock()
                    self.assertEqual(repeat.infer(audio, str(Path(name)/'model'), plan), [])
                    loader.assert_not_called()

    def test_batch_groups_models_preserves_order_and_restores_deadlines_on_failure(self):
        import signal
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        for failure in (None, 'child', 'deadline'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(dir=work) as name:
                root = Path(name)
                contexts = [{'index': i, 'modelPath': str(root / model),
                    'model': {'snapshot': model}, 'plan': {'index': i}}
                    for i, model in enumerate(('a', 'b', 'a'))]
                handler = signal.getsignal(signal.SIGALRM)
                seen, caches = [], []
                def infer(_audio, _model, plan, *, prediction_version, model_cache, model_identity):
                    seen.append(plan['index'])
                    caches.append(model_cache)
                    self.assertEqual(prediction_version, 4)
                    self.assertEqual(model_identity, contexts[plan['index']]['model'])
                    if plan['index'] == 2 and failure:
                        if failure == 'deadline':
                            signal.raise_signal(signal.SIGALRM)
                        raise RuntimeError('context failed')
                    return [{'fixture': plan['index']}]
                with patch.object(repeat, 'infer', side_effect=infer):
                    if failure:
                        with self.assertRaisesRegex((RuntimeError, TimeoutError), 'context.*(failed|deadline)'):
                            repeat.infer_many(root / 'audio.wav', contexts, 7)
                    else:
                        self.assertEqual(repeat.infer_many(root / 'audio.wav', contexts, 7), [
                            {'index': i, 'records': [{'fixture': i}]} for i in range(3)])
                self.assertEqual(seen, [0, 2] if failure else [0, 2, 1])
                self.assertTrue(all(c is caches[0] for c in caches))
                self.assertEqual(caches[0], {})
                self.assertIs(signal.getsignal(signal.SIGALRM), handler)
                self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0, 0))
                self.assertEqual(list(root.iterdir()), [])
        for contexts, timeout in (([], 1), ([contexts[0], contexts[0]], 1), (contexts, 0), (contexts, True)):
            with self.assertRaises(ValueError):
                repeat.infer_many(root / 'audio.wav', contexts, timeout)

    def test_one_batch_requires_every_current_context_before_publishing(self):
        plan, base, records = prediction_case()
        work = Path(__file__).resolve().parents[3] / 'Test suite/work'
        for fault in (None, 'missing', 'order', 'stale', 'boolean', 'skip', 'count', 'cancelled', 'parent-deadline', 'retirement-failed'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=work) as name:
                root = Path(name)/'builder'
                root.mkdir()
                prepare_runtime_layout(root)
                attempt = root / 'endpoint-review/one'
                attempt.mkdir(parents=True)
                (attempt / 'review.json').write_bytes(b'pending review')
                paths = []
                for i in range(2):
                    scratch = ctc._directory(attempt) / f'context-{i:03d}'
                    scratch.mkdir(parents=True)
                    (scratch / 'prepared.json').write_text(json.dumps({
                        'model': {'snapshot': 'fixed'}, 'modelPath': str(root / 'model')}))
                    paths.append(scratch)
                directory = repeat._directory(attempt)
                directory.mkdir(parents=True)
                (directory / 'repeat-inference.json').write_bytes(b'old numeric result')
                declaration = {
                    'occurrences': [{'occurrenceId': str(i)} for i in range(2)]}
                canonical, mapping, evidence = bind_content(root, declaration)
                packet = {'method': ctc.METHOD, 'manifestSha256': 'bound',
                    'occurrences': [{'assessment': base} for _ in range(2)]}
                verified = {'occurrences': [{'inference': {'model': {'snapshot': 'fixed'}}} for _ in range(2)]}
                if fault == 'count': packet['occurrences'].pop()
                seen = []
                def child(_command, **options):
                    request = json.loads((directory / 'repeat-request.private.json').read_bytes())
                    seen.append(request)
                    self.assertEqual(options['timeout'], 120)
                    self.assertEqual([c['index'] for c in request['contexts']], [0, 1])
                    if fault == 'skip':
                        return subprocess.CompletedProcess([], 0, '', '')
                    rows = [{'index': i, 'records': records} for i in range(2)]
                    if fault == 'missing': rows.pop()
                    if fault == 'order': rows.reverse()
                    if fault == 'boolean': rows[1]['index'] = True
                    output = {'requestSha256': repeat.sha256_file(directory / 'repeat-request.private.json'), 'contexts': rows}
                    if fault == 'stale': output['requestSha256'] = '0' * 64
                    (directory / 'repeat-inference.json').write_text(json.dumps(output))
                    marker = ('STEMWISE_WORKER_FAILURE='+json.dumps({'formatVersion': 1,
                        'code': 'nested-stop', 'disposition': fault})) if fault in (
                            'cancelled', 'parent-deadline', 'retirement-failed') else ''
                    return subprocess.CompletedProcess([], 0, '', marker)
                with patch.object(repeat, 'reference_plan', return_value=plan), patch.object(
                        ctc, 'verify_stored_packet', return_value=verified), patch.object(
                        ctc, '_invoke') as preparation, patch.object(repeat, 'verify_packet'), patch.object(
                        repeat, 'run_process_tree', side_effect=child):
                    args = (attempt, declaration, 'source', root / 'audio.wav',
                        {'projectDir': str(root), 'analysisTimeoutMinutes': 1}, mapping, evidence, canonical, packet)
                    if fault:
                        from stemwise_engine.common import WorkerRetirementError
                        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
                        error = {'cancelled': ProviderCancelled, 'parent-deadline': ProviderDeadline,
                                 'retirement-failed': WorkerRetirementError}.get(fault, ValueError)
                        with self.assertRaises(error): repeat.write_packet(*args)
                        self.assertFalse((directory / 'manifest.json').exists())
                    else:
                        result = repeat.write_packet(*args)
                        self.assertEqual([e['occurrenceId'] for e in result['occurrences']], ['0', '1'])
                        self.assertTrue(all(e['assessment']['startMs']['candidateMs'] == 6000 for e in result['occurrences']))
                    if fault == 'count': preparation.assert_not_called()
                    else: preparation.assert_called_once_with(root, 'prepare-many', paths, 60, alignment=True)
                self.assertEqual(len(seen), 0 if fault == 'count' else 1)
                self.assertEqual((attempt / 'review.json').read_bytes(), b'pending review')
                self.assertTrue(all((p / 'prepared.json').exists() for p in paths))

    def test_completed_repeat_context_survives_late_failure_and_reuses_only_exact_inputs(self):
        work = Path(__file__).resolve().parents[3]/'Test suite/work'
        for changed in ('audio', 'plan', 'model', 'runtime', 'during-inference'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory(dir=work) as name:
                root = Path(name)
                audio = root/'audio.wav'
                audio.write_bytes(b'exact synthetic recording')
                checkpoint = root/'completed-contexts'
                request = root/'repeat-request.private.json'
                output = root/'repeat-inference.json'
                contexts = [{'index': index, 'modelPath': str(root/'model'),
                    'model': {'snapshot': 'current'}, 'plan': {'index': index}} for index in range(2)]
                calls = []
                failed = False
                mutate_during = False
                runtime = {'uuid': 'GPU-fixture', 'torch': 'fixture'}
                def infer(_audio, _model, plan, **_):
                    nonlocal failed
                    calls.append(plan['index'])
                    if plan['index'] == 1 and not failed:
                        failed = True
                        raise RuntimeError('late context failed')
                    if mutate_during:
                        audio.write_bytes(b'source changed while inference was active')
                    return [{'fixture': plan['index'], 'plan': copy.deepcopy(plan)}]
                with patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', side_effect=lambda: dict(runtime)), \
                        patch.object(repeat, 'infer', side_effect=infer):
                    def invoke():
                        request.write_bytes(repeat.json_bytes({'audio': str(audio), 'contexts': contexts,
                            'timeoutSeconds': 7, 'predictionVersion': repeat.PREDICTION_VERSION}))
                        repeat.run_request(request)
                        packet = json.loads(output.read_bytes())
                        self.assertEqual(packet['requestSha256'], repeat.sha256_file(request))
                        return packet['contexts']
                    with self.assertRaisesRegex(RuntimeError, 'late context failed'):
                        invoke()
                    saved = {path: path.read_bytes() for path in checkpoint.glob('*.json')}
                    self.assertEqual(len(saved), 1)
                    self.assertFalse(output.exists())
                    self.assertEqual([row['index'] for row in invoke()], [0, 1])
                    self.assertEqual(calls, [0, 1, 1])
                    self.assertEqual({path: path.read_bytes() for path in saved}, saved)
                    calls.clear()
                    if changed == 'audio': audio.write_bytes(b'changed synthetic recording')
                    if changed == 'plan': contexts[0]['plan']['range'] = [10, 20]
                    if changed == 'model': contexts[0]['model']['snapshot'] = 'replacement'
                    if changed == 'runtime': runtime['uuid'] = 'GPU-other-fixture'
                    if changed == 'during-inference':
                        contexts[0]['plan']['range'] = [10, 20]
                        mutate_during = True
                        prior_output = output.read_bytes()
                        with self.assertRaisesRegex(ValueError, 'source audio changed'):
                            invoke()
                        self.assertEqual({path: path.read_bytes() for path in saved}, saved)
                        self.assertEqual(output.read_bytes(), prior_output)
                        self.assertNotEqual(json.loads(output.read_bytes())['requestSha256'], repeat.sha256_file(request))
                        continue
                    result = invoke()
                    self.assertEqual(calls, [0, 1] if changed in ('audio', 'runtime') else [0])
                    self.assertEqual(result[0]['records'][0]['plan'], contexts[0]['plan'])

    def test_private_endpoint_worker_mains_keep_nested_retirement_fatal(self):
        script = '''
import importlib, io, json, runpy, sys
from pathlib import Path
from unittest.mock import patch
from stemwise_engine.common import WorkerRetirementError
module, directory = sys.argv[1:]
root = Path(directory)
importlib.import_module('stemwise_engine.' + module)
def fatal(*args, **options):
    raise WorkerRetirementError('nested owner retirement remains unconfirmed')
if module == 'endpoint_repeat_alignment':
    audio = root/'audio.wav'
    audio.write_bytes(b'exact synthetic source')
    request = root/'repeat-request.private.json'
    request.write_text(json.dumps({'audio': str(audio), 'contexts': [{'index': 0,
        'modelPath': str(root/'model'), 'model': {}, 'plan': {}}],
        'timeoutSeconds': 1, 'predictionVersion': 4}), encoding='utf-8')
    sys.argv = [module, str(request)]
    boundary = 'stemwise_engine.worker_runtime_probe.torch_runtime_identity'
elif module == 'endpoint_ctc_evidence':
    sys.argv = [module, 'infer-many', str(root), '1']
    sys.stdin = io.StringIO(json.dumps([str(root)]))
    boundary = 'stemwise_engine.common.read_bounded_json'
elif module == 'endpoint_native_ctc':
    sys.argv = [module, str(root), str(root/'context')]
    boundary = 'stemwise_engine.nemo_nfa_forced_alignment._bound_file'
else:
    sys.argv = [module, str(root), json.dumps([{'index': 0, 'path': str(root), 'languages': ['en']}])]
    boundary = 'stemwise_engine.owsm_ctc_forced_alignment.cuda_device_identity'
with patch(boundary, side_effect=fatal):
    runpy.run_module('stemwise_engine.' + module, run_name='__main__')
'''
        for module in ('endpoint_repeat_alignment', 'endpoint_ctc_evidence', 'endpoint_native_ctc', 'endpoint_owsm_ctc'):
            with self.subTest(module=module), tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
                root = Path(name)
                output = root/'repeat-inference.json'
                output.write_bytes(b'prior completed output remains owned')
                result = repeat.run_process_tree([sys.executable, '-c', script, module, str(root)],
                    capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 70, result.stderr)
                marker = result.stderr.splitlines()[-1]
                self.assertTrue(marker.startswith('STEMWISE_WORKER_FAILURE='))
                self.assertEqual(json.loads(marker.partition('=')[2]), {'formatVersion': 1,
                    'code': 'alignment-model-retirement-failed', 'disposition': 'retirement-failed'})
                self.assertIn('nested owner retirement remains unconfirmed', result.stderr)
                self.assertEqual(output.read_bytes(), b'prior completed output remains owned')

    def test_current_repeat_cache_is_reused_without_recomputation(self):
        plan, base, records = prediction_case()
        for version in (4,):
            with self.subTest(version=version), tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
                root = Path(name)
                attempt = root / "endpoint-review" / "one"
                directory = repeat._directory(attempt)
                directory.mkdir(parents=True)
                scratch = ctc._directory(attempt) / "context-000"
                scratch.mkdir(parents=True)
                (scratch / "prepared.json").write_text(json.dumps({
                    "model": {"snapshot": "fixed"}, "modelPath": str(root / "model")}))
                binding = {"method": ctc.METHOD, "manifestSha256": "bound"}
                old = {"method": repeat.METHOD, "predictionVersion": version, "ctcEvidence": binding,
                    "occurrences": [{"occurrenceId": "test", "plan": plan, "records": records,
                        "assessment": assess(records, plan, base, prediction_version=4)}]}
                path = directory / "manifest.json"
                path.write_text(json.dumps(old))
                before = path.read_bytes()
                declaration = {
                               "occurrences": [{"occurrenceId": "test"}]}
                canonical, mapping, evidence = bind_content(root, declaration)
                packet = {**binding, "occurrences": [{"assessment": base}]}
                requests = []
                def child(_command, **_kwargs):
                    requests.append(json.loads((directory / "repeat-request.private.json").read_text()))
                    (directory / "repeat-inference.json").write_text(json.dumps({
                        'requestSha256': repeat.sha256_file(directory / 'repeat-request.private.json'),
                        'contexts': [{'index': 0, 'records': records}]}))
                    return subprocess.CompletedProcess([], 0, "", "")
                with patch.object(repeat, "reference_plan", return_value=plan), \
                        patch.object(repeat, "verify_packet", return_value=old), \
                        patch.object(ctc, "verify_stored_packet", return_value={"occurrences": [
                            {"inference": {"model": {"snapshot": "fixed"}}}]}), \
                        patch.object(ctc, "_invoke"), patch.object(repeat, "run_process_tree", side_effect=child):
                    result = repeat.write_packet(attempt, declaration, "source", root / "audio.wav",
                        {"projectDir": str(root), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, packet)
                self.assertEqual(result["occurrences"][0]["assessment"]["startMs"]["candidateMs"], 6000)
                self.assertEqual(path.read_bytes(), before)
                self.assertEqual(requests, [])

    def test_longer_reference_maps_into_shorter_target_without_swapping_time_axes(self):
        rng = np.random.default_rng(193)
        phones = rng.uniform(.01, 1, (30, 7))
        phones /= phones.sum(axis=1, keepdims=True)
        indices = [0, 1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 25, 26, 27, 28, 29]
        def posterior(rows, blank_mass):
            return np.log(np.column_stack((np.full(len(rows), blank_mass), rows*(1-blank_mass))))
        result = transfer(posterior(phones, .2), np.arange(30)*20.,
                          posterior(phones[indices], .85), 5000+np.arange(len(indices))*20.,
                          0, [40, 160, 440, 560])
        np.testing.assert_allclose(result, [5040, 5120, 5260, 5360], atol=1e-6)

    def test_invalid_or_empty_dtw_path_is_rejected_without_inventing_a_mapping(self):
        rng = np.random.default_rng(73)
        phones = rng.uniform(.01, 1, (6, 8))
        phones /= phones.sum(axis=1, keepdims=True)
        logp, times = np.log(phones), np.arange(6)*20.
        for path in (np.empty((0, 2), dtype=int), np.array([[5, 6], [0, 0]]),
                     np.array([[5, -1], [0, 0]])):
            with self.subTest(path=path.tolist()), patch("librosa.sequence.dtw", return_value=(None, path)):
                with self.assertRaisesRegex(ValueError, "invalid repeat DTW path"):
                    transfer(logp, times, logp, times+1700, 0, [40])

    def test_child_failure_keeps_bounded_private_cause_without_changing_review(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            root = Path(name)/'builder'
            root.mkdir()
            prepare_runtime_layout(root)
            attempt = root / "endpoint-review" / "one"
            attempt.mkdir(parents=True)
            review = attempt / "review.json"
            review.write_bytes(b'{"outcome":"pending"}\n')
            before = review.read_bytes()
            scratch = ctc._directory(attempt) / "context-000"
            scratch.mkdir(parents=True)
            (scratch / "prepared.json").write_text(json.dumps({
                "model": {"snapshot": "fixed"}, "modelPath": str(root / "model")
            }))
            directory = repeat._directory(attempt)
            directory.mkdir(parents=True)
            stale = directory / "repeat-inference.json"
            stale.write_bytes(b"[]")
            diagnostic = directory / "repeat-worker-error.private.txt"
            diagnostic.write_text("obsolete failure")
            declaration = {
                           "occurrences": [{"occurrenceId": "test-occurrence"}]}
            canonical, mapping, evidence = bind_content(root, declaration)
            packet = {"method": ctc.METHOD, "manifestSha256": "bound",
                      "occurrences": [{"assessment": {"startMs": {"result": "abstain"}}}]}
            base = {"occurrences": [{"inference": {"model": {"snapshot": "fixed"}}}]}
            run_child = repeat.run_process_tree
            def failed_child(_command, **kwargs):
                from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
                self.assertIsNotNone(process_lock_descriptor(heavy_worker_lock_path({'projectDir': str(root)})))
                return run_child([sys.executable, "-c",
                    "import sys; print('private request detail'); "
                    "sys.stderr.write('é' * 14000 + '\\nRuntimeError: private child cause\\n'); sys.exit(17)"],
                    **kwargs)
            with patch.object(repeat, "reference_plan", return_value={"reference": {}}), \
                    patch.object(ctc, "verify_stored_packet", return_value=base), \
                    patch.object(ctc, "_invoke"), patch.object(repeat, "run_process_tree", side_effect=failed_child):
                from stemwise_engine.worker_protocol import WorkerProtocolError
                with self.assertRaises(WorkerProtocolError) as raised:
                    repeat.write_packet(attempt, declaration, "source", root / "audio.wav",
                        {"projectDir": str(root), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, packet)
            diagnostic = directory / raised.exception.diagnostics[0]['file']
            self.assertTrue(diagnostic.exists(), "The failing child's diagnostic was discarded")
            detail = diagnostic.read_text(encoding="utf-8")
            self.assertEqual(raised.exception.code, 'repeat-ctc-worker-failed')
            self.assertIn("RuntimeError: private child cause", detail)
            self.assertNotIn("obsolete failure", detail)
            self.assertLessEqual(diagnostic.stat().st_size, 12000)
            self.assertNotIn("private child cause", str(raised.exception))
            self.assertEqual(review.read_bytes(), before)
            self.assertFalse(stale.exists())
            self.assertFalse((directory / "manifest.json").exists())

    def test_successful_exit_without_fresh_numeric_output_cannot_adopt_old_results(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            root = Path(name)/'builder'
            root.mkdir()
            prepare_runtime_layout(root)
            attempt = root / "endpoint-review" / "one"
            scratch = ctc._directory(attempt) / "context-000"
            scratch.mkdir(parents=True)
            (scratch / "prepared.json").write_text(json.dumps({
                "model": {"snapshot": "fixed"}, "modelPath": str(root / "model")
            }))
            directory = repeat._directory(attempt)
            directory.mkdir(parents=True)
            plan, assessment, records = prediction_case()
            (directory / "repeat-inference.json").write_text(json.dumps(records))
            declaration = {
                           "occurrences": [{"occurrenceId": "test-occurrence"}]}
            canonical, mapping, evidence = bind_content(root, declaration)
            packet = {"method": ctc.METHOD, "manifestSha256": "bound",
                      "occurrences": [{"assessment": assessment}]}
            base = {"occurrences": [{"inference": {"model": {"snapshot": "fixed"}}}]}
            with patch.object(repeat, "reference_plan", return_value=plan), \
                    patch.object(ctc, "verify_stored_packet", return_value=base), \
                    patch.object(ctc, "_invoke"), patch.object(repeat, "verify_packet"), \
                    patch.object(repeat, "run_process_tree", return_value=subprocess.CompletedProcess([], 0, "", "")):
                with self.assertRaisesRegex(ValueError, "unavailable"):
                    repeat.write_packet(attempt, declaration, "source", root / "audio.wav",
                        {"projectDir": str(root), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, packet)
            self.assertFalse((directory / "manifest.json").exists())

    def test_blank_only_frames_keep_the_time_grid_without_becoming_matches(self):
        rng = np.random.default_rng(193)
        phones = rng.uniform(.01, 1, (18, 7))
        phones /= phones.sum(axis=1, keepdims=True)
        indices = [16, 17, *range(4), 4, 4, 5, 5, 6, 6, 7, 7, *range(8, 18), 0, 1]
        a = np.log(np.column_stack((np.full(18, .2), phones*.8)))
        b = np.log(np.column_stack((np.full(len(indices), .85), phones[indices]*.15)))
        silence = np.log([1-7e-8, *([1e-8]*7)])
        a[8] = silence
        b[np.asarray(indices) == 8] = silence
        actual = transfer(a, np.arange(18)*20., b, 5000+np.arange(len(indices))*20.,
                          0, [40, 180, 280])
        np.testing.assert_allclose(actual, [5080, 5300, 5400], atol=1e-6)
        with self.assertRaisesRegex(ValueError, "no discriminating"):
            transfer(np.tile(silence, (18, 1)), np.arange(18)*20., b,
                     5000+np.arange(len(indices))*20., 0, [40])

    def test_repeat_can_resolve_an_end_without_trusting_a_gap_smeared_ctc_offset(self):
        plan, base, old_records = prediction_case()
        # A declared outlier extends across silence, while the repeated audio
        # transfers the final phoneme edge to the existing near candidate.
        plan["target"]["familyCandidates"]["language-wav2vec2"]["endMs"] = 39000
        base["endMs"] = {"result": "abstain", "candidateMs": None, "supportedFamilies": []}
        records = copy.deepcopy(old_records)
        result = assess(records, plan, base, prediction_version=4)
        self.assertEqual(result["startMs"]["candidateMs"], 6000)
        self.assertEqual(result["endMs"]["candidateMs"], 7900)
        self.assertEqual(base["endMs"]["result"], "abstain")
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as temp:
            attempt = Path(temp) / "endpoint-review/one"
            ctc_dir, repeat_dir = ctc._directory(attempt), repeat._directory(attempt)
            ctc_dir.mkdir(parents=True)
            repeat_dir.mkdir(parents=True)
            ctc_path, repeat_path = ctc_dir / "manifest.json", repeat_dir / "manifest.json"
            ctc_path.write_text(json.dumps({"method": ctc.METHOD, "scorerVersion": ctc.SCORER_VERSION, "reviewRequired": True,
                "occurrences": [{"occurrenceId": "target", "assessment": base}]}))
            manifest = {"method": repeat.METHOD, "predictionVersion": 4,
                "ctcEvidence": {"method": ctc.METHOD, "manifestSha256": repeat.sha256_file(ctc_path)},
                "occurrences": [{"occurrenceId": "target", "records": records, "plan": plan, "assessment": result}]}
            repeat_path.write_text(json.dumps(manifest))
            binding = {"method": repeat.METHOD, "manifestSha256": repeat.sha256_file(repeat_path)}
            self.assertEqual(repeat.verify_stored_packet(attempt, binding), manifest)
            manifest["predictionVersion"] = 4
            repeat_path.write_text(json.dumps(manifest))
            binding["manifestSha256"] = repeat.sha256_file(repeat_path)
            self.assertEqual(repeat.verify_stored_packet(attempt, binding), manifest)
            manifest["occurrences"][0]["assessment"] = copy.deepcopy(result)
            manifest["occurrences"][0]["assessment"]["endMs"]["candidateMs"] = 39000
            repeat_path.write_text(json.dumps(manifest))
            binding["manifestSha256"] = repeat.sha256_file(repeat_path)
            with self.assertRaisesRegex(ValueError, "differs from acoustic results"):
                repeat.verify_stored_packet(attempt, binding)
        # A poor interior word match, missing channel, or ambiguous end remains unresolved.
        bad = copy.deepcopy(records)
        bad[0]["mappedMs"][-3] += 500
        self.assertEqual(assess(bad, plan, base, prediction_version=4), base)
        self.assertEqual(assess(records[:-1], plan, base, prediction_version=4), base)
        plan["target"]["familyCandidates"]["language-wav2vec2"]["endMs"] = 7920
        self.assertEqual(assess(records, plan, base, prediction_version=4)["endMs"]["result"], "abstain")

    def test_new_prediction_rechecks_model_and_rejects_a_stale_ctc_binding(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            root = Path(name)
            attempt = root / "endpoint-review" / "one"
            scratch = root / "endpoint-ctc-evidence" / "one" / "context-000"
            scratch.mkdir(parents=True)
            (scratch / "prepared.json").write_text(json.dumps({"model": {"snapshot": "new"}}))
            declaration = {"occurrences": [{"occurrenceId": "test"}]}
            canonical, mapping, evidence = bind_content(root, declaration)
            packet = {"method": "local-ctc-boundary-v1", "manifestSha256": "old",
                      "occurrences": [{"assessment": {"startMs": {"result": "abstain"}, "endMs": {"result": "supported"}}}]}
            base = {"occurrences": [{"inference": {"model": {"snapshot": "old"}}}]}
            with patch.object(repeat, "reference_plan", return_value={"reference": {}}), patch('stemwise_engine.endpoint_ctc_evidence.verify_stored_packet', return_value=base), patch('stemwise_engine.endpoint_ctc_evidence._invoke') as prepare, patch.object(repeat, "run_process_tree") as infer:
                with self.assertRaisesRegex(ValueError, "model differs"):
                    repeat.write_packet(attempt, declaration, "source", root / "audio.wav",
                        {"projectDir": str(root), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, packet)
                prepare.assert_called_once_with(root, 'prepare-many', [scratch], 60, alignment=True)
                infer.assert_not_called()

    def test_reference_must_be_an_identical_resolved_phrase(self):
        lyrics = {"tracks": [{"kind": "original", "format": "plain", "text": "one two three\none two four\none two three"}]}
        mapping = {"performedOccurrences": [{"sourcePhysicalIndex": i} for i in range(3)]}
        families = prediction_case()[0]['reference']['familyCandidates']
        items = [{"publishDecision": {"result": "words"}, "familyCandidates": families, "selectedPublisherFamily": "mms-fa"} for _ in range(3)]
        items[2] = {"publishDecision": {"result": "unresolved"}}
        evidence = {"occurrenceCandidates": items}
        self.assertEqual(reference_plan({"performedIndex": 2}, mapping, evidence, lyrics)["reference"], items[0])
        items[0]["publishDecision"]["result"] = "unresolved"
        self.assertIsNone(reference_plan({"performedIndex": 2}, mapping, evidence, lyrics))

    def test_transfer_tracks_phonetic_pattern_and_rejects_flat_input(self):
        rng = np.random.default_rng(73)
        p = rng.uniform(.01, 1, (30, 8))
        p /= p.sum(axis=1, keepdims=True)
        logp = np.log(p)
        times = np.arange(30)*20.
        actual = transfer(logp, times, logp, times+1700, 0, [100, 220, 400])
        np.testing.assert_allclose(actual, [1800, 1920, 2100])
        edge_times = times + 2.5
        edges = transfer(logp, edge_times, logp, edge_times+1700, 0, [0, 600], reference_bounds=(0, 600))
        np.testing.assert_allclose(edges, [1702.5, 2282.5])
        with self.assertRaisesRegex(ValueError, 'timestamps'):
            transfer(logp, edge_times, logp, edge_times+1700, 0, [-1, 601], reference_bounds=(0, 600))
        with self.assertRaises(ValueError):
            transfer(np.full_like(logp, -np.log(8)), times, logp, times+1700, 0, [100])
        with self.assertRaises(ValueError):
            transfer(logp, times, logp, times[::-1], 0, [100])

    def test_prediction_needs_complete_stable_channels_and_matching_later_words(self):
        plan, base, records = prediction_case()
        target = plan["target"]
        self.assertEqual(assess(records, plan, base)["startMs"]["candidateMs"], 6000)
        self.assertEqual(assess(records[:-1], plan, base), base)
        changed = copy.deepcopy(records)
        changed[0]["mappedMs"][0] -= 400
        self.assertEqual(assess(changed, plan, base), base)
        changed = copy.deepcopy(records)
        changed[0]["mappedMs"][-1] -= 900
        self.assertEqual(assess(changed, plan, base), base)
        changed = copy.deepcopy(records)
        changed[0]["mappedMs"][6] -= 500
        self.assertEqual(assess(changed, plan, base), base)
        target["familyCandidates"]["language-wav2vec2"]["startMs"] = 6020
        self.assertEqual(assess(records, plan, base), base)

    def test_repeat_end_requires_an_eligible_target_end_claim(self):
        plan, base, records = prediction_case()
        candidates = plan['target']['familyCandidates']
        candidates['language-wav2vec2']['endMs'] = 39000
        candidates['language-wav2vec2']['words'][-1]['endMs'] = 39000
        base['endMs'] = ctc._assess([], candidates)['endMs']
        self.assertEqual(assess(records, plan, base)['endMs']['candidateMs'], 7900)
        for fault in ('zero-target-boundary', 'lexical-target-end'):
            changed = copy.deepcopy(plan)
            target = changed['target']['familyCandidates']
            if fault == 'zero-target-boundary':
                target['mms-fa']['words'][-1]['confidence'] = 0.
            else:
                target['owsm-ctc'] = target.pop('mms-fa')
            with self.subTest(fault=fault):
                self.assertEqual(assess(records, changed, base)['endMs']['result'], 'abstain')

    def test_transfer_follows_local_stretch_with_context_and_different_blank_mass(self):
        # Unique phone patterns define an independent correspondence. The target
        # repeats frames 4..7: a constant offset or uniform tempo cannot pass.
        rng = np.random.default_rng(193)
        phones = rng.uniform(.01, 1, (18, 7))
        phones /= phones.sum(axis=1, keepdims=True)
        indices = [16, 17, *range(4), 4, 4, 5, 5, 6, 6, 7, 7, *range(8, 18), 0, 1]
        def posterior(rows, blank_mass):
            return np.log(np.column_stack((np.full(len(rows), blank_mass), rows*(1-blank_mass))))
        actual = transfer(posterior(phones, .2), np.arange(18)*20.,
                          posterior(phones[indices], .85), 5000+np.arange(len(indices))*20.,
                          0, [40, 180, 280])
        np.testing.assert_allclose(actual, [5080, 5300, 5400], atol=1e-6)

    def test_stored_prediction_revalidates_real_bindings_decisions_and_selection(self):
        # Synthetic numeric inference replaces only expensive audio processing.
        # Packet parsing, CTC consensus, reference selection and publication
        # selection checks all run real code, with no verifier mocks.
        plan, base, records = prediction_case()
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            root = Path(name)
            attempt = root / "endpoint-review" / "one"
            canonical = root / "lyrics.json"
            canonical.write_text(json.dumps({"tracks": [{"kind": "original", "format": "plain",
                                                       "text": "one two three\none two three"}]}))
            mapping = {"performedOccurrences": [{"sourcePhysicalIndex": i} for i in range(2)]}
            evidence = {"occurrenceCandidates": [plan["reference"], plan["target"]]}
            occurrence = {"occurrenceId": "target", "performedIndex": 1,
                          "candidateFamilies": plan["target"]["familyCandidates"]}
            declaration = {"analysisAudio": {"sha256": "fixture-source"},
                           "canonicalPlainSha256": repeat.sha256_file(canonical), "occurrences": [occurrence]}
            plan['target']['publishDecision']['code'] = 'line-start-disagreement'
            canonical, mapping, evidence = bind_content(root, declaration,
                lyrics=json.loads(canonical.read_bytes()), mapping=mapping, evidence=evidence)
            ctc_manifest = {**declaration, "method": ctc.METHOD, "scorerVersion": ctc.SCORER_VERSION, "reviewRequired": True,
                "declarationSha256": "fixture-declaration", "occurrences": [{"occurrenceId": "target",
                    "inference": {"records": [{"route": r, "shiftMs": s, "decisions": {
                        "startMs": {"result": "abstain", "candidateMs": None},
                        "endMs": {"result": "supported", "candidateMs": 7900}}}
                        for r in ctc.ROUTES for s in (-5, 0, 5)]}, "assessment": base}]}
            expected = {"startMs": {"result": "supported", "candidateMs": 6000,
                        "supportedFamilies": ["mms-fa"], "reason": "stable-repeat-phonetic-prediction",
                        "predictionRangeMs": [5985, 6035]}, "endMs": base["endMs"]}
            def save(directory, manifest):
                directory.mkdir(parents=True, exist_ok=True)
                path = directory / "manifest.json"
                path.write_text(json.dumps(manifest))
                return {"method": manifest["method"], "manifestSha256": repeat.sha256_file(path)}
            ctc_binding = save(ctc._directory(attempt), ctc_manifest)
            manifest = {"method": repeat.METHOD, "predictionVersion": 4, "ctcEvidence": ctc_binding, "occurrences": [
                {"occurrenceId": "target", "plan": plan, "records": records, "assessment": expected}]}
            binding = save(repeat._directory(attempt), manifest)
            def verify(value=binding, declared=declaration, mapped=mapping):
                return repeat.verify_packet(attempt, declared, "fixture-declaration", root / "absent.wav",
                                            value, mapped, evidence, canonical)
            selection = [{"occurrenceId": "target", "startCandidateFamily": "mms-fa", "endCandidateFamily": "mms-fa"}]
            self.assertEqual(verify(), manifest)
            ctc.verify_selections(verify(), selection)
            with self.assertRaisesRegex(ValueError, "does not support"):
                ctc.verify_selections(verify(), [{**selection[0], "startCandidateFamily": "language-wav2vec2"}])
            from types import SimpleNamespace
            from stemwise_engine import alignment_endpoints as endpoints, mms_forced_alignment as mms
            from stemwise_engine import owsm_ctc_forced_alignment as owsm
            ref_words = plan['reference']['familyCandidates']['mms-fa']['words']
            native_line = {'lineId': 'reference', 'lineIndex': 0, 'sourcePhysicalIndex': 0,
                'startWord': 0, 'wordCount': 3, 'words': [
                    {'wordId': word['occurrenceWordId'], 'startWord': i, 'wordCount': 1}
                    for i, word in enumerate(ref_words)]}
            weak = mms._aggregate_records([native_line], [{'startMs': word['startMs'], 'endMs': word['endMs'],
                'confidence': .01 if i == 0 else .8, 'tokenCount': 1} for i, word in enumerate(ref_words)])[0]
            lexical = owsm._lexical_records([{'lineId': 'reference', 'sourcePhysicalIndex': 0,
                'words': [{'wordId': word['occurrenceWordId'], 'acousticText': text}
                          for word, text in zip(ref_words, ('one', 'two', 'three'))]}],
                [{'lineId': 'reference', 'tokenIds': [7, 8, 9], 'words': [
                    {'wordId': word['occurrenceWordId'], 'targetStart': i, 'targetEnd': i+1}
                    for i, word in enumerate(ref_words)]}], [
                    SimpleNamespace(token=token, start=start, end=end, score=-.1)
                    for token, (start, end) in zip((7, 8, 9), ((50, 75), (85, 110), (125, 145)))], 20., 4000)[0]
            for fault in ('weak-native-reference', 'lexical-end-reference'):
                broken = copy.deepcopy(manifest)
                ref = broken['occurrences'][0]['plan']['reference']['familyCandidates']
                if fault == 'weak-native-reference':
                    ref['mms-fa'] = endpoints._candidate_audit({**weak, 'route': 'mms'})
                else:
                    broken['occurrences'][0]['plan']['reference'].update(
                        selectedPublisherFamily='language-wav2vec2', familyCandidates={
                            'owsm-ctc': endpoints._candidate_audit({**lexical, 'route': owsm.ROUTES_BY_LANGUAGE['en']}),
                            'language-wav2vec2': ref['language-wav2vec2']})
                updated = save(repeat._directory(attempt), broken)
                with self.subTest(stored_reference=fault), self.assertRaisesRegex(ValueError, 'differs from acoustic results'):
                    repeat.verify_stored_packet(attempt, updated)
            binding.update(save(repeat._directory(attempt), manifest))
            manifest["predictionVersion"] = 4
            manifest["occurrences"][0]["records"] = copy.deepcopy(records)
            binding.update(save(repeat._directory(attempt), manifest))
            self.assertEqual(verify(), manifest)
            # Cached numeric results remain usable after scratch cleanup, without
            # launching a model worker or rewriting the decision.
            with patch.object(repeat, "run_process_tree", side_effect=AssertionError("unexpected inference")):
                cached = repeat.write_packet(attempt, declaration, "fixture-declaration", root / "absent.wav",
                    {}, mapping, evidence, canonical, {**ctc_binding, "occurrences": ctc_manifest["occurrences"]})
            self.assertFalse(cached["reviewRequired"])
            self.assertEqual(cached["occurrences"][0]["assessment"], expected)
            for fault in ("reported-choice", "missing-channel", "word-disagreement", "occurrence-id"):
                with self.subTest(fault=fault):
                    broken = copy.deepcopy(manifest)
                    entry = broken["occurrences"][0]
                    if fault == "reported-choice":
                        entry["assessment"]["startMs"]["candidateMs"] = 5600
                    elif fault == "missing-channel":
                        entry["records"].pop()
                    elif fault == "word-disagreement":
                        entry["records"][0]["mappedMs"][6] -= 500
                    else:
                        entry["occurrenceId"] = "other"
                    updated = save(repeat._directory(attempt), broken)
                    with self.assertRaisesRegex(ValueError, "differs from acoustic results"):
                        verify(updated)  # Rehashing an altered result must not make it valid.
            binding.update(save(repeat._directory(attempt), manifest))
            changed_source = {**declaration, "analysisAudio": {"sha256": "other-source"}}
            with self.assertRaisesRegex(ValueError, "bindings are stale"):
                verify(declared=changed_source)
            evidence["occurrenceCandidates"][0]["publishDecision"]["result"] = "unresolved"
            with self.assertRaises(LyricsContentBlocker):
                verify()
            canonical.write_text("changed approved words")
            with self.assertRaisesRegex(ValueError, "authority is stale"):
                verify()


if __name__ == "__main__":
    unittest.main()
