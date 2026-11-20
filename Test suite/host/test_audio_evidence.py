from pathlib import Path
import copy
import json
import subprocess
import tempfile
import unittest
from unittest import mock

import audio_evidence as audio


class AudioEvidenceTest(unittest.TestCase):
    def test_self_test_receipts_cannot_hide_failed_or_skipped_subtests(self):
        class Fixture(unittest.TestCase):
            def runTest(self):
                with self.subTest(case='actual child'):
                    if disposition == 'failed': self.fail('injected subtest failure')
                    if disposition == 'error': raise RuntimeError('injected subtest error')
                    if disposition == 'skipped': self.skipTest('injected subtest skip')
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            path = Path(directory) / 'receipt.json'
            for disposition in ('failed', 'error', 'skipped', 'passed'):
                with self.subTest(disposition=disposition):
                    case = Fixture()
                    with mock.patch.object(audio, 'self_test_cases', return_value=[case]), mock.patch.object(
                            audio, 'host_inventory', return_value=[case.id()]):
                        if disposition == 'passed': audio.run_self_tests(path)
                        else:
                            with self.assertRaises(ValueError): audio.run_self_tests(path)
                    self.assertEqual([[case.id(), 'failed' if disposition == 'error' else disposition]],
                                     json.loads(path.read_text(encoding='utf-8')))

    def test_memory_shards_reconcile_each_actual_xml_and_instrumented_binary(self):
        sanitizer = audio.runner_module('test_sanitizer_shards', audio.SUITE/'native/run_sanitizers.py')
        required = ['core-case', *(f'stemwise_song_render_pipeline_long_loop_canonical_{seconds}s_tuned_9_parts_test'
                                  for seconds in (30, 37))]
        with tempfile.TemporaryDirectory(dir=audio.SUITE/'work') as directory, mock.patch(
                'subprocess.check_output', return_value='                 U __asan_init\n                 U __ubsan_handle_type_mismatch_v1\n'):
            root = Path(directory)
            reports = []
            for shard in sanitizer.MEMORY_SHARDS:
                work = root/shard
                work.mkdir()
                selected = sanitizer.partition_tests(required, shard)
                xml = work/'cases.xml'
                xml.write_text('<testsuite>'+''.join('<testcase name="'+name+'"/>' for name in selected)+'</testsuite>', encoding='utf-8')
                artifacts = {}
                for target in sanitizer.targets_for('memory', shard):
                    binary = work/target
                    binary.write_bytes(b'instrumented fixture')
                    artifacts[binary.relative_to(root).as_posix()] = audio.digest(binary)
                reports.append({'schemaVersion': 1, 'lane': 'sanitizer-memory', 'sourceSha256': 'current', 'shard': shard,
                    'required': list(selected), 'records': [[name, 'passed'] for name in selected], 'tools': {'cc': 'test'},
                    'xml': audio.file_binding(xml, root), 'artifacts': artifacts})
            self.assertEqual(sorted(required), audio.validate_native_shards(reports, 'sanitizer-memory', 'current', required, root))
            partial = root/'partial.json'
            partial.write_text(json.dumps(reports[0]), encoding='utf-8')
            # Inventory qualification is a separate boundary; execute the actual lane consumer here.
            with mock.patch.object(audio, 'validate_current_plan'):
                result = audio.candidate_result({'sourceSha256': 'current', 'gitRevision': 'fixture', 'profile': 'host',
                    'required': {'sanitizer-memory': required}}, {'sanitizer-memory': partial}, root)
            self.assertNotIn('sanitizer-memory', result['executed'])
            self.assertTrue(any('partial sanitizer shard' in failure for failure in result['failures']))
            for changed in (reports[:2], reports+[reports[0]], [reports[0], reports[0], reports[2]]):
                with self.assertRaisesRegex(ValueError, 'shard'):
                    audio.validate_native_shards(changed, 'sanitizer-memory', 'current', required, root)
            xml.write_text('<testsuite><testcase name="'+selected[0]+'"><failure/></testcase></testsuite>', encoding='utf-8')
            reports[-1]['xml'] = audio.file_binding(xml, root)
            with self.assertRaises(ValueError):
                audio.validate_native_shards(reports, 'sanitizer-memory', 'current', required, root)

    def test_managed_audio_selection_matches_device_isolation_without_optional_acoustics(self):
        inventory = audio.runner_module('audio_isolation_inventory',
                                        audio.SUITE / 'device/device_inventory.py').build_accounting()
        required = set(audio.managed_inventory(audio.SUITE / 'android/instrumentation/java'))
        self.assertEqual({row['id'] for row in inventory['methods']
                          if row['requiresAudioIsolation'] and not row['optionalAcoustic']}, required)
        live_ui = {row['id'] for row in inventory['methods'] if
                   row['class'] == 'app.stemwise.ui.TuningSpeedAvailabilityInstrumentationTest' or
                   row['id'] == 'app.stemwise.ui.player.surface.DesignSystemNativePlayerInstrumentationTest#'
                   'realHeldBTracksCursorAndEveryFastCrossingWrapsWithinTheOutputServiceBudget'}
        self.assertEqual(6, len(live_ui))
        self.assertTrue(live_ui <= required)
        self.assertFalse(any('Acoustic' in selector for selector in required))

    def test_full_jvm_execution_must_be_complete_before_audio_projection(self):
        expected = audio.complete_jvm_inventory()
        plan = {'required': {'jvm': expected[:2]}}
        records = [(name, 'passed') for name in expected]
        self.assertEqual(records[:2], audio.project_jvm_records(plan, records, 'all'))
        for bad in (records[:-1], records + [records[-1]], records + [('unknown#method', 'passed')],
                    records[:-1] + [(expected[-1], 'skipped')], records[:-1] + [(expected[-1], 'failed')]):
            with self.assertRaises(ValueError):
                audio.project_jvm_records(plan, bad, 'all')
        with self.assertRaises(ValueError):
            audio.project_jvm_records(plan, records, 'audio')

    def test_full_device_execution_requires_complete_raw_records_and_accounting_before_projection(self):
        expected = audio.complete_device_inventory()
        inventory = audio.runner_module('projection_inventory', audio.SUITE / 'device/device_inventory.py').build_accounting()
        manifest = audio.load_json(audio.SUITE / 'device/device-lanes.json')
        phases = [manifest['fatalTwoProcess']['phaseA'], manifest['fatalTwoProcess']['phaseB']]
        phases += [name for workflow in manifest['lifecycleWorkflows'] for name in workflow['phases']]
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as temporary:
            directory = Path(temporary)
            report = {'mode': 'Device', 'lanes': [{'lane': lane, 'status': 'PASS'} for lane in
                ('ordinary', 'explicit-suite', 'lifecycle-phase', 'fatal-two-process', 'native-process-fault')]}
            accounting = {'status': 'PASS', 'expectedMethods': expected, 'passingMethods': expected,
                          'skippedMethods': [], 'missingMethods': [], 'unexpectedMethods': []}
            for key, body in [('deviceInventory', inventory), ('deviceExecutionAccounting', accounting)]:
                path = directory / (key + '.json')
                path.write_text(json.dumps(body), encoding='utf-8')
                report[key] = {'path': path.name, 'sha256': audio.digest(path)}
            direct = []
            for index, name in enumerate(phases):
                path = directory / f'direct-{index}.json'
                path.write_text(json.dumps({'selector': name}), encoding='utf-8')
                direct.append(path)
            plan = {'required': {'device': expected[:2]}}
            records = [(name, 'passed') for name in expected]
            project = lambda rows, actual=report, phases=direct: audio.project_device_records(
                directory / 'run.json', actual, plan, rows, phases)
            self.assertEqual(records[:2], project(records))
            for bad in (records[:-1], records + [records[-1]], records + [('unknown#method', 'passed')],
                        records[:-1] + [(expected[-1], 'skipped')], records[:-1] + [(expected[-1], 'failed')]):
                with self.assertRaises(ValueError):
                    project(bad)
            with self.assertRaises(ValueError):
                project(records, phases=direct[:-1])
            for key in ('deviceInventory', 'deviceExecutionAccounting'):
                bad = copy.deepcopy(report)
                del bad[key]
                with self.assertRaises(ValueError):
                    project(records, actual=bad)
            bad = copy.deepcopy(report)
            bad['lanes'][0]['status'] = 'FAIL'
            with self.assertRaises(ValueError):
                project(records, actual=bad)

    def test_expected_process_retirement_requires_started_test_os_absence_and_fresh_process_pass(self):
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            phases = audio.load_json(audio.SUITE / 'device/device-lanes.json')['fatalTwoProcess']
            run = {'runId': 'current'}
            pair = {'application': 'a' * 64, 'androidTest': 'b' * 64}
            marker = root / 'marker.txt'
            marker.write_text('current\n1234\n', encoding='utf-8')
            absent = {'exitCode': 1, 'stdout': '', 'stderr': ''}
            proof = {'schemaVersion': 1, 'runId': 'current', 'marker': audio.file_binding(marker, root),
                     'markerBefore': absent, 'processAfter': absent, 'markerAfter': absent}
            proof_path = root / 'fatal-retirement.json'
            proof_path.write_text(json.dumps(proof), encoding='utf-8')
            paths = []
            for phase in ('phaseA', 'phaseB'):
                selector = phases[phase]
                owner, method = selector.split('#')
                start = f'INSTRUMENTATION_STATUS: class={owner}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\n'
                text = start + ('INSTRUMENTATION_FAILED: Process crashed\n' if phase == 'phaseA' else
                    start.replace('CODE: 1', 'CODE: 0') + 'INSTRUMENTATION_CODE: -1\nOK (1 test)\n')
                raw = root / (phase + '.txt')
                raw.write_text(text, encoding='utf-8')
                record = {'schemaVersion': 1, 'selector': selector, 'exitCode': 0, 'stderr': '',
                    'component': 'app.stemwise.development.test/app.stemwise.qualification.DevelopmentStandardInstrumentationRunner',
                    'arguments': {'stemwiseDisposableTarget': 'true',
                        'stemwiseFatalRetirementQualification': 'true', 'stemwiseFatalRetirementRunId': 'current',
                        'stemwiseExpectedApplicationSha256': pair['application'], 'stemwiseExpectedTestSha256': pair['androidTest']},
                    'artifacts': [{'kind': kind, 'role': 'qualification', 'sha256': value} for kind, value in pair.items()],
                    'output': audio.file_binding(raw, root)}
                path = root / (phase + '.json')
                path.write_text(json.dumps(record), encoding='utf-8')
                paths.append(path)
            self.assertEqual([(phases['phaseA'], 'passed'), (phases['phaseB'], 'passed')],
                             audio.fatal_retirement_records(root / 'run.json', run, paths, pair, root))
            for change in ('wrong-job', 'inspection-error', 'marker-already-existed'):
                invalid = copy.deepcopy(proof)
                if change == 'wrong-job':
                    invalid['runId'] = 'previous'
                elif change == 'inspection-error':
                    invalid['processAfter']['exitCode'] = 127
                else:
                    invalid['markerBefore']['exitCode'] = 0
                proof_path.write_text(json.dumps(invalid), encoding='utf-8')
                with self.subTest(change=change), self.assertRaises(ValueError):
                    audio.fatal_retirement_records(root / 'run.json', run, paths, pair, root)
            proof_path.write_text(json.dumps(proof), encoding='utf-8')
            raw = root / 'phaseA.txt'
            raw.write_text(raw.read_text() + 'FATAL_RECENTS_PROCESS_RETIREMENT_DID_NOT_OCCUR', encoding='utf-8')
            record = audio.load_json(paths[0])
            record['output'] = audio.file_binding(raw, root)
            paths[0].write_text(json.dumps(record), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'failure sentinel'):
                audio.fatal_retirement_records(root / 'run.json', run, paths, pair, root)

    def test_registry_rejects_missing_conflict_order_and_unknown_negative_detector(self):
        registry = audio.load_json(audio.ROOT / 'StemWise/contracts/audio/engine-invariants.json')
        inventories = {}
        references = [name for item in registry['invariants'] for name in item['tests'] + item['negativeControls']]
        references += [name for item in registry['replayCoverage'] for order in item['orders'] for name in order['tests']]
        for name in references:
            lane, test = name.split(':', 1)
            inventories.setdefault(lane, []).append(test)
        audio.validate_registry(registry, inventories)
        for change in ('missing-order', 'missing-scenario', 'unknown-detector'):
            invalid = copy.deepcopy(registry)
            if change == 'missing-order':
                invalid['replayCoverage'][0]['orders'] = invalid['replayCoverage'][0]['orders'][:1]
            elif change == 'missing-scenario':
                invalid['replayCoverage'].pop()
            else:
                invalid['invariants'][0]['negativeControls'] = ['native:nonexistent']
            with self.subTest(change=change), self.assertRaises(ValueError):
                audio.validate_registry(invalid, inventories)

    def test_native_plan_cannot_omit_a_current_ctest_registration(self):
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            binary = root / 'engine'
            binary.write_bytes(b'not executed by this inventory test')
            (root / 'CMakeCache.txt').write_text('CMAKE_CTEST_COMMAND:INTERNAL=ctest\n', encoding='utf-8')
            actual = {'tests': [{'name': name, 'command': [str(binary)]} for name in ('one', 'two')]}
            with mock.patch.object(audio.subprocess, 'check_output', return_value=json.dumps(actual).encode()):
                audio.validate_native_discovery(actual, root)
                with self.assertRaisesRegex(ValueError, 'omits or changes'):
                    audio.validate_native_discovery({'tests': actual['tests'][:1]}, root)

    def test_boundary_requires_both_intended_compiler_rejections_and_restored_build(self):
        report = {'schemaVersion': 1, 'restoredCompileExitCode': 0, 'records': [
            {'id': 'app-private-owner-reference', 'exitCode': 1,
             'diagnostic': ['AudioBoundaryProbe.kt: Cannot access NativePlaybackEngineController']},
            {'id': 'engine-app-dependency', 'exitCode': 1,
             'diagnostic': ['AudioBoundaryProbe.kt: Unresolved reference Song']}]}
        self.assertEqual(list(audio.BOUNDARY_IDS), [name for name, _ in audio.boundary_records(report)])
        for change in ('accepted', 'unrelated', 'missing', 'restore-failed'):
            invalid = copy.deepcopy(report)
            if change == 'accepted':
                invalid['records'][0]['exitCode'] = 0
            elif change == 'unrelated':
                invalid['records'][0]['diagnostic'] = ['Gradle compilation failed']
            elif change == 'missing':
                invalid['records'].pop()
            else:
                invalid['restoredCompileExitCode'] = 1
            with self.subTest(change=change), self.assertRaises(ValueError):
                audio.boundary_records(invalid)

    def test_protocol_requires_solver_properties_and_the_intended_counterexample(self):
        report = {'schemaVersion': 1, 'lane': 'protocol', 'sourceSha256': 'current', 'sourceEndSha256': 'current',
                  'required': ['immediate', 'reject-early-ack'], 'tools': {'esbmc': '8.5.0'}, 'cases': [
                      {'id': 'immediate', 'expected': 'verified', 'returnCode': 0,
                       'stdout': '** 0 of 4 properties failed, 4 passed\nVERIFICATION SUCCESSFUL', 'stderr': ''},
                      {'id': 'reject-early-ack', 'expected': 'counterexample', 'returnCode': 1,
                       'stdout': 'Violated property:\n  assertion roles.activeRing() == outgoing\n** 1 of 4 properties failed',
                       'stderr': '', 'assertion': 'roles.activeRing() == outgoing'}]}
        self.assertEqual(2, len(audio.protocol_records(report, report['required'], 'current')))
        for change in ('empty', 'false-success', 'wrong-counterexample', 'skipped', 'stale',
                       'forged-kind', 'forged-assertion', 'signal-after-counterexample', 'wrong-exit',
                       'intended-assertion-in-results-only'):
            invalid = copy.deepcopy(report)
            if change == 'empty':
                invalid['cases'] = []
            elif change == 'false-success':
                invalid['cases'][0]['stdout'] = 'PASS'
            elif change == 'wrong-counterexample':
                invalid['cases'][1]['assertion'] = 'unrelated failure'
            elif change == 'skipped':
                invalid['cases'][0]['returnCode'] = 124
            elif change == 'forged-kind':
                invalid['cases'][1] = dict(invalid['cases'][0], id='reject-early-ack')
            elif change == 'forged-assertion':
                invalid['cases'][1]['assertion'] = 'unrelated failure'
                invalid['cases'][1]['stdout'] = '** 1 of 4 properties failed\nViolated property: unrelated failure'
            elif change == 'signal-after-counterexample':
                invalid['cases'][1]['returnCode'] = -11
            elif change == 'wrong-exit':
                invalid['cases'][1]['returnCode'] = 10
            elif change == 'intended-assertion-in-results-only':
                invalid['cases'][1]['stdout'] = ('Violated property:\n  assertion wrong_owner\n** Results:\n'
                    '  assertion roles.activeRing() == outgoing\n** 1 of 4 properties failed')
            else:
                invalid['sourceSha256'] = 'old'
            with self.subTest(change=change), self.assertRaises(ValueError):
                audio.protocol_records(invalid, report['required'], 'current')

    def test_absent_aggregate_and_blocked_or_forged_pass_cannot_promote(self):
        for receipt in ({}, {'result': 'blocked'}, {'result': 'failed'},
                        {'result': 'passed', 'sourceSha256': 'wrong candidate'}):
            with self.subTest(receipt=receipt), self.assertRaises(ValueError):
                audio.validate_promotion(receipt, {}, Path('absent.apk'))
        plan = {'sourceSha256': 'current', 'gitRevision': 'revision', 'profile': 'android-physical'}
        with mock.patch.object(audio, 'validate_current_plan'):
            result = audio.candidate_result(plan, {})
        self.assertEqual('blocked', result['result'])
        self.assertEqual(list(audio.EVIDENCE_LANES), result['missingLanes'])
        self.assertFalse(result['executed'])

    def test_ci_aggregate_rejects_unexpected_skip_cancel_failure_and_missing_job(self):
        good = dict.fromkeys(audio.SOFTWARE_JOBS, 'success')
        audio.validate_ci_jobs(good)
        for disposition in ('skipped', 'cancelled', 'failure', None):
            invalid = dict(good)
            invalid['audio-check'] = disposition
            with self.subTest(disposition=disposition), self.assertRaises(ValueError):
                audio.validate_ci_jobs(invalid)
        good.pop('audio-protocol')
        with self.assertRaises(ValueError):
            audio.validate_ci_jobs(good)

    def test_mutation_requires_healthy_baseline_and_its_exact_runtime_failure(self):
        report = {'schemaVersion': 1, 'lane': 'negative-controls', 'sourceSha256': 'current',
                  'sourceEndSha256': 'current', 'required': ['stale-control'], 'sanitizers': False,
                  'cases': [{'id': 'stale-control', 'assertion': 'AE-01 stale control changed independent current endpoint', 'baseline': {'returnCode': 0},
                             'returnCode': 1, 'stdout': '', 'stderr': 'Late stem mix control mailbox test failure: AE-01 stale control changed independent current endpoint',
                             'artifactSha256': 'a' * 64}]}
        self.assertEqual([('stale-control', 'passed')], audio.negative_records(report, report['required'], 'current'))
        for change in ('crash', 'unrelated', 'bad-baseline', 'not-instrumented', 'forged-assertion',
                       'echoed-assertion-wrong-failure'):
            invalid = copy.deepcopy(report)
            if change == 'crash':
                invalid['cases'][0]['returnCode'] = 139
            elif change == 'unrelated':
                invalid['cases'][0]['stdout'] = 'compile error'
                invalid['cases'][0]['stderr'] = ''
            elif change == 'bad-baseline':
                invalid['cases'][0]['baseline']['returnCode'] = 1
            elif change == 'forged-assertion':
                invalid['cases'][0]['assertion'] = 'unrelated failure'
                invalid['cases'][0]['stdout'] = 'FAIL: unrelated failure'
            elif change == 'echoed-assertion-wrong-failure':
                invalid['cases'][0]['stdout'] = invalid['cases'][0]['assertion']
                invalid['cases'][0]['stderr'] = 'Late stem mix control mailbox test failure: unrelated failure'
            else:
                invalid['sanitizers'] = True
            with self.subTest(change=change), self.assertRaises(ValueError):
                audio.negative_records(invalid, report['required'], 'current', memory=change == 'not-instrumented')

    def test_actual_junit_is_required_and_each_disposition_is_checked(self):
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            path = Path(directory) / 'results.xml'
            for child in ('', '<skipped/>', '<failure message="fault"/>', '<error/>'):
                path.write_text('<testsuite><testcase classname="engine" name="acceptance">' +
                                child + '</testcase></testsuite>', encoding='utf-8')
                actual = audio.xml_records(path, 'jvm')
                if child:
                    with self.assertRaisesRegex(ValueError, 'failed/skipped'):
                        audio.reconcile(['engine#acceptance'], actual)
                else:
                    self.assertEqual(['engine#acceptance'], audio.reconcile(['engine#acceptance'], actual))
            for invalid in ('<testsuite/>', '<result passed="true"/>'):
                path.write_text(invalid, encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'empty execution'):
                    audio.xml_records(path, 'native')

    def test_missing_duplicate_unexpected_and_skipped_execution_cannot_pass(self):
        for actual in ([], [('other', 'passed')], [('expected', 'passed')] * 2,
                       [('expected', 'skipped')], [('expected', 'failed')]):
            with self.subTest(actual=actual), self.assertRaises(ValueError):
                audio.reconcile(['expected'], actual)

    def test_stale_or_changed_source_identity_cannot_reuse_results(self):
        plan = {'schemaVersion': 1, 'sourceSha256': 'before', 'required': {'native': ['actual']}}
        records = {'native': [('actual', 'passed')]}
        self.assertEqual({'native': ['actual']}, audio.verify(plan, records, 'before'))
        with self.assertRaisesRegex(ValueError, 'identity'):
            audio.verify(plan, records, 'after')

    def test_partial_aggregate_is_not_full_qualification(self):
        plan = {'schemaVersion': 1, 'sourceSha256': 'same',
                'required': {'native': ['actual'], 'device': ['physical']}}
        with self.assertRaisesRegex(ValueError, 'aggregate lane'):
            audio.verify(plan, {'native': [('actual', 'passed')]}, 'same')
        self.assertEqual({'native': ['actual']}, audio.verify(plan,
            {'native': [('actual', 'passed')]}, 'same', lanes=('native',)))
        with self.assertRaises(ValueError):
            audio.reconcile(plan['required']['device'], [])

    def test_native_lane_binds_actual_xml_binary_source_and_selection(self):
        # Only the external nm boundary is stubbed; the consumer parses its real output contract.
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory, mock.patch(
                'subprocess.check_output', return_value='                 U __asan_init\n'
                '                 U __ubsan_handle_type_mismatch_v1\n') as symbols:
            root = Path(directory)
            targets = audio.runner_module('test_sanitizer_inventory', audio.SUITE / 'native/run_sanitizers.py').TARGETS
            xml, binary = root / 'native.xml', root / targets[0]
            xml.write_text('<testsuite><testcase name="actual"/></testsuite>', encoding='utf-8')
            for target in targets:
                (root / target).write_bytes(b'candidate')
            report = {'schemaVersion': 1, 'lane': 'sanitizer-memory', 'sourceSha256': 'current',
                      'required': ['actual'], 'records': [['actual', 'passed']], 'tools': {'cc': 'test'},
                      'xml': {'path': xml.name, 'sha256': audio.digest(xml)},
                      'artifacts': {name: audio.digest(root / name) for name in targets}}
            self.assertEqual(['actual'], audio.validate_native_lane(report,
                'sanitizer-memory', 'current', ['actual'], root))
            self.assertEqual(len(targets), symbols.call_count)
            instrumented = symbols.return_value
            for unsanitized in ('                 U puts\n',
                                '00000001 T __asan_init\n00000002 T __ubsan_handle_type_mismatch_v1\n',
                                '                 U fake___asan_init\n                 U __ubsan_handle_fake\n',
                                '                 U __asan_init\n'):
                symbols.return_value = unsanitized
                with self.subTest(symbols=unsanitized), self.assertRaisesRegex(ValueError, 'instrumentation absent'):
                    audio.validate_native_lane(report, 'sanitizer-memory', 'current', ['actual'], root)
            symbols.return_value = instrumented
            for change in ({'records': [['invented', 'passed']]}, {'artifacts': {}},
                           {'artifacts': {binary.name: audio.digest(binary)}},
                           {'lane': 'sanitizer-thread'}, {'required': []},
                           {'sourceSha256': 'old'}, {'xml': {'path': 'absent.xml', 'sha256': 'fake'}}):
                invalid = copy.deepcopy(report)
                invalid.update(change)
                with self.subTest(change=change), self.assertRaises((ValueError, OSError)):
                    audio.validate_native_lane(invalid, 'sanitizer-memory', 'current', ['actual'], root)
            binary.write_bytes(b'different candidate')
            with self.assertRaisesRegex(ValueError, 'artifact'):
                audio.validate_native_lane(report, 'sanitizer-memory', 'current', ['actual'], root)

    def test_host_receipt_cannot_omit_one_of_two_current_executables(self):
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            first, second = root / 'first', root / 'second'
            first.write_bytes(b'one')
            second.write_bytes(b'two')
            required = {lane: ['actual'] for lane in audio.HOST_LANES}
            plan = {'sourceSha256': 'current', 'gitRevision': 'head', 'required': required,
                    'nativeDiscovery': {'tests': [{'command': [str(path)]} for path in (first, second)]}}
            report = {'schemaVersion': 1, 'sourceSha256': 'current', 'gitRevision': 'head', 'required': required,
                      'toolchain': {'python': 'actual', 'native': ['compiler']},
                      'artifacts': [audio.file_binding(first, root)]}
            with self.assertRaisesRegex(ValueError, 'omits a required native executable'):
                audio.validate_host_receipt(report, plan, root)

    def test_device_requires_current_prepost_source_and_exact_apk_and_execution_bindings(self):
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            selections = root / 'selection' / 'results'
            selections.mkdir(parents=True)
            xml = selections / 'TEST-actual.xml'
            xml.write_text('<testsuite><testcase classname="engine" name="actual"/></testsuite>', encoding='utf-8')
            artifacts = []
            frozen = []
            for kind in ('application', 'androidTest'):
                apk = root / (kind + '.apk')
                apk.write_bytes(kind.encode())
                entry = dict(audio.file_binding(apk, root), kind=kind)
                artifacts.append(entry)
                frozen.append({'role': 'qualification', 'kind': kind, 'sha256': entry['sha256'].upper()})
            selection = selections.parent / 'selection.json'
            guarded_arguments = ['-Pandroid.testInstrumentationRunnerArguments.stemwiseExpectedApplicationSha256=' + artifacts[0]['sha256'],
                '-Pandroid.testInstrumentationRunnerArguments.stemwiseExpectedTestSha256=' + artifacts[1]['sha256']]
            selection.write_text(json.dumps({'status': 'PASS', 'artifacts': frozen,
                                             'gradleArguments': guarded_arguments}), encoding='utf-8')
            run = root / 'run.json'
            configuration = {'buildType': 'development', 'debuggable': True, 'minifyEnabled': True, 'shrinkResources': True,
                             'identity': 'app.stemwise.development', 'signing': 'disposable'}
            build_evidence = []
            for kind, variant in (('application', 'development'), ('androidTest', 'developmentAndroidTest'), ('mapping', None)):
                path = root / (kind + '.txt')
                path.write_text(json.dumps({'variantName': variant, 'applicationId': configuration['identity'] +
                                ('.test' if kind == 'androidTest' else '')}) if variant else '# compiler: R8\n', encoding='utf-8')
                build_evidence.append(dict(audio.file_binding(path, root), kind=kind))
            report = {'status': 'PASS', 'deviceProfile': {'serial': 'approved'}, 'artifacts': frozen,
                      'audioArtifacts': artifacts, 'audioSourceSha256': 'current', 'audioSourceEndSha256': 'current',
                      'audioBuildConfiguration': configuration, 'audioBuildEvidence': build_evidence,
                      'audioExecutions': [audio.file_binding(path, root) for path in (selection, xml)]}
            plan = {'sourceSha256': 'current', 'deviceProfile': {'identity': {'serial': 'approved'}},
                    'candidateProfile': {'buildConfiguration': configuration},
                    'required': {'device': ['engine#actual']}}
            run.write_text(json.dumps(report), encoding='utf-8')
            self.assertEqual([('engine#actual', 'passed')], audio.device_records(run, plan, root))
            for change in ({'audioSourceSha256': 'old'}, {'audioSourceEndSha256': 'after'},
                           {'deviceProfile': {'serial': 'different'}}, {'audioArtifacts': artifacts[:1]},
                           {'audioBuildConfiguration': dict(configuration, buildType='release')},
                           {'audioExecutions': report['audioExecutions'][:1]}):
                invalid = dict(report, **change)
                run.write_text(json.dumps(invalid), encoding='utf-8')
                with self.subTest(change=change), self.assertRaises(ValueError):
                    audio.device_records(run, plan, root)
            run.write_text(json.dumps(report), encoding='utf-8')
            xml_contents = xml.read_bytes()
            xml.unlink()
            missing_xml = dict(report, audioExecutions=[audio.file_binding(selection, root)])
            run.write_text(json.dumps(missing_xml), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'actual JUnit'):
                audio.device_records(run, plan, root)
            xml.write_bytes(xml_contents)
            run.write_text(json.dumps(report), encoding='utf-8')
            (root / 'application.apk').write_bytes(b'different binary')
            with self.assertRaisesRegex(ValueError, 'evidence'):
                audio.device_records(run, plan, root)

    def test_all_candidate_device_lanes_require_the_same_application_and_driver(self):
        import audio_physical_evidence
        import audio_development_evidence
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            paths = {lane: root / (lane + '.json') for lane in ('device', 'physical-performance', 'development')}
            device = {'audioArtifacts': [{'kind': 'application', 'sha256': 'a' * 64},
                                         {'kind': 'androidTest', 'sha256': 'b' * 64}]}
            physical = {'auditApk': {'sha256': 'a' * 64}, 'auditTestApk': {'sha256': 'b' * 64}}
            release = {'apk': {'sha256': 'a' * 64}, 'testApk': {'sha256': 'b' * 64}}
            for lane, value in zip(paths, (device, physical, release)):
                paths[lane].write_text(json.dumps(value), encoding='utf-8')
            plan = {'sourceSha256': 'current', 'gitRevision': 'head', 'profile': 'physical'}
            with mock.patch.object(audio, 'validate_current_plan'), mock.patch.object(audio, 'device_records', return_value=[]), \
                    mock.patch.object(audio_physical_evidence, 'validate_physical', return_value=[]), \
                    mock.patch.object(audio_development_evidence, 'validate_development', return_value=[]):
                self.assertEqual('blocked', audio.candidate_result(plan, paths, root)['result'])
                for kind in ('auditApk', 'auditTestApk'):
                    invalid = copy.deepcopy(physical)
                    invalid[kind]['sha256'] = 'c' * 64
                    paths['physical-performance'].write_text(json.dumps(invalid), encoding='utf-8')
                    result = audio.candidate_result(plan, paths, root)
                    self.assertEqual('failed', result['result'])
                    self.assertTrue(any('different candidate APK pairs' in item for item in result['failures']))

    def test_deleted_dirty_input_changes_identity_without_breaking_discovery(self):
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '--quiet', str(root)], check=True)
            source = root / 'StemWise' / 'source.kt'
            source.parent.mkdir()
            source.write_bytes(b'original')
            subprocess.run(['git', 'add', '--', 'StemWise/source.kt'], cwd=root, check=True)
            original = audio.source_identity(root)
            source.write_bytes(b'changed')
            self.assertNotEqual(original, audio.source_identity(root))
            source.unlink()
            deleted = audio.source_identity(root)
            self.assertNotEqual(original, deleted)
            self.assertEqual(deleted, audio.source_identity(root))
            guide = root / 'SEND_TO_AGENTS.md'
            guide.write_text('packaged guide', encoding='utf-8')
            self.assertNotEqual(deleted, audio.source_identity(root))
            previous = audio.source_identity(root)
            producer = root / 'PC side/stemwise-playlist-builder/src/packages.py'
            producer.parent.mkdir(parents=True)
            producer.write_text('fixture producer', encoding='utf-8')
            self.assertNotEqual(previous, audio.source_identity(root))

    def test_jvm_case_has_one_compilation_owner(self):
        owners = audio.jvm_test_owners()
        self.assertEqual('audio-engine', owners['app.stemwise.playback.engine.NativePlaybackEngineControllerTest'])
        self.assertEqual('app', owners['app.stemwise.playback.engine.NativeSourceWaveformProcessorTest'])
        with tempfile.TemporaryDirectory(dir=audio.SUITE / 'work') as directory:
            root = Path(directory)
            for module in ('unit', 'audio-engine/unit'):
                path = root / ('Test suite/android/' + module + '/java/Duplicate.kt')
                path.parent.mkdir(parents=True)
                path.write_text('package fixture\nclass Duplicate { @Test fun actual() {} }', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'duplicate JVM compilation owner'):
                audio.jvm_test_owners(root)


if __name__ == '__main__':
    unittest.main()
