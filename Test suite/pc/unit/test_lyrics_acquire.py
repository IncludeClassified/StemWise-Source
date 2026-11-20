from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.lyrics_acquire import acquire, infer_language_script  # noqa: E402
from stemwise_engine.lyrics_intake import build  # noqa: E402


VIDEO_1 = "a1B2c3D4e5F"
VIDEO_2 = "z9Y8x7W6v5U"
VIDEO_3 = "Q1w2E3r4T5y"


class _Iso:
    def __init__(self, name: str):
        self.name = name


class _Language:
    def __init__(self, code: str):
        self.iso_code_639_1 = _Iso(code)


class _Confidence:
    def __init__(self, code: str, value: float):
        self.language = _Language(code)
        self.value = value


class _Detector:
    def __init__(self, code: str = "EN", confidence: float = 0.99):
        self.code = code
        self.confidence = confidence

    def compute_language_confidence_values(self, text: str):
        return [
            _Confidence(self.code, self.confidence),
            _Confidence("FR", 1.0 - self.confidence),
        ]


class _Client:
    def __init__(self, watch: dict[str, object], lyrics: dict[str, object]):
        self.watch = watch
        self.lyrics = lyrics
        self.watch_calls: list[str] = []

    def get_watch_playlist(self, *, videoId: str, limit: int):
        self.watch_calls.append(videoId)
        value = self.watch[videoId]
        if isinstance(value, Exception):
            raise value
        return value

    def get_lyrics(self, browse_id: str, *, timestamps: bool):
        value = self.lyrics[browse_id]
        if isinstance(value, Exception):
            raise value
        return value


class LyricsAcquireTests(unittest.TestCase):
    def test_acquire_cli_compiles_recovered_pair_without_refetch_or_unrelated_owner_inputs(self):
        self._assert_acquire_cli_recovered_pair(existing_sidecar=False)

    def test_acquire_cli_preserves_existing_sidecar_after_recovering_pair(self):
        self._assert_acquire_cli_recovered_pair(existing_sidecar=True)

    def _assert_acquire_cli_recovered_pair(self, *, existing_sidecar):
        import io
        from contextlib import redirect_stdout
        from unittest.mock import patch
        from stemwise_engine import lyrics_acquire, lyrics_intake
        lyrics_intake.scaffold(self.config, VIDEO_1, 'en', 'Latn', False)
        draft = self.project/'owner-input/drafts'/VIDEO_1
        companion = draft/'owner-notes.txt'
        companion.write_bytes(b'Owner companion')
        client = _Client({VIDEO_1: {'tracks': [{'videoId': VIDEO_1}], 'lyrics': 'body'}},
                         {'body': {'lyrics': 'Provider text'}})
        link = lyrics_acquire.os.link
        def interrupted(source, target):
            link(source, target)
            if Path(target) == draft/'original.txt':
                raise RuntimeError('interrupted after acquired text publication')
        with patch.object(lyrics_intake, 'load_config', return_value=self.config), \
                patch.object(lyrics_acquire, '_load_client', return_value=client), \
                patch.object(lyrics_acquire, '_load_language_detector', return_value=_Detector()), \
                patch.object(lyrics_acquire.os, 'link', side_effect=interrupted), \
                redirect_stdout(io.StringIO()):
            self.assertEqual(lyrics_intake.main(['--config', str(self.root/'config.json'),
                'acquire', '--video-id', VIDEO_1]), 1)
        self.assertTrue((draft/'.acquired-pair.json').exists())
        self.assertEqual((draft/'original.txt').read_bytes(), b'Provider text\n')
        self.assertEqual(client.watch_calls, [VIDEO_1])
        lyrics_intake.scaffold(self.config, VIDEO_2, 'en', 'Latn', False)
        owner = self.project/'owner-input/drafts'/VIDEO_2/'original.txt'
        owner.write_bytes(b'Unrelated owner draft')
        sidecar = self.project/'owner-input'/f'{VIDEO_3}.lyrics.json'
        sidecar.write_bytes(b'Unrelated owner sidecar')
        recovered_sidecar = self.project/'owner-input'/f'{VIDEO_1}.lyrics.json'
        if existing_sidecar:
            recovered_sidecar.write_bytes(b'Existing owner sidecar')
        retry_client, output = _Client({}, {}), io.StringIO()
        with patch.object(lyrics_intake, 'load_config', return_value=self.config), \
                patch.object(lyrics_acquire, '_load_client', return_value=retry_client), \
                patch.object(lyrics_acquire, '_load_language_detector', return_value=_Detector()), \
                redirect_stdout(output):
            code = lyrics_intake.main(['--config', str(self.root/'config.json'), 'acquire'])
        result = json.loads(output.getvalue())
        self.assertEqual(code, 0)
        self.assertEqual(result['failedCount'], 0)
        self.assertEqual(result['compiled'], [] if existing_sidecar else [VIDEO_1])
        self.assertEqual(result['compiledCount'], 0 if existing_sidecar else 1)
        self.assertEqual(retry_client.watch_calls, [])
        self.assertFalse((draft/'.acquired-pair.json').exists())
        self.assertEqual(companion.read_bytes(), b'Owner companion')
        self.assertEqual(owner.read_bytes(), b'Unrelated owner draft')
        self.assertFalse((self.project/'owner-input'/f'{VIDEO_2}.lyrics.json').exists())
        self.assertEqual(sidecar.read_bytes(), b'Unrelated owner sidecar')
        if existing_sidecar:
            self.assertEqual(recovered_sidecar.read_bytes(), b'Existing owner sidecar')
        else:
            compiled = json.loads(recovered_sidecar.read_bytes())
            self.assertEqual(compiled['tracks'][0]['text'], 'Provider text')

    def test_owner_lrc_during_text_or_provenance_publication_rolls_back_only_acquisition_pair(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        from unittest.mock import patch
        from stemwise_engine import lyrics_acquire, worker_publication
        from stemwise_engine.lyrics_intake import scaffold
        for boundary, video_id in (('text', VIDEO_1), ('metadata', VIDEO_2), ('recovery', VIDEO_3)):
            with self.subTest(boundary=boundary):
                scaffold(self.config, video_id, 'en', 'Latn', False)
                draft = self.project/'owner-input/drafts'/video_id
                metadata = draft/'lyrics.meta.json'
                previous = metadata.read_bytes()
                companion = draft/'translation.en.txt'
                companion.write_bytes(b'Owner companion')
                entered, release = threading.Event(), threading.Event()
                link, publish = lyrics_acquire.os.link, worker_publication.publish_owner_file
                if boundary == 'recovery':
                    manifest = json.loads(previous)
                    manifest['sources']['original'] = {'name': 'Provider provenance'}
                    def leave_pair_pending(source, target):
                        link(source, target)
                        if Path(target) == draft/'original.txt':
                            raise RuntimeError('interrupted after text publication')
                    with patch.object(lyrics_acquire.os, 'link', side_effect=leave_pair_pending):
                        with self.assertRaisesRegex(RuntimeError, 'interrupted after text publication'):
                            lyrics_acquire._publish_acquired_draft(draft, manifest, b'Provider text\n', expected_metadata=previous)
                def wait_for_owner():
                    entered.set()
                    if not release.wait(5):
                        raise TimeoutError('Owner publication handshake expired')
                def link_at_boundary(source, target, *args, **kwargs):
                    if boundary == 'text' and Path(target) == draft/'original.txt':
                        wait_for_owner()
                    return link(source, target, *args, **kwargs)
                def publish_at_boundary(destination, replacement, expected, **kwargs):
                    publish(destination, replacement, expected, **kwargs)
                    if boundary in ('metadata', 'recovery') and expected == previous:
                        wait_for_owner()
                client = _Client({video_id: {'tracks': [{'videoId': video_id}], 'lyrics': 'body'}},
                                 {'body': {'lyrics': 'Provider text'}})
                with patch.object(lyrics_acquire.os, 'link', side_effect=link_at_boundary), \
                        patch.object(worker_publication, 'publish_owner_file', side_effect=publish_at_boundary), \
                        ThreadPoolExecutor(max_workers=1) as pool:
                    future = pool.submit(acquire, self.config, selected=[video_id], client=client, detector=_Detector())
                    try:
                        self.assertTrue(entered.wait(5), 'Acquisition did not reach publication boundary')
                        (draft/'original.lrc').write_bytes(b'[00:01.00]Owner original\n')
                    finally:
                        release.set()
                    result = future.result(timeout=8)
                self.assertEqual(result['acquired'], [])
                self.assertEqual((draft/'original.lrc').read_bytes(), b'[00:01.00]Owner original\n')
                self.assertEqual(metadata.read_bytes(), previous)
                self.assertEqual(companion.read_bytes(), b'Owner companion')
                self.assertFalse((draft/'original.txt').exists())
                self.assertFalse((draft/'.acquired-pair.json').exists())
                self.assertFalse(list(draft.glob('*.pending')))

    def test_owner_lrc_after_process_death_rolls_back_current_acquisition_without_provider_replay(self):
        from stemwise_engine.lyrics_intake import scaffold
        for boundary, video_id in (('text', VIDEO_1), ('metadata', VIDEO_2)):
            with self.subTest(boundary=boundary):
                scaffold(self.config, video_id, 'en', 'Latn', False)
                draft = self.project/'owner-input/drafts'/video_id
                previous = (draft/'lyrics.meta.json').read_bytes()
                program = '''
import json, os, sys
from pathlib import Path
import stemwise_engine.lyrics_acquire as acquire
import stemwise_engine.worker_publication as publication
draft, boundary = Path(sys.argv[1]), sys.argv[2]
metadata = draft/'lyrics.meta.json'
manifest = json.loads(metadata.read_bytes())
manifest['sources']['original'] = {'name': 'Provider provenance'}
link, publish = os.link, publication.publish_owner_file
def die_after_link(source, target):
    link(source, target)
    if boundary == 'text' and Path(target) == draft/'original.txt':
        os._exit(17)
def die_after_metadata(*args, **kwargs):
    publish(*args, **kwargs)
    if boundary == 'metadata':
        os._exit(17)
acquire.os.link = die_after_link
publication.publish_owner_file = die_after_metadata
acquire._publish_acquired_draft(draft, manifest, b'Provider text\\n', expected_metadata=metadata.read_bytes())
'''
                result = subprocess.run([sys.executable, '-B', '-c', program, str(draft), boundary],
                    capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 17, result.stderr)
                self.assertTrue((draft/'.acquired-pair.json').is_file())
                (draft/'original.lrc').write_bytes(b'[00:01.00]Owner original\n')
                client = _Client({}, {})
                recovered = acquire(self.config, selected=[video_id], client=client, detector=_Detector())
                self.assertEqual(client.watch_calls, [])
                self.assertEqual(recovered['acquired'], [])
                self.assertEqual((draft/'original.lrc').read_bytes(), b'[00:01.00]Owner original\n')
                self.assertEqual((draft/'lyrics.meta.json').read_bytes(), previous)
                self.assertFalse((draft/'original.txt').exists())
                self.assertFalse((draft/'.acquired-pair.json').exists())

    def test_alternate_original_recovery_preserves_owner_edits_and_rejects_unavailable_prior_metadata(self):
        import os
        from unittest.mock import patch
        from stemwise_engine import lyrics_acquire
        from stemwise_engine.lyrics_intake import scaffold
        scaffold(self.config, VIDEO_1, 'en', 'Latn', False)
        previous = (self.project/'owner-input/drafts'/VIDEO_1/'lyrics.meta.json').read_bytes()
        for edit in ('metadata', 'text-owner', 'prior-missing', 'prior-changed'):
            with self.subTest(edit=edit):
                draft = self.root/edit
                draft.mkdir()
                metadata, text = draft/'lyrics.meta.json', draft/'original.txt'
                metadata.write_bytes(previous)
                manifest = json.loads(previous)
                manifest['sources']['original'] = {'name': 'Provider provenance'}
                with patch.object(lyrics_acquire, '_recover_acquired_pair', side_effect=RuntimeError('cleanup interrupted')):
                    with self.assertRaisesRegex(RuntimeError, 'cleanup interrupted'):
                        lyrics_acquire._publish_acquired_draft(draft, manifest, b'Provider text\n', expected_metadata=previous)
                journal = json.loads((draft/'.acquired-pair.json').read_bytes())
                if edit == 'metadata':
                    metadata.write_bytes(previous+b' ')
                elif edit == 'text-owner':
                    replacement = draft/'owner-replacement.txt'
                    replacement.write_bytes(text.read_bytes())
                    self.assertNotEqual(replacement.stat().st_ino, text.stat().st_ino)
                    os.replace(replacement, text)
                elif edit == 'prior-missing':
                    (draft/journal['previousMetadataFile']).unlink()
                else:
                    (draft/journal['previousMetadataFile']).write_bytes(b'Owner changed retained metadata')
                (draft/'original.lrc').write_bytes(b'[00:01.00]Owner original\n')
                expected_metadata, expected_text = metadata.read_bytes(), text.read_bytes()
                with self.assertRaises(lyrics_acquire.LyricsAcquisitionError):
                    lyrics_acquire._recover_acquired_pair(draft)
                self.assertEqual(metadata.read_bytes(), expected_metadata)
                self.assertEqual(text.read_bytes(), expected_text)
                self.assertEqual((draft/'original.lrc').read_bytes(), b'[00:01.00]Owner original\n')
                self.assertTrue((draft/'.acquired-pair.json').is_file())

    def test_counterpart_search_preserves_failed_and_malformed_surface_outcomes(self):
        from unittest.mock import Mock
        from stemwise_engine.lyrics_acquire import discover_counterpart_candidates
        item = {'videoId': VIDEO_1, 'title': 'Song', 'uploader': 'Artist', 'durationSeconds': 100}
        for replies, expected in (([[], []], ('successful-empty', 'successful-empty')),
                ([RuntimeError('offline'), RuntimeError('offline')], ('failed', 'failed')),
                ([[], RuntimeError('offline')], ('successful-empty', 'failed')),
                ([{'items': []}, []], ('malformed', 'successful-empty')),
                ([[False], []], ('malformed', 'successful-empty')),
                ([[{}], []], ('malformed', 'successful-empty')),
                ([[{'videoId': VIDEO_2, 'title': False, 'artists': [], 'duration': '1:40'}], []],
                    ('malformed', 'successful-empty'))):
            with self.subTest(expected=expected):
                provider = Mock()
                provider.search.side_effect = replies
                result = discover_counterpart_candidates(item, client=provider)
                self.assertEqual(tuple(result['surfaceOutcomes'][surface]['status']
                    for surface in ('songs', 'videos')), expected)
                complete = expected == ('successful-empty', 'successful-empty')
                self.assertEqual(result['searchStatus'], 'complete' if complete else 'incomplete')
                self.assertEqual(result['decision'], 'not-found' if complete else 'search-incomplete')
                self.assertEqual(provider.search.call_count, 2)
        valid = {'videoId': VIDEO_2, 'title': 'Song', 'artists': [{'name': 'Artist'}], 'duration': '1:40'}
        provider = Mock()
        provider.search.side_effect = [[valid, {}], []]
        partial = discover_counterpart_candidates(item, client=provider)
        self.assertEqual(partial['searchStatus'], 'incomplete')
        self.assertEqual(partial['decision'], 'candidates-found')
        self.assertEqual([candidate['videoId'] for candidate in partial['candidates']], [VIDEO_2])
        provider.search.side_effect = [[{**valid, 'artists': [{'name': 'Artist'}, False]}], []]
        malformed = discover_counterpart_candidates(item, client=provider)
        self.assertEqual(malformed['searchStatus'], 'incomplete')
        self.assertEqual(malformed['candidates'], [])
        for nonmatching in ({**valid, 'title': 'Another song'},
                {**valid, 'artists': [{'name': 'Another artist'}]}, {**valid, 'duration': '4:30'}):
            provider.search.side_effect = [[nonmatching], []]
            complete = discover_counterpart_candidates(item, client=provider)
            self.assertEqual(complete['searchStatus'], 'complete')
            self.assertEqual(complete['decision'], 'not-found')

    def test_unrelated_search_rows_need_no_duration_but_matching_unknown_rows_remain_incomplete(self):
        from unittest.mock import Mock
        from stemwise_engine.lyrics_acquire import discover_counterpart_candidates
        from stemwise_engine.source_acquisition import counterpart_search_complete
        item = {'videoId': VIDEO_1, 'title': 'Song', 'uploader': 'Artist', 'durationSeconds': 100}
        valid = {'videoId': VIDEO_2, 'title': 'Song', 'artists': [{'name': 'Artist'}], 'duration': '1:40'}
        unrelated = {'videoId': VIDEO_3, 'title': 'Another song', 'artists': [{'name': 'Another artist'}],
            'duration': None, 'duration_seconds': None, 'videoType': 'MUSIC_VIDEO_TYPE_PODCAST_EPISODE'}
        provider = Mock()
        provider.search.side_effect = [[valid], [unrelated, valid]]
        result = discover_counterpart_candidates(item, client=provider)
        self.assertEqual(result['searchStatus'], 'complete')
        self.assertTrue(counterpart_search_complete(result))
        self.assertEqual(result['surfaceOutcomes']['videos']['excludedUnrelatedRows'], 1)
        self.assertEqual([candidate['videoId'] for candidate in result['candidates']], [VIDEO_2])
        self.assertEqual(result['surfaceErrors'], [])
        for unknown in ({**valid, 'duration': None}, {**unrelated, 'artists': []},
                {**unrelated, 'title': 'Song', 'artists': [{'name': 'Unknown artist'}]},
                {**unrelated, 'title': False}, {**unrelated, 'videoId': 'invalid'}):
            with self.subTest(unknown=unknown):
                provider.search.side_effect = [[valid], [unknown]]
                incomplete = discover_counterpart_candidates(item, client=provider)
                self.assertEqual(incomplete['searchStatus'], 'incomplete')
                self.assertFalse(counterpart_search_complete(incomplete))
                self.assertEqual(incomplete['surfaceOutcomes']['videos']['status'], 'malformed')
                self.assertEqual([candidate['videoId'] for candidate in incomplete['candidates']], [VIDEO_2])

    def test_counterpart_staging_parent_stop_never_publishes_editorial_setup(self):
        from types import SimpleNamespace
        from unittest.mock import Mock, patch
        from stemwise_engine import source_acquisition as acquisition
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
            with self.subTest(failure=type(failure).__name__):
                root = self.root/VIDEO_1
                root.mkdir(exist_ok=True)
                sources = root/'sources.json'
                sources.write_bytes(b'{"prior":"preserved"}')
                operations = SimpleNamespace(save_job=Mock(), write_task=Mock(), pipeline=Mock(),
                    process_song=Mock(return_value='complete'), song_info=Mock(return_value={}),
                    preflight=Mock(), collect_normal=Mock(return_value={}), display_path=str,
                    failure_code=Mock(side_effect=OSError('diagnostic failed')))
                service = acquisition.SourceAcquisition(acquisition.AcquisitionContext(self.root,
                    root, root/'runtime', b'{}'), operations)
                with patch('stemwise_engine.bridge_delivery.require_workflow_budget'), \
                        patch.object(acquisition, 'read_child', return_value=({}, None)), \
                        patch.object(acquisition, 'analysis_summary', return_value={}), \
                        patch.object(acquisition, 'acquire_exact_item_draft', return_value={'decision': 'not-found'}), \
                        patch.object(acquisition, 'discover_counterpart_candidates', return_value={'recommendedVideoId': VIDEO_2}), \
                        patch.object(acquisition, 'collect_cross_id_evidence', side_effect=failure):
                    with self.assertRaises(type(failure)) as raised:
                        service.run({'videoId': VIDEO_1, 'state': 'NEW'})
                self.assertIs(raised.exception, failure)
                self.assertEqual(sources.read_bytes(), b'{"prior":"preserved"}')
                self.assertFalse((root/'agent-review.json').exists())
                operations.failure_code.assert_not_called()
                operations.write_task.assert_not_called()

    def test_counterpart_discovery_stops_after_parent_cancellation_or_deadline(self):
        from unittest.mock import Mock, patch
        from stemwise_engine import lyrics_acquire
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline, ProviderStageDeadline
        item = {'videoId': VIDEO_1, 'title': 'Song', 'uploader': 'Artist', 'durationSeconds': 100}
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
            for boundary in ('client', 'search'):
                with self.subTest(failure=type(failure).__name__, boundary=boundary):
                    client = Mock()
                    client.search.side_effect = failure
                    with patch.object(lyrics_acquire, '_load_client', side_effect=failure):
                        with self.assertRaises(type(failure)) as raised:
                            lyrics_acquire.discover_counterpart_candidates(item,
                                client=client if boundary == 'search' else None)
                    self.assertIs(raised.exception, failure)
                    self.assertEqual(client.search.call_count, int(boundary == 'search'))
        client = Mock()
        client.search.side_effect = [ProviderStageDeadline('route deadline'), []]
        result = lyrics_acquire.discover_counterpart_candidates(item, client=client)
        self.assertEqual(client.search.call_count, 2)
        self.assertEqual(len(result['surfaceErrors']), 1)

    def test_source_collection_preserves_parent_stop_and_existing_candidate(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        from stemwise_engine import source_acquisition as acquisition
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline')):
            with self.subTest(failure=type(failure).__name__):
                candidate = self.root/'candidates'/VIDEO_1/'youtube-music-lyrics.txt'
                candidate.parent.mkdir(parents=True, exist_ok=True)
                candidate.write_bytes(b'prior candidate')
                context = SimpleNamespace(job_dir=self.root, workspace_root=self.root,
                    fetch_info=lambda video: {'id': video}, display_path=str)
                with patch.object(acquisition, 'acquire_exact_item_draft', side_effect=failure), \
                        patch.object(acquisition, 'collect_normal_youtube_candidates', return_value={}):
                    with self.assertRaises(type(failure)) as raised:
                        acquisition.collect_source_evidence(context, VIDEO_1, VIDEO_1, relationship='exact')
                self.assertIs(raised.exception, failure)
                self.assertEqual(candidate.read_bytes(), b'prior candidate')
                self.assertFalse(list(self.root.glob('.ytm-candidate-*')))

    def test_lyrics_optional_diagnostics_do_not_intercept_cancellation_or_retirement(self):
        from unittest.mock import patch
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from stemwise_engine.lyrics_acquire import acquire_exact_item_draft
        for error in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline'),
                      WorkerRetirementError('worker survived')):
            with self.subTest(error=type(error).__name__), \
                    tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name, \
                    patch('stemwise_engine.private_diagnostics.retain_error') as capture:
                with self.assertRaises(type(error)) as raised:
                    acquire_exact_item_draft({'videoId': VIDEO_1, 'position': 1}, Path(name)/'draft',
                        client=_Client({VIDEO_1: error}, {}), detector=object())
                self.assertIs(raised.exception, error)
                capture.assert_not_called()

    def test_damaged_optional_diagnostic_preserves_acquisition_error_and_owner_metadata(self):
        from test_private_diagnostics import damaged_diagnostic_target
        from stemwise_engine.lyrics_acquire import acquire_exact_item_draft
        for kind in ('mismatch', 'directory'):
            with self.subTest(kind=kind), \
                    tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                draft = root/'draft'
                draft.mkdir()
                metadata = draft/'lyrics.meta.json'
                metadata.write_bytes(b'{"owner":"preserved"}')
                client = _Client({VIDEO_1: RuntimeError('provider failed')}, {})
                with damaged_diagnostic_target(kind) as targets:
                    record = acquire_exact_item_draft({'videoId': VIDEO_1, 'position': 1}, draft,
                        diagnostics_dir=root, client=client, detector=object())
                self.assertEqual(record['decision'], 'api-error')
                self.assertEqual(record['reason'], 'RuntimeError')
                self.assertEqual(record['diagnosticError'], 'ValueError')
                self.assertEqual(metadata.read_bytes(), b'{"owner":"preserved"}')
                self.assertFalse((draft/'original.txt').exists())
                self.assertEqual(len(targets), 1)

    def test_source_collection_publishes_real_acquired_draft_inside_owned_scratch(self):
        from types import SimpleNamespace
        from unittest.mock import patch
        import stemwise_engine.source_acquisition as acquisition
        import stemwise_engine.lyrics_acquire as lyrics
        for source_id in (VIDEO_1, VIDEO_2):
            with self.subTest(source_id=source_id), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                draft = root/'drafts'
                draft.mkdir()
                (draft/'original.txt').write_bytes(b'owner text\n')
                client = _Client({source_id: {'tracks': [{'videoId': source_id}], 'lyrics': 'body'}},
                                 {'body': {'lyrics': 'Collected evidence'}})
                context = SimpleNamespace(job_dir=root, workspace_root=root,
                    fetch_info=lambda video: {'id': video}, display_path=str)
                with patch.object(lyrics, '_load_client', return_value=client), \
                        patch.object(lyrics, '_load_language_detector', return_value=_Detector()), \
                        patch.object(acquisition, 'collect_normal_youtube_candidates',
                            return_value={'checked': True, 'candidateCount': 0, 'records': []}):
                    result = acquisition.collect_source_evidence(context, VIDEO_1, source_id,
                        relationship='same-recording')
                self.assertEqual(result['youtubeMusic']['result']['decision'], 'acquired')
                self.assertEqual((root/'candidates'/source_id/'youtube-music-lyrics.txt').read_bytes(),
                                 b'Collected evidence\n')
                self.assertEqual((draft/'original.txt').read_bytes(), b'owner text\n')
                self.assertFalse(list(root.glob('.ytm-candidate-*')))

    def test_concurrent_disjoint_acquisitions_merge_current_report_after_network_work(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        from unittest.mock import patch
        import stemwise_engine.lyrics_acquire as lyrics_acquire
        rendezvous = threading.Barrier(2, timeout=5)
        def lookup(item, draft, **kwargs):
            rendezvous.wait()
            return {**lyrics_acquire._base_record(item), 'decision': 'not-found', 'reason': 'Fixture unavailable'}
        with patch.object(lyrics_acquire, 'acquire_exact_item_draft', side_effect=lookup):
            with ThreadPoolExecutor(max_workers=2) as pool:
                futures = [pool.submit(acquire, self.config, selected=[video], client=object(), detector=object())
                    for video in (VIDEO_1, VIDEO_2)]
                for future in futures:
                    future.result(timeout=8)
        report = json.loads((self.project/'owner-input/lyrics-acquisition-report.json').read_bytes())
        self.assertEqual({row['videoId'] for row in report['records']}, {VIDEO_1, VIDEO_2})
        worklist = json.loads(Path(futures[-1].result()['missingJson']).read_bytes())
        self.assertEqual({row['videoId']: row['status'] for row in worklist['songs']},
            {VIDEO_1: 'not-found', VIDEO_2: 'not-found', VIDEO_3: 'not-processed'})

    def test_process_death_between_text_and_provenance_recovers_without_provider_replay(self):
        from stemwise_engine.lyrics_intake import scaffold
        for owner_edit in (False, True):
            with self.subTest(owner_edit=owner_edit):
                video_id = VIDEO_2 if owner_edit else VIDEO_1
                scaffold(self.config, video_id, 'en', 'Latn', False)
                draft = self.project/'owner-input/drafts'/video_id
                metadata = draft/'lyrics.meta.json'
                previous = metadata.read_bytes()
                (draft/'translation.en.txt').write_bytes(b'Owner companion')
                program = "\nimport json, os, sys\nfrom pathlib import Path\nsys.path.insert(0, sys.argv[1])\nimport stemwise_engine.lyrics_acquire as acquire\ndraft = Path(sys.argv[2])\nmanifest = json.loads((draft/'lyrics.meta.json').read_bytes())\nmanifest['sources']['original'] = {'name': 'Exact provider provenance'}\nlink = os.link\ndef die_after_link(source, target):\n    link(source, target)\n    if Path(target) == draft/'original.txt':\n        os._exit(17)\nacquire.os.link = die_after_link\nacquire._publish_acquired_draft(draft, manifest, b'Provider text\\n',\n    expected_metadata=(draft/'lyrics.meta.json').read_bytes())\n"
                result = subprocess.run([sys.executable, '-B', '-c', program,
                    str(PROJECT_ROOT/'src'), str(draft)], capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 17, result.stderr)
                self.assertEqual(metadata.read_bytes(), previous)
                self.assertTrue((draft/'.acquired-pair.json').is_file())
                self.assertIn('publication is pending', build(self.config, [video_id])['failed'][0]['reason'])
                if owner_edit:
                    metadata.write_bytes(previous+b' ')
                expected = metadata.read_bytes()
                client = _Client({}, {})  # Any provider replay would fail this test.
                recovered = acquire(self.config, selected=[video_id], client=client, detector=_Detector())
                self.assertEqual(client.watch_calls, [])
                self.assertEqual((draft/'original.txt').read_bytes(), b'Provider text\n')
                self.assertEqual((draft/'translation.en.txt').read_bytes(), b'Owner companion')
                if owner_edit:
                    self.assertEqual(metadata.read_bytes(), expected)
                    self.assertTrue((draft/'.acquired-pair.json').exists())
                    self.assertEqual(recovered['acquired'], [])
                else:
                    self.assertEqual(json.loads(metadata.read_bytes())['sources']['original']['name'],
                                     'Exact provider provenance')
                    self.assertFalse((draft/'.acquired-pair.json').exists())

    def test_scaffold_then_acquire_fills_original_and_preserves_companions(self):
        from stemwise_engine.lyrics_intake import scaffold
        scaffold(self.config, VIDEO_1, 'fr', 'Latn', False)
        draft = self.project/'owner-input/drafts'/VIDEO_1
        companion = draft/'translation.en.txt'
        companion.write_bytes(b'Owner companion\n')
        client = _Client({VIDEO_1: {'tracks': [{'videoId': VIDEO_1}], 'lyrics': 'MPLYt_one'}},
                         {'MPLYt_one': {'lyrics': 'Bonjour\nDeuxieme ligne', 'hasTimestamps': False}})
        result = acquire(self.config, selected=[VIDEO_1], client=client, detector=_Detector('FR'))
        self.assertEqual(result['acquired'], [VIDEO_1])
        self.assertEqual((draft/'original.txt').read_text(encoding='utf-8'), 'Bonjour\nDeuxieme ligne\n')
        self.assertEqual(companion.read_bytes(), b'Owner companion\n')
        self.assertEqual(json.loads((draft/'lyrics.meta.json').read_bytes())['originalLanguage'], 'fr')

    def test_scaffold_acquisition_preserves_concurrent_owner_metadata_and_original(self):
        from stemwise_engine.lyrics_intake import scaffold
        for edit in ('metadata', 'original'):
            with self.subTest(edit=edit):
                video_id = VIDEO_1 if edit == 'metadata' else VIDEO_2
                scaffold(self.config, video_id, 'en', 'Latn', False)
                draft = self.project/'owner-input/drafts'/video_id
                metadata = draft/'lyrics.meta.json'
                original_bytes = metadata.read_bytes()
                client = _Client({video_id: {'tracks': [{'videoId': video_id}], 'lyrics': 'MPLYt_one'}},
                                 {'MPLYt_one': {'lyrics': 'Provider text', 'hasTimestamps': False}})
                provider = client.get_lyrics
                def edited(*args, **kwargs):
                    if edit == 'metadata':
                        metadata.write_bytes(original_bytes + b' ')
                    else:
                        (draft/'original.txt').write_bytes(b'Owner text')
                    return provider(*args, **kwargs)
                client.get_lyrics = edited
                result = acquire(self.config, selected=[video_id], client=client, detector=_Detector())
                self.assertEqual(result['acquired'], [])
                if edit == 'metadata':
                    self.assertEqual(metadata.read_bytes(), original_bytes + b' ')
                    self.assertFalse((draft/'original.txt').exists())
                else:
                    self.assertEqual((draft/'original.txt').read_bytes(), b'Owner text')
                    self.assertEqual(metadata.read_bytes(), original_bytes)

    def test_postcommit_metadata_failure_preserves_text_and_recovers_without_provider_replay(self):
        from stemwise_engine.lyrics_intake import scaffold
        from stemwise_engine import common
        from unittest.mock import patch
        scaffold(self.config, VIDEO_1, 'en', 'Latn', False)
        draft = self.project/'owner-input/drafts'/VIDEO_1
        (draft/'translation.en.txt').write_bytes(b'Owner companion')
        client = _Client({VIDEO_1: {'tracks': [{'videoId': VIDEO_1}], 'lyrics': 'MPLYt_one'}},
                         {'MPLYt_one': {'lyrics': 'Provider text', 'hasTimestamps': False}})
        real_write = common.atomic_write_json
        def fail_exchanged(path, value, **kwargs):
            if path.name == '.lyrics.meta.json.publication-conflict.json' and value.get('phase') == 'exchanged':
                raise PermissionError('postcommit marker unavailable')
            return real_write(path, value, **kwargs)
        with patch.object(common, 'atomic_write_json', side_effect=fail_exchanged):
            acquire(self.config, selected=[VIDEO_1], client=client, detector=_Detector())
        self.assertEqual((draft/'original.txt').read_bytes(), b'Provider text\n')
        retry = _Client({}, {})
        acquire(self.config, selected=[VIDEO_1], client=retry, detector=_Detector())
        self.assertEqual(retry.watch_calls, [])
        self.assertEqual((draft/'translation.en.txt').read_bytes(), b'Owner companion')
        self.assertFalse((draft/'.acquired-pair.json').exists())
        self.assertFalse((draft/'.lyrics.meta.json.publication-conflict.json').exists())
        self.assertFalse(build(self.config, [VIDEO_1])['failed'])

    def test_uncertain_postcommit_cleanup_retains_pair_until_retry(self):
        from stemwise_engine.lyrics_intake import scaffold
        from stemwise_engine import common
        from unittest.mock import patch
        scaffold(self.config, VIDEO_1, 'en', 'Latn', False)
        draft = self.project/'owner-input/drafts'/VIDEO_1
        client = _Client({VIDEO_1: {'tracks': [{'videoId': VIDEO_1}], 'lyrics': 'MPLYt_one'}},
                         {'MPLYt_one': {'lyrics': 'Provider text', 'hasTimestamps': False}})
        real_write, unlink = common.atomic_write_json, Path.unlink
        def fail_exchanged(path, value, **kwargs):
            if path.name == '.lyrics.meta.json.publication-conflict.json' and value.get('phase') == 'exchanged':
                raise PermissionError('postcommit marker unavailable')
            return real_write(path, value, **kwargs)
        def retain_displaced(path, *args, **kwargs):
            if path.parent == draft and path.name.startswith('.lyrics.meta.json.') and path.suffix in {'.pending', '.conflict'}:
                raise PermissionError('displaced metadata still busy')
            return unlink(path, *args, **kwargs)
        with patch.object(common, 'atomic_write_json', side_effect=fail_exchanged), \
                patch.object(Path, 'unlink', retain_displaced):
            acquire(self.config, selected=[VIDEO_1], client=client, detector=_Detector())
        self.assertEqual((draft/'original.txt').read_bytes(), b'Provider text\n')
        self.assertTrue((draft/'.acquired-pair.json').exists())
        self.assertTrue((draft/'.lyrics.meta.json.publication-conflict.json').exists())
        retry = _Client({}, {})
        acquire(self.config, selected=[VIDEO_1], client=retry, detector=_Detector())
        self.assertEqual(retry.watch_calls, [])
        self.assertFalse((draft/'.acquired-pair.json').exists())
        self.assertFalse((draft/'.lyrics.meta.json.publication-conflict.json').exists())
        self.assertFalse(build(self.config, [VIDEO_1])['failed'])

    def test_scaffold_acquisition_rolls_back_only_its_text_on_metadata_failure(self):
        from stemwise_engine.lyrics_intake import scaffold
        from unittest.mock import patch
        scaffold(self.config, VIDEO_1, 'en', 'Latn', False)
        draft = self.project/'owner-input/drafts'/VIDEO_1
        metadata = (draft/'lyrics.meta.json').read_bytes()
        (draft/'translation.en.txt').write_bytes(b'Companion')
        client = _Client({VIDEO_1: {'tracks': [{'videoId': VIDEO_1}], 'lyrics': 'MPLYt_one'}},
                         {'MPLYt_one': {'lyrics': 'Provider text', 'hasTimestamps': False}})
        with patch('stemwise_engine.worker_publication.publish_owner_file', side_effect=OSError('injected publication failure')):
            result = acquire(self.config, selected=[VIDEO_1], client=client, detector=_Detector())
        self.assertEqual(result['acquired'], [])
        self.assertFalse((draft/'original.txt').exists())
        self.assertEqual((draft/'lyrics.meta.json').read_bytes(), metadata)
        self.assertEqual((draft/'translation.en.txt').read_bytes(), b'Companion')
        self.assertFalse(list(draft.glob('.lyrics.meta.json.*.pending')))

    def setUp(self) -> None:
        work = PROJECT_ROOT.parents[1]/'Test suite/work'
        work.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=work)
        self.root = Path(self.temporary.name)
        self.project = self.root / "project"
        self.output = self.root / "packages"
        (self.project / "owner-input" / "drafts").mkdir(parents=True)
        (self.output / "work").mkdir(parents=True)
        items = [
            {
                "position": index,
                "videoId": video_id,
                "title": f"Song {index}",
                "uploader": f"Artist {index}",
                "durationSeconds": 100.0 + index,
            }
            for index, video_id in enumerate((VIDEO_1, VIDEO_2, VIDEO_3), start=1)
        ]
        (self.output / "work" / "inventory.json").write_text(
            json.dumps({"items": items}), encoding="utf-8"
        )
        self.config = {"projectDir": str(self.project), "outputDir": str(self.output)}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_exact_id_acquires_compiles_and_keeps_reports_text_free(self) -> None:
        private_text = "DISTINCTIVE_PRIVATE_LYRIC\r\nSecond line\r\n"
        client = _Client(
            {VIDEO_1: {"tracks": [{"videoId": VIDEO_1}], "lyrics": "MPLYt_one"}},
            {"MPLYt_one": {"lyrics": private_text, "hasTimestamps": False}},
        )
        result = acquire(
            self.config,
            selected=[VIDEO_1],
            client=client,
            detector=_Detector("EN"),
        )
        self.assertEqual(result["acquired"], [VIDEO_1])
        draft = self.project / "owner-input" / "drafts" / VIDEO_1
        self.assertEqual(
            (draft / "original.txt").read_text(encoding="utf-8"),
            "DISTINCTIVE_PRIVATE_LYRIC\nSecond line\n",
        )
        manifest = json.loads((draft / "lyrics.meta.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["originalLanguage"], "en")
        self.assertEqual(manifest["originalScript"], "Latn")
        report_text = Path(result["report"]).read_text(encoding="utf-8")
        missing_text = Path(result["missingJson"]).read_text(encoding="utf-8")
        self.assertNotIn("DISTINCTIVE_PRIVATE_LYRIC", report_text)
        self.assertNotIn("DISTINCTIVE_PRIVATE_LYRIC", missing_text)

        compiled = build(self.config, [VIDEO_1])
        self.assertEqual(compiled["built"], [VIDEO_1])
        sidecar = json.loads(
            (self.project / "owner-input" / f"{VIDEO_1}.lyrics.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(sidecar["status"], "partial")
        self.assertEqual(sidecar["tracks"][0]["format"], "plain")

    def test_missing_mismatch_and_api_error_are_isolated_and_identified(self) -> None:
        client = _Client(
            {
                VIDEO_1: {"tracks": [{"videoId": VIDEO_1}], "lyrics": None},
                VIDEO_2: {"tracks": [{"videoId": VIDEO_1}], "lyrics": "MPLYt_wrong"},
                VIDEO_3: RuntimeError("temporary provider failure"),
            },
            {},
        )
        result = acquire(self.config, client=client, detector=_Detector())
        self.assertEqual(result["counts"]["not-found"], 1)
        self.assertEqual(result["counts"]["rejected"], 1)
        self.assertEqual(result["counts"]["api-error"], 1)
        self.assertEqual(result["missingCount"], 3)
        worklist = Path(result["missingCsv"]).read_text(encoding="utf-8-sig")
        for video_id in (VIDEO_1, VIDEO_2, VIDEO_3):
            self.assertIn(video_id, worklist)
            self.assertIn(f"https://music.youtube.com/watch?v={video_id}", worklist)

    def test_existing_owner_original_is_never_overwritten_or_queried(self) -> None:
        draft = self.project / "owner-input" / "drafts" / VIDEO_1
        draft.mkdir()
        (draft / "original.txt").write_text("Owner correction", encoding="utf-8")
        client = _Client({}, {})
        result = acquire(
            self.config,
            selected=[VIDEO_1],
            client=client,
            detector=_Detector(),
        )
        self.assertEqual(result["counts"]["skipped-existing"], 1)
        self.assertEqual(client.watch_calls, [])
        self.assertEqual((draft / "original.txt").read_text(encoding="utf-8"), "Owner correction")

    def test_owner_draft_created_during_provider_call_survives_with_its_metadata(self):
        draft = self.project / 'owner-input/drafts' / VIDEO_1
        client = _Client({VIDEO_1: {'tracks': [{'videoId': VIDEO_1}], 'lyrics': 'MPLYt_one'}}, {})
        def provide(*_args, **_kwargs):
            draft.mkdir()
            (draft / 'original.txt').write_bytes(b'Owner correction')
            (draft / 'lyrics.meta.json').write_bytes(b'Owner metadata')
            return {'lyrics': 'Provider text'}
        client.get_lyrics = provide
        result = acquire(self.config, selected=[VIDEO_1], client=client, detector=_Detector())
        self.assertEqual(result['counts']['skipped-existing'], 1)
        self.assertEqual((draft / 'original.txt').read_bytes(), b'Owner correction')
        self.assertEqual((draft / 'lyrics.meta.json').read_bytes(), b'Owner metadata')

    def test_invalid_provider_markup_is_rejected_without_staging(self) -> None:
        client = _Client(
            {VIDEO_1: {"tracks": [{"videoId": VIDEO_1}], "lyrics": "MPLYt_one"}},
            {"MPLYt_one": {"lyrics": "<html>not lyrics</html>"}},
        )
        result = acquire(
            self.config,
            selected=[VIDEO_1],
            client=client,
            detector=_Detector(),
        )
        self.assertEqual(result["counts"]["rejected"], 1)
        self.assertFalse(
            (self.project / "owner-input" / "drafts" / VIDEO_1 / "original.txt").exists()
        )

    def test_arabic_variety_detection_is_canonicalized_to_ar(self) -> None:
        result = infer_language_script("مرحبا بكم في هذه الأغنية", _Detector("ARY"))
        self.assertEqual(result["language"], "ar")
        self.assertEqual(result["script"], "Arab")

    def test_subset_retry_preserves_previous_report_state(self) -> None:
        success = _Client(
            {VIDEO_1: {"tracks": [{"videoId": VIDEO_1}], "lyrics": "MPLYt_one"}},
            {"MPLYt_one": {"lyrics": "First lyric"}},
        )
        acquire(
            self.config,
            selected=[VIDEO_1],
            client=success,
            detector=_Detector("EN"),
        )
        missing = _Client(
            {VIDEO_2: {"tracks": [{"videoId": VIDEO_2}], "lyrics": None}},
            {},
        )
        result = acquire(
            self.config,
            selected=[VIDEO_2],
            client=missing,
            detector=_Detector("EN"),
        )
        report = json.loads(Path(result["report"]).read_text(encoding="utf-8"))
        decisions = {record["videoId"]: record["decision"] for record in report["records"]}
        self.assertEqual(decisions[VIDEO_1], "acquired")
        self.assertEqual(decisions[VIDEO_2], "not-found")


if __name__ == "__main__":
    unittest.main()
