"""Bind the SDK to production inputs and publish the verified AAR/manifest pair."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


SCHEMA = "bridge-sdk-artifact-v4"
AAR = "phone-pc-bridge.aar"
MANIFEST = "phone-pc-bridge.build.json"
TARGET = {"goos": "android", "goarch": "arm64", "cgo": True, "tags": ["android"]}
GO_SOURCE_FIELDS = ("GoFiles", "CgoFiles", "CFiles", "CXXFiles", "MFiles", "HFiles",
                    "FFiles", "SFiles", "SwigFiles", "SwigCXXFiles", "SysoFiles", "EmbedFiles")
CANDIDATE_SUFFIXES = {".go", ".c", ".cc", ".cpp", ".cxx", ".m", ".h", ".hh", ".hpp",
                      ".hxx", ".f", ".for", ".f90", ".s", ".sx", ".swig", ".swigcxx", ".syso"}


def fingerprint(path):
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while data := stream.read(1 << 20):
            digest.update(data)
            size += len(data)
    return {"sha256": digest.hexdigest(), "size": size}


def android_dependencies(root, go):
    core = (root / "bridge-core").resolve()
    pins = json.loads((core / "sdk-build-pins.json").read_text(encoding="utf-8"))
    if pins.get("abi") != "arm64-v8a":
        raise ValueError("SDK dependency discovery supports the pinned Android arm64 target")
    env = dict(os.environ, GOOS=TARGET["goos"], GOARCH=TARGET["goarch"], CGO_ENABLED="1",
               GOTOOLCHAIN="local", GOWORK="off", GOFLAGS="-mod=readonly")

    def output(*arguments):
        try:
            result = subprocess.run([str(go), *arguments], cwd=core, env=env, capture_output=True,
                                    text=True, encoding="utf-8", timeout=60, check=True)
        except (OSError, subprocess.SubprocessError) as failure:
            detail = getattr(failure, "stderr", None) or str(failure)
            if isinstance(detail, bytes):
                detail = detail.decode("utf-8", errors="replace")
            raise ValueError("SDK dependency discovery failed: " + detail.strip()[:2048]) from failure
        return result.stdout

    if output("env", "GOVERSION").strip() != pins.get("go"):
        raise ValueError("SDK dependency discovery requires pinned Go " + str(pins.get("go")))
    stream = output("list", "-deps", "-json", "-tags=android", "./mobile")
    decoder, offset, paths, scopes, found = json.JSONDecoder(), 0, set(), {}, False
    while offset < len(stream):
        if stream[offset].isspace():
            offset += 1
            continue
        package, offset = decoder.raw_decode(stream, offset)
        if package.get("Incomplete") or package.get("Error") or package.get("DepsErrors"):
            raise ValueError("SDK dependency discovery returned an incomplete package")
        directory = Path(package["Dir"]).resolve()
        if not directory.is_relative_to(core):
            replacement = (package.get("Module") or {}).get("Replace")
            if replacement and not replacement.get("Version"):
                raise ValueError("SDK dependency discovery cannot bind a local replacement outside the shipped module")
            continue  # External modules and the Go runtime are bound by module/toolchain pins.
        found = found or directory == core / "mobile"
        scopes[directory.relative_to(root.resolve()).as_posix()] = bool(package.get("EmbedPatterns"))
        module_file = (package.get("Module") or {}).get("GoMod")
        if module_file:
            module_file = Path(module_file).resolve()
            if not module_file.is_relative_to(core):
                raise ValueError("SDK dependency module configuration leaves the shipped module")
            paths.add(module_file.relative_to(root.resolve()).as_posix())
        for field in GO_SOURCE_FIELDS:
            for name in package.get(field, []):
                source = (directory / name).resolve()
                if not source.is_relative_to(core):
                    raise ValueError("SDK dependency source leaves the shipped module")
                paths.add(source.relative_to(root.resolve()).as_posix())
    if not found or not paths:
        raise ValueError("SDK dependency discovery did not include the mobile target")
    # An embedded .go file can be outside its owning package directory.
    for name in paths:
        if name.endswith(".go"):
            scopes.setdefault(Path(name).parent.as_posix(), False)
    return paths, [{"path": name, "embeds": scopes[name]} for name in sorted(scopes)]


def android_go_paths(root, go):
    return android_dependencies(root, go)[0]


def _source_path(root, name):
    if not isinstance(name, str) or "\\" in name or ":" in name:
        raise ValueError("Invalid SDK production/build input inventory")
    path = Path(name)
    if path.as_posix() != name or path.is_absolute() or ".." in path.parts or not (name == "bridge-core" or name.startswith("bridge-core/")):
        raise ValueError("Invalid SDK production/build input inventory")
    source = root / path
    if not source.resolve().is_relative_to((root / "bridge-core").resolve()):
        raise ValueError("SDK production/build input leaves the shipped module")
    return source


def _validate_scopes(root, scopes):
    if not isinstance(scopes, list) or not scopes:
        raise ValueError("Invalid SDK candidate scopes")
    for scope in scopes:
        if (not isinstance(scope, dict) or set(scope) != {"path", "embeds"}
                or not isinstance(scope["embeds"], bool)):
            raise ValueError("Invalid SDK candidate scopes")
        _source_path(root, scope["path"])
    names = [scope["path"] for scope in scopes]
    if names != sorted(set(names)):
        raise ValueError("Invalid SDK candidate scopes")


def candidate_snapshot(root, scopes):
    """Conservative discovery triggers, not the list of compiled Android inputs."""
    _validate_scopes(root, scopes)
    paths = set()
    core = (root / "bridge-core").resolve()
    for scope in scopes:
        directory = _source_path(root, scope["path"])
        if not directory.is_dir():
            raise ValueError("SDK candidate directory missing; rebuild the SDK pair")
        entries = directory.rglob("*") if scope["embeds"] else directory.iterdir()
        for source in entries:
            relative = source.relative_to(root).as_posix()
            if any(part in {".git", ".hg", ".svn", ".bzr"} for part in source.relative_to(directory).parts):
                continue
            if not source.is_file():
                continue
            if (scope["embeds"] or (source.suffix.lower() in CANDIDATE_SUFFIXES
                                    and not source.name.endswith("_test.go"))):
                _source_path(root, relative)
                paths.add(relative)
        # A newly introduced nested module can change import resolution too.
        for parent in [directory, *directory.parents]:
            if not parent.resolve().is_relative_to(core):
                break
            for name in ["go.mod", "go.sum"]:
                source = parent / name
                if source.is_file():
                    relative = source.relative_to(root).as_posix()
                    _source_path(root, relative)
                    paths.add(relative)
    return [{"path": name, **fingerprint(root / name)} for name in sorted(paths)]


def build_paths(root):
    core = root / "bridge-core"
    paths = {p.relative_to(root).as_posix() for p in (core / "android").iterdir()
             if p.suffix in {".java", ".xml"}}
    paths.update("bridge-core/" + name for name in
                 ["go.mod", "go.sum", "build-sdk.ps1", "sdk-build-pins.json", "sdk_artifact.py", "sdk_archive.py"])
    paths.add("bridge-core/internal/sdkmodinfo/main.go")
    return paths


def production_paths(root, go="go"):
    return sorted(build_paths(root) | android_go_paths(root, go))


def source_paths(root, go="go"):
    go_paths, scopes = android_dependencies(root, go)
    paths = build_paths(root) | go_paths
    paths.update(row["path"] for row in candidate_snapshot(root, scopes))
    core = root / "bridge-core"
    paths.update(p.relative_to(root).as_posix() for p in core.rglob("*.go")
                 if ".work" not in p.relative_to(core).parts and not p.name.endswith("_test.go"))
    paths.update(p.relative_to(root).as_posix() for p in (root / "test-suite/bridge-core").rglob("*")
                 if p.is_file() and p.suffix in {".go", ".java"})
    paths.update("test-suite/" + name for name in ["BuildWorkCheck.ps1", "Run-Go.ps1"])
    paths.add("test-suite/GoDiscoveryCheck.ps1")
    paths.add("test-suite/integration/test_sdk_artifact.py")
    paths.add("test-suite/integration/test_sdk_build.py")
    paths.add("test-suite/integration/test_sdk_archive.py")
    paths.add("test-suite/integration/test_sdk_notices.py")
    paths.add("test-suite/integration/test_sdk_modinfo.py")
    paths.add("test-suite/integration/check_sdk_reproducible.py")
    paths.add("test-suite/integration/test_sdk_reproducible.py")
    paths.update(p.relative_to(root).as_posix() for p in (root / "test-suite/android").glob("*")
                 if p.is_file() and p.suffix in {".py", ".java"})
    return sorted(paths)


def capture(root, go="go"):
    paths, scopes = android_dependencies(root, go)
    candidates = candidate_snapshot(root, scopes)
    current_paths, current_scopes = android_dependencies(root, go)
    if paths != current_paths or scopes != current_scopes or candidates != candidate_snapshot(root, current_scopes):
        raise ValueError("SDK candidate inventory changed during dependency discovery; retry capture")
    inputs = [{"path": name, **fingerprint(root / name)} for name in sorted(build_paths(root) | paths)]
    return {"schema": SCHEMA,
            "target": TARGET,
            "toolchain": json.loads((root / "bridge-core/sdk-build-pins.json").read_text(encoding="utf-8")),
            "candidateScopes": scopes,
            "candidates": candidates,
            "inputs": inputs,
            "inputsSha256": _inputs_hash(inputs)}


def create(root, aar, manifest, captured=None, go="go"):
    metadata = capture(root, go)
    compiled = ("schema", "target", "toolchain", "inputs", "inputsSha256")
    if captured is not None and any(captured.get(key) != metadata[key] for key in compiled):
        raise ValueError("SDK production/build inputs changed during compilation; rebuild the SDK pair")
    metadata["aar"] = fingerprint(aar)
    manifest.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return metadata


def verify(root, artifact_dir, go="go", force_discovery=False):
    metadata = json.loads((artifact_dir / MANIFEST).read_text(encoding="utf-8"))
    if not isinstance(metadata, dict):
        raise ValueError("Invalid SDK artifact manifest; expected an object")
    if metadata.get("schema") != SCHEMA:
        raise ValueError("Unsupported SDK manifest; rebuild with the current SDK builder")
    if metadata.get("target") != TARGET:
        raise ValueError("SDK Android target changed; rebuild and publish the SDK pair")
    if metadata.get("aar") != fingerprint(artifact_dir / AAR):
        raise ValueError("SDK AAR bytes do not match the consumed build manifest")
    pins = json.loads((root / "bridge-core/sdk-build-pins.json").read_text(encoding="utf-8"))
    if metadata.get("toolchain") != pins:
        raise ValueError("SDK tool pins changed; rebuild and publish the SDK pair")
    rows = metadata.get("inputs")
    _validate_rows(root, rows)
    if metadata.get("inputsSha256") != _inputs_hash(rows):
        raise ValueError("SDK production/build input inventory changed; rebuild and publish the SDK pair")
    names = [row["path"] for row in rows]
    if not build_paths(root).issubset(names):
        raise ValueError("SDK production/build input inventory changed; rebuild and publish the SDK pair")
    scopes = metadata.get("candidateScopes")
    _validate_scopes(root, scopes)
    android_inputs = set(names) - build_paths(root)
    if {scope["path"] for scope in scopes} != {Path(name).parent.as_posix() for name in android_inputs if name.endswith(".go")}:
        raise ValueError("Invalid SDK candidate scopes for production inputs")
    candidates = metadata.get("candidates")
    _validate_rows(root, candidates)
    if not set(name for name in android_inputs if name.endswith(".go") and not name.endswith("_test.go")).issubset(row["path"] for row in candidates):
        raise ValueError("Invalid SDK candidate inventory for production inputs")
    for row in rows:
        if row != {"path": row["path"], **fingerprint(root / row["path"])}:
            raise ValueError("SDK production/build input changed: " + row["path"] + "; rebuild and publish the SDK pair")
    current_candidates = candidate_snapshot(root, scopes)
    if force_discovery or current_candidates != candidates:
        # Only candidate changes need Go. It remains the authority for build tags,
        # init-only imports and embed matching; excluded changes need no rebuild.
        paths, current_scopes = android_dependencies(root, go)
        if names != sorted(build_paths(root) | paths) or scopes != current_scopes:
            raise ValueError("SDK production/build input inventory changed; rebuild and publish the SDK pair")
        if current_candidates != candidate_snapshot(root, scopes):
            raise ValueError("SDK candidate inventory changed during dependency discovery; retry verification")
        for row in rows:
            if row != {"path": row["path"], **fingerprint(root / row["path"])}:
                raise ValueError("SDK production/build input changed during dependency discovery; rebuild the SDK pair")
    return metadata


def _validate_rows(root, rows):
    if not isinstance(rows, list) or not rows:
        raise ValueError("Invalid SDK production/build input inventory")
    for row in rows:
        if (not isinstance(row, dict) or set(row) != {"path", "sha256", "size"}
                or not isinstance(row["sha256"], str) or len(row["sha256"]) != 64
                or any(char not in "0123456789abcdef" for char in row["sha256"])
                or type(row["size"]) is not int or row["size"] < 0):
            raise ValueError("Invalid SDK production/build input inventory")
        _source_path(root, row["path"])
    names = [row["path"] for row in rows]
    if names != sorted(set(names)):
        raise ValueError("SDK production/build input inventory changed; rebuild and publish the SDK pair")


def _inputs_hash(rows):
    return hashlib.sha256(json.dumps(rows, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def publish(root, destination, source=None, go="go"):
    source = source or root / "bridge-core"
    verify(root, source, go)
    if not destination.is_dir():
        raise ValueError("SDK publication requires an existing artifact directory")
    # The manifest is the commit point; verification rejects an interrupted mixed pair.
    work = Path(tempfile.mkdtemp(prefix=".bridge-publish-", dir=destination))
    retain = False
    try:
        prior = {}
        for name in [AAR, MANIFEST]:
            target = destination / name
            prior[name] = target.exists()
            if target.exists():
                if not target.is_file() or target.is_symlink():
                    raise ValueError("SDK destination must contain ordinary artifact files")
                shutil.copyfile(target, work / (name + ".previous"))
            shutil.copyfile(source / name, work / name)
        replaced = []
        try:
            for name in [AAR, MANIFEST]:
                os.replace(work / name, destination / name)
                replaced.append(name)
            verify(root, destination, go)
        except BaseException:
            try:
                for name in reversed(replaced):
                    if prior[name]:
                        os.replace(work / (name + ".previous"), destination / name)
                    else:
                        (destination / name).unlink()
            except OSError as rollback:
                retain = True
                raise OSError("SDK publication rollback failed; prior artifacts retained at " + str(work)) from rollback
            raise
    finally:
        if not retain:
            if work.parent.resolve() != destination.resolve() or not work.name.startswith(".bridge-publish-"):
                raise ValueError("Unsafe SDK publication cleanup path")
            shutil.rmtree(work)


def main():
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ["verify", "publish"]:
        command = sub.add_parser(name)
        command.add_argument("--artifact-dir", type=Path, required=True)
        command.add_argument("--go", default="go", help="Pinned Go executable used for Android dependency discovery")
        if name == "verify":
            command.add_argument("--discover", action="store_true", help="Run exact pinned-Go discovery even when candidates are unchanged")
        if name == "publish":
            command.add_argument("--source-dir", type=Path)
    command = sub.add_parser("capture")
    command.add_argument("--go", default="go")
    command.add_argument("--manifest", type=Path, required=True)
    command = sub.add_parser("create")
    command.add_argument("--go", default="go")
    command.add_argument("--aar", type=Path, required=True)
    command.add_argument("--manifest", type=Path, required=True)
    command.add_argument("--captured", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "capture":
            args.manifest.write_text(json.dumps(capture(root, args.go), indent=2) + "\n", encoding="utf-8")
        elif args.command == "create":
            captured = json.loads(args.captured.read_text(encoding="utf-8")) if args.captured else None
            create(root, args.aar, args.manifest, captured, args.go)
        elif args.command == "publish":
            publish(root, args.artifact_dir.resolve(), args.source_dir.resolve() if args.source_dir else None, args.go)
        else:
            verify(root, args.artifact_dir.resolve(), args.go, force_discovery=args.discover)
    except (OSError, ValueError, KeyError, TypeError) as failure:
        parser.exit(1, "SDK identity check failed: " + str(failure) + "\n")
    print("SDK production/build inputs captured." if args.command == "capture" else
          "SDK " + args.command + " passed: production/build inputs and AAR/manifest pair agree.")


if __name__ == "__main__":
    main()
