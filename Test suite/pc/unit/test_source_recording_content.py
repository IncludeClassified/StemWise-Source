"""Selected reference and complete recording decisions replay actual retained bytes."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import builtins

from alignment.test_qwen3_asr_transcribe import output_fixture, generation_fixture
from unit.lyric_content_fixtures import write_recognition
from stemwise_engine import source_recording_content as content
from stemwise_engine.common import atomic_write_json, sha256_file
from stemwise_engine.model_registry import model_assets
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.qwen3_asr_transcribe import DECODER


class SourceRecordingContentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.video, self.source_video = 'abcdefghijk', 'OFFICIAL001'
        self.job, self.evidence = self.root/'jobs'/self.video, self.root/'evidence'/self.video
        (self.job/'drafts').mkdir(parents=True)
        self.evidence.mkdir(parents=True)
        self.url = 'https://www.youtube.com/watch?v='+self.source_video
        self.original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': 'en',
            'script': 'Latn', 'text': 'You feel\nWe sing\n\nYou feel',
            'source': {'name': 'Selected publisher reference', 'url': self.url}}
        self.draft = self.job/'drafts/original.txt'
        self.draft.write_text(self.original['text']+'\n', encoding='utf-8')
        self.metadata = {'videoId': self.video, 'originalLanguage': 'en', 'originalScript': 'Latn',
            'sources': {'original': dict(self.original['source'])}}
        atomic_write_json(self.job/'drafts/lyrics.meta.json', self.metadata)
        self.source_path = self.job/'candidates/published-description.txt'
        self.source_path.parent.mkdir()
        self.source_path.write_text('Published song lyrics\nYou feel\nWe sing\nCredits: publisher\n', encoding='utf-8')
        self.source_record = {'type': 'description', 'path': str(self.source_path),
            'sha256': sha256_file(self.source_path), 'byteCount': self.source_path.stat().st_size}
        self.sources = {'formatVersion': 2, 'videoId': self.video, 'lyricEvidenceSources': [{
            'videoId': self.source_video, 'submittedUrl': self.url,
            'metadata': {'uploader': 'Selected publisher', 'title': 'Published song'},
            'normalYouTube': {'checked': True, 'exactVideoIdConfirmed': True,
                'exactUrl': self.url, 'records': [self.source_record]}}]}
        self.publish_sources()
        self.selected = {'sourceVideoId': self.source_video,
            'path': self.source_path.relative_to(self.job).as_posix(), 'sha256': sha256_file(self.source_path)}
        self.primary_path = self.evidence/'qwen-structure-en.private.json'
        self.publish_primary('You feel We sing You feel')

    def publish_sources(self):
        atomic_write_json(self.job/'sources.json', self.sources)

    def publish_primary(self, text):
        pins = model_assets('lyrics_align_pc')
        audio = {'sha256': 'a'*64, 'byteCount': 64044,
            'stream': {'sampleRate': 16000, 'channelCount': 1, 'frameCount': 32000, 'durationMs': 2000}}
        request = {'audio': audio, 'language': 'en', 'model': {
            'revision': pins['QWEN3_ASR_REVISION'], 'snapshotSha256': pins['QWEN3_ASR_SNAPSHOT_SHA256']}}
        output = output_fixture(request)
        output['result'] = generation_fixture(text, 'en')
        self.cache = {'cacheVersion': 1, 'videoId': self.video,
            'provenance': {'audio': audio, 'language': 'en',
                'modelRevision': pins['QWEN3_ASR_REVISION'],
                'modelSnapshotSha256': pins['QWEN3_ASR_SNAPSHOT_SHA256'], 'decoder': dict(DECODER),
                'helperSha256': semantic_source_identity(Path(content.__file__).with_name('qwen3_asr_transcribe.py')),
                'runtime': {'runtime': output['runtime'], 'supportedLanguages': ['en']}}, 'workerOutput': output}
        atomic_write_json(self.primary_path, self.cache)

    def inputs(self, **overrides):
        return {**dict(job_dir=self.job, evidence_root=self.evidence, video_id=self.video,
            original=self.original, indices=(0, 1, 3), audio_sha='a'*64, duration_ms=2000), **overrides}

    def create(self, **overrides):
        return content.create(**self.inputs(), selected_source=self.selected, **overrides)

    def test_selected_source_and_full_recording_create_replayable_immutable_evidence(self):
        self.assertEqual(content.selection(self.job), self.selected)
        reference = self.create()
        path = self.evidence/reference['path']
        before = path.read_bytes()
        record = content.current_admission(reference, **self.inputs())
        self.assertEqual(record['kind'], content.KIND)
        self.assertEqual(record['sourcePhysicalIndices'], [0, 1, 3])
        self.assertEqual(record['source']['path'], self.selected['path'])
        self.assertEqual(record['source']['byteCount'], self.source_path.stat().st_size)
        self.assertEqual(record['primary']['path'], self.primary_path.name)
        self.assertEqual(record['contrary'], [])
        self.assertNotIn('heardText', json.dumps(record))
        self.assertNotIn('ownerVerification', record)
        decorated = {**self.original, '_spokenTargets': object(), 'presentationNote': 'non-authority',
            'componentLanguages': []}
        self.assertEqual(content.current_admission(reference, **self.inputs(original=decorated)), record)
        pre_editorial = {key: value for key, value in self.original.items() if key != 'source'}
        self.assertEqual(content.current_admission(reference, **self.inputs(original=pre_editorial)), record)
        self.assertEqual(self.create(), reference)
        self.assertEqual(path.read_bytes(), before)
        metadata_path = self.job/'drafts/lyrics.meta.json'
        metadata_raw = metadata_path.read_bytes()
        try:
            metadata_path.write_text(json.dumps(self.metadata, ensure_ascii=False, indent=4)+'\n', encoding='utf-8')
            self.assertEqual(content.current_admission(reference, **self.inputs()), record)
        finally:
            metadata_path.write_bytes(metadata_raw)
        with patch.object(content, 'atomic_write_bytes', side_effect=AssertionError('passive write')), \
                patch('stemwise_engine.common.run_process_tree', side_effect=AssertionError('passive worker')):
            self.assertEqual(content.current_admission(reference, **self.inputs()), record)

    def test_optional_whisper_diagnostic_replays_without_lyrics_runtime_or_numerical_imports(self):
        words = ['We', 'feel', 'We', 'sing', 'You', 'feel']
        cache = {'cacheVersion': 3, 'videoId': self.video, 'model': 'large-v3+unprompted',
            'language': 'en', 'promptSha256': hashlib.sha256(b'').hexdigest(),
            'cacheIdentity': {'retainedProducer': 'diagnostic only'}, 'evidence': {},
            'provenance': {'audio': self.cache['provenance']['audio'], 'durationMs': 2000,
                'decoder': {'promptMode': 'withheld'}},
            'words': [{'word': word.lower(), 'raw': word, 'start': index*.2, 'end': index*.2+.1}
                for index, word in enumerate(words)]}
        path = self.evidence/'transcript-unprompted-mix.private.json'
        atomic_write_json(path, cache)
        reference = self.create()
        original_import = builtins.__import__
        def passive(name, *args, **kwargs):
            if name.split('.')[0] in {'syncalong', 'torch', 'numpy', 'faster_whisper', 'ctranslate2'}:
                raise AssertionError('Passive source decision imported '+name)
            return original_import(name, *args, **kwargs)
        with patch.object(builtins, '__import__', side_effect=passive), \
                patch('stemwise_engine.common.run_process_tree', side_effect=AssertionError('passive worker')):
            record = content.current_admission(reference, **self.inputs())
        self.assertEqual(record['contrary'][0]['scope']['optimalEditCost'], 1)
        self.assertEqual(record['contrary'][0]['role'], 'diagnostic-observation')
        self.assertEqual(path.read_bytes(), content._read(path, self.evidence))
        outside = self.root/'outside-observation.json'
        outside.write_bytes(b'Unrelated private bytes')
        (self.evidence/'transcript-unreadable.private.json').symlink_to(outside)
        self.assertEqual(self.create(), reference, 'an unsafe optional cache vetoed valid current evidence')

    def test_contrary_raw_recording_is_retained_with_scope_without_an_automatic_veto(self):
        path = write_recognition(self.evidence, self.original, (0, 1, 3),
            recognized='We feel We sing You feel')
        cache = json.loads(path.read_bytes())
        cache['videoId'] = self.video
        cache['provenance']['durationMs'] = cache['workerOutput']['durationMs'] = 2000
        atomic_write_json(path, cache)
        before = path.read_bytes()
        reference = self.create()
        record = content.current_admission(reference, **self.inputs())
        self.assertEqual(len(record['contrary']), 1)
        self.assertEqual(record['contrary'][0]['scope']['optimalEditCost'], 1)
        self.assertEqual(record['contrary'][0]['recognition']['sha256'], hashlib.sha256(before).hexdigest())
        self.assertEqual(path.read_bytes(), before)
        cache['workerOutput']['words'][0]['raw'] = 'They'
        atomic_write_json(path, cache)
        with self.assertRaises(ValueError):
            content.current_admission(reference, **self.inputs())

    def test_new_unbound_optional_observation_does_not_revoke_frozen_admission(self):
        path = write_recognition(self.evidence, self.original, (0, 1, 3),
            recognized='We feel We sing You feel')
        cache = json.loads(path.read_bytes())
        cache['videoId'] = self.video
        cache['provenance']['durationMs'] = cache['workerOutput']['durationMs'] = 2000
        atomic_write_json(path, cache)
        bound_raw = path.read_bytes()
        reference = self.create()
        record = content.current_admission(reference, **self.inputs())
        self.assertEqual(len(record['contrary']), 1)
        changed = copy.deepcopy(cache)
        changed['workerOutput']['words'][0]['raw'] = 'They'
        atomic_write_json(path, changed)
        with self.assertRaises(ValueError):
            content.current_admission(reference, **self.inputs())
        path.write_bytes(bound_raw)
        path.unlink()
        with self.assertRaises(ValueError):
            content.current_admission(reference, **self.inputs())
        path.write_bytes(bound_raw)
        additional = self.evidence/'native-asr-additional-mix.private.json'
        atomic_write_json(additional, changed)
        before = {item: item.read_bytes() for item in (
            self.draft, self.job/'drafts/lyrics.meta.json', self.job/'sources.json',
            self.source_path, self.primary_path, path, additional, self.evidence/reference['path'])}
        decisions = set(self.evidence.glob('source-recording-content-*.private.json'))
        try:
            self.assertEqual(content.current_admission(reference, **self.inputs()), record)
            self.assertEqual(set(self.evidence.glob('source-recording-content-*.private.json')), decisions,
                'Passive replay silently renewed the source decision')
        finally:
            self.assertEqual({item: item.read_bytes() for item in before}, before)
        renewed = self.create()
        self.assertNotEqual(renewed, reference)
        self.assertEqual(len(content.current_admission(renewed, **self.inputs())['contrary']), 2)
        self.assertEqual(content.current_admission(reference, **self.inputs()), record)

    def test_missing_repeat_or_wrong_primary_word_rejects_without_changing_prior_evidence(self):
        reference = self.create()
        before = (self.evidence/reference['path']).read_bytes()
        for words in ('You feel We sing', 'We feel We sing You feel'):
            self.publish_primary(words)
            with self.subTest(words=words), self.assertRaises(ValueError):
                self.create()
        self.assertEqual((self.evidence/reference['path']).read_bytes(), before)

    def test_written_reference_once_supports_recorded_adjacent_repeat_without_folding_primary(self):
        self.original['text'] = 'Just one big family, family\nYou feel'
        self.draft.write_text(self.original['text']+'\n', encoding='utf-8')
        self.source_path.write_text('Just one big family\nYou feel\n', encoding='utf-8')
        self.source_record.update(sha256=sha256_file(self.source_path), byteCount=self.source_path.stat().st_size)
        self.selected['sha256'] = self.source_record['sha256']
        self.publish_sources()
        self.publish_primary('Just one big family family You feel')
        inputs = self.inputs(indices=(0, 1))
        reference = content.create(**inputs, selected_source=self.selected)
        record = content.current_admission(reference, **inputs)
        self.assertEqual(record['sourcePhysicalIndices'], [0, 1])
        self.assertEqual(self.original['text'], 'Just one big family, family\nYou feel')
        self.assertEqual(record['primary']['sha256'], sha256_file(self.primary_path))
        before = (self.evidence/reference['path']).read_bytes()
        for primary in ('Just one big family You feel', 'Just one big family family family You feel'):
            self.publish_primary(primary)
            with self.subTest(primary=primary), self.assertRaisesRegex(ValueError, 'every Original occurrence'):
                content.create(**inputs, selected_source=self.selected)
        self.publish_primary('Just one big family family You feel')
        for published in ('Just one large family\nYou feel\n', 'Just one big familiar\nYou feel\n'):
            self.source_path.write_text(published, encoding='utf-8')
            self.source_record.update(sha256=sha256_file(self.source_path), byteCount=self.source_path.stat().st_size)
            self.selected['sha256'] = self.source_record['sha256']
            self.publish_sources()
            with self.subTest(published=published), self.assertRaisesRegex(ValueError, 'does not cover'):
                content.create(**inputs, selected_source=self.selected)
        self.assertEqual((self.evidence/reference['path']).read_bytes(), before)

        # A whole repeated phrase on one performed line also has known published words.
        self.original['text'] = 'You feel You feel\nWe sing'
        self.draft.write_text(self.original['text']+'\n', encoding='utf-8')
        self.source_path.write_text('You feel\nWe sing\n', encoding='utf-8')
        self.source_record.update(sha256=sha256_file(self.source_path), byteCount=self.source_path.stat().st_size)
        self.selected['sha256'] = self.source_record['sha256']
        self.publish_sources()
        self.publish_primary('You feel You feel We sing')
        repeated = content.create(**self.inputs(indices=(0, 1)), selected_source=self.selected)
        self.assertEqual(content.current_admission(repeated, **self.inputs(indices=(0, 1)))[
            'sourcePhysicalIndices'], [0, 1])
        self.assertEqual(self.draft.read_text(encoding='utf-8'), 'You feel You feel\nWe sing\n')

    def test_selected_source_is_frozen_before_comparison_and_never_replaced_by_agreeing_sibling(self):
        self.source_path.write_text('You feel\nThey sing\n', encoding='utf-8')
        self.source_record['sha256'] = self.selected['sha256'] = sha256_file(self.source_path)
        self.source_record['byteCount'] = self.source_path.stat().st_size
        sibling = self.job/'candidates/agreeing-source.txt'
        sibling.write_text('You feel\nWe sing\n', encoding='utf-8')
        self.sources['lyricEvidenceSources'][0]['normalYouTube']['records'].append({
            'type': 'description', 'path': str(sibling), 'sha256': sha256_file(sibling)})
        self.publish_sources()
        with self.assertRaisesRegex(ValueError, 'does not cover'):
            self.create()
        self.assertFalse(list(self.evidence.glob('source-recording-content-*.json')))

    def test_all_changed_input_bindings_refuse_retained_admission(self):
        reference = self.create()
        original = copy.deepcopy(self.original)
        for field, value in (('audio_sha', 'b'*64), ('duration_ms', 2001), ('indices', (0, 1)),
                ('original', {**original, 'text': 'You feel\nThey sing\n\nYou feel'})):
            with self.subTest(field=field), self.assertRaises((ValueError, KeyError)):
                content.current_admission(reference, **self.inputs(**{field: value}))
        protected = {path: path.read_bytes() for path in
            (self.draft, self.job/'drafts/lyrics.meta.json', self.job/'sources.json', self.source_path, self.primary_path)}
        for path in protected:
            try:
                if path.name == 'lyrics.meta.json':
                    changed = copy.deepcopy(self.metadata)
                    changed['sources']['original']['name'] = 'A different selected reference descriptor'
                    atomic_write_json(path, changed)
                else:
                    path.write_bytes(protected[path]+b' ')
                with self.subTest(path=path.name), self.assertRaises((ValueError, KeyError)):
                    content.current_admission(reference, **self.inputs())
            finally:
                path.write_bytes(protected[path])

    def test_mismatched_source_identity_or_unqualified_local_claim_never_admits(self):
        for fault in ('url', 'source-id', 'record-sha', 'size'):
            sources, metadata = copy.deepcopy(self.sources), copy.deepcopy(self.metadata)
            if fault == 'url': metadata['sources']['original']['url'] = 'https://www.youtube.com/watch?v=OTHERID0001'
            elif fault == 'source-id': sources['lyricEvidenceSources'][0]['videoId'] = 'OTHERID0001'
            elif fault == 'record-sha': sources['lyricEvidenceSources'][0]['normalYouTube']['records'][0]['sha256'] = 'b'*64
            else: sources['lyricEvidenceSources'][0]['normalYouTube']['records'][0]['byteCount'] += 1
            atomic_write_json(self.job/'sources.json', sources)
            atomic_write_json(self.job/'drafts/lyrics.meta.json', metadata)
            with self.subTest(fault=fault), self.assertRaises(ValueError):
                self.create()
        self.publish_sources()
        atomic_write_json(self.job/'drafts/lyrics.meta.json', self.metadata)
        for decision in ('source', 'alternative', 'unclear'):
            with self.subTest(decision=decision), self.assertRaisesRegex(ValueError, 'qualified checker'):
                self.create(local_verifier={'decision': decision})

    def test_current_primary_acquisition_without_submitted_url_binds_exact_normal_source(self):
        source = self.sources['lyricEvidenceSources'][0]
        source.pop('submittedUrl')
        source.pop('metadata')
        source.update(videoId=self.video, relationshipToPackage='package-source-upload',
            associationStatus='exact-package-id', youtubeMusic={'checked': True,
                'exactUrl': 'https://music.youtube.com/watch?v='+self.video,
                'result': {'decision': 'skipped-existing'}})
        url = 'https://www.youtube.com/watch?v='+self.video
        source['normalYouTube']['exactUrl'] = url
        self.metadata['sources']['original']['url'] = url
        self.selected['sourceVideoId'] = self.video
        atomic_write_json(self.job/'drafts/lyrics.meta.json', self.metadata)
        self.publish_sources()
        self.assertEqual(content.selection(self.job), self.selected)
        reference = self.create()
        record = content.current_admission(reference, **self.inputs())
        self.assertEqual(record['source']['url'], url)
        before = (self.evidence/reference['path']).read_bytes()
        for fault in ('submittedUrl', 'normal-url'):
            if fault == 'submittedUrl':
                source['submittedUrl'] = 'https://www.youtube.com/watch?v=OTHERID0001'
            else:
                source.pop('submittedUrl')
                source['normalYouTube']['exactUrl'] = 'https://www.youtube.com/watch?v=OTHERID0001'
            self.publish_sources()
            with self.subTest(fault=fault), self.assertRaisesRegex(ValueError, 'acquisition identity'):
                self.create()
        self.assertEqual((self.evidence/reference['path']).read_bytes(), before)

    def test_manual_published_captions_cover_lines_without_borrowing_their_timestamps(self):
        self.source_path.write_text('WEBVTT\n\n00:00.000 --> 00:00.200\nYou feel\n\n'
            '00:00.200 --> 00:00.400\nWe sing\n', encoding='utf-8')
        self.source_record.update(type='manual-subtitle', language='en', format='vtt', sha256=sha256_file(self.source_path),
            byteCount=self.source_path.stat().st_size)
        self.selected['sha256'] = sha256_file(self.source_path)
        self.publish_sources()
        record = content.current_admission(self.create(), **self.inputs())
        self.assertEqual(record['decision'], 'admitted')
        self.assertNotIn('startMs', json.dumps(record))

    def test_written_coverage_precedes_format_language_order_without_recording_read(self):
        description = self.source_path.read_bytes()
        self.source_path.write_text('You feel\n', encoding='utf-8')
        self.source_record.update(sha256=sha256_file(self.source_path), byteCount=self.source_path.stat().st_size)
        self.selected['sha256'] = sha256_file(self.source_path)
        caption = self.job/'candidates/caption.vtt'
        caption.write_text('WEBVTT\n\n00:00.000 --> 00:00.400\nYou feel We sing\n', encoding='utf-8')
        caption_record = {
            'type': 'manual-subtitle', 'language': 'en', 'format': 'vtt', 'path': str(caption),
            'sha256': sha256_file(caption)}
        other = self.job/'candidates/aaa-other-language.vtt'
        other.write_text('WEBVTT\n\n00:00.000 --> 00:00.400\nWrong reference\n', encoding='utf-8')
        records = self.sources['lyricEvidenceSources'][0]['normalYouTube']['records']
        records.extend([caption_record, {**caption_record, 'language': 'fr', 'path': str(other),
            'sha256': sha256_file(other)}])
        self.publish_sources()
        selected = {'sourceVideoId': self.source_video, 'path': caption.relative_to(self.job).as_posix(),
            'sha256': sha256_file(caption)}
        original_read = content._read
        def written_only(path, root):
            self.assertTrue(Path(path).resolve().is_relative_to(self.job.resolve()), 'Selection read recording output')
            return original_read(path, root)
        with patch.object(content, '_read', side_effect=written_only), \
                patch.object(content, '_primary', side_effect=AssertionError('Selection read ASR')):
            self.assertEqual(content.selection(self.job), selected)
        reference = content.create(**self.inputs())
        self.assertEqual(content.current_admission(reference, **self.inputs())['source']['path'], selected['path'])
        self.source_path.write_bytes(description)
        self.source_record.update(sha256=sha256_file(self.source_path), byteCount=self.source_path.stat().st_size)
        self.selected['sha256'] = sha256_file(self.source_path)
        records.reverse()
        self.publish_sources()
        self.assertEqual(content.selection(self.job), selected)
        caption.write_text('WEBVTT\n\n00:00.000 --> 00:00.400\nWrong reference\n', encoding='utf-8')
        caption_record['sha256'] = sha256_file(caption)
        self.publish_sources()
        self.assertEqual(content.selection(self.job), self.selected)
        reference = content.create(**self.inputs())
        self.assertEqual(content.current_admission(reference, **self.inputs())['source']['path'], self.selected['path'])
        self.source_path.write_text('Wrong reference\n', encoding='utf-8')
        self.source_record.update(sha256=sha256_file(self.source_path), byteCount=self.source_path.stat().st_size)
        self.publish_sources()
        self.assertEqual(content.selection(self.job)['path'], selected['path'])
        with patch.object(content, '_primary', side_effect=AssertionError('Incomplete source reached ASR')), \
                self.assertRaisesRegex(ValueError, 'does not cover'):
            content.create(**self.inputs())

    def test_complete_description_beats_partial_manual_before_any_recording_read(self):
        caption = self.job/'candidates/partial-manual.vtt'
        caption.write_text('WEBVTT\n\n00:00.000 --> 00:00.400\nYou feel\n', encoding='utf-8')
        self.sources['lyricEvidenceSources'][0]['normalYouTube']['records'].append({
            'type': 'manual-subtitle', 'language': 'en', 'format': 'vtt', 'path': str(caption),
            'sha256': sha256_file(caption)})
        self.publish_sources()
        with patch.object(content, '_primary', side_effect=AssertionError('Body selection read ASR')):
            selected = content.selection(self.job)
        reference = content.create(**self.inputs())
        record = content.current_admission(reference, **self.inputs())
        self.assertEqual(selected, self.selected)
        self.assertEqual(record['source']['path'], self.selected['path'])

    def test_failed_caption_acquisition_preserves_complete_description_admission(self):
        from stemwise_engine import source_acquisition
        with patch.object(source_acquisition, '_download_subtitle', side_effect=OSError('caption unavailable')):
            normal = source_acquisition.collect_normal_youtube_candidates({
                'id': self.source_video, 'description': 'You feel\nWe sing\n',
                'subtitles': {'en': [{'ext': 'vtt', 'url': 'https://www.youtube.com/api/timedtext?v='+self.source_video}]}},
                self.job/'candidates/acquired', video_id=self.source_video)
        acquired, failed = normal['records']
        self.assertEqual(failed, {'type': 'manual-subtitle', 'surface': 'normal-youtube',
            'language': 'en', 'automatic': False, 'status': 'unavailable', 'reason': 'OSError'})
        self.sources['lyricEvidenceSources'][0]['normalYouTube'] = normal
        self.publish_sources()
        selected = {'sourceVideoId': self.source_video,
            'path': Path(acquired['path']).relative_to(self.job).as_posix(), 'sha256': acquired['sha256']}
        self.assertEqual(content.selection(self.job), selected)
        reference = content.create(**self.inputs())
        record = content.current_admission(reference, **self.inputs())
        self.assertEqual(record['source']['path'], selected['path'])
        self.assertEqual(record['sourcesSha256'], sha256_file(self.job/'sources.json'))
        self.assertEqual(json.loads((self.job/'sources.json').read_bytes())[
            'lyricEvidenceSources'][0]['normalYouTube']['records'][1], failed)
        before = (self.evidence/reference['path']).read_bytes()
        self.publish_primary('We feel We sing You feel')
        with self.assertRaisesRegex(ValueError, 'every Original occurrence'):
            content.create(**self.inputs())
        self.publish_primary('You feel We sing You feel')
        for malformed in ({**failed, 'status': 'unknown'},
                {key: value for key, value in failed.items() if key != 'status'},
                {**failed, 'path': 'invented.vtt'}):
            normal['records'][1] = malformed
            self.publish_sources()
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                content.selection(self.job)
        normal['records'][1] = failed
        Path(acquired['path']).write_text('You feel\n', encoding='utf-8')
        acquired.update(sha256=sha256_file(Path(acquired['path'])), bytes=Path(acquired['path']).stat().st_size)
        self.publish_sources()
        with patch.object(content, '_primary', side_effect=AssertionError('Incomplete source reached ASR')), \
                self.assertRaisesRegex(ValueError, 'does not cover'):
            content.create(**self.inputs())
        self.assertEqual((self.evidence/reference['path']).read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
