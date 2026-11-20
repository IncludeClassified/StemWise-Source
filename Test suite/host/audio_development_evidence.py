"""Read actual final-artifact instrumentation and consequential managed compiler-run results."""
import argparse
import hashlib
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

import audio_evidence as evidence

DEVELOPMENT_IDS = (
    'app.stemwise.playback.engine.MinifiedAudioJniInstrumentationTest#'
    'wavPackReadInvokesKeptJniAndPreservesIndependentSampleBits',
    'app.stemwise.playback.engine.MinifiedAudioJniInstrumentationTest#'
    'publicSessionRunsDecoderJniOutputPauseSeekResumeAndStop',
)
ROUTE_ID = ('app.stemwise.playback.engine.NativePlaybackControllerLifecycleTest#'
            'routeChangeReportedAfterTaskRemovalIsRetainedUntilTheNextOwner')
ROUTE_ASSERTION = 'A stopped player should defer the physical reopen until it has a new owner'


def validate_managed_negative(report, plan, root=evidence.ROOT):
    if (report.get('schemaVersion') != 1 or report.get('sourceSha256') != plan['sourceSha256'] or
            report.get('required') != ['stale-route-recovery']):
        raise ValueError('AE-12 wrong managed mutation identity or selection')
    rows = report['records']
    if [row['phase'] for row in rows] != ['baseline', 'mutant', 'restored']:
        raise ValueError('AE-12 missing baseline, mutation or restoration execution')
    for row in rows:
        path = evidence.checked_file(row['xml'], root)
        records = evidence.xml_records(path, 'jvm')
        if row['phase'] == 'mutant':
            tree = ET.parse(path)
            failures = list(tree.getroot().iter('failure'))
            primary = 'java.lang.AssertionError: ' + ROUTE_ASSERTION
            if (row['exitCode'] != 1 or records != [(ROUTE_ID, 'failed')] or len(failures) != 1 or
                    failures[0].get('type') != 'java.lang.AssertionError' or
                    failures[0].get('message') != primary or
                    (failures[0].text or '').splitlines()[:1] != [primary]):
                raise ValueError('AE-12 mutation did not fail for stopped-owner recovery')
        elif row['exitCode'] != 0 or records != [(ROUTE_ID, 'passed')]:
            raise ValueError('AE-12 original/restored production did not pass its runtime regression')
    return ['stale-route-recovery']


def instrumentation_records(text, required=DEVELOPMENT_IDS, retiring=False):
    """AndroidJUnitRunner's native status protocol, including exact start/finish pairing."""
    if ('INSTRUMENTATION_FAILED' in text and not retiring) or re.search(r'^FAILURES!!!\s*$', text, re.M):
        raise ValueError('AE-15 instrumentation reports a failure')
    if not retiring and len(re.findall(r'^INSTRUMENTATION_CODE: -1\s*$', text, re.M)) != 1:
        raise ValueError('AE-15 instrumentation completion absent')
    active, records, fields = set(), [], {}
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line.removeprefix('INSTRUMENTATION_STATUS: ').partition('=')
            fields[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.removeprefix('INSTRUMENTATION_STATUS_CODE: '))
            if 'class' in fields and 'test' in fields:
                name = fields['class'] + '#' + fields['test']
                if code == 1:
                    if name in active or any(item[0] == name for item in records):
                        raise ValueError('AE-15 duplicate instrumentation start')
                    active.add(name)
                else:
                    if name not in active:
                        raise ValueError('AE-15 instrumentation finish has no matching start')
                    active.remove(name)
                    records.append((name, 'passed' if code == 0 else 'failed'))
            elif code not in (1, 2):
                raise ValueError('AE-15 instrumentation failure without a test identity')
            fields = {}
    if retiring:
        if records or active != set(required):
            raise ValueError('AE-15 expected retiring instrumentation did not start its exact test or returned normally')
        return [(name, 'interrupted') for name in sorted(active)]
    if active:
        raise ValueError('AE-15 unfinished instrumentation tests')
    evidence.reconcile(required, records)
    return records


def validate_development(report, plan, root=evidence.ROOT):
    if (report.get('schemaVersion') != 1 or report.get('sourceSha256') != plan['sourceSha256'] or
            report.get('deviceProfile') != plan['deviceProfile']['identity'] or report.get('abi') != 'arm64-v8a'):
        raise ValueError('AE-15 wrong minified candidate input, ABI or actual device')
    apk = evidence.checked_file(report['apk'], root)
    test_apk = evidence.checked_file(report['testApk'], root)
    raw = evidence.checked_file(report['instrumentation'], root)
    installed = evidence.checked_file(report['installedReadback'], root).read_text(encoding='utf-8-sig')
    if (report.get('installedSha256') != evidence.digest(apk) or
            not re.match('^' + re.escape(evidence.digest(apk)) + r'\s+\S+/base\.apk\s*$', installed.strip())):
        raise ValueError('AE-15 installed APK readback does not match this exact artifact')
    driver_readback = evidence.checked_file(report['installedDriverReadback'], root).read_text(encoding='utf-8-sig')
    if (report.get('installedTestSha256') != evidence.digest(test_apk) or
            not re.match('^' + re.escape(evidence.digest(test_apk)) + r'\s+\S+/base\.apk\s*$', driver_readback.strip())):
        raise ValueError('AE-15 installed driver readback does not match this exact artifact')
    for path in (apk, test_apk):
        with zipfile.ZipFile(path) as archive:
            if len(archive.namelist()) != len(set(archive.namelist())):
                raise ValueError('AE-14 duplicate APK entries')
            libraries = [name for name in archive.namelist() if name.endswith('/libstemwise_audio_output.so')]
            expected = ['lib/arm64-v8a/libstemwise_audio_output.so'] if path == apk else []
            if libraries != expected:
                raise ValueError('AE-14 audio JNI must have exactly one application package owner')
            if path == apk:
                if hashlib.sha256(archive.read(libraries[0])).hexdigest() != report['nativeSha256']:
                    raise ValueError('AE-15 wrong embedded native library')
    mapping = evidence.checked_file(report['mapping'], root).read_text(encoding='utf-8')
    support = evidence.checked_file(report['instrumentationSupport'], root)
    if support != root / 'Test suite/android/development/instrumentation-support.pro':
        raise ValueError('AE-15 undeclared minified instrumentation support configuration')
    if not mapping.startswith('# compiler: R8') or report.get('buildConfiguration') != {
            'buildType': 'development', 'debuggable': True, 'minifyEnabled': True, 'shrinkResources': True,
            'identity': 'app.stemwise.development', 'signing': 'disposable',
            'auditHelpers': True, 'nativeAuditOptimized': True,
            'nativeCacheIoProfiling': True, 'externalDriverRetention': True}:
        raise ValueError('AE-15 minified Development configuration is not established')
    classes = dict(re.findall(r'^([^ #].*?) -> ([^ ]+):$', mapping, re.M))
    controller = 'app.stemwise.playback.engine.NativePlaybackEngineController'
    if controller not in classes:
        raise ValueError('AE-15 current R8 mapping omits the playback engine controller')
    for name in ('NativeAudioOutput', 'WavPackReader', 'NativePlaybackRuntimeJniBackend'):
        native_class = 'app.stemwise.playback.engine.' + name
        if classes.get(native_class) != native_class:
            raise ValueError('AE-14 static JNI class identity was not preserved by consumer rules')
    return [name for name, _ in instrumentation_records(raw.read_text(encoding='utf-8-sig'))]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['managed-negative', 'development'])
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    report = evidence.load_json(args.report)
    plan = {'sourceSha256': evidence.source_identity(),
            'deviceProfile': {'identity': report.get('deviceProfile')}}
    checked = validate_managed_negative if args.operation == 'managed-negative' else validate_development
    print(checked(report, plan))


if __name__ == '__main__':
    main()
