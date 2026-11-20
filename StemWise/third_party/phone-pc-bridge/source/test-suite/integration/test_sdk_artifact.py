from pathlib import Path
import json
import os
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


PROJECT = Path(__file__).resolve().parents[2]
SDK = runpy.run_path(str(PROJECT / "bridge-core/sdk_artifact.py"))
LOCAL_GO = PROJECT / ".work/toolchain/go/bin/go.exe"
GO = str(LOCAL_GO) if LOCAL_GO.is_file() else (shutil.which("go") or "go")


def fixture(root):
    core = root / "bridge-core"
    (core / "mobile").mkdir(parents=True)
    (core / "android").mkdir()
    (root / "test-suite/bridge-core/mobile").mkdir(parents=True)
    (core / "mobile/client.go").write_text('package mobile\nimport _ "phonepcbridge/shared"\n', encoding="utf-8")
    (core / "shared/assets").mkdir(parents=True)
    (core / "shared/shared.go").write_text('package shared\nimport "embed"\n//go:embed assets/*\nvar Asset embed.FS\n', encoding="utf-8")
    (core / "shared/assets/fixture.txt").write_text("embedded Android fixture", encoding="utf-8")
    (core / "shared/platform_android.go").write_text("//go:build android && arm64 && cgo\n\npackage shared\n", encoding="utf-8")
    (core / "android/Bridge.java").write_text("class Bridge {}\n", encoding="utf-8")
    (core / "android/AndroidManifest.xml").write_text("<manifest/>\n", encoding="utf-8")
    (core / "internal/sdkmodinfo").mkdir(parents=True)
    shutil.copyfile(PROJECT / "bridge-core/internal/sdkmodinfo/main.go", core / "internal/sdkmodinfo/main.go")
    for name in ["go.mod", "go.sum", "build-sdk.ps1", "sdk-build-pins.json", "sdk_artifact.py", "sdk_archive.py"]:
        shutil.copyfile(PROJECT / "bridge-core" / name, core / name)
    for name in ["Run-Go.ps1", "BuildWorkCheck.ps1", "GoDiscoveryCheck.ps1"]:
        shutil.copyfile(PROJECT / "test-suite" / name, root / "test-suite" / name)
    (core / SDK["AAR"]).write_bytes(b"known artifact candidate")
    SDK["create"](root, core / SDK["AAR"], core / SDK["MANIFEST"], go=GO)
    return core


class SdkIdentityCheck(unittest.TestCase):
    def setUp(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="sdk-identity-", dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.core = fixture(self.root)

    def test_actual_producer_output_passes_consumer_and_cli_check(self):
        metadata = SDK["verify"](self.root, self.core, go=GO)
        self.assertEqual(metadata["aar"], SDK["fingerprint"](self.core / SDK["AAR"]))
        result = subprocess.run([sys.executable, str(self.core / "sdk_artifact.py"),
                                 "verify", "--go", GO, "--artifact-dir", str(self.core)], capture_output=True,
                                text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_unchanged_and_host_test_prose_changes_verify_without_go(self):
        (self.core / "backend").mkdir()
        (self.core / "backend/new.go").write_text("package backend\n", encoding="utf-8")
        (self.core / "mobile/client_test.go").write_text("package mobile\n", encoding="utf-8")
        (self.core / "mobile/README.md").write_text("Only prose", encoding="utf-8")
        with mock.patch.object(SDK["subprocess"], "run", side_effect=AssertionError("Unchanged verification invoked Go")):
            SDK["verify"](self.root, self.core, go="missing-go")

    def test_explicit_build_helper_is_bound_without_entering_android_closure(self):
        metadata = SDK["verify"](self.root, self.core, go=GO)
        helper = "bridge-core/internal/sdkmodinfo/main.go"
        self.assertIn(helper, [row["path"] for row in metadata["inputs"]])
        self.assertNotIn("bridge-core/internal/sdkmodinfo", [row["path"] for row in metadata["candidateScopes"]])
        # The builder runs the explicit file, not an expandable host package.
        (self.core / "internal/sdkmodinfo/unselected.go").write_text("package main\n", encoding="utf-8")
        with mock.patch.object(SDK["subprocess"], "run", side_effect=AssertionError("Host helper sibling expanded Android closure")):
            SDK["verify"](self.root, self.core, go="missing-go")
            source = self.root / helper
            source.write_bytes(source.read_bytes() + b"\n// changed build helper\n")
            with self.assertRaisesRegex(ValueError, "production/build input changed"):
                SDK["verify"](self.root, self.core, go="missing-go")

    def test_explicit_discovery_still_requires_and_uses_pinned_go(self):
        with self.assertRaisesRegex(ValueError, "dependency discovery"):
            SDK["verify"](self.root, self.core, go="missing-go", force_discovery=True)
        result = subprocess.run([sys.executable, str(self.core / "sdk_artifact.py"), "verify", "--discover",
                                 "--go", GO, "--artifact-dir", str(self.core)], capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_candidate_inventory_tracks_init_and_embed_additions(self):
        metadata = json.loads((self.core / SDK["MANIFEST"]).read_text(encoding="utf-8"))
        self.assertIn({"path": "bridge-core/shared", "embeds": True}, metadata["candidateScopes"])
        for relative, contents in [("shared/new_init.go", "package shared\nfunc init() {}\n"),
                                   ("shared/assets/new.bin", "new embedded bytes")]:
            with self.subTest(relative=relative):
                path = self.core / relative
                path.write_text(contents, encoding="utf-8")
                try:
                    with self.assertRaisesRegex(ValueError, "inventory changed"):
                        SDK["verify"](self.root, self.core, go=GO)
                finally:
                    path.unlink()

    def test_production_go_java_manifest_and_build_changes_require_rebuild(self):
        for name in ["mobile/client.go", "shared/shared.go", "shared/platform_android.go", "shared/assets/fixture.txt", "android/Bridge.java", "android/AndroidManifest.xml", "go.mod", "go.sum", "build-sdk.ps1", "sdk-build-pins.json", "sdk_artifact.py", "sdk_archive.py", "internal/sdkmodinfo/main.go"]:
            with self.subTest(name=name):
                path = self.core / name
                prior = path.read_bytes()
                path.write_bytes(prior + b"\n// changed production input\n" if path.suffix == ".go" else prior + b"\n")
                try:
                    with self.assertRaisesRegex(ValueError, "production/build input changed"):
                        SDK["verify"](self.root, self.core, go=GO)
                finally:
                    path.write_bytes(prior)
        (self.core / "mobile/new.go").write_text("package mobile\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            SDK["verify"](self.root, self.core, go=GO)

    def test_prose_and_test_only_edits_do_not_require_binary_rebuild(self):
        before = (self.core / SDK["AAR"]).read_bytes()
        (self.root / "documentation.md").write_text("Updated prose", encoding="utf-8")
        (self.root / "test-suite/bridge-core/mobile/new_test.go").write_text("package mobile\n", encoding="utf-8")
        (self.root / "test-suite/Run-Go.ps1").write_text("Updated tests", encoding="utf-8")
        SDK["verify"](self.root, self.core, go=GO)
        self.assertEqual((self.core / SDK["AAR"]).read_bytes(), before)
        self.assertIn("test-suite/bridge-core/mobile/new_test.go", SDK["source_paths"](self.root, go=GO))

    def test_host_only_edits_and_additions_do_not_invalidate_android_artifact(self):
        for package in ["backend", "pc"]:
            directory = self.core / package
            directory.mkdir()
            source = directory / "host.go"
            source.write_text("package " + package + "\n", encoding="utf-8")
            SDK["verify"](self.root, self.core, go=GO)
            source.write_text("package " + package + "\n// Changed host-only behavior\n", encoding="utf-8")
            SDK["verify"](self.root, self.core, go=GO)
            self.assertIn("bridge-core/" + package + "/host.go", SDK["source_paths"](self.root, go=GO))

    def test_android_ignored_go_source_does_not_invalidate_android_artifact(self):
        source = self.core / "mobile" / "host_windows.go"
        source.write_text("package mobile\n", encoding="utf-8")
        SDK["verify"](self.root, self.core, go=GO)
        source.write_text("package mobile\n// Changed Windows-only source\n", encoding="utf-8")
        SDK["verify"](self.root, self.core, go=GO)

    def test_real_transitive_android_tags_and_embedded_inventory_are_bound(self):
        metadata = SDK["verify"](self.root, self.core, go=GO)
        paths = {row["path"] for row in metadata["inputs"]}
        self.assertIn("bridge-core/shared/shared.go", paths)
        self.assertIn("bridge-core/shared/platform_android.go", paths)
        self.assertIn("bridge-core/shared/assets/fixture.txt", paths)
        self.assertIn("bridge-core/shared/assets/fixture.txt", SDK["source_paths"](self.root, go=GO))
        for relative, contents in [("shared/added.go", "package shared\n"),
                                   ("shared/assets/added.txt", "new matched embed input")]:
            with self.subTest(relative=relative):
                extra = self.core / relative
                extra.write_text(contents, encoding="utf-8")
                try:
                    with self.assertRaisesRegex(ValueError, "inventory changed"):
                        SDK["verify"](self.root, self.core, go=GO)
                finally:
                    extra.unlink()

    def test_in_module_replacement_configuration_is_bound_and_exported(self):
        module = self.core / "local"
        module.mkdir()
        (module / "go.mod").write_text("module fixture.invalid/local\ngo 1.26.6\n", encoding="utf-8")
        (module / "local.go").write_text("package local\n", encoding="utf-8")
        go_mod = self.core / "go.mod"
        go_mod.write_text(go_mod.read_text(encoding="utf-8") +
                          "\nrequire fixture.invalid/local v0.0.0\nreplace fixture.invalid/local => ./local\n", encoding="utf-8")
        client = self.core / "mobile/client.go"
        client.write_text(client.read_text(encoding="utf-8") + 'import _ "fixture.invalid/local"\n', encoding="utf-8")
        SDK["create"](self.root, self.core / SDK["AAR"], self.core / SDK["MANIFEST"], go=GO)
        self.assertIn("bridge-core/local/go.mod", SDK["source_paths"](self.root, go=GO))
        (module / "go.mod").write_text("module fixture.invalid/local\ngo 1.26.6\n// changed\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "production/build input changed"):
            SDK["verify"](self.root, self.core, go=GO)

    def test_unconfirmed_dependency_discovery_cannot_accept_an_artifact(self):
        (self.core / "mobile/new_windows.go").write_text("package mobile\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "dependency discovery"):
            SDK["verify"](self.root, self.core, go=str(self.root / "missing-go.exe"))
        (self.core / "mobile/client.go").write_text('package mobile\nimport _ "missing.invalid/dependency"\n', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "production/build input changed"):
            SDK["verify"](self.root, self.core, go=GO)

    def test_existing_excluded_source_can_become_a_selected_input(self):
        source = self.core / "shared/optional.go"
        source.write_text("//go:build windows\n\npackage shared\n", encoding="utf-8")
        SDK["create"](self.root, self.core / SDK["AAR"], self.core / SDK["MANIFEST"], go=GO)
        source.write_text("//go:build android\n\npackage shared\nfunc init() {}\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            SDK["verify"](self.root, self.core, go=GO)

    def test_changed_excluded_candidate_rediscovers_without_requiring_rebuild(self):
        source = self.core / "mobile/optional_windows.go"
        source.write_text("package mobile\n", encoding="utf-8")
        run = subprocess.run
        with mock.patch.object(SDK["subprocess"], "run", wraps=run) as calls:
            SDK["verify"](self.root, self.core, go=GO)
        self.assertTrue(any("list" in call.args[0] for call in calls.call_args_list))
        self.assertEqual((self.core / SDK["AAR"]).read_bytes(), b"known artifact candidate")

    def test_all_embed_hidden_asset_additions_are_candidates(self):
        source = self.core / "shared/shared.go"
        source.write_text('package shared\nimport "embed"\n//go:embed all:assets\nvar Asset embed.FS\n', encoding="utf-8")
        SDK["create"](self.root, self.core / SDK["AAR"], self.core / SDK["MANIFEST"], go=GO)
        hidden = self.core / "shared/assets/.work"
        hidden.mkdir()
        (hidden / "added.txt").write_text("new matched hidden asset", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            SDK["verify"](self.root, self.core, go=GO)

    def test_candidate_added_during_discovery_cannot_pass_stale_closure(self):
        (self.core / "mobile/extra_windows.go").write_text("package mobile\n", encoding="utf-8")
        discover = SDK["android_dependencies"]
        def add_after_discovery(root, go):
            result = discover(root, go)
            (self.core / "shared/assets/late.txt").write_text("racing embed input", encoding="utf-8")
            return result
        with mock.patch.dict(SDK["verify"].__globals__, android_dependencies=add_after_discovery):
            with self.assertRaisesRegex(ValueError, "candidate inventory changed during dependency discovery"):
                SDK["verify"](self.root, self.core, go=GO)

    def test_malformed_candidate_scopes_and_inventory_fail_closed(self):
        path = self.core / SDK["MANIFEST"]
        original = path.read_bytes()
        for variant in ["absent", "empty", "duplicate", "unsafe", "wrong_type", "missing_hash", "truncated_inputs"]:
            with self.subTest(variant=variant):
                metadata = json.loads(original)
                if variant == "absent":
                    del metadata["candidateScopes"]
                elif variant == "empty":
                    metadata["candidateScopes"] = []
                elif variant == "duplicate":
                    metadata["candidateScopes"].append(metadata["candidateScopes"][0])
                elif variant == "unsafe":
                    metadata["candidateScopes"][0]["path"] = "bridge-core/../private"
                elif variant == "wrong_type":
                    metadata["candidateScopes"][0]["embeds"] = "false"
                elif variant == "missing_hash":
                    metadata["candidates"][0].pop("sha256")
                else:
                    metadata["inputs"] = [row for row in metadata["inputs"] if row["path"] != "bridge-core/shared/shared.go"]
                path.write_text(json.dumps(metadata), encoding="utf-8")
                with self.assertRaises(ValueError):
                    SDK["verify"](self.root, self.core, go=GO)
        path.write_bytes(original)

    def test_dependency_timeout_reports_bounded_decoded_error(self):
        failure = subprocess.TimeoutExpired("go", 60, stderr=b"known discovery failure" + b"x" * 4096)
        with mock.patch.object(SDK["subprocess"], "run", side_effect=failure):
            with self.assertRaisesRegex(ValueError, "dependency discovery failed: known discovery failure") as result:
                SDK["android_go_paths"](self.root, GO)
        self.assertLess(len(str(result.exception)), 2100)

    def test_changed_production_during_build_cannot_publish_matching_new_manifest(self):
        captured = SDK["capture"](self.root, go=GO)
        previous = (self.core / SDK["MANIFEST"]).read_bytes()
        (self.core / "mobile/client.go").write_text("package mobile\n// Changed during build\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "changed during compilation"):
            SDK["create"](self.root, self.core / SDK["AAR"], self.core / SDK["MANIFEST"], captured, go=GO)
        self.assertEqual((self.core / SDK["MANIFEST"]).read_bytes(), previous)

    def test_added_embed_input_during_build_cannot_be_certified(self):
        captured = SDK["capture"](self.root, go=GO)
        previous = (self.core / SDK["MANIFEST"]).read_bytes()
        (self.core / "shared/assets/late.txt").write_text("new matched asset", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "changed during compilation"):
            SDK["create"](self.root, self.core / SDK["AAR"], self.core / SDK["MANIFEST"], captured, go=GO)
        self.assertEqual((self.core / SDK["MANIFEST"]).read_bytes(), previous)

    def test_excluded_change_during_build_does_not_change_compiled_identity(self):
        captured = SDK["capture"](self.root, go=GO)
        (self.core / "mobile/late_windows.go").write_text("package mobile\n", encoding="utf-8")
        SDK["create"](self.root, self.core / SDK["AAR"], self.core / SDK["MANIFEST"], captured, go=GO)
        SDK["verify"](self.root, self.core, go="missing-go")

    def test_wrong_aar_or_ambiguous_manifest_fails_closed(self):
        path = self.core / SDK["MANIFEST"]
        original = path.read_bytes()
        for variant in ["aar", "duplicate", "unsafe", "missing", "toolchain"]:
            with self.subTest(variant=variant):
                metadata = json.loads(original)
                if variant == "aar":
                    metadata["aar"]["sha256"] = "0" * 64
                elif variant == "duplicate":
                    metadata["inputs"].append(metadata["inputs"][0])
                elif variant == "unsafe":
                    metadata["inputs"][0]["path"] = "bridge-core/../private"
                elif variant == "missing":
                    metadata["inputs"].pop()
                else:
                    metadata["toolchain"]["go"] = "unrecognized"
                path.write_text(json.dumps(metadata), encoding="utf-8")
                with self.assertRaises(ValueError):
                    SDK["verify"](self.root, self.core, go=GO)
        path.write_bytes(original)

    def test_malformed_manifest_shape_has_an_explicit_cli_failure(self):
        (self.core / SDK["MANIFEST"]).write_text("[]", encoding="utf-8")
        result = subprocess.run([sys.executable, str(self.core / "sdk_artifact.py"),
                                 "verify", "--go", GO, "--artifact-dir", str(self.core)], capture_output=True,
                                text=True, timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Invalid SDK artifact manifest", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_publish_changes_only_pair_and_restores_old_pair_on_commit_failure(self):
        destination = self.root / "consumed"
        destination.mkdir()
        marker = destination / "keep.txt"
        marker.write_bytes(b"unrelated app file")
        for name in [SDK["AAR"], SDK["MANIFEST"]]:
            (destination / name).write_bytes(b"prior " + name.encode())
        before = {p.name: p.read_bytes() for p in destination.iterdir()}
        replace = os.replace
        failed = False

        def fail_manifest_once(source, target):
            nonlocal failed
            if Path(target) == destination / SDK["MANIFEST"] and not failed:
                failed = True
                raise OSError("owned publication fixture failure")
            return replace(source, target)

        with mock.patch.object(SDK["publish"].__globals__["os"], "replace", fail_manifest_once):
            with self.assertRaisesRegex(OSError, "publication fixture failure"):
                SDK["publish"](self.root, destination, go=GO)
        self.assertEqual({p.name: p.read_bytes() for p in destination.iterdir()}, before)
        SDK["publish"](self.root, destination, go=GO)
        SDK["verify"](self.root, destination, go=GO)
        self.assertEqual(marker.read_bytes(), b"unrelated app file")
        self.assertEqual({p.name for p in destination.iterdir()}, {SDK["AAR"], SDK["MANIFEST"], "keep.txt"})

    def test_failed_rollback_retains_the_only_prior_artifact_for_recovery(self):
        destination = self.root / "consumed"
        destination.mkdir()
        (destination / SDK["AAR"]).write_bytes(b"valid prior artifact")
        replace = os.replace

        def fail_commit_and_rollback(source, target):
            if Path(target) == destination / SDK["MANIFEST"] or Path(source).name.endswith(".previous"):
                raise OSError("owned rollback fixture failure")
            return replace(source, target)

        with mock.patch.object(SDK["publish"].__globals__["os"], "replace", fail_commit_and_rollback):
            with self.assertRaisesRegex(OSError, "prior artifacts retained at"):
                SDK["publish"](self.root, destination, go=GO)
        remaining = list(destination.glob(".bridge-publish-*"))
        self.assertEqual(len(remaining), 1)
        self.assertEqual((remaining[0] / (SDK["AAR"] + ".previous")).read_bytes(), b"valid prior artifact")


if __name__ == "__main__":
    unittest.main()
