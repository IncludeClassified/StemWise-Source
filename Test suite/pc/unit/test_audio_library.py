"""The PC audio workflow retains native files and separates recording from master identity."""
import importlib
import io
import json
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import numpy as np
from scipy import signal
from qobuz_fixtures import write_download, flac_bytes

PROJECT = Path(__file__).resolve().parents[3]


class AudioLibraryTest(unittest.TestCase):
    def test_retirement_keeps_sibling_notes_and_unbound_audio(self):
        batch = self.audio.batch
        base = self.root/'audio/youtube/abcdefghijk'
        base.mkdir(parents=True)
        (base/'source.opus').write_bytes(b'owned original')
        (base/'source.opus.part').write_bytes(b'owned partial')
        notes, unrelated = base/'source.opus.notes.txt',base/'owner-take.wav'
        notes.write_bytes(b'owner notes')
        unrelated.write_bytes(b'owner recording')
        (base/'download.json').write_text(json.dumps({'videoId':'abcdefghijk','file':'source.opus'}),encoding='utf-8')
        flac = self.root/'replacement.flac'
        flac.write_bytes(b'approved replacement')
        with patch.object(batch,'ROOT',self.root),patch.object(batch,'qobuz_file',return_value=flac), \
                patch.object(batch,'probe',return_value={'codec_name':'flac'}):
            batch.retire_youtube_audio('abcdefghijk',11)
        self.assertEqual(notes.read_bytes(),b'owner notes')
        self.assertEqual(unrelated.read_bytes(),b'owner recording')
        self.assertFalse((base/'source.opus').exists())
        self.assertFalse((base/'source.opus.part').exists())

    def test_reference_symlink_is_rejected_before_hash_admission(self):
        batch = self.audio.batch
        base = self.root/'audio/youtube/abcdefghijk'
        base.mkdir(parents=True)
        target = self.root/'owner-audio.opus'
        target.write_bytes(b'outside reference root')
        (base/'source.opus').symlink_to(target)
        (base/'download.json').write_text(json.dumps({'videoId':'abcdefghijk','file':'source.opus'}),encoding='utf-8')
        with patch.object(batch,'ROOT',self.root),self.assertRaises(ValueError):
            evidence = batch.ComparisonEvidence()
            evidence.digest(evidence.source('abcdefghijk'))
        self.assertEqual(target.read_bytes(),b'outside reference root')

    def test_hash_rejects_replacement_of_the_named_open_file(self):
        batch = self.audio.batch
        source, retained = self.root/'source.opus',self.root/'retained.opus'
        source.write_bytes(b'original audio')
        real_stat, calls = batch.os.fstat, 0
        def inspected(descriptor):
            nonlocal calls
            result = real_stat(descriptor)
            calls += 1
            if calls == 2:
                source.rename(retained)
                source.write_bytes(b'different audio')
            return result
        with patch.object(batch.os,'fstat',side_effect=inspected),self.assertRaises(ValueError):
            batch.file_sha256(source)
        self.assertEqual(retained.read_bytes(),b'original audio')
        self.assertEqual(source.read_bytes(),b'different audio')

    def test_pending_or_uncertain_submission_cannot_be_replayed_or_rebound(self):
        base=self.root/'audio/qobuz/11'
        base.mkdir(parents=True)
        pending=base/'job.json'
        for job in ('https://eu.doubledouble.top/dl/Accepted', None):
            pending.write_text(json.dumps({'trackId':11, 'jobUrl':job}), encoding='utf-8')
            before=pending.read_bytes()
            with patch.object(self.dd,'ROOT',self.root), \
                    patch.object(self.dd,'_read_json',side_effect=AssertionError('must not replay submission')):
                with self.assertRaises(ValueError):
                    self.dd.download(11,job_url='https://eu.doubledouble.top/dl/Other' if job else None)
            self.assertEqual(pending.read_bytes(),before)

    def test_explicit_retry_after_verified_terminal_failure_creates_a_new_job(self):
        base = self.root/'audio/qobuz/11'
        base.mkdir(parents=True)
        pending = base/'job.json'
        pending.write_text(json.dumps({'trackId': 11, 'jobUrl': 'https://eu.doubledouble.top/dl/Failed'}), encoding='utf-8')
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', return_value={'status':'error', 'error':'provider failure'}):
            with self.assertRaises(RuntimeError): self.dd.download(11)
        self.assertFalse(pending.exists())
        self.assertEqual(json.loads((base/'job-failure.json').read_bytes())['jobUrl'], 'https://eu.doubledouble.top/dl/Failed')
        states = [{'active':False}, {'success':True, 'id':'Fresh'}, {'status':'done'}]
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', side_effect=states), \
                patch.object(self.dd, '_save_download', return_value={'status':'saved'}):
            self.assertEqual(self.dd.download(11)['status'], 'saved')

    def test_public_retry_finishes_exact_transfer_offline_and_rejects_conflicts(self):
        from stemwise_media import save_download
        job = 'https://eu.doubledouble.top/dl/ExactJob'
        for conflict in (None, 'explicit', 'pending', 'audio'):
            with self.subTest(conflict=conflict), tempfile.TemporaryDirectory(dir=PROJECT/'Test suite/work') as temporary:
                root = Path(temporary)
                base = root/'audio/qobuz/11'
                base.mkdir(parents=True)
                payload = flac_bytes()
                state = {'status':'done', 'service':'qobuz', 'url':'/temp/complete/Exact.flac',
                    'current':{'name':'Exact', 'artist':'Artist', 'cover':'/cover?url=https%3A%2F%2Fplay.qobuz.com%2Ftrack%2F11'}}
                write = save_download.atomic_write_json
                def interrupt(path, value):
                    if path.name == 'download.json':
                        raise OSError('metadata publication interrupted')
                    return write(path, value)
                with patch.object(save_download, 'http_request', return_value=json.dumps(state).encode()), \
                        patch.object(save_download, 'download_file', side_effect=lambda url, partial, **kwargs: partial.write_bytes(payload)), \
                        patch.object(save_download, 'atomic_write_json', side_effect=interrupt):
                    with self.assertRaisesRegex(OSError, 'metadata publication interrupted'):
                        save_download._save_download(11, job, base)
                metadata = json.loads((base/'download-transfer.json').read_bytes())['metadata']
                (base/'job.json').write_text(json.dumps({'trackId':11, 'jobUrl':job+('Other' if conflict == 'pending' else '')}), encoding='utf-8')
                audio = base/'Exact.flac'
                if conflict == 'audio': audio.write_bytes(payload+b'changed')
                with patch.object(self.dd, 'ROOT', root), patch.object(self.dd, '_read_json', side_effect=AssertionError('No provider replay')), \
                        patch.object(save_download, 'http_request', side_effect=AssertionError('No provider replay')), \
                        patch.object(self.audio.batch, 'ROOT', root), patch.object(self.audio, 'probe', return_value={'codec_name':'flac'}):
                    if conflict:
                        with self.assertRaises(ValueError):
                            self.audio.download_source('doubledouble', '11', job_url=job+'Other' if conflict == 'explicit' else None)
                        self.assertTrue(audio.exists())
                        self.assertTrue((base/'download-transfer.json').exists())
                        self.assertTrue((base/'job.json').exists())
                    else:
                        self.audio.download_source('doubledouble', '11')
                        self.assertEqual((base/'Exact.flac').read_bytes(), payload)
                        self.assertEqual(json.loads((base/'download.json').read_bytes()), metadata)
                        self.assertFalse((base/'download-transfer.json').exists())
                        self.assertFalse((base/'job.json').exists())

    def setUp(self):
        self.audio = importlib.import_module('stemwise_media.audio_library')
        self.dd = importlib.import_module('stemwise_media.doubledouble')
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT / 'Test suite/work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.addCleanup(patch.stopall)
        patch('stemwise_media.bounded_pcm.SCRATCH_ROOT', self.root/'comparison-pcm').start()

    def test_submission_is_private_native_and_resumes_without_resubmitting(self):
        observed = []
        def response(url, timeout):
            observed.append(url)
            if '/captcha/config?' in url:
                return {'active': False}
            if '/dl?' in url:
                return {'success': True, 'id': 'Job123'}
            return {'status': 'done', 'service': 'qobuz'}
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', response), \
                patch.object(self.dd, '_save_download', side_effect=OSError('Interrupted transfer')):
            with self.assertRaises(OSError):
                self.dd.download(11)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(observed[1]).query)
        self.assertEqual(query['url'], ['https://play.qobuz.com/track/11'])
        self.assertEqual(query['donotshare'], ['true'])
        self.assertNotIn('external', query)
        self.assertNotIn('format', query)
        self.assertEqual(json.loads((self.root / 'audio/qobuz/11/job.json').read_text())['jobUrl'],
                         'https://eu.doubledouble.top/dl/Job123')
        observed.clear()
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', response), \
                patch.object(self.dd, '_save_download', return_value={'status': 'saved'}):
            self.dd.download(11)
        self.assertEqual(observed, ['https://eu.doubledouble.top/dl/Job123'])
        self.assertFalse((self.root / 'audio/qobuz/11/job.json').exists())

    def test_captcha_requires_action_without_submission_or_fake_success(self):
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', return_value={'active': True}):
            with self.assertRaises(self.dd.CaptchaRequired):
                self.dd.download(11)
        self.assertFalse(list(self.root.rglob('job.json')))

    def test_concurrent_same_track_rejects_before_submission_and_retains_exact_resume(self):
        from stemwise_engine.common import ProcessLockBusyError
        entered, release = threading.Event(), threading.Event()
        submitted, observed = [], []
        def response(url, timeout):
            observed.append(url)
            if '/captcha/config?' in url:
                if not entered.is_set():
                    entered.set()
                    if not release.wait(10):
                        raise TimeoutError('Fixture submission was not released')
                return {'active': False}
            if '/dl?' in url:
                identity = 'Job' + str(len(submitted) + 1)
                submitted.append(identity)
                return {'success': True, 'id': identity}
            return {'status': 'done'}
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', response), \
                patch.object(self.dd, '_save_download', side_effect=OSError('Interrupted transfer')):
            with ThreadPoolExecutor(max_workers=1) as worker:
                first = worker.submit(self.dd.download, 11)
                self.assertTrue(entered.wait(5))
                try:
                    with self.assertRaises(ProcessLockBusyError):
                        self.dd.download(11)
                finally:
                    release.set()
                with self.assertRaisesRegex(OSError, 'Interrupted transfer'):
                    first.result(timeout=10)
        pending = self.root / 'audio/qobuz/11/job.json'
        self.assertEqual(submitted, ['Job1'])
        self.assertEqual(json.loads(pending.read_bytes())['jobUrl'],
                         'https://eu.doubledouble.top/dl/Job1')
        observed.clear()
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', response), \
                patch.object(self.dd, '_save_download', return_value={'status': 'saved'}):
            self.dd.download(11)
        self.assertEqual(observed, ['https://eu.doubledouble.top/dl/Job1'])
        self.assertEqual(submitted, ['Job1'])
        self.assertFalse(pending.exists())

    def test_rejected_tracks_never_submit_and_wrong_resume_binding_fails(self):
        base = self.root / 'audio/qobuz/11'
        base.mkdir(parents=True)
        (base / 'download.json').write_text(json.dumps({'status': 'owner_rejected'}))
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', side_effect=AssertionError('network forbidden')):
            with self.assertRaisesRegex(ValueError, 'owner rejected'):
                self.dd.download(11)
        (base / 'download.json').unlink()
        (base / 'job.json').write_text(json.dumps({'trackId': 22, 'jobUrl': 'https://eu.doubledouble.top/dl/Job123'}))
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', side_effect=AssertionError('network forbidden')):
            with self.assertRaisesRegex(ValueError, 'binding'):
                self.dd.download(11)

    def test_pending_job_deadline_preserves_resume_and_never_calls_save(self):
        clock = iter([0, 0, 2])
        with patch.object(self.dd, 'ROOT', self.root), patch.object(self.dd, '_read_json', return_value={'status': 'downloading'}), \
                patch.object(self.dd.time, 'monotonic', side_effect=lambda: next(clock, 2)), \
                patch.object(self.dd.time, 'sleep'), patch.object(self.dd, '_save_download', side_effect=AssertionError('not complete')):
            with self.assertRaises(TimeoutError):
                self.dd.download(11, job_url='https://eu.doubledouble.top/dl/Job123', timeout_seconds=1)
        self.assertTrue((self.root / 'audio/qobuz/11/job.json').is_file())

    def test_source_urls_cannot_select_another_host_or_playlist(self):
        self.assertEqual(self.audio.youtube_id('https://music.youtube.com/watch?v=2yHJs914M4g'), '2yHJs914M4g')
        self.assertEqual(self.audio.qobuz_id('https://play.qobuz.com/track/11'), 11)
        self.assertEqual(self.audio.qobuz_id('9007199254740991'), 9007199254740991)
        self.assertEqual(self.audio.youtube_id('https://m.youtube.com/watch?v=2yHJs914M4g'), '2yHJs914M4g')
        self.assertEqual(self.audio.youtube_id('https://youtube.com/shorts/2yHJs914M4g'), '2yHJs914M4g')
        from stemwise_engine.recording_source import registration_details
        for value in ('9007199254740992', 'https://play.qobuz.com/track/9007199254740992'):
            with self.assertRaises(ValueError): self.audio.qobuz_id(value)
        with self.assertRaises(ValueError): registration_details(9007199254740992, '2yHJs914M4g', 'Song', 'Artist')
        from stemwise_media.save_download import save_download
        with patch.object(self.dd,'_read_json',side_effect=AssertionError('invalid ID must not reach a provider')):
            with self.assertRaises(ValueError): self.dd.download(9007199254740992)
            with self.assertRaises(ValueError): save_download(9007199254740992,'https://eu.doubledouble.top/dl/Exact')
        for url in ['https://youtube.com.evil/watch?v=2yHJs914M4g', 'https://youtube.com/playlist?list=x', 'https://youtu.be/2yHJs914M4g/extra']:
            with self.assertRaises(ValueError): self.audio.youtube_id(url)
        for url in ['https://play.qobuz.com.evil/track/11', 'https://play.qobuz.com/album/11', '-11']:
            with self.assertRaises(ValueError): self.audio.qobuz_id(url)

    def test_direct_comparison_cancellation_and_deadline_cover_decoder_work(self):
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        from stemwise_media.bounded_pcm import check_budget
        reference, candidate = self.root/'reference', self.root/'candidate'
        for path in (reference, candidate): path.touch()
        with self.assertRaises(ProviderCancelled):
            self.audio.compare_files(reference, candidate, cancelled=lambda: True)
        def expired(*args, **kwargs):
            import time
            time.sleep(.02)
            check_budget()
        with patch.object(self.audio, 'probe', return_value={'duration':1, 'channels':2}), \
                patch.object(self.audio, 'decode', side_effect=expired):
            with self.assertRaises(ProviderDeadline):
                self.audio.compare_files(reference, candidate, seconds=.01)

    def make_audio(self, name, samples, codec):
        path = self.root / name
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'f32le', '-ar', '48000', '-ac', '2', '-i', '-',
                        '-c:a', codec, *(['-b:a', '160k'] if codec == 'libopus' else []), str(path)],
                       input=samples.astype('<f4').tobytes(), check=True, capture_output=True)
        return path

    def test_real_codec_comparison_accepts_recording_and_exposes_edited_or_different_audio(self):
        rng = np.random.default_rng(142)
        samples = signal.resample_poly(rng.normal(0, .10, (32 * 8000, 2)), 6, 1)
        flac = self.make_audio('native.flac', samples, 'flac')
        opus = self.make_audio('source.webm', samples, 'libopus')
        result = self.audio.compare_files(opus, flac)
        self.assertTrue(result['sameRecordingSupported'])
        self.assertEqual(result['referenceAudio']['codec_name'], 'opus')
        self.assertEqual(result['candidateAudio']['codec_name'], 'flac')
        self.assertFalse(result['losslessHistoryEstablished'])
        phase = self.make_audio('phase.flac', -samples, 'flac')
        alternate = self.audio.compare_files(opus, phase)
        self.assertTrue(alternate['sameRecordingSupported'])
        self.assertEqual(alternate['classification'], 'same_performance_with_differences')
        self.assertFalse(alternate['fullContentSupported'])
        self.assertNotEqual(alternate['waveform']['classification'], 'strong_recording_match')
        modified = samples.copy()
        modified[12 * 48000:18 * 48000, 1] = rng.normal(0, .15, 6 * 48000)
        changed = self.make_audio('changed.flac', modified, 'flac')
        self.assertFalse(self.audio.compare_files(opus, changed)['sameRecordingSupported'])
        intro = self.make_audio('extra.flac', np.vstack([rng.normal(0, .1, (48000, 2)), samples]), 'flac')
        extra = self.audio.compare_files(opus, intro)
        self.assertTrue(extra['sameRecordingSupported'])
        self.assertFalse(extra['fullContentSupported'])
        self.assertNotEqual(extra['classification'], 'strong_recording_match')

    def test_compare_cli_does_not_turn_nonmatch_into_success(self):
        with patch.object(self.audio, 'compare_files', return_value={'sameRecordingSupported': False}), \
                patch('sys.stdout', new_callable=io.StringIO):
            self.assertEqual(self.audio.main(['compare', 'a.flac', 'b.webm']), 1)

    def test_youtube_cache_requires_bound_bytes_and_failed_repair_preserves_them(self):
        import hashlib
        batch = self.audio.batch
        base = self.root/'audio/youtube/abcdefghijk'
        base.mkdir(parents=True)
        source = base/'source.webm'
        metadata = {'videoId':'abcdefghijk', 'file':source.name, 'status':'saved',
                    'bytes':8, 'sha256':hashlib.sha256(b'original').hexdigest()}
        for fault in ('intact', 'truncated', 'changed', 'unbound'):
            with self.subTest(fault=fault):
                payload = b'orig' if fault == 'truncated' else b'changed!' if fault == 'changed' else b'original'
                source.write_bytes(payload)
                receipt = base/'download.json'
                receipt.write_text(json.dumps({k:v for k,v in metadata.items()
                    if fault != 'unbound' or k not in ('bytes','sha256')}), encoding='utf-8')
                before = receipt.read_bytes()
                with patch.object(batch, 'ROOT', self.root), patch('stemwise_engine.youtube.extract_youtube_info',
                        side_effect=RuntimeError('repair unavailable')) as provider:
                    result = batch.download_reference({'position':1, 'videoId':'abcdefghijk'})
                self.assertEqual(result['status'], 'already_saved' if fault == 'intact' else 'failed')
                self.assertEqual(provider.call_count, 0 if fault == 'intact' else 1)
                self.assertEqual(source.read_bytes(), payload)
                self.assertEqual(receipt.read_bytes(), before)

    def test_youtube_route_identity_conflicts_reject_before_registration(self):
        a, b = 'aaaaaaaaaaa', 'bbbbbbbbbbb'
        for url in (f'https://youtube.com/watch?v={a}', f'https://youtu.be/{a}',
                    *(f'https://youtube.com/{route}/{a}?v={a}' for route in ('shorts','embed','live'))):
            self.assertEqual(self.audio.youtube_id(url), a)
        for url in (f'https://youtube.com/shorts/{a}?v={b}', f'https://youtu.be/{a}?v={b}',
                    f'https://youtube.com/watch?v={a}&v={a}', f'https://youtube.com/watch?v={a}&v={b}',
                    f'https://youtube.com/playlist?v={a}', 'https://youtube.com/playlist?list=x'):
            with self.subTest(url=url), patch('stemwise_engine.recording_source.register_input') as register, \
                    patch.object(self.audio.batch, 'retire_youtube_audio') as retire, patch('sys.stderr', new_callable=io.StringIO):
                self.assertEqual(self.audio.main(['register','--qobuz','11','--youtube',url,
                    '--title','Song','--artist','Artist','--replace-youtube']), 2)
                register.assert_not_called()
                retire.assert_not_called()

    def test_initial_partial_http_response_cannot_publish_completed_download(self):
        from stemwise_media import save_download, transfer_budget
        class Response(io.BytesIO):
            def __init__(self, payload, status, headers):
                super().__init__(payload)
                self.status, self.headers = status, headers
        job = {'status':'done','service':'qobuz','url':'/temp/complete/Exact.flac',
            'current':{'name':'Exact','artist':'Artist','cover':'/cover?url=https%3A%2F%2Fplay.qobuz.com%2Ftrack%2F11'}}
        base = self.root/'audio/qobuz/11'
        base.mkdir(parents=True)
        def request(url, budget, *, headers, partial=None):
            if partial is None: return json.dumps(job).encode()
            return transfer_budget.http_worker({'url':url, 'headers':headers, 'partial':str(partial),
                'maxBytes':budget.max_bytes, 'seconds':budget.remaining()})
        with patch.object(save_download,'http_request',side_effect=request), patch.object(
                urllib.request,'urlopen',side_effect=lambda *a,**kw: Response(b'fLaCjunk',206,
                    {'Content-Length':'8','Content-Range':'bytes 0-7/4096'})):
            with self.assertRaises(ValueError):
                save_download._save_download(11,'https://eu.doubledouble.top/dl/Exact',base)
        self.assertFalse((base/'download.json').exists())
        self.assertFalse(list(base.glob('*.flac')))
        for headers in ({'Content-Length':'8','Content-Range':'bytes 1-8/9'},
                        {'Content-Length':'4096','Content-Range':'bytes 0-4095/4096'}):
            with self.subTest(headers=headers), patch.object(save_download,'http_request',side_effect=request), patch.object(
                    urllib.request,'urlopen',side_effect=lambda *a,**kw: Response(b'fLaCjunk',206,headers)):
                with self.assertRaises(ValueError):
                    save_download._save_download(11,'https://eu.doubledouble.top/dl/Exact',base)
            self.assertFalse((base/'download.json').exists())
            self.assertFalse(list(base.glob('*.flac')))

    def test_http_full_and_exact_continuation_verify_completed_bytes(self):
        import hashlib
        from stemwise_media import transfer_budget
        class Response(io.BytesIO):
            def __init__(self, payload, status, headers):
                super().__init__(payload)
                self.status, self.headers = status, headers
        payload = b'fLaCcomplete native bytes'
        for offset in (0, 8):
            with self.subTest(offset=offset):
                path = self.root/f'transfer-{offset}.partial'
                if offset: path.write_bytes(payload[:offset])
                if offset:
                    path.with_name(path.name+'.http.json').write_text(json.dumps({'url':'https://example.test/audio',
                        'etag':'"complete"','length':len(payload),'bytes':offset,
                        'sha256':hashlib.sha256(payload[:offset]).hexdigest()}),encoding='utf-8')
                headers = {'Content-Length':str(len(payload)-offset),'ETag':'"complete"'}
                if offset: headers['Content-Range'] = f'bytes {offset}-{len(payload)-1}/{len(payload)}'
                with patch.object(urllib.request,'urlopen',side_effect=lambda *a,**kw: Response(
                        payload[offset:],206 if offset else 200,headers)):
                    transfer_budget.http_worker({'url':'https://example.test/audio','headers':{},
                        'partial':str(path),'maxBytes':1024,'seconds':10})
                self.assertEqual(path.stat().st_size,len(payload))
                self.assertEqual(hashlib.sha256(path.read_bytes()).digest(),hashlib.sha256(payload).digest())

    def test_retirement_removes_only_current_index_bound_links_and_replays_interruption(self):
        self.assert_retirement_recovery('index')

    def test_retirement_replays_death_before_metadata_commit(self):
        self.assert_retirement_recovery('metadata')

    def test_retirement_export_replay_requires_exact_replacement(self):
        batch = self.audio.batch
        for fault in ('missing', 'changed', 'io', 'valid'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=PROJECT/'Test suite/work') as temporary:
                root = Path(temporary)
                base = root/'audio/youtube/abcdefghijk'
                base.mkdir(parents=True)
                source = base/'source.webm'
                source.write_bytes(b'last original')
                receipt = base/'download.json'
                receipt.write_bytes(b'{"videoId":"abcdefghijk","file":"source.webm"}')
                before = receipt.read_bytes()
                replacement = root/'audio/qobuz/11/native.flac'
                replacement.parent.mkdir(parents=True)
                replacement.write_bytes(flac_bytes())
                write_download(replacement.parent, {'qobuzTrackId':11, 'file':replacement.name})
                write = batch.atomic_write_json
                def interrupt(path, value):
                    if path == receipt: raise SystemExit('interrupted before deletion')
                    return write(path, value)
                with patch.object(batch, 'ROOT', root), patch.object(batch, 'probe', return_value={'codec_name':'flac'}):
                    with patch.object(batch, 'atomic_write_json', side_effect=interrupt), self.assertRaises(SystemExit):
                        batch.retire_youtube_audio('abcdefghijk', 11)
                    if fault == 'missing': replacement.unlink()
                    if fault == 'changed': replacement.write_bytes(replacement.read_bytes()[:-1]+b'X')
                    with patch.object(batch, 'file_sha256', side_effect=OSError('unreadable')) if fault == 'io' else patch.object(batch, 'probe', return_value={'codec_name':'flac'}):
                        if fault == 'valid':
                            batch.export_library({'rows':[]})
                            batch.export_library({'rows':[]})
                        else:
                            with self.assertRaises((ValueError, OSError)):
                                batch.export_library({'rows':[]})
                    if fault != 'valid':
                        self.assertEqual(source.read_bytes(), b'last original')
                        self.assertEqual(receipt.read_bytes(), before)
                        self.assertTrue((base/'retirement.pending.json').exists())
                    else:
                        self.assertFalse(source.exists())
                        self.assertFalse((base/'retirement.pending.json').exists())

    def assert_retirement_recovery(self, boundary):
        import hashlib
        import os
        batch = self.audio.batch
        base = self.root / 'audio/youtube/abcdefghijk'
        base.mkdir(parents=True)
        source = base / 'source.webm'
        source.write_bytes(b'retained original')
        (base / 'download.json').write_text(json.dumps({'videoId': 'abcdefghijk', 'file': source.name}))
        flac = self.root / 'replacement.flac'
        flac.write_bytes(b'approved replacement')
        output = self.root / 'comparison-results'
        folder = output / 'reviews/abcdefghijk'
        folder.mkdir(parents=True)
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        link = folder / f'01-YouTube-{digest}.webm'
        os.link(source, link)
        unrelated = folder / '01-YouTube-unrelated.webm'
        unrelated.write_bytes(b'owner file')
        index = output / 'review-index.json'
        retained_row = {'status': 'matched', 'audio': [{'file': 'reviews/other/02-Qobuz.flac', 'sha256': 'f'*64}]}
        index.write_text(json.dumps({'abcdefghijk': {'status': 'matched', 'directory': 'reviews/abcdefghijk',
            'audio': [{'file': link.relative_to(output).as_posix(), 'sha256': digest},
                      {'file': 'reviews/abcdefghijk/02-Qobuz.flac', 'sha256': 'e'*64}]}, 'other': retained_row}))
        original_write = batch.atomic_write_json
        def interrupt(target, value, *args, **kwargs):
            if target == (index if boundary == 'index' else base / 'download.json'):
                raise SystemExit('death before review index publication')
            return original_write(target, value, *args, **kwargs)
        with patch.object(batch, 'ROOT', self.root), patch.object(batch, 'qobuz_file', return_value=flac), \
                patch.object(batch, 'probe', return_value={'codec_name': 'flac'}):
            with patch.object(batch, 'atomic_write_json', side_effect=interrupt), self.assertRaises(SystemExit):
                batch.retire_youtube_audio('abcdefghijk', 11)
            batch.retire_youtube_audio('abcdefghijk', 11)
        self.assertFalse(source.exists())
        self.assertFalse(link.exists())
        self.assertEqual(unrelated.read_bytes(), b'owner file')
        current = json.loads(index.read_bytes())
        self.assertEqual(current['other'], retained_row)
        self.assertEqual(current['abcdefghijk']['audio'], [{'file': 'reviews/abcdefghijk/02-Qobuz.flac', 'sha256': 'e'*64}])
        self.assertEqual(flac.read_bytes(), b'approved replacement')
        self.assertFalse((base / 'retirement.pending.json').exists())

    def test_approved_replacement_blocks_both_audio_download_paths_but_allows_youtube_info(self):
        batch = self.audio.batch
        import stemwise_engine.youtube as youtube
        base = self.root / 'audio/youtube/abcdefghijk'
        base.mkdir(parents=True)
        flac = self.root / 'audio/qobuz/11/song.flac'
        flac.parent.mkdir(parents=True)
        self.make_audio('native.flac', np.zeros((48000, 2)), 'flac').replace(flac)
        write_download(flac.parent, {'qobuzTrackId': 11, 'file': flac.name})
        (base / 'source.webm').write_bytes(b'old Opus')
        (base / 'download.json').write_text(json.dumps({'videoId': 'abcdefghijk', 'file': 'source.webm', 'title': 'YouTube title'}))
        with patch.object(batch, 'ROOT', self.root):
            batch.retire_youtube_audio('abcdefghijk', 11)
            self.assertFalse((base / 'source.webm').exists())
            self.assertIsNone(batch.source_file('abcdefghijk'))
            # Even losing the replacement must not authorize a silent YouTube fallback.
            flac.unlink()
            with patch.object(youtube, '_ydl', side_effect=AssertionError('Audio network forbidden')):
                with self.assertRaisesRegex(ValueError, 'Approved FLAC'):
                    batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'})
                with self.assertRaisesRegex(ValueError, 'Approved FLAC'):
                    youtube.download_analysis_audio('abcdefghijk', self.root / 'temp', timeout_seconds=1)
                with self.assertRaisesRegex(ValueError, 'Approved FLAC'):
                    youtube._download_analysis_audio_attempt('abcdefghijk', str(self.root / 'source.%(ext)s'),
                        'bestaudio/best', None, self.root / 'info.json')
                selected = self.root / 'comparison-results/library-files.json'
                selected.parent.mkdir(exist_ok=True)
                selected.write_text(json.dumps({'playlist': [{'videoId': 'abcdefghijk', 'source': 'qobuz'}]}))
                metadata = json.loads((base / 'download.json').read_text())
                metadata['status'] = 'saved'
                (base / 'download.json').write_text(json.dumps(metadata))
                with self.assertRaisesRegex(ValueError, 'Approved FLAC'):
                    batch.download_reference({'position': 1, 'videoId': 'abcdefghijk'})
            self.assertFalse((self.root / 'temp').exists())
            self.assertEqual(json.loads((base / 'download.json').read_text())['title'], 'YouTube title')
        with patch.object(youtube, 'extract_youtube_info', return_value={
                'id': 'abcdefghijk', 'title': 'Current YouTube title'}) as extract:
            self.assertEqual(youtube.fetch_video_info('abcdefghijk')['title'], 'Current YouTube title')
        self.assertEqual(extract.call_args.args[0], youtube.canonical_url('abcdefghijk'))
        self.assertTrue(extract.call_args.args[1]['skip_download'])

    def test_native_opus_upgrade_preserves_aac_and_rejects_an_aac_substitute(self):
        batch = self.audio.batch
        base = self.root / 'audio/youtube/2yHJs914M4g'
        base.mkdir(parents=True)
        samples = np.random.default_rng(221).normal(0, .08, (48000, 2))
        old = self.make_audio('old.m4a', samples, 'aac')
        original = old.read_bytes()
        (base / 'source.m4a').write_bytes(original)
        metadata = {'videoId': '2yHJs914M4g', 'file': 'source.m4a', 'codec': 'aac', 'status': 'saved'}
        (base / 'download.json').write_text(json.dumps(metadata))
        selected = self.make_audio('new.webm', samples, 'libopus')
        def extract(url, options, *, download):
            self.assertEqual(url, 'https://www.youtube.com/watch?v=2yHJs914M4g')
            self.assertTrue(download)
            self.assertEqual(options['format'], 'bestaudio[acodec=opus]')
            (base / 'source-opus.webm').write_bytes(selected.read_bytes())
            return {'id': '2yHJs914M4g', 'acodec': 'opus', 'format_id': '251',
                    '_filename': str(base / 'source-opus.webm')}
        with patch.object(batch, 'ROOT', self.root), patch('stemwise_engine.youtube.extract_youtube_info', side_effect=extract):
            saved = batch.download_reference({'position': 1, 'videoId': '2yHJs914M4g'}, 'bestaudio[acodec=opus]')
        self.assertEqual(saved['status'], 'saved')
        value = json.loads((base / 'download.json').read_text())
        self.assertEqual(value['file'], 'source-opus.webm')
        self.assertEqual(value['additionalSourceFiles'][0]['file'], 'source.m4a')
        self.assertEqual((base / 'source.m4a').read_bytes(), original)
        (base / 'download.json').write_text(json.dumps(metadata))
        selected = old
        with patch.object(batch, 'ROOT', self.root), patch('stemwise_engine.youtube.extract_youtube_info', side_effect=extract):
            rejected = batch.download_reference({'position': 1, 'videoId': '2yHJs914M4g'}, 'bestaudio[acodec=opus]')
        self.assertEqual(rejected['status'], 'failed')
        self.assertEqual(json.loads((base / 'download.json').read_text()), metadata)
        self.assertEqual((base / 'source.m4a').read_bytes(), original)

    def test_future_registration_retires_opus_only_when_matching_replacement_is_approved(self):
        flac = self.root / 'audio/qobuz/11/song.flac'
        flac.parent.mkdir(parents=True)
        self.make_audio('native.flac', np.zeros((48000, 2)), 'flac').replace(flac)
        write_download(flac.parent, {'qobuzTrackId': 11, 'file': flac.name})
        source = self.root / 'audio/youtube/abcdefghijk/source.webm'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'old Opus')
        (source.parent / 'download.json').write_text(json.dumps({'videoId': 'abcdefghijk', 'file': source.name}))
        arguments = ['register', '--qobuz', '11', '--youtube', 'abcdefghijk', '--title', 'Song', '--artist', 'Artist']
        with patch.object(self.audio, 'ROOT', self.root), patch.object(self.audio.batch, 'ROOT', self.root), \
                patch('sys.stdout', new_callable=io.StringIO):
            self.assertEqual(self.audio.main(arguments), 0)
            self.assertTrue(source.exists())
            self.assertEqual(self.audio.main(arguments + ['--replace-youtube']), 0)
            self.assertFalse(source.exists())
            self.assertEqual(self.audio.main(arguments + ['--replace-youtube']), 0)
            self.assertTrue(flac.exists())


if __name__ == '__main__':
    unittest.main()
