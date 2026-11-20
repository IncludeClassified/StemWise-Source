"""Published sung references and exact controls, through current package consumers.

Matching fixture output tests wiring/scoring only. No model is run or qualified.
"""
from __future__ import annotations

import copy
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import wave

import numpy as np
import soundfile as sf

from stemwise_engine.common import run_process_tree, sha256_file
from stemwise_engine.lyrics_qualification import _measurement_export, joint_word_metrics
from stemwise_engine.lyrics_tokenization import display_word_ranges, TOKENIZATION_VERSION
from stemwise_engine.original_delivery import prepare_original, create_bundle
from stemwise_engine.packages import create_child_zip
from stemwise_engine.youtube import decode_analysis_audio
from test_packages import song_document, package_config, PNG_1X1

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / 'Test suite/fixtures/packages/self-check-v2'
COLLECTION = json.loads((FIXTURES / 'timing-reference.json').read_text(encoding='utf-8'))


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


class TimingReferenceTests(unittest.TestCase):
    def setUp(self):
        work = ROOT / 'Test suite/work'
        work.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='timing-reference-', dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def control(self, codec='flac'):
        case = COLLECTION['controlled']
        pcm = np.zeros((case['frames'], case['channels']), dtype='<i2')
        for frame, left, right in case['impulses']:
            pcm[frame] = left, right
        quiet = case['quiet']
        pcm[quiet['firstFrame']:quiet['endFrameExclusive']] = quiet['left'], quiet['right']
        wav = self.root / 'control.wav'
        with wave.open(str(wav), 'wb') as writer:
            writer.setparams((2, 2, case['sampleRate'], 0, 'NONE', 'not compressed'))
            writer.writeframes(pcm.tobytes())
        audio = self.root / ('control.flac' if codec == 'flac' else 'control.opus')
        run_process_tree(['ffmpeg', '-v', 'error', '-y', '-i', str(wav), '-c:a', codec,
                          *([] if codec == 'flac' else ['-b:a', '192k']), str(audio)],
                         capture_output=True, check=True, timeout=20)
        return case, audio, pcm.astype(np.float32) / 32768

    def decode(self, audio):
        wav = decode_analysis_audio(audio, self.root / audio.stem, timeout_seconds=20)
        pcm, rate = sf.read(wav, dtype='float32', always_2d=True)
        self.assertEqual(48000, rate)
        return pcm

    def test_flac_control_preserves_quiet_silence_exact_cuts_and_endpoint(self):
        case, audio, expected = self.control()
        document = song_document('FIXTURE0001')
        document['analysis']['durationMs'] = case['durationMs']
        retained, _ = prepare_original(audio, document, self.root)
        self.assertEqual(audio, retained)
        actual = self.decode(retained)
        np.testing.assert_array_equal(expected, actual)
        for start, end in case['silenceCuts']:
            self.assertFalse(np.any(actual[start:end]))
        for cut in case['exactCuts']:
            with self.subTest(cut=cut):
                sliced = actual[cut['firstFrame']:cut['firstFrame'] + cut['frames']]
                self.assertEqual(cut['frames'], len(sliced))
                np.testing.assert_array_equal(np.array(cut['first']) / 32768, sliced[0])
                np.testing.assert_array_equal(np.array(cut['last']) / 32768, sliced[-1])
        self.assertEqual(96017, len(actual))

    def test_opus_nonzero_preskip_preserves_known_impulse_coordinates_and_endpoint(self):
        case, audio, _ = self.control('libopus')
        encoded = audio.read_bytes()
        header = encoded.index(b'OpusHead')
        self.assertGreater(int.from_bytes(encoded[header + 10:header + 12], 'little'), 0)
        document = song_document('FIXTURE0001')
        document['analysis']['durationMs'] = case['durationMs']
        retained, _ = prepare_original(audio, document, self.root)
        actual = self.decode(retained)
        self.assertEqual((96017, 2), actual.shape)
        for frame, _, _ in case['impulses']:
            start, end = max(0, frame - 1000), min(len(actual), frame + 1001)
            for channel in range(2):
                with self.subTest(frame=frame, channel=channel):
                    # Known impulse coordinates, not a general musical timing scorer.
                    self.assertEqual(frame, start + int(np.argmax(np.abs(actual[start:end, channel]))))

    def reference(self, case, audio):
        occurrences, required = [], {}
        for index, line in enumerate(case['lines']):
            identity = digest(f"{case['id']}:occurrence:{index}")
            words = []
            self.assertEqual([(word['startUtf16'], word['endUtf16']) for word in line['words']],
                             display_word_ranges(line['text']))
            for number, word in enumerate(line['words']):
                words.append({key: word[key] for key in ('startUtf16', 'endUtf16', 'startMs', 'endMs')})
                words[-1]['wordIdSha256'] = digest(f'{identity}:word:{number}')
            occurrences.append({'occurrenceIdSha256': identity, 'startMs': words[0]['startMs'],
                                'endMs': words[-1]['endMs'], 'words': words})
            required[identity] = {'text': line['text'], 'words': [
                {key: word[key] for key in ('wordIdSha256', 'startUtf16', 'endUtf16')} for word in words]}
        reference = {'recordingSha256': sha256_file(audio), 'lexicalAuthoritySha256': digest(json.dumps(required)),
                     'durationMs': case['durationMs'], 'occurrences': occurrences}
        return reference, required

    def bundle(self, case, audio, result):
        folder = self.root / case['id']
        folder.mkdir(exist_ok=True)
        document = song_document('FIXTURE0001')
        document['analysis']['durationMs'] = case['durationMs']
        document['analysis']['status'] = 'unavailable'
        document['analysis']['waveform'] = {'status': 'unavailable', 'reason': 'Timing fixture'}
        document['analysis']['toolRuns'] = [{'name': run['name'], 'status': 'skipped', 'reason': 'Timing fixture'}
                                            for run in document['analysis']['toolRuns']]
        track = {'id': 'original', 'kind': 'original', 'language': case.get('language', 'mul'),
                 'format': 'synced', 'source': {'name': 'Controlled labels' if 'referenceClass' in case
                                               else 'JamendoLyrics published manual annotations'}, 'lines': []}
        if track['language'] == 'mul':
            track['componentLanguages'] = ['en', 'ar', 'tr']
        for expected, observed in zip(case['lines'], result['occurrences']):
            track['lines'].append({'text': expected['text'], 'startMs': observed['startMs'], 'endMs': observed['endMs'],
                'words': [{key: word[key] for key in ('startUtf16', 'endUtf16', 'startMs', 'endMs')}
                          for word in observed['words']]})
        document['lyrics'] = {'status': 'partial', 'reason': 'Reference readback fixture; companions unavailable',
                              'tokenizationVersion': TOKENIZATION_VERSION, 'tracks': [track]}
        cover = folder / 'cover.png'
        cover.write_bytes(PNG_1X1)
        metadata, bundle = folder / 'metadata.zip', folder / 'complete.zip'
        create_child_zip(metadata, document, cover, package_config(folder))
        original, identity = prepare_original(audio, document, folder)
        create_bundle(bundle, metadata, original, identity, package_config(folder), folder)
        return {'path': str(bundle.relative_to(self.root)), 'sha256': sha256_file(bundle)}

    def test_actual_bundle_readback_uses_independent_occurrences_and_rejects_wrong_binding(self):
        control, audio, _ = self.control()
        for case, audio in [(control, audio), *[(case, FIXTURES / case['audio']) for case in COLLECTION['musical']]]:
            with self.subTest(case=case['id']):
                reference, required = self.reference(case, audio)
                result = copy.deepcopy(reference)
                descriptor = self.bundle(case, audio, result)
                exported, supported, estimated = _measurement_export(self.root, descriptor, reference, result, required)
                self.assertTrue(exported['valid'], exported)
                self.assertEqual([], estimated)
                metrics = joint_word_metrics(reference, {**result, 'occurrences': supported}, required, complete_export=True)
                self.assertEqual(sum(len(line['words']) for line in case['lines']), metrics['verifiedWords'])
                wrong = {**reference, 'recordingSha256': digest('wrong recording')}
                self.assertFalse(_measurement_export(self.root, descriptor, wrong, result, required)[0]['valid'])
                self.assertEqual(descriptor['sha256'], sha256_file(self.root / descriptor['path']))
                shifted = copy.deepcopy(result)
                first = shifted['occurrences'][0]
                for item in [first, *first['words']]:
                    item['startMs'] += 500
                    item['endMs'] += 500
                self.assertEqual(metrics['verifiedWords'] - len(first['words']),
                    joint_word_metrics(reference, shifted, required, complete_export=True)['verifiedWords'])
                if case is control:
                    swapped = copy.deepcopy(result)
                    swapped['occurrences'][0], swapped['occurrences'][1] = swapped['occurrences'][1], swapped['occurrences'][0]
                    rejected, supported, _ = _measurement_export(self.root, descriptor, reference, swapped, required)
                    self.assertIn('lyricProjectionError', rejected)
                    self.assertEqual([], supported)

    def test_published_sung_references_retain_source_clock_without_qualifying_music_timing(self):
        sys.path.insert(0, str(ROOT / 'Test suite/host'))
        self.addCleanup(sys.path.remove, str(ROOT / 'Test suite/host'))
        import source_timing
        for case in COLLECTION['musical']:
            with self.subTest(case=case['id']):
                audio = FIXTURES / case['audio']
                self.assertEqual(case['audioSha256'], sha256_file(audio))
                pcm, rate = sf.read(audio, dtype='float32', always_2d=True)
                self.assertEqual((case['frames'], 2), pcm.shape)
                self.assertEqual(case['sampleRate'], rate)
                self.assertEqual(case['decodedF32leSha256'], hashlib.sha256(pcm.astype('<f4').tobytes()).hexdigest())
                offset = Decimal(case['crop']['sourceStartFrame']) / case['crop']['sourceSampleRate']
                for line in case['lines']:
                    for word in line['words']:
                        for boundary in ('start', 'end'):
                            expected = ((Decimal(word[f'source{boundary.title()}Seconds']) - offset) * 1000).quantize(
                                Decimal('1'), rounding=ROUND_HALF_UP)
                            self.assertEqual(int(expected), word[f'{boundary}Ms'])
                canonical = self.decode(audio)
                self.assertEqual(case['durationMs'] * 48, len(canonical))
                measured = source_timing.measure_audio(canonical, canonical, calibrated=False)
                self.assertEqual('UNVERIFIED', measured['decision'])
                self.assertIsNone(case['provenance']['annotationUncertaintyMs'])


if __name__ == '__main__':
    unittest.main()
