from __future__ import annotations

import json
import math
import subprocess
import sys
import unittest
import uuid
import wave
from pathlib import Path
from types import SimpleNamespace

WORKSPACE = Path(__file__).resolve().parents[3]
PROJECT = WORKSPACE / "PC side" / "stemwise-playlist-builder"
import stemwise_engine.alignment_models as alignment_models

from stemwise_engine.common import manifested_temporary_directory
from stemwise_engine.lyrics_align_pc import ConditionalCascadeTechnicalError, _run_conditional_cascade
from stemwise_engine.runtime_environment import environment_python
from stemwise_engine.alignment_models import ROFORMER_PACKAGE_WHEEL_SHA256, runtime_profile, qualified_python_version
from stemwise_engine.alignment_audio import invalidate_downstream_roformer_views, read_audio_provenance, roformer_transform, write_audio_provenance
from stemwise_engine.alignment_models import roformer_offline_env
from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION, AlignmentWorkspace
from stemwise_engine.roformer_separation import (
    _chunk_reconstruction,
    _nonfinite_summary,
    _time_transform,
)


SCRATCH_ROOT = WORKSPACE / "Test suite" / "work"
KIM = "kimberley-jensen-roformer-vocals"
SUCIAL = "sucial-v2-roformer-dry-vocals"


class ConditionalCascadeTest(unittest.TestCase):
    def test_runtime_probe_reuses_only_the_same_operation_and_bound_environment(self):
        import copy
        import os
        import tempfile
        from unittest.mock import patch
        import stemwise_engine.alignment_models as alignment_models
        import stemwise_engine.lyrics_align_pc as align
        expected = {'python': 'fixed', 'packages': {'torch': 'fixed'}}
        runtime = {'runtimeVersion': 1, 'supportedResultVersions': [1], **expected,
            'requestedDevice': 'cuda', 'resolvedDevice': 'cuda', 'cudaAvailable': True,
            'package': {'version': alignment_models.ROFORMER_PACKAGE_VERSION,
                'wheelSha256': alignment_models.ROFORMER_PACKAGE_WHEEL_SHA256,
                'sourceManifestSha256': alignment_models.ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256,
                'sourceCommit': alignment_models.ROFORMER_PACKAGE_SOURCE_COMMIT}}
        with tempfile.TemporaryDirectory(dir=SCRATCH_ROOT) as name:
            project = Path(name) / 'project'
            helper = project / 'src/roformer_separation.py'
            helper.parent.mkdir(parents=True)
            helper.write_text('WORKER_VERSION = 1\n', encoding='utf-8')
            python = project / 'python'
            python.write_text('interpreter')
            cache = {}
            with patch.object(alignment_models, 'roformer_runtime_pins', return_value=copy.deepcopy(expected)) as pins, patch.object(
                    align, 'run_process_tree', return_value=SimpleNamespace(returncode=0,
                    stdout=json.dumps(runtime), stderr='')) as worker:
                def probe(attempt=cache):
                    return align._roformer_runtime_provenance(python, helper, device='cuda',
                        timeout_seconds=5, attempt_cache=attempt)
                received = probe()
                received['packages']['torch'] = 'fabricated'
                self.assertEqual(probe(), runtime)
                self.assertEqual(worker.call_count, 1)
                probe(attempt={})
                self.assertEqual(worker.call_count, 2)
                helper.write_text('WORKER_VERSION = 2\n', encoding='utf-8')
                probe()
                python.write_text('changed interpreter')
                probe()
                with patch.dict(os.environ, {'CUDA_VISIBLE_DEVICES': 'changed'}): probe()
                self.assertEqual(worker.call_count, 5)
                pins.return_value['packages']['torch'] = 'changed'
                for _ in range(2):
                    with self.assertRaisesRegex(RuntimeError, 'differs from exact dependency pins'): probe()
                self.assertEqual(worker.call_count, 7)
                helper.write_text('changed worker', encoding='utf-8')
                with self.assertRaises(SyntaxError):
                    probe()
                self.assertEqual(worker.call_count, 7)

    def test_worker_reuses_verified_pins_but_rejects_replacement_and_changed_pin(self):
        import hashlib
        import os
        import tempfile
        from unittest.mock import patch
        import stemwise_engine.lyrics_align_pc as align
        import stemwise_engine.roformer_separation as worker
        with tempfile.TemporaryDirectory(dir=SCRATCH_ROOT) as name:
            root = Path(name)
            checkpoint, config = root / 'model', root / 'config'
            checkpoint.write_bytes(b'model bytes')
            config.write_bytes(b'config bytes')
            args = SimpleNamespace(route=KIM, checkpoint=checkpoint, config=config,
                checkpoint_sha256=hashlib.sha256(b'model bytes').hexdigest(),
                config_sha256=hashlib.sha256(b'config bytes').hexdigest(), audio=root / 'missing-input')
            reads = []
            opened = Path.open
            def tracked(path, mode='r', *args, **kwargs):
                if path in (checkpoint, config) and mode == 'rb':
                    reads.append(path)
                return opened(path, mode, *args, **kwargs)
            with patch.object(alignment_models, '_PINNED_FILE_CACHE', {}), patch.object(Path, 'open', tracked):
                for _ in range(3):
                    with self.assertRaises(FileNotFoundError):
                        worker.run(args)
                self.assertEqual(len(reads), 6 if os.name == 'nt' else 2)
                args.checkpoint_sha256 = '0' * 64
                with self.assertRaisesRegex(RuntimeError, 'checkpoint.*identity'):
                    worker.run(args)
                args.checkpoint_sha256 = hashlib.sha256(b'model bytes').hexdigest()
                stat = checkpoint.stat()
                checkpoint.write_bytes(b'Model bytes')
                os.utime(checkpoint, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                with self.assertRaisesRegex(RuntimeError, 'checkpoint.*identity'):
                    worker.run(args)
                self.assertEqual(checkpoint.read_bytes(), b'Model bytes')
                self.assertEqual(config.read_bytes(), b'config bytes')

    def test_regenerated_upstream_view_invalidates_downstream_lineage(self) -> None:
        views = {KIM: {"sha256": "new"}, SUCIAL: {"sourceAudioSha256": "old"}}
        invalidate_downstream_roformer_views(views, KIM)
        self.assertEqual(views, {KIM: {"sha256": "new"}})

    def test_roformer_nonfinite_summary_is_numeric_and_frame_aware(self) -> None:
        import numpy as np

        output = np.array(
            [
                [math.nan, 1.0, math.inf, 0.0],
                [0.0, math.nan, math.inf, 0.0],
            ],
            dtype=np.float32,
        )
        self.assertEqual(
            _nonfinite_summary(output, np),
            {
                "sampleCount": 4,
                "frameCount": 3,
                "firstFrame": 0,
                "lastFrame": 2,
                "longestFrameRun": 3,
                "totalFrames": 4,
                "channelCount": 2,
                "nanCount": 2,
                "positiveInfinityCount": 2,
                "negativeInfinityCount": 0,
            },
        )

    def run_routes(self, outcomes: dict[str, bool]) -> tuple[str, list[str]]:
        called: list[str] = []

        def evaluate(route_id: str) -> tuple[bool, str]:
            called.append(route_id)
            return outcomes[route_id], route_id

        selected, _ = _run_conditional_cascade(
            ("htdemucs-vocals", KIM, SUCIAL), evaluate
        )
        return selected, called

    def test_easy_path_skips_both_roformer_routes(self) -> None:
        selected, called = self.run_routes({"htdemucs-vocals": True})
        self.assertEqual(selected, "htdemucs-vocals")
        self.assertEqual(called, ["htdemucs-vocals"])

    def test_unresolved_htdemucs_runs_kim_and_stops_when_resolved(self) -> None:
        selected, called = self.run_routes(
            {"htdemucs-vocals": False, KIM: True}
        )
        self.assertEqual(selected, KIM)
        self.assertEqual(called, ["htdemucs-vocals", KIM])

    def test_sucial_runs_only_after_kim_remains_unresolved(self) -> None:
        selected, called = self.run_routes(
            {"htdemucs-vocals": False, KIM: False, SUCIAL: True}
        )
        self.assertEqual(selected, SUCIAL)
        self.assertEqual(called, ["htdemucs-vocals", KIM, SUCIAL])

    def test_later_safe_route_can_resolve_after_technical_failure(self) -> None:
        failures: list[tuple[str, str]] = []

        def evaluate(route_id: str) -> tuple[bool, str]:
            if route_id == KIM:
                raise RuntimeError("private provider detail")
            return route_id == SUCIAL, route_id

        selected, value = _run_conditional_cascade(
            ("htdemucs-vocals", KIM, SUCIAL),
            evaluate,
            on_failure=lambda route_id, code: failures.append((route_id, code)),
        )
        self.assertEqual((selected, value), (SUCIAL, SUCIAL))
        self.assertEqual(failures, [(KIM, "RuntimeError")])

    def test_exhausted_cascade_preserves_retryable_technical_outcome(self) -> None:
        failures: list[tuple[str, str]] = []

        def evaluate(route_id: str) -> tuple[bool, str]:
            if route_id == KIM:
                raise RuntimeError("private provider detail")
            return False, route_id

        with self.assertRaises(ConditionalCascadeTechnicalError) as raised:
            _run_conditional_cascade(
                ("htdemucs-vocals", KIM, SUCIAL),
                evaluate,
                on_failure=lambda route_id, code: failures.append((route_id, code)),
            )
        self.assertIn("conditional cascade exhausted", str(raised.exception))
        self.assertEqual(failures, [(KIM, "RuntimeError")])
        self.assertNotIn("private provider detail", str(raised.exception))

    def test_required_baseline_failure_survives_unresolved_roformer_passes(self) -> None:
        called = []

        def evaluate(route_id: str) -> tuple[bool, str]:
            called.append(route_id)
            return False, route_id

        with self.assertRaises(ConditionalCascadeTechnicalError):
            _run_conditional_cascade(
                (KIM, SUCIAL), evaluate, prior_technical_failure=True
            )
        self.assertEqual(called, [KIM, SUCIAL])

    def test_safe_later_route_recovers_required_baseline_failure(self) -> None:
        selected, value = _run_conditional_cascade(
            (KIM, SUCIAL),
            lambda route_id: (route_id == SUCIAL, route_id),
            prior_technical_failure=True,
        )
        self.assertEqual((selected, value), (SUCIAL, SUCIAL))


class CanonicalTimeTest(unittest.TestCase):
    def test_exact_resample_and_overlap_reconstruction_are_recorded(self) -> None:
        source = {
            "sampleRate": 48_000,
            "channelCount": 2,
            "frameCount": 480_000,
            "durationMs": 10_000,
        }
        target = {
            "sampleRate": 44_100,
            "channelCount": 2,
            "frameCount": 441_000,
            "durationMs": 10_000,
        }
        config = SimpleNamespace(
            inference=SimpleNamespace(chunk_size=352_800, num_overlap=2)
        )
        reconstruction = _chunk_reconstruction(config, target["frameCount"])
        stage = _time_transform(source, target, reconstruction)
        canonical = roformer_transform(source, source, target, stage)
        chained = roformer_transform(
            source,
            target,
            target,
            _time_transform(target, target, reconstruction),
        )
        self.assertEqual(canonical["measuredResidualFrames"], 0)
        self.assertEqual(chained["inputFrameCount"], source["frameCount"])
        self.assertEqual(chained["outputFrameCount"], target["frameCount"])
        self.assertEqual(0, canonical["separatorLatencyFrames"])
        self.assertEqual(
            "zero-by-offline-index-contract",
            canonical["separatorLatencyStatus"],
        )
        self.assertEqual(reconstruction["modelOutputFrameCount"], 441_000)
        self.assertEqual(
            reconstruction["lastChunkPadFrames"],
            reconstruction["chunkSizeFrames"]
            - reconstruction["lastChunkSourceFrames"],
        )

    def test_more_than_one_millisecond_residual_is_rejected(self) -> None:
        source = {
            "sampleRate": 48_000,
            "channelCount": 2,
            "frameCount": 480_000,
            "durationMs": 10_000,
        }
        target = {
            "sampleRate": 44_100,
            "channelCount": 2,
            "frameCount": 441_045,
            "durationMs": 10_001,
        }
        config = SimpleNamespace(
            inference=SimpleNamespace(chunk_size=352_800, num_overlap=2)
        )
        reconstruction = _chunk_reconstruction(config, target["frameCount"])
        with self.assertRaisesRegex(RuntimeError, "material canonical-time residual"):
            _time_transform(source, target, reconstruction)


class CurrentContractTest(unittest.TestCase):
    def test_python_pin_does_not_depend_on_unrelated_manifest_version(self) -> None:
        owner = {"kind": "runtime-pin-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="runtime-pin-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            project = scratch / "stemwise-playlist-builder"
            project.mkdir()
            (scratch / "DEPENDENCY_MANIFEST.json").write_text(
                json.dumps(
                    {
                        "formatVersion": 999,
                        "lyricsV2Runtime": {"python": "3.10.18"},
                    }
                ),
                encoding="utf-8",
            )
            self.assertEqual(qualified_python_version(project), "3.10.18")

    def test_roformer_cache_rejects_links_before_writing(self) -> None:
        owner = {"kind": "roformer-cache-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="roformer-cache-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            agent_root = scratch / "PC side"
            cache_root = agent_root / "model-cache"
            outside = scratch / "outside"
            cache_root.mkdir(parents=True)
            outside.mkdir()
            link = cache_root / "huggingface"
            link.symlink_to(outside, target_is_directory=True)
            try:
                with self.assertRaisesRegex(ValueError, "link or reparse point"):
                    roformer_offline_env(agent_root)
            finally:
                link.unlink(missing_ok=True)

    def test_roformer_environment_enables_deterministic_cublas(self) -> None:
        owner = {"kind": "roformer-environment-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="roformer-environment-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            environment = roformer_offline_env(scratch / "PC side")
            self.assertEqual(environment["CUBLAS_WORKSPACE_CONFIG"], ":4096:8")


    def test_v3_audio_provenance_and_current_alignment_are_current_only(self) -> None:
        owner = {"kind": "roformer-contract-test", "testId": uuid.uuid4().hex}
        with manifested_temporary_directory(
            SCRATCH_ROOT,
            prefix="roformer-contract-",
            owner=owner,
            workspace_root=WORKSPACE,
        ) as scratch:
            workspace = AlignmentWorkspace(scratch / "alignment", workspace_root=WORKSPACE)
            video_id = "-Abc_123XYZ"
            audio = scratch / "analysis.wav"
            with wave.open(str(audio), "wb") as handle:
                handle.setnchannels(2)
                handle.setsampwidth(2)
                handle.setframerate(48_000)
                handle.writeframes(b"\0" * 480 * 4)
            written = write_audio_provenance(
                workspace,
                video_id,
                duration_ms=10,
                mix_audio=audio,
            )
            self.assertEqual(written["provenanceVersion"], 3)
            self.assertEqual(written["audioViews"], {})
            workspace.write_state(video_id, {"alignmentVersion": 4, "status": "test"})
            state = json.loads(
                workspace.state_path(video_id).read_text(encoding="utf-8")
            )
            self.assertEqual(ALIGNMENT_VERSION, 12)
            self.assertEqual(state["alignmentVersion"], ALIGNMENT_VERSION)

            written["provenanceVersion"] = 2
            workspace.audio_provenance_path(video_id).write_text(
                json.dumps(written), encoding="utf-8"
            )
            self.assertIsNone(
                read_audio_provenance(workspace, video_id, 10)
            )

    def test_local_wheel_identity_is_pinned_in_requirements(self) -> None:
        versions = runtime_profile('main')['packages']
        self.assertEqual(versions["melband-roformer-infer"], "0.1.5")
        wheel = (
            PROJECT.parent
            / "model-cache"
            / "python-wheels"
            / "melband_roformer_infer-0.1.5-py3-none-any.whl"
        )
        import hashlib

        self.assertEqual(hashlib.sha256(wheel.read_bytes()).hexdigest(), ROFORMER_PACKAGE_WHEEL_SHA256)

    def test_installed_roformer_uses_the_pinned_project_runtime(self) -> None:
        runtime = environment_python('main', PROJECT)
        installed = subprocess.run(
            [
                str(runtime),
                "-c",
                (
                    "import importlib.metadata as m; "
                    "print(m.version('melband-roformer-infer'))"
                ),
            ],
            text=True,
            capture_output=True,
            check=True,
            timeout=30,
        )
        self.assertEqual(installed.stdout.strip(), "0.1.5")


if __name__ == "__main__":
    unittest.main()
