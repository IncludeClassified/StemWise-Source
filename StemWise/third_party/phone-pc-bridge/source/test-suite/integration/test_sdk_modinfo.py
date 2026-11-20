import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]
LOCAL_GO = PROJECT / ".work/toolchain/go/bin/go.exe"
GO = str(LOCAL_GO) if LOCAL_GO.is_file() else (shutil.which("go") or "go")


class SdkModuleInfoCheck(unittest.TestCase):
    def test_linker_normalization_preserves_standard_and_runtime_readers(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off", GOFLAGS="-mod=readonly", GOOS="windows", GOARCH="amd64", CGO_ENABLED="0",
                   GOCACHE=str(PROJECT / ".work/toolchain/cache"), GOMODCACHE=str(PROJECT / ".work/toolchain/modules"))
        with tempfile.TemporaryDirectory(prefix="sdk-modinfo-", dir=work) as temporary:
            root = Path(temporary)
            library, app = root / "local-source", root / "app"
            library.mkdir()
            (app / "gobind").mkdir(parents=True)
            (library / "go.mod").write_text("module phonepcbridge\ngo 1.26.6\n", encoding="utf-8")
            (library / "library.go").write_text('package phonepcbridge\nconst Proof = "retained"\n', encoding="utf-8")
            (app / "go.mod").write_text('module gobind\ngo 1.26.6\nrequire phonepcbridge v0.0.0\nreplace phonepcbridge => ' + json.dumps(library.as_posix()) + '\n', encoding="utf-8")
            (app / "gobind/main.go").write_text(
                'package main\nimport("encoding/json";"os";"runtime/debug"; bridge "phonepcbridge")\n'
                'func main(){if bridge.Proof!="retained"{panic("wrong dependency")};info,ok:=debug.ReadBuildInfo();if !ok{panic("missing build info")};json.NewEncoder(os.Stdout).Encode(info)}\n',
                encoding="utf-8")
            first, final = root / "first.exe", root / "final.exe"
            subprocess.run([str(GO), "build", "-trimpath", "-o", str(first), "./gobind"], cwd=app, env=env, check=True, timeout=60, capture_output=True)
            before = json.loads(subprocess.check_output([str(first)], timeout=10))
            receipt = root / "receipt.txt"
            helper = [str(GO), "run", "./internal/sdkmodinfo/main.go", "-binary", str(first), "-source", str(library), "-output", str(receipt)]
            subprocess.run(helper, cwd=PROJECT / "bridge-core", env=env, check=True, timeout=60, capture_output=True)
            flags = "-buildid= -X 'runtime.modinfo=" + receipt.read_text(encoding="utf-8") + "'"
            subprocess.run([str(GO), "build", "-trimpath", "-ldflags=" + flags, "-o", str(final), "./gobind"], cwd=app, env=env, check=True, timeout=60, capture_output=True)
            runtime = json.loads(subprocess.check_output([str(final)], timeout=10))
            standard = json.loads(subprocess.check_output([str(GO), "version", "-m", "-json", str(final)], env=env, timeout=10))
            for dependency in before["Deps"]:
                if dependency["Path"] == "phonepcbridge":
                    dependency["Replace"]["Path"] = "./."
            self.assertEqual(runtime, before)
            standard.pop("File", None)
            self.assertEqual(standard, runtime)
            helper[helper.index(str(first))] = str(final)
            subprocess.run(helper + ["-verify"], cwd=PROJECT / "bridge-core", env=env, check=True, timeout=60, capture_output=True)
            receipt.write_bytes(receipt.read_bytes() + b"changed")
            rejected = subprocess.run(helper + ["-verify"], cwd=PROJECT / "bridge-core", env=env, timeout=60, capture_output=True)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn(b"Final SDK native module receipt changed", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
