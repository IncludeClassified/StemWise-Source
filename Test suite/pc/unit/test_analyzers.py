from __future__ import annotations

import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.analyzers import (  # noqa: E402
    SKEY_UNCALIBRATED_REASON,
    _interpret_skey_scores,
    _verify_file_sha256,
)


LABELS = {
    **{index: label for index, label in enumerate(
        [
            "A Major", "Bb Major", "B Major", "C Major", "C# Major", "D Major",
            "D# Major", "E Major", "F Major", "F# Major", "G Major", "G# Major",
        ]
    )},
    **{index + 12: label for index, label in enumerate(
        [
            "B minor", "C minor", "C# minor", "D minor", "D# minor", "E minor",
            "F minor", "F# minor", "G minor", "G# minor", "A minor", "Bb minor",
        ]
    )},
}


class SKeyInterpretationTests(unittest.TestCase):
    def test_actual_skey_output_retains_24_raw_scores_through_owned_private_telemetry(self):
        import hashlib
        import json
        from contextlib import nullcontext
        from types import SimpleNamespace
        from unittest.mock import Mock
        import numpy as np
        import soundfile as sf
        import essentia.standard as es
        from stemwise_engine import analyzers, analysis_numerics, pipeline
        import stemwise_engine.provenance as provenance
        from stemwise_engine.private_diagnostics import analysis_diagnostics, harvest_analysis_causes
        from stemwise_engine.contract import _validate_analysis
        from test_packages import song_document
        scores = [0.0]*24
        scores[16], scores[3], scores[23] = .7, .2, .1
        tensor = Mock()
        tensor.to.return_value = tensor
        tensor.unsqueeze.return_value = tensor
        mean = Mock()
        mean.detach.return_value.cpu.return_value.reshape.return_value.tolist.return_value = scores
        torch = SimpleNamespace(inference_mode=nullcontext, zeros=Mock(), mean=Mock(return_value=mean))
        skey = SimpleNamespace(DEFAULT_CHECKPOINT_PATH='/unused/model', key_map=LABELS,
            load_audio=Mock(return_value=tensor), load_checkpoint=Mock(), load_model_components=Mock())
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            audio = root/'source.wav'
            samples = np.repeat(np.array([.2, .4], dtype='float32'), 4000)
            sf.write(audio, np.column_stack((samples, samples)), 8000, subtype='FLOAT')
            with patch.dict(sys.modules, {'torch': torch, 'skey.key_detection': skey}), \
                    patch.object(analyzers, '_SKEY_COMPONENTS', (Mock(), Mock(), Mock(), 'cpu', 8000)), \
                    patch.object(analyzers, '_full_window_reason', return_value=None), \
                    patch.object(analyzers, 'package_version', return_value='fixture'), \
                    patch.object(provenance, 'numerical_execution_identity', return_value={'device': 'cpu'}):
                key, tool = analyzers.analyze_skey(audio)
            self.assertEqual(tool['status'], 'complete', tool)
            self.assertEqual([row['rawScore'] for row in tool['_privateKeyEvidence']['classScores']], scores)
            base = song_document('abcdefghijk')['analysis']
            tools = {row['name']: row for row in base['toolRuns']}
            structure = {'status': 'complete', 'sections': [
                {'startMs': 0, 'endMs': 500, 'label': 'verse'},
                {'startMs': 500, 'endMs': 1000, 'label': 'chorus'}]}
            extractor = Mock(side_effect=[('C', 'major', .95), ('C', 'major', .8), ('G', 'major', .7)])
            extractor.parameterNames.return_value = ['sampleRate', 'profileType']
            extractor.paramValue.side_effect = lambda name: {'sampleRate': 8000, 'profileType': 'bgate'}[name]
            with patch.object(es, 'KeyExtractor', return_value=extractor), \
                    patch('stemwise_engine.analysis_resources.require_analysis_capacity', return_value={'fullWindowAdmitted': True}), \
                    patch.object(analysis_numerics, '_waveform_analysis', return_value=(base['waveform'], tools['stemwise-waveform'])):
                analysis = analysis_numerics._analysis_document(audio, 1000,
                    (base['rhythm'], tools['stemwise-rhythm-normalizer'], key, tool, tools['beat-this-full']),
                    (base['tuning'], base['loudness'], tools['essentia']),
                    (structure, None, {'name': 'all-in-one', 'status': 'complete', 'version': 'fixture'}))
            transmitted = json.loads(json.dumps(analysis, allow_nan=False))
            with analysis_diagnostics(root/'causes', workspace_root=root) as detail, \
                    patch.object(pipeline, 'process_lock_descriptor', return_value=1), \
                    patch.object(pipeline, '_run_analysis_single', return_value=transmitted):
                transmitted = pipeline.run_analysis_worker(audio, 1000, root, 30)
            _validate_analysis(transmitted, '$.analysis')
            self.assertNotIn('_privateKeyEvidence', transmitted)
            self.assertTrue(all('_privateKeyEvidence' not in row for row in transmitted['toolRuns']))
            evidence = detail['keyEvidence']
            self.assertEqual([row['rawScore'] for row in evidence['skey']['classScores']], scores)
            self.assertEqual({row['classIndex']: row['label'] for row in evidence['skey']['classScores']}, LABELS)
            self.assertEqual(evidence['tonal']['recording']['tonic'], 'C')
            self.assertEqual([(row['startFrame'], row['endFrame']) for row in evidence['tonal']['sections']],
                             [(0, 4000), (4000, 8000)])
            self.assertFalse(evidence['tonal']['sectionConsistency']['allSectionsAgree'])
            np.testing.assert_array_equal(extractor.call_args_list[1].args[0], samples[:4000])
            np.testing.assert_array_equal(extractor.call_args_list[2].args[0], samples[4000:])
            self.assertEqual(transmitted['key']['status'], 'uncertain')
            self.assertNotIn('confidence', transmitted['key'])
            self.assertEqual(evidence['tonal']['sourceSha256'], hashlib.sha256(audio.read_bytes()).hexdigest())
            malformed = json.loads(json.dumps(analysis))
            malformed['_privateKeyEvidence']['skey']['classScores'].pop()
            with analysis_diagnostics(root/'causes', workspace_root=root):
                with self.assertRaisesRegex(ValueError, 'class scores'):
                    harvest_analysis_causes(malformed)
            import copy
            previous = copy.deepcopy(transmitted)
            previous['key'] = {'status': 'unavailable', 'reason': 'prior-key-failure'}
            next(row for row in previous['toolRuns'] if row['name'] == 's-key').update(
                status='failed', reason='prior-key-failure')
            with patch.object(analysis_numerics, 'analyze_skey', return_value=(key, copy.deepcopy(tool))), \
                    patch.object(analysis_numerics, 'analyze_tonal_evidence', return_value=evidence['tonal']) as tonal:
                repaired = analysis_numerics._repair_analysis_worker(audio, 1000, root, 30, previous)
            tonal.assert_called_once_with(audio, previous['structure'])
            with analysis_diagnostics(root/'causes', workspace_root=root) as repair_detail:
                harvest_analysis_causes(repaired)
            _validate_analysis(repaired, '$.analysis')
            self.assertEqual(repair_detail['keyEvidence']['skey']['classScores'], evidence['skey']['classScores'])
            self.assertTrue(all('_privateKeyEvidence' not in row for row in repaired['toolRuns']))

    def test_tonal_evidence_preserves_valid_sections_when_another_window_fails(self):
        from unittest.mock import Mock
        import numpy as np
        import soundfile as sf
        import essentia.standard as es
        from stemwise_engine.analyzers import analyze_tonal_evidence
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            audio = Path(name)/'source.wav'
            sf.write(audio, np.zeros((8000, 2), dtype='float32'), 8000, subtype='FLOAT')
            estimator = Mock(side_effect=[('C', 'major', .9), ('C', 'major', .8), RuntimeError('local failure')])
            estimator.parameterNames.return_value = []
            with patch.object(es, 'KeyExtractor', return_value=estimator), \
                    patch('stemwise_engine.analysis_resources.require_analysis_capacity', return_value={'fullWindowAdmitted': True}):
                result = analyze_tonal_evidence(audio, {'sections': [
                    {'startMs': 0, 'endMs': 500, 'label': 'verse'},
                    {'startMs': 500, 'endMs': 1000, 'label': 'chorus'}]})
            self.assertEqual(result['status'], 'candidate')
            self.assertEqual([row['status'] for row in result['sections']], ['candidate', 'unavailable'])
            self.assertEqual(result['sectionConsistency'], {'analyzedSections': 1, 'totalSections': 2,
                'structureStatus': 'unavailable', 'matchingRecordingSections': 1, 'allSectionsAgree': False})

    def test_ranked_alternatives_keep_raw_score_order_without_confidence(self):
        from stemwise_engine.contract import _validate_key
        scores = [0.0]*24
        scores[16], scores[3], scores[23] = .7, .2, .1
        key = _interpret_skey_scores(scores, LABELS)
        self.assertEqual(key['alternatives'], [{'tonic': 'C', 'mode': 'major'},
                                               {'tonic': 'Bb', 'mode': 'minor'}])
        self.assertNotIn('confidence', key)
        _validate_key(key, '$.key')

    def test_global_rhythm_summary_requires_coverage_and_consistent_tempo(self):
        from stemwise_engine.analyzers import normalize_rhythm
        from stemwise_engine.contract import _validate_rhythm
        for beats, duration, complete in (([0, 500], 300000, False),
                (list(range(0, 5001, 500)) + list(range(6000, 16001, 1000)), 17000, False),
                (list(range(0, 10000, 500)), 10000, True)):
            with self.subTest(beats=beats):
                rhythm, tool = normalize_rhythm(beats, [], duration)
                self.assertEqual(rhythm['status'], 'complete' if complete else 'partial')
                self.assertEqual([row['timeMs'] for row in rhythm['beats']], beats)
                if complete:
                    self.assertEqual(rhythm['bpm'], 120.)
                else:
                    self.assertNotIn('bpm', rhythm)
                    self.assertEqual(tool['status'], 'complete', 'Insufficient evidence is not a retryable producer failure')
                _validate_rhythm(rhythm, '$.rhythm', duration)

    def test_discarded_model_sections_are_partial_and_warn_without_losing_survivors(self):
        from stemwise_engine.analyzers import _normalize_allin1_result
        clean = [{'start': 0, 'end': 4, 'label': 'verse'}, {'start': 8, 'end': 10, 'label': 'outro'}]
        for invalid in ({'start': 3, 'end': 8, 'label': 'chorus'},
                        {'start': 7, 'end': 6, 'label': 'reversed'},
                        {'start': float('nan'), 'end': 8, 'label': 'invalid'},
                        {'start': 4, 'end': 8, 'label': ' '}):
            with self.subTest(invalid=invalid):
                structure, _, tool = _normalize_allin1_result({'segments': [clean[0], invalid, clean[1]]}, 10000)
                self.assertEqual(structure['status'], 'partial')
                self.assertEqual([section['label'] for section in structure['sections']], ['verse', 'outro'])
                self.assertTrue(tool['warnings'])
        self.assertEqual(_normalize_allin1_result({'segments': clean}, 10000)[0]['status'], 'complete')
        self.assertEqual(_normalize_allin1_result({'segments': [{'label': 'bad'}]}, 10000)[0]['status'], 'unavailable')
    def test_forced_choice_is_uncertain_without_invented_confidence(self) -> None:
        scores = [0.0] * 24
        scores[16] = 0.999
        scores[3] = 0.001

        result = _interpret_skey_scores(scores, LABELS)

        self.assertEqual(
            {
                "status": "uncertain",
                "reason": SKEY_UNCALIBRATED_REASON,
                "tonic": "D#",
                "mode": "minor",
                "alternatives": [{"tonic": "C", "mode": "major"}],
            },
            result,
        )
        self.assertNotIn("confidence", result)

    def test_argmax_preserves_flat_label(self) -> None:
        scores = [1.0 / 24.0] * 24
        scores[23] = 0.5
        result = _interpret_skey_scores(scores, LABELS)
        self.assertEqual(("Bb", "minor"), (result["tonic"], result["mode"]))

    def test_rejects_wrong_shape_nonfinite_negative_and_empty_mass(self) -> None:
        invalid = (
            [1.0] * 23,
            [0.0] * 24,
            [float("nan")] + [1.0] * 23,
            [-0.1] + [1.0] * 23,
        )
        for scores in invalid:
            with self.subTest(scores=scores[:2]):
                with self.assertRaises(RuntimeError):
                    _interpret_skey_scores(scores, LABELS)

    def test_requires_the_exact_24_class_label_map(self) -> None:
        with self.assertRaises(RuntimeError):
            _interpret_skey_scores([1.0] * 24, {**LABELS, 24: "C Major"})


class WeightIdentityTests(unittest.TestCase):
    def test_sha256_accepts_exact_content_and_rejects_change(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.bin"
            path.write_bytes(b"frozen model")
            digest = "e53d989a57dfed763fd28993b27f15ef304bc2ed1d384f2fff91afbe889c5fab"
            _verify_file_sha256(path, digest, "test model")

            path.write_bytes(b"changed model")
            with self.assertRaisesRegex(RuntimeError, "content identity mismatch"):
                _verify_file_sha256(path, digest, "test model")


class EssentiaRateTests(unittest.TestCase):
    def test_partial_essentia_failure_keeps_distinct_tuning_and_descriptor_causes(self):
        import numpy as np
        import soundfile as sf
        import essentia.standard as es
        from stemwise_engine.analyzers import analyze_essentia
        from stemwise_engine.private_diagnostics import attach_analysis_causes, analysis_diagnostics, harvest_analysis_causes
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            audio = root/'audio.wav'
            signal = (.2*np.sin(np.arange(48000*4)*.03)).astype('float32')
            sf.write(audio, np.column_stack((signal, signal)), 48000)
            with patch.object(es, 'TuningFrequency', side_effect=RuntimeError('private tuning cause')), \
                    patch.object(es, 'DynamicComplexity', side_effect=RuntimeError('private descriptor cause')):
                tuning, loudness, tool = analyze_essentia(audio)
            self.assertEqual((tuning['status'], loudness['status'], tool['status']), ('unavailable', 'complete', 'complete'))
            value = {'toolRuns': [tool]}
            attach_analysis_causes(value)
            with analysis_diagnostics(root/'causes', workspace_root=root) as detail:
                harvest_analysis_causes(value)
            self.assertEqual(len(detail['privateDiagnostics']), 2)
            bodies = [(root/ref['file']).read_text(encoding='utf-8') for ref in detail['privateDiagnostics']]
            self.assertTrue(any('private tuning cause' in body for body in bodies))
            self.assertTrue(any('private descriptor cause' in body for body in bodies))

    def test_dynamic_complexity_uses_the_actual_resampled_mono_rate(self):
        import numpy as np
        import soundfile as sf
        import essentia.standard as es
        from stemwise_engine.analyzers import analyze_essentia

        with tempfile.TemporaryDirectory() as temporary:
            for rate in (48000, 44100):
                with self.subTest(rate=rate):
                    time = np.arange(12 * rate) / rate
                    signal = ((.1 + .09 * np.sin(2 * np.pi * .7 * time)) *
                              (np.sin(2 * np.pi * 440 * time) + .3 * np.sin(2 * np.pi * 880 * time)))
                    audio = Path(temporary) / f'{rate}.wav'
                    sf.write(audio, np.column_stack((signal, signal)).astype('float32'), rate, subtype='FLOAT')
                    mono = es.MonoLoader(filename=str(audio), sampleRate=44100)()
                    expected = round(float(es.DynamicComplexity(sampleRate=44100)(mono)[0]), 3)
                    _, loudness, tool = analyze_essentia(audio)
                    self.assertEqual(loudness['status'], 'complete')
                    self.assertEqual(loudness['descriptors'], [
                        {'id': 'dynamic-complexity', 'value': expected, 'unit': 'dB'}])


if __name__ == "__main__":
    unittest.main()
