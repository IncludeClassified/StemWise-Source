import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np
import soundfile as sf
from stemwise_engine.common import sha256_file
from stemwise_engine.endpoint_vocal_evidence import _vocal_samples

import stemwise_engine.endpoint_owsm_ctc as owsm
import test_endpoint_content_admission as content_fixtures


def acoustic_case():
    frame_ms = 26000/324  # Independent pinned runtime ratio, not nominal 80 ms.
    families = {"a": {"startMs": 960, "endMs": 2000},
                "b": {"startMs": 1200, "endMs": 2300},
                "owsm-ctc": {"startMs": 1560, "endMs": 2700}}
    records = []
    for route in owsm.ROUTES:
        for shift, gain in ((-5, .9), (0, 1.), (5, 1.1)):
            decisions = {}
            for boundary, frame in (("startMs", 12), ("endMs", 25)):
                mass = np.full(40, .2/39)
                mass[frame] = .8
                decisions[boundary] = owsm.ctc.select_candidate(mass,
                    [c[boundary] for c in families.values()], origin_ms=shift, step_ms=frame_ms)
            records.append({"route": route, "shiftMs": shift, "gain": gain,
                "originMs": shift, "stepMs": frame_ms, "decisions": decisions})
    return families, records


class MultilingualCtcTests(unittest.TestCase):
    def test_owsm_start_requires_raw_eligible_independent_corroborators(self):
        families, records = acoustic_case()
        families = dict(zip(('mms-fa', 'language-wav2vec2', 'owsm-ctc'), families.values()))
        families['mms-fa']['words'] = [{'startMs': 960, 'endMs': 2000,
            'confidence': .01, 'acousticTokenCount': 1}]
        rejected = owsm.assess(records, families)
        self.assertEqual(rejected['startMs']['result'], 'abstain')
        families['mms-fa']['words'][0]['confidence'] = .9
        accepted = owsm.assess(records, families)
        self.assertEqual(accepted['startMs']['supportedFamilies'], ['mms-fa'])
        self.assertEqual(accepted['endMs']['result'], 'abstain')

    def test_lexical_posterior_cannot_create_sung_end_support_but_keeps_admitted_parent_end(self):
        families, records = acoustic_case()
        actual = owsm.assess(records, families)
        self.assertEqual(actual['startMs']['result'], 'supported')
        self.assertEqual(actual['endMs']['result'], 'abstain')
        parent_records = []
        for route in owsm.ctc.ROUTES:
            for shift in (-5, 0, 5):
                decisions = {}
                for boundary, frame in (('startMs', 48), ('endMs', 100)):
                    mass = np.full(151, .2/150)
                    mass[frame] = .8
                    decisions[boundary] = owsm.ctc.select_candidate(mass,
                        [candidate[boundary] for candidate in families.values()], origin_ms=shift, step_ms=20)
                parent_records.append({'route': route, 'shiftMs': shift, 'decisions': decisions})
        admitted = owsm.ctc._assess(parent_records, families)
        self.assertEqual(admitted['endMs']['result'], 'supported')
        merged = owsm._assessment({'conditionings': {'en': {'records': records}}}, families, admitted)
        self.assertEqual(merged['endMs'], admitted['endMs'])

    def setUp(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        self.cuda_device = {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        device = patch.object(owsm.worker, 'cuda_device_identity', return_value=self.cuda_device)
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
                    stdout=json.dumps({'0': {'model': owsm.numerical_model(), 'records': []}}))
                with patch.object(endpoint_context_cache, 'read_context_result', return_value=None), \
                        patch.object(owsm, 'run_process_tree', return_value=result), \
                        self.assertRaises(error):
                    owsm._infer(root, [{'index': 0, 'path': str(root)}], 1)

    def test_decoders_evaluate_only_the_requested_forward_path(self):
        import stemwise_engine.endpoint_mms_prediction as mms
        import stemwise_engine.ctc_numerics as ctc_numerics
        probability = np.full((9, 3), .01)
        probability[:, 0] = .98
        probability[2] = [.01, .98, .01]
        probability[6] = [.01, .98, .01]
        logp = np.log(probability)
        edges = np.arange(10) * 20.
        for estimator, expected in (('viterbi', 0), ('posterior', 1), ('posterior+risk', 1)):
            with self.subTest(estimator=estimator), patch.object(
                    ctc_numerics, 'forward_path', wraps=ctc_numerics.forward_path) as redundant, patch.object(
                    mms, '_forward_path', wraps=mms._forward_path) as constrained:
                decoded = mms.decode(logp, [1, 1], [0, 1], ['first', 'last'], edges,
                    {'first': (0, 100), 'last': (100, 180)}, estimator=estimator)
                timings, confidence = decoded['posterior'] if estimator == 'posterior+risk' else decoded
                self.assertEqual(redundant.call_count, 0)
                self.assertEqual(constrained.call_count, expected)
                self.assertEqual([item['wordId'] for item in timings], ['first', 'last'])
                self.assertLessEqual(timings[0]['endMs'], 100)
                self.assertGreaterEqual(timings[1]['startMs'], 100)
                self.assertLessEqual(timings[1]['endMs'], 180)
                self.assertGreater(confidence, 0)

    def test_retained_context_accepts_rounded_eof_but_rejects_changed_or_truncated_audio(self):
        import stemwise_engine.endpoint_vocal_evidence as vocal
        for fault in (None, 'short', 'changed', 'beyond-end'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[2] / 'work') as temp:
                root = Path(temp)
                attempt = root / 'job' / 'endpoint-review' / ('a' * 32)
                attempt.mkdir(parents=True)
                audio = root / 'analysis.wav'
                samples = np.column_stack((np.linspace(-.3, .4, 48000 - 16),
                                           np.linspace(.2, -.1, 48000 - 16))).astype('float32')
                owsm.sf.write(audio, samples, 48000, subtype='FLOAT')
                scratch = owsm.ctc._directory(attempt) / 'context-000'
                scratch.mkdir(parents=True)
                plan = {'startMs': 0, 'endMs': 1001 if fault == 'beyond-end' else 1000}
                (scratch / 'request.private.json').write_text(json.dumps(plan))
                retained = samples[:-1] if fault == 'short' else samples.copy()
                if fault == 'changed':
                    retained[20, 0] += .01
                owsm.sf.write(scratch / 'source.wav', retained, 48000, subtype='FLOAT')
                (scratch / 'prepared.json').write_text(json.dumps({'modelPath': 'retained-ctc-model',
                    'model': {'snapshot': 'current-ctc'}, 'views': {
                    route: {'path': 'source.wav', 'provenance': {
                        'sha256': owsm.sha256_file(scratch/'source.wav'),
                        'byteCount': (scratch/'source.wav').stat().st_size}} for route in owsm.ROUTES}}))
                outputs = {route: (scratch/'source.wav', {'sha256': owsm.sha256_file(scratch/'source.wav'),
                    'byteCount': (scratch/'source.wav').stat().st_size}) for route in owsm.ROUTES}
                with patch.object(vocal, '_separate_many', return_value=[outputs]) as separator:
                    if fault:
                        with self.assertRaisesRegex(ValueError, 'crop differs'):
                            owsm._contexts(attempt, [plan], [['en']], audio, {})
                    else:
                        contexts = owsm._contexts(attempt, [plan], [['en']], audio, {})
                        self.assertEqual(len(contexts), 1)
                        self.assertEqual(contexts[0]['path'], str(scratch))
                        self.assertEqual(set(contexts[0]['inputs']),
                                         {'request.private.json', 'prepared.json', 'source.wav'})
                        separator.assert_called_once_with({}, [(scratch/'source.wav', scratch)])
                        prepared = json.loads((scratch/'prepared.json').read_bytes())
                        self.assertEqual(prepared['modelPath'], 'retained-ctc-model')
                        self.assertEqual(prepared['model'], {'snapshot': 'current-ctc'})

    def test_delivered_pending_review_retains_both_owsm_context_owners_without_repreparing(self):
        from workflow_fixture import initialized_workflow
        for owner in ('ctc', 'owsm'):
            with self.subTest(owner=owner), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[3]/'Test suite/work') as temporary:
                root = Path(temporary)
                workflow = initialized_workflow(root)
                video, attempt_id = 'abcdefghijk', 'a'*32
                attempt = workflow._endpoint_review_attempt_dir(video, attempt_id)
                context = (owsm.ctc._directory(attempt) if owner == 'ctc' else owsm._directory(attempt))/'context-000'
                context.mkdir(parents=True)
                audio = root/'analysis.wav'
                samples = np.linspace(-.2, .3, 4800, dtype='float32').reshape(-1, 1)
                sf.write(audio, samples, 48000, subtype='FLOAT')
                sf.write(context/'source.wav', samples, 48000, subtype='FLOAT')
                plan = {'startMs': 0, 'endMs': 100}
                (context/'request.private.json').write_text(json.dumps(plan), encoding='utf-8')
                (context/'prepared.json').write_text(json.dumps({'views': {route: {
                    'path': 'source.wav', 'provenance': {'sha256': sha256_file(context/'source.wav'),
                        'byteCount': (context/'source.wav').stat().st_size}} for route in owsm.ROUTES}}), encoding='utf-8')
                job = {'videoId': video, 'state': 'DELIVERED', 'endpointReview': {'attemptId': attempt_id}}
                with patch('stemwise_engine.endpoint_vocal_evidence._separate_many',
                           side_effect=AssertionError('must retain prepared context')):
                    before = owsm._contexts(attempt, [plan], [['en']], audio, {}, prepare=False)
                    workflow._cleanup_completed_endpoint_contexts(job)
                    self.assertEqual(owsm._contexts(attempt, [plan], [['en']], audio, {}, prepare=False), before)
                    job['endpointReview']['result'] = 'accepted'
                    workflow._cleanup_completed_endpoint_contexts(job)
                    self.assertFalse(context.exists())
                    with self.assertRaises(ValueError):
                        owsm._contexts(attempt, [plan], [['en']], audio, {}, prepare=False)

    def test_neighborhood_source_probe_is_read_only_and_rejects_changed_inputs(self):
        from stemwise_engine.common import sha256_file
        probability = np.tile([.995,.001,.001,.001,.001,.001],(12,1))
        for frame,token in ((2,2),(5,3),(8,4)):
            probability[frame] = .001
            probability[frame,token] = .995
        calls = []
        def emissions(_model,signal,origin):
            calls.append(len(signal))
            edges = origin+np.arange(13)*80.
            edges[-1] = origin+len(signal)/16
            return np.log(probability),edges
        identities = ['outside-left','before','target','after','outside-right']
        targets = ([1,2,3,4,5],identities,[0,1,2,3,4])
        model = SimpleNamespace(config=SimpleNamespace(blank=0))
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as temp:
            project = Path(temp)
            context = project/'context-000'
            context.mkdir()
            plan = {'language':'mul','startMs':1000,'endMs':2200,'request':{'lines':[
                {'lineId':word,'words':[{'wordId':word,'acousticText':word}]} for word in identities]}}
            (context/'request.private.json').write_text(json.dumps(plan))
            owsm.sf.write(context/'source.wav',np.column_stack((np.ones(57600),np.zeros(57600))),48000,subtype='FLOAT')
            views = {}
            for route in owsm.ROUTES:
                path = context/(route+'.wav')
                owsm.sf.write(path,np.column_stack((np.ones(52920),np.zeros(52920))),44100,subtype='FLOAT')
                views[route] = {'path':path.name,'provenance':{'sha256':sha256_file(path),
                    'canonicalTimeTransform':dict.fromkeys(('offsetFrames','trimFrames','padFrames',
                                                          'separatorLatencyFrames','measuredResidualMs'),0)}}
            (context/'prepared.json').write_text(json.dumps({'views':views}))
            before = {p.name:sha256_file(p) for p in context.iterdir()}
            probe = {'inputs':{name:before[name] for name in ('source.wav','request.private.json','prepared.json')},
                'window':{'occurrenceId':'target','startMs':1100,'endMs':2060,
                          'wordIds':['before','target','after'],'targetWordIds':['target']},'bounds':{}}
            with patch.object(owsm,'prediction_context',return_value=(model,targets)),patch.object(
                    owsm,'prediction_emissions',side_effect=emissions):
                result = owsm.probe_neighborhood_sources(project,context,probe)
                self.assertEqual(result['model']['fusion'],'none;each-source-decoded-independently')
                self.assertEqual(result['model']['decoder'],'complete-ctc-neighborhood-posterior-and-viterbi-v1')
                self.assertEqual(result['contextWordCount'],3)
                self.assertEqual(result['targetWordCount'],1)
                self.assertEqual(set(result['sources']),{'original',*owsm.ROUTES})
                for source in result['sources'].values():
                    self.assertEqual(source['representation']['selected'],'left')
                    for row in source['decoders'].values():
                        self.assertEqual(row['startMs'],[1505.]*3+[1510.]*3+[1515.]*3)
                        self.assertEqual(row['endMs'],[1585.]*3+[1590.]*3+[1595.]*3)
                        self.assertTrue(row['startStable'] and row['endStable'])
                # 960 ms crop, 10 ms end margin, and 5/10/15 ms start margins at 16 kHz.
                self.assertEqual(set(calls),{14960,15040,15120},'only the bounded crop may reach the model')
                calls_before = calls[:]
                stale = copy.deepcopy(probe)
                stale['inputs']['source.wav'] = '0'*64
                with self.assertRaisesRegex(ValueError,'binding'):
                    owsm.probe_neighborhood_sources(project,context,stale)
                incomplete = copy.deepcopy(probe)
                incomplete['window']['wordIds'].remove('after')
                with self.assertRaisesRegex(ValueError,'neighbor'):
                    owsm.probe_neighborhood_sources(project,context,incomplete)
                self.assertEqual(calls,calls_before)
            self.assertEqual({p.name:sha256_file(p) for p in context.iterdir()},before)

    def test_single_source_probe_keeps_neighbors_and_every_gain_crop_check(self):
        probability = np.tile([.997,.001,.001,.001],(12,1))
        for frame,token in ((2,1),(5,2),(8,3)):
            probability[frame] = .001
            probability[frame,token] = .997
        calls = []
        def emissions(_model,signal,origin):
            calls.append((origin,float(signal[0])))
            return np.log(probability),origin+np.linspace(0,len(signal)/16,13)
        targets = ([1,2,3],['before','target','after'],[0,1,2])
        audio = np.column_stack((np.ones(15360),np.zeros(15360))).astype(np.float32)
        model = SimpleNamespace(config=SimpleNamespace(blank=0))
        options = dict(at_start=True,at_end=True,emission_reader=emissions,
                       estimator='both',ensemble_route='original',source_routes=('original',))
        records,representations = owsm.infer_fused_views({'original':audio},model,targets,['target'],{},1000,
                                                       **options)
        self.assertEqual(representations['original']['selected'],'left')
        for rows in records.values():
            self.assertEqual({(r['shiftMs'],r['gain']) for r in rows},
                             {(s,g) for s in (-5,0,5) for g in (.9,1.,1.1)})
            self.assertTrue(all(r['members'] == ['original'] for r in rows))
            self.assertTrue(all([w['wordId'] for w in r['words']] == ['target'] for r in rows))
            nominal = next(r for r in rows if r['shiftMs'] == 0 and r['gain'] == 1.)
            self.assertEqual([(w['startMs'],w['endMs']) for w in nominal['words']],[(1400.,1480.)])
        self.assertEqual(len(calls),11,'a single source must not be copied into three fake stem views')
        with self.assertRaises(ValueError):
            owsm.infer_fused_views({'unknown':audio},model,targets,['target'],{},1000,
                                  **{**options,'source_routes':('unknown',)})
        with self.assertRaises(ValueError):
            owsm.infer_fused_views({'original':audio},model,targets,['target'],{},1000,
                                  **{k:v for k,v in options.items() if k != 'source_routes'})

    def test_unconditioned_context_retains_complete_targets_and_requires_trained_prefix(self):
        words = ['hello', 'مرحبا', 'বাংলা']
        tokens = ['<unk>', '<nolang>', *words]
        converter = SimpleNamespace(tokens2ids=lambda values: [tokens.index(v) for v in values],
            ids2tokens=lambda values: [tokens[i] for i in values])
        aligner = SimpleNamespace(token_list=tokens[:], lang_sym='<eng>', config=SimpleNamespace(char_list=tokens),
            preprocess_fn=SimpleNamespace(text_cleaner=lambda value: value,
                tokenizer=SimpleNamespace(text2tokens=lambda value: value.split()), token_id_converter=converter))
        request = {'lines': [{'lineId': 'line', 'words': [
            {'wordId': f'w{i}', 'acousticText': text} for i, text in enumerate(words)]}]}
        with patch.object(owsm, '_request', return_value=request):
            actual, targets = owsm.prediction_context(Path('project'), Path('context'), {}, None,
                aligner, unconditioned=True)
            self.assertIs(actual, aligner)
            self.assertEqual(actual.lang_sym, '<nolang>')
            self.assertEqual(targets, ([2, 3, 4], ['w0', 'w1', 'w2'], [0, 1, 2]))
            actual.token_list.remove('<nolang>')
            with self.assertRaisesRegex(ValueError, 'lacks trained no-language conditioning'):
                owsm.prediction_context(Path('project'), Path('context'), {}, None, aligner, unconditioned=True)

    def test_repeat_parent_uses_bound_ctc_authority_without_changing_packets(self):
        import stemwise_engine.endpoint_repeat_alignment as repeat
        families, _ = acoustic_case()
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            attempt = Path(name) / "endpoint-review/attempt"
            declaration = {"analysisAudio": {"sha256": "a" * 64, "durationMs": 3000},
                "canonicalPlainSha256": "b" * 64, "occurrences": [
                    {"occurrenceId": key, "candidateFamilies": families} for key in ("one", "two")]}
            base = {"method": owsm.ctc.METHOD, "scorerVersion": owsm.ctc.SCORER_VERSION, "declarationSha256": "c" * 64,
                "analysisAudio": declaration["analysisAudio"],
                "canonicalPlainSha256": declaration["canonicalPlainSha256"], "reviewRequired": True,
                "occurrences": [{"occurrenceId": e["occurrenceId"], "assessment": owsm.ctc._assess([], families)}
                    for e in declaration["occurrences"]]}
            base_path = owsm.ctc._directory(attempt) / "manifest.json"
            base_path.parent.mkdir(parents=True)
            base_path.write_text(json.dumps(base))
            parent = {"method": repeat.METHOD, "predictionVersion": repeat.PREDICTION_VERSION,
                "ctcEvidence": {"method": owsm.ctc.METHOD, "manifestSha256": owsm.sha256_file(base_path)},
                "occurrences": [{**e, "plan": None, "records": []} for e in base["occurrences"]]}
            parent_path = repeat._directory(attempt) / "manifest.json"
            parent_path.parent.mkdir(parents=True)
            parent_path.write_text(json.dumps(parent))
            binding = {"method": repeat.METHOD, "manifestSha256": owsm.sha256_file(parent_path)}
            before = {p: p.read_bytes() for p in (base_path, parent_path)}
            self.assertEqual(owsm._parent(attempt, binding, declaration, "c" * 64), parent)
            for fault in ("audio", "canonical", "declaration", "order", "missing"):
                changed, digest = copy.deepcopy(declaration), "c" * 64
                if fault == "audio": changed["analysisAudio"]["sha256"] = "d" * 64
                elif fault == "canonical": changed["canonicalPlainSha256"] = "d" * 64
                elif fault == "declaration": digest = "d" * 64
                elif fault == "order": changed["occurrences"].reverse()
                else: changed["occurrences"].pop()
                with self.subTest(fault=fault), self.assertRaises(ValueError):
                    owsm._parent(attempt, binding, changed, digest)
                self.assertEqual({p: p.read_bytes() for p in before}, before)
            # Matching rewritten packet hashes still cannot change the recording authority.
            changed_base = copy.deepcopy(base)
            changed_base["analysisAudio"]["sha256"] = "d" * 64
            base_path.write_text(json.dumps(changed_base))
            changed_parent = copy.deepcopy(parent)
            changed_parent["ctcEvidence"]["manifestSha256"] = owsm.sha256_file(base_path)
            parent_path.write_text(json.dumps(changed_parent))
            changed_binding = {"method": repeat.METHOD, "manifestSha256": owsm.sha256_file(parent_path)}
            with self.assertRaisesRegex(ValueError, "CTC evidence bindings are stale"):
                owsm._parent(attempt, changed_binding, declaration, "c" * 64)

    def test_declared_zulu_target_keeps_arabic_context_without_espeak(self):
        plan = {"language": "mul", "targetLine": 1, "request": {"lines": [
            {"words": [{"acousticText": "سلام"}]},
            {"words": [{"acousticText": "sawubona"}, {"acousticText": "umhlaba"}]}]}}
        original = copy.deepcopy(plan)
        self.assertEqual(owsm._routes_for_context(plan, ["ar", "zu"]), ["zu"])
        self.assertEqual(plan, original)
        # Two declared Latin languages cannot be assigned by script alone.
        self.assertEqual(owsm._routes_for_context(plan, ["ar", "en", "zu"]), [])
        plan["request"]["lines"][1]["words"][0]["acousticText"] = "שלום"
        self.assertEqual(owsm._routes_for_context(plan, ["ar", "zu"]), [])

    def test_declared_turkish_context_preserves_arabic_without_guessing_latin_language(self):
        plan = {'language': 'mul', 'targetLine': 1, 'request': {'lines': [
            {'words': [{'acousticText': 'سلام'}]},
            {'words': [{'acousticText': 'görüşürüz'}]}]}}
        before = copy.deepcopy(plan)
        self.assertEqual(owsm._routes_for_context(plan, ['ar', 'tr']), ['tr'])
        self.assertEqual(owsm._routes_for_context(plan, ['ar', 'en', 'tr']), [])
        self.assertEqual(plan, before)

    def test_native_grid_requires_stability_and_independent_corroboration(self):
        families, records = acoustic_case()
        result = owsm.assess(records, families)
        self.assertEqual((result["startMs"]["candidateMs"], result["endMs"]["candidateMs"]), (960, None))
        self.assertEqual(result['endMs']['result'], 'abstain')
        low, high = result["startMs"]["predictionRangeMs"]
        self.assertAlmostEqual(low, 917.8395432098766)
        self.assertAlmostEqual(high, 1008.0864567901235)
        for fault in ("missing", "weak", "grid", "fake-pass", "tie", "no-independent-family"):
            changed, candidates = copy.deepcopy(records), copy.deepcopy(families)
            if fault == "missing": changed.pop()
            elif fault == "weak": changed[0]["decisions"]["startMs"]["peakMass"] = .49
            elif fault == "grid": changed[0]["originMs"] += 1
            elif fault == "fake-pass": changed[0]["decisions"]["startMs"].update(result="supported", candidateMs=960, peakMass=.1)
            elif fault == "tie":
                candidates["b"]["startMs"] = 970
                for row in changed:
                    d = row["decisions"]["startMs"]
                    d["candidateScores"][1].update(candidateMs=970, frameEdge=12, mass=.8)
            else: candidates["b"]["startMs"] = 4000
            with self.subTest(fault=fault):
                self.assertEqual(owsm.assess(changed, candidates)["startMs"]["result"], "abstain")
        previous = owsm.ctc._assess([], families)
        previous["startMs"] = {"result": "supported", "candidateMs": 44390, "supportedFamilies": ["mms-fa"]}
        inference = {"conditionings": {"ar": {"records": records}, "zu": {"records": copy.deepcopy(records)}}}
        inference["conditionings"]["zu"]["records"][0]["decisions"]["endMs"]["peakMass"] = .1
        result = owsm._assessment(inference, families, previous)
        self.assertEqual(result["startMs"], previous["startMs"])
        self.assertEqual(result["endMs"]["result"], "abstain")

    def test_publication_rejects_fabricated_approval_and_preserves_parent(self):
        families, records = acoustic_case()
        inference = {"model": owsm.numerical_model(), "conditionings": {"zu": {"records": records}}}
        previous = {"occurrences": [{"assessment": owsm.ctc._assess([], families)}]}
        packet = {"model": owsm.numerical_model(), "previousEvidence": {"method": "parent", "manifestSha256": "source"},
            "contexts": [{"index": 0}], "occurrences": [{"candidateFamilies": families, "languages": ["zu"],
                "assessment": owsm._assessment(inference, families, previous["occurrences"][0]["assessment"])}]}
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / 'Test suite/work') as name:
            root = Path(name)
            marker = root / "parent.json"
            declaration, mapping, evidence, lyrics, canonical, audio, recognition, _ = \
                content_fixtures.EndpointContentAdmissionTests().fixture(root)
            declaration['occurrences'][0]['candidateFamilies'] = families
            for row in evidence['occurrenceCandidates']:
                row['familyCandidates'] = families
            def plans(*_args):
                planned = owsm.ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=recognition.parent)
                return planned, [['zu']]
            marker.write_text(json.dumps(previous))
            before = marker.read_bytes()
            weak = copy.deepcopy(inference)
            weak["conditionings"]["zu"]["records"][0]["decisions"]["startMs"]["peakMass"] = .1
            with patch.object(owsm, "verify_stored_packet", return_value=packet), \
                    patch.object(owsm, "_plans", side_effect=plans), \
                    patch.object(owsm, "_parent", return_value=previous), \
                    patch.object(owsm, "_contexts", return_value=[{"index": 0}]), \
                    patch.object(owsm, "_infer", return_value={"0": weak}) as replay:
                args = (root, declaration, "declaration", audio, {}, {"projectDir": str(root), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical)
                with self.assertRaisesRegex(ValueError, "did not reproduce"):
                    owsm.verify_packet(*args, replay=True)
                self.assertEqual(marker.read_bytes(), before)
                with patch.object(owsm, '_infer', side_effect=AssertionError('ordinary approval replayed models')):
                    self.assertEqual(owsm.verify_packet(*args), packet)
                replay.return_value = {"0": inference}
                self.assertEqual(owsm.verify_packet(*args, replay=True), packet)

    def test_pending_packet_skips_unrequested_inference_and_preserves_authority(self):
        families, records = acoustic_case()
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / 'work') as name:
            root = Path(name)
            audio = root / "source.wav"
            audio.write_bytes(b"bound fixture PCM; audio admission substituted")
            attempt = root / "endpoint-review/attempt"
            directory = owsm._directory(attempt)
            directory.mkdir(parents=True)
            previous_binding = {"method": "parent", "manifestSha256": "0" * 64}
            previous = {"occurrences": [{"assessment": owsm.ctc._assess([], families)}]}
            declaration = {"analysisAudio": {"sha256": owsm.sha256_file(audio)}, "canonicalPlainSha256": "canonical"}
            contexts = [{"index": 0, "path": str(root), "languages": ["ar"], "inputs": {}}]
            entry = {"occurrenceId": "one", "candidateFamilies": families, "languages": ["ar"],
                "assessment": previous["occurrences"][0]["assessment"]}
            packet = {"method": owsm.METHOD, "model": owsm.numerical_model(), "contextVersion": owsm.ctc.SCORER_VERSION,
                "declarationSha256": "decl", **declaration, "previousEvidence": previous_binding,
                "contexts": contexts, "occurrences": [entry]}
            packet_path = directory / "manifest.json"
            packet_path.write_text(json.dumps(packet))
            before = packet_path.read_bytes()
            with patch.object(owsm, '_parent', return_value=previous), patch.object(
                    owsm.worker, 'cuda_device_identity', side_effect=AssertionError('stored inspection initialized CUDA')):
                recorded = owsm.verify_stored_packet(attempt, {'method': owsm.METHOD,
                    'manifestSha256': owsm.sha256_file(packet_path)})
                self.assertEqual(recorded['model']['cudaDevice'], self.cuda_device)
            records = records[:3]
            for row in records:
                row["route"] = "original-control"
            original = {"0": {"model": owsm.numerical_model(), "conditionings": {"ar": {"records": records}}}}
            with patch.object(owsm, "_plans", return_value=([{"occurrenceId": "one", "candidateFamilies": families}], [["ar"]])), \
                    patch.object(owsm, "_parent", return_value=previous), \
                    patch.object(owsm, "_contexts", return_value=contexts), \
                    patch.object(owsm, "verify_stored_packet", return_value=packet), \
                    patch.object(owsm, "_infer", return_value=original) as infer:
                args = (attempt, declaration, "decl", audio, {"projectDir": str(root), "analysisTimeoutMinutes": 1}, {}, {}, root, previous_binding)
                result = owsm.write_packet(*args)
                self.assertNotIn("sourceControl", result)
                infer.assert_not_called()
                self.assertTrue(result["reviewRequired"])
                self.assertEqual(result["occurrences"][0]["assessment"], previous["occurrences"][0]["assessment"])
                self.assertFalse((directory / "original-control.private.json").exists())
                self.assertEqual(packet_path.read_bytes(), before)
                owsm.write_packet(*args)
                infer.assert_not_called()
                alternate = {**self.cuda_device, 'name': 'Another capable accelerator'}
                with patch.object(owsm.worker, 'cuda_device_identity', return_value=alternate):
                    with self.assertRaisesRegex(ValueError, 'retained authority changed'):
                        owsm.write_packet(*args)
                self.assertEqual(packet_path.read_bytes(), before)
                infer.assert_not_called()



class SharedCtcNumericsTests(unittest.TestCase):
    def setUp(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        self.cuda_device = {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}
        device = patch.object(owsm.worker, 'cuda_device_identity', return_value=self.cuda_device)
        device.start()
        self.addCleanup(device.stop)
        import stemwise_engine.endpoint_mms_prediction as mms
        from stemwise_engine.model_registry import runtime_profile
        profile = runtime_profile('main')
        self.execution = {'runtime': {**{key: profile['packages'][key] for key in
            ('torch', 'torchaudio', 'demucs', 'uroman')}, 'python': profile['python'],
            'cudaRuntime': '12.8', 'cudnn': '91002'}, 'device': {'type': 'cuda', **self.cuda_device}}
        observation = patch.object(mms, 'numerical_model', side_effect=lambda template:
            {**template, 'execution': copy.deepcopy(self.execution)})
        observation.start()
        self.addCleanup(observation.stop)

    def test_source_probe_fractional_eof_preserves_real_pcm_without_clipping_interior_windows(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        work = Path(__file__).resolve().parents[2] / 'work'
        work.mkdir(exist_ok=True)
        words = ['before', 'target', 'after']
        for frames, duration, original_end in ((48032, 1001, 16011), (48016, 1000, 16000)):
            with self.subTest(frames=frames), tempfile.TemporaryDirectory(dir=work) as name:
                project = Path(name)
                context = project / 'context'
                context.mkdir()
                plan = {'startMs': 3000, 'endMs': 3000 + duration, 'request': {'lines': [
                    {'lineId': word, 'words': [{'wordId': word}]} for word in words]}}
                (context / 'request.private.json').write_text(json.dumps(plan))
                samples = np.full((frames, 2), .25, dtype=np.float32)
                sf.write(context / 'source.wav', samples, 48000, subtype='FLOAT')
                views = {}
                for route in owsm.ROUTES:
                    path = context / (route + '.wav')
                    sf.write(path, samples, 48000, subtype='FLOAT')
                    views[route] = {'path': path.name, 'provenance': {
                        'sha256': sha256_file(path), 'canonicalTimeTransform': {
                            'offsetFrames': 0, 'trimFrames': 0, 'padFrames': 0,
                            'separatorLatencyFrames': 0, 'measuredResidualMs': 0}}}
                (context / 'prepared.json').write_text(json.dumps({'views': views}))
                inputs = {name: sha256_file(context / name) for name in (
                    'request.private.json', 'prepared.json', 'source.wav')}
                probe = {'inputs': inputs, 'bounds': {}, 'window': {
                    'occurrenceId': 'target', 'startMs': 3000, 'endMs': 3000 + duration,
                    'wordIds': words, 'targetWordIds': ['target']}}
                seen = {}
                def decode(sources, aligner, targets, wanted, bounds, origin, **kwargs):
                    self.assertEqual(wanted, words)
                    self.assertEqual(bounds, {})
                    self.assertEqual(origin, 3000)
                    route, pcm = next(iter(sources.items()))
                    self.assertEqual(pcm.shape[1], 2)
                    self.assertTrue(np.isfinite(pcm).all())
                    if route == 'original':
                        self.assertGreater(float(np.max(np.abs(pcm[-1]))), .1)
                    seen[route] = len(pcm)
                    return {'posterior': [], 'viterbi': []}, {route: {'selected': 'left'}}
                with patch.object(owsm, 'prediction_context', return_value=(
                        object(), ([1, 2, 3], words, [0, 1, 2]))), patch.object(
                        owsm, 'infer_fused_views', side_effect=decode):
                    result, _ = owsm.infer_neighborhood_sources(project, context, probe)
                    self.assertEqual(seen, {'original': original_end,
                        **dict.fromkeys(owsm.ROUTES, duration * 16)})
                    self.assertEqual(result['windowMs'], [3000, 3000 + duration])
                    self.assertEqual(result['contextWordIds'], words)
                    seen.clear()
                    if duration == 1001:
                        interior = copy.deepcopy(probe)
                        interior['window']['endMs'] = 4000
                        owsm.infer_neighborhood_sources(project, context, interior)
                        self.assertEqual(seen, dict.fromkeys(('original', *owsm.ROUTES), 16000))
                    outside = copy.deepcopy(probe)
                    outside['window']['endMs'] += 1
                    with self.assertRaisesRegex(ValueError, 'outside its retained source'):
                        owsm.infer_neighborhood_sources(project, context, outside)
                    sf.write(context / 'source.wav', samples[:-96], 48000, subtype='FLOAT')
                    truncated = copy.deepcopy(probe)
                    truncated['inputs']['source.wav'] = sha256_file(context / 'source.wav')
                    with self.assertRaisesRegex(ValueError, 'invalid rate, duration or channels'):
                        owsm.infer_neighborhood_sources(project, context, truncated)
                self.assertEqual(probe['inputs'], inputs)
                self.assertEqual(sha256_file(context / 'prepared.json'), inputs['prepared.json'])
                self.assertEqual(sha256_file(context / 'request.private.json'), inputs['request.private.json'])

    def test_boundary_risk_keeps_peak_and_required_repeat_gap(self):
        import stemwise_engine.endpoint_mms_prediction as mms
        edges = np.arange(11)*80.
        ending = np.zeros(11)
        ending[9] = 1
        for left,right in ((.21,.49),(.19,.51)):
            onset = np.zeros(11)
            onset[[1,2,7]] = [.3,left,right]
            actual = mms._minimum_risk_timings([onset,ending],[1],[0],['line'],edges)
            self.assertEqual(actual,[{'wordId':'line','startMs':560.,'endMs':720.}])
        # A plausible joint posterior over (first end, next start) is
        # (1,2):.40, (2,3):.35, (2,4):.25. Its separate modes (2,2)
        # cannot emit the repeated token without the required blank frame.
        curves = np.zeros((4,6))
        curves[0,0] = curves[3,5] = 1
        curves[1,[1,2]] = [.4,.6]
        curves[2,[2,3,4]] = [.4,.35,.25]
        actual = mms._minimum_risk_timings(curves,[1,1],[0,1],['first','last'],np.arange(6)*80.)
        self.assertEqual([(r['startMs'],r['endMs']) for r in actual],[(0.,160.),(240.,400.)])

    def test_boundary_risk_matches_independent_complete_ctc_paths(self):
        import stemwise_engine.endpoint_mms_prediction as mms
        probability = np.array([[.5,.3,.2],[.2,.6,.2],[.4,.3,.3],[.2,.6,.2],
                                [.3,.2,.5],[.2,.2,.6],[.5,.1,.4]])
        targets,words,labels = [1,1,2],['first','last'],[0,1,0,1,0,2,0]
        edges = np.arange(8)*80.
        states = [([s],1.) for s in (0,1)]
        for frame,row in enumerate(probability):
            if frame:
                states = [(path+[s],weight) for path,weight in states for s in range(path[-1],min(7,path[-1]+3))
                    if s-path[-1] < 2 or labels[s] != 0 and labels[s] != labels[path[-1]]]
            states = [(path,weight*(max(row[k] for k in range(3) if k != (1 if path[-1] == 0 else 2))
                        if path[-1] in (0,6) else row[labels[path[-1]]])) for path,weight in states]
        paths = [(path,weight) for path,weight in states if path[-1] in (5,6) and all(s in path for s in (1,3,5))]
        for owners in ([0,0,1],[0,1,1]):
            with self.subTest(owners=owners):
                weighted = []
                for path,weight in paths:
                    times = []
                    for word in range(2):
                        first = 2*owners.index(word)+1
                        last = 2*(len(owners)-1-owners[::-1].index(word))+1
                        times.extend((path.index(first),max(i for i,s in enumerate(path) if s == last)+1))
                    weighted.append((tuple(times),weight))
                total = sum(weight for _,weight in weighted)
                curves = np.zeros((4,8))
                for times,weight in weighted:
                    for boundary,time in enumerate(times):
                        curves[boundary,time] += weight/total
                feasible = {times for times,_ in weighted}
                best = max(sum(curves[i,t] for i,t in enumerate(times)) for times in feasible)
                with patch.object(mms,'_forward_path',wraps=mms._forward_path) as forward:
                    decoded = mms.decode(np.log(probability),targets,owners,words,edges,{},estimator='posterior+risk')
                self.assertEqual(forward.call_count,1,'median and risk must share posterior computation')
                self.assertEqual(set(decoded),{'posterior','risk'})
                actual,confidence = decoded['risk']
                times = tuple(int(value/80) for row in actual for value in (row['startMs'],row['endMs']))
                self.assertIn(times,feasible)
                self.assertAlmostEqual(sum(curves[i,t] for i,t in enumerate(times)),best,places=12)
                self.assertGreater(confidence,0)
                self.assertLessEqual(confidence,1)
                with self.assertRaises(ValueError):
                    mms.decode(np.log(probability),targets,owners,words,edges,{'last':[81,100]},
                               estimator='posterior+risk')

    def test_dual_decoders_share_inference_and_keep_every_acoustic_check(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        import stemwise_engine.endpoint_mms_prediction as mms
        probability = np.tile([.998, .001, .001], (8, 1))
        probability[2] = [.001, .998, .001]
        probability[5] = [.001, .001, .998]
        targets = ([1, 2], ['first', 'last'], [0, 1])
        windows = [{'occurrenceId': 'line', 'startMs': 0, 'endMs': 640,
                    'wordIds': ['first', 'last'], 'targetWordIds': ['first', 'last']}]
        audio = np.ones((10240, 1), dtype=np.float32)
        calls = []
        def emissions(_model, signal, origin):
            calls.append((origin, float(signal[0])))
            return np.log(probability), origin + np.linspace(0, len(signal)/16, 9)
        options = dict(at_start=True, at_end=True, emission_reader=emissions, decode_windows=windows,
                       ensemble_route='mms-vocal-ensemble')
        model = SimpleNamespace(config=SimpleNamespace(blank=0))
        owsm.infer_fused_views({r: audio for r in owsm.ROUTES}, model, targets, targets[1], {}, 0,
                             estimator='posterior', **options)
        single_calls = calls[:]
        calls.clear()
        with patch.object(mms, 'decode', wraps=mms.decode) as decoder:
            both, _ = owsm.infer_fused_views({r: audio for r in owsm.ROUTES}, model, targets, targets[1], {}, 0,
                                           estimator='both', **options)
        self.assertEqual(calls, single_calls, 'a second decoder must not repeat neural inference')
        self.assertEqual(set(both), {'posterior', 'viterbi'})
        self.assertEqual(decoder.call_count, 18)
        self.assertEqual([c.kwargs['estimator'] for c in decoder.call_args_list].count('posterior'), 9)
        self.assertEqual([c.kwargs['estimator'] for c in decoder.call_args_list].count('viterbi'), 9)
        for records in both.values():
            self.assertEqual(len(records), 9)
            self.assertTrue(all([w['wordId'] for w in r['words']] == targets[1] for r in records))
            nominal = next(r for r in records if r['shiftMs'] == 0 and r['gain'] == 1)
            self.assertEqual([(w['startMs'], w['endMs']) for w in nominal['words']],
                             [(160., 240.), (400., 480.)])

    def test_shared_decoding_retains_all_words_and_reuses_the_acoustic_frames(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        import stemwise_engine.endpoint_mms_prediction as mms
        probability = np.full((20,4),.001)
        probability[:,0] = .997
        for start,end,token in ((2,4,3),(8,10,1),(12,14,2),(16,18,3)):
            probability[start:end] = .001
            probability[start:end,token] = .997
        targets = ([3,1,2,3],['distant','before','target','after'],[0,1,2,3])
        windows = [{'occurrenceId':'line','startMs':700,'endMs':1900,
                    'wordIds':['before','target','after'],'targetWordIds':['target']}]
        samples = {route:np.ones((32000,1),dtype=np.float32) for route in owsm.ROUTES}
        calls = []
        def emissions(_model,signal,origin):
            calls.append((origin,float(signal[0])))
            return np.log(probability),origin+np.linspace(0,len(signal)/16,21)
        model = SimpleNamespace(config=SimpleNamespace(blank=0))
        options = dict(at_start=True,at_end=True,emission_reader=emissions,
                       decode_windows=windows,estimator='both')
        owsm.infer_fused_views(samples,model,targets,['target'],{},0,**options)
        previous_calls = calls[:]
        calls.clear()
        with patch.object(mms,'decode',wraps=mms.decode) as decoder:
            records,_ = owsm.infer_fused_views(samples,model,targets,['target'],{},0,
                                             complete_context=True,**options)
        self.assertEqual(calls,previous_calls)
        self.assertEqual(set(records),{'posterior','viterbi','shared-posterior','shared-viterbi'})
        self.assertEqual(decoder.call_count,36)
        self.assertEqual(sum(c.args[3] == targets[1] for c in decoder.call_args_list),18)
        for rows in records.values():
            self.assertEqual(len(rows),9)
            nominal = next(r for r in rows if r['shiftMs'] == 0 and r['gain'] == 1)
            self.assertEqual(nominal['words'],[{'wordId':'target','startMs':1200.,'endMs':1400.}])
        calls.clear()
        with patch.object(mms,'decode',wraps=mms.decode) as decoder:
            risk,_ = owsm.infer_fused_views(samples,model,targets,['target'],{},0,
                complete_context=True,boundary_risk=True,**options)
        self.assertEqual(calls,previous_calls)
        self.assertEqual(decoder.call_count,36,'risk must reuse the median posterior pass')
        self.assertEqual(sum(c.kwargs['estimator'] == 'posterior+risk' for c in decoder.call_args_list),18)
        self.assertEqual(set(risk),{'posterior','viterbi','shared-posterior','shared-viterbi','risk','shared-risk'})
        for name in ('risk','shared-risk'):
            self.assertEqual(len(risk[name]),9)
            nominal = next(r for r in risk[name] if r['shiftMs'] == 0 and r['gain'] == 1)
            self.assertEqual(nominal['words'],[{'wordId':'target','startMs':1200.,'endMs':1400.}])
        # The local path cannot check this distant word. A complete shared
        # path must retain it and reject an impossible qualified interval.
        with self.assertRaisesRegex(ValueError,'retain every target'):
            owsm.infer_fused_views(samples,model,targets,['target'],{'distant':[50,60]},0,
                                  complete_context=True,**options)

    def test_identical_local_and_shared_context_decodes_once_per_estimator_and_check(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        import stemwise_engine.endpoint_mms_prediction as mms
        probability = np.tile([.998, .001, .001], (8, 1))
        probability[2] = [.001, .998, .001]
        probability[5] = [.001, .001, .998]
        targets = ([1, 2], ['first', 'last'], [0, 1])
        windows = [{'occurrenceId': 'line', 'startMs': 0, 'endMs': 640,
                    'wordIds': targets[1], 'targetWordIds': ['last']}]
        def emissions(_model, signal, origin):
            return np.log(probability), origin + np.linspace(0, len(signal)/16, 9)
        options = dict(at_start=True, at_end=True, emission_reader=emissions,
                       decode_windows=windows, estimator='both', complete_context=True,
                       boundary_risk=True)
        samples = {r: np.ones((10240, 1), dtype=np.float32) for r in owsm.ROUTES}
        model = SimpleNamespace(config=SimpleNamespace(blank=0))
        with patch.object(mms, 'decode', wraps=mms.decode) as decoder:
            records, _ = owsm.infer_fused_views(samples, model, targets, ['last'], {}, 0, **options)
        self.assertEqual(decoder.call_count, 18)
        for name, rows in records.items():
            self.assertEqual(len(rows), 9)
            self.assertEqual(rows, records[name.removeprefix('shared-')])
            nominal = next(r for r in rows if r['shiftMs'] == 0 and r['gain'] == 1)
            self.assertEqual(nominal['words'], [{'wordId': 'last', 'startMs': 400., 'endMs': 480.}])
        # Even a non-published word must still satisfy the full path constraints.
        with self.assertRaisesRegex(ValueError, 'retain every target'):
            owsm.infer_fused_views(samples, model, targets, ['last'], {'first': [0, 1]}, 0, **options)

    def test_mms_emission_reuse_is_input_exact_bounded_and_invocation_local(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        import stemwise_engine.endpoint_mms_prediction as mms
        calls = []
        model = SimpleNamespace(config=SimpleNamespace(blank=0))
        def emissions(actual_model, signal, origin):
            calls.append((actual_model, signal.copy(), origin))
            probability = np.tile([.998, .001, .001], (8, 1))
            probability[2] = [.001, .998, .001]
            probability[5] = [.001, .001, .998]
            return np.log(probability), origin + np.linspace(0, len(signal)/16, 9)
        cache = mms.EmissionCache(model, emissions, max_bytes=32768)
        samples = {r: np.full((10240, 1), (i+1)/10, dtype=np.float32)
                   for i, r in enumerate(owsm.ROUTES)}
        targets = ([1, 2], ['first', 'last'], [0, 1])
        options = dict(at_start=False, at_end=True, blank=0, emission_reader=cache)
        baseline, _ = owsm.infer_fused_views(samples, model, targets, targets[1], {}, 0, **options)
        count = len(calls)
        self.assertEqual(count, 27)
        fallback, _ = owsm.infer_fused_views(samples, model, targets, targets[1], {}, 0,
            decode_windows=[{'startMs': 0, 'endMs': 640, 'wordIds': targets[1],
                             'targetWordIds': targets[1]}], estimator='both',
            complete_context=True, boundary_risk=True, **options)
        self.assertEqual(len(calls), count, 'same acoustic input must not rerun the neural model')
        self.assertEqual(fallback['posterior'], baseline)
        self.assertLessEqual(cache.bytes_used, 32768)
        prior = len(calls)
        with self.assertRaisesRegex(ValueError, 'retain every target'):
            owsm.infer_fused_views(samples, model, targets, targets[1], {'first': [0, 1]}, 0, **options)
        self.assertEqual(len(calls), prior, 'reusing emissions must still enforce changed word bounds')
        signal = samples[owsm.ROUTES[0]][:, 0]
        cache(model, signal, 0)
        prior = len(calls)
        cache(model, signal.copy(), 0)
        self.assertEqual(len(calls), prior)
        phase_changed = signal.copy()
        phase_changed[:len(signal)//2] *= -1
        for altered, origin, actual_model in ((signal*1.01, 0, model), (signal, 1, model),
                (signal[1:], 0, model), (phase_changed, 0, model), (signal, 0, object())):
            prior = len(calls)
            cache(actual_model, altered, origin)
            self.assertEqual(len(calls), prior+1)
        # A new worker/replay has no transferable cache or persisted emissions.
        prior = len(calls)
        fresh = mms.EmissionCache(model, emissions, max_bytes=1)
        fresh(model, signal, 0)
        fresh(model, signal, 0)
        self.assertEqual(len(calls), prior+2)
        self.assertEqual(fresh.bytes_used, 0)
        limited = mms.EmissionCache(model, emissions, max_bytes=800)
        limited(model, signal, 0)
        limited(model, signal, 1)
        prior = len(calls)
        limited(model, signal, 0)
        self.assertEqual(len(calls), prior+1, 'eviction must recompute rather than use another frame grid')
        self.assertLessEqual(limited.bytes_used, 800)

    def test_neighborhood_viterbi_retains_all_words_and_all_nine_acoustic_checks(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        import stemwise_engine.endpoint_mms_prediction as mms
        probability = np.tile([.998, .001, .001], (8, 1))
        probability[2] = [.001, .998, .001]
        probability[5] = [.001, .001, .998]
        targets = ([1, 2], ['first', 'last'], [0, 1])
        windows = [{'occurrenceId': 'line', 'startMs': 0, 'endMs': 640,
                    'wordIds': ['first', 'last'], 'targetWordIds': ['first', 'last']}]
        audio = np.ones((10240, 1), dtype=np.float32)
        def emissions(_model, signal, origin):
            return np.log(probability), origin + np.linspace(0, len(signal)/16, 9)
        with patch.object(mms, 'decode', wraps=mms.decode) as decoder:
            records, _ = owsm.infer_fused_views({r: audio for r in owsm.ROUTES},
                SimpleNamespace(config=SimpleNamespace(blank=0)), targets, ['first', 'last'], {}, 0,
                at_start=True, at_end=True, emission_reader=emissions,
                decode_windows=windows, estimator='viterbi')
        self.assertEqual(decoder.call_count, 9)
        self.assertTrue(all(c.kwargs['estimator'] == 'viterbi' for c in decoder.call_args_list))
        self.assertEqual(len(records), 9)
        self.assertTrue(all([w['wordId'] for w in r['words']] == ['first', 'last'] for r in records))
        nominal = next(r for r in records if r['shiftMs'] == 0 and r['gain'] == 1)
        self.assertEqual([(w['startMs'], w['endMs']) for w in nominal['words']], [(160., 240.), (400., 480.)])

    def test_neighborhood_decode_retains_complete_context_and_original_pcm_times(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        probability = np.full((20, 4), .001)
        probability[:, 0] = .997
        for start, end, token in ((2, 4, 3), (8, 10, 1), (12, 14, 2), (16, 18, 3)):
            probability[start:end] = .001
            probability[start:end, token] = .997
        targets = ([3, 1, 2, 3], ['distant', 'before', 'target', 'after'], [0, 1, 2, 3])
        windows = [{'occurrenceId': 'line', 'startMs': 700, 'endMs': 1900,
            'wordIds': ['before', 'target', 'after'], 'targetWordIds': ['target']}]
        before = copy.deepcopy(windows)
        audio = np.ones((32000, 1), dtype=np.float32)
        def emissions(_model, signal, origin):
            return np.log(probability), origin + np.linspace(0, len(signal)/16, 21)
        records, _ = owsm.infer_fused_views({r: audio for r in owsm.ROUTES},
            SimpleNamespace(config=SimpleNamespace(blank=0)), targets, ['target'], {}, 0,
            at_start=True, at_end=True, emission_reader=emissions, decode_windows=windows)
        self.assertEqual(len(records), 9)
        nominal = next(r for r in records if r['shiftMs'] == 0 and r['gain'] == 1)
        self.assertEqual(nominal['words'], [{'wordId': 'target', 'startMs': 1200., 'endMs': 1400.}])
        self.assertEqual(windows, before)
        # The complete neighboring words must be decoded, not discarded to
        # force the target into its window. An impossible neighbor fence fails.
        with self.assertRaisesRegex(ValueError, 'retain every target'):
            owsm.infer_fused_views({r: audio for r in owsm.ROUTES},
                SimpleNamespace(config=SimpleNamespace(blank=0)), targets, ['target'],
                {'before': [700, 701]}, 0, at_start=True, at_end=True,
                emission_reader=emissions, decode_windows=windows)
        cropped = copy.deepcopy(windows)
        cropped[0]['endMs'] = 1100
        with self.assertRaisesRegex(ValueError, 'retain every target'):
            owsm.infer_fused_views({r: audio for r in owsm.ROUTES},
                SimpleNamespace(config=SimpleNamespace(blank=0)), targets, ['target'],
                {'after': [1600, 1800]}, 0, at_start=True, at_end=True,
                emission_reader=emissions, decode_windows=cropped)
        for key, value in (('wordIds', ['target', 'before']),
                           ('targetWordIds', ['after']), ('startMs', 2000)):
            changed = copy.deepcopy(windows)
            changed[0][key] = value
            with self.assertRaises(ValueError):
                owsm.infer_fused_views({r: audio for r in owsm.ROUTES},
                    SimpleNamespace(config=SimpleNamespace(blank=0)), targets, ['target'], {}, 0,
                    at_start=True, at_end=True, emission_reader=emissions, decode_windows=changed)

    def test_mixed_language_path_uses_each_words_declared_acoustic_stream(self):
        import stemwise_engine.endpoint_mms_prediction as mms
        import stemwise_engine.endpoint_owsm_ctc as owsm
        streams = [np.tile([.997, .001, .001, .001], (10, 1)) for _ in range(2)]
        for frame, token in ((2, 1), (5, 2), (3, 3)):
            streams[0][frame] = .001
            streams[0][frame, token] = .997
        for frame, token in ((1, 1), (4, 2), (8, 3)):
            streams[1][frame] = .001
            streams[1][frame, token] = .997
        words, owners, targets = ['arabic', 'zulu'], [0, 0, 1], [1, 2, 3]
        edges = np.arange(11)*80.
        old, _ = mms.decode(np.log(streams[0]), targets, owners, words, edges, {}, estimator='posterior')
        self.assertNotEqual([(w['startMs'], w['endMs']) for w in old], [(160, 480), (640, 720)])
        token_logp = np.column_stack([np.log(streams[0][:, 1]), np.log(streams[0][:, 2]),
                                     np.log(streams[1][:, 3])])
        mixed, _ = mms.decode(np.log((streams[0]+streams[1])/2), targets, owners, words,
                              edges, {}, estimator='posterior', token_logp=token_logp)
        self.assertEqual([(w['startMs'], w['endMs']) for w in mixed], [(160, 480), (640, 720)])
        plan = {'language': 'mul', 'request': {'lines': [{'lineId': 'mixed', 'words': [
            {'wordId': 'arabic', 'acousticText': '\u0645\u0631\u062d\u0628\u0627'},
            {'wordId': 'zulu', 'acousticText': 'sawubona'}]}]}}
        self.assertEqual(owsm.prediction_word_languages(plan, ['ar', 'zu']), {'arabic': 'ar', 'zulu': 'zu'})
        self.assertEqual(owsm.prediction_word_languages(plan, ['ar', 'zu', 'en']), {})
        for fault in (token_logp[:, :-1], token_logp+10):
            with self.assertRaises(ValueError):
                mms.decode(np.log((streams[0]+streams[1])/2), targets, owners, words,
                           edges, {}, estimator='posterior', token_logp=fault)

    def test_vocal_probability_fusion_keeps_complementary_speech_and_requires_all_sources(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        from stemwise_engine.endpoint_mms_prediction import decode
        sources = [np.tile([.98, .01, .01], (8, 1)) for _ in range(3)]
        sources[0][2] = [.01, .98, .01]
        sources[1][5] = [.01, .01, .98]
        before = [s.copy() for s in sources]
        fused = owsm.fuse_emissions([np.log(s) for s in sources])
        reference = sum(sources)/3
        np.testing.assert_allclose(np.exp(fused), reference)
        timings, _ = decode(fused, [1, 2], [0, 1], ["first", "last"], np.arange(9)*80., {})
        self.assertEqual([(w["startMs"], w["endMs"]) for w in timings], [(160, 240), (400, 480)])
        for actual, expected in zip(sources, before):
            np.testing.assert_array_equal(actual, expected)
        for broken in ([np.log(sources[0]), np.log(sources[1])],
                       [np.log(sources[0]), np.log(sources[1]), np.log(sources[2][:-1])],
                       [np.log(sources[0]), np.log(sources[1]), np.log(sources[2])+1]):
            with self.assertRaises(ValueError):
                owsm.fuse_emissions(broken)

    def test_mixed_native_pipeline_requires_all_languages_sources_and_checks(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        calls = []
        aligner = SimpleNamespace(config=SimpleNamespace(blank=0), context_len_in_secs=3, lang_sym=None)
        def infer(signal):
            calls.append((aligner.lang_sym, round(float(np.mean(signal))*10)))
            probability = np.tile([.997, .001, .001, .001], (300, 1))
            peaks = ((2, 1), (5, 2), (3, 3)) if aligner.lang_sym == '<ara>' else ((1, 1), (4, 2), (8, 3))
            for frame, token in peaks:
                probability[frame] = .001
                probability[frame, token] = .997
            return np.log(probability), 24*16000
        aligner.get_lpz = infer
        samples = {route: np.full((int(10*owsm.PREDICTION_FRAME_MS*16)+160, 1), (i+1)/10., dtype='float32')
                   for i, route in enumerate(owsm.ROUTES)}
        languages = {'arabic': 'ar', 'zulu': 'zu'}
        records, _ = owsm.infer_fused_views(samples, aligner, ([1, 2, 3], ['arabic', 'zulu'], [0, 0, 1]),
            ['arabic', 'zulu'], {}, 0, word_languages=languages)
        self.assertEqual({key: calls.count(key) for key in set(calls)},
                         {(language, source): 9 for language in ('<ara>', '<zul>') for source in (1, 2, 3)})
        self.assertEqual(len(records), 9)
        for record in records:
            values = [v for word in record['words'] for v in (word['startMs'], word['endMs'])]
            np.testing.assert_allclose(values, np.array([2, 6, 8, 9])*owsm.PREDICTION_FRAME_MS+10, atol=5.001)
        with self.assertRaises(ValueError):
            owsm.infer_fused_views(samples, aligner, ([1, 2, 3], ['arabic', 'zulu'], [0, 0, 1]),
                ['arabic', 'zulu'], {}, 0, word_languages={'arabic': 'ar'})

    def test_mixed_stereo_selection_scores_the_complete_declared_language_path(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        aligner = SimpleNamespace(config=SimpleNamespace(blank=0), context_len_in_secs=3, lang_sym=None)
        def infer(signal):
            view = round(float(np.mean(signal))*10)
            probability = np.tile([.998, .001, .001], (300, 1))
            peaks = []
            if (view == 1 and aligner.lang_sym == '<ara>') or (view == 3 and aligner.lang_sym == '<zul>'):
                peaks = [(4, 1), (12, 2)]
            elif view == 2:
                peaks = [(4, 1), (2, 2)] if aligner.lang_sym == '<ara>' else [(14, 1), (12, 2)]
            for frame, token in peaks:
                probability[frame] = .001
                probability[frame, token] = .998
            return np.log(probability), 24*16000
        aligner.get_lpz = infer
        audio = np.tile([.1, .3], (int(20*owsm.PREDICTION_FRAME_MS*16), 1)).astype('float32')
        records, selected = owsm.infer_fused_views({route: audio for route in owsm.ROUTES}, aligner,
            ([1, 2], ['arabic', 'zulu'], [0, 1]), ['arabic', 'zulu'], {}, 0,
            word_languages={'arabic': 'ar', 'zulu': 'zu'})
        self.assertEqual({route: row['selected'] for route, row in selected.items()},
                         {route: 'mid' for route in owsm.ROUTES})
        self.assertEqual(len(records), 9)
        for row in records:
            np.testing.assert_allclose([w['startMs'] for w in row['words']],
                                       np.array([4, 12])*owsm.PREDICTION_FRAME_MS+10, atol=5.001)

    def test_fused_native_prediction_processes_all_sources_for_all_nine_checks(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        calls = []
        def infer(signal):
            source = round(float(np.mean(signal))*10)
            calls.append(source)
            probability = np.full((300, 4), .01)
            probability[:, 2] = .97
            if source == 1:
                probability[4] = [.01, .01, .01, .97]
            if source == 2:
                probability[12] = [.97, .01, .01, .01]
            return np.log(probability), 24*16000
        aligner = SimpleNamespace(config=SimpleNamespace(blank=2), context_len_in_secs=3, get_lpz=infer)
        samples = {route: np.full((int(20*owsm.PREDICTION_FRAME_MS*16), 1), (i+1)/10., dtype="float32")
                   for i, route in enumerate(owsm.ROUTES)}
        records, representations = owsm.infer_fused_views(samples, aligner, ([3, 0], ["first", "last"], [0, 1]),
            ["first", "last"], {"first": [100, 1800], "last": [100, 1800]}, 0)
        self.assertEqual({source: calls.count(source) for source in (1, 2, 3)}, {1: 9, 2: 9, 3: 9})
        self.assertEqual(set(representations), set(owsm.ROUTES))

    def test_fused_mms_pipeline_uses_its_emission_adapter_for_every_vocal_check(self):
        import torch
        import stemwise_engine.endpoint_mms_prediction as mms
        import stemwise_engine.endpoint_owsm_ctc as owsm
        calls = []
        class Emissions(torch.nn.Module):
            def __init__(self):
                super().__init__()
                self.marker = torch.nn.Parameter(torch.zeros(1))
            def forward(self, signal):
                source = round(float(signal.mean())*10)
                calls.append(source)
                probability = np.tile([.98, .01, .01], (20, 1))
                if source in (1, 2):
                    frame, token = (4, 1) if source == 1 else (12, 2)
                    probability[frame] = .01
                    probability[frame, token] = .98
                return torch.tensor(np.log(probability))[None], None
        samples = {route: np.full((20*320+320, 1), (i+1)/10., dtype='float32')
                   for i, route in enumerate(owsm.ROUTES)}
        records, _ = owsm.infer_fused_views(samples, Emissions(), ([1, 2], ['first', 'last'], [0, 1]),
            ['first', 'last'], {}, 0, emission_reader=mms.prediction_emissions,
            blank=0, ensemble_route='mms-vocal-ensemble')
        self.assertEqual({source: calls.count(source) for source in (1, 2, 3)}, {1: 9, 2: 9, 3: 9})
        self.assertEqual(len(records), 9)
        for record in records:
            self.assertEqual(record['route'], 'mms-vocal-ensemble')
            np.testing.assert_allclose([w['startMs'] for w in record['words']], [90, 250], atol=16)

    def test_posterior_boundaries_match_independent_complete_path_enumeration(self):
        import itertools
        import stemwise_engine.endpoint_mms_prediction as mms
        probability = np.array([[.5, .3, .2], [.2, .6, .2], [.4, .3, .3],
                                [.2, .2, .6], [.5, .1, .4]])
        targets, owners, words = [1, 2], [0, 1], ["first", "last"]
        edges = np.arange(6)*80.
        labels = [0, 1, 0, 2, 0]
        paths = []
        for path in itertools.product(range(5), repeat=5):
            if path[0] not in (0, 1) or path[-1] not in (3, 4):
                continue
            if any(b-a not in (0, 1, 2) or (b-a == 2 and (labels[b] == 0 or labels[a] == labels[b]))
                   for a, b in zip(path, path[1:])):
                continue
            if not all(s in path for s in (1, 3)):
                continue
            weight = np.prod([max(row[k] for k in range(3) if k != (1 if state == 0 else 2))
                              if state in (0, 4) else row[labels[state]]
                              for row, state in zip(probability, path)])
            spans = [(path.index(state), max(i for i, s in enumerate(path) if s == state)+1)
                     for state in (1, 3)]
            paths.append((weight, spans))
        expected = []
        for word in range(2):
            row = []
            for edge in range(2):
                mass = np.zeros(6)
                for weight, spans in paths:
                    mass[spans[word][edge]] += weight
                row.append(edges[np.searchsorted(np.cumsum(mass), mass.sum()/2)])
            expected.append(tuple(row))
        timings, confidence = mms.decode(np.log(probability), targets, owners, words, edges, {}, estimator="posterior")
        self.assertEqual([(w["startMs"], w["endMs"]) for w in timings], expected)
        self.assertGreater(confidence, 0)
        self.assertLessEqual(confidence, 1)
        with self.assertRaises(ValueError):
            mms.decode(np.log(probability), targets, owners, words, edges, {"last": [81, 100]}, estimator="posterior")

    def test_multilingual_decoder_retains_repeats_and_rejects_impossible_qualified_windows(self):
        import stemwise_engine.endpoint_mms_prediction as mms
        sequence = [3, 3, 1, 1, 0, 1, 1, 0, 2, 2, 3, 3]
        probability = np.full((len(sequence), 4), .01)
        probability[np.arange(len(sequence)), sequence] = .97
        targets, owners, words = [1, 1, 2], [0, 1, 2], ["first", "repeated", "tail"]
        planned = mms.target_plan({"lines": [{"lineId": "line", "sourcePhysicalIndex": 0,
            "words": [{"wordId": identity, "acousticText": text}
                      for identity, text in zip(words, ("a", "a", "b"))]}]}, None, {"a": 1, "b": 2})
        self.assertEqual(planned, (targets, words, owners))
        edges = np.arange(len(sequence)+1)*20.
        bounds = {"repeated": [90, 150]}
        timings, _ = mms.decode(np.log(probability), targets, owners, words, edges, bounds)
        self.assertEqual([(w["wordId"], w["startMs"], w["endMs"]) for w in timings],
                         [("first", 40, 80), ("repeated", 100, 140), ("tail", 160, 200)])
        import torch
        class Emissions(torch.nn.Module):
            def __init__(self):
                super().__init__()
                self.device_marker = torch.nn.Parameter(torch.zeros(1))
            def forward(self, _signal):
                return torch.tensor(np.log(probability))[None], None
        wired, _ = mms.align_signal(Emissions(), np.zeros(len(sequence)*320, dtype="float32"),
                                    planned, 0, bounds=bounds)
        self.assertEqual(wired, timings)
        before = probability.copy()
        for broken in ({"repeated": [105, 115]}, {"repeated": [150, 90]}):
            with self.assertRaises(ValueError):
                mms.decode(np.log(probability), targets, owners, words, edges, broken)
            np.testing.assert_array_equal(probability, before)
        with self.assertRaises(ValueError):
            mms.decode(np.log(probability), targets, owners, words, edges[:-1], bounds)
        # OWSM's blank ID is supplied by its pinned model, never assumed to be zero.
        shifted = np.roll(probability, 2, axis=1)
        timings_with_other_blank, _ = mms.decode(np.log(shifted), [3, 3, 0], owners,
                                                words, edges, bounds, blank=2)
        self.assertEqual(timings_with_other_blank, timings)

    def test_owsm_adapter_keeps_word_token_ownership_and_excludes_padding(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        probability = np.full((300, 4), .01)
        probability[:, 2] = .97
        for begin, end, token in ((4, 6, 3), (9, 12, 3), (16, 20, 0)):
            probability[begin:end] = .01
            probability[begin:end, token] = .97
        aligner = SimpleNamespace(config=SimpleNamespace(blank=2), context_len_in_secs=2,
            get_lpz=lambda _signal: (np.log(probability), 24*16000))
        preflight = [{"tokenIds": [3, 3, 0], "words": [
            {"wordId": identity, "targetStart": i, "targetEnd": i+1}
            for i, identity in enumerate(("first", "repeat", "last"))]}]
        with patch.object(owsm, "_request", return_value={"route": owsm.worker.ROUTES_BY_LANGUAGE["ar"]}), patch.object(
                owsm.worker, "_preflight", return_value=([], preflight, {})):
            actual_model, targets = owsm.prediction_context(Path("project"), Path("context"), {}, "ar", aligner)
        self.assertIs(actual_model, aligner)
        self.assertEqual(aligner.context_len_in_secs, 3)
        self.assertEqual(targets, ([3, 3, 0], ["first", "repeat", "last"], [0, 1, 2]))
        signal = np.zeros(int(20*owsm.PREDICTION_FRAME_MS*16)-100, dtype=np.float32)
        bounds = {"repeat": [9*owsm.PREDICTION_FRAME_MS, 12*owsm.PREDICTION_FRAME_MS]}
        timings, _ = owsm.align_prediction_signal(aligner, signal, targets, 0, bounds=bounds)
        self.assertEqual([w["wordId"] for w in timings], ["first", "repeat", "last"])
        np.testing.assert_allclose([w["startMs"] for w in timings], np.array([4, 9, 16])*owsm.PREDICTION_FRAME_MS)
        np.testing.assert_allclose([w["endMs"] for w in timings[:2]], np.array([6, 12])*owsm.PREDICTION_FRAME_MS)
        self.assertEqual(timings[-1]["endMs"], len(signal)/16)
        with self.assertRaises(ValueError):
            owsm.align_prediction_signal(aligner, signal, targets, 0, bounds={"repeat": [300, 350]})
        with patch.object(aligner, 'get_lpz', return_value=(np.log(probability), 26*16000)):
            with self.assertRaisesRegex(ValueError, 'frame grid changed'):
                owsm.prediction_emissions(aligner, signal, 0)
        aligner.context_len_in_secs = 2
        with self.assertRaisesRegex(ValueError, 'context changed'):
            owsm.prediction_emissions(aligner, signal, 0)

    def test_native_context_anchors_only_qualified_lines_with_existing_tolerance(self):
        import stemwise_engine.endpoint_owsm_ctc as owsm
        mapping = {"performedOccurrences": [{"occurrenceId": str(i),
            "words": [{"occurrenceWordId": f"w{i}"}]} for i in range(3)]}
        evidence = {"occurrenceCandidates": [{"publishDecision": {
            "result": "unresolved" if i == 1 else "line-only"}, "selectedPublisherFamily": "mms-fa",
            "familyCandidates": {"mms-fa": {"startMs": a, "endMs": b}}}
            for i, a, b in ((0, 1000, 1400), (1, 9999, 11000), (2, 3000, 3600))]}
        plan = {"request": {"lines": [{"lineId": str(i)} for i in range(3)]}}
        before = copy.deepcopy((mapping, evidence, plan))
        bounds = owsm.qualified_context_bounds(plan, mapping, evidence)
        self.assertEqual(bounds, {"w0": [700, 1800], "w2": [2700, 4000]})
        self.assertEqual((mapping, evidence, plan), before)
        # A louder repeated neighbour can steal the target's audio unless its
        # own states stay inside the already qualified interval.
        from stemwise_engine.endpoint_mms_prediction import decode
        probability = np.full((20, 4), .01)
        probability[:, 0] = .97
        for frame, token in ((2, 1), (5, 1), (6, 2), (15, 3)):
            probability[frame] = .01
            probability[frame, token] = .97
        probability[2] = [.18, .8, .01, .01]
        words, owners, targets = ["w0", "w1", "w2"], [0, 1, 2], [1, 2, 3]
        edges = np.arange(21)*200.
        unanchored, _ = decode(np.log(probability), targets, owners, words, edges, {})
        anchored, _ = decode(np.log(probability), targets, owners, words, edges, {"w0": [100, 800]})
        self.assertGreater(unanchored[0]["endMs"], 800)
        self.assertLessEqual(anchored[0]["endMs"], 800)
        self.assertEqual([w["wordId"] for w in anchored], words)

    def test_vocal_loading_preserves_native_bandwidth_and_timeline(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3] / "Test suite/work") as name:
            path = Path(name) / "vocal.wav"
            t = np.arange(44100) / 44100
            tone = .2 * np.sin(2*np.pi*11000*t)
            sf.write(path, np.column_stack((tone, -tone)), 44100, subtype="FLOAT")
            provenance = {"sha256": sha256_file(path), "canonicalTimeTransform": {
                **dict.fromkeys(("offsetFrames", "trimFrames", "padFrames", "separatorLatencyFrames"), 0),
                "measuredResidualMs": 0}}
            high = _vocal_samples(path, provenance, 1000, sample_rate=44100)
            low = _vocal_samples(path, provenance, 1000)
            self.assertEqual(high.shape, (44100, 2))
            self.assertEqual(low.shape, (16000, 2))
            self.assertGreater(np.sqrt(np.mean(high[:, 0]**2)), .14)
            self.assertLess(np.sqrt(np.mean(low[:, 0]**2)), .005)
            np.testing.assert_allclose(high[:, 0], -high[:, 1])
            provenance["canonicalTimeTransform"]["offsetFrames"] = 1
            with self.assertRaisesRegex(ValueError, "timeline"):
                _vocal_samples(path, provenance, 1000, sample_rate=44100)


if __name__ == "__main__":
    unittest.main()
