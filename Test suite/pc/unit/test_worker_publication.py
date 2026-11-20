"""Interrupted owner-file publication replays exact bytes and preserves conflicting edits."""
import sys
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.worker_publication as publication
from stemwise_engine.common import read_bounded_regular_bytes


class WorkerPublicationTests(unittest.TestCase):
    def test_reader_rejects_recovery_marker_naming_owner_notes(self):
        from stemwise_engine.common import read_bounded_json
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            directory = Path(name)
            owner, notes = directory/'session.json', directory/'owner-lyrics.txt'
            owner.write_bytes(b'{"current":true}')
            notes.write_bytes(b'approved words')
            marker = directory/'.session.json.publication-conflict.json'
            marker.write_text(json.dumps({'formatVersion':1,'phase':'exchanged','ownerFile':owner.name,
                'stagedFile':notes.name,'displacedFile':notes.name,
                'expectedSha256':hashlib.sha256(notes.read_bytes()).hexdigest(),
                'candidateSha256':hashlib.sha256(owner.read_bytes()).hexdigest()}),encoding='utf-8')
            before = {path:path.read_bytes() for path in (owner,notes,marker)}
            with self.assertRaises(ValueError):
                read_bounded_json(owner,max_bytes=1024,root=directory)
            self.assertEqual({path:path.read_bytes() for path in before},before)

    def test_process_lock_rejects_symlink_and_hardlink_without_touching_target(self):
        from stemwise_engine.common import exclusive_process_lock
        for kind in ('symlink','hardlink'):
            with self.subTest(kind=kind),tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                root = Path(name)
                target, lock = root/'owner.txt',root/'download.lock'
                target.write_bytes(b'owner words')
                (lock.symlink_to(target) if kind == 'symlink' else os.link(target,lock))
                with self.assertRaises((OSError,ValueError)):
                    with exclusive_process_lock(lock):
                        self.fail('linked lock admitted')
                self.assertEqual(target.read_bytes(),b'owner words')
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            with exclusive_process_lock(Path(name)/'download.lock'):
                self.assertTrue((Path(name)/'download.lock').read_text().strip().isdigit())

    def test_interruption_before_or_after_exchange_and_cleanup_recovers(self):
        class PowerLoss(BaseException):
            pass
        for boundary in ('before-exchange', 'after-exchange', 'displaced-removal', 'marker-removal'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                directory = Path(name)
                owner, staged = directory/'owner.json', directory/('.owner.json.'+'a'*32+'.pending')
                owner.write_bytes(b'old owner')
                staged.write_bytes(b'candidate')
                marker = directory/'.owner.json.publication-conflict.json'
                exchange, unlink = publication.replace_preserving_previous, Path.unlink
                def interrupted_exchange(*args):
                    if boundary == 'before-exchange':
                        raise OSError('exchange refused')
                    result = exchange(*args)
                    if boundary == 'after-exchange':
                        raise PowerLoss()
                    return result
                def interrupted_unlink(path, *args, **kwargs):
                    if ((boundary == 'marker-removal' and path == marker)
                            or (boundary == 'displaced-removal' and path.name in {staged.name, staged.with_suffix('.conflict').name})):
                        raise PowerLoss()
                    return unlink(path, *args, **kwargs)
                with patch.object(publication, 'replace_preserving_previous', side_effect=interrupted_exchange), \
                        patch.object(Path, 'unlink', interrupted_unlink), self.assertRaises((PowerLoss, OSError)):
                    publication.publish_owner_file(owner, staged, b'old owner', max_bytes=1024)
                self.assertTrue(marker.exists())
                recovered = read_bounded_regular_bytes(owner, max_bytes=1024, root=directory)
                if recovered == b'old owner':
                    self.assertEqual(boundary, 'before-exchange')
                    publication.publish_owner_file(owner, staged, recovered, max_bytes=1024)
                self.assertEqual(read_bounded_regular_bytes(owner, max_bytes=1024, root=directory), b'candidate')
                self.assertFalse(marker.exists())
                self.assertEqual({path.name for path in directory.iterdir() if path.suffix != '.lock'}, {'owner.json'})

    def test_conflicting_displaced_owner_edit_stays_private_and_blocks_reads(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
            directory = Path(name)
            owner, staged = directory/'owner.json', directory/('.owner.json.'+'a'*32+'.pending')
            owner.write_bytes(b'old owner')
            staged.write_bytes(b'candidate')
            exchange = publication.replace_preserving_previous
            def concurrent_edit(*args):
                owner.write_bytes(b'concurrent owner edit')
                return exchange(*args)
            with patch.object(publication, 'replace_preserving_previous', side_effect=concurrent_edit), \
                    self.assertRaisesRegex(ValueError, 'requires recovery'):
                publication.publish_owner_file(owner, staged, b'old owner', max_bytes=1024)
            before = {path: path.read_bytes() for path in directory.iterdir() if path.is_file()}
            with self.assertRaisesRegex(ValueError, 'requires recovery'):
                read_bounded_regular_bytes(owner, max_bytes=1024, root=directory)
            self.assertEqual({path: path.read_bytes() for path in before}, before)
            self.assertIn(b'concurrent owner edit', before.values())


if __name__ == '__main__':
    unittest.main()
