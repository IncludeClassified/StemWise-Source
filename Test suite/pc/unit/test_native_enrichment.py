"""Native enrichment must preserve its source identity across ZIP and install boundaries."""
import copy
import sys
import tempfile
import unittest
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[3]
from stemwise_engine.common import sha256_file
from stemwise_engine.packages import create_child_zip, read_child, validate_document, PackageValidationError
from stemwise_engine.song_enrichment_v2 import validate_song
from installed_contract import build_installed_document, validate_installed_document, installed_digest
from test_packages import song_document, package_config, PNG_1X1
from jsonschema import Draft202012Validator
import json


def native_document():
    document = song_document('abcdefghijk')
    document['source']['recording'] = {
        'recordingKey': 'qobuz:11', 'trackId': 11,
        'file': 'PC side/qobuz-comparison/audio/qobuz/11/native.flac',
        'sha256': 'a' * 64, 'byteCount': 1234, 'durationMs': 1000}
    return document


class NativeEnrichmentTest(unittest.TestCase):
    def test_native_zip_is_portable_and_does_not_strip_recording_binding(self):
        document = native_document()
        validate_song(document)
        with tempfile.TemporaryDirectory(dir=PROJECT / 'Test suite/work') as temporary:
            root = Path(temporary)
            cover = root / 'cover.png'
            cover.write_bytes(PNG_1X1)
            config = package_config(root)
            child = root / 'abcdefghijk.stemwise.zip'
            create_child_zip(child, document, cover, config)
            actual, _ = read_child(child, config)
            self.assertEqual(actual, document)
            with self.assertRaises(PackageValidationError):
                validate_document(actual, recording_input={**document['source']['recording'], 'sha256': 'b' * 64})

    def test_native_identity_rejects_unknown_fields_bad_paths_and_inconsistent_keys(self):
        for changes in ({'trackId': True}, {'trackId': 0}, {'trackId': 12},
                        {'recordingKey': 'qobuz:011'}, {'file': '../native.flac'},
                        {'file': 'C:/native.flac'}, {'file': 'PC side\\native.flac'},
                        {'file': 'PC side/./native.flac'}, {'file': '/native.flac'},
                        {'byteCount': 0}, {'durationMs': 0}, {'extra': True}):
            with self.subTest(changes=changes):
                document = native_document()
                document['source']['recording'].update(changes)
                with self.assertRaises(ValueError):
                    validate_song(document)
        document = native_document()
        document['analysis']['durationMs'] = 1100
        with self.assertRaises(ValueError):
            validate_song(document)

    def test_production_schema_native_paths_reject_traversal(self):
        pc_schema = PROJECT / 'StemWise/contracts/enrichment/song-enrichment-v2.schema.json'
        validator = Draft202012Validator(json.loads(pc_schema.read_bytes()))
        self.assertFalse(list(validator.iter_errors(native_document())))
        for path in ('../native.flac', 'PC side/../native.flac', 'PC side/./native.flac', '/native.flac',
                     'C:/native.flac', 'PC side\\native.flac', 'PC side//native.flac'):
            with self.subTest(path=path):
                document = native_document()
                document['source']['recording']['file'] = path
                self.assertTrue(list(validator.iter_errors(document)))

    def test_installed_native_document_keeps_flac_and_exact_source_binding(self):
        payload = native_document()
        document = build_installed_document(payload, original_sha256='a' * 64,
            media={'original.flac': {'sha256': 'a' * 64, 'byteLength': 1234}},
            content_revision=1, payload_sha256='c' * 64,
            artwork_sha256='d' * 64, artwork_bytes=100)
        self.assertEqual(document['source'], payload['source'])
        self.assertEqual(document['media']['files'][0]['path'], 'original.flac')
        validate_installed_document(document, expected_video_id='abcdefghijk', expected_original_sha256='a' * 64)
        for field, value in (('sha256', 'e' * 64), ('byteCount', 1235)):
            changed = copy.deepcopy(document)
            changed['source']['recording'][field] = value
            changed['install']['generatedDigest'] = installed_digest(changed)
            with self.assertRaises(ValueError):
                validate_installed_document(changed, expected_video_id='abcdefghijk', expected_original_sha256='a' * 64)
        with self.assertRaises(ValueError):
            build_installed_document(payload, original_sha256='a' * 64,
                media={'original.opus': {'sha256': 'a' * 64, 'byteLength': 1234}},
                content_revision=1, payload_sha256='c' * 64, artwork_sha256='d' * 64, artwork_bytes=100)


if __name__ == '__main__':
    unittest.main()
