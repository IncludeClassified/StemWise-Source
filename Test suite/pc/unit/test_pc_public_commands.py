"""Only the reviewed workflow can publish phone packages."""
import contextlib
import io
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.stemwise_pc as stemwise_pc


class PublicCommandsTest(unittest.TestCase):
    def test_metadata_only_export_commands_are_not_admitted(self):
        for command in ('run', 'bulk', 'validate'):
            with self.subTest(command=command), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    stemwise_pc.parser().parse_args(['--config', 'unused.json', command])
        for command in ('doctor', 'inventory', 'status'):
            self.assertEqual(stemwise_pc.parser().parse_args(['--config', 'unused.json', command]).command, command)


if __name__ == '__main__':
    unittest.main()
