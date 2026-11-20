"""Arabic candidate wiring through the real disagreement collector and Status."""
from contextlib import contextmanager
import copy
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_whisper_lexical_candidate as whisper_fixtures
from stemwise_engine import nemo_lexical_candidate as lexical
from stemwise_engine import recording_evidence as evidence, runtime_environment
from stemwise_engine.alignment_phases import AudioIdentity
from stemwise_engine.common import WorkerRetirementError, atomic_write_json, sha256_file
from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
from stemwise_engine.workflow_status import validate_recovery_candidates


class NemoRecordingRecoveryTests(unittest.TestCase):
    @contextmanager
    def fixture(self, language='ar', text='مولاي نور', components=None):
        case = whisper_fixtures.WhisperLexicalCandidateTests(
            'test_empty_dtw_vendor_generator_retains_untimed_text_without_timing_authority')
        case.setUp()
        try:
            (case.job/'drafts/original.txt').write_text(text+'\n', encoding='utf-8')
            metadata = {'originalLanguage': language, 'originalScript': 'Arab' if language != 'en' else 'Latn'}
            if components is not None:
                metadata['componentLanguages'] = components
            atomic_write_json(case.job/'drafts/lyrics.meta.json', metadata)
            with case.boundaries() as wrapper:
                case.active = False
                def load():
                    case.active = True
                    return wrapper
                case.owner.get.side_effect = load
                case.owner.close.side_effect = lambda: setattr(case, 'active', False)
                yield case
        finally:
            case.doCleanups()

    def collect(self, case):
        draft, draft_sha, metadata, metadata_sha = evidence.read_recording_draft(case.job)
        identity = whisper_fixtures.alignment_audio.file_identity(case.audio)
        identity['canonicalTimeTransform'] = whisper_fixtures.alignment_audio.identity_transform(identity['stream'])
        original = {'text': draft, 'language': metadata['originalLanguage'], 'script': metadata['originalScript'],
                    'componentLanguages': metadata.get('componentLanguages', [])}
        return evidence.collect_disagreement_candidate(case.config, case.workspace, 'AAAAAAAAAAA',
            case.audio, identity, original, draft_sha, metadata_sha, case.primary, {'known': 'unresolved'}, diagnostic=True)

    def native_boundary(self, case, error=None):
        def native(**arguments):
            self.assertTrue(case.owner.get.called, 'The ordinary Whisper route did not run')
            self.assertFalse(case.active, 'Native inference started before Whisper retirement')
            self.assertTrue(case.owner.close.called)
            project = Path(case.config['projectDir'])
            lease = project.resolve()/f'.environment-use-{lexical.ENVIRONMENT}.lock'
            self.assertTrue(any(path == lease and not exclusive
                for path, descriptor, exclusive in runtime_environment._LEASES.get()), 'Native lease is not active')
            expected = whisper_fixtures.alignment_audio.file_identity(case.audio)
            self.assertEqual(arguments['audio_identity'], expected)
            self.assertEqual(arguments['audio'], case.audio)
            self.assertEqual(arguments['project'], project)
            self.assertEqual(arguments['original']['language'], 'ar')
            self.assertEqual(arguments['original']['script'], 'Arab')
            self.assertEqual(arguments['original']['text'].strip(), 'مولاي نور')
            arguments['require_inputs']()
            case.prior = {path: path.read_bytes() for directory in (case.job/'candidates', case.primary.parent)
                          for path in directory.rglob('*') if path.is_file()}
            if error is not None:
                raise error
            runtime = {'python': lexical.nfa.PYTHON_VERSION, 'nemoToolkit': lexical.nfa.NEMO_VERSION,
                'torch': lexical.nfa.TORCH_VERSION, 'cudaRuntime': lexical.nfa.CUDA_VERSION,
                'cudaDevice': {'name': 'Known fixture CUDA', 'capability': [12, 0], 'totalMemoryBytes': 16*1024**3}}
            runtime.update(deviceIndex=0, torchProducer={'device': 'cuda', 'torch': lexical.nfa.TORCH_VERSION,
                'cudaRuntime': lexical.nfa.CUDA_VERSION, 'cudaDriver': 13020, 'cudnn': '91200',
                'uuid': 'GPU-11111111-1111-1111-1111-111111111111', **runtime['cudaDevice']})
            value = {'formatVersion': lexical.FORMAT_VERSION, 'kind': lexical.KIND, 'videoId': 'AAAAAAAAAAA',
                'provenance': {'familyId': lexical.FAMILY, 'language': 'ar', 'routeId': 'mix', 'audio': expected,
                    'model': copy.deepcopy(lexical.MODEL), 'runtime': runtime,
                    'producer': lexical.producer_identity(), 'decoder': copy.deepcopy(lexical.DECODER)},
                'rawOutput': [{'text': 'مولاي نور', 'tokens': [3, 7]}], 'transcription': 'مولاي نور',
                'contentAuthority': False, 'timingAuthority': False, 'publicationAuthority': False}
            lexical.validate(value, expected['sha256'])
            atomic_write_json(arguments['job_dir']/'nemo-lexical-ar-mix.private.json', value)
            return value, False
        return native

    def readback(self, case, packet):
        validate_recovery_candidates(case.job, case.primary.parent, packet, video_id='AAAAAAAAAAA',
            audio_sha=sha256_file(case.audio), duration_ms=1000)

    def test_arabic_collector_retires_whisper_and_holds_native_lease_through_candidate_staging(self):
        with self.fixture() as case, patch.object(lexical, 'load_or_transcribe',
                side_effect=self.native_boundary(case)) as native:
            packet = self.collect(case)
            native.assert_called_once()
            self.assertEqual(len(packet['candidates']), 3)
            candidate = next(row for row in packet['candidates'] if 'nemo' in row['recognition']['path'])
            self.assertEqual((case.job/candidate['candidate']['path']).read_text(encoding='utf-8'), 'مولاي نور\n')
            self.assertIs(candidate['publicationAuthority'], False)
            self.assertFalse(case.active)
            self.readback(case, packet)
            self.assertEqual({path: path.read_bytes() for path in case.prior}, case.prior)

    def test_english_and_mixed_recordings_skip_monolingual_native_adapter(self):
        for language, text, components in (('en', 'first first', None), ('mul', 'مولاي hello', ['ar', 'en'])):
            with self.subTest(language=language), self.fixture(language, text, components) as case, \
                    patch.object(lexical, 'load_or_transcribe', side_effect=AssertionError('ineligible native route')) as native:
                packet = self.collect(case)
                native.assert_not_called()
                self.assertEqual(len(packet['candidates']), 2)
                self.readback(case, packet)

    def test_optional_native_failure_preserves_prior_candidates_and_status_readback(self):
        with self.fixture() as case, patch.object(lexical, 'load_or_transcribe',
                side_effect=self.native_boundary(case, RuntimeError('optional native failure'))):
            packet = self.collect(case)
            self.assertEqual(len(packet['candidates']), 2)
            self.assertTrue(any(row['familyId'] == lexical.FAMILY for row in packet['routeFailures']))
            self.assertEqual({path: path.read_bytes() for path in case.prior}, case.prior)
            self.readback(case, packet)

    def test_native_fatal_and_cancellation_propagate_without_discarding_prior_candidates(self):
        for error in (WorkerRetirementError('unretired native owner'), ProviderCancelled('parent cancelled'),
                      ProviderDeadline('parent deadline')):
            with self.subTest(error=type(error).__name__), self.fixture() as case, \
                    patch.object(lexical, 'load_or_transcribe', side_effect=self.native_boundary(case, error)):
                with self.assertRaises(type(error)) as raised:
                    self.collect(case)
                self.assertIs(raised.exception, error)
                self.assertFalse(case.active)
                self.assertEqual({path: path.read_bytes() for path in case.prior}, case.prior)


if __name__ == '__main__':
    unittest.main()
