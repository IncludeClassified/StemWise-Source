"""Private source acquisition must stage caption text, never a transport playlist."""
from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SRC = Path(__file__).resolve().parents[3] / "PC side/stemwise-playlist-builder/src"
import stemwise_engine.source_acquisition as source_acquisition

VIDEO_ID = "a1B2c3D4e5F"
URL = "https://www.youtube.com/api/timedtext?v=" + VIDEO_ID + "&signature=private-fixture"
SEGMENT = URL + "&segment=1"
VTT = b"WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nFixture caption\n"
JSON3 = json.dumps({"events": [{"segs": [{"utf8": "Fixture caption"}]}]}).encode()
PLAYLIST = (
    "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n"
    "#EXT-X-TARGETDURATION:10\n#EXTINF:10,\n" + SEGMENT + "\n#EXT-X-ENDLIST\n"
).encode()


class CaptionCandidateTests(unittest.TestCase):
    def test_later_optional_caption_expiry_preserves_first_candidate_and_parent_control(self):
        from stemwise_engine.provider_operations import (current_budget, provider_operation,
            ProviderCancelled, ProviderDeadline)
        root = Path(__file__).resolve().parents[3]/'Test suite/work'
        for parent_failure in (None, ProviderCancelled, ProviderDeadline):
            with self.subTest(parent_failure=parent_failure), tempfile.TemporaryDirectory(dir=root) as name:
                info = {'id': VIDEO_ID, 'subtitles': {language: [{'ext': 'vtt', 'url': URL}]
                                                     for language in ('en', 'ar')}}
                calls = []
                def download(choice):
                    calls.append(choice)
                    if len(calls) == 2:
                        if parent_failure is not None:
                            raise parent_failure('parent stopped')
                        current_budget().deadline = current_budget().clock()-1
                        current_budget().remaining()
                    return VTT
                with provider_operation(120), patch.object(source_acquisition, '_download_subtitle', side_effect=download):
                    if parent_failure is not None:
                        with self.assertRaises(parent_failure):
                            source_acquisition.collect_normal_youtube_candidates(info, Path(name), video_id=VIDEO_ID)
                    else:
                        result = source_acquisition.collect_normal_youtube_candidates(info, Path(name), video_id=VIDEO_ID)
                        self.assertEqual(result['candidateCount'], 1)
                        self.assertEqual(result['records'][1]['status'], 'unavailable')
                        self.assertEqual(Path(result['records'][0]['path']).read_bytes(), VTT)

    def test_invisible_vtt_controls_fall_back_and_are_not_reusable_evidence(self):
        for cue in ('<c.green></c>', '<00:00:01.500>', ' <c> &nbsp; </c> '):
            with self.subTest(cue=cue), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                payload = b'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n' + cue.encode() + b'\n'
                result, calls = self.collect(directory,
                    {URL + '&fmt=vtt': payload, URL + '&fmt=json3': JSON3}, ('vtt','json3'))
                self.assertEqual(calls, [URL + '&fmt=vtt', URL + '&fmt=json3'])
                self.assertEqual(result['usableLyricCandidateCount'], 1)
                self.assertEqual(Path(result['records'][0]['path']).read_bytes(), JSON3)
                path = directory / 'invisible.vtt'
                path.write_bytes(payload)
                self.assertFalse(source_acquisition.source_record_has_usable_evidence({'normalYouTube': {'records': [
                    {'type':'manual-subtitle', 'format':'vtt', 'lyricUse':'potential-body',
                     'path':str(path), 'sha256':hashlib.sha256(payload).hexdigest()}]}},
                    draft_present=False, job_root=directory, package_video_id=VIDEO_ID))
        self.assertTrue(source_acquisition._subtitle_has_text(
            b'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<c.green>Visible words</c>\n', 'vtt'))

    def collect(self, directory, responses, formats=("vtt",)):
        calls = []

        def fetch(url, **options):
            calls.append(url)
            value = responses[url]
            if isinstance(value, Exception):
                raise value
            return value

        info = {"id": VIDEO_ID, "subtitles": {"en": [
            {"ext": extension, "url": URL + "&fmt=" + extension} for extension in formats
        ]}}
        with patch('stemwise_engine.source_acquisition.fetch_bytes', side_effect=fetch):
            result = source_acquisition.collect_normal_youtube_candidates(info, directory, video_id=VIDEO_ID)
        return result, calls

    def test_single_complete_playlist_stages_its_caption_body(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            result, calls = self.collect(directory, {URL + "&fmt=vtt": PLAYLIST, SEGMENT: VTT})
            self.assertEqual((result["candidateCount"], result["usableLyricCandidateCount"]), (1, 1))
            record = result["records"][0]
            self.assertEqual(Path(record["path"]).read_bytes(), VTT)
            self.assertEqual(record["sha256"], hashlib.sha256(VTT).hexdigest())
            self.assertEqual(calls, [URL + "&fmt=vtt", SEGMENT])
            self.assertNotIn("private-fixture", json.dumps(result))

    def test_unsupported_playlists_fall_back_without_fetching_their_uris(self):
        rejected = (
            PLAYLIST.replace(b"#EXT-X-ENDLIST\n", b""),
            PLAYLIST.replace(b"#EXTINF:10,", b'#EXT-X-KEY:METHOD=AES-128,URI="https://invalid.test/key"\n#EXTINF:10,'),
            PLAYLIST.replace(b"#EXT-X-ENDLIST", ("#EXTINF:10,\n" + SEGMENT + "&extra=1\n#EXT-X-ENDLIST").encode()),
            PLAYLIST.replace(SEGMENT.encode(), b"https://invalid.test/api/timedtext"),
            PLAYLIST.replace(SEGMENT.encode(), b"https://www.youtube.com/other"),
            PLAYLIST.replace(VIDEO_ID.encode(), b"z9Y8x7W6v5U"),
            b"<html>Not subtitles</html>",
            b"WEBVTT\n\n",
        )
        for payload in rejected:
            with self.subTest(payload=payload[:30]), tempfile.TemporaryDirectory() as name:
                directory = Path(name)
                result, calls = self.collect(directory, {
                    URL + "&fmt=vtt": payload, URL + "&fmt=json3": JSON3,
                }, ("vtt", "json3"))
                record = result["records"][0]
                self.assertEqual(record.get("format"), "json3")
                self.assertEqual(Path(record["path"]).read_bytes(), JSON3)
                self.assertEqual(calls, [URL + "&fmt=vtt", URL + "&fmt=json3"])
                self.assertFalse((directory / "normal-youtube-manual-subtitle.en.vtt").exists())

    def test_nested_playlist_is_not_staged_and_existing_files_are_preserved(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            existing = directory / "normal-youtube-manual-subtitle.en.vtt"
            existing.write_bytes(VTT)
            result, calls = self.collect(directory, {URL + "&fmt=vtt": PLAYLIST, SEGMENT: PLAYLIST})
            self.assertEqual((result["candidateCount"], result["usableLyricCandidateCount"]), (0, 0))
            self.assertEqual(result["records"][0]["status"], "unavailable")
            self.assertEqual(calls, [URL + "&fmt=vtt", SEGMENT])
            self.assertEqual(existing.read_bytes(), VTT)

    def test_direct_supported_caption_formats_keep_their_original_bytes(self):
        payloads = {
            "vtt": b"\xef\xbb\xbf" + VTT,
            "json3": JSON3,
            "ttml": b'<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p>Fixture <span>caption</span></p></div></body></tt>',
            "srv3": b'<timedtext format="3"><body><p t="1000"><s>Fixture caption</s></p></body></timedtext>',
        }
        for extension, payload in payloads.items():
            with self.subTest(format=extension), tempfile.TemporaryDirectory() as name:
                result, calls = self.collect(Path(name), {URL + "&fmt=" + extension: payload}, (extension,))
                self.assertEqual(result["usableLyricCandidateCount"], 1)
                self.assertEqual(Path(result["records"][0]["path"]).read_bytes(), payload)
                self.assertEqual(len(calls), 1)

    def test_provider_failure_uses_next_format_without_exposing_the_url(self):
        with tempfile.TemporaryDirectory() as name:
            result, calls = self.collect(Path(name), {
                URL + "&fmt=vtt": RuntimeError("unavailable " + URL),
                URL + "&fmt=json3": JSON3,
            }, ("vtt", "json3"))
            self.assertEqual(result["usableLyricCandidateCount"], 1)
            self.assertEqual(result["records"][0].get("format"), "json3")
            self.assertEqual(len(calls), 2)
            self.assertNotIn("private-fixture", json.dumps(result))

    def test_empty_or_unrelated_documents_are_not_usable_captions(self):
        invalid = {
            "vtt": b"WEBVTT\n\nNOTE\n00:00:01.000 --> 00:00:02.000\ncomment only\n",
            "json3": b'{"events":[{"segs":[{"utf8":" "}]}]}',
            "ttml": b"<html><p>Not captions</p></html>",
            "srv3": b"<timedtext><body/></timedtext>",
        }
        for extension, payload in invalid.items():
            with self.subTest(format=extension), tempfile.TemporaryDirectory() as name:
                result, _ = self.collect(Path(name), {URL + "&fmt=" + extension: payload}, (extension,))
                self.assertEqual(result["usableLyricCandidateCount"], 0)
                self.assertEqual(list(Path(name).iterdir()), [])
                candidate = Path(name) / ("retained." + extension)
                candidate.write_bytes(payload)
                record = {"normalYouTube": {"records": [{
                    "path": str(candidate), "format": extension, "type": "manual-subtitle",
                    "sha256": hashlib.sha256(payload).hexdigest(), "lyricUse": "potential-body",
                }]}}
                self.assertFalse(source_acquisition.source_record_has_usable_evidence(
                    record, draft_present=False, job_root=Path(name), package_video_id=VIDEO_ID,
                ))
                del record["normalYouTube"]["records"][0]["type"]
                self.assertFalse(source_acquisition.source_record_has_usable_evidence(
                    record, draft_present=False, job_root=Path(name), package_video_id=VIDEO_ID,
                ))

    def test_retained_playlist_is_unusable_even_with_a_matching_hash(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            candidate = root / "caption.vtt"
            candidate.write_bytes(PLAYLIST)
            caption_record = {"type": "automatic-caption", "format": "vtt", "path": str(candidate),
                              "sha256": hashlib.sha256(PLAYLIST).hexdigest(), "lyricUse": "potential-body"}
            record = {"normalYouTube": {"records": [caption_record]}}
            usable = lambda: source_acquisition.source_record_has_usable_evidence(
                record, draft_present=False, job_root=root, package_video_id=VIDEO_ID,
            )
            with patch('stemwise_engine.source_acquisition.fetch_bytes', side_effect=AssertionError("availability must not fetch")):
                self.assertFalse(usable())
                self.assertEqual(candidate.read_bytes(), PLAYLIST)
                manual = root / "manual.vtt"
                manual.write_bytes(VTT)
                record["normalYouTube"]["records"].append({
                    "type": "manual-subtitle", "format": "vtt", "path": str(manual),
                    "sha256": hashlib.sha256(VTT).hexdigest(), "lyricUse": "potential-body",
                })
                self.assertTrue(usable())
                self.assertEqual(candidate.read_bytes(), PLAYLIST)
                record["normalYouTube"]["records"].pop()
                candidate.write_bytes(VTT)
                self.assertFalse(usable())
                caption_record["sha256"] = hashlib.sha256(VTT).hexdigest()
                self.assertTrue(usable())

    def test_provider_size_failure_during_manifest_or_segment_never_stages_a_candidate(self):
        for segment in (False, True):
            with self.subTest(segment=segment), tempfile.TemporaryDirectory() as name:
                error = ValueError('provider-response-size-limit')
                responses = [PLAYLIST, error] if segment else [error]
                with patch('stemwise_engine.source_acquisition.fetch_bytes', side_effect=responses):
                    result = source_acquisition.collect_normal_youtube_candidates(
                        {'id': VIDEO_ID, 'subtitles': {'en': [{'ext': 'vtt', 'url': URL}]}},
                        Path(name), video_id=VIDEO_ID)
                self.assertEqual(result['candidateCount'], 0)
                self.assertEqual(result['records'][0]['reason'], 'ValueError')
                self.assertEqual(list(Path(name).iterdir()), [])
