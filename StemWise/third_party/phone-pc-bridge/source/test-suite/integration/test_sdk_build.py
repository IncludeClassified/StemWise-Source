"""Run the real builder with fake external tools; inspect its actual tool arguments."""
from pathlib import Path
import json
import os
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile


PROJECT = Path(__file__).resolve().parents[2]
LOCAL_GO = PROJECT / ".work/toolchain/go/bin/go.exe"
GO = str(LOCAL_GO) if LOCAL_GO.is_file() else (shutil.which("go") or "go")
SDK = runpy.run_path(str(PROJECT / "bridge-core/sdk_artifact.py"))


def configured_jdk():
    configured = os.environ.get("JAVA_HOME")
    if not configured:
        raise RuntimeError("Set JAVA_HOME to the pinned JDK before running the real-compiler SDK regression")
    jdk = Path(configured).absolute()
    if not (jdk / "bin/javac.exe").is_file():
        raise RuntimeError("JAVA_HOME must contain bin/javac.exe: " + str(jdk))
    return jdk

# All build/SDK tools are external boundaries. The actual PowerShell builder,
# pin parsing, command construction, archive inspection and cleanup remain real.
TOOL = r'''
package main
import("archive/zip";"bytes";"encoding/json";"fmt";"os";"os/exec";"path/filepath";"strings")
func main(){
 root:=os.Getenv("SDK_ARGUMENT_FIXTURE")
 raw,e:=os.ReadFile(filepath.Join(root,"bridge-core","sdk-build-pins.json"));if e!=nil{panic(e)}
 pins:=map[string]any{};if json.Unmarshal(raw,&pins)!=nil{panic("pins")}
 name:=strings.TrimSuffix(strings.ToLower(filepath.Base(os.Args[0])),".exe")
 log,e:=os.OpenFile(filepath.Join(root,"arguments.jsonl"),os.O_CREATE|os.O_APPEND|os.O_WRONLY,0600);if e!=nil{panic(e)}
 javac,_:=exec.LookPath("javac")
 json.NewEncoder(log).Encode(map[string]any{"tool":name,"args":os.Args[1:],"javac":javac,"goos":os.Getenv("GOOS"),"goarch":os.Getenv("GOARCH"),"cgo":os.Getenv("CGO_ENABLED")});log.Close()
 args:=os.Args[1:]
 switch name {
 case "go":
  if len(args)>1&&args[0]=="env"&&args[1]=="GOVERSION"{fmt.Println(pins["go"])}else if args[0]=="version"{fmt.Printf("\tmod\tgolang.org/x/mobile\t%s\t\n",pins["xMobile"])}else if args[0]=="list"{fmt.Println("fixture.invalid/license|"+filepath.Join(root,"license"))}else if args[0]=="run"{for i,a:=range args{if a=="-output"{os.WriteFile(args[i+1],[]byte("fixture module receipt"),0600)}}}else{panic("unexpected go")}
 case "gomobile":
  out:="";for i,a:=range args{if a=="-o"{out=args[i+1]}}
  var classes bytes.Buffer;j:=zip.NewWriter(&classes);entry,_:=j.Create("go/Seq.class");entry.Write([]byte("mock generated class"));j.Close()
  f,e:=os.Create(out);if e!=nil{panic(e)};z:=zip.NewWriter(f)
  for name,data:=range map[string][]byte{"classes.jar":classes.Bytes(),"jni/arm64-v8a/libgojni.so":[]byte("mock ELF"),"proguard.txt":[]byte("mock rules")}{w,e:=z.Create(name);if e!=nil{panic(e)};w.Write(data)}
  z.Close();f.Close()
 case "javac":if len(args)==1&&args[0]=="-version"{version:=pins["javac"].(string);if configured:=os.Getenv("SDK_FIXTURE_JAVAC_VERSION");configured!=""{version=configured};fmt.Println("javac "+version)}
 case "llvm-readelf":fmt.Println(" LOAD 0x0000 0x0000 0x0000 0x4000 0x4000 R E 0x4000")
 case "python":
  if filepath.Base(args[0])=="sdk_archive.py"{
   child:=exec.Command(os.Getenv("SDK_FIXTURE_PYTHON"),args...);output,e:=child.CombinedOutput();fmt.Print(string(output));if e!=nil{os.Exit(1)}
  }else if args[1]=="capture"{
   data,e:=os.ReadFile(filepath.Join(root,"captured-inputs.json"));if e!=nil{panic(e)}
   for i,a:=range args{if a=="--manifest"{if os.WriteFile(args[i+1],data,0600)!=nil{panic("capture")}}}
  }else if args[1]=="publish"{
   data,e:=os.ReadFile(filepath.Join(root,"bridge-core",".work","sdk","phone-pc-bridge.aar"));if e!=nil{panic(e)}
   if os.WriteFile(filepath.Join(root,"built.aar"),data,0600)!=nil{panic("receipt")}
  }
 case "jar":
 default:panic("unexpected tool "+name)
 }
}
'''


class SdkBuildPinsCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        cls.temporary = tempfile.TemporaryDirectory(prefix="sdk-arguments-", dir=work)
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        source = cls.root / "tool.go"
        source.write_text(TOOL, encoding="utf-8")
        cls.tool = cls.root / "tool.exe"
        env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off", GOOS="windows", GOARCH="amd64", CGO_ENABLED="0",
                   GOCACHE=str(PROJECT / ".work/toolchain/cache"), GOMODCACHE=str(PROJECT / ".work/toolchain/modules"),
                   GOPATH=str(PROJECT / ".work/toolchain/gopath"), GOFLAGS="-mod=readonly")
        subprocess.run([str(GO), "build", "-o", str(cls.tool), str(source)], env=env, check=True, timeout=60,
                       capture_output=True, text=True)

    def fixture(self, pins):
        root = self.root / self._testMethodName
        core = root / "bridge-core"
        core.mkdir(parents=True)
        shutil.copyfile(PROJECT / "bridge-core/build-sdk.ps1", core / "build-sdk.ps1")
        shutil.copyfile(PROJECT / "bridge-core/sdk_archive.py", core / "sdk_archive.py")
        (core / "sdk-build-pins.json").write_text(json.dumps(pins), encoding="utf-8")
        (core / "android").mkdir()
        (core / "android/AndroidManifest.xml").write_text("<manifest/>", encoding="utf-8")
        for name in ["BridgeNetwork.java", "BridgeRecovery.java"]:
            (core / "android" / name).write_text("// boundary fixture", encoding="utf-8")
        (root / "test-suite").mkdir()
        for name in ["BuildWorkCheck.ps1", "Run-Go.ps1"]:
            (root / "test-suite" / name).write_text("$ErrorActionPreference='Stop'\n", encoding="utf-8")
        (root / "test-suite/Run-Go.ps1").write_text(
            "param([string]$Go,[string[]]$Packages)\n"
            "$Packages | ConvertTo-Json -AsArray | Set-Content -LiteralPath (Join-Path $PSScriptRoot '../test-selection.json')\n",
            encoding="utf-8")
        paths = {"Go": "tools/bin/go.exe", "Gomobile": "tools/bin/gomobile.exe",
                 "Sdk": "android-sdk", "Ndk": "ndk", "Jdk": "jdk"}
        for relative in ["tools/bin/go.exe", "tools/bin/gomobile.exe", "tools/bin/gobind.exe", "tools/bin/python.exe",
                         "jdk/bin/javac.exe", "jdk/bin/jar.exe", "ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"]:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.tool, path)
        jar = root / ("android-sdk/platforms/android-" + str(pins["compileSdk"]) + "/android.jar")
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b"mock Android platform")
        (root / "ndk/source.properties").write_text("Pkg.Revision = " + pins["ndk"], encoding="utf-8")
        for directory in ["license", "tools"]:
            (root / directory).mkdir(exist_ok=True)
            (root / directory / "LICENSE").write_text("mock notice", encoding="utf-8")
        return root, core, paths

    def run_builder(self, root, core, paths, test_package=None):
        (root / "captured-inputs.json").write_text(json.dumps({"inputs": [{"path": name} for name in sorted(SDK["build_paths"](root))]}), encoding="utf8")
        command = [shutil.which("pwsh") or "pwsh", "-NoProfile", "-File", str(core / "build-sdk.ps1")]
        for name, relative in paths.items():
            command += ["-" + name, str(root / relative)]
        if test_package:
            command += ["-TestPackages", test_package]
        return subprocess.run(command, env=dict(os.environ, SDK_ARGUMENT_FIXTURE=str(root), SDK_FIXTURE_PYTHON=sys.executable),
                              capture_output=True, text=True, timeout=30)

    def test_actual_bind_and_javac_arguments_follow_changed_pins(self):
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf-8"))
        pins.update(androidApi=34, compileSdk=37)
        root, core, paths = self.fixture(pins)
        result = self.run_builder(root, core, paths)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        rows = [json.loads(line) for line in (root / "arguments.jsonl").read_text(encoding="utf-8").splitlines()]
        notices = next(row for row in rows if row["tool"] == "go" and row["args"][0] == "list")
        self.assertEqual((notices["goos"], notices["goarch"], notices["cgo"]), ("android", "arm64", "1"))
        self.assertIn("-tags=android", notices["args"])
        self.assertIn("./mobile", notices["args"])
        self.assertIn("golang.org/x/mobile/bind/seq", notices["args"])
        publication = next(row for row in rows if row["tool"] == "python" and row["args"][1] == "create")
        self.assertEqual((publication["goos"], publication["goarch"], publication["cgo"]), ("windows", "amd64", "0"))
        binds = [row["args"] for row in rows if row["tool"] == "gomobile"]
        self.assertEqual(len(binds), 2)
        bind = binds[0]
        self.assertIn("-target=android/arm64", bind)
        self.assertIn("-androidapi=34", bind)
        self.assertIn("-trimpath", bind)
        self.assertIn("-trimpath", binds[1])
        self.assertTrue(any("-buildid= -X 'runtime.modinfo=fixture module receipt'" in value for value in binds[1]))
        self.assertTrue(any(row["tool"] == "go" and "-verify" in row["args"] for row in rows))
        for row in rows:
            if row["tool"] == "gomobile":
                self.assertEqual(Path(row["javac"]).resolve(), (root / "jdk/bin/javac.exe").resolve())
        javac = next(row["args"] for row in rows if row["tool"] == "javac" and "-classpath" in row["args"])
        self.assertEqual(javac[javac.index("-classpath") + 1].split(";")[1],
                         str(root / "android-sdk/platforms/android-37/android.jar"))
        self.assertFalse((core / ".work/sdk").exists())
        archive_calls = [row["args"] for row in rows if row["tool"] == "python" and row["args"][0].endswith("sdk_archive.py")]
        self.assertEqual([args[1] for args in archive_calls], ["merge-jar", "pack-aar"])
        self.assertFalse(any(row["tool"] == "jar" for row in rows))
        self.assertEqual(json.loads((root / "test-selection.json").read_text(encoding="utf-8-sig")),
                         ["./protocol", "./codex", "./mobile", "./pc", "./cmd/bridge-pc-check", "./files", "./backend"])

    def test_selected_package_reaches_real_suite_invocation(self):
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf-8"))
        root, core, paths = self.fixture(pins)
        result = self.run_builder(root, core, paths, test_package="./mobile")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads((root / "test-selection.json").read_text(encoding="utf-8-sig")), ["./mobile"])

    def test_manifested_third_wrapper_is_compiled_into_actual_jar(self):
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf8"))
        root, core, paths = self.fixture(pins)
        custom_jdk = root / "custom configured JDK"
        with ZipFile(root / ("android-sdk/platforms/android-" + str(pins["compileSdk"]) + "/android.jar"), "w"):
            pass
        (core / "android/ThirdWrapper.java").write_text(
            "package local.phonepc.bridge; public final class ThirdWrapper { public static int value() { return 37; } }\n",
            encoding="utf8")
        subprocess.run(["cmd", "/c", "mklink", "/J", str(custom_jdk), str(configured_jdk())],
                       check=True, capture_output=True, text=True, timeout=10)
        try:
            with patch.dict(os.environ, {"JAVA_HOME": str(custom_jdk)}):
                paths["Jdk"] = str(configured_jdk())
                result = self.run_builder(root, core, paths)
        finally:
            custom_jdk.rmdir()  # Remove only the owned junction, never its configured JDK target.
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        rows = [json.loads(line) for line in (root / "arguments.jsonl").read_text(encoding="utf8").splitlines()]
        for row in rows:
            if row["tool"] == "gomobile":
                self.assertEqual(Path(row["javac"]).absolute(), custom_jdk / "bin/javac.exe")
        captured = json.loads((root / "captured-inputs.json").read_text(encoding="utf8"))
        self.assertIn("bridge-core/android/ThirdWrapper.java", [row["path"] for row in captured["inputs"]])
        with ZipFile(root / "built.aar") as aar:
            from io import BytesIO
            with ZipFile(BytesIO(aar.read("classes.jar"))) as jar:
                self.assertTrue(jar.read("local/phonepc/bridge/ThirdWrapper.class").startswith(b"\xca\xfe\xba\xbe"))

    def test_real_compiler_configuration_is_required_without_default_fallback(self):
        for configured in ["", str(self.root / "missing configured JDK")]:
            with self.subTest(configured=configured), patch.dict(os.environ, {"JAVA_HOME": configured}):
                with self.assertRaisesRegex(RuntimeError, "JAVA_HOME"):
                    configured_jdk()

    def test_wrong_configured_compiler_version_is_refused(self):
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf8"))
        root, core, paths = self.fixture(pins)
        with patch.dict(os.environ, {"SDK_FIXTURE_JAVAC_VERSION": "17.0.19"}):
            result = self.run_builder(root, core, paths)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SDK requires javac " + pins["javac"], result.stderr)
        self.assertFalse((root / "built.aar").exists())
        self.assertFalse((core / ".work/sdk").exists())

    def test_empty_wrapper_inventory_refuses_compilation(self):
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf8"))
        root, core, paths = self.fixture(pins)
        for source in (core / "android").glob("*.java"):
            source.unlink()
        result = self.run_builder(root, core, paths)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Captured SDK wrapper source inventory is empty", result.stderr)
        rows = [json.loads(line) for line in (root / "arguments.jsonl").read_text(encoding="utf8").splitlines()]
        self.assertFalse(any(row["tool"] == "javac" and "-classpath" in row["args"] for row in rows))
        self.assertFalse((core / ".work/sdk").exists())

    def test_invalid_api_or_abi_pins_stop_before_tool_invocation(self):
        pins = json.loads((PROJECT / "bridge-core/sdk-build-pins.json").read_text(encoding="utf-8"))
        root, core, paths = self.fixture(pins)
        for patch in [{"androidApi": "34"}, {"androidApi": 34.5}, {"androidApi": True},
                      {"androidApi": 20}, {"compileSdk": 32}, {"abi": "x86_64"}]:
            with self.subTest(patch=patch):
                (core / "sdk-build-pins.json").write_text(json.dumps(pins | patch), encoding="utf-8")
                result = self.run_builder(root, core, paths)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Unsupported SDK build pins", result.stderr)
                self.assertFalse((root / "arguments.jsonl").exists())
                self.assertFalse((core / ".work/sdk").exists())


if __name__ == "__main__":
    unittest.main()
