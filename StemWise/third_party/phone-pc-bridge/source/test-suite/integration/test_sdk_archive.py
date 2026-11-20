from io import BytesIO
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest import mock
from zipfile import ZipFile, ZipInfo


PROJECT = Path(__file__).resolve().parents[2]
SDK = runpy.run_path(str(PROJECT / "bridge-core/sdk_archive.py"))


def aar_source(root):
    source = root / "source"
    source.mkdir()
    entries = {"AndroidManifest.xml": b"<manifest/>", "classes.jar": SDK["archive_bytes"]({"Fixture.class": b"fixture"}),
               "jni/arm64-v8a/libgojni.so": b"native", "proguard.txt": b"rules",
               "assets/phone-pc-bridge-licenses.txt": b"notices"}
    for name, data in entries.items():
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return source, entries


class SdkArchiveCheck(unittest.TestCase):
    def test_real_nested_assembly_ignores_root_order_and_wall_clock(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sdk-archive-", dir=work) as temporary:
            outputs = []
            for index in range(2):
                root = Path(temporary) / ("a" if index == 0 else "different-build-directory")
                stage, classes = root / "archive", root / "java"
                stage.mkdir(parents=True)
                classes.mkdir()
                names = ["go/Seq.class", "mobile/Client.class", "META-INF/MANIFEST.MF"]
                with ZipFile(stage / "classes.jar", "w") as jar:
                    for name in names[::1 if index == 0 else -1]:
                        info = ZipInfo(name, (2001 + index, 2, 3, 4, 5, 6))
                        info.extra = b"\xfe\xca\x00\x00"
                        info.comment = b"noncanonical"
                        jar.writestr(info, name.encode())
                for name, data in [("bridge/Recovery.class", b"compiled-wrapper"), ("bridge/Network.class", b"compiled-network")][::1 if index == 0 else -1]:
                    path = classes / name
                    path.parent.mkdir(exist_ok=True)
                    path.write_bytes(data)
                    os.utime(path, (1_000_000_000 + index * 123456, 1_000_000_000 + index * 123456))
                for name, data in [("AndroidManifest.xml", b"<manifest/>"), ("R.txt", b""), ("jni/arm64-v8a/libgojni.so", b"native-payload"),
                                   ("proguard.txt", b"fixture rules"), ("assets/phone-pc-bridge-licenses.txt", b"fixture notices")]:
                    path = stage / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                    os.utime(path, (1_000_000_000 + index * 123456, 1_000_000_000 + index * 123456))
                SDK["merge_jar"](stage / "classes.jar", classes)
                output = root / "sdk.aar"
                SDK["pack_aar"](stage, output)
                outputs.append(output.read_bytes())
            self.assertEqual(outputs[0], outputs[1])
            with ZipFile(BytesIO(outputs[0])) as aar:
                self.check_entries(aar)
                self.assertEqual(aar.read("jni/arm64-v8a/libgojni.so"), b"native-payload")
                with ZipFile(BytesIO(aar.read("classes.jar"))) as jar:
                    self.check_entries(jar)
                    self.assertEqual(jar.read("bridge/Recovery.class"), b"compiled-wrapper")
                    self.assertEqual(jar.read("go/Seq.class"), b"go/Seq.class")

    def check_entries(self, archive):
        self.assertEqual(archive.namelist(), sorted(archive.namelist()))
        self.assertEqual(archive.comment, b"")
        for info in archive.infolist():
            self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))
            self.assertEqual(info.extra, b"")
            self.assertEqual(info.comment, b"")
            self.assertEqual(info.create_system, 3)
            self.assertEqual(info.external_attr, 0o100644 << 16)

    def test_duplicate_or_unsafe_entries_are_refused(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sdk-archive-bad-", dir=work) as temporary:
            root = Path(temporary)
            (root / "classes").mkdir()
            jar = root / "classes.jar"
            with self.assertRaisesRegex(ValueError, "Invalid SDK archive entry"):
                SDK["entry_name"]("back\\slash.class")
            for name in ["../outside.class", "/absolute.class", "drive:C.class"]:
                with ZipFile(jar, "w") as archive:
                    archive.writestr(name, b"bad")
                before = jar.read_bytes()
                with self.assertRaisesRegex(ValueError, "Invalid SDK archive entry"):
                    SDK["merge_jar"](jar, root / "classes")
                self.assertEqual(before, jar.read_bytes())
            with self.assertWarns(UserWarning):
                with ZipFile(jar, "w") as archive:
                    archive.writestr("Same.class", b"first")
                    archive.writestr("Same.class", b"second")
            with self.assertRaisesRegex(ValueError, "Duplicate SDK JAR entry"):
                SDK["merge_jar"](jar, root / "classes")

    def test_invalid_aar_root_or_required_entry_preserves_existing_output(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sdk-archive-roots-", dir=work) as temporary:
            root = Path(temporary)
            source, entries = aar_source(root)
            output = root / "healthy.aar"
            good = SDK["archive_bytes"](entries)
            output.write_bytes(good)
            invalid = [root / "missing", root / "regular-file"]
            invalid[-1].write_text("not a directory", encoding="utf8")
            link = root / "linked-root"
            if os.name == "nt":
                subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(source)], check=True, capture_output=True)
            else:
                link.symlink_to(source, target_is_directory=True)
            invalid.append(link)
            for directory in invalid:
                with self.subTest(root=directory.name):
                    output.write_bytes(good)
                    with self.assertRaises((ValueError, OSError)):
                        SDK["pack_aar"](directory, output)
                    self.assertEqual(output.read_bytes(), good)
            for name in entries:
                with self.subTest(missing=name):
                    output.write_bytes(good)
                    path = source / name
                    path.unlink()
                    try:
                        with self.assertRaises(ValueError):
                            SDK["pack_aar"](source, output)
                        self.assertEqual(output.read_bytes(), good)
                    finally:
                        path.write_bytes(entries[name])
            self.assertFalse(list(root.glob(".sdk-archive-*")))

    def test_bad_classes_or_failed_publication_preserves_working_aar(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sdk-archive-publish-", dir=work) as temporary:
            root = Path(temporary)
            source, entries = aar_source(root)
            output = root / "healthy.aar"
            before = SDK["archive_bytes"](entries)
            output.write_bytes(before)
            for invalid in [b"truncated JAR", SDK["archive_bytes"]({}), SDK["archive_bytes"]({"META-INF/MANIFEST.MF": b"manifest"})]:
                with self.subTest(invalid=invalid[:20]):
                    (source / "classes.jar").write_bytes(invalid)
                    with self.assertRaisesRegex(ValueError, "SDK classes JAR"):
                        SDK["pack_aar"](source, output)
                    self.assertEqual(output.read_bytes(), before)
            (source / "classes.jar").write_bytes(entries["classes.jar"])
            with mock.patch.object(SDK["os"], "replace", side_effect=OSError("injected publication failure")):
                with self.assertRaisesRegex(OSError, "publication failure"):
                    SDK["pack_aar"](source, output)
            self.assertEqual(output.read_bytes(), before)
            self.assertFalse(list(root.glob(".sdk-archive-*")))

    def test_compiled_classes_cannot_replace_existing_jar_entries(self):
        work = PROJECT / "test-suite/.work"
        work.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="sdk-archive-collision-", dir=work) as temporary:
            root = Path(temporary)
            classes = root / "classes"
            classes.mkdir()
            jar = root / "classes.jar"
            for replacement in [b"different", b"original"]:
                with self.subTest(replacement=replacement):
                    jar.write_bytes(SDK["archive_bytes"]({"Same.class": b"original"}))
                    (classes / "Same.class").write_bytes(replacement)
                    before = jar.read_bytes()
                    with self.assertRaisesRegex(ValueError, "Duplicate SDK JAR entry"):
                        SDK["merge_jar"](jar, classes)
                    self.assertEqual(jar.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
