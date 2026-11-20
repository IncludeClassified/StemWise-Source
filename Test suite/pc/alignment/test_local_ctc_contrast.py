import copy
from contextlib import ExitStack, redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np
import soundfile as sf

from stemwise_engine import local_ctc_contrast as contrast
from stemwise_engine import acoustic_inputs, ctc_worker, endpoint_mms_prediction as mms
from stemwise_engine import worker_protocol
from stemwise_engine.common import WorkerRetirementError, sha256_file
from stemwise_engine.provider_operations import ProviderCancelled


class LocalCtcContrastTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.project = self.root/'builder'
        self.project.mkdir()
        self.directory = self.root/'diagnostic'
        self.audio = self.root/'original.wav'
        sf.write(self.audio, np.sin(np.arange(48000)/17).astype('float32')*.1, 48000, subtype='FLOAT')
        self.vocab = {'-': 0, 'a': 1, 'b': 2, 'c': 3}
        self.cases = [{'id': 'local', 'startMs': 0, 'endMs': 1000, 'alternatives': [
            {'id': 'source', 'text': 'ab cab', 'role': 'candidate'},
            {'id': 'alternative', 'text': 'ab bac', 'role': 'candidate'},
            {'id': 'agreed-phrase', 'text': 'ab cab', 'role': 'positive-control'},
            {'id': 'wrong-word', 'text': 'ac cab', 'role': 'negative-control'},
            {'id': 'omission', 'text': 'ab', 'role': 'negative-control'},
            {'id': 'extra-repeat', 'text': 'ab cab cab', 'role': 'negative-control'}]}]
        self.events, self.requests = [], []
        self.runtime = {'fixture': 'native-runtime', 'uuid': 'GPU-known', 'driver': 13020}
        self.error = self.after_inference = None
        expected = [0, 1, 0, 2, 0, 3, 0, 1, 0, 2, 0]
        probabilities = np.full((len(expected), 4), .02, dtype=np.float64)
        probabilities[np.arange(len(expected)), expected] = .94
        self.logp = np.log(probabilities)
        self.edges = np.linspace(0, 1000, len(expected)+1)
        case = self

        class Reader:
            def __init__(self, path, rate):
                case.assertEqual((path, rate), (case.audio, 16000))
                self.representation = 'mono'
            def read(self, first, last):
                case.assertEqual((first, last), (0, 16000))
                return [SimpleNamespace(numpy=lambda: np.zeros(last-first, dtype=np.float32))]

        # Only the established audio/model numerical boundaries are substituted.
        lifetime = ExitStack()
        self.addCleanup(lifetime.close)
        lifetime.enter_context(patch.object(acoustic_inputs, 'StreamedMonoAudio', Reader))
        lifetime.enter_context(patch.object(contrast, '_runtime_identity', side_effect=lambda: copy.deepcopy(self.runtime)))
        lifetime.enter_context(patch.object(contrast, 'environment_python', return_value=self.root/'native-python'))
        lifetime.enter_context(patch.object(contrast, 'run_process_tree', side_effect=self.dispatch))
        lifetime.enter_context(patch.object(mms, 'load_model', side_effect=self.load_model))
        lifetime.enter_context(patch.object(mms, 'prediction_emissions', side_effect=self.infer))
        lifetime.enter_context(patch.object(ctc_worker, 'retire_model', side_effect=self.retire))
        lifetime.enter_context(patch.dict(sys.modules, {'torchaudio': SimpleNamespace(
            pipelines=SimpleNamespace(MMS_FA=SimpleNamespace(get_dict=lambda **_: self.vocab)))}))
        self.checkpoint = self.root/'model-cache/torch/hub/checkpoints/model.pt'
        self.checkpoint.parent.mkdir(parents=True)
        self.checkpoint.write_bytes(b'known fixture model')
        lifetime.enter_context(patch.object(mms, 'MODEL', {**mms.MODEL, 'modelSha256': sha256_file(self.checkpoint)}))

    def load_model(self, project):
        self.assertEqual(project, self.project)
        self.events.append('load')
        return SimpleNamespace(vocab=self.vocab)

    def infer(self, model, signal, origin):
        self.events.append('infer')
        self.assertEqual((len(signal), origin), (16000, 0))
        if self.error:
            raise self.error
        if self.after_inference:
            self.after_inference()
        return self.logp.copy(), self.edges.copy()

    def retire(self, state):
        self.events.append('retire')
        state.clear()

    def dispatch(self, command, **settings):
        self.assertEqual(settings['cwd'], self.project)
        self.assertEqual(command[0], str(self.root/'native-python'))
        arguments = command[command.index('stemwise_engine.local_ctc_contrast')+1:]
        if '--request' in arguments:
            self.requests.append(json.loads(Path(arguments[arguments.index('--request')+1]).read_bytes()))
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(sys, 'argv', ['local_ctc_contrast', *arguments]), redirect_stdout(stdout), redirect_stderr(stderr):
            try:
                code = worker_protocol.run_worker_main(contrast.main)
            except SystemExit as error:
                code = error.code
        return subprocess.CompletedProcess(command, code or 0, stdout.getvalue(), stderr.getvalue())

    def run_experiment(self):
        return contrast.run_experiment(project=self.project, audio=self.audio, cases=self.cases,
            directory=self.directory, timeout_seconds=10)

    def test_actual_adapter_worker_cache_and_editorial_only_replay_keep_diagnostic_scores(self):
        result = self.run_experiment()
        self.assertEqual(self.events, ['load', 'infer', 'retire'])
        self.assertEqual(result['decision'], 'unclear')
        self.assertTrue(all(result[key] is False for key in ('contentAuthority', 'timingAuthority', 'publicationAuthority')))
        row = result['cases'][0]
        self.assertFalse(row['cacheReused'])
        scores = row['result']['alternatives']
        self.assertGreater(scores[0]['logLikelihood'], scores[1]['logLikelihood'])
        self.assertEqual(scores[0]['logLikelihood'], scores[2]['logLikelihood'])
        self.assertEqual(scores[0]['orderContrast'], scores[2]['orderContrast'])
        self.assertEqual([r['id'] for r in scores], [r['id'] for r in self.cases[0]['alternatives']])
        self.cases[0]['alternatives'][1]['text'] = 'ac bac'
        self.events.clear()
        replay = self.run_experiment()
        self.assertEqual(self.events, [])
        self.assertTrue(replay['cases'][0]['cacheReused'])
        self.assertEqual(replay['cases'][0]['emissionsSha256'], row['emissionsSha256'])
        self.assertNotEqual(replay['cases'][0]['result']['alternatives'][1]['logLikelihood'], scores[1]['logLikelihood'])

    def test_flat_emissions_never_become_content_or_timing_authority(self):
        self.logp[:] = np.log(.25)
        result = self.run_experiment()['cases'][0]['result']
        self.assertEqual(result['decision'], 'unclear')
        self.assertTrue(all(row['orderContrast'] == 0 for row in result['alternatives']))

    def test_malformed_or_empty_emissions_retire_without_publication(self):
        for values in (np.empty((0, 4)), np.full((11, 4), np.nan), np.ones((11, 4))):
            with self.subTest(shape=values.shape):
                self.logp = values
                with self.assertRaises(worker_protocol.WorkerProtocolError):
                    self.run_experiment()
                self.assertFalse((self.directory/'comparison.private.json').exists())
                self.assertEqual(self.events[-1], 'retire')

    def test_changed_runtime_model_and_audio_miss_cache_without_destroying_prior_output(self):
        self.run_experiment()
        previous = (self.directory/'comparison.private.json').read_bytes()
        cached = {p.name: p.read_bytes() for p in self.directory.glob('local-emissions-*.npz')}
        for change in ('runtime', 'model', 'audio'):
            with self.subTest(change=change):
                if change == 'runtime':
                    self.runtime['uuid'] += '-changed'
                elif change == 'model':
                    self.checkpoint.write_bytes(b'changed fixture model')
                    mms.MODEL['modelSha256'] = sha256_file(self.checkpoint)
                else:
                    sf.write(self.audio, np.zeros(48000, dtype='float32'), 48000, subtype='FLOAT')
                self.error = ValueError('native failure')
                self.events.clear()
                with self.assertRaises(worker_protocol.WorkerProtocolError):
                    self.run_experiment()
                self.assertEqual(self.events, ['load', 'infer', 'retire'])
                self.assertEqual((self.directory/'comparison.private.json').read_bytes(), previous)
                self.assertEqual({p.name: p.read_bytes() for p in self.directory.glob('local-emissions-*.npz')}, cached)

    def test_input_change_after_inference_retires_and_preserves_previous_result(self):
        self.run_experiment()
        previous = (self.directory/'comparison.private.json').read_bytes()
        self.runtime['uuid'] += '-new'
        self.after_inference = lambda: self.audio.write_bytes(b'changed input')
        self.events.clear()
        with self.assertRaises(worker_protocol.WorkerProtocolError):
            self.run_experiment()
        self.assertEqual(self.events, ['load', 'infer', 'retire'])
        self.assertEqual((self.directory/'comparison.private.json').read_bytes(), previous)

    def test_malformed_cache_requires_real_inference_and_preserves_binding(self):
        self.run_experiment()
        path = next(self.directory.glob('local-emissions-*.npz'))
        path.write_bytes(b'invalid cache')
        self.events.clear()
        result = self.run_experiment()
        self.assertEqual(self.events, ['load', 'infer', 'retire'])
        self.assertFalse(result['cases'][0]['cacheReused'])

    def test_cancellation_and_retirement_failure_propagate_without_publication(self):
        for failure in (ProviderCancelled('stop'), WorkerRetirementError('unretired worker')):
            with self.subTest(failure=type(failure).__name__):
                with patch.object(contrast, 'run_process_tree', side_effect=failure):
                    with self.assertRaises(type(failure)):
                        self.run_experiment()
                self.assertFalse((self.directory/'comparison.private.json').exists())

    def test_actual_worker_cancellation_retires_model_and_keeps_prior_output(self):
        self.run_experiment()
        previous = (self.directory/'comparison.private.json').read_bytes()
        self.runtime['uuid'] += '-new'
        self.error = ProviderCancelled('stop during model')
        self.events.clear()
        with self.assertRaises(ProviderCancelled):
            self.run_experiment()
        self.assertEqual(self.events, ['load', 'infer', 'retire'])
        self.assertEqual((self.directory/'comparison.private.json').read_bytes(), previous)

    def test_wrong_request_producer_stops_actual_worker_before_model_load(self):
        self.directory.mkdir()
        request = self.directory/'wrong-request.json'
        request.write_text(json.dumps({'project': str(self.project), 'audio': str(self.audio),
            'audioIdentity': contrast._audio_identity(self.audio), 'producer': {'changed': 'source'},
            'runtime': self.runtime, 'cases': self.cases, 'timeoutSeconds': 10,
            'cacheDir': str(self.directory)}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'producer'):
            contrast.run_request(request, sha256_file(request))
        self.assertEqual(self.events, [])

    def test_invalid_alternatives_and_crops_are_rejected_before_inference(self):
        for change in ('empty', 'duplicate', 'outside'):
            cases = copy.deepcopy(self.cases)
            if change == 'empty': cases[0]['alternatives'][0]['text'] = ''
            elif change == 'duplicate': cases[0]['alternatives'][1]['id'] = 'source'
            else: cases[0]['endMs'] = 1001
            with self.subTest(change=change), self.assertRaises(ValueError):
                contrast.run_experiment(project=self.project, audio=self.audio, cases=cases,
                    directory=self.directory, timeout_seconds=10)
        self.assertEqual(self.events, [])


if __name__ == '__main__':
    unittest.main()
