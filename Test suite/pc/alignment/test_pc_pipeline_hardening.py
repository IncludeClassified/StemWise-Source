from __future__ import annotations

import contextlib
import copy
import hashlib
import inspect
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.doctor as doctor
import stemwise_engine.future_song as future_song_module
import stemwise_engine.workflow_command as workflow_command
import stemwise_engine.lyrics_align_pc as align_pc
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.lyrics_acquire as lyrics_acquire
import stemwise_engine.lyrics_intake as lyrics_intake
import stemwise_engine.pipeline as pipeline_module
import stemwise_engine.provenance as provenance
import stemwise_engine.stemwise_pc as stemwise_pc
from stemwise_engine.future_song import ACTIVE_ALIGNMENT_STATES, FutureSongError
from stemwise_engine.lyrics_alignment import (
    AlignmentMetrics,
    AlignmentResult,
    AlignmentWorkspace,
    snapshot_canonical_plain,
    transcript_to_json,
)


VIDEO_ID = "-Abc_123XYZ"


class ReviewedBaselineSelectionTest(unittest.TestCase):
    def test_unaccepted_fixed_baseline_can_reach_fallback_cascade(self):
        metrics = AlignmentMetrics(4, 4, 2, 2, 4, 0.5, 0.5, 0.5, 90.0, 0.8)
        baseline = AlignmentResult(False, ('short-timed-span',), (0, 100, 200, 300),
                                   (0, 1, 3, 4), metrics)
        selected = align_pc._select_evidence_option([('baseline', baseline, {}, False)],
            text='a\nb\n\na\nb', reviewed_baseline_structure=True,
            performed_structure_review_sha256='a'*64)
        self.assertIs(selected[1], baseline)
        self.assertEqual(selected[2]['performedStructureReview'], {'decision': 'baseline', 'sha256': 'a'*64})

    def test_content_mutation_blocks_before_timing_ranking(self):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        metrics = AlignmentMetrics(4, 4, 4, 4, 4, 1, 1, 1, 100, .8)
        mutation = AlignmentResult(True, (), (0, 100), (0, 1), metrics)
        with self.assertRaises(LyricsContentBlocker):
            align_pc._select_evidence_option([('mutation', mutation, {}, False)], text='a\nb\n\na\nb')


class DoctorPolicyProbeTest(unittest.TestCase):
    def test_policy_probe_initializes_the_pure_validation_dependencies(self) -> None:
        temporary = tempfile.TemporaryDirectory(dir=PROJECT.parents[1] / 'Test suite/work')
        self.addCleanup(temporary.cleanup)
        pc_side = Path(temporary.name) / 'PC side'
        project = pc_side / 'builder'
        project.mkdir(parents=True)
        (project / 'config.json').write_text(json.dumps({
            'playlistUrl': 'https://music.youtube.com/playlist?list=fixture',
            'generatorVersion': '2.1.0',
            'outputDirWindows': str(pc_side / 'runtime'),
            'pcSideRootWindows': str(pc_side),
            'futureRootWindows': str(project / 'future-songs'),
            'tempDirWindows': str(pc_side / 'temporary-analysis'),
        }), encoding='utf-8')

        def validate(workflow: object) -> dict[str, object]:
            self.assertFalse(hasattr(workflow, 'campaign_policy_path'))
            return {"policyVersion": "test-policy"}

        with patch.object(future_song_module.FutureSongWorkflow, "_validate_policy", validate):
            result = doctor.future_song_policy_identity(project / "config.json")

        self.assertTrue(result["ready"])
        self.assertEqual(result["policyVersion"], "test-policy")










class PinnedIdentityTest(unittest.TestCase):
    def test_unchanged_model_and_git_blob_are_read_only_once(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'model.bin'
            payload = b'fixed pinned model'
            path.write_bytes(payload)
            expected_file = hashlib.sha256(payload).hexdigest()
            expected_blob = hashlib.sha1(f'blob {len(payload)}\0'.encode() + payload).hexdigest()
            original_open = Path.open
            with patch.dict(alignment_models._PINNED_FILE_CACHE, clear=True), patch.dict(align_pc._PINNED_GIT_BLOB_CACHE, clear=True), \
                    patch.object(Path, 'open', autospec=True, side_effect=original_open) as reads:
                for _ in range(2):
                    self.assertEqual(alignment_models.require_pinned_file(path, expected_file, 'model'), expected_file)
                    self.assertEqual(align_pc._require_pinned_git_blob(path, expected_blob, len(payload), 'model'), expected_blob)
                self.assertEqual(reads.call_count, 4 if os.name == 'nt' else 2)

    def test_cached_bytes_cannot_satisfy_a_different_required_digest(self) -> None:
        for kind in ('model', 'git-blob'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as name:
                path = Path(name) / 'model.bin'
                payload = b'fixed pinned model'
                path.write_bytes(payload)
                expected = (hashlib.sha256(payload).hexdigest() if kind == 'model' else
                            hashlib.sha1(f'blob {len(payload)}\0'.encode() + payload).hexdigest())
                validate = (lambda digest: alignment_models.require_pinned_file(path, digest, 'model')) if kind == 'model' else \
                           (lambda digest: align_pc._require_pinned_git_blob(path, digest, len(payload), 'model'))
                cache = alignment_models._PINNED_FILE_CACHE if kind == 'model' else align_pc._PINNED_GIT_BLOB_CACHE
                with patch.dict(cache, clear=True):
                    self.assertEqual(validate(expected), expected)
                    before = dict(cache)
                    with self.assertRaisesRegex(RuntimeError, 'identity check'):
                        validate('0' * len(expected))
                    self.assertEqual(cache, before)
                    self.assertEqual(path.read_bytes(), payload)

    def test_windows_rehashes_even_when_the_file_identity_is_unchanged(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'model.bin'
            payload = b'fixed pinned model'
            path.write_bytes(payload)
            expected_file = hashlib.sha256(payload).hexdigest()
            expected_blob = hashlib.sha1(f'blob {len(payload)}\0'.encode() + payload).hexdigest()
            original_open = Path.open
            with patch.dict(alignment_models._PINNED_FILE_CACHE, clear=True), patch.dict(align_pc._PINNED_GIT_BLOB_CACHE, clear=True), \
                    patch.object(Path, 'open', autospec=True, side_effect=original_open) as reads, patch.object(align_pc.os, 'name', 'nt'):
                for _ in range(2):
                    self.assertEqual(alignment_models.require_pinned_file(path, expected_file, 'model'), expected_file)
                    self.assertEqual(align_pc._require_pinned_git_blob(path, expected_blob, len(payload), 'model'), expected_blob)
                self.assertEqual(reads.call_count, 4)

    def test_same_size_rewrite_with_restored_mtime_is_rehashed(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "model.bin"
            original = b"original"
            replacement = b"changed!"
            self.assertEqual(len(original), len(replacement))
            path.write_bytes(original)
            expected = hashlib.sha256(original).hexdigest()
            alignment_models._PINNED_FILE_CACHE.clear()
            try:
                self.assertEqual(
                    alignment_models.require_pinned_file(path, expected, "test model"),
                    expected,
                )
                initial = path.stat()
                path.write_bytes(replacement)
                os.utime(
                    path,
                    ns=(initial.st_atime_ns, initial.st_mtime_ns),
                )
                with self.assertRaisesRegex(RuntimeError, "SHA-256"):
                    alignment_models.require_pinned_file(path, expected, "test model")
            finally:
                alignment_models._PINNED_FILE_CACHE.clear()


class CanonicalBackupSafetyTest(unittest.TestCase):
    def test_snapshot_rejects_existing_symlink_backup(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root / "future-songs", workspace_root=root)
            workspace.canonical_plain.mkdir(parents=True)
            raw = b'{"tracks":[]}\n'
            digest = hashlib.sha256(raw).hexdigest()
            target = root / "outside.json"
            target.write_bytes(raw)
            backup = workspace.canonical_plain / f"{VIDEO_ID}.{digest}.lyrics.json"
            try:
                backup.symlink_to(target)
            except OSError as error:
                self.skipTest(f"symlinks are unavailable: {type(error).__name__}")
            with self.assertRaisesRegex(ValueError, "link|unsafe"):
                snapshot_canonical_plain(workspace, VIDEO_ID, raw)


class EvidenceCacheIdentityTest(unittest.TestCase):
    def test_whisper_payload_cannot_be_relabelled_as_caption_cache(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root / "future-songs", workspace_root=root)
            workspace.job_dir(VIDEO_ID).mkdir(parents=True)
            identity = {"cacheIdentityVersion": 1, "authority": "test"}
            evidence = {
                "type": "youtube-caption",
                "captionKind": "manual",
                "captionLanguage": "en",
                "wordCount": 1,
            }
            provenance = {
                "source": "youtube-caption",
                "captionKind": "manual",
                "captionLanguage": "en",
            }
            words = [SimpleNamespace(to_dict=lambda: {
                "word": "hello", "raw": "Hello", "start": 0.0, "end": 0.5})]
            forged = transcript_to_json(
                VIDEO_ID,
                words,
                model="large-v3",
                language="en",
                prompt_sha256="not-applicable",
                evidence=evidence,
                provenance=provenance,
                cache_identity=identity,
            )
            cache = workspace.caption_path(VIDEO_ID)
            cache.write_text(json.dumps(forged), encoding="utf-8")
            with (
                patch.object(align_pc, "_current_cache_identity", return_value=identity),
                patch.object(
                    align_pc,
                    "download_youtube_caption_evidence",
                    return_value=None,
                ) as download,
            ):
                self.assertIsNone(
                    align_pc._load_caption(
                        video_id=VIDEO_ID,
                        original={"language": "en"},
                        workspace=workspace,
                        temp_root=root / "temporary",
                    )
                )
            download.assert_called_once()

            valid = transcript_to_json(
                VIDEO_ID,
                words,
                model="youtube-caption",
                language="en",
                prompt_sha256="not-applicable",
                evidence=evidence,
                provenance=provenance,
                cache_identity=identity,
            )
            cache.write_text(json.dumps(valid), encoding="utf-8")
            with (
                patch.object(align_pc, "_current_cache_identity", return_value=identity),
                patch.object(align_pc, "transcript_from_json", return_value=words),
                patch.object(
                    align_pc,
                    "download_youtube_caption_evidence",
                    side_effect=AssertionError("valid caption cache was not reused"),
                ),
            ):
                loaded = align_pc._load_caption(
                    video_id=VIDEO_ID,
                    original={"language": "en"},
                    workspace=workspace,
                    temp_root=root / "temporary",
                )
            self.assertIsNotNone(loaded)
            self.assertTrue(loaded[2])

    def test_mms_cache_binds_variant_and_acoustic_view(self) -> None:
        value = {
            "cacheVersion": align_pc.MMS_CACHE_VERSION,
            "videoId": VIDEO_ID,
            "variant": "baseline",
            "acousticTrackKind": "original-display-uroman:htdemucs-vocals",
            "requestSha256": "a" * 64,
            "helperSha256": "b" * 64,
            "provenance": {"audio": "identity"},
            "mappingIdentity": {"mapping": "identity"},
            "engine": "torchaudio-mms-fa",
            "lines": [],
            "confidence": {},
        }
        arguments = {
            "video_id": VIDEO_ID,
            "variant": "baseline",
            "acoustic_track_kind": "original-display-uroman:htdemucs-vocals",
            "request_sha256": "a" * 64,
            "helper_sha256": "b" * 64,
            "provenance": {"audio": "identity"},
            "mapping_identity": {"mapping": "identity"},
        }
        self.assertTrue(align_pc._mms_cache_matches(value, **arguments))
        self.assertFalse(
            align_pc._mms_cache_matches(
                value, **(arguments | {"variant": "candidate-structure"})
            )
        )
        self.assertFalse(
            align_pc._mms_cache_matches(
                value,
                **(
                    arguments
                    | {
                        "acoustic_track_kind": (
                            "original-display-uroman:kimberley-jensen-roformer-vocals"
                        )
                    }
                ),
            )
        )


class PrivateReportBoundaryTest(unittest.TestCase):
    def test_job_summary_drops_unstructured_private_fields(self) -> None:
        marker = "PRIVATE TOKEN https://secret.invalid C:\\private\\file"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root / "future-songs", workspace_root=root)
            job = workspace.job_dir(VIDEO_ID)
            job.mkdir(parents=True)
            (job / "state.json").write_text(
                json.dumps(
                    {
                        "videoId": VIDEO_ID,
                        "position": 1,
                        "status": "committed-lines",
                        "reasons": ["safe-code", marker],
                        "evidenceType": marker,
                        "metrics": {"duration_ms": 1, "private": marker},
                    }
                ),
                encoding="utf-8",
            )
            report = align_pc._job_summary(workspace)
        serialized = json.dumps(report)
        self.assertNotIn("PRIVATE TOKEN", serialized)
        self.assertNotIn("secret.invalid", serialized)
        self.assertEqual(report["jobs"][0]["reasons"], ["safe-code"])
        self.assertEqual(report["jobs"][0]["metrics"], {"duration_ms": 1})
        self.assertIsNone(report["jobs"][0]["evidenceType"])

    def test_cli_errors_are_code_only(self) -> None:
        class FakeParser:
            def parse_args(self, _argv: list[str] | None) -> SimpleNamespace:
                return SimpleNamespace(config=Path("unused.json"), command="status", recording_key=None, video_id=None)

        cases = (
            (FutureSongError("PRIVATE TOKEN https://secret.invalid"), 2, "future-song-error"),
            (RuntimeError("PRIVATE TOKEN C:\\private\\file"), 1, "unexpected-error"),
        )
        for error, expected_return, expected_code in cases:
            with self.subTest(error=type(error).__name__):
                stderr = io.StringIO()
                with (
                    patch.object(workflow_command, "parser", return_value=FakeParser()),
                    patch.object(
                        future_song_module.FutureSongWorkflow,
                        "__init__",
                        side_effect=error,
                    ),
                    contextlib.redirect_stderr(stderr),
                ):
                    result = workflow_command.main([])
                output = stderr.getvalue()
                self.assertEqual(result, expected_return)
                self.assertIn(expected_code, output)
                self.assertNotIn("PRIVATE TOKEN", output)
                self.assertNotIn("secret.invalid", output)
                self.assertNotIn("private\\file", output)
                self.assertEqual(json.loads(output)['causalTrace'][0]['type'], type(error).__name__)

    def test_bulk_status_does_not_echo_corrupt_job_path_or_error(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = future_song_module.FutureSongWorkflow.__new__(
                future_song_module.FutureSongWorkflow
            )
            workflow.jobs = root / "jobs"
            corrupt = workflow.jobs / "PRIVATE-TOKEN" / "job.json"
            corrupt.parent.mkdir(parents=True)
            corrupt.write_text('{"private":"SECRET BODY"', encoding="utf-8")
            status = workflow.status(None)
        serialized = json.dumps(status)
        self.assertNotIn("PRIVATE-TOKEN", serialized)
        self.assertNotIn("SECRET BODY", serialized)
        self.assertEqual(status["jobs"][0]["videoId"], "invalid-id")
        self.assertEqual(status["jobs"][0]["statusError"], "JSONDecodeError")

    def test_future_status_summary_projects_operational_paths_and_legacy_error(self) -> None:
        marker = "PRIVATE TOKEN https://secret.invalid C:\\private\\file"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workflow = future_song_module.FutureSongWorkflow.__new__(
                future_song_module.FutureSongWorkflow
            )
            workflow.workspace_root = root
            workflow.root = root / "future-songs"
            workflow.jobs = workflow.root / "jobs"
            workflow.runtime = workflow.root / "runtime"
            summary = workflow.summary(
                {
                    "videoId": VIDEO_ID,
                    "state": "VALIDATION_FAILED",
                    "deliveryAllowed": False,
                    "error": marker,
                    "validation": {"report": marker, "reportSha256": "a" * 64},
                    "delivery": {"path": marker, "sha256": "b" * 64},
                }
            )
        serialized = json.dumps(summary)
        self.assertNotIn("PRIVATE TOKEN", serialized)
        self.assertNotIn("secret.invalid", serialized)
        self.assertNotIn("private\\file", serialized)
        self.assertNotIn("report", summary["validation"])
        self.assertNotIn("path", summary["delivery"])
        self.assertEqual(summary["error"], "future-song-error")
        self.assertFalse(Path(summary["job"]).is_absolute())

    def test_user_facing_pc_clis_do_not_echo_raw_exceptions(self) -> None:
        class FakeParser:
            def parse_args(self, _argv: list[str] | None) -> SimpleNamespace:
                return SimpleNamespace(config=Path("unused.json"), command="status")

        for module, prefix in (
            (stemwise_pc, "stemwise-pc"),
            (lyrics_intake, "stemwise-lyrics"),
        ):
            with self.subTest(module=module.__name__):
                stderr = io.StringIO()
                with (
                    patch.object(module, "parser", return_value=FakeParser()),
                    patch.object(
                        module,
                        "load_config",
                        side_effect=RuntimeError(
                            "PRIVATE TOKEN https://secret.invalid C:\\private\\file"
                        ),
                    ),
                    contextlib.redirect_stderr(stderr),
                ):
                    result = module.main([])
                output = stderr.getvalue()
                self.assertEqual(result, 2)
                self.assertIn(f"{prefix}: RuntimeError", output)
                self.assertNotIn("PRIVATE TOKEN", output)
                self.assertNotIn("secret.invalid", output)
                self.assertNotIn("private\\file", output)

    def test_acquisition_reports_provider_error_codes_only(self) -> None:
        class BrokenProvider:
            def get_watch_playlist(self, **_kwargs: object) -> object:
                raise RuntimeError(
                    "PRIVATE TOKEN https://secret.invalid C:\\private\\file"
                )

            def search(self, *_args: object, **_kwargs: object) -> object:
                raise RuntimeError(
                    "PRIVATE TOKEN https://secret.invalid C:\\private\\file"
                )

        item = {
            "position": 1,
            "videoId": VIDEO_ID,
            "title": "Public title",
            "uploader": "Public artist",
            "durationSeconds": 180,
        }
        with tempfile.TemporaryDirectory() as name:
            record = lyrics_acquire.acquire_exact_item_draft(
                item,
                Path(name) / "draft",
                client=BrokenProvider(),
                detector=object(),
            )
        candidates = lyrics_acquire.discover_counterpart_candidates(
            item,
            client=BrokenProvider(),
        )
        serialized = json.dumps({"record": record, "candidates": candidates})
        self.assertNotIn("PRIVATE TOKEN", serialized)
        self.assertNotIn("secret.invalid", serialized)
        self.assertNotIn("private\\file", serialized)
        self.assertEqual(record["reason"], "RuntimeError")
        self.assertEqual(
            candidates["surfaceErrors"],
            ["songs:RuntimeError", "videos:RuntimeError"],
        )

    def test_pc_report_projects_stale_private_diagnostics_and_paths(self) -> None:
        marker = "PRIVATE TOKEN https://secret.invalid C:\\private\\file"
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            pipeline = pipeline_module.StemwisePipeline.__new__(
                pipeline_module.StemwisePipeline
            )
            pipeline.inventory_path = root / "missing-inventory.json"
            pipeline.report_path = root / "pc-report.json"
            pipeline.provenance_sha256 = "a" * 64
            pipeline.provenance = {}
            pipeline.checkpoints = lambda: [
                {
                    "videoId": VIDEO_ID,
                    "status": marker,
                    "output": marker,
                    "sourceUrl": marker,
                    "stages": {
                        "analysis": {
                            "status": "failed",
                            "reason": marker,
                            "warning": marker,
                        }
                    },
                }
            ]
            report = pipeline.write_report(
                snapshot={
                    "playlist": {
                        "id": "public-id",
                        "title": "Public playlist",
                        "sourceUrl": marker,
                    },
                    "itemCount": 1,
                }
            )
        serialized = json.dumps(report)
        self.assertNotIn("PRIVATE TOKEN", serialized)
        self.assertNotIn("secret.invalid", serialized)
        self.assertNotIn("private\\file", serialized)
        self.assertNotIn("sourceUrl", serialized)
        self.assertEqual(report["states"][0]["status"], "corrupt")
        self.assertEqual(
            report["states"][0]["stages"]["analysis"]["reason"],
            "redacted-diagnostic",
        )


class ProvenancePrivacyTest(unittest.TestCase):
    def test_file_and_package_identities_strip_paths_and_direct_urls(self) -> None:
        class FakeDistribution:
            version = "1.2.3"

            @staticmethod
            def read_text(_name: str) -> str:
                return json.dumps(
                    {
                        "url": "https://user:PRIVATE-TOKEN@example.invalid/private.git",
                        "vcs_info": {
                            "vcs": "git",
                            "commit_id": "a" * 40,
                            "requested_revision": "release",
                        },
                    }
                )

        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "model.bin"
            path.write_bytes(b"model")
            file_record = provenance.file_identity(path)
        with patch.object(
            provenance.importlib.metadata,
            "distribution",
            return_value=FakeDistribution(),
        ):
            package_record = provenance.package_identity("private-package")
        serialized = json.dumps(
            {"file": file_record, "package": package_record},
            sort_keys=True,
        )
        self.assertNotIn(name, serialized)
        self.assertNotIn("PRIVATE-TOKEN", serialized)
        self.assertNotIn("example.invalid", serialized)
        self.assertNotIn("url", package_record.get("source", {}))
        self.assertEqual(package_record["source"]["commit"], "a" * 40)


class CancellationStateTest(unittest.TestCase):
    def test_roformer_fallback_is_an_active_alignment_state(self) -> None:
        self.assertIn("transcribing-roformer-fallback", ACTIVE_ALIGNMENT_STATES)

    def test_provider_client_uses_shared_budget_and_typed_owned_transport(self) -> None:
        from stemwise_engine.provider_operations import ProviderBudget, ProviderDeadline, provider_operation
        calls = []
        def transport(budget, arguments):
            self.assertGreater(budget.remaining(), 0)
            self.assertLessEqual(budget.remaining(), 1)
            calls.append(arguments)
            return b'{"lyrics":"known provider response"}'
        with provider_operation(1), patch.object(ProviderBudget, 'run', autospec=True, side_effect=transport):
            result = lyrics_acquire._provider_call(lambda: lyrics_acquire._load_client().get_lyrics('fixture-id', timestamps=False))
        self.assertEqual(result, {'lyrics': 'known provider response'})
        mode, payload = calls[0]
        self.assertEqual(mode, '--lyrics')
        self.assertEqual(json.loads(payload), {'method': 'get_lyrics', 'args': ['fixture-id'], 'kwargs': {'timestamps': False}})
        clock = [0.]
        with provider_operation(1, clock=lambda: clock[0]):
            clock[0] = 2.
            with patch.object(ProviderBudget, 'run') as retired, self.assertRaises(ProviderDeadline):
                lyrics_acquire._provider_call(lambda: lyrics_acquire._load_client().search('fixture'))
            retired.assert_not_called()
        from stemwise_engine.model_registry import runtime_profile
        self.assertEqual('2.34.2', runtime_profile('main')['packages']['requests'])
        self.assertIn("requests", provenance.MAIN_PACKAGES)


if __name__ == "__main__":
    unittest.main()
