"""Current bootstrap, model-free producer and editorial regressions."""
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / 'PC side/stemwise-playlist-builder/src'


import stemwise_engine.workflow_diagnostics as workflow_diagnostics

class CurrentAuditFixes(unittest.TestCase):
    def test_wsl_setup_rejects_stale_uv_before_first_use(self):
        import os
        import tarfile
        import io
        for installed in ('missing', 'current', 'stale', 'bad-digest'):
            with self.subTest(installed=installed), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                root = Path(directory)
                builder = root/'PC side/stemwise-playlist-builder'
                builder.mkdir(parents=True)
                setup = builder/'setup_wsl.sh'
                setup.write_bytes((SRC.parent/'setup_wsl.sh').read_bytes())
                shim = root/'bin'
                shim.mkdir()
                def executable(path, text):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(text, encoding='utf-8')
                    path.chmod(0o755)
                binary = b'#!/bin/sh\nif [ "$1" = --version ]; then echo "uv 0.8.3"; else echo "$*" >> "$CALLS"; exit 45; fi\n'
                archive = root/'uv.tar.gz'
                with tarfile.open(archive,'w:gz') as output:
                    info = tarfile.TarInfo('uv-x86_64-unknown-linux-gnu/uv')
                    info.size, info.mode = len(binary), 0o755
                    output.addfile(info,io.BytesIO(binary))
                executable(shim/'python3','#!/bin/sh\nexit 0\n')
                executable(shim/'curl','#!/bin/bash\ncp "$ARCHIVE" "${@: -1}"\n')
                executable(builder/'.tools/bootstrap/bin/python', '''#!/bin/sh
case "$*" in
  *UV_VERSION*) echo 0.8.3;;
  *UV_ARCHIVE_SHA256*) echo "$ARCHIVE_SHA";;
  *runtime_layout*) echo runtime/fixture;;
  *model_registry*) echo fixture;;
esac
''')
                uv = builder/'.tools/uv'
                if installed in ('current','stale'):
                    executable(uv, binary.decode().replace('0.8.3','0.1.0') if installed == 'stale' else binary.decode())
                before = uv.read_bytes() if uv.exists() else None
                environment = {**os.environ, 'PATH':str(shim)+os.pathsep+os.environ['PATH'],
                    'STEMWISE_SKIP_APT':'1','ARCHIVE':str(archive),'CALLS':str(root/'calls'),
                    'ARCHIVE_SHA':'0'*64 if installed == 'bad-digest' else hashlib.sha256(archive.read_bytes()).hexdigest(),
                    'TMPDIR':str(root)}
                result = subprocess.run(['bash',str(setup)],env=environment,capture_output=True,timeout=10)
                calls = (root/'calls').read_text() if (root/'calls').exists() else ''
                if installed in ('current','missing'):
                    self.assertEqual(result.returncode,45,result.stderr.decode())
                    self.assertIn('python install',calls)
                else:
                    self.assertNotEqual(result.returncode,0)
                    self.assertEqual(calls,'')
                    self.assertEqual(uv.read_bytes() if uv.exists() else None,before)

    def test_deno_first_install_and_failed_extraction_cleanup(self):
        source = (SRC.parent / 'setup_wsl.sh').read_text()
        function = source[source.index('install_deno()'):source.index('\nif [[ "${STEMWISE_SKIP_APT')]
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            root = Path(directory)
            archive = root / 'deno.zip'
            binary = b'#!/bin/sh\nexit 0\n'
            with zipfile.ZipFile(archive, 'w') as output:
                output.writestr('deno', binary)
            setup = '''set -Eeuo pipefail
log() { :; }
curl() { cp "$LOCAL_ZIP" "${@: -1}"; }
''' + function + '\ninstall_deno\n'
            import os
            environment = {**os.environ, 'CORE_VENV': str(root/'absent-venv'),
                'CANONICAL_PYTHON': sys.executable, 'DENO_DIR': str(root/'runtime'),
                'DENO_BIN': str(root/'runtime/deno'), 'DENO_VERSION': 'fixture',
                'DENO_ZIP_SHA256': hashlib.sha256(archive.read_bytes()).hexdigest(),
                'DENO_BINARY_SHA256': hashlib.sha256(binary).hexdigest(),
                'LOCAL_ZIP': str(archive), 'TMPDIR': str(root/'scratch')}
            (root/'scratch').mkdir()
            result = subprocess.run(['bash', '-c', setup], env=environment, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            self.assertEqual((root/'runtime/deno').read_bytes(), binary)
            self.assertEqual(list((root/'scratch').iterdir()), [])
            (root/'runtime/deno').unlink()
            environment['CANONICAL_PYTHON'] = '/bin/false'
            result = subprocess.run(['bash', '-c', setup], env=environment, capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(list((root/'scratch').iterdir()), [])
            self.assertFalse((root/'runtime/deno').exists())

    def test_artwork_dispatch_accepts_exact_association_and_rejects_other_recording(self):
        from stemwise_engine.workflow_records import ArtworkUnavailableError
        from workflow_fixture import initialized_workflow
        authority = {'lyricEvidenceVideoIds': ['abcdefghijk'], 'recordingReview': {
            'sourceAssociations': [{'videoId': 'abcdefghijk', 'relation': 'exact-recording'}]}}
        sources = {'lyricEvidenceSources': [{'videoId': 'abcdefghijk'}]}
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as directory:
            workflow = initialized_workflow(Path(directory))
            workflow._require_artwork_source_association(authority, sources, 'abcdefghijk')
            with self.assertRaisesRegex(ArtworkUnavailableError, 'exact recording'):
                workflow._require_artwork_source_association(authority, sources, 'otherid0001')


    def test_validated_source_invalidation_is_an_admitted_transition(self):
        from stemwise_engine.workflow_states import JobTransition
        for target in ('WAITING_FOR_EDITORIAL', 'AUTHORITY_UNRESOLVED'):
            self.assertEqual(JobTransition.require('VALIDATED', target).after.value, target)

    def test_multilingual_turkish_order_uses_real_letters(self):
        from stemwise_engine.lyrics_alignment import transcription_languages
        self.assertEqual(transcription_languages({'language': 'mul', 'text': 'çağrı', 'componentLanguages': ['en', 'tr']})[0], 'tr')
        self.assertEqual(transcription_languages({'language': 'mul', 'text': '\u00c3', 'componentLanguages': ['en', 'tr']})[0], 'en')

    def test_multilingual_qa_uses_declared_components(self):
        from stemwise_engine.lyrics_qa import audit_text
        for text, components in [('Pourquoi ?\nHello\n', ['fr', 'en']), ('می\u200cروم\nHello\n', ['ur', 'en'])]:
            audit = audit_text(text, 'mul', 'Zyyy', component_languages=components)
            self.assertFalse(any(i['code'] in {'zero-width-character', 'space-before-punctuation'} for i in audit['issues']))
        audit = audit_text('a\u200cb\n', 'mul', 'Zyyy', component_languages=['ur', 'en'])
        self.assertTrue(any(i['code'] == 'zero-width-character' for i in audit['issues']))

    def test_shared_script_language_ownership_abstains_on_arabic_kurdish(self):
        from stemwise_engine.alignment_endpoints import _component_phonemizer_languages
        words = [[(0, 4, 'نور'), (5, 10, 'hello')]]
        self.assertIsNone(_component_phonemizer_languages(
            {'language': 'mul', 'componentLanguages': ['ar','ku']}, words))
        self.assertEqual(_component_phonemizer_languages(
            {'language': 'mul', 'componentLanguages': ['ar','en']}, words), [['ar','en-us']])

    def test_demucs_normalization_rejects_silence_and_retains_inverse(self):
        import torch
        from stemwise_engine.endpoint_vocal_evidence import normalize_demucs_input, NoAcousticEvidence
        signal = torch.sin(torch.arange(2000)*.1)
        for audio in (torch.stack([signal, signal]), torch.stack([signal, -signal])):
            normalized, offset, scale = normalize_demucs_input(audio)
            self.assertTrue(torch.isfinite(normalized).all())
            self.assertTrue(torch.allclose(normalized*scale+offset, audio, atol=1e-6))
        with self.assertRaises(NoAcousticEvidence):
            normalize_demucs_input(torch.zeros(2, 2000))

    def test_doubledouble_unknown_submission_is_not_submitted_again(self):
        import stemwise_media.doubledouble as dd
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            calls = []
            def request(url, timeout):
                calls.append(url)
                if '/captcha/' in url: return {'active': False}
                raise TimeoutError('acknowledgement lost')
            with patch.object(dd, '_read_json', side_effect=request):
                for _ in range(2):
                    with self.assertRaises((TimeoutError, ValueError)):
                        dd._download(123, root, job_url=None, timeout_seconds=2)
            self.assertEqual(sum('/dl?' in call for call in calls), 1)
            self.assertEqual(json.loads((root/'job.json').read_bytes())['state'], 'submission-unknown')
            resume = 'https://eu.doubledouble.top/dl/ExactAcceptedJob'
            with patch.object(dd, '_read_json', return_value={'status': 'done'}) as status, \
                    patch.object(dd, '_save_download', return_value={'status': 'saved'}) as save:
                self.assertEqual(dd._download(123, root, job_url=resume, timeout_seconds=2), {'status':'saved'})
                self.assertEqual(status.call_args.args[0], resume)
                self.assertEqual(save.call_args.args[:3], (123, resume, root))
            self.assertFalse((root/'job.json').exists())


    def test_unsupported_targets_are_checked_before_cold_emission_queue(self):
        import stemwise_engine.lyrics_align_pc as align
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            request = {'formatVersion': 1, 'durationMs': 1000, 'lines': [], 'segments': []}
            request_path, output = root/'request.json', root/'output.json'
            request_path.write_text(json.dumps(request), encoding='utf-8')
            def capability(command, **kwargs):
                self.assertIn('--check-targets', command)
                output.write_text(json.dumps({'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                    'requestSha256': align._alignment_request_sha256(request), 'supported': False}), encoding='utf-8')
                return subprocess.CompletedProcess(command, 3, '', '')
            with patch('stemwise_engine.alignment_model_queue.prepare_emissions', side_effect=AssertionError('model queue entered')), \
                    patch.object(align, '_run_numerical_process', side_effect=capability):
                result, resolved, recovery = align._run_alignment_worker_batch(config={'projectDir': str(root)},
                    python=Path(sys.executable), helper=SRC/'wav2vec2_forced_alignment.py', audio=root/'audio.wav',
                    audio_sha256='a'*64, request=request, request_path=request_path, output_path=output,
                    device='cpu', timeout_seconds=10, cache_key='b'*64, producer={}, environment={},
                    failure_path=root/'failure.txt', repair=False, model=root/'model', language='en')
            self.assertEqual(result.returncode, 3)
            self.assertEqual(resolved, request)
            self.assertIsNone(recovery)

    def test_supported_targets_prepare_once_after_request_bound_preflight(self):
        import stemwise_engine.lyrics_align_pc as align
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            request = {'formatVersion': 5, 'durationMs': 1000, 'lines': [], 'segments': []}
            request_path, output = root/'request.json', root/'output.json'
            request_path.write_text(json.dumps(request), encoding='utf-8')
            events = []
            def numerical(command, **kwargs):
                if '--check-targets' in command:
                    events.append('targets')
                    output.write_text(json.dumps({'formatVersion': 1, 'engine': 'wav2vec2-target-capability',
                        'requestSha256': align._alignment_request_sha256(request), 'supported': True}), encoding='utf-8')
                else:
                    events.append('decode')
                    manifest = json.loads(request_path.with_suffix('.batch.json').read_bytes())
                    context = manifest['contexts'][0]
                    output.with_suffix('.batch.json').write_text(json.dumps({'formatVersion': 1,
                        'batchSha256': align._alignment_request_sha256(manifest), 'contexts': [{
                            **{key: context[key] for key in ('contextId','audioSha256','requestSha256')},
                            'resolvedRequestSha256': align._alignment_request_sha256(request),
                            'resolvedSegments': [], 'mergeSteps': []}]}), encoding='utf-8')
                return subprocess.CompletedProcess(command, 0, '', '')
            def prepare(**kwargs):
                self.assertEqual(events, ['targets'])
                events.append('prepare')
            with patch('stemwise_engine.alignment_model_queue.prepare_emissions', side_effect=prepare) as preparation, \
                    patch.object(align, '_run_numerical_process', side_effect=numerical):
                result, resolved, recovery = align._run_alignment_worker_batch(config={'projectDir': str(root)},
                    python=Path(sys.executable), helper=SRC/'wav2vec2_forced_alignment.py', audio=root/'audio.wav',
                    audio_sha256='a'*64, request=request, request_path=request_path, output_path=output,
                    device='cpu', timeout_seconds=10, cache_key='b'*64, producer={}, environment={},
                    failure_path=root/'failure.txt', repair=False, model=root/'model', language='en')
            self.assertEqual(events, ['targets','prepare','decode'])
            preparation.assert_called_once()
            self.assertEqual(result.returncode, 0)
            self.assertEqual(resolved, request)
            self.assertIsNone(recovery)

    def test_every_emitted_endpoint_stage_survives_report_projection(self):
        from stemwise_engine.lyrics_align_pc import _route_attempt_event
        from stemwise_engine.future_song import FutureSongWorkflow
        workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
        stages = ['endpoint', 'endpoint-consensus', 'endpoint-route-verifier', 'endpoint-bootstrap']
        events = [_route_attempt_event(index, stage, 'current-route', 'failure', code='worker-failed')
                  for index, stage in enumerate(stages)]
        events.append(_route_attempt_event(len(events), 'endpoint', 'fallback-route', 'success'))
        projected = workflow_diagnostics._alignment_diagnostics({'routeAttempts': events})
        self.assertEqual(projected['routeAttempts'], events)
        from stemwise_engine.workflow_reports import ImmutableReportStore
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            record = {'runId': 'a'*32, 'videoId': 'abcdefghijk', 'operation': 'lyrics-alignment', 'status': 'complete'}
            evidence = {'evidenceVersion': 1, **{key: record[key] for key in ('runId','videoId','operation')},
                        'diagnostics': projected}
            store = ImmutableReportStore(root, 65536)
            store.commit(root/'reports', record, evidence=evidence)
            stored = json.loads((root/'reports/runs'/('a'*32)/'run.json').read_bytes())
            artifact = stored['reportArtifacts'][0]['fileName']
            self.assertEqual(json.loads((root/'reports/runs'/('a'*32)/artifact).read_bytes())['diagnostics'], projected)
            self.assertTrue(store.artifacts_match(root/'reports/runs'/('a'*32), stored))


    def test_fractional_delay_preserves_match_for_opposite_phase_audio(self):
        import stemwise_media.compare as compare
        import numpy as np
        from scipy.signal import fftconvolve
        rng = np.random.default_rng(830)
        signal = np.convolve(rng.normal(0, .1, 32000), np.ones(5)/5, mode="same")
        kernel = np.sinc(np.arange(-32, 33)-.5)*np.blackman(65)
        kernel /= kernel.sum()
        delayed = fftconvolve(signal, kernel, mode='same')
        for polarity in (1, -1):
            reference = np.column_stack((signal, polarity*signal))
            candidate = np.column_stack((delayed, polarity*delayed))
            result = compare.analyze_waveforms(reference, candidate, 1000, allow_fractional_delay=True)
            self.assertEqual(result['classification'], 'strong_recording_match')

    def test_receipt_publication_failure_preserves_unknown_submission_intent(self):
        import stemwise_media.doubledouble as dd
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            original_write = dd.write_json
            def save(path, value):
                if value.get('jobUrl'):
                    raise OSError('receipt write failed')
                return original_write(path, value)
            def request(url, timeout):
                return {'active': False} if '/captcha/' in url else {'success': True, 'id': 'Accepted123'}
            with patch.object(dd, '_read_json', side_effect=request) as http, patch.object(dd, 'write_json', side_effect=save):
                with self.assertRaises(OSError):
                    dd._download(123, root, job_url=None, timeout_seconds=2)
                with self.assertRaises(ValueError):
                    dd._download(123, root, job_url=None, timeout_seconds=2)
            self.assertEqual(sum('/dl?' in call.args[0] for call in http.call_args_list), 1)
