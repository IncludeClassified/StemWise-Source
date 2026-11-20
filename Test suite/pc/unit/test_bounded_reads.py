from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from stemwise_engine.common import read_bounded_regular_bytes


class BoundedReadTests(unittest.TestCase):
    def test_special_files_are_rejected_before_open_can_block(self):
        for kind in (stat.S_IFDIR, stat.S_IFIFO, stat.S_IFSOCK):
            metadata = SimpleNamespace(st_mode=kind | 0o600, st_file_attributes=0)
            with self.subTest(kind=kind), patch.object(Path, 'lstat', return_value=metadata), patch(
                'stemwise_engine.common.os.open', side_effect=AssertionError("Opened a nonregular input")
            ):
                with self.assertRaisesRegex(ValueError, "regular file"):
                    read_bounded_regular_bytes(Path('special-input'), max_bytes=16)

    def test_windows_reparse_file_is_rejected_before_open(self):
        metadata = SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_file_attributes=0x400)
        with patch.object(Path, "lstat", return_value=metadata), patch(
            'stemwise_engine.common.os.open', side_effect=AssertionError("Followed a reparse input")
        ):
            with self.assertRaisesRegex(ValueError, "reparse"):
                read_bounded_regular_bytes(Path("input.json"), max_bytes=16)

    def test_regular_reads_preserve_bytes_and_enforce_the_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "input.json"
            path.write_bytes(b"{\"value\":1}")
            self.assertEqual(path.read_bytes(), read_bounded_regular_bytes(path, max_bytes=11))
            with self.assertRaisesRegex(ValueError, "limit"):
                read_bounded_regular_bytes(path, max_bytes=10)
            path.write_bytes(b"")
            self.assertEqual(b"", read_bounded_regular_bytes(path, max_bytes=1))


if __name__ == "__main__":
    unittest.main()
