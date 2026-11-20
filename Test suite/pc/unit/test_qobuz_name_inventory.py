"""Title availability cannot turn a cover, missing source or rejected file into a match."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
from stemwise_media import name_inventory as inventory


class NameInventoryTests(unittest.TestCase):
    def test_audio_link_can_resolve_a_misleading_title_without_changing_owner_preference(self):
        choice = {'source': 'youtube', 'file': 'original.webm'}
        row = {'position': 1, 'videoId': 'source', 'title': 'Misleading title',
               'ownerAudioSelection': choice, 'candidates': [],
               'metadataSelection': {'basis': 'matching_artist_title_and_rendition_not_located_in_checked_catalogue'}}
        library = {'flac': [{'trackId': 20, 'title': 'Actual title', 'artist': 'Singer'}],
                   'playlist': [{'position': 1, 'performanceTrackIds': [20, 99]}]}
        result = inventory.name_rows({'rows': [row]}, library)
        self.assertEqual(result[0]['availableTrackIds'], [20])
        self.assertEqual(result[0]['status'], 'retained_version')
        self.assertIn('الصوت', result[0]['note'])
        self.assertEqual(row['ownerAudioSelection'], choice)

    def test_saved_alternate_is_available_but_a_cover_or_unknown_source_is_not_confirmed(self):
        track = {'id': 10, 'title': 'Song', 'artist': 'Singer', 'version': 'Studio'}
        row = {'position': 1, 'videoId': 'source', 'title': 'Singer - Song (Live)',
               'candidates': [track], 'metadataSelection': {'status': 'deferred', 'basis': 'matching_live_rendition_or_venue_not_located'}}
        cover = {**row, 'position': 2, 'title': 'Another Singer - Song',
                 'metadataSelection': {'status': 'deferred', 'basis': 'matching_artist_title_and_rendition_not_located_in_checked_catalogue'}}
        unknown = {**row, 'position': 3, 'title': None, 'candidates': [],
                   'metadataSelection': {'status': 'deferred', 'basis': 'source_metadata_unavailable'}}
        library = {'flac': [{'trackId': 10, 'title': 'Song', 'artist': 'Singer', 'version': 'Studio', 'album': 'Album'}]}
        result = inventory.name_rows({'rows': [row, cover, unknown]}, library)
        self.assertEqual(result[0]['availableTrackIds'], [10])
        self.assertEqual(result[0]['status'], 'retained_version')
        self.assertEqual(result[1]['availableTrackIds'], [])
        self.assertEqual(result[1]['status'], 'not_found')
        self.assertEqual(result[2]['status'], 'unidentified')

    def test_corrected_title_relinks_availability_without_replacing_owner_audio(self):
        choice = {'source': 'youtube', 'file': 'original.webm'}
        row = {'position': 1, 'videoId': 'source', 'title': 'Wrong title',
               'ownerDisplayTitle': 'Correct title', 'ownerAudioSelection': choice,
               'candidates': [{'id': 10}], 'metadataSelection': {'status': 'selected_candidate'},
               'flacNameMatch': {'trackIds': [20], 'note': 'Owner corrected title'}}
        result = inventory.name_rows({'rows': [row]}, {'flac': [{'trackId': 10}, {'trackId': 20}]})
        self.assertEqual(result[0]['availableTrackIds'], [20])
        self.assertEqual(result[0]['title'], 'Correct title')
        self.assertEqual(row['ownerAudioSelection'], choice)
        # Removing a rejected track from the retained inventory must remove availability too.
        self.assertEqual(inventory.name_rows({'rows': [row]}, {'flac': [{'trackId': 10}]})[0]['status'], 'not_found')


if __name__ == '__main__':
    unittest.main()
