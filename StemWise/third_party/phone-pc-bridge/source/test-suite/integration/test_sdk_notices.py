"""Check shipped notices against real Android dependencies and the linked native library."""
from pathlib import Path
import json
import os
import re
import subprocess
import tempfile
import unittest
from zipfile import ZipFile


PROJECT = Path(__file__).resolve().parents[2]
LOCAL_GO = PROJECT / ".work/toolchain/go/bin/go.exe"
GO = str(LOCAL_GO) if LOCAL_GO.is_file() else "go"


class SdkTargetNoticesCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off", GOFLAGS="-mod=readonly")
        for name, directory in [("GOCACHE", "cache"), ("GOMODCACHE", "modules"), ("GOPATH", "gopath")]:
            cls.env.setdefault(name, str(PROJECT / ".work/toolchain" / directory))
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf8"))
        version = cls.go("env", "GOVERSION").strip()
        if version != pins["go"]:
            raise AssertionError("Notice inventory requires pinned Go " + pins["go"])
        cls.android = cls.modules("android", "arm64", "1", "-tags=android")
        cls.windows = cls.modules("windows", "amd64", "0")

    @classmethod
    def go(cls, *arguments, env=None):
        return subprocess.run([GO, *arguments], cwd=PROJECT / "bridge-core", env=env or cls.env,
                              check=True, capture_output=True, text=True, encoding="utf8", timeout=60).stdout

    @classmethod
    def modules(cls, goos, goarch, cgo, *tags):
        packages = ["./mobile"]
        if goos == "android":
            packages.append("golang.org/x/mobile/bind/seq")
        output = cls.go("list", "-deps", *tags, "-f", "{{if .Module}}{{.Module.Path}}|{{.Module.Dir}}{{end}}",
                        *packages,
                        env=dict(cls.env, GOOS=goos, GOARCH=goarch, CGO_ENABLED=cgo))
        return {name: Path(directory) for line in output.splitlines() if "|" in line
                for name, directory in [line.split("|", 1)] if name != "phonepcbridge"}

    def test_real_android_graph_is_not_the_windows_graph(self):
        for name in ["github.com/mdlayher/socket", "github.com/tailscale/peercred"]:
            self.assertIn(name, self.android)
            self.assertNotIn(name, self.windows)
        self.assertIn("golang.org/x/mobile", self.android)
        self.assertNotEqual(self.android.keys(), self.windows.keys())

    def test_shipped_notices_cover_exact_android_and_linked_module_inventory(self):
        artifact = PROJECT / "bridge-core/phone-pc-bridge.aar"
        if not artifact.is_file():
            artifact = PROJECT.parent / "android/phone-pc-bridge.aar"
        with ZipFile(artifact) as aar:
            notices_bytes = aar.read("assets/phone-pc-bridge-licenses.txt")
            native = aar.read("jni/arm64-v8a/libgojni.so")
        notices = notices_bytes.decode("utf8").replace("\r\n", "\n")
        names = re.findall(r"^===== (.+) =====$", notices, re.M)
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sdk-notices-", dir=work) as temporary:
            library = Path(temporary) / "libgojni.so"
            library.write_bytes(native)
            linked = {match[1] for line in self.go("version", "-m", str(library)).splitlines()
                      if (match := re.match(r"\s*dep\s+(\S+)\s+", line)) and match[1] != "phonepcbridge"}
        self.assertEqual(linked, set(self.android))
        self.assertEqual(names, sorted(self.android) + ["Go runtime"])
        sections = dict(zip(names, re.split(r"^===== .+ =====\n", notices, flags=re.M)[1:]))
        for name, directory in self.android.items():
            licenses = [p for p in directory.iterdir() if p.is_file() and re.match(r"^(LICENSE|COPYING)(\..*)?$", p.name)]
            self.assertTrue(licenses, name)
            self.assertTrue(any(p.read_text(encoding="utf8").strip() in sections[name] for p in licenses), name)
            for notice in directory.iterdir():
                if notice.is_file() and re.match(r"^NOTICE(\..*)?$", notice.name):
                    self.assertIn(notice.read_text(encoding="utf8").strip(), sections[name], name)
        runtime_license = Path(self.go("env", "GOROOT").strip()) / "LICENSE"
        self.assertIn(runtime_license.read_text(encoding="utf8").strip(), sections["Go runtime"])
        bundle_path = PROJECT / "Bridge-integration.zip"
        if bundle_path.is_file():
            with ZipFile(bundle_path) as bundle:
                self.assertEqual(bundle.read("THIRD_PARTY_NOTICES.txt"), notices_bytes)
                self.assertEqual(bundle.read("android/phone-pc-bridge.aar"), artifact.read_bytes())
        else:
            self.assertEqual((PROJECT.parent / "THIRD_PARTY_NOTICES.txt").read_bytes(), notices_bytes)
            self.assertEqual((PROJECT.parent / "android/phone-pc-bridge.aar").read_bytes(), artifact.read_bytes())


if __name__ == "__main__":
    unittest.main()
