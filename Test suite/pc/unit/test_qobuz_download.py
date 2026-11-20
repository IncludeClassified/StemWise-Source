"""Completed-job transfers preserve audio and cannot mislabel another track."""
import io
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

PROJECT = Path(__file__).resolve().parents[3]
from stemwise_media import save_download as MODULE


class Response(io.BytesIO):
    def __init__(self, data, status=200):
        super().__init__(data)
        self.status = status
        self.headers = {"Content-Length": str(len(data))}


class CompletedQobuzDownloadTest(unittest.TestCase):
    def test_committed_http_prefix_survives_process_death_and_discards_only_uncommitted_tail(self):
        import subprocess
        import sys
        from stemwise_engine.common import run_process_tree
        from stemwise_media.transfer_budget import http_worker
        partial = self.root/'crashed.partial'
        request = {'url': 'https://example.invalid/audio', 'headers': {}, 'partial': str(partial),
            'maxBytes': 1024, 'seconds': 10}
        script = '''import io,json,os,sys,urllib.request
from pathlib import Path
from stemwise_engine import common
from stemwise_media.transfer_budget import http_worker
write=common.atomic_write_json
def checkpoint(path,value,**kwargs):
 write(path,value,**kwargs)
 if value.get('bytes')==4: os._exit(37)
common.atomic_write_json=checkpoint
class Response(io.BytesIO):
 status=200
 headers={'Content-Length':'8','ETag':'"exact"'}
 def read1(self,*args):
  if self.tell(): raise ConnectionResetError('interrupted body')
  return super().read(4)
urllib.request.urlopen=lambda *a,**kw: Response(b'AAAABBBB')
http_worker(json.loads(sys.argv[1]))
'''
        result = run_process_tree([sys.executable, '-B', '-c', script, json.dumps(request)],
            timeout=15, capture_output=True, text=True)
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(partial.read_bytes(), b'AAAA')
        checkpoint = json.loads(partial.with_name(partial.name+'.http.json').read_bytes())
        self.assertEqual(checkpoint['bytes'], 4)
        partial.write_bytes(b'AAAAjunk')
        def resume(request, **kwargs):
            self.assertEqual(request.get_header('Range'), 'bytes=4-')
            self.assertEqual(request.get_header('If-range'), '"exact"')
            result = Response(b'BBBB', 206)
            result.headers.update({'Content-Range': 'bytes 4-7/8', 'ETag': '"exact"'})
            return result
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=resume):
            http_worker(request)
        self.assertEqual(partial.read_bytes(), b'AAAABBBB')
        partial.write_bytes(b'XXXXjunk')
        def reject_unbound(request, **kwargs):
            self.assertIsNone(request.get_header('Range'))
            raise OSError('replacement unavailable')
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=reject_unbound):
            with self.assertRaises(OSError):
                http_worker(request)
        self.assertEqual(partial.read_bytes(), b'XXXXjunk')

    def test_http_connection_reset_resumes_the_committed_prefix_on_next_attempt(self):
        from stemwise_media.transfer_budget import http_worker
        class Reset(Response):
            def read1(self, *args):
                if self.tell():
                    raise ConnectionResetError('response interrupted')
                return super().read(4)
        calls = []
        def response(request, **kwargs):
            calls.append(request.get_header('Range'))
            if len(calls) == 1:
                result = Reset(b'AAAABBBB')
            else:
                self.assertEqual(request.get_header('If-range'), '"exact"')
                result = Response(b'BBBB', 206)
                result.headers['Content-Range'] = 'bytes 4-7/8'
            result.headers['ETag'] = '"exact"'
            return result
        partial = self.root/'reset.partial'
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=response):
            http_worker({'url': 'https://example.invalid/audio', 'headers': {}, 'partial': str(partial),
                'maxBytes': 1024, 'seconds': 10})
        self.assertEqual(calls, [None, 'bytes=4-'])
        self.assertEqual(partial.read_bytes(), b'AAAABBBB')

    def test_tiny_http_reads_batch_durable_prefix_checkpoints(self):
        from stemwise_media.transfer_budget import http_worker
        from stemwise_engine import common
        class Tiny(Response):
            def read1(self, *_):
                return super().read(1)
        response = Tiny(b'x'*512)
        response.headers['ETag'] = '"exact"'
        partial = self.root/'tiny.partial'
        with patch.object(MODULE.urllib.request, 'urlopen', return_value=response), \
                patch.object(common, 'atomic_write_json', wraps=common.atomic_write_json) as checkpoints:
            http_worker({'url': 'https://example.invalid/audio', 'headers': {}, 'partial': str(partial),
                'maxBytes': 1024, 'seconds': 10})
        self.assertEqual(partial.read_bytes(), b'x'*512)
        self.assertLessEqual(checkpoints.call_count, 4)
        self.assertEqual(json.loads(partial.with_name(partial.name+'.http.json').read_bytes())['bytes'], 512)

    def test_unbound_retained_prefix_is_replaced_by_a_full_206_object(self):
        partial = self.root/'owned.partial'
        partial.write_bytes(b'old unverified prefix and stale trailing bytes')
        requests = []
        def response(request, timeout):
            requests.append(dict(request.header_items()))
            result = Response(b'new object', 206)
            result.headers.update({'Content-Range':'bytes 0-9/10', 'ETag':'"current"'})
            return result
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=response):
            MODULE.download_file('https://example.invalid/audio', partial)
        self.assertEqual(partial.read_bytes(), b'new object')
        self.assertEqual(len(requests), 1)
        self.assertNotIn('Range', requests[0])
        self.assertFalse(list(self.root.glob('.*.replacement')))

    def test_restart_does_not_follow_a_preexisting_replacement_link(self):
        partial = self.root/'owned.partial'
        partial.write_bytes(b'retained prefix')
        owner = self.root/'owner.txt'
        owner.write_bytes(b'owner words')
        link = partial.with_name(partial.name+'.replacement')
        link.symlink_to(owner)
        with patch.object(MODULE.urllib.request,'urlopen',side_effect=lambda *a,**kw:Response(self.audio)):
            MODULE.download_file('https://example.invalid/audio',partial)
        self.assertEqual(owner.read_bytes(),b'owner words')
        self.assertTrue(link.is_symlink())
        self.assertEqual(partial.read_bytes(),self.audio)

    def test_resumption_never_joins_an_unbound_old_prefix(self):
        partial = self.root/'owned.partial'
        partial.write_bytes(b'AAAA')
        requested = []
        def response(request,timeout):
            requested.append(dict(request.header_items()))
            result = Response(b'BBBB' if request.get_header('Range') else b'CCCCBBBB',
                              206 if request.get_header('Range') else 200)
            if result.status == 206: result.headers['Content-Range'] = 'bytes 4-7/8'
            return result
        with patch.object(MODULE.urllib.request,'urlopen',side_effect=response):
            MODULE.download_file('https://example.invalid/audio',partial)
        self.assertEqual(partial.read_bytes(),b'CCCCBBBB')
        self.assertNotIn('Range',requested[0])

    def test_strong_etag_resumes_exact_prefix_and_changed_version_restarts(self):
        for changed in (False,True):
            with self.subTest(changed=changed):
                partial = self.root/f'bound-{changed}.partial'
                requests = []
                def response(request,timeout):
                    requests.append(dict(request.header_items()))
                    if len(requests) == 1:
                        result = Response(b'AAAA')
                        result.headers.update({'Content-Length':'8','ETag':'"version-one"'})
                    else:
                        self.assertEqual(request.get_header('Range'),'bytes=4-')
                        self.assertEqual(request.get_header('If-range'),'"version-one"')
                        result = Response(b'CCCCBBBB' if changed else b'BBBB',200 if changed else 206)
                        result.headers['ETag'] = '"version-two"' if changed else '"version-one"'
                        if not changed: result.headers['Content-Range'] = 'bytes 4-7/8'
                    return result
                with patch.object(MODULE.urllib.request,'urlopen',side_effect=response):
                    MODULE.download_file('https://example.invalid/audio',partial)
                self.assertEqual(partial.read_bytes(),b'CCCCBBBB' if changed else b'AAAABBBB')
                self.assertEqual(len(requests),2)

    def test_416_cannot_complete_an_unbound_same_length_prefix(self):
        from urllib.error import HTTPError
        partial = self.root/'unbound.partial'
        partial.write_bytes(b'oldbytes')
        with patch.object(MODULE.urllib.request,'urlopen',side_effect=lambda *a,**kw:(_ for _ in ()).throw(
                HTTPError('https://example.invalid',416,'EOF',{'Content-Range':'bytes */8','ETag':'"new"'},None))):
            with self.assertRaises(HTTPError):
                MODULE.download_file('https://example.invalid',partial)
        self.assertEqual(partial.read_bytes(),b'oldbytes')

    def test_flac_signature_without_samples_is_never_published(self):
        responses = self.responses(11)
        responses[1] = Response(b'fLaC')
        with patch.object(MODULE.urllib.request,'urlopen',side_effect=responses),self.assertRaises(ValueError):
            MODULE.save_download(11,'https://eu.doubledouble.top/dl/Invalid')
        base = self.root/'audio/qobuz/11'
        self.assertFalse((base/'download.json').exists())
        self.assertFalse((base/'Song.flac').exists())
        self.assertEqual((base/'Song.flac.Invalid.partial').read_bytes(),b'fLaC')
        with patch.object(MODULE.urllib.request,'urlopen',side_effect=self.responses(11)):
            self.assertEqual(MODULE.save_download(11,'https://eu.doubledouble.top/dl/Invalid')['status'],'saved')
        self.assertEqual((base/'Song.flac').read_bytes(),self.audio)

    def test_missing_approved_audio_reacquires_only_the_receipted_digest(self):
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)):
            MODULE.save_download(11, 'https://eu.doubledouble.top/dl/First')
        base = self.root/'audio/qobuz/11'
        receipt = (base/'download.json').read_bytes()
        (base/'Song.flac').unlink()
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)):
            self.assertEqual(MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Replacement')['status'], 'saved')
        self.assertEqual((base/'download.json').read_bytes(), receipt)
        self.assertEqual((base/'Song.flac').read_bytes(), self.audio)
        (base/'Song.flac').unlink()
        responses = self.responses(11)
        responses[1] = Response(self.audio+b'other recording')
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=responses):
            with self.assertRaisesRegex(ValueError, 'approved.*digest'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Changed')
        self.assertEqual((base/'download.json').read_bytes(), receipt)
        self.assertFalse((base/'Song.flac').exists())

    def test_resume_with_full_200_replaces_only_after_complete_read(self):
        partial = self.root/'owned.partial'
        partial.write_bytes(b'fLaCpartial')
        full = Response(self.audio)
        full.status = 200
        with patch.object(MODULE.urllib.request, 'urlopen', return_value=full):
            MODULE.download_file('https://example.invalid/audio', partial)
        self.assertEqual(partial.read_bytes(), self.audio)

    def test_multichunk_restart_rejects_short_writes_and_preserves_exact_resume_authority(self):
        payload = b'fLaC' + bytes(range(256))*1024
        offset = 12_345
        url = 'https://example.invalid/audio'
        fdopen = os.fdopen
        for fault in ('zero', 'short', 'none'):
            for recovery_status in (200, 206):
                with self.subTest(fault=fault, recovery_status=recovery_status):
                    partial = self.root/f'write-{fault}-{recovery_status}.partial'
                    partial.write_bytes(payload[:offset])
                    sidecar = partial.with_name(partial.name+'.http.json')
                    sidecar.write_text(json.dumps({'url': url, 'etag': '"exact"', 'length': len(payload),
                        'bytes': offset, 'sha256': hashlib.sha256(payload[:offset]).hexdigest()}), encoding='utf-8')
                    original_sidecar = sidecar.read_bytes()
                    requests, outputs = [], []

                    def response(request, timeout):
                        requests.append((request.get_header('Range'), request.get_header('If-range')))
                        result = Response(payload)
                        result.headers['ETag'] = '"exact"'
                        return result

                    class ShortOutput:
                        def __init__(self, stream):
                            self.stream, self.writes = stream, 0
                        def __enter__(self):
                            return self
                        def __exit__(self, *error):
                            return self.stream.__exit__(*error)
                        def __getattr__(self, name):
                            return getattr(self.stream, name)
                        def write(self, chunk):
                            self.writes += 1
                            if self.writes == 2:
                                if fault == 'short':
                                    return self.stream.write(chunk[:len(chunk)//2])
                                return None if fault == 'none' else 0
                            return self.stream.write(chunk)

                    def output(descriptor, *args, **kwargs):
                        stream = fdopen(descriptor, *args, **kwargs)
                        if kwargs.get('buffering') == 0:
                            stream = ShortOutput(stream)
                            outputs.append(stream)
                        return stream

                    with patch.object(MODULE.urllib.request, 'urlopen', side_effect=response), \
                            patch.object(os, 'fdopen', side_effect=output):
                        with self.assertRaisesRegex(OSError, 'write.*incomplete'):
                            MODULE.download_file(url, partial)
                    self.assertEqual(requests, [(f'bytes={offset}-', '"exact"')]*3)
                    self.assertEqual([stream.writes for stream in outputs], [2, 2, 2])
                    self.assertEqual(partial.read_bytes(), payload[:offset])
                    self.assertEqual(sidecar.read_bytes(), original_sidecar)
                    self.assertFalse(list(self.root.glob('.*.replacement')))
                    self.assertFalse((self.root/'download.json').exists())

                    def recover(request, timeout):
                        self.assertEqual(request.get_header('Range'), f'bytes={offset}-')
                        self.assertEqual(request.get_header('If-range'), '"exact"')
                        result = Response(payload if recovery_status == 200 else payload[offset:], recovery_status)
                        result.headers['ETag'] = '"exact"'
                        if recovery_status == 206:
                            result.headers['Content-Range'] = f'bytes {offset}-{len(payload)-1}/{len(payload)}'
                        return result

                    with patch.object(MODULE.urllib.request, 'urlopen', side_effect=recover) as retry:
                        MODULE.download_file(url, partial)
                    self.assertEqual(retry.call_count, 1)
                    self.assertEqual(partial.read_bytes(), payload)
                    receipt = json.loads(sidecar.read_bytes())
                    self.assertEqual(receipt, {'url': url, 'etag': '"exact"', 'length': len(payload),
                        'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()})
                    self.assertFalse(list(self.root.glob('.*.replacement')))

    def test_restart_checks_actual_staged_extent_before_replacing_verified_prefix(self):
        payload = b'fLaC' + bytes(range(256))*1024
        prior = payload[:12_345]
        url = 'https://example.invalid/audio'
        partial = self.root/'extent.partial'
        partial.write_bytes(prior)
        sidecar = partial.with_name(partial.name+'.http.json')
        sidecar.write_text(json.dumps({'url': url, 'etag': '"exact"', 'length': len(payload),
            'bytes': len(prior), 'sha256': hashlib.sha256(prior).hexdigest()}), encoding='utf-8')
        original_sidecar = sidecar.read_bytes()
        fdopen, fsync = os.fdopen, os.fsync
        staged = set()
        mismatches = []

        def output(descriptor, *args, **kwargs):
            stream = fdopen(descriptor, *args, **kwargs)
            if kwargs.get('buffering') == 0:
                staged.add(descriptor)
            return stream

        def truncate_before_sync(descriptor):
            if descriptor in staged:
                staged.remove(descriptor)
                self.assertEqual(os.fstat(descriptor).st_size, len(payload))
                os.ftruncate(descriptor, len(payload)-1)
                mismatches.append(os.fstat(descriptor).st_size)
            return fsync(descriptor)

        def response(request, timeout):
            self.assertEqual(request.get_header('Range'), f'bytes={len(prior)}-')
            result = Response(payload)
            result.headers['ETag'] = '"exact"'
            return result

        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=response) as requests, \
                patch.object(os, 'fdopen', side_effect=output), patch.object(os, 'fsync', side_effect=truncate_before_sync):
            with self.assertRaisesRegex((OSError, ValueError), 'replacement|write|extent'):
                MODULE.download_file(url, partial)
        self.assertEqual(requests.call_count, 3)
        self.assertEqual(mismatches, [len(payload)-1]*3)
        self.assertEqual(partial.read_bytes(), prior)
        self.assertEqual(sidecar.read_bytes(), original_sidecar)
        self.assertFalse(list(self.root.glob('.*.replacement')))

    def test_incomplete_full_response_preserves_retained_partial(self):
        partial=self.root/'owned.partial'
        partial.write_bytes(b'fLaCretained-prefix')
        for status in (200, 206):
            with self.subTest(status=status):
                def incomplete(*args, **kwargs):
                    response=Response(b'fLaCshort', status)
                    response.headers['Content-Length']='1000'
                    if status == 206:
                        response.headers['Content-Range']='bytes 0-999/1000'
                    return response
                with patch.object(MODULE.urllib.request,'urlopen',side_effect=incomplete):
                    with self.assertRaisesRegex(ValueError,'Incomplete replacement'):
                        MODULE.download_file('https://example.invalid/audio',partial)
                self.assertEqual(partial.read_bytes(),b'fLaCretained-prefix')
                self.assertFalse(list(self.root.glob('.*.replacement')))

    def test_cli_interrupt_does_not_start_the_queued_third_transfer(self):
        import os
        import signal
        import subprocess
        import sys
        import time
        script = self.root / 'interrupt_cli.py'
        script.write_text("import http.server, json, os, sys, threading\nfrom pathlib import Path\nsys.path.insert(0, sys.argv[1])\nimport stemwise_media.save_download as saver\nsaver.ROOT = root = Path(sys.argv[2])\n(root/'cli-pid').write_text(str(os.getpid()))\nblocked = threading.Event()\nclass Handler(http.server.BaseHTTPRequestHandler):\n    def log_message(self, *args): pass\n    def do_GET(self):\n        if '/dl/' in self.path:\n            track = self.path.rsplit('Example',1)[1]\n            (root / ('started-' + track)).touch()\n            job = {'status':'done','service':'qobuz','url':'/temp/complete/'+track+'/Song.flac',\n                'current':{'name':'Song','artist':'Artist','cover':'/cover?url=https%3A%2F%2Fplay.qobuz.com%2Ftrack%2F'+track}}\n            body = json.dumps(job).encode()\n            self.send_response(200); self.send_header('Content-Length',str(len(body))); self.end_headers()\n            self.wfile.write(body)\n        else:\n            self.send_response(200); self.send_header('Content-Length','10000000'); self.end_headers()\n            self.wfile.write(b'fLaC' + b'x'*65532); self.wfile.flush()\n            blocked.wait(30)\nserver = http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)\nserver.daemon_threads = True\nthreading.Thread(target=server.serve_forever,daemon=True).start()\nhttp = saver.http_request\ndef local(url, budget, **kwargs):\n    return http(url.replace('https://eu.doubledouble.top', 'http://127.0.0.1:'+str(server.server_port)), budget, **kwargs)\nsaver.http_request = local\nsys.argv = ['save_download.py'] + [str(i)+'=https://eu.doubledouble.top/dl/Example'+str(i) for i in (11,22,33)]\ntry:\n    try: saver.main()\n    except KeyboardInterrupt: raise SystemExit(130)\nfinally:\n    blocked.set(); server.shutdown(); server.server_close()\n", encoding='utf-8')
        from stemwise_engine.common import owned_process_tree
        owner = owned_process_tree([sys.executable, '-B', str(script),
            str(PROJECT / 'PC side/qobuz-comparison'), str(self.root)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        child = owner.__enter__()
        partials = [self.root/f'audio/qobuz/{track}/Song.flac.Example{track}.partial' for track in (11,22)]
        try:
            deadline = time.monotonic() + 12
            while not (any(path.exists() and path.stat().st_size == 65536 for path in partials)
                    and all((self.root/f'started-{track}').exists() for track in (11,22))):
                if child.poll() is not None or time.monotonic() >= deadline:
                    if child.poll() is not None:
                        stdout, stderr = child.communicate()
                        self.fail(stderr.decode() + stdout.decode())
                    self.fail('Retained chunk sizes: ' + str([path.stat().st_size if path.exists() else None for path in partials]))
                time.sleep(.01)
            started = time.monotonic()
            os.kill(int((self.root/'cli-pid').read_text()), signal.SIGINT)
            stdout, stderr = child.communicate(timeout=8)
            self.assertEqual(child.returncode, 130, stderr.decode())
            self.assertLess(time.monotonic()-started, 8)
            retained = [path for path in partials if path.exists()]
            self.assertTrue(retained)
            self.assertTrue(all(path.read_bytes() == b'fLaC'+b'x'*65532 for path in retained))
            self.assertFalse((self.root/'started-33').exists())
            self.assertFalse((self.root/'audio/qobuz/33/download.json').exists())
        finally:
            owner.__exit__(None, None, None)

    def test_complete_partial_recovers_after_first_journal_write_failure(self):
        from urllib.error import HTTPError
        real_write = MODULE.atomic_write_json
        def fail_transfer(path, value):
            if path.name == 'download-transfer.json':
                raise OSError('transfer journal full')
            return real_write(path, value)
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)), \
                patch.object(MODULE, 'atomic_write_json', side_effect=fail_transfer):
            with self.assertRaisesRegex(OSError, 'journal full'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        base = self.root / 'audio/qobuz/11'
        self.assertEqual((base / 'Song.flac.Example.partial').read_bytes(), self.audio)
        def eof(request, timeout):
            if '/dl/' in request.full_url:
                return self.responses(11)[0]
            self.assertEqual(request.get_header('Range'), f'bytes={len(self.audio)}-')
            self.assertEqual(request.get_header('If-range'), '"fixture-audio"')
            raise HTTPError(request.full_url, 416, 'EOF',
                {'Content-Range': f'bytes */{len(self.audio)}','ETag':'"fixture-audio"'}, None)
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=eof):
            self.assertEqual(MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')['status'], 'saved')
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
        self.assertEqual(json.loads((base / 'download.json').read_bytes())['qobuzTrackId'], 11)

    def test_eof_response_cannot_adopt_a_different_sized_partial(self):
        from urllib.error import HTTPError
        partial = self.root / 'owned.partial'
        partial.write_bytes(self.audio[:12])
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=lambda *a, **k:
                (_ for _ in ()).throw(HTTPError('https://example.invalid', 416, 'EOF',
                    {'Content-Range': f'bytes */{len(self.audio)}'}, None))):
            with self.assertRaises(HTTPError):
                MODULE.download_file('https://example.invalid', partial)
        self.assertEqual(partial.read_bytes(), self.audio[:12])

    def test_retained_binding_cannot_reuse_audio_under_another_track_id(self):
        base = self.root / 'audio/qobuz/11'
        base.mkdir(parents=True)
        (base / 'Song.flac').write_bytes(self.audio)
        (base / 'download.json').write_text(json.dumps({'qobuzTrackId': 22, 'file': 'Song.flac'}))
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=AssertionError('Must not download')):
            with self.assertRaisesRegex(ValueError, 'binding'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
        self.assertFalse((base / 'download-transfer.json').exists())

    def test_owner_rejected_track_cannot_be_reused_or_downloaded(self):
        base = self.root / 'audio/qobuz/11'
        base.mkdir(parents=True)
        (base / 'Song.flac').write_bytes(self.audio)
        (base / 'download.json').write_text(json.dumps({'qobuzTrackId': 11,
                'file': 'Song.flac', 'status': 'owner_rejected'}))
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=AssertionError('Must not download')):
            with self.assertRaisesRegex(ValueError, 'owner rejected'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')

    def setUp(self):
        from qobuz_fixtures import inline_http_worker
        inline_http_worker(self)
        work = PROJECT / "Test suite/work"
        work.mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.addCleanup(patch.stopall)
        patch.object(MODULE, "ROOT", self.root).start()
        import soundfile as sf
        import numpy as np
        buffer = io.BytesIO()
        sf.write(buffer,np.linspace(-.2,.2,4800),48000,format='FLAC',subtype='PCM_16')
        self.audio = buffer.getvalue()

    def responses(self, track_id):
        job = {"status": "done", "service": "qobuz", "url": "./temp/complete/Example/Song.flac",
               "current": {"name": "Song", "artist": "Artist", "cover": f"/cover?url=https%3A%2F%2Fplay.qobuz.com%2Ftrack%2F{track_id}"}}
        audio = Response(self.audio)
        audio.headers['ETag'] = '"fixture-audio"'
        return [Response(json.dumps(job).encode()), audio]

    def test_another_tracks_job_cannot_be_saved_under_requested_id(self):
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=self.responses(22)):
            with self.assertRaisesRegex(ValueError, "Qobuz track"):
                MODULE.save_download(11, "https://eu.doubledouble.top/dl/Example")
        self.assertFalse(list(self.root.rglob("*.flac")))

    def test_transfer_preserves_bytes_and_existing_download_is_reused(self):
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=self.responses(11)):
            result = MODULE.save_download(11, "https://eu.doubledouble.top/dl/Example")
        base = self.root / "audio/qobuz/11"
        self.assertEqual((base / "Song.flac").read_bytes(), self.audio)
        metadata = json.loads((base / "download.json").read_text())
        self.assertEqual(metadata["qobuzTrackId"], 11)
        self.assertEqual(metadata["audioComparison"], "not_performed")
        with patch.object(MODULE.urllib.request, "urlopen", side_effect=AssertionError("Unexpected duplicate transfer")):
            reused = MODULE.save_download(11, "https://eu.doubledouble.top/dl/Example")
        self.assertEqual(reused["status"], "already_saved")
        self.assertEqual(reused["bytes"], len(self.audio))
        self.assertEqual(result["bytes"], len(self.audio))

    def test_another_jobs_partial_bytes_are_not_appended_to_a_new_job(self):
        base = self.root / 'audio/qobuz/11'
        base.mkdir(parents=True)
        unrelated = base / 'Song.flac.partial'
        unrelated.write_bytes(b'fLaCwrong prefix from another job')
        def response(request, timeout):
            if '/dl/' in request.full_url:
                return self.responses(11)[0]
            offset = int(request.get_header('Range', 'bytes=0-')[6:-1])
            result = Response(self.audio[offset:])
            result.status = 206 if offset else 200
            if offset:
                result.headers['Content-Range'] = f'bytes {offset}-{len(self.audio)-1}/{len(self.audio)}'
            return result
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=response):
            MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
        self.assertEqual(unrelated.read_bytes(), b'fLaCwrong prefix from another job')

    def test_empty_owned_partial_can_retry_without_losing_audio(self):
        base = self.root / 'audio/qobuz/11'
        base.mkdir(parents=True)
        partial = base / 'Song.flac.Example.partial'
        partial.write_bytes(b'')
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)):
            result = MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual(result['status'], 'saved')
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)

    def test_metadata_commit_failure_resumes_exact_promoted_audio_without_network(self):
        real_write = MODULE.atomic_write_json
        def interrupted(path, value):
            if path.name == 'download.json':
                raise OSError('interrupted metadata commit')
            return real_write(path, value)
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)), \
                patch.object(MODULE, 'atomic_write_json', interrupted):
            with self.assertRaisesRegex(OSError, 'interrupted metadata'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        base = self.root / 'audio/qobuz/11'
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=AssertionError('Do not repeat transfer')):
            result = MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual(result['status'], 'saved')
        self.assertEqual(json.loads((base / 'download.json').read_bytes())['qobuzTrackId'], 11)
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)

    def test_unbound_same_name_audio_is_never_adopted(self):
        base = self.root / 'audio/qobuz/11'
        base.mkdir(parents=True)
        original = base / 'Song.flac'
        original.write_bytes(self.audio)
        with patch.object(MODULE.urllib.request, 'urlopen', return_value=self.responses(11)[0]):
            with self.assertRaises(FileExistsError):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual(original.read_bytes(), self.audio)
        self.assertFalse((base / 'download.json').exists())

    def test_interrupted_promotion_resumes_only_its_bound_partial(self):
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)), \
                patch.object(MODULE, 'durable_replace', side_effect=OSError('interrupted audio promotion')):
            with self.assertRaisesRegex(OSError, 'promotion'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        base = self.root / 'audio/qobuz/11'
        partial = base / 'Song.flac.Example.partial'
        self.assertEqual(partial.read_bytes(), self.audio)
        self.assertFalse((base / 'Song.flac').exists())
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=AssertionError('No repeat transfer')):
            result = MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual(result['status'], 'saved')
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
        self.assertFalse(partial.exists())
        self.assertFalse((base / 'download-transfer.json').exists())

    def test_interrupted_registration_rejects_changed_audio_and_different_job(self):
        real_write = MODULE.atomic_write_json
        def fail_metadata(path, value):
            if path.name == 'download.json':
                raise OSError('interrupted metadata commit')
            return real_write(path, value)
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)), \
                patch.object(MODULE, 'atomic_write_json', fail_metadata):
            with self.assertRaises(OSError):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        base = self.root / 'audio/qobuz/11'
        transaction = (base / 'download-transfer.json').read_bytes()
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=AssertionError('No network')):
            with self.assertRaisesRegex(ValueError, 'binding'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/OtherJob')
            self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
            (base / 'Song.flac').write_bytes(self.audio[:-1] + b'x')
            with self.assertRaisesRegex(ValueError, 'audio differs'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        self.assertEqual((base / 'download-transfer.json').read_bytes(), transaction)
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio[:-1] + b'x')
        self.assertFalse((base / 'download.json').exists())

    def test_commit_before_transaction_cleanup_reuses_metadata_and_removes_transaction(self):
        unlink = Path.unlink
        def interrupted(path, *args, **kwargs):
            if path.name == 'download-transfer.json':
                raise OSError('interrupted cleanup')
            return unlink(path, *args, **kwargs)
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=self.responses(11)), \
                patch.object(Path, 'unlink', interrupted):
            with self.assertRaisesRegex(OSError, 'cleanup'):
                MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Example')
        base = self.root / 'audio/qobuz/11'
        committed = (base / 'download.json').read_bytes()
        with patch.object(MODULE.urllib.request, 'urlopen', side_effect=AssertionError('No repeat transfer')):
            result = MODULE.save_download(11, 'https://eu.doubledouble.top/dl/Retained')
        self.assertEqual(result['status'], 'already_saved')
        self.assertEqual((base / 'download.json').read_bytes(), committed)
        self.assertEqual((base / 'Song.flac').read_bytes(), self.audio)
        self.assertFalse((base / 'download-transfer.json').exists())


if __name__ == "__main__":
    unittest.main()
