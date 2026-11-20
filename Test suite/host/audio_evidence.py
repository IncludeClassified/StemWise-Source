"""Discover audio checks and reconcile actual execution records for one current candidate."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SUITE = ROOT / 'Test suite'
sys.path.insert(0, str(SUITE))
from source_test_inventory import is_managed_audio_test, is_optional_acoustic_test, source_tests

HOST_LANES = ('native', 'jvm', 'host', 'boundary')
EVIDENCE_LANES = ('host', 'sanitizer-memory', 'sanitizer-thread', 'protocol',
                  'negative-controls', 'negative-controls-memory', 'negative-controls-managed',
                  'device', 'physical-performance', 'development')
CANDIDATE_LANES = (*HOST_LANES, *EVIDENCE_LANES[1:])
SOFTWARE_JOBS = ('audio-check', 'audio-memory-core', 'audio-memory-long-30', 'audio-memory-long-37',
                 'audio-thread', 'audio-protocol', 'audio-negative-controls')
BOUNDARY_IDS = ('app-private-owner-reference', 'engine-app-dependency')
REPLAY_SCENARIOS = {
    'selection-late-completion', 'same-song-old-lease', 'transport-during-replacement',
    'partial-zero-pause-resume', 'queued-source-replacement', 'route-stop-source-change',
    'seek-eof-cold-opus', 'clear-retarget-source-change', 'decoder-failure-replacement',
    'writer-native-retirement', 'cache-refill-eviction', 'controls-prepared-handoff',
}


def load_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def runner_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    hasher = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
    return hasher.hexdigest()


def source_identity(root=ROOT):
    # Include all Android integration/build inputs conservatively, including dirty/new files.
    paths = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z', '--',
        'StemWise', 'Test suite/native', 'Test suite/android', 'Test suite/device',
        'Test suite/fixtures', 'Test suite/host', 'Test suite/source_test_inventory.py',
        'Test suite/Run-Tester.ps1', '.github/workflows', 'SEND_TO_AGENTS.md', 'PC side/pyproject.toml',
        'PC side/stemwise-playlist-builder/src',
        'Test suite/pc/unit/test_playback_performance.py', 'Test suite/pc/unit/test_focused_checks.py',
        'Test suite/pc/powershell_fixture.py', 'Test suite/pc/process_fixture.py',
        'Test suite/pc/unit/test_tester_launcher.py'], cwd=root).decode('utf-8').split('\0')
    entries = [(name, digest(root / name) if (root / name).is_file() else "deleted")
               for name in sorted(set(paths)) if name]
    if not entries:
        raise ValueError('AE-15 empty audio input closure')
    return hashlib.sha256(json.dumps(entries, separators=(',', ':')).encode()).hexdigest()


def managed_inventory(directory):
    tests = []
    for path in sorted(directory.rglob('*')):
        if path.suffix not in {'.kt', '.java'}:
            continue
        source = path.read_text(encoding='utf-8')
        for owner, methods in source_tests(path).items():
            tests.extend(owner + '#' + method for method in methods
                         if is_managed_audio_test(owner, method, source)
                         and not is_optional_acoustic_test(owner, method, source))
    if not tests or len(tests) != len(set(tests)):
        raise ValueError('AE-15 empty or duplicate managed audio inventory')
    return sorted(tests)


def native_inventory(manifest):
    tests = manifest['tests']
    names = [test['name'] for test in tests]
    if not names or len(names) != len(set(names)) or any(not test.get('command') for test in tests):
        raise ValueError('AE-15 empty, duplicate or unexecutable native inventory')
    return sorted(names)


def jvm_test_owners(root=ROOT):
    owners = {}
    for module, relative in (('app', 'Test suite/android/unit/java'),
                             ('audio-engine', 'Test suite/android/audio-engine/unit/java')):
        for path in sorted((root / relative).rglob('*')):
            if path.suffix not in {'.kt', '.java'}:
                continue
            for name, methods in source_tests(path).items():
                if methods:
                    if name in owners:
                        raise ValueError('AE-15 duplicate JVM compilation owner: ' + name)
                    owners[name] = module
    return owners


def validate_registry(registry, inventories):
    if registry.get('schemaVersion') != 1 or registry.get('profiles') != ['host', 'android-scripted', 'android-physical']:
        raise ValueError('AE-15 unsupported invariant registry')
    records = registry['invariants']
    if [item['id'] for item in records] != [f'AE-{index:02}' for index in range(1, 16)]:
        raise ValueError('AE-15 missing or duplicated invariant')
    for item in records:
        for field in ('statement', 'owner', 'boundary', 'tests', 'negativeControls', 'oracle', 'acceptance'):
            if not item.get(field):
                raise ValueError(f"{item['id']} missing {field}")
        for test in item['tests'] + item['negativeControls']:
            lane, name = test.split(':', 1)
            if name not in inventories.get(lane, []):
                raise ValueError(f"{item['id']} undiscovered test: {test}")
    if not records[5].get('liveness', {}).get('prerequisites') or not records[5]['liveness'].get('bound'):
        raise ValueError('AE-06 missing progress prerequisites/bound')
    coverage = registry.get('replayCoverage', [])
    if len(coverage) != len(REPLAY_SCENARIOS) or {item['id'] for item in coverage} != REPLAY_SCENARIOS:
        raise ValueError('AE-15 required replay scenario missing or duplicated')
    for item in coverage:
        orders = item['orders']
        if len(orders) < 2 or len({order['id'] for order in orders}) != len(orders):
            raise ValueError('AE-15 replay scenario lacks distinct competing orderings: ' + item['id'])
        for order in orders:
            if not order.get('boundaries') or not order.get('tests'):
                raise ValueError('AE-15 replay order lacks a production boundary or detector')
            for name in order['tests']:
                lane, test = name.split(':', 1)
                if test not in inventories.get(lane, []):
                    raise ValueError('AE-15 undiscovered replay detector: ' + name)


def complete_jvm_inventory(root=ROOT):
    inventory = runner_module('complete_jvm_inventory', SUITE / 'host/host_inventory.py')
    return inventory.discover((root / 'Test suite/android/unit/java', root / 'Test suite/android/audio-engine/unit/java'))


def complete_device_inventory(root=ROOT):
    inventory = runner_module('complete_device_inventory', SUITE / 'device/device_inventory.py')
    inventory.SOURCE_ROOT = root / 'Test suite/android/instrumentation/java'
    inventory.MANIFEST_PATH = root / 'Test suite/device/device-lanes.json'
    return sorted(row['id'] for row in inventory.build_accounting()['methods']
                  if not row.get('excluded') and row['lane'] != 'optional')


def make_plan(manifest, root=ROOT, device_profile=None):
    from audio_development_evidence import DEVELOPMENT_IDS
    device = managed_inventory(root / 'Test suite/android/instrumentation/java')
    registry = load_json(root / 'StemWise/contracts/audio/engine-invariants.json')
    physical = registry['candidateProfile']
    if (physical.get('schemaVersion') != 1 or not physical.get('workloads') or
            any(name not in device for item in physical['workloads']
                for name in [item['test'], *item.get('additionalTests', [])])):
        raise ValueError('AE-15 physical profile has undiscovered required workloads')
    profile = None
    if device_profile:
        device_profile = Path(device_profile).resolve()
        if not device_profile.is_relative_to(root.resolve()):
            raise ValueError('AE-15 device profile must be a project-owned input')
        declared = load_json(device_profile)
        purpose = declared.pop('purpose', None)
        if purpose not in (None, 'everyday', 'dedicated-qualification'):
            raise ValueError('AE-15 unsupported device purpose')
        profile = {'path': device_profile.relative_to(root).as_posix(), 'sha256': digest(device_profile),
                   'identity': declared, 'purpose': purpose}
    android = {'schemaVersion': 1, 'sourceSha256': source_identity(root),
            'gitRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
            'profile': 'android-physical', 'deviceProfile': profile,
            'required': {'device': device, 'development': list(DEVELOPMENT_IDS),
                         'physical-performance': [item['test'] for item in physical['workloads']]},
            'candidateProfile': physical}
    inventories = {
        'native': native_inventory(manifest),
        'jvm': sorted(managed_inventory(root / 'Test suite/android/unit/java') +
                      managed_inventory(root / 'Test suite/android/audio-engine/unit/java')),
        'host': host_inventory(),
        'boundary': list(BOUNDARY_IDS),
    }
    sanitizer = runner_module('audio_sanitizer_inventory', root / 'Test suite/native/run_sanitizers.py')
    for kind, targets in (('memory', sanitizer.TARGETS), ('thread', sanitizer.THREAD_TARGETS)):
        inventories['sanitizer-' + kind] = list(sanitizer.selected_tests(manifest, targets, kind))
    protocol = runner_module('audio_protocol_inventory', root / 'Test suite/native/formal/verify.py')
    inventories['protocol'] = [case[0] for case in protocol.CASES] + list(protocol.MUTATIONS)
    mutations = runner_module('audio_negative_inventory', root / 'Test suite/native/run_negative_controls.py')
    inventories['negative-controls'] = list(mutations.CASES)
    inventories['negative-controls-memory'] = ['premature-resource-release']
    inventories['negative-controls-managed'] = ['stale-route-recovery']
    inventories.update(android['required'])
    registry = json.loads((root / 'StemWise/contracts/audio/engine-invariants.json').read_text(encoding='utf-8'))
    validate_registry(registry, inventories)
    return {**android, 'required': inventories,
            'hostLanes': list(HOST_LANES), 'candidateLanes': list(CANDIDATE_LANES),
            'nativeDiscovery': manifest, 'jvmOwners': jvm_test_owners(root), 'invariants': registry['invariants'],
            'replayCoverage': registry['replayCoverage'],
            'observation': 'host checks cannot qualify Android output, decoder or physical performance'}


def self_test_modules():
    import test_audio_evidence
    import test_audio_development_evidence
    import test_audio_physical_evidence
    return (test_audio_evidence, test_audio_development_evidence, test_audio_physical_evidence,
            runner_module('test_negative_controls', SUITE / 'native/test_negative_controls.py'),
            runner_module('formal_test_runner', SUITE / 'native/formal/test_runner.py'),
            runner_module('test_playback_performance', SUITE / 'pc/unit/test_playback_performance.py'),
            runner_module('test_focused_checks', SUITE / 'pc/unit/test_focused_checks.py'))


def self_test_cases():
    cases = [case for module in self_test_modules()
             for group in unittest.defaultTestLoader.loadTestsFromModule(module) for case in group]
    launcher = runner_module('test_tester_launcher', SUITE / 'pc/unit/test_tester_launcher.py')
    cases.extend(unittest.defaultTestLoader.loadTestsFromName(
        'TesterLauncherPolicyTest.test_development_success_retains_only_junit_before_next_selection_and_feeds_consumer',
        launcher))
    return cases


def host_inventory():
    return sorted(case.id() for case in self_test_cases())


def run_self_tests(path):
    suite = unittest.TestSuite(self_test_cases())
    expected = host_inventory()
    class RecordedResult(unittest.TestResult):
        def __init__(self):
            super().__init__()
            self.started = []
            self.failed_parents = set()
            self.skipped_parents = set()

        def startTest(self, test):
            self.started.append(test.id())
            super().startTest(test)

        def addSubTest(self, test, subtest, error):
            if error is not None:
                self.failed_parents.add(test.id())
            super().addSubTest(test, subtest, error)

        def addSkip(self, test, reason):
            self.skipped_parents.add(self.started[-1])
            super().addSkip(test, reason)

    result = RecordedResult()
    suite.run(result)
    for _, traceback in result.failures + result.errors:
        print(traceback, file=sys.stderr)
    failures = {case.id() for case, _ in result.failures + result.errors} | result.failed_parents
    skipped = {case.id() for case, _ in result.skipped} | result.skipped_parents
    if result.testsRun != len(expected):
        raise ValueError('AE-15 self-test execution count mismatch')
    records = [(name, 'failed' if name in failures else 'skipped' if name in skipped else 'passed')
               for name in result.started]
    path.write_text(json.dumps(records) + '\n', encoding='utf-8')
    reconcile(expected, records)
    if not result.wasSuccessful():
        raise ValueError('AE-15 self-test execution failed')


def boundary_records(report):
    if report.get('schemaVersion') != 1 or report.get('restoredCompileExitCode') != 0:
        raise ValueError('AE-15 boundary normal compilation absent or failed')
    expected = {'app-private-owner-reference': ('Cannot access', 'NativePlaybackEngineController'),
                'engine-app-dependency': ('Unresolved reference', 'Song')}
    records = []
    for item in report['records']:
        diagnostic = '\n'.join(item['diagnostic'])
        if (item['id'] not in expected or not isinstance(item['exitCode'], int) or item['exitCode'] == 0 or
                any(text.lower() not in diagnostic.lower() for text in expected[item['id']]) or
                'AudioBoundaryProbe.kt' not in diagnostic):
            raise ValueError('AE-15 boundary did not reject the intended forbidden compiler reference')
        records.append((item['id'], 'passed'))
    reconcile(BOUNDARY_IDS, records)
    return records


def validate_current_plan(plan, root=ROOT):
    profile = plan.get('deviceProfile')
    current = make_plan(plan['nativeDiscovery'], root, root / profile['path'] if profile else None)
    if plan != current:
        raise ValueError('AE-15 stale plan, changed selection or input identity')
    validate_native_discovery(plan['nativeDiscovery'], root)


def native_artifacts(manifest, root=ROOT):
    return sorted({Path(test['command'][0]).resolve() for test in manifest['tests']
                   if Path(test['command'][0]).resolve().is_relative_to(root.resolve())})


def validate_native_discovery(manifest, root=ROOT):
    artifacts = native_artifacts(manifest, root)
    if not artifacts:
        raise ValueError('AE-15 native build artifacts absent')
    build = artifacts[0].parent
    cache = (build / 'CMakeCache.txt').read_text(encoding='utf-8')
    ctest = next(line.split('=', 1)[1] for line in cache.splitlines() if line.startswith('CMAKE_CTEST_COMMAND:'))
    actual = json.loads(subprocess.check_output([ctest, '--test-dir', str(build), '--show-only=json-v1'], timeout=30))
    selected = lambda value: sorted((item['name'], item.get('command')) for item in value['tests'])
    if selected(actual) != selected(manifest):
        raise ValueError('AE-15 native plan omits or changes a current CTest registration')


def protocol_records(report, required, source_sha256):
    if (report.get('schemaVersion') != 1 or report.get('lane') != 'protocol' or
            report.get('sourceSha256') != source_sha256 or report.get('required') != list(required) or
            report.get('sourceEndSha256') != source_sha256 or not report.get('tools')):
        raise ValueError('AE-15 protocol scope, toolchain or source identity mismatch')
    contract = runner_module('audio_protocol_contract', SUITE / 'native/formal/verify.py')
    expectations = {name: ('verified', None) for name, _, _ in contract.CASES}
    expectations.update({name: ('counterexample', mutation[3]) for name, mutation in contract.MUTATIONS.items()})
    records = []
    for case in report['cases']:
        expected = expectations.get(case['id'])
        if expected is None or (case.get('expected'), case.get('assertion')) != expected:
            raise ValueError('AE-15 protocol case contradicts its current detector: ' + case['id'])
        output = case['stdout'] + case['stderr']
        if expected[0] == 'verified':
            valid = (case['returnCode'] == 0 and 'VERIFICATION SUCCESSFUL' in output and
                     re.search(r'\*\* 0 of ([1-9][0-9]*) properties failed, \1 passed', output))
        elif expected[0] == 'counterexample':
            valid = (case['returnCode'] == contract.COUNTEREXAMPLE_EXIT_CODE and
                     contract.violates_assertion(output, expected[1]) and
                     re.search(r'\*\* [1-9][0-9]* of [1-9][0-9]* properties failed', output))
        else:
            valid = False
        records.append((case['id'], 'passed' if valid else 'failed'))
    reconcile(required, records)
    return records


def negative_records(report, required, source_sha256, memory=False):
    if (report.get('schemaVersion') != 1 or report.get('lane') != 'negative-controls' or
            report.get('sourceSha256') != source_sha256 or report.get('sourceEndSha256') != source_sha256 or
            report.get('required') != list(required) or report.get('sanitizers') != memory):
        raise ValueError('AE-15 negative-control scope or source identity mismatch')
    if memory and not report.get('instrumentation'):
        raise ValueError('AE-15 negative-control sanitizer instrumentation absent')
    contract = runner_module('audio_negative_contract', SUITE / 'native/run_negative_controls.py')
    assertions = {name: mutation[-1] for name, mutation in contract.CASES.items()}
    records = []
    for case in report['cases']:
        assertion = assertions.get(case['id'])
        if assertion is None or case.get('assertion') != assertion:
            raise ValueError('AE-15 negative control contradicts its current detector: ' + case['id'])
        actual = subprocess.CompletedProcess([], case['returnCode'], case['stdout'], case['stderr'])
        valid = (case['baseline']['returnCode'] == 0 and
                 contract.detected(actual, assertion, contract.CASES[case['id']][3]) and
                 re.fullmatch('[0-9a-f]{64}', case['artifactSha256']))
        records.append((case['id'], 'passed' if valid else 'failed'))
    reconcile(required, records)
    return records


def validate_ci_jobs(jobs):
    if set(jobs) != set(SOFTWARE_JOBS) or any(value != 'success' for value in jobs.values()):
        raise ValueError('AE-15 required CI job missing, failed, cancelled or unexpectedly skipped: ' + str(jobs))


def project_jvm_records(plan, records, scope='audio', root=ROOT):
    expected = complete_jvm_inventory(root) if scope == 'all' else plan['required']['jvm']
    if scope not in ('audio', 'all'):
        raise ValueError('AE-15 unsupported JVM execution scope')
    reconcile(expected, records)
    required = set(plan['required']['jvm'])
    return [row for row in records if row[0] in required]


def host_records(plan, native_report, jvm_results, host_report, boundary_report, jvm_scope='audio'):
    records = {'native': xml_records(native_report, 'native'), 'jvm': [],
               'host': load_json(host_report), 'boundary': boundary_records(load_json(boundary_report))}
    for directory in jvm_results:
        for report in sorted(directory.glob('TEST-*.xml')):
            records['jvm'].extend(xml_records(report, 'jvm'))
    verify(plan, {**records, 'jvm': project_jvm_records(plan, records['jvm'], jvm_scope)}, source_identity(), HOST_LANES)
    return records


def file_binding(path, root=ROOT):
    path = Path(path).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError('AE-15 execution evidence escaped the project')
    return {'path': path.relative_to(root).as_posix(), 'sha256': digest(path)}


def checked_file(entry, root=ROOT):
    path = (root / entry['path']).resolve()
    if not path.is_relative_to(root.resolve()) or digest(path) != entry['sha256']:
        raise ValueError('AE-15 missing, changed or wrong execution evidence')
    return path


def write_host_receipt(path, plan, records, native_report, jvm_results, host_report, boundary_report, jvm_scope='audio'):
    evidence = {'native': [file_binding(native_report)], 'jvm': [],
                'host': [file_binding(host_report)], 'boundary': [file_binding(boundary_report)]}
    for directory in jvm_results:
        evidence['jvm'].extend(file_binding(report) for report in sorted(directory.glob('TEST-*.xml')))
    artifacts = native_artifacts(plan['nativeDiscovery'])
    build = artifacts[0].parent
    compiler_records = sorted((build / 'CMakeFiles').glob('*/CMakeCXXCompiler.cmake'))
    if not compiler_records:
        raise ValueError('AE-15 actual native compiler metadata absent')
    receipt = {'schemaVersion': 1, 'result': 'passed', 'observation': 'host-only',
               'sourceSha256': plan['sourceSha256'], 'gitRevision': plan['gitRevision'],
               'required': {lane: plan['required'][lane] for lane in HOST_LANES},
               'executed': records, 'evidence': evidence, 'jvmScope': jvm_scope,
               'toolchain': {'python': {'path': sys.executable, 'version': sys.version, 'sha256': digest(sys.executable)},
                             'externalTestTools': [{'path': name, 'sha256': digest(name)} for name in sorted({
                                 test['command'][0] for test in plan['nativeDiscovery']['tests']
                                 if not Path(test['command'][0]).resolve().is_relative_to(ROOT.resolve())})],
                             'native': [file_binding(build / 'CMakeCache.txt'),
                                        file_binding(build / 'CTestTestfile.cmake'),
                                        *[file_binding(record) for record in compiler_records]]},
               'artifacts': [file_binding(artifact) for artifact in artifacts]}
    path.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')


def validate_host_receipt(report, plan, root=ROOT):
    if (report.get('schemaVersion') != 1 or report.get('sourceSha256') != plan['sourceSha256'] or
            report.get('gitRevision') != plan['gitRevision'] or not report.get('artifacts') or
            not report.get('toolchain', {}).get('python') or not report.get('toolchain', {}).get('native') or
            report.get('required') != {lane: plan['required'][lane] for lane in HOST_LANES}):
        raise ValueError('AE-15 host receipt has wrong candidate, artifacts or required execution')
    expected_artifacts = {path.relative_to(root.resolve()).as_posix() for path in native_artifacts(plan['nativeDiscovery'], root)}
    if (len(report['artifacts']) != len(expected_artifacts) or
            {entry['path'] for entry in report['artifacts']} != expected_artifacts):
        raise ValueError('AE-15 host receipt omits a required native executable identity')
    records = {}
    for lane in HOST_LANES:
        records[lane] = []
        for entry in report['evidence'][lane]:
            path = checked_file(entry, root)
            records[lane].extend(boundary_records(load_json(path)) if lane == 'boundary' else
                                 load_json(path) if lane == 'host' else xml_records(path, lane))
    for entry in report['artifacts']:
        checked_file(entry, root)
    for entry in report['toolchain']['native']:
        checked_file(entry, root)
    if {lane: [list(item) for item in rows] for lane, rows in records.items()} != report['executed']:
        raise ValueError('AE-15 receipt differs from underlying executions')
    records['jvm'] = project_jvm_records(plan, records['jvm'], report['jvmScope'], root)
    return verify(plan, records, source_identity(root), HOST_LANES)


def device_records(path, plan, root=ROOT):
    report = load_json(path)
    profile = plan.get('deviceProfile')
    if not profile or report.get('status') != 'PASS' or report.get('deviceProfile') != profile['identity']:
        raise ValueError('AE-15 Android execution has failed, absent or wrong actual device identity')
    if (report.get('audioSourceSha256') != plan['sourceSha256'] or
            report.get('audioSourceEndSha256') != plan['sourceSha256']):
        raise ValueError('AE-15 Android execution has absent, stale or changed audio inputs')
    configuration = plan['candidateProfile']['buildConfiguration']
    if report.get('audioBuildConfiguration') != configuration:
        raise ValueError('AE-15 Android cases did not execute the declared minified Development configuration')
    build_inputs = report.get('audioBuildEvidence', [])
    if len(build_inputs) != 3 or {item['kind'] for item in build_inputs} != {'application', 'androidTest', 'mapping'}:
        raise ValueError('AE-15 Android minified metadata or R8 evidence is absent')
    for item in build_inputs:
        build_file = checked_file(item, root)
        if item['kind'] == 'mapping':
            with build_file.open(encoding='utf-8') as stream:
                if not stream.readline().startswith('# compiler: R8'):
                    raise ValueError('AE-15 Android candidate has no observed R8 output')
        else:
            metadata = load_json(build_file)
            expected_variant = 'development' if item['kind'] == 'application' else 'developmentAndroidTest'
            expected_package = configuration['identity'] + ('.test' if item['kind'] == 'androidTest' else '')
            if metadata.get('variantName') != expected_variant or metadata.get('applicationId') != expected_package:
                raise ValueError('AE-15 Android metadata is not the exact Development variant')
    bindings = report.get('audioArtifacts', [])
    if len(bindings) != 2 or {item['kind'] for item in bindings} != {'application', 'androidTest'}:
        raise ValueError('AE-15 Android execution lacks its exact application and driver APKs')
    artifacts = {item['kind']: digest(checked_file(item, root)) for item in bindings}
    actual_artifacts = {item['kind']: item['sha256'].lower() for item in report['artifacts']
                        if item['role'] == 'qualification'}
    if artifacts != actual_artifacts:
        raise ValueError('AE-15 Android retained APKs differ from the executed frozen pair')
    files = [item for item in path.parent.rglob('*') if item.is_file() and
             (item.name in {'selection.json', 'direct.json', 'fatal-retirement.json', 'fatal-marker.txt'} or
              item.name == 'instrumentation.txt' and re.fullmatch('direct-[0-9]+', item.parent.name) or
              item.match('TEST-*.xml') and 'results' in item.relative_to(path.parent).parts)]
    executions = report.get('audioExecutions', [])
    if (len(executions) != len(files) or
            {item['path'] for item in executions} != {item.resolve().relative_to(root.resolve()).as_posix() for item in files}):
        raise ValueError('AE-15 Android underlying selection/JUnit evidence is missing or unexpected')
    for item in executions:
        checked_file(item, root)
    records = []
    selections = sorted(path.parent.rglob('selection.json'))
    if not selections:
        raise ValueError('AE-15 Android structured selections absent')
    for selection in selections:
        actual = load_json(selection)
        if actual.get('status') != 'PASS' or actual.get('failedMethods') or actual.get('skippedMethods'):
            raise ValueError('AE-15 Android failed/skipped selection')
        if actual.get('retry'):
            raise ValueError('AE-15 Android prior failure needs explicit qualification classification')
        selection_artifacts = {item['kind']: item['sha256'].lower() for item in actual['artifacts']
                               if item['role'] == 'qualification'}
        if selection_artifacts != artifacts:
            raise ValueError('AE-15 Android selection consumed a different exact APK pair')
        arguments = actual.get('gradleArguments', [])
        for required_argument in ('-Pandroid.testInstrumentationRunnerArguments.stemwiseExpectedApplicationSha256=' + artifacts['application'],
                '-Pandroid.testInstrumentationRunnerArguments.stemwiseExpectedTestSha256=' + artifacts['androidTest']):
            if required_argument.lower() not in {value.lower() for value in arguments}:
                raise ValueError('AE-15 Android selection lacks actual installed app/driver hash guards')
        xml_files = sorted((selection.parent / 'results').rglob('TEST-*.xml'))
        if not xml_files:
            raise ValueError('AE-15 Android selection lacks its actual JUnit execution evidence')
        for xml in xml_files:
            records.extend(xml_records(xml, 'device'))
    direct = sorted(path.parent.rglob('direct.json'))
    if direct:
        manifest = load_json(root / 'Test suite/device/device-lanes.json')
        fatal_ids = {manifest['fatalTwoProcess']['phaseA'], manifest['fatalTwoProcess']['phaseB']}
        fatal = [item for item in direct if load_json(item)['selector'] in fatal_ids]
        if fatal:
            records.extend(fatal_retirement_records(path, report, fatal, artifacts, root))
        from audio_development_evidence import instrumentation_records
        for item in direct:
            actual = load_json(item)
            if actual['selector'] in fatal_ids:
                continue
            pair = {entry['kind']: entry['sha256'].lower() for entry in actual['artifacts'] if entry['role'] == 'qualification'}
            arguments = actual['arguments']
            if (actual['exitCode'] != 0 or actual['component'] != configuration['identity'] +
                    '.test/app.stemwise.qualification.DevelopmentStandardInstrumentationRunner' or pair != artifacts or
                    arguments.get('stemwiseExpectedApplicationSha256', '').lower() != artifacts['application'] or
                    arguments.get('stemwiseExpectedTestSha256', '').lower() != artifacts['androidTest']):
                raise ValueError('AE-15 direct test lacks its successful exact installed APK boundary')
            raw = checked_file(actual['output'], root).read_text(encoding='utf-8-sig')
            records.extend(instrumentation_records(raw, [actual['selector']]))
    return project_device_records(path, report, plan, records, direct, root)


def project_device_records(path, report, plan, records, direct, root=ROOT):
    """Admit a full Device run only after checking every current nonoptional method."""
    if report.get('mode') == 'Device' and {item['lane'] for item in report.get('lanes', [])} == {
            'ordinary', 'explicit-suite', 'lifecycle-phase', 'fatal-two-process', 'native-process-fault'}:
        lanes = report['lanes']
        expected = complete_device_inventory(root)
        manifest = load_json(root / 'Test suite/device/device-lanes.json')
        required_direct = {manifest['fatalTwoProcess']['phaseA'], manifest['fatalTwoProcess']['phaseB']}
        required_direct.update(name for workflow in manifest['lifecycleWorkflows'] for name in workflow['phases'])
        if sorted(load_json(item)['selector'] for item in direct) != sorted(required_direct):
            raise ValueError('AE-15 complete Android execution lacks its exact direct lifecycle/retirement phases')
        if len(lanes) != 5 or any(item['status'] != 'PASS' for item in lanes):
            raise ValueError('AE-15 complete Android execution has a failed or duplicate lane')
        for name in ('deviceInventory', 'deviceExecutionAccounting'):
            binding = report.get(name)
            if not binding:
                raise ValueError('AE-15 complete Android execution lacks actual inventory/accounting')
            retained = {**binding, 'path': (path.parent / binding['path']).resolve().relative_to(root.resolve()).as_posix()}
            actual = load_json(checked_file(retained, root))
            if name == 'deviceInventory':
                observed = sorted(row['id'] for row in actual['methods'] if not row.get('excluded') and row['lane'] != 'optional')
            else:
                if actual.get('status') != 'PASS' or actual.get('skippedMethods') or actual.get('missingMethods') or actual.get('unexpectedMethods'):
                    raise ValueError('AE-15 complete Android accounting did not pass')
                observed = sorted(actual['passingMethods'])
                if sorted(actual['expectedMethods']) != expected:
                    raise ValueError('AE-15 complete Android accounting changed its full selection')
            if observed != expected:
                raise ValueError('AE-15 complete Android inventory is missing or changes current methods')
        reconcile(expected, records)
        records = [row for row in records if row[0] in set(plan['required']['device'])]
    reconcile(plan['required']['device'], records)
    return records


def fatal_retirement_records(run_path, run, paths, artifacts, root=ROOT):
    from audio_development_evidence import instrumentation_records
    manifest = load_json(ROOT / 'Test suite/device/device-lanes.json')['fatalTwoProcess']
    phase_a, phase_b = manifest['phaseA'], manifest['phaseB']
    actual = [load_json(path) for path in paths]
    if len(actual) != 2 or {item['selector'] for item in actual} != {phase_a, phase_b}:
        raise ValueError('AE-15 missing, unexpected or duplicate direct retirement phase')
    proof = load_json(run_path.parent / 'fatal-retirement.json')
    run_id = re.sub('[^A-Za-z0-9._-]', '_', run['runId'])
    if proof.get('schemaVersion') != 1 or proof.get('runId') != run_id:
        raise ValueError('AE-15 retirement proof belongs to a different exact operation')
    marker = checked_file(proof['marker'], root).read_text(encoding='utf-8').splitlines()
    if len(marker) != 2 or marker[0] != run_id or not marker[1].isdecimal() or int(marker[1]) <= 0:
        raise ValueError('AE-15 missing or wrong current process marker')
    for name in ('markerBefore', 'processAfter', 'markerAfter'):
        observation = proof[name]
        if observation['exitCode'] != 1 or observation['stdout'].strip() or observation['stderr'].strip():
            raise ValueError('AE-15 process retirement/marker cleanup is absent or inspection failed')
    for item in actual:
        pair = {entry['kind']: entry['sha256'].lower() for entry in item['artifacts'] if entry['role'] == 'qualification'}
        arguments = item['arguments']
        if (item.get('schemaVersion') != 1 or
                item.get('component') != 'app.stemwise.development.test/app.stemwise.qualification.DevelopmentStandardInstrumentationRunner' or
                pair != artifacts or
                arguments.get('stemwiseExpectedApplicationSha256', '').lower() != artifacts['application'] or
                arguments.get('stemwiseExpectedTestSha256', '').lower() != artifacts['androidTest'] or
                arguments.get('stemwiseFatalRetirementQualification') != 'true' or
                arguments.get('stemwiseDisposableTarget') != 'true' or
                arguments.get('stemwiseFatalRetirementRunId') != run_id):
            raise ValueError('AE-15 direct retirement phase used a different APK or operation guard')
        text = checked_file(item['output'], root).read_text(encoding='utf-8') + '\n' + item['stderr']
        retiring = item['selector'] == phase_a
        if retiring and 'FATAL_RECENTS_PROCESS_RETIREMENT_DID_NOT_OCCUR' in text:
            raise ValueError('AE-15 expected process retirement returned its failure sentinel')
        if item['exitCode'] not in ((0, 1) if retiring else (0,)):
            raise ValueError('AE-15 direct instrumentation infrastructure failed')
        observed = instrumentation_records(text, [item['selector']], retiring=retiring)
        if observed != [(item['selector'], 'interrupted' if retiring else 'passed')]:
            raise ValueError('AE-15 direct retirement phase lacks its actual expected disposition')
    return [(phase_a, 'passed'), (phase_b, 'passed')]


def candidate_result(plan, paths, root=ROOT):
    """Absence blocks qualification; failed or mismatched actual evidence fails it."""
    validate_current_plan(plan, root)
    executed, failures, missing, bindings = {}, [], [], {}
    for lane in EVIDENCE_LANES:
        path = paths.get(lane)
        lane_paths = path if isinstance(path, list) else [path]
        if not lane_paths or any(item is None or not Path(item).is_file() for item in lane_paths):
            missing.append(lane)
            continue
        path = Path(lane_paths[0])
        try:
            report = load_json(path)
            if lane == 'host':
                executed.update(validate_host_receipt(report, plan, root))
            elif lane.startswith('sanitizer-'):
                if len(lane_paths) == 1:
                    if report.get('shard', 'all') != 'all':
                        raise ValueError('AE-15 a partial sanitizer shard cannot establish the complete lane')
                    executed[lane] = validate_native_lane(report, lane, plan['sourceSha256'], plan['required'][lane], root)
                else:
                    executed[lane] = validate_native_shards([load_json(item) for item in lane_paths], lane,
                        plan['sourceSha256'], plan['required'][lane], root)
            elif lane == 'protocol':
                executed[lane] = [name for name, _ in protocol_records(report, plan['required'][lane], plan['sourceSha256'])]
            elif lane == 'negative-controls-managed':
                from audio_development_evidence import validate_managed_negative
                executed[lane] = validate_managed_negative(report, plan, root)
            elif lane.startswith('negative-controls'):
                executed[lane] = [name for name, _ in negative_records(report, plan['required'][lane], plan['sourceSha256'], lane.endswith('-memory'))]
            elif lane == 'device':
                executed[lane] = [name for name, _ in device_records(path, plan, root)]
            elif lane == 'physical-performance':
                from audio_physical_evidence import validate_physical
                executed[lane] = validate_physical(report, plan, root)
            else:
                # Development qualification binds actual final-artifact executions and correspondence.
                from audio_development_evidence import validate_development
                executed[lane] = validate_development(report, plan, root)
            bindings[lane] = (file_binding(path, root) if len(lane_paths) == 1 else
                              [file_binding(item, root) for item in lane_paths])
        except (ValueError, KeyError, OSError, ET.ParseError, ImportError) as exc:
            failures.append(lane + ': ' + str(exc))
    if all(lane in bindings for lane in ('device', 'physical-performance', 'development')):
        device, physical, development = (load_json(paths[lane]) for lane in ('device', 'physical-performance', 'development'))
        device_pair = {item['kind']: item['sha256'].lower() for item in device['audioArtifacts']}
        physical_pair = {'application': physical['auditApk']['sha256'].lower(), 'androidTest': physical['auditTestApk']['sha256'].lower()}
        development_pair = {'application': development['apk']['sha256'].lower(), 'androidTest': development['testApk']['sha256'].lower()}
        if device_pair != physical_pair or device_pair != development_pair:
            failures.append('AE-15 functional, physical and JNI/public-session gates executed different candidate APK pairs')
    covered = {lane + ':' + name for lane, names in executed.items() for name in names}
    invariants = plan.get('invariants', [])
    return {'schemaVersion': 1, 'result': 'failed' if failures else 'blocked' if missing else 'passed',
            'sourceSha256': plan['sourceSha256'], 'gitRevision': plan['gitRevision'],
            'profile': plan['profile'], 'requiredLanes': list(CANDIDATE_LANES),
            'executed': executed, 'missingLanes': missing, 'failures': failures, 'evidence': bindings,
            'invariantsExercised': [item['id'] for item in invariants
                                    if set(item['tests'] + item['negativeControls']) <= covered],
            'negativeControlsExercised': sorted({name for item in invariants
                                                for name in item['negativeControls']} & covered),
            'replayOrdersExercised': [item['id'] + '/' + order['id'] for item in plan.get('replayCoverage', [])
                                      for order in item['orders'] if set(order['tests']) <= covered]}


def validate_promotion(receipt, plan, apk, root=ROOT):
    if receipt.get('result') != 'passed' or receipt.get('sourceSha256') != source_identity(root):
        raise ValueError('AE-15 absent, blocked, failed or stale audio qualification cannot promote a candidate')
    paths = {lane: ([checked_file(item, root) for item in entry] if isinstance(entry, list) else checked_file(entry, root))
             for lane, entry in receipt['evidence'].items()}
    actual = candidate_result(plan, paths, root)
    if actual != receipt:
        raise ValueError('AE-15 qualification aggregate changed or underlying executions unavailable')
    development = load_json(paths['development'])
    if checked_file(development['apk'], root) != Path(apk).resolve() or digest(apk) != development['installedSha256']:
        raise ValueError('AE-15 qualification does not cover this exact installed candidate APK')


def xml_records(path, lane):
    text = Path(path).read_text(encoding='utf-8-sig')
    if '<!DOCTYPE' in text or '<!ENTITY' in text:
        raise ValueError('AE-15 unexpected XML declarations')
    root = ET.fromstring(text)
    cases = []
    for case in root.iter('testcase'):
        name = case.attrib['name'].removesuffix('()')
        if lane != 'native':
            name = case.attrib['classname'] + '#' + name
        status = 'passed'
        if case.find('failure') is not None or case.find('error') is not None:
            status = 'failed'
        elif case.find('skipped') is not None or case.get('status') in {'notrun', 'disabled'}:
            status = 'skipped'
        cases.append((name, status))
    if not cases:
        raise ValueError('AE-15 empty execution record: ' + str(path))
    return cases


def reconcile(required, records):
    if not required or not records:
        raise ValueError('AE-15 required execution absent or empty')
    counts = Counter(name for name, _ in records)
    missing = set(required) - counts.keys()
    unexpected = counts.keys() - set(required)
    failed = [name for name, status in records if status != 'passed']
    duplicate = [name for name, count in counts.items() if count != 1]
    if missing or unexpected or failed or duplicate:
        raise ValueError(f'AE-15 missing={sorted(missing)} unexpected={sorted(unexpected)} '
                         f'failed/skipped={failed} duplicate={duplicate}')
    return sorted(counts)


def native_lane_report(path, lane, source_sha256, required, xml, artifacts, tools, *, shard='all'):
    """Retain actual CTest records and exact binaries, rather than an editable pass flag."""
    records = xml_records(xml, 'native')
    report = {'schemaVersion': 1, 'lane': lane, 'sourceSha256': source_sha256, 'shard': shard,
              'required': list(required), 'records': records, 'tools': tools,
              'xml': {'path': xml.resolve().relative_to(ROOT).as_posix(), 'sha256': digest(xml)},
              'artifacts': {item.resolve().relative_to(ROOT).as_posix(): digest(item)
                            for item in artifacts}}
    path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    validate_native_lane(report, lane, source_identity(), required)


def validate_native_lane(report, lane, source_sha256, required, root=ROOT):
    sanitizer = runner_module('audio_sanitizer_artifact_inventory', ROOT / 'Test suite/native/run_sanitizers.py')
    shard = report.get('shard', 'all')
    expected = sanitizer.partition_tests(required, shard)
    if (report.get('schemaVersion') != 1 or report.get('lane') != lane or
            report.get('sourceSha256') != source_sha256 or report.get('required') != list(expected)):
        raise ValueError('AE-15 wrong native lane, input identity or selection')
    if not report.get('tools') or not report.get('artifacts'):
        raise ValueError('AE-15 missing toolchain or binary identity')
    targets = sanitizer.targets_for(lane.removeprefix('sanitizer-'), shard)
    if ({Path(name).name for name in report['artifacts']} != set(targets) or
            len({Path(name).parent for name in report['artifacts']}) != 1):
        raise ValueError('AE-15 sanitizer receipt omits or changes a required instrumented executable')
    def checked_file(entry):
        target = (root / entry['path']).resolve()
        if not target.is_relative_to(root.resolve()) or digest(target) != entry['sha256']:
            raise ValueError('AE-15 changed or wrong execution artifact')
        return target
    xml = checked_file(report['xml'])
    for name, sha256 in report['artifacts'].items():
        artifact = checked_file({'path': name, 'sha256': sha256})
        sanitizer.verify_instrumentation(artifact, lane.removeprefix('sanitizer-'))
    actual = xml_records(xml, 'native')
    if [list(item) for item in actual] != [list(item) for item in report['records']]:
        raise ValueError('AE-15 record differs from actual CTest execution')
    return reconcile(expected, actual)


def validate_native_shards(reports, lane, source_sha256, required, root=ROOT):
    sanitizer = runner_module('audio_sanitizer_shards', ROOT / 'Test suite/native/run_sanitizers.py')
    if lane != 'sanitizer-memory' or sorted(report.get('shard', '') for report in reports) != sorted(sanitizer.MEMORY_SHARDS):
        raise ValueError('AE-15 missing, duplicate or unexpected sanitizer shard')
    records = [(name, 'passed') for report in reports
               for name in validate_native_lane(report, lane, source_sha256, required, root)]
    return reconcile(required, records)


def verify(plan, records, source_sha256, lanes=None):
    if plan.get('schemaVersion') != 1 or plan.get('sourceSha256') != source_sha256:
        raise ValueError('AE-15 stale or mismatched candidate input identity')
    required_lanes = set(plan['required'] if lanes is None else lanes)
    if not required_lanes or set(records) != required_lanes:
        raise ValueError('AE-15 missing or unexpected required aggregate lane')
    return {lane: reconcile(plan['required'][lane], actual) for lane, actual in records.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    plan_parser = sub.add_parser('plan')
    plan_parser.add_argument('--native-inventory', type=Path, required=True)
    plan_parser.add_argument('--output', type=Path, required=True)
    plan_parser.add_argument('--device-profile', type=Path)
    check = sub.add_parser('check')
    check.add_argument('--plan', type=Path, required=True)
    check.add_argument('--native-report', type=Path, required=True)
    check.add_argument('--jvm-results', type=Path, nargs='+', required=True)
    check.add_argument('--host-report', type=Path, required=True)
    check.add_argument('--boundary-report', type=Path, required=True)
    check.add_argument('--jvm-scope', choices=('audio', 'all'), default='audio')
    check.add_argument('--output', type=Path, required=True)
    self_test = sub.add_parser('self-test')
    self_test.add_argument('--output', type=Path, required=True)
    for name in ('candidate', 'ci'):
        candidate = sub.add_parser(name)
        candidate.add_argument('--plan', type=Path, required=True)
        candidate.add_argument('--output', type=Path, required=True)
        for lane in EVIDENCE_LANES:
            candidate.add_argument('--' + lane, type=Path, **({'nargs': '+'} if lane == 'sanitizer-memory' else {}))
        if name == 'ci':
            candidate.add_argument('--jobs', required=True, help='Actual GitHub needs job result JSON')
    promotion = sub.add_parser('promote')
    promotion.add_argument('--plan', type=Path, required=True)
    promotion.add_argument('--receipt', type=Path, required=True)
    promotion.add_argument('--apk', type=Path, required=True)
    sub.add_parser('jvm-owners')
    sub.add_parser('identity')
    args = parser.parse_args()
    if args.operation == 'plan':
        plan = make_plan(load_json(args.native_inventory), device_profile=args.device_profile)
        args.output.write_text(json.dumps(plan, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'required': {lane: len(ids) for lane, ids in plan['required'].items()},
                          'sourceSha256': plan['sourceSha256']}))
    elif args.operation == 'self-test':
        run_self_tests(args.output)
        print('AE-15 execution-evidence negative controls passed')
    elif args.operation == 'jvm-owners':
        print(json.dumps(jvm_test_owners(), sort_keys=True))
    elif args.operation == 'identity':
        print(source_identity())
    elif args.operation in ('candidate', 'ci'):
        paths = {lane: getattr(args, lane.replace('-', '_')) for lane in EVIDENCE_LANES}
        result = candidate_result(load_json(args.plan), paths)
        if args.operation == 'ci':
            try:
                validate_ci_jobs(json.loads(args.jobs))
            except ValueError as exc:
                result['failures'].append(str(exc))
                result['result'] = 'failed'
            missing_software = set(result['missingLanes']) - {'device', 'physical-performance', 'development'}
            result['softwareResult'] = 'failed' if result['failures'] or missing_software else 'passed'
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result))
        if (result['softwareResult'] if args.operation == 'ci' else result['result']) != 'passed':
            raise SystemExit(1)
    elif args.operation == 'promote':
        validate_promotion(load_json(args.receipt), load_json(args.plan), args.apk)
        print('Exact current audio candidate qualification and installed artifact evidence verified')
    else:
        plan = load_json(args.plan)
        validate_current_plan(plan)
        records = host_records(plan, args.native_report, args.jvm_results, args.host_report, args.boundary_report, args.jvm_scope)
        write_host_receipt(args.output, plan, records, args.native_report, args.jvm_results, args.host_report, args.boundary_report, args.jvm_scope)
        print(json.dumps({'executed': {lane: len(ids) for lane, ids in records.items()},
                          'observation': 'host-only; not candidate qualification'}))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, ET.ParseError) as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
