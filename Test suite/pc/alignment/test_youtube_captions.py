"""Caption acquisition uses the production downloader configuration and real JSON3 parser."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

SRC = Path(__file__).resolve().parents[3] / "PC side/stemwise-playlist-builder/src"
import stemwise_engine.lyrics_alignment as lyrics_alignment
import stemwise_engine.youtube as youtube

VIDEO_ID = "a1B2c3D4e5F"


class YoutubeCaptionRouteTests(unittest.TestCase):
    def acquire(self, directory, *, manual=True, video_id=VIDEO_ID, fail=False, download_video_id=VIDEO_ID):
        calls = []
        def constructor(options):
            if options.get("js_runtimes") != {"deno": {"path": str(youtube.DENO_RUNTIME)}}:
                raise RuntimeError("caption acquisition bypassed canonical Deno")
            if options.get("plugin_dirs") != []:
                raise RuntimeError("caption acquisition allowed plugins")
            class Downloader:
                def __enter__(self): return self
                def __exit__(self, *_args): return False
                def extract_info(self, url, *, download):
                    calls.append((dict(options), url, download))
                    if fail: raise RuntimeError("provider unavailable")
                    if download:
                        directory.joinpath("caption.en.json3").write_text(json.dumps({"events": [{
                            "tStartMs": 1000, "dDurationMs": 500,
                            "segs": [{"utf8": "Hello", "tOffsetMs": 0}]
                        }]}))
                    return {"id": download_video_id if download else video_id, "subtitles": {"en": [{}]} if manual else {},
                            "automatic_captions": {"en-orig": [{}]}}
            return Downloader()
        def extract(arguments, **kwargs):
            self.assertEqual(arguments[0], '--youtube')
            request = json.loads(arguments[1])
            with youtube._ydl(request['options'], check_update=False) as downloader:
                return json.dumps(downloader.extract_info(request['url'], download=request['download'])).encode('utf-8')
        with mock.patch.dict(sys.modules, {"yt_dlp": SimpleNamespace(YoutubeDL=constructor)}), \
                mock.patch('stemwise_engine.provider_operations.ProviderBudget.run', side_effect=extract), \
                mock.patch('stemwise_engine.youtube.maybe_update_ytdlp') as update:
            result = lyrics_alignment.download_youtube_caption_evidence(VIDEO_ID, directory, language="en")
        return result, calls, update.call_args_list

    def test_manual_caption_uses_shared_runtime_for_lookup_and_download(self):
        with tempfile.TemporaryDirectory() as name:
            result, calls, updates = self.acquire(Path(name))
        words, evidence = result
        self.assertEqual([(word.word, word.start, word.end) for word in words], [("hello", 1.0, 1.5)])
        self.assertEqual(evidence, {"type": "youtube-caption", "captionKind": "manual", "captionLanguage": "en", "wordCount": 1})
        self.assertEqual([call[2] for call in calls], [False, True])
        self.assertEqual(len(updates), 1)
        self.assertTrue(all(call.kwargs["probe_url"] == youtube.canonical_url(VIDEO_ID) for call in updates))
        self.assertTrue(calls[1][0]["writesubtitles"])
        self.assertFalse(calls[1][0]["writeautomaticsub"])

    def test_automatic_caption_keeps_language_selection_and_skip_audio_behavior(self):
        with tempfile.TemporaryDirectory() as name:
            result, calls, _ = self.acquire(Path(name), manual=False)
        self.assertEqual(result[1]["captionLanguage"], "en-orig")
        self.assertEqual(result[1]["captionKind"], "automatic")
        self.assertTrue(all(call[0]["skip_download"] for call in calls))
        self.assertTrue(calls[1][0]["writeautomaticsub"])
        self.assertFalse(calls[1][0]["writesubtitles"])

    def test_wrong_video_does_not_delete_existing_caption_or_download_another(self):
        with tempfile.TemporaryDirectory() as name:
            directory=Path(name)
            existing=directory/"caption.old.json3"
            existing.write_bytes(b"retained")
            with self.assertRaisesRegex(ValueError, 'caption-response-identity-mismatch'):
                self.acquire(directory, video_id="z9Y8x7W6v5U")
            self.assertEqual(existing.read_bytes(), b"retained")

    def test_provider_failure_preserves_existing_files_and_is_not_an_empty_success(self):
        with tempfile.TemporaryDirectory() as name:
            directory=Path(name)
            existing=directory/"caption.old.json3"
            existing.write_bytes(b"retained")
            with self.assertRaisesRegex(RuntimeError, "provider unavailable"):
                self.acquire(directory, fail=True)
            self.assertEqual(existing.read_bytes(), b"retained")

    def test_second_request_identity_must_match_before_caption_parsing(self):
        with tempfile.TemporaryDirectory() as name:
            with self.assertRaisesRegex(ValueError, 'caption-response-identity-mismatch'):
                self.acquire(Path(name), download_video_id="z9Y8x7W6v5U")

    def test_identity_failure_does_not_negative_cache_a_corrected_retry(self):
        import stemwise_engine.lyrics_align_pc as lyrics_align_pc
        for phase in ('lookup', 'download'):
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                workspace = SimpleNamespace(caption_path=lambda _: root / 'cache.json',
                    job_dir=lambda _: root, workspace_root=root)
                attempts = []
                def download(video, directory, **kwargs):
                    bad = not attempts
                    attempts.append(bad)
                    return self.acquire(directory,
                        video_id='z9Y8x7W6v5U' if bad and phase == 'lookup' else VIDEO_ID,
                        download_video_id='z9Y8x7W6v5U' if bad and phase == 'download' else VIDEO_ID)[0]
                original = {'id':'original', 'kind':'original', 'language':'en', 'script':'Latn', 'format':'plain', 'text':'Hello'}
                with mock.patch.object(lyrics_align_pc, 'download_youtube_caption_evidence', side_effect=download):
                    self.assertIsNone(lyrics_align_pc._load_caption(video_id=VIDEO_ID, original=original,
                        workspace=workspace, temp_root=root / 'temp'))
                    self.assertFalse((root / 'cache.json').exists())
                    result = lyrics_align_pc._load_caption(video_id=VIDEO_ID, original=original,
                        workspace=workspace, temp_root=root / 'temp')
                    self.assertIsNotNone(result)
                    self.assertEqual(len(attempts), 2)


if __name__ == "__main__": unittest.main()
