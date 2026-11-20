"""Native output projection and scorer isolation, without numerical inference."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import wave
from contextlib import redirect_stdout, redirect_stderr, ExitStack, nullcontext
import io

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('english_timing_measurement',
    ROOT/'Test suite/pc/tools/measure_english_timing.py')
measurement = importlib.util.module_from_spec(spec)
spec.loader.exec_module(measurement)


class EnglishTimingMeasurementTests(unittest.TestCase):
    def guarded_inputs(self, root):
        cases = []
        for index, identity in enumerate(('hila-give-me-the-same', 'ridgway-fire-inside')):
            audio, lyrics = root/(identity+'.wav'), root/(identity+'.txt')
            with wave.open(str(audio), 'wb') as writer:
                writer.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
                writer.writeframes((index+1).to_bytes(2, 'little', signed=True)+b'\0'*(144000*4-2))
            lyrics.write_text('a\na\n', encoding='utf-8')
            cases.append({'id': identity, 'language': 'en', 'heldOutByUs': True,
                'audio': audio.name, 'audioSha256': measurement.sha256_file(audio),
                'sampleRate': 48000, 'channels': 2, 'frames': 144000, 'durationMs': 3000,
                'modelInputLyrics': lyrics.name, 'modelInputLyricsSha256': measurement.sha256_file(lyrics),
                'lines': [{'text': 'a', 'words': [{'startUtf16': 0, 'endUtf16': 1,
                    'startMs': start, 'endMs': end}]} for start, end in ((137, 371), (2019, 2231))]})
        reference = root/'reference.json'
        reference.write_text(json.dumps({'musical': cases}), encoding='utf-8')
        policy = json.loads((ROOT/'Test suite/fixtures/lyrics-timing/English/sylla-qualification-plan.json').read_bytes())
        frozen = root/'frozen-plan.json'
        frozen.write_text(json.dumps({**policy, 'referenceSha256': measurement.sha256_file(reference)}), encoding='utf-8')
        return cases, [measurement.model_plan(case, root) for case in cases], reference, frozen

    def whisper_boundary(self, stack, events):
        from stemwise_engine import lyrics_align_pc as align, alignment_models as models, lyrics_alignment
        from syncalong.transcribe import WordTimestamp
        class Model:
            def __init__(self, *args, **kwargs):
                events.append('whisper-open')
            def transcribe(self, *args, **kwargs):
                self_test.assertIn(kwargs['prompt'], (None, ''))
                events.append('whisper-transcribe')
                return [WordTimestamp(word='a', raw='a', start=.1, end=.3),
                        WordTimestamp(word='a', raw='a', start=2., end=2.2)], {'detectedLanguage': 'en',
                    'detectedLanguageProbability': 1., 'segmentCount': 2, 'wordCount': 2}
            def close(self):
                events.append('whisper-close')
        self_test = self
        stack.enter_context(patch.object(models, 'FasterWhisperEvidence', Model))
        stack.enter_context(patch.object(models, 'heavy_worker_stage', return_value=nullcontext()))
        stack.enter_context(patch.object(align, '_verify_whisper_snapshot', return_value=(Path('/pinned'), {})))
        stack.enter_context(patch.object(align, '_whisper_cache_provenance', return_value={'pinned-fixture': True}))
        stack.enter_context(patch.object(lyrics_alignment, 'resolve_compute_type', return_value='float16'))
        return Model

    def test_guarded_request_uses_actual_timed_cache_and_native_decoder_without_annotation_times(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, ExitStack() as stack:
            root = Path(name)
            cases, plans, _, _ = self.guarded_inputs(root)
            before = copy.deepcopy(plans)
            events = []
            model_type = self.whisper_boundary(stack, events)
            config = {'projectDir': str(measurement.PROJECT), 'analysisTimeoutMinutes': 1}
            guarded, metadata = measurement.whisper_guarded_plans(cases, plans, root/'planning', config, 60, 'cuda')
            self.assertEqual(events, ['whisper-open', 'whisper-transcribe', 'whisper-transcribe', 'whisper-close'], metadata)
            self.assertEqual(plans, before)
            for base, changed in zip(plans, guarded):
                self.assertEqual({key: value for key, value in changed['request'].items() if key != 'segments'},
                    {key: value for key, value in base['request'].items() if key != 'segments'})
                self.assertEqual(changed['request']['segments'], [
                    {'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1, 'windowStartMs': 0, 'windowEndMs': 1150},
                    {'segmentIndex': 1, 'startLineIndex': 1, 'endLineIndex': 2, 'windowStartMs': 1150, 'windowEndMs': 3000}])
                self.assertFalse(metadata[base['id']]['contentAuthority'])
            changed_labels = copy.deepcopy(cases)
            for case in changed_labels:
                for line in case['lines']:
                    line['words'][0]['startMs'] += 23
                    line['words'][0]['endMs'] -= 17
            cached, _ = measurement.whisper_guarded_plans(changed_labels, plans, root/'planning', config, 60, 'cuda')
            self.assertEqual(cached, guarded)
            self.assertEqual(events.count('whisper-transcribe'), 2)
            with patch.object(model_type, 'transcribe', side_effect=RuntimeError('private decoder refusal fixture')):
                fallback, failures = measurement.whisper_guarded_plans(cases, plans, root/'failed-planning', config, 60, 'cuda')
                self.assertEqual(fallback, plans)
                self.assertNotIn('private decoder refusal fixture', json.dumps(failures))
                for row in failures.values():
                    diagnostic = ROOT/row['diagnostic']['path']
                    self.assertEqual(measurement.sha256_file(diagnostic), row['diagnostic']['sha256'])
                    self.assertIn('private decoder refusal fixture', diagnostic.read_text(encoding='utf-8'))
                with patch('stemwise_engine.private_diagnostics.retain_error', side_effect=OSError('diagnostic disk failure')):
                    fallback, failures = measurement.whisper_guarded_plans(cases, plans,
                        root/'failed-diagnostic', config, 60, 'cuda')
                    self.assertEqual(fallback, plans)
                    self.assertTrue(all(row['technicalFailure'] == 'RuntimeError'
                        and row['diagnosticRetentionFailure'] == 'OSError' for row in failures.values()))
            request = guarded[0]['request']
            request_path, result_path = root/'native-request.json', root/'native-result.json'
            request_path.write_text(json.dumps(request), encoding='utf-8')
            program = '''import json, sys, torch
from pathlib import Path
from stemwise_engine import sylla_forced_alignment as worker
request = json.loads(Path(sys.argv[1]).read_bytes())
vocabulary = {'<blank>': 0, '|': 1, 'a': 2}
emission = torch.full((30, 3), -40.)
for index in range(30):
    emission[index, 2 if index in (1, 2, 20, 21) else 1 if index == 15 else 0] = 0
lines = worker._decode(request, worker.target_admission(request, 'en', vocabulary), vocabulary,
    emission.log_softmax(-1), [worker.ctc.chunk_time_map(0, 48000, 0, 30)])
Path(sys.argv[2]).write_text(json.dumps(lines), encoding='utf-8')
'''
            # The parent exercises the real lyrics-runtime planner; the unchanged
            # native decoder runs in its managed main runtime, without a model.
            with measurement.environment_lease('main', builder=measurement.PROJECT):
                completed = measurement.run_process_tree([str(measurement.environment_python('main', measurement.PROJECT)),
                    '-c', program, str(request_path), str(result_path)],
                    capture_output=True, text=True, check=False, timeout=60)
            measurement.require_worker_success(completed, code='guarded-cpu-native-fixture',
                diagnostic_path=root/'native-failure.private.txt')
            lines = json.loads(result_path.read_bytes())
            self.assertEqual([(line['startMs'], line['endMs']) for line in lines], [(100, 300), (2000, 2200)])
            self.assertEqual(measurement.score(cases[0], guarded[0], lines)['joint']['verifiedWords'], 2)

    def test_guarded_comparison_retires_whisper_before_sylla_and_retains_both_requests(self):
        from stemwise_engine import sylla_forced_alignment as worker
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name, ExitStack() as stack:
            root = Path(name)
            _, _, reference, frozen = self.guarded_inputs(root)
            events, requests, redecodes = [], [], []
            fault = None
            self.whisper_boundary(stack, events)
            stack.enter_context(patch.object(measurement, 'SYLLA_PLAN_SHA256', measurement.sha256_file(frozen)))
            def serial(plan, family, directory, *args, redecode=False):
                self.assertEqual(family, 'sylla')
                self.assertEqual(events[-1], 'whisper-close')
                requests.append((plan['id'], copy.deepcopy(plan['request'])))
                redecodes.append(redecode)
                lines = [{'lineId': line['lineId'], 'startMs': start, 'endMs': end,
                    'words': [{'wordId': line['words'][0]['wordId'], 'startMs': start, 'endMs': end}]}
                    for line, start, end in zip(plan['request']['lines'], (100, 2000),
                        (1000 if fault == 'guard-end' else 300, 2200))]
                if fault == 'guard-end':
                    for segment in plan['request']['segments']:
                        worker.ctc.validate_segment_boundary(lines[segment['startLineIndex']:segment['endLineIndex']],
                            segment, plan['request']['durationMs'])
                result = {'lines': lines, 'model': {'fixture': 'same pinned model'},
                    'producer': {'fixture': 'same worker'}, 'runtime': {'fixture': 'same observed GPU'},
                    'decoder': {'fixture': 'same decoder'},
                    'canonicalTimeTransform': plan['audioIdentity']['canonicalTimeTransform']}
                binding = worker.emission_binding(plan['audioIdentity'], result['model'],
                    result['runtime'], result['producer'])
                cache = directory/'emissions'/('emissions-'+measurement.canonical_sha256(binding)+'.pt')
                cache.parent.mkdir(parents=True, exist_ok=True)
                full = len(plan['request']['segments']) == 1
                affected = plan['id'] == 'hila-give-me-the-same'
                if full and not (affected and fault == 'missing'):
                    cache.write_bytes(b'known target-independent emissions')
                if not full and affected:
                    if fault == 'deleted':
                        cache.unlink(missing_ok=True)
                    elif fault == 'replaced':
                        cache.write_bytes(b'x'*len(b'known target-independent emissions'))
                path = directory/(measurement.canonical_sha256(plan['request'])+'.json')
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(result), encoding='utf-8')
                return result, False, str(path)
            stack.enter_context(patch.object(measurement, 'serial_model', side_effect=serial))
            def verify_run(fault):
                requests.clear()
                redecodes.clear()
                output = root/('out-'+str(fault))
                with redirect_stdout(io.StringIO()):
                    self.assertEqual(measurement.main(['--compare-whisper-guards', '--run-models', '--family', 'sylla',
                        '--reference', str(reference), '--qualification-plan', str(frozen), '--output', str(output)]),
                        int(fault is not None and fault != 'guard-end'))
                report = json.loads((output/'measurement.private.json').read_bytes())
                self.assertFalse(report['qualificationClaim'])
                self.assertFalse(report['publicExportVerified'])
                self.assertEqual(report['splitForCandidate'], 'development')
                for variant in ('full-recording', 'whisper-guarded'):
                    self.assertEqual(report['variants'][variant]['aggregate']['wordCount'], 4)
                self.assertEqual(report['variants']['full-recording']['aggregate']['jointWithinTolerance'],
                    2 if fault == 'guard-end' else 4)
                if fault == 'guard-end':
                    self.assertEqual(report['variants']['whisper-guarded']['aggregate']['technicalFailureCases'], 0)
                    self.assertEqual(report['variants']['whisper-guarded']['aggregate']['jointWithinTolerance'], 2)
                    for identity in ('hila-give-me-the-same', 'ridgway-fire-inside'):
                        row = report['variants']['whisper-guarded'][identity]
                        self.assertEqual(row['mergeSteps'], [{'segmentIndex': 0, 'boundary': 'end'}])
                        self.assertEqual(len(row['initialSegments']), 2)
                        self.assertEqual(len(row['resolvedSegments']), 1)
                        self.assertTrue(row['resolvedFallbackToFull'])
                        self.assertNotEqual(row['initialRequestSha256'], row['resolvedRequestSha256'])
                        self.assertEqual(row['resolvedRequestSha256'],
                            report['variants']['full-recording'][identity]['requestSha256'])
                        self.assertTrue(row['mergeDiagnostics'])
                        for ref in row['mergeDiagnostics']:
                            self.assertEqual(ref['requestSha256'], row['initialRequestSha256'])
                            self.assertEqual(ref['segments'], row['initialSegments'])
                            self.assertEqual(ref['reasonCode'], 'wav2vec2-segment-boundary-guard:0:end')
                            diagnostic = ROOT/ref['path']
                            self.assertEqual(measurement.sha256_file(diagnostic), ref['sha256'])
                            self.assertEqual(diagnostic.stat().st_size, ref['byteCount'])
                            self.assertIn('boundary guard', diagnostic.read_text(encoding='utf-8'))
                    return
                if fault is not None:
                    self.assertEqual(report['variants']['whisper-guarded']['aggregate']['technicalFailureCases'], 1)
                    return
                self.assertEqual(len(requests), 4)
                self.assertEqual(redecodes, [True]*4)
                for identity in ('hila-give-me-the-same', 'ridgway-fire-inside'):
                    pair = [request for target, request in requests if target == identity]
                    self.assertEqual([len(request['segments']) for request in pair], [1, 2])
                    self.assertEqual({key: value for key, value in pair[0].items() if key != 'segments'},
                        {key: value for key, value in pair[1].items() if key != 'segments'})
                    self.assertIn('emissionCache', report['variants']['full-recording'][identity])
                    self.assertIn('emissionCache', report['variants']['whisper-guarded'][identity])
                    full = report['variants']['full-recording'][identity]['emissionCache']
                    guarded = report['variants']['whisper-guarded'][identity]['emissionCache']
                    self.assertEqual(full, guarded)
                    cache = ROOT/full['path']
                    self.assertEqual(measurement.sha256_file(cache), full['sha256'])
                    self.assertEqual(cache.stat().st_size, full['byteCount'])
            for fault in (None, 'missing', 'deleted', 'replaced', 'guard-end'):
                with self.subTest(cache=fault):
                    verify_run(fault)

    def test_guarded_comparison_plan_never_runs_asr_or_models(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            _, _, reference, frozen = self.guarded_inputs(root)
            with patch.object(measurement, 'SYLLA_PLAN_SHA256', measurement.sha256_file(frozen)), \
                    patch.object(measurement, 'whisper_guarded_plans', create=True,
                        side_effect=AssertionError('dry plan must not transcribe')), \
                    patch.object(measurement, 'serial_model', side_effect=AssertionError('dry plan must not infer')), \
                    redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(measurement.main(['--compare-whisper-guards', '--family', 'sylla',
                    '--reference', str(reference), '--qualification-plan', str(frozen), '--output', str(root/'out')]), 0)
            planned = json.loads(stdout.getvalue())
            self.assertFalse(planned['modelsRun'])
            self.assertFalse(planned['qualificationClaim'])
            self.assertFalse((root/'out').exists())

    def case(self, root, identity, count=2):
        from stemwise_engine.ctc_worker import canonical_time_transform, chunk_time_map
        from stemwise_engine.mms_forced_alignment import _aggregate_records, _request_plan
        from stemwise_engine.lyrics_tokenization import display_words
        audio, lyrics = root/(identity+'.wav'), root/(identity+'.txt')
        with wave.open(str(audio), 'wb') as writer:
            writer.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
            writer.writeframes(b'\0'*20000*16*2)
        text = ' '.join(['a']*count)
        lyrics.write_text(text, encoding='utf-8')
        intervals = [(100, 1200), *((1300+i*20, 1310+i*20) for i in range(count-1))]
        case = {'id': identity, 'language': 'en', 'audio': audio.name,
            'audioSha256': hashlib.sha256(audio.read_bytes()).hexdigest(), 'sampleRate': 16000,
            'channels': 1, 'frames': 320000, 'durationMs': 20000, 'heldOutByUs': True,
            'modelInputLyrics': lyrics.name, 'modelInputLyricsSha256': hashlib.sha256(lyrics.read_bytes()).hexdigest(),
            'lines': [{'text': text, 'words': [{'startUtf16': a, 'endUtf16': b, 'startMs': start, 'endMs': end}
                for (a, b, _), (start, end) in zip(display_words(text), intervals)]}]}
        plan = measurement.model_plan(case, root)
        records, _, duration, segments = _request_plan(plan['request'])
        result = {'formatVersion': 5, 'engine': 'torchaudio-mms-fa', 'sampleRate': 16000,
            'durationMs': duration, 'emissionFrameCount': 1000,
            'canonicalTimeTransform': canonical_time_transform(source_sample_rate=16000,
                source_channel_count=1, source_frame_count=320000, target_sample_rate=16000,
                target_frame_count=320000, chunk_target_frames=480000,
                chunks=[chunk_time_map(0, 320000, 0, 1000)]),
            'segmentCount': len(segments), 'segmentBoundaryGuardMs': 250,
            'displayWordCount': count, 'romanizedWordCount': count, 'acousticTokenCount': count, 'lineCount': 1,
            'confidence': dict.fromkeys(('wordP10', 'wordP50', 'wordP90', 'lineP10', 'lineP50', 'lineP90'), .9),
            'lines': _aggregate_records(records, [{'startMs': start, 'endMs': end,
                'confidence': .9, 'tokenCount': 1} for start, end in intervals])}
        measurement.validate_native(plan, result, 'mms')
        return case, plan, result

    def test_more_than_four_cases_retain_completed_batch_before_later_failure(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            cases = [self.case(root, 'case-'+str(index)) for index in range(6)]
            reference = root/'reference.json'
            reference.write_text(json.dumps({'musical': [row[0] for row in cases]}), encoding='utf-8')
            batches = []
            def native(plans, family, directory, *args, on_result=None):
                batches.append([plan['id'] for plan in plans])
                if len(batches) == 2:
                    raise RuntimeError('later batch failed')
                results = {}
                for plan in plans:
                    result = next(row[2] for row in cases if row[1]['id'] == plan['id'])
                    path = directory/(plan['id']+'.json')
                    path.write_text(json.dumps(result), encoding='utf-8')
                    results[plan['id']] = result, False, str(path)
                    if on_result:
                        on_result(plan, results[plan['id']])
                return results
            with patch.object(measurement, 'native_batch', side_effect=native), redirect_stdout(io.StringIO()):
                self.assertEqual(measurement.main(['--reference', str(reference), '--output', str(root/'out'),
                    '--family', 'mms', '--run-models']), 1)
            self.assertEqual(batches, [['case-'+str(i) for i in range(4)], ['case-4', 'case-5']])
            report = json.loads((root/'out/measurement.private.json').read_bytes())
            family = report['families']['mms']
            self.assertEqual([family['case-'+str(i)]['metrics']['joint']['verifiedWords'] for i in range(4)], [2]*4)
            self.assertTrue(all('technicalFailure' in family['case-'+str(i)] for i in (4, 5)))
            totals = family['aggregate']
            self.assertEqual((totals['wordCount'], totals['measuredWordCount'], totals['jointWithinTolerance']), (12, 8, 8))
            self.assertEqual((totals['technicalFailureCases'], totals['jointWordRate']), (2, 8/12))
            self.assertEqual((totals['sustainedWordCount'], totals['sustainedWordsWithinTolerance']), (6, 4))
            self.assertFalse(report['qualificationClaim'])

    def test_serial_failure_keeps_successes_counts_abstentions_and_preserves_terminal_stop(self):
        from stemwise_engine.common import WorkerRetirementError
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            cases = [self.case(root, 'case-'+str(index)) for index in range(3)]
            reference = root/'reference.json'
            reference.write_text(json.dumps({'musical': [row[0] for row in cases]}), encoding='utf-8')
            calls = []
            def serial(plan, family, directory, *args):
                calls.append(plan['id'])
                if plan['id'] == 'case-1':
                    raise RuntimeError('decoder abstained')
                result = next(row[2] for row in cases if row[1]['id'] == plan['id'])
                path = directory/(plan['id']+'.json')
                path.write_text(json.dumps(result), encoding='utf-8')
                return result, False, str(path)
            with patch.object(measurement, 'serial_model', side_effect=serial), redirect_stdout(io.StringIO()):
                self.assertEqual(measurement.main(['--reference', str(reference), '--output', str(root/'out'),
                    '--family', 'sylla', '--run-models']), 1)
            self.assertEqual(calls, ['case-0', 'case-1', 'case-2'])
            family = json.loads((root/'out/measurement.private.json').read_bytes())['families']['sylla']
            self.assertEqual(family['aggregate']['wordCount'], 6)
            self.assertEqual(family['aggregate']['jointWithinTolerance'], 4)
            self.assertEqual(family['aggregate']['technicalFailureCases'], 1)
            fatal = WorkerRetirementError('unconfirmed worker retirement')
            def stopped(plan, *args):
                if plan['id'] == 'case-1':
                    raise fatal
                return serial(plan, *args)
            with patch.object(measurement, 'serial_model', side_effect=stopped), redirect_stdout(io.StringIO()):
                with self.assertRaises(WorkerRetirementError) as raised:
                    measurement.main(['--reference', str(reference), '--output', str(root/'fatal'),
                        '--family', 'sylla', '--run-models'])
            self.assertIs(raised.exception, fatal)
            retained = json.loads((root/'fatal/measurement.private.json').read_bytes())['families']['sylla']
            self.assertEqual(retained['case-0']['metrics']['joint']['verifiedWords'], 2)
            self.assertNotIn('case-2', retained)

    def test_frozen_sylla_assessment_revalidates_all_2090_words_and_counts_missing_outputs(self):
        from stemwise_engine import sylla_forced_alignment as worker
        from stemwise_engine.ctc_worker import canonical_time_transform, chunk_time_map
        frozen = ROOT/'Test suite/fixtures/lyrics-timing/English/sylla-qualification-plan.json'
        policy = json.loads(frozen.read_bytes())
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            project = root/'builder'
            model = root/'model-cache/lyrics-sylla/sylla-600m-v1'/worker.MODEL_SPEC['revision']
            model.mkdir(parents=True)
            spec = copy.deepcopy(worker.MODEL_SPEC)
            vocabulary = {'<blank>': 0, '|': 1, 'a': 2}
            for row in spec['files']:
                raw = json.dumps(vocabulary).encode() if row['name'] == 'vocab.json' else row['name'].encode()
                (model/row['name']).write_bytes(raw)
                row.update(sha256=hashlib.sha256(raw).hexdigest(), byteCount=len(raw))
            runtime = {'runtimeVersion': 1, **copy.deepcopy(spec['runtime']),
                'requestedDevice': 'cuda', 'resolvedDevice': 'cuda', 'cudaAvailable': True,
                'cudaDeviceName': 'fixture GPU, no device opened', 'cudaDeviceCapability': [12, 0],
                'dtype': 'float16', 'torchThreads': 4, 'deviceIndex': 0,
                'torchProducer': {'device': 'cuda', 'torch': spec['runtime']['packages']['torch'],
                    'cudaDriver': 13000, 'cudaRuntime': spec['runtime']['cudaRuntime'], 'cudnn': '91002',
                    'uuid': 'GPU-11111111-1111-1111-1111-111111111111',
                    'name': 'fixture GPU, no device opened', 'capability': [12, 0], 'totalMemoryBytes': 16000000000}}
            with patch.object(worker, 'MODEL_SPEC', spec), patch.object(measurement, 'PROJECT', project):
                model_record, producer = worker.model_identity(model), worker.producer_identity()
                cases = [self.case(root, identity, count) for identity, count in
                    zip(policy['heldOutIds'], (349, 349, 348, 348, 348, 348))]
                rows = {}
                for case, plan, native in cases:
                    directory = root/'outputs'/case['id']
                    directory.mkdir(parents=True)
                    path = directory/'result.private.json'
                    value = {'formatVersion': worker.FORMAT_VERSION, 'engine': worker.ENGINE,
                        'status': 'candidate', 'audioSha256': plan['audioIdentity']['sha256'],
                        'requestSha256': measurement.canonical_sha256(plan['request']),
                        'model': model_record, 'producer': producer, 'runtime': runtime,
                        'decoder': worker.DECODER, 'normalization': {'id': worker.NORMALIZATION_VERSION,
                            'sourceSha256': producer['sylla_forced_alignment.py']},
                        'canonicalTimeTransform': canonical_time_transform(source_sample_rate=16000,
                            source_channel_count=1, source_frame_count=320000, target_sample_rate=16000,
                            target_frame_count=320000, chunk_target_frames=320000,
                            chunks=[chunk_time_map(0, 320000, 0, 1000)]),
                        'emissionWindows': [{'inputStartSample': 0, 'inputEndSample': 320000,
                            'coreStartSample': 0, 'coreEndSample': 320000, 'rawEmissionFrames': 1000,
                            'retainedFirstFrame': 0, 'retainedEndFrame': 1000}],
                        'targetAdmission': [{**row, 'requestLanguage': 'en'}
                            for row in worker.target_admission(plan['request'], 'en', vocabulary)],
                        'supportedAuthority': False, 'publicationAuthority': False, 'corroborationAuthority': False,
                        'lines': [{key: item for key, item in line.items() if key in
                            ('lineIndex', 'lineId', 'sourcePhysicalIndex', 'startMs', 'endMs')}
                            | {'words': [{key: word[key] for key in ('wordId', 'startMs', 'endMs')}
                                for word in line['words']]} for line in native['lines']]}
                    worker.validate_result(value, plan['request'], plan['audioIdentity']['sha256'], model_record,
                        vocabulary, runtime, plan['audioIdentity']['stream'], language='en')
                    path.write_text(json.dumps(value), encoding='utf-8')
                    binding = {'audio': plan['audioIdentity'], 'request': plan['request'], 'model': model_record,
                        'producer': producer, 'runtime': runtime, 'decoder': worker.DECODER}
                    (directory/'binding.private.json').write_text(json.dumps(binding), encoding='utf-8')
                    rows[case['id']] = {'output': str(path.relative_to(ROOT)),
                        'outputSha256': measurement.sha256_file(path),
                        'metrics': {'joint': {'verifiedWords': 999999}, 'qualificationClaim': True}}
                reference, report = root/'reference.json', root/'measurement.private.json'
                reference.write_text(json.dumps({'musical': [row[0] for row in cases]}), encoding='utf-8')
                record = {'referenceSha256': measurement.sha256_file(reference), 'families': {'sylla': rows}}
                report.write_text(json.dumps(record), encoding='utf-8')
                fixture_policy = root/'frozen-plan.json'
                fixture_policy.write_text(json.dumps({**policy, 'referenceSha256': measurement.sha256_file(reference)}),
                    encoding='utf-8')
                pinned = patch.object(measurement, 'SYLLA_PLAN_SHA256', measurement.sha256_file(fixture_policy))
                pinned.start()
                self.addCleanup(pinned.stop)
                assessed = measurement.assess_sylla(reference, report, fixture_policy)
                self.assertEqual(assessed['aggregate']['wordCount'], 2090)
                self.assertEqual(assessed['aggregate']['jointWithinTolerance'], 2090)
                self.assertEqual(assessed['aggregate']['sustainedWordsWithinTolerance'], 6)
                self.assertTrue(all(row['metrics']['jointWordRate'] == 1
                    and row['metrics']['onsetWithinToleranceRate'] == 1
                    and row['metrics']['offsetWithinToleranceRate'] == 1
                    and row['metrics']['sustainedJointWordRate'] == 1 for row in assessed['cases'].values()))
                self.assertEqual(assessed['failures'], [])
                self.assertFalse(assessed['qualificationClaim'])
                self.assertFalse(assessed['publicExportVerified'])
                first = policy['heldOutIds'][0]
                for fault in ('missing', 'changed-word', 'changed-binding', 'unknown-output', 'mixed-device', 'outside-tolerance'):
                    broken = copy.deepcopy(record)
                    affected = policy['heldOutIds'][-1] if fault == 'mixed-device' else first
                    path = ROOT/rows[affected]['output']
                    before = path.read_bytes()
                    bound = path.with_name('binding.private.json')
                    binding_before = bound.read_bytes()
                    if fault == 'missing':
                        del broken['families']['sylla'][first]
                    elif fault == 'changed-word':
                        changed = json.loads(before)
                        changed['lines'][0]['words'][0]['wordId'] = 'unrelated-word'
                        path.write_text(json.dumps(changed), encoding='utf-8')
                        broken['families']['sylla'][first]['outputSha256'] = measurement.sha256_file(path)
                    elif fault == 'changed-binding':
                        changed = json.loads(binding_before)
                        changed['audio']['sha256'] = 'f'*64
                        bound.write_text(json.dumps(changed), encoding='utf-8')
                    elif fault == 'mixed-device':
                        changed = json.loads(before)
                        changed['runtime']['torchProducer']['uuid'] = 'GPU-22222222-2222-2222-2222-222222222222'
                        path.write_text(json.dumps(changed), encoding='utf-8')
                        changed_binding = json.loads(binding_before)
                        changed_binding['runtime'] = changed['runtime']
                        bound.write_text(json.dumps(changed_binding), encoding='utf-8')
                        broken['families']['sylla'][affected]['outputSha256'] = measurement.sha256_file(path)
                    elif fault == 'outside-tolerance':
                        changed = json.loads(before)
                        for line in changed['lines']:
                            for item in (line, *line['words']):
                                item['startMs'] += 500
                                item['endMs'] += 500
                        path.write_text(json.dumps(changed), encoding='utf-8')
                        broken['families']['sylla'][affected]['outputSha256'] = measurement.sha256_file(path)
                    else:
                        broken['families']['sylla'][first] = {'status': 'supported', 'qualificationClaim': True}
                    report.write_text(json.dumps(broken), encoding='utf-8')
                    try:
                        result = measurement.assess_sylla(reference, report, fixture_policy)
                        self.assertEqual(result['aggregate']['wordCount'], 2090)
                        self.assertEqual(result['aggregate']['jointWithinTolerance'], 1742 if fault == 'mixed-device' else 1741)
                        self.assertEqual(result['aggregate']['technicalFailureCases'], 0 if fault == 'outside-tolerance' else 1)
                        self.assertTrue(result['failures'])
                        if fault == 'outside-tolerance':
                            self.assertTrue({'jointWordRate', 'onsetRate', 'offsetRate', 'sustainedJointWordRate'}
                                <= set(result['failures']))
                            self.assertIn(first+':per-recording-joint-rate', result['failures'])
                        self.assertFalse(result['qualificationClaim'])
                    finally:
                        path.write_bytes(before)
                        bound.write_bytes(binding_before)
                from types import SimpleNamespace
                from stemwise_engine.worker_protocol import WorkerProtocolError, WorkerReasonError, emit_worker_failure
                serial_directory = root/'serial-redecode'
                selected_plan = cases[0][1]
                serial_binding = {'audio': selected_plan['audioIdentity'], 'request': selected_plan['request'],
                    'model': model_record, 'producer': producer, 'runtime': runtime, 'decoder': worker.DECODER}
                serial_folder = serial_directory/measurement.canonical_sha256(serial_binding)
                serial_folder.mkdir(parents=True)
                prior = serial_folder/'result.private.json'
                prior.write_bytes((ROOT/rows[first]['output']).read_bytes())
                prior_binding = serial_folder/'binding.private.json'
                prior_binding.write_text(json.dumps(serial_binding), encoding='utf-8')
                retained = prior.read_bytes(), prior_binding.read_bytes()
                commands = []
                native_code = 'wav2vec2-segment-boundary-guard:0:end'
                with redirect_stderr(io.StringIO()) as native_stderr:
                    emit_worker_failure(WorkerReasonError(native_code))
                def failed_worker(command, **kwargs):
                    commands.append(command)
                    if '--runtime-info' in command:
                        return SimpleNamespace(returncode=0, stdout=json.dumps(runtime), stderr='')
                    staged = Path(command[command.index('--output')+1])
                    self.assertNotEqual(staged, prior)
                    self.assertEqual(command[command.index('--emission-cache-dir')+1], str(serial_directory/'emissions'))
                    staged.write_bytes(b'failed partial worker output')
                    return SimpleNamespace(returncode=1, stdout='', stderr=native_stderr.getvalue())
                with patch.object(measurement, 'environment_python', return_value=Path('/pinned-main-python')), \
                        patch.object(measurement, 'environment_lease', return_value=nullcontext()), \
                        patch.object(measurement, 'heavy_worker_stage', return_value=nullcontext()), \
                        patch.object(measurement, 'run_process_tree', side_effect=failed_worker):
                    _, reused, _ = measurement.serial_model(selected_plan, 'sylla', serial_directory,
                        {'projectDir': str(project)}, 60, 'cuda')
                    self.assertTrue(reused)
                    self.assertTrue(all('--runtime-info' in command for command in commands))
                    for existing in (True, False):
                        if not existing:
                            prior.unlink()
                            prior_binding.unlink()
                        with self.subTest(prior_result=existing), self.assertRaises(WorkerProtocolError) as raised:
                            measurement.serial_model(selected_plan, 'sylla', serial_directory,
                                {'projectDir': str(project)}, 60, 'cuda', redecode=True)
                        self.assertEqual(raised.exception.code, native_code)
                        if existing:
                            self.assertEqual((prior.read_bytes(), prior_binding.read_bytes()), retained)
                        else:
                            self.assertFalse(prior.exists())
                            self.assertFalse(prior_binding.exists())
                wrong = json.loads(reference.read_bytes())
                wrong['musical'][0]['lines'][0]['words'][0]['endMs'] += 1
                reference.write_text(json.dumps(wrong), encoding='utf-8')
                report.write_text(json.dumps({**record, 'referenceSha256': measurement.sha256_file(reference)}), encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'frozen annotations'):
                    measurement.assess_sylla(reference, report, fixture_policy)
                wrong['musical'][0]['id'] = 'post-output-replacement'
                reference.write_text(json.dumps(wrong), encoding='utf-8')
                with self.assertRaises(ValueError):
                    measurement.assess_sylla(reference, report, fixture_policy)

    def test_native_batch_passes_the_pinned_phonemizer_language(self):
        from stemwise_engine import lyrics_align_pc as align
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            folder = Path(name)
            commands = []
            def stop_at_capability(command, **kwargs):
                commands.append(command)
                raise RuntimeError('capability boundary')
            plan = {'id': 'language-control', 'audioIdentity': {}, 'request': {}}
            with patch.object(measurement, 'check_inputs'), \
                    patch.object(measurement, 'environment_python', return_value=Path('/python')), \
                    patch.object(align, '_wav2vec2_runtime_provenance', return_value={}), \
                    patch.object(align, '_wav2vec2_model_assets', return_value=(Path('/model'),
                        {'phonemizerLanguage': 'en-us'})), \
                    patch.object(align, '_wav2vec2_helper_hashes', return_value={}), \
                    patch.object(align, '_run_numerical_process', side_effect=stop_at_capability):
                with self.assertRaisesRegex(RuntimeError, 'capability boundary'):
                    measurement.native_batch([plan], 'wav2vec2', folder,
                        {'projectDir': str(measurement.PROJECT)}, 30, 'cuda')
            self.assertEqual(commands[0][commands[0].index('--language')+1], 'en-us')

    def test_ground_truth_never_enters_model_request_and_native_output_reaches_existing_scorer(self):
        from stemwise_engine.ctc_worker import canonical_time_transform, chunk_time_map
        from stemwise_engine.mms_forced_alignment import _aggregate_records, _request_plan
        from stemwise_engine.lyrics_tokenization import display_words
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            audio = root/'audio.wav'
            with wave.open(str(audio), 'wb') as writer:
                writer.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
                writer.writeframes(b'\0'*48000*4)
            text = '🎵 one two\n'
            lyrics = root/'lyrics.txt'
            lyrics.write_text(text, encoding='utf-8')
            ranges = display_words(text.rstrip('\n'))
            case = {'id': 'known-answer-control', 'language': 'en', 'audio': audio.name,
                'audioSha256': hashlib.sha256(audio.read_bytes()).hexdigest(), 'sampleRate': 48000,
                'channels': 2, 'frames': 48000, 'durationMs': 1000, 'modelInputLyrics': lyrics.name,
                'modelInputLyricsSha256': hashlib.sha256(lyrics.read_bytes()).hexdigest(),
                'lines': [{'text': text.rstrip('\n'), 'words': [
                    {'startUtf16': a, 'endUtf16': b, 'startMs': start, 'endMs': end}
                    for (a, b, _), (start, end) in zip(ranges, ((137, 371), (619, 931)))]}]}
            plan = measurement.model_plan(case, root)
            changed = copy.deepcopy(case)
            for word in changed['lines'][0]['words']:
                word['startMs'] += 23
                word['endMs'] -= 17
            changed['provenance'] = {'notForModels': 'arbitrary annotation commentary'}
            self.assertEqual(measurement.model_plan(changed, root), plan)
            serialized = json.dumps(plan['request'])
            for timestamp in (137, 371, 619, 931):
                self.assertNotIn(str(timestamp), serialized)
            self.assertEqual(plan['request']['segments'], [{'segmentIndex': 0, 'startLineIndex': 0,
                'endLineIndex': 1, 'windowStartMs': 0, 'windowEndMs': 1000}])

            records, targets, duration, segments = _request_plan(plan['request'])
            self.assertEqual(targets, ['one', 'two'])
            lines = _aggregate_records(records, [
                {'startMs': start, 'endMs': end, 'confidence': .9, 'tokenCount': 1}
                for start, end in ((147, 361), (629, 921))])
            result = {'formatVersion': 5, 'engine': 'torchaudio-mms-fa', 'sampleRate': 16000,
                'durationMs': duration, 'emissionFrameCount': 50,
                'canonicalTimeTransform': canonical_time_transform(source_sample_rate=48000,
                    source_channel_count=2, source_frame_count=48000, target_sample_rate=16000,
                    target_frame_count=16000, chunk_target_frames=480000,
                    chunks=[chunk_time_map(0, 16000, 0, 50)]),
                'segmentCount': len(segments), 'segmentBoundaryGuardMs': 250,
                'displayWordCount': 2, 'romanizedWordCount': 2, 'acousticTokenCount': 2, 'lineCount': 1,
                'confidence': dict.fromkeys(('wordP10', 'wordP50', 'wordP90', 'lineP10', 'lineP50', 'lineP90'), .9),
                'lines': lines}
            validated = measurement.validate_native(plan, result, 'mms')
            metrics = measurement.score(case, plan, validated['lines'])
            self.assertEqual(metrics['joint']['verifiedWords'], 2)
            self.assertEqual(metrics['onsetsWithin300Ms'], 2)
            self.assertEqual(metrics['offsetsWithin400Ms'], 2)
            self.assertFalse(metrics['qualificationClaim'])
            self.assertFalse(metrics['publicExportVerified'])

            shifted = copy.deepcopy(result)
            shifted['lines'][0]['words'][0]['endMs'] = 850
            shifted['lines'][0]['words'][1].update(startMs=900, endMs=980)
            shifted['lines'][0]['endMs'] = 980
            validated = measurement.validate_native(plan, shifted, 'mms')
            metrics = measurement.score(case, plan, validated['lines'])
            self.assertEqual((metrics['onsetsWithin300Ms'], metrics['offsetsWithin400Ms']), (2, 1))
            self.assertEqual(metrics['joint']['verifiedWords'], 1)
            for fault in ('word-id', 'missing-word', 'out-of-bounds'):
                malformed = copy.deepcopy(result)
                if fault == 'word-id':
                    malformed['lines'][0]['words'][0]['wordId'] = 'unrelated-word'
                elif fault == 'missing-word':
                    malformed['lines'][0]['words'].pop()
                else:
                    malformed['lines'][0]['words'][0]['endMs'] = 2000
                with self.subTest(fault=fault), self.assertRaises(ValueError):
                    measurement.validate_native(plan, malformed, 'mms')


if __name__ == '__main__':
    unittest.main()
