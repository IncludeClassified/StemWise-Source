from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import copy
import hashlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "PC side" / "stemwise-playlist-builder" / "src"

from stemwise_engine.workflow_context import report_control_path
import stemwise_engine.editorial_review as editorial_review

import stemwise_engine.future_song as future_song_module  # noqa: E402
import stemwise_engine.workflow_review as review_services
import stemwise_engine.workflow_command as command_services
from stemwise_engine.common import GENERATED_MANIFEST_NAME, atomic_write_json  # noqa: E402
from stemwise_engine.future_song import FutureSongError, FutureSongWorkflow  # noqa: E402
from stemwise_engine.lyrics_alignment import (  # noqa: E402
    ALIGNMENT_VERSION,
    build_lexical_occurrence_mapping,
    lexical_mapping_identity,
)
from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION, display_words  # noqa: E402


VIDEO_ID = "KbKqkR3Q6SA"
PLAIN_TEXT = "اَلْحُبُّ جَمِيلٌ\nهُنَا الآن"


def _plain_original(text: str = PLAIN_TEXT) -> dict:
    return {
        "id": "original",
        "kind": "original",
        "language": "ar",
        "script": "Arab",
        "format": "plain",
        "text": text,
        "source": {"name": "Owner reviewed"},
    }


def _timed_lyrics(original: dict, mapping: dict) -> dict:
    physical = original["text"].splitlines()
    lines = []
    for performed, occurrence in enumerate(mapping["performedOccurrences"]):
        text = physical[occurrence["sourcePhysicalIndex"]]
        start = performed * 1_500
        words = display_words(text)
        lines.append(
            {
                "lineId": occurrence["lineId"],
                "occurrenceId": occurrence["occurrenceId"],
                "startMs": start,
                "endMs": start + 1_200,
                "text": text,
                "words": [
                    {
                        "wordId": private["occurrenceWordId"],
                        "authorityWordId": private["authorityWordId"],
                        "startUtf16": begin,
                        "endUtf16": end,
                        "startMs": start + word * 400,
                        "endMs": start + (word + 1) * 400,
                    }
                    for word, ((begin, end, _), private) in enumerate(
                        zip(words, occurrence["words"])
                    )
                ],
            }
        )
    synced = copy.deepcopy(original)
    synced.pop("text")
    synced["format"] = "synced"
    synced["lines"] = lines
    return {
        "status": "partial",
        "reason": "Only the reviewed original track is available.",
        "tokenizationVersion": TOKENIZATION_VERSION,
        "lexicalAuthoritySha256": mapping["lexicalAuthoritySha256"],
        "tracks": [synced],
    }


class DisplayReviewCase:
    def __init__(self, root: Path, *, duration_ms=10000, original=None, sources=None):
        self.root = root
        sys.path.insert(0, str(ROOT/'Test suite/pc/unit'))
        from workflow_fixture import initialized_workflow
        self.workflow = initialized_workflow(root)
        self.workflow._require_platform_content_allowed = Mock()
        workflow = self.workflow
        workflow._windows_path = lambda path: str(path)
        workflow._save_job = Mock()
        workflow._write_task = Mock()
        workflow._load_job = lambda _video_id, **options: self.job
        workflow._run_mutating_operation = lambda _video_id, _action, callback: callback()
        # These fixtures exercise strict display adjudication. Ordinary v6
        # capability fallback uses normal construction in test_available_completion.
        workflow._validate_action = workflow._validate_enhancements_action
        workflow._align_future_lyrics = Mock(side_effect=AssertionError("must reuse alignment"))
        workflow.summary = lambda job: {"state": job["state"]}
        workflow._editorial_sha = Mock(return_value="e" * 64)
        self.cover = b"validated cover"
        self.analysis = {'status': 'partial', 'durationMs': duration_ms,
            **{name: {'status': 'unavailable', 'reason': 'No estimate'} for name in
                ('rhythm', 'key', 'structure', 'loudness', 'waveform')},
            'tuning': {'status': 'complete', 'frequencyHz': 440., 'centsFromA440': 0.},
            'toolRuns': [{'name': name, 'status': 'complete', 'version': '1'} if name == 'essentia'
                else {'name': name, 'status': 'skipped', 'reason': 'No estimate'} for name in
                ('beat-this-full', 'stemwise-rhythm-normalizer', 's-key', 'all-in-one', 'essentia', 'stemwise-waveform')]}
        artwork_sha256 = hashlib.sha256(self.cover).hexdigest()
        workflow._completed_analysis_audio = Mock(
            return_value={
                "sha256": "a" * 64,
                "byteCount": 100,
                "durationMs": duration_ms,
                "artworkSha256": artwork_sha256,
                "path": root / "analysis.wav",
            }
        )
        workflow.report_store.immutable_run_matches = Mock(return_value=True)
        workflow.report_store.report_authority_matches = Mock(return_value=True)
        workflow._pipeline = Mock(return_value=Mock(process_song=Mock(return_value="complete")))
        workflow._runtime_config = Mock(return_value={})

        job_dir = workflow._job_dir(VIDEO_ID)
        job_dir.mkdir(parents=True)
        (job_dir / 'drafts').mkdir()
        original = original or _plain_original()
        self.original = original
        (job_dir / 'drafts/original.txt').write_text(original['text'] + '\n', encoding='utf-8')
        atomic_write_json(job_dir / 'drafts/lyrics.meta.json', {
            'videoId': VIDEO_ID, 'originalLanguage': original['language'],
            'originalScript': original['script'], 'sources': {'original': original['source']},
            **({'componentLanguages': original['componentLanguages']} if 'componentLanguages' in original else {}),
        })
        self.sources = job_dir / "sources.json"
        self.sources.write_text("{}\n", encoding="utf-8")
        sources = tuple(range(len(original['text'].splitlines()))) if sources is None else sources
        self.mapping = build_lexical_occurrence_mapping(VIDEO_ID, original, sources)
        identity = lexical_mapping_identity(self.mapping)
        alignment_dir = workflow._alignment_state_path(VIDEO_ID).parent
        alignment_dir.mkdir(parents=True)
        mapping_path = alignment_dir / "mapping.private.json"
        atomic_write_json(
            mapping_path,
            {**self.mapping, "mappingSha256": identity["mappingSha256"], "numeric": {}},
        )
        mapping_artifact_sha = future_song_module.sha256_file(mapping_path)
        self.aligned = _timed_lyrics(original, self.mapping)
        self.sidecar = workflow.owner / f"{VIDEO_ID}.lyrics.json"
        self.candidate = alignment_dir / "candidate.private.lyrics.json"
        atomic_write_json(self.sidecar, self.aligned)
        atomic_write_json(self.candidate, self.aligned)
        self.alignment_attempt = "1" * 32
        self.state = {
            "videoId": VIDEO_ID,
            "attemptId": self.alignment_attempt,
            "status": "committed-words",
            "alignmentVersion": ALIGNMENT_VERSION,
            "durationMs": duration_ms,
            **identity,
            "mappingArtifactSha256": mapping_artifact_sha,
        }
        atomic_write_json(workflow._alignment_state_path(VIDEO_ID), self.state)
        self.job = {
            "videoId": VIDEO_ID,
            "state": "ALIGNING_LYRICS",
            "policyVersion": editorial_review.POLICY_VERSION,
            "policy": {"schemaSha256": "c" * 64},
            'artworkReview': {'result': 'accepted', 'sourceVideoId': VIDEO_ID,
                'sourceUrl': f'https://www.youtube.com/watch?v={VIDEO_ID}',
                'artworkSha256': artwork_sha256, 'reviewedAt': '2026-10-22T00:00:00Z'},
        }
        self.alignment_artifacts = {
            "sourcesSha256": future_song_module.sha256_file(self.sources),
            "sidecarSha256": future_song_module.sha256_file(self.sidecar),
            "alignmentCandidateSha256": future_song_module.sha256_file(self.candidate),
            "mappingArtifactSha256": mapping_artifact_sha,
            "alignmentStateSha256": future_song_module.sha256_file(
                workflow._alignment_state_path(VIDEO_ID)
            ),
            "analysisAudioSha256": "a" * 64,
            "artworkSha256": artwork_sha256,
        }
        child = workflow.runtime / "children" / f"{VIDEO_ID}.stemwise.zip"
        child.parent.mkdir(parents=True)
        child.write_bytes(b"package")

    def bind_spoken_languages(self, languages):
        from stemwise_engine import spoken_targets
        from stemwise_engine.common import json_bytes, sha256_file
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace, snapshot_canonical_plain
        from stemwise_engine.lyric_content import confirm_content
        from lyric_content_fixtures import write_recognition
        directory = self.workflow._job_dir(VIDEO_ID)
        sources = [row['sourcePhysicalIndex'] for row in self.mapping['performedOccurrences']]
        physical = self.original['text'].splitlines()
        spans = []
        for source in sources:
            words = display_words(physical[source])
            assert len(words) == len(languages[source])
            spans.extend({'sourcePhysicalIndex': source, 'startUtf16': start, 'endUtf16': end,
                'displayText': text, 'spokenText': text, 'language': language}
                for (start, end, text), language in zip(words, languages[source]))
        evidence = directory/'word-language-review.txt'
        evidence.write_text('Known-answer synthetic review of every fixture word and its language.', encoding='utf-8')
        value = {'formatVersion': 1, 'binding': spoken_targets.binding(VIDEO_ID, self.original, sources, 'a'*64),
            'spans': spans}
        value['review'] = {'candidateSha256': spoken_targets._digest(value), 'reviewer': 'fixture-listener',
            'completedAt': '2026-11-07T00:00:00Z', 'decision': 'admit',
            'checks': dict.fromkeys(spoken_targets.CHECKS, 'reviewed'),
            'evidence': [{'path': evidence.name, 'sha256': sha256_file(evidence)}],
            'rationale': 'Synthetic reviewed language ownership; not an acoustic quality qualification.'}
        atomic_write_json(directory/spoken_targets.PLAN_FILE, value)
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.root)
        _, canonical = snapshot_canonical_plain(workspace, VIDEO_ID, json_bytes({'tracks': [self.original]}))
        recognition = write_recognition(workspace.job_dir(VIDEO_ID), self.original, sources)
        admission = confirm_content(self.original, sources, canonical, 'a'*64,
            evidence_path=recognition, evidence_root=workspace.job_dir(VIDEO_ID), adjudication_job_dir=directory)
        self.state.update(canonicalPlainSha256=canonical, contentAdmission=admission.to_record())
        atomic_write_json(self.workflow._alignment_state_path(VIDEO_ID), self.state)
        self.alignment_artifacts['alignmentStateSha256'] = sha256_file(self.workflow._alignment_state_path(VIDEO_ID))
        run = self.workflow.report_store.ensure_report_identity(VIDEO_ID)/'runs'/self.alignment_attempt/'run.json'
        run.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_json(run, {'checkpointKey': {}, 'artifacts': self.alignment_artifacts})
        self.workflow.report_store.alignment_checkpoint_key = Mock(return_value={})
        self.workflow._validated_artwork_lineage = Mock(return_value=None)

    def available_timing(self):
        audio = self.workflow._completed_analysis_audio(None, VIDEO_ID)
        with patch.object(future_song_module, 'read_child', side_effect=self.read_child):
            return self.workflow._available_original_timing(self.job, self.original, audio)

    def begin(self) -> Path:
        self.workflow.review_construction.create_display_review_attempt(
            self.job,
            original_text=self.original['text'],
            editorial_sha256="e" * 64,
            analysis_audio=self.workflow._completed_analysis_audio(self.workflow._pipeline(), VIDEO_ID),
            alignment_state=self.state,
            alignment_run_sha256="f" * 64,
            alignment_artifacts=self.alignment_artifacts,
        )
        self.workflow._save_job(self.job)
        self.workflow._write_task(self.job)
        return self.workflow._display_review_attempt_dir(
            VIDEO_ID, self.job["displayReview"]["attemptId"]
        )

    def read_child(self, *_args, **_kwargs) -> tuple[dict, bytes]:
        return (
            {
                "source": {"videoId": VIDEO_ID},
                'artwork': {'source': {'url': f'https://www.youtube.com/watch?v={VIDEO_ID}'}},
                "lyrics": json.loads(self.sidecar.read_text(encoding="utf-8")),
                "analysis": self.analysis,
            },
            self.cover,
        )

    def consume(self) -> dict:
        with patch.object(future_song_module, "read_child", side_effect=self.read_child):
            return self.workflow._validate_display_review(self.job)

    def check_display(self) -> dict:
        with patch.object(future_song_module, 'read_child', side_effect=self.read_child):
            return self.workflow._validate_display_review(self.job, read_only=True)

    def validate(self) -> dict:
        with patch.object(future_song_module, "read_child", side_effect=self.read_child):
            return self.workflow.validate(VIDEO_ID)

    def validated_snapshot_is_current(self) -> bool:
        with patch.object(future_song_module, "read_child", side_effect=self.read_child):
            return self.workflow._validated_snapshot_is_current(self.job)


class FutureSongDisplayReviewTests(unittest.TestCase):
    def test_multilingual_azerbaijani_display_review_admits_only_turkic_case(self):
        layouts = [('QIR\nI SING', 'qır\ni sing', 'qir\ni sing', 'qır\nı sing'),
            ('QIR I\nKİR I', 'qır i\nkir i', 'qir i\nkir i', 'qır ı\nkir i')]
        for language in ('az', 'tr'):
            for text, allowed, wrong_turkic, wrong_english in layouts:
                for edited in (allowed, wrong_turkic, wrong_english):
                    with self.subTest(language=language, text=text, edited=edited), \
                            tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                        original = {**_plain_original(text), 'language': 'mul',
                            'script': 'Latn', 'componentLanguages': [language, 'en']}
                        case = DisplayReviewCase(Path(directory), original=original, sources=(0, 1, 0))
                        languages = [[language], ['en', 'en']] if '\nI' in text else [[language, 'en']]*2
                        case.bind_spoken_languages(languages)
                        attempt = case.begin()
                        (attempt/'original.txt').write_text(edited+'\n', encoding='utf-8')
                        before = case.sidecar.read_bytes()
                        if edited == allowed:
                            self.assertEqual(case.check_display()['displayReviewCheck'], 'pass')
                            available = case.available_timing()
                            self.assertIsNotNone(available)
                            self.assertEqual([line['text'] for line in available[0]['lines']],
                                [edited.splitlines()[index] for index in (0, 1, 0)])
                            self.assertEqual(case.sidecar.read_bytes(), before)
                            self.assertEqual(case.consume()['state'], 'VALIDATED')
                            actual = json.loads(case.sidecar.read_bytes())['tracks'][0]
                            self.assertEqual(case.available_timing()[0], actual)
                            self.assertEqual([line['startMs'] for line in actual['lines']], [0, 1500, 3000])
                            (case.workflow._job_dir(VIDEO_ID)/'word-language-review.txt').write_text(
                                'changed after accepted display', encoding='utf-8')
                            self.assertIsNone(case.available_timing())
                            self.assertEqual(json.loads(case.sidecar.read_bytes())['tracks'][0], actual)
                        else:
                            self.assertIsNone(case.available_timing())
                            with self.assertRaises(review_services.DisplayReviewAuthorityError):
                                case.check_display()
                            self.assertEqual(case.sidecar.read_bytes(), before)
                            case.workflow._pipeline.return_value.process_song.assert_not_called()

    def test_display_word_languages_require_the_exact_recorded_review_and_evidence(self):
        from stemwise_engine import spoken_targets
        faults = ('missing', 'evidence', 'deleted-evidence', 'audio', 'original', 'occurrences', 'new-language-review', 'unbound-review')
        for fault in faults:
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                original = {**_plain_original('QIR I\nHELLO'), 'language': 'mul',
                    'script': 'Latn', 'componentLanguages': ['az', 'en']}
                case = DisplayReviewCase(Path(directory), original=original)
                case.bind_spoken_languages([['az', 'en'], ['en']])
                root = case.workflow._job_dir(VIDEO_ID)
                review = root/spoken_targets.PLAN_FILE
                if fault == 'unbound-review':
                    case.state['contentAdmission'].pop('spokenTargets')
                    atomic_write_json(case.workflow._alignment_state_path(VIDEO_ID), case.state)
                    case.alignment_artifacts['alignmentStateSha256'] = future_song_module.sha256_file(
                        case.workflow._alignment_state_path(VIDEO_ID))
                attempt = case.begin()
                (attempt/'original.txt').write_text('qır i\nhello\n', encoding='utf-8')
                if fault == 'missing':
                    review.unlink()
                elif fault == 'evidence':
                    (root/'word-language-review.txt').write_text('changed review evidence', encoding='utf-8')
                elif fault == 'deleted-evidence':
                    (root/'word-language-review.txt').unlink()
                elif fault != 'unbound-review':
                    value = json.loads(review.read_bytes())
                    if fault == 'audio':
                        value['binding']['audioSha256'] = 'b'*64
                    elif fault == 'original':
                        value['binding']['originalSha256'] = 'b'*64
                    elif fault == 'occurrences':
                        value['binding']['occurrenceIds'].reverse()
                    else:
                        value['spans'][1]['language'] = 'az'
                    value['review']['candidateSha256'] = spoken_targets._digest(
                        {key: value[key] for key in ('formatVersion', 'binding', 'spans')})
                    atomic_write_json(review, value)
                before = case.sidecar.read_bytes(), case.candidate.read_bytes()
                self.assertIsNone(case.available_timing())
                with self.assertRaises(review_services.DisplayReviewAuthorityError):
                    case.check_display()
                self.assertEqual((case.sidecar.read_bytes(), case.candidate.read_bytes()), before)
                case.workflow._pipeline.return_value.process_song.assert_not_called()
                if fault == 'unbound-review':
                    (attempt/'original.txt').write_text('QIR I\nhello!\n', encoding='utf-8')
                    self.assertEqual(case.check_display()['displayReviewCheck'], 'pass')
                    self.assertIsNotNone(case.available_timing())
                    self.assertEqual((case.sidecar.read_bytes(), case.candidate.read_bytes()), before)

    def test_reviewed_unknown_language_withholds_load_and_recheck_io_failures(self):
        from stemwise_engine import spoken_targets
        for boundary in ('load', 'require_current'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as directory:
                original = {**_plain_original('I'), 'language': 'und', 'script': 'Latn'}
                case = DisplayReviewCase(Path(directory), original=original)
                case.bind_spoken_languages([[None]])
                attempt = case.begin()
                (attempt/'original.txt').write_text('i\n', encoding='utf-8')
                self.assertEqual(case.check_display()['displayReviewCheck'], 'pass')
                self.assertIsNotNone(case.available_timing())
                before = case.sidecar.read_bytes(), case.candidate.read_bytes()
                owner = spoken_targets if boundary == 'load' else spoken_targets.ReviewedSpokenPlan
                with patch.object(owner, boundary, side_effect=PermissionError('retained evidence cannot be read')):
                    self.assertIsNone(case.available_timing())
                    with self.assertRaises(review_services.DisplayReviewAuthorityError):
                        case.check_display()
                self.assertEqual((case.sidecar.read_bytes(), case.candidate.read_bytes()), before)
                case.workflow._pipeline.return_value.process_song.assert_not_called()

    def test_alignment_worker_uses_configured_lyrics_binary_and_cuda_libraries(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            case = DisplayReviewCase(Path(name))
            workflow = case.workflow
            project = Path(workflow.config['projectDir'])
            layout_path = project.parent/'runtime-layout.json'
            layout = json.loads(layout_path.read_text(encoding='utf-8'))
            layout['environments']['lyrics'] = 'custom/lyrics/bin/python'
            layout_path.write_text(json.dumps(layout), encoding='utf-8')
            runtime = project.parent/'custom/lyrics'
            for root in (runtime, project/'.venv-lyrics-align'):
                (root/'bin').mkdir(parents=True)
                (root/'bin/python').write_bytes(b'interpreter fixture')
                for package in ('cublas', 'cudnn', 'cuda_nvrtc'):
                    (root/f'lib/python3.10/site-packages/nvidia/{package}/lib').mkdir(parents=True)
            (project/'src/lyrics_align_pc.py').write_bytes(b'entry fixture')
            class ObservedCommand(BaseException):
                pass
            def observe(_stage, _identity, command, **kwargs):
                self.assertEqual(command[0], str(runtime/'bin/python'))
                libraries = kwargs['env']['LD_LIBRARY_PATH'].split(os.pathsep)[:3]
                self.assertEqual(libraries, [str(runtime/f'lib/python3.10/site-packages/nvidia/{package}/lib')
                    for package in ('cublas', 'cudnn', 'cuda_nvrtc')])
                raise ObservedCommand
            with patch.object(workflow, '_run_primary_worker', side_effect=observe) as launched:
                with self.assertRaises(ObservedCommand):
                    FutureSongWorkflow._align_future_lyrics(workflow, VIDEO_ID, '1'*32,
                        'a'*64, 'e'*64, 'b'*64, Path(name)/'analysis.wav')
                launched.assert_called_once()
                (runtime/'bin/python').unlink()
                launched.reset_mock()
                with self.assertRaisesRegex(FutureSongError, 'dependency is missing'):
                    FutureSongWorkflow._align_future_lyrics(workflow, VIDEO_ID, '1'*32,
                        'a'*64, 'e'*64, 'b'*64, Path(name)/'analysis.wav')
                launched.assert_not_called()

    def test_display_publication_preserves_owner_edits_and_never_packages_a_conflict(self):
        import stemwise_engine.worker_publication as publication
        for boundary in ('after-validation', 'final-replace'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                (attempt/'original.txt').write_text('❦ '+PLAIN_TEXT+' ❦\n', encoding='utf-8')
                newer = copy.deepcopy(case.aligned)
                newer['tracks'][0]['source']['name'] = 'Owner correction saved during review'
                newer_raw = future_song_module.json_bytes(newer, pretty=True)
                def edit():
                    case.sidecar.write_bytes(newer_raw)
                original_replace = publication.replace_preserving_previous
                def replace(*args):
                    edit()
                    return original_replace(*args)
                with patch.object(case.workflow, '_companion_projection_current',
                        side_effect=(lambda *_: (edit(), True)[1]) if boundary == 'after-validation' else lambda *_: True), \
                        patch.object(publication, 'replace_preserving_previous', side_effect=replace):
                    with self.assertRaisesRegex((ValueError, RuntimeError), 'publication|conflict'):
                        case.consume()
                case.workflow._pipeline.return_value.process_song.assert_not_called()
                retained = [path.read_bytes() for path in case.sidecar.parent.iterdir()
                    if path.is_file() and path.name != '.'+case.sidecar.name+'.publication.lock']
                self.assertIn(newer_raw, retained)
                self.assertTrue(any(b'\xe2\x9d\xa6' in raw for raw in retained), 'display projection must remain private')
                if boundary == 'after-validation':
                    self.assertEqual(case.sidecar.read_bytes(), newer_raw)

    def test_duration_limited_acoustics_reach_display_validation_and_bundle_readback(self):
        import stemwise_engine.analysis_numerics as numerics
        from stemwise_engine.analyzers import analyze_allin1
        from stemwise_engine.contract import _validate_analysis
        from stemwise_engine.packages import create_child_zip, read_child
        from stemwise_engine.original_delivery import create_bundle, read_bundle, prepare_original
        sys.path.insert(0, str(ROOT/'Test suite/pc/unit'))
        from test_packages import song_document, package_config, PNG_1X1
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            duration = 600001
            audio = root/'not-needed.wav'
            with patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=512*1024**2):
                gpu = numerics._gpu_analysis(audio, duration)
                structure = analyze_allin1(audio, duration, project_dir=root, timeout_seconds=10)
            with patch.object(numerics, 'build_waveform', return_value={'status': 'unavailable', 'reason': 'fixture has no waveform'}):
                analysis = numerics._analysis_document(audio, duration, gpu,
                    ({'status': 'complete', 'frequencyHz': 440., 'centsFromA440': 0.},
                     {'status': 'unavailable', 'reason': 'No estimate'},
                     {'name': 'essentia', 'status': 'complete', 'version': '1'}), structure)
            _validate_analysis(analysis, '$.analysis')
            case = DisplayReviewCase(root, duration_ms=duration)
            case.analysis = analysis
            case.begin()
            result = case.consume()
            self.assertEqual(result['state'], 'VALIDATED')
            document = song_document(VIDEO_ID)
            document['analysis'] = analysis
            document['lyrics'] = json.loads(case.sidecar.read_bytes())
            cover = root/'cover.png'
            cover.write_bytes(PNG_1X1)
            child = root/'child.stemwise.zip'
            config = package_config(root)
            create_child_zip(child, document, cover, config)
            restored, _ = read_child(child, config)
            self.assertEqual(restored['analysis'], analysis)
            original = root/'original.flac'
            from stemwise_engine.common import run_process_tree
            run_process_tree(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
                'anullsrc=r=48000:cl=stereo', '-t', str(duration/1000), '-c:a', 'flac', str(original)],
                check=True, capture_output=True, timeout=30)
            original, identity = prepare_original(original, document, root)
            bundle = root/'delivered.stemwise.zip'
            create_bundle(bundle, child, original, identity, config, root)
            package, _, _ = read_bundle(bundle, config, metadata=child, original=identity)
            self.assertEqual(package['analysis'], analysis)
            self.assertTrue(any(row.get('reason') == 'analysis-full-window-budget-exceeded'
                for row in package['analysis']['toolRuns']))


    def test_typed_review_and_snapshot_services_share_the_current_workflow_authority(self):
        from dataclasses import replace
        from test_available_completion import AvailableCompletionTests
        from workflow_fixture import nested_case
        case = nested_case(self, lambda: AvailableCompletionTests('runTest'))
        case.current_display_fixture()
        job = case.workflow._load_job(case.video)
        self.assertEqual(case.workflow._validate_display_review(job, read_only=True)['displayReviewCheck'], 'pass')
        self.assertEqual(case.workflow.validate(case.video)['state'], 'VALIDATED')
        job = case.workflow._load_job(case.video)
        snapshot = review_services.ValidatedSnapshotService(case.workflow._snapshot_context(),
            case.workflow._review_operations(), case.workflow._review_policy())
        request = review_services.ValidatedSnapshotRequest(job=job)
        self.assertTrue(snapshot.validate(request))
        current_projection = Mock(return_value=False)
        rejected = replace(snapshot, context=replace(snapshot.context,
            companion_projection_current=current_projection))
        self.assertFalse(rejected.validate(request))
        lyrics = future_song_module.read_child(case.child, case.pipeline.config)[0]['lyrics']
        current_projection.assert_called_once_with(job, lyrics,
            {key: case.workflow._completed_analysis_audio(case.pipeline, case.video)[key]
             for key in ('sha256', 'byteCount', 'durationMs')})
        sidecar = case.workflow._owner_sidecar_path(case.video)
        sidecar.write_bytes(sidecar.read_bytes()+b' ')
        self.assertFalse(snapshot.validate(request))

    def test_display_calculation_uses_only_immutable_inputs_and_leaves_artifacts_unchanged(self):
        from stemwise_engine.common import json_bytes, sha256_file
        from dataclasses import replace
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            root = Path(name)
            attempt, state_dir = root/'review', root/'alignment'
            attempt.mkdir()
            state_dir.mkdir()
            original = _plain_original()
            mapping = build_lexical_occurrence_mapping(VIDEO_ID, original, (0, 1))
            mapping_identity = lexical_mapping_identity(mapping)
            baseline = _timed_lyrics(original, mapping)
            sidecar = root/'sidecar.json'
            candidate = state_dir/'candidate.private.lyrics.json'
            atomic_write_json(sidecar, baseline)
            atomic_write_json(candidate, baseline)
            run_id, attempt_id = '1'*32, '2'*32
            state = {'attemptId': run_id, 'alignmentVersion': ALIGNMENT_VERSION,
                'status': 'committed-words', **mapping_identity}
            atomic_write_json(state_dir/'state.json', state)
            analysis = {'sha256': 'a'*64, 'byteCount': 100, 'durationMs': 10000,
                'artworkSha256': 'b'*64}
            declaration = {'declarationVersion': future_song_module.review_contract.DISPLAY_REVIEW_VERSION,
                'policy': future_song_module.review_contract.DISPLAY_REVIEW_POLICY,
                'videoId': VIDEO_ID, 'attemptId': attempt_id, 'bodyFile': 'original.txt',
                'preReviewTextSha256': hashlib.sha256(PLAIN_TEXT.encode('utf-8')).hexdigest(),
                'preReviewSidecarSha256': sha256_file(sidecar), 'editorialSha256': 'e'*64,
                'sourcesSha256': 'c'*64, 'analysisAudio': {key: analysis[key] for key in ('sha256','byteCount','durationMs')},
                'alignment': {'attemptId': run_id, 'runId': run_id, 'runSha256': 'f'*64,
                    'stateSha256': sha256_file(state_dir/'state.json'), 'artifacts': {'artworkSha256': 'b'*64}},
                'mapping': mapping_identity}
            atomic_write_json(attempt/'declaration.json', declaration)
            atomic_write_json(attempt/GENERATED_MANIFEST_NAME, {'manifestVersion': 1,
                'owner': {'kind': 'display-review-attempt', 'videoId': VIDEO_ID, 'attemptId': attempt_id}, 'generatedPaths': ['.']})
            (attempt/'original.txt').write_text(PLAIN_TEXT+'\n', encoding='utf-8')
            job = {'videoId': VIDEO_ID, 'state': 'ALIGNING_LYRICS', 'displayReview': {
                'attemptId': attempt_id, 'declarationSha256': sha256_file(attempt/'declaration.json'),
                'preReviewTextSha256': declaration['preReviewTextSha256'], 'alignmentRunId': run_id,
                'alignmentRunSha256': 'f'*64}}
            inputs = review_services.DisplayReviewInputs(json_bytes(job), attempt, 'e'*64, 'c'*64,
                json_bytes(mapping), json_bytes(mapping_identity), state_dir/'state.json', json_bytes(analysis),
                True, PLAIN_TEXT, sidecar)
            service = review_services.DisplayReviewService(FutureSongWorkflow._review_operations(), FutureSongWorkflow._review_policy())
            saved = {path: path.read_bytes() for path in root.rglob('*') if path.is_file()}
            result = service.validate(inputs)
            projected = json.loads(result.projected_raw)
            self.assertEqual([line['startMs'] for line in projected['tracks'][0]['lines']], [0, 1500])
            self.assertEqual([line['endMs'] for line in projected['tracks'][0]['lines']], [1200, 2700])
            with self.assertRaisesRegex(FutureSongError, 'audio binding'):
                service.validate(replace(inputs, analysis_audio_json=json_bytes({**analysis, 'sha256': '0'*64})))
            (attempt/'original.txt').write_text('changed words\n', encoding='utf-8')
            changed = (attempt/'original.txt').read_bytes()
            with self.assertRaises(review_services.DisplayReviewAuthorityError):
                service.validate(inputs)
            saved[attempt/'original.txt'] = changed
            self.assertEqual({path: path.read_bytes() for path in root.rglob('*') if path.is_file()}, saved)

    def test_lrc_only_accepted_original_reaches_display_review_with_the_same_source_lines(self):
        from stemwise_engine.lyrics_intake import compile_draft
        with tempfile.TemporaryDirectory() as name:
            case = DisplayReviewCase(Path(name))
            drafts = case.workflow._job_dir(VIDEO_ID) / 'drafts'
            (drafts / 'original.txt').unlink()
            lrc = drafts / 'original.lrc'
            first, second = PLAIN_TEXT.splitlines()
            lrc.write_text(f'[ar:Fixture]\n\n[00:00.00]{first}\n[00:01.50]{second}\n', encoding='utf-8')
            atomic_write_json(drafts / 'lyrics.meta.json', {'videoId': VIDEO_ID,
                'originalLanguage': 'ar', 'originalScript': 'Arab',
                'sources': {'original': {'name': 'Owner reviewed'}}})
            compiled = compile_draft(drafts, {**case.workflow.config, 'outputDir': str(case.root / 'not-yet-analyzed')},
                {VIDEO_ID}, expected_video_id=VIDEO_ID)
            self.assertEqual(compiled['tracks'][0]['text'], PLAIN_TEXT)
            before = lrc.read_bytes()
            case.begin()
            self.assertEqual(case.check_display()['displayReviewCheck'], 'pass')
            self.assertEqual(lrc.read_bytes(), before)
            self.assertFalse((drafts / 'original.txt').exists())

    def test_cli_display_repair_uses_no_model_slots_and_still_runs_full_validation(self):
        scenarios = ('waiting', 'exact-pointer', 'repaired-rejection', 'changed-authority', 'compute', 'unknown-pointer')
        for scenario in scenarios:
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                if scenario in ('repaired-rejection', 'changed-authority'):
                    body = attempt / 'original.txt'
                    body.write_text(PLAIN_TEXT.replace('\n', '\n\n') + '\n', encoding='utf-8')
                    with self.assertRaises(FutureSongError): case.validate()
                    body.write_text(PLAIN_TEXT + '\n', encoding='utf-8')
                if scenario in ('exact-pointer', 'unknown-pointer'): case.job['state'] = 'VALIDATION_FAILED'
                if scenario == 'unknown-pointer': case.job['displayReview']['unknown'] = True
                if scenario == 'compute': case.job['state'] = 'WAITING_FOR_EDITORIAL'
                if scenario == 'changed-authority': case.workflow._editorial_sha.return_value = 'x' * 64
                display_only = scenario in ('waiting', 'exact-pointer', 'repaired-rejection')
                before, sidecar = copy.deepcopy(case.job), case.sidecar.read_bytes()
                stdout, stderr = io.StringIO(), io.StringIO()
                with patch.object(command_services, 'FutureSongWorkflow', return_value=case.workflow) as constructor, \
                        patch.object(future_song_module, 'read_child', side_effect=case.read_child), \
                        patch.object(command_services, 'model_operation_slot',
                            side_effect=RuntimeError('model-operation-capacity-full')) as slots, \
                        redirect_stdout(stdout), redirect_stderr(stderr):
                    constructor._workflow_operation_failure_code = FutureSongWorkflow._workflow_operation_failure_code
                    result = command_services.main(['--config', str(case.workflow.config_path), 'validate', '--video-id', VIDEO_ID])
                self.assertEqual(slots.call_count, 0 if display_only else 1)
                if display_only:
                    self.assertEqual(result, 0, stderr.getvalue())
                    self.assertEqual(case.job['state'], 'VALIDATED')
                    self.assertTrue(case.validated_snapshot_is_current())
                else:
                    self.assertEqual(result, 1)
                    self.assertEqual(case.job, before)
                    self.assertEqual(case.sidecar.read_bytes(), sidecar)
                case.workflow._align_future_lyrics.assert_not_called()

    def test_completed_current_alignment_resumes_display_review_after_rejection(self):
        with tempfile.TemporaryDirectory() as name:
            case = DisplayReviewCase(Path(name))
            attempt = case.begin()
            body = attempt / "original.txt"
            body.write_text(PLAIN_TEXT.replace("\n", "\n\n") + "\n", encoding="utf-8")
            with self.assertRaises(FutureSongError):
                case.validate()
            body.write_text(PLAIN_TEXT + "\n", encoding="utf-8")

            workflow = case.workflow
            case.job["lastAlignmentAttemptId"] = case.alignment_attempt
            run_path = workflow.report_store.ensure_report_identity(VIDEO_ID) / "runs" / case.alignment_attempt / "run.json"
            run_path.parent.mkdir(parents=True)
            atomic_write_json(run_path, {
                "runId": case.alignment_attempt, "videoId": VIDEO_ID,
                "operation": "lyrics-alignment", "status": "complete",
            })
            state_bytes = workflow._alignment_state_path(VIDEO_ID).read_bytes()

            self.assertEqual(case.validate(), {"state": "VALIDATED"})
            self.assertEqual(case.job['validation']['audioSha256'],
                workflow._completed_analysis_audio.return_value['sha256'])
            self.assertEqual(case.job["displayReview"]["alignmentRunId"], case.alignment_attempt)
            self.assertEqual(workflow._alignment_state_path(VIDEO_ID).read_bytes(), state_bytes)
            workflow._align_future_lyrics.assert_not_called()
            self.assertTrue(case.validated_snapshot_is_current())

    def test_read_only_display_check_preserves_job_sidecar_and_package(self):
        for display, valid in ((PLAIN_TEXT, True), (PLAIN_TEXT.replace("\n", "\n\n"), False)):
            with self.subTest(valid=valid), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                (attempt / "original.txt").write_text(display + "\n", encoding="utf-8")
                before = copy.deepcopy(case.job)
                child = case.workflow.runtime / "children" / f"{VIDEO_ID}.stemwise.zip"
                preserved = {path: path.read_bytes() for path in (case.sidecar, case.candidate, child)}
                case.workflow._save_job.reset_mock()
                if valid:
                    self.assertEqual(case.check_display(),
                                     {"state": "WAITING_FOR_DISPLAY_REVIEW", "displayReviewCheck": "pass"})
                else:
                    with self.assertRaisesRegex(FutureSongError, "upstream authority"):
                        case.check_display()
                self.assertEqual(case.job, before)
                for path, content in preserved.items():
                    self.assertEqual(path.read_bytes(), content)
                case.workflow._save_job.assert_not_called()
                case.workflow._pipeline.return_value.process_song.assert_not_called()
                case.workflow._align_future_lyrics.assert_not_called()

    def test_cli_error_identifies_failing_check_without_private_exception_text(self):
        with tempfile.TemporaryDirectory() as name:
            case = DisplayReviewCase(Path(name))
            case.begin()
            case.workflow._editorial_sha.side_effect = FutureSongError(
                "PRIVATE lyrics https://private.example C:/owner/private.txt"
            )
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(command_services, "FutureSongWorkflow", return_value=case.workflow) as constructor:
                constructor._workflow_operation_failure_code = FutureSongWorkflow._workflow_operation_failure_code
                with redirect_stdout(stdout), redirect_stderr(stderr):
                    exit_code = command_services.main([
                        "--config", str(case.workflow.config_path), "validate", "--video-id", VIDEO_ID
                    ])
            self.assertEqual(exit_code, 2)
            self.assertEqual(stdout.getvalue(), "")
            error = json.loads(stderr.getvalue())
            self.assertEqual(error["error"], "future-song-error")
            self.assertEqual(error["failureLocation"]["function"], "execute")
            self.assertGreater(error["failureLocation"]["line"], 0)
            self.assertEqual(set(error["failureLocation"]), {"function", "line"})
            self.assertNotIn("PRIVATE", stderr.getvalue())
            self.assertNotIn("private.example", stderr.getvalue())
            self.assertNotIn(str(case.root), stderr.getvalue())
            self.assertEqual(case.job["state"], "VALIDATION_FAILED")
            self.assertNotIn("validation", case.job)

    def test_rejected_stanza_edit_can_be_undone_without_changing_authority_or_alignment(self):
        for restored in (PLAIN_TEXT, "اَلْحُبُّ جَمِيلٌ!\nهُنَا الآن؟"):
            with self.subTest(decorated=restored != PLAIN_TEXT), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                pointer = copy.deepcopy(case.job["displayReview"])
                state_path = case.workflow._alignment_state_path(VIDEO_ID)
                state_bytes = state_path.read_bytes()
                candidate_bytes = case.candidate.read_bytes()
                sources_bytes = case.sources.read_bytes()
                body = attempt / "original.txt"
                body.write_text(PLAIN_TEXT.replace("\n", "\n\n") + "\n", encoding="utf-8")

                with self.assertRaisesRegex(FutureSongError, "upstream authority"):
                    case.validate()
                self.assertEqual(case.job["state"], "VALIDATION_FAILED")
                self.assertEqual(case.job["displayReview"]["result"], "rejected-authority-change")

                body.write_text(restored + "\n", encoding="utf-8")
                self.assertEqual(case.validate(), {"state": "VALIDATED"})
                for key, value in pointer.items():
                    self.assertEqual(case.job["displayReview"][key], value)
                self.assertNotIn("rejectedEditorialSha256", case.job["displayReview"])
                self.assertEqual(state_path.read_bytes(), state_bytes)
                self.assertEqual(case.candidate.read_bytes(), candidate_bytes)
                self.assertEqual(case.sources.read_bytes(), sources_bytes)
                projected = json.loads(case.sidecar.read_bytes())
                original_lines = case.aligned["tracks"][0]["lines"]
                for before, after in zip(original_lines, projected["tracks"][0]["lines"]):
                    self.assertEqual((before["startMs"], before["endMs"]),
                                     (after["startMs"], after["endMs"]))
                    self.assertEqual([(word["startMs"], word["endMs"]) for word in before["words"]],
                                     [(word["startMs"], word["endMs"]) for word in after["words"]])
                case.workflow._align_future_lyrics.assert_not_called()
                self.assertTrue(case.validated_snapshot_is_current())

    def test_rejected_review_retry_still_rejects_changed_words_and_stanza_structure(self):
        for invalid in (PLAIN_TEXT.replace("\n", "\n\n"), "الحب كبير\nهنا الآن"):
            with self.subTest(kind=hashlib.sha256(invalid.encode()).hexdigest()[:8]), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                child = case.workflow.runtime / "children" / f"{VIDEO_ID}.stemwise.zip"
                preserved = {path: path.read_bytes() for path in (case.sidecar, case.candidate, child)}
                (attempt / "original.txt").write_text(invalid + "\n", encoding="utf-8")
                for _ in range(2):
                    with self.assertRaisesRegex(FutureSongError, "upstream authority"):
                        case.validate()
                    self.assertEqual(case.job["state"], "VALIDATION_FAILED")
                    self.assertNotIn("validation", case.job)
                    for path, content in preserved.items():
                        self.assertEqual(path.read_bytes(), content)
                case.workflow._pipeline.return_value.process_song.assert_not_called()
                case.workflow._align_future_lyrics.assert_not_called()

    def test_corrected_rejected_review_rechecks_frozen_alignment_binding(self):
        with tempfile.TemporaryDirectory() as name:
            case = DisplayReviewCase(Path(name))
            attempt = case.begin()
            body = attempt / "original.txt"
            body.write_text(PLAIN_TEXT.replace("\n", "\n\n") + "\n", encoding="utf-8")
            with self.assertRaises(FutureSongError):
                case.validate()
            body.write_text(PLAIN_TEXT + "\n", encoding="utf-8")
            state_path = case.workflow._alignment_state_path(VIDEO_ID)
            state = json.loads(state_path.read_bytes())
            state["durationMs"] += 1
            atomic_write_json(state_path, state)
            sidecar_bytes = case.sidecar.read_bytes()

            with self.assertRaisesRegex(FutureSongError, "alignment or mapping binding is stale"):
                case.validate()
            self.assertEqual(case.job["state"], "VALIDATION_FAILED")
            self.assertNotIn("validation", case.job)
            self.assertEqual(case.sidecar.read_bytes(), sidecar_bytes)
            case.workflow._align_future_lyrics.assert_not_called()

    def test_unchanged_and_decorated_reviews_reproject_and_stay_body_private(self):
        for display in (PLAIN_TEXT, "اَلْحُبُّ جَمِيلٌ!\nهُنَا الآن؟"):
            with self.subTest(changed=display != PLAIN_TEXT), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                (attempt / "original.txt").write_text(display + "\n", encoding="utf-8")

                self.assertEqual(case.consume(), {"state": "VALIDATED"})
                projected = json.loads(case.sidecar.read_text(encoding="utf-8"))
                self.assertEqual(projected["tracks"][0]["lines"][0]["text"], display.splitlines()[0])
                report = json.loads(
                    (case.workflow.report_store.report_root(VIDEO_ID) / "validation.json").read_text(
                        encoding="utf-8"
                    )
                )
                self.assertFalse(report["containsLyricText"])
                body = display.splitlines()[0]
                for path in (
                    attempt / GENERATED_MANIFEST_NAME,
                    attempt / "declaration.json",
                    case.workflow.report_store.report_root(VIDEO_ID) / "validation.json",
                    case.workflow.report_store.report_root(VIDEO_ID)
                    / "runs"
                    / case.job["validation"]["runId"]
                    / "run.json",
                ):
                    self.assertNotIn(body, path.read_text(encoding="utf-8"))
                self.assertEqual(
                    {path.name for path in attempt.iterdir()},
                    {GENERATED_MANIFEST_NAME, "declaration.json", "original.txt"},
                )
                self.assertTrue(case.validated_snapshot_is_current())
                (attempt / "original.txt").write_text(display + "!\n", encoding="utf-8")
                self.assertFalse(case.validated_snapshot_is_current())

    def test_audio_bundle_finalization_pointer_recovers_before_delivered_job_is_saved(self):
        self._check_finalization_pointer()

    def test_artwork_finalization_pointer_recovers_without_bypassing_repair_authority(self):
        self._check_finalization_pointer(artwork=True)

    def _check_finalization_pointer(self, *, artwork=False):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as name:
            case = DisplayReviewCase(Path(name))
            case.begin()
            self.assertEqual(case.consume(), {'state': 'VALIDATED'})
            workflow = case.workflow
            workflow.ready = Path(name) / 'Ready to Import'
            workflow.ready.mkdir()
            child = workflow.runtime / 'children' / f'{VIDEO_ID}.stemwise.zip'
            destination = workflow.ready / child.name
            destination.write_bytes(b'authenticated transport with Original and unchanged metadata')
            for helper in ('common.py', 'future_song.py', 'packages.py'):
                path = workflow.config_path.parent / 'src' / helper
                path.parent.mkdir(exist_ok=True)
                path.write_text('fixture helper', encoding='utf-8')
            repair_path = None
            if artwork:
                previous_sha = future_song_module.sha256_file(destination)
                prior_id, prior_sha = workflow.report_store.commit_finalization_run(case.job,
                    destination=destination, package_sha256=previous_sha)
                case.job['delivery'] = {'runId': prior_id, 'runSha256': prior_sha,
                    'sha256': previous_sha, 'reviewEvidenceRetained': True}
                child.write_bytes(b'repaired artwork child')
                case.cover = b'reviewed replacement cover'
                arguments = {'source_video_id': VIDEO_ID,
                    'previous_child_sha256': case.job['validation']['childSha256'],
                    'previous_artwork_sha256': case.job['validation']['artworkSha256'],
                    'child_sha256': future_song_module.sha256_file(child),
                    'artwork_sha256': hashlib.sha256(case.cover).hexdigest()}
                artifacts = workflow.report_store.artwork_repair_artifacts(case.job, **arguments)
                run_id, run_sha, repaired_at = workflow.report_store.commit_artwork_repair_run(case.job, **arguments)
                case.job['artworkRepair'] = {**artifacts, 'runId': run_id,
                    'runSha256': run_sha, 'repairedAt': repaired_at}
                repair_path = workflow.report_store.report_root(VIDEO_ID) / 'runs' / run_id / 'run.json'
                with patch.object(future_song_module, 'read_child', side_effect=case.read_child), \
                        patch('stemwise_engine.original_delivery.read_bundle',
                            side_effect=AssertionError('A current repair must not reopen the previous delivery')):
                    self.assertTrue(workflow._validated_snapshot_is_current(case.job))
                    self.assertTrue(workflow._validated_snapshot_is_current(case.job, allow_finalization_pointer=True))
                destination.write_bytes(b'authenticated transport with repaired artwork and unchanged Original')
            workflow.report_store.commit_finalization_run(case.job, destination=destination,
                package_sha256=future_song_module.sha256_file(destination))
            def immutable_run(*args, **kwargs):
                if kwargs['operation'] in {'package-finalization', 'artwork-repair'}:
                    return type(workflow.report_store).immutable_run_matches(workflow.report_store, *args, **kwargs)
                return not (kwargs['operation'] == 'display-review' and kwargs.get('require_current_pointer'))
            workflow.report_store.immutable_run_matches = immutable_run
            # Numerical/display authority uses the existing fixture; transport validation
            # is independently covered with real FLAC/Opus ZIPs in test_original_delivery.
            with patch.object(future_song_module, 'read_child', side_effect=case.read_child), \
                    patch('stemwise_engine.original_delivery.read_bundle', return_value=(case.read_child()[0], case.cover, {})) as transport:
                self.assertFalse(workflow._validated_snapshot_is_current(case.job))
                self.assertTrue(workflow._validated_snapshot_is_current(case.job, allow_finalization_pointer=True))
                transport.assert_called_once()
                pointer = report_control_path(workflow.report_store.report_root(VIDEO_ID), 'current-run.json')
                saved_pointer = pointer.read_bytes()
                stale_pointers = [{'runId': '0' * 32, 'runSha256': '0' * 64}]
                if artwork:
                    stale_pointers.append({'runId': prior_id, 'runSha256': prior_sha})
                for stale in stale_pointers:
                    atomic_write_json(pointer, stale)
                    self.assertFalse(workflow._validated_snapshot_is_current(case.job, allow_finalization_pointer=True))
                pointer.write_bytes(saved_pointer)
                if repair_path is not None:
                    saved_repair = repair_path.read_bytes()
                    repair_path.write_bytes(saved_repair + b'changed')
                    self.assertFalse(workflow._validated_snapshot_is_current(case.job, allow_finalization_pointer=True))
                    repair_path.write_bytes(saved_repair)
                intact = destination.read_bytes()
                destination.write_bytes(intact + b'changed')
                self.assertFalse(workflow._validated_snapshot_is_current(case.job, allow_finalization_pointer=True))
                destination.write_bytes(intact)
                transport.side_effect = ValueError('metadata changed')
                self.assertFalse(workflow._validated_snapshot_is_current(case.job, allow_finalization_pointer=True))

    def test_lexical_line_order_and_disallowed_mark_changes_are_rejected(self):
        changed = (
            "الحب كبير\nهنا الآن",
            "الحب جميل هنا الآن",
            "هنا الآن\nالحب جميل",
            "آلحب جميل\nهنا الآن",
        )
        for display in changed:
            with self.subTest(kind=hashlib.sha256(display.encode()).hexdigest()[:8]), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                attempt = case.begin()
                (attempt / "original.txt").write_text(display + "\n", encoding="utf-8")

                with self.assertRaisesRegex(FutureSongError, "upstream authority"):
                    case.consume()
                self.assertEqual(
                    case.job["displayReview"]["result"], "rejected-authority-change"
                )

    def test_stale_binding_is_rejected_before_reprojection(self):
        with tempfile.TemporaryDirectory() as name:
            case = DisplayReviewCase(Path(name))
            case.begin()
            state_path = case.workflow._alignment_state_path(VIDEO_ID)
            state = json.loads(state_path.read_text(encoding="utf-8"))
            state["durationMs"] += 1
            atomic_write_json(state_path, state)

            with self.assertRaisesRegex(FutureSongError, "binding is stale"):
                case.consume()
            self.assertEqual(case.job["state"], "WAITING_FOR_DISPLAY_REVIEW")

    def test_consumption_resumes_at_each_durable_boundary(self):
        for boundary in ("sidecar", "package", "run", "report", "job-task"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as name:
                case = DisplayReviewCase(Path(name))
                case.begin()
                waiting = copy.deepcopy(case.job)
                case.workflow._save_job.reset_mock()
                case.workflow._write_task.reset_mock()
                pipeline = case.workflow._pipeline.return_value
                interrupted = False

                if boundary == "sidecar":
                    pipeline.process_song.side_effect = [
                        OSError("interrupted after sidecar"),
                        "complete",
                    ]
                elif boundary == "package":
                    commit = case.workflow.report_store.commit_display_review_run

                    def commit_after_package(*args, **kwargs):
                        nonlocal interrupted
                        if not interrupted:
                            interrupted = True
                            raise OSError("interrupted after package")
                        return commit(*args, **kwargs)

                    case.workflow.report_store.commit_display_review_run = Mock(
                        side_effect=commit_after_package
                    )
                elif boundary == "report":
                    case.workflow._save_job.side_effect = [
                        OSError("interrupted after report"),
                        None,
                    ]
                elif boundary == "job-task":
                    case.workflow._write_task.side_effect = [
                        OSError("interrupted after job"),
                        None,
                    ]

                write_json = future_song_module.atomic_write_json

                def interrupt_after_run(path, value, **kwargs):
                    nonlocal interrupted
                    if (
                        boundary == "run"
                        and Path(path).name == "validation.json"
                        and not interrupted
                    ):
                        interrupted = True
                        raise OSError("interrupted after run")
                    return write_json(path, value, **kwargs)

                with patch.object(
                    future_song_module,
                    "atomic_write_json",
                    side_effect=interrupt_after_run,
                ):
                    with self.assertRaises(OSError):
                        case.consume()

                    attempt_id = waiting["displayReview"]["attemptId"]
                    run_path = (
                        case.workflow.report_store.report_root(VIDEO_ID)
                        / "runs"
                        / attempt_id
                        / "run.json"
                    )
                    report_path = case.workflow.report_store.report_root(VIDEO_ID) / "validation.json"
                    child = (
                        case.workflow.runtime
                        / "children"
                        / f"{VIDEO_ID}.stemwise.zip"
                    )
                    before = {
                        path: path.read_bytes()
                        for path in (case.sidecar, child, run_path, report_path)
                        if path.is_file()
                    }

                    if boundary == "job-task":
                        case.workflow._load_job = Mock(return_value=case.job)
                        case.workflow._validate_impl = Mock(
                            side_effect=AssertionError(
                                "current display validation must not rerun"
                            )
                        )
                        with patch.object(
                            future_song_module,
                            "read_child",
                            side_effect=case.read_child,
                        ):
                            result = case.workflow._validate_action(VIDEO_ID)
                        case.workflow._validate_impl.assert_not_called()
                    else:
                        case.job = copy.deepcopy(waiting)
                        case.workflow._save_job.side_effect = None
                        case.workflow._write_task.side_effect = None
                        result = case.consume()

                self.assertEqual(result, {"state": "VALIDATED"})
                self.assertTrue(case.validated_snapshot_is_current())
                for path, payload in before.items():
                    self.assertEqual(path.read_bytes(), payload)
                self.assertEqual(
                    [
                        path.name
                        for path in (
                            case.workflow.report_store.report_root(VIDEO_ID) / "runs"
                        ).iterdir()
                    ],
                    [attempt_id],
                )


if __name__ == "__main__":
    unittest.main()
