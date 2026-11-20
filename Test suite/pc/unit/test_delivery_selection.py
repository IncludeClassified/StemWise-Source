"""Current playlist authority and public folder closure cannot be replaced with counts."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

from stemwise_engine.delivery_selection import catalogue, layout


class DeliverySelectionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.workflow = SimpleNamespace(workspace_root=self.root, pc_side_root=self.root/'PC side', ready=self.root/'Ready to Import')
        self.path = self.workflow.pc_side_root/'qobuz-comparison/comparison-results/library-files.json'
        self.path.parent.mkdir(parents=True)

    def write(self, rows):
        raw = json.dumps({'playlist': rows}).encode()
        self.path.write_bytes(raw)

    def test_selected_ids_preserve_playlist_slots_and_exact_recordings(self):
        self.write([{'videoId':'abcdefghijk','source':'qobuz','trackId':11},
                          {'videoId':'lmnopqrstuv','source':'qobuz','trackId':11},
                          {'videoId':'01234567890','source':'youtube'},
                          {'videoId':'zyxwvutsrqp','source':'unavailable'}])
        self.assertEqual(catalogue(self.workflow),
            {'abcdefghijk':'qobuz:11','lmnopqrstuv':'qobuz:11','01234567890':'youtube:01234567890','zyxwvutsrqp':None})
        self.path.write_bytes(self.path.read_bytes().replace(b'11',b'12'))
        before = self.path.read_bytes()
        self.assertEqual(catalogue(self.workflow)['abcdefghijk'], 'qobuz:12')
        self.assertEqual(self.path.read_bytes(),before)

    def test_duplicate_or_unknown_selected_recordings_fail(self):
        for rows in ([{'videoId':'abcdefghijk','source':'youtube'}]*2,
                     [{'videoId':video,'source':'youtube'} for video in ('abcdefghijk','ABCdefghijk')],
                     [{'videoId':'abcdefghijk','source':'other'}],
                     [{'videoId':'abcdefghijk','source':'qobuz','trackId':True}]):
            self.write(rows)
            before = self.path.read_bytes()
            with self.assertRaises(ValueError):
                catalogue(self.workflow)
            self.assertEqual(self.path.read_bytes(),before)

    def test_unrelated_catalogue_metadata_does_not_change_selected_recording_authority(self):
        self.write([{'videoId':'abcdefghijk','source':'qobuz','trackId':11}])
        value = json.loads(self.path.read_bytes())
        value['flac'] = [{'trackId':99,'title':'Unrelated future input'}]
        value['playlist'][0]['title'] = 'Updated display metadata'
        self.path.write_text(json.dumps(value),encoding='utf-8')
        self.assertEqual(catalogue(self.workflow),{'abcdefghijk':'qobuz:11'})

    def test_folder_closure_rejects_missing_renamed_or_nested_archives(self):
        selected = {'abcdefghijk':'qobuz:11','lmnopqrstuv':'youtube:lmnopqrstuv','zyxwvutsrqp':None}
        self.workflow.ready.mkdir()
        first = self.workflow.ready/'abcdefghijk.stemwise.zip'
        second = self.workflow.ready/'lmnopqrstuv.stemwise.zip'
        first.write_bytes(b'byte validation is owned by scoped Status')
        self.assertFalse(layout(self.workflow,selected)['valid'])
        second.write_bytes(b'other current package')
        self.assertTrue(layout(self.workflow,selected)['valid'])
        extra = self.workflow.ready/'old.zip'
        extra.write_bytes(first.read_bytes())
        self.assertEqual(layout(self.workflow,selected)['unexpectedEntries'],1)
        extra.unlink()
        nested = self.workflow.ready/'old'
        nested.mkdir()
        (nested/first.name).write_bytes(first.read_bytes())
        self.assertFalse(layout(self.workflow,selected)['valid'])
