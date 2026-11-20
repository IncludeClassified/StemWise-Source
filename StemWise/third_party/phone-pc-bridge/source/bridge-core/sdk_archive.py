"""Assemble SDK archives with stable ordering and metadata, independent of file times."""
import argparse
from io import BytesIO
import os
from pathlib import Path, PurePosixPath
import stat
import tempfile
from zipfile import BadZipFile, ZipFile, ZipInfo, ZIP_DEFLATED


STAMP = (1980, 1, 1, 0, 0, 0)
REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
AAR_REQUIRED = {"AndroidManifest.xml", "classes.jar", "jni/arm64-v8a/libgojni.so", "proguard.txt",
                "assets/phone-pc-bridge-licenses.txt"}


def entry_name(name):
    path = PurePosixPath(name)
    if not name or "\\" in name or ":" in name or path.is_absolute() or ".." in path.parts or path.as_posix() != name:
        raise ValueError("Invalid SDK archive entry: " + name)
    return name


def archive_bytes(entries):
    result = BytesIO()
    with ZipFile(result, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = ZipInfo(entry_name(name), STAMP)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data, compress_type=ZIP_DEFLATED, compresslevel=9)
    return result.getvalue()


def directory_entries(directory):
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or getattr(info, "st_file_attributes", 0) & REPARSE_POINT:
        raise ValueError("SDK assembly requires an ordinary source directory")
    entries = {}
    for path in directory.rglob("*"):
        if path.is_symlink() or getattr(path.lstat(), "st_file_attributes", 0) & REPARSE_POINT:
            raise ValueError("SDK assembly cannot follow symlinks")
        if path.is_file():
            entries[entry_name(path.relative_to(directory).as_posix())] = path.read_bytes()
    return entries


def write_archive(output, entries):
    data = archive_bytes(entries)
    candidate = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".sdk-archive-", dir=output.parent, delete=False) as stream:
            candidate = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(candidate, output)
    finally:
        if candidate is not None:
            candidate.unlink(missing_ok=True)


def merge_jar(jar, classes):
    entries = {}
    with ZipFile(jar) as source:
        seen = set()
        for info in source.infolist():
            if info.filename in seen:
                raise ValueError("Duplicate SDK JAR entry: " + info.filename)
            seen.add(info.filename)
            if not info.is_dir():
                entries[entry_name(info.filename)] = source.read(info)
    compiled = directory_entries(classes)
    collisions = entries.keys() & compiled.keys()
    if collisions:
        raise ValueError("Duplicate SDK JAR entry: " + sorted(collisions)[0])
    entries.update(compiled)
    write_archive(jar, entries)


def pack_aar(source, output):
    entries = directory_entries(source)
    missing = sorted(name for name in AAR_REQUIRED if not entries.get(name))
    if missing:
        raise ValueError("Missing or empty SDK AAR entries: " + ", ".join(missing))
    try:
        with ZipFile(BytesIO(entries["classes.jar"])) as classes:
            if (not any(info.filename.endswith(".class") and info.file_size for info in classes.infolist())
                    or classes.testzip() is not None):
                raise ValueError("Invalid or empty SDK classes JAR")
    except BadZipFile as error:
        raise ValueError("Invalid SDK classes JAR") from error
    write_archive(output, entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    jar = sub.add_parser("merge-jar")
    jar.add_argument("--jar", type=Path, required=True)
    jar.add_argument("--classes", type=Path, required=True)
    aar = sub.add_parser("pack-aar")
    aar.add_argument("--source", type=Path, required=True)
    aar.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "merge-jar":
        merge_jar(args.jar, args.classes)
    else:
        pack_aar(args.source, args.output)


if __name__ == "__main__":
    main()
