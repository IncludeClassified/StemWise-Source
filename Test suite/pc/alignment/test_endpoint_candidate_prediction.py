import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

import stemwise_engine.endpoint_candidate_prediction as prediction
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'Test suite/pc/unit'))
from lyric_content_fixtures import retained_content
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout
from stemwise_engine.endpoint_ctc_evidence import ROUTES, select_candidate


def case():
    families = {"a": {"startMs": 1000, "endMs": 2000},
                "b": {"startMs": 1020, "endMs": 2100},
                "c": {"startMs": 1500, "endMs": 2400}}
    records = []
    for route in ROUTES:
        for shift in (-5, 0, 5):
            decisions = {}
            for boundary, peak in (("startMs", 1000+shift), ("endMs", 2080+shift)):
                mass = np.full(100, .01/99)
                mass[50] = .99
                decisions[boundary] = select_candidate(mass, [c[boundary] for c in families.values()],
                    origin_ms=peak-1000, step_ms=20)
            records.append({"route": route, "shiftMs": shift, "originMs": shift,
                            "stepMs": 20, "decisions": decisions})
    return families, records


def prediction_context(root, index, language):
    from stemwise_engine.common import atomic_write_bytes, json_bytes, sha256_file
    scratch = root / f'context-{index}'
    scratch.mkdir()
    atomic_write_bytes(scratch/'request.private.json', json_bytes({'language': language, 'occurrence': index}))
    (scratch/'source.wav').write_bytes(b'known canonical PCM')
    views = {}
    for route in ROUTES:
        path = scratch/(route+'.wav')
        path.write_bytes(b'known vocal PCM: '+route.encode('ascii'))
        views[route] = {'path': path.name,
                        'provenance': {'sha256': sha256_file(path), 'byteCount': path.stat().st_size}}
    atomic_write_bytes(scratch/'prepared.json', json_bytes({'views': views}))
    return scratch


class CandidatePredictionTests(unittest.TestCase):
    def test_prediction_requires_eligible_selected_boundary_and_corroborators(self):
        families, records = case()
        families = dict(zip(('mms-fa', 'language-wav2vec2', 'owsm-ctc'), families.values()))
        families['mms-fa']['words'] = [{'startMs': 1000, 'endMs': 2000,
            'confidence': .01, 'acousticTokenCount': 1}]
        rejected = prediction.assess(records, families)
        self.assertEqual(rejected['startMs']['result'], 'abstain')
        self.assertEqual(rejected['endMs']['result'], 'abstain')
        families['mms-fa']['words'][0]['confidence'] = .9
        accepted = prediction.assess(records, families)
        self.assertEqual(accepted['startMs']['supportedFamilies'], ['mms-fa'])
        self.assertEqual(accepted['endMs']['supportedFamilies'], ['language-wav2vec2'])
        families['owsm-ctc'], families['language-wav2vec2'] = families['language-wav2vec2'], families['owsm-ctc']
        self.assertEqual(prediction.assess(records, families)['endMs']['result'], 'abstain')

    def test_language_tag_routes_prediction_without_rebinding_approved_context(self):
        from types import SimpleNamespace
        import stemwise_engine.endpoint_ctc_evidence as ctc
        from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
        from stemwise_engine.workflow_records import LyricsContentBlocker
        families, records = case()
        bindings = set()
        for language in ('en', 'EN', 'en-US'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(
                    dir=Path(__file__).resolve().parents[2]/'work') as name:
                root = Path(name)
                prepare_runtime_layout(root/'builder')
                video = 'TESTVID0001'
                original = {'id': 'original', 'kind': 'original', 'format': 'plain',
                    'language': language, 'script': 'Latn', 'text': 'we sing'}
                lyrics = {'status': 'partial', 'tracks': [original]}
                canonical = root/'lyrics-alignment/canonical-plain'/f'{video}.json'
                canonical.parent.mkdir(parents=True)
                canonical.write_text(json.dumps(lyrics), encoding='utf-8')
                canonical_sha = prediction.sha256_file(canonical)
                mapping = build_lexical_occurrence_mapping(video, original, (0,))
                owner = mapping['performedOccurrences'][0]['occurrenceId']
                evidence = {'occurrenceCandidates': [{'occurrenceId': owner,
                    'familyCandidates': families,
                    'publishDecision': {'result': 'unresolved', 'code': 'line-end-disagreement'}}]}
                evidence_root = canonical.parent.parent/'jobs'/video
                admission, _ = retained_content(evidence_root, original, (0,), canonical_sha, 'a'*64)
                bindings.add(admission.binding['canonicalPlainSha256'])
                declaration = {'videoId': video, 'canonicalPlainSha256': canonical_sha,
                    'analysisAudio': {'sha256': 'a'*64, 'durationMs': 4000},
                    'contentAdmission': admission.to_record(), 'occurrences': [
                        {'performedIndex': 0, 'occurrenceId': owner, 'candidateFamilies': families}]}
                plans = ctc._plan(declaration, mapping, evidence, lyrics, evidence_root=evidence_root)
                self.assertEqual(plans[0]['language'], language)
                attempt = root/'endpoint-review/attempt'
                directory = ctc._directory(attempt)
                scratch = directory/'context-000'
                scratch.mkdir(parents=True)
                request = scratch/'request.private.json'
                request.write_text(json.dumps(plans[0]), encoding='utf-8')
                parent = {'method': ctc.METHOD, 'scorerVersion': ctc.SCORER_VERSION, 'reviewRequired': True,
                    'declarationSha256': 'declaration', **declaration,
                    'occurrences': [{'occurrenceId': owner, 'inference': {'records': records},
                        'assessment': ctc._assess(records, families)}]}
                parent_path = directory/'manifest.json'
                parent_path.write_text(json.dumps(parent), encoding='utf-8')
                previous = {'method': ctc.METHOD, 'manifestSha256': prediction.sha256_file(parent_path)}
                before = canonical.read_bytes(), request.read_bytes(), parent_path.read_bytes()
                def worker(command, **kwargs):
                    self.assertEqual(command[4:], [str(scratch)])
                    (scratch/'candidate-prediction.json').write_text(json.dumps({
                        'model': prediction.EN_MODEL_IDENTITY, 'records': records}), encoding='utf-8')
                    return SimpleNamespace(returncode=0, stdout='', stderr='')
                config = {'projectDir': str(root/'builder'), 'analysisTimeoutMinutes': 1}
                with patch.object(prediction, 'run_process_tree', side_effect=worker) as run:
                    result = prediction.write_packet(attempt, declaration, 'declaration', root/'source.wav',
                        config, mapping, evidence, canonical, previous)
                    self.assertEqual(result['method'], prediction.METHOD)
                    run.assert_called_once()
                    packet = prediction.verify_packet(attempt, declaration, 'declaration', root/'source.wav',
                        {key: result[key] for key in ('method', 'manifestSha256')})
                    self.assertEqual(packet['occurrences'][0]['language'], language)
                    prediction.write_packet(attempt, declaration, 'declaration', root/'source.wav',
                        config, mapping, evidence, canonical, previous)
                    run.assert_called_once()
                    self.assertEqual((canonical.read_bytes(), request.read_bytes(), parent_path.read_bytes()), before)
                    original['language'] = 'en-GB' if language != 'en-GB' else 'en'
                    canonical.write_text(json.dumps(lyrics), encoding='utf-8')
                    declaration['canonicalPlainSha256'] = prediction.sha256_file(canonical)
                    with self.assertRaises(LyricsContentBlocker):
                        prediction.write_packet(attempt, declaration, 'declaration', root/'source.wav',
                            config, mapping, evidence, canonical, previous)
                    run.assert_called_once()
        self.assertEqual(len(bindings), 3)

    def admitted_plans(self, root, declaration, plans):
        import stemwise_engine.endpoint_ctc_evidence as ctc
        video = 'TESTVID0001'
        canonical = root / 'lyrics-alignment/canonical-plain' / (video + '.json')
        canonical.parent.mkdir(parents=True)
        original = {'kind': 'original', 'format': 'plain', 'language': 'en',
            'text': '\n'.join('we' for _ in plans)}
        canonical.write_text(json.dumps({'tracks': [original]}), encoding='utf-8')
        declaration.update(videoId=video, canonicalPlainSha256=prediction.sha256_file(canonical))
        declaration['analysisAudio']['sha256'] = 'a' * 64
        declaration['analysisAudio']['durationMs'] = 4000
        mapping = {'performedOccurrences': [{'occurrenceId': plan['occurrenceId'], 'sourcePhysicalIndex': index,
            'words': [{'occurrenceWordId': 'w' + str(index)}]} for index, plan in enumerate(plans)]}
        evidence = {'occurrenceCandidates': [{'occurrenceId': plan['occurrenceId'],
            'familyCandidates': plan['candidateFamilies'],
            'publishDecision': {'result': 'unresolved', 'code': 'line-end-disagreement'}} for plan in plans]}
        declaration['occurrences'] = [{'performedIndex': index, 'occurrenceId': plan['occurrenceId'],
            'candidateFamilies': plan['candidateFamilies']} for index, plan in enumerate(plans)]
        evidence_root = canonical.parent.parent / 'jobs' / video
        admission, _ = retained_content(evidence_root, original, tuple(range(len(plans))),
            declaration['canonicalPlainSha256'], declaration['analysisAudio']['sha256'])
        declaration['contentAdmission'] = admission.to_record()
        def resolve(*args, **kwargs):
            # Retain the real content admission; only prepared numeric model contexts are substituted.
            ctc._plan(*args, **kwargs)
            return plans
        return canonical, mapping, evidence, resolve

    def test_monolingual_refinement_keeps_verified_supported_start_and_rejects_changed_parent(self):
        from types import SimpleNamespace
        import stemwise_engine.endpoint_ctc_evidence as ctc
        families, records = case()
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            prepare_runtime_layout(root/'builder')
            attempt, canonical = root/'endpoint-review/attempt', root/'canonical.json'
            canonical.write_text('{}')
            declaration = {'canonicalPlainSha256': prediction.sha256_file(canonical),
                'analysisAudio': {'sha256': 'source'},
                'occurrences': [{'occurrenceId': 'one', 'candidateFamilies': families}]}
            plan = {'occurrenceId': 'one', 'candidateFamilies': families, 'language': 'ar'}
            canonical, mapping, evidence, resolve = self.admitted_plans(root, declaration, [plan])
            directory = ctc._directory(attempt)
            scratch = directory/'context-000'
            scratch.mkdir(parents=True)
            (scratch/'request.private.json').write_text(json.dumps(plan))
            base = {'method': ctc.METHOD, 'reviewRequired': True, 'scorerVersion': ctc.SCORER_VERSION,
                'declarationSha256': 'declaration', **declaration,
                'occurrences': [{'occurrenceId': 'one', 'inference': {'records': records},
                    'assessment': ctc._assess(records, families)}]}
            path = directory/'manifest.json'
            path.write_text(json.dumps(base))
            previous = {'method': ctc.METHOD, 'manifestSha256': prediction.sha256_file(path)}
            def worker(*_, **__):
                emitted = copy.deepcopy(records)
                for row in emitted:
                    row['decisions']['startMs']['peakMass'] = .2
                (scratch/'candidate-prediction.json').write_text(json.dumps({
                    'model': prediction.MODEL_IDENTITY, 'records': emitted}))
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            from stemwise_engine.common import WorkerRetirementError
            from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
            for disposition, error in (('cancelled', ProviderCancelled), ('parent-deadline', ProviderDeadline),
                                       ('retirement-failed', WorkerRetirementError)):
                def stopped(*args, **kwargs):
                    worker(*args, **kwargs)
                    marker = 'STEMWISE_WORKER_FAILURE='+json.dumps({'formatVersion': 1,
                        'code': 'nested-stop', 'disposition': disposition})
                    return SimpleNamespace(returncode=0, stdout='', stderr=marker)
                before = path.read_bytes()
                with self.subTest(disposition=disposition), patch.object(prediction, '_plan', side_effect=resolve), \
                        patch.object(prediction, 'run_process_tree', side_effect=stopped), self.assertRaises(error):
                    prediction.write_packet(attempt, declaration, 'declaration', root/'source.wav',
                        {'projectDir': str(root/'builder'), 'analysisTimeoutMinutes': 1}, mapping, evidence, canonical, previous)
                self.assertEqual(path.read_bytes(), before)
            with patch.object(prediction, '_plan', side_effect=resolve), \
                    patch.object(prediction, 'run_process_tree', side_effect=worker) as run:
                result = prediction.write_packet(attempt, declaration, 'declaration', root/'source.wav',
                    {'projectDir': str(root/'builder'), 'analysisTimeoutMinutes': 1}, mapping, evidence, canonical, previous)
                self.assertEqual(result['occurrences'][0]['assessment']['startMs'], base['occurrences'][0]['assessment']['startMs'])
                self.assertEqual(run.call_count, 1)
                path.write_text(json.dumps({**base, 'declarationSha256': 'changed'}))
                with self.assertRaisesRegex(ValueError, 'stale'):
                    prediction.write_packet(attempt, declaration, 'declaration', root/'source.wav',
                        {'projectDir': str(root/'builder')}, mapping, evidence, canonical, previous)
                self.assertEqual(run.call_count, 1)

    def test_worker_groups_languages_and_keeps_each_context_result_independent(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            paths = []
            languages = ('ar', 'EN', 'ar-SA', 'en-US')
            for index, language in enumerate(languages):
                path = prediction_context(root, index, language)
                paths.append(path)
            caches = []
            seen = []
            def infer(path, assets, *, model_cache):
                seen.append(path)
                caches.append(model_cache)
                return {'context': path.name, 'language': assets[1]['language'], 'runtime': {'device': 'fixture', 'transformers': 'fixture'}}
            with patch.object(prediction, '_model', side_effect=lambda _, language: (root, {'language': language})) as models, \
                    patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', return_value={'device': 'fixture'}), \
                    patch('importlib.metadata.version', return_value='fixture'), \
                    patch('stemwise_engine.provenance.endpoint_producer_identity', return_value='fixture'), \
                    patch.object(prediction, 'infer_records', side_effect=infer):
                prediction._infer_contexts(root, paths)
            self.assertEqual([call.args[1] for call in models.call_args_list], ['ar', 'en'])
            self.assertEqual(seen, [paths[0], paths[2], paths[1], paths[3]])
            self.assertTrue(all(cache is caches[0] for cache in caches))
            for path, language in zip(paths, ('ar', 'en', 'ar', 'en')):
                self.assertEqual(json.loads((path / 'candidate-prediction.json').read_bytes()),
                                 {'context': path.name, 'language': language,
                                  'runtime': {'device': 'fixture', 'transformers': 'fixture'}})

    def test_late_prediction_failure_preserves_exact_context_and_reemits_without_repeating(self):
        from stemwise_engine.worker_protocol import WorkerProtocolError
        work = Path(__file__).resolve().parents[2]/'work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            root = Path(name)
            contexts = [prediction_context(root, index, language)
                        for index, language in enumerate(('ar', 'en'))]
            calls = []
            failed = True
            def infer(scratch, assets, *, model_cache):
                calls.append(scratch)
                if scratch == contexts[1] and failed:
                    raise WorkerProtocolError('fixture-local-failure')
                return {'model': assets[1], 'records': [], 'runtime': {'device': 'fixture', 'transformers': 'fixture'}}
            with patch.object(prediction, '_model', side_effect=lambda _, language: (root, {'language': language})), \
                    patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', return_value={'device': 'fixture'}), \
                    patch('importlib.metadata.version', return_value='fixture'), \
                    patch('stemwise_engine.provenance.endpoint_producer_identity', return_value='fixture'), \
                    patch.object(prediction, 'infer_records', side_effect=infer):
                with self.assertRaises(WorkerProtocolError):
                    prediction._infer_contexts(root, contexts)
                retained = list(contexts[0].glob('inference-*.json'))
                self.assertEqual(len(retained), 1)
                original = retained[0].read_bytes()
                self.assertFalse((contexts[1]/'candidate-prediction.json').exists())
                for scratch in contexts:
                    (scratch/'candidate-prediction.json').unlink(missing_ok=True)
                failed = False
                prediction._infer_contexts(root, contexts)
                self.assertEqual(calls, [contexts[0], contexts[1], contexts[1]])
                self.assertEqual(retained[0].read_bytes(), original)
                self.assertTrue(all((scratch/'candidate-prediction.json').is_file() for scratch in contexts))

    def test_prediction_reuse_rejects_changed_audio_plan_model_producer_and_device(self):
        from stemwise_engine.worker_protocol import WorkerProtocolError
        work = Path(__file__).resolve().parents[2]/'work'
        for changed in ('audio', 'plan', 'language', 'model', 'producer', 'device'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory(dir=work) as name:
                root = Path(name)
                scratch = prediction_context(root, 0, 'ar')
                runtime = {'device': 'one'}
                model = {'language': 'ar'}
                producer = ['one']
                failed = False
                def infer(*args, **kwargs):
                    if failed:
                        raise WorkerProtocolError('fixture-local-failure')
                    return {'model': dict(model), 'records': [], 'runtime': {**runtime, 'transformers': 'fixture'}}
                with patch.object(prediction, '_model', side_effect=lambda *_: (root, dict(model))), \
                        patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity', side_effect=lambda: dict(runtime)), \
                        patch('importlib.metadata.version', return_value='fixture'), \
                        patch('stemwise_engine.provenance.endpoint_producer_identity', side_effect=lambda _: producer[0]), \
                        patch.object(prediction, 'infer_records', side_effect=infer):
                    prediction._infer_contexts(root, [scratch])
                    retained = next(scratch.glob('inference-*.json'))
                    original = retained.read_bytes()
                    (scratch/'candidate-prediction.json').unlink()
                    if changed == 'audio':
                        (scratch/'source.wav').write_bytes(b'changed canonical PCM')
                    elif changed == 'plan':
                        (scratch/'request.private.json').write_text(json.dumps({'language': 'ar', 'occurrence': 1}))
                    elif changed == 'language':
                        (scratch/'request.private.json').write_text(json.dumps({'language': 'AR-sa', 'occurrence': 0}))
                    elif changed == 'model':
                        model['revision'] = 'changed'
                    elif changed == 'producer':
                        producer[0] = 'changed'
                    else:
                        runtime['device'] = 'two'
                    failed = True
                    with self.assertRaises(WorkerProtocolError):
                        prediction._infer_contexts(root, [scratch])
                    self.assertEqual(retained.read_bytes(), original)
                    self.assertFalse((scratch/'candidate-prediction.json').exists())

    def test_supported_contexts_continue_and_preserve_bound_unsupported_results(self):
        from types import SimpleNamespace
        import stemwise_engine.endpoint_ctc_evidence as ctc
        families, records = case()
        abstain = {boundary: {"result": "abstain", "candidateMs": None,
            "supportedFamilies": [], "reason": "mixed-language-context-needs-supported-word-bindings"}
            for boundary in ("startMs", "endMs")}
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            prepare_runtime_layout(root/'builder')
            attempt = root / "endpoint-review/attempt"
            canonical = root / "canonical.json"
            canonical.write_text("{}")
            plans = [{"occurrenceId": identity, "language": language, "candidateFamilies": families}
                     for identity, language in (("one", "ar"), ("two", "en"), ("three", "mul"))]
            plans[-1]["unsupported"] = "mixed-language-context-needs-supported-word-bindings"
            declaration = {"canonicalPlainSha256": prediction.sha256_file(canonical),
                "analysisAudio": {"sha256": "source"}, "occurrences": [
                    {"occurrenceId": plan["occurrenceId"], "candidateFamilies": families} for plan in plans]}
            canonical, mapping, evidence, resolve = self.admitted_plans(root, declaration, plans)
            base_path = ctc._directory(attempt) / "manifest.json"
            base_path.parent.mkdir(parents=True)
            base = {"method": ctc.METHOD, "reviewRequired": True, "scorerVersion": ctc.SCORER_VERSION,
                "declarationSha256": "declaration", **declaration,
                "occurrences": [{"occurrenceId": p["occurrenceId"], "assessment": copy.deepcopy(abstain)} for p in plans]}
            base["occurrences"][0].update(inference={"records": copy.deepcopy(records)}, assessment=ctc._assess(records, families))
            base_path.write_text(json.dumps(base))
            previous = {"method": ctc.METHOD, "manifestSha256": prediction.sha256_file(base_path),
                "occurrences": copy.deepcopy(base["occurrences"])}
            for index, plan in enumerate(plans[:2]):
                scratch = base_path.parent / f"context-{index:03d}"
                scratch.mkdir()
                (scratch / "request.private.json").write_text(json.dumps(plan))
            invoked = []
            def worker(command, **kwargs):
                from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
                self.assertIsNotNone(process_lock_descriptor(heavy_worker_lock_path(config)))
                self.assertEqual(command[1:3], ['-m', 'stemwise_engine.endpoint_candidate_prediction'])
                for context in command[4:]:
                    scratch = Path(context)
                    plan = json.loads((scratch / "request.private.json").read_text())
                    invoked.append(plan["occurrenceId"])
                    identity = prediction.MODEL_IDENTITY if plan["language"] == "ar" else prediction.EN_MODEL_IDENTITY
                    emitted = copy.deepcopy(records)
                    if plan["language"] == "ar":
                        for record in emitted:
                            record["decisions"]["startMs"]["peakMass"] = .2
                    (scratch / "candidate-prediction.json").write_text(json.dumps({"model": identity, "records": emitted}))
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            config = {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}
            with patch.object(prediction, "_plan", side_effect=resolve), patch.object(prediction, "run_process_tree", side_effect=worker):
                result = prediction.write_packet(attempt, declaration, "declaration", root / "source.wav",
                    config, mapping, evidence, canonical, previous)
                self.assertEqual(invoked, ["one", "two"])
                self.assertEqual(result["method"], prediction.METHOD)
                self.assertTrue(result["reviewRequired"])
                for entry in result["occurrences"][:2]:
                    self.assertEqual(entry["assessment"]["startMs"]["candidateMs"], 1000)
                    self.assertEqual(entry["assessment"]["endMs"]["candidateMs"], 2100)
                self.assertEqual(result["occurrences"][0]["assessment"]["startMs"], base["occurrences"][0]["assessment"]["startMs"])
                self.assertEqual(result["occurrences"][2], base["occurrences"][2])
                self.assertEqual(json.loads(base_path.read_text()), base)
                prediction.write_packet(attempt, declaration, "declaration", root / "source.wav", config, mapping, evidence, canonical, previous)
                self.assertEqual(invoked, ["one", "two"])
                # Replaced parent evidence must not reuse an obsolete supported boundary.
                for record in base["occurrences"][0]["inference"]["records"]:
                    record["decisions"]["startMs"].update(result="abstain", candidateMs=None,
                        compatibleCandidatesMs=[], peakMass=.2)
                base["occurrences"][0]["assessment"] = ctc._assess(base["occurrences"][0]["inference"]["records"], families)
                base_path.write_text(json.dumps(base))
                previous["manifestSha256"] = prediction.sha256_file(base_path)
                result = prediction.write_packet(attempt, declaration, "declaration", root / "source.wav", config, mapping, evidence, canonical, previous)
                self.assertEqual(invoked, ["one", "two", "one", "two"])
                self.assertEqual(result["occurrences"][0]["assessment"]["startMs"]["result"], "abstain")
                # The production cascade passes the verified repeat packet, not a free-form summary.
                import stemwise_engine.endpoint_repeat_alignment as repeat
                repeat_path = repeat._directory(attempt) / "manifest.json"
                repeat_path.parent.mkdir(parents=True)
                repeat_packet = {"method": repeat.METHOD, "predictionVersion": repeat.PREDICTION_VERSION,
                    "ctcEvidence": {k: previous[k] for k in ("method", "manifestSha256")},
                    "occurrences": [{**copy.deepcopy(entry), "plan": None, "records": []}
                                    for entry in base["occurrences"]]}
                repeat_path.write_text(json.dumps(repeat_packet))
                previous = {"method": repeat.METHOD, "manifestSha256": prediction.sha256_file(repeat_path)}
                result = prediction.write_packet(attempt, declaration, "declaration", root / "source.wav", config, mapping, evidence, canonical, previous)
                self.assertEqual(invoked, ["one", "two"] * 3)
                self.assertEqual(result["occurrences"][2], base["occurrences"][2])
            packet = prediction._directory(attempt) / "manifest.json"
            valid = json.loads(packet.read_text())
            for fault in ("unsupported-pass", "model", "dropped", "previous"):
                changed = copy.deepcopy(valid)
                if fault == "unsupported-pass": changed["occurrences"][2]["assessment"]["startMs"].update(result="supported", candidateMs=1000, supportedFamilies=["a"])
                elif fault == "model": changed["occurrences"][1]["inference"]["model"] = prediction.MODEL_IDENTITY
                elif fault == "dropped": changed["occurrences"].pop()
                else: changed["previousEvidence"]["manifestSha256"] = "0" * 64
                packet.write_text(json.dumps(changed))
                with self.subTest(fault=fault), self.assertRaises(ValueError):
                    prediction.verify_packet(attempt, declaration, "declaration", root / "source.wav",
                        {"method": prediction.METHOD, "manifestSha256": prediction.sha256_file(packet)})
            false_parent = copy.deepcopy(base)
            false_parent["occurrences"][0]["assessment"]["startMs"].update(result="supported", candidateMs=1000, supportedFamilies=["a"])
            base_path.write_text(json.dumps(false_parent))
            repeat_packet["ctcEvidence"]["manifestSha256"] = prediction.sha256_file(base_path)
            repeat_packet["occurrences"][0]["assessment"] = false_parent["occurrences"][0]["assessment"]
            repeat_path.write_text(json.dumps(repeat_packet))
            changed = copy.deepcopy(valid)
            changed["previousEvidence"]["manifestSha256"] = prediction.sha256_file(repeat_path)
            packet.write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "CTC consensus differs"):
                prediction.verify_stored_packet(attempt,
                    {"method": prediction.METHOD, "manifestSha256": prediction.sha256_file(packet)})

    def test_english_fallback_reuses_bound_context_and_preserves_unsupported_language(self):
        from types import SimpleNamespace
        from stemwise_engine.endpoint_ctc_evidence import _directory as ctc_directory
        families, records = case()
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            prepare_runtime_layout(root/'builder')
            attempt = root / "endpoint-review/attempt"
            canonical = root / "canonical.json"
            canonical.write_text("{}")
            declaration = {"canonicalPlainSha256": prediction.sha256_file(canonical),
                "analysisAudio": {"sha256": "source"},
                "occurrences": [{"occurrenceId": identity, "candidateFamilies": families} for identity in ("one", "two")]}
            plan = {"language": "en", "occurrenceId": "one", "candidateFamilies": families}
            plans = [plan, {**plan, "occurrenceId": "two"}]
            canonical, mapping, evidence, resolve = self.admitted_plans(root, declaration, plans)
            canonical_before = canonical.read_bytes()
            requests = []
            for index, item in enumerate(plans):
                scratch = ctc_directory(attempt) / f"context-{index:03d}"
                scratch.mkdir(parents=True)
                request = scratch / "request.private.json"
                request.write_text(json.dumps(item))
                requests.append(request)
            import stemwise_engine.endpoint_ctc_evidence as ctc
            parent = {"method": ctc.METHOD, "reviewRequired": True, "scorerVersion": ctc.SCORER_VERSION,
                "declarationSha256": "declaration", **declaration,
                "occurrences": [{"occurrenceId": item['occurrenceId'],
                    "inference": {"records": copy.deepcopy(records)},
                    "assessment": ctc._assess(records, families)} for item in plans]}
            parent_path = ctc_directory(attempt)/'manifest.json'
            parent_path.write_text(json.dumps(parent))
            previous = {"method": ctc.METHOD, "manifestSha256": prediction.sha256_file(parent_path)}
            wrong_model = True
            def worker(*args, **kwargs):
                self.assertEqual(args[0][1:3], ['-m', 'stemwise_engine.endpoint_candidate_prediction'])
                for context in args[0][4:]:
                    (Path(context) / "candidate-prediction.json").write_text(json.dumps({
                        "model": prediction.MODEL_IDENTITY if wrong_model else prediction.EN_MODEL_IDENTITY,
                        "records": records}))
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            with patch.object(prediction, "_plan", side_effect=resolve), patch.object(
                prediction, "run_process_tree", side_effect=worker
            ) as run:
                with self.assertRaisesRegex(ValueError, "candidate prediction"):
                    prediction.write_packet(attempt, declaration, "declaration", root / "source.wav",
                        {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, previous)
                self.assertFalse((prediction._directory(attempt) / "manifest.json").exists())
                self.assertEqual([json.loads(request.read_text()) for request in requests], plans)
                self.assertEqual(canonical.read_bytes(), canonical_before)
                wrong_model = False
                run.reset_mock()
                result = prediction.write_packet(attempt, declaration, "declaration", root / "source.wav",
                    {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, previous)
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.kwargs["timeout"], 60)
                self.assertEqual(result["method"], prediction.METHOD)
                self.assertFalse(result["reviewRequired"])
                self.assertEqual([json.loads(request.read_text()) for request in requests], plans)
                # Completed predictions reuse their packet, not the GPU.
                prediction.write_packet(attempt, declaration, "declaration", root / "source.wav",
                    {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, previous)
                self.assertEqual(run.call_count, 1)
                packet = prediction._directory(attempt) / "manifest.json"
                old_context = json.loads(packet.read_text())
                old_context["contextVersion"] = 5
                packet.write_text(json.dumps(old_context))
                prediction.write_packet(attempt, declaration, "declaration", root / "source.wav",
                    {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, previous)
                self.assertEqual(run.call_count, 2)
                self.assertEqual(json.loads(packet.read_text())["contextVersion"], prediction.SCORER_VERSION)
                for item in plans:
                    item["language"] = "fr"
                self.assertIs(prediction.write_packet(attempt, declaration, "declaration", root / "source.wav",
                    {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}, mapping, evidence, canonical, previous), previous)
                self.assertEqual(run.call_count, 2)

    def test_model_admission_uses_inference_runtime_and_rejects_changed_assets(self):
        # Run in .venv, which deliberately has no alignment-only syncalong package.
        # Only model bytes are substituted; runtime imports and asset admission are real.
        with tempfile.TemporaryDirectory() as name:
            project = Path(name) / "builder"
            model = project.parent / "model-cache/lyrics-alignment" / (
                "models--"+prediction.REPOSITORY.replace("/", "--")) / "snapshots" / prediction.SNAPSHOT
            model.mkdir(parents=True)
            expected = {}
            for filename in prediction.MODEL_FILES:
                data = filename.encode()
                (model / filename).write_bytes(data)
                expected[filename] = hashlib.sha256(data).hexdigest()
            with patch.object(prediction, "MODEL_FILES", expected):
                self.assertEqual(prediction._model(project), (model, prediction.MODEL_IDENTITY))
                (model / "config.json").write_bytes(b"changed")
                with self.assertRaisesRegex(ValueError, "asset changed"):
                    prediction._model(project)

    def test_obsolete_or_implicit_prediction_versions_require_regeneration(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'work') as name:
            attempt = Path(name) / 'endpoint-review' / 'attempt'
            directory = prediction._directory(attempt)
            directory.mkdir(parents=True)
            path = directory / 'manifest.json'
            for fields in ({}, {'predictionVersion': 1}, {'predictionVersion': 2},
                    {'predictionVersion': 2, 'contextVersion': 5}):
                manifest = {'method': prediction.METHOD, 'occurrences': [{}], **fields}
                path.write_text(json.dumps(manifest), encoding='utf-8')
                with self.subTest(fields=fields), self.assertRaisesRegex(ValueError, 'regeneration'):
                    prediction.verify_stored_packet(attempt, {'method': prediction.METHOD,
                        'manifestSha256': prediction.sha256_file(path)})

    def test_concentrated_stable_peak_selects_only_unique_nearest_declared_candidate(self):
        families, records = case()
        result = prediction.assess(records, families)
        self.assertEqual(result["startMs"]["candidateMs"], 1000)
        self.assertEqual(result["endMs"]["candidateMs"], 2100)
        self.assertEqual(result["endMs"]["supportedFamilies"], ["b"])
        self.assertEqual(result["endMs"]["reason"], "bounded-nearest-acoustic-prediction")
        self.assertTrue(all(r["decisions"]["endMs"]["result"] == "abstain" for r in records))

    def test_missing_weak_conflicting_or_midpoint_evidence_cannot_choose(self):
        families, records = case()
        for fault in ("missing", "weak", "conflicting", "midpoint", "nonfinite", "too-far", "fake-pass"):
            with self.subTest(fault=fault):
                changed = copy.deepcopy(records)
                changed_families = copy.deepcopy(families)
                decision = changed[0]["decisions"]["endMs"]
                if fault == "missing": changed.pop()
                elif fault == "weak": decision["peakMass"] = .89
                elif fault == "conflicting": decision["peakMs"] = 2300
                elif fault == "midpoint": decision["peakMs"] = 2055
                elif fault == "nonfinite": decision["peakMs"] = float("nan")
                elif fault == "fake-pass":
                    for record in changed:
                        record["decisions"]["endMs"].update(result="supported", candidateMs=2100,
                            compatibleCandidatesMs=[2100], peakMass=.2)
                else:
                    for candidate in changed_families.values(): candidate["endMs"] += 1000
                self.assertEqual(prediction.assess(changed, changed_families)["endMs"]["result"], "abstain")


if __name__ == "__main__":
    unittest.main()
