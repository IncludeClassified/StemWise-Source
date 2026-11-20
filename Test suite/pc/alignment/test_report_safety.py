from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

WORKSPACE = Path(__file__).resolve().parents[3]
PROJECT = WORKSPACE / "PC side" / "stemwise-playlist-builder"
sys.path.insert(0, str(WORKSPACE/"Test suite/pc/unit"))

from stemwise_engine.workflow_context import report_control_path
import stemwise_engine.future_song as future_song_module
import stemwise_engine.source_acquisition as source_acquisition
from workflow_fixture import initialized_workflow
import stemwise_engine.workflow_reports as workflow_reports_module
from stemwise_engine.common import (
    atomic_write_bytes,
    create_manifested_directory,
    manifested_temporary_directory,
    preflight_staging_space,
    remove_manifested_directory,
    validate_generated_path,
    validate_video_id,
)
from stemwise_engine.future_song import (
    FutureSongError,
    FutureSongOutcomeError,
    FutureSongWorkflow,
)
from stemwise_engine.workflow_command import parser
from stemwise_engine.workflow_records import EndpointReviewResult
from stemwise_engine.editorial_review import POLICY_VERSION, validate_agent_review
from stemwise_engine.lyrics_alignment import (
    ALIGNMENT_VERSION,
    build_lexical_occurrence_mapping,
    lexical_mapping_identity,
)


SCRATCH_ROOT = WORKSPACE / "Test suite" / "work"


import stemwise_engine.workflow_diagnostics as workflow_diagnostics

class GeneratedPathSafetyTest(unittest.TestCase):
    def test_reviewed_endpoint_supersedes_the_machine_route_selection(self) -> None:
        machine = {
            "resultClass": "line-unresolved",
            "lineCount": 1,
            "lineReadyCount": 0,
            "unresolvedLineReasons": {"line-end-disagreement": 1},
        }
        reviewed = {
            "resultClass": "committed-lines",
            "lineCount": 1,
            "lineReadyCount": 1,
            "unresolvedLineReasons": {},
        }
        state = {
            "status": "committed-lines",
            "endpointRouteAttempts": [
                {
                    "routeId": "htdemucs-vocals:baseline",
                    "reused": True,
                    "endpointEvidence": machine,
                }
            ],
            "selectedEndpointAttemptIndex": 0,
            "endpointEvidence": reviewed,
            "endpointReview": EndpointReviewResult("1" * 64, "2" * 64,
                "3" * 32, "4" * 64, 1).to_record(),
        }

        diagnostics = workflow_diagnostics._alignment_diagnostics(state)

        self.assertEqual(reviewed, diagnostics["endpointEvidence"])
        self.assertEqual(machine, diagnostics["endpointRouteAttempts"][0]["endpointEvidence"])
        self.assertNotIn("selectedEndpointAttemptIndex", diagnostics)
        self.assertEqual(state["endpointReview"], diagnostics["endpointReview"])

    def test_unqualified_profile_still_requires_a_validated_local_job(self) -> None:
        temporary = tempfile.TemporaryDirectory(dir=SCRATCH_ROOT)
        self.addCleanup(temporary.cleanup)
        workflow = initialized_workflow(Path(temporary.name))
        workflow.source_context = None
        workflow._run_mutating_operation = lambda _video_id, _action, callback: callback()
        workflow._load_job = lambda _video_id: {
            "videoId": _video_id,
            "state": "WAITING_FOR_EDITORIAL",
        }
        with self.assertRaisesRegex(FutureSongError, "successful Validate"):
            workflow.finalize("-Abc_123XYZ")

    def test_exact_identity_containment_manifest_and_reparse_guards(self) -> None:
        owner = {"kind": "safety-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="case-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            self.assertEqual(validate_video_id("-Abc_123XYZ"), "-Abc_123XYZ")
            with self.assertRaises(ValueError):
                validate_video_id("../not-an-id")
            with self.assertRaises(ValueError):
                validate_generated_path(scratch, scratch.parent / "escape")

            generated = create_manifested_directory(
                scratch,
                scratch / "generated",
                owner={"kind": "unit", "id": "one"},
                workspace_root=WORKSPACE,
            )
            (generated / "value.bin").write_bytes(b"safe")
            self.assertTrue(
                remove_manifested_directory(
                    scratch,
                    generated,
                    owner={"kind": "unit", "id": "one"},
                    workspace_root=WORKSPACE,
                )
            )

            unowned = scratch / "unowned"
            unowned.mkdir()
            (unowned / "keep.bin").write_bytes(b"owner")
            with self.assertRaises(ValueError):
                remove_manifested_directory(
                    scratch,
                    unowned,
                    owner={"kind": "unit", "id": "one"},
                    workspace_root=WORKSPACE,
                )
            self.assertTrue((unowned / "keep.bin").is_file())

            outside = scratch / "outside"
            outside.mkdir()
            link = scratch / "linked"
            try:
                os.symlink(outside, link, target_is_directory=True)
            except OSError:
                pass
            else:
                with self.assertRaises(ValueError):
                    validate_generated_path(scratch, link, workspace_root=WORKSPACE)
                link.unlink()

            redirected = scratch / "redirected-owner"
            redirected.mkdir()
            owner_link = scratch / "owner-input"
            try:
                os.symlink(redirected, owner_link, target_is_directory=True)
            except OSError:
                pass
            else:
                with self.assertRaises(ValueError):
                    atomic_write_bytes(
                        owner_link / "-Abc_123XYZ.lyrics.json",
                        b"must-not-write",
                        root=owner_link,
                        workspace_root=WORKSPACE,
                    )
                self.assertEqual(list(redirected.iterdir()), [])
                owner_link.unlink()

            destination = scratch / "package.zip"
            staging = scratch / ".package.zip.tmp"
            preflight_staging_space(
                destination,
                staging,
                candidate_bytes=4,
                rollback_bytes=4,
            )


class WorkflowOperationReportTest(unittest.TestCase):
    @staticmethod
    def workflow(scratch: Path) -> FutureSongWorkflow:
        sys.path.insert(0, str(WORKSPACE/'Test suite/pc/unit'))
        from workflow_fixture import initialized_workflow
        workflow = initialized_workflow(scratch)
        return workflow

    @staticmethod
    def records(workflow: FutureSongWorkflow, video_id: str) -> list[dict]:
        runs = workflow.report_store.report_root(video_id) / "runs"
        return [
            json.loads(path.read_text(encoding="utf-8"))
            for path in sorted(runs.glob("*/run.json"))
        ]

    def test_success_records_start_and_outcome_without_touching_release_pointer(self) -> None:
        owner = {"kind": "operation-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="operation-success-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = self.workflow(scratch)
            video_id = "-Abc_123XYZ"
            report_root = workflow.report_store.ensure_report_identity(video_id)
            release_pointer = {"runId": "a" * 32, "runSha256": "b" * 64}
            report_control_path(report_root, "current-run.json").parent.mkdir(exist_ok=True)
            (report_control_path(report_root, "current-run.json")).write_text(
                json.dumps(release_pointer), encoding="utf-8"
            )

            def succeed() -> str:
                job_dir = workflow._job_dir(video_id)
                job_dir.mkdir(parents=True, exist_ok=True)
                (job_dir / "job.json").write_text(
                    json.dumps({"videoId": video_id, "state": "WAITING_FOR_EDITORIAL"}),
                    encoding="utf-8",
                )
                return "done"

            self.assertEqual(
                "done", workflow._run_mutating_operation(video_id, "New", succeed)
            )
            records = self.records(workflow, video_id)
            self.assertEqual({"start", "outcome"}, {record["phase"] for record in records})
            self.assertEqual(1, len({record["operationId"] for record in records}))
            self.assertTrue(all("reportArtifacts" not in record for record in records))
            outcome = next(record for record in records if record["phase"] == "outcome")
            self.assertEqual("complete", outcome["status"])
            self.assertEqual("success", outcome["outcomeCode"])
            self.assertEqual("WAITING_FOR_EDITORIAL", outcome["stateAfter"])
            self.assertEqual(
                release_pointer,
                json.loads((report_control_path(report_root, "current-run.json")).read_text(encoding="utf-8")),
            )
            operation_pointer = json.loads(
                (report_control_path(report_root, "current-operation.json")).read_text(encoding="utf-8")
            )
            self.assertEqual(outcome["runId"], operation_pointer["runId"])

    def test_normal_exception_and_keyboard_interrupt_get_text_free_outcomes(self) -> None:
        owner = {"kind": "operation-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="operation-failure-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            for video_id, error, status, code in (
                ("-Abc_123XYZ", FutureSongError("PRIVATE lyric https://secret"), "failed", "future-song-error"),
                ("Abcd_123XYZ", KeyboardInterrupt(), "interrupted", "keyboard-interrupt"),
            ):
                workflow = self.workflow(scratch / video_id)

                def fail() -> None:
                    raise error

                with self.assertRaises(type(error)):
                    workflow._run_mutating_operation(video_id, "Validate", fail)
                records = self.records(workflow, video_id)
                self.assertEqual(2, len(records))
                outcome = next(record for record in records if record["phase"] == "outcome")
                self.assertEqual(status, outcome["status"])
                self.assertEqual(code, outcome["outcomeCode"])
                serialized = json.dumps(records, sort_keys=True)
                self.assertNotIn("PRIVATE lyric", serialized)
                self.assertNotIn("https://secret", serialized)

    def test_hard_interruption_leaves_a_start_record_only(self) -> None:
        class HardInterruption(BaseException):
            pass

        owner = {"kind": "operation-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="operation-hard-stop-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = self.workflow(scratch)
            video_id = "-Abc_123XYZ"

            def stop() -> None:
                raise HardInterruption()

            with self.assertRaises(HardInterruption):
                workflow._run_mutating_operation(video_id, "Retry", stop)
            records = self.records(workflow, video_id)
            self.assertEqual(1, len(records))
            self.assertEqual("start", records[0]["phase"])
            pointer = json.loads(
                (report_control_path(workflow.report_store.report_root(video_id), "current-operation.json")).read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(records[0]["runId"], pointer["runId"])

    def test_validation_failure_before_alignment_state_still_gets_an_outcome(self) -> None:
        owner = {"kind": "operation-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="operation-pre-alignment-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = self.workflow(scratch)
            video_id = "-Abc_123XYZ"
            job = {"videoId": video_id, "state": "WAITING_FOR_EDITORIAL"}
            job_dir = workflow._job_dir(video_id)
            job_dir.mkdir(parents=True)
            (job_dir / "job.json").write_text(json.dumps(job), encoding="utf-8")
            workflow._load_job = lambda _video_id, **_kwargs: job
            workflow._validated_snapshot_is_current = lambda _job: False
            workflow._validate_action = workflow._validate_enhancements_action
            workflow._validate_impl = lambda _video_id: (_ for _ in ()).throw(
                FutureSongError("PRIVATE draft body")
            )
            workflow._record_validation_failure = lambda *_args: job
            workflow._commit_failed_alignment_attempt = lambda *_args: None

            with self.assertRaises(FutureSongError):
                workflow.validate(video_id)
            records = self.records(workflow, video_id)
            self.assertEqual(2, len(records))
            outcome = next(record for record in records if record["phase"] == "outcome")
            self.assertEqual("failed", outcome["status"])
            self.assertEqual("future-song-error", outcome["outcomeCode"])
            self.assertNotIn("PRIVATE draft body", json.dumps(records, sort_keys=True))
            self.assertFalse(workflow._alignment_state_path(video_id).exists())

    def test_report_commit_failure_preserves_the_original_action_failure(self) -> None:
        from contextlib import redirect_stderr
        from io import StringIO
        owner = {"kind": "operation-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="operation-report-failure-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = self.workflow(scratch)
            video_id = "-Abc_123XYZ"
            original = FutureSongError("original action failure")

            def fail_after_start() -> None:
                workflow.report_store.store.commit = lambda *_args, **_kwargs: (_ for _ in ()).throw(
                    FutureSongError("PRIVATE report failure https://secret")
                )
                raise original

            with redirect_stderr(StringIO()) as warning, self.assertRaisesRegex(
                    FutureSongError, "original action failure") as caught:
                workflow._run_mutating_operation(video_id, "Source", fail_after_start)
            self.assertIs(caught.exception, original)
            self.assertIsNone(caught.exception.__cause__)
            self.assertEqual("workflow-operation-report-failed\n", warning.getvalue())
            records = self.records(workflow, video_id)
            self.assertEqual(1, len(records))
            self.assertEqual("start", records[0]["phase"])

    def test_production_bindings_fail_closed_for_missing_or_linked_files(self) -> None:
        owner = {"kind": "operation-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="operation-bindings-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = self.workflow(scratch)
            config_path = workflow.config_path
            config_payload = config_path.read_bytes()
            config_path.unlink()
            with self.assertRaisesRegex(FutureSongError, "cannot be verified"):
                workflow.report_store.workflow_operation_bindings()
            config_path.write_bytes(config_payload)

            policy_path = workflow.policy_dir / "lyrics-policy.json"
            policy_payload = policy_path.read_bytes()
            link_target = scratch / "replacement-policy.json"
            link_target.write_bytes(policy_payload)
            policy_path.unlink()
            try:
                os.symlink(link_target, policy_path)
            except OSError:
                policy_path.write_bytes(policy_payload)
            else:
                try:
                    with self.assertRaisesRegex(FutureSongError, "cannot be verified"):
                        workflow.report_store.workflow_operation_bindings()
                finally:
                    policy_path.unlink()
                    policy_path.write_bytes(policy_payload)

            self.assertEqual(
                {"configSha256", "policySha256", "engineSourceSha256"},
                set(workflow.report_store.workflow_operation_bindings()),
            )

    def test_every_top_level_mutator_uses_the_generic_wrapper(self) -> None:
        import tempfile
        temporary = tempfile.TemporaryDirectory(dir=SCRATCH_ROOT)
        self.addCleanup(temporary.cleanup)
        workflow = self.workflow(Path(temporary.name))
        workflow._require_selected_audio = lambda _video_id: None
        calls = []

        def record(video_id, action, _callback):
            calls.append((video_id, action))
            return action

        workflow._run_mutating_operation = record
        video_id = "-Abc_123XYZ"
        self.assertEqual("New", workflow.new(f"https://youtu.be/{video_id}"))
        self.assertEqual("Source", workflow.add_lyric_source(video_id, "not-inspected"))
        self.assertEqual("Validate", workflow.validate(video_id))
        self.assertEqual("Finalize", workflow.finalize(video_id))
        self.assertEqual("Retry", workflow.retry(video_id))
        self.assertEqual(
            [(video_id, action) for action in ("New", "Source", "Validate", "Finalize", "Retry")],
            calls,
        )


class ImmutableRunReportTest(unittest.TestCase):
    def test_immutable_run_retry_finishes_artifact_and_pointer_boundaries(self) -> None:
        for boundary in ("artifact-to-run", "run-to-pointer"):
            with self.subTest(boundary=boundary), manifested_temporary_directory(
                SCRATCH_ROOT,
                prefix="immutable-retry-",
                owner={
                    "kind": "immutable-retry-test",
                    "testId": uuid.uuid4().hex,
                },
                workspace_root=WORKSPACE,
            ) as scratch:
                store = workflow_reports_module.ImmutableReportStore(WORKSPACE, 16*1024*1024)
                report_root = scratch / "reports" / "-Abc_123XYZ"
                record = {
                    "runId": "1" * 32,
                    "videoId": "-Abc_123XYZ",
                    "operation": "lyrics-alignment",
                }
                evidence = {
                    "evidenceVersion": 1,
                    "runId": record["runId"],
                    "videoId": record["videoId"],
                    "operation": record["operation"],
                    "diagnostics": {"fixture": True},
                }
                interrupted = False

                if boundary == "artifact-to-run":
                    original = workflow_reports_module.atomic_write_bytes

                    def write_bytes(path, payload, **kwargs):
                        nonlocal interrupted
                        if Path(path).name == "run.json" and not interrupted:
                            interrupted = True
                            raise OSError("interrupted before run")
                        return original(path, payload, **kwargs)

                    target = "atomic_write_bytes"
                    fault = write_bytes
                else:
                    original = workflow_reports_module.atomic_write_json

                    def write_json(path, value, **kwargs):
                        nonlocal interrupted
                        if Path(path).name == "current-run.json" and not interrupted:
                            interrupted = True
                            raise OSError("interrupted before pointer")
                        return original(path, value, **kwargs)

                    target = "atomic_write_json"
                    fault = write_json

                with patch.object(workflow_reports_module, target, side_effect=fault):
                    with self.assertRaises(OSError):
                        store.commit(
                            report_root,
                            record,
                            evidence=evidence,
                        )

                run_dir = report_root / "runs" / record["runId"]
                if boundary == "artifact-to-run":
                    (run_dir / ".run.json.abcdefgh").write_bytes(b"crash residue")
                before = {
                    path.name: (path.read_bytes(), path.stat().st_mtime_ns)
                    for path in run_dir.iterdir()
                    if not path.name.startswith(".run.json.")
                }
                self.assertEqual(
                    len([name for name in before if name.startswith("evidence.")]),
                    1,
                )
                self.assertEqual("run.json" in before, boundary == "run-to-pointer")
                self.assertFalse((report_control_path(report_root, "current-run.json")).exists())

                run_sha = store.commit(
                    report_root,
                    record,
                    evidence=evidence,
                )

                self.assertFalse((run_dir / ".run.json.abcdefgh").exists())
                for name, (payload, modified) in before.items():
                    path = run_dir / name
                    self.assertEqual(path.read_bytes(), payload)
                    self.assertEqual(path.stat().st_mtime_ns, modified)
                self.assertEqual(
                    json.loads(
                        (report_control_path(report_root, "current-run.json")).read_text(encoding="utf-8")
                    ),
                    {"runId": record["runId"], "runSha256": run_sha},
                )

    def test_run_record_is_append_only_and_omits_cached_text(self) -> None:
        owner = {"kind": "report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="report-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow.workspace_root = WORKSPACE
            workflow.config_path = scratch / "config.json"
            workflow.config_path.write_text("{}", encoding="utf-8")
            workflow.config = {'projectDir': str(PROJECT)}
            workflow.root = scratch / "future"
            workflow.jobs = workflow.root / "jobs"
            workflow.owner = workflow.root / "owner-input"
            workflow.runtime = workflow.root / "runtime"
            workflow.ready = scratch / "ready"
            workflow.pc_side_root = scratch / "pc-side"
            workflow.reports = workflow.pc_side_root / "reports"
            workflow.config = {"projectDir": str(PROJECT), "tempDir": str(scratch / "temporary-analysis")}
            video_id = "-Abc_123XYZ"
            (workflow.jobs / video_id).mkdir(parents=True)
            checkpoint = workflow.runtime / "work" / "checkpoints" / f"{video_id}.json"
            checkpoint.parent.mkdir(parents=True)
            checkpoint.write_text(
                json.dumps({"provenanceSha256": "a" * 64}), encoding="utf-8"
            )
            alignment_job = workflow.root / "lyrics-alignment" / "jobs" / video_id
            alignment_job.mkdir(parents=True)
            import wave
            import stemwise_engine.alignment_audio as alignment_audio
            import stemwise_engine.alignment_endpoints as endpoints
            import stemwise_engine.alignment_models as models
            import stemwise_engine.roformer_separation as roformer
            from stemwise_engine.lyrics_alignment import AlignmentWorkspace
            from stemwise_engine.provenance import semantic_source_identity
            audio_workspace = AlignmentWorkspace(workflow.root, workspace_root=WORKSPACE)
            routes = ("htdemucs-vocals", "kimberley-jensen-roformer-vocals", "sucial-v2-roformer-dry-vocals")
            audio_paths = {view: scratch / f"{view}.wav" for view in ("mix", *routes)}
            for index, audio in enumerate(audio_paths.values()):
                with wave.open(str(audio), "wb") as output:
                    output.setnchannels(2)
                    output.setsampwidth(2)
                    output.setframerate(48_000)
                    output.writeframes((index + 1).to_bytes(2, "little", signed=True) * 2 * 48_000)
            provenance = alignment_audio.write_audio_provenance(audio_workspace, video_id, duration_ms=1000,
                mix_audio=audio_paths["mix"], vocals_audio=audio_paths[routes[0]], demucs_model_sha256=models.PINNED_DEMUCS_SHA256)
            package = {"version": models.ROFORMER_PACKAGE_VERSION, "wheelSha256": models.ROFORMER_PACKAGE_WHEEL_SHA256,
                "sourceManifestSha256": models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
                "sourceCommit": models.ROFORMER_PACKAGE_SOURCE_COMMIT}
            runtime = {"runtimeVersion": alignment_audio.ROFORMER_RESULT_VERSION,
                "supportedResultVersions": [alignment_audio.ROFORMER_RESULT_VERSION],
                **models.roformer_runtime_pins(PROJECT), "package": package,
                "implementation": "CPython", "platform": "Linux", "machine": "x86_64",
                "requestedDevice": "cpu", "resolvedDevice": "cpu", "cudaAvailable": False,
                "cudaDeviceName": None, "cudaRuntime": None, "cudnn": None}
            for route in routes[1:]:
                definition = endpoints.ROFORMER_ROUTES[route]
                source = provenance["mix"] if definition["sourceViewId"] == "mix" else provenance["audioViews"][definition["sourceViewId"]]
                output = alignment_audio.file_identity(audio_paths[route])
                reconstruction = roformer._chunk_reconstruction(SimpleNamespace(
                    inference=SimpleNamespace(chunk_size=4800, num_overlap=2)), 48_000)
                result = {"resultVersion": alignment_audio.ROFORMER_RESULT_VERSION, "routeId": route,
                    "targetInstrument": "vocals" if route == routes[1] else "dry", "package": package,
                    "model": {key: definition[key] for key in ("checkpointSha256", "configSha256")},
                    "input": {key: source[key] for key in ("sha256", "byteCount", "stream")}, "output": output,
                    "runtime": runtime, "device": "cpu", "parameters": {"deterministicAlgorithms": True,
                        "cudnnBenchmark": False, "cudnnDeterministic": True, "modelSampleRate": 48_000, **reconstruction},
                    "stageMetrics": dict.fromkeys(("preprocessingRuntimeMs", "modelLoadRuntimeMs", "inferenceRuntimeMs",
                        "writeRuntimeMs", "runtimeMs"), 0),
                    "canonicalTimeTransform": roformer._time_transform(source["stream"], output["stream"], reconstruction)}
                provenance = alignment_audio.write_roformer_audio_provenance(audio_workspace, video_id, duration_ms=1000,
                    expected_mix_sha256=provenance["mix"]["sha256"], route_id=route, output_audio=audio_paths[route],
                    result=result, helper_sha256=semantic_source_identity(Path(roformer.__file__)))
            (alignment_job / "transcript.en.private.json").write_text(
                json.dumps(
                    {
                        "model": "large-v3",
                        "promptSha256": "e" * 64,
                        "provenance": {
                            "fasterWhisperVersion": "1",
                            "modelSnapshot": "snapshot-id",
                            "device": "cpu",
                            "computeType": "int8",
                            "decoder": {"beamSize": 5},
                        },
                        "words": [{"word": "DO-NOT-REPORT-THIS"}],
                    }
                ),
                encoding="utf-8",
            )
            import stemwise_engine.mms_asr as mms_asr
            (alignment_job / "native-asr-mix.private.json").write_text(json.dumps({
                "provenance": {"familyId": "mms-asr", "modelRevision": mms_asr.REVISION,
                               "adapter": mms_asr.ADAPTER, "assets": mms_asr.ASSETS,
                               "audioSha256": provenance["mix"]["sha256"], "promptMode": "withheld",
                               "privateText": "DO-NOT-REPORT-THIS"},
                "workerOutput": {"words": [{"raw": "DO-NOT-REPORT-THIS"}]},
            }), encoding="utf-8")
            run_id = "1" * 32
            job = {
                "videoId": video_id,
                "policyVersion": "test-policy",
                "policy": {
                    "policySha256": "f" * 64,
                    "schemaSha256": "8" * 64,
                },
            }
            state = {
                "attemptId": run_id,
                "workerStartedAtUnix": 1,
                "alignmentVersion": ALIGNMENT_VERSION,
                "model": "large-v3",
                "durationMs": 1000,
                "metrics": {"coverage": 0.9},
                "timedLineCount": 2,
                "canonicalPlainSha256": "0" * 64,
                "error": "DO-NOT-REPORT-THIS-EITHER",
                "endpointEvidence": {
                    "familyCandidateCounts": {
                        "language-wav2vec2": 2,
                        "mms-fa": 2,
                    },
                    "selectedPublisherFamilyCounts": {"mms-fa": 2},
                    "independentVerifierRoute": "legacy-route",
                },
                "roformerCascade": {
                    "cascadeVersion": 1,
                    "routes": [
                        {
                            "routeId": "sucial-v2-roformer-dry-vocals",
                            "familyId": "sucial-v2-roformer",
                            "sourceViewId": "kimberley-jensen-roformer-vocals",
                            "outputViewId": "sucial-v2-roformer-dry-vocals",
                            "status": "complete",
                            "cacheReused": False,
                            "runtimeMs": 42,
                            "privateText": "DO-NOT-REPORT-THIS-EITHER",
                        }
                    ],
                    "selectedEndpointRoute": "sucial-v2-roformer-dry-vocals",
                },
                "evidenceAttempts": [
                    {
                        "type": "faster-whisper:auto",
                        "accepted": True,
                        "reused": False,
                        "metrics": {"coverage": 0.9},
                        "privateText": "DO-NOT-REPORT-THIS-EITHER",
                    }
                ],
            }
            artifacts = {
                "sourcesSha256": "1" * 64,
                "sidecarSha256": "2" * 64,
                "childSha256": "3" * 64,
            }
            from stemwise_engine.alignment_phases import AttemptCacheBindings
            consumed = AttemptCacheBindings(state, alignment_job)
            consumed.bind('whisper-asr', alignment_job / 'transcript.en.private.json', (None, True))
            consumed.bind('mms-asr', alignment_job / 'native-asr-mix.private.json', (None, True))
            provenance_path = alignment_job / "audio-provenance.private.json"
            valid_provenance = provenance_path.read_bytes()
            provenance_path.write_text(json.dumps({**provenance,
                "vocals": {"privateText": "LEGACY-ALIAS-MUST-NOT-SURVIVE"}}), encoding="utf-8")
            with self.assertRaisesRegex(FutureSongError, "audio provenance is malformed or stale"):
                workflow.report_store.commit_alignment_run(job, run_id=run_id, editorial_sha256="9" * 64,
                    state=state, status="complete", artifacts=artifacts)
            self.assertFalse((workflow.report_store.report_root(video_id) / "runs" / run_id / "run.json").exists())
            provenance_path.write_bytes(valid_provenance)
            run_sha256 = workflow.report_store.commit_alignment_run(
                job,
                run_id=run_id,
                editorial_sha256="9" * 64,
                state=state,
                status="complete",
                artifacts=artifacts,
            )
            run_path = workflow.report_store.report_root(video_id) / "runs" / run_id / "run.json"
            raw = run_path.read_text(encoding="utf-8")
            report = json.loads(raw)
            evidence_path = run_path.parent / report["reportArtifacts"][0]["fileName"]
            authority_dir = workflow.report_store.report_root(video_id) / "authority"
            authority_reference = next(authority_dir.glob("authority-reference.*.json"))
            source_evidence = next(authority_dir.glob("source-evidence.*.json"))
            combined = "".join(
                path.read_text(encoding="utf-8")
                for path in (
                    run_path,
                    evidence_path,
                    authority_reference,
                    source_evidence,
                )
            )
            self.assertNotIn("DO-NOT-REPORT-THIS", combined)
            self.assertNotIn("LEGACY-ALIAS-MUST-NOT-SURVIVE", combined)
            self.assertNotIn("http", combined)
            diagnostics = json.loads(evidence_path.read_text())["diagnostics"]
            self.assertEqual(
                diagnostics["endpointEvidence"]["familyCandidateCounts"],
                {"language-wav2vec2": 2, "mms-fa": 2},
            )
            self.assertEqual(
                diagnostics["endpointEvidence"]["selectedPublisherFamilyCounts"],
                {"mms-fa": 2},
            )
            self.assertNotIn(
                "independentVerifierRoute", diagnostics["endpointEvidence"]
            )
            self.assertEqual(
                diagnostics["roformerCascade"]["routes"][0]["familyId"],
                "sucial-v2-roformer",
            )
            self.assertEqual(
                report["checkpointKey"]["models"]["asr"]["familyId"],
                "whisper-asr",
            )
            native = report["checkpointKey"]["models"]["nativeAsr"]
            self.assertEqual(native["familyId"], "mms-asr")
            self.assertEqual(native["routes"][0]["adapter"], mms_asr.ADAPTER)
            self.assertEqual(native["routes"][0]["cacheSha256"], hashlib.sha256(
                (alignment_job / "native-asr-mix.private.json").read_bytes()).hexdigest())
            self.assertIn(
                "sucial-v2-roformer-dry-vocals",
                report["checkpointKey"]["audio"]["audioViews"],
            )
            self.assertNotIn("vocals", report["checkpointKey"]["audio"])
            self.assertEqual(
                report["checkpointKey"]["helpers"]["roformer_separation.py"],
                semantic_source_identity(PROJECT / "src" / "roformer_separation.py"),
            )
            helper = scratch/'roformer_separation.py'
            helper.write_bytes((PROJECT/'src/roformer_separation.py').read_bytes())
            helper.write_text(helper.read_text(encoding='utf-8') + '\n# Clarified documentation.\n', encoding='utf-8')
            self.assertEqual(semantic_source_identity(helper), report['checkpointKey']['helpers']['roformer_separation.py'])
            helper.write_text(helper.read_text(encoding='utf-8') + '\nEXECUTABLE_CHANGE = True\n', encoding='utf-8')
            self.assertNotEqual(semantic_source_identity(helper), report['checkpointKey']['helpers']['roformer_separation.py'])
            self.assertTrue(authority_reference.is_file())
            self.assertTrue(source_evidence.is_file())
            preserved = {
                authority_reference: authority_reference.read_bytes(),
                source_evidence: source_evidence.read_bytes(),
            }
            workflow.report_store.write_report_authority_references(
                video_id, "a" * 64, "b" * 64
            )
            self.assertEqual(
                preserved,
                {path: path.read_bytes() for path in preserved},
            )
            self.assertTrue(
                workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=run_id,
                    run_sha256=run_sha256,
                    operation="lyrics-alignment",
                    artifacts=artifacts,
                    schema_sha256="8" * 64,
                    require_current_pointer=True,
                )
            )
            evidence_path.write_bytes(evidence_path.read_bytes() + b" ")
            self.assertFalse(
                workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=run_id,
                    run_sha256=run_sha256,
                    operation="lyrics-alignment",
                    artifacts=artifacts,
                    schema_sha256="8" * 64,
                    require_current_pointer=True,
                )
            )
            with self.assertRaises(FutureSongError):
                workflow.report_store.commit_alignment_run(
                    job,
                    run_id=run_id,
                    editorial_sha256="8" * 64,
                    state=state,
                    status="failed",
                )

    def test_finalization_run_is_manifest_only_and_schema_bound(self) -> None:
        owner = {"kind": "finalization-report-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="finalization-report-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow.workspace_root = WORKSPACE
            workflow.config_path = scratch / "config.json"
            workflow.config_path.write_text("{}", encoding="utf-8")
            workflow.root = scratch / "future"
            workflow.config = {'projectDir': str(PROJECT)}
            workflow.jobs = workflow.root / "jobs"
            workflow.owner = workflow.root / "owner-input"
            workflow.pc_side_root = scratch / "pc-side"
            workflow.reports = workflow.pc_side_root / "reports"
            video_id = "-Abc_123XYZ"
            (workflow.jobs / video_id).mkdir(parents=True)
            destination = scratch / "ready" / f"{video_id}.stemwise.zip"
            destination.parent.mkdir()
            destination.write_bytes(b"package")
            package_sha256 = hashlib.sha256(destination.read_bytes()).hexdigest()
            job = {
                "videoId": video_id,
                "policy": {"schemaSha256": "8" * 64},
                "validation": {
                    "reportSha256": "7" * 64,
                    "runId": "1" * 32,
                    "runSha256": "6" * 64,
                    "alignmentRunId": "2" * 32,
                    "alignmentRunSha256": "5" * 64,
                },
            }
            run_id, run_sha256 = workflow.report_store.commit_finalization_run(
                job,
                destination=destination,
                package_sha256=package_sha256,
            )
            run_path = workflow.report_store.report_root(video_id) / "runs" / run_id / "run.json"
            record = json.loads(run_path.read_text(encoding="utf-8"))
            self.assertNotIn("reportArtifacts", record)
            self.assertTrue(
                workflow.report_store.immutable_run_matches(
                    video_id,
                    run_id=run_id,
                    run_sha256=run_sha256,
                    operation="package-finalization",
                    artifacts={
                        "workspaceRelativePath": destination.relative_to(
                            WORKSPACE
                        ).as_posix(),
                        "sha256": package_sha256,
                        "reviewEvidenceRetained": True,
                    },
                    schema_sha256="8" * 64,
                    require_current_pointer=True,
                    checkpoint_requirements=workflow.report_store.finalization_checkpoint_requirements(
                        job, package_sha256
                    ),
                )
            )

    def test_alignment_exit_uses_terminal_status_before_sanitized_reason_codes(self) -> None:
        owner = {"kind": "classification-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="classification-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow.workspace_root = WORKSPACE
            workflow.root = scratch / "future"
            workflow.runtime = workflow.root / "runtime"
            workflow.analysis_temp = scratch / "temporary-analysis"
            project = scratch / "project"
            workflow.config_path = project / "config.json"
            workflow.config = {"projectDir": str(project)}
            sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
            from process_fixture import prepare_runtime_layout
            self.assertEqual(project.parent, scratch)
            prepare_runtime_layout(project)
            for path in (
                project / ".venv-lyrics-align" / "bin" / "python",
                project / "src" / "lyrics_align_pc.py",
                workflow.runtime / "children" / "-Abc_123XYZ.stemwise.zip",
            ):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"test")
            site_packages = project / ".venv-lyrics-align" / "lib" / "python3.10" / "site-packages"
            for package in ("cublas", "cudnn", "cuda_nvrtc"):
                (site_packages / "nvidia" / package / "lib").mkdir(parents=True)
            state_path = workflow._alignment_state_path("-Abc_123XYZ")
            state_path.parent.mkdir(parents=True, exist_ok=True)
            state_path.write_text(
                json.dumps(
                    {
                        "status": "line-unresolved",
                        "reasons": ["low-coverage", "private words are not a code"],
                    }
                ),
                encoding="utf-8",
            )
            failed = SimpleNamespace(returncode=1, stdout="", stderr="")
            with patch.object(future_song_module, "run_process_tree", return_value=failed):
                with self.assertRaises(FutureSongOutcomeError) as raised:
                    workflow._align_future_lyrics(
                        "-Abc_123XYZ",
                        "1" * 32,
                        "2" * 64,
                        "3" * 64,
                        "4" * 64,
                        scratch / "analysis.wav",
                    )
            self.assertEqual(raised.exception.state, "LINE_UNRESOLVED")
            self.assertEqual(raised.exception.code, "line-unresolved")

            state_path.write_text(
                json.dumps(
                    {
                        "status": "retryable-technical",
                        "reasons": ["TimeoutError", "private words are not a code"],
                    }
                ),
                encoding="utf-8",
            )
            with patch.object(future_song_module, "run_process_tree", return_value=failed):
                with self.assertRaises(FutureSongOutcomeError) as raised:
                    workflow._align_future_lyrics(
                        "-Abc_123XYZ",
                        "1" * 32,
                        "2" * 64,
                        "3" * 64,
                        "4" * 64,
                        scratch / "analysis.wav",
                    )
            self.assertEqual(raised.exception.state, "RETRYABLE_TECHNICAL")
            self.assertEqual(raised.exception.code, "retryable-technical")

    def test_needs_lyrics_can_advance_to_the_authority_gate(self) -> None:
        temporary = tempfile.TemporaryDirectory(dir=SCRATCH_ROOT)
        self.addCleanup(temporary.cleanup)
        workflow = initialized_workflow(Path(temporary.name))
        workflow._load_job = lambda _video_id: {
            "videoId": "-Abc_123XYZ",
            "state": "NEEDS_LYRICS",
        }

        def authority_gate(_job: dict[str, object]) -> None:
            raise FutureSongError("authority-gate-reached")

        workflow._validate_authority_outcome = authority_gate
        with self.assertRaisesRegex(FutureSongError, "authority-gate-reached"):
            workflow._validate_impl("-Abc_123XYZ")

    def test_snapshot_rejects_missing_or_tampered_run_records_and_pointer(self) -> None:
        owner = {"kind": "snapshot-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="snapshot-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow._windows_path = lambda path: str(path)
            workflow.workspace_root = WORKSPACE
            workflow.root = scratch / "future"
            workflow.jobs = workflow.root / "jobs"
            workflow.runtime = workflow.root / "runtime"
            workflow.owner = workflow.root / "owner-input"
            workflow.ready = scratch / "ready"
            workflow.pc_side_root = scratch / "pc-side"
            workflow.reports = workflow.pc_side_root / "reports"
            workflow.config = {"projectDir": str(PROJECT), "tempDir": str(scratch / "temporary-analysis")}
            workflow.analysis_temp = Path(workflow.config["tempDir"])
            for directory in (
                workflow.jobs,
                workflow.runtime / "children",
                workflow.owner,
                workflow.ready,
            ):
                directory.mkdir(parents=True, exist_ok=True)

            video_id = "-Abc_123XYZ"
            job_dir = workflow.jobs / video_id
            job_dir.mkdir(parents=True)
            report_root = workflow.report_store.report_root(video_id)
            report_root.parent.mkdir(parents=True)
            create_manifested_directory(
                report_root.parent,
                report_root,
                owner={"kind": "private-song-report", "videoId": video_id},
                workspace_root=WORKSPACE,
            )
            authority_dir = report_root / "authority"
            authority_dir.mkdir()
            sources = job_dir / "sources.json"
            sidecar = workflow.owner / f"{video_id}.lyrics.json"
            child = workflow.runtime / "children" / f"{video_id}.stemwise.zip"
            sources.write_text('{"videoId":"-Abc_123XYZ"}\n', encoding="utf-8")
            child.write_bytes(b"validated-child")
            cover = b"validated-cover"
            artwork_sha = hashlib.sha256(cover).hexdigest()
            child_document = {"source": {"videoId": video_id}}
            read_child_patcher = patch.object(
                future_song_module,
                "read_child",
                return_value=(child_document, cover),
            )
            read_child_patcher.start()
            self.addCleanup(read_child_patcher.stop)
            workflow._runtime_config = lambda: {}

            alignment_state_path = workflow._alignment_state_path(video_id)
            alignment_state_path.parent.mkdir(parents=True)
            candidate = alignment_state_path.with_name("candidate.private.lyrics.json")
            candidate.write_bytes(b"aligned-candidate\n")
            original = {
                "id": "original",
                "kind": "original",
                "language": "en",
                "script": "Latn",
                "format": "plain",
                "text": "Display",
                "source": {"name": "Synthetic reviewed original"},
            }
            from stemwise_engine.lyrics_tokenization import TOKENIZATION_VERSION
            from stemwise_engine.contract import validate_lyrics
            lyrics = {'status': 'partial', 'reason': 'Only the reviewed original is available.',
                'tokenizationVersion': TOKENIZATION_VERSION, 'tracks': [original]}
            validate_lyrics(lyrics, duration_ms=1000)
            future_song_module.atomic_write_json(sidecar, lyrics)
            child_document['lyrics'] = lyrics
            mapping = build_lexical_occurrence_mapping(video_id, original, (0,))
            mapping_identity = lexical_mapping_identity(mapping)
            mapping_path = alignment_state_path.with_name("mapping.private.json")
            future_song_module.atomic_write_json(
                mapping_path,
                {
                    **mapping,
                    "mappingSha256": mapping_identity["mappingSha256"],
                    "numeric": {},
                },
            )
            mapping_identity["mappingArtifactSha256"] = hashlib.sha256(
                mapping_path.read_bytes()
            ).hexdigest()
            future_song_module.atomic_write_json(
                alignment_state_path,
                {
                    "videoId": video_id,
                    "attemptId": "a" * 32,
                    "status": "committed-lines",
                    "alignmentVersion": ALIGNMENT_VERSION,
                    **mapping_identity,
                },
            )

            digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
            editorial_sha = "1" * 64
            workflow._editorial_sha = lambda _job: editorial_sha
            sources_sha = digest(sources)
            sidecar_sha = digest(sidecar)
            child_sha = digest(child)
            retained_audio = {
                "sha256": "9" * 64,
                "byteCount": 100,
                "durationMs": 1000,
            }
            candidate_sha = digest(candidate)
            alignment_artifacts = {
                "sourcesSha256": sources_sha,
                "sidecarSha256": candidate_sha,
                "alignmentCandidateSha256": candidate_sha,
                "mappingArtifactSha256": mapping_identity[
                    "mappingArtifactSha256"
                ],
                "alignmentStateSha256": digest(alignment_state_path),
                "analysisAudioSha256": retained_audio["sha256"],
                "artworkSha256": artwork_sha,
            }
            workflow._pipeline = lambda: object()
            workflow._completed_analysis_audio = lambda _pipeline, _video_id: {
                **retained_audio,
                "artworkSha256": artwork_sha,
                "path": scratch / "temporary-analysis" / video_id / "analysis.wav",
            }
            workflow.report_store.write_report_authority_references(
                video_id, editorial_sha, sources_sha
            )

            alignment_run_id = "a" * 32
            alignment_run = report_root / "runs" / alignment_run_id / "run.json"
            alignment_run.parent.mkdir(parents=True)
            alignment_evidence = {
                "evidenceVersion": 1,
                "runId": alignment_run_id,
                "videoId": video_id,
                "operation": "lyrics-alignment",
                "diagnostics": {"resultClass": "committed-lines"},
            }
            alignment_evidence_bytes = (
                json.dumps(alignment_evidence, indent=2) + "\n"
            ).encode()
            alignment_evidence_sha = hashlib.sha256(alignment_evidence_bytes).hexdigest()
            alignment_evidence_name = f"evidence.{alignment_evidence_sha}.json"
            (alignment_run.parent / alignment_evidence_name).write_bytes(
                alignment_evidence_bytes
            )
            alignment_checkpoint = {"authority": {"editorialSha256": editorial_sha}}
            alignment_checkpoint_sha = hashlib.sha256(
                json.dumps(
                    alignment_checkpoint,
                    ensure_ascii=True,
                    allow_nan=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode()
            ).hexdigest()
            alignment_record = {
                "reportVersion": 2,
                "runId": alignment_run_id,
                "videoId": video_id,
                "operation": "lyrics-alignment",
                "status": "complete",
                "checkpointKey": alignment_checkpoint,
                "checkpointKeySha256": alignment_checkpoint_sha,
                "schemaValidation": {
                    "result": "pass",
                    "externalFormatVersion": 2,
                    "schemaSha256": "8" * 64,
                },
                "reportArtifacts": [
                    {
                        "kind": "alignment-evidence",
                        "fileName": alignment_evidence_name,
                        "sha256": alignment_evidence_sha,
                        "byteCount": len(alignment_evidence_bytes),
                    }
                ],
                "artifacts": alignment_artifacts,
            }
            alignment_bytes = (json.dumps(alignment_record, sort_keys=True) + "\n").encode()
            alignment_run.write_bytes(alignment_bytes)
            alignment_run_sha = digest(alignment_run)
            current_run = report_control_path(report_root, "current-run.json")
            current_run.parent.mkdir(exist_ok=True)
            current_run.write_text(
                json.dumps(
                    {"runId": alignment_run_id, "runSha256": alignment_run_sha}
                ),
                encoding="utf-8",
            )

            display_run_id = "d" * 32
            display_root = job_dir / "display-review"
            display_root.mkdir()
            display_attempt = display_root / display_run_id
            create_manifested_directory(
                display_root,
                display_attempt,
                owner={
                    "kind": "display-review-attempt",
                    "videoId": video_id,
                    "attemptId": display_run_id,
                },
                workspace_root=WORKSPACE,
            )
            declaration = display_attempt / "declaration.json"
            declaration.write_text('{"declarationVersion":1}\n', encoding="utf-8")
            display_body = display_attempt / "original.txt"
            display_body.write_text("Display\n", encoding="utf-8")
            declaration_sha = digest(declaration)
            display_original_sha = hashlib.sha256(b"Display").hexdigest()
            display_artifacts = {
                "declarationSha256": declaration_sha,
                "originalSha256": display_original_sha,
                "sourcesSha256": sources_sha,
                "sidecarSha256": sidecar_sha,
                "childSha256": child_sha,
                "artworkSha256": artwork_sha,
                "mappingArtifactSha256": mapping_identity[
                    "mappingArtifactSha256"
                ],
                "alignmentRunSha256": alignment_run_sha,
            }
            display_checkpoint = {"displayReviewVersion": 1}
            display_checkpoint_sha = hashlib.sha256(
                json.dumps(
                    display_checkpoint,
                    ensure_ascii=True,
                    allow_nan=False,
                    sort_keys=True,
                    separators=(",", ":"),
                ).encode()
            ).hexdigest()
            display_record = {
                "reportVersion": 2,
                "runId": display_run_id,
                "videoId": video_id,
                "operation": "display-review",
                "status": "complete",
                "checkpointKey": display_checkpoint,
                "checkpointKeySha256": display_checkpoint_sha,
                "schemaValidation": {
                    "result": "pass",
                    "externalFormatVersion": 2,
                    "schemaSha256": "8" * 64,
                },
                "artifacts": display_artifacts,
            }
            display_run_sha = workflow.report_store.store.commit(
                report_root, display_record
            )

            validation_report = report_root / "validation.json"
            validation_record = {
                "result": "pass",
                "stage": "display-review",
                "videoId": video_id,
                "policyVersion": POLICY_VERSION,
                "generatedAt": "2026-08-23T00:00:00Z",
                "editorialSha256": editorial_sha,
                "sourcesSha256": sources_sha,
                "sidecarSha256": sidecar_sha,
                "childSha256": child_sha,
                "artworkSha256": artwork_sha,
                "lyricsTiming": {},
                "displayReviewAttemptId": display_run_id,
                "displayReviewDeclarationSha256": declaration_sha,
                "displayOriginalSha256": display_original_sha,
                "mappingSha256": mapping_identity["mappingSha256"],
                "alignmentRunId": alignment_run_id,
                "alignmentRunSha256": alignment_run_sha,
                "alignmentArtifacts": alignment_artifacts,
                "displayReviewArtifacts": display_artifacts,
                "runId": display_run_id,
                "runSha256": display_run_sha,
                "retainedAnalysisAudio": retained_audio,
                "temporaryAudioRetained": True,
            }
            validation_report.write_text(
                json.dumps(validation_record), encoding="utf-8"
            )
            job = {
                "videoId": video_id,
                "state": "VALIDATED",
                "policyVersion": POLICY_VERSION,
                "policy": {"schemaSha256": "8" * 64},
                "displayReview": {
                    "attemptId": display_run_id,
                    "declarationSha256": declaration_sha,
                    "preReviewTextSha256": display_original_sha,
                    "alignmentRunId": alignment_run_id,
                    "alignmentRunSha256": alignment_run_sha,
                    "result": "accepted-display-only",
                    "originalSha256": display_original_sha,
                    "runId": display_run_id,
                    "runSha256": display_run_sha,
                },
                "validation": {
                    "report": workflow._windows_path(validation_report),
                    "reportSha256": digest(validation_report),
                    "editorialSha256": editorial_sha,
                    "sourcesSha256": sources_sha,
                    "sidecarSha256": sidecar_sha,
                    "childSha256": child_sha,
                    "artworkSha256": artwork_sha,
                    "lyricsTiming": {},
                    "validatedAt": validation_record["generatedAt"],
                    "displayReviewAttemptId": display_run_id,
                    "displayReviewDeclarationSha256": declaration_sha,
                    "displayOriginalSha256": display_original_sha,
                    "alignmentRunId": alignment_run_id,
                    "alignmentRunSha256": alignment_run_sha,
                    "runId": display_run_id,
                    "runSha256": display_run_sha,
                },
            }
            self.assertTrue(workflow._validated_snapshot_is_current(job))

            alignment_run.write_bytes(alignment_bytes + b" ")
            self.assertFalse(workflow._validated_snapshot_is_current(job))
            alignment_run.write_bytes(alignment_bytes)
            alignment_run.unlink()
            self.assertFalse(workflow._validated_snapshot_is_current(job))
            alignment_run.write_bytes(alignment_bytes)
            current_run.write_text(
                json.dumps({"runId": "b" * 32, "runSha256": "0" * 64}),
                encoding="utf-8",
            )
            self.assertFalse(workflow._validated_snapshot_is_current(job))

            destination = workflow.ready / child.name
            destination.write_bytes(child.read_bytes())
            finalization_run_id = "b" * 32
            finalization_run = (
                report_root / "runs" / finalization_run_id / "run.json"
            )
            finalization_run.parent.mkdir(parents=True)
            finalization_checkpoint = {
                **workflow.report_store.finalization_checkpoint_requirements(job, child_sha),
                "configurationSha256": "c" * 64,
                "helperSha256": {
                    "common.py": "d" * 64,
                    "future_song.py": "e" * 64,
                    "packages.py": "f" * 64,
                },
            }
            finalization_record = {
                "reportVersion": 2,
                "runId": finalization_run_id,
                "videoId": video_id,
                "operation": "package-finalization",
                "status": "complete",
                "checkpointKey": finalization_checkpoint,
                "checkpointKeySha256": hashlib.sha256(
                    json.dumps(
                        finalization_checkpoint,
                        ensure_ascii=True,
                        allow_nan=False,
                        sort_keys=True,
                        separators=(",", ":"),
                    ).encode()
                ).hexdigest(),
                "schemaValidation": {
                    "result": "pass",
                    "externalFormatVersion": 2,
                    "schemaSha256": "8" * 64,
                },
                "artifacts": {
                    "workspaceRelativePath": destination.relative_to(
                        WORKSPACE
                    ).as_posix(),
                    "sha256": child_sha,
                    "reviewEvidenceRetained": True,
                },
            }
            finalization_bytes = (
                json.dumps(finalization_record, sort_keys=True) + "\n"
            ).encode()
            finalization_run.write_bytes(finalization_bytes)
            finalization_run_sha = digest(finalization_run)
            current_run.write_text(
                json.dumps(
                    {
                        "runId": finalization_run_id,
                        "runSha256": finalization_run_sha,
                    }
                ),
                encoding="utf-8",
            )
            job["state"] = "DELIVERED"
            job["delivery"] = {
                "path": workflow._windows_path(destination),
                "sha256": child_sha,
                "bytes": destination.stat().st_size,
                "deliveredAt": "2026-08-23T00:01:00Z",
                "readyToImport": True,
                "reviewEvidenceRetained": True,
                "runId": finalization_run_id,
                "runSha256": finalization_run_sha,
            }
            self.assertEqual(display_body.read_bytes(), b"Display\n")
            self.assertTrue(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            display_body.unlink()
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            marker = job["delivery"].pop("reviewEvidenceRetained")
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            job["delivery"]["reviewEvidenceRetained"] = marker
            display_body.write_bytes(b"Display\n")
            finalization_run.write_bytes(finalization_bytes + b" ")
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )
            finalization_run.write_bytes(finalization_bytes)
            finalization_run.unlink()
            self.assertFalse(
                workflow._validated_snapshot_is_current(job, require_delivery=True)
            )


class AuthorityOutcomeTest(unittest.TestCase):
    @staticmethod
    def review(outcome: str) -> dict[str, object]:
        value: dict[str, object] = {
            "formatVersion": 2,
            "policyVersion": POLICY_VERSION,
            "videoId": "-Abc_123XYZ",
            "authorityOutcome": outcome,
            "recordingOrSourceAssociationReviewed": outcome != "pending",
            "youtubeSurfaceVariantsReviewed": outcome != "pending",
            "publicSourceResearchComplete": outcome != "pending",
            "normalYouTubeVideoId": "-Abc_123XYZ",
            "youtubeMusicVideoId": "-Abc_123XYZ",
            "lyricEvidenceVideoIds": ["-Abc_123XYZ"],
            "unresolvedMaterialIssues": [],
        }
        if outcome != "pending":
            value.update(
                reviewer="Codex",
                completedAt="2026-08-23T00:00:00Z",
            )
        if outcome == "resolved":
            value.update(
                sourceDisposition="accepted-exact-recording",
                originalReviewed=True,
            )
        elif outcome == "authority-unresolved":
            value["unresolvedMaterialIssues"] = ["PRIVATE AUTHORITY QUESTION"]
        return value

    @staticmethod
    def sources() -> dict[str, object]:
        surface = {"checked": True}
        return {
            "videoId": "-Abc_123XYZ",
            "exactSurfaceChecks": {
                "youtubeMusic": surface,
                "normalYouTube": surface,
            },
            "lyricEvidenceSources": [
                {
                    "videoId": "-Abc_123XYZ",
                    "youtubeMusic": surface,
                    "normalYouTube": surface,
                }
            ],
        }

    def test_v2_pending_resolved_and_unresolved_are_distinct(self) -> None:
        for outcome in ("pending", "resolved", "authority-unresolved"):
            review = self.review(outcome)
            self.assertEqual(
                validate_agent_review(
                    review,
                    video_id="-Abc_123XYZ",
                    available_lyric_source_ids={"-Abc_123XYZ"},
                ),
                outcome,
            )
        legacy = self.review("pending")
        legacy["formatVersion"] = 1
        with self.assertRaises(FutureSongError):
            validate_agent_review(
                legacy,
                video_id="-Abc_123XYZ",
                available_lyric_source_ids={"-Abc_123XYZ"},
            )
        unresolved = self.review("authority-unresolved")
        unresolved["publicSourceResearchComplete"] = False
        with self.assertRaisesRegex(FutureSongError, "research"):
            validate_agent_review(
                unresolved,
                video_id="-Abc_123XYZ",
                available_lyric_source_ids={"-Abc_123XYZ"},
            )

    def test_pending_preserves_state_without_touching_audio_or_drafts(self) -> None:
        temporary = tempfile.TemporaryDirectory(dir=SCRATCH_ROOT)
        self.addCleanup(temporary.cleanup)
        workflow = initialized_workflow(Path(temporary.name))
        job = {"videoId": "-Abc_123XYZ", "state": "NEEDS_LYRICS"}
        workflow._load_job = lambda _video_id: job
        workflow._validate_authority_outcome = lambda _job: (
            "pending",
            self.review("pending"),
            self.sources(),
        )
        workflow._pipeline = lambda: self.fail("pending authority must not touch audio")
        workflow.summary = lambda value: {"state": value["state"]}
        self.assertEqual(
            workflow._validate_impl("-Abc_123XYZ"), {"state": "NEEDS_LYRICS"}
        )
        self.assertEqual(job["state"], "NEEDS_LYRICS")

    def test_authority_unresolved_commits_body_free_run_and_job_state(self) -> None:
        owner = {"kind": "authority-outcome-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="authority-outcome-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow.workspace_root = WORKSPACE
            workflow.root = scratch / "future"
            workflow.jobs = workflow.root / "jobs"
            workflow.owner = workflow.root / "owner-input"
            workflow.pc_side_root = scratch / "pc-side"
            workflow.reports = workflow.pc_side_root / "reports"
            workflow._windows_path = lambda path: str(path)
            workflow._write_task = lambda _job: None
            video_id = "-Abc_123XYZ"
            job_dir = workflow.jobs / video_id
            job_dir.mkdir(parents=True)
            workflow.analysis_temp = scratch / "analysis"
            review = self.review("authority-unresolved")
            sources = self.sources()
            (job_dir / "agent-review.json").write_text(
                json.dumps(review), encoding="utf-8"
            )
            (job_dir / "sources.json").write_text(
                json.dumps(sources), encoding="utf-8"
            )
            job = {
                "videoId": video_id,
                "state": "WAITING_FOR_EDITORIAL",
                "policy": {"schemaSha256": "8" * 64},
            }
            workflow._load_job = lambda _video_id: job
            workflow._save_job = lambda _job: None
            with self.assertRaises(FutureSongOutcomeError) as raised:
                workflow._validate_impl(video_id)
            self.assertEqual(raised.exception.state, "AUTHORITY_UNRESOLVED")
            self.assertEqual(job["state"], "AUTHORITY_UNRESOLVED")
            run_path = (
                workflow.report_store.report_root(video_id)
                / "runs"
                / job["outcome"]["runId"]
                / "run.json"
            )
            projection = workflow.report_store.report_root(video_id) / "authority" / "outcome.json"
            combined = run_path.read_text(encoding="utf-8") + projection.read_text(
                encoding="utf-8"
            )
            self.assertNotIn("PRIVATE AUTHORITY QUESTION", combined)
            self.assertEqual(json.loads(run_path.read_text())["operation"], "authority-outcome")

    def test_typed_outcome_is_not_relabelled_by_generic_failure_path(self) -> None:
        for state, code in (
            ("LINE_UNRESOLVED", "line-unresolved"),
            ("RETRYABLE_TECHNICAL", "retryable-technical"),
        ):
            with self.subTest(state=state):
                temporary = tempfile.TemporaryDirectory(dir=SCRATCH_ROOT)
                self.addCleanup(temporary.cleanup)
                workflow = initialized_workflow(Path(temporary.name))
                workflow._run_mutating_operation = (
                    lambda _video_id, _action, callback: callback()
                )
                job = {"videoId": "-Abc_123XYZ", "state": "ALIGNING_LYRICS"}
                workflow._load_job = lambda _video_id: job
                workflow._validated_snapshot_is_current = lambda _job: False
                workflow._validate_action = workflow._validate_enhancements_action
                workflow._validate_impl = lambda _video_id: (_ for _ in ()).throw(
                    FutureSongOutcomeError(state, code)
                )
                workflow._record_validation_failure = lambda *_args: self.fail(
                    "generic failure path must not run"
                )
                workflow._record_validation_outcome = lambda _video_id, outcome: job.update(
                    state=outcome.state, outcome={"code": outcome.code}
                )
                workflow._commit_failed_alignment_attempt = lambda *_args: None
                with self.assertRaises(FutureSongOutcomeError):
                    workflow.validate("-Abc_123XYZ")
                self.assertEqual(job["state"], state)
                self.assertEqual(job["outcome"], {"code": code})
                self.assertNotIn("error", job)

    def test_outcome_persistence_keeps_only_typed_code(self) -> None:
        owner = {"kind": "outcome-state-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="outcome-state-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow.workspace_root = WORKSPACE
            workflow.root = scratch / "future"
            workflow._write_task = lambda _job: None
            workflow._save_job = lambda _job: None
            workflow._cleanup_alignment_attempt_temp = lambda *_args: 0
            job = {
                "videoId": "-Abc_123XYZ",
                "state": "ALIGNING_LYRICS",
                "activeAlignmentAttemptId": "1" * 32,
                "error": "C:\\private\\lyrics.txt secret words",
            }
            workflow._load_job = lambda _video_id: job
            workflow._record_validation_outcome(
                "-Abc_123XYZ",
                FutureSongOutcomeError(
                    "RETRYABLE_TECHNICAL", "retryable-technical"
                ),
            )
            self.assertEqual(job["state"], "RETRYABLE_TECHNICAL")
            self.assertEqual(job["outcome"], {"code": "retryable-technical"})
            self.assertNotIn("error", job)
            self.assertEqual(job["lastAlignmentAttemptId"], "1" * 32)


class EditorialRetrySafetyTest(unittest.TestCase):
    def test_partial_owner_draft_fails_closed_without_changing_bytes(self) -> None:
        owner = {"kind": "partial-draft-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="partial-draft-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            draft = scratch / "drafts"
            draft.mkdir()
            original = draft / "original.txt"
            original.write_bytes(b"owner partial bytes\n")
            before = original.read_bytes()
            with self.assertRaisesRegex(FutureSongError, "incomplete"):
                source_acquisition.preserved_local_draft_acquisition(
                    {"videoId": "-Abc_123XYZ"}, draft
                )
            self.assertEqual(original.read_bytes(), before)
            self.assertEqual([path.name for path in draft.iterdir()], ["original.txt"])

    def test_editorial_setup_retry_preserves_existing_owner_inputs(self) -> None:
        owner = {"kind": "retry-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="retry-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workflow = initialized_workflow(scratch)
            workflow.workspace_root = WORKSPACE
            workflow.root = scratch / "future"
            workflow.jobs = workflow.root / "jobs"
            workflow.runtime = workflow.root / "runtime"
            workflow.ready = scratch / "ready"
            project = scratch / "project"
            project.mkdir()
            workflow.config = {
                "projectDir": str(project.resolve()),
                "tempDir": str(scratch / "temporary-analysis"),
            }
            workflow._runtime_config = lambda: {}

            video_id = "-Abc_123XYZ"
            job_dir = workflow.jobs / video_id
            draft = job_dir / "drafts"
            candidates = job_dir / "candidates"
            draft.mkdir(parents=True)
            candidates.mkdir()
            (draft / "lyrics.meta.json").write_text(
                json.dumps(
                    {"originalLanguage": "en", "componentLanguages": []}
                ),
                encoding="utf-8",
            )
            original = draft / "original.txt"
            original.write_text("owner-edited words\n", encoding="utf-8")
            (candidates / "owner-note.txt").write_text(
                "keep this evidence", encoding="utf-8"
            )
            sources_path = job_dir / "sources.json"
            sources_path.write_text(
                json.dumps(
                    {
                        "formatVersion": 1,
                        "videoId": video_id,
                        "lyricEvidenceSources": [],
                        "ownerDecision": "preserve",
                    }
                ),
                encoding="utf-8",
            )
            review_path = job_dir / "agent-review.json"
            review_path.write_text(
                json.dumps({"ownerDecision": "preserve"}), encoding="utf-8"
            )
            needed_path = job_dir / "LYRICS_NEEDED.txt"
            needed_path.write_text("owner submission", encoding="utf-8")
            preserved = {
                path: path.read_bytes()
                for path in (
                    original,
                    candidates / "owner-note.txt",
                    review_path,
                    needed_path,
                )
            }

            class Pipeline:
                @staticmethod
                def prepared_inputs(_video_id):
                    return None

                @staticmethod
                def process_song(_item: dict[str, object]) -> str:
                    return "skipped"

            workflow._pipeline = lambda: Pipeline()
            workflow._analysis_summary = lambda _document: {
                "formatVersion": 1,
                "containsLyrics": False,
                "videoId": video_id,
            }
            job = {
                "videoId": video_id,
                "state": "EDITORIAL_SETUP_FAILED",
                "url": f"https://music.youtube.com/watch?v={video_id}",
            }
            workflow._job_path(video_id).write_text(json.dumps(job), encoding='utf-8')
            with (
                patch.object(
                    workflow.source,
                    "song_info",
                    return_value={
                        "title": "Song",
                        "uploader": "Artist",
                        "duration": 240,
                    },
                ),
                patch.object(
                    source_acquisition,
                    "collect_normal_youtube_candidates",
                    return_value={"records": [], "usableLyricCandidateCount": 0},
                ),
                patch.object(
                    source_acquisition,
                    "read_child",
                    return_value=(__import__("test_packages").song_document(video_id), None),
                ),
                patch.object(
                    source_acquisition,
                    "acquire_exact_item_draft",
                    return_value={"decision": "skipped-existing"},
                ) as acquire,
                patch.object(
                    source_acquisition,
                    "discover_counterpart_candidates",
                    return_value={"candidateCount": 0},
                ),
            ):
                workflow.acquisition(job['videoId']).run(job)

            acquire.assert_not_called()
            self.assertEqual(job["state"], "WAITING_FOR_EDITORIAL")
            for path, expected in preserved.items():
                self.assertEqual(path.read_bytes(), expected)
            refreshed_sources = json.loads(sources_path.read_text(encoding="utf-8"))
            self.assertEqual(refreshed_sources["ownerDecision"], "preserve")
            self.assertTrue(refreshed_sources["containsLyrics"])
            self.assertTrue(refreshed_sources["draftLyricsPresent"])
            self.assertEqual(refreshed_sources["lyricsAvailability"], "draft-acquired")


class RemovedTestExistingPathTest(unittest.TestCase):
    def test_analysis_cleanup_root_cannot_overlap_durable_campaign_state(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            workspace = Path(name)
            pc_side = workspace / "PC side"
            builder = pc_side / "stemwise-playlist-builder"
            install_root = builder / "future-songs"
            ready = workspace / "Ready to Import"
            for path in (install_root, ready, pc_side / "model-cache"):
                path.mkdir(parents=True)
            config_path = builder / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            config = {
                "futureRootWindows": str(install_root),
                "futureReadyDirWindows": str(ready),
                "pcSideRootWindows": str(pc_side),
                "tempDir": str(install_root),
            }
            with (
                patch.object(future_song_module, "load_config", return_value=config),
                patch.object(FutureSongWorkflow, "_validate_policy", return_value={}),
                self.assertRaisesRegex(
                    FutureSongError,
                    "Temporary cleanup root overlaps future policy state",
                ),
            ):
                FutureSongWorkflow(config_path)

    def test_current_policy_state_is_disjoint_from_retired_job_and_audio_state(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            workspace = Path(name)
            pc_side = workspace / "PC side"
            builder = pc_side / "stemwise-playlist-builder"
            install_root = builder / "future-songs"
            ready = workspace / "Ready to Import"
            analysis = pc_side / "temporary-analysis"
            for path in (install_root, ready, analysis, pc_side / "model-cache"):
                path.mkdir(parents=True)
            config_path = builder / "config.json"
            config_path.write_text("{}", encoding="utf-8")
            retired = install_root / "jobs" / "AAAAAAAAAAA" / "job.json"
            retired.parent.mkdir(parents=True)
            retired.write_text('{"policyVersion":"retired"}\n', encoding="utf-8")
            config = {
                "futureRootWindows": str(install_root),
                "futureReadyDirWindows": str(ready),
                "pcSideRootWindows": str(pc_side),
                "tempDir": str(analysis),
            }
            with (
                patch.object(future_song_module, "load_config", return_value=config),
                patch.object(FutureSongWorkflow, "_validate_policy", return_value={}),
            ):
                workflow = FutureSongWorkflow(config_path)

            state_name = "current"
            self.assertEqual(
                workflow.jobs,
                install_root / "policy-state" / state_name / "jobs",
            )
            self.assertEqual(
                workflow.analysis_temp,
                analysis / "future-song-policy-state" / state_name,
            )
            self.assertEqual(retired.read_text(encoding="utf-8"), '{"policyVersion":"retired"}\n')



if __name__ == "__main__":
    unittest.main()
