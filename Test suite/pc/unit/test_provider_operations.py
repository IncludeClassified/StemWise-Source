"""Owned local HTTP work proves elapsed budgets; no external provider traffic."""
from contextlib import contextmanager, ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import json
import sys
import tempfile
import threading
import time
import unittest
from types import ModuleType
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
from stemwise_engine.provider_operations import (ProviderCancelled, ProviderDeadline, ProviderStageDeadline, ProviderTransientError,
    fetch_bytes, provider_operation, optional_provider_stage, retry)
import stemwise_engine.lyrics_acquire as lyrics_acquire
import stemwise_engine.provider_operations as provider_operations
import stemwise_engine.youtube as youtube


class ProviderOperationTests(unittest.TestCase):
    def test_backoff_crossing_endpoint_never_sleeps_negative_and_preserves_termination(self):
        clock = Mock(side_effect=[0, 0, .099, .101, .101, .101])
        provider_operations.ProviderBudget(10, clock=clock).pause(.1)
        with self.assertRaises(ProviderCancelled):
            provider_operations.ProviderBudget(10, cancelled=lambda: True).pause(.1)
        with self.assertRaises(ProviderDeadline):
            provider_operations.ProviderBudget(.1, clock=Mock(side_effect=[0, 0, .101, .101])).pause(1)

    def test_album_catalogue_deadline_retires_worker_and_releases_archive_lease(self):
        from stemwise_media import save_album, transfer_budget
        from stemwise_engine.common import exclusive_process_lock
        entered, release = threading.Event(), threading.Event()
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                self.send_response(200)
                self.send_header('Content-Length', '10000')
                self.end_headers()
                self.wfile.write(b'x')
                self.wfile.flush()
                entered.set()
                release.wait(8)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        request = transfer_budget.http_request
        urlopen = save_album.urllib.request.urlopen
        budget_type = transfer_budget.TransferBudget
        def local_request(url, budget, **kwargs):
            self.assertEqual(url, 'https://www.qobuz.com/album/example')
            return request(f'http://127.0.0.1:{server.server_port}/album', budget, **kwargs)
        def local_urlopen(request, **kwargs):
            # The retired direct-read path must also remain local during the negative control.
            self.assertEqual(request.full_url, 'https://www.qobuz.com/album/example')
            return urlopen(f'http://127.0.0.1:{server.server_port}/album', **kwargs)
        try:
            with tempfile.TemporaryDirectory(dir=ROOT/'Test suite/work') as temporary:
                root = Path(temporary)
                retained = root/'audio/qobuz/album-archives/Example.zip'
                retained.parent.mkdir(parents=True)
                retained.write_bytes(b'prior archive bytes')
                started = time.monotonic()
                with patch.object(save_album, 'ROOT', root), \
                        patch.object(save_album, 'TransferBudget', side_effect=lambda deadline, **kwargs:
                            budget_type(time.monotonic()+2, **kwargs)), \
                        patch.object(save_album.urllib.request, 'urlopen', side_effect=local_urlopen), \
                        patch.object(transfer_budget, 'http_request', side_effect=local_request), self.tracked_owner():
                    with self.assertRaises(TimeoutError):
                        save_album.save_album('https://www.qobuz.com/album/example',
                            'https://eu.doubledouble.top/dl/Example', {10})
                self.assertTrue(entered.is_set(), 'Actual HTTP worker must enter the blocked read')
                self.assertLess(time.monotonic()-started, 5)
                self.assertFalse(release.is_set(), 'Operation must retire without waiting for the body')
                self.assertEqual(retained.read_bytes(), b'prior archive bytes')
                with exclusive_process_lock(retained.with_suffix('.lock')):
                    pass
        finally:
            release.set()
            server.shutdown()
            server.server_close()
            thread.join(3)
            self.assertFalse(thread.is_alive())

    @contextmanager
    def downloader(self, *, blocked=False, download_only=False, handshake=False):
        scratch = ROOT / 'Test suite/work/audit-36-pc'
        scratch.mkdir(exist_ok=True)
        try:
            with tempfile.TemporaryDirectory(dir=scratch) as name:
                root = Path(name)
                source = '''
import json,os,time
from pathlib import Path
class YoutubeDL:
 def __init__(self, options): self.options=options
 def __enter__(self): return self
 def __exit__(self,*args): pass
 def sanitize_info(self,value): return value
 def prepare_filename(self,value): return self.options['outtmpl'].replace('%(ext)s','json3' if self.options.get('skip_download') else 'opus')
 def extract_info(self,url,download=False):
  if os.environ.get('STEMWISE_TEST_EXTRACTOR_HANDSHAKE')=='1':
   Path(os.environ['STEMWISE_TEST_EXTRACTOR_STARTED']).write_bytes(b'started')
   release=Path(os.environ['STEMWISE_TEST_EXTRACTOR_RELEASE'])
   deadline=time.monotonic()+5
   while not release.exists():
    if time.monotonic()>=deadline: raise TimeoutError('Fixture transport observation was not released')
    time.sleep(.01)
  if os.environ.get('STEMWISE_TEST_BLOCK_EXTRACTOR')=='1' and (not os.environ.get('STEMWISE_TEST_DOWNLOAD_ONLY') or download):
   Path(os.environ['STEMWISE_TEST_EXTRACTOR_STARTED']).write_bytes(b'started')
   import sys
   print('private blocked transport entered', file=sys.stderr, flush=True)
   time.sleep(10)
  if self.options.get('extract_flat'):
   return {'id':'playlist','title':'Fixture','entries':[{'id':'abcdefghijk','title':'First'},None,{'id':'abcdefghijk'}]}
  if download:
   path=Path(self.options['outtmpl'].replace('%(ext)s','json3'))
   path.parent.mkdir(parents=True,exist_ok=True)
   path.write_text(json.dumps({'events':[{'tStartMs':100,'dDurationMs':600,'segs':[{'utf8':'one two'}]}]}),encoding='utf-8')
   if not self.options.get('skip_download'):
    path.unlink()
    Path(self.prepare_filename({})).write_bytes(b'complete original')
  return {'id':'abcdefghijk','title':'Fixture metadata','subtitles':{'en':[{'ext':'json3'}]}}
'''
                package = root / 'stemwise_engine'
                package.mkdir()
                # Canonical children use fixture providers and the real orchestration only.
                # Refuse external DNS before it can reach a provider if a stub is missed.
                (package / '__init__.py').write_text(
                    "import socket\nfrom pathlib import Path\n"
                    f"__path__ = [str(Path(__file__).parent), {str(ROOT / 'PC side/stemwise-playlist-builder/src')!r}]\n"
                    "_resolve = socket.getaddrinfo\n"
                    "def _local_only(host, *args, **kwargs):\n"
                    " if host not in ('localhost', '127.0.0.1', '::1', None): raise AssertionError('External provider access forbidden in fixture')\n"
                    " return _resolve(host, *args, **kwargs)\n"
                    "socket.getaddrinfo = _local_only\n", encoding='utf-8')
                (root / 'yt_dlp.py').write_text(source, encoding='utf-8')
                module = ModuleType('yt_dlp')
                exec(compile(source, str(root / 'yt_dlp.py'), 'exec'), module.__dict__)
                with patch.dict(sys.modules, {'yt_dlp': module}), patch.dict(os.environ, {
                        'PYTHONPATH': str(root),
                        'STEMWISE_YTDLP_VERSION': 'base', 'STEMWISE_TEST_BLOCK_EXTRACTOR': '1' if blocked else '0',
                        'STEMWISE_TEST_EXTRACTOR_STARTED': str(root / 'extractor-started'),
                        'STEMWISE_TEST_EXTRACTOR_HANDSHAKE': '1' if handshake else '0',
                        'STEMWISE_TEST_EXTRACTOR_RELEASE': str(root / 'extractor-release'),
                        'STEMWISE_TEST_DOWNLOAD_ONLY': '1' if download_only else ''}), \
                        patch.object(youtube, 'maybe_update_ytdlp'):
                    yield root
        finally:
            try:
                scratch.rmdir()
            except OSError:
                pass

    def test_child_fixture_refuses_external_provider_resolution(self):
        from stemwise_engine.common import run_process_tree
        with self.downloader():
            child = run_process_tree([sys.executable, '-c',
                "import stemwise_engine, socket; socket.getaddrinfo('provider.invalid', 443)"],
                timeout=5, capture_output=True, text=True)
        self.assertNotEqual(child.returncode, 0)
        self.assertIn('External provider access forbidden in fixture', child.stderr)

    def test_reference_download_is_owned_bounded_and_preserves_prior_binding(self):
        import stemwise_media.quick_batch as batch
        for cancel in (False, True):
            with self.subTest(cancel=cancel), self.downloader(blocked=True, download_only=True) as root:
                library = root/'PC side/qobuz-comparison'
                base = library/'audio/youtube/abcdefghijk'
                base.mkdir(parents=True)
                original = base/'source.m4a'
                original.write_bytes(b'prior audio')
                receipt = base/'download.json'
                receipt.write_bytes(b'{"videoId":"abcdefghijk","file":"source.m4a","status":"saved"}')
                before = receipt.read_bytes()
                with patch.object(batch, 'ROOT', library), patch.object(batch, 'probe', return_value={'codec_name': 'aac'}), self.tracked_owner():
                    if cancel:
                        with self.assertRaises(ProviderCancelled):
                            batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'}, 'bestaudio[acodec=opus]',
                                timeout_seconds=5, cancelled=lambda: (root/'extractor-started').exists())
                    else:
                        result = batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'}, 'bestaudio[acodec=opus]', timeout_seconds=5)
                        self.assertEqual(result['status'], 'failed')
                        self.assertIs(result['retryable'], True)
                        failure = root/'Engine telemetry/youtube-abcdefghijk/reference-download/failure.json'
                        self.assertEqual(json.loads(failure.read_bytes())['privateDiagnostics'], result['privateDiagnostics'])
                    self.assertEqual(receipt.read_bytes(), before)
                    self.assertEqual(original.read_bytes(), b'prior audio')
                    from stemwise_engine.common import exclusive_process_lock
                    with exclusive_process_lock(base/'source-selection.lock'):
                        pass

    def test_reference_respects_its_enclosing_provider_deadline(self):
        import stemwise_media.quick_batch as batch
        with self.downloader(blocked=True, download_only=True) as root:
            library = root/'PC side/qobuz-comparison'
            with patch.object(batch, 'ROOT', library), self.tracked_owner(), provider_operation(5):
                result = batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'})
                self.assertEqual(result['status'], 'failed')
                self.assertIs(result['retryable'], True)

    def test_reference_timeout_returns_failure_after_comparison_deadline(self):
        import stemwise_media.quick_batch as batch
        import stemwise_media.bounded_pcm as pcm
        with self.downloader() as root:
            library = root/'PC side/qobuz-comparison'
            base = library/'audio/youtube/abcdefghijk'
            base.mkdir(parents=True)
            original = base/'source.m4a'
            original.write_bytes(b'prior audio')
            receipt = base/'download.json'
            receipt.write_bytes(b'{"videoId":"abcdefghijk","file":"source.m4a","status":"saved"}')
            before = receipt.read_bytes()
            def expire(*args, **kwargs):
                budget = pcm._BUDGET.get()
                budget.deadline = budget.clock() - 1
                raise ProviderDeadline('provider-operation-deadline')
            with patch.object(batch, 'ROOT', library), \
                    patch.object(batch, 'probe', return_value={'codec_name': 'aac'}), \
                    patch.object(youtube, 'extract_youtube_info', side_effect=expire):
                result = batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'},
                                                  'bestaudio[acodec=opus]')
            self.assertEqual(result['status'], 'failed')
            self.assertIs(result['retryable'], True)
            failure = root/'Engine telemetry/youtube-abcdefghijk/reference-download/failure.json'
            self.assertEqual(json.loads(failure.read_bytes())['privateDiagnostics'], result['privateDiagnostics'])
            self.assertEqual(receipt.read_bytes(), before)
            self.assertEqual(original.read_bytes(), b'prior audio')

    def test_reference_success_uses_actual_child_filename_and_durable_receipt(self):
        import stemwise_media.quick_batch as batch
        with self.downloader(handshake=True) as root:
            library = root/'PC side/qobuz-comparison'
            with patch.object(batch, 'ROOT', library), \
                    patch.object(batch, 'probe', return_value={'codec_name': 'opus'}), self.tracked_owner(handshake=root):
                result = batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'}, timeout_seconds=8)
            self.assertTrue((root/'extractor-release').is_file(), 'Actual child observation did not release transport')
            self.assertEqual(result['status'], 'saved')
            base = library/'audio/youtube/abcdefghijk'
            receipt = json.loads((base/'download.json').read_bytes())
            self.assertEqual(receipt['file'], 'source.opus')
            self.assertEqual((base/receipt['file']).read_bytes(), b'complete original')

    def extract(self, kind, root):
        if kind == 'metadata':
            return youtube.fetch_video_info('abcdefghijk')
        if kind == 'inventory':
            return youtube.inventory_playlist('https://www.youtube.com/playlist?list=fixture')
        from stemwise_engine.lyrics_alignment import download_youtube_caption_evidence
        return download_youtube_caption_evidence('abcdefghijk', root / 'captions', language='en')

    def test_blocked_metadata_inventory_and_caption_extractors_obey_elapsed_budget(self):
        for kind in ('metadata', 'inventory', 'caption'):
            with self.subTest(kind=kind), self.downloader(blocked=True) as root:
                with self.tracked_owner(), self.assertRaises(ProviderDeadline) as caught, provider_operation(6):
                    self.extract(kind, root)
                self.assertTrue((root / 'extractor-started').exists())
                self.assertIn('private blocked transport entered', caught.exception.stderr)
        # Give both real child launches time to reach the blocked transport, then expire it.
        with self.downloader(blocked=True, download_only=True) as root:
            with self.tracked_owner(expected=2), self.assertRaises(ProviderDeadline), provider_operation(6):
                self.extract('caption', root)
            self.assertTrue((root / 'extractor-started').exists())

    def test_cancellation_retires_each_blocked_extraction_path(self):
        for kind in ('metadata', 'inventory', 'caption'):
            with self.subTest(kind=kind), self.downloader(blocked=True) as root:
                with self.tracked_owner(), self.assertRaises(ProviderCancelled), provider_operation(
                        5, cancelled=lambda: (root / 'extractor-started').exists()):
                    self.extract(kind, root)

    def test_successful_metadata_inventory_and_caption_protocol_reaches_actual_consumers(self):
        with self.downloader() as root, provider_operation(10):
            self.assertEqual(self.extract('metadata', root)['title'], 'Fixture metadata')
            playlist = self.extract('inventory', root)
            self.assertEqual(playlist['itemCount'], 1)
            self.assertEqual(playlist['omittedCount'], 2)
            import stemwise_engine.lyrics_alignment as lyrics_alignment
            def known_normalization(raw):
                self.assertEqual(raw, 'one two')
                return 'one two'
            # CPU CI substitutes only the optional text-normalization/DTO library.
            from types import SimpleNamespace
            with patch.object(lyrics_alignment, 'normalize', side_effect=known_normalization), \
                    patch.object(lyrics_alignment, 'WordTimestamp', side_effect=lambda **values: SimpleNamespace(**values)):
                words, evidence = self.extract('caption', root)
            self.assertEqual([word.raw for word in words], ['one', 'two'])
            self.assertEqual([(word.start, word.end) for word in words], [(.1, .4), (.4, .7)])
            self.assertEqual(evidence['captionKind'], 'manual')

    @contextmanager
    def blocked_update(self, root):
        import stemwise_engine.ytdlp_update as ytdlp_update
        runtime = root / 'runtime'
        runtime.mkdir()
        (runtime / 'active.json').write_bytes(b'{"version":"2026.08.19"}')
        installed = runtime / '2026.08.19'
        installed.mkdir()
        (installed / 'yt-dlp').write_bytes(b'prior installed bytes')
        # The real provider worker owns the process; only the remote update body is synthetic.
        worker = root / 'stemwise_engine/provider_operations.py'
        worker.write_bytes(Path(provider_operations.__file__).read_bytes())
        (root / 'stemwise_engine/ytdlp_update.py').write_text('''
import time
from pathlib import Path
def activate_installed(): pass
def maybe_update_ytdlp(*args, **kwargs): raise AssertionError('recursive update')
def _update_ytdlp(deno, *, probe_url, scratch_parent):
 candidate=Path(scratch_parent)/'candidate-fixture'
 candidate.mkdir()
 (candidate/'partial').write_bytes(b'incomplete')
 (Path(scratch_parent).parent/'started').write_bytes(b'started')
 time.sleep(10)
''', encoding='utf-8')
        with patch.object(ytdlp_update, 'RUNTIME', runtime), \
                patch.object(youtube, 'maybe_update_ytdlp', ytdlp_update.maybe_update_ytdlp):
            yield runtime
        self.assertEqual((runtime / 'active.json').read_bytes(), b'{"version":"2026.08.19"}')
        self.assertEqual((installed / 'yt-dlp').read_bytes(), b'prior installed bytes')
        self.assertEqual(sorted(path.name for path in runtime.iterdir()), ['2026.08.19', 'active.json', 'started'])

    def test_optional_update_timeout_retires_child_preserves_install_and_allows_metadata(self):
        with self.downloader() as root, self.blocked_update(root), self.tracked_owner(expected=2), provider_operation(8):
            self.assertEqual(self.extract('metadata', root)['title'], 'Fixture metadata')

    def test_optional_update_cancellation_retires_child_and_preserves_install(self):
        import stemwise_engine.ytdlp_update as ytdlp_update
        from stemwise_engine.workflow_records import workflow_failure_details
        with self.downloader() as root, self.blocked_update(root) as runtime:
            with self.tracked_owner(), self.assertRaises(ProviderCancelled) as caught, provider_operation(
                    8, cancelled=lambda: (runtime / 'started').exists()):
                ytdlp_update.maybe_update_ytdlp(youtube.DENO_RUNTIME)
            details = workflow_failure_details(caught.exception)
            self.assertEqual(details['reasonCode'], 'retryable-technical')
            self.assertIn('explicit user request', details['nextAction'])
            self.assertEqual(workflow_failure_details(ProviderDeadline())['reasonCode'], 'retryable-technical')

    def test_extractor_failure_keeps_typed_download_error_without_private_output(self):
        from yt_dlp.utils import DownloadError
        with self.downloader() as root, provider_operation(5):
            # A real child emits progress before its failure; only the final typed cause crosses back.
            with (root / 'yt_dlp.py').open('a', encoding='utf-8') as stream:
                stream.write('''
class DownloadError(Exception): pass
def fail(*args, **kwargs):
 print('private provider output' + '\u0633' * 5000, flush=True)
 raise DownloadError('private URL/token')
YoutubeDL.extract_info=fail
''')
            with self.assertRaises(DownloadError) as caught:
                self.extract('metadata', root)
            self.assertEqual(caught.exception.__cause__.cause['exceptionType'], 'DownloadError')
            self.assertNotIn('private', str(caught.exception))
            self.assertNotIn('token', str(caught.exception))
            self.assertIn('DownloadError: private URL/token', caught.exception.__cause__.stderr)
            self.assertLessEqual(len(caught.exception.__cause__.stderr.encode('utf-8')), 12000)
            from stemwise_engine.private_diagnostics import retain_error
            reference = retain_error(root/'provider.private.txt', caught.exception,
                context={'operationId': 'fixture', 'stage': 'metadata', 'provider': 'youtube'})
            retained = (root/reference['file']).read_text(encoding='utf-8')
            self.assertIn('DownloadError: private URL/token', retained)
            self.assertIn('metadata', retained)


    def test_abnormal_provider_exit_retains_original_stderr_in_workflow_reference(self):
        from test_workflow_operations import WorkflowOperationsTest
        from stemwise_engine.provider_operations import ProviderError
        with self.downloader() as root, provider_operation(8):
            with (root/'yt_dlp.py').open('a', encoding='utf-8') as stream:
                stream.write("\nimport sys\ndef fail(*args,**kwargs):\n sys.stderr.write('exact abnormal transport cause\\n');sys.stderr.flush();os._exit(37)\nYoutubeDL.extract_info=fail\n")
            workflow = WorkflowOperationsTest().workflow(root)
            with self.assertRaises(ProviderError) as caught:
                workflow._run_mutating_operation('abcdefghijk', 'Source', lambda: self.extract('metadata', root))
            self.assertEqual(caught.exception.cause['exceptionType'], 'ProviderProtocolError')
            operation = workflow.report_store.latest_workflow_operation('abcdefghijk')
            reference, = operation['privateDiagnostics']
            path = workflow.report_store.report_root('abcdefghijk')/reference['file']
            payload = path.read_bytes()
            import hashlib
            self.assertEqual(hashlib.sha256(payload).hexdigest(), reference['sha256'])
            self.assertLessEqual(len(payload), 12000)
            self.assertIn(b'exact abnormal transport cause', payload)
            self.assertNotIn('exact abnormal', json.dumps(operation))

    def test_caption_coordinator_propagates_deadline_and_cancellation_without_replacing_cache(self):
        from types import SimpleNamespace
        import stemwise_engine.lyrics_align_pc as lyrics_align_pc
        # No cache can be admitted here; optional numerical-stack identity is outside this failure path.
        with self.downloader() as root, patch.object(lyrics_align_pc, '_current_cache_identity', return_value={}):
            cache = root / 'cache.json'
            cache.write_bytes(b'prior incomplete cache')
            workspace = SimpleNamespace(caption_path=lambda _: cache, job_dir=lambda _: root, workspace_root=root)
            original = {'id': 'original', 'kind': 'original', 'language': 'en', 'script': 'Latn',
                        'format': 'plain', 'text': 'Hello'}
            for failure in (ProviderDeadline('provider-operation-deadline'), ProviderCancelled('provider-operation-cancelled')):
                with self.subTest(error=type(failure).__name__), patch.object(
                        lyrics_align_pc, 'download_youtube_caption_evidence', side_effect=failure), \
                        self.assertRaises(type(failure)):
                    lyrics_align_pc._load_caption(video_id='abcdefghijk', original=original,
                        workspace=workspace, temp_root=root / 'temp')
                self.assertEqual(cache.read_bytes(), b'prior incomplete cache')
            outcomes = []
            with patch.object(lyrics_align_pc, 'download_youtube_caption_evidence',
                              side_effect=ProviderStageDeadline('provider-stage-deadline')):
                self.assertIsNone(lyrics_align_pc._load_caption(video_id='abcdefghijk', original=original,
                    workspace=workspace, temp_root=root / 'temp', record_outcome=lambda *args: outcomes.append(args)))
            self.assertEqual(outcomes[0][:2], ('unavailable', False))
            self.assertEqual(cache.read_bytes(), b'prior incomplete cache')

    def test_optional_stage_expiry_is_distinct_from_outer_deadline_and_cancellation(self):
        for enclosing, failure in ((None, ProviderStageDeadline), (10, ProviderStageDeadline),
                                   (1, ProviderDeadline), ('cancel', ProviderCancelled)):
            clock = [0.]
            cancelled = [False]
            with self.subTest(enclosing=enclosing), ExitStack() as contexts:
                if enclosing is not None:
                    contexts.enter_context(provider_operation(1 if enclosing == 1 else 10,
                        clock=lambda: clock[0], cancelled=lambda: cancelled[0]))
                with self.assertRaises(failure) as caught, optional_provider_stage(1) as stage:
                    if enclosing is None:
                        stage.deadline = stage.clock() - 1
                    else:
                        clock[0] = 2.
                    cancelled[0] = enclosing == 'cancel'
                    stage.remaining()
                self.assertIs(type(caught.exception), failure)

    @contextmanager
    def tracked_owner(self, *, expected=1, handshake=None):
        import stemwise_engine.common as common
        retired, observed, active = [], {}, None
        original_owner = provider_operations.owned_process_tree
        original_remaining = provider_operations.ProviderBudget.remaining
        def remaining(budget):
            if sys.platform.startswith('linux'):
                current = common._owned_descendants()
                observed.update(current)
                if (handshake is not None and active is not None
                        and (handshake/'extractor-started').exists() and active.pid in current
                        and any(identity[0] == active.pid for identity in current.values())):
                    # Release only after observing this exact guardian and its real worker.
                    (handshake/'extractor-release').touch()
            elif handshake is not None and (handshake/'extractor-started').exists():
                (handshake/'extractor-release').touch()
            return original_remaining(budget)
        @contextmanager
        def own(*args, **kwargs):
            nonlocal active
            captured = None
            try:
                with original_owner(*args, **kwargs) as process:
                    captured = active = process
                    yield process
            finally:
                if captured is not None:
                    retired.append(captured.poll())
        with patch.object(provider_operations, 'owned_process_tree', own), patch.object(
                provider_operations.ProviderBudget, 'remaining', remaining):
            yield
        self.assertEqual(len(retired), expected)
        self.assertIsNotNone(retired[0])
        if sys.platform.startswith('linux'):
            self.assertGreaterEqual(len(observed), 2, 'Observe both guardian and actual transport child')
            for pid, identity in observed.items():
                self.assertNotEqual(common._linux_process_identity(pid), identity,
                                    'An owned provider process survived the failure')

    @contextmanager
    def server(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                if self.path.startswith('/status/'):
                    self.send_response(int(self.path.rsplit('/', 1)[1]))
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header('Content-Length', '20' if self.path == '/slow' else '2')
                self.end_headers()
                try:
                    if self.path == '/slow':
                        for _ in range(20):
                            self.wfile.write(b'x')
                            self.wfile.flush()
                            time.sleep(.05)
                    else:
                        self.wfile.write(b'ok')
                except (BrokenPipeError, ConnectionResetError):
                    pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield f'http://127.0.0.1:{server.server_port}'
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)
            self.assertFalse(thread.is_alive())

    def test_real_artwork_slow_progress_obeys_outer_budget_and_valid_response_succeeds(self):
        with self.server() as base:
            started = time.monotonic()
            with self.tracked_owner(), self.assertRaises(ProviderDeadline), provider_operation(.3):
                youtube._download_image(base + '/slow', 100)
            self.assertLess(time.monotonic() - started, 3)
            with provider_operation(5):
                self.assertEqual(youtube._download_image(base + '/valid', 100), b'ok')

    def test_real_transport_cancellation_retires_child_while_response_is_progressing(self):
        with self.server() as base:
            cancelled = threading.Event()
            timer = threading.Timer(.3, cancelled.set)
            timer.start()
            started = time.monotonic()
            try:
                with self.tracked_owner(), self.assertRaises(ProviderCancelled), provider_operation(5, cancelled=cancelled.is_set):
                    fetch_bytes(base + '/slow', max_bytes=100)
            finally:
                timer.cancel()
                timer.join(2)
            self.assertLess(time.monotonic() - started, 3)

    def test_actual_lyrics_provider_retries_only_transient_errors_and_shares_elapsed_budget(self):
        permanent = Mock(side_effect=ValueError('invalid response'))
        with self.assertRaises(ValueError):
            lyrics_acquire._provider_call(permanent)
        permanent.assert_called_once_with()
        clock = [0.]
        def sleep(seconds):
            clock[0] += seconds
        def slow_transient():
            clock[0] += .4
            raise ProviderTransientError('ConnectionError')
        call = Mock(side_effect=slow_transient)
        with provider_operation(.8, clock=lambda: clock[0], sleeper=sleep):
            with self.assertRaises(ProviderDeadline):
                lyrics_acquire._provider_call(call)
        self.assertEqual(call.call_count, 2)
        with provider_operation(2, clock=lambda: clock[0], sleeper=sleep):
            self.assertEqual(retry(Mock(side_effect=[ProviderTransientError('ConnectionError'), 'valid'])), 'valid')

    def test_fallback_calls_inherit_the_same_deadline_and_cannot_return_late_success(self):
        clock = [0.]
        with provider_operation(1, clock=lambda: clock[0], sleeper=lambda value: None):
            clock[0] = .75
            with provider_operation(60) as nested:
                self.assertAlmostEqual(nested.remaining(), .25)
            clock[0] = 1.01
            call = Mock(return_value='late')
            with self.assertRaises(ProviderDeadline):
                retry(call)
            call.assert_not_called()

    def test_real_http_refusals_preserve_only_typed_body_free_causes_for_operation_diagnostics(self):
        from stemwise_engine.workflow_records import workflow_failure_details
        from stemwise_engine.provider_operations import ProviderError
        with self.server() as base:
            for status, transient in ((403, False), (429, True)):
                with self.subTest(status=status), provider_operation(5):
                    with self.assertRaises(ProviderError) as caught:
                        fetch_bytes(base + f'/status/{status}', max_bytes=100)
                    self.assertEqual(caught.exception.cause, {'exceptionType': 'HTTPError',
                        'httpStatus': status, 'retryable': transient})
                    details = workflow_failure_details(caught.exception)
                    self.assertIn(str(status), details['affectedInput'])
                    self.assertNotIn(base, str(caught.exception))
                    self.assertNotIn(base, str(details))
