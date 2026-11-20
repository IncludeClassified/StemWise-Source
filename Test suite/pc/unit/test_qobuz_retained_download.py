"""Interrupted transfers resume without losing received audio."""
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
from stemwise_media.save_download import download_file


class RetainedDownloadTests(unittest.TestCase):
    def test_late_eof_cannot_complete_after_the_absolute_budget(self):
        import io
        import time
        from stemwise_media.transfer_budget import TransferBudget, copy_bounded
        class LateEof(io.BytesIO):
            def read1(self, size):
                time.sleep(.08)
                return b''
        with self.assertRaises(TimeoutError):
            copy_bounded(LateEof(), io.BytesIO(), TransferBudget(time.monotonic()+.02))

    def test_owned_network_deadline_covers_headers_body_streaming_and_eof(self):
        import time
        from http.server import ThreadingHTTPServer
        from stemwise_media.transfer_budget import TransferBudget
        for mode in ('headers', 'body', 'streaming', 'eof'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                release, accepted = threading.Event(), threading.Event()
                class Handler(BaseHTTPRequestHandler):
                    def log_message(self, *args): pass
                    def do_GET(self):
                        accepted.set()
                        try:
                            if mode == 'headers': release.wait(15)
                            self.send_response(200)
                            if mode != 'eof': self.send_header('Content-Length', '65537')
                            self.end_headers()
                            if mode in ('streaming', 'eof'):
                                self.wfile.write(b'x'*65536); self.wfile.flush()
                            release.wait(15)
                            self.close_connection = True
                        except (BrokenPipeError, ConnectionResetError): pass
                server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); server.daemon_threads = True
                worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
                target = Path(name)/'audio.partial'
                try:
                    start = time.monotonic()
                    with self.assertRaises(TimeoutError):
                        download_file(f'http://127.0.0.1:{server.server_port}/file', target,
                            budget=TransferBudget(start+4))
                    self.assertTrue(accepted.is_set(), 'Actual child did not reach the HTTP boundary')
                    self.assertLess(time.monotonic()-start, 6)
                    if mode in ('streaming', 'eof'): self.assertEqual(target.read_bytes(), b'x'*65536)
                finally:
                    release.set(); server.shutdown(); server.server_close(); worker.join(timeout=5)
                    self.assertFalse(worker.is_alive())

    def test_cancelled_owned_transfer_preserves_received_bytes_for_exact_resume(self):
        import time
        from http.server import ThreadingHTTPServer
        from stemwise_media.transfer_budget import TransferBudget
        from stemwise_engine.provider_operations import provider_operation, ProviderCancelled
        release = threading.Event(); offsets = []; validators = []; payload = b'x'*65536+b'remaining bytes'
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                offset = int(self.headers.get('Range', 'bytes=0-')[6:-1]); offsets.append(offset)
                validators.append(self.headers.get('If-Range'))
                self.send_response(206 if offset else 200)
                self.send_header('Content-Length', str(len(payload)-offset))
                self.send_header('ETag', '"stable-audio"')
                if offset: self.send_header('Content-Range', f'bytes {offset}-{len(payload)-1}/{len(payload)}')
                self.end_headers()
                try:
                    self.wfile.write(payload[offset:] if offset else payload[:65536]); self.wfile.flush()
                    if not offset: release.wait(15)
                except (BrokenPipeError, ConnectionResetError): pass
                self.close_connection = True
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler); server.daemon_threads = True
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as name:
                target = Path(name)/'audio.partial'; url = f'http://127.0.0.1:{server.server_port}/file'
                with provider_operation(10, cancelled=lambda: target.exists() and target.stat().st_size >= 65536):
                    with self.assertRaises(ProviderCancelled): download_file(url, target, budget=TransferBudget(time.monotonic()+10))
                self.assertEqual(target.read_bytes(), payload[:65536])
                release.set()
                download_file(url, target, budget=TransferBudget(time.monotonic()+10))
                self.assertEqual(target.read_bytes(), payload)
                self.assertEqual(offsets, [0, 65536])
                self.assertEqual(validators, [None, '"stable-audio"'])
        finally:
            release.set(); server.shutdown(); server.server_close(); worker.join(timeout=5)
            self.assertFalse(worker.is_alive())

    def test_interrupted_transfer_resumes_the_missing_bytes(self):
        payload = b"fLaC" + bytes(range(100))
        offsets, validators = [], []

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                offset = int(self.headers.get("Range", "bytes=0-")[6:-1])
                offsets.append(offset)
                validators.append(self.headers.get("If-Range"))
                self.send_response(206 if offset else 200)
                self.send_header("Content-Length", str(len(payload) - offset))
                self.send_header("ETag", '"stable-audio"')
                if offset:
                    self.send_header("Content-Range", f"bytes {offset}-{len(payload)-1}/{len(payload)}")
                self.end_headers()
                self.wfile.write(payload[:20] if not offset else payload[offset:])
                self.close_connection = True

        server = HTTPServer(("127.0.0.1", 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with tempfile.TemporaryDirectory(dir=ROOT / "Test suite/work") as temporary:
                target = Path(temporary) / "audio.partial"
                download_file(f"http://127.0.0.1:{server.server_port}/file", target)
                self.assertEqual(target.read_bytes(), payload)
                self.assertEqual(offsets, [0, 20])
                self.assertEqual(validators, [None, '"stable-audio"'])
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
