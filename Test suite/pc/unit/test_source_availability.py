"""Source lookup outcomes never establish that lyric material does not exist."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from stemwise_engine.source_acquisition import refresh_sources_availability


class SourceAvailabilityTests(unittest.TestCase):
    def setUp(self):
        work = Path(__file__).resolve().parents[2] / 'work'
        work.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.draft = self.root / 'drafts'

    def sources(self, decision='not-found'):
        music = {'checked': True, 'result': {'decision': decision}}
        normal = {'checked': True, 'records': []}
        return {'videoId': 'KbKqkR3Q6SA',
                'exactSurfaceChecks': {'youtubeMusic': music, 'normalYouTube': normal},
                'lyricEvidenceSources': [{'videoId': 'KbKqkR3Q6SA',
                    'youtubeMusic': music, 'normalYouTube': normal}]}

    def test_absent_search_api_failure_and_missing_material_remain_distinct(self):
        for sources, expected in (({'videoId': 'KbKqkR3Q6SA'}, 'not-searched'),
                                  (self.sources('api-error'), 'lookup-failed'),
                                  (self.sources(), 'not-found')):
            with self.subTest(expected=expected):
                result = refresh_sources_availability(sources, self.draft)
                self.assertEqual(result['lyricsAvailability'], expected)
                self.assertFalse(result['containsLyrics'])

    def test_failed_caption_and_counterpart_search_do_not_claim_not_found(self):
        for failed in ('caption', 'counterpart'):
            sources = self.sources()
            if failed == 'caption':
                sources['lyricEvidenceSources'][0]['normalYouTube']['records'] = [
                    {'type': 'automatic-caption', 'status': 'unavailable', 'reason': 'http-error'}]
            else:
                sources['counterpartDiscovery'] = {'decision': 'api-error'}
            with self.subTest(failed=failed):
                self.assertEqual(refresh_sources_availability(sources, self.draft)['lyricsAvailability'],
                                 'lookup-failed')

    def test_tampered_candidate_remains_rejected_instead_of_missing(self):
        source = self.root / 'candidate.txt'
        source.write_text('known lyric candidate\n', encoding='utf-8')
        sources = self.sources()
        sources['lyricEvidenceSources'][0]['normalYouTube']['records'] = [
            {'type': 'description', 'lyricUse': 'potential-body', 'path': str(source),
             'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}]
        self.assertEqual(refresh_sources_availability(sources, self.draft)['lyricsAvailability'],
                         'candidate-evidence')
        source.write_text('changed bytes\n', encoding='utf-8')
        result = refresh_sources_availability(sources, self.draft)
        self.assertEqual(result['lyricsAvailability'], 'candidate-rejected')
        self.assertFalse(result['containsLyrics'])

    def test_status_exposes_lookup_failure_without_private_material_or_absence_claim(self):
        import json
        from stemwise_engine.workflow_status import project_job_summary
        sources = refresh_sources_availability(self.sources('api-error'), self.draft)
        sources['privateFixture'] = 'not a public lyric body'
        (self.root/'sources.json').write_text(json.dumps(sources), encoding='utf-8')
        summary = project_job_summary({'videoId': 'KbKqkR3Q6SA', 'state': 'NEEDS_LYRICS',
            'deliveryAllowed': False}, workspace_root=self.root, root=self.root,
            runtime=self.root/'runtime', job_dir=self.root,
            recording_evidence_inputs=None, review_attempts={})
        self.assertEqual(summary['sourceAvailability']['status'], 'lookup-failed')
        self.assertIn('lookup-failed', summary['sourceAvailability']['issues'])
        self.assertNotIn('not a public lyric body', json.dumps(summary))

    def test_malformed_and_incomplete_surface_results_never_claim_successful_absence(self):
        for case in ('caption-row', 'music-result', 'normal-container', 'unchecked',
                     'counterpart-incomplete', 'counterpart-not-searched'):
            with self.subTest(case=case):
                sources = self.sources()
                record = sources['lyricEvidenceSources'][0]
                expected = 'lookup-failed'
                if case == 'caption-row':
                    record['normalYouTube']['records'] = [None]
                elif case == 'music-result':
                    record['youtubeMusic']['result'] = []
                elif case == 'normal-container':
                    record['normalYouTube'] = ''
                elif case == 'unchecked':
                    record['youtubeMusic']['checked'] = False
                    expected = 'not-searched'
                elif case == 'counterpart-incomplete':
                    sources['counterpartDiscovery'] = {'decision': 'not-found',
                        'searchStatus': 'incomplete', 'surfaceOutcomes': {'songs': {'status': 'failed'}}}
                else:
                    sources['counterpartDiscovery'] = {'decision': 'skipped-insufficient-metadata'}
                    expected = 'not-searched'
                self.assertEqual(refresh_sources_availability(sources, self.draft)['lyricsAvailability'], expected)


if __name__ == '__main__':
    unittest.main()
