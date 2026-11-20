from pathlib import Path
import tempfile
import hashlib
import unittest
import zipfile

import audio_evidence as evidence
import audio_development_evidence as development


class AudioDevelopmentEvidenceTest(unittest.TestCase):
    def test_instrumentation_requires_exact_started_finished_passing_methods(self):
        blocks = []
        for name in development.DEVELOPMENT_IDS:
            owner, method = name.split('#')
            for code in (1, 0):
                blocks.append(f'INSTRUMENTATION_STATUS: class={owner}\n'
                              f'INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n')
        text = ''.join(blocks) + 'INSTRUMENTATION_CODE: -1\n'
        self.assertEqual(set(development.DEVELOPMENT_IDS), {name for name, _ in development.instrumentation_records(text)})
        for invalid in (text.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -2', 1),
                        text.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3', 1),
                        text.replace('INSTRUMENTATION_CODE: -1', ''), ''.join(blocks[:2]) + 'INSTRUMENTATION_CODE: -1\n',
                        text + blocks[0], 'INSTRUMENTATION_CODE: -1\n'):
            with self.assertRaises(ValueError):
                development.instrumentation_records(invalid)

    def test_development_requires_declared_actual_qualification_configuration(self):
        with tempfile.TemporaryDirectory(dir=evidence.SUITE / 'work') as temporary:
            root = Path(temporary)
            def bind(name, content):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                return evidence.file_binding(path, root)
            app, driver = root / 'app.apk', root / 'driver.apk'
            with zipfile.ZipFile(app, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libstemwise_audio_output.so', b'native')
            with zipfile.ZipFile(driver, 'w'):
                pass
            blocks = []
            for name in development.DEVELOPMENT_IDS:
                owner, method = name.split('#')
                for code in (1, 0):
                    blocks.append(f'INSTRUMENTATION_STATUS: class={owner}\n'
                                  f'INSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n')
            mapping = '# compiler: R8\napp.stemwise.playback.engine.NativePlaybackEngineController -> app.stemwise.playback.engine.NativePlaybackEngineController:\n'
            for name in ('NativeAudioOutput', 'WavPackReader', 'NativePlaybackRuntimeJniBackend'):
                full = 'app.stemwise.playback.engine.' + name
                mapping += f'{full} -> {full}:\n'
            report = dict(schemaVersion=1, sourceSha256='source', deviceProfile='device', abi='arm64-v8a',
                          apk=evidence.file_binding(app, root), testApk=evidence.file_binding(driver, root),
                          nativeSha256=hashlib.sha256(b'native').hexdigest(),
                          mapping=bind('mapping.txt', mapping.encode()),
                          instrumentationSupport=bind('Test suite/android/development/instrumentation-support.pro', b'keep'),
                          instrumentation=bind('result.txt', (''.join(blocks) + 'INSTRUMENTATION_CODE: -1\n').encode()),
                          installedSha256=evidence.digest(app), installedTestSha256=evidence.digest(driver),
                          installedReadback=bind('installed.txt', f'{evidence.digest(app)}  /data/app/app/base.apk'.encode()),
                          installedDriverReadback=bind('driver.txt', f'{evidence.digest(driver)}  /data/app/test/base.apk'.encode()),
                          buildConfiguration=dict(buildType='development', debuggable=True, minifyEnabled=True, shrinkResources=True,
                              identity='app.stemwise.development', signing='disposable', auditHelpers=True,
                              nativeAuditOptimized=True, nativeCacheIoProfiling=True, externalDriverRetention=True))
            plan = dict(sourceSha256='source', deviceProfile=dict(identity='device'))
            self.assertEqual(set(development.DEVELOPMENT_IDS), set(development.validate_development(report, plan, root)))
            for old, replacement in (('app.stemwise.playback.engine.NativePlaybackEngineController -> app.stemwise.playback.engine.NativePlaybackEngineController:\n', ''),
                                     ('NativeAudioOutput -> app.stemwise.playback.engine.NativeAudioOutput:',
                                      'NativeAudioOutput -> x.y:')):
                report['mapping'] = bind('mapping.txt', mapping.replace(old, replacement).encode())
                with self.assertRaises(ValueError):
                    development.validate_development(report, plan, root)
            report['mapping'] = bind('mapping.txt', mapping.encode())
            for field in ('debuggable', 'minifyEnabled', 'shrinkResources', 'auditHelpers', 'nativeAuditOptimized', 'nativeCacheIoProfiling', 'externalDriverRetention'):
                report['buildConfiguration'][field] = False
                with self.assertRaisesRegex(ValueError, 'configuration'):
                    development.validate_development(report, plan, root)
                report['buildConfiguration'][field] = True
            for field, wrong in (('buildType', 'release'), ('identity', 'app.stemwise'), ('signing', 'consumer')):
                prior = report['buildConfiguration'][field]
                report['buildConfiguration'][field] = wrong
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'configuration'):
                    development.validate_development(report, plan, root)
                report['buildConfiguration'][field] = prior

    def test_managed_mutation_requires_intended_failure_and_restored_production(self):
        with tempfile.TemporaryDirectory(dir=evidence.SUITE / 'work') as temporary:
            root = Path(temporary)
            owner, method = development.ROUTE_ID.split('#')
            rows = []
            for phase in ('baseline', 'mutant', 'restored'):
                path = root / (phase + '.xml')
                primary = 'java.lang.AssertionError: ' + development.ROUTE_ASSERTION
                failure = (f'<failure message="{primary}" type="java.lang.AssertionError">{primary}\n'
                           '\tat org.junit.Assert.fail(Assert.java:89)</failure>') if phase == 'mutant' else ''
                path.write_text(f'<testsuite><testcase classname="{owner}" name="{method}">{failure}'
                                '</testcase></testsuite>', encoding='utf-8')
                rows.append({'phase': phase, 'exitCode': int(phase == 'mutant'),
                             'xml': evidence.file_binding(path, root)})
            report = {'schemaVersion': 1, 'sourceSha256': 'candidate',
                      'required': ['stale-route-recovery'], 'records': rows}
            plan = {'sourceSha256': 'candidate'}
            self.assertEqual(['stale-route-recovery'], development.validate_managed_negative(report, plan, root))
            rows[1]['exitCode'] = 0
            with self.assertRaisesRegex(ValueError, 'did not fail'):
                development.validate_managed_negative(report, plan, root)
            rows[1]['exitCode'] = 1
            path = root / 'mutant.xml'
            valid = path.read_text(encoding='utf-8')
            for invalid in (valid.replace(primary, 'java.lang.AssertionError: unrelated setup error', 1),
                            valid.replace('type="java.lang.AssertionError"', 'type="java.lang.IllegalStateException"'),
                            valid.replace('>' + primary + '\n', '>java.lang.AssertionError: unrelated setup error\n'
                                          '\tSuppressed: ' + primary + '\n')):
                path.write_text(invalid, encoding='utf-8')
                rows[1]['xml'] = evidence.file_binding(path, root)
                with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, 'did not fail'):
                    development.validate_managed_negative(report, plan, root)


if __name__ == '__main__':
    unittest.main()
