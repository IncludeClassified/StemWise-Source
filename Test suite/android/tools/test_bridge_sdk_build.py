import hashlib
import json
from pathlib import Path
import runpy
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
CHECK = runpy.run_path(str(ROOT / "StemWise/tools/verify-bridge-sdk.py"))
BRIDGE = CHECK["BRIDGE"]


class PrebuiltSdkBuildTest(unittest.TestCase):
    def setUp(self):
        work = ROOT / "Test suite/work"
        work.mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(temporary.cleanup)
        self.bridge = Path(temporary.name)
        self.metadata = json.loads((BRIDGE / "android/phone-pc-bridge.build.json").read_text(encoding="utf-8"))
        for row in self.metadata["inputs"] + self.metadata["candidates"]:
            destination = self.bridge / "source" / row["path"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(BRIDGE / "source" / row["path"], destination)
        (self.bridge / "android").mkdir()
        data = b"an independently fingerprinted artifact fixture"
        (self.bridge / "android/phone-pc-bridge.aar").write_bytes(data)
        self.metadata["aar"] = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        self.save()

    def save(self):
        (self.bridge / "android/phone-pc-bridge.build.json").write_text(json.dumps(self.metadata), encoding="utf-8")

    def test_prebuilt_verification_never_invokes_go_or_any_other_process(self):
        with patch("subprocess.run", side_effect=AssertionError("Unexpected discovery")):
            self.assertEqual(self.metadata, CHECK["verify"](self.bridge, go="nonexistent-go"))

    def test_added_production_candidate_cannot_reuse_the_unchanged_proof(self):
        package = self.bridge / 'source' / self.metadata['candidateScopes'][0]['path']
        (package/'added.go').write_text('package files\nfunc init() {}\n', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'dependency discovery failed'):
            CHECK['verify'](self.bridge, go='nonexistent-go')

    def test_tests_and_prose_do_not_require_go(self):
        package = self.bridge / 'source' / self.metadata['candidateScopes'][0]['path']
        (package/'added_test.go').write_text('package files\n', encoding='utf-8')
        (package/'notes.md').write_text('Documentation only', encoding='utf-8')
        with patch('subprocess.run', side_effect=AssertionError('Unnecessary discovery')):
            self.assertEqual(self.metadata, CHECK['verify'](self.bridge, go='nonexistent-go'))

    def test_changed_declared_source_and_aar_bytes_are_rejected(self):
        row = next(row for row in self.metadata["inputs"] if row["path"].endswith("client.go"))
        source = self.bridge / "source" / row["path"]
        original = source.read_bytes()
        source.write_bytes(original + b"\n// source change\n")
        with self.assertRaisesRegex(ValueError, "input changed"):
            CHECK["verify"](self.bridge)
        source.write_bytes(original)
        (self.bridge / "android/phone-pc-bridge.aar").write_bytes(b"changed artifact")
        with self.assertRaisesRegex(ValueError, "AAR bytes"):
            CHECK["verify"](self.bridge)

    def test_undeclared_build_input_and_manifest_traversal_are_rejected(self):
        (self.bridge / "source/bridge-core/android/Added.java").write_text("class Added {}", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "build input inventory changed"):
            CHECK["verify"](self.bridge)
        (self.bridge / "source/bridge-core/android/Added.java").unlink()
        self.metadata["inputs"].insert(0, {"path": "../escape", "size": 0, "sha256": "0" * 64})
        self.save()
        with self.assertRaisesRegex(ValueError, "Invalid SDK production/build input"):
            CHECK["verify"](self.bridge)


if __name__ == "__main__":
    unittest.main()
