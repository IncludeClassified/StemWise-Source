"""Work selection preserves completed content and never claims FLAC execution."""
import importlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[3]


class EnrichmentPlanTest(unittest.TestCase):
    def setUp(self):
        self.plan = importlib.import_module('stemwise_media.enrichment_plan')
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)
        self.root = self.workspace / 'PC side/qobuz-comparison'
        self.state = self.workspace / 'PC side/stemwise-playlist-builder/future-songs/policy-state/current'
        for module in (self.plan, self.plan.batch):
            patcher = patch.object(module, 'ROOT', self.root)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.rows = []
        self.library = {'flac': [], 'youtube': [], 'playlist': []}

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding='utf-8')

    def flac(self, track):
        relative = f'audio/qobuz/{track}/song.flac'
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'fixture-file-binding-only')
        from qobuz_fixtures import write_download
        write_download(path.parent, {'qobuzTrackId': track, 'file': path.name})
        self.library['flac'].append({'trackId': track, 'file': relative, 'title': f'Song {track}'})
        return relative

    def row(self, video, source, track=None, job=None, analysis=False):
        relative = (f'audio/qobuz/{track}/song.flac' if source == 'qobuz' else
                    f'audio/youtube/{video}/source.webm' if source == 'youtube' else None)
        row = {'videoId': video, 'source': source, 'trackId': track, 'file': relative,
               'position': len(self.rows) + 1, 'title': video}
        self.library['playlist'].append(row)
        self.rows.append({'videoId': video, 'candidates': [{'id': track}] if track else [],
                          'ownerRecordingDecision': 'ignored_unavailable' if source == 'unavailable' else None})
        if source == 'youtube':
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture-file-binding-only')
            self.write(path.parent / 'download.json', {'videoId': video, 'file': path.name})
            self.library['youtube'].append({'videoId': video, 'file': relative, 'title': video})
        if job:
            self.write(self.state / 'jobs' / video / 'job.json', {'videoId': video, 'state': job})
        if analysis:
            self.write(self.state / 'runtime/work/checkpoints' / f'{video}.json', {'videoId': video, 'status': 'complete'})

    def build(self):
        return self.plan.build_plan(self.library, {'rows': self.rows}, self.state)

    def test_selected_aliases_share_one_task_and_unselected_flac_is_preserved(self):
        self.flac(11)
        self.flac(22)
        self.row('abcdefghijk', 'qobuz', 11, 'DELIVERED', True)
        self.row('lmnopqrstuv', 'qobuz', 11, 'LINE_UNRESOLVED', True)
        before = {path: path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        plan = self.build()
        self.assertEqual(plan['playlistCounts'], {'first_audio_analysis': 2})
        self.assertEqual(plan['recordingCounts'], {'first_audio_analysis': 1})
        tasks = plan['audioRecordings']
        self.assertEqual([task['recordingKey'] for task in tasks], ['qobuz:11'])
        self.assertEqual(tasks[0]['preferredPlaylistVideoIds'], ['abcdefghijk', 'lmnopqrstuv'])
        self.assertFalse(tasks[0]['reuseAudioResults'])
        self.assertEqual(before, {path: path.read_bytes() for path in self.root.rglob('*') if path.is_file()})
        self.assertEqual(tasks[0]['execution'], 'canonical_local_recording_enrichment')
        self.assertEqual(plan['purpose'], 'work_selection_only_not_completion_or_campaign_policy')

    def test_unselected_youtube_version_and_its_existing_work_are_preserved_without_planning(self):
        self.row('abcdefghijk', 'youtube')
        self.row('lmnopqrstuv', 'youtube', job='LINE_UNRESOLVED', analysis=True)
        self.library['playlist'].pop()
        self.rows.pop()
        before = {path: path.read_bytes() for path in self.workspace.rglob('*') if path.is_file()}
        tasks = self.build()['audioRecordings']
        self.assertEqual([task['recordingKey'] for task in tasks],
                         ['youtube:abcdefghijk'])
        self.assertEqual(before, {path: path.read_bytes() for path in self.workspace.rglob('*') if path.is_file()})

    def test_stale_playlist_youtube_file_binding_is_rejected(self):
        self.row('abcdefghijk', 'youtube')
        self.library['playlist'][0]['file'] = 'audio/youtube/abcdefghijk/stale.webm'
        with self.assertRaisesRegex(ValueError, 'YouTube binding differs'):
            self.build()

    def test_unchanged_audio_reuses_analysis_and_preserves_finished_jobs(self):
        self.row('abcdefghijk', 'youtube', job='DELIVERED', analysis=True)
        self.row('lmnopqrstuv', 'youtube', job='LINE_UNRESOLVED', analysis=True)
        self.row('01234567890', 'youtube')
        self.row('XXXXXXXXXXX', 'unavailable')
        before = {p: p.read_bytes() for p in self.state.rglob('*.json')}
        plan = self.build()
        self.assertEqual(plan['playlistCounts'], {'preserve_delivered': 1, 'resume_existing': 1,
                                                'first_enrichment': 1, 'ignored_unavailable': 1})
        self.assertEqual([task['reuseAudioResults'] for task in plan['audioRecordings']], [True, True, False])
        self.assertEqual(plan['ignoredPlaylistEntries'][0]['videoId'], 'XXXXXXXXXXX')
        self.assertEqual(before, {p: p.read_bytes() for p in self.state.rglob('*.json')})

    def test_native_plan_uses_exact_current_recording_namespace(self):
        from stemwise_engine.workflow_context import native_state_root
        from stemwise_engine.common import sha256_file
        relative = self.flac(11)
        self.row('abcdefghijk', 'qobuz', 11, 'DELIVERED', True)
        identity = {'trackId': 11, 'sha256': sha256_file(self.root/relative), 'recordingKey': 'qobuz:11'}
        native = native_state_root(self.state.parent.parent, identity)
        path = native/'jobs/abcdefghijk/job.json'
        checkpoint = native/'runtime/work/checkpoints/abcdefghijk.json'
        for state, expected in (('DELIVERED', 'preserve_delivered'), ('LINE_UNRESOLVED', 'resume_existing')):
            self.write(path, {'videoId': 'abcdefghijk', 'recordingInput': identity, 'state': state})
            self.write(checkpoint, {'videoId': 'abcdefghijk', 'status': 'complete'})
            before = path.read_bytes(), checkpoint.read_bytes()
            task = self.build()['audioRecordings'][0]
            self.assertEqual(task['action'], expected)
            self.assertTrue(task['reuseAudioResults'])
            self.assertEqual((path.read_bytes(), checkpoint.read_bytes()), before)
        audio = self.root/relative
        import numpy as np
        import soundfile as sf
        sf.write(audio, np.ones((480, 2))*.1, 48000, format='FLAC', subtype='PCM_16')
        from qobuz_fixtures import write_download
        write_download(audio.parent, {'qobuzTrackId': 11, 'file': audio.name})
        task = self.build()['audioRecordings'][0]
        self.assertEqual(task['action'], 'first_audio_analysis')
        self.assertFalse(task['reuseAudioResults'])

    def test_missing_or_rejected_flac_never_falls_back_to_youtube(self):
        relative = self.flac(11)
        self.row('abcdefghijk', 'qobuz', 11, 'DELIVERED', True)
        metadata = self.root / relative
        metadata.unlink()
        with self.assertRaisesRegex(ValueError, 'missing or truncated'):
            self.build()
        metadata.write_bytes(b'fixture-file-binding-only')
        self.write(metadata.parent / 'download.json', {'qobuzTrackId': 11, 'file': metadata.name, 'status': 'owner_rejected'})
        with self.assertRaisesRegex(ValueError, 'missing, rejected, or stale'):
            self.build()
        self.assertEqual(json.loads((self.state / 'jobs/abcdefghijk/job.json').read_text())['state'], 'DELIVERED')

    def test_stale_binding_or_unapproved_skip_is_rejected(self):
        self.flac(11)
        self.row('abcdefghijk', 'qobuz', 11)
        self.library['playlist'][0]['file'] = 'audio/qobuz/22/song.flac'
        with self.assertRaisesRegex(ValueError, 'binding differs'):
            self.build()
        self.library['playlist'][0]['file'] = 'audio/qobuz/11/song.flac'
        self.row('XXXXXXXXXXX', 'unavailable')
        self.rows[-1]['ownerRecordingDecision'] = None
        with self.assertRaisesRegex(ValueError, 'owner disposition'):
            self.build()

    def test_public_plan_command_saves_work_selection_with_no_analysis_claim(self):
        self.row('abcdefghijk', 'youtube', job='DELIVERED', analysis=True)
        self.write(self.root / 'catalogue.json', {'rows': self.rows})
        self.write(self.root / 'comparison-results/library-files.json', self.library)
        audio = importlib.import_module('stemwise_media.audio_library')
        with patch.object(self.plan, 'STATE', self.state), \
                patch.object(self.plan.batch, 'build_library', return_value=self.library), \
                patch('sys.stdout', new_callable=io.StringIO) as stdout, \
                patch('sys.stderr', new_callable=io.StringIO):
            result = audio.main(['plan'])
        self.assertEqual(result, 0)
        summary = json.loads(stdout.getvalue())
        self.assertFalse(summary['analysisStarted'])
        self.assertTrue(summary['localAudioAnalysisAvailable'])
        self.assertTrue(summary['localAudioCampaignIntegration'])
        saved = json.loads((self.root / 'comparison-results/enrichment-plan.json').read_text())
        self.assertEqual(saved['recordingCounts'], {'preserve_delivered': 1})

    def test_no_op_library_export_preserves_exact_catalogue_bytes_and_hash_binding(self):
        import hashlib
        self.row('abcdefghijk', 'youtube')
        self.rows[0]['position'] = 1
        document = {'rows': self.rows, 'librarySelectionCounts': {
            'flacFiles': 0, 'retainedYouTubeVersions': 1, 'preferredSources': {'youtube': 1}}}
        path = self.root / 'catalogue.json'
        before = json.dumps(document, indent=2).replace('\n', '\r\n').encode('utf-8')
        path.write_bytes(before)
        self.plan.batch.export_library(self.plan.batch.read_catalogue(path))
        self.assertEqual(path.read_bytes(), before)
        projection = json.loads((self.root / 'comparison-results/library-files.json').read_text(encoding='utf-8'))
        self.assertEqual(projection['catalogueSha256'], hashlib.sha256(before).hexdigest())


if __name__ == '__main__':
    unittest.main()
