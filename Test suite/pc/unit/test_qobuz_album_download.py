"""Archive track numbers cannot silently bind another title or performer."""
import sys
import unittest
import json
import tempfile
import zipfile
from unittest.mock import patch
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
from stemwise_media import save_album as album
from qobuz_fixtures import flac_bytes
from test_qobuz_catalogue_page import MULTIDISC_TRACKS, multidisc_album_page


class AlbumBindingTests(unittest.TestCase):
    def test_identify_omitted_ordinals_counts_rows_within_each_disc(self):
        from stemwise_media.catalogue_page import parse_album_html
        tracks = parse_album_html(multidisc_album_page((1, 2, 1, 2)))
        for row in tracks:
            del row['trackNumber']
        for track_id, disc, title in MULTIDISC_TRACKS:
            with self.subTest(track_id=track_id):
                self.assertEqual(album.identify({'disc': str(disc), 'track': '1' if track_id in (101, 201) else '2',
                    'title': title, 'artist': 'Singer', 'album': 'Collection'}, tracks)['id'], track_id)

    def test_identify_rejects_noncolliding_explicit_anchors_that_cannot_authorize_missing_ordinals(self):
        from stemwise_media.catalogue_page import parse_album_html
        for missing_index, explicit_index in ((0, 1), (1, 0)):
            with self.subTest(missing_index=missing_index):
                tracks = parse_album_html(multidisc_album_page((1, 2, 1, 2)))
                del tracks[missing_index]['trackNumber']
                tracks[explicit_index]['trackNumber'] = 7
                with self.assertRaisesRegex(ValueError, 'Ambiguous'):
                    album.identify({'disc': '2', 'track': '1', 'title': 'Third',
                        'artist': 'Singer', 'album': 'Collection'}, tracks)

    def test_real_multidisc_page_saves_each_selected_disc_without_bypassing_other_members(self):
        import hashlib
        import io
        import subprocess
        import numpy as np
        import soundfile as sf
        album_url = 'https://www.qobuz.com/album/example'
        job_url = 'https://eu.doubledouble.top/dl/Example'
        job = {'status': 'done', 'service': 'qobuz', 'url': '/temp/complete/Example/Album.zip',
            'current': {'cover': '/cover?url=https%3A%2F%2Fwww.qobuz.com%2Falbum%2Fexample'}}
        bodies = {album_url: multidisc_album_page().encode(), job_url: json.dumps(job).encode()}

        class Response(io.BytesIO):
            status = 200
            def __init__(self, body):
                super().__init__(body)
                self.headers = {'Content-Length': str(len(body))}

        def response(request, timeout):
            self.assertIn(request.full_url, bodies, 'No external provider request is permitted')
            return Response(bodies[request.full_url])

        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            directory = Path(temporary)
            source = directory/'source.flac'
            sf.write(source, np.linspace(-.2, .2, 48_000), 48_000, subtype='PCM_16')
            members = {}
            for track_id, disc, title in MULTIDISC_TRACKS:
                number = 1 if track_id in (101, 201) else 2
                tagged = directory/f'{track_id}.flac'
                subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-c', 'copy',
                    '-metadata', f'disc={disc}/2', '-metadata', f'track={number}/2',
                    '-metadata', f'title={title}', '-metadata', 'artist=Singer', '-metadata', 'album=Collection',
                    str(tagged)], check=True, capture_output=True, timeout=30)
                members[track_id] = (f'album/{disc}-{number}.flac', tagged.read_bytes())
            for bad_unselected in (False, True):
                with self.subTest(bad_unselected=bad_unselected):
                    root = directory/str(bad_unselected)
                    retained = root/'audio/qobuz/album-archives/Example.zip'
                    retained.parent.mkdir(parents=True)
                    with zipfile.ZipFile(retained, 'w') as archive:
                        for track_id, (name, payload) in members.items():
                            archive.writestr(name, members[101][1] if bad_unselected and track_id == 201 else payload)
                    original_archive = retained.read_bytes()
                    with patch.object(album, 'ROOT', root), patch.object(album.urllib.request, 'urlopen', side_effect=response):
                        if bad_unselected:
                            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                                album.save_album(album_url, job_url, {101})
                            self.assertFalse(list(root.glob('audio/qobuz/*/download.json')))
                            self.assertFalse(list(root.glob('audio/qobuz/*/*.flac')))
                        else:
                            prior = None
                            for track_id in (101, 201):
                                album.save_album(album_url, job_url, {track_id})
                                base = root/'audio/qobuz'/str(track_id)
                                receipt = json.loads((base/'download.json').read_bytes())
                                name, payload = members[track_id]
                                self.assertEqual((base/receipt['file']).read_bytes(), payload)
                                self.assertEqual(receipt['qobuzTrackId'], track_id)
                                self.assertEqual(receipt['archiveMember'], name)
                                self.assertEqual(receipt['bytes'], len(payload))
                                self.assertEqual(receipt['sha256'], hashlib.sha256(payload).hexdigest())
                                self.assertEqual(receipt['archiveSha256'], hashlib.sha256(original_archive).hexdigest())
                                self.assertFalse((base/'download-transfer.json').exists())
                                if prior is None:
                                    self.assertFalse((root/'audio/qobuz/201/download.json').exists())
                                    prior = (base/receipt['file'], (base/'download.json').read_bytes())
                                else:
                                    self.assertEqual(prior[0].read_bytes(), members[101][1])
                                    self.assertEqual((prior[0].parent/'download.json').read_bytes(), prior[1])
                            for unselected in (102, 202):
                                self.assertFalse((root/'audio/qobuz'/str(unselected)/'download.json').exists())
                    self.assertEqual(retained.read_bytes(), original_archive)

    def test_missing_receipted_album_media_restores_only_exact_archive_bytes(self):
        import io
        import soundfile as sf
        import numpy as np
        from types import SimpleNamespace
        buffer = io.BytesIO()
        sf.write(buffer,np.linspace(-.2,.2,480),48000,format='FLAC',subtype='PCM_16')
        payload = buffer.getvalue()
        for mismatch in (False,True):
            with self.subTest(mismatch=mismatch),tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                root = Path(temporary)
                archive_path = root/'audio/qobuz/album-archives/Example.zip'
                archive_path.parent.mkdir(parents=True)
                with zipfile.ZipFile(archive_path,'w') as archive:
                    archive.writestr('album/01.flac',payload)
                base = root/'audio/qobuz/10'
                base.mkdir()
                import hashlib
                receipt = {'qobuzTrackId':10,'file':'retained.flac','title':'Mawlaya',
                    'bytes':len(payload),'sha256':hashlib.sha256(payload).hexdigest() if not mismatch else '0'*64,
                    'submittedAlbumUrl':'https://www.qobuz.com/album/example',
                    'archiveMember':'album/01.flac','archiveSha256':'a'*64}
                (base/'download.json').write_text(json.dumps(receipt),encoding='utf-8')
                before = (base/'download.json').read_bytes()
                job = {'status':'done','service':'qobuz','url':'/temp/complete/Example/Album.zip',
                    'current':{'cover':'/cover?url=https%3A%2F%2Fwww.qobuz.com%2Falbum%2Fexample'}}
                with patch.object(album,'ROOT',root),patch.object(album,'read_album',return_value=self.tracks), \
                        patch.object(album,'http_request',return_value=json.dumps(job).encode()), \
                        patch.object(album,'run_process_tree',return_value=SimpleNamespace(stdout=json.dumps({
                            'streams':[{'codec_name':'flac'}],'format':{'tags':self.tags}}))):
                    if mismatch:
                        with self.assertRaises(ValueError):
                            album.save_album(receipt['submittedAlbumUrl'],'https://eu.doubledouble.top/dl/Example',{10})
                        self.assertFalse((base/'retained.flac').exists())
                    else:
                        album.save_album(receipt['submittedAlbumUrl'],'https://eu.doubledouble.top/dl/Example',{10})
                        self.assertEqual((base/'retained.flac').read_bytes(),payload)
                self.assertEqual((base/'download.json').read_bytes(),before)

    def test_duplicate_album_mapping_rejects_before_any_selected_publication(self):
        from types import SimpleNamespace
        for reverse in (False, True):
            with self.subTest(reverse=reverse), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                root = Path(temporary)
                retained = root/'audio/qobuz/album-archives/Example.zip'
                retained.parent.mkdir(parents=True)
                with zipfile.ZipFile(retained, 'w') as archive:
                    for name in (('second.flac','first.flac') if reverse else ('first.flac','second.flac')):
                        archive.writestr(name, b'fLaC'+name.encode())
                tracks = [self.tracks[0], {**self.tracks[0], 'id':11, 'title':'Second'}]
                job = {'status':'done','service':'qobuz','url':'/temp/complete/Example/Album.zip',
                    'current':{'cover':'/cover?url=https%3A%2F%2Fwww.qobuz.com%2Falbum%2Fexample'}}
                with patch.object(album,'ROOT',root), patch.object(album,'read_album',return_value=tracks), \
                        patch.object(album,'http_request',return_value=json.dumps(job).encode()), \
                        patch.object(album,'run_process_tree',return_value=SimpleNamespace(stdout=json.dumps({
                            'streams':[{'codec_name':'flac'}], 'format':{'tags':self.tags}}))):
                    for _ in range(2):
                        with self.assertRaisesRegex(ValueError, 'Duplicate'):
                            album.save_album('https://www.qobuz.com/album/example',
                                'https://eu.doubledouble.top/dl/Example', {10})
                        self.assertFalse((root/'audio/qobuz/10/download.json').exists())
                        self.assertFalse(list((root/'audio/qobuz/10').glob('*.flac')))

    def test_filter_preserves_hash_budget_and_retry_for_compressible_members(self):
        import time
        from stemwise_media.transfer_budget import TransferBudget, hash_bounded
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            path = Path(temporary)/'album.zip'
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('good.flac', b'fLaC'+bytes(8192))
                archive.writestr('rejected.flac', b'fLaCrejected')
            budget = TransferBudget(time.monotonic()+10, max_bytes=1024)
            hash_bounded(path, budget)
            album.remove_archive_members(path, {'rejected.flac'}, budget=budget)
            hash_bounded(path, budget)
            album.remove_archive_members(path, {'rejected.flac'}, budget=budget)
            hash_bounded(path, budget)
            with zipfile.ZipFile(path) as archive:
                self.assertEqual(archive.read('good.flac'), b'fLaC'+bytes(8192))

    def test_album_complete_journal_replays_before_catalogue_and_job_requests(self):
        import hashlib
        from stemwise_engine.common import atomic_write_json
        album_url, job_url = 'https://www.qobuz.com/album/example', 'https://eu.doubledouble.top/dl/Example'
        for conflict in (None, 'album', 'job', 'audio'):
            with self.subTest(conflict=conflict), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                root = Path(temporary)
                base = root/'audio/qobuz/10'
                base.mkdir(parents=True)
                payload = flac_bytes()
                digest = hashlib.sha256(payload).hexdigest()
                metadata = {'qobuzTrackId':10, 'title':'Mawlaya', 'file':'Mawlaya.flac',
                    'bytes':len(payload), 'sha256':digest, 'submittedAlbumUrl':album_url,
                    'archiveMember':'album/01.flac', 'archiveSha256':'a'*64}
                if conflict == 'album': metadata['submittedAlbumUrl'] += '/other'
                transfer = {'formatVersion':1, 'qobuzTrackId':10,
                    'jobUrl':job_url+('Other' if conflict == 'job' else ''), 'sha256':digest, 'metadata':metadata}
                atomic_write_json(base/'download-transfer.json', transfer)
                audio = base/'Mawlaya.flac.Example.partial'
                audio.write_bytes(payload+(b'changed' if conflict == 'audio' else b''))
                with patch.object(album, 'ROOT', root), patch.object(album, 'read_album', side_effect=AssertionError('No catalogue replay')), \
                        patch.object(album, 'http_request', side_effect=AssertionError('No provider replay')):
                    if conflict:
                        with self.assertRaises(ValueError):
                            album.save_album(album_url, job_url, {10})
                        self.assertTrue((base/'download-transfer.json').exists())
                        self.assertTrue(audio.exists())
                    else:
                        album.save_album(album_url, job_url, {10})
                        self.assertEqual((base/'Mawlaya.flac').read_bytes(), payload)
                        self.assertEqual(json.loads((base/'download.json').read_bytes()), metadata)
                        self.assertFalse((base/'download-transfer.json').exists())

    def test_noop_archive_filter_needs_no_free_allocation(self):
        from types import SimpleNamespace
        from stemwise_media import transfer_budget
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            path = Path(temporary)/'album.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('original.flac', b'fLaCretained')
            before = path.read_bytes()
            with patch.object(transfer_budget.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)):
                album.remove_archive_members(path, {'absent.flac'})
            self.assertEqual(path.read_bytes(), before)

    def test_filter_admission_and_cleanup_failure_preserve_the_retained_archive(self):
        from types import SimpleNamespace
        from stemwise_media import transfer_budget
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            root = Path(temporary)
            path = root/'album.zip'
            with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('good.flac', b'fLaCgood')
                archive.writestr('rejected.flac', b'fLaCrejected')
            before = path.read_bytes()
            with patch.object(transfer_budget.shutil, 'disk_usage', return_value=SimpleNamespace(free=0)):
                with self.assertRaisesRegex(ValueError, 'free space'):
                    album.remove_archive_members(path, {'rejected.flac'})
            self.assertEqual(path.read_bytes(), before)
            primary = RuntimeError('original archive copy failure')
            real_unlink = Path.unlink
            def unlink(candidate, *args, **kwargs):
                if candidate.parent == root and candidate != path and candidate.suffix == '.zip':
                    raise PermissionError('filtered scratch remains busy')
                return real_unlink(candidate, *args, **kwargs)
            with patch.object(album, 'copy_bounded', side_effect=primary), patch.object(Path, 'unlink', unlink):
                with self.assertRaises(RuntimeError) as caught:
                    album.remove_archive_members(path, {'rejected.flac'})
            self.assertIs(caught.exception, primary)
            self.assertEqual(path.read_bytes(), before)

    def test_album_space_admission_uses_actual_copies_and_verified_partial_remainder(self):
        from types import SimpleNamespace
        from stemwise_media import transfer_budget
        unit, reserve = 1024, 64*1024**2
        cases = ((300, {10}, False, False), (480, {10, 11, 12, 13}, False, True),
                 (220, {10}, True, False))
        for capacity, selected, resume, exhausted in cases:
            with self.subTest(capacity=capacity, selected=selected, resume=resume), \
                    tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                root = Path(temporary)
                payloads = [flac_bytes(index).ljust(100*unit, b'\0') for index in range(4)]
                retained = root/'audio/qobuz/album-archives/Example.zip'
                retained.parent.mkdir(parents=True)
                with zipfile.ZipFile(retained, 'w') as archive:
                    for index, payload in enumerate(payloads):
                        archive.writestr(f'album/{index}.flac', payload)
                tracks = [self.tracks[0] | {'id': 10+index, 'title': f'Track{index}',
                    'displayTitle': f'Track{index}'} for index in range(4)]
                if resume:
                    partial = root/'audio/qobuz/10/Track0.flac.Example.partial'
                    partial.parent.mkdir()
                    partial.write_bytes(payloads[0][:50*unit])
                def usage(directory):
                    occupied = sum(path.stat().st_size for path in root.rglob('*')
                        if path.is_file() and (path.suffix == '.flac' or path.suffix == '.partial'))
                    return SimpleNamespace(free=reserve+capacity*unit-occupied)
                def probe(command, **kwargs):
                    index = payloads.index(Path(command[-1]).read_bytes())
                    return SimpleNamespace(stdout=json.dumps({'streams': [{'codec_name': 'flac'}],
                        'format': {'tags': {'track': str(index+1), 'title': f'Track{index}',
                            'artist': 'Maher Zain', 'album': 'Concert'}}}))
                job = {'status': 'done', 'service': 'qobuz', 'url': '/temp/complete/Example/Album.zip',
                    'current': {'cover': '/cover?url=https%3A%2F%2Fwww.qobuz.com%2Falbum%2Fexample'}}
                with patch.object(album, 'ROOT', root), patch.object(album, 'read_album', return_value=tracks), \
                        patch.object(album, 'http_request', return_value=json.dumps(job).encode()), \
                        patch.object(album, 'download_file', side_effect=AssertionError('Use retained archive')), \
                        patch.object(album, 'run_process_tree', side_effect=probe), \
                        patch.object(transfer_budget.shutil, 'disk_usage', side_effect=usage):
                    if exhausted:
                        with self.assertRaisesRegex(ValueError, 'free space'):
                            album.save_album('https://www.qobuz.com/album/example',
                                'https://eu.doubledouble.top/dl/Example', selected)
                    else:
                        album.save_album('https://www.qobuz.com/album/example',
                            'https://eu.doubledouble.top/dl/Example', selected)
                    self.assertGreaterEqual(usage(root).free, reserve)
                saved = [base for base in (root/'audio/qobuz').iterdir() if (base/'download.json').is_file()]
                self.assertEqual(len(saved), 3 if exhausted else 1)
                for base in saved:
                    metadata = json.loads((base/'download.json').read_bytes())
                    self.assertEqual((base/metadata['file']).read_bytes(), payloads[int(base.name)-10])

    def test_two_processes_share_the_archive_lease_even_for_different_tracks(self):
        from concurrent.futures import ThreadPoolExecutor
        from stemwise_engine.common import run_process_tree
        script = "\nimport json, sys, time\nfrom pathlib import Path\nsys.path.insert(0, sys.argv[1])\nimport stemwise_media.save_album as save_album\nroot = Path(sys.argv[2]); save_album.ROOT = root\ndef transaction(*args):\n    marker = root/'archive-being-mutated'\n    with marker.open('x') as handle:\n        handle.write(sys.argv[3])\n    try:\n        time.sleep(.4)\n        with (root/'completed.txt').open('a') as handle:\n            handle.write(sys.argv[3]+'\\n')\n    finally:\n        marker.unlink()\nsave_album._save_album = transaction\nsave_album.save_album('https://www.qobuz.com/album/example',\n    'https://eu.doubledouble.top/dl/Example', {int(sys.argv[3])})\n"
        with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
            def launch(track):
                return run_process_tree([sys.executable, '-c', script,
                    str(ROOT/'PC side/qobuz-comparison'), temporary, str(track)],
                    capture_output=True, text=True, check=True, timeout=20)
            with ThreadPoolExecutor(max_workers=2) as executor:
                list(executor.map(launch, (10, 11)))
            root = Path(temporary)
            self.assertEqual(set((root/'completed.txt').read_text().splitlines()), {'10', '11'})
            self.assertFalse((root/'archive-being-mutated').exists())

    def test_album_metadata_failure_recovers_only_exact_archive_member_audio(self):
        import io
        import numpy as np
        import soundfile as sf
        from stemwise_engine.common import sha256_file
        for failure_name in ('download-transfer.json', 'download.json'):
            with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
                root = Path(temporary)
                source = root / 'source.flac'
                sf.write(source, np.zeros((4800, 2)), 48000, subtype='PCM_16')
                tagged = root / 'tagged.flac'
                import subprocess
                subprocess.run(['ffmpeg','-v','error','-i',str(source),'-c','copy',
                    '-metadata','track=1','-metadata','title=Mawlaya','-metadata','artist=Maher Zain',
                    '-metadata','album=Concert',str(tagged)], check=True, capture_output=True)
                retained = root / 'audio/qobuz/album-archives/Example.zip'
                retained.parent.mkdir(parents=True)
                with zipfile.ZipFile(retained, 'w') as archive:
                    archive.write(tagged, 'album/01.flac')
                job = {'status':'done','service':'qobuz','url':'/temp/complete/Example/Album.zip',
                    'current':{'cover':'/cover?url=https%3A%2F%2Fwww.qobuz.com%2Falbum%2Fexample'}}
                real_write = album.atomic_write_json
                def fail_metadata(path, value):
                    if path.name == failure_name:
                        raise OSError('metadata unavailable')
                    return real_write(path, value)
                with patch.object(album,'ROOT',root), patch.object(album,'read_album',return_value=self.tracks), \
                        patch.object(album.urllib.request,'urlopen',side_effect=lambda *a,**k: io.BytesIO(json.dumps(job).encode())), \
                        patch.object(album,'download_file',side_effect=AssertionError('Use retained archive')):
                    with patch.object(album, 'atomic_write_json', side_effect=fail_metadata), \
                            patch('stemwise_media.save_download.atomic_write_json', side_effect=fail_metadata):
                        with self.assertRaisesRegex(OSError, 'metadata unavailable'):
                            album.save_album('https://www.qobuz.com/album/example',
                                'https://eu.doubledouble.top/dl/Example', {10})
                    if failure_name == 'download.json':
                        with patch.object(album, 'read_album', side_effect=AssertionError('No catalogue recovery request')), \
                                patch.object(album, 'http_request', side_effect=AssertionError('No provider recovery request')):
                            album.save_album('https://www.qobuz.com/album/example',
                                'https://eu.doubledouble.top/dl/Example', {10})
                    else:
                        album.save_album('https://www.qobuz.com/album/example',
                            'https://eu.doubledouble.top/dl/Example', {10})
                    base = root / 'audio/qobuz/10'
                    metadata = json.loads((base / 'download.json').read_bytes())
                    self.assertEqual((base / metadata['file']).read_bytes(), tagged.read_bytes())
                    self.assertEqual(metadata['archiveMember'], 'album/01.flac')
                    self.assertEqual(metadata['archiveSha256'], sha256_file(retained))
                    self.assertFalse((base / 'download-transfer.json').exists())
                    original = (base / metadata['file']).read_bytes()
                    (base / 'download.json').unlink()
                    # Recover the pre-fix crash state using the freshly validated exact member.
                    album.save_album('https://www.qobuz.com/album/example',
                        'https://eu.doubledouble.top/dl/Example', {10})
                    self.assertEqual((base / metadata['file']).read_bytes(), original)
                    (base / 'download.json').unlink()
                    changed = original + b'changed'
                    (base / metadata['file']).write_bytes(changed)
                    with self.assertRaises(FileExistsError):
                        album.save_album('https://www.qobuz.com/album/example',
                            'https://eu.doubledouble.top/dl/Example', {10})
                    self.assertEqual((base / metadata['file']).read_bytes(), changed)

    def test_owner_rejected_selection_stops_before_requesting_an_album(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            base = Path(temporary) / 'audio/qobuz/10'
            base.mkdir(parents=True)
            (base / 'download.json').write_text(json.dumps({'qobuzTrackId': 10, 'status': 'owner_rejected'}))
            with patch.object(album, 'ROOT', Path(temporary)), patch.object(album, 'read_album',
                    side_effect=AssertionError('Must not request rejected track')):
                with self.assertRaisesRegex(ValueError, 'owner rejected'):
                    album.save_album('https://www.qobuz.com/album/example',
                                     'https://eu.doubledouble.top/dl/Example', {10})

    def test_archive_removes_only_rejected_audio_and_keeps_other_members(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'Test suite/work') as temporary:
            path = Path(temporary) / 'album.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('album/01.good.flac', b'fLaCgood')
                archive.writestr('album/02.wrong.flac', b'fLaCwrong')
                archive.writestr('cover.jpg', b'cover')
            album.remove_archive_members(path, {'album/02.wrong.flac'})
            album.remove_archive_members(path, {'album/02.wrong.flac'})
            with zipfile.ZipFile(path) as archive:
                self.assertEqual(set(archive.namelist()), {'album/01.good.flac', 'cover.jpg'})
                self.assertEqual(archive.read('album/01.good.flac'), b'fLaCgood')
                self.assertEqual(archive.read('cover.jpg'), b'cover')
            self.assertFalse(path.with_suffix('.zip.partial').exists())

    def setUp(self):
        from qobuz_fixtures import inline_http_worker
        inline_http_worker(self)
        self.tracks = [{"id": 10, "title": "Mawlaya", "displayTitle": "Mawlaya (Live)",
                        "artist": "Maher Zain", "album": "Concert"}]
        self.tags = {"track": "1/25", "title": "Mawlaya (Live)", "artist": "Maher Zain", "album": "Concert"}

    def test_full_album_number_and_tags_bind_the_actual_id(self):
        self.assertEqual(album.identify(self.tags, self.tracks)["id"], 10)

    def test_correct_number_with_wrong_title_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "title"):
            album.identify(self.tags | {"title": "Another Song"}, self.tracks)

    def test_exact_punctuation_bearing_performer_names_are_not_split(self):
        for performer in ('North, South', 'North; South'):
            with self.subTest(performer=performer):
                tracks = [self.tracks[0] | {'artist': performer}]
                self.assertEqual(album.identify(self.tags | {'artist': performer}, tracks)['id'], 10)
                with self.assertRaisesRegex(ValueError, 'performer'):
                    album.identify(self.tags | {'artist': 'North, East'}, tracks)

    def test_featured_artist_does_not_change_the_lead_performer_binding(self):
        self.assertEqual(album.identify(self.tags | {"artist": "Maher Zain, Atif Aslam"}, self.tracks)["id"], 10)

    def test_wrong_performer_or_album_is_rejected(self):
        for field in ("artist", "album"):
            with self.subTest(field=field), self.assertRaises(ValueError):
                album.identify(self.tags | {field: "Other"}, self.tracks)


if __name__ == "__main__":
    unittest.main()
