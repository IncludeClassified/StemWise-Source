"""Native recording review must use the corrected candidates and actual stereo audio."""
import json
import csv
import sys
from qobuz_fixtures import write_download
import tempfile
import unittest
import wave
import io
import threading
import time
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
from stemwise_media import recording_review as review


class ComparisonRecoveryTests(unittest.TestCase):
    def test_selected_invalid_and_fatal_optional_inputs_preserve_previous_reports(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        self.row['candidates'] = self.tracks[:1]
        self.row['ownerAudioSelection'] = {'source': 'qobuz', 'trackId': 20,
            'file': self.paths[20].relative_to(self.root).as_posix()}
        data = {'rows': [self.row], 'libraryPolicy': {'includeAllRetainedFlac': True}, 'downloadPolicy': {}}
        output = self.root/'comparison-results'
        output.mkdir()
        before = {'recording-matches.csv': b'previous complete CSV', 'unmatched-recordings.txt': b'previous complete text'}
        for name, raw in before.items():
            (output/name).write_bytes(raw)
        original = review.batch.qobuz_file
        for failure in (ValueError('invalid selected digest'), ProviderCancelled('cancelled'),
                        WorkerRetirementError('unretired optional verifier')):
            with self.subTest(failure=type(failure).__name__):
                (self.root/'catalogue.json').write_text(json.dumps(data), encoding='utf-8')
                def resolve(track_id):
                    if track_id == 20:
                        raise failure
                    return original(track_id)
                with patch.object(review.batch, 'qobuz_file', side_effect=resolve), \
                        patch.object(review, 'review_row', return_value=({'status': 'not_checked', 'candidates': []}, 0)), \
                        redirect_stdout(io.StringIO()):
                    with self.assertRaises(type(failure)) as raised:
                        review.main()
                self.assertIs(raised.exception, failure)
                self.assertEqual({name: (output/name).read_bytes() for name in before}, before)

    def test_excluded_cached_equivalence_does_not_block_discovery_or_public_review(self):
        import stemwise_media.recording_equivalence as equivalence
        self.row['candidates'] = self.tracks[:1]
        self.row['recordingEquivalence'] = self.bound(
            {'trackId': 20, 'classification': 'same_performance_with_differences',
             'checkedWindows': 20, 'supportedWindowFraction': 1., 'channelMedians': [1., 1.],
             'anchors': [{'correlation': 1., 'offsetSeconds': 0.}]*3}, rate=8000)
        registration = self.paths[20].parent/'enrichment-input.json'
        registration.write_text(json.dumps({'formatVersion': 1, 'trackId': 20,
            'referenceVideoId': self.row['videoId'], 'title': 'Retained optional', 'artist': 'Artist',
            'album': '', 'file': self.paths[20].relative_to(self.root).as_posix()}), encoding='utf-8')
        original_optional = self.paths[20].read_bytes()
        self.paths[20].write_bytes(original_optional[:-1] + bytes([original_optional[-1] ^ 1]))
        data = {'rows': [self.row], 'libraryPolicy': {'includeAllRetainedFlac': True}, 'downloadPolicy': {}}
        catalogue = self.root/'catalogue.json'
        catalogue.write_text(json.dumps(data), encoding='utf-8')
        inputs = {path: path.read_bytes() for path in [self.source, registration, *self.paths.values(),
            *(path.parent/'download.json' for path in self.paths.values())]}
        with patch.object(review.batch, 'decode', return_value=np.zeros((16, 2))), \
                patch.object(review.batch, 'analyze_waveforms', return_value=self.waveform()), \
                patch.object(review.batch, 'analyze_clock_drift', return_value={'classification': 'not_matched'}), \
                patch.object(review, 'probe', return_value={'codec_name': 'opus'}):
            for deep in (False, True):
                with self.subTest(discovery_deep=deep):
                    catalogue.write_text(json.dumps(data), encoding='utf-8')
                    review.batch.export(review.batch.read_catalogue(catalogue))
                    with patch.object(sys, 'argv', ['recording_equivalence.py'] + (['--deep'] if deep else [])), \
                            patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)), \
                            patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}), \
                            patch.object(review.batch, 'qobuz_file', wraps=review.batch.qobuz_file) as resolve, \
                            redirect_stdout(io.StringIO()) as captured:
                        equivalence.main()
                    self.assertEqual(json.loads(captured.getvalue().splitlines()[-1])['comparedPairs'], 1)
                    self.assertNotIn(20, [call.args[0] for call in resolve.call_args_list])
            catalogue.write_text(json.dumps(data), encoding='utf-8')
            with redirect_stdout(io.StringIO()) as captured:
                self.assertEqual(review.main(), 0)
            summary = json.loads(captured.getvalue().splitlines()[-1])
            self.assertEqual(summary['pairedFlacFiles'], 1)
            self.assertEqual(summary['performancePairedFlacFiles'], 1)
            reports = self.root/'comparison-results'
            with (reports/'recording-matches.csv').open(encoding='utf-8-sig', newline='') as stream:
                row = list(csv.reader(stream))[1]
            self.assertEqual(row[7], '')
            self.assertIn('0', (reports/'unmatched-recordings.txt').read_text(encoding='utf-8'))
            self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)
            import copy
            receipt = self.paths[20].parent/'download.json'
            for control in ('missing-receipt', 'rejected', 'unsupported'):
                with self.subTest(control=control):
                    current = copy.deepcopy(data)
                    if control == 'missing-receipt':
                        receipt.unlink()
                    elif control == 'rejected':
                        current['rows'][0]['rejectedTrackIds'] = [20]
                    else:
                        current['rows'][0]['recordingEquivalence']['candidates'][0]['classification'] = 'not_supported'
                    catalogue.write_text(json.dumps(current), encoding='utf-8')
                    with redirect_stdout(io.StringIO()):
                        self.assertEqual(review.main(), 0)
                    receipt.write_bytes(inputs[receipt])
            catalogue.write_text(json.dumps(data), encoding='utf-8')
            self.paths[20].write_bytes(original_optional)
            inputs[self.paths[20]] = original_optional
            with redirect_stdout(io.StringIO()):
                self.assertEqual(review.main(), 0)
            self.assertEqual({t['trackId'] for t in json.loads((reports/'library-files.json').read_bytes())['flac']}, {10, 20})
        self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)

    def test_review_retirement_stops_later_candidate_decodes(self):
        from stemwise_engine.common import WorkerRetirementError
        for failed_decode in (1, 2):
            fatal = WorkerRetirementError('unretired review decoder')
            with self.subTest(failed_decode=failed_decode), \
                    patch.object(review.batch, 'decode', side_effect=[fatal] if failed_decode == 1 else [np.zeros((8,2)), fatal]) as decode:
                with self.assertRaises(WorkerRetirementError) as raised:
                    review.review_row(self.row, self.tracks)
            self.assertIs(raised.exception, fatal)
            self.assertEqual(decode.call_count, failed_decode)

    def test_public_review_ignores_never_downloaded_alternative_and_persists_retained_candidate(self):
        self.row['candidates'] = [self.tracks[0], {**self.tracks[1], 'id': 30}]
        (self.root/'catalogue.json').write_text(json.dumps({'rows': [self.row]}), encoding='utf-8')
        with patch.object(review.batch, 'decode', return_value=np.zeros((16, 2))) as decoded, \
                patch.object(review.batch, 'analyze_waveforms', return_value=self.waveform()), \
                patch.object(review.batch, 'analyze_clock_drift', return_value={'classification': 'not_matched'}), \
                patch.object(review, 'probe', return_value={'codec_name': 'opus'}), \
                patch.object(review.batch, 'export'), patch.object(review, 'export_review', return_value={}), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(review.main(), 0)
        saved = json.loads((self.root/'catalogue.json').read_bytes())['rows'][0]
        self.assertEqual([candidate['trackId'] for candidate in saved['refinedAudioComparison']['candidates']], [10])
        self.assertGreaterEqual(decoded.call_count, 2)
        self.assertEqual(saved['candidates'], self.row['candidates'])

    def test_missing_first_recording_preserves_evidence_and_compares_later_rows(self):
        import copy
        batch = review.batch
        prior = {'status':'unconfirmed','candidates':[{'trackId':10,'classification':'error','reason':'retained prior evidence'}]}
        self.row.update(candidates=[self.tracks[0]],refinedAudioComparison=copy.deepcopy(prior))
        later = copy.deepcopy(self.row)
        later.update(position=2,videoId='lmnopqrstuv',candidates=[self.tracks[1]],refinedAudioComparison={'candidates':[]})
        source = self.root/'audio/youtube/lmnopqrstuv/source.opus'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'later reference')
        write_download(source.parent,{'videoId':'lmnopqrstuv','file':source.name})
        self.paths[10].unlink()
        for command in ('compare','review'):
            with self.subTest(command=command):
                (self.root/'catalogue.json').write_text(json.dumps({'rows':[self.row,later]}),encoding='utf-8')
                with patch.object(batch,'decode',return_value=np.zeros((16,2))) as decoded, \
                        patch.object(batch,'analyze_waveforms',return_value={'classification':'unconfirmed'}), \
                        patch.object(batch,'analyze_clock_drift',return_value={'classification':'unconfirmed'}), \
                        patch.object(review,'probe',return_value={'codec_name':'opus'}), \
                        patch.object(batch,'export'),patch.object(review,'export_review',return_value={}), \
                        redirect_stdout(io.StringIO()):
                    if command == 'compare':
                        batch.compare_rows(batch.read_catalogue(self.root/'catalogue.json'),refine=True)
                    else:
                        review.main()
                saved = json.loads((self.root/'catalogue.json').read_bytes())['rows']
                self.assertEqual(saved[0]['refinedAudioComparison'],prior)
                self.assertEqual(saved[1]['refinedAudioComparison']['candidates'][0]['trackId'],20)
                self.assertGreaterEqual(decoded.call_count,2)

    def setUp(self):
        self.work = ROOT / 'Test suite/work'
        self.work.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=self.work)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root/'audio/youtube/abcdefghijk/source.opus'
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(b'reference')
        write_download(self.source.parent, {'videoId': 'abcdefghijk', 'file': self.source.name})
        self.paths = {}
        for identity in (10, 20):
            path = self.root/f'audio/qobuz/{identity}/audio.flac'
            path.parent.mkdir(parents=True)
            path.write_bytes(b'fixture')
            write_download(path.parent, {'qobuzTrackId': identity, 'file': path.name})
            self.paths[identity] = path
        self.tracks = [{'id': i, 'title': 'Song', 'artist': 'Artist', 'album': ''} for i in self.paths]
        self.row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': self.tracks,
                    'metadataSelection': {'status': 'deferred', 'basis': 'competing'},
                    'refinedAudioComparison': {'candidates': []}}
        patcher = patch.object(review.batch, 'ROOT', self.root)
        patcher.start()
        self.addCleanup(patcher.stop)

    def bound(self, candidate, *, rate=48000):
        identity = candidate['trackId']
        return {'sampleRate': rate, 'recipeVersion': 1,
                'referenceFile': self.source.relative_to(self.root).as_posix(),
                'referenceSha256': review.batch.file_sha256(self.source),
                'candidates': [candidate | {'candidateFile': self.paths[identity].relative_to(self.root).as_posix(),
                    'candidateSha256': review.batch.file_sha256(self.paths[identity])}]}

    @staticmethod
    def waveform():
        return {'classification': 'strong_recording_match', 'fullContentSupported': True,
                'channels': [{'channel': c, 'windows': 1, 'minimumCorrelation': 1} for c in ('left', 'right')]}

    def test_managed_album_archive_and_directory_diagnostics_preserve_automatic_export(self):
        from stemwise_media import enrichment_plan
        from stemwise_media import audio_library
        batch = review.batch
        self.row['refinedAudioComparison'] = self.bound({'trackId':10,**self.waveform()}) | {
            'status':'matched','matchedTrackId':10}
        data = {'rows':[self.row], 'libraryPolicy':{'includeAllRetainedFlac':True},'downloadPolicy':{}}
        (self.root/'catalogue.json').write_text(json.dumps(data),encoding='utf-8')
        (self.root/'audio/qobuz/album-archives').mkdir()
        (self.root/'audio/qobuz/unrelated-directory').mkdir()
        media = {path:path.read_bytes() for path in self.paths.values()}
        with patch.object(batch,'ROOT',self.root),patch.object(enrichment_plan,'ROOT',self.root), \
                patch.object(enrichment_plan,'STATE',self.root/'state'),patch('sys.stdout',new_callable=io.StringIO):
            batch.export(batch.read_catalogue(self.root/'catalogue.json'))
            self.assertEqual(audio_library.main(['plan']),0)
        selected = json.loads((self.root/'comparison-results/library-files.json').read_bytes())
        self.assertEqual(selected['playlist'][0]['trackId'],10)
        self.assertEqual(selected['catalogueSha256'],batch.file_sha256(self.root/'catalogue.json'))
        self.assertEqual(selected['invalidAlternatives'],[{'directory':'unrelated-directory','reason':'Invalid recording directory identity'}])
        self.assertEqual({path:path.read_bytes() for path in media},media)

    def save(self):
        (self.root/'comparison-results').mkdir(exist_ok=True)
        (self.root/'comparison-results/library-files.json').write_text(
            json.dumps({'flac': [{'trackId': i} for i in self.paths]}), encoding='utf-8')
        (self.root/'catalogue.json').write_text(json.dumps({'rows': [self.row]}), encoding='utf-8')

    def test_strict_negative_requires_broader_review_then_reuses_its_result(self):
        from scipy import signal
        audio = signal.resample_poly(np.random.default_rng(4103).normal(0, .12, (24*8000, 2)), 6, 1)
        reference = signal.resample_poly(audio, 100073, 100000, axis=0)
        self.row['refinedAudioComparison'] = self.bound({'trackId': 10, 'classification': 'needs_review',
            'reason': 'Distributed audio anchors disagree'})
        with patch.object(review.batch, 'decode', side_effect=lambda path, rate: reference if path == self.source else audio), \
                patch.object(review, 'probe', return_value={'codec_name': 'opus'}), \
                patch.object(review.batch, 'analyze_clock_drift', wraps=review.batch.analyze_clock_drift) as clock:
            result, measured = review.review_row(self.row, self.tracks[:1])
            self.assertEqual(measured, 1)
            self.assertTrue(review.confirmed(result['candidates'][0]), result)
            self.assertAlmostEqual(result['candidates'][0]['candidateTimeScale'], 1.00073, places=6)
            self.assertEqual(clock.call_count, 1)
            self.row['refinedAudioComparison'] = result
            second, measured = review.review_row(self.row, self.tracks[:1])
            self.assertEqual(measured, 0)
            self.assertEqual(second['candidates'], result['candidates'])
            self.assertEqual(clock.call_count, 1)

    def test_direct_compare_cli_runs_general_clock_and_rejects_unrelated_or_local_edit(self):
        from scipy import signal
        from stemwise_media import audio_library
        original = signal.resample_poly(np.random.default_rng(4103).normal(0, .12, (24*8000, 2)), 6, 1)
        reference = signal.resample_poly(original, 100073, 100000, axis=0)
        edited = original.copy()
        edited[10*48000:14*48000, 1] = 0
        unrelated = np.random.default_rng(89).normal(0, .1, original.shape)
        for candidate, expected in ((original, True), (edited, False), (unrelated, False)):
            with self.subTest(expected=expected), \
                    patch.object(audio_library, 'probe', return_value={'duration': 24, 'channels': 2}), \
                    patch.object(audio_library, 'decode', side_effect=lambda path, rate: (
                        reference if path == self.source else candidate)[::6 if rate == 8000 else 1]), \
                    redirect_stdout(io.StringIO()) as output:
                code = audio_library.main(['compare', str(self.source), str(self.paths[10])])
            result = json.loads(output.getvalue())
            self.assertEqual(result['sameRecordingSupported'], expected, result)
            self.assertEqual(code, 0 if expected else 1)
            if expected:
                self.assertEqual(result['classification'], 'matching_body_with_speed_change')
                self.assertFalse(result['lyricTimingTransferEstablished'])

    def test_rejected_proposal_preserves_current_prior_binding_but_not_changed_bytes(self):
        from stemwise_media import recording_equivalence as equivalence
        for changed in (False, True):
            with self.subTest(changed=changed):
                prior = {'trackId': 10, 'classification': 'same_performance_with_differences', 'checkedWindows': 29,
                    'supportedWindowFraction': 1, 'channelMedians': [1, 1],
                    'anchors': [{'correlation': 1, 'offsetSeconds': 0} for _ in range(3)]}
                self.row['recordingEquivalence'] = self.bound(prior, rate=8000)
                if changed:
                    self.paths[10].write_bytes(self.paths[10].read_bytes()+b'changed')
                    write_download(self.paths[10].parent, {'qobuzTrackId': 10, 'file': self.paths[10].name})
                self.save()
                with patch.object(sys, 'argv', ['recording_equivalence.py']), \
                        patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)), \
                        patch.object(review.batch, 'decode', return_value=np.zeros((1, 2))), \
                        patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}), \
                        redirect_stdout(io.StringIO()):
                    equivalence.main()
                saved = review.batch.read_catalogue(self.root/'catalogue.json')['rows'][0]
                self.assertEqual([c['trackId'] for c in review.equivalences(saved)], [] if changed else [10])
                self.assertEqual(review.needs_review(saved), changed)

    def test_missing_retained_audio_is_skipped_without_losing_healthy_peer_or_receipt(self):
        from stemwise_media import recording_equivalence as equivalence
        receipt = self.paths[20].parent/'download.json'
        original = receipt.read_bytes()
        self.paths[20].unlink()
        self.save()
        with patch.object(sys, 'argv', ['recording_equivalence.py']), \
                patch.object(equivalence, 'fingerprint', return_value=np.arange(400, dtype=np.uint32)) as fingerprint, \
                patch.object(review.batch, 'decode', return_value=np.zeros((1, 2))), \
                patch.object(equivalence, 'analyze_equivalence', return_value={'classification': 'not_supported'}), \
                redirect_stdout(io.StringIO()) as output:
            equivalence.main()
        summary = json.loads(output.getvalue().splitlines()[-1])
        self.assertEqual(summary['comparedPairs'], 1)
        self.assertEqual([v['trackId'] for v in summary['skippedEntries']], [20])
        self.assertEqual({call.args[0] for call in fingerprint.call_args_list}, {self.source, self.paths[10]})
        self.assertEqual(receipt.read_bytes(), original)

    def test_interrupt_retires_active_fingerprints_and_never_launches_queued_work(self):
        from stemwise_media import recording_equivalence as equivalence
        self.save()
        # Add enough queued sources to distinguish retirement from draining the queue.
        data = review.batch.read_catalogue(self.root/'catalogue.json')
        data['rows'] = [dict(self.row, position=i) for i in range(6)]
        (self.root/'catalogue.json').write_text(json.dumps(data), encoding='utf-8')
        started, retired = [], []
        both = threading.Event()
        def run(command, **kwargs):
            started.append(command)
            if len(started) == 2:
                both.set()
            callback = kwargs.get('cancelled')
            until = time.monotonic()+2
            while time.monotonic() < until:
                if callback and callback():
                    retired.append(command)
                    raise InterruptedError('cancelled')
                time.sleep(.005)
            return type('Result', (), {'stdout': np.arange(400, dtype=np.uint32).tobytes()})()
        def interrupt(futures):
            self.assertTrue(both.wait(1))
            raise KeyboardInterrupt('original interrupt')
            yield
        with patch.object(sys, 'argv', ['recording_equivalence.py']), \
                patch.object(equivalence, 'run_process_tree', side_effect=run), \
                patch.object(equivalence, 'as_completed', side_effect=interrupt):
            began = time.monotonic()
            with self.assertRaisesRegex(KeyboardInterrupt, 'original interrupt'):
                equivalence.main()
            self.assertLess(time.monotonic()-began, 1)
        self.assertEqual(len(started), 2)
        self.assertEqual(len(retired), 2)

    def test_reference_retry_failure_preserves_match_selection_and_only_retries_pending_candidate(self):
        from stemwise_media import compare
        from types import SimpleNamespace
        match = self.waveform() | {'trackId': 10, 'classification': 'matching_body_with_extra_audio',
                                  'body': {'channels': self.waveform()['channels']}}
        self.row['refinedAudioComparison'] = self.bound(match) | {
            'status': 'matching_body_with_extra_audio', 'matchedTrackId': 10}
        self.row['refinedAudioComparison']['candidates'].append({'trackId': 20, 'classification': 'error'})
        self.save()
        data = review.batch.read_catalogue(self.root/'catalogue.json')
        with patch.object(review.batch, 'decode', wraps=compare.decode), \
                patch.object(compare, 'probe', return_value={'duration': 24}), \
                patch.object(compare.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(review.batch.compare_rows(data, refine=True), 1)
        saved = review.batch.read_catalogue(self.root/'catalogue.json')
        row = saved['rows'][0]
        self.assertEqual([c['trackId'] for c in review.current_matches(row)], [10])
        self.assertEqual(review.batch.build_library(saved)['playlist'][0]['source'], 'qobuz')
        self.assertIn('Insufficient scratch', row['refinedAudioComparison']['candidates'][1]['reason'])
        with patch.object(review.batch, 'decode', return_value=np.zeros((1, 2))) as decode, \
                patch.object(review.batch, 'analyze_waveforms', return_value={'classification': 'not_matched'}), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(review.batch.compare_rows(saved, refine=True), 0)
        self.assertEqual([call.args[0] for call in decode.call_args_list], [self.source, self.paths[20]])
        saved['rows'][0]['refinedAudioComparison']['candidates'][1]['classification'] = 'error'
        review.batch.write_json(self.root/'catalogue.json', saved)
        def changed_reference(path, rate):
            self.source.write_bytes(b'changed reference')
            raise OSError('Reference changed during retry')
        with patch.object(review.batch, 'decode', side_effect=changed_reference), redirect_stdout(io.StringIO()):
            self.assertEqual(review.batch.compare_rows(saved, refine=True), 1)
        rejected = review.batch.read_catalogue(self.root/'catalogue.json')
        self.assertEqual(review.current_matches(rejected['rows'][0]), [])
        self.assertEqual(review.batch.build_library(rejected)['playlist'][0]['source'], 'youtube')

    def test_missing_or_unreadable_reference_after_decode_failure_persists_explicit_errors(self):
        for unreadable in (False, True):
            with self.subTest(unreadable=unreadable):
                self.source.write_bytes(b'reference')
                self.row['refinedAudioComparison'] = self.bound(self.waveform() | {'trackId': 10}) | {
                    'status': 'matched', 'matchedTrackId': 10}
                self.row['refinedAudioComparison']['candidates'].append({'trackId': 20, 'classification': 'error'})
                self.save()
                data = review.batch.read_catalogue(self.root/'catalogue.json')
                receipt = self.source.parent/'download.json'
                retained_receipt = receipt.read_bytes()
                retained_candidate = self.paths[10].read_bytes()
                def unavailable_reference(path, rate):
                    self.assertEqual(path, self.source)
                    path.unlink()
                    if unreadable:
                        path.mkdir()
                    raise OSError('Reference decode failed')
                try:
                    with patch.object(review.batch, 'decode', side_effect=unavailable_reference), redirect_stdout(io.StringIO()):
                        self.assertEqual(review.batch.compare_rows(data, refine=True), 1)
                    saved = review.batch.read_catalogue(self.root/'catalogue.json')['rows'][0]
                    self.assertEqual(saved['refinedAudioComparison']['status'], 'unconfirmed')
                    self.assertEqual([(c['trackId'], c['classification'], c['reason'])
                        for c in saved['refinedAudioComparison']['candidates']], [(20, 'error', 'Reference decode failed')])
                    self.assertEqual(review.current_matches(saved), [])
                    self.assertEqual(receipt.read_bytes(), retained_receipt)
                    self.assertEqual(self.paths[10].read_bytes(), retained_candidate)
                finally:
                    if self.source.is_dir():
                        self.source.rmdir()


class RecordingReviewTests(unittest.TestCase):
    def test_batched_review_preserves_owner_edits_and_running_worker_snapshots(self):
        from concurrent.futures import as_completed
        from stemwise_media.catalogue_store import read_catalogue, commit_catalogue
        rows = [{'position': i, 'videoId': str(i), 'title': 'Original', 'candidates': []}
                for i in range(1, 18)]
        editing, committed = threading.Event(), threading.Event()
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            path = root/'catalogue.json'
            commit_catalogue(path, {'rows': rows})
            def work(row, tracks, **kwargs):
                if row['position'] == 17:
                    owner = read_catalogue(path)
                    owner['rows'][-1]['title'] = 'Owner edit'
                    commit_catalogue(path, owner)
                    editing.set()
                    self.assertTrue(committed.wait(3), 'Main commit never released the worker')
                    self.assertEqual(row['title'], 'Original')
                return {'status': 'not_checked', 'candidates': []}, 0
            def complete(futures):
                self.assertTrue(editing.wait(3))
                yield from as_completed(futures)
            def save(destination, value):
                commit_catalogue(destination, value)
                committed.set()
            with patch.object(review.batch, 'ROOT', root), \
                    patch.object(review, 'review_row', side_effect=work), \
                    patch.object(review, 'as_completed', side_effect=complete), \
                    patch.object(review.batch, 'write_json', side_effect=save) as writes, \
                    patch.object(review.batch, 'export'), patch.object(review, 'export_review', return_value={}), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(review.main(), 0)
            self.assertEqual(writes.call_count, 2)
            saved = read_catalogue(path)
            self.assertEqual(saved['rows'][-1]['title'], 'Owner edit')
            self.assertTrue(all(row['refinedAudioComparison']['status'] == 'not_checked' for row in saved['rows']))

    def test_interruption_stops_queued_rows_and_retires_active_comparisons(self):
        import threading
        import time
        from concurrent.futures import as_completed
        import stemwise_media.bounded_pcm as pcm
        rows = [{'position': i, 'videoId': str(i), 'candidates': []} for i in range(8)]
        started = []
        both = threading.Event()
        def active(row, tracks, **kwargs):
            started.append(row['position'])
            if len(started) == 2:
                both.set()
            limit = time.monotonic() + 2
            while time.monotonic() < limit:
                pcm.check_budget()
                time.sleep(.01)
            self.fail('Cancelled comparison kept running')
        def interrupted(futures):
            self.assertTrue(both.wait(2))
            raise KeyboardInterrupt
            yield
        with patch.object(review.batch, 'read_catalogue', return_value={'rows': rows}), \
                patch.object(review, 'review_row', side_effect=active), \
                patch.object(review, 'as_completed', side_effect=interrupted), \
                patch.object(review.batch, 'export') as export:
            began = time.monotonic()
            with self.assertRaises(KeyboardInterrupt):
                review.main()
            self.assertLess(time.monotonic()-began, 1)
        self.assertEqual(len(started), 2)
        export.assert_not_called()

    def test_comparison_budget_cancels_a_native_process_and_its_child(self):
        import threading
        import time
        import stemwise_media.bounded_pcm as pcm
        from stemwise_engine.provider_operations import ProviderCancelled
        from stemwise_engine.common import run_process_tree
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            pid_file = Path(name)/'native.pid'
            command = [sys.executable, '-c',
                'import subprocess,time,pathlib,sys; p=subprocess.Popen([sys.executable,"-c","import time;time.sleep(20)"]); '
                'pathlib.Path(sys.argv[1]).write_text(str(p.pid));time.sleep(20)', str(pid_file)]
            cancelled = threading.Event()
            def cancel_after_start():
                limit=time.monotonic()+3
                while not pid_file.exists() and time.monotonic()<limit:
                    time.sleep(.01)
                cancelled.set()
            timer = threading.Thread(target=cancel_after_start)
            timer.start()
            try:
                with pcm.comparison_operation(cancelled=cancelled.is_set):
                    with self.assertRaises(ProviderCancelled):
                        run_process_tree(command, timeout=5, cancelled=pcm.check_budget)
            except ProviderCancelled:
                pass
            finally:
                timer.join(timeout=4)
                self.assertFalse(timer.is_alive())
            self.assertTrue(pid_file.exists(), 'The native child must actually start')
            self.assertFalse(Path('/proc/'+pid_file.read_text()).exists(), 'Retire native descendants before returning')

    def test_candidate_inventory_and_real_exports_verify_each_repeated_track_once(self):
        import io
        from contextlib import redirect_stdout
        track={'id':10,'title':'Song','artist':'Artist','album':'Album'}
        rows=[{'position':i,'videoId':video,'title':'Song','candidates':[dict(track)],
               'metadataSelection':{'status':'selected_candidate','trackId':10,'basis':'metadata'}}
              for i,video in enumerate(('abcdefghijk','lmnopqrstuv'),1)]
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            root=Path(name)
            base=root/'audio/qobuz/10';base.mkdir(parents=True)
            audio=base/'song.flac';audio.write_bytes(b'fixture')
            write_download(base,{'qobuzTrackId':10,'file':audio.name})
            for row in rows:
                source=root/'audio/youtube'/row['videoId'];source.mkdir(parents=True)
                (source/'source.opus').write_bytes(b'retained reference')
                write_download(source,{'videoId':row['videoId'],'file':'source.opus'})
            (root/'catalogue.json').write_text(json.dumps({'rows':rows,'downloadPolicy':{},'libraryPolicy':{'includeAllRetainedFlac':True}}),encoding='utf-8')
            with patch.object(review.batch,'ROOT',root), \
                    patch.object(review.batch,'qobuz_file',wraps=review.batch.qobuz_file) as verify, \
                    patch.object(review,'review_row',return_value=({'status':'not_checked','candidates':[]},0)), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(review.main(),0)
            self.assertEqual([call.args[0] for call in verify.call_args_list],[10])
            library=json.loads((root/'comparison-results/library-files.json').read_text(encoding='utf-8'))
            self.assertEqual([item['trackId'] for item in library['flac']],[10])
            self.assertTrue((root/'comparison-results/recording-matches.csv').is_file())
            self.assertTrue((root/'comparison-results/review-index.json').is_file())

    def setUp(self):
        self.work = ROOT / 'Test suite/work'
        self.work.mkdir(exist_ok=True)

    def test_equivalence_respects_row_rejection_without_rejecting_another_row(self):
        import copy
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            root = Path(name)
            source, candidate = root/'source.opus', root/'candidate.flac'
            source.write_bytes(b'source'); candidate.write_bytes(b'candidate')
            result = {'trackId': 10, 'classification': 'same_performance_with_differences',
                'checkedWindows': 29, 'supportedWindowFraction': 1, 'channelMedians': [1, 1],
                'anchors': [{'correlation': 1, 'offsetSeconds': 0} for _ in range(3)],
                'candidateFile': candidate.name, 'candidateSha256': review.batch.file_sha256(candidate)}
            row = {'position': 1, 'videoId': 'abcdefghijk', 'rejectedTrackIds': [10],
                'recordingEquivalence': {'sampleRate': 8000, 'recipeVersion': 1,
                'referenceFile': source.name, 'referenceSha256': review.batch.file_sha256(source),
                'candidates': [result]}}
            accepted = copy.deepcopy(row); accepted.pop('rejectedTrackIds'); accepted['position'] = 2
            with patch.object(review.batch, 'ROOT', root), \
                    patch.object(review.batch, 'source_file', return_value=source), \
                    patch.object(review.batch, 'qobuz_file', return_value=candidate):
                self.assertEqual(review.equivalences(row), [])
                self.assertTrue(review.needs_review(row))
                self.assertEqual(review.equivalences(accepted), [result])
                self.assertEqual(review.handled_flac({'rows': [row]}), set())
                self.assertEqual(review.handled_flac({'rows': [row, accepted]}), {10})

    def test_report_render_failure_preserves_both_previous_reports(self):
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            root = Path(name); output = root/'comparison-results'; output.mkdir()
            (output/'library-files.json').write_text('{"flac": []}', encoding='utf-8')
            reports = {'recording-matches.csv': b'complete prior CSV', 'unmatched-recordings.txt': b'complete prior text'}
            for filename, content in reports.items(): (output/filename).write_bytes(content)
            data = {'rows': [{'position': i, 'videoId': 'abcdefghijk', 'title': 'Song'} for i in (1, 2)]}
            with patch.object(review.batch, 'ROOT', root), patch.object(review, 'problem', side_effect=['fine', ValueError('second row failed')]):
                with self.assertRaisesRegex(ValueError, 'second row failed'): review.export_review(data)
            self.assertEqual({filename: (output/filename).read_bytes() for filename in reports}, reports)

    def test_strict_cache_reuses_only_exact_current_candidate_and_reference_bytes(self):
        import hashlib
        for changed in ('none', 'candidate-path', 'candidate-bytes', 'reference-bytes'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory(dir=self.work) as name:
                root = Path(name)
                reference, prior, candidate = root / 'reference.opus', root / 'prior.flac', root / 'current.flac'
                reference.write_bytes(b'reference')
                prior.write_bytes(b'candidate')
                candidate.write_bytes(b'candidate')
                path = prior if changed != 'candidate-path' else candidate
                cached = {'trackId':10, 'candidateFile':prior.name,
                    'candidateSha256':hashlib.sha256(b'candidate').hexdigest(),
                    'classification':'strong_recording_match','fullContentSupported':True,
                    'channels':[{'channel':channel,'windows':1,'minimumCorrelation':1} for channel in ('left','right')]}
                row = {'videoId':'abcdefghijk','refinedAudioComparison':{
                    'sampleRate':48000,'recipeVersion':1,'referenceFile':reference.name,
                    'referenceSha256':hashlib.sha256(b'reference').hexdigest(), 'candidates':[cached]}}
                if changed == 'candidate-bytes': prior.write_bytes(b'changed')
                if changed == 'reference-bytes': reference.write_bytes(b'changed')
                with patch.object(review.batch, 'ROOT', root), \
                        patch.object(review.batch, 'source_file', return_value=reference), \
                        patch.object(review.batch, 'qobuz_file', return_value=path), \
                        patch.object(review.batch, 'decode', return_value=np.zeros((8,2))) as decode, \
                        patch.object(review.batch, 'analyze_waveforms', return_value={'classification':'unconfirmed'}), \
                        patch.object(review, 'probe', return_value={'codec_name':'opus'}):
                    result, measured = review.review_row(row,[{'id':10}])
                self.assertEqual(measured, 0 if changed == 'none' else 1)
                self.assertEqual(decode.call_count, 0 if changed == 'none' else 2)

    def test_failed_candidates_do_not_read_irrelevant_download_bindings(self):
        with tempfile.TemporaryDirectory(dir=self.work) as temporary:
            root = Path(temporary)
            source = root / 'audio/youtube/abcdefghijk'
            source.mkdir(parents=True)
            (source / 'audio.webm').write_bytes(b'audio')
            write_download(source, {'videoId': 'abcdefghijk', 'file': 'audio.webm'})
            candidate = root / 'audio/qobuz/10'
            candidate.mkdir(parents=True)
            write_download(candidate, {'qobuzTrackId': 99, 'file': 'wrong.flac'})
            result = {'trackId': 10, 'classification': 'not_supported'}
            row = {'videoId': 'abcdefghijk', 'recordingEquivalence': {'sampleRate': 8000,
                   'referenceFile': 'audio/youtube/abcdefghijk/audio.webm', 'referenceSha256': review.batch.file_sha256(source / 'audio.webm'), 'recipeVersion': 1, 'candidates': [result]}}
            with patch.object(review.batch, 'ROOT', root):
                self.assertEqual(review.equivalences(row), [])
                result.update(classification='same_performance_with_differences', checkedWindows=20,
                              supportedWindowFraction=1, channelMedians=[.99, .99],
                              anchors=[{'offsetSeconds': 0, 'correlation': .99}] * 3)
                with self.assertRaises(ValueError):
                    review.equivalences(row)

    def test_only_exact_structured_owner_decisions_resolve_current_review(self):
        for note in ('accepted مقبولة', 'احفظ كل النسخ', 'هل هذه مقبولة؟'):
            row = {'position': 1, 'videoId': 'source',
                'ownerAudioSelection': {'source': 'youtube', 'file': 'retained.opus'},
                'ownerRecordingNote': {'note': note}}
            self.assertFalse(review.owner_selection_is_current(row))
            with patch.object(review.batch, 'source_file', return_value=None):
                self.assertTrue(review.needs_review(row))
            row['ownerRecordingNote'].update(decisionFormatVersion=1, identity='youtube-1-source',
                decision='keep_youtube')
            self.assertTrue(review.owner_selection_is_current(row))
            row['ownerRecordingNote']['identity'] = 'youtube-2-source'
            self.assertFalse(review.owner_selection_is_current(row))

    def test_unavailable_report_distinguishes_rejection_compilation_and_failed_source(self):
        with tempfile.TemporaryDirectory(dir=self.work) as temporary:
            root = Path(temporary)
            source = root / 'audio/youtube/source'
            source.mkdir(parents=True)
            write_download(source, {'status': 'failed', 'error': 'Video unavailable'})
            missing = {'videoId': 'source', 'refinedAudioComparison': {'unavailableReason': 'Source audio unavailable'}}
            rejected = {'rejectedTrackIds': [10], 'refinedAudioComparison': {'unavailableReason': 'No retained candidate'}}
            compilation = {'metadataSelection': {'basis': 'multi_song_compilation_or_concert_not_one_catalogue_track'},
                           'refinedAudioComparison': {'unavailableReason': 'No retained candidate'}}
            searched = {'flacNameMatch': {'note': 'الموجود بهذا الاسم لفنان آخر.'},
                        'refinedAudioComparison': {'unavailableReason': 'No retained candidate'}}
            with patch.object(review.batch, 'ROOT', root):
                self.assertIn('Video unavailable', review.problem(missing, {}))
                self.assertIn('رفضها المالك', review.problem(rejected, {}))
                self.assertIn('ليست أغنية واحدة', review.problem(compilation, {}))
                self.assertIn('لفنان آخر', review.problem(searched, {}))

    def test_unmatched_report_names_the_failed_flac_and_its_actual_difference(self):
        candidate = {'trackId': 10, 'classification': 'needs_review',
                     'reason': 'Dense waveform comparison differs',
                     'anchors': [{'correlation': .99}],
                     'channels': [{'channel': c, 'windows': 26, 'minimumCorrelation': .5,
                                   'medianCorrelation': .99} for c in ('left', 'right')],
                     'differingWindows': [{'referenceStartSeconds': 12, 'referenceEndSeconds': 14}]}
        row = {'position': 1, 'videoId': 'source', 'title': 'YouTube Song',
               'refinedAudioComparison': {'sampleRate': 48000, 'status': 'unconfirmed', 'candidates': [candidate]}}
        with tempfile.TemporaryDirectory(dir=self.work) as temporary:
            root = Path(temporary)
            output = root / 'comparison-results'
            output.mkdir()
            (output / 'library-files.json').write_text(json.dumps({'flac': [
                {'trackId': 10, 'artist': 'Singer', 'title': 'FLAC Song', 'version': 'Studio', 'album': 'Album'}]}))
            with patch.object(review.batch, 'ROOT', root):
                summary = review.export_review({'rows': [row]})
            with (output / 'recording-matches.csv').open(encoding='utf-8-sig') as stream:
                result = next(csv.DictReader(stream))
            self.assertIn('FLAC Song', result['المشكلة أو الاختلاف'])
            self.assertIn('12.00–14.00', result['المشكلة أو الاختلاف'])
            self.assertEqual(summary['unmatchedFlacFiles'], 1)
            report = (output / 'unmatched-recordings.txt').read_text(encoding='utf-8')
            self.assertIn('YouTube Song', report)
            self.assertIn('FLAC Song', report)
            self.assertIn('12.00–14.00', report)

    def test_fixed_timing_with_low_waveform_scores_is_not_called_a_different_performance(self):
        candidate = {'reason': 'Distributed audio anchors disagree',
                     'anchors': [{'offsetSeconds': .148, 'correlation': score} for score in (.939, .945, .946)]}
        explanation = review.candidate_problem(candidate)
        self.assertIn('التوقيت ثابت', explanation)
        self.assertIn('أقل من حد التأكيد', explanation)
        self.assertIn('لا يثبت أداءً مختلفاً', explanation)

    def test_title_correction_adds_real_candidates_without_changing_owner_choice(self):
        tracks = {10: {'id': 10, 'title': 'Wrong', 'artist': 'Singer'},
                  20: {'id': 20, 'title': 'Correct', 'artist': 'Singer'},
                  30: {'id': 30, 'title': 'Correct', 'artist': 'Singer', 'version': 'Live'},
                  40: {'id': 40, 'title': 'Correct', 'artist': 'Cover singer'},
                  50: {'id': 50, 'title': 'Correct', 'artist': 'Singer'}}
        owner = {'source': 'youtube'}
        row = {'candidates': [tracks[10]], 'flacNameMatch': {'trackIds': [20]},
               'rejectedTrackIds': [10, 50], 'ownerAudioSelection': owner}
        self.assertEqual({t['id'] for t in review.review_tracks(row, tracks)}, {20, 30})
        self.assertEqual(row['ownerAudioSelection'], owner)

    def test_old_low_rate_success_cannot_hide_a_changed_right_channel_at_48khz(self):
        with tempfile.TemporaryDirectory(dir=self.work) as temporary:
            root = Path(temporary)
            rng = np.random.default_rng(703)
            audio = rng.integers(-9000, 9000, (26 * 48000, 2), dtype=np.int16)
            other = audio.copy()
            other[12 * 48000:13 * 48000, 1] = 0
            for directory, samples, identity in [('audio/youtube/abcdefghijk', audio, {'videoId': 'abcdefghijk'}),
                                                  ('audio/qobuz/10', other, {'qobuzTrackId': 10})]:
                base = root / directory
                base.mkdir(parents=True)
                with wave.open(str(base / 'audio.wav'), 'wb') as output:
                    output.setparams((2, 2, 48000, 0, 'NONE', 'not compressed'))
                    output.writeframes(samples.astype('<i2').tobytes())
                write_download(base, identity | {'file': 'audio.wav'})
            row = {'videoId': 'abcdefghijk', 'quickAudioComparison': {'status': 'matched', 'sampleRate': 16000},
                   'candidates': [{'id': 10, 'title': 'Song', 'artist': 'Singer'}]}
            with patch.object(review.batch, 'ROOT', root):
                result, measured = review.review_row(row, row['candidates'])
            self.assertEqual(measured, 1)
            self.assertEqual(result['sampleRate'], 48000)
            self.assertEqual(result['status'], 'unconfirmed')
            candidate = result['candidates'][0]
            self.assertEqual(candidate['reason'], 'Dense waveform comparison differs')
            self.assertTrue(any(region['referenceStartSeconds'] <= 12 < region['referenceEndSeconds']
                                for region in candidate['differingWindows']))
            self.assertEqual(row['quickAudioComparison']['status'], 'matched')

    def test_reports_admit_only_current_byte_bound_strict_measurements(self):
        import copy
        for changed in ('none', 'reference-bytes', 'candidate-bytes', 'missing-candidate',
                        'missing-reference', 'rejected', 'bad-receipt', 'missing-binding', 'bad-measurement'):
            with self.subTest(changed=changed), tempfile.TemporaryDirectory(dir=self.work) as name:
                root = Path(name)
                reference = root / 'audio/youtube/abcdefghijk/audio.webm'
                candidate = root / 'audio/qobuz/20/audio.flac'
                for path, identity in ((reference, {'videoId': 'abcdefghijk'}),
                                       (candidate, {'qobuzTrackId': 20})):
                    path.parent.mkdir(parents=True)
                    path.write_bytes(b'retained audio')
                    write_download(path.parent, identity | {'file': path.name})
                result = {'sampleRate': 48000, 'recipeVersion': 1, 'status': 'matched',
                    'referenceFile': reference.relative_to(root).as_posix(),
                    'referenceSha256': review.batch.file_sha256(reference), 'candidates': [
                        {'trackId': 20, 'classification': 'strong_recording_match',
                         'fullContentSupported': True, 'candidateFile': candidate.relative_to(root).as_posix(),
                         'candidateSha256': review.batch.file_sha256(candidate),
                         'channels': [{'channel': channel, 'windows': 26, 'minimumCorrelation': .99}
                                      for channel in ('left', 'right')]}]}
                row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song',
                       'refinedAudioComparison': result}
                if changed.endswith('-bytes'):
                    (reference if changed.startswith('reference') else candidate).write_bytes(b'changed audio')
                elif changed.startswith('missing-') and changed != 'missing-binding':
                    (reference if changed == 'missing-reference' else candidate).unlink()
                elif changed == 'rejected':
                    row['rejectedTrackIds'] = [20]
                    write_download(candidate.parent, {'qobuzTrackId': 20, 'file': candidate.name,
                                                     'status': 'owner_rejected'})
                elif changed == 'bad-receipt':
                    (candidate.parent / 'download.json').write_bytes(b'{')
                elif changed == 'missing-binding':
                    result['candidates'][0].pop('candidateSha256')
                elif changed == 'bad-measurement':
                    result['candidates'][0].pop('channels')
                before = copy.deepcopy(row)
                data = {'rows': [row]}
                output = root / 'comparison-results'
                output.mkdir()
                (output / 'library-files.json').write_text(json.dumps({'flac': [
                    {'trackId': 20, 'title': 'Current FLAC', 'artist': 'Singer', 'album': 'Album'}]}), encoding='utf-8')
                with patch.object(review.batch, 'ROOT', root):
                    expected = {20} if changed == 'none' else set()
                    self.assertEqual(review.paired_tracks(data), expected)
                    self.assertEqual(review.handled_flac(data), expected)
                    self.assertEqual(review.needs_review(row), changed != 'none')
                    summary = review.export_review(data)
                self.assertEqual(summary['pairedFlacFiles'], len(expected))
                self.assertEqual(summary['unmatchedYouTubeRows'], int(changed != 'none'))
                with (output / 'recording-matches.csv').open(encoding='utf-8-sig') as stream:
                    record = next(csv.DictReader(stream))
                self.assertEqual(bool(record['FLAC المتطابق صوتياً']), changed == 'none')
                if changed != 'none':
                    self.assertNotIn('اجتاز التسجيل', record['المشكلة أو الاختلاف'])
                    self.assertNotEqual(record['نتيجة التسجيل'], 'matched')
                self.assertEqual(row, before)

    def test_orphan_inventory_accepts_any_measured_pair_but_not_owner_buttons(self):
        with tempfile.TemporaryDirectory(dir=self.work) as name:
            root = Path(name)
            reference, candidate = root / 'reference.opus', root / 'candidate.flac'
            reference.write_bytes(b'reference')
            candidate.write_bytes(b'candidate')
            data = {'rows': [{'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song',
                'ownerAudioSelection': {'source': 'qobuz', 'trackId': 10},
                'refinedAudioComparison': {'sampleRate': 48000, 'recipeVersion': 1,
                    'referenceFile': reference.name, 'referenceSha256': review.batch.file_sha256(reference),
                    'status': 'unconfirmed', 'candidates': [
                        {'trackId': 10, 'classification': 'not_matched'},
                        {'trackId': 20, 'classification': 'strong_recording_match', 'fullContentSupported': True,
                         'candidateFile': candidate.name, 'candidateSha256': review.batch.file_sha256(candidate),
                         'channels': [{'channel': c, 'windows': 26, 'minimumCorrelation': .99} for c in ('left', 'right')]},
                        {'trackId': 30, 'classification': 'strong_recording_match', 'fullContentSupported': True}]}}]}
            with patch.object(review.batch, 'ROOT', root), \
                    patch.object(review.batch, 'source_file', return_value=reference), \
                    patch.object(review.batch, 'qobuz_file', return_value=candidate):
                self.assertEqual(review.paired_tracks(data), {20})


if __name__ == '__main__':
    unittest.main()
