from __future__ import annotations

import ast
import copy
import hashlib
import json
import re
import sys
import tempfile
import threading
import base64
import shutil
import subprocess
import unittest
import wave
from contextlib import ExitStack
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


PROJECT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
SRC = PROJECT / "src"
WORKSPACE = PROJECT.parents[1]
sys.path.insert(0, str(WORKSPACE / 'Test suite/pc/unit'))

from stemwise_engine.workflow_context import report_control_path
import stemwise_engine.editorial_review as editorial_review
import stemwise_engine.workflow_reports as workflow_reports
import stemwise_engine.workflow_review as workflow_review
from stemwise_engine.workflow_review import companion_requirements
import stemwise_engine.source_acquisition as source_acquisition
from stemwise_engine.source_acquisition import collect_normal_youtube_candidates

import stemwise_engine.future_song as future_song_module  # noqa: E402
from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError, ReportSizeLimitExceeded, extract_video_id, option_safe_video_id_argument, validate_synced_companion_timing
from stemwise_engine.editorial_review import validate_agent_review, validate_plain_draft
from stemwise_engine.workflow_command import parser as future_song_parser
from stemwise_engine.lyrics_acquire import (  # noqa: E402
    acquire_exact_item_draft,
    discover_counterpart_candidates,
)
from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION  # noqa: E402


def workflow_fixture(workspace_root: Path) -> FutureSongWorkflow:
    from workflow_fixture import initialized_workflow
    workflow = initialized_workflow(workspace_root)
    workflow._run_mutating_operation = lambda _video_id, _action, callback: callback()
    return workflow


def private_finalization_fixture(workspace_root: Path) -> FutureSongWorkflow:
    # These transaction checks retain the private two-file branch; audio publication is tested separately.
    from dataclasses import replace
    import numpy as np
    import soundfile as sf
    from stemwise_engine.recording_source import selected_recording
    workflow = workflow_fixture(workspace_root)
    from stemwise_engine.workflow_context import WorkflowSourceContext
    workflow.source_context = WorkflowSourceContext(
        state_name='current-qobuz-11-fixture', root=workflow.install_root,
        ready=workspace_root / 'ready', reports=workspace_root / 'reports',
        analysis_temp=workflow.analysis_temp, recording_key='qobuz:11',
        recording_identity_json=b'{}', lyric_reference_ids=('KbKqkR3Q6SA',))
    workflow.key = "qobuz:11"
    project = workspace_root / 'PC side/stemwise-playlist-builder'
    project.mkdir(parents=True, exist_ok=True)
    library = workspace_root / 'PC side/qobuz-comparison'
    audio = library / 'audio/qobuz/11/native.flac'
    audio.parent.mkdir(parents=True)
    sf.write(audio, np.zeros((48000, 2), dtype='float32'), 48000, subtype='PCM_24')
    future_song_module.atomic_write_json(audio.parent / 'download.json', {
        'qobuzTrackId': 11, 'file': audio.name, 'bytes': audio.stat().st_size,
        'sha256': future_song_module.sha256_file(audio)})
    future_song_module.atomic_write_json(library / 'comparison-results/library-files.json', {
        'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/native.flac'}],
        'playlist': [{'videoId': 'KbKqkR3Q6SA', 'source': 'qobuz', 'trackId': 11}]})
    workflow.config.update(projectDir=str(project), pcSideRootWindows=str(workspace_root / 'PC side'))
    from stemwise_engine.recording_source import RecordingSelection
    workflow.video_id = 'KbKqkR3Q6SA'
    workflow.recording = RecordingSelection(*selected_recording(workflow.config, workflow.key),
        [workflow.video_id], {})
    workflow.source_context = replace(workflow.source_context,
        recording_identity_json=json.dumps(workflow.recording[3], sort_keys=True).encode('utf-8'))
    workflow = FutureSongWorkflow(workflow.config_path, source_context=workflow.source_context,
        recording=workflow.recording)
    workflow._run_mutating_operation = lambda _video_id, _action, callback: callback()
    return workflow


def endpoint_occurrence_candidate(performed_index: int = 0) -> dict[str, object]:
    occurrence_id = f"occurrence-{performed_index:024x}"
    word_id = f"occurrence-word-{performed_index:024x}"
    return {
        "occurrenceId": occurrence_id,
        "performedIndex": performed_index,
        "sourcePhysicalIndex": performed_index,
        "familyCandidates": {
            "mms-fa": {
                "route": "torchaudio-mms-fa-on-htdemucs-vocals",
                "startMs": 100,
                "endMs": 900,
                "confidence": 0.8,
                "words": [
                    {
                        "occurrenceWordId": word_id,
                        "startMs": 100,
                        "endMs": 400,
                        "confidence": 0.75,
                        "acousticTokenCount": 1,
                    }
                ],
            },
            "language-wav2vec2": {
                "route": "language-wav2vec2:pinned",
                "startMs": 120,
                "endMs": 880,
                "words": [
                    {
                        "occurrenceWordId": word_id,
                        "startUtf16": 0,
                        "endUtf16": 5,
                        "startMs": 120,
                        "endMs": 420,
                    }
                ],
            },
        },
        "selectedPublisherFamily": "mms-fa",
        "publishDecision": {"result": "words"},
    }


import stemwise_engine.workflow_diagnostics as workflow_diagnostics

def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class FakeLyricsClient:
    def __init__(self, returned_id: str = "KbKqkR3Q6SA"):
        self.returned_id = returned_id

    def get_watch_playlist(self, *, videoId: str, limit: int):
        return {"tracks": [{"videoId": self.returned_id}], "lyrics": "browse-1"}

    def get_lyrics(self, browse_id: str, *, timestamps: bool):
        return {"lyrics": "First line\nSecond line"}


class FutureSongTests(unittest.TestCase):
    def test_tracked_config_relocates_actual_unqualified_workflow_to_each_checkout(self):
        for checkout in ('first checkout', 'second checkout'):
            with self.subTest(checkout=checkout), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                root = Path(name) / checkout
                config = root / 'PC side/stemwise-playlist-builder/config.json'
                for relative in ('PC side/stemwise-playlist-builder/config.json',
                        'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json',
                        'StemWise/contracts/enrichment/song-enrichment-v2.schema.json',
                        'StemWise/contracts/enrichment/installed-enrichment-v2.schema.json'):
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((WORKSPACE / relative).read_bytes())
                loaded = future_song_module.load_config(config)
                self.assertEqual(Path(loaded['outputDir']), root / 'PC side/generated-packages')
                workflow = FutureSongWorkflow(config)
                self.assertEqual(workflow.workspace_root, root)
                self.assertEqual(workflow.install_root, config.parent / 'future-songs')
                self.assertEqual(workflow.ready, root / 'Ready to Import')
                self.assertTrue(workflow.analysis_temp.is_relative_to(root / 'PC side/temporary-analysis'))
                self.assertFalse(hasattr(workflow, 'qualification'))
                created = workflow._base_job('abcdefghijk', 'https://youtu.be/abcdefghijk')
                self.assertTrue(created['deliveryAllowed'])
                self.assertFalse(created['qualificationClaimsAllowed'])
                workflow._save_job(created)
                with patch.object(future_song_module.subprocess, 'run', side_effect=FileNotFoundError('wslpath')):
                    self.assertEqual(workflow.status('abcdefghijk')['state'], 'ANALYSIS_FAILED')
                task = (workflow._job_dir('abcdefghijk')/'AGENT_TASK.txt').read_text(encoding='utf-8')
                self.assertIn(str(workflow._job_dir('abcdefghijk').resolve()), task)
                self.assertIn("-Action Status -VideoId 'abcdefghijk'", task)

    def test_status_preserves_other_state_and_reports_damaged_progress_without_changing_bytes(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root, workflow.jobs, workflow.runtime = root / 'state', root / 'jobs', root / 'runtime'
            video = 'abcdefghijk'
            job = {'videoId': video, 'state': 'NEW', 'deliveryAllowed': False}
            alignment = workflow.root / 'lyrics-alignment/jobs' / video / 'state.json'
            checkpoint = workflow.runtime / 'work/checkpoints' / f'{video}.json'
            alignment.parent.mkdir(parents=True)
            checkpoint.parent.mkdir(parents=True)
            for value in ([], None, {'status': 'working', 'attemptedEvidence': 7},
                          {'status': 'working', 'reasons': 3}, {'status': 'working', 'metrics': []}):
                alignment.write_text(json.dumps(value), encoding='utf-8')
                checkpoint.write_text(json.dumps({'status': 'complete', 'stages': {}}), encoding='utf-8')
                before = alignment.read_bytes(), checkpoint.read_bytes()
                result = workflow.summary(job)
                self.assertEqual(result['state'], 'NEW')
                self.assertEqual(result['analysisProgress']['status'], 'complete')
                self.assertEqual(result['lyricsAlignmentProgress']['status'], 'corrupt')
                self.assertEqual(result['lyricsAlignmentProgress']['source'], alignment.relative_to(root).as_posix())
                self.assertEqual((alignment.read_bytes(), checkpoint.read_bytes()), before)
            alignment.write_text(json.dumps({'status': 'ready'}), encoding='utf-8')
            for value in ([], None, {'status': 'complete', 'stages': []}):
                checkpoint.write_text(json.dumps(value), encoding='utf-8')
                before = checkpoint.read_bytes()
                result = workflow.summary(job)
                self.assertEqual(result['lyricsAlignmentProgress']['status'], 'ready')
                self.assertEqual(result['analysisProgress']['status'], 'corrupt')
                self.assertEqual(checkpoint.read_bytes(), before)

    def test_workflow_failure_dispatch_retains_specific_actionable_codes(self):
        from stemwise_engine.workflow_records import WorkflowInputError
        for message, code in (
                ('inspected artwork is not selected; run Artwork first', 'artwork-unavailable'),
                ('recording review input binding is stale', 'review-stale'),
                ('native ASR cache identity is invalid', 'model-identity-mismatch'),
                ('Existing delivery changed while retaining rollback', 'publication-conflict')):
            with self.subTest(code=code):
                self.assertEqual(FutureSongWorkflow._workflow_operation_failure_code(
                    WorkflowInputError(message + ': PRIVATE-LYRIC-BODY', reason_code=code,
                        affected_input='exact current input', next_action='Read Task for the exact repair.')), code)

    def test_endpoint_contexts_stay_until_review_acceptance_and_delivery(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "state"
            workflow.jobs = workflow.root / "jobs"
            video_id, attempt_id = "9PXEE7Da7hM", "a" * 32
            attempt = workflow._endpoint_review_attempt_dir(video_id, attempt_id)
            attempt.mkdir(parents=True)
            from stemwise_engine.endpoint_ctc_evidence import _directory
            context_root = _directory(attempt)
            context = context_root / "context-000"
            context.mkdir(parents=True)
            (context / "source.wav").write_bytes(b"retained replay audio")
            packet = context_root / "manifest.json"
            packet.write_bytes(b"numeric results")
            from stemwise_engine.endpoint_owsm_ctc import _directory as owsm_directory
            native_context = owsm_directory(attempt) / "context-001"
            native_context.mkdir(parents=True)
            (native_context / "source.wav").write_bytes(b"multilingual replay audio")
            native_packet = native_context.parent / "manifest.json"
            native_packet.write_bytes(b"multilingual numeric results")
            job = {"videoId": video_id, "state": "WAITING_FOR_DISPLAY_REVIEW",
                   "endpointReview": {"attemptId": attempt_id}}
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertTrue(context.exists())
            self.assertTrue(native_context.exists())
            job["state"] = "DELIVERED"
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertTrue(context.exists())
            self.assertTrue(native_context.exists())
            job['endpointReview']['result'] = 'accepted'
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertFalse(context.exists())
            self.assertFalse(native_context.exists())
            self.assertFalse((root / "Pending deletion").exists())
            self.assertEqual(native_packet.read_bytes(), b"multilingual numeric results")
            self.assertEqual(packet.read_bytes(), b"numeric results")
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertFalse((root / "Pending deletion").exists())
            # Delivery owns only its exact review; another attempt keeps its replay inputs.
            source_attempt = workflow._endpoint_review_attempt_dir(video_id, "b" * 32)
            source_context = _directory(source_attempt) / "context-000"
            source_context.mkdir(parents=True)
            (source_context / "source.wav").write_bytes(b"singing replay audio")
            job["state"] = "VALIDATED"
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertTrue(source_context.exists())
            job["state"] = "DELIVERED"
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertTrue(source_context.exists())
            job["endpointReview"] = {"attemptId": "b" * 32, 'result': 'accepted'}
            workflow._cleanup_completed_endpoint_contexts(job)
            self.assertFalse(source_context.exists())
            self.assertFalse((root / "Pending deletion").exists())
            self.assertEqual(packet.read_bytes(), b"numeric results")



    def test_ready_output_is_fixed_to_the_workspace_delivery_root(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            pc_side = root / "PC side"
            builder = pc_side / "stemwise-playlist-builder"
            builder.mkdir(parents=True)
            config = {
                "pcSideRootWindows": str(pc_side),
                "futureRootWindows": str(builder / "future-songs"),
                "futureReadyDirWindows": str(root / "wrong-ready-root"),
                "tempDir": str(pc_side / "temporary-analysis"),
                "generatorVersion": "test",
            }
            with patch('stemwise_engine.future_song.load_config', return_value=config):
                with self.assertRaisesRegex(
                    FutureSongError, "workspace Ready to Import directory"
                ):
                    FutureSongWorkflow(builder / "config.json")

    def test_core_qualification_import_stays_independent_of_alignment_runtime(self):
        from stemwise_engine.common import run_process_tree
        completed = run_process_tree([sys.executable, '-c',
            "import sys; sys.path.insert(0,sys.argv[1]); sys.modules['stemwise_engine.lyrics_align_pc']=None; import stemwise_engine.lyrics_qualification as lyrics_qualification",
            str(SRC)], capture_output=True, timeout=15)
        self.assertEqual(completed.returncode, 0, completed.stderr)















    def test_alignment_diagnostics_preserve_result_without_private_text(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        occurrence = endpoint_occurrence_candidate()
        endpoint = {
            "resultClass": "committed-mixed",
            "lineCount": 1,
            "lineReadyCount": 1,
            "wordLineCount": 1,
            "lineOnlyCount": 0,
            "lineOnlyReasons": {},
            "unresolvedLineReasons": {},
            "familyCandidateCounts": {"mms-fa": 1, "language-wav2vec2": 1},
            "selectedPublisherFamilyCounts": {"mms-fa": 1},
            "baselinePublishedLineCount": 1,
            "anchoredFilledLineCount": 0,
            "bootstrapAttempted": False,
            "occurrenceCandidates": [occurrence],
            "privateText": "must not escape",
        }
        diagnostics = workflow_diagnostics._alignment_diagnostics(
            {
                "status": "committed-mixed",
                "alignmentVersion": 5,
                "endpointEvidenceReused": False,
                "endpointEvidence": endpoint,
                "endpointRouteAttempts": [
                    {
                        "routeId": "htdemucs-vocals",
                        "reused": False,
                        "endpointEvidence": endpoint,
                        "privateText": "must not escape",
                    },
                    {
                        "routeId": "kimberley-jensen-roformer-vocals",
                        "reused": True,
                        "endpointEvidence": endpoint,
                    },
                ],
                "selectedEndpointAttemptIndex": 0,
                "routeAttempts": [
                    {
                        "sequence": 0,
                        "stage": "caption",
                        "routeId": "youtube-caption",
                        "status": "started",
                    },
                    {
                        "sequence": 1,
                        "stage": "caption",
                        "routeId": "youtube-caption",
                        "status": "unavailable",
                        "cacheReused": True,
                    },
                    {
                        "sequence": 2,
                        "stage": "structure",
                        "routeId": "faster-whisper:en",
                        "status": "failure",
                        "cacheReused": False,
                        "code": "RuntimeError",
                    },
                ],
                "evidenceAttempts": [
                    {
                        "type": "faster-whisper:en",
                        "accepted": True,
                        "reused": False,
                        "sequenceCandidate": {
                            "performedOccurrences": [
                                {
                                    "occurrenceId": "occurrence-000000000000000000000000",
                                    "performedIndex": 0,
                                    "sourcePhysicalIndex": 0,
                                    "startMs": 1_000,
                                    "lyrics": "must not escape",
                                }
                            ],
                            "lyrics": "must not escape",
                        },
                    }
                ],
                "routeFailures": [
                    {
                        "stage": "endpoint",
                        "routeId": "kimberley-jensen-roformer-vocals",
                        "code": "RuntimeError",
                        "privateDetail": "must not escape",
                    }
                ],
                "roformerCascade": {
                    "cascadeVersion": 1,
                    "routes": [
                        {
                            "routeId": "kimberley-jensen-roformer-vocals",
                            "status": "failed",
                            "failureStage": "endpoint",
                            "failureCode": "RuntimeError",
                            "privateDetail": "must not escape",
                        }
                    ],
                },
                "pinnedModels": {
                    "whisperModelSha256": "a" * 64,
                    "modelPath": "private/path",
                },
            }
        )

        self.assertEqual(diagnostics["resultClass"], "committed-mixed")
        self.assertEqual(diagnostics["endpointEvidence"]["wordLineCount"], 1)
        self.assertEqual(
            diagnostics["endpointEvidence"]["familyCandidateCounts"],
            {"mms-fa": 1, "language-wav2vec2": 1},
        )
        self.assertNotIn("privateText", diagnostics["endpointEvidence"])
        self.assertNotIn("occurrenceCandidates", diagnostics["endpointEvidence"])
        self.assertEqual(diagnostics["selectedEndpointAttemptIndex"], 0)
        self.assertEqual(
            diagnostics["endpointRouteAttempts"][0]["endpointEvidence"][
                "occurrenceCandidates"
            ][0][
                "familyCandidates"
            ][
                "mms-fa"
            ]["words"][0]["occurrenceWordId"],
            "occurrence-word-000000000000000000000000",
        )
        self.assertEqual(
            [item["routeId"] for item in diagnostics["endpointRouteAttempts"]],
            ["htdemucs-vocals", "kimberley-jensen-roformer-vocals"],
        )
        self.assertEqual(
            [item["status"] for item in diagnostics["routeAttempts"]],
            ["started", "unavailable", "failure"],
        )
        self.assertEqual(
            diagnostics["attempts"][0]["sequenceCandidate"][
                "performedOccurrences"
            ][0]["startMs"],
            1_000,
        )
        self.assertEqual(
            diagnostics["routeFailures"][0],
            {
                "stage": "endpoint",
                "routeId": "kimberley-jensen-roformer-vocals",
                "code": "RuntimeError",
            },
        )
        self.assertEqual(
            diagnostics["roformerCascade"]["routes"][0]["failureCode"],
            "RuntimeError",
        )
        self.assertEqual(diagnostics["pinnedModels"], {"whisperModelSha256": "a" * 64})
        self.assertNotIn("must not escape", json.dumps(diagnostics))

    def test_alignment_diagnostics_rejects_open_or_misowned_family_candidates(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        candidate = endpoint_occurrence_candidate()
        invalid = []
        leaked_record = copy.deepcopy(candidate)
        leaked_record["lyrics"] = "must not escape"
        invalid.append(leaked_record)
        leaked_family = copy.deepcopy(candidate)
        leaked_family["familyCandidates"]["mms-fa"]["sourceBody"] = "must not escape"
        invalid.append(leaked_family)
        unknown_family = copy.deepcopy(candidate)
        unknown_family["familyCandidates"]["another-mms-vote"] = copy.deepcopy(
            unknown_family["familyCandidates"]["mms-fa"]
        )
        invalid.append(unknown_family)
        wrong_publisher = copy.deepcopy(candidate)
        wrong_publisher["selectedPublisherFamily"] = "language-wav2vec2"
        invalid.append(wrong_publisher)
        unresolved_selected = copy.deepcopy(candidate)
        unresolved_selected["publishDecision"] = {
            "result": "unresolved",
            "code": "independent-endpoint-missing",
        }
        invalid.append(unresolved_selected)
        for value in invalid:
            with self.subTest(value=value):
                with self.assertRaises(FutureSongError):
                    workflow_diagnostics._occurrence_candidate_diagnostics([value])

        unresolved = copy.deepcopy(candidate)
        unresolved["publishDecision"] = {
            "result": "unresolved",
            "code": "independent-endpoint-missing",
        }
        del unresolved["selectedPublisherFamily"]
        projected = workflow_diagnostics._occurrence_candidate_diagnostics([unresolved])[0]
        self.assertNotIn("selectedPublisherFamily", projected)

    def test_alignment_diagnostics_preserves_every_closed_family_candidate(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        candidate = endpoint_occurrence_candidate()
        verifier = copy.deepcopy(candidate["familyCandidates"]["language-wav2vec2"])
        candidate["familyCandidates"].update(
            {
                "youtube-caption": {**verifier, "route": "youtube-caption:manual"},
                "unprompted-faster-whisper": {
                    **verifier,
                    "route": "unprompted-faster-whisper:pinned",
                },
            }
        )
        projected = workflow_diagnostics._occurrence_candidate_diagnostics([candidate])[0]
        self.assertEqual(
            list(projected["familyCandidates"]),
            [
                "mms-fa",
                "language-wav2vec2",
                "youtube-caption",
                "unprompted-faster-whisper",
            ],
        )

    def test_alignment_diagnostics_allows_overlapping_nonpublisher_caption_words(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        candidate = endpoint_occurrence_candidate()
        verifier = copy.deepcopy(candidate["familyCandidates"]["language-wav2vec2"])
        verifier["endMs"] = 500
        verifier["words"] = [
            {
                "occurrenceWordId": "occurrence-word-000000000000000000000001",
                "startUtf16": 0,
                "endUtf16": 2,
                "startMs": 120,
                "endMs": 700,
            },
            {
                "occurrenceWordId": "occurrence-word-000000000000000000000002",
                "startUtf16": 3,
                "endUtf16": 5,
                "startMs": 300,
                "endMs": 500,
            },
        ]
        candidate["familyCandidates"]["youtube-caption"] = {
            **verifier,
            "route": "youtube-caption:manual",
        }

        projected = workflow_diagnostics._occurrence_candidate_diagnostics([candidate])[0]

        self.assertEqual(
            projected["familyCandidates"]["youtube-caption"]["words"],
            verifier["words"],
        )

        invalid_publisher = copy.deepcopy(candidate)
        invalid_publisher["familyCandidates"]["language-wav2vec2"] = verifier
        with self.assertRaisesRegex(FutureSongError, "word timing is invalid"):
            workflow_diagnostics._occurrence_candidate_diagnostics([invalid_publisher])

    def test_alignment_diagnostics_accepts_closed_line_only_consensus(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        candidate = endpoint_occurrence_candidate()
        lexical = copy.deepcopy(candidate["familyCandidates"]["language-wav2vec2"])
        for word in lexical["words"]:
            word.pop("startUtf16")
            word.pop("endUtf16")
        candidate["familyCandidates"].update(
            {
                "owsm-ctc": {**copy.deepcopy(lexical), "route": "owsm-ctc:pinned"},
                "nemo-nfa": {**copy.deepcopy(lexical), "route": "nemo-nfa:pinned"},
            }
        )
        candidate["selectedPublisherFamily"] = "owsm-ctc"
        candidate["supportingEndpointFamilies"] = ["owsm-ctc", "nemo-nfa"]
        candidate["publishDecision"] = {
            "result": "line-only",
            "code": "independent-endpoint-consensus-line-only",
        }
        projected = workflow_diagnostics._occurrence_candidate_diagnostics([candidate])[0]
        self.assertEqual(projected["selectedPublisherFamily"], "owsm-ctc")
        self.assertEqual(
            projected["supportingEndpointFamilies"], ["owsm-ctc", "nemo-nfa"]
        )
        self.assertNotIn(
            "startUtf16", projected["familyCandidates"]["owsm-ctc"]["words"][0]
        )

    def test_alignment_diagnostics_reject_invalid_selected_endpoint_reference(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        endpoint = {
            "resultClass": "committed-words",
            "lineCount": 1,
            "occurrenceCandidates": [endpoint_occurrence_candidate()],
        }
        with self.assertRaisesRegex(FutureSongError, "index is invalid"):
            workflow_diagnostics._alignment_diagnostics(
                {
                    "endpointEvidence": endpoint,
                    "endpointRouteAttempts": [
                        {
                            "routeId": "htdemucs-vocals",
                            "reused": False,
                            "endpointEvidence": endpoint,
                        }
                    ],
                    "selectedEndpointAttemptIndex": 1,
                }
            )
        with self.assertRaisesRegex(FutureSongError, "does not match aggregate"):
            workflow_diagnostics._alignment_diagnostics(
                {
                    "endpointEvidence": endpoint,
                    "endpointRouteAttempts": [
                        {
                            "routeId": "htdemucs-vocals",
                            "reused": False,
                            "endpointEvidence": {
                                **endpoint,
                                "resultClass": "line-unresolved",
                            },
                        }
                    ],
                    "selectedEndpointAttemptIndex": 0,
                }
            )

    def test_alignment_diagnostics_strictly_projects_ordered_route_attempts(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        all_attempted = [f"route-{index}" for index in range(101)]
        self.assertEqual(
            workflow_diagnostics._alignment_diagnostics(
                {"attemptedEvidence": all_attempted}
            )["attemptedEvidence"],
            all_attempted,
        )
        valid = [
            {
                "sequence": 0,
                "stage": "caption",
                "routeId": "youtube-caption",
                "status": "started",
            },
            {
                "sequence": 1,
                "stage": "caption",
                "routeId": "youtube-caption",
                "status": "transient-failure",
                "cacheReused": False,
                "code": "RuntimeError",
            },
            {
                "sequence": 2,
                "stage": "endpoint-verifier",
                "routeId": "faster-whisper-precision-unprompted:en",
                "status": "success",
                "cacheReused": True,
            },
        ]
        self.assertEqual(
            workflow_diagnostics._alignment_diagnostics({"routeAttempts": valid})["routeAttempts"],
            valid,
        )
        for invalid in (
            [{**valid[0], "sequence": 1}],
            [{**valid[0], "privateDetail": "must not escape"}],
            [{**valid[1], "code": "contains private words"}],
        ):
            with self.subTest(invalid=invalid):
                with self.assertRaises(FutureSongError):
                    workflow_diagnostics._alignment_diagnostics({"routeAttempts": invalid})

    def test_alignment_diagnostics_preserve_complete_occurrence_lists(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        candidates = [endpoint_occurrence_candidate(index) for index in range(1_001)]
        diagnostics = workflow_diagnostics._alignment_diagnostics(
            {
                "endpointEvidence": {
                    "resultClass": "committed-words",
                    "lineCount": len(candidates),
                    "occurrenceCandidates": candidates,
                }
            }
        )
        self.assertEqual(
            len(diagnostics["endpointEvidence"]["occurrenceCandidates"]), 1_001
        )
        self.assertEqual(
            diagnostics["endpointEvidence"]["occurrenceCandidates"][-1][
                "performedIndex"
            ],
            1_000,
        )

    def test_alignment_checkpoint_skips_damaged_regular_caches(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            cache = root / "transcript.private.json"
            cache.write_text('{"truncated":', encoding="utf-8")
            self.assertIsNone(
                workflow_reports.read_optional_alignment_cache(cache, root=root)
            )
            cache.write_text('["not-an-object"]', encoding="utf-8")
            self.assertIsNone(
                workflow_reports.read_optional_alignment_cache(cache, root=root)
            )
            cache.write_text('{"oversized":"xxxxxxxxxxxxxxxx"}', encoding="utf-8")
            with patch.object(workflow_reports, "MAX_JSON_BYTES", 16):
                self.assertIsNone(
                    workflow_reports.read_optional_alignment_cache(cache, root=root)
                )

    def test_alignment_checkpoint_binds_language_aligner_helper_and_cache(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            builder = Path(workflow.config['projectDir'])
            source = builder / "src"
            source.mkdir(parents=True, exist_ok=True)
            helper_names = (
                "common.py",
                "contract.py",
                "lyrics_align_pc.py",
                "lyrics_alignment.py",
                "lyrics_tokenization.py",
                "lyrics_unicode.py",
                "alignment_attempt.py",
                "mms_forced_alignment.py",
                "owsm_ctc_forced_alignment.py",
                "qwen3_asr_transcribe.py",
                "wav2vec2_forced_alignment.py",
                "roformer_separation.py",
                "song_enrichment_v2.py",
            )
            for helper_name in helper_names:
                (source / helper_name).write_text("def helper():\n    return 1\n", encoding="utf-8")
            alignment_job = (
                workflow.root / "lyrics-alignment" / "jobs" / "KbKqkR3Q6SA"
            )
            alignment_job.mkdir(parents=True)
            wav_cache = (
                alignment_job
                / "wav2vec2-public-endpoints-v4-tr.private.json"
            )
            wav_cache.write_text(
                json.dumps(
                    {
                        "engine": "wav2vec2-ctc-forced-alignment",
                        "language": "tr",
                        "requestVariant": "baseline",
                        "requestSha256": "a" * 64,
                        "helperSha256": "b" * 64,
                        "requestHelperSha256": "c" * 64,
                        "route": "wav2vec2-ctc-forced-on-mix",
                        "sampleRate": 16_000,
                        "segmentBoundaryGuardMs": 250,
                        "provenance": {
                            "audioViewId": "mix",
                            "model": {"modelSha256": "d" * 64},
                            "runtime": {"torch": "2.7.1+cu128"},
                            "decoder": {
                                "chunkSeconds": 30,
                                "strideSeconds": 5,
                                "retainedCoreSeconds": 20,
                                "emissionMap": "shared-global-overlap-trimmed",
                            },
                        },
                    }
                ),
                encoding="utf-8",
            )

            from stemwise_engine.alignment_phases import AttemptCacheBindings
            used_state = {'alignmentVersion': ALIGNMENT_VERSION}
            recorder = AttemptCacheBindings(used_state, alignment_job)
            recorder.bind('wav2vec2', wav_cache, ({}, True, {}))
            (alignment_job / 'native-asr-unused.private.json').write_bytes(b'{corrupt unused cache')
            (alignment_job / 'qwen-structure.private.json').write_bytes(b'{corrupt unused Qwen')
            checkpoint = workflow.report_store.alignment_checkpoint_key(
                {"videoId": "KbKqkR3Q6SA", "policy": {}},
                editorial_sha256="e" * 64,
                state=used_state,
            )

            self.assertEqual(set(checkpoint["helpers"]), set(helper_names))
            for helper_name in ("wav2vec2_forced_alignment.py", "owsm_ctc_forced_alignment.py"):
                with self.subTest(helper=helper_name):
                    helper = source / helper_name
                    original = helper.read_text(encoding="utf-8")
                    self.assertRegex(checkpoint["helpers"][helper_name], r"^[0-9a-f]{64}$")
                    helper.write_text("# Documentation only\n" + original, encoding="utf-8")
                    unchanged = workflow.report_store.alignment_checkpoint_key(
                        {"videoId": "KbKqkR3Q6SA", "policy": {}},
                        editorial_sha256="e" * 64, state=used_state)
                    self.assertEqual(checkpoint, unchanged)
                    helper.write_text("def helper():\n    return 2\n", encoding="utf-8")
                    changed = workflow.report_store.alignment_checkpoint_key(
                        {"videoId": "KbKqkR3Q6SA", "policy": {}},
                        editorial_sha256="e" * 64, state=used_state)
                    self.assertNotEqual(checkpoint["helpers"][helper_name], changed["helpers"][helper_name])
                    self.assertEqual(
                        {name: value for name, value in checkpoint["helpers"].items() if name != helper_name},
                        {name: value for name, value in changed["helpers"].items() if name != helper_name})
                    checkpoint = changed
            wav_record = checkpoint["models"]["forcedAlignment"][0]
            self.assertEqual(wav_record["cacheId"], wav_cache.name)
            self.assertEqual(wav_record["model"]["modelSha256"], "d" * 64)
            self.assertEqual(wav_record["runtime"]["torch"], "2.7.1+cu128")
            self.assertNotIn('nativeAsr', checkpoint['models'])
            self.assertNotIn('structureAsr', checkpoint['models'])
            original_cache = wav_cache.read_bytes()
            wav_cache.write_bytes(original_cache + b' ')
            with self.assertRaisesRegex(FutureSongError, 'attempt cache changed'):
                workflow.report_store.alignment_checkpoint_key({'videoId': 'KbKqkR3Q6SA', 'policy': {}},
                    editorial_sha256='e' * 64, state=used_state)
            wav_cache.write_bytes(original_cache)

            witness = {"family": "qwen3-asr", "route": "qwen3-asr-unprompted-mix",
                       "status": "success", "candidateCount": 1,
                       "selectedSequenceSupported": True, "languageHint": "ar"}
            qwen_cache = alignment_job / "qwen-structure-ar.private.json"
            packet = {"cacheVersion": 1, "videoId": "KbKqkR3Q6SA",
                      "provenance": {"language": "ar", "modelRevision": "a"*40,
                                     "modelSnapshotSha256": "b"*64, "helperSha256": "c"*64},
                      "workerOutput": {"language": "ar"}}
            qwen_cache.write_text(json.dumps(packet), encoding="utf-8")
            selected_state = {"alignmentVersion": ALIGNMENT_VERSION, "structureWitness": witness}
            bound = workflow.report_store.alignment_checkpoint_key(
                {"videoId": "KbKqkR3Q6SA", "policy": {}},
                editorial_sha256="e"*64, state=selected_state)
            self.assertEqual(bound["models"]["structureAsr"]["cacheId"], qwen_cache.name)
            self.assertEqual(bound["models"]["structureAsr"]["cacheSha256"],
                             hashlib.sha256(qwen_cache.read_bytes()).hexdigest())
            packet["workerOutput"]["language"] = "en"
            qwen_cache.write_text(json.dumps(packet), encoding="utf-8")
            with self.assertRaisesRegex(FutureSongError, "cache language differs"):
                workflow.report_store.alignment_checkpoint_key(
                    {"videoId": "KbKqkR3Q6SA", "policy": {}},
                    editorial_sha256="e"*64, state=selected_state)
            qwen_cache.unlink()
            with self.assertRaisesRegex(FutureSongError, "cache is missing"):
                workflow.report_store.alignment_checkpoint_key(
                    {"videoId": "KbKqkR3Q6SA", "policy": {}},
                    editorial_sha256="e"*64, state=selected_state)

            # A selected vocal witness must bind its own cache, even when the
            # older mix transcript is still present and valid.
            packet["workerOutput"]["language"] = "ar"
            qwen_cache.write_text(json.dumps(packet), encoding="utf-8")
            for view in ("htdemucs-vocals", "kimberley-jensen-roformer-vocals",
                         "sucial-v2-roformer-dry-vocals"):
                vocal_path = alignment_job / f"qwen-structure-{view}-ar.private.json"
                vocal_packet = {**packet, "provenance": {**packet["provenance"], "audioView": view}}
                vocal_path.write_text(json.dumps(vocal_packet), encoding="utf-8")
                vocal_state = {**selected_state, "structureWitness": {
                    **witness, "route": f"qwen3-asr-unprompted-{view}"}}
                bound = workflow.report_store.alignment_checkpoint_key(
                    {"videoId": "KbKqkR3Q6SA", "policy": {}},
                    editorial_sha256="e"*64, state=vocal_state)
                self.assertEqual(bound["models"]["structureAsr"]["cacheId"], vocal_path.name)
                self.assertEqual(bound["models"]["structureAsr"]["cacheSha256"],
                                 hashlib.sha256(vocal_path.read_bytes()).hexdigest())
                vocal_packet["provenance"]["audioView"] = "mix"
                vocal_path.write_text(json.dumps(vocal_packet), encoding="utf-8")
                with self.assertRaisesRegex(FutureSongError, "cache audio view differs"):
                    workflow.report_store.alignment_checkpoint_key(
                        {"videoId": "KbKqkR3Q6SA", "policy": {}},
                        editorial_sha256="e"*64, state=vocal_state)
                vocal_path.unlink()
                with self.assertRaisesRegex(FutureSongError, "cache is missing"):
                    workflow.report_store.alignment_checkpoint_key(
                        {"videoId": "KbKqkR3Q6SA", "policy": {}},
                        editorial_sha256="e"*64, state=vocal_state)
            auto_state = {**selected_state, "structureWitness": {
                **witness, "languageHint": None, "route": "qwen3-asr-unprompted-htdemucs-vocals"}}
            auto_cache = alignment_job / "qwen-structure-htdemucs-vocals-auto.private.json"
            auto_cache.write_text(json.dumps({**packet,
                "provenance": {**packet["provenance"], "language": None, "audioView": "htdemucs-vocals"},
                "workerOutput": {"language": None}}), encoding="utf-8")
            bound = workflow.report_store.alignment_checkpoint_key(
                {"videoId": "KbKqkR3Q6SA", "policy": {}},
                editorial_sha256="e"*64, state=auto_state)
            self.assertEqual(bound["models"]["structureAsr"]["cacheId"], auto_cache.name)
            auto_cache.unlink()
            with self.assertRaisesRegex(FutureSongError, "cache is missing"):
                workflow.report_store.alignment_checkpoint_key(
                    {"videoId": "KbKqkR3Q6SA", "policy": {}},
                    editorial_sha256="e"*64, state=auto_state)

    def test_immutable_run_rejects_oversized_json_before_creating_run_directory(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            report_root = root / "reports" / "KbKqkR3Q6SA"
            report_root.mkdir(parents=True)
            with patch.object(workflow.report_store.store, "max_json_bytes", 256):
                with self.assertRaisesRegex(FutureSongError, "evidence exceeds"):
                    record = {"runId": "a" * 32}
                    workflow.report_store.store.commit(
                        report_root,
                        record,
                        evidence={"payload": "x" * 1_000},
                    )
                self.assertEqual(record, {"runId": "a" * 32})
                with self.assertRaisesRegex(FutureSongError, "run record exceeds"):
                    record = {"runId": "b" * 32, "payload": "x" * 1_000}
                    workflow.report_store.store.commit(
                        report_root,
                        record,
                        evidence={"small": True},
                    )
                self.assertNotIn("reportArtifacts", record)
            self.assertFalse((report_root / "runs").exists())
            run_id = "c" * 32
            run_sha = workflow.report_store.store.commit(
                report_root,
                {
                    "runId": run_id,
                    "videoId": "KbKqkR3Q6SA",
                    "operation": "lyrics-alignment",
                },
                evidence={
                    "evidenceVersion": 1,
                    "runId": run_id,
                    "videoId": "KbKqkR3Q6SA",
                    "operation": "lyrics-alignment",
                    "diagnostics": {},
                },
            )
            self.assertRegex(run_sha, r"^[0-9a-f]{64}$")
            self.assertTrue((report_root / "runs" / run_id / "run.json").is_file())

    def test_oversized_alignment_evidence_commits_idempotent_small_failure(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            report_root = root / "reports" / "KbKqkR3Q6SA"
            report_root.mkdir(parents=True)
            sources = workflow._job_dir('KbKqkR3Q6SA') / "sources.json"
            sources.parent.mkdir(parents=True, exist_ok=True)
            sources.write_text('{"source":1}\n', encoding="utf-8")
            sources_sha = future_song_module.sha256_file(sources)
            job = {
                "videoId": "KbKqkR3Q6SA",
                "policy": {"schemaSha256": "c" * 64},
            }
            state = {
                "alignmentVersion": 5,
                "workerStartedAtUnix": 1,
                "updatedAtUnix": 2,
            }
            workflow.report_store.ensure_report_identity = Mock(return_value=report_root)
            workflow.report_store.write_report_authority_references = Mock(
                return_value={"authorityReferenceSha256": "d" * 64}
            )
            workflow.report_store.alignment_checkpoint_key = Mock(
                return_value={
                    "authority": {"editorialSha256": "e" * 64},
                    "oversized": "x" * 10_000,
                }
            )
            projection = Mock(
                return_value={
                    "resultClass": "committed-words",
                    "oversized": [index for index in range(5_000)],
                }
            )
            run_id = "a" * 32
            with patch.object(workflow.report_store.store, "max_json_bytes", 4_096), \
                    patch.object(workflow_diagnostics, '_alignment_diagnostics', projection):
                with self.assertRaises(ReportSizeLimitExceeded):
                    workflow.report_store.commit_alignment_run(
                        job,
                        run_id=run_id,
                        editorial_sha256="e" * 64,
                        state=state,
                        status="complete",
                        artifacts={"sourcesSha256": sources_sha, "largeArtifact": "f" * 64},
                    )
                first_sha = future_song_module.sha256_file(
                    report_root / "runs" / run_id / "run.json"
                )
                second_sha = workflow.report_store.commit_alignment_run(
                    job,
                    run_id=run_id,
                    editorial_sha256="e" * 64,
                    state=state,
                    status="failed",
                    failure_code="ReportSizeLimitExceeded",
                )
            self.assertEqual(first_sha, second_sha)
            record = json.loads(
                (report_root / "runs" / run_id / "run.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(record["status"], "failed")
            self.assertEqual(record["failureCode"], "ReportSizeLimitExceeded")
            self.assertEqual(record["artifacts"], {"sourcesSha256": sources_sha})
            self.assertNotIn("oversized", json.dumps(record))
            evidence_path = report_root / "runs" / run_id / record["reportArtifacts"][0][
                "fileName"
            ]
            evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
            self.assertEqual(
                evidence["diagnostics"]["reasons"],
                ["report-size-limit-exceeded"],
            )
            self.assertRegex(
                evidence["diagnostics"]["omittedEvidenceSha256"],
                r"^[0-9a-f]{64}$",
            )

    @staticmethod
    def _stale_workflow(root: Path, video_id: str):
        workflow = workflow_fixture(root)
        workflow.root = root / "future-songs"
        workflow.jobs = workflow.root / "jobs"
        workflow.jobs.mkdir(parents=True)
        job_dir = workflow.jobs / video_id
        job_dir.mkdir()
        job_path = job_dir / "job.json"
        attempt_id = "a" * 32
        job = {
            "videoId": video_id,
            "state": "ALIGNING_LYRICS",
            "activeAlignmentAttemptId": attempt_id,
            "deliveryAllowed": True,
        }
        job_path.write_text(json.dumps(job), encoding="utf-8")

        def load_job(value):
            if value != video_id:
                raise AssertionError("unexpected identity")
            return json.loads(job_path.read_text(encoding="utf-8"))

        def save_job(value):
            job_path.write_text(json.dumps(value), encoding="utf-8")

        workflow._load_job = load_job
        workflow._save_job = save_job
        workflow._write_task = lambda value: None
        return workflow, job, job_path

    def test_status_reconciles_only_a_proven_dead_alignment(self):
        video_id = "-AbCdEf1234"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow, job, job_path = self._stale_workflow(root, video_id)
            state_path = workflow._alignment_state_path(video_id)
            state_path.parent.mkdir(parents=True)
            state_path.write_text(
                json.dumps(
                    {
                        "videoId": video_id,
                        "attemptId": job["activeAlignmentAttemptId"],
                        "status": "transcribing",
                    }
                ),
                encoding="utf-8",
            )
            target = workflow._alignment_attempt_temp_dir(
                video_id, job["activeAlignmentAttemptId"]
            )
            sibling = workflow._alignment_attempt_temp_dir(video_id, "b" * 32)
            baseline = (
                workflow.analysis_temp
                / "lyrics-alignment"
                / f"lyrics-{video_id}-baseline"
            )
            for owned, attempt_id in (
                (target, job["activeAlignmentAttemptId"]),
                (sibling, "b" * 32),
            ):
                future_song_module.create_manifested_directory(
                    workflow.analysis_temp
                    / "lyrics-alignment"
                    / "future",
                    owned,
                    owner={
                        "kind": "lyrics-alignment-attempt",
                        "videoId": video_id,
                        "attemptId": attempt_id,
                    },
                    workspace_root=root,
                )
            baseline.mkdir(parents=True)
            (target / "caption-evidence.tmp").write_bytes(b"temporary")

            newest = max(job_path.stat().st_mtime, state_path.stat().st_mtime)
            with patch('stemwise_engine.future_song.time.time', return_value=newest + 60):
                reconciled = workflow._reconcile_interrupted_validation(job)

            saved_state = json.loads(state_path.read_text(encoding="utf-8"))
            self.assertEqual(reconciled["state"], "VALIDATION_FAILED")
            self.assertEqual(saved_state["status"], "interrupted")
            self.assertEqual(saved_state["reasons"], ["worker-not-active"])
            self.assertFalse(target.exists())
            self.assertTrue(sibling.is_dir())
            self.assertTrue(baseline.is_dir())

    def test_status_does_not_reconcile_while_job_lock_is_held(self):
        video_id = "-AbCdEf1234"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow, job, job_path = self._stale_workflow(root, video_id)
            state_path = workflow._alignment_state_path(video_id)
            state_path.parent.mkdir(parents=True)
            state_path.write_text(
                json.dumps(
                    {
                        "videoId": video_id,
                        "attemptId": job["activeAlignmentAttemptId"],
                        "status": "transcribing",
                    }
                ),
                encoding="utf-8",
            )
            newest = max(job_path.stat().st_mtime, state_path.stat().st_mtime)
            lock_path = workflow._job_dir(video_id) / "workflow.lock"
            with future_song_module.exclusive_process_lock(lock_path):
                with patch('stemwise_engine.future_song.time.time', return_value=newest + 60):
                    current = workflow._reconcile_interrupted_validation(job)
            self.assertEqual(current["state"], "ALIGNING_LYRICS")
            self.assertEqual(
                json.loads(state_path.read_text(encoding="utf-8"))["status"],
                "transcribing",
            )

    def test_status_reloads_job_after_worker_lock_contention(self):
        video_id = "-AbCdEf1234"
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.root = Path(name) / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            stale = {"videoId": video_id, "state": "ANALYZING"}
            completed = {"videoId": video_id, "state": "WAITING_FOR_EDITORIAL"}
            workflow._load_job = Mock(return_value=completed)

            with patch(
                'stemwise_engine.future_song.exclusive_process_lock',
                side_effect=future_song_module.ProcessLockBusyError("busy"),
            ):
                current = workflow._reconcile_interrupted_validation(stale)

            self.assertIs(current, completed)
            workflow._load_job.assert_called_once_with(video_id)

    def test_status_does_not_reconcile_while_alignment_worker_lease_is_held(self):
        video_id = "-AbCdEf1234"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow, job, job_path = self._stale_workflow(root, video_id)
            state_path = workflow._alignment_state_path(video_id)
            state_path.parent.mkdir(parents=True)
            state_path.write_text(
                json.dumps(
                    {
                        "videoId": video_id,
                        "attemptId": job["activeAlignmentAttemptId"],
                        "status": "queued",
                    }
                ),
                encoding="utf-8",
            )
            newest = max(job_path.stat().st_mtime, state_path.stat().st_mtime)
            worker_lock = workflow._alignment_worker_lock_path(video_id)
            with future_song_module.exclusive_process_lock(worker_lock):
                with patch('stemwise_engine.future_song.time.time', return_value=newest + 60):
                    current = workflow._reconcile_interrupted_validation(job)
            self.assertEqual(current["state"], "ALIGNING_LYRICS")
            self.assertEqual(
                json.loads(state_path.read_text(encoding="utf-8"))["status"],
                "queued",
            )

    def test_leading_hyphen_video_id_is_option_safe_for_every_cli_route(self):
        video_id = "-AbCdEf1234"
        safe = option_safe_video_id_argument(video_id)
        self.assertEqual(safe, "--video-id=-AbCdEf1234")

        route_arguments = {
            "status": [],
            "source": ["--url-base64=YQ=="],
            "artwork": ["--url-base64=YQ=="],
            "validate": [],
            "finalize": [],
            "retry": [],
        }
        for command, trailing in route_arguments.items():
            with self.subTest(command=command):
                parsed = future_song_parser().parse_args(
                    ["--config", "config.json", command, safe, *trailing]
                )
                self.assertEqual(parsed.video_id, video_id)

        runner = (PROJECT / "future-song.ps1").read_text(encoding="utf-8")
        self.assertEqual(runner.count('"--video-id=$VideoId"'), 4)
        self.assertNotIn("@('--video-id', $VideoId", runner)

    def test_artwork_gate_binds_selected_source_and_exact_cover_bytes(self):
        video_id = "AAAAAAAAAAA"
        source_video_id = "BBBBBBBBBBB"
        cover = b"reviewed-cover"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        workflow._runtime_config = Mock(return_value={})
        pipeline = SimpleNamespace(child_path=lambda _video_id: Path("child.zip"), load_checkpoint=Mock())
        job = {
            "videoId": video_id,
            "artworkReview": {
                "result": "accepted",
                "sourceVideoId": source_video_id,
                "sourceUrl": f"https://www.youtube.com/watch?v={source_video_id}",
                "artworkSha256": hashlib.sha256(cover).hexdigest(),
                "reviewedAt": "2026-09-03T00:00:00Z",
            },
        }
        document = {
            "artwork": {
                "source": {
                    "url": f"https://www.youtube.com/watch?v={source_video_id}"
                }
            }
        }
        with patch('stemwise_engine.future_song.read_child', return_value=(document, cover)):
            workflow._require_reviewed_artwork(job, pipeline)
            job["artworkReview"]["artworkSha256"] = "0" * 64
            with self.assertRaisesRegex(FutureSongError, "missing or changed"):
                workflow._require_reviewed_artwork(job, pipeline)
        with self.assertRaisesRegex(FutureSongError, "inspected artwork is not selected"):
            workflow._require_reviewed_artwork({"videoId": video_id}, pipeline)
        with tempfile.TemporaryDirectory() as name:
            run_dir = Path(name)
            (run_dir / "run.json").write_text("{}", encoding="utf-8")
            self.assertTrue(
                workflow.report_store.store.artifacts_match(
                    run_dir, {"operation": "artwork-repair"}
                )
            )

    def test_repeated_delivered_artwork_selection_is_a_verified_no_op(self):
        video_id = "AAAAAAAAAAA"
        source_video_id = "BBBBBBBBBBB"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        job = {
            "videoId": video_id,
            "state": "DELIVERED",
            "artworkReview": {
                "result": "accepted",
                "sourceVideoId": source_video_id,
            },
        }
        pipeline = object()
        workflow._load_job = Mock(return_value=job)
        workflow._validate_authority_outcome = Mock(
            return_value=(
                "resolved",
                {"lyricEvidenceVideoIds": [source_video_id], "recordingReview": {
                    "sourceAssociations": [{"videoId": source_video_id, "relation": "exact-recording"}]}},
                {"lyricEvidenceSources": [{"videoId": source_video_id}]},
            )
        )
        workflow._pipeline = Mock(return_value=pipeline)
        workflow._validated_snapshot_is_current = Mock(return_value=True)
        workflow._require_reviewed_artwork = Mock()
        workflow.summary = Mock(return_value={"state": "DELIVERED"})
        with patch(
            'stemwise_engine.youtube.fetch_video_info',
            side_effect=AssertionError("a repeated selection must not refetch artwork"),
        ):
            self.assertEqual(
                workflow._select_artwork_action(
                    video_id, f"https://www.youtube.com/watch?v={source_video_id}"
                ),
                {"state": "DELIVERED"},
            )
        workflow._require_reviewed_artwork.assert_called_once_with(job, pipeline)

    def test_generated_task_uses_only_quoted_canonical_launcher_commands(self):
        video_id = "-AbCdEf1234"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.runtime = workflow.root / "runtime"
            workflow.policy_dir = workflow.root / "policy"
            workflow.config_path = root / "PC side" / "builder" / "config.json"
            workflow._windows_path = lambda path: str(path)
            workflow._job_dir(video_id).mkdir(parents=True)
            job = {
                "videoId": video_id,
                "state": "WAITING_FOR_EDITORIAL",
                "url": f"https://www.youtube.com/watch?v={video_id}",
                "title": "Fixture",
            }
            task_path = workflow._job_dir(video_id) / "AGENT_TASK.txt"
            workflow._write_task(job)
            task_bytes = task_path.read_bytes()
            task_mtime = task_path.stat().st_mtime_ns
            workflow._write_task(job)
            self.assertEqual(task_path.read_bytes(), task_bytes)
            self.assertEqual(task_path.stat().st_mtime_ns, task_mtime)
            task_path.write_bytes(b"stale task")
            workflow._write_task(job)
            self.assertEqual(task_path.read_bytes(), task_bytes)
            task = task_bytes.decode("utf-8")
            self.assertNotIn("future-song.ps1", task)
            self.assertIn("Builder guide:", task)
            self.assertIn(f"-VideoId '{video_id}'", task)
            self.assertIn("$StemWiseAgent", task)

    def test_generated_task_preserves_quoted_unicode_paths_and_atomic_refresh(self):
        import stemwise_engine.common as common
        shell = shutil.which('powershell.exe') or shutil.which('pwsh.exe') or shutil.which('pwsh')
        self.assertIsNotNone(shell)
        video_id = '-AbCdEf1234'
        for suffix in ('with spaces', "owner's checkout", '資料 café'):
            with self.subTest(path=suffix), tempfile.TemporaryDirectory() as name:
                root = Path(name) / suffix
                workflow = workflow_fixture(root)
                workflow.root = root / 'future-songs'
                workflow.jobs = workflow.root / 'jobs'
                workflow.runtime = workflow.root / 'runtime'
                workflow.policy_dir = workflow.root / 'policy'
                workflow.config_path = root / 'PC side/builder/config.json'
                job = {'videoId': video_id, 'state': 'WAITING_FOR_EDITORIAL',
                    'url': f'https://youtube.com/watch?v={video_id}', 'title': 'Fixture'}
                before = copy.deepcopy(job)
                directory = workflow._job_dir(video_id)
                directory.mkdir(parents=True)
                task = directory / 'AGENT_TASK.txt'
                task.write_bytes(b'previous task')
                sibling = directory.parent / 'other-task.txt'
                sibling.write_bytes(b'untouched')
                with patch.object(common, 'durable_replace', side_effect=OSError('interrupted before commit')):
                    with self.assertRaises(OSError): workflow._write_task(job)
                self.assertEqual(task.read_bytes(), b'previous task')
                self.assertEqual(list(directory.glob('.AGENT_TASK.txt.*')), [])
                workflow._write_task(job)
                payload, mtime = task.read_bytes(), task.stat().st_mtime_ns
                workflow._write_task(job)
                self.assertEqual((task.read_bytes(), task.stat().st_mtime_ns), (payload, mtime))
                self.assertEqual(job, before)
                self.assertEqual(sibling.read_bytes(), b'untouched')
                text = payload.decode('utf-8')
                self.assertIn('- English translation: `determine after original identification`', text)
                self.assertIn('- IPA: `determine after original identification`', text)
                self.assertIn('the package may finish partial', text)
                commands = text.split('```powershell\n', 1)[1].split('```', 1)[0]
                inline = next(line.split('`')[1] for line in text.splitlines() if 'stage it with `' in line)
                launcher = workflow._windows_path(workflow.config_path.parent.parent / 'STEMWISE_AGENT.ps1')
                def literal(value): return "'" + value.replace("'", "''") + "'"
                script = '''$ErrorActionPreference='Stop'
foreach ($Snippet in @(''' + ','.join(literal(value) for value in (commands, inline)) + ''')) {
    $Tokens=$null; $Errors=$null
    $Ast=[Management.Automation.Language.Parser]::ParseInput($Snippet,[ref]$Tokens,[ref]$Errors)
    if ($Errors.Count) { throw 'Generated command syntax is invalid' }
    $Paths=@($Ast.FindAll({param($Node) $Node -is [Management.Automation.Language.StringConstantExpressionAst] -and
        $Node.Value.EndsWith('STEMWISE_AGENT.ps1')}, $true))
    if ($Paths.Count -ne 1 -or $Paths[0].Value -cne ''' + literal(launcher) + ''') { throw 'Launcher path changed' }
}'''
                result = subprocess.run([shell, '-NoProfile', '-NonInteractive', '-EncodedCommand',
                    base64.b64encode(script.encode('utf-16le')).decode('ascii')], capture_output=True, timeout=20)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_aggregate_status_keeps_jobs_that_cannot_be_read(self):
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / "jobs"
            broken = workflow.jobs / "AAAAAAAAAAA" / "job.json"
            broken.parent.mkdir(parents=True)
            broken.write_text("not-json", encoding="utf-8")
            result = workflow.status(None)
            self.assertEqual(result["jobCount"], 1)
            self.assertEqual(result["stateCounts"], {"STATUS_ERROR": 1})
            self.assertEqual(result["jobs"][0]["videoId"], "AAAAAAAAAAA")
            self.assertEqual(result["jobs"][0]["state"], "STATUS_ERROR")


    def test_aggregate_status_is_compact_but_per_id_keeps_verified_details(self):
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / "jobs"
            job = {"videoId": "AAAAAAAAAAA", "state": "DELIVERED",
                   "delivery": {"original": {"sha256": "a" * 64}}}
            path = workflow.jobs / job["videoId"] / "job.json"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(job))
            details = {
                **job,
                "updatedAt": "2026-09-05T00:00:00Z",
                "agentTask": "task.md",
                "validation": {"lyricsTiming": {"diagnostics": "x" * 100_000}},
            }
            workflow._reconcile_interrupted_validation = Mock(side_effect=lambda value: value)
            workflow._load_job = Mock(return_value=job)
            workflow.summary = Mock(return_value=details)
            workflow._validated_snapshot_is_current = Mock(return_value=True)
            aggregate = workflow.status(None, verify_delivery=True)
            self.assertNotIn("validation", aggregate["jobs"][0])
            self.assertEqual(aggregate["jobs"][0]["updatedAt"], details["updatedAt"])
            self.assertLess(len(json.dumps(aggregate)), 2_000)
            workflow.report_store.latest_workflow_operation = Mock(return_value=None)
            self.assertEqual(workflow.status(job["videoId"], verify_delivery=True), details)
            self.assertEqual(workflow._validated_snapshot_is_current.call_count, 2)



    def test_aggregate_status_checks_deliveries_concurrently_and_keeps_order(self):
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / "jobs"
            states = {
                "AAAAAAAAAAA": "DELIVERED",
                "BBBBBBBBBBB": "WAITING_FOR_EDITORIAL",
                "CCCCCCCCCCC": "DELIVERED",
                "DDDDDDDDDDD": "DELIVERED",
            }
            for video_id, state in states.items():
                path = workflow.jobs / video_id / "job.json"
                path.parent.mkdir(parents=True)
                path.write_text(
                    json.dumps({"videoId": video_id, "state": state}),
                    encoding="utf-8",
                )
            malformed = workflow.jobs / "EEEEEEEEEEE" / "job.json"
            malformed.parent.mkdir(parents=True)
            malformed.write_text('{"state":"DELIVERED"}', encoding="utf-8")
            workflow._reconcile_interrupted_validation = Mock(
                side_effect=lambda job: job
            )
            delivery_pair = threading.Barrier(2, timeout=1)

            def summarize(job, *, verify_delivery):
                self.assertTrue(verify_delivery)
                if job["videoId"] == "DDDDDDDDDDD":
                    raise RuntimeError("broken delivery fixture")
                if job["state"] == "DELIVERED":
                    delivery_pair.wait()
                return {"videoId": job["videoId"], "state": job["state"]}

            workflow._status_summary = Mock(side_effect=summarize)
            result = workflow.status(None, verify_delivery=True)

            self.assertEqual(
                [job["videoId"] for job in result["jobs"]],
                [*states, "EEEEEEEEEEE"],
            )
            self.assertEqual(
                result["stateCounts"],
                {
                    "DELIVERED": 2,
                    "STATUS_ERROR": 2,
                    "WAITING_FOR_EDITORIAL": 1,
                },
            )
            self.assertEqual(
                [job["state"] for job in result["jobs"][-2:]],
                ["STATUS_ERROR", "STATUS_ERROR"],
            )

    def test_current_policy_mismatch_rejects_job_and_preserves_bytes(self):
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / 'jobs'
            path = workflow._job_path('AAAAAAAAAAA')
            path.parent.mkdir(parents=True)
            path.write_bytes(future_song_module.json_bytes({'videoId': 'AAAAAAAAAAA',
                'state': 'DELIVERED', 'policyVersion': 'different-policy'}))
            before = path.read_bytes()
            with self.assertRaisesRegex(FutureSongError, 'policy version'):
                workflow._load_job('AAAAAAAAAAA')
            self.assertEqual(path.read_bytes(), before)

    def test_current_policy_checks_contracts_and_preserves_known_work_without_archives(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workflow = workflow_fixture(root)
            workflow.policy_dir = root / 'policy'
            workflow.policy_dir.mkdir()
            workflow.config = {'generatorVersion': '2.1.0'}
            source = PROJECT / 'future-songs/policy'
            (workflow.policy_dir/'lyrics-policy.json').write_bytes((source/'lyrics-policy.json').read_bytes())
            policy = workflow._effective_policy({})
            shutil.copytree(WORKSPACE/'StemWise/contracts', root/'StemWise/contracts', dirs_exist_ok=True)
            workflow.policy = workflow._validate_policy()
            self.assertFalse((workflow.policy_dir/'history').exists())
            workflow.jobs = root/'jobs'
            job = {'videoId':'AAAAAAAAAAA','state':'WAITING_FOR_EDITORIAL',
                'policyVersion':editorial_review.POLICY_VERSION,'policy':workflow._policy_binding()}
            path = workflow._job_path(job['videoId'])
            path.parent.mkdir(parents=True)
            path.write_bytes(future_song_module.json_bytes(job))
            before = path.read_bytes()
            self.assertEqual(workflow._load_job(job['videoId']),job)
            (root/'SEND_TO_AGENTS.md').write_text('A changed explanation',encoding='utf-8')
            self.assertEqual(workflow._validate_policy(),policy)
            self.assertEqual(workflow._load_job(job['videoId']),job)
            metadata = {**policy, 'description': 'An irrelevant explanation'}
            metadata = copy.deepcopy(metadata)
            metadata['privateReports']['description'] = 'Report prose is not an executable setting'
            metadata['privateReports']['workflowOperationLedger']['outcomeCodes'].reverse()
            (workflow.policy_dir/'lyrics-policy.json').write_bytes(future_song_module.json_bytes(metadata))
            self.assertEqual(workflow._validate_policy(), policy)
            self.assertEqual(workflow._load_job(job['videoId']), job)
            self.assertEqual(path.read_bytes(), before)
            changed = copy.deepcopy(policy)
            changed['delivery']['reviewEvidenceRetained'] = False
            (workflow.policy_dir/'lyrics-policy.json').write_bytes(future_song_module.json_bytes(changed))
            self.assertEqual(workflow._load_job(job['videoId']), job)
            self.assertEqual(path.read_bytes(),before)
            self.assertEqual(workflow._validate_policy(), policy)






    def test_alignment_child_receives_safe_id_attempt_without_an_overall_job_watchdog(self):
        video_id = "-AbCdEf1234"
        attempt_id = "c" * 32
        with tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            project = Path(workflow.config["projectDir"])
            workflow.config["analysisTimeoutMinutes"] = 17
            workflow.config_path = project / "config.json"
            workflow.runtime = root / "runtime"
            workflow.root = project / "future-songs"
            workflow.jobs = workflow.root/'jobs'
            workflow.analysis_temp = root / "temporary-analysis"
            workflow.owner = workflow.root / "owner-input"
            for file_path in (
                project / ".venv-lyrics-align" / "bin" / "python",
                project / "src" / "lyrics_align_pc.py",
                workflow.runtime / "children" / f"{video_id}.stemwise.zip",
            ):
                file_path.parent.mkdir(parents=True, exist_ok=True)
                file_path.write_bytes(b"fixture")
            site_packages = (
                project
                / ".venv-lyrics-align"
                / "lib"
                / "python3.10"
                / "site-packages"
            )
            for package in ("cublas", "cudnn", "cuda_nvrtc"):
                (site_packages / "nvidia" / package / "lib").mkdir(parents=True)
            job_path = workflow._job_path(video_id)
            job_path.parent.mkdir(parents=True, exist_ok=True)
            future_song_module.atomic_write_json(job_path, {'videoId': video_id,
                'policyVersion': editorial_review.POLICY_VERSION, 'state': 'ALIGNING_LYRICS'})

            completed = SimpleNamespace(returncode=2, stdout="", stderr="failed")
            with patch('stemwise_engine.future_song.run_process_tree', return_value=completed) as run:
                with self.assertRaises(FutureSongError):
                    workflow._align_future_lyrics(
                        video_id,
                        attempt_id,
                        "a" * 64,
                        "b" * 64,
                        "c" * 64,
                        root / "analysis.wav",
                    )
            command = run.call_args.args[0]
            self.assertIn("--video-id=-AbCdEf1234", command)
            self.assertIn(f"--attempt-id={attempt_id}", command)
            self.assertIn(f"--analysis-audio={root / 'analysis.wav'}", command)
            self.assertIn(f"--future-root={workflow.root}", command)
            self.assertIn(f"--analysis-root={workflow.analysis_temp}", command)
            self.assertIn(f"--canonical-plain-sha256={'c' * 64}", command)
            self.assertIsNone(run.call_args.kwargs['timeout'])

            state_path = workflow._alignment_state_path(video_id)
            state_path.parent.mkdir(parents=True, exist_ok=True)
            for bound_attempt, stderr, busy in (
                (attempt_id, "private warning\nstemwise-lyrics-align: ProcessLockBusyError\n", True),
                ("d" * 32, "stemwise-lyrics-align: ProcessLockBusyError\n", False),
                (attempt_id, "private ProcessLockBusyError details", False),
            ):
                with self.subTest(bound_attempt=bound_attempt, busy=busy):
                    initial = {"attemptId": bound_attempt, "status": "starting"}
                    state_path.write_text(json.dumps(initial), encoding="utf-8")
                    completed.stderr = stderr
                    with patch('stemwise_engine.future_song.run_process_tree', return_value=completed):
                        with self.assertRaises(FutureSongError) as raised:
                            workflow._align_future_lyrics(
                                video_id, attempt_id, "a" * 64, "b" * 64,
                                "c" * 64, root / "analysis.wav",
                            )
                    state = json.loads(state_path.read_text(encoding="utf-8"))
                    if busy:
                        self.assertIsInstance(raised.exception, future_song_module.FutureSongOutcomeError)
                        self.assertEqual(raised.exception.state, "RETRYABLE_TECHNICAL")
                        self.assertEqual(state["status"], "retryable-technical")
                        self.assertEqual(state["reasons"], ["heavy-worker-busy"])
                        self.assertNotIn("workerPid", state)
                        self.assertNotIn("private", json.dumps(state))
                    else:
                        self.assertNotIsInstance(raised.exception, future_song_module.FutureSongOutcomeError)
                        self.assertEqual(state, initial)

    def test_alignment_child_failure_retains_one_private_cause_without_mutating_review(self):
        video_id, attempt_id = 'abcdefghijk', 'a' * 32
        for failure in ('exit', 'timeout', 'child-cause'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                root = Path(name)
                workflow = workflow_fixture(root)
                project = Path(workflow.config['projectDir'])
                workflow.config_path = project/'config.json'
                workflow.runtime = root/'runtime'
                workflow.root = project/'future-songs'
                workflow.jobs = workflow.root/'jobs'
                workflow.analysis_temp = root/'temporary-analysis'
                for path in (project/'.venv-lyrics-align/bin/python', project/'src/lyrics_align_pc.py',
                             workflow.runtime/'children'/f'{video_id}.stemwise.zip'):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b'fixture')
                state_path = workflow._alignment_state_path(video_id)
                state_path.parent.mkdir(parents=True, exist_ok=True)
                state_path.write_bytes(json.dumps({'attemptId': attempt_id, 'status': 'starting'}).encode())
                before = state_path.read_bytes()
                review = workflow._job_dir(video_id)/'agent-review.json'
                review.parent.mkdir(parents=True, exist_ok=True)
                review.write_bytes(b'{"approved":false}')
                future_song_module.atomic_write_json(workflow._job_path(video_id), {'videoId': video_id,
                    'policyVersion': editorial_review.POLICY_VERSION, 'state': 'ALIGNING_LYRICS'})
                private_cause = 'private-warning: ' + 'x' * 13000 + '\nprivate-root-cause'
                exception = subprocess.TimeoutExpired(['alignment'], 1, stderr=private_cause)
                completed = SimpleNamespace(returncode=2, stdout='', stderr=private_cause)
                diagnostic = state_path.with_name(f'attempt-{attempt_id}.failure.private.txt')
                child_cause = b'private-root-cause from the exact child traceback'
                def run_child(*args, **kwargs):
                    if failure == 'timeout':
                        raise exception
                    if failure == 'child-cause':
                        diagnostic.write_bytes(child_cause)
                    return completed
                with patch.object(workflow, '_alignment_worker_environment', return_value={}), \
                        patch('stemwise_engine.future_song.run_process_tree', side_effect=run_child):
                    with self.assertRaises(Exception) as raised:
                        workflow._align_future_lyrics(video_id, attempt_id, 'b'*64, 'c'*64, 'd'*64, root/'analysis.wav')
                diagnostic = state_path.with_name(f'attempt-{attempt_id}.failure.private.txt')
                self.assertTrue(diagnostic.is_file())
                self.assertLessEqual(diagnostic.stat().st_size, 12000)
                self.assertIn(b'private-root-cause', diagnostic.read_bytes())
                diagnostic.read_bytes().decode('utf-8')
                if failure == 'child-cause':
                    self.assertEqual(diagnostic.read_bytes(), child_cause)
                self.assertNotIn('private-root-cause', str(raised.exception))
                self.assertEqual(state_path.read_bytes(), before)
                self.assertEqual(review.read_bytes(), b'{"approved":false}')

    def test_zero_exit_word_incomplete_alignment_preserves_private_progress_without_content_recovery(self):
        for status in ('committed-lines', 'committed-mixed', 'line-unresolved', 'committed-words'):
            with self.subTest(status=status), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                root, video_id, attempt_id = Path(name), 'abcdefghijk', 'a'*32
                workflow = workflow_fixture(root)
                project = Path(workflow.config['projectDir'])
                for path in (project/'.venv-lyrics-align/bin/python', project/'src/lyrics_align_pc.py',
                             workflow.runtime/'children'/f'{video_id}.stemwise.zip'):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b'inert external boundary')
                state_path = workflow._alignment_state_path(video_id)
                state_path.parent.mkdir(parents=True, exist_ok=True)
                state = {'attemptId': attempt_id, 'canonicalPlainSha256': 'c'*64,
                    'alignmentVersion': ALIGNMENT_VERSION, 'durationMs': 1000, 'status': status,
                    'reasons': ['incomplete-display-word-map'],
                    'endpointEvidence': {'resultClass': status, 'lineOnlyCount': 1}}
                future_song_module.atomic_write_json(state_path, state)
                owner = workflow._owner_sidecar_path(video_id)
                owner.parent.mkdir(parents=True, exist_ok=True)
                future_song_module.atomic_write_json(owner, {'status': 'partial', 'reason': 'Word timing pending.',
                    'tracks': [{'id': 'original', 'kind': 'original', 'language': 'en', 'format': 'synced',
                        'source': {'name': 'Retained reviewed source'},
                        'lines': [{'text': 'one two', 'startMs': 100, 'endMs': 900}]}]})
                candidate = state_path.with_name('candidate.private.lyrics.json')
                candidate.write_bytes(owner.read_bytes())
                before = {path: path.read_bytes() for path in (owner, state_path, candidate)}
                with patch.object(workflow, '_alignment_worker_environment', return_value={}), \
                        patch.object(workflow, '_run_primary_worker', return_value=SimpleNamespace(
                            returncode=0, stdout='', stderr='')) as run:
                    with self.assertRaises(future_song_module.FutureSongOutcomeError) as caught:
                        workflow._align_future_lyrics(video_id, attempt_id, 'b'*64, 'd'*64, 'c'*64, root/'audio.wav')
                self.assertEqual((caught.exception.state, caught.exception.code), ('LINE_UNRESOLVED', 'line-unresolved'))
                self.assertEqual(run.call_count, 1)
                self.assertEqual({path: path.read_bytes() for path in before}, before)
                self.assertFalse((workflow._job_dir(video_id)/'endpoint-reviews').exists())
                self.assertFalse((workflow._job_dir(video_id)/'sources.json').exists())

    def test_release_companions_require_exact_original_timing(self):
        lyrics = {
            "status": "complete",
            "tracks": [
                {
                    "kind": "original",
                    "format": "synced",
                    "lines": [{"startMs": 100, "text": "A"}],
                },
                {
                    "kind": "translation",
                    "format": "synced",
                    "lines": [{"startMs": 100, "text": "B"}],
                },
            ],
        }
        validate_synced_companion_timing(lyrics)
        lyrics["tracks"][1]["lines"][0]["startMs"] = 101
        with self.assertRaises(FutureSongError):
            validate_synced_companion_timing(lyrics)

    def test_extract_video_id_ignores_playlist_context(self):
        value = "https://www.youtube.com/watch?v=KbKqkR3Q6SA&list=PLTC7rx61lFQeF2QHqzoR7Skw_HnJ-6hdu"
        self.assertEqual(extract_video_id(value), "KbKqkR3Q6SA")
        self.assertEqual(extract_video_id("-AbCdEf1234"), "-AbCdEf1234")
        self.assertEqual(
            extract_video_id("https://music.youtube.com/watch?v=-AbCdEf1234"),
            "-AbCdEf1234",
        )
        self.assertEqual(
            extract_video_id("https://music.youtube.com/watch?v=KbKqkR3Q6SA"),
            "KbKqkR3Q6SA",
        )
        self.assertEqual(extract_video_id("https://youtu.be/KbKqkR3Q6SA"), "KbKqkR3Q6SA")

    def test_extract_video_id_rejects_playlist_only(self):
        workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
        workflow._require_selected_audio = Mock(side_effect=AssertionError('identity must precede source selection'))
        workflow._job_dir = Mock(side_effect=AssertionError('identity must precede job creation'))
        for value in ('https://www.youtube.com/playlist?list=fixture',
                'https://youtube.com/playlist?v=AAAAAAAAAAA',
                'https://youtube.com/shorts/AAAAAAAAAAA?v=BBBBBBBBBBB',
                'https://youtu.be/AAAAAAAAAAA?v=BBBBBBBBBBB',
                'https://youtube.com/watch?v=AAAAAAAAAAA&v=AAAAAAAAAAA'):
            with self.subTest(value=value), self.assertRaises(FutureSongError):
                workflow.new(value)
        workflow._require_selected_audio.assert_not_called()
        workflow._job_dir.assert_not_called()

    def test_generated_task_states_required_and_forbidden_companions_from_metadata(self):
        video_id = 'AAAAAAAAAAA'
        cases = (({'originalLanguage': 'ar', 'originalScript': 'Arab'}, 'required', 'forbidden'),
                 ({'originalLanguage': 'en'}, 'forbidden', 'forbidden'),
                 ({'originalLanguage': 'tr'}, 'required', 'required'),
                 ({'originalLanguage': 'mul', 'componentLanguages': ['ar', 'en']}, 'required', 'forbidden'),
                 ({'originalLanguage': 'mul', 'componentLanguages': ['ar', 'tr']}, 'required', 'required'),
                 (None, 'determine after original identification', 'determine after original identification'))
        for metadata, translation, ipa in cases:
            with self.subTest(metadata=metadata), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                workflow = workflow_fixture(root)
                workflow.root = root/'future-songs'
                workflow.jobs = workflow.root/'jobs'
                workflow.runtime = workflow.root / "runtime"
                workflow.config_path = root/'PC side/builder/config.json'
                workflow._windows_path = lambda path: str(path)
                draft = workflow._job_dir(video_id)/'drafts'
                draft.mkdir(parents=True)
                if metadata is not None:
                    (draft/'lyrics.meta.json').write_text(json.dumps(metadata))
                job = {'videoId': video_id, 'state': 'WAITING_FOR_EDITORIAL',
                       'url': f'https://www.youtube.com/watch?v={video_id}'}
                before = {p: p.read_bytes() for p in draft.iterdir()}
                workflow._write_task(job)
                task = (draft.parent/'AGENT_TASK.txt').read_text()
                requirements = '\n'.join(line for line in task.splitlines()
                    if line.startswith(('- English translation', '- IPA')))
                self.assertIn(f'- English translation: `{translation}`', requirements)
                self.assertIn(f'- IPA: `{ipa}`', requirements)
                self.assertTrue('only companion tracks marked required above' in task,
                                'Task must scope authoring to required companions')
                self.assertFalse('Author/review required English and IPA' in task,
                                 'Task must not require both companion tracks unconditionally')
                self.assertIn('original.txt', task)
                self.assertIn('original.lrc', task)
                self.assertIn('lyrics.meta.json before RecordingEvidence', task)
                self.assertFalse(str(root/'AGENTS.md') in task, 'Task must use the sole Builder guide')
                self.assertFalse(str(root/'STEMWISE_DOCUMENTATION.md') in task, 'Task must not require engineering documentation')
                self.assertTrue(str(workflow.runtime / "children" / f"{video_id}.stemwise.zip") in task, "Task must locate its child ZIP")
                self.assertEqual(before, {p: p.read_bytes() for p in draft.iterdir()})

    def test_companion_requirements(self):
        self.assertEqual(companion_requirements({"originalLanguage": "en"}), (False, False))
        self.assertEqual(companion_requirements({"originalLanguage": "ar"}), (True, False))
        self.assertEqual(companion_requirements({"originalLanguage": "tr"}), (True, True))
        self.assertEqual(
            companion_requirements({"originalLanguage": "mul", "componentLanguages": ["ar", "en"]}),
            (True, False),
        )
        self.assertEqual(
            companion_requirements({"originalLanguage": "mul", "componentLanguages": ["ar", "tr"]}),
            (True, True),
        )

    def test_multilingual_component_rules_preserve_draft_qa(self):
        for components, text in ((['fr','en'], 'Pourquoi ?\nHello\n'),
                                 (['ur','en'], 'می\u200cروم\nHello\n')):
            with self.subTest(components=components), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                root = Path(name)
                workflow = workflow_fixture(root)
                workflow.jobs = root/'jobs'
                workflow._runtime_config = lambda: {'outputDir': str(root/'output')}
                video_id = 'abcdefghijk'
                draft = workflow._job_dir(video_id)/'drafts'
                draft.mkdir(parents=True)
                manifest = {'videoId': video_id, 'originalLanguage': 'mul', 'originalScript': 'Zyyy',
                    'componentLanguages': components, 'sources': {key: {'name': 'Owner reviewed'}
                        for key in ('original','translationEn','ipa')}}
                (draft/'lyrics.meta.json').write_text(json.dumps(manifest), encoding='utf-8')
                (draft/'original.txt').write_text(text, encoding='utf-8')
                (draft/'translation-en.txt').write_text('Why?\nHello\n', encoding='utf-8')
                (draft/'transliteration-ipa.txt').write_text('a\nb\n', encoding='utf-8')
                review = {'formatVersion': 2, 'policyVersion': editorial_review.POLICY_VERSION,
                    'videoId': video_id, 'authorityOutcome': 'resolved', 'reviewer': 'Owner',
                    'completedAt': '2026-10-20T00:00:00Z', 'sourceDisposition': 'accepted-owner-supplied',
                    'recordingOrSourceAssociationReviewed': True, 'youtubeSurfaceVariantsReviewed': True,
                    'publicSourceResearchComplete': True, 'normalYouTubeVideoId': video_id,
                    'youtubeMusicVideoId': None, 'lyricEvidenceVideoIds': [video_id],
                    'originalReviewed': True,
                    'unresolvedMaterialIssues': []}
                (draft.parent/'agent-review.json').write_text(json.dumps(review), encoding='utf-8')
                sources = {'videoId': video_id, 'lyricEvidenceSources': [{'videoId': video_id}]}
                lyrics, _, _ = workflow._preflight_drafts({'videoId': video_id})
                self.assertEqual(lyrics['tracks'][0]['componentLanguages'], sorted(components))
                self.assertEqual(future_song_module.audit_text(text, 'mul', 'Zyyy', component_languages=components)['issues'], [])
                (draft/'original.txt').write_text('a\u200cb\nHello\n', encoding='utf-8')
                with self.assertRaisesRegex(FutureSongError, 'zero-width-character'):
                    workflow._validate_drafts({'videoId': video_id}, review=review, sources=sources)

    def test_plain_draft_standard(self):
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "original.txt"
            path.write_bytes("First\n\nSecond\n".encode("utf-8"))
            validate_plain_draft(path)
            path.write_bytes("First\r\nSecond\r\n".encode("utf-8"))
            with self.assertRaises(FutureSongError):
                validate_plain_draft(path)

    def test_agent_review_blocks_unresolved_material_issue(self):
        review = {
            "formatVersion": 2,
            "policyVersion": editorial_review.POLICY_VERSION,
            "videoId": "KbKqkR3Q6SA",
            "authorityOutcome": "resolved",
            "reviewer": "Codex",
            "completedAt": "2026-07-28T00:00:00Z",
            "sourceDisposition": "accepted-owner-supplied",
            "recordingOrSourceAssociationReviewed": True,
            "youtubeSurfaceVariantsReviewed": True,
            "publicSourceResearchComplete": True,
            "normalYouTubeVideoId": "KbKqkR3Q6SA",
            "youtubeMusicVideoId": "AAAAAAAAAAA",
            "lyricEvidenceVideoIds": ["KbKqkR3Q6SA", "AAAAAAAAAAA"],
            "originalReviewed": True,
            "unresolvedMaterialIssues": [],
        }
        validate_agent_review(
            review,
            video_id="KbKqkR3Q6SA",
            available_lyric_source_ids={"KbKqkR3Q6SA", "AAAAAAAAAAA"},
        )
        review["performedStructureReview"] = {
            "sourceAlignmentAttemptId": "a" * 32,
            "sourceAlignmentRunSha256": "b" * 64,
            "sourceAlignmentStateSha256": "c" * 64,
            "decision": "baseline",
            "witnessVideoId": "AAAAAAAAAAA",
            "witnessLanguage": "ar",
            "witnessSha256": "d" * 64,
            "reviewer": "External Builder",
            "completedAt": "2026-07-28T00:01:00Z",
        }
        validate_agent_review(
            review,
            video_id="KbKqkR3Q6SA",
            available_lyric_source_ids={"KbKqkR3Q6SA", "AAAAAAAAAAA"},
        )
        for invalid_reviewer in ('', '   ', 'a\nb', 'a' * 201, None, 'REPLACE_WITH_REVIEWER'):
            review['performedStructureReview']['reviewer'] = invalid_reviewer
            with self.assertRaisesRegex(FutureSongError, 'performedStructureReview'):
                validate_agent_review(review, video_id='KbKqkR3Q6SA', available_lyric_source_ids={'KbKqkR3Q6SA', 'AAAAAAAAAAA'})
        review['performedStructureReview']['reviewer'] = 'External Builder'
        review["performedStructureReview"]["decision"] = "candidate"
        with self.assertRaisesRegex(FutureSongError, "performedStructureReview"):
            validate_agent_review(
                review,
                video_id="KbKqkR3Q6SA",
                available_lyric_source_ids={"KbKqkR3Q6SA", "AAAAAAAAAAA"},
            )
        review["performedStructureReview"]["decision"] = "baseline"
        review["unresolvedMaterialIssues"] = ["material ambiguity"]
        with self.assertRaises(FutureSongError):
            validate_agent_review(
                review,
                video_id="KbKqkR3Q6SA",
                available_lyric_source_ids={"KbKqkR3Q6SA", "AAAAAAAAAAA"},
            )

    def test_agent_review_rejects_unstaged_cross_id_source(self):
        review = {
            "formatVersion": 2,
            "policyVersion": editorial_review.POLICY_VERSION,
            "videoId": "KbKqkR3Q6SA",
            "authorityOutcome": "resolved",
            "reviewer": "Codex",
            "completedAt": "2026-07-28T00:00:00Z",
            "sourceDisposition": "accepted-same-song-version",
            "recordingOrSourceAssociationReviewed": True,
            "youtubeSurfaceVariantsReviewed": True,
            "publicSourceResearchComplete": True,
            "normalYouTubeVideoId": "KbKqkR3Q6SA",
            "youtubeMusicVideoId": "AAAAAAAAAAA",
            "lyricEvidenceVideoIds": ["KbKqkR3Q6SA", "AAAAAAAAAAA"],
            "originalReviewed": True,
            "unresolvedMaterialIssues": [],
        }
        with self.assertRaises(FutureSongError):
            validate_agent_review(
                review,
                video_id="KbKqkR3Q6SA",
                available_lyric_source_ids={"KbKqkR3Q6SA"},
            )

    def test_performed_structure_review_binds_the_failed_run_and_witness(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "policy-state"
            workflow.jobs = workflow.root / "jobs"
            video_id = "KbKqkR3Q6SA"
            attempt_id = "a" * 32
            canonical_sha = "b" * 64
            job_dir = workflow.jobs / video_id
            job_dir.mkdir(parents=True)
            state_path = workflow._alignment_state_path(video_id)
            state_path.parent.mkdir(parents=True)
            future_song_module.atomic_write_json(
                state_path,
                {
                    "attemptId": attempt_id,
                    "status": "line-unresolved",
                    "reasons": ["unsupported-mixed-repeat-structure"],
                    "canonicalPlainSha256": canonical_sha,
                },
            )
            witness = job_dir / "candidates" / "AAAAAAAAAAA" / "manual.ar.vtt"
            witness.parent.mkdir(parents=True)
            witness.write_bytes(b"private witness")
            witness_sha = future_song_module.sha256_file(witness)
            source_run_path = root / "source-run.json"
            source_run_path.write_bytes(b"immutable source run")
            run_sha = future_song_module.sha256_file(source_run_path)
            state_sha = future_song_module.sha256_file(state_path)
            workflow.report_store.report_root = Mock(return_value=root / "reports" / video_id)
            workflow.report_store.alignment_failure_snapshot = Mock(
                return_value=(
                    source_run_path,
                    {
                        "failureCode": "line-unresolved",
                        "summary": {
                            "resultClass": "line-unresolved",
                            "reasons": ["unsupported-mixed-repeat-structure"],
                        },
                    },
                    {
                        "resultClass": "line-unresolved",
                        "reasons": ["unsupported-mixed-repeat-structure"],
                    },
                )
            )
            review = {
                "performedStructureReview": {
                    "sourceAlignmentAttemptId": attempt_id,
                    "sourceAlignmentRunSha256": run_sha,
                    "sourceAlignmentStateSha256": state_sha,
                    "decision": "baseline",
                    "witnessVideoId": "AAAAAAAAAAA",
                    "witnessLanguage": "ar",
                    "witnessSha256": witness_sha,
                    "reviewer": "Luna Max",
                    "completedAt": "2026-09-01T20:00:00Z",
                }
            }
            sources = {
                "lyricEvidenceSources": [
                    {
                        "videoId": "AAAAAAAAAAA",
                        "normalYouTube": {
                            "records": [
                                {
                                    "type": "manual-subtitle",
                                    "language": "ar",
                                    "sha256": witness_sha,
                                    "path": str(witness),
                                }
                            ]
                        },
                    }
                ]
            }

            failed_job = {
                "videoId": video_id,
                "state": "WAITING_FOR_EDITORIAL",
                "lastAlignmentAttemptId": attempt_id,
            }
            result = workflow._validated_performed_structure_review(
                failed_job, review, sources, canonical_sha
            )
            self.assertEqual(result["decision"], "baseline")
            witness.write_bytes(b"changed")
            with self.assertRaisesRegex(FutureSongError, "witness changed"):
                workflow._validated_performed_structure_review(
                    failed_job, review, sources, canonical_sha
                )

    def test_performed_structure_review_survives_its_bound_followup_attempt(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "policy-state"
            workflow.jobs = workflow.root / "jobs"
            video_id = "KbKqkR3Q6SA"
            source_attempt = "a" * 32
            current_attempt = "b" * 32
            canonical_sha = "c" * 64
            job_dir = workflow.jobs / video_id
            job_dir.mkdir(parents=True)
            witness = job_dir / "candidates" / "AAAAAAAAAAA" / "manual.ar.vtt"
            witness.parent.mkdir(parents=True)
            witness.write_bytes(b"private witness")
            source_run_path = root / "source-run.json"
            source_run_path.write_bytes(b"immutable source run")
            current_run_path = report_control_path(root, "current-run.json")
            current_run_path.parent.mkdir(exist_ok=True)
            current_run_path.write_bytes(b"immutable current run")
            review = {
                "performedStructureReview": {
                    "sourceAlignmentAttemptId": source_attempt,
                    "sourceAlignmentRunSha256": future_song_module.sha256_file(
                        source_run_path
                    ),
                    "sourceAlignmentStateSha256": "d" * 64,
                    "decision": "baseline",
                    "witnessVideoId": "AAAAAAAAAAA",
                    "witnessLanguage": "ar",
                    "witnessSha256": future_song_module.sha256_file(witness),
                    "reviewer": "Luna Max",
                    "completedAt": "2026-09-02T22:00:00Z",
                }
            }
            review_binding = {
                "decision": "baseline",
                "sha256": hashlib.sha256(
                    future_song_module.json_bytes(
                        review["performedStructureReview"], pretty=True
                    )
                ).hexdigest(),
            }
            sources = {
                "lyricEvidenceSources": [
                    {
                        "videoId": "AAAAAAAAAAA",
                        "normalYouTube": {
                            "records": [
                                {
                                    "type": "manual-subtitle",
                                    "language": "ar",
                                    "sha256": review["performedStructureReview"][
                                        "witnessSha256"
                                    ],
                                    "path": str(witness),
                                }
                            ]
                        },
                    }
                ]
            }
            state_path = workflow._alignment_state_path(video_id)
            state_path.parent.mkdir(parents=True)
            workflow.report_store.report_root = Mock(return_value=root / "reports" / video_id)
            for result_class, reasons in (
                ("retryable-technical", ["ValueError"]),
                ("line-unresolved", ["low-lyric-word-coverage"]),
            ):
                with self.subTest(result_class=result_class):
                    future_song_module.atomic_write_json(
                        state_path,
                        {
                            "attemptId": current_attempt,
                            "status": result_class,
                            "reasons": reasons,
                            "canonicalPlainSha256": canonical_sha,
                            "performedStructureReview": review_binding,
                        },
                    )
                    workflow.report_store.alignment_failure_snapshot = Mock(
                        side_effect=[
                            (
                                source_run_path,
                                {
                                    "failureCode": "line-unresolved",
                                    "summary": {
                                        "resultClass": "line-unresolved",
                                        "reasons": [
                                            "unsupported-mixed-repeat-structure"
                                        ],
                                    },
                                },
                                {
                                    "resultClass": "line-unresolved",
                                    "reasons": [
                                        "unsupported-mixed-repeat-structure"
                                    ],
                                },
                            ),
                            (
                                current_run_path,
                                {
                                    "failureCode": result_class,
                                    "summary": {
                                        "resultClass": result_class,
                                        "reasons": reasons,
                                    },
                                },
                                {
                                    "resultClass": result_class,
                                    "reasons": reasons,
                                    "performedStructureReview": review_binding,
                                },
                            ),
                        ]
                    )
                    result = workflow._validated_performed_structure_review(
                        {
                            "videoId": video_id,
                            "lastAlignmentAttemptId": current_attempt,
                        },
                        review,
                        sources,
                        canonical_sha,
                    )
                    self.assertEqual(result, review_binding)

    def test_agent_review_must_acknowledge_every_staged_source(self):
        video_id = "KbKqkR3Q6SA"
        review = {
            "formatVersion": 2,
            "policyVersion": editorial_review.POLICY_VERSION,
            "videoId": video_id,
            "authorityOutcome": "resolved",
            "reviewer": "Codex",
            "completedAt": "2026-07-31T00:00:00Z",
            "sourceDisposition": "accepted-exact-recording",
            "recordingOrSourceAssociationReviewed": True,
            "youtubeSurfaceVariantsReviewed": True,
            "publicSourceResearchComplete": True,
            "normalYouTubeVideoId": video_id,
            "youtubeMusicVideoId": video_id,
            "lyricEvidenceVideoIds": [video_id],
            "originalReviewed": True,
            "unresolvedMaterialIssues": [],
        }
        with self.assertRaisesRegex(FutureSongError, "every staged"):
            validate_agent_review(
                review,
                video_id=video_id,
                available_lyric_source_ids={video_id, "AAAAAAAAAAA"},
            )

    def test_exact_item_acquisition_is_text_private_and_writes_draft(self):
        with tempfile.TemporaryDirectory() as name:
            draft = Path(name) / "draft"
            record = acquire_exact_item_draft(
                {
                    "position": 1,
                    "videoId": "KbKqkR3Q6SA",
                    "url": "https://www.youtube.com/watch?v=KbKqkR3Q6SA",
                    "title": "Fixture",
                },
                draft,
                client=FakeLyricsClient(),
                detector=object(),
                inferer=lambda text, detector: {
                    "language": "en",
                    "script": "Latn",
                    "confidence": 1.0,
                    "method": "fixture",
                },
            )
            self.assertEqual(record["decision"], "acquired")
            self.assertNotIn("lyrics", record)
            self.assertTrue((draft / "original.txt").is_file())
            self.assertEqual(json.loads((draft / "lyrics.meta.json").read_text())["videoId"], "KbKqkR3Q6SA")

    def test_exact_item_acquisition_rejects_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as name:
            record = acquire_exact_item_draft(
                {"position": 1, "videoId": "KbKqkR3Q6SA", "title": "Fixture"},
                Path(name) / "draft",
                client=FakeLyricsClient("AAAAAAAAAAA"),
                detector=object(),
            )
            self.assertEqual(record["decision"], "rejected")
            self.assertFalse((Path(name) / "draft" / "original.txt").exists())

    def test_normal_youtube_description_candidate_is_private_file(self):
        with tempfile.TemporaryDirectory() as name:
            result = collect_normal_youtube_candidates(
                {
                    "id": "KbKqkR3Q6SA",
                    "description": "Candidate description",
                    "subtitles": {},
                    "automatic_captions": {},
                },
                Path(name),
                video_id="KbKqkR3Q6SA",
            )
            self.assertEqual(result["candidateCount"], 1)
            self.assertEqual(result["usableLyricCandidateCount"], 0)
            self.assertNotIn("Candidate description", json.dumps(result))
            reference = result['records'][0]
            retained = Path(reference['path'])
            self.assertEqual(retained.read_bytes(), b'Candidate description\n')
            self.assertEqual(reference['sha256'], future_song_module.sha256_file(retained))
            self.assertEqual(reference['lyricUse'], 'metadata-or-prose-only')

    def test_autogenerated_credit_description_does_not_satisfy_lyric_body_gate(self):
        with tempfile.TemporaryDirectory() as name:
            result = collect_normal_youtube_candidates(
                {
                    "id": "KbKqkR3Q6SA",
                    "description": (
                        "Provided to YouTube by Example\n\nSong title\nArtist name\n"
                        "Released on: 2026-01-01\nComposer: Example\n\n"
                        "Auto-generated by YouTube."
                    ),
                    "subtitles": {},
                    "automatic_captions": {},
                },
                Path(name),
                video_id="KbKqkR3Q6SA",
            )
        self.assertEqual(result["candidateCount"], 1)
        self.assertEqual(result["usableLyricCandidateCount"], 0)
        self.assertEqual(result["records"][0]["lyricUse"], "metadata-or-prose-only")

    def test_counterpart_discovery_is_tight_and_never_changes_package_id(self):
        class SearchClient:
            def search(self, query, *, filter, limit):
                if filter == "songs":
                    return [
                        {
                            "videoId": "KbKqkR3Q6SA",
                            "resultType": "song",
                            "title": "Example Song",
                            "artists": [{"name": "Example Artist"}],
                            "duration_seconds": 189,
                        },
                        {
                            "videoId": "AAAAAAAAAAA",
                            "resultType": "song",
                            "title": "Example Song (Live)",
                            "artists": [{"name": "Example Artist"}],
                            "duration_seconds": 229,
                        },
                    ]
                return [
                    {
                        "videoId": "BBBBBBBBBBB",
                        "resultType": "video",
                        "title": "Example Artist - Example Song (Official Video)",
                        "artists": [{"name": "Example Artist"}],
                        "duration_seconds": 189,
                    },
                    {
                        "videoId": "CCCCCCCCCCC",
                        "resultType": "video",
                        "title": "Example Song",
                        "artists": [{"name": "Different Artist"}],
                        "duration_seconds": 189,
                    },
                ]

        result = discover_counterpart_candidates(
            {
                "videoId": "KbKqkR3Q6SA",
                "title": "Example Song",
                "uploader": "Example Artist - Topic",
                "durationSeconds": 189,
            },
            client=SearchClient(),
        )
        self.assertEqual(result["decision"], "candidates-found")
        self.assertEqual(result["recommendedVideoId"], "BBBBBBBBBBB")
        self.assertEqual([value["videoId"] for value in result["candidates"]], ["BBBBBBBBBBB"])
        self.assertEqual(result["candidates"][0]["associationStatus"], "pending-agent-review")

    def test_distinct_counterpart_is_staged_without_changing_package_id(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.jobs = root / "jobs"
            workflow.jobs.mkdir()
            workflow._windows_path = lambda path: str(path)
            package_id = "KbKqkR3Q6SA"
            counterpart_id = "AAAAAAAAAAA"
            job_dir = workflow._job_dir(package_id)
            job_dir.mkdir()
            (job_dir / "sources.json").write_text(
                json.dumps(
                    {
                        "videoId": package_id,
                        "lyricEvidenceSources": [{"videoId": package_id}],
                    }
                ),
                encoding="utf-8",
            )
            review = {
                "youtubeSurfaceVariantsReviewed": True,
                "sourceDisposition": "accepted-exact-recording",
                "recordingOrSourceAssociationReviewed": True,
                "unresolvedMaterialIssues": ["Existing issue."],
            }
            (job_dir / "agent-review.json").write_text(json.dumps(review), encoding="utf-8")
            job = {"videoId": package_id, "state": "WAITING_FOR_EDITORIAL"}
            workflow._load_job = lambda value: job
            workflow._save_job = lambda value: None
            workflow._write_task = lambda value: None
            workflow.summary = lambda value: {"videoId": value["videoId"], "state": value["state"]}

            def fake_acquire(item, destination, *, diagnostics_dir):
                self.assertEqual(diagnostics_dir, job_dir)
                destination.mkdir(parents=True, exist_ok=True)
                (destination / "original.txt").write_text("Evidence only\n", encoding="utf-8")
                return {"videoId": item["videoId"], "decision": "acquired"}

            with (
                patch(
                    'stemwise_engine.youtube.fetch_video_info',
                    return_value={
                        "id": counterpart_id,
                        "title": "Counterpart",
                        "uploader": "Artist",
                        "duration": 120,
                    },
                ),
                patch(
                    'stemwise_engine.source_acquisition.collect_normal_youtube_candidates',
                    return_value={"checked": True, "candidateCount": 0, "records": []},
                ),
                patch('stemwise_engine.source_acquisition.acquire_exact_item_draft', side_effect=fake_acquire),
            ):
                result = workflow.add_lyric_source(
                    package_id,
                    f"https://music.youtube.com/watch?v={counterpart_id}",
                )

            sources = json.loads((job_dir / "sources.json").read_text(encoding="utf-8"))
            staged = sources["lyricEvidenceSources"][-1]
            self.assertEqual(result["videoId"], package_id)
            self.assertEqual(staged["videoId"], counterpart_id)
            self.assertEqual(staged["associationStatus"], "pending-agent-review")
            self.assertEqual(sources["videoId"], package_id)
            updated_review = json.loads((job_dir / "agent-review.json").read_text(encoding="utf-8"))
            self.assertFalse(updated_review["youtubeSurfaceVariantsReviewed"])
            self.assertFalse(updated_review["recordingOrSourceAssociationReviewed"])
            self.assertEqual(updated_review["sourceDisposition"], "REPLACE_AFTER_REVIEW")
            self.assertNotIn("recordingReview", updated_review)
            self.assertNotIn("metadataReview", updated_review)
            self.assertIn("Existing issue.", updated_review["unresolvedMaterialIssues"])
            self.assertTrue(
                (job_dir / "candidates" / counterpart_id / "youtube-music-lyrics.txt").is_file()
            )

    def test_normal_new_creates_drafts_directory_before_acquisition_even_without_exact_lyrics(self):
        self._assert_normal_new_drafts(caption_expiry=False)

    def test_normal_new_optional_caption_expiry_preserves_candidates_and_runs_audio(self):
        self._assert_normal_new_drafts(caption_expiry=True)

    def _assert_normal_new_drafts(self, *, caption_expiry):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.jobs.mkdir(parents=True, exist_ok=True)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.owner = root / "owner-input"
            workflow.config = {"projectDir": str(root.resolve())}
            workflow._windows_path = lambda path: str(path)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow._recover_acquisition_lyrics = Mock()
            pipeline = SimpleNamespace(process_song=Mock(return_value="complete"), prepared_inputs=lambda _: None)
            workflow._pipeline = lambda: pipeline
            workflow._analysis_summary = lambda document: {
                "formatVersion": 1,
                "containsLyrics": False,
                "videoId": video_id,
            }
            job_dir = workflow._job_dir(video_id)
            job_dir.mkdir(exist_ok=True)
            job = {
                "videoId": video_id,
                "state": "NEW",
                "url": f"https://www.youtube.com/watch?v={video_id}",
            }

            def acquire_without_lyrics(item, destination):
                self.assertTrue(
                    destination.is_dir(),
                    "the permanent editorial drafts directory must exist before acquisition",
                )
                return {"videoId": item["videoId"], "decision": "not-found"}

            calls = []
            def subtitle(choice):
                from stemwise_engine.provider_operations import current_budget
                calls.append(choice)
                if len(calls) == 2:
                    current_budget().deadline = current_budget().clock()-1
                    current_budget().remaining()
                return b'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nFixture caption\n'

            with (
                patch(
                    'stemwise_engine.youtube.fetch_video_info',
                    return_value={
                        "id": video_id,
                        "title": "Fixture",
                        "uploader": "Artist",
                        "duration": 120,
                        "subtitles": {language: [{'ext': 'vtt', 'url': f'https://www.youtube.com/api/timedtext?v={video_id}'}]
                                      for language in ('en', 'ar')} if caption_expiry else {},
                    },
                ),
                patch(
                    'stemwise_engine.source_acquisition.collect_normal_youtube_candidates',
                    wraps=collect_normal_youtube_candidates,
                ),
                patch(
                    'stemwise_engine.source_acquisition._download_subtitle', side_effect=subtitle,
                ),
                patch('stemwise_engine.source_acquisition.read_child', return_value=({
                    'source': {'videoId': video_id}, 'analysis': {
                        'status': 'complete', 'toolRuns': [], **{section: {'status': 'unavailable'}
                            for section in ('rhythm', 'key', 'structure', 'tuning', 'loudness', 'waveform')}}}, None)),
                patch('stemwise_engine.source_acquisition.acquire_exact_item_draft', side_effect=acquire_without_lyrics),
                patch(
                    'stemwise_engine.source_acquisition.discover_counterpart_candidates',
                    return_value={"decision": "not-found", "candidateCount": 0},
                ),
            ):
                workflow.acquisition(job['videoId']).run(job)

            self.assertTrue((job_dir / "drafts").is_dir())
            self.assertEqual(job["state"], "WAITING_FOR_EDITORIAL" if caption_expiry else "NEEDS_LYRICS")
            pipeline.process_song.assert_called_once()
            if caption_expiry:
                records = json.loads((job_dir/'sources.json').read_bytes())['exactSurfaceChecks']['normalYouTube']['records']
                self.assertEqual(len(records), 2)
                self.assertIn('sha256', records[0])
                self.assertEqual(records[1]['status'], 'unavailable')

    def test_normal_new_preserves_local_draft_for_pending_review(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            project = root / "builder"
            source = root / "future-songs" / "jobs" / video_id / "drafts"
            source.mkdir(parents=True)
            files = {
                "lyrics.meta.json": json.dumps(
                    {
                        "videoId": video_id,
                        "originalLanguage": "mul",
                        "originalScript": "Zyyy",
                        "sources": {
                            "original": {"name": "Fixture original"},
                            "translationEn": {"name": "Fixture translation"},
                            "ipa": {"name": "Fixture IPA"},
                        },
                        "componentLanguages": ["ar", "fr"],
                    }
                )
                + "\n",
                "original.txt": "alpha\n\nbeta\n",
                "translation-en.txt": "one\n\ntwo\n",
                "transliteration-ipa.txt": "a\n\nb\n",
            }
            for filename, value in files.items():
                (source / filename).write_text(value, encoding="utf-8")
            (source / "agent-review.json").write_text("{}\n", encoding="utf-8")
            expected = {filename: (source/filename).read_bytes() for filename in (*files, 'agent-review.json')}

            workflow = workflow_fixture(root)
            workflow.root = root / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.jobs.mkdir(parents=True, exist_ok=True)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.owner = root / "runtime-owner-input"
            workflow.config = {"projectDir": str(project.resolve())}
            workflow._windows_path = lambda path: str(path)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow._pipeline = lambda: SimpleNamespace(
                process_song=lambda item: "complete", prepared_inputs=lambda _: None
            )
            workflow._analysis_summary = lambda document: {
                "formatVersion": 1,
                "containsLyrics": False,
                "videoId": video_id,
            }
            job_dir = workflow._job_dir(video_id)
            job_dir.mkdir(exist_ok=True)
            job = {
                "videoId": video_id,
                "state": "NEW",
                "url": f"https://www.youtube.com/watch?v={video_id}",
            }

            with (
                patch(
                    'stemwise_engine.youtube.fetch_video_info',
                    return_value={
                        "id": video_id,
                        "title": "Fixture",
                        "uploader": "Artist",
                        "duration": 120,
                    },
                ),
                patch(
                    'stemwise_engine.source_acquisition.collect_normal_youtube_candidates',
                    return_value={
                        "checked": True,
                        "candidateCount": 0,
                        "usableLyricCandidateCount": 0,
                        "records": [],
                    },
                ),
                patch('stemwise_engine.source_acquisition.read_child', return_value=({
                    'source': {'videoId': video_id}, 'analysis': {
                        'status': 'complete', 'toolRuns': [], **{section: {'status': 'unavailable'}
                            for section in ('rhythm', 'key', 'structure', 'tuning', 'loudness', 'waveform')}}}, None)),
                patch(
                    'stemwise_engine.source_acquisition.acquire_exact_item_draft',
                    side_effect=AssertionError("existing local draft was ignored"),
                ),
                patch(
                    'stemwise_engine.source_acquisition.discover_counterpart_candidates',
                    return_value={"decision": "not-found", "candidateCount": 0},
                ),
            ):
                workflow.acquisition(job['videoId']).run(job)

            staged = job_dir / "drafts"
            def assert_preserved():
                self.assertEqual({filename: (staged/filename).read_bytes() for filename in expected}, expected)
            assert_preserved()
            for filename, raw in expected.items():
                with self.subTest(mutated=filename):
                    (staged/filename).write_bytes(raw + b'changed')
                    with self.assertRaises(AssertionError):
                        assert_preserved()
                    (staged/filename).write_bytes(raw)
            self.assertEqual((staged / "agent-review.json").read_bytes(), b"{}\n")
            self.assertEqual(workflow_review.requirements_from_draft(staged), (True, True))
            self.assertEqual(job["state"], "WAITING_FOR_EDITORIAL")

    def test_expensive_song_disk_preflight_is_duration_bound_and_fail_closed(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.ready = root / "ready"
            workflow.config = {"childZipMaxBytes": 32 * 1024 * 1024}
            with patch(
                'stemwise_engine.future_song.shutil.disk_usage',
                return_value=SimpleNamespace(total=1, used=1, free=0),
            ):
                with self.assertRaisesRegex(FutureSongError, "insufficient disk"):
                    workflow._preflight_expensive_song_space(
                        "KbKqkR3Q6SA", 240
                    )
            with self.assertRaisesRegex(FutureSongError, "duration"):
                workflow._preflight_expensive_song_space("KbKqkR3Q6SA", None)

    def test_sources_availability_distinguishes_private_text_from_usable_evidence(self):
        video_id = "KbKqkR3Q6SA"
        counterpart_id = "AAAAAAAAAAA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.jobs = root / "jobs"
            draft = root / "drafts"
            draft.mkdir()
            (draft / "lyrics.meta.json").write_text(
                json.dumps({"videoId": video_id, "originalLanguage": "en"}),
                encoding="utf-8",
            )
            (draft / "original.txt").write_text("private fixture\n", encoding="utf-8")
            exact_sources = {
                "videoId": video_id,
                "lyricEvidenceSources": [
                    {
                        "videoId": video_id,
                        "youtubeMusic": {"result": {"decision": "acquired", "lineCount": 2}},
                        "normalYouTube": {"usableLyricCandidateCount": 0},
                    }
                ],
            }

            exact_result = source_acquisition.refresh_sources_availability(exact_sources, draft)
            self.assertIs(exact_result, exact_sources)
            self.assertTrue(exact_result["containsLyrics"])
            self.assertFalse(exact_result["containsLyricText"])
            self.assertTrue(exact_result["usableLyricEvidenceAvailable"])
            self.assertTrue(exact_result["draftLyricsPresent"])
            self.assertEqual(exact_result["lyricsAvailability"], "draft-acquired")
            self.assertNotIn("private fixture", json.dumps(exact_result))

            candidate_path = workflow._job_dir(video_id) / "candidates" / "evidence.txt"
            candidate_path.parent.mkdir(parents=True)
            candidate_path.write_text("private candidate fixture\n", encoding="utf-8")
            candidate_sources = {
                "videoId": video_id,
                "lyricEvidenceSources": [
                    {
                        "videoId": video_id,
                        "youtubeMusic": {"result": {"decision": "not-found"}},
                        "normalYouTube": {"usableLyricCandidateCount": 0},
                    },
                    {
                        "videoId": counterpart_id,
                        "associationStatus": "pending-agent-review",
                        "youtubeMusic": {"result": {"decision": "not-found"}},
                        "normalYouTube": {
                            "usableLyricCandidateCount": 1,
                            "records": [
                                {
                                    "lyricUse": "potential-body",
                                    "path": str(candidate_path),
                                    "sha256": future_song_module.sha256_file(candidate_path),
                                }
                            ],
                        },
                    },
                ],
            }
            missing_draft = root / "candidate-only-drafts"
            candidate_result = source_acquisition.refresh_sources_availability(
                candidate_sources, missing_draft
            )
            self.assertTrue(candidate_result["containsLyrics"])
            self.assertFalse(candidate_result["containsLyricText"])
            self.assertTrue(candidate_result["usableLyricEvidenceAvailable"])
            self.assertFalse(candidate_result["draftLyricsPresent"])
            self.assertEqual(candidate_result["lyricsAvailability"], "candidate-evidence")
            candidate_path.write_text("tampered\n", encoding="utf-8")
            source_acquisition.refresh_sources_availability(candidate_sources, missing_draft)
            self.assertFalse(candidate_sources["containsLyrics"])
            self.assertEqual(candidate_sources["lyricsAvailability"], "candidate-rejected")

    def test_review_template_reports_staged_evidence_without_false_no_body_claim(self):
        video_id = "KbKqkR3Q6SA"
        counterpart_id = "AAAAAAAAAAA"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        sources = {
            "videoId": video_id,
            "containsLyrics": True,
            "containsLyricText": False,
            "usableLyricEvidenceAvailable": True,
            "draftLyricsPresent": False,
            "lyricsAvailability": "candidate-evidence",
            "lyricEvidenceSources": [
                {"videoId": video_id},
                {"videoId": counterpart_id, "associationStatus": "pending-agent-review"},
            ],
        }

        review = workflow_review.review_template(video_id, sources=sources)

        self.assertNotIn("no lyric bodies", review["notes"].casefold())
        self.assertIn("evidence", review["notes"].casefold())
        self.assertEqual(
            review["lyricEvidenceVideoIds"], [video_id, counterpart_id]
        )
        self.assertNotIn("lyrics", review)

    def test_source_for_already_staged_id_is_successful_no_op_even_after_delivery(self):
        package_id = "KbKqkR3Q6SA"
        counterpart_id = "AAAAAAAAAAA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.jobs = root / "jobs"
            job_dir = workflow._job_dir(package_id)
            job_dir.mkdir(parents=True)
            sources_path = job_dir / "sources.json"
            review_path = job_dir / "agent-review.json"
            job_path = job_dir / "job.json"
            candidate = job_dir / "candidates" / counterpart_id / "lyrics.txt"
            candidate.parent.mkdir(parents=True)
            candidate.write_bytes(b"private fixture body\n")
            candidate_sha = future_song_module.sha256_file(candidate)
            sources_path.write_text(
                json.dumps(
                    {
                        "videoId": package_id,
                        "lyricEvidenceSources": [
                            {"videoId": package_id},
                            {
                                "videoId": counterpart_id,
                                "associationStatus": "pending-agent-review",
                                "youtubeMusic": {"checked": True, "result": {"decision": "not-found"}},
                                "normalYouTube": {
                                    "checked": True,
                                    "records": [
                                        {
                                            "path": str(candidate),
                                            "sha256": candidate_sha,
                                            "lyricUse": "potential-body",
                                        }
                                    ]
                                },
                            },
                        ],
                    },
                    sort_keys=True,
                ),
                encoding="utf-8",
            )
            review_path.write_text(
                json.dumps({"notes": "reviewed", "sourceDisposition": "accepted-exact-recording",
                    "recordingOrSourceAssociationReviewed": True,
                    "metadataReview": {"recordingIdentityConfirmed": True}}, sort_keys=True), encoding="utf-8"
            )
            job = {"videoId": package_id, "state": "DELIVERED"}
            job_path.write_text(json.dumps(job, sort_keys=True), encoding="utf-8")
            before = {
                path: (path.read_bytes(), path.stat().st_mtime_ns)
                for path in (sources_path, review_path, job_path)
            }
            workflow._load_job = Mock(return_value=job)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            collect_cross = Mock(
                side_effect=AssertionError("already-staged Source must not reacquire evidence")
            )
            boundary = patch.object(source_acquisition, 'collect_cross_id_evidence', new=collect_cross)
            boundary.start()
            self.addCleanup(boundary.stop)
            workflow.summary = Mock(
                return_value={"videoId": package_id, "state": "DELIVERED"}
            )

            result = workflow.add_lyric_source(
                package_id,
                f"https://music.youtube.com/watch?v={counterpart_id}",
            )

            self.assertEqual(result["sourceAction"]["result"], "already-staged")
            self.assertEqual(result["sourceAction"]["videoId"], counterpart_id)
            collect_cross.assert_not_called()
            workflow._save_job.assert_not_called()
            workflow._write_task.assert_not_called()
            for path, (body, modified) in before.items():
                self.assertEqual(path.read_bytes(), body)
                self.assertEqual(path.stat().st_mtime_ns, modified)

            candidate.write_bytes(b"tampered\n")
            with self.assertRaisesRegex(FutureSongError, "completed job"):
                workflow.add_lyric_source(
                    package_id,
                    f"https://music.youtube.com/watch?v={counterpart_id}",
                )

            with self.assertRaisesRegex(FutureSongError, "completed job"):
                workflow.add_lyric_source(
                    package_id,
                    "https://www.youtube.com/watch?v=BBBBBBBBBBB",
                )
            collect_cross.assert_not_called()

    def test_source_repairs_corrupt_existing_evidence_before_reporting_success(self):
        package_id = "KbKqkR3Q6SA"
        counterpart_id = "AAAAAAAAAAA"
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / "jobs"
            job_dir = workflow._job_dir(package_id)
            job_dir.mkdir(parents=True)
            broken = job_dir / "candidates" / counterpart_id / "lyrics.txt"
            broken.parent.mkdir(parents=True)
            broken.write_bytes(b"corrupt\n")
            sources_path = job_dir / "sources.json"
            sources_path.write_text(
                json.dumps(
                    {
                        "videoId": package_id,
                        "lyricEvidenceSources": [
                            {
                                "videoId": counterpart_id,
                                "associationStatus": "pending-agent-review",
                                "normalYouTube": {
                                    "records": [
                                        {
                                            "path": str(broken),
                                            "sha256": "0" * 64,
                                            "lyricUse": "potential-body",
                                        }
                                    ]
                                },
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (job_dir / "agent-review.json").write_text(
                json.dumps({"unresolvedMaterialIssues": []}), encoding="utf-8"
            )
            job = {
                "videoId": package_id,
                "state": "WAITING_FOR_EDITORIAL",
                "validation": {"stale": True},
            }
            workflow._load_job = Mock(return_value=job)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow.summary = Mock(
                return_value={"videoId": package_id, "state": "WAITING_FOR_EDITORIAL"}
            )

            def repair(*_args, **_kwargs):
                broken.write_bytes(b"reacquired private fixture\n")
                return {
                    "videoId": counterpart_id,
                    "associationStatus": "pending-agent-review",
                    "normalYouTube": {
                        "records": [
                            {
                                "path": str(broken),
                                "sha256": future_song_module.sha256_file(broken),
                                "lyricUse": "potential-body",
                            }
                        ]
                    },
                }

            collect_cross = Mock(side_effect=repair)
            boundary = patch.object(source_acquisition, 'collect_cross_id_evidence', new=collect_cross)
            boundary.start()
            self.addCleanup(boundary.stop)

            result = workflow.add_lyric_source(
                package_id,
                f"https://music.youtube.com/watch?v={counterpart_id}",
            )

            self.assertEqual(result["sourceAction"]["result"], "restaged")
            self.assertNotIn("validation", job)
            refreshed = json.loads(sources_path.read_text(encoding="utf-8"))
            self.assertTrue(refreshed["containsLyrics"])
            self.assertTrue(refreshed["usableLyricEvidenceAvailable"])
            workflow._save_job.assert_called_once_with(job)
            workflow._write_task.assert_called_once_with(job)

    def test_validate_returns_current_snapshot_without_recompiling_or_aligning(self):
        video_id = "KbKqkR3Q6SA"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        job = {
            "videoId": video_id,
            "state": "VALIDATED",
            "lastAlignmentAttemptId": "a" * 32,
            "validation": {"validatedAt": "2026-07-31T00:00:00Z"},
        }
        workflow._load_job = Mock(return_value=job)
        workflow._validated_snapshot_is_current = Mock(return_value=True)
        workflow._validate_drafts = Mock(
            side_effect=AssertionError("current validation must not recompile drafts")
        )
        workflow._align_future_lyrics = Mock(
            side_effect=AssertionError("current validation must not realign lyrics")
        )
        workflow._save_job = Mock()
        workflow._write_task = Mock()
        workflow.summary = Mock(return_value={"videoId": video_id, "state": "VALIDATED"})

        result = workflow.validate(video_id)

        self.assertEqual(result, {"videoId": video_id, "state": "VALIDATED"})
        workflow._validated_snapshot_is_current.assert_called_with(job)
        workflow._validate_drafts.assert_not_called()
        workflow._align_future_lyrics.assert_not_called()
        workflow._save_job.assert_not_called()
        workflow._write_task.assert_called_once_with(job)

    def test_validated_task_write_failure_preserves_binding_and_retry_regenerates(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "future-songs"
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {"reportSha256": "a" * 64},
            }
            validation = dict(job["validation"])
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(return_value=True)
            workflow._write_task = Mock(
                side_effect=[OSError("task write failed"), None, None]
            )
            workflow._save_job = Mock()
            workflow._commit_failed_alignment_attempt = Mock()
            workflow.summary = Mock(return_value={"state": "VALIDATED"})

            with self.assertRaisesRegex(FutureSongError, r"validation failed \(OSError\)"):
                workflow.validate(video_id)

            self.assertEqual(job["state"], "VALIDATED")
            self.assertEqual(job["validation"], validation)
            workflow._save_job.assert_not_called()
            self.assertEqual(workflow.validate(video_id), {"state": "VALIDATED"})
            self.assertEqual(workflow._write_task.call_count, 3)

    def test_successful_alignment_creates_bound_display_review_before_validation(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workspace = Path(name)
            workflow = workflow_fixture(workspace)
            workflow.root = workspace / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.owner = workflow.root / "owner-input"
            workflow.runtime = workspace / "runtime"
            workflow.pc_side_root = workspace / "PC side"
            workflow.reports = workflow.pc_side_root / "reports"
            workflow.config = {"tempDir": str(workspace / "temporary-analysis"), 'projectDir': str(PROJECT)}
            workflow.config_path = workspace/'config.json'
            workflow.config_path.write_text(json.dumps(workflow.config), encoding='utf-8')
            job_dir = workflow._job_dir(video_id)
            job_dir.mkdir(parents=True)
            (job_dir / "sources.json").write_text("{}\n", encoding="utf-8")
            child = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            child.parent.mkdir(parents=True)
            child.write_bytes(b"package")

            job = {
                "videoId": video_id,
                "state": "WAITING_FOR_EDITORIAL",
                "endpointReview": {"stale": True},
            }
            plain = {
                "status": "partial",
                "tracks": [{"kind": "original", "format": "plain", "text": "Line"}],
            }
            drafts = job_dir / 'drafts'
            drafts.mkdir()
            (drafts / 'original.txt').write_text(plain['tracks'][0]['text'] + '\n', encoding='utf-8')
            future_song_module.atomic_write_json(drafts / 'lyrics.meta.json', {
                'videoId': video_id, 'originalLanguage': 'en', 'originalScript': 'Latn',
                'sources': {'original': {'name': 'Synthetic reviewed original'}},
            })
            synced = {
                "status": "partial",
                "tracks": [
                    {
                        "kind": "original",
                        "format": "synced",
                        "lines": [{"startMs": 0, "endMs": 1000, "text": "Line"}],
                    }
                ],
            }
            alignment_state = {
                "status": "committed-lines",
                "alignmentVersion": 5,
                "durationMs": 1000,
            }
            pipeline = Mock()
            pipeline.process_song.return_value = "complete"
            workflow._load_job = Mock(return_value=job)
            workflow._pipeline = Mock(return_value=pipeline)
            workflow._completed_analysis_audio = Mock(
                return_value={
                    "sha256": "a" * 64,
                    "byteCount": 100,
                    "durationMs": 1000,
                    "artworkSha256": "9" * 64,
                    "path": workspace / "temporary-analysis" / video_id / "analysis.wav",
                }
            )
            workflow._validate_authority_outcome = Mock(
                return_value=("resolved", {}, {})
            )
            workflow._validate_drafts = Mock(
                return_value=(
                    plain,
                    "e" * 64,
                    {"sourceFiles": [], "startsMs": []},
                )
            )
            workflow._write_reviewed_lrc_evidence = Mock()
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            initial_alignment_state = None

            def align(_video_id, attempt_id, *_args):
                state_path = workflow._alignment_state_path(video_id)
                nonlocal initial_alignment_state
                initial_alignment_state = json.loads(state_path.read_text(encoding="utf-8"))
                self.assertEqual(initial_alignment_state["canonicalPlainSha256"], _args[2])
                state = {
                    **alignment_state,
                    "attemptId": attempt_id,
                    "canonicalPlainSha256": _args[2],
                }
                future_song_module.atomic_write_json(state_path, state)
                future_song_module.atomic_write_json(
                    state_path.with_name("candidate.private.lyrics.json"), synced
                )
                future_song_module.atomic_write_json(
                    workflow.owner / f"{video_id}.lyrics.json", synced
                )
                return synced, state

            workflow._align_future_lyrics = Mock(side_effect=align)
            workflow._editorial_sha = Mock(return_value="e" * 64)
            workflow._runtime_config = Mock(return_value={})
            workflow.report_store.commit_alignment_run = Mock(return_value="f" * 64)
            workflow._lexical_mapping = Mock(
                return_value=(
                    {},
                    {
                        "mappingVersion": 4,
                        "tokenizationVersion": "stemwise.display-words.v2",
                        "lexicalAuthoritySha256": "b" * 64,
                        "mappingSha256": "c" * 64,
                        "mappingArtifactSha256": "d" * 64,
                    },
                )
            )

            def create_review(current, **values):
                current["state"] = "WAITING_FOR_DISPLAY_REVIEW"
                current["lastAlignmentAttemptId"] = values["alignment_state"][
                    "attemptId"
                ]

            workflow.review_construction.create_display_review_attempt = Mock(side_effect=create_review)
            workflow._windows_path = lambda path: str(path)
            workflow.summary = lambda current: {"state": current["state"]}

            cover = b'reviewed artwork'
            source_url = f'https://www.youtube.com/watch?v={video_id}'
            job['artworkReview'] = {'result': 'accepted', 'sourceVideoId': video_id,
                'sourceUrl': source_url, 'artworkSha256': hashlib.sha256(cover).hexdigest(),
                'reviewedAt': '2026-10-20T00:00:00Z'}
            sources = {'lyricEvidenceSources': [{'videoId': video_id}]}
            sources_path = job_dir/'sources.json'
            sources_path.write_text(json.dumps(sources), encoding='utf-8')
            sources_sha = hashlib.sha256(sources_path.read_bytes()).hexdigest()
            metadata = {'matchStatus': 'verified', 'title': 'Reviewed song', 'artists': ['Reviewed artist'],
                        'sources': [{'name': 'YouTube', 'url': source_url}],
                        'links': [{'type': 'youtube', 'url': source_url}]}
            from stemwise_engine.metadata_research import metadata_sha256
            recording_review = workflow_review.review_template(video_id, sources)['recordingReview']
            recording_review.update(audioSha256='a' * 64, draftSha256=workflow._recording_draft_sha(job),
                sourcesSha256=sources_sha, recordingCompared=True,
                versionSpecificWordingReviewed=True, performedOrderReviewed=True,
                sourceAssociations=[{'videoId': video_id, 'relation': 'exact-recording'}])
            review = {'recordingReview': recording_review,
                'metadataReview': {'formatVersion': 1, 'videoId': video_id,
                    'originalMetadataSha256': metadata_sha256(metadata), 'sourcesSha256': sources_sha,
                    'title': metadata['title'], 'artists': metadata['artists'], 'evidenceUrls': [source_url],
                    'recordingIdentityConfirmed': True}}
            workflow._validate_authority_outcome.return_value = ('resolved', review, sources)
            with patch('stemwise_engine.future_song.read_child', return_value=({'metadata': metadata,
                    'artwork': {'source': {'url': source_url}}}, cover)):
                self.assertEqual(
                    workflow._validate_impl(video_id),
                    {"state": "WAITING_FOR_DISPLAY_REVIEW"},
                )

            attempt_id = workflow._align_future_lyrics.call_args.args[1]
            self.assertEqual(job["lastAlignmentAttemptId"], attempt_id)
            self.assertNotIn("endpointReview", job)
            self.assertIsNotNone(initial_alignment_state)
            canonical_sha = workflow._align_future_lyrics.call_args.args[4]
            pointer_path = (
                workflow.root
                / "lyrics-alignment"
                / "canonical-plain"
                / f"{video_id}.current.json"
            )
            pointer = json.loads(pointer_path.read_text(encoding="utf-8"))
            canonical_path = pointer_path.parent / pointer["fileName"]
            self.assertEqual(pointer["sha256"], canonical_sha)
            self.assertEqual(hashlib.sha256(canonical_path.read_bytes()).hexdigest(), canonical_sha)
            self.assertNotIn("Line", json.dumps(initial_alignment_state))
            self.assertEqual(
                workflow.report_store.commit_alignment_run.call_args.kwargs["artifacts"][
                    "artworkSha256"
                ],
                "9" * 64,
            )
            self.assertEqual(
                workflow.review_construction.create_display_review_attempt.call_args.kwargs[
                    "alignment_artifacts"
                ]["artworkSha256"],
                "9" * 64,
            )
            self.assertEqual(
                workflow.review_construction.create_display_review_attempt.call_args.kwargs[
                    "alignment_state"
                ]["attemptId"],
                attempt_id,
            )
            pipeline.process_song.assert_not_called()



    def test_endpoint_review_projects_only_bound_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=False)

    def test_endpoint_review_rejects_a_lexical_token_end_even_when_coordinates_agree(self):
        self._check_endpoint_review_projection(visual=False, lexical_role='selected-end')

    def test_endpoint_review_requires_two_eligible_end_witnesses(self):
        self._check_endpoint_review_projection(visual=False, lexical_role='single-end')

    def test_endpoint_review_preserves_a_qualified_lexical_onset_contradiction(self):
        self._check_endpoint_review_projection(visual=False, lexical_role='contrary-start')

    def test_endpoint_review_keeps_agreeing_lexical_onsets_as_private_evidence(self):
        self._check_endpoint_review_projection(visual=False, lexical_role='agreeing-start')

    def test_endpoint_review_rechecks_end_witnesses_for_retained_word_ready_lines(self):
        self._check_endpoint_review_projection(visual=False, preserve_words=True, lexical_role='ready-single-end')

    def test_endpoint_review_rejects_disjoint_line_only_witnesses(self):
        self._check_endpoint_review_projection(visual=False, lexical_role='disjoint')

    def test_endpoint_review_preserves_verified_acoustic_end_adjudication(self):
        for case in ('adjudicated-end', 'adjudicated-weak'):
            with self.subTest(case=case):
                self._check_endpoint_review_projection(visual=True, ctc=True, lexical_role=case)

    def test_endpoint_review_rejects_external_edit_during_projection(self):
        self._check_endpoint_review_projection(visual=False, concurrent_review=True)

    def test_endpoint_review_rechecks_retained_content_before_any_replay_or_publication(self):
        self._check_endpoint_review_projection(visual=False, content_fault=True)

    def test_external_reviewer_projects_only_bound_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=False, reviewer='External Builder')

    def test_visual_endpoint_review_projects_only_bound_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=True)

    def test_ctc_endpoint_review_projects_only_supported_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=True, ctc=True)

    def test_repeat_prediction_projects_only_supported_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=True, repeat=True)

    def test_candidate_prediction_projects_only_supported_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=True, prediction=True)

    def test_native_ctc_projects_only_replayed_supported_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=True, native=True)

    def test_multilingual_ctc_projects_only_replayed_supported_candidates_without_realigning(self):
        self._check_endpoint_review_projection(visual=True, owsm=True)

    def test_endpoint_projection_preserves_already_qualified_word_timings(self):
        self._check_endpoint_review_projection(visual=False, preserve_words=True)

    def test_endpoint_projection_reassesses_partial_onset_observations(self):
        from stemwise_engine import alignment_endpoints as endpoints
        with patch.object(endpoints, 'assess_complete_word_sequence',
                wraps=endpoints.assess_complete_word_sequence) as assess:
            self._check_endpoint_review_projection(visual=False, preserve_words=True, lexical_role='ready-onset')
        self.assertGreater(assess.call_count, 0)
        self.assertTrue(all('owsm-ctc' in call.args[1] for call in assess.call_args_list))


    def test_endpoint_recovery_preserves_accepted_work_across_unavailable_evidence_and_retry(self):
        self._check_endpoint_review_projection(visual=False, preserve_words=True, automatic=True)

    def test_endpoint_recovery_retains_completed_packets_after_retired_route_failure_and_resume(self):
        self._check_endpoint_review_projection(visual=False, preserve_words=True, automatic=True,
            recovery_failure=True)

    def test_endpoint_recovery_keeps_unknown_acceptance_on_terminal_or_authority_failure(self):
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for error in (WorkerRetirementError('unconfirmed'), ProviderCancelled('cancelled'),
                ProviderDeadline('parent-deadline'), ValueError('stale-authority'), RuntimeError('unknown-work')):
            with self.subTest(disposition=type(error).__name__):
                self._check_endpoint_review_projection(visual=False, preserve_words=True, automatic=True,
                    recovery_error=error)






    def _check_endpoint_review_projection(self, *, visual, ctc=False, repeat=False, prediction=False, native=False, owsm=False, preserve_words=False, automatic=False, recovery_failure=False, recovery_error=None, reviewer='Luna Max', content_fault=False, concurrent_review=False, lexical_role=None):
        evidence_module = "endpoint_owsm_ctc" if owsm else "endpoint_native_ctc" if native else "endpoint_candidate_prediction" if prediction else "endpoint_repeat_alignment" if repeat else "endpoint_ctc_evidence" if ctc else "endpoint_audio_evidence"
        evidence_module = 'stemwise_engine.' + evidence_module
        from stemwise_engine.lyrics_alignment import (
            AlignmentWorkspace,
            build_lexical_occurrence_mapping,
            lexical_mapping_identity,
            snapshot_canonical_plain,
        )

        video_id = "yBhH5D1v698"
        self.assertNotIn(
            "unsafe-occurrence-identity",
            workflow_review.ENDPOINT_REVIEW_REASONS,
        )
        source_attempt_id = "a" * 32
        with tempfile.TemporaryDirectory() as name:
            root = Path(name).resolve()
            workflow = workflow_fixture(root)
            if native or owsm:
                workflow.config = {"projectDir": str(root / "builder"), "analysisTimeoutMinutes": 1}
            workflow.root = root / "state"
            workflow.jobs = workflow.root / "jobs"
            workflow.owner = root / "owner"
            workflow.reports = root / "reports"
            workflow.pc_side_root = root / "PC side"
            for directory in (
                workflow.jobs / video_id,
                workflow.owner,
                workflow.reports,
                workflow.root / "lyrics-alignment" / "jobs" / video_id,
            ):
                directory.mkdir(parents=True, exist_ok=True)

            original_text = "Fixed prelude\nAlpha beta\nGamma delta"
            plain = {
                "status": "partial",
                "reason": "Timing unavailable.",
                "tracks": [
                    {
                        "id": "original-en",
                        "kind": "original",
                        "language": "en",
                        "script": "Latn",
                        "format": "plain",
                        "text": original_text,
                        "source": {"name": "owner-supplied"},
                    }
                ],
            }
            plain_raw = future_song_module.json_bytes(plain, pretty=True)
            sidecar = workflow.owner / f"{video_id}.lyrics.json"
            sidecar.write_bytes(plain_raw)
            alignment_workspace = AlignmentWorkspace(
                workflow.root, workspace_root=root
            )
            _, canonical_sha = snapshot_canonical_plain(
                alignment_workspace, video_id, plain_raw
            )
            mapping = build_lexical_occurrence_mapping(
                video_id, plain["tracks"][0], [0, 1, 2]
            )
            mapping["numeric"] = {
                "sourcePhysicalIndices": [0, 1, 2],
                "startsMs": [0, 100, 890],
            }
            mapping_identity = {
                **lexical_mapping_identity(mapping),
                "mappingArtifactSha256": "d" * 64,
            }

            def occurrence(index, start, end, *, unresolved):
                mapped = mapping["performedOccurrences"][index]
                value = endpoint_occurrence_candidate(index)
                value.update(
                    occurrenceId=mapped["occurrenceId"],
                    sourcePhysicalIndex=mapped["sourcePhysicalIndex"],
                )
                for family in value["familyCandidates"].values():
                    family["startMs"] += start - 100
                    family["endMs"] += end - 900
                    for word in family['words']:
                        word.update(startMs=max(family['startMs'], start+10),
                            endMs=min(family['endMs'], start+50))
                if unresolved:
                    value.pop("selectedPublisherFamily")
                    value["publishDecision"] = {
                        "result": "unresolved",
                        "code": "line-end-disagreement",
                    }
                return value

            endpoint_evidence = {
                "resultClass": "line-unresolved",
                "occurrenceCandidates": [
                    occurrence(0, 0, 110, unresolved=False),
                    occurrence(1, 100, 900, unresolved=True),
                    occurrence(2, 890, 1800, unresolved=True),
                ],
            }
            if lexical_role:
                from types import SimpleNamespace
                from stemwise_engine import owsm_ctc_forced_alignment as worker
                from stemwise_engine.alignment_endpoints import _candidate_audit
                def lexical_candidate(index, intervals):
                    target = mapping['performedOccurrences'][index]
                    word_ids = [word['occurrenceWordId'] for word in target['words']]
                    token_lines = [{'lineId': target['occurrenceId'], 'sourcePhysicalIndex': index}]
                    token_plan = [{'tokenIds': [1, 2], 'words': [
                        {'wordId': word_id, 'targetStart': i, 'targetEnd': i+1}
                        for i, word_id in enumerate(word_ids)]}]
                    spans = [SimpleNamespace(token=i+1, start=a, end=b, score=-.01)
                        for i, (a, b) in enumerate(intervals)]
                    lexical = worker._lexical_records(token_lines, token_plan, spans, 1., 2000)[0]
                    lexical.update(route=worker.ROUTES_BY_LANGUAGE['en'],
                        endpointSemantics='first-to-last-approved-lexical-ctc-token-span-v1')
                    return _candidate_audit(lexical)
                intervals = ((700, 720), (740, 880)) if lexical_role == 'contrary-start' else ((120, 140), (740, 880))
                endpoint_evidence['occurrenceCandidates'][1]['familyCandidates']['owsm-ctc'] = lexical_candidate(1, intervals)
                if lexical_role in {'single-end', 'adjudicated-weak'}:
                    endpoint_evidence['occurrenceCandidates'][1]['familyCandidates']['mms-fa']['words'][-1]['confidence'] = 0.
                    endpoint_evidence['occurrenceCandidates'][1]['familyCandidates']['mms-fa']['confidence'] = 0.
                if lexical_role == 'disjoint':
                    for family, start, end in (('mms-fa', 200, 250), ('language-wav2vec2', 300, 350)):
                        candidate = endpoint_evidence['occurrenceCandidates'][1]['familyCandidates'][family]
                        candidate.update(startMs=start, endMs=end)
                        candidate['words'][0].update(startMs=start, endMs=end)
                if lexical_role == 'adjudicated-end':
                    candidate = endpoint_evidence['occurrenceCandidates'][1]['familyCandidates']['mms-fa']
                    candidate['endMs'] = candidate['words'][-1]['endMs'] = 1500
                    for family, start in (('mms-fa', 1600), ('language-wav2vec2', 1620)):
                        following = endpoint_evidence['occurrenceCandidates'][2]['familyCandidates'][family]
                        following['startMs'] = start
                        following['words'][0].update(startMs=start, endMs=start+40)
            ready = endpoint_evidence["occurrenceCandidates"][0]
            ready["publishDecision"] = {"result": "line-only", "code": "independent-endpoint-consensus-line-only"}
            if preserve_words:
                ready["publishDecision"] = {"result": "words"}
                ready["familyCandidates"]["mms-fa"]["words"] = [
                    {"occurrenceWordId": word["occurrenceWordId"], "startMs": a, "endMs": b,
                     "confidence": .8, "acousticTokenCount": 1}
                    for word, a, b in zip(mapping["performedOccurrences"][0]["words"], (0, 60), (40, 110))]
                ready['familyCandidates']['language-wav2vec2'].update(startMs=0, endMs=110)
                ready["familyCandidates"]["language-wav2vec2"]["words"] = [
                    {"occurrenceWordId": word["occurrenceWordId"], "startUtf16": a, "endUtf16": b,
                     "startMs": start, "endMs": end}
                    for word, a, b, start, end in zip(mapping["performedOccurrences"][0]["words"],
                        (0, 6), (5, 13), (0, 60), (40, 110))]
                if lexical_role == 'ready-single-end':
                    ready['familyCandidates']['language-wav2vec2']['words'][-1]['confidence'] = 0.
                if lexical_role == 'ready-onset':
                    ready['familyCandidates']['owsm-ctc'] = lexical_candidate(0, ((0, 40), (60, 110)))
            from stemwise_engine.alignment_endpoints import _summarize_endpoint_occurrences
            endpoint_evidence = _summarize_endpoint_occurrences(endpoint_evidence['occurrenceCandidates'],
                reviewed_lrc_start_evidence_count=0)
            diagnostics = {
                "alignmentVersion": ALIGNMENT_VERSION,
                "durationMs": 2000,
                "resultClass": "line-unresolved",
                "metrics": {
                    "lyric_word_count": 6,
                    "transcript_word_count": 6,
                    "matched_word_count": 6,
                    "directly_matched_line_count": 3,
                    "line_count": 3,
                    "lyric_word_coverage": 1.0,
                    "transcript_word_coverage": 1.0,
                    "direct_line_coverage": 1.0,
                    "average_word_score": 99.0,
                    "timed_span_ratio": 0.85,
                },
                "internalRepeatStructureKind": "none",
                "internalRepeatAuditComplete": True,
                "endpointRouteAttempts": [{"routeId": "mix", "reused": False,
                    "endpointEvidence": copy.deepcopy(endpoint_evidence)}],
                "selectedEndpointAttemptIndex": 0,
                "routeFailures": [],
            }
            sys.path.insert(0, str(WORKSPACE / 'Test suite/pc/unit'))
            from lyric_content_fixtures import retained_content
            audio_path = root/'analysis.wav'
            with wave.open(str(audio_path), 'wb') as audio:
                audio.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                audio.writeframes(b'\0\0'*32000)
            audio_sha = future_song_module.sha256_file(audio_path)
            admission, recognition_path = retained_content(alignment_workspace.job_dir(video_id), plain['tracks'][0],
                (0, 1, 2), canonical_sha, audio_sha)
            diagnostics['contentAdmission'] = admission.to_record()
            state = {
                'contentAdmission': admission.to_record(),
                "videoId": video_id,
                "attemptId": source_attempt_id,
                "status": "line-unresolved",
                "durationMs": 2000,
                "canonicalPlainSha256": canonical_sha,
                **mapping_identity,
            }
            state_path = workflow._alignment_state_path(video_id)
            future_song_module.atomic_write_json(state_path, state)
            sources_path = workflow.jobs / video_id / "sources.json"
            sources_path.write_text("{}\n", encoding="utf-8")
            source_run_sha = "b" * 64
            blocker = {
                "reason": "exhausted-endpoint-cascade",
                "alignmentAttemptId": source_attempt_id,
                "bindings": {
                    "alignmentRunSha256": source_run_sha,
                    "alignmentStateSha256": hashlib.sha256(
                        state_path.read_bytes()
                    ).hexdigest(),
                },
            }
            job = {
                "videoId": video_id,
                "state": "LINE_UNRESOLVED",
                "lastAlignmentAttemptId": source_attempt_id,
                "outcome": {"code": "line-unresolved"},
            }
            analysis_audio = {
                "sha256": audio_sha,
                "byteCount": audio_path.stat().st_size,
                "durationMs": 2000,
                "artworkSha256": "9" * 64,
                "path": audio_path,
            }
            workflow.review_construction.endpoint_failure_source = Mock(return_value=blocker)
            workflow.review_construction.failed_endpoint_diagnostics = Mock(
                return_value=(diagnostics, endpoint_evidence)
            )
            workflow._lexical_mapping = Mock(
                return_value=(mapping, mapping_identity)
            )
            workflow._pipeline = Mock(return_value=object())
            workflow._completed_analysis_audio = Mock(return_value=analysis_audio)
            workflow._editorial_sha = Mock(return_value="e" * 64)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow._load_job = Mock(return_value=job)
            workflow._align_future_lyrics = Mock(
                side_effect=AssertionError("endpoint review must not realign")
            )
            workflow.report_store.commit_alignment_run = Mock(return_value="f" * 64)
            workflow.summary = lambda current: {"state": current["state"]}

            from dataclasses import replace
            workflow.review_construction.context = replace(workflow.review_construction.context,
                lexical_mapping=workflow._lexical_mapping, pipeline=workflow._pipeline,
                completed_analysis_audio=workflow._completed_analysis_audio, editorial_sha=workflow._editorial_sha)
            pointer = workflow.review_construction.create_endpoint_review_attempt(job)
            review_path = workflow._endpoint_review_attempt_dir(
                video_id, pointer["attemptId"]
            ) / "review.json"
            review = json.loads(review_path.read_text(encoding="utf-8"))
            original_job = copy.deepcopy(job)
            if content_fault:
                cache = json.loads(recognition_path.read_bytes())
                cache['workerOutput']['words'][0]['raw'] = 'wrong'
                recognition_path.write_text(json.dumps(cache), encoding='utf-8')
                before = {p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}
                with patch('stemwise_engine.endpoint_ctc_evidence.write_packet', side_effect=AssertionError('must not infer')) as infer:
                    from stemwise_engine.workflow_review import EndpointReviewService, EndpointReviewRequest
                    with self.assertRaises(future_song_module.LyricsContentBlocker):
                        EndpointReviewService(workflow._endpoint_review_context(), workflow._review_operations(), workflow._review_policy()).validate(
                            EndpointReviewRequest(job=job, evidence_only=True, vocal=True))
                    infer.assert_not_called()
                self.assertEqual({p.relative_to(root): p.read_bytes() for p in root.rglob('*') if p.is_file()}, before)
                self.assertEqual(job, original_job)
                return
            if automatic:
                from importlib import import_module
                from stemwise_engine.alignment_attempt import SongAttemptBlocked
                from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
                names = ('endpoint_ctc_evidence', 'endpoint_repeat_alignment',
                    'endpoint_candidate_prediction', 'endpoint_native_ctc', 'endpoint_owsm_ctc')
                manifests, writers = {}, {}
                with ExitStack() as substitutes:
                    for name in names:
                        module = import_module('stemwise_engine.' + name)
                        manifest_path = module._directory(review_path.parent)/'manifest.json'
                        manifest = {'method': module.METHOD, 'occurrences': [
                            {'occurrenceId': occurrence['occurrenceId'], 'assessment': {
                                boundary: {'result': 'abstain', 'reason': 'ctc-views-or-perturbations-inconclusive', 'supportedFamilies': []}
                                for boundary in ('startMs', 'endMs')}} for occurrence in review['occurrences']]}
                        manifests[name] = manifest_path, manifest
                        def execute(*_args, name=name, manifest_path=manifest_path, manifest=manifest):
                            self.assertEqual(process_lock_descriptor(heavy_worker_lock_path(workflow.config))
                                is not None, name == 'endpoint_ctc_evidence')
                            if recovery_error is not None and name == names[1]:
                                raise recovery_error
                            if recovery_failure and name == names[1]:
                                from stemwise_engine.worker_protocol import WorkerProtocolError
                                self.assertEqual(_args[-1]['manifestSha256'],
                                    future_song_module.sha256_file(manifests[names[0]][0]))
                                raise WorkerProtocolError('repeat-ctc-worker-failed')
                            if recovery_failure and name == names[2]:
                                self.assertEqual(_args[-1]['manifestSha256'],
                                    future_song_module.sha256_file(manifests[names[0]][0]))
                            if recovery_failure and name == names[3]:
                                return _args[-1]
                            future_song_module.atomic_write_json(manifest_path, manifest)
                            return {'method': manifest['method'], 'manifestSha256':
                                future_song_module.sha256_file(manifest_path), 'occurrences': manifest['occurrences']}
                        def verify(*args, manifest_path=manifest_path, manifest=manifest):
                            self.assertEqual(args[4], {'method': manifest['method'], 'manifestSha256':
                                future_song_module.sha256_file(manifest_path)})
                            self.assertEqual(json.loads(manifest_path.read_bytes()), manifest)
                            return manifest
                        writers[name] = substitutes.enter_context(patch.object(module, 'write_packet', side_effect=execute))
                        substitutes.enter_context(patch.object(module, 'verify_packet', side_effect=verify))
                    if recovery_error is not None:
                        with self.assertRaises(type(recovery_error)) as failed:
                            workflow.endpoint_evidence(video_id, vocal=True)
                        self.assertIs(failed.exception, recovery_error)
                        retained = manifests[names[0]][0].read_bytes()
                        self.assertEqual([row['status'] for row in
                            workflow._song_ledger(video_id).record['actions'].values()], ['complete', 'unknown'])
                        with self.assertRaisesRegex(SongAttemptBlocked, 'accepted-recovery-evidence-unavailable'):
                            workflow.endpoint_evidence(video_id, vocal=True)
                        self.assertEqual(manifests[names[0]][0].read_bytes(), retained)
                        for name in names[:2]:
                            writers[name].assert_called_once()
                        for name in names[2:]:
                            writers[name].assert_not_called()
                        self.assertEqual(job, original_job)
                        self.assertEqual(json.loads(review_path.read_bytes()), review)
                        self.assertEqual(sidecar.read_bytes(), plain_raw)
                        return
                    result = workflow.endpoint_evidence(video_id, vocal=True)
                    self.assertFalse(result['automaticallySelected'])
                    if recovery_failure:
                        actions = list(workflow._song_ledger(video_id).record['actions'].values())
                        self.assertEqual([action['status'] for action in actions],
                            ['complete', 'failed', 'complete', 'complete', 'complete'])
                        self.assertEqual(actions[1]['failure'], {
                            'exceptionType': 'WorkerProtocolError', 'code': 'repeat-ctc-worker-failed'})
                        self.assertFalse(manifests[names[1]][0].exists())
                    retained = {path: path.read_bytes() for path, _manifest in manifests.values() if path.is_file()}
                    missing = manifests[names[0]][0]
                    missing.unlink()
                    with self.assertRaisesRegex(SongAttemptBlocked, 'accepted-recovery-evidence-unavailable'):
                        workflow.endpoint_evidence(video_id, vocal=True)
                    missing.write_bytes(retained[missing])
                    self.assertFalse(workflow.endpoint_evidence(video_id, vocal=True)['automaticallySelected'])
                    for writer in writers.values():
                        writer.assert_called_once()
                    self.assertEqual({path: path.read_bytes() for path in retained}, retained)
                self.assertEqual(job, original_job)
                self.assertEqual(json.loads(review_path.read_bytes()), review)
                self.assertEqual(sidecar.read_bytes(), plain_raw)
                self.assertEqual({action['status'] for action in workflow._song_ledger(video_id).record['actions'].values()},
                    {'complete', 'failed'} if recovery_failure else {'complete'})
                workflow._align_future_lyrics.assert_not_called()
                return
            with patch('stemwise_engine.endpoint_audio_evidence.write_packet', return_value={"method": "local-signal-view-v1"}) as render:
                self.assertEqual(workflow.endpoint_evidence(video_id), {"method": "local-signal-view-v1"})
                render.assert_called_once()
            self.assertEqual(job, original_job)
            self.assertEqual(json.loads(review_path.read_text(encoding="utf-8")), review)
            with patch('stemwise_engine.endpoint_audio_evidence.write_packet', side_effect=RuntimeError("child diagnostic detail")):
                with self.assertRaisesRegex(RuntimeError, "child diagnostic detail"):
                    workflow.endpoint_evidence(video_id)
            failure_path = state_path.with_name("endpoint.failure.private.txt")
            failure = failure_path.read_text(encoding="utf-8")
            self.assertIn("RuntimeError: child diagnostic detail", failure)
            self.assertIn("_validate_endpoint_review", failure)
            self.assertLessEqual(failure_path.stat().st_size, 12000)
            with patch('stemwise_engine.endpoint_audio_evidence.write_packet', side_effect=RuntimeError("detail " + "é" * 12000)):
                with self.assertRaises(RuntimeError):
                    workflow.endpoint_evidence(video_id)
            # A byte cap may drop the final half of the two-byte test character.
            failure_bytes = failure_path.read_bytes()
            self.assertIn(len(failure_bytes), (12000, 12000-len('é'.encode('utf-8'))+1))
            self.assertEqual(failure_bytes.decode('utf-8').encode('utf-8'), failure_bytes)
            self.assertEqual(job, original_job)
            self.assertEqual(json.loads(review_path.read_text(encoding="utf-8")), review)
            self.assertEqual(
                workflow._validate_impl(video_id), {"state": "LINE_UNRESOLVED"}
            )
            edited_pending = copy.deepcopy(review)
            edited_pending["completedAt"] = "2026-09-01T12:00:00Z"
            future_song_module.atomic_write_json(review_path, edited_pending)
            with self.assertRaisesRegex(
                future_song_module.EndpointReviewSelectionError,
                "endpoint-review-decision-invalid",
            ):
                workflow._validate_impl(video_id)
            review.update(
                outcome="resolved",
                reviewer=reviewer,
                completedAt="2026-09-01T12:00:00Z",
            )
            review["occurrences"][0].update(
                startCandidateFamily="language-wav2vec2",
                endCandidateFamily="language-wav2vec2",
            )
            review["occurrences"][1].update(
                startCandidateFamily="mms-fa",
                endCandidateFamily="mms-fa",
            )

            original_sidecar = sidecar.read_bytes()
            if lexical_role in {'adjudicated-end', 'adjudicated-weak'}:
                review['audioEvidence'] = {'method': 'local-ctc-boundary-v1', 'manifestSha256': 'b'*64}
                future_song_module.atomic_write_json(review_path, review)
                workflow.review_construction.create_display_review_attempt = Mock(
                    side_effect=lambda current, **_: current.update(state='WAITING_FOR_DISPLAY_REVIEW'))
                # Substitute expensive acoustic verification; the bound selection gate stays real.
                packet = {'boundaries': [], 'occurrences': [
                    {'occurrenceId': choice['occurrenceId'], 'assessment': {
                        boundary: {'result': 'supported', 'supportedFamilies': [family]}
                        for boundary in ('startMs', 'endMs')}}
                    for choice, family in zip(review['occurrences'], ('language-wav2vec2', 'mms-fa'))]}
                with patch('stemwise_engine.endpoint_ctc_evidence.verify_packet', return_value=packet):
                    self.assertEqual(workflow._validate_impl(video_id), {'state': 'WAITING_FOR_DISPLAY_REVIEW'})
                lines = json.loads(sidecar.read_bytes())['tracks'][0]['lines']
                self.assertEqual((lines[1]['startMs'], lines[1]['endMs']), (120, 880))
                self.assertEqual(endpoint_evidence['occurrenceCandidates'][1]['familyCandidates']['mms-fa']['endMs'],
                    1500 if lexical_role == 'adjudicated-end' else 900)
                workflow._align_future_lyrics.assert_not_called()
                return
            if lexical_role in {'selected-end', 'single-end', 'contrary-start', 'ready-single-end', 'disjoint'}:
                if lexical_role == 'selected-end':
                    review['occurrences'][0]['endCandidateFamily'] = 'owsm-ctc'
                if lexical_role == 'disjoint':
                    review['occurrences'][0].update(startCandidateFamily='mms-fa', endCandidateFamily='mms-fa')
                future_song_module.atomic_write_json(review_path, review)
                workflow.review_construction.create_display_review_attempt = Mock(
                    side_effect=lambda current, **_: current.update(state='WAITING_FOR_DISPLAY_REVIEW'))
                original_state = state_path.read_bytes()
                original_pointer = copy.deepcopy(job['endpointReview'])
                with self.assertRaisesRegex(future_song_module.EndpointReviewSelectionError,
                        'endpoint-review-selection-invalid-'+('0' if lexical_role == 'ready-single-end' else '1')):
                    workflow._validate_impl(video_id)
                self.assertEqual(sidecar.read_bytes(), original_sidecar)
                self.assertEqual(state_path.read_bytes(), original_state)
                self.assertEqual(job['endpointReview'], original_pointer)
                workflow._align_future_lyrics.assert_not_called()
                return
            invalid_cases = []
            for invalid_reviewer in ('', '   ', 'a\nb', 'a' * 201, None, 'REPLACE_WITH_REVIEWER'):
                invalid_reviewer_case = copy.deepcopy(review)
                invalid_reviewer_case['reviewer'] = invalid_reviewer
                invalid_cases.append((invalid_reviewer_case, 'endpoint-review-metadata-invalid'))
            with_raw_time = copy.deepcopy(review)
            with_raw_time["occurrences"][0]["startMs"] = 123
            invalid_cases.append(
                (with_raw_time, "endpoint-review-selection-invalid-1")
            )
            wrong_occurrence = copy.deepcopy(review)
            wrong_occurrence["occurrences"][0]["occurrenceId"] = "occurrence-wrong"
            invalid_cases.append(
                (wrong_occurrence, "endpoint-review-selection-invalid-1")
            )
            wrong_family = copy.deepcopy(review)
            wrong_family["occurrences"][0]["endCandidateFamily"] = "manual"
            invalid_cases.append(
                (wrong_family, "endpoint-review-selection-invalid-1")
            )
            incomplete = copy.deepcopy(review)
            incomplete["occurrences"].pop()
            invalid_cases.append((incomplete, "endpoint-review-selections-incomplete"))
            invalid_metadata = copy.deepcopy(review)
            invalid_metadata["completedAt"] = "2026-09-01T12:00:00.500Z"
            invalid_cases.append((invalid_metadata, "endpoint-review-metadata-invalid"))
            invalid_decision = copy.deepcopy(review)
            invalid_decision["outcome"] = "approved"
            invalid_cases.append((invalid_decision, "endpoint-review-decision-invalid"))
            for invalid, code in invalid_cases:
                future_song_module.atomic_write_json(review_path, invalid)
                with self.assertRaisesRegex(
                    future_song_module.EndpointReviewSelectionError,
                    code,
                ):
                    workflow._validate_impl(video_id)
                self.assertEqual(sidecar.read_bytes(), original_sidecar)

            safe_error = future_song_module.EndpointReviewSelectionError(
                "endpoint-review-metadata-invalid"
            )
            self.assertEqual(
                workflow._workflow_operation_failure_code(safe_error),
                "endpoint-review-metadata-invalid",
            )
            with self.assertRaisesRegex(ValueError, 'invalid endpoint refusal code'):
                future_song_module.EndpointReviewSelectionError("private lyric body")
            operation_record = {
                "reportVersion": future_song_module.PRIVATE_REPORT_VERSION,
                "runId": "1" * 32,
                "videoId": video_id,
                "operation": "workflow-operation",
                "operationId": "2" * 32,
                "action": "Validate",
                "phase": "outcome",
                "status": "failed",
                "startedAtUnix": 1,
                "completedAtUnix": 2,
                "stateBefore": "LINE_UNRESOLVED",
                "stateAfter": "VALIDATION_FAILED",
                "outcomeCode": "endpoint-review-metadata-invalid",
                "bindings": {"configSha256": "3" * 64, "policySha256": "4" * 64,
                             "engineSourceSha256": "5" * 64},
            }
            self.assertTrue(workflow._workflow_operation_record_is_safe(operation_record))
            operation_record["outcomeCode"] = "private lyric body"
            self.assertFalse(workflow._workflow_operation_record_is_safe(operation_record))

            workflow._validated_snapshot_is_current = Mock(return_value=False)
            workflow._commit_failed_alignment_attempt = Mock()
            workflow._record_validation_failure = Mock()

            fixed_overlap = copy.deepcopy(review)
            fixed_overlap["occurrences"][0]["startCandidateFamily"] = "mms-fa"
            future_song_module.atomic_write_json(review_path, fixed_overlap)
            with self.assertRaisesRegex(
                future_song_module.EndpointReviewSelectionError,
                "endpoint-review-selection-overlap-0-1",
            ):
                workflow._validate_enhancements_action(video_id)
            workflow._record_validation_failure.assert_called_once_with(
                video_id, "endpoint-review-selection-overlap-0-1"
            )
            self.assertEqual(sidecar.read_bytes(), original_sidecar)

            workflow._record_validation_failure.reset_mock()
            overlapping = copy.deepcopy(review)
            overlapping["occurrences"][0]["endCandidateFamily"] = "mms-fa"
            future_song_module.atomic_write_json(review_path, overlapping)
            with self.assertRaisesRegex(
                future_song_module.EndpointReviewSelectionError,
                "endpoint-review-selection-overlap-1-2",
            ):
                workflow._validate_enhancements_action(video_id)
            workflow._record_validation_failure.assert_called_once_with(
                video_id, "endpoint-review-selection-overlap-1-2"
            )
            self.assertEqual(sidecar.read_bytes(), original_sidecar)

            workflow._record_validation_failure.reset_mock()
            combined_overlaps = copy.deepcopy(review)
            combined_overlaps["occurrences"][0].update(
                startCandidateFamily="mms-fa",
                endCandidateFamily="mms-fa",
            )
            future_song_module.atomic_write_json(review_path, combined_overlaps)
            with self.assertRaisesRegex(
                future_song_module.EndpointReviewSelectionError,
                "endpoint-review-selection-overlap-0-1_1-2",
            ):
                workflow._validate_enhancements_action(video_id)
            workflow._record_validation_failure.assert_called_once_with(
                video_id, "endpoint-review-selection-overlap-0-1_1-2"
            )
            self.assertEqual(sidecar.read_bytes(), original_sidecar)

            def create_display(current, **_values):
                self.assertEqual(current["endpointReview"]["result"], "accepted")
                self.assertEqual(
                    current["endpointReview"]["reviewSha256"],
                    future_song_module.sha256_file(review_path),
                )
                current["state"] = "WAITING_FOR_DISPLAY_REVIEW"

            workflow.review_construction.create_display_review_attempt = Mock(
                side_effect=create_display
            )
            if visual:
                review["audioEvidence"] = {"method": "local-multilingual-native-ctc-v1" if owsm else "local-native-ctc-boundary-v1" if native else "local-vocal-candidate-prediction-v1" if prediction else "local-repeat-phonetic-v1" if repeat else "local-ctc-boundary-v1" if ctc else "local-signal-view-v1", "manifestSha256": "b" * 64}
                if repeat or prediction or native or owsm:
                    review["reviewer"] = review["audioEvidence"]["method"]
                future_song_module.atomic_write_json(review_path, review)
                with patch(evidence_module + ".verify_packet", side_effect=ValueError("tampered")):
                    with self.assertRaisesRegex(future_song_module.EndpointReviewSelectionError, "endpoint-review-audio-evidence-invalid"):
                        workflow._validate_impl(video_id)
                self.assertEqual(sidecar.read_bytes(), original_sidecar)
            future_song_module.atomic_write_json(review_path, review)
            verified_packet = {"boundaries": [], "occurrences": []}
            if ctc or repeat or prediction or native or owsm:
                # Substitute the acoustic calculation, not the selection gate.
                # These families are the fixture's independently declared safe
                # choices; their projected intervals are asserted below.
                verified_packet["occurrences"] = [
                    {"occurrenceId": choice["occurrenceId"], "assessment": {
                        boundary: {"result": "supported", "supportedFamilies": [family]}
                        for boundary in ("startMs", "endMs")}}
                    for choice, family in zip(review["occurrences"], ("language-wav2vec2", "mms-fa"))
                ]
                for boundary in ("startMs", "endMs"):
                    unsupported = copy.deepcopy(verified_packet)
                    unsupported["occurrences"][0]["assessment"][boundary] = {
                        "result": "abstain", "supportedFamilies": []}
                    with patch(evidence_module + ".verify_packet", return_value=unsupported):
                        with self.assertRaisesRegex(future_song_module.EndpointReviewSelectionError,
                                                    "endpoint-review-ctc-evidence-abstained", msg=boundary):
                            workflow._validate_impl(video_id)
                    self.assertEqual(sidecar.read_bytes(), original_sidecar, boundary)
            if repeat or prediction or native or owsm:
                wrong_reviewer = {**review, 'reviewer': 'External Builder'}
                future_song_module.atomic_write_json(review_path, wrong_reviewer)
                with patch(evidence_module + '.verify_packet', return_value=verified_packet):
                    with self.assertRaisesRegex(future_song_module.EndpointReviewSelectionError,
                                                'endpoint-review-metadata-invalid'):
                        workflow._validate_impl(video_id)
                self.assertEqual(sidecar.read_bytes(), original_sidecar)
                future_song_module.atomic_write_json(review_path, review)
            def verify_current(*_args, **_kwargs):
                if native or owsm:
                    from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
                    self.assertIsNone(process_lock_descriptor(heavy_worker_lock_path(workflow.config)),
                                      'Endpoint replay parent CPU retained the GPU')
                return verified_packet
            if concurrent_review:
                from stemwise_engine.lyrics_alignment import build_synced_sidecar
                baseline_state = workflow._alignment_state_path(video_id).read_bytes()
                baseline_pointer = copy.deepcopy(job['endpointReview'])
                for replacement in (workflow.review_construction.endpoint_review_template(video_id, review['attemptId'],
                        review['occurrences']),
                        {**review, 'reviewer': 'Different valid reviewer'}):
                    def replace_decision(*args, **kwargs):
                        future_song_module.atomic_write_json(review_path, replacement)
                        return build_synced_sidecar(*args, **kwargs)
                    future_song_module.atomic_write_json(review_path, review)
                    with patch('stemwise_engine.lyrics_alignment.build_synced_sidecar', side_effect=replace_decision):
                        with self.assertRaisesRegex(future_song_module.EndpointReviewSelectionError,
                                'endpoint-review-decision-changed'):
                            workflow._validate_impl(video_id)
                    self.assertEqual(sidecar.read_bytes(), original_sidecar)
                    self.assertEqual(workflow._alignment_state_path(video_id).read_bytes(), baseline_state)
                    self.assertEqual(job['endpointReview'], baseline_pointer)
                return
            with patch(evidence_module + ".verify_packet", side_effect=verify_current) as verify:
                self.assertEqual(workflow._validate_impl(video_id), {"state": "WAITING_FOR_DISPLAY_REVIEW"})
                self.assertEqual(verify.call_count, int(visual))
            workflow._align_future_lyrics.assert_not_called()
            synced = json.loads(sidecar.read_text(encoding="utf-8"))
            lines = synced["tracks"][0]["lines"]
            self.assertEqual(
                [(line["startMs"], line["endMs"]) for line in lines],
                [(0, 110), (120, 880), (890, 1800)],
            )
            if preserve_words:
                self.assertEqual(lines[0]["words"], [
                    {"startUtf16": 0, "endUtf16": 5, "startMs": 0, "endMs": 40},
                    {"startUtf16": 6, "endUtf16": 13, "startMs": 60, "endMs": 110}])
                self.assertTrue(all("words" not in line for line in lines[1:]))
            else:
                self.assertTrue(all("words" not in line for line in lines))
            workflow.review_construction.create_display_review_attempt.assert_called_once()
            from stemwise_engine.workflow_records import EndpointReviewResult
            persisted = json.loads(workflow._alignment_state_path(video_id).read_bytes())
            result = EndpointReviewResult.from_record(persisted['endpointReview'])
            self.assertEqual(result.occurrence_count, len(review['occurrences']))
            self.assertEqual(result.review_sha256, future_song_module.sha256_file(review_path))
            _, evidence = workflow_reports.alignment_run_record(job, run_id='e'*32, video_id=video_id,
                state=persisted, checkpoint_key={}, checkpoint_raw=b'{}', completed_at=1, status='failed',
                run_artifacts={}, failure_code='retryable-technical', producing_inputs=None, observed_input_changes={})
            self.assertEqual(evidence['diagnostics']['endpointReview'], result.to_record())


    def test_completed_analysis_audio_hashes_validated_cover_bytes(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.config = {"generatorVersion": "test-generator"}
            checkpoint = root / "checkpoint.json"
            checkpoint.write_text(
                json.dumps(
                    {
                        "videoId": video_id,
                        "status": "complete",
                        "generatorVersion": "test-generator",
                        "provenanceSha256": "pinned-provenance",
                        "originalAudio": {
                            "sha256": "a" * 64,
                            "byteCount": 100,
                            "durationMs": 1_000,
                        },
                    }
                ),
                encoding="utf-8",
            )
            retained = root / "analysis.wav"
            retained.write_bytes(b"pcm")
            pipeline = Mock(
                checkpoint_dir=root,
                config={},
                provenance_sha256="pinned-provenance",
            )
            pipeline.checkpoint_path.return_value = checkpoint
            pipeline.load_checkpoint.return_value = json.loads(checkpoint.read_bytes())
            pipeline.child_path.return_value = root / "child.stemwise.zip"
            pipeline.require_retained_analysis_audio.return_value = retained
            cover = b"validated cover bytes"
            with patch.object(
                future_song_module,
                "read_child",
                return_value=(
                    {
                        "source": {"videoId": video_id},
                        "generator": {"version": "test-generator"},
                        "analysis": {"status": "complete", "durationMs": 1_000},
                    },
                    cover,
                ),
            ):
                result = workflow._completed_analysis_audio(pipeline, video_id)
            self.assertEqual(
                result["artworkSha256"], hashlib.sha256(cover).hexdigest()
            )
            self.assertEqual(result["path"], retained)

    def test_validated_snapshot_detects_editorial_child_and_delivery_mutation(self):
        self._check_validated_snapshot()

    def test_validated_snapshot_retains_new_prediction_methods_and_rejects_invalid_packets(self):
        from stemwise_engine.endpoint_candidate_prediction import METHOD
        from stemwise_engine.endpoint_dispatch import evidence_adapter
        with self.assertRaisesRegex(ValueError, 'Unsupported current endpoint evidence method'):
            evidence_adapter('retired-source-prediction')
        self._check_validated_snapshot(prediction_method=METHOD)

    def _check_validated_snapshot(self, *, prediction_method=None):
        video_id = "KbKqkR3Q6SA"
        editorial_sha = "e" * 64
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.root = root / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.test_results = root / "test-results"
            workflow.owner = workflow.root / "owner-input"
            workflow.pc_side_root = root / "PC side"
            workflow.reports = workflow.pc_side_root / "reports"
            workflow.config = {"tempDir": str(root / "temporary-analysis")}
            workflow._windows_path = lambda path: str(path)
            job_dir = workflow._job_dir(video_id)
            job_dir.mkdir(parents=True)
            report_path = workflow.report_store.ensure_report_identity(video_id) / "validation.json"
            sources_path = job_dir / "sources.json"
            sidecar = workflow.owner / f"{video_id}.lyrics.json"
            child = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            delivered = workflow.ready / child.name
            for path in (sources_path, sidecar, child, delivered):
                path.parent.mkdir(parents=True, exist_ok=True)
            sources_path.write_text('{"sources":true}\n', encoding="utf-8")
            from test_future_song_display_review import _plain_original
            from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION
            published_lyrics = {'status': 'partial', 'reason': 'Only the reviewed original is available.',
                'tokenizationVersion': TOKENIZATION_VERSION,
                'tracks': [{**_plain_original('Display'), 'language': 'en', 'script': 'Latn'}]}
            future_song_module.atomic_write_json(sidecar, published_lyrics)
            child_document = {'source': {'videoId': video_id}, 'lyrics': published_lyrics}
            child.write_bytes(b"validated child")
            delivered.write_bytes(child.read_bytes())
            cover = b"validated cover bytes"
            artwork_sha = hashlib.sha256(cover).hexdigest()
            read_child_patcher = patch.object(
                future_song_module,
                "read_child",
                return_value=(child_document, cover),
            )
            read_child = read_child_patcher.start()
            self.addCleanup(read_child_patcher.stop)
            workflow._runtime_config = Mock(return_value={})
            sources_sha = future_song_module.sha256_file(sources_path)
            sidecar_sha = future_song_module.sha256_file(sidecar)
            child_sha = future_song_module.sha256_file(child)
            generated_at = "2026-07-31T00:00:00Z"
            lyrics_timing = {"alignmentVersion": 5}
            retained_audio = {
                "sha256": "a" * 64,
                "byteCount": 100,
                "durationMs": 1_000,
            }
            alignment_run_id = "a" * 32
            alignment_run_sha = "b" * 64
            display_attempt_id = "d" * 32
            display_run_sha = "f" * 64
            mapping_identity = {
                "mappingSha256": "1" * 64,
                "lexicalAuthoritySha256": "2" * 64,
                "mappingArtifactSha256": "3" * 64,
            }
            alignment_state = workflow._alignment_state_path(video_id)
            alignment_state.parent.mkdir(parents=True)
            alignment_state.write_text("{}\n", encoding="utf-8")
            candidate = alignment_state.with_name("candidate.private.lyrics.json")
            candidate.write_bytes(sidecar.read_bytes())
            candidate_sha = future_song_module.sha256_file(candidate)
            alignment_artifacts = {
                "sourcesSha256": sources_sha,
                "sidecarSha256": candidate_sha,
                "alignmentCandidateSha256": candidate_sha,
                "mappingArtifactSha256": mapping_identity["mappingArtifactSha256"],
                "alignmentStateSha256": future_song_module.sha256_file(alignment_state),
                "analysisAudioSha256": retained_audio["sha256"],
                "artworkSha256": artwork_sha,
            }
            display_root = job_dir / "display-review"
            attempt = future_song_module.create_manifested_directory(
                display_root,
                display_root / display_attempt_id,
                owner={
                    "kind": "display-review-attempt",
                    "videoId": video_id,
                    "attemptId": display_attempt_id,
                },
                workspace_root=root,
            )
            declaration = attempt / "declaration.json"
            declaration.write_text('{"declarationVersion":1}\n', encoding="utf-8")
            (attempt / "original.txt").write_text("Display\n", encoding="utf-8")
            declaration_sha = future_song_module.sha256_file(declaration)
            display_original_sha = hashlib.sha256(b"Display").hexdigest()
            display_artifacts = {
                "declarationSha256": declaration_sha,
                "originalSha256": display_original_sha,
                "sourcesSha256": sources_sha,
                "sidecarSha256": sidecar_sha,
                "childSha256": child_sha,
                "artworkSha256": artwork_sha,
                "mappingArtifactSha256": mapping_identity["mappingArtifactSha256"],
                "alignmentRunSha256": alignment_run_sha,
            }
            report_path.write_text(
                json.dumps(
                    {
                        "generatedAt": generated_at,
                        "result": "pass",
                        "stage": "display-review",
                        "videoId": video_id,
                        "policyVersion": editorial_review.POLICY_VERSION,
                        "editorialSha256": editorial_sha,
                        "sourcesSha256": sources_sha,
                        "sidecarSha256": sidecar_sha,
                        "childSha256": child_sha,
                        "artworkSha256": artwork_sha,
                        "lyricsTiming": lyrics_timing,
                        "retainedAnalysisAudio": retained_audio,
                        "temporaryAudioRetained": True,
                        "displayReviewAttemptId": display_attempt_id,
                        "displayReviewDeclarationSha256": declaration_sha,
                        "displayOriginalSha256": display_original_sha,
                        "mappingSha256": mapping_identity["mappingSha256"],
                        "alignmentRunId": alignment_run_id,
                        "alignmentRunSha256": alignment_run_sha,
                        "alignmentArtifacts": alignment_artifacts,
                        "displayReviewArtifacts": display_artifacts,
                        "runId": display_attempt_id,
                        "runSha256": display_run_sha,
                    }
                ),
                encoding="utf-8",
            )
            report_sha = future_song_module.sha256_file(report_path)
            initial_job = workflow._base_job(video_id, f'https://youtu.be/{video_id}')
            workflow._save_job(initial_job)
            initial_job['state'] = 'WAITING_FOR_EDITORIAL'
            workflow._save_job(initial_job)
            job = {
                **initial_job,
                "videoId": video_id,
                "state": "VALIDATED",
                "policyVersion": editorial_review.POLICY_VERSION,
                "policy": initial_job['policy'],
                "displayReview": {
                    "attemptId": display_attempt_id,
                    "declarationSha256": declaration_sha,
                    "preReviewTextSha256": display_original_sha,
                    "alignmentRunId": alignment_run_id,
                    "alignmentRunSha256": alignment_run_sha,
                    "result": "accepted-display-only",
                    "originalSha256": display_original_sha,
                    "runId": display_attempt_id,
                    "runSha256": display_run_sha,
                },
                "validation": {
                    "report": str(report_path),
                    "reportSha256": report_sha,
                    "editorialSha256": editorial_sha,
                    "sourcesSha256": sources_sha,
                    "sidecarSha256": sidecar_sha,
                    "childSha256": child_sha,
                    "artworkSha256": artwork_sha,
                    "validatedAt": generated_at,
                    "lyricsTiming": lyrics_timing,
                    "displayReviewAttemptId": display_attempt_id,
                    "displayReviewDeclarationSha256": declaration_sha,
                    "displayOriginalSha256": display_original_sha,
                    "alignmentRunId": alignment_run_id,
                    "alignmentRunSha256": alignment_run_sha,
                    "runId": display_attempt_id,
                    "runSha256": display_run_sha,
                },
            }
            workflow._save_job(job)
            workflow._editorial_sha = Mock(return_value=editorial_sha)
            workflow._lexical_mapping = Mock(return_value=({}, mapping_identity))
            workflow.report_store.report_authority_matches = Mock(return_value=True)
            workflow.report_store.immutable_run_matches = Mock(return_value=True)
            workflow._pipeline = Mock(return_value=object())
            workflow._completed_analysis_audio = Mock(
                return_value={
                    **retained_audio,
                    "artworkSha256": artwork_sha,
                    "path": root / "analysis.wav",
                }
            )
            workflow._validate_drafts = Mock(
                side_effect=AssertionError("snapshot checks must not compile drafts")
            )

            if prediction_method:
                import stemwise_engine.endpoint_candidate_prediction as prediction
                review_id = 'e'*32
                endpoint = future_song_module.create_manifested_directory(job_dir / 'endpoint-review',
                    workflow._endpoint_review_attempt_dir(video_id, review_id), owner={
                        'kind': 'endpoint-review-attempt', 'videoId': video_id, 'attemptId': review_id},
                    workspace_root=root)
                binding = {'method': prediction_method, 'manifestSha256': '7'*64}
                future_song_module.atomic_write_json(endpoint / 'declaration.json', {})
                future_song_module.atomic_write_json(endpoint / 'review.json', {'audioEvidence': binding})
                pointer = {'attemptId': review_id, 'result': 'accepted',
                    'declarationSha256': future_song_module.sha256_file(endpoint / 'declaration.json'),
                    'reviewSha256': future_song_module.sha256_file(endpoint / 'review.json'),
                    'sourceAlignmentRunId': alignment_run_id, 'sourceAlignmentRunSha256': alignment_run_sha}
                job['endpointReview'] = pointer
                report = json.loads(report_path.read_bytes())
                report['lyricsTiming']['endpointReview'] = pointer
                future_song_module.atomic_write_json(report_path, report)
                job['validation']['lyricsTiming'] = report['lyricsTiming']
                job['validation']['reportSha256'] = future_song_module.sha256_file(report_path)
                verifier = patch.object(prediction, 'verify_stored_packet', return_value={})
                verify = verifier.start()
                self.addCleanup(verifier.stop)
                self.assertTrue(workflow._validated_snapshot_is_current(job),
                                'a supported prediction must remain current without baseline realignment')
                verify.assert_called_once_with(endpoint, binding)
                verify.side_effect = ValueError('changed acoustic packet')
                self.assertFalse(workflow._validated_snapshot_is_current(job))
                verify.side_effect = None
            self.assertTrue(workflow._validated_snapshot_is_current(job))
            read_child.return_value = (
                child_document,
                b"changed cover bytes",
            )
            self.assertFalse(workflow._validated_snapshot_is_current(job))
            read_child.return_value = (child_document, cover)
            missing_artwork = job["validation"].pop("artworkSha256")
            self.assertFalse(workflow._validated_snapshot_is_current(job))
            job["validation"]["artworkSha256"] = missing_artwork
            child.write_bytes(b"mutated child")
            self.assertFalse(workflow._validated_snapshot_is_current(job))
            child.write_bytes(b"validated child")
            workflow._editorial_sha.return_value = "f" * 64
            self.assertFalse(workflow._validated_snapshot_is_current(job))

            workflow._editorial_sha.return_value = editorial_sha
            missing_binding = job["validation"].pop("sourcesSha256")
            self.assertFalse(workflow._validated_snapshot_is_current(job))
            job["validation"]["sourcesSha256"] = missing_binding
            job["state"] = "DELIVERED"
            job["delivery"] = {
                "path": str(delivered),
                "sha256": child_sha,
                "bytes": delivered.stat().st_size,
                "deliveredAt": generated_at,
                "readyToImport": True,
                "reviewEvidenceRetained": True,
                "runId": "9" * 32,
                "runSha256": "8" * 64,
            }
            workflow._save_job(job)
            workflow._completed_analysis_audio.reset_mock()
            temporary_root = workflow.analysis_temp
            retained = future_song_module.create_manifested_directory(
                temporary_root,
                temporary_root / video_id,
                owner={"kind": "analysis-temporary", "videoId": video_id},
                workspace_root=root,
            )
            (retained / "original.wav").write_bytes(b"retained pcm")
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            self.assertTrue(
                workflow._validated_snapshot_is_current(
                    job,
                    require_delivery=True,
                    allow_pending_delivery_cleanup=True,
                )
            )
            self.assertTrue(attempt.is_dir())
            workflow._remove_retained_analysis_audio(video_id)
            self.assertTrue(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            body_path = attempt / "original.txt"
            body = body_path.read_bytes()
            body_path.unlink()
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True),
                "an intact package cannot replace its required accepted review",
            )
            body_path.write_bytes(body)
            self.assertTrue(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            job["delivery"]["reviewEvidenceRetained"] = False
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True),
                "changing the retention flag cannot waive review validation",
            )
            job["delivery"]["reviewEvidenceRetained"] = True
            delivered.write_bytes(b"mutated delivery")
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            workflow._completed_analysis_audio.assert_not_called()
            workflow._validate_drafts.assert_not_called()

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_delivers_only_current_snapshot_and_repeated_call_is_no_op(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.test_results = root / "test-results"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"validated package")
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {
                    "validatedAt": "2026-07-31T00:00:00Z",
                    "childSha256": future_song_module.sha256_file(source),
                },
            }
            workflow._load_job = Mock(side_effect=lambda value: job)
            workflow._validated_snapshot_is_current = Mock(return_value=True)
            workflow.validate = Mock(
                side_effect=AssertionError("Finalize must not invoke Validate")
            )
            workflow._runtime_config = Mock(return_value={})
            workflow.report_store.commit_finalization_run = Mock(
                return_value=("f" * 32, "e" * 64)
            )
            workflow._windows_path = lambda path: str(path)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            display_review = root / "display-review" / "accepted" / "original.txt"
            endpoint_review = root / "endpoint-review" / "accepted" / "review.json"
            display_review.parent.mkdir(parents=True)
            endpoint_review.parent.mkdir(parents=True)
            display_review.write_bytes(b"accepted display\n")
            endpoint_review.write_bytes(b'{"outcome":"resolved"}\n')
            workflow.summary = Mock(
                side_effect=lambda current: {
                    "videoId": current["videoId"],
                    "state": current["state"],
                }
            )

            with patch('stemwise_engine.packages.validate_child_zip', return_value={}):
                first = workflow.finalize(video_id)
            delivered = workflow.ready / source.name
            self.assertEqual(first["state"], "DELIVERED")
            self.assertTrue(delivered.is_file())
            self.assertEqual(delivered.read_bytes(), source.read_bytes())
            self.assertEqual(display_review.read_bytes(), b"accepted display\n")
            self.assertEqual(endpoint_review.read_bytes(), b'{"outcome":"resolved"}\n')
            first_delivery = dict(job["delivery"])
            workflow.validate.assert_not_called()
            self.assertEqual(workflow._save_job.call_count, 2)
            workflow._save_job.assert_called_with(job)
            self.assertNotIn("nativePublication", job)

            workflow._save_job.reset_mock()
            workflow._write_task.reset_mock()
            with patch(
                'stemwise_engine.packages.validate_child_zip',
                side_effect=AssertionError("repeated Finalize must not revalidate package"),
            ):
                second = workflow.finalize(video_id)
            self.assertEqual(second["state"], "DELIVERED")
            self.assertEqual(job["delivery"], first_delivery)
            workflow.validate.assert_not_called()
            workflow._save_job.assert_not_called()
            workflow._write_task.assert_called_once_with(job)
            self.assertEqual(
                workflow._validated_snapshot_is_current.call_args_list[-1].kwargs,
                {"require_delivery": True},
            )

    def test_finalization_run_requires_its_bound_validation_checkpoint(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            report_root = root / "reports" / video_id
            run_id = "f" * 32
            run_root = report_root / "runs" / run_id
            run_root.mkdir(parents=True)
            requirements = {
                "validationReportSha256": "1" * 64,
                "validationRunId": "2" * 32,
                "validationRunSha256": "3" * 64,
                "alignmentRunId": "4" * 32,
                "alignmentRunSha256": "5" * 64,
                "childSha256": "6" * 64,
                "policyHashes": {"schemaSha256": "7" * 64},
            }
            checkpoint = {
                **requirements,
                "configurationSha256": "8" * 64,
                "helperSha256": {
                    "common.py": "9" * 64,
                    "future_song.py": "a" * 64,
                    "packages.py": "b" * 64,
                },
            }
            artifacts = {
                "workspaceRelativePath": f"Ready to Import/{video_id}.stemwise.zip",
                "sha256": requirements["childSha256"],
            }

            def write_record(value):
                raw = json.dumps(
                    value["checkpointKey"],
                    ensure_ascii=True,
                    allow_nan=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode("utf-8")
                value["checkpointKeySha256"] = hashlib.sha256(raw).hexdigest()
                (run_root / "run.json").write_text(
                    json.dumps(value), encoding="utf-8"
                )
                return future_song_module.sha256_file(run_root / "run.json")

            record = {
                "reportVersion": future_song_module.PRIVATE_REPORT_VERSION,
                "runId": run_id,
                "videoId": video_id,
                "operation": "package-finalization",
                "status": "complete",
                "checkpointKey": checkpoint,
                "schemaValidation": {
                    "result": "pass",
                    "externalFormatVersion": 2,
                    "schemaSha256": "7" * 64,
                },
                "artifacts": artifacts,
            }
            run_sha = write_record(record)
            workflow.report_store.report_root = Mock(return_value=report_root)
            workflow.report_store.store.artifacts_match = Mock(return_value=True)

            self.assertTrue(
                workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=run_id,
                    run_sha256=run_sha,
                    operation="package-finalization",
                    artifacts=artifacts,
                    schema_sha256="7" * 64,
                    require_current_pointer=False,
                    checkpoint_requirements=requirements,
                )
            )
            wrong = {**requirements, "validationRunId": "0" * 32}
            self.assertFalse(
                workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=run_id,
                    run_sha256=run_sha,
                    operation="package-finalization",
                    artifacts=artifacts,
                    schema_sha256="7" * 64,
                    require_current_pointer=False,
                    checkpoint_requirements=wrong,
                )
            )
            # An unrecognized retained-review label is rejected for every producer.
            record['artifacts'] = artifacts | {'reviewEvidenceRetained': True}
            run_sha = write_record(record)
            self.assertFalse(workflow.report_store.immutable_run_matches(video_id, run_id=run_id,
                run_sha256=run_sha, operation='package-finalization', artifacts=artifacts,
                schema_sha256='7'*64, require_current_pointer=False, checkpoint_requirements=requirements))
            record['artifacts'] = artifacts
            del checkpoint["helperSha256"]["packages.py"]
            run_sha = write_record(record)
            self.assertFalse(
                workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=run_id,
                    run_sha256=run_sha,
                    operation="package-finalization",
                    artifacts=artifacts,
                    schema_sha256="7" * 64,
                    require_current_pointer=False,
                    checkpoint_requirements=requirements,
                )
            )

    def test_finalization_run_identity_covers_validation_checkpoint(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            workflow.report_store.ensure_report_identity = Mock(return_value=root / "reports")
            workflow.report_store.store.commit = Mock(return_value="e" * 64)
            destination = root / "Ready to Import" / f"{video_id}.stemwise.zip"
            destination.parent.mkdir(parents=True)
            destination.write_bytes(b"package")
            job = {
                "videoId": video_id,
                "policy": {"schemaSha256": "7" * 64},
                "validation": {
                    "reportSha256": "1" * 64,
                    "runId": "2" * 32,
                    "runSha256": "3" * 64,
                    "alignmentRunId": "4" * 32,
                    "alignmentRunSha256": "5" * 64,
                },
            }
            with patch('stemwise_engine.future_song.sha256_file', return_value="8" * 64):
                first, _ = workflow.report_store.commit_finalization_run(
                    job, destination=destination, package_sha256="6" * 64
                )
                repeated, _ = workflow.report_store.commit_finalization_run(
                    job, destination=destination, package_sha256="6" * 64
                )
                job["validation"]["reportSha256"] = "9" * 64
                changed, _ = workflow.report_store.commit_finalization_run(
                    job, destination=destination, package_sha256="6" * 64
                )

            self.assertEqual(first, repeated)
            self.assertNotEqual(first, changed)
            job.update(state='DELIVERED', delivery={})
            with patch('stemwise_engine.future_song.sha256_file', return_value='8' * 64):
                workflow.report_store.commit_finalization_run(job, destination=destination, package_sha256='6' * 64)
            self.assertIs(workflow.report_store.store.commit.call_args.args[1]['artifacts']['reviewEvidenceRetained'], True)

    def test_status_never_projects_an_unverified_delivery(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        job = {"videoId": "KbKqkR3Q6SA", "state": "DELIVERED"}
        workflow.summary = Mock(
            return_value={"state": "DELIVERED", "delivery": {"sha256": "a" * 64}}
        )
        workflow._validated_snapshot_is_current = Mock(return_value=False)

        self.assertEqual(
            workflow._status_summary(job),
            {
                "state": "STATUS_ERROR",
                "sourceState": "DELIVERED",
                "statusError": "delivery-integrity-failed",
            },
        )
        workflow._validated_snapshot_is_current.assert_called_once_with(
            job,
            require_delivery=True,
            allow_pending_delivery_cleanup=True,
        )

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_resumes_at_each_durable_boundary(self):
        for boundary in ("destination", "finalization-pointer", "job", "task"):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                workflow = private_finalization_fixture(root)
                workflow.runtime = root / "runtime"
                workflow.ready = root / "ready"
                workflow.history = root / "history"
                workflow.config.update(tempDir=str(root / "temporary-analysis"))
                source = workflow.runtime / "children" / "KbKqkR3Q6SA.stemwise.zip"
                source.parent.mkdir(parents=True)
                source.write_bytes(b"validated package")
                initial_job = {
                    "videoId": "KbKqkR3Q6SA",
                    "state": "VALIDATED",
                    "validation": {
                        "childSha256": future_song_module.sha256_file(source)
                    },
                }
                stored = {"job": copy.deepcopy(initial_job)}
                retained = {"present": True}
                interrupted = False
                run_id = "f" * 32
                run_sha = "e" * 64
                pointer = report_control_path(root / "reports", "current-run.json")

                def load_job(_video_id):
                    return copy.deepcopy(stored["job"])

                def snapshot_is_current(
                    job,
                    *,
                    require_delivery=False,
                    allow_finalization_pointer=False,
                    allow_pending_delivery_cleanup=False,
                ):
                    del allow_finalization_pointer
                    if job["state"] == "VALIDATED":
                        return True
                    if require_delivery and retained["present"]:
                        return allow_pending_delivery_cleanup
                    return True

                def commit_run(_job, *, destination, package_sha256):
                    nonlocal interrupted
                    if boundary == "destination" and not interrupted:
                        interrupted = True
                        raise OSError("interrupted after destination")
                    future_song_module.atomic_write_json(
                        pointer,
                        {
                            "runId": run_id,
                            "runSha256": run_sha,
                            "packageSha256": package_sha256,
                        },
                        root=root,
                        workspace_root=root,
                    )
                    self.assertEqual(destination.read_bytes(), source.read_bytes())
                    return run_id, run_sha

                def save_job(job):
                    nonlocal interrupted
                    if boundary == "finalization-pointer" and not interrupted:
                        interrupted = True
                        raise OSError("interrupted after finalization pointer")
                    stored["job"] = copy.deepcopy(job)

                def write_task(_job):
                    nonlocal interrupted
                    if boundary == "job" and not interrupted:
                        interrupted = True
                        raise OSError("interrupted after job")

                def remove_audio(_video_id):
                    nonlocal interrupted
                    if boundary == "task" and not interrupted:
                        interrupted = True
                        raise OSError("interrupted after task")
                    retained["present"] = False

                workflow._load_job = Mock(side_effect=load_job)
                workflow._validated_snapshot_is_current = Mock(
                    side_effect=snapshot_is_current
                )
                workflow._runtime_config = Mock(return_value={})
                workflow.report_store.commit_finalization_run = Mock(side_effect=commit_run)
                workflow._save_job = Mock(side_effect=save_job)
                workflow._write_task = Mock(side_effect=write_task)
                workflow._remove_retained_analysis_audio = Mock(
                    side_effect=remove_audio
                )
                workflow._windows_path = lambda path: str(path)
                workflow.summary = lambda job: {"state": job["state"]}

                with patch('stemwise_engine.packages.validate_child_zip', return_value={}):
                    with self.assertRaises(OSError):
                        workflow.finalize("KbKqkR3Q6SA")
                    self.assertTrue(interrupted, f"the {boundary} fault was not reached")
                    destination = workflow.ready / source.name
                    destination_bytes = destination.read_bytes()
                    destination_mtime = destination.stat().st_mtime_ns
                    pointer_bytes = pointer.read_bytes() if pointer.is_file() else None
                    result = workflow.finalize("KbKqkR3Q6SA")

                self.assertEqual(result, {"state": "DELIVERED"})
                self.assertEqual(stored["job"]["state"], "DELIVERED")
                self.assertEqual(stored["job"]["delivery"]["runId"], run_id)
                self.assertFalse(retained["present"])
                self.assertEqual(destination.read_bytes(), destination_bytes)
                self.assertEqual(destination.stat().st_mtime_ns, destination_mtime)
                if pointer_bytes is not None:
                    self.assertEqual(pointer.read_bytes(), pointer_bytes)

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_retry_reuses_one_deterministic_rollback_archive(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            destination = workflow.ready / source.name
            source.parent.mkdir(parents=True)
            destination.parent.mkdir(parents=True)
            source.write_bytes(b"new validated package")
            destination.write_bytes(b"old delivered package")
            source_sha = future_song_module.sha256_file(source)
            destination_sha = future_song_module.sha256_file(destination)
            rollback_id = hashlib.sha256(
                b"stemwise-delivery-rollback\0"
                + destination_sha.encode("ascii")
                + b"\0"
                + source_sha.encode("ascii")
            ).hexdigest()[:24]
            archived = (
                workflow.history
                / video_id
                / f"{rollback_id}.{destination.name}"
            )
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {"childSha256": source_sha},
            }
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(return_value=True)
            workflow._runtime_config = Mock(return_value={})
            workflow.report_store.commit_finalization_run = Mock(
                return_value=("f" * 32, "e" * 64)
            )
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow._remove_retained_analysis_audio = Mock()
            workflow._windows_path = lambda path: str(path)
            workflow.summary = lambda current: {"state": current["state"]}
            durable_replace = future_song_module.durable_replace
            interrupted = False

            def interrupt_after_archive(staged, target, **kwargs):
                nonlocal interrupted
                result = durable_replace(staged, target, **kwargs)
                if target == archived and not interrupted:
                    interrupted = True
                    raise OSError("interrupted after rollback archive")
                return result

            with (
                patch('stemwise_engine.packages.validate_child_zip', return_value={}),
                patch(
                    'stemwise_engine.original_delivery.durable_replace',
                    side_effect=interrupt_after_archive,
                ),
            ):
                with self.assertRaisesRegex(OSError, "after rollback archive"):
                    workflow.finalize(video_id)

            self.assertTrue(interrupted, "the rollback publication fault was not reached")

            self.assertEqual(destination.read_bytes(), b"old delivered package")
            self.assertEqual(archived.read_bytes(), b"old delivered package")
            self.assertEqual(list(archived.parent.iterdir()), [archived])

            with patch('stemwise_engine.packages.validate_child_zip', return_value={}):
                self.assertEqual(workflow.finalize(video_id), {"state": "DELIVERED"})
            self.assertEqual(destination.read_bytes(), b"new validated package")
            self.assertFalse(archived.exists())
            self.assertFalse(archived.parent.exists())

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_preserves_a_ready_destination_changed_during_promotion(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            destination = workflow.ready / source.name
            source.parent.mkdir(parents=True)
            destination.parent.mkdir(parents=True)
            source.write_bytes(b"new validated package")
            destination.write_bytes(b"old delivered package")
            source_sha = future_song_module.sha256_file(source)
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {"childSha256": source_sha},
            }
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(return_value=True)
            workflow._runtime_config = Mock(return_value={})
            durable_replace = future_song_module.durable_replace

            changed = False

            def change_destination_after_archive(staged, target, **kwargs):
                nonlocal changed
                result = durable_replace(staged, target, **kwargs)
                if target.parent == workflow.history / video_id:
                    changed = True
                    destination.write_bytes(b"out-of-band replacement")
                return result

            with (
                patch('stemwise_engine.packages.validate_child_zip', return_value={}),
                patch(
                    'stemwise_engine.original_delivery.durable_replace',
                    side_effect=change_destination_after_archive,
                ),
            ):
                with self.assertRaisesRegex(
                    ValueError, "changed while retaining rollback"
                ):
                    workflow.finalize(video_id)

            self.assertTrue(changed, "the out-of-band publication fault was not reached")
            self.assertEqual(destination.read_bytes(), b"out-of-band replacement")

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_terminal_finalize_preserves_cleanup_inputs_until_delivery_is_proven(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            job = {"videoId": "KbKqkR3Q6SA", "state": "DELIVERED"}
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(return_value=False)
            workflow._remove_retained_analysis_audio = Mock()

            with self.assertRaisesRegex(FutureSongError, "missing or changed"):
                workflow.finalize(job["videoId"])

            workflow._remove_retained_analysis_audio.assert_not_called()

    def test_finalize_blocks_stale_snapshot_without_running_validate_or_alignment(self):
        video_id = "KbKqkR3Q6SA"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        job = {
            "videoId": video_id,
            "state": "VALIDATED",
            "validation": {"validatedAt": "2026-07-31T00:00:00Z"},
        }
        workflow._load_job = Mock(return_value=job)
        workflow._validated_snapshot_is_current = Mock(return_value=False)
        workflow.validate = Mock(side_effect=AssertionError("Finalize must not invoke Validate"))
        workflow._align_future_lyrics = Mock(
            side_effect=AssertionError("Finalize must not invoke alignment")
        )
        workflow._save_job = Mock()
        workflow._write_task = Mock()

        with self.assertRaisesRegex(FutureSongError, "(?i)run Validate"):
            workflow.finalize(video_id)

        workflow.validate.assert_not_called()
        workflow._align_future_lyrics.assert_not_called()
        workflow._save_job.assert_not_called()
        workflow._write_task.assert_not_called()

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_scavenges_only_its_manifested_abandoned_stage(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            staging = workflow._finalization_staging_root()
            abandoned = future_song_module.create_manifested_directory(
                staging,
                staging / f"{video_id}-abandoned",
                owner={"kind": "package-finalization-staging", "videoId": video_id},
                workspace_root=root,
            )
            (abandoned / "delivery.stemwise.zip").write_bytes(b"partial")
            unmanifested = staging / f"{video_id}-empty-interruption"
            unmanifested.mkdir()
            foreign = future_song_module.create_manifested_directory(
                staging,
                staging / "AAAAAAAAAAA-foreign",
                owner={
                    "kind": "package-finalization-staging",
                    "videoId": "AAAAAAAAAAA",
                },
                workspace_root=root,
            )

            self.assertEqual(workflow._scavenge_finalization_staging(video_id), staging)
            self.assertFalse(abandoned.exists())
            self.assertFalse(unmanifested.exists())
            self.assertTrue(foreign.is_dir())

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_preflights_delivery_and_rollback_before_copying(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            destination = workflow.ready / source.name
            source.parent.mkdir(parents=True)
            destination.parent.mkdir(parents=True)
            source.write_bytes(b"new validated bytes")
            destination.write_bytes(b"protected delivery")
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {"childSha256": future_song_module.sha256_file(source)},
            }
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(return_value=True)

            with (
                patch(
                    'stemwise_engine.common.preflight_staging_space',
                    side_effect=OSError("insufficient peak space"),
                ) as preflight,
                patch('stemwise_engine.future_song.shutil.copy2') as copy,
            ):
                with self.assertRaisesRegex(OSError, "peak space"):
                    workflow.finalize(video_id)

            preflight.assert_called_once()
            self.assertEqual(
                preflight.call_args.kwargs,
                {
                    "candidate_bytes": source.stat().st_size,
                    "rollback_bytes": destination.stat().st_size,
                },
            )
            copy.assert_not_called()
            self.assertEqual(destination.read_bytes(), b"protected delivery")

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_terminal_finalize_removes_retained_pcm_only_after_durable_delivery(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            temporary_root = workflow.analysis_temp
            retained = future_song_module.create_manifested_directory(
                temporary_root,
                temporary_root / video_id,
                owner={"kind": "analysis-temporary", "videoId": video_id},
                workspace_root=root,
            )
            (retained / "original.wav").write_bytes(b"retained pcm")
            job = {"videoId": video_id, "state": "DELIVERED"}
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(
                side_effect=[False, True, True]
            )
            workflow._write_task = Mock()
            workflow.summary = Mock(return_value={"state": "DELIVERED"})

            self.assertEqual(workflow.finalize(video_id), {"state": "DELIVERED"})
            self.assertFalse(retained.exists())
            workflow._write_task.assert_called_once_with(job)
            self.assertEqual(
                workflow._validated_snapshot_is_current.call_args_list[-1].kwargs,
                {"require_delivery": True},
            )

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_terminal_finalize_task_failure_keeps_pcm_for_retry(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            temporary_root = workflow.analysis_temp
            retained = future_song_module.create_manifested_directory(
                temporary_root,
                temporary_root / video_id,
                owner={"kind": "analysis-temporary", "videoId": video_id},
                workspace_root=root,
            )
            (retained / "original.wav").write_bytes(b"retained pcm")
            job = {"videoId": video_id, "state": "DELIVERED"}
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(
                side_effect=[False, True, False, True, True]
            )
            workflow._write_task = Mock(
                side_effect=[OSError("task write failed"), None]
            )
            workflow.summary = Mock(return_value={"state": "DELIVERED"})

            with self.assertRaisesRegex(OSError, "task write failed"):
                workflow.finalize(video_id)
            self.assertTrue(retained.exists())

            self.assertEqual(workflow.finalize(video_id), {"state": "DELIVERED"})
            self.assertFalse(retained.exists())
            self.assertEqual(workflow._write_task.call_count, 2)

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_retry_finishes_retained_audio_cleanup(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"validated package")
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {"childSha256": future_song_module.sha256_file(source)},
            }
            cleanup = {"audio": True, "audio_attempts": 0}

            def snapshot_is_current(
                current,
                *,
                require_delivery=False,
                allow_finalization_pointer=False,
                allow_pending_delivery_cleanup=False,
            ):
                del allow_finalization_pointer
                if current["state"] == "VALIDATED":
                    return True
                if not require_delivery:
                    return allow_pending_delivery_cleanup
                return not cleanup["audio"] or allow_pending_delivery_cleanup

            def remove_audio(_video_id):
                cleanup["audio_attempts"] += 1
                if cleanup["audio_attempts"] == 1:
                    raise OSError("retained audio removal failed")
                cleanup["audio"] = False

            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(
                side_effect=snapshot_is_current
            )
            workflow._runtime_config = Mock(return_value={})
            workflow.report_store.commit_finalization_run = Mock(
                return_value=("f" * 32, "e" * 64)
            )
            workflow._windows_path = lambda path: str(path)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow._remove_retained_analysis_audio = Mock(side_effect=remove_audio)
            workflow.summary = Mock(return_value={"state": "DELIVERED"})

            with patch('stemwise_engine.packages.validate_child_zip', return_value={}):
                with self.assertRaisesRegex(OSError, "retained audio removal failed"):
                    workflow.finalize(video_id)
                self.assertEqual(cleanup["audio_attempts"], 1)
                result = workflow.finalize(video_id)

            self.assertEqual(result, {"state": "DELIVERED"})
            self.assertEqual(job["state"], "DELIVERED")
            self.assertFalse(cleanup["audio"])
            self.assertEqual(cleanup["audio_attempts"], 2)

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_rejects_source_replacement_after_snapshot_check(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.test_results = root / "test-results"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            destination = workflow.ready / source.name
            source.parent.mkdir(parents=True)
            destination.parent.mkdir(parents=True)
            source.write_bytes(b"validated revision")
            destination.write_bytes(b"protected delivery")
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {
                    "validatedAt": "2026-07-31T00:00:00Z",
                    "childSha256": future_song_module.sha256_file(source),
                },
            }
            workflow._load_job = Mock(return_value=job)

            def replace_after_check(_job):
                source.write_bytes(b"raced replacement")
                return True

            workflow._validated_snapshot_is_current = Mock(side_effect=replace_after_check)

            with self.assertRaisesRegex(FutureSongError, "changed after validation"):
                workflow.finalize(video_id)

            workflow._validated_snapshot_is_current.assert_called_once_with(job)
            self.assertEqual(source.read_bytes(), b"raced replacement")
            self.assertEqual(destination.read_bytes(), b"protected delivery")

    @patch('stemwise_engine.native_delivery.publish', new=lambda *_args: [])
    def test_finalize_validation_failure_cannot_replace_existing_delivery(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = private_finalization_fixture(root)
            workflow.runtime = root / "runtime"
            workflow.ready = root / "ready"
            workflow.test_results = root / "test-results"
            workflow.history = root / "history"
            workflow.config.update(tempDir=str(root / "temporary-analysis"))
            source = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            destination = workflow.ready / source.name
            source.parent.mkdir(parents=True)
            destination.parent.mkdir(parents=True)
            source.write_bytes(b"new validated bytes")
            destination.write_bytes(b"previous delivery")
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "validation": {
                    "validatedAt": "2026-07-31T00:00:00Z",
                    "childSha256": future_song_module.sha256_file(source),
                },
            }
            workflow._load_job = Mock(return_value=job)
            workflow._validated_snapshot_is_current = Mock(return_value=True)
            workflow._runtime_config = Mock(return_value={})
            workflow._save_job = Mock()
            workflow._write_task = Mock()

            with patch(
                'stemwise_engine.packages.validate_child_zip',
                side_effect=FutureSongError("staged package is invalid"),
            ) as validate_package:
                with self.assertRaisesRegex(FutureSongError, "staged package"):
                    workflow.finalize(video_id)

            validate_package.assert_called_once()
            self.assertEqual(destination.read_bytes(), b"previous delivery")
            self.assertFalse(workflow.history.exists())
            self.assertEqual(
                list(destination.parent.glob(f".{destination.name}.*.tmp")), []
            )
            workflow._save_job.assert_not_called()

    def test_validation_failure_does_not_relabel_pre_editorial_states(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.root = Path(name) / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.config = {"tempDir": str(Path(name) / "temporary-analysis")}
            workflow._save_job = Mock()
            workflow._write_task = Mock()

            for state in ("NEW", "ANALYZING", "ANALYSIS_FAILED", "NEEDS_LYRICS"):
                job = {"videoId": video_id, "state": state}
                workflow._load_job = Mock(return_value=job)
                result = workflow._mark_validation_failed_under_worker_lock(
                    video_id, "premature validate"
                )
                self.assertEqual(result["state"], state)

            workflow._save_job.assert_not_called()
            workflow._write_task.assert_not_called()

    def test_pre_alignment_failure_never_rewrites_the_previous_immutable_run(self):
        video_id = "KbKqkR3Q6SA"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        workflow._load_job = Mock(
            return_value={
                "videoId": video_id,
                "state": "VALIDATION_FAILED",
                "lastAlignmentAttemptId": "a" * 32,
            }
        )
        workflow.report_store.commit_alignment_run = Mock()

        workflow._commit_failed_alignment_attempt(video_id, "FutureSongError")

        workflow.report_store.commit_alignment_run.assert_not_called()

    def test_retry_resumes_editorial_setup_without_forcing_analysis(self):
        video_id = "KbKqkR3Q6SA"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        job = {
            "videoId": video_id,
            "state": "EDITORIAL_SETUP_FAILED",
        }
        workflow._load_job = Mock(return_value=job)
        workflow.acquisition = Mock(return_value=Mock())
        workflow._save_job = Mock()
        workflow._write_task = Mock()
        workflow.summary = Mock(return_value={"videoId": video_id})

        workflow.retry(video_id)

        workflow.acquisition.return_value.run.assert_called_once_with(job, force_analysis=False)

    def test_retry_for_analysis_failure_forces_analysis(self):
        video_id = "KbKqkR3Q6SA"
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        job = {
            "videoId": video_id,
            "state": "ANALYSIS_FAILED",
        }
        workflow._load_job = Mock(return_value=job)
        workflow.acquisition = Mock(return_value=Mock())
        workflow._save_job = Mock()
        workflow._write_task = Mock()
        workflow.summary = Mock(return_value={"videoId": video_id})

        workflow.retry(video_id)

        workflow.acquisition.return_value.run.assert_called_once_with(job, force_analysis=True)

    def test_status_makes_interrupted_new_or_analysis_resumable_without_forcing(self):
        video_id = "KbKqkR3Q6SA"
        for state in ("NEW", "ANALYZING"):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as name:
                workflow = workflow_fixture(Path(name))
                workflow.root = Path(name) / "future-songs"
                workflow.jobs = workflow.root / "jobs"
                job = {"videoId": video_id, "state": state}
                workflow._load_job = Mock(return_value=job)
                workflow._save_job = Mock()
                workflow._write_task = Mock()

                reconciled = workflow._reconcile_interrupted_validation(job)

                self.assertEqual(reconciled["state"], "ANALYSIS_FAILED")
                self.assertEqual(reconciled["error"], "worker-not-active")
                workflow.acquisition = Mock(return_value=Mock())
                workflow.summary = Mock(return_value={"videoId": video_id})
                workflow.retry(video_id)
                workflow.acquisition.return_value.run.assert_called_once_with(
                    job, force_analysis=False
                )

    def test_retry_resumes_failed_validation_without_restarting_analysis(self):
        video_id = "KbKqkR3Q6SA"
        for state in (
            "VALIDATION_FAILED",
            "AUTHORITY_UNRESOLVED",
            "LINE_UNRESOLVED",
            "RETRYABLE_TECHNICAL",
        ):
            with self.subTest(state=state), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                workflow = workflow_fixture(Path(name))
                workflow.root = workflow.install_root
                workflow.jobs = workflow.root / 'jobs'
                job = {"videoId": video_id, "state": state}
                future_song_module.atomic_write_json(workflow._job_path(video_id), job)
                workflow._load_job = Mock(
                    return_value=job
                )
                workflow._validate_action = Mock(return_value={"state": state})
                workflow._validate_authority_outcome = Mock(return_value=("resolved", {}, {}))
                workflow.acquisition = Mock(return_value=Mock())
                pipeline = object()
                workflow._pipeline = Mock(return_value=pipeline)
                workflow._completed_analysis_audio = Mock(return_value={})

                workflow._retry_action(video_id)

                workflow._validate_action.assert_called_once_with(video_id)
                workflow.acquisition.assert_not_called()
                workflow._completed_analysis_audio.assert_called_once_with(
                    pipeline, video_id
                )

                retained = json.loads(workflow._job_path(video_id).read_text(encoding='utf-8'))
                self.assertEqual(retained['state'], state)
                self.assertIsInstance(retained['updatedAt'], str)

    def test_retry_rebuilds_stale_analysis_without_discarding_editorial_setup(self):
        from test_pipeline_hardening import AnalysisRetryTests, VIDEO_ID
        import stemwise_engine.pipeline as pipeline_module
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            root = Path(name)
            pipeline, _item = AnalysisRetryTests().retained_rebuild_fixture(root)
            workflow = workflow_fixture(root)
            workflow.config = pipeline.config
            workflow.runtime = pipeline.output_dir
            workflow.jobs = root / 'jobs'
            workflow.owner = pipeline.lyrics_input_dir
            workflow.analysis_temp = pipeline.temp_root
            job = {'videoId': VIDEO_ID, 'state': 'VALIDATION_FAILED', 'title': 'retained song'}
            workflow._load_job = Mock(return_value=job)
            workflow._pipeline = Mock(return_value=pipeline)
            workflow._save_job = Mock()
            workflow._write_task = Mock()
            workflow._preflight_expensive_song_space = Mock(return_value={'requiredFreeBytes': 1})
            workflow._song_info = Mock(side_effect=AssertionError('must not reacquire source metadata'))
            draft = workflow._job_dir(VIDEO_ID) / 'drafts/original.txt'
            draft.parent.mkdir(parents=True)
            draft.write_bytes(b'Owner approved words')
            review = draft.parent.parent / 'agent-review.json'
            review.write_bytes(b'{"owner": "retained review bytes"}')
            sources = draft.parent.parent / 'sources.json'
            sources.write_bytes(json.dumps({'videoId': VIDEO_ID, 'containsLyrics': True,
                                           'owner': 'retained evidence bytes'}).encode('utf-8'))
            preserved = {path: path.read_bytes() for path in (draft, review, sources)}
            def validate(video_id):
                self.assertEqual(workflow._completed_analysis_audio(pipeline, video_id)['path'].read_bytes(), b'exact retained PCM')
                self.assertEqual({path: path.read_bytes() for path in preserved}, preserved)
                return {'state': 'WAITING_FOR_DISPLAY_REVIEW'}
            workflow._validate_action = Mock(side_effect=validate)
            with patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    patch.object(pipeline, '_analysis', return_value=__import__('test_packages').song_document(VIDEO_ID)['analysis']) as analysis, \
                    patch.object(pipeline, '_acquire_analysis_audio', side_effect=AssertionError('must reuse PCM')):
                self.assertEqual(workflow._retry_action(VIDEO_ID), {'state': 'WAITING_FOR_DISPLAY_REVIEW'})
            analysis.assert_called_once()
            workflow._song_info.assert_not_called()
            workflow._validate_action.assert_called_once_with(VIDEO_ID)
            self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)['provenanceSha256'], pipeline.provenance_sha256)
            # A process can stop after the pair commits but before the workflow leaves ANALYZING.
            job['state'] = 'ANALYSIS_FAILED'
            workflow.summary = lambda value: {'state': value['state']}
            with patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    patch.object(pipeline, '_analysis', side_effect=AssertionError('committed analysis must not rerun')):
                self.assertEqual(workflow._retry_action(VIDEO_ID), {'state': 'WAITING_FOR_EDITORIAL'})
            self.assertEqual({path: path.read_bytes() for path in preserved}, preserved)

    def test_retry_preserves_complete_analysis_pair_when_retained_pcm_is_missing_or_corrupt(self):
        from test_pipeline_hardening import AnalysisRetryTests, VIDEO_ID
        import stemwise_engine.pipeline as pipeline_module
        for damage in ('missing', 'corrupt'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as directory:
                root = Path(directory)
                pipeline, _item = AnalysisRetryTests().retained_rebuild_fixture(root)
                workflow = workflow_fixture(root)
                workflow.config = pipeline.config
                workflow.runtime, workflow.owner = pipeline.output_dir, pipeline.lyrics_input_dir
                workflow.analysis_temp, workflow.jobs = pipeline.temp_root, root / 'jobs'
                workflow._job_dir(VIDEO_ID).mkdir(parents=True)
                job = {'videoId': VIDEO_ID, 'state': 'VALIDATION_FAILED'}
                workflow._load_job = Mock(return_value=job)
                workflow._pipeline = Mock(return_value=pipeline)
                workflow._save_job = Mock()
                workflow._write_task = Mock()
                workflow._preflight_expensive_song_space = Mock(return_value={'requiredFreeBytes': 1})
                workflow._validate_action = Mock(side_effect=AssertionError('invalid PCM cannot reach timing validation'))
                child, checkpoint = pipeline.child_path(VIDEO_ID), pipeline.checkpoint_path(VIDEO_ID)
                previous = child.read_bytes(), checkpoint.read_bytes()
                pcm = pipeline.temp_root / VIDEO_ID / 'analysis.wav'
                if damage == 'missing':
                    pcm.unlink()
                else:
                    pcm.write_bytes(b'retained damaged PCM for inspection')
                retained = {path.name: path.read_bytes() for path in pcm.parent.iterdir()}
                first, second, third = AnalysisRetryTests().patches(pipeline)
                with first, second, third, patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                        patch.object(pipeline, '_acquire_analysis_audio', side_effect=AssertionError('must not reacquire PCM')) as acquire, \
                        patch.object(pipeline, '_analysis', side_effect=AssertionError('must not rerun analyzer')) as analyze:
                    with self.assertRaisesRegex(FutureSongError, 'PC analysis did not complete'):
                        workflow._retry_action(VIDEO_ID)
                acquire.assert_not_called()
                analyze.assert_not_called()
                workflow._validate_action.assert_not_called()
                self.assertEqual(job['state'], 'ANALYSIS_FAILED')
                self.assertEqual((child.read_bytes(), checkpoint.read_bytes()), previous)
                self.assertEqual({path.name: path.read_bytes() for path in pcm.parent.iterdir()}, retained)

    def test_editorial_fingerprint_frames_draft_and_review_bytes(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / "jobs"
            job = {"videoId": video_id}
            job_dir = workflow._job_dir(video_id)
            draft = job_dir / "drafts"
            draft.mkdir(parents=True)
            body = draft / "original.txt"
            review = job_dir / "agent-review.json"

            body.write_bytes(b"b")
            review.write_bytes(b" {}")
            first = workflow._editorial_sha(job)

            # The former unframed serialization produced the same byte stream
            # for these two file splits: original='b', review=' {}' versus
            # original='b ', review='{}'. Both reviews are valid JSON.
            body.write_bytes(b"b ")
            review.write_bytes(b"{}")
            second = workflow._editorial_sha(job)

            self.assertNotEqual(first, second)

    def test_review_inputs_exposes_interrupted_draft_layout_without_text_or_writes(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = Path(name) / "jobs"
            workflow.runtime = Path(name) / "runtime"
            job = {"videoId": "KbKqkR3Q6SA"}
            job_dir = workflow._job_dir(job["videoId"])
            draft = job_dir / "drafts"
            draft.mkdir(parents=True)
            (draft / "original.txt").write_bytes(b"Private alpha\n\nPrivate beta\n")
            (draft / "translation-en.txt").write_bytes(b"Private companion\n")
            future_song_module.atomic_write_json(draft/'lyrics.meta.json', {'videoId': job['videoId'],
                'originalLanguage': 'en', 'originalScript': 'Latn',
                'sources': {'original': {'name': 'Fixture original'}}})
            (job_dir / "sources.json").write_bytes(b"{}\n")
            checkpoint = workflow.runtime/'work/checkpoints'/f"{job['videoId']}.json"
            checkpoint.parent.mkdir(parents=True)
            future_song_module.atomic_write_json(checkpoint, {'originalAudio': {'sha256': '2'*64}})
            before = {path: path.read_bytes() for path in job_dir.rglob("*") if path.is_file()}
            with (
                patch.object(workflow, "_runtime_config", return_value={'outputDir': str(workflow.runtime)}),
                patch('stemwise_engine.future_song.read_child', return_value=({"metadata": {}}, None)),
                patch('stemwise_engine.future_song.metadata_sha256', return_value="3" * 64),
            ):
                result = workflow._review_inputs(job)
                companion = draft/'translation-en.txt'
                companion.write_bytes(b'\xff')
                malformed = workflow._review_inputs(job)
                self.assertEqual(malformed['plainDraftLayout']['translation-en.txt']['reason'], 'invalid-utf8')
                companion.unlink()
                companion.symlink_to(draft/'original.txt')
                unsafe = workflow._review_inputs(job)
                self.assertEqual(unsafe['plainDraftLayout']['translation-en.txt']['reason'], 'unsafe-or-unavailable-input')
                self.assertEqual(unsafe['plainDraftLayout']['original.txt'], result['plainDraftLayout']['original.txt'])
                companion.unlink()
                companion.write_bytes(b'Private companion\n')
            self.assertEqual(result["plainDraftLayout"], {
                "original.txt": {"nonblankLineCount": 2, "blankLineNumbers": [2]},
                "translation-en.txt": {"nonblankLineCount": 1, "blankLineNumbers": []},
            })
            self.assertNotIn("Private", json.dumps(result))
            self.assertEqual(before, {path: path.read_bytes() for path in job_dir.rglob("*") if path.is_file()})

    def test_new_uses_the_same_per_job_workflow_lock_as_other_actions(self):
        video_id = "KbKqkR3Q6SA"
        with tempfile.TemporaryDirectory() as name:
            workflow = workflow_fixture(Path(name))
            workflow.jobs = workflow.workspace_root / "jobs"
            workflow._save_job = Mock(wraps=workflow._save_job)
            workflow.report_store.ensure_report_identity = Mock()
            workflow.acquisition = Mock(return_value=Mock())
            workflow._write_task = Mock()
            workflow.summary = Mock(return_value={"videoId": video_id})

            with patch('stemwise_engine.future_song.exclusive_process_lock') as process_lock:
                workflow.new(f"https://youtu.be/{video_id}")

            process_lock.assert_called_once_with(
                workflow._job_dir(video_id) / "workflow.lock"
            )

    def test_future_orchestration_leaves_model_admission_to_the_pipeline(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name).resolve()
            workflow = workflow_fixture(root)
            workflow.config = {"projectDir": str(root)}
            pipeline = Mock()
            from stemwise_engine.common import heavy_worker_lock_path, exclusive_process_lock
            lock_path = heavy_worker_lock_path(workflow.config)

            with exclusive_process_lock(lock_path):
                workflow._process_song(pipeline, {"videoId": "KbKqkR3Q6SA"})

            pipeline.process_song.assert_called_once_with({'videoId': 'KbKqkR3Q6SA'})


class AuthorityTerminalCleanupTests(unittest.TestCase):
    def test_authority_retry_only_reacquires_audio_after_resolved_review(self):
        for outcome in ("pending", "authority-unresolved", "resolved"):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                workflow = workflow_fixture(Path(name).resolve())
                video_id = "KbKqkR3Q6SA"
                job = {"videoId": video_id, "state": "AUTHORITY_UNRESOLVED"}
                workflow.root = workflow.install_root
                workflow.jobs = workflow.root / 'jobs'
                future_song_module.atomic_write_json(workflow._job_path(video_id), job)
                workflow._load_job = Mock(return_value=job)
                workflow._validate_authority_outcome = Mock(return_value=(outcome, {}, {}))
                workflow._validate_action = Mock(return_value={"outcome": outcome})
                workflow._pipeline = Mock()
                workflow._completed_analysis_audio = Mock(side_effect=FutureSongError("no audio"))
                workflow.acquisition = Mock(return_value=Mock())
                workflow._save_job = Mock(wraps=workflow._save_job)
                workflow._write_task = Mock()

                self.assertEqual(workflow.retry(video_id), {"outcome": outcome})

                workflow._validate_action.assert_called_once_with(video_id)
                if outcome == "resolved":
                    workflow._pipeline.assert_called_once_with()
                    workflow.acquisition.return_value.run.assert_called_once_with(job, force_analysis=True)
                    self.assertEqual(workflow._save_job.call_count, 2)
                    workflow._write_task.assert_called_once_with(job)
                else:
                    workflow._pipeline.assert_not_called()
                    workflow._completed_analysis_audio.assert_not_called()
                    workflow.acquisition.assert_not_called()
                    workflow._save_job.assert_called_once_with(job)
                    workflow._write_task.assert_not_called()

                retained = json.loads(workflow._job_path(video_id).read_text(encoding='utf-8'))
                self.assertEqual(retained['state'], 'AUTHORITY_UNRESOLVED')
                self.assertIsInstance(retained['updatedAt'], str)

    def test_authority_unresolved_preserves_completed_pcm_and_other_audio(self):
        for failure in (None, "run", "job", "task"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as name:
                root = Path(name).resolve()
                workflow = workflow_fixture(root)
                video_id = "KbKqkR3Q6SA"
                retained = future_song_module.create_manifested_directory(
                    workflow.analysis_temp, workflow.analysis_temp / video_id,
                    owner={"kind": "analysis-temporary", "videoId": video_id}, workspace_root=root)
                pcm = retained / "analysis.wav"
                pcm.write_bytes(b"owned pcm")
                unrelated = workflow.analysis_temp / "unrelated.wav"
                unrelated.write_bytes(b"unrelated pcm")
                report = root / "authority.json"
                report.write_text("{}", encoding="utf-8")
                durable = workflow._base_job(video_id, f'https://youtu.be/{video_id}')
                workflow._save_job(durable)
                durable['state'] = 'WAITING_FOR_EDITORIAL'
                workflow._save_job(durable)
                save_job = workflow._save_job
                workflow._validated_snapshot_is_current = Mock(return_value=False)
                workflow._validate_authority_outcome = Mock(return_value=("authority-unresolved", {}, {}))
                workflow._windows_path = str
                def commit(stage, job=None):
                    self.assertEqual(pcm.read_bytes(), b"owned pcm")
                    if failure == stage:
                        raise FutureSongError("injected " + stage)
                    if stage == "job":
                        save_job(job)
                    return "a" * 32, "b" * 64, report
                workflow.report_store.commit_authority_unresolved_run = lambda job, **_kwargs: commit("run")
                workflow._save_job = lambda job: commit("job", job)
                workflow._write_task = lambda job: commit("task")
                workflow._remove_retained_analysis_audio = Mock(side_effect=AssertionError("Review can reopen"))
                workflow._commit_failed_alignment_attempt = Mock()
                workflow._record_validation_failure = Mock()
                with self.assertRaises(FutureSongError) as raised:
                    workflow._validate_enhancements_action(video_id)
                if failure:
                    self.assertIn("injected " + failure, str(raised.exception))
                else:
                    self.assertIsInstance(raised.exception, future_song_module.FutureSongOutcomeError)
                self.assertEqual(workflow._load_job(video_id)['state'],
                    'WAITING_FOR_EDITORIAL' if failure in {'run', 'job'} else 'AUTHORITY_UNRESOLVED')
                self.assertEqual(pcm.read_bytes(), b"owned pcm")
                self.assertEqual(unrelated.read_bytes(), b"unrelated pcm")
                workflow._remove_retained_analysis_audio.assert_not_called()



class PrimarySourceRecoveryTests(unittest.TestCase):
    def test_validated_source_uses_durable_transition_and_failed_request_preserves_delivery(self):
        for fail in (False, True):
            with self.subTest(provider_failure=fail), tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work') as name:
                workflow, job, job_dir = self.fixture(Path(name))
                job['state'] = 'VALIDATED'
                path = job_dir/'job.json'
                path.write_text(json.dumps(job), encoding='utf-8')
                previous = path.read_bytes()
                delivery = job_dir/'completed.zip'
                delivery.write_bytes(b'previous completed delivery')
                workflow._save_job = FutureSongWorkflow._save_job.__get__(workflow)
                def provider(item, destination, **kwargs):
                    if fail: raise OSError('provider unavailable')
                    return self.acquire(item, destination)
                with patch.object(future_song_module.youtube, 'fetch_video_info', return_value={'id': job['videoId']}), \
                        patch.object(source_acquisition, 'collect_normal_youtube_candidates', return_value={'records': []}), \
                        patch.object(source_acquisition, 'acquire_exact_item_draft', side_effect=provider):
                    result = workflow.add_lyric_source(job['videoId'], 'https://youtu.be/'+job['videoId'])
                durable = json.loads(path.read_bytes())
                if fail:
                    self.assertEqual(path.read_bytes(), previous)
                    self.assertEqual(result['sourceAction']['result'], 'no-usable-body')
                else:
                    self.assertEqual(durable['state'], 'WAITING_FOR_EDITORIAL')
                    self.assertNotIn('validation', durable)
                self.assertEqual(delivery.read_bytes(), b'previous completed delivery')
                workflow._pipeline.assert_not_called()


    def test_normal_captions_cannot_hide_a_skipped_primary_music_acquisition(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow, job, job_dir = self.fixture(Path(name))
            sources_path = job_dir / 'sources.json'
            sources = json.loads(sources_path.read_bytes())
            caption = job_dir / 'normal-lyrics.txt'
            caption.write_bytes(b'private normal video lyric candidate\n')
            sources['lyricEvidenceSources'][0]['normalYouTube'] = {'records': [{
                'path': str(caption), 'sha256': future_song_module.sha256_file(caption),
                'lyricUse': 'potential-body'}]}
            sources_path.write_text(json.dumps(sources), encoding='utf-8')
            before = {p.name: p.read_bytes() for p in (job_dir / 'drafts').iterdir()}
            with patch.object(future_song_module.youtube, 'fetch_video_info', return_value={'id': job['videoId']}), \
                    patch.object(source_acquisition, 'collect_normal_youtube_candidates',
                        side_effect=AssertionError('intact normal candidates must not be rewritten')), \
                    patch.object(source_acquisition, 'acquire_exact_item_draft', side_effect=self.acquire) as acquire:
                result = workflow.add_lyric_source(job['videoId'], f'https://music.youtube.com/watch?v={job["videoId"]}')
            self.assertEqual(acquire.call_count, 1)
            self.assertEqual(result['sourceAction']['result'], 'restaged')
            current = json.loads(sources_path.read_bytes())['lyricEvidenceSources'][0]
            self.assertEqual(current['youtubeMusic']['result']['decision'], 'acquired')
            self.assertEqual(len(current['normalYouTube']['records']), 1)
            self.assertEqual(current['normalYouTube']['records'][0]['sha256'],
                future_song_module.sha256_file(caption))
            self.assertEqual(current['normalYouTube']['records'][0]['lyricUse'], 'potential-body')
            self.assertEqual({p.name: p.read_bytes() for p in (job_dir / 'drafts').iterdir()}, before)
            self.assertEqual(caption.read_bytes(), b'private normal video lyric candidate\n')
            self.assertEqual(json.loads((job_dir / 'agent-review.json').read_bytes())['authorityOutcome'], 'pending')
            workflow._pipeline.assert_not_called()

    def test_partially_damaged_source_is_repaired_and_intact_candidate_is_retained(self):
        for damage in ('missing', 'changed'):
            with self.subTest(damage=damage), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                workflow, job, job_dir = self.fixture(Path(name))
                paths = [job_dir / f'candidate-{i}.txt' for i in range(2)]
                for path in paths:
                    path.write_bytes(b'intact lyric candidate')
                records = [{'path': str(path), 'sha256': future_song_module.sha256_file(path),
                            'lyricUse': 'potential-body', 'type': 'description'} for path in paths]
                source_path = job_dir / 'sources.json'
                sources = json.loads(source_path.read_bytes())
                sources['lyricEvidenceSources'][0]['youtubeMusic']['result'] = {'decision': 'not-found'}
                sources['lyricEvidenceSources'][0]['normalYouTube']['records'] = records
                source_path.write_text(json.dumps(sources), encoding='utf-8')
                if damage == 'missing': paths[1].unlink()
                else: paths[1].write_bytes(b'changed evidence')
                def repair(*args, **kwargs):
                    paths[1].write_bytes(b'intact lyric candidate')
                    return {'records': [records[1]]}
                with patch.object(future_song_module.youtube, 'fetch_video_info', return_value={'id': job['videoId']}), \
                        patch.object(source_acquisition, 'collect_normal_youtube_candidates', side_effect=repair) as fetch, \
                        patch.object(source_acquisition, 'acquire_exact_item_draft', return_value={'decision': 'not-found'}):
                    result = workflow.add_lyric_source(job['videoId'], 'https://youtu.be/'+job['videoId'])
                self.assertEqual(fetch.call_count, 1)
                self.assertEqual(result['sourceAction']['result'], 'restaged')
                current = json.loads(source_path.read_bytes())['lyricEvidenceSources'][0]
                self.assertIn(records[0], current['normalYouTube']['records'])
                self.assertEqual(len(current['normalYouTube']['records']), 2)
                self.assertEqual(current['normalYouTube']['candidateCount'], 2)
                self.assertEqual(current['normalYouTube']['usableLyricCandidateCount'], 2)
                self.assertEqual(paths[0].read_bytes(), b'intact lyric candidate')
                self.assertEqual(json.loads((job_dir/'agent-review.json').read_bytes())['authorityOutcome'], 'pending')
                with patch.object(future_song_module.youtube, 'fetch_video_info', side_effect=AssertionError('complete is a no-op')):
                    self.assertEqual(workflow.add_lyric_source(job['videoId'], 'https://youtu.be/'+job['videoId'])['sourceAction']['result'], 'already-staged')

    def fixture(self, root):
        workflow = workflow_fixture(root)
        workflow.jobs = root / 'jobs'
        video_id = 'KbKqkR3Q6SA'
        job_dir = workflow._job_dir(video_id)
        draft = job_dir / 'drafts'
        draft.mkdir(parents=True)
        for name, raw in {'original.txt': b'owner draft\n', 'translation-en.txt': b'owner translation\n',
                          'transliteration-ipa.txt': b'owner IPA\n',
                          'lyrics.meta.json': b'{"originalLanguage":"en"}\n'}.items():
            (draft / name).write_bytes(raw)
        source = {'videoId': video_id, 'relationshipToPackage': 'exact-package-id',
                  'associationStatus': 'exact-package-id',
                  'youtubeMusic': {'result': {'decision': 'skipped-existing'}},
                  'normalYouTube': {'records': []}}
        (job_dir / 'sources.json').write_text(json.dumps({'videoId': video_id,
            'lyricEvidenceSources': [source]}), encoding='utf-8')
        (job_dir / 'agent-review.json').write_text(json.dumps({'authorityOutcome': 'accepted',
            'youtubeSurfaceVariantsReviewed': True, 'publicSourceResearchComplete': True,
            'unresolvedMaterialIssues': []}), encoding='utf-8')
        job = {'videoId': video_id, 'state': 'WAITING_FOR_EDITORIAL',
               'recordingInput': {'recordingKey': 'qobuz:12', 'sha256': '1' * 64},
               'validation': {'stale': True}}
        workflow._load_job = Mock(return_value=job)
        workflow._save_job = Mock()
        workflow._write_task = Mock()
        workflow.summary = Mock(side_effect=lambda _job: {'videoId': video_id, 'state': _job['state']})
        workflow._pipeline = Mock(side_effect=AssertionError('Source must not acquire or analyze audio'))
        return workflow, job, job_dir

    def acquire(self, item, destination, **kwargs):
        raw = b'provider lyric candidate\n'
        destination.mkdir(parents=True, exist_ok=True)
        (destination / 'original.txt').write_bytes(raw)
        return {'videoId': item['videoId'], 'decision': 'acquired',
                'sha256': hashlib.sha256(raw).hexdigest()}

    def test_metadata_only_primary_source_is_reacquired_without_changing_audio_or_drafts(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow, job, job_dir = self.fixture(Path(name))
            review_path = job_dir / 'agent-review.json'
            approved = workflow_review.review_template(job['videoId'])
            approved.update(authorityOutcome='resolved', reviewer='Fixture reviewer',
                completedAt='2026-10-15T00:00:00Z', sourceDisposition='accepted-exact-recording',
                recordingOrSourceAssociationReviewed=True, youtubeSurfaceVariantsReviewed=True,
                publicSourceResearchComplete=True, originalReviewed=True, unresolvedMaterialIssues=[])
            old_sources_sha = future_song_module.sha256_file(job_dir / 'sources.json')
            approved['recordingReview'].update(sourcesSha256=old_sources_sha,
                recordingCompared=True, versionSpecificWordingReviewed=True, performedOrderReviewed=True)
            approved['metadataReview'].update(sourcesSha256=old_sources_sha, recordingIdentityConfirmed=True)
            self.assertEqual(validate_agent_review(approved, video_id=job['videoId'],
                available_lyric_source_ids={job['videoId']}), 'resolved')
            review_path.write_text(json.dumps(approved), encoding='utf-8')
            drafts = {path.name: path.read_bytes() for path in (job_dir / 'drafts').iterdir()}
            original_input = copy.deepcopy(job['recordingInput'])
            with patch.object(future_song_module.youtube, 'fetch_video_info', return_value={'id': job['videoId'], 'title': 'fixture'}), \
                    patch.object(source_acquisition, 'collect_normal_youtube_candidates', return_value={'checked': True, 'records': []}), \
                    patch.object(source_acquisition, 'acquire_exact_item_draft', side_effect=self.acquire) as acquire:
                result = workflow.add_lyric_source(job['videoId'], f'https://music.youtube.com/watch?v={job["videoId"]}')
                self.assertEqual(acquire.call_count, 1)
            self.assertEqual(result['sourceAction']['result'], 'restaged')
            self.assertEqual(job['recordingInput'], original_input)
            self.assertEqual({path.name: path.read_bytes() for path in (job_dir / 'drafts').iterdir()}, drafts)
            review = json.loads((job_dir / 'agent-review.json').read_bytes())
            self.assertEqual(review['authorityOutcome'], 'pending')
            self.assertFalse(review['youtubeSurfaceVariantsReviewed'])
            self.assertFalse(review['publicSourceResearchComplete'])
            self.assertEqual(review['sourceDisposition'], 'REPLACE_AFTER_REVIEW')
            self.assertFalse(review['recordingOrSourceAssociationReviewed'])
            for key in ('recordingCompared', 'versionSpecificWordingReviewed', 'performedOrderReviewed'):
                self.assertFalse(review['recordingReview'][key], key)
            self.assertFalse(review['metadataReview']['recordingIdentityConfirmed'])
            for key in ('recordingReview', 'metadataReview'):
                self.assertEqual(review[key]['sourcesSha256'], old_sources_sha)
            for key in ('originalReviewed', 'notes'):
                self.assertEqual(review[key], approved[key])
            incomplete = copy.deepcopy(review)
            incomplete['authorityOutcome'] = 'resolved'
            incomplete['unresolvedMaterialIssues'] = []
            with self.assertRaisesRegex(FutureSongError, 'authority research is not marked complete'):
                validate_agent_review(incomplete, video_id=job['videoId'], available_lyric_source_ids={job['videoId']})
            sources = json.loads((job_dir / 'sources.json').read_bytes())
            self.assertNotEqual(future_song_module.sha256_file(job_dir / 'sources.json'), old_sources_sha)
            self.assertEqual(len(sources['lyricEvidenceSources']), 1)
            self.assertTrue(sources['usableLyricEvidenceAvailable'])
            workflow._pipeline.assert_not_called()
            self.assertNotIn('validation', job)
            with patch.object(future_song_module.youtube, 'fetch_video_info', side_effect=AssertionError('Intact primary source must reuse')):
                reused = workflow.add_lyric_source(job['videoId'], f'https://youtu.be/{job["videoId"]}')
            self.assertEqual(reused['sourceAction']['result'], 'already-staged')
            self.assertEqual(json.loads(review_path.read_bytes()), review)

    def test_primary_no_body_or_api_error_preserves_durable_inputs_and_reports_the_difference(self):
        for decision, normal_present in ((d, n) for d in ('not-found', 'api-error') for n in (False, True)):
            with self.subTest(decision=decision, normal_present=normal_present), tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
                workflow, job, job_dir = self.fixture(Path(name))
                normal = {'records': [], 'usableLyricCandidateCount': int(normal_present)}
                if normal_present:
                    caption = job_dir / 'normal-lyrics.txt'
                    caption.write_bytes(b'private retained lyric candidate\n')
                    normal['records'].append({'path': str(caption), 'lyricUse': 'potential-body',
                        'sha256': future_song_module.sha256_file(caption)})
                    sources_path = job_dir / 'sources.json'
                    sources = json.loads(sources_path.read_bytes())
                    sources['lyricEvidenceSources'][0]['normalYouTube'] = normal
                    sources_path.write_text(json.dumps(sources), encoding='utf-8')
                saved = {path.name: path.read_bytes() for path in job_dir.iterdir() if path.is_file()}
                with patch.object(future_song_module.youtube, 'fetch_video_info', return_value={'id': job['videoId']}), \
                        patch.object(source_acquisition, 'collect_normal_youtube_candidates', return_value=normal), \
                        patch.object(source_acquisition, 'acquire_exact_item_draft', return_value={'videoId': job['videoId'], 'decision': decision}):
                    result = workflow.add_lyric_source(job['videoId'], f'https://youtu.be/{job["videoId"]}')
                self.assertEqual(result['sourceAction']['result'], 'no-usable-body')
                self.assertEqual(result['sourceAction']['youtubeMusicDecision'], decision)
                self.assertEqual(result['sourceAction']['usableLyricEvidenceAvailable'], normal_present)
                self.assertEqual({path.name: path.read_bytes() for path in job_dir.iterdir() if path.is_file()}, saved)
                workflow._save_job.assert_not_called()
                workflow._write_task.assert_not_called()
                workflow._pipeline.assert_not_called()
                self.assertIn('validation', job)

    def test_metadata_only_terminal_primary_source_rejects_before_provider_access(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow, job, _job_dir = self.fixture(Path(name))
            job['state'] = 'DELIVERED'
            with patch.object(future_song_module.youtube, 'fetch_video_info') as fetch:
                with self.assertRaisesRegex(FutureSongError, 'completed job'):
                    workflow.add_lyric_source(job['videoId'], f'https://youtu.be/{job["videoId"]}')
                fetch.assert_not_called()

    def test_primary_playlist_payload_cannot_become_usable_lyric_evidence(self):
        with tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work') as name:
            workflow, job, job_dir = self.fixture(Path(name))
            saved = (job_dir / 'sources.json').read_bytes()
            def playlist(item, destination, **kwargs):
                raw = b'#EXTM3U\nprovider playlist metadata\n'
                destination.mkdir(parents=True, exist_ok=True)
                (destination / 'original.txt').write_bytes(raw)
                return {'videoId': item['videoId'], 'decision': 'acquired',
                        'sha256': hashlib.sha256(raw).hexdigest()}
            with patch.object(future_song_module.youtube, 'fetch_video_info', return_value={'id': job['videoId']}), \
                    patch.object(source_acquisition, 'collect_normal_youtube_candidates', return_value={'records': []}), \
                    patch.object(source_acquisition, 'acquire_exact_item_draft', side_effect=playlist):
                result = workflow.add_lyric_source(job['videoId'], f'https://youtu.be/{job["videoId"]}')
            self.assertEqual(result['sourceAction']['result'], 'no-usable-body')
            self.assertFalse(result['sourceAction']['usableLyricEvidenceAvailable'])
            self.assertEqual((job_dir / 'sources.json').read_bytes(), saved)
            self.assertFalse((job_dir / 'candidates' / job['videoId']).exists())
            workflow._save_job.assert_not_called()

    def test_cross_id_collector_keeps_its_distinct_identity_contract(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE/'Test suite/work')
        self.addCleanup(temporary.cleanup)
        workflow = workflow_fixture(Path(temporary.name))
        with patch.object(source_acquisition, 'collect_source_evidence') as collect:
            with self.assertRaisesRegex(FutureSongError, 'must differ'):
                source_acquisition.collect_cross_id_evidence(workflow.candidate_context('KbKqkR3Q6SA'), 'KbKqkR3Q6SA', 'KbKqkR3Q6SA', relationship='counterpart')
            collect.assert_not_called()


class ExistingDraftProvenanceTests(unittest.TestCase):
    def test_preserved_draft_alone_is_not_youtube_music_evidence(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = workflow_fixture(root)
            record = {
                "videoId": "KbKqkR3Q6SA",
                "youtubeMusic": {"result": {"decision": "skipped-existing"}},
            }
            self.assertFalse(source_acquisition.source_record_has_usable_evidence(
                record, draft_present=True, job_root=root,
                package_video_id="KbKqkR3Q6SA",
            ))
            candidate = root / "candidate.txt"
            candidate.write_text("fixture", encoding="utf-8")
            record["youtubeMusic"]["result"].update({
                "candidatePath": str(candidate),
                "sha256": hashlib.sha256(candidate.read_bytes()).hexdigest(),
            })
            self.assertTrue(source_acquisition.source_record_has_usable_evidence(
                record, draft_present=True, job_root=root,
                package_video_id="KbKqkR3Q6SA",
            ))
            candidate.write_text("changed", encoding="utf-8")
            self.assertFalse(source_acquisition.source_record_has_usable_evidence(
                record, draft_present=True, job_root=root,
                package_video_id="KbKqkR3Q6SA",
            ))


class RecordingReviewIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=WORKSPACE/"Test suite/work")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.workflow = workflow_fixture(self.root)
        self.workflow.root = self.root / "future-songs"
        self.workflow.jobs = self.workflow.root / "jobs"
        self.video_id = "KbKqkR3Q6SA"
        self.job = {"videoId": self.video_id, "state": "WAITING_FOR_EDITORIAL", "recordingReviewRequired": True}
        self.job_dir = self.workflow._job_dir(self.video_id)
        self.draft = self.job_dir / "drafts"
        self.draft.mkdir(parents=True)
        (self.draft / "lyrics.meta.json").write_text(json.dumps({'videoId': self.video_id,
            'originalLanguage': 'fr', 'originalScript': 'Latn',
            'sources': {'original': {'name': 'Synthetic source'}}}), encoding="utf-8")
        (self.draft / "original.txt").write_text("first line\nsecond line\n", encoding="utf-8")
        (self.job_dir / "agent-review.json").write_text("{}", encoding="utf-8")
        self.candidate = self.job_dir / "candidate.txt"
        self.candidate.write_bytes(b"candidate fixture")
        self.sources = {"lyricEvidenceSources": [{
            "videoId": self.video_id,
            "normalYouTube": {"records": [{"path": str(self.candidate), "sha256": hashlib.sha256(self.candidate.read_bytes()).hexdigest()}]},
        }]}
        self.sources_path = self.job_dir / "sources.json"
        self.sources_path.write_text(json.dumps(self.sources), encoding="utf-8")
        self.audio = {"sha256": hashlib.sha256(b"actual audio fixture").hexdigest()}
        self.review = {"recordingReview": {
            "formatVersion": 2, "audioSha256": self.audio["sha256"],
            "draftSha256": self.workflow._recording_draft_sha(self.job),
            "sourcesSha256": hashlib.sha256(self.sources_path.read_bytes()).hexdigest(),
            "recordingCompared": True, "versionSpecificWordingReviewed": True,
            "performedOrderReviewed": True,
            "sourceAssociations": [{"videoId": self.video_id, "relation": "exact-recording"}],
            "publicSources": [],
        }}
        (self.job_dir / "agent-review.json").write_text(json.dumps(self.review), encoding="utf-8")

    def require_review(self):
        self.workflow._require_recording_review(self.job, self.review, self.sources, self.audio)

    def test_bound_review_passes_without_hashing_itself(self):
        self.require_review()
        self.assertEqual(self.review["recordingReview"]["draftSha256"], self.workflow._recording_draft_sha(self.job))

    def test_companion_only_change_preserves_original_recording_review(self):
        initial = self.workflow._recording_draft_sha(self.job)
        (self.draft / "translation-en.txt").write_text("unreviewed replacement\n", encoding="utf-8")
        (self.draft / "transliteration-ipa.txt").write_text("kat\n", encoding="utf-8")
        metadata = json.loads((self.draft / "lyrics.meta.json").read_text(encoding="utf-8"))
        metadata['sources']['translationEn'] = {"name": "Changed companion source"}
        (self.draft / "lyrics.meta.json").write_text(json.dumps(metadata), encoding="utf-8")
        self.assertEqual(self.workflow._recording_draft_sha(self.job), initial)
        self.require_review()

    def test_same_shaped_word_change_invalidates_approval(self):
        (self.draft / "original.txt").write_text("other line\nsecond line\n", encoding="utf-8")
        with self.assertRaisesRegex(FutureSongError, "draftSha256 is stale"):
            self.require_review()

    def test_used_candidate_change_with_same_id_invalidates_approval(self):
        self.candidate.write_bytes(b"changed candidate")
        with self.assertRaisesRegex(FutureSongError, "candidate changed"):
            self.require_review()

    def test_actual_audio_or_source_metadata_change_invalidates_approval(self):
        original_audio = dict(self.audio)
        self.audio["sha256"] = hashlib.sha256(b"other recording").hexdigest()
        with self.assertRaisesRegex(FutureSongError, "audioSha256 is stale"):
            self.require_review()
        self.audio = original_audio
        self.sources_path.write_text(json.dumps({**self.sources, "changed": True}), encoding="utf-8")
        with self.assertRaisesRegex(FutureSongError, "sourcesSha256 is stale"):
            self.require_review()

    def test_unusable_candidate_change_does_not_block_other_usable_reference(self):
        self.review["recordingReview"]["sourceAssociations"][0]["relation"] = "unusable"
        self.review["recordingReview"]["publicSources"] = [{"url": "https://example.org/poem", "relation": "text-reference"}]
        self.candidate.write_bytes(b"discarded candidate changed")
        self.require_review()

    def test_different_version_comparison_still_binds_candidate_bytes(self):
        self.review["recordingReview"]["sourceAssociations"][0]["relation"] = "different-version"
        self.review["recordingReview"]["publicSources"] = [{"url": "https://example.org/poem", "relation": "text-reference"}]
        self.candidate.write_bytes(b"other version changed")
        with self.assertRaisesRegex(FutureSongError, "candidate changed"):
            self.require_review()

    def test_malformed_candidate_fields_raise_workflow_errors(self):
        for candidate in (None, {"path": 7, "sha256": "a" * 64}, {"path": 0, "sha256": "a" * 64}, {"path": str(self.candidate), "sha256": None}):
            with self.subTest(candidate=candidate):
                self.sources["lyricEvidenceSources"][0]["normalYouTube"]["records"] = [candidate]
                self.sources_path.write_text(json.dumps(self.sources), encoding="utf-8")
                self.review["recordingReview"]["sourcesSha256"] = hashlib.sha256(self.sources_path.read_bytes()).hexdigest()
                with self.assertRaises(FutureSongError):
                    self.require_review()


    def test_validate_blocks_stale_words_before_metadata_or_alignment(self):
        (self.draft / "original.txt").write_text("other line\nsecond line\n", encoding="utf-8")
        self.workflow._load_job = Mock(return_value=self.job)
        self.workflow._validate_authority_outcome = Mock(return_value=("resolved", self.review, self.sources))
        self.workflow._pipeline = Mock()
        self.workflow._require_reviewed_artwork = Mock()
        self.workflow._completed_analysis_audio = Mock(return_value=self.audio)
        self.workflow._validate_drafts = Mock(return_value=({}, "d" * 64, {}))
        self.workflow._apply_metadata_review = Mock()
        with patch('stemwise_engine.lyrics_alignment.snapshot_canonical_plain') as snapshot:
            with self.assertRaisesRegex(FutureSongError, "draftSha256 is stale"):
                self.workflow._validate_impl(self.video_id)
            snapshot.assert_not_called()
        self.workflow._apply_metadata_review.assert_not_called()


class RecordingReviewTests(unittest.TestCase):
    def setUp(self):
        self.video_id = "KbKqkR3Q6SA"
        self.review = {
            "formatVersion": 2,
            "audioSha256": "a" * 64, "draftSha256": "b" * 64,
            "sourcesSha256": "c" * 64,
            "recordingCompared": True, "versionSpecificWordingReviewed": True,
            "performedOrderReviewed": True,
            "sourceAssociations": [{"videoId": self.video_id, "relation": "exact-recording"}],
            "publicSources": [],
        }

    def validate(self):
        editorial_review.validate_recording_review(
            self.review, audio_sha256="a" * 64, draft_sha256="b" * 64,
            sources_sha256="c" * 64, available_source_ids={self.video_id},
        )

    def test_exact_recording_needs_no_external_website(self):
        self.validate()

    def test_poem_reference_requires_recording_comparison_and_usable_source(self):
        self.review["sourceAssociations"][0]["relation"] = "different-version"
        with self.assertRaises(FutureSongError):
            self.validate()
        self.review["publicSources"] = [{"url": "https://example.org/poem", "relation": "text-reference"}]
        self.validate()
        self.review["recordingCompared"] = False
        with self.assertRaises(FutureSongError):
            self.validate()

    def test_stale_or_malformed_binding_and_unreviewed_flags_are_rejected(self):
        original = copy.deepcopy(self.review)
        changes = [(key, value) for key in ("audioSha256", "draftSha256", "sourcesSha256")
                   for value in ("d" * 64, "bad", None)]
        changes += [(key, value) for key in ("recordingCompared", "versionSpecificWordingReviewed", "performedOrderReviewed")
                    for value in (False, 1, "true")]
        changes += [("formatVersion", True), ("formatVersion", 1), ("unexpected", True)]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                self.review = copy.deepcopy(original)
                self.review[key] = value
                with self.assertRaises(FutureSongError):
                    self.validate()

    def test_all_staged_sources_must_be_classified_once(self):
        for sources in ([], self.review["sourceAssociations"] * 2,
                        [{"videoId": "a1B2c3D4e5F", "relation": "exact-recording"}],
                        [{"videoId": self.video_id, "relation": "unusable"}],
                        [{"videoId": self.video_id, "relation": "same-title"}]):
            with self.subTest(sources=sources):
                self.review["sourceAssociations"] = sources
                with self.assertRaises(FutureSongError):
                    self.validate()

    def test_public_sources_reject_credentials_controls_and_invalid_urls(self):
        for url in ("file:///tmp/poem", "https:///poem", "https://user:pass@example.org/poem",
                    "https://example.org/poem\n", "https://example.org/\x00poem",
                    "https://example.org:bad/poem", "https://[broken/poem",
                    "https://www.youtube.com/watch?v=AAAAAAAAAAA", "https://youtu.be/AAAAAAAAAAA"):
            with self.subTest(url=url):
                self.review["publicSources"] = [{"url": url, "relation": "text-reference"}]
                with self.assertRaises(FutureSongError):
                    self.validate()


if __name__ == "__main__":
    unittest.main()
