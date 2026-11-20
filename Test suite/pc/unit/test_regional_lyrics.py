from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.contract as contract
import stemwise_engine.song_enrichment_v2 as song_enrichment_v2

FIXTURE = ROOT / 'Test suite/android/unit/resources/fixtures/enrichment/regional-lyrics.json'


class RegionalLyricsTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads(FIXTURE.read_text(encoding='utf-8'))
        self.schema = Draft202012Validator(json.loads((ROOT /
            'StemWise/contracts/enrichment/song-enrichment-v2.schema.json').read_text(encoding='utf-8')))

    def complete_estimated_document(self):
        from stemwise_engine.lyrics_tokenization import display_word_ranges
        document = copy.deepcopy(self.document)
        document['artwork'] = {'file': 'cover.png', 'kind': 'other', 'source': {'name': 'Fixture'}}
        lyrics = document['lyrics']
        lyrics.update(status='complete')
        lyrics.pop('reason')
        original = lyrics['tracks'][0]
        original['language'] = 'tr'
        rows = [('Bir iki üç', [(100, 300), (400, 600), (700, 1000)]),
                ('Olası tekrar', [(1100, 1300), (1400, 1800)]), ('Dört', [(2000, 2300)])]
        original['lines'] = [{'text': text, 'contentStatus': 'admitted', 'occurrenceStatus': 'supported',
            'timing': {'status': 'estimated', 'startMs': intervals[0][0], 'endMs': intervals[-1][1],
                       'basis': 'Complete-target acoustic prediction; unverified'},
            'words': [{'startUtf16': start, 'endUtf16': end, 'timing': {'status': 'estimated',
                'startMs': interval[0], 'endMs': interval[1], 'basis': 'Acoustic word prediction; unverified'}}
                for (start, end), interval in zip(display_word_ranges(text), intervals)]} for text, intervals in rows]
        for kind, language, texts in (
                ('translation', 'en', ['One two three', 'Possible repeat', 'Four']),
                ('transliteration', 'tr-fonipa', ['biɾ iki ytʃ', 'olasɯ tekɾaɾ', 'dœɾt'])):
            lyrics['tracks'].append({'id': kind, 'kind': kind, 'language': language,
                'format': 'regional', 'basedOnTrackId': original['id'], 'source': {'name': 'Reviewed fixture'},
                'lines': [{'text': text, 'contentStatus': 'admitted', 'occurrenceStatus': 'supported',
                    'timing': {**line['timing'], 'basis': 'Line correspondence inherited from Original; not companion acoustic word timing'}}
                    for text, line in zip(texts, original['lines'])]})
        return document

    def test_complete_estimates_and_inherited_companions_roundtrip_through_public_and_installed_contracts(self):
        import installed_contract
        document = self.complete_estimated_document()
        song_enrichment_v2.validate_song(document, installed_duration_ms=5000)
        song_enrichment_v2.require_publication_lyrics(document['lyrics'])
        self.assertEqual([], list(self.schema.iter_errors(document)))
        installed = installed_contract.build_installed_document(document, original_sha256='a'*64,
            media={'original.opus': {'sha256': 'a'*64, 'byteLength': 1234}}, content_revision=1,
            payload_sha256='b'*64, artwork_sha256='c'*64, artwork_bytes=100)
        decoded = json.loads(json.dumps(installed))
        installed_contract.validate_installed_document(decoded, expected_video_id='a1B2c3D4e5F', expected_original_sha256='a'*64)
        self.assertEqual(document['lyrics'], decoded['lyrics'])
        schema = Draft202012Validator(json.loads((ROOT/'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json').read_text(encoding='utf-8')))
        self.assertEqual([], list(schema.iter_errors(decoded)))

    def test_companion_estimates_cannot_upgrade_or_change_original_occurrence_placement(self):
        for mutate in (
                lambda lyrics: lyrics['tracks'][1]['lines'][0]['timing'].update(status='supported'),
                lambda lyrics: lyrics['tracks'][1]['lines'][0]['timing'].update(startMs=101),
                lambda lyrics: lyrics['tracks'][1]['lines'].pop(),
                lambda lyrics: lyrics['tracks'][2]['lines'][0].update(text='[123]')):
            with self.subTest(mutate=mutate):
                document = self.complete_estimated_document()
                mutate(document['lyrics'])
                if document['lyrics']['tracks'][1]['lines'][0]['timing']['status'] == 'supported':
                    document['lyrics']['tracks'][1]['lines'][0]['timing'].pop('basis')
                with self.assertRaises(contract.ContractError):
                    song_enrichment_v2.validate_song(document, installed_duration_ms=5000)

    def test_public_estimates_reject_partial_plain_missing_words_and_untimed_progress(self):
        for mutate in (
                lambda lyrics: lyrics.update(status='partial', reason='Still pending'),
                lambda lyrics: lyrics['tracks'][0]['lines'][0].pop('words'),
                lambda lyrics: lyrics['tracks'][0]['lines'][0]['words'][0].update(timing={'status': 'none', 'reason': 'Unknown word end'}),
                lambda lyrics: lyrics['tracks'][1].update(format='plain', text='One two three\nPossible repeat\nFour')):
            with self.subTest(mutate=mutate):
                document = self.complete_estimated_document()
                mutate(document['lyrics'])
                if document['lyrics']['tracks'][1]['format'] == 'plain':
                    document['lyrics']['tracks'][1].pop('lines')
                private = copy.deepcopy(document['lyrics'])
                private.update(status='partial', reason='Private timing progress')
                song_enrichment_v2.validate_lyrics(private, duration_ms=5000)
                with self.assertRaises(contract.ContractError):
                    song_enrichment_v2.require_publication_lyrics(document['lyrics'])

    def test_roundtrip_keeps_supported_estimated_and_untimed_words(self):
        decoded = song_enrichment_v2.load_and_validate_song_json(
            json.dumps(self.document).encode('utf-8'), installed_duration_ms=5000)
        self.assertEqual(self.document, decoded)
        self.assertEqual([], list(self.schema.iter_errors(decoded)))
        words = decoded['lyrics']['tracks'][0]['lines'][0]['words']
        self.assertEqual(['supported', 'estimated', 'none'], [word['timing']['status'] for word in words])
        self.assertNotIn('startMs', words[2]['timing'])

    def test_rejects_false_authority_and_malformed_discriminators(self):
        line = self.document['lyrics']['tracks'][0]['lines'][0]
        for mutate in (
            lambda row: row.update(contentStatus='disputed'),
            lambda row: row.update(occurrenceStatus='unknown'),
            lambda row: row['timing'].update(status='invented'),
            lambda row: row['words'][1]['timing'].pop('basis'),
            lambda row: row['words'][2]['timing'].update(startMs=600),
            lambda row: row['words'][1]['timing'].update(extra=True),
            lambda row: row['words'].pop(),
            lambda row: row['words'][1]['timing'].update(endMs=1900),
        ):
            with self.subTest(mutate=mutate):
                document = copy.deepcopy(self.document)
                mutate(document['lyrics']['tracks'][0]['lines'][0])
                with self.assertRaises(contract.ContractError):
                    song_enrichment_v2.validate_song(document, installed_duration_ms=5000)

    def test_source_text_has_no_performed_placement(self):
        song_enrichment_v2.validate_song(self.document, installed_duration_ms=5000)
        row = self.document['lyrics']['tracks'][0]['lines'][1]
        self.assertEqual('source-only', row['contentStatus'])
        self.assertEqual({'status': 'none', 'reason': 'Occurrence unresolved'}, row['timing'])

    def test_public_package_validation_rejects_unqualified_regional_claims(self):
        import stemwise_engine.packages as packages
        with self.assertRaisesRegex(packages.PackageValidationError, 'requires complete timed lyrics'):
            packages.validate_document(self.document)

    def test_installed_schema_keeps_the_same_strict_timing_contract(self):
        import installed_contract
        payload = copy.deepcopy(self.document)
        payload['artwork'] = {'file': 'cover.png', 'kind': 'other', 'source': {'name': 'Fixture'}}
        original = {'sha256': 'a' * 64, 'byteLength': 1234}
        installed = installed_contract.build_installed_document(payload,
            original_sha256=original['sha256'], media={'original.opus': original}, content_revision=1,
            payload_sha256='b' * 64, artwork_sha256='c' * 64, artwork_bytes=100)
        schema = Draft202012Validator(json.loads((ROOT /
            'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json').read_text(encoding='utf-8')))
        self.assertEqual([], list(schema.iter_errors(installed)))
        self.assertEqual(self.document['lyrics'], installed['lyrics'])
        installed['lyrics']['tracks'][0]['lines'][0]['words'][2]['timing']['startMs'] = 0
        self.assertTrue(list(schema.iter_errors(installed)))


if __name__ == '__main__':
    unittest.main()
