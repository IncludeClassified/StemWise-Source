import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

SUITE = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('verify_vendor', SUITE/'native/verify_vendor.py')
VENDOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VENDOR)


class VendorSourcesTests(unittest.TestCase):
    def test_current_retained_subset_matches_exact_manifest(self):
        self.assertEqual(51, VENDOR.verify())

    def test_undeclared_edit_or_file_is_rejected(self):
        for fault in ('edit', 'extra'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=SUITE/'work') as temporary:
                root = Path(temporary)/'vendor'
                shutil.copytree(VENDOR.VENDOR, root)
                if fault == 'edit':
                    with (root/'wavpack/src/unpack_seek.c').open('ab') as stream:
                        stream.write(b'\n/* unreviewed */\n')
                else:
                    (root/'undeclared.h').write_text('')
                with self.assertRaisesRegex(ValueError, 'Vendor file changed|Undeclared'):
                    VENDOR.verify(root)


if __name__ == '__main__':
    unittest.main()
