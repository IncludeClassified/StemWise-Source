from __future__ import annotations

import io
import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.metadata_research as research  # noqa: E402
import stemwise_engine.contract as contract  # noqa: E402


VIDEO_ID = "a1B2c3D4e5F"
RECORDING_ID = "12345678-1234-1234-1234-123456789abc"
RELEASE_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"


def fallback(title: str = "Example Song", artists: list[str] | None = None) -> dict:
    return {
        "matchStatus": "youtube-fallback",
        "title": title,
        "artists": artists or ["Example Artist"],
        "sources": [{"name": "YouTube", "url": f"https://www.youtube.com/watch?v={VIDEO_ID}"}],
        "links": [{"type": "youtube", "url": f"https://www.youtube.com/watch?v={VIDEO_ID}"}],
    }


def recording(
    *,
    title: str = "Example Song",
    length: int = 240_000,
    score: int | None = 100,
    disambiguation: str = "",
    recording_id: str = RECORDING_ID,
) -> dict:
    value = {
        "id": recording_id,
        "title": title,
        "length": length,
        "artist-credit": [{"name": "Example Artist", "artist": {"name": "Example Artist"}}],
        "disambiguation": disambiguation,
    }
    if score is not None:
        value["score"] = score
    return value


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class FakeClient:
    def __init__(self, search: list[dict], lookup: dict | None = None):
        self.search = search
        self.lookup = lookup or (dict(search[0], score=None) if search else {})

    def search_recordings(self, title: str, artist: str) -> list[dict]:
        return self.search

    def lookup_recording(self, recording_id: str) -> dict:
        return self.lookup


class MatchingTests(unittest.TestCase):
    def test_metadata_optional_diagnostics_do_not_intercept_cancellation_or_retirement(self):
        from unittest.mock import patch
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        for error in (ProviderCancelled('cancelled'), ProviderDeadline('parent deadline'),
                      WorkerRetirementError('worker survived')):
            client = FakeClient([])
            with self.subTest(error=type(error).__name__), \
                    patch.object(client, 'search_recordings', side_effect=error), \
                    patch('stemwise_engine.private_diagnostics.retain_error') as capture:
                with self.assertRaises(type(error)) as raised:
                    research.enrich_metadata(fallback(), 240000, client)
                self.assertIs(raised.exception, error)
                capture.assert_not_called()

    def test_damaged_optional_diagnostic_preserves_both_metadata_fallbacks(self):
        from unittest.mock import patch
        from test_private_diagnostics import damaged_diagnostic_target
        from stemwise_engine.provider_operations import ProviderStageDeadline
        for kind in ('mismatch', 'directory'):
            for error in (RuntimeError('provider failed'), ProviderStageDeadline('optional stage expired')):
                with self.subTest(kind=kind, error=type(error).__name__), \
                        tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                    original = fallback()
                    client = FakeClient([])
                    with patch.object(client, 'search_recordings', side_effect=error), \
                            damaged_diagnostic_target(kind) as targets:
                        result, detail = research.enrich_metadata(original, 240000, client,
                            diagnostics_dir=Path(name))
                    self.assertEqual(result, original)
                    self.assertEqual(detail['status'], 'unavailable')
                    self.assertIn(type(error).__name__, detail['reason'])
                    self.assertEqual(detail['diagnosticError'], 'ValueError')
                    self.assertEqual(len(targets), 1)
                    self.assertEqual(targets[0].is_dir(), kind == 'directory')
                    if kind == 'mismatch':
                        self.assertEqual(targets[0].read_bytes(), b'damaged diagnostic')

    def test_each_musicbrainz_retry_reserves_its_own_slot_and_cache_reserves_none(self):
        from unittest.mock import patch
        from stemwise_engine.provider_operations import provider_operation, ProviderTransientError
        epoch = [1000.]
        starts = []
        def clock(): return epoch[0]
        def sleeper(seconds): epoch[0] += seconds
        def attempt(*args, **kwargs):
            starts.append(clock())
            if len(starts) == 1:
                raise ProviderTransientError('ConnectionError')
            return b'{"recordings":[]}'
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            client = research.MusicBrainzClient(root/'cache', clock=clock, sleeper=sleeper,
                rate_state_path=root/'rate.json')
            with provider_operation(10, clock=clock, sleeper=sleeper), \
                    patch('stemwise_engine.provider_operations.ProviderBudget.run', side_effect=attempt):
                self.assertEqual(client.search_recordings('Fixture', 'Artist'), [])
                self.assertEqual(client.search_recordings('Fixture', 'Artist'), [])
        self.assertEqual(len(starts), 2)
        self.assertGreaterEqual(starts[1]-starts[0], 1.1-1e-8)

    def test_unique_exact_recording_is_verified_and_enriched(self) -> None:
        found = recording()
        lookup = recording(score=None)
        lookup.update(
            isrcs=["US-AAA-12-34567"],
            genres=[{"name": "Rock"}],
            releases=[
                {
                    "id": RELEASE_ID,
                    "title": "Example Album",
                    "status": "Official",
                    "date": "2020-05-01",
                    "artist-credit": [{"name": "Example Artist"}],
                    "release-group": {
                        "id": "bbbbbbbb-cccc-dddd-eeee-ffffffffffff",
                        "primary-type": "Album",
                    },
                }
            ],
        )
        enriched, outcome = research.enrich_metadata(fallback(), 240_200, FakeClient([found], lookup))
        self.assertEqual(outcome["status"], "verified")
        self.assertEqual(enriched["matchStatus"], "verified")
        self.assertEqual(enriched["identifiers"]["musicBrainzRecordingId"], RECORDING_ID)
        self.assertEqual(enriched["identifiers"]["musicBrainzReleaseId"], RELEASE_ID)
        self.assertEqual(enriched["identifiers"]["isrc"], "USAAA1234567")
        self.assertEqual(enriched["album"]["releaseDate"], "2020-05-01")
        self.assertIn("Rock", enriched["genres"])
        contract._validate_metadata(enriched, "$.metadata")

    def test_duration_mismatch_is_not_verified(self) -> None:
        original = fallback()
        enriched, outcome = research.enrich_metadata(original, 225_000, FakeClient([recording()]))
        self.assertEqual(outcome["status"], "no-match")
        self.assertEqual(enriched, original)

    def test_remix_remaster_live_and_cover_ambiguity_is_strict(self) -> None:
        cases = (
            ("Example Song (Alice Remix)", recording(title="Example Song (Bob Remix)")),
            ("Example Song (2011 Remaster)", recording(title="Example Song (2020 Remaster)")),
            ("Example Song (Live at Wembley)", recording(title="Example Song", disambiguation="live in Paris")),
            ("Example Song (Cover)", recording(title="Example Song")),
        )
        for title, candidate in cases:
            with self.subTest(title=title):
                enriched, outcome = research.enrich_metadata(fallback(title), 240_000, FakeClient([candidate]))
                self.assertEqual(outcome["status"], "no-match")
                self.assertEqual(enriched["matchStatus"], "youtube-fallback")

    def test_equivalent_live_qualifier_can_be_verified(self) -> None:
        candidate = recording(title="Example Song", disambiguation="Live at Wembley")
        lookup = recording(title="Example Song", disambiguation="live at Wembley", score=None)
        enriched, outcome = research.enrich_metadata(
            fallback("Example Song (Live at Wembley)"), 240_000, FakeClient([candidate], lookup)
        )
        self.assertEqual(outcome["status"], "verified")
        self.assertEqual(enriched["matchStatus"], "verified")

    def test_two_exact_recording_ids_remain_unverified(self) -> None:
        candidates = [
            recording(),
            recording(recording_id="87654321-4321-4321-4321-cba987654321", length=240_300),
        ]
        enriched, outcome = research.enrich_metadata(fallback(), 240_100, FakeClient(candidates))
        self.assertEqual(outcome["reason"], "Multiple exact recordings remained ambiguous.")
        self.assertEqual(enriched["matchStatus"], "youtube-fallback")

    def test_unknown_musicbrainz_disambiguation_is_not_guessed(self) -> None:
        candidate = recording(disambiguation="take 3")
        enriched, outcome = research.enrich_metadata(fallback(), 240_000, FakeClient([candidate]))
        self.assertEqual(outcome["status"], "no-match")
        self.assertEqual(enriched["matchStatus"], "youtube-fallback")

    def test_official_video_presentation_label_does_not_change_title(self) -> None:
        self.assertEqual(
            research.title_evidence("Example Song (Official Music Video)"),
            research.title_evidence("Example Song"),
        )


class _Response:
    def __init__(self, payload: dict):
        self.payload = io.BytesIO(json.dumps(payload).encode("utf-8"))

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False

    def read(self, _limit: int) -> bytes:
        return self.payload.read(_limit)


class ClientTests(unittest.TestCase):
    def test_reservation_lock_wait_obeys_current_deadline_and_cancellation(self):
        import os
        import time
        from stemwise_engine.common import owned_process_tree
        from stemwise_engine.provider_operations import provider_operation, ProviderDeadline, ProviderCancelled
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            state = root / 'provider/rate.json'
            script = "import sys,time\nfrom pathlib import Path\nfrom stemwise_engine.common import exclusive_process_lock\nroot=Path(sys.argv[1])\nwith exclusive_process_lock(root/'provider/rate.lock'):\n (root/'ready').touch(); end=time.monotonic()+5\n while not (root/'release').exists() and time.monotonic()<end:time.sleep(.01)\n"
            with owned_process_tree([sys.executable, '-B', '-c', script, str(root)], stdout=-1, stderr=-1,
                    text=True, env={**os.environ, 'PYTHONPATH': str(PROJECT_ROOT / 'src')}) as child:
                try:
                    end = time.monotonic() + 3
                    while not (root / 'ready').exists() and time.monotonic() < end:
                        time.sleep(.01)
                    self.assertTrue((root / 'ready').exists())
                    client = research.MusicBrainzClient(root / 'responses', rate_state_path=state)
                    for cancelled, error in ((False, ProviderDeadline), (True, ProviderCancelled)):
                        started = time.monotonic()
                        with provider_operation(seconds=.12 if not cancelled else 1,
                                cancelled=lambda: cancelled and time.monotonic()-started > .06):
                            with self.assertRaises(error):
                                client._throttle()
                        self.assertLess(time.monotonic()-started, .5)
                        self.assertFalse(state.exists(), 'Expired wait must not reserve a request')
                        self.assertIsNone(child.poll())
                finally:
                    (root / 'release').touch()
                    _, detail = child.communicate(timeout=3)
                self.assertEqual(child.returncode, 0, detail)

    def test_separate_processes_and_response_caches_share_provider_reservations(self):
        import os
        from stemwise_engine.common import owned_process_tree
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
            root = Path(directory)
            script = "import sys,time\nfrom pathlib import Path\nimport stemwise_engine.metadata_research as m\nfrom stemwise_engine.provider_operations import provider_operation\nroot=Path(sys.argv[1]); index=sys.argv[2]\nm.__file__=str(root/'PC side/builder/src/metadata_research.py')\nclient=m.MusicBrainzClient(root/('cache-'+index),min_interval_seconds=.25)\n(root/('ready-'+index)).touch()\ndeadline=time.monotonic()+5\nwhile not all((root/('ready-'+i)).exists() for i in ('0','1')):\n if time.monotonic()>deadline:raise TimeoutError('peer did not start')\n time.sleep(.01)\nwith provider_operation(seconds=4):print(client._throttle(),flush=True)\n"
            environment = {**os.environ, 'PYTHONPATH': str(PROJECT_ROOT / 'src')}
            with owned_process_tree([sys.executable, '-B', '-c', script, str(root), '0'],
                    stdout=-1, stderr=-1, text=True, env=environment) as first, \
                    owned_process_tree([sys.executable, '-B', '-c', script, str(root), '1'],
                    stdout=-1, stderr=-1, text=True, env=environment) as second:
                values = []
                for child in (first, second):
                    output, error = child.communicate(timeout=8)
                    self.assertEqual(child.returncode, 0, error)
                    values.append(float(output))
            self.assertGreaterEqual(abs(values[1]-values[0]), .249)

    def test_http_response_is_cached_and_second_network_request_is_throttled(self) -> None:
        epoch = [1_000.0]

        def clock() -> float:
            return epoch[0]

        sleeps: list[float] = []

        def sleeper(seconds: float) -> None:
            sleeps.append(seconds)
            epoch[0] += seconds

        opener = Mock(side_effect=[_Response({"recordings": []}), _Response({"recordings": []})])
        with tempfile.TemporaryDirectory() as name:
            client = research.MusicBrainzClient(
                Path(name), opener=opener, clock=clock, sleeper=sleeper, min_interval_seconds=1.1,
                rate_state_path=Path(name) / 'provider/rate.json'
            )
            client._throttle = Mock(wraps=client._throttle)
            first = client.search_recordings("One", "Artist")
            cached = client.search_recordings("One", "Artist")
            second = client.search_recordings("Two", "Artist")
        self.assertEqual(first, cached)
        self.assertEqual(second, [])
        self.assertEqual(opener.call_count, 2)
        self.assertEqual(client._throttle.call_count, 2, 'A cache hit reserves no provider request')
        self.assertAlmostEqual(sum(sleeps), 1.1, places=4)
        request = opener.call_args_list[0].args[0]
        self.assertIn("StemwisePC/", request.headers["User-agent"])

    def test_network_failure_returns_untouched_fallback(self) -> None:
        client = FakeClient([])
        client.search_recordings = Mock(side_effect=RuntimeError("offline"))
        original = fallback()
        enriched, outcome = research.enrich_metadata(original, 240_000, client)
        self.assertEqual(enriched, original)
        self.assertEqual(outcome["status"], "unavailable")


class ReviewedMetadataTests(unittest.TestCase):
    def setUp(self):
        self.original = fallback("Uploader heading", ["Channel name"])
        self.original.update(album={"title": "Wrong album"}, identifiers={"musicBrainzRecordingId": RECORDING_ID})
        self.review = {
            "formatVersion": 1,
            "videoId": VIDEO_ID,
            "originalMetadataSha256": research.metadata_sha256(self.original),
            "sourcesSha256": "a" * 64,
            "title": "Reviewed song",
            "artists": ["Reviewed performer"],
            "evidenceUrls": ["https://artist.example.org/song"],
            "recordingIdentityConfirmed": True,
        }

    def apply(self, metadata=None, review=None):
        return research.apply_reviewed_metadata(
            self.original if metadata is None else metadata,
            self.review if review is None else review,
            video_id=VIDEO_ID, sources_sha256="a" * 64, staged_source_ids={VIDEO_ID},
        )

    def test_correction_clears_unreviewed_identity_and_repeats_without_mutation(self):
        before = copy.deepcopy(self.original)
        corrected = self.apply()
        self.assertEqual(corrected["title"], "Reviewed song")
        self.assertEqual(corrected["artists"], ["Reviewed performer"])
        self.assertNotIn("album", corrected)
        self.assertNotIn("identifiers", corrected)
        self.assertEqual(self.original, before)
        contract._validate_metadata(corrected, "$.metadata")
        self.assertIs(self.apply(metadata=corrected), corrected)
        changed = {**corrected, "album": {"title": "Unreviewed change"}}
        with self.assertRaisesRegex(ValueError, "original-changed"):
            self.apply(metadata=changed)

    def test_same_identity_preserves_existing_metadata(self):
        review = {**self.review, "title": self.original["title"], "artists": self.original["artists"]}
        self.assertIs(self.apply(review=review), self.original)

    def test_wrong_owner_changed_sources_or_missing_confirmation_fail_closed(self):
        for changes in (
            {"videoId": "z1B2c3D4e5F"}, {"sourcesSha256": "b" * 64},
            {"recordingIdentityConfirmed": False}, {"originalMetadataSha256": "c" * 64},
            {"formatVersion": True}, {"unrecognized": True},
            {"title": ""}, {"artists": ["duplicate", "duplicate"]},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.apply(review={**self.review, **changes})

    def test_evidence_must_be_public_and_youtube_ids_must_be_staged(self):
        for url in (
            "https://user:secret@example.org/song", "https://example.org/\nwrong",
            "file:///tmp/song", "http://127.0.0.1/song", "http://127.1/song", "http://localhost/song",
            "https://%31%32%37.0.0.1/song",
            "https://artist.example.org/\u200bhidden", "https://artist.example.org/\u202edirection",
            "https://www.youtube.com/watch?v=z1B2c3D4e5F", "https://youtube.com/@channel",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.apply(review={**self.review, "evidenceUrls": [url]})
        for url in (f"https://youtu.be/{VIDEO_ID}", "http://artist.example.org/song"):
            corrected = self.apply(review={**self.review, "evidenceUrls": [url]})
            contract._validate_metadata(corrected, "$.metadata")


class MetadataReviewPackageTests(unittest.TestCase):
    def test_review_flows_through_real_child_and_lyrics_refresh_without_touching_audio(self):
        from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import read_child
        from test_packages import package_config, write_child

        with tempfile.TemporaryDirectory() as name:
            root = Path(name).resolve()
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            child = pipeline.child_path(VIDEO_ID)
            write_child(child, VIDEO_ID, config)
            original, original_cover = read_child(child, config)
            state = pipeline._new_state({'videoId': VIDEO_ID}, 'absent')
            from stemwise_engine.common import sha256_file
            state.update(status='complete', childSha256=sha256_file(child))
            pipeline.save_checkpoint(state)
            audio = root / "retained-audio.wav"
            audio.write_bytes(b"unchanged synthetic retained recording")
            audio_before = audio.read_bytes()
            audio_mtime = audio.stat().st_mtime_ns
            workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
            workflow.workspace_root = root
            workflow.jobs = root / "jobs"
            workflow._runtime_config = lambda: config
            job_dir = workflow._job_dir(VIDEO_ID)
            job_dir.mkdir(parents=True)
            sources = {"lyricEvidenceSources": [{"videoId": VIDEO_ID}]}
            sources_raw = json.dumps(sources).encode()
            (job_dir / "sources.json").write_bytes(sources_raw)
            review = {"metadataReview": {
                "formatVersion": 1, "videoId": VIDEO_ID,
                "originalMetadataSha256": research.metadata_sha256(original["metadata"]),
                "sourcesSha256": hashlib.sha256(sources_raw).hexdigest(),
                "title": "Evidence-reviewed title", "artists": ["Evidence-reviewed artist"],
                "evidenceUrls": [f"https://www.youtube.com/watch?v={VIDEO_ID}"],
                "recordingIdentityConfirmed": True,
            }}
            job = {"videoId": VIDEO_ID, "recordingReviewRequired": True}
            before_bytes, before_mtime = child.read_bytes(), child.stat().st_mtime_ns
            invalid = copy.deepcopy(review)
            invalid["metadataReview"]["sourcesSha256"] = "0" * 64
            with self.assertRaisesRegex(FutureSongError, "incomplete or stale"):
                workflow._apply_metadata_review(job, invalid, sources, pipeline)
            with self.assertRaisesRegex(FutureSongError, "changed after alignment"):
                workflow._apply_metadata_review(job, review, sources, pipeline, allow_change=False)
            self.assertEqual((child.read_bytes(), child.stat().st_mtime_ns), (before_bytes, before_mtime))

            workflow._apply_metadata_review(job, review, sources, pipeline)
            corrected, corrected_cover = read_child(child, config)
            self.assertEqual(corrected["metadata"]["title"], "Evidence-reviewed title")
            self.assertEqual(corrected["metadata"]["artists"], ["Evidence-reviewed artist"])
            self.assertGreater(corrected["generatedAt"], original["generatedAt"])
            for field in ("source", "artwork", "analysis", "lyrics", "generator"):
                self.assertEqual(corrected[field], original[field])
            self.assertEqual(corrected_cover, original_cover)
            retry_bytes, retry_mtime = child.read_bytes(), child.stat().st_mtime_ns
            workflow._apply_metadata_review(job, review, sources, pipeline)
            self.assertEqual((child.read_bytes(), child.stat().st_mtime_ns), (retry_bytes, retry_mtime))
            workflow._apply_metadata_review(job, review, sources, pipeline, allow_change=False)
            self.assertEqual((child.read_bytes(), child.stat().st_mtime_ns), (retry_bytes, retry_mtime))

            lyrics = {"status": "unavailable", "reason": "Synthetic refresh check", "tracks": []}
            state = pipeline._new_state({"position": 1, "videoId": VIDEO_ID}, "b" * 64)
            pipeline._refresh_lyrics_only(VIDEO_ID, state, lyrics, "b" * 64)
            refreshed, refreshed_cover = read_child(child, config)
            self.assertEqual(refreshed["metadata"], corrected["metadata"])
            self.assertEqual(refreshed["lyrics"], lyrics)
            self.assertGreater(refreshed["generatedAt"], corrected["generatedAt"])
            for field in ("source", "artwork", "analysis"):
                self.assertEqual(refreshed[field], original[field])
            self.assertEqual(refreshed_cover, original_cover)
            self.assertEqual((audio.read_bytes(), audio.stat().st_mtime_ns), (audio_before, audio_mtime))


if __name__ == "__main__":
    unittest.main()
