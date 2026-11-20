"""Recording review owns spoken forms/languages; display spans stay unchanged."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from stemwise_engine import spoken_targets
from stemwise_engine.lyrics_alignment import build_lexical_occurrence_mapping
from stemwise_engine.lyrics_align_pc import _forced_alignment_request
from stemwise_engine.lyric_content import confirm_content, require_content, ContentAdmission
from stemwise_engine.workflow_records import LyricsContentBlocker
from lyric_content_fixtures import write_recognition

ROOT = Path(__file__).resolve().parents[3]
VIDEO = 'abcdefghijk'


class SpokenTargetsTests(unittest.TestCase):
    def test_intake_spoken_request_preserves_raw_utf16_coordinates(self):
        from stemwise_engine.lyrics_intake import compile_draft
        from stemwise_engine.lyrics_alignment import _physical_lines, build_predicted_sidecar
        from stemwise_engine.lyrics_tokenization import display_words
        from stemwise_engine.mms_forced_alignment import _request_plan
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            original, review = self.fixture(root)
            original.update(language='en', text='\u2003\t🎵 In 2026\u00a0\n \t\n\u00a0hello friend  ')
            original.pop('componentLanguages')
            drafts = root/'drafts'
            drafts.mkdir()
            (drafts/'original.txt').write_text(original['text']+'\n', encoding='utf-8')
            (drafts/'lyrics.meta.json').write_text(json.dumps({'videoId': VIDEO,
                'originalLanguage': 'en', 'originalScript': 'Latn',
                'sources': {'original': {'name': 'Exact recording review'}}}), encoding='utf-8')
            lyrics = compile_draft(drafts, {'outputDir': str(root/'output')}, {VIDEO}, expected_video_id=VIDEO)
            original = lyrics['tracks'][0]
            expected = [[(5, 7, 'In'), (8, 12, '2026')], [(1, 6, 'hello'), (7, 13, 'friend')]]
            review['binding'] = spoken_targets.binding(VIDEO, original, (0, 2), 'a'*64)
            review['spans'] = [dict(sourcePhysicalIndex=source, startUtf16=start, endUtf16=end,
                displayText=text, spokenText=spoken, language='en')
                for source, words, forms in zip((0, 2), expected, (('in', 'twenty twenty six'), ('hello', 'friend')))
                for (start, end, text), spoken in zip(words, forms)]
            review['review']['candidateSha256'] = spoken_targets._digest(
                {key: review[key] for key in ('formatVersion', 'binding', 'spans')})
            path = root/spoken_targets.PLAN_FILE
            path.write_text(json.dumps(review, ensure_ascii=False), encoding='utf-8')
            before = copy.deepcopy(original), path.read_bytes()
            mapping = build_lexical_occurrence_mapping(VIDEO, original, (0, 2))
            mapping['_spokenTargets'] = spoken_targets.load(root, VIDEO, original, (0, 2), 'a'*64)
            physical = _physical_lines(original['text'])
            words = [display_words(physical[source]) for source in (0, 2)]
            request, line_ids, word_ids, _ = _forced_alignment_request(VIDEO, [0, 2], mapping,
                words, duration_ms=4000)
            self.assertEqual(words, expected)
            _, acoustic, _, _ = _request_plan(request)
            self.assertEqual(acoustic, ['in', 'twenty', 'twenty', 'six', 'hello', 'friend'])
            forced = {'engine': 'torchaudio-mms-fa', 'lines': [
                {'lineIndex': index, 'sourcePhysicalIndex': source, 'lineId': line_ids[index],
                 'startMs': index*2000, 'endMs': index*2000+1500,
                 'words': [{'wordId': owner, 'startMs': index*2000+word*800,
                            'endMs': index*2000+word*800+700} for word, owner in enumerate(word_ids[index])]}
                for index, source in enumerate((0, 2))]}
            candidate = build_predicted_sidecar(lyrics, mapping, forced, 4000, basis='Known acoustic fixture')
            self.assertEqual([line['text'] for line in candidate['tracks'][0]['lines']],
                [original['text'].split('\n')[source] for source in (0, 2)])
            self.assertEqual([[(word['startUtf16'], word['endUtf16']) for word in line['words']]
                for line in candidate['tracks'][0]['lines']], [[(5, 7), (8, 12)], [(1, 6), (7, 13)]])
            self.assertEqual((original, path.read_bytes()), before)
            with self.assertRaises(LyricsContentBlocker):
                _forced_alignment_request(VIDEO, [0, 2], mapping,
                    [display_words(line.strip()) for line in physical if line], duration_ms=4000)
            self.assertEqual((original, path.read_bytes()), before)

    def test_recording_compare_normalizes_only_recognition_routing_for_tag_variants(self):
        import stemwise_engine.recording_evidence as recording_evidence
        for language in ('mul', 'MUL', 'mul-Latn', 'en', 'EN', 'en-US'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                job = root/'jobs'/VIDEO
                job.mkdir(parents=True)
                multilingual = language in {'mul', 'MUL', 'mul-Latn'}
                if multilingual:
                    original, _ = self.fixture(job, language=language)
                    draft = original['text']
                    transcript = 'in twenty twenty six first one two hello merhaba sawubona'
                    metadata = {'originalLanguage': language, 'originalScript': 'Latn',
                        'componentLanguages': original['componentLanguages']}
                else:
                    draft = 'hello friend\nmorning light'
                    transcript = 'hello friend morning light'
                    metadata = {'originalLanguage': language, 'originalScript': 'Latn'}
                identity = {'sha256': 'a'*64, 'stream': {'durationMs': 10000,
                    'sampleRate': 48000, 'channelCount': 2, 'frameCount': 480000}}
                before = copy.deepcopy(metadata), {p: p.read_bytes() for p in job.iterdir()}
                with patch.object(recording_evidence, 'read_recording_draft',
                        return_value=(draft, 'c'*64, metadata, 'd'*64)), \
                        patch.object(recording_evidence, 'file_identity', return_value=identity), \
                        patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript',
                            return_value=(transcript, {}, True)) as recognize:
                    result = recording_evidence.compare({'projectDir': str(root/'PC side/builder')},
                        root, VIDEO, root/'source.wav', 'a'*64)
                self.assertEqual(recognize.call_args.kwargs['language'], None if multilingual else 'en')
                self.assertTrue(result['structureSupport']['accepted'])
                self.assertEqual((metadata, {p: p.read_bytes() for p in job.iterdir()}), before)

    def test_multilingual_tag_variants_require_fresh_exact_spoken_reviews(self):
        from stemwise_engine.endpoint_owsm_ctc import _routes_for_context
        hashes = set()
        for language in ('mul', 'MUL', 'mul-Latn'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                original, value = self.fixture(root)
                original.update(language=language, componentLanguages=['EN-us', 'fr-FR'], text='hello bonjour')
                (root/spoken_targets.PLAN_FILE).unlink()
                self.assertTrue(spoken_targets.needed(original))
                with self.assertRaises(LyricsContentBlocker):
                    spoken_targets.load(root, VIDEO, original, (0,), 'a'*64)
                value['binding'] = spoken_targets.binding(VIDEO, original, (0,), 'a'*64)
                hashes.add(value['binding']['originalSha256'])
                value['spans'] = [dict(sourcePhysicalIndex=0, startUtf16=start, endUtf16=end,
                    displayText=text, spokenText=text, language=owner)
                    for start, end, text, owner in ((0, 5, 'hello', 'en'), (6, 13, 'bonjour', 'fr'))]
                def publish():
                    candidate = json.dumps({key: value[key] for key in ('formatVersion', 'binding', 'spans')},
                        ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
                    value['review']['candidateSha256'] = hashlib.sha256(candidate).hexdigest()
                    (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
                publish()
                before = copy.deepcopy(original), (root/spoken_targets.PLAN_FILE).read_bytes()
                plan = spoken_targets.load(root, VIDEO, original, (0,), 'a'*64)
                self.assertEqual(plan.words, ((('hello', 'en'), ('bonjour', 'fr')),))
                context = {'language': language, 'targetLine': 0, 'request': {'lines': [{'words': [
                    {'acousticText': word, 'acousticLanguage': owner} for word, owner in plan.words[0]]}]}}
                self.assertEqual(_routes_for_context(context, original['componentLanguages']), [])
                changed = {**original, 'language': 'mul-Latn' if language != 'mul-Latn' else 'MUL'}
                with self.assertRaises(LyricsContentBlocker):
                    spoken_targets.load(root, VIDEO, changed, (0,), 'a'*64)
                self.assertEqual((original, (root/spoken_targets.PLAN_FILE).read_bytes()), before)
                for owner in ('de', 'zxx'):
                    value['spans'][1]['language'] = owner
                    publish()
                    with self.subTest(owner=owner), self.assertRaises(LyricsContentBlocker):
                        spoken_targets.load(root, VIDEO, original, (0,), 'a'*64)
                value['spans'][1]['language'] = 'fr'
                publish()
                (root/'listening-review.txt').write_text('changed listening evidence', encoding='utf-8')
                with self.assertRaises(LyricsContentBlocker):
                    spoken_targets.load(root, VIDEO, original, (0,), 'a'*64)
                with self.assertRaises(LyricsContentBlocker):
                    plan.require_current()
        self.assertEqual(len(hashes), 3)

    def test_independent_owsm_contexts_preserve_reviewed_route_owners_and_result_indices(self):
        from dataclasses import replace
        from types import SimpleNamespace
        from alignment.test_alignment_content_admission import AlignmentContentAdmissionTests
        from alignment.test_owsm_ctc_forced_alignment import request_fixture
        from stemwise_engine import alignment_endpoints as endpoints, lyrics_align_pc as parent
        from stemwise_engine import owsm_ctc_forced_alignment as worker
        from stemwise_engine.lyrics_tokenization import display_words
        for layout in ('separate', 'mixed'):
            with self.subTest(layout=layout), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                original, value = self.fixture(root)
                if layout == 'separate':
                    original['text'] = 'In 2026\nmerhaba dünya\nsawubona mhlaba'
                    forms = [[('in', 'en'), ('twenty twenty six', 'en')],
                             [('merhaba', 'tr'), ('dünya', 'tr')], [('sawubona', 'zu'), ('mhlaba', 'zu')]]
                    value['binding'] = spoken_targets.binding(VIDEO, original, (0, 1, 2), 'a'*64)
                    value['spans'] = [{'sourcePhysicalIndex': source, 'startUtf16': start, 'endUtf16': end,
                        'displayText': text, 'spokenText': spoken, 'language': language}
                        for source, (line, targets) in enumerate(zip(original['text'].split('\n'), forms))
                        for (start, end, text), (spoken, language) in zip(display_words(line), targets)]
                    value['review']['candidateSha256'] = spoken_targets._digest(
                        {key: value[key] for key in ('formatVersion', 'binding', 'spans')})
                    (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
                plan = spoken_targets.load(root, VIDEO, original, (0, 1, 2), 'a'*64)
                original['_spokenTargets'] = plan
                mapping = build_lexical_occurrence_mapping(VIDEO, original, (0, 1, 2))
                mapping['_spokenTargets'] = plan
                before = copy.deepcopy(mapping['performedOccurrences'])
                reviewed_raw = (root/spoken_targets.PLAN_FILE).read_bytes()
                words = [display_words(line) for line in original['text'].split('\n')]
                groups = endpoints._independent_endpoint_inputs(original, mapping, words, set(worker.ROUTES_BY_LANGUAGE))
                expected = {'en': [0], 'tr': [1], 'zu': [2]} if layout == 'separate' else {'en': [0, 1]}
                self.assertEqual({language: group[0] for language, group in groups.items()}, expected)
                stage, _ = AlignmentContentAdmissionTests().endpoint_stage(root/'stage')
                stage.request = replace(stage.request,
                    context=replace(stage.request.context, config={'projectDir': str(ROOT/'PC side/stemwise-playlist-builder'),
                        'analysisTimeoutMinutes': 1}),
                    text=replace(stage.request.text, original=original, sources=[0, 1, 2], source_occurrences=(0, 1, 2),
                        lexical_mapping=mapping, display_word_lines=words),
                    evidence=replace(stage.request.evidence, independent_inputs=groups),
                    audio=replace(stage.request.audio, canonical_mix=lambda: (Path('/private/audio.wav'),
                        {'sha256': 'a'*64, 'byteCount': 12, 'stream': {'durationMs': 10000}})))
                requests = []
                def load(**arguments):
                    request_base, _, _, _ = parent._forced_alignment_request(VIDEO, arguments['sources'],
                        arguments['occurrence_mapping'], arguments['display_word_lines'], duration_ms=10000)
                    request = parent._owsm_ctc_request(arguments['config'], arguments['language'], arguments['audio'],
                        arguments['audio_identity'], request_base['lines'], 10000)
                    worker._validated_request(request)
                    requests.append(request)
                    return tuple({'lineId': line['lineId'], 'sourcePhysicalIndex': line['sourcePhysicalIndex'],
                        'wordIds': [word['wordId'] for word in line['words']]} for line in request['lines']), True
                stage.services = replace(stage.services, workers=replace(stage.services.workers, load_owsm=load))
                with patch.object(parent, '_worker_cuda_device', return_value=request_fixture()['runtime']['cudaDevice']):
                    candidates, _ = stage.load_independent_witness(endpoints.OWSM_CTC_FAMILY)
                    full, _, _, _ = parent._forced_alignment_request(VIDEO, [0, 1, 2], mapping, words, duration_ms=10000)
                    incompatible = parent._owsm_ctc_request(stage.request.context.config, 'en', Path('/private/audio.wav'),
                        {'sha256': 'a'*64, 'byteCount': 12}, full['lines'], 10000)
                    with self.assertRaisesRegex(ValueError, 'acoustic language'):
                        worker._validated_request(incompatible)
                for indices in expected.values():
                    for index in indices:
                        self.assertEqual(candidates[index]['lineId'], mapping['performedOccurrences'][index]['occurrenceId'])
                        self.assertEqual(candidates[index]['sourcePhysicalIndex'], index)
                        self.assertEqual(candidates[index]['wordIds'],
                            [word['occurrenceWordId'] for word in mapping['performedOccurrences'][index]['words']])
                self.assertEqual(candidates[2] is None, layout == 'mixed')
                self.assertEqual(sum(len(request['lines']) for request in requests), 3 if layout == 'separate' else 2)
                self.assertTrue(all('acousticLanguage' in word for request in requests
                    for line in request['lines'] for word in line['words']))
                self.assertEqual(mapping['performedOccurrences'], before)
                self.assertEqual((root/spoken_targets.PLAN_FILE).read_bytes(), reviewed_raw)

    def parent_workflow(self, root):
        from types import SimpleNamespace
        from unittest.mock import Mock
        from workflow_fixture import initialized_workflow
        from stemwise_engine.workflow_review import review_template
        workflow = initialized_workflow(root)
        job = workflow._base_job(VIDEO, f'https://youtu.be/{VIDEO}')
        workflow._save_job(job)
        directory = workflow._job_dir(VIDEO)
        original, plan = self.fixture(directory)
        drafts = directory/'drafts'
        drafts.mkdir()
        (drafts/'original.txt').write_text(original['text']+'\n', encoding='utf-8')
        (drafts/'lyrics.meta.json').write_text(json.dumps({'videoId': VIDEO,
            'originalLanguage': 'mul', 'originalScript': 'Latn',
            'componentLanguages': original['componentLanguages'],
            'sources': {'original': {'name': 'Fixture recording review'}}}), encoding='utf-8')
        (directory/'sources.json').write_text(json.dumps({'lyricEvidenceSources': [{'videoId': VIDEO}]}), encoding='utf-8')
        (directory/'agent-review.json').write_text(json.dumps(review_template(VIDEO)), encoding='utf-8')
        job.update(state='NEEDS_LYRICS', outcome={'code': 'lyrics-content-unresolved',
            'sourceStage': 'recording-evidence', 'reasons': ['pronunciation-support-unavailable'],
            'draftSha256': workflow._recording_draft_sha(job),
            'contentBinding': {'canonicalPlainSha256': 'c'*64, 'audioSha256': 'a'*64,
                               'sourcePhysicalIndices': [0, 1, 2]}})
        workflow._save_job(job)
        evidence = write_recognition(workflow._alignment_state_path(VIDEO).parent, original, (0, 1, 2),
            recognized='in twenty twenty six first one two hello merhaba sawubona')
        workflow._require_selected_audio = Mock()
        workflow._pipeline = Mock(return_value=object())
        workflow._completed_analysis_audio = Mock(return_value={'path': root/'mix.wav', 'sha256': 'a'*64})
        workflow._review_inputs = Mock(side_effect=lambda current, **_: {
            'draftSha256': workflow._recording_draft_sha(current)})
        workflow._alignment_worker_environment = Mock(return_value={})
        workflow._run_primary_worker = Mock(return_value=SimpleNamespace(returncode=0,
            stdout=json.dumps({'status': 'complete', 'privateTranscript': str(evidence)}), stderr=''))
        return workflow, job, original, plan, evidence

    def test_parent_gates_resume_after_only_spoken_target_repair(self):
        for action in ('recording-evidence', 'validate'):
            with self.subTest(action=action), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                workflow, job, original, _, evidence = self.parent_workflow(Path(name))
                directory = workflow._job_dir(VIDEO)
                if action == 'recording-evidence':
                    job['outcome'].pop('contentBinding')
                    workflow._save_job(job)
                plan = directory/spoken_targets.PLAN_FILE
                raw = plan.read_bytes()
                plan.unlink()
                if action == 'recording-evidence':
                    invoke = lambda: workflow._recording_evidence_action(VIDEO, on_start=lambda _: None)
                else:
                    invoke = lambda: workflow._validate_impl(VIDEO)
                with self.assertRaises(LyricsContentBlocker):
                    invoke()
                before = {path: path.read_bytes() for path in directory.rglob('*') if path.is_file()}
                plan.write_bytes(raw)
                admission = confirm_content(original, (0, 1, 2), 'c'*64, 'a'*64,
                    evidence_path=evidence, evidence_root=evidence.parent, adjudication_job_dir=directory)
                self.assertEqual(admission.spoken_targets['path'], spoken_targets.PLAN_FILE)
                result = invoke()
                self.assertEqual(result['state'], 'NEEDS_LYRICS')
                self.assertEqual(workflow._recording_draft_sha(job), job['outcome']['draftSha256'])
                for path, content in before.items():
                    if path.name != 'job.json':
                        self.assertEqual(path.read_bytes(), content)
                if action == 'recording-evidence':
                    workflow._run_primary_worker.assert_called_once()
                    self.assertIn('recordingContentEvidence', workflow._load_job(VIDEO))
                else:
                    workflow._run_primary_worker.assert_not_called()

    def test_parent_spoken_repair_rejects_stale_bindings_and_other_content_blockers(self):
        faults = ('evidence', 'audioSha256', 'originalSha256', 'occurrenceMappingSha256',
                  'candidateSha256', 'sequence', 'mixed', 'malformed-reason')
        for action in ('recording-evidence', 'validate'):
            for fault in faults:
                with self.subTest(action=action, fault=fault), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                    workflow, job, _, plan, _ = self.parent_workflow(Path(name))
                    directory = workflow._job_dir(VIDEO)
                    if fault == 'evidence':
                        (directory/'listening-review.txt').write_text('stale', encoding='utf-8')
                    elif fault in ('sequence', 'mixed', 'malformed-reason'):
                        job['outcome']['reasons'] = (['content-sequence-uncertain'] if fault == 'sequence' else
                            ['pronunciation-support-unavailable',
                             'invalid reason' if fault == 'malformed-reason' else 'content-sequence-uncertain'])
                        workflow._save_job(job)
                    else:
                        target = plan['review'] if fault == 'candidateSha256' else plan['binding']
                        target[fault] = 'b'*64
                        if target is plan['binding']:
                            plan['review']['candidateSha256'] = spoken_targets._digest(
                                {key: plan[key] for key in ('formatVersion', 'binding', 'spans')})
                        (directory/spoken_targets.PLAN_FILE).write_text(json.dumps(plan), encoding='utf-8')
                    before = {path: path.read_bytes() for path in directory.rglob('*') if path.is_file()}
                    with self.assertRaises(LyricsContentBlocker):
                        if action == 'recording-evidence':
                            workflow._recording_evidence_action(VIDEO, on_start=lambda _: None)
                        else:
                            workflow._validate_impl(VIDEO)
                    workflow._run_primary_worker.assert_not_called()
                    self.assertEqual({path: path.read_bytes() for path in before}, before)

    def test_adjudication_maps_display_spans_through_reviewed_spoken_languages(self):
        from stemwise_engine.lyric_content import require_recording_adjudication
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.recording_evidence import adjudicate_retained_recording
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workspace = AlignmentWorkspace(Path(name), workspace_root=Path(name))
            root = Path(name)/'jobs'/VIDEO
            root.mkdir(parents=True)
            original, value = self.fixture(root)
            value['spans'][-2]['spokenText'] = 'ışık'
            value['review']['candidateSha256'] = spoken_targets._digest(
                {key: value[key] for key in ('formatVersion', 'binding', 'spans')})
            (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value), encoding='utf-8')
            plan = spoken_targets.load(root, VIDEO, original, (0, 1, 2), 'a'*64)
            original = {**original, '_spokenTargets': plan}
            (root/'drafts').mkdir()
            (root/'drafts/original.txt').write_text(original['text'], encoding='utf-8')
            (root/'drafts/lyrics.meta.json').write_text(json.dumps({'originalLanguage': 'mul',
                'originalScript': 'Latn', 'componentLanguages': ['en', 'tr', 'zu']}), encoding='utf-8')
            _, draft_sha, _, metadata_sha = read_recording_draft(root)
            review = {'formatVersion': 2, 'videoId': VIDEO, 'audioSha256': 'a'*64,
                'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
                'reviewer': 'Fixture reviewer', 'completedAt': '2026-10-24T00:00:00Z', 'spans': []}
            prefix = 'in twenty twenty six first one two '
            for decision, transcript, first, last, heard, recognized_end in (
                    ('confirmed-asr-omission', prefix+'hello sawubona', 1, 2, 'IŞIK', 8),
                    ('confirmed-asr-substitution', prefix+'hello wrong sawubona', 1, 2, 'IŞIK', 9),
                    ('confirmed-asr-insertion', prefix+'hello extra IŞIK sawubona', 1, 1, '', 9)):
                review['spans'] = [{'performedIndex': 2, 'sourcePhysicalIndex': 2,
                    'startWordIndex': first, 'endWordIndex': last,
                    'recognizedStartWordIndex': 8, 'recognizedEndWordIndex': recognized_end,
                    'startMs': 100, 'endMs': 200, 'decision': decision,
                    'heardText': heard, 'rationale': 'Independent exact recording listening.'}]
                evidence = write_recognition(workspace.job_dir(VIDEO), original, (0, 1, 2), recognized=transcript)
                before = evidence.read_bytes()
                review['recognition'] = {'path': evidence.name, 'sha256': hashlib.sha256(before).hexdigest()}
                (root/'recording-evidence-adjudication.json').write_text(json.dumps(review), encoding='utf-8')
                with self.subTest(decision=decision):
                    result = require_recording_adjudication(review, video_id=VIDEO, original=original,
                        indices=(0, 1, 2), transcript=transcript, audio_sha='a'*64, duration_ms=1000,
                        draft_sha=draft_sha, metadata_sha=metadata_sha, evidence_path=evidence, evidence_root=evidence.parent)
                    self.assertEqual(result['spanCount'], 1)
                    admitted = confirm_content(original, (0, 1, 2), 'c'*64, 'a'*64,
                        evidence_path=evidence, evidence_root=evidence.parent, adjudication_job_dir=root)
                    self.assertIsNotNone(admitted.adjudication)
                    adjudicate_retained_recording({'projectDir': str(Path(name)/'PC side/builder')},
                        Path(name), VIDEO, {'sha256': 'a'*64, 'durationMs': 4000})
                    self.assertEqual(evidence.read_bytes(), before)
            (root/'listening-review.txt').write_text('changed', encoding='utf-8')
            with self.assertRaises(LyricsContentBlocker):
                require_recording_adjudication(review, video_id=VIDEO, original=original,
                    indices=(0, 1, 2), transcript=transcript, audio_sha='a'*64, duration_ms=1000,
                    draft_sha=draft_sha, metadata_sha=metadata_sha, evidence_path=evidence, evidence_root=evidence.parent)

    def test_owsm_recovery_uses_complete_reviewed_language_owners(self):
        from stemwise_engine.endpoint_owsm_ctc import _routes_for_context
        for language in ('mul', 'MUL', 'mul-Latn'):
            with self.subTest(language=language), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                original, _ = self.fixture(root, language=language)
                plan = spoken_targets.load(root, VIDEO, original, (0, 1, 2), 'a'*64)
                lines = [{'words': [{'wordId': str(i), 'acousticText': text, 'acousticLanguage': lang}
                    for i, (text, lang) in enumerate(words)]} for words in plan.words]
                context = {'language': language, 'targetLine': 2, 'request': {'lines': lines}}
                before = copy.deepcopy(context)
                self.assertEqual(_routes_for_context(context, ['en', 'tr', 'zu']), ['en', 'tr', 'zu'])
                self.assertEqual(_routes_for_context(context, ['EN-us', 'TR', 'zu-ZA']), ['en', 'tr', 'zu'])
                self.assertEqual(context, before)
                del lines[0]['words'][0]['acousticLanguage']
                self.assertEqual(_routes_for_context(context, ['en', 'tr', 'zu']), [])
                lines[0]['words'][0]['acousticLanguage'] = 'ar'
                self.assertEqual(_routes_for_context(context, ['en', 'tr', 'zu']), [])

    def test_valid_spoken_plan_repairs_only_pronunciation_retry_block(self):
        from stemwise_engine import lyrics_align_pc as worker
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root/'agent', workspace_root=root)
            job = workspace.root.parent/'jobs'/VIDEO
            job.mkdir(parents=True)
            original, _ = self.fixture(job)
            prior = {'status': 'lyrics-content-unresolved', 'reasons': ['pronunciation-support-unavailable']}
            worker._require_no_unchanged_content_block(prior, 'c'*64, 'a'*64,
                workspace=workspace, video_id=VIDEO, original=original)
            prior['reasons'] = ['content-sequence-uncertain']
            with self.assertRaises(LyricsContentBlocker):
                worker._require_no_unchanged_content_block(prior, 'c'*64, 'a'*64,
                    workspace=workspace, video_id=VIDEO, original=original)
            prior['reasons'] = ['pronunciation-support-unavailable']
            (job/'listening-review.txt').write_text('stale evidence', encoding='utf-8')
            with self.assertRaises(LyricsContentBlocker):
                worker._require_no_unchanged_content_block(prior, 'c'*64, 'a'*64,
                    workspace=workspace, video_id=VIDEO, original=original)

    def reviewed_repeated_request(self, root, language):
        from stemwise_engine.lyrics_tokenization import display_words
        original, value = self.fixture(root)
        original.update(language=language, componentLanguages=[language],
            text='🎵 In 2026' if language == 'en' else '🎵 عام ٢٠٢٦')
        forms = [('in', 'en'), ('twenty twenty six', 'en')] if language == 'en' else [
            ('عام', 'ar'), ('ألفان وستة وعشرون', 'ar')]
        display = display_words(original['text'])
        spans = [dict(sourcePhysicalIndex=0, startUtf16=start, endUtf16=end, displayText=text,
            spokenText=spoken, language=lang) for (start, end, text), (spoken, lang) in zip(display, forms)]
        sources = (0, 0)
        value['binding'] = spoken_targets.binding(VIDEO, original, sources, 'a'*64)
        value['spans'] = spans+copy.deepcopy(spans)
        candidate = {key: value[key] for key in ('formatVersion', 'binding', 'spans')}
        value['review']['candidateSha256'] = hashlib.sha256(json.dumps(candidate, ensure_ascii=False,
            sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
        (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
        plan = spoken_targets.load(root, VIDEO, original, sources, 'a'*64)
        mapping = build_lexical_occurrence_mapping(VIDEO, original, sources)
        before = copy.deepcopy(mapping)
        mapping['_spokenTargets'] = plan
        request, line_ids, word_ids, _ = _forced_alignment_request(VIDEO, list(sources), mapping,
            [display, display], duration_ms=10000)
        self.assertEqual({key: item for key, item in mapping.items() if key != '_spokenTargets'}, before)
        self.assertNotEqual(line_ids[0], line_ids[1])
        self.assertNotEqual(word_ids[0], word_ids[1])
        self.assertEqual(display, [(3, 5, 'In'), (6, 10, '2026')] if language == 'en' else [
            (3, 6, 'عام'), (7, 11, '٢٠٢٦')])
        return request['lines'], line_ids, word_ids

    def closed_endpoint_candidates(self, worker, request, lines):
        from stemwise_engine.lyrics_align_pc import _closed_endpoint_witness_candidates
        raw = (json.dumps(request, ensure_ascii=False, allow_nan=False, separators=(',', ':'))+'\n').encode('utf-8')
        digest = hashlib.sha256(raw).hexdigest()
        result = dict(engine=worker.ENGINE, route=request['route'], formatVersion=worker.FORMAT_VERSION,
            durationMs=request['durationMs'], request={'sha256': digest, 'byteCount': len(raw)},
            audio={'sha256': request['audio']['sha256'], 'byteCount': request['audio']['byteCount']},
            lineCount=len(lines), lines=lines)
        return _closed_endpoint_witness_candidates(result, expected_engine=worker.ENGINE,
            expected_route=request['route'], expected_format_version=worker.FORMAT_VERSION,
            duration_ms=request['durationMs'], request_sha256=digest, request_byte_count=len(raw),
            audio_identity=request['audio'], request_lines=request['lines'])

    def test_reviewed_arabic_spoken_units_regroup_under_exact_nemo_display_owners(self):
        from alignment.test_nemo_nfa_forced_alignment import request_fixture
        from stemwise_engine import nemo_nfa_forced_alignment as worker
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            lines, line_ids, word_ids = self.reviewed_repeated_request(root, 'ar')
            request = request_fixture()
            request['lines'] = lines
            records, words = worker._flatten(worker._validated_request(request))
            self.assertEqual(words, ['عام', 'ألفان', 'وستة', 'وعشرون']*2)
            ctm = root/'word.ctm'
            ctm.write_text(''.join(f'audio 1 {(.1+.2*index):.1f} 0.1 {word} NA lex NA\n'
                for index, word in enumerate(words)), encoding='utf-8')
            result = worker._regroup(records, worker._parse_word_ctm(ctm, 'audio', words, 10000))
            self.assertEqual([line['lineId'] for line in result], line_ids)
            self.assertEqual([[word['wordId'] for word in line['words']] for line in result], word_ids)
            self.assertEqual([line['sourcePhysicalIndex'] for line in result], [0, 0])
            self.assertEqual([[(word['startMs'], word['endMs']) for word in line['words']] for line in result],
                [[(100, 200), (300, 800)], [(900, 1000), (1100, 1600)]])
            projected = self.closed_endpoint_candidates(worker, request, result)
            self.assertEqual([[word['wordId'] for word in line['words']] for line in projected], word_ids)
            self.assertEqual(projected[1]['words'][1],
                {'wordId': word_ids[1][1], 'startMs': 1100, 'endMs': 1600})
            ctm.write_text(ctm.read_text(encoding='utf-8').replace('وعشرون', 'مختلف', 1), encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'approved normalized word sequence'):
                worker._parse_word_ctm(ctm, 'audio', words, 10000)

    def test_reviewed_english_spoken_units_regroup_under_exact_owsm_display_owners(self):
        from types import SimpleNamespace
        from alignment.test_owsm_ctc_forced_alignment import IdentityConverter, request_fixture
        from stemwise_engine import owsm_ctc_forced_alignment as worker
        from stemwise_engine.lyrics_align_pc import _owsm_ctc_request
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            lines, line_ids, word_ids = self.reviewed_repeated_request(Path(name), 'en')
            with patch('stemwise_engine.lyrics_align_pc._worker_cuda_device', return_value=request_fixture()['runtime']['cudaDevice']):
                request = _owsm_ctc_request({'projectDir': str(ROOT/'PC side/stemwise-playlist-builder')},
                    'en', Path('/private/audio.wav'), {'sha256': 'a'*64, 'byteCount': 12}, lines, 10000)
            worker._validated_request(request)
            tokens = ['<blank>', '<unk>', 'in', 'twenty', 'six']
            aligner = SimpleNamespace(preprocess_fn=SimpleNamespace(text_cleaner=lambda value: value,
                tokenizer=SimpleNamespace(text2tokens=lambda value: value.split()),
                token_id_converter=IdentityConverter(tokens)), config=SimpleNamespace(char_list=tokens))
            texts, plan, evidence = worker._preflight(aligner, request)
            self.assertEqual(texts, ['in twenty twenty six']*2)
            self.assertEqual((evidence['wordCount'], evidence['tokenCount']), (4, 8))
            spans = [SimpleNamespace(token=token, start=10+20*index, end=20+20*index, score=-.1)
                for index, token in enumerate([2, 3, 3, 4]*2)]
            result = worker._lexical_records(lines, plan, spans, 10, 10000)
            self.assertEqual([line['lineId'] for line in result], line_ids)
            self.assertEqual([[word['wordId'] for word in line['words']] for line in result], word_ids)
            self.assertEqual([line['sourcePhysicalIndex'] for line in result], [0, 0])
            self.assertEqual([[(word['startMs'], word['endMs']) for word in line['words']] for line in result],
                [[(100, 200), (300, 800)], [(900, 1000), (1100, 1600)]])
            self.assertEqual([word['acousticTokenCount'] for word in result[0]['words']], [1, 3])
            projected = self.closed_endpoint_candidates(worker, request, result)
            self.assertEqual([[word['wordId'] for word in line['words']] for line in projected], word_ids)
            self.assertEqual(projected[1]['words'][1],
                {'wordId': word_ids[1][1], 'startMs': 1100, 'endMs': 1600})
            spans[2].token = 4
            with self.assertRaisesRegex(RuntimeError, 'changed the approved target'):
                worker._lexical_records(lines, plan, spans, 10, 10000)

    def test_repeated_physical_line_retains_distinct_occurrences_without_duplicating_words(self):
        from stemwise_engine.lyrics_tokenization import display_words
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)/VIDEO
            root.mkdir()
            original, value = self.fixture(root)
            indices = (0, 1, 0, 2)
            value['binding'] = spoken_targets.binding(VIDEO, original, indices, 'a'*64)
            value['spans'] = value['spans'][:5]+copy.deepcopy(value['spans'][:2])+value['spans'][5:]
            def publish():
                candidate = {key: value[key] for key in ('formatVersion', 'binding', 'spans')}
                value['review']['candidateSha256'] = hashlib.sha256(json.dumps(candidate, ensure_ascii=False,
                    sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
                (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value), encoding='utf-8')
            publish()
            plan = spoken_targets.load(root, VIDEO, original, indices, 'a'*64)
            self.assertEqual(plan.word_lines(indices), [['in', 'twenty twenty six'], ['first', 'one', 'two'],
                ['in', 'twenty twenty six'], ['hello', 'merhaba', 'sawubona']])
            mapping = build_lexical_occurrence_mapping(VIDEO, original, indices)
            mapping['_spokenTargets'] = plan
            request, line_ids, word_ids, _ = _forced_alignment_request(VIDEO, list(indices), mapping,
                [display_words(original['text'].splitlines()[source]) for source in indices], duration_ms=10000)
            self.assertEqual([len(line['words']) for line in request['lines']], [2, 3, 2, 3])
            self.assertNotEqual(line_ids[0], line_ids[2])
            self.assertNotEqual(word_ids[0], word_ids[2])
            value['spans'][6]['spokenText'] = 'two thousand twenty six'
            publish()
            with self.assertRaises(LyricsContentBlocker):
                spoken_targets.load(root, VIDEO, original, indices, 'a'*64)

    def test_review_changes_invalidate_editorial_and_attempt_authority_but_keep_numerical_request(self):
        from workflow_fixture import initialized_workflow
        from stemwise_engine.common import atomic_write_json
        from stemwise_engine.lyrics_tokenization import display_words
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            workflow = initialized_workflow(Path(name))
            root = workflow._job_dir(VIDEO)
            root.mkdir(parents=True)
            original, value = self.fixture(root)
            drafts = root/'drafts'
            drafts.mkdir()
            (drafts/'original.txt').write_text(original['text'], encoding='utf-8')
            (root/'agent-review.json').write_bytes(b'{}')
            job = workflow._base_job(VIDEO, 'https://youtu.be/'+VIDEO)
            job['state'] = 'WAITING_FOR_EDITORIAL'
            atomic_write_json(workflow._job_path(VIDEO), job)
            words = [display_words(line) for line in original['text'].splitlines()]
            def request():
                mapping = build_lexical_occurrence_mapping(VIDEO, original, (0, 1, 2))
                mapping['_spokenTargets'] = spoken_targets.load(root, VIDEO, original, (0, 1, 2), 'a'*64)
                return _forced_alignment_request(VIDEO, [0, 1, 2], mapping, words, duration_ms=10000)[0]
            numeric = request()
            authority = workflow._editorial_sha(job), workflow._capability_input_sha(VIDEO)
            with patch.object(workflow, '_require_selected_audio'):
                workflow._run_mutating_operation(VIDEO, 'Validate', lambda: 23)
                generation = workflow._song_ledger(VIDEO).record['generation']
                (drafts/'notes.md').write_bytes(b'unconsumed notes')
                workflow._run_mutating_operation(VIDEO, 'Validate', lambda: 23)
                self.assertEqual(workflow._song_ledger(VIDEO).record['generation'], generation)
                value['review']['rationale'] = 'Additional exact-recording review explanation; same reviewed spoken targets.'
                (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value), encoding='utf-8')
                self.assertNotEqual(workflow._editorial_sha(job), authority[0])
                self.assertNotEqual(workflow._capability_input_sha(VIDEO), authority[1])
                self.assertEqual(request(), numeric)
                workflow._run_mutating_operation(VIDEO, 'Validate', lambda: 23)
                self.assertEqual(workflow._song_ledger(VIDEO).record['generation'], generation+1)

    def fixture(self, root, *, language='mul'):
        original = {'id': 'original', 'kind': 'original', 'format': 'plain', 'language': language,
            'script': 'Latn', 'componentLanguages': ['en', 'tr', 'zu'],
            'text': '🎵 In 2026\n1st ١ ²\nhello merhaba sawubona'}
        forms = ((0, 3, 5, 'In', 'in', 'en'), (0, 6, 10, '2026', 'twenty twenty six', 'en'),
            (1, 0, 3, '1st', 'first', 'en'), (1, 4, 5, '١', 'one', 'en'), (1, 6, 7, '²', 'two', 'en'),
            (2, 0, 5, 'hello', 'hello', 'en'), (2, 6, 13, 'merhaba', 'merhaba', 'tr'),
            (2, 14, 22, 'sawubona', 'sawubona', 'zu'))
        spans = [dict(zip(('sourcePhysicalIndex', 'startUtf16', 'endUtf16', 'displayText', 'spokenText', 'language'), row))
                 for row in forms]
        evidence = root/'listening-review.txt'
        evidence.write_text('Known-answer review of the exact fixture recording and every declared spoken form.', encoding='utf-8')
        value = {'formatVersion': 1, 'binding': spoken_targets.binding(VIDEO, original, (0, 1, 2), 'a'*64), 'spans': spans}
        candidate = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
        value['review'] = {'candidateSha256': hashlib.sha256(candidate).hexdigest(), 'reviewer': 'fixture-listener',
            'completedAt': '2026-11-01T00:00:00Z', 'decision': 'admit',
            'checks': dict.fromkeys(spoken_targets.CHECKS, 'reviewed'),
            'evidence': [{'path': evidence.name, 'sha256': hashlib.sha256(evidence.read_bytes()).hexdigest()}],
            'rationale': 'Every fixture word, spoken form and language was reviewed against this exact recording.'}
        (root/spoken_targets.PLAN_FILE).write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
        return original, value

    def test_reviewed_year_ordinal_arabic_superscript_and_languages_project_to_exact_display_words(self):
        from stemwise_engine.lyrics_tokenization import display_words
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)/VIDEO
            root.mkdir()
            original, _ = self.fixture(root)
            before = copy.deepcopy(original)
            plan = spoken_targets.load(root, VIDEO, original, (0, 1, 2), 'a'*64)
            mapping = build_lexical_occurrence_mapping(VIDEO, original, (0, 1, 2))
            mapping['_spokenTargets'] = plan
            request, _, ids, _ = _forced_alignment_request(VIDEO, [0, 1, 2], mapping,
                [display_words(line) for line in original['text'].splitlines()], duration_ms=10000)
            self.assertEqual([[word['acousticText'] for word in line['words']] for line in request['lines']],
                [['in', 'twenty twenty six'], ['first', 'one', 'two'], ['hello', 'merhaba', 'sawubona']])
            self.assertEqual([[word['acousticLanguage'] for word in line['words']] for line in request['lines']],
                [['en-us', 'en-us'], ['en-us', 'en-us', 'en-us'], ['en-us', 'tr', 'zu']])
            from stemwise_engine.mms_forced_alignment import _request_plan
            records, words, _, _ = _request_plan(request)
            self.assertEqual(words, ['in', 'twenty', 'twenty', 'six', 'first', 'one', 'two', 'hello', 'merhaba', 'sawubona'])
            self.assertEqual(records[0]['words'][1], {'startWord': 1, 'wordCount': 3, 'wordId': ids[0][1]})
            from types import SimpleNamespace
            from stemwise_engine.wav2vec2_forced_alignment import _target_plan
            vocabulary = {'<pad>': 0, '<unk>': 1, '|': 2,
                **{character: index+3 for index, character in enumerate('abcdefghijklmnopqrstuvwxyz')}}
            tokenizer = SimpleNamespace(get_vocab=lambda: vocabulary, word_delimiter_token_id=2,
                unk_token_id=1, all_special_ids=[0, 1])
            targets, lines, delimiter = _target_plan({'lines': request['lines']}, tokenizer, 'en')
            self.assertEqual(delimiter, 2)
            self.assertEqual(lines[0]['words'][1], {'wordId': ids[0][1], 'targetStart': 3, 'targetEnd': 20})
            self.assertEqual(targets[3:20], [vocabulary[character] for character in 'twenty']+[2]
                +[vocabulary[character] for character in 'twenty']+[2]+[vocabulary[character] for character in 'six'])
            spoken_calls = []
            def phonemize(text, phonemizer_lang):
                spoken_calls.append((text, phonemizer_lang))
                return 'n'
            phoneme_tokenizer = SimpleNamespace(get_vocab=lambda: {'<pad>': 0, '<unk>': 1, 'n': 3},
                word_delimiter_token_id=1, unk_token_id=1, all_special_ids=[0, 1],
                phone_delimiter_token=' ', word_delimiter_token='|', phonemize=phonemize)
            _, phoneme_lines, _ = _target_plan({'lines': request['lines']}, phoneme_tokenizer, 'mul', phoneme=True)
            self.assertEqual(phoneme_lines[0]['words'][1], {'wordId': ids[0][1], 'targetStart': 1, 'targetEnd': 4})
            self.assertEqual(spoken_calls[:4], [('in', 'en-us'), ('twenty', 'en-us'), ('twenty', 'en-us'), ('six', 'en-us')])
            self.assertEqual(original, before)

    def test_content_admission_retains_and_rechecks_exact_review_evidence(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)/VIDEO
            root.mkdir()
            original, _ = self.fixture(root)
            evidence = root/'recognition'
            path = write_recognition(evidence, original, (0, 1, 2),
                recognized='in twenty twenty six first one two hello merhaba sawubona')
            admission = confirm_content(original, (0, 1, 2), 'c'*64, 'a'*64,
                evidence_path=path, evidence_root=evidence, adjudication_job_dir=root)
            self.assertEqual(ContentAdmission.from_record(admission.to_record()), admission)
            self.assertEqual(admission.spoken_targets['path'], spoken_targets.PLAN_FILE)
            require_content(admission.to_record(), original, (0, 1, 2), 'c'*64, 'a'*64, evidence,
                            adjudication_job_dir=root)
            (root/'listening-review.txt').write_bytes(b'changed review evidence')
            with self.assertRaises(LyricsContentBlocker):
                require_content(admission.to_record(), original, (0, 1, 2), 'c'*64, 'a'*64, evidence,
                                adjudication_job_dir=root)

    def test_absent_ambiguous_or_changed_plan_cannot_authorize_recognition(self):
        import stemwise_engine.recording_evidence as recording_evidence
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)/VIDEO
            root.mkdir()
            original, value = self.fixture(root)
            for fault in ('audio', 'language', 'word', 'review', 'evidence'):
                changed = copy.deepcopy(value)
                if fault == 'audio': changed['binding']['audioSha256'] = 'b'*64
                elif fault == 'language': changed['spans'][-1]['language'] = 'en'
                elif fault == 'word': changed['spans'][1]['spokenText'] = 'twenty twenty five'
                elif fault == 'review': changed['review']['checks']['language-ownership'] = True
                else: changed['review']['evidence'][0]['sha256'] = 'b'*64
                (root/spoken_targets.PLAN_FILE).write_text(json.dumps(changed), encoding='utf-8')
                with self.subTest(fault=fault), self.assertRaises(LyricsContentBlocker):
                    spoken_targets.load(root, VIDEO, original, (0, 1, 2), 'a'*64)
            (root/spoken_targets.PLAN_FILE).unlink()
            config = {'projectDir': str(root/'PC side/builder')}
            identity = {'sha256': 'a'*64, 'stream': {'durationMs': 10000,
                'sampleRate': 48000, 'channelCount': 2, 'frameCount': 480000}}
            with patch.object(recording_evidence, 'read_recording_draft', return_value=(original['text'], 'c'*64,
                    {'originalLanguage': 'mul', 'originalScript': 'Latn', 'componentLanguages': ['en', 'tr', 'zu']}, 'd'*64)), \
                    patch.object(recording_evidence, 'file_identity', return_value=identity), \
                    patch.object(recording_evidence, '_load_or_run_qwen3_structure_transcript', side_effect=AssertionError('recognition must not run')):
                with self.assertRaises(LyricsContentBlocker):
                    recording_evidence.compare(config, root/'agent', VIDEO, root/'source.wav', 'a'*64)


if __name__ == '__main__':
    unittest.main()
