"""Build the same SDK source in two isolated directories and compare actual AAR bytes."""
import argparse
import hashlib
import os
from pathlib import Path
import runpy
import shutil
import signal
import subprocess
import tempfile
import time
from zipfile import ZipFile


class BuildRetirementError(RuntimeError):
    pass


def retire_build(build, timeout=20):
    """Retire the owned session before its staging can be reclaimed."""
    try:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(build.pid), "/T", "/F"], check=True, timeout=timeout)
            build.wait(timeout=timeout)
            return

        def group_exists():
            build.poll()  # Reap the direct child, including after a group signal.
            try:
                os.killpg(build.pid, 0)
                return True
            except ProcessLookupError:
                return False

        for signum in [signal.SIGTERM, signal.SIGKILL]:
            if not group_exists():
                return
            try:
                os.killpg(build.pid, signum)
            except ProcessLookupError:
                return
            deadline = time.monotonic() + timeout / 2
            while group_exists():
                if time.monotonic() >= deadline:
                    break
                time.sleep(.02)
            else:
                return
        raise BuildRetirementError("SDK process group did not retire before the deadline")
    except BaseException as failure:
        raise BuildRetirementError("SDK process-tree retirement is unconfirmed: " + str(failure)) from failure


def run_build(command, timeout=900):
    build = subprocess.Popen(command, start_new_session=os.name != "nt")
    try:
        build.wait(timeout=timeout)
    except BaseException:
        retire_build(build)
        raise
    # A successful parent exit does not establish that all descendants exited.
    if os.name != "nt":
        retire_build(build)
    if build.returncode:
        raise subprocess.CalledProcessError(build.returncode, command)


def main():
    project = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ["go", "gomobile", "sdk", "ndk", "jdk"]:
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    paths = {name: str(getattr(args, name).resolve()) for name in ["go", "gomobile", "sdk", "ndk", "jdk"]}
    identity = runpy.run_path(str(project / "bridge-core/sdk_artifact.py"))
    sources = {name: (project / name).read_bytes() for name in identity["source_paths"](project, go=paths["go"])}
    work = project / "test-suite/.work"
    work.mkdir(exist_ok=True)
    if work.resolve().parent != (project / "test-suite").resolve():
        raise ValueError("Reproducibility staging must stay inside the test suite")
    temporary = Path(tempfile.mkdtemp(prefix="sdk-repro-", dir=work))
    cleanup = True
    try:
        artifacts = []
        for name in ["first", "second-different-directory"]:
            root = Path(temporary) / name
            for relative, data in sources.items():
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            command = [shutil.which("pwsh") or "pwsh", "-NoProfile", "-File", str(root / "bridge-core/build-sdk.ps1")]
            for option, path in paths.items():
                command += ["-" + option, path]
            command += ["-TestPackages", "./mobile"]
            run_build(command)
            artifacts.append(root / "bridge-core/phone-pc-bridge.aar")
        first, second = [path.read_bytes() for path in artifacts]
        if first != second:
            with ZipFile(artifacts[0]) as left, ZipFile(artifacts[1]) as right:
                changed = sorted(name for name in set(left.namelist()) | set(right.namelist())
                                 if name not in left.namelist() or name not in right.namelist() or left.read(name) != right.read(name))
            raise RuntimeError("SDK AARs differ across build directories; differing payloads: " + ", ".join(changed))
        print("Two independent SDK builds are byte-identical: " + hashlib.sha256(first).hexdigest() + " (" + str(len(first)) + " bytes).")
        if any((project / name).read_bytes() != data for name, data in sources.items()):
            raise RuntimeError("Original source changed during the reproducibility check")
    except BuildRetirementError as failure:
        cleanup = False
        raise BuildRetirementError(str(failure) + "; SDK staging retained at " + str(temporary)) from failure
    finally:
        if cleanup:
            shutil.rmtree(temporary)


if __name__ == "__main__":
    main()
