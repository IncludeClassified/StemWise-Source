"""Quick comparison must abstain on competing matches and keep extras explicit."""
import sys
from qobuz_fixtures import flac_bytes, write_download
import unittest
import json
import tempfile
import wave
import csv
import numpy as np
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
from stemwise_media import quick_batch as batch


class QuickBatchTests(unittest.TestCase):
    def test_damaged_provider_diagnostic_preserves_reference_failure_and_cancellation(self):
        import io
        from contextlib import redirect_stderr
        from test_private_diagnostics import damaged_diagnostic_target
        from stemwise_engine.provider_operations import ProviderError, ProviderDeadline, ProviderCancelled
        from stemwise_engine.workflow_context import telemetry_job_root
        for kind, retained in (('mismatch', False), ('directory', False), ('mismatch', True), ('directory', True)):
            for error in (ProviderError('DownloadError'), ProviderDeadline('deadline'), ProviderCancelled('cancelled')):
                with self.subTest(kind=kind, retained=retained, error=type(error).__name__), \
                        tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    workspace = Path(name)
                    library = workspace/'PC side/qobuz-comparison'
                    source = library/'audio/youtube/abcdefghijk/source.webm'
                    receipt = source.parent/'download.json'
                    if retained:
                        source.parent.mkdir(parents=True)
                        source.write_bytes(b'preserved incomplete source')
                        receipt.write_bytes(b'{"status":"saved","owner":"preserved"}')
                    stderr = io.StringIO()
                    with patch.object(batch, 'ROOT', library), \
                            patch.object(batch, 'source_file', return_value=source if retained else None), \
                            patch.object(batch, 'require_youtube_audio_allowed'), \
                            patch('stemwise_engine.youtube.extract_youtube_info', side_effect=error), \
                            damaged_diagnostic_target(kind) as targets, redirect_stderr(stderr):
                        if isinstance(error, ProviderCancelled):
                            with self.assertRaises(ProviderCancelled) as raised:
                                batch.download_reference({'videoId': 'abcdefghijk', 'position': 1})
                            self.assertIs(raised.exception, error)
                        else:
                            failure = batch.download_reference({'videoId': 'abcdefghijk', 'position': 1})
                            self.assertEqual(failure['status'], 'failed')
                            self.assertEqual(failure['error'], batch.safe_error(error))
                            self.assertEqual(failure.get('retryable', False), isinstance(error, ProviderDeadline))
                            report = telemetry_job_root(workspace, 'youtube:abcdefghijk')/'reference-download/failure.json'
                            self.assertEqual(json.loads(report.read_bytes()), {'videoId': 'abcdefghijk', **failure})
                    if retained:
                        self.assertEqual(source.read_bytes(), b'preserved incomplete source')
                        self.assertEqual(receipt.read_bytes(), b'{"status":"saved","owner":"preserved"}')
                    elif isinstance(error, ProviderCancelled):
                        self.assertFalse(receipt.exists())
                    else:
                        self.assertEqual(json.loads(receipt.read_bytes())['error'], batch.safe_error(error))
                    self.assertIn('reference-private-diagnostic-retention-failed', stderr.getvalue())
                    self.assertEqual(len(targets), 1)

    def test_owner_selected_export_preserves_invalid_superseded_metadata_audio(self):
        import io
        from contextlib import redirect_stdout
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            for track in (10, 20, 30):
                path = root/f'audio/qobuz/{track}/audio.flac'
                path.parent.mkdir(parents=True)
                path.write_bytes(flac_bytes(track//10))
                write_download(path.parent, {'qobuzTrackId': track, 'file': path.name})
            rows = [{'position': position, 'videoId': video, 'title': f'Song {position}',
                'candidates': [{'id': track, 'title': f'Candidate {track}', 'artist': 'Artist', 'album': ''}
                    for track in tracks],
                'metadataSelection': {'status': 'selected_candidate', 'trackId': tracks[0], 'basis': 'metadata'}}
                for position, video, tracks in ((1, 'abcdefghijk', [10, 20]), (2, 'lmnopqrstuv', [30]))]
            selected = {'source': 'qobuz', 'trackId': 20, 'file': 'audio/qobuz/20/audio.flac'}
            rows[0]['ownerAudioSelection'] = selected
            invalid = root/'audio/qobuz/10/audio.flac'
            invalid.write_bytes(flac_bytes(5))
            inputs = {path: path.read_bytes() for path in (root/'audio').rglob('*') if path.is_file()}
            path = root/'catalogue.json'
            path.write_text(json.dumps({'rows': rows, 'downloadPolicy': {},
                'libraryPolicy': {'includeAllRetainedFlac': True}}), encoding='utf-8')
            with patch.object(batch, 'ROOT', root), redirect_stdout(io.StringIO()):
                batch.export(batch.read_catalogue(path))
            saved = json.loads(path.read_bytes())
            self.assertEqual(saved['rows'][0]['download']['trackId'], 20)
            self.assertEqual(saved['identifiedUndownloadedUniqueTracks'], 0)
            output = root/'comparison-results'
            library = json.loads((output/'library-files.json').read_bytes())
            self.assertEqual(library['playlist'][0]['file'], selected['file'])
            self.assertEqual({track['trackId'] for track in library['flac']}, {20, 30})
            self.assertEqual(library['invalidAlternatives'][0]['trackId'], 10)
            with (output/'results.csv').open(encoding='utf-8-sig') as stream:
                report = list(csv.DictReader(stream))
            self.assertEqual(len(report), 2)
            self.assertEqual(report[0]['ملف المكتبة'], selected['file'])
            self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)

    def test_optional_export_admission_propagates_fatal_and_cancelled_work(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        from stemwise_media.catalogue_store import Catalogue
        for error in (WorkerRetirementError('unretired admission worker'), ProviderCancelled('cancelled')):
            with self.subTest(error=type(error).__name__), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                source = root/'audio/youtube/abcdefghijk/source.webm'
                source.parent.mkdir(parents=True); source.write_bytes(b'original')
                write_download(source.parent, {'videoId': 'abcdefghijk', 'file': source.name})
                native = root/'audio/qobuz/10'; native.mkdir(parents=True)
                data = Catalogue({'rows': [{'position': 1, 'videoId': 'abcdefghijk', 'candidates': [{'id': 10}],
                    'metadataSelection': {'status': 'deferred', 'basis': 'review'}}], 'downloadPolicy': {}})
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'qobuz_file', side_effect=error):
                    with self.assertRaises(type(error)) as raised: batch.export(data)
                self.assertIs(raised.exception, error)
                self.assertFalse((root/'comparison-results/review-index.json').exists())
                self.assertEqual(source.read_bytes(), b'original')

    def test_optional_supported_equivalence_cannot_block_export(self):
        from stemwise_media.catalogue_store import Catalogue
        for include_all in (False, True):
            for listed in (False, True):
                with self.subTest(include_all=include_all, listed=listed), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    root = Path(name)
                    source = root/'audio/youtube/abcdefghijk/source.webm'
                    native = root/'audio/qobuz/10/audio.flac'
                    source.parent.mkdir(parents=True); native.parent.mkdir(parents=True)
                    source.write_bytes(b'original'); native.write_bytes(flac_bytes())
                    write_download(source.parent, {'videoId': 'abcdefghijk', 'file': source.name})
                    write_download(native.parent, {'qobuzTrackId': 10, 'file': native.name})
                    candidate = {'trackId': 10, 'classification': 'same_performance_with_differences',
                        'checkedWindows': 20, 'supportedWindowFraction': 1, 'channelMedians': [.99, .99],
                        'anchors': [{'offsetSeconds': 0, 'correlation': .99}]*3,
                        'candidateFile': native.relative_to(root).as_posix(), 'candidateSha256': batch.file_sha256(native)}
                    row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Before',
                        'candidates': [{'id': 10}] if listed else [],
                        'metadataSelection': {'status': 'deferred', 'basis': 'review'},
                        'recordingEquivalence': {'sampleRate': 8000, 'recipeVersion': 1,
                            'referenceFile': source.relative_to(root).as_posix(),
                            'referenceSha256': batch.file_sha256(source), 'candidates': [candidate]}}
                    data = Catalogue({'rows': [row], 'downloadPolicy': {},
                        'libraryPolicy': {'includeAllRetainedFlac': include_all}})
                    with patch.object(batch, 'ROOT', root):
                        batch.export(data)
                        native.write_bytes(flac_bytes(1))
                        row = data['rows'][0]
                        row['title'] = 'After'
                        inputs = {path: path.read_bytes() for path in (source, native, source.parent/'download.json', native.parent/'download.json')}
                        batch.export(data)
                        output = root/'comparison-results'
                        self.assertIn('After', (output/'reviews/abcdefghijk/details.txt').read_text(encoding='utf-8'))
                        self.assertEqual(batch.build_library(data)['playlist'][0]['performanceTrackIds'], [])
                        self.assertEqual(batch.build_library(data)['invalidAlternatives'][0]['trackId'], 10)
                        self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)
                        reports = {path: path.read_bytes() for path in output.rglob('*') if path.is_file() and path.suffix != '.lock'}
                        row['ownerAudioSelection'] = {'source': 'qobuz', 'trackId': 10, 'file': native.relative_to(root).as_posix()}
                        with self.assertRaises(ValueError): batch.export(data)
                        self.assertEqual({path: path.read_bytes() for path in reports}, reports)

    def test_corrupt_retained_reference_is_preserved_and_replaced_once(self):
        import soundfile as sf
        for corrupt in (False, True):
            with self.subTest(corrupt=corrupt), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                source = root/'audio/youtube/abcdefghijk/source.wav'
                source.parent.mkdir(parents=True)
                sf.write(source, np.zeros((480, 2)), 48000)
                write_download(source.parent, {'videoId': 'abcdefghijk', 'file': source.name,
                    'bytes': source.stat().st_size, 'sha256': batch.file_sha256(source), 'codec': 'pcm_s16le'})
                if corrupt:
                    source.write_bytes(b'corrupt retained media')
                else:
                    sf.write(source, np.ones((960, 2))*.1, 48000)
                old = source.read_bytes()
                def download(_url, options, *, download):
                    replacement = Path(options['outtmpl'].replace('%(ext)s', 'wav'))
                    sf.write(replacement, np.zeros((480, 2)), 48000)
                    return {'id': 'abcdefghijk', '_filename': str(replacement), 'acodec': 'pcm_s16le'}
                actual_probe = batch.probe
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', wraps=actual_probe) as probed, \
                        patch('stemwise_engine.youtube.extract_youtube_info', side_effect=download) as provider:
                    self.assertEqual(batch.download_reference({'videoId': 'abcdefghijk', 'position': 1})['status'], 'saved')
                    receipt = json.loads((source.parent/'download.json').read_bytes())
                    replacement = source.parent/receipt['file']
                    self.assertNotEqual(replacement, source)
                    self.assertEqual(actual_probe(replacement)['codec_name'], 'pcm_s16le')
                    self.assertEqual(receipt['sha256'], batch.file_sha256(replacement))
                    self.assertEqual(receipt['additionalSourceFiles'][0]['file'], source.name)
                    self.assertEqual(source.read_bytes(), old)
                    self.assertEqual(batch.source_file('abcdefghijk'), replacement)
                    self.assertEqual(batch.download_reference({'videoId': 'abcdefghijk', 'position': 1})['status'], 'already_saved')
                    self.assertEqual(provider.call_count, 1)
                    self.assertNotIn(source, [call.args[0] for call in probed.call_args_list])

    def test_reference_acquisition_retirement_never_publishes_an_ordinary_failure(self):
        from stemwise_engine.common import WorkerRetirementError
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root, fatal = Path(name), WorkerRetirementError('provider worker survived')
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=None), \
                    patch.object(batch, 'require_youtube_audio_allowed'), \
                    patch('stemwise_engine.youtube.extract_youtube_info', side_effect=fatal) as acquire:
                with self.assertRaises(WorkerRetirementError) as raised:
                    batch.download_reference({'videoId':'abcdefghijk', 'position':1})
            self.assertIs(raised.exception, fatal)
            acquire.assert_called_once()
            self.assertFalse((root/'audio/youtube/abcdefghijk/download.json').exists())

    def test_reference_retirement_cancels_active_peer_and_blocks_queued_rows(self):
        import threading
        import time
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled
        fatal, both, retired = WorkerRetirementError('reference process survived'), threading.Event(), threading.Event()
        started = []
        def download(row, *, cancelled, **options):
            started.append(row['position'])
            if len(started) == 2:
                both.set()
            self.assertTrue(both.wait(5))
            if row['position'] == 0:
                raise fatal
            deadline = time.monotonic()+5
            while not cancelled() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(cancelled())
            retired.set()
            raise ProviderCancelled('peer retired')
        with patch.object(sys, 'argv', ['quick_batch.py', 'references']), \
                patch.object(batch, 'read_catalogue', return_value={'rows':[{'position':i} for i in range(8)]}), \
                patch.object(batch, 'download_reference', side_effect=download):
            with self.assertRaises(WorkerRetirementError) as raised:
                batch.main()
        self.assertIs(raised.exception, fatal)
        self.assertEqual(started, [0, 1])
        self.assertTrue(retired.is_set())

    def test_comparison_retirement_stops_candidate_and_row_fallbacks(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_media.catalogue_store import Catalogue
        for failed_decode in (1, 2):
            with self.subTest(failed_decode=failed_decode), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                reference, candidate = root/'reference.opus', root/'candidate.flac'
                reference.write_bytes(b'reference')
                candidate.write_bytes(b'candidate')
                row = {'position':1, 'videoId':'abcdefghijk', 'metadataSelection':{'status':'deferred', 'basis':'unconfirmed'},
                    'candidates':[{'id':1}, {'id':2}]}
                data = Catalogue({'rows':[row, {**row, 'position':2}]})
                fatal = WorkerRetirementError('decoder survived')
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=reference), \
                        patch.object(batch, 'qobuz_file', return_value=candidate), \
                        patch.object(batch, 'decode', side_effect=[fatal] if failed_decode == 1 else [np.zeros((8,2)), fatal]) as decode, \
                        patch.object(batch, 'write_json') as commit:
                    with self.assertRaises(WorkerRetirementError) as raised:
                        batch.compare_rows(data)
                self.assertIs(raised.exception, fatal)
                self.assertEqual(decode.call_count, failed_decode)
                commit.assert_not_called()
                self.assertNotIn('quickAudioComparison', row)

    def test_selected_candidate_is_bound_by_id_not_list_position(self):
        row = {'candidates': [{'id': 1}, {'id': 2}],
               'metadataSelection': {'status': 'selected_candidate', 'trackId': 2}}
        self.assertEqual(batch.candidates(row), [{'id': 2}])
        row['candidates'].reverse()
        self.assertEqual(batch.candidates(row), [{'id': 2}])
        for candidates in ([{'id': 1}], [{'id': 2}, {'id': 2}]):
            row['candidates'] = candidates
            with self.assertRaisesRegex(ValueError, 'selected recording'):
                batch.candidates(row)

    def test_catalogue_write_cap_preserves_readable_previous_commit(self):
        from stemwise_media import catalogue_store as store
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            path = Path(name)/'catalogue.json'
            store.commit_catalogue(path, {'rows': [{'position': 1}]})
            previous = path.read_bytes()
            data = store.read_catalogue(path)
            data['rows'][0]['oversized'] = 'x'*1024
            with patch.object(store, 'MAX_BYTES', 512, create=True):
                with self.assertRaisesRegex(ValueError, 'size limit'):
                    store.commit_catalogue(path, data)
            self.assertEqual(path.read_bytes(), previous)
            self.assertEqual(store.read_catalogue(path)['rows'], [{'position': 1}])

    def test_media_discovery_uses_receipt_case_and_isolates_invalid_directories(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            base = root/'audio/qobuz/20'
            base.mkdir(parents=True)
            (base/'audio.FLAC').write_bytes(b'fixture')
            write_download(base, {'qobuzTrackId': 20, 'file': 'audio.FLAC'})
            bad = root/'audio/qobuz/not-a-track'
            bad.mkdir()
            (bad/'audio.flac').write_bytes(b'bad')
            (bad/'enrichment-input.json').write_text('{}', encoding='utf-8')
            with patch.object(batch, 'ROOT', root):
                result = batch.build_library({'rows': [{'position': 1, 'videoId': 'abcdefghijk',
                    'candidates': [{'id': 20, 'title': 'Song'}]}]})
            self.assertEqual(result['flac'][0]['file'], 'audio/qobuz/20/audio.FLAC')
            self.assertTrue(any('not-a-track' in str(item) for item in result['invalidAlternatives']))

    def test_cancelled_reference_never_enters_cached_or_native_probe_work(self):
        from stemwise_engine.provider_operations import ProviderCancelled
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            with patch.object(batch,'ROOT',Path(name)), patch.object(batch,'_download_reference') as work:
                with self.assertRaises(ProviderCancelled):
                    batch.download_reference({'videoId':'abcdefghijk','position':1},cancelled=lambda:True)
                work.assert_not_called()

    def test_real_reference_probe_observes_batch_cancellation_and_retires_its_child(self):
        import threading
        import time
        import stemwise_media.compare as compare
        from stemwise_engine.provider_operations import ProviderCancelled
        from stemwise_engine.common import run_process_tree
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root=Path(name);library=root/'PC side/qobuz-comparison'
            source=library/'audio/youtube/abcdefghijk/source.opus'
            source.parent.mkdir(parents=True);source.write_bytes(b'retained-source')
            write_download(source.parent, {'videoId':'abcdefghijk','file':source.name,
                'bytes':source.stat().st_size,'sha256':batch.file_sha256(source)})
            marker=root/'probe.pid';cancelled=threading.Event()
            def cancel_after_start():
                end=time.monotonic()+3
                while not marker.exists() and time.monotonic()<end: time.sleep(.01)
                cancelled.set()
            def probe_process(arguments,**kwargs):
                command=[sys.executable,'-c','import os,pathlib,sys,time;pathlib.Path(sys.argv[1]).write_text(str(os.getpid()));time.sleep(20)',str(marker)]
                return run_process_tree(command,**kwargs)
            timer=threading.Thread(target=cancel_after_start);timer.start()
            try:
                with patch.object(batch,'ROOT',library), patch.object(batch,'source_file',return_value=source), \
                        patch.object(batch,'require_youtube_audio_allowed'), \
                        patch('stemwise_engine.youtube.extract_youtube_info', side_effect=AssertionError('Retained probe cannot download')), \
                        patch.object(compare,'run_process_tree',side_effect=probe_process) as probe:
                    with self.assertRaises(ProviderCancelled):
                        batch.download_reference({'videoId':'abcdefghijk','position':1},
                            selected_format='bestaudio[acodec=opus]',timeout_seconds=5,cancelled=cancelled.is_set)
            finally:
                timer.join(timeout=4)
                self.assertFalse(timer.is_alive())
            self.assertEqual(probe.call_count, 1)
            self.assertTrue(marker.exists())
            self.assertFalse(Path('/proc/'+marker.read_text()).exists())
            self.assertEqual(source.read_bytes(),b'retained-source')

    def test_reference_interruption_propagates_cancellation_and_does_not_start_queued_work(self):
        import threading
        import time
        rows=[{'position':i} for i in range(8)]
        started=[];both=threading.Event()
        def download(row, *, cancelled, **kwargs):
            if cancelled():
                raise InterruptedError
            started.append(row['position'])
            if len(started)==2: both.set()
            limit=time.monotonic()+2
            while not cancelled() and time.monotonic()<limit: time.sleep(.01)
            if not cancelled(): self.fail('Active download ignored cancellation')
            raise InterruptedError
        def interrupt(futures):
            self.assertTrue(both.wait(2))
            raise KeyboardInterrupt
            yield
        with patch.object(sys,'argv',['quick_batch.py','references']), \
                patch.object(batch,'read_catalogue',return_value={'rows':rows}), \
                patch.object(batch,'download_reference',side_effect=download), \
                patch.object(batch,'as_completed',side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt): batch.main()
        self.assertEqual(len(started),2)

    def test_precision_capability_is_not_inherited_by_changed_audio(self):
        from stemwise_media.catalogue_store import Catalogue
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            reference, candidate = root/'reference.opus', root/'candidate.flac'
            reference.write_bytes(b'original reference')
            candidate.write_bytes(b'original candidate')
            row = {'position': 1, 'videoId': 'abcdefghijk', 'candidates': [{'id': 10}],
                   'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}}
            data = Catalogue({'rows': [row]})
            result = {'classification': 'not_matched', 'anchors': [{'correlation': .99, 'offsetSeconds': 0.0}]}
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=reference), \
                    patch.object(batch, 'qobuz_file', return_value=candidate), \
                    patch.object(batch, 'decode', return_value=np.zeros((8, 2))), \
                    patch.object(batch, 'analyze_waveforms', return_value=result) as analyze:
                batch.compare_rows(data, refine=True)
                batch.compare_rows(data, refine=True, precision=True)
                self.assertEqual(row['refinedAudioComparison']['fractionalDelayReview'], 3)
                candidate.write_bytes(b'replacement candidate')
                batch.compare_rows(data, refine=True)
                self.assertFalse(row['refinedAudioComparison']['fractionalDelayReview'])
                batch.compare_rows(data, refine=True, precision=True)
                self.assertEqual(sum(call.kwargs.get('allow_fractional_delay', False)
                                     for call in analyze.call_args_list), 2)

    def test_reference_cli_reports_failure_after_retaining_successful_results(self):
        import io
        from contextlib import redirect_stdout
        rows = [{'position': 1}, {'position': 2}]
        results = [{'position': 1, 'status': 'already_saved'}, {'position': 2, 'status': 'failed'}]
        output = io.StringIO()
        with patch.object(sys, 'argv', ['quick_batch.py', 'references']), \
                patch.object(batch, 'read_catalogue', return_value={'rows': rows}), \
                patch.object(batch, 'download_reference', side_effect=results), redirect_stdout(output):
            self.assertEqual(batch.main(), 1)
        self.assertCountEqual([json.loads(line) for line in output.getvalue().splitlines()], results)

    def test_comparison_cli_reports_failure_without_discarding_successful_candidate(self):
        from stemwise_media.catalogue_store import Catalogue
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            reference, good, bad = (root/name for name in ('reference.opus', 'good.flac', 'bad.flac'))
            for path in (reference, good, bad):
                path.write_bytes(path.name.encode())
            row = {'position': 1, 'videoId': 'abcdefghijk', 'candidates': [{'id': 10}, {'id': 20}],
                   'metadataSelection': {'status': 'deferred', 'basis': 'metadata'}}
            data = Catalogue({'rows': [row]})
            def decode(path, rate):
                if path == bad: raise RuntimeError('fixture decoder refusal')
                return np.zeros((8, 2))
            with patch.object(sys, 'argv', ['quick_batch.py', 'compare']), patch.object(batch, 'ROOT', root), \
                    patch.object(batch, 'read_catalogue', return_value=data), \
                    patch.object(batch, 'source_file', return_value=reference), \
                    patch.object(batch, 'qobuz_file', side_effect=lambda track: good if track == 10 else bad), \
                    patch.object(batch, 'decode', side_effect=decode), \
                    patch.object(batch, 'analyze_waveforms', return_value={'classification': 'strong_recording_match'}):
                self.assertEqual(batch.main(), 1)
            saved = json.loads((root/'catalogue.json').read_bytes())['rows'][0]['quickAudioComparison']
            self.assertEqual(saved['matchedTrackId'], 10)
            self.assertEqual([c['classification'] for c in saved['candidates']], ['strong_recording_match', 'error'])

    def test_retirement_recovers_pending_review_publication(self):
        import os
        from stemwise_media.catalogue_store import Catalogue
        for after_link in (False, True):
            with self.subTest(after_link=after_link), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                source, candidate = root/'audio/youtube/abcdefghijk/source.opus', root/'audio/qobuz/10/audio.flac'
                for path, identity in ((source, {'videoId': 'abcdefghijk'}), (candidate, {'qobuzTrackId': 10})):
                    path.parent.mkdir(parents=True)
                    path.write_bytes(b'original' if path == source else flac_bytes())
                    write_download(path.parent, identity | {'file': path.name})
                row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': [{'id': 10}],
                    'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}}
                data = Catalogue({'rows': [row], 'downloadPolicy': {}})
                output = root/'comparison-results'
                real_link = os.link
                def die(original, target):
                    if after_link: real_link(original, target)
                    raise SystemExit('interrupted')
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', return_value={'codec_name': 'flac'}):
                    with patch.object(batch.os, 'link', side_effect=die):
                        with self.assertRaises(SystemExit): batch.export(data)
                    self.assertTrue((output/'.review-publication.json').exists())
                    batch.retire_youtube_audio('abcdefghijk', 10)
                    self.assertFalse((output/'.review-publication.json').exists())
                    self.assertFalse(source.exists())
                    batch.export(data)
                    index = json.loads((output/'review-index.json').read_bytes())['abcdefghijk']
                    for binding in index['audio']:
                        self.assertNotIn('YouTube', binding['file'])
                        self.assertEqual(batch.file_sha256(output/binding['file']), binding['sha256'])
                    self.assertEqual(candidate.read_bytes(), flac_bytes())

    def test_reports_use_current_catalogue_and_byte_bound_comparisons(self):
        from stemwise_media.catalogue_store import Catalogue, commit_catalogue
        for rate, changed in ((16000, False), (48000, False), (48000, True)):
            with self.subTest(rate=rate, changed=changed), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                source, candidate = root/'audio/youtube/abcdefghijk/source.opus', root/'audio/qobuz/10/audio.flac'
                for path, identity in ((source, {'videoId': 'abcdefghijk'}), (candidate, {'qobuzTrackId': 10})):
                    path.parent.mkdir(parents=True)
                    path.write_bytes(flac_bytes())
                    write_download(path.parent, identity | {'file': path.name})
                measured = {'sampleRate': rate, 'recipeVersion': 1, 'status': 'matched', 'matchedTrackId': 10,
                    'referenceFile': source.relative_to(root).as_posix(), 'referenceSha256': batch.file_sha256(source),
                    'candidates': [{'trackId': 10, 'candidateFile': candidate.relative_to(root).as_posix(),
                        'candidateSha256': batch.file_sha256(candidate), 'classification': 'strong_recording_match',
                        'fullContentSupported': True, 'channels': [{'channel': c, 'windows': 1, 'minimumCorrelation': 1}
                            for c in ('left', 'right')]}]}
                row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Old title', 'candidates': [{'id': 10}],
                    'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}, 'quickAudioComparison': measured}
                data = Catalogue({'rows': [row], 'downloadPolicy': {}})
                catalogue = root/'catalogue.json'
                commit_catalogue(catalogue, data)
                owner = batch.read_catalogue(catalogue)
                owner['rows'][0]['ownerDisplayTitle'] = 'Current owner title'
                commit_catalogue(catalogue, owner)
                if changed:
                    candidate.write_bytes(flac_bytes(1))
                    write_download(candidate.parent, {'qobuzTrackId': 10, 'file': candidate.name})
                with patch.object(batch, 'ROOT', root):
                    batch.export(data)
                    output = root/'comparison-results'
                    index = json.loads((output/'review-index.json').read_bytes())['abcdefghijk']
                    status = 'stale' if changed else 'matched'
                    self.assertEqual(index['status'], status)
                    self.assertEqual(data['quickAudioComparisonCounts'], {status: 1})
                    self.assertIn('Current owner title', (output/'results.csv').read_text(encoding='utf-8-sig'))
                    self.assertIn('Current owner title', (output/index['directory']/'details.txt').read_text(encoding='utf-8'))
                    self.assertEqual(data['rows'][0]['quickAudioComparison'], measured)
                    self.assertEqual(batch.current_comparison_binding(data['rows'][0], 10) is not None, rate == 48000 and not changed)
                    library = batch.build_library(data)
                    self.assertEqual(library['playlist'][0]['source'], 'qobuz' if rate == 48000 and not changed else 'youtube')
                    if rate == 48000 and not changed:
                        batch.export_library(data)
                        published = output/'library-files.json'
                        before = published.read_bytes()
                        data['libraryPolicy'] = {'includeAllRetainedFlac': True}
                        actual = batch._export_library_locked
                        def replace_then_publish(*args, **kwargs):
                            replacement = candidate.with_name('replacement.flac')
                            replacement.write_bytes(flac_bytes(2))
                            replacement.replace(candidate)
                            return actual(*args, **kwargs)
                        with patch.object(batch, '_export_library_locked', side_effect=replace_then_publish):
                            with self.assertRaises(ValueError):
                                batch.export(data)
                        self.assertEqual(published.read_bytes(), before)


    def test_report_status_cycles_and_changed_audio_use_stable_current_links(self):
        import copy
        import os
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            source, candidate = root/'audio/source.webm', root/'audio/candidate.flac'
            source.parent.mkdir()
            source.write_bytes(b'original source')
            candidate.write_bytes(b'candidate generation one')
            row = {'videoId': 'abcdefghijk', 'position': 1, 'title': 'Title',
                   'candidates': [{'id': 10}],
                   'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}}
            from stemwise_media.catalogue_store import Catalogue
            data = Catalogue({'rows': [row], 'downloadPolicy': {}})
            directory = root/'comparison-results/reviews/abcdefghijk'
            directory.mkdir(parents=True)
            notes = directory/'owner-notes.txt'
            notes.write_bytes(b'preserve owner notes')
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=source), \
                    patch.object(batch, 'qobuz_file', return_value=candidate):
                for cycle in range(2):
                    for status in ('unconfirmed', 'not_checked', 'unconfirmed', 'not_checked'):
                        if cycle == 1 and status == 'unconfirmed':
                            replacement = root/'replacement.flac'
                            replacement.write_bytes(b'candidate generation two')
                            os.replace(replacement, candidate)
                        row['quickAudioComparison'] = {'status': status, 'matchedTrackId': 10 if status == 'matched' else None}
                        batch.export(data)
                        index = json.loads((root/'comparison-results/review-index.json').read_bytes())
                        self.assertEqual(index['abcdefghijk']['status'], status)
                        self.assertEqual(index['abcdefghijk']['directory'], 'reviews/abcdefghijk')
                        for link in index['abcdefghijk']['audio']:
                            path = root/'comparison-results'/link['file']
                            self.assertEqual(batch.file_sha256(path), link['sha256'])
                            self.assertEqual(path.read_bytes(), (source if 'YouTube' in path.name else candidate).read_bytes())
                        self.assertEqual({path.name for path in directory.iterdir() if path.suffix in {'.webm', '.flac'}},
                            {Path(link['file']).name for link in index['abcdefghijk']['audio']})
                        with (root/'comparison-results/results.csv').open(encoding='utf-8-sig') as stream:
                            self.assertEqual(len(list(csv.reader(stream))), 2)
                        self.assertEqual(notes.read_bytes(), b'preserve owner notes')
                reports = root/'comparison-results'
                before = {p: p.read_bytes() for p in reports.rglob('*') if p.is_file()}
                row['quickAudioComparison']['status'] = 'unconfirmed'
                with patch.object(batch, 'atomic_write_json', side_effect=OSError('preparation failed')):
                    with self.assertRaisesRegex(OSError, 'preparation failed'):
                        batch.export(data)
                self.assertEqual({p: p.read_bytes() for p in before}, before)
                actual = batch.atomic_write_bytes
                def die(path, payload, *args, **kwargs):
                    result = actual(path, payload, *args, **kwargs)
                    if path.name == 'results.csv':
                        raise SystemExit('process death')
                    return result
                with patch.object(batch, 'atomic_write_bytes', side_effect=die):
                    with self.assertRaises(SystemExit):
                        batch.export(data)
                self.assertTrue((reports/'.review-publication.json').exists())
                batch.export(data)
                self.assertEqual(json.loads((reports/'review-index.json').read_bytes())['abcdefghijk']['status'], 'unconfirmed')
                self.assertFalse((reports/'.review-publication.json').exists())
                self.assertEqual(notes.read_bytes(), b'preserve owner notes')

    def test_review_link_creation_is_journaled_and_restart_retires_only_indexed_audio(self):
        import os
        from stemwise_media.catalogue_store import Catalogue
        for death in ('before-link', 'after-link', 'changed-old-link'):
            with self.subTest(death=death), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                source, candidate = root / 'source.webm', root / 'candidate.flac'
                source.write_bytes(b'original')
                candidate.write_bytes(b'old candidate')
                row = {'videoId': 'abcdefghijk', 'position': 1, 'title': 'Song', 'candidates': [{'id': 10}],
                       'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}}
                data = Catalogue({'rows': [row], 'downloadPolicy': {}})
                output = root / 'comparison-results'
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=source), \
                        patch.object(batch, 'qobuz_file', return_value=candidate):
                    batch.export(data)
                    old_index = (output / 'review-index.json').read_bytes()
                    old_binding = next(item for item in json.loads(old_index)['abcdefghijk']['audio'] if 'Qobuz' in item['file'])
                    old_link = output / old_binding['file']
                    unrelated = old_link.parent / 'owner-recording.flac'
                    unrelated.write_bytes(b'owner file')
                    replacement = root / 'replacement.flac'
                    replacement.write_bytes(b'new candidate')
                    os.replace(replacement, candidate)
                    actual_link = os.link
                    def die(source_path, destination, *args, **kwargs):
                        journal = output / '.review-publication.json'
                        self.assertTrue(journal.is_file())
                        record = json.loads(journal.read_bytes())
                        self.assertIn(destination.relative_to(output).as_posix(), {item['file'] for item in record['links']})
                        self.assertEqual((output / 'review-index.json').read_bytes(), old_index)
                        if death != 'before-link':
                            actual_link(source_path, destination, *args, **kwargs)
                        raise SystemExit('interrupted link publication')
                    with patch.object(batch.os, 'link', side_effect=die):
                        with self.assertRaisesRegex(SystemExit, 'interrupted link'):
                            batch.export(data)
                    self.assertEqual(old_link.read_bytes(), b'old candidate')
                    if death == 'changed-old-link':
                        old_link.write_bytes(b'owner edited old link')
                        with self.assertRaisesRegex(ValueError, 'changed retired review link'):
                            batch.export(data)
                        self.assertEqual(old_link.read_bytes(), b'owner edited old link')
                        self.assertTrue((output / '.review-publication.json').is_file())
                    else:
                        batch.export(data)
                        index = json.loads((output / 'review-index.json').read_bytes())
                        self.assertFalse(old_link.exists())
                        self.assertFalse((output / '.review-publication.json').exists())
                        for binding in index['abcdefghijk']['audio']:
                            self.assertEqual(batch.file_sha256(output / binding['file']), binding['sha256'])
                    self.assertEqual(unrelated.read_bytes(), b'owner file')
                    self.assertEqual(source.read_bytes(), b'original')
                    self.assertEqual(candidate.read_bytes(), b'new candidate')

    def test_concurrent_catalogue_commit_waits_for_export_and_preserves_both_edits(self):
        import threading
        from stemwise_media.catalogue_store import commit_catalogue
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            root = Path(name)
            path = root / 'catalogue.json'
            rows = [{'position': i, 'videoId': video, 'title': 'Original', 'candidates': [{'id': 10}],
                     'metadataSelection': {'status': 'deferred', 'basis': 'metadata'}}
                    for i, video in enumerate(('abcdefghijk', 'lmnopqrstuv'), 1)]
            path.write_text(json.dumps({'rows': rows, 'downloadPolicy': {}}), encoding='utf-8')
            exporting, independent = batch.read_catalogue(path), batch.read_catalogue(path)
            exporting['rows'][0]['title'] = 'Export edit'
            independent['rows'][1]['title'] = 'Concurrent edit'
            entered, attempted = threading.Event(), threading.Event()
            failures = []
            actual_commit = batch._commit_catalogue_locked
            def export_commit(*args):
                result = actual_commit(*args)
                entered.set()
                self.assertTrue(attempted.wait(3), 'Concurrent writer never reached admission')
                return result
            def writer():
                try:
                    if not entered.wait(3):
                        raise TimeoutError('Export never reached its commit')
                    attempted.set()
                    commit_catalogue(path, independent)
                except BaseException as error:
                    failures.append(error)
            worker = threading.Thread(target=writer, daemon=True)
            with patch.object(batch, 'ROOT', root), patch.object(batch, '_commit_catalogue_locked', side_effect=export_commit):
                worker.start()
                try:
                    batch.export(exporting)
                finally:
                    entered.set(); worker.join(5)
            self.assertFalse(worker.is_alive(), 'Concurrent commit did not retire')
            self.assertEqual(failures, [])
            self.assertEqual([row['title'] for row in batch.read_catalogue(path)['rows']], ['Export edit', 'Concurrent edit'])

    def test_invalid_optional_candidate_is_reported_but_cannot_be_selected(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            original, alternative = root/'audio/youtube/abcdefghijk', root/'audio/qobuz/20'
            for path in (original, alternative): path.mkdir(parents=True)
            (original/'source.webm').write_bytes(b'owner original')
            write_download(original, {'videoId': 'abcdefghijk', 'file': 'source.webm'})
            (alternative/'audio.flac').write_bytes(flac_bytes())
            (alternative/'download.json').write_text(json.dumps({'qobuzTrackId': 20, 'file': 'audio.flac'}))
            row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': [{'id': 20}]}
            before = (alternative/'audio.flac').read_bytes()
            with patch.object(batch, 'ROOT', root):
                library = batch.build_library({'rows': [row]})
                self.assertEqual(library['playlist'][0]['source'], 'youtube')
                self.assertEqual(library['invalidAlternatives'][0]['trackId'], 20)
                row['ownerAudioSelection'] = {'source': 'qobuz', 'trackId': 20, 'file': 'audio/qobuz/20/audio.flac'}
                with self.assertRaisesRegex(ValueError, 'content verification'):
                    batch.build_library({'rows': [row]})
            self.assertEqual((alternative/'audio.flac').read_bytes(), before)

    def test_optional_export_failure_keeps_prior_audio_and_publishes_independent_rows(self):
        import os
        from stemwise_media.catalogue_store import Catalogue
        for mutate_in_place in (False, True):
            with self.subTest(mutate_in_place=mutate_in_place), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                root = Path(directory)
                rows = []
                for position, video, track in ((1, 'abcdefghijk', 10), (2, 'lmnopqrstuv', 20)):
                    source, native = root/'audio/youtube'/video, root/'audio/qobuz'/str(track)
                    source.mkdir(parents=True); native.mkdir(parents=True)
                    (source/'source.webm').write_bytes(b'original')
                    write_download(source, {'videoId': video, 'file': 'source.webm'})
                    (native/'audio.flac').write_bytes(flac_bytes())
                    write_download(native, {'qobuzTrackId': track, 'file': 'audio.flac'})
                    rows.append({'position': position, 'videoId': video, 'title': 'Song', 'candidates': [{'id': track}],
                        'metadataSelection': {'status': 'deferred', 'basis': 'review'}})
                data = Catalogue({'rows': rows, 'downloadPolicy': {}, 'libraryPolicy': {'includeAllRetainedFlac': True}})
                output = root/'comparison-results'
                with patch.object(batch, 'ROOT', root):
                    batch.export(data)
                    prior_index = json.loads((output/'review-index.json').read_bytes())
                    old_audio = {output/binding['file']: (output/binding['file']).read_bytes()
                        for binding in prior_index['abcdefghijk']['audio']}
                    candidate = root/'audio/qobuz/10/audio.flac'
                    replacement = candidate.with_name('replacement.flac')
                    replacement.write_bytes(flac_bytes(1))
                    if mutate_in_place:
                        candidate.write_bytes(replacement.read_bytes()); replacement.unlink()
                    else:
                        os.replace(replacement, candidate)
                    old_audio = {path: path.read_bytes() for path in old_audio}
                    inputs = {path: path.read_bytes() for path in root.rglob('*') if path.is_file()
                        and 'comparison-results' not in path.parts and path.name != 'catalogue.json'
                        and path.suffix != '.lock'}
                    batch.export(data)
                    index = json.loads((output/'review-index.json').read_bytes())
                    self.assertEqual(set(index), {'abcdefghijk', 'lmnopqrstuv'})
                    if mutate_in_place:
                        self.assertEqual(index['abcdefghijk']['unavailableAudio'], [binding for binding in prior_index['abcdefghijk']['audio'] if 'Qobuz' in binding['file']])
                    self.assertEqual(index['abcdefghijk']['unavailableCandidates'][0]['trackId'], 10)
                    self.assertIn('digest differs', index['abcdefghijk']['unavailableCandidates'][0]['reason'])
                    self.assertIn('Qobuz 10', (output/'reviews/abcdefghijk/details.txt').read_text(encoding='utf-8'))
                    self.assertEqual(json.loads((output/'library-files.json').read_bytes())['invalidAlternatives'][0]['trackId'], 10)
                    for binding in index['abcdefghijk']['audio']:
                        self.assertEqual(batch.file_sha256(output/binding['file']), binding['sha256'])
                    batch.export(data)
                    self.assertEqual(json.loads((output/'review-index.json').read_bytes()), index)
                    self.assertEqual({path: path.read_bytes() for path in old_audio}, old_audio)
                    self.assertEqual({path: path.read_bytes() for path in inputs}, inputs)
                    reports = {path: path.read_bytes() for path in output.rglob('*') if path.is_file() and path.suffix != '.lock'}
                    data['rows'][0]['ownerAudioSelection'] = {'source': 'qobuz', 'trackId': 10,
                        'file': 'audio/qobuz/10/audio.flac'}
                    with self.assertRaises(ValueError):
                        batch.export(data)
                    self.assertEqual({path: path.read_bytes() for path in reports}, reports)
                    data['rows'][0].pop('ownerAudioSelection')
                    data['rows'][0]['metadataSelection'] = {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}
                    with self.assertRaises(ValueError):
                        batch.export(data)
                    self.assertEqual({path: path.read_bytes() for path in reports}, reports)

    def test_optional_export_failure_does_not_hide_a_receipt_change(self):
        from stemwise_media.catalogue_store import Catalogue
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            source = root/'source.webm'; source.write_bytes(b'original')
            receipt = root/'audio/qobuz/10/download.json'
            receipt.parent.mkdir(parents=True)
            receipt.write_text('{}', encoding='utf-8')
            row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': [{'id': 10}],
                   'metadataSelection': {'status': 'deferred', 'basis': 'review'}}
            data = Catalogue({'rows': [row], 'downloadPolicy': {}})
            actual = batch.ComparisonEvidence.require_unchanged
            def mutate(evidence):
                receipt.write_text('{"changed": true}', encoding='utf-8')
                actual(evidence)
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=source), \
                    patch.object(batch, 'qobuz_file', side_effect=ValueError('invalid optional receipt')), \
                    patch.object(batch.ComparisonEvidence, 'require_unchanged', mutate):
                with self.assertRaisesRegex(ValueError, 'changed during comparison export'):
                    batch.export(data)
            self.assertFalse((root/'comparison-results/review-index.json').exists())

    def test_export_uses_committed_rows_and_stale_projection_cannot_deliver(self):
        from stemwise_media.catalogue_store import commit_catalogue
        from stemwise_engine.delivery_selection import catalogue
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            library_root = root/'PC side/qobuz-comparison'
            rows = []
            for position, video in enumerate(('abcdefghijk', 'lmnopqrstuv'), 1):
                folder = library_root/'audio/youtube'/video
                folder.mkdir(parents=True)
                (folder/'audio.webm').write_bytes(b'original')
                write_download(folder, {'videoId': video, 'file': 'audio.webm'})
                rows.append({'position': position, 'videoId': video, 'title': 'Song',
                             'candidates': [{'id': 20, 'title': 'Song'}]})
            native = library_root/'audio/qobuz/20'
            native.mkdir(parents=True)
            (native/'audio.flac').write_bytes(flac_bytes())
            write_download(native, {'qobuzTrackId': 20, 'file': 'audio.flac'})
            source = library_root/'catalogue.json'
            source.write_text(json.dumps({'rows': rows}), encoding='utf-8')
            first, second = batch.read_catalogue(source), batch.read_catalogue(source)
            first['rows'][0]['ownerAudioSelection'] = {'source': 'qobuz', 'trackId': 20,
                'file': 'audio/qobuz/20/audio.flac'}
            second['rows'][1]['title'] = 'Concurrent title'
            commit_catalogue(source, first)
            commit_catalogue(source, second)
            workflow = SimpleNamespace(workspace_root=root, pc_side_root=root/'PC side')
            with patch.object(batch, 'ROOT', library_root):
                batch.export_library(first)
                self.assertEqual(catalogue(workflow)['abcdefghijk'], 'qobuz:20')
                projected = json.loads((library_root/'comparison-results/library-files.json').read_bytes())
                self.assertEqual(projected['playlist'][1]['title'], 'Concurrent title')
                current = batch.read_catalogue(source)
                current['rows'][0]['ownerAudioSelection'] = {'source': 'youtube',
                    'file': 'audio/youtube/abcdefghijk/audio.webm'}
                commit_catalogue(source, current)
                with self.assertRaisesRegex(ValueError, 'projection is stale'):
                    catalogue(workflow)
                batch.export_library(first)
                self.assertEqual(catalogue(workflow)['abcdefghijk'], 'youtube:abcdefghijk')

    def test_transient_candidate_failure_retries_same_bytes_and_preserves_successful_sibling(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
            root = Path(directory)
            reference = root/'reference.opus'
            reference.write_bytes(b'reference')
            candidates = {track: root/f'{track}.flac' for track in (10, 20)}
            for track, path in candidates.items():
                path.write_bytes(str(track).encode())
            row = {'videoId': 'abcdefghijk', 'position': 1, 'candidates': [{'id': 10}, {'id': 20}],
                'metadataSelection': {'status': 'deferred', 'basis': 'competing'}}
            from stemwise_media.catalogue_store import Catalogue
            data = Catalogue({'rows': [row]})
            measured = []
            def analyze(reference, candidate, *args, **kwargs):
                track = int(candidate[0, 0])
                measured.append(track)
                if track == 20 and measured.count(20) == 1:
                    raise RuntimeError('transient decoder failure')
                return {'classification': 'not_matched', 'independentMarker': track}
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'source_file', return_value=reference), \
                    patch.object(batch, 'qobuz_file', side_effect=candidates.get), \
                    patch.object(batch, 'decode', side_effect=lambda path, rate: np.full((8, 2), 0 if path == reference else int(path.stem))), \
                    patch.object(batch, 'analyze_waveforms', side_effect=analyze):
                batch.compare_rows(data)
                success = row['quickAudioComparison']['candidates'][0].copy()
                self.assertEqual(measured, [10, 20])
                batch.compare_rows(data)
                self.assertEqual(measured, [10, 20, 20])
                self.assertEqual(row['quickAudioComparison']['candidates'][0], success)
                self.assertTrue(all(item['classification'] != 'error' for item in row['quickAudioComparison']['candidates']))
                batch.compare_rows(data)
                self.assertEqual(measured, [10, 20, 20])

    def test_export_requires_current_comparison_bytes_and_never_retires_audio(self):
        import copy
        for change in ('none', 'before-export', 'before-retirement', 'missing-proof'):
            with self.subTest(change=change), tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
                root = Path(name)
                video = 'abcdefghijk'
                reference = root / f'audio/youtube/{video}/audio.opus'
                candidate = root / 'audio/qobuz/10/audio.flac'
                for path, identity in ((reference, {'videoId':video}), (candidate, {'qobuzTrackId':10})):
                    path.parent.mkdir(parents=True)
                    path.write_bytes(path.suffix.encode())
                    write_download(path.parent, {**identity,'file':path.name})
                (root / 'comparison-results').mkdir()
                binding = {'referenceFile':reference.relative_to(root).as_posix(),
                    'referenceSha256':batch.file_sha256(reference),
                    'candidateFile':candidate.relative_to(root).as_posix(),
                    'candidateSha256':batch.file_sha256(candidate)}
                measured = {'trackId':10, 'candidateFile':binding['candidateFile'],
                    'candidateSha256':binding['candidateSha256'], 'classification':'strong_recording_match',
                    'fullContentSupported':True,
                    'channels':[{'channel':channel,'windows':1,'minimumCorrelation':1} for channel in ('left','right')]}
                row = {'videoId':video,'position':1,'candidates':[{'id':10}],
                    'refinedAudioComparison':{'sampleRate':48000,'recipeVersion':1,'status':'matched','matchedTrackId':10,
                        'referenceFile':binding['referenceFile'],'referenceSha256':binding['referenceSha256'],
                        'candidates':[measured]}}
                if change == 'missing-proof': measured.pop('candidateSha256')
                if change == 'before-export': candidate.write_bytes(b'unmeasured replacement')
                before = reference.read_bytes(), (reference.parent / 'download.json').read_bytes()
                retire = batch.retire_youtube_audio
                def retire_after_change(*args, **kwargs):
                    if change == 'before-retirement': candidate.write_bytes(b'unmeasured replacement')
                    return retire(*args, **kwargs)
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', return_value={'codec_name':'flac'}), \
                        patch.object(batch, 'retire_youtube_audio', side_effect=retire_after_change):
                    if change in ('none', 'before-retirement'):
                        batch.export_library({'rows':[row]})
                        self.assertEqual((reference.read_bytes(), (reference.parent / 'download.json').read_bytes()), before)
                        batch.export_library({'rows':[row]})
                    elif change == 'missing-proof':
                        batch.export_library({'rows':[row]})
                        library = json.loads((root/'comparison-results/library-files.json').read_bytes())
                        self.assertEqual(library['playlist'][0]['source'], 'youtube')
                    else:
                        with self.assertRaisesRegex(ValueError, 'comparison|Retained download'):
                            batch.export_library({'rows':[row]})
                        self.assertEqual((reference.read_bytes(), (reference.parent / 'download.json').read_bytes()), before)

    def test_failed_native_opus_upgrade_preserves_the_existing_audio_binding(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            base = root / 'audio/youtube/2yHJs914M4g'
            base.mkdir(parents=True)
            (base / 'source.m4a').write_bytes(b'original source')
            binding = {'videoId': '2yHJs914M4g', 'file': 'source.m4a', 'status': 'saved'}
            write_download(base, binding)
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', return_value={'codec_name': 'aac'}), \
                    patch('stemwise_engine.youtube.extract_youtube_info', side_effect=RuntimeError('Opus unavailable')):
                result = batch.download_reference({'position': 1, 'videoId': '2yHJs914M4g'}, 'bestaudio[acodec=opus]')
            self.assertEqual(result['status'], 'failed')
            self.assertEqual(json.loads((base / 'download.json').read_text()), binding)
            self.assertEqual((base / 'source.m4a').read_bytes(), b'original source')

    def test_approved_flac_export_retains_explicit_youtube_version_and_review_copies(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            video = 'abcdefghijk'
            for folder, binding, name in [(f'audio/youtube/{video}', {'videoId': video}, 'audio.webm'),
                                          ('audio/qobuz/10', {'qobuzTrackId': 10}, 'audio.flac')]:
                base = root / folder; base.mkdir(parents=True)
                (base / name).write_bytes(b'retained audio')
                write_download(base, binding | {'file': name})
            selection = {'source': 'qobuz', 'trackId': 10, 'file': 'audio/qobuz/10/audio.flac',
                         'additionalVersions': [{'source': 'youtube', 'videoId': video, 'file': f'audio/youtube/{video}/audio.webm'}]}
            row = {'position': 1, 'videoId': video, 'title': 'Live source',
                   'candidates': [{'id': 10, 'title': 'Studio version'}], 'ownerAudioSelection': selection,
                   'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'}}
            (root / 'comparison-results').mkdir()
            review = root / 'comparison-results/unconfirmed/0001-Live source'
            review.mkdir(parents=True)
            import os
            os.link(root / f'audio/youtube/{video}/audio.webm', review / '01-YouTube.webm')
            (review / 'notes.md').write_text('owner notes')
            unrelated_catalogue = root / 'owner-catalogue.json'
            unrelated_catalogue.write_bytes(b'Preserve owner catalogue')
            with patch.object(batch, 'ROOT', root), \
                    patch.object(batch, 'CATALOGUE', unrelated_catalogue, create=True), \
                    patch.object(batch, 'probe', return_value={'codec_name': 'flac'}):
                batch.export_library({'rows': [row]})
                batch.export_library({'rows': [row]})
                source = root / f'audio/youtube/{video}/audio.webm'
                metadata = source.parent/'download.json'
                selection_path = root/'comparison-results/library-files.json'
                before = source.read_bytes(), metadata.read_bytes(), selection_path.read_bytes()
                with patch.object(batch, 'write_json', side_effect=OSError('publication failed')):
                    with self.assertRaisesRegex(OSError, 'publication failed'):
                        batch.export_library({'rows': [row]})
                self.assertEqual((source.read_bytes(), metadata.read_bytes(), selection_path.read_bytes()), before)
                batch.export({'rows': [row], 'downloadPolicy': {}, 'libraryPolicy': {'includeAllRetainedFlac': True}})
            self.assertEqual(unrelated_catalogue.read_bytes(), b'Preserve owner catalogue')
            self.assertEqual(json.loads((root / 'catalogue.json').read_bytes())['rows'][0]['videoId'], video)
            library = json.loads((root / 'comparison-results/library-files.json').read_text())
            self.assertEqual(library['playlist'][0]['source'], 'qobuz')
            self.assertEqual(library['youtube'][0]['videoId'], video)
            self.assertTrue((root / f'audio/youtube/{video}/audio.webm').exists())
            self.assertTrue((review / '01-YouTube.webm').exists())
            self.assertEqual((review / 'notes.md').read_text(), 'owner notes')
            self.assertTrue((root / 'audio/qobuz/10/audio.flac').exists())
            binding = json.loads((root / f'audio/youtube/{video}/download.json').read_text())
            self.assertEqual(binding['file'], 'audio.webm')
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', return_value={'codec_name': 'flac'}):
                with self.assertRaisesRegex(ValueError, 'additional owner version'):
                    batch.retire_youtube_audio(video, 10)
            with (root / 'comparison-results/results.csv').open(encoding='utf-8-sig') as stream:
                report = next(csv.DictReader(stream))
            self.assertNotIn('تعذر تنزيل', report['السبب'])

    def test_failed_flac_validation_preserves_the_existing_opus(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            for folder, binding, name in [('audio/youtube/abcdefghijk', {'videoId': 'abcdefghijk'}, 'audio.webm'),
                                          ('audio/qobuz/10', {'qobuzTrackId': 10}, 'audio.flac')]:
                base = root / folder; base.mkdir(parents=True)
                (base / name).write_bytes(b'retained audio')
                write_download(base, binding | {'file': name})
            row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': [{'id': 10}],
                   'ownerAudioSelection': {'source': 'qobuz', 'trackId': 10, 'file': 'audio/qobuz/10/audio.flac'}}
            (root / 'comparison-results').mkdir()
            original = (root / 'audio/youtube/abcdefghijk/download.json').read_bytes()
            with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', return_value={'codec_name': 'opus'}):
                with self.assertRaisesRegex(ValueError, 'FLAC'):
                    batch.retire_youtube_audio('abcdefghijk', 10)
            self.assertEqual((root / 'audio/youtube/abcdefghijk/download.json').read_bytes(), original)
            self.assertEqual((root / 'audio/youtube/abcdefghijk/audio.webm').read_bytes(), b'retained audio')

    def test_rejected_audio_is_not_available_even_if_an_old_copy_exists(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            base = root / 'audio/qobuz/10'
            base.mkdir(parents=True)
            (base / 'wrong.flac').write_bytes(flac_bytes(1))
            write_download(base, {'qobuzTrackId': 10,
                    'file': 'wrong.flac', 'status': 'owner_rejected'})
            with patch.object(batch, 'ROOT', root):
                self.assertIsNone(batch.qobuz_file(10))

    def test_export_keeps_owner_audio_preference_separate_from_waveform_result(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            def audio(base, metadata, filename='audio.webm'):
                base.mkdir(parents=True)
                (base / filename).write_bytes(b'retained audio')
                write_download(base, {**metadata, 'file': filename})
            audio(root / 'audio/youtube/abcdefghijk', {'videoId': 'abcdefghijk'})
            audio(root / 'audio/qobuz/10', {'qobuzTrackId': 10}, 'audio.flac')
            audio(root / 'audio/qobuz/20', {'qobuzTrackId': 20}, 'audio.flac')
            audio(root / 'audio/qobuz/30', {'qobuzTrackId': 30, 'purpose': 'unrelated codec sample'}, 'audio.flac')
            selection = {'source': 'youtube', 'file': 'audio/youtube/abcdefghijk/audio.webm',
                         'additionalVersions': [{'source': 'qobuz', 'trackId': 20, 'file': 'audio/qobuz/20/audio.flac'}],
                         'reviews': [{'trackId': 10, 'verdict': 'different', 'note': 'النسختان مقبولتان'}]}
            row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': [{'id': 10}, {'id': 20}],
                   'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'},
                   'quickAudioComparison': {'status': 'unconfirmed'}, 'ownerAudioSelection': selection}
            catalogue = root / 'catalogue.json'
            catalogue.write_text(json.dumps({'rows': [row], 'downloadPolicy': {},
                'libraryPolicy': {'includeAllRetainedFlac': True}}), encoding='utf-8')
            with patch.object(batch, 'ROOT', root):
                batch.export(batch.read_catalogue(catalogue))
            row = json.loads(catalogue.read_text(encoding='utf-8'))['rows'][0]
            with (root / 'comparison-results/results.csv').open(encoding='utf-8-sig') as stream:
                report = next(csv.DictReader(stream))
            self.assertEqual(report['اختيار المكتبة'], 'YouTube')
            self.assertEqual(report['ملف المكتبة'], selection['file'])
            self.assertEqual(report['إصدارات إضافية مقبولة'], 'audio/qobuz/20/audio.flac')
            self.assertIn('النسختان مقبولتان', report['ملاحظات المالك'])
            self.assertEqual(row['quickAudioComparison']['status'], 'unconfirmed')
            self.assertEqual(row['ownerAudioSelection'], selection)
            self.assertEqual((root / selection['file']).read_bytes(), b'retained audio')
            library = json.loads((root / 'comparison-results/library-files.json').read_text())
            self.assertEqual({track['trackId'] for track in library['flac']}, {10, 20})
            self.assertEqual(library['playlist'][0]['source'], 'youtube')
            self.assertEqual(library['playlist'][0]['file'], selection['file'])
            self.assertEqual(library['youtube'][0]['videoId'], 'abcdefghijk')
            self.assertTrue((root / 'audio/qobuz/30/audio.flac').is_file())

    def test_export_uses_the_owner_selected_flac_instead_of_the_first_candidate(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            for track_id in (10, 20):
                base = root / 'audio/qobuz' / str(track_id)
                base.mkdir(parents=True)
                (base / 'audio.flac').write_bytes(flac_bytes())
                write_download(base, {'qobuzTrackId': track_id, 'file': 'audio.flac'})
            selection = {'source': 'qobuz', 'trackId': 20, 'file': 'audio/qobuz/20/audio.flac',
                         'additionalVersions': [], 'reviews': [{'trackId': 20, 'verdict': 'body', 'note': 'مطابق'}]}
            row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Song', 'candidates': [{'id': 10}, {'id': 20}],
                   'metadataSelection': {'status': 'selected_candidate', 'trackId': 10, 'basis': 'metadata'},
                   'quickAudioComparison': {'status': 'unconfirmed'}, 'ownerAudioSelection': selection}
            (root/'catalogue.json').write_text(json.dumps({'rows': [row]}))
            with patch.object(batch, 'ROOT', root):
                export_data = batch.read_catalogue(root/'catalogue.json')
                export_data['downloadPolicy'] = {}
                batch.export(export_data)
            with (root / 'comparison-results/results.csv').open(encoding='utf-8-sig') as stream:
                report = next(csv.DictReader(stream))
            self.assertEqual(report['اختيار المكتبة'], 'FLAC (Qobuz 20)')
            self.assertEqual(report['ملف المكتبة'], selection['file'])
            self.assertEqual(row['ownerAudioSelection']['trackId'], 20)

    def test_export_preserves_retained_performance_links_without_fabricating_equivalence(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            source = root / 'audio/youtube/abcdefghijk'
            source.mkdir(parents=True)
            (source / 'audio.webm').write_bytes(b'retained original')
            write_download(source, {'videoId': 'abcdefghijk', 'file': 'audio.webm'})
            for track_id in (10, 20):
                folder = root / 'audio/qobuz' / str(track_id)
                folder.mkdir(parents=True)
                (folder / 'audio.flac').write_bytes(flac_bytes())
                write_download(folder, {'qobuzTrackId': track_id, 'file': 'audio.flac'})
            row = {'position': 1, 'videoId': 'abcdefghijk', 'title': 'Owner song',
                'candidates': [{'id': track_id, 'title': 'Song', 'artist': 'Artist'} for track_id in (10, 20, 30)],
                'retainedPerformanceTrackIds': [10, 10, 20, 30], 'rejectedTrackIds': [20]}
            original_links = row['retainedPerformanceTrackIds'].copy()
            (root / 'comparison-results').mkdir()
            with patch.object(batch, 'ROOT', root):
                batch.export_library({'rows': [row]})
                batch.export_library({'rows': [row]})
            library = json.loads((root / 'comparison-results/library-files.json').read_bytes())
            self.assertEqual(library['playlist'][0]['performanceTrackIds'], [10])
            self.assertEqual(library['playlist'][0]['source'], 'youtube')
            self.assertEqual(row['retainedPerformanceTrackIds'], original_links)
            self.assertNotIn('recordingEquivalence', row)
            self.assertNotIn('refinedAudioComparison', row)
            self.assertEqual((source / 'audio.webm').read_bytes(), b'retained original')

    def test_alternative_search_keeps_all_versions_of_the_same_artist_and_title(self):
        selected = {'id': 10, 'title': 'Insha Allah', 'artist': 'Maher Zain'}
        row = {'candidates': [selected], 'metadataSelection': {'status': 'selected_candidate', 'trackId': 10}}
        alternate = {'id': 20, 'title': 'Insha-Allah', 'artist': 'Maher Zain', 'version': 'English'}
        cover = {'id': 30, 'title': 'Insha Allah', 'artist': 'Another singer'}
        other = {'id': 40, 'title': 'Mawlaya', 'artist': 'Maher Zain'}
        data = {'rows': [row, {'candidates': [alternate, cover, other]}]}
        self.assertEqual(batch.alternative_candidates(data, row), [selected, alternate])
        self.assertEqual(row['candidates'], [selected])

    def test_real_alternative_match_updates_the_selected_download_and_preserves_audio(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            root = Path(temporary)
            audio = np.random.default_rng(41).integers(-8000, 8000, (25000, 2), dtype=np.int16)
            wrong = np.random.default_rng(72).integers(-8000, 8000, audio.shape, dtype=np.int16)
            def save(base, samples, binding):
                base.mkdir(parents=True)
                with wave.open(str(base / 'audio.wav'), 'wb') as stream:
                    stream.setparams((2, 2, 1000, 0, 'NONE', 'not compressed'))
                    stream.writeframes(samples.astype('<i2').tobytes())
                write_download(base, binding | {'file': 'audio.wav'})
            save(root / 'audio/youtube/abcdefghijk', audio, {'videoId': 'abcdefghijk'})
            save(root / 'audio/qobuz/10', wrong, {'qobuzTrackId': 10})
            save(root / 'audio/qobuz/20', audio, {'qobuzTrackId': 20})
            save(root / 'audio/qobuz/30', wrong // 2, {'qobuzTrackId': 30})
            selected = {'id': 10, 'title': 'Song', 'artist': 'Artist'}
            alternate = {'id': 20, 'title': 'Song', 'artist': 'Artist'}
            row = {'position': 1, 'videoId': 'abcdefghijk', 'candidates': [selected],
                   'metadataSelection': {'status': 'selected_candidate', 'trackId': 10},
                   'quickAudioComparison': {'status': 'unconfirmed'},
                   'refinedAudioComparison': {'status': 'unconfirmed', 'sampleRate': 48000,
                      'candidates': [{'trackId': 10, 'classification': 'not_matched'}]}}
            wrong_alternate = {'id': 30, 'title': 'Song', 'artist': 'Artist'}
            from stemwise_media.catalogue_store import Catalogue
            data = Catalogue({'rows': [row, {'position': 2, 'videoId': 'lmnopqrstuv',
                'metadataSelection': {'status': 'deferred', 'basis': 'candidate-discovery'},
                'candidates': [wrong_alternate], 'quickAudioComparison': {'status': 'matched'}}]})
            review = root / 'comparison-results/unconfirmed/0001-abcdefghijk'
            review.mkdir(parents=True)
            (review / '02-Qobuz-10.flac').hardlink_to(root / 'audio/qobuz/10/audio.flac')
            with patch.object(batch, 'ROOT', root):
                batch.compare_rows(data, refine=True, edges=True, alternatives=True)
                self.assertEqual(row['refinedAudioComparison']['status'], 'unconfirmed')
                batch.compare_rows(data, refine=True, drift=True)
                self.assertEqual(row['refinedAudioComparison']['status'], 'unconfirmed')
                self.assertEqual({c['trackId'] for c in row['refinedAudioComparison']['candidates']}, {10, 30})
                data['rows'][1]['candidates'] = [alternate]
                batch.compare_rows(data, refine=True, edges=True, alternatives=True)
                export_data = batch.read_catalogue(root/'catalogue.json')
                export_data['downloadPolicy'] = {}
                batch.export(export_data)
            self.assertEqual(row['refinedAudioComparison']['matchedTrackId'], 20)
            self.assertEqual(row['candidates'][0]['id'], 20)
            self.assertEqual(row['download']['trackId'], 20)
            self.assertEqual((root / 'audio/youtube/abcdefghijk/audio.wav').read_bytes()[44:], audio.astype('<i2').tobytes())
            self.assertTrue((root / 'audio/qobuz/10/audio.flac').is_file())
            resolved = root / 'comparison-results/reviews/abcdefghijk'
            current_link = next(resolved.glob('02-Qobuz-20-*.flac'))
            self.assertTrue(current_link.samefile(root / 'audio/qobuz/20/audio.flac'))
            self.assertTrue((review / '02-Qobuz-10.flac').is_file())
            with (root / 'comparison-results/results.csv').open(encoding='utf-8-sig') as stream:
                report = list(csv.reader(stream))
            self.assertIn('تطابق التسجيل بعد', report[1][4])
            self.assertNotIn('لا تتطابق', report[1][4])

    def test_followup_result_is_used_without_destroying_the_original_check(self):
        quick = {"status": "unconfirmed", "sampleRate": 16000}
        refined = {"status": "matched", "sampleRate": 48000}
        row = {"quickAudioComparison": quick, "refinedAudioComparison": refined}
        self.assertEqual(batch.comparison_for(row), refined)
        self.assertEqual(row["quickAudioComparison"], quick)
        self.assertEqual(batch.comparison_for({"quickAudioComparison": quick}), quick)

    def test_reference_requires_matching_video_binding(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary, patch.object(batch, 'ROOT', Path(temporary)):
            base = Path(temporary) / 'audio/youtube/abcdefghijk'
            base.mkdir(parents=True)
            (base / 'reference.wv').write_bytes(b'audio')
            (base / 'reference.json').write_text(json.dumps({'sourceVideoId': 'lmnopqrstuv', 'referenceFile': 'reference.wv'}))
            self.assertIsNone(batch.source_file('abcdefghijk'))
            (base / 'reference.json').write_text(json.dumps({'sourceVideoId': 'abcdefghijk', 'referenceFile': 'reference.wv'}))
            self.assertEqual(batch.source_file('abcdefghijk'), base / 'reference.wv')

    def test_reference_cannot_escape_its_track_folder(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary, patch.object(batch, 'ROOT', Path(temporary)):
            base = Path(temporary) / 'audio/youtube/abcdefghijk'
            base.mkdir(parents=True)
            (base.parent / 'other.webm').write_bytes(b'audio')
            write_download(base, {'videoId': 'abcdefghijk', 'file': '../other.webm'})
            self.assertIsNone(batch.source_file('abcdefghijk'))

    def test_one_actual_match_resolves_equal_duration_alternatives(self):
        results = [{"trackId": 10, "classification": "not_matched"},
                   {"trackId": 20, "classification": "strong_recording_match"}]
        self.assertEqual(batch.decision(results), ("matched", 20))

    def test_two_matching_candidates_do_not_arbitrarily_pick_one(self):
        results = [{"trackId": i, "classification": "strong_recording_match"} for i in (10, 20)]
        self.assertEqual(batch.decision(results), ("ambiguous", None))

    def test_matching_body_is_not_a_complete_recording_confirmation(self):
        results = [{"trackId": 20, "classification": "matching_body_with_extra_audio"}]
        self.assertEqual(batch.decision(results), ("matching_body_with_extra_audio", 20))

    def test_speed_adjusted_recording_keeps_its_timing_difference_explicit(self):
        result = {'trackId': 20, 'classification': 'matching_body_with_speed_change'}
        self.assertEqual(batch.decision([result]), ('matching_body_with_speed_change', 20))
        self.assertEqual(batch.decision([result, {'trackId': 30, 'classification': 'matching_body_with_extra_audio'}]),
                         ('unconfirmed', None))

    def test_edge_differences_remain_explicit_and_competing_bodies_cannot_choose_a_track(self):
        result = {"trackId": 10, "classification": "matching_body_with_edge_edits"}
        self.assertEqual(batch.decision([result]), ("matching_body_with_edge_edits", 10))
        self.assertEqual(batch.decision([result, {"trackId": 20, "classification": "matching_body_with_extra_audio"}]), ("unconfirmed", None))

    def test_failed_or_missing_audio_cannot_be_a_match(self):
        self.assertEqual(batch.decision([]), ("unconfirmed", None))
        self.assertEqual(batch.decision([{"trackId": 10, "classification": "error"}]), ("unconfirmed", None))


if __name__ == "__main__":
    unittest.main()
