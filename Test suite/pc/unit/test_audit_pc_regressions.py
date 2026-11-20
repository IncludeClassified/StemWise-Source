"""Small CPU regressions for optional metadata and shared numeric contracts."""
import sys
import json
import unittest
import tempfile
from unittest.mock import patch
from pathlib import Path

from test_metadata_research import FakeClient, fallback, recording, RELEASE_ID
import stemwise_engine.metadata_research as metadata_research
import stemwise_engine.contract as contract
import stemwise_engine.analyzers as analyzers
import stemwise_engine.song_enrichment_v2 as song_enrichment_v2


class AuditPcRegressions(unittest.TestCase):
    def test_queued_transfers_receive_the_full_execution_budget(self):
        import io
        from concurrent.futures import ThreadPoolExecutor
        from threading import Event
        from types import SimpleNamespace
        from contextlib import redirect_stdout
        import stemwise_media.save_download as download
        import stemwise_media.transfer_budget as budgets
        now, observed, queued = [0.], [], Event()
        class SerialQueue(ThreadPoolExecutor):
            def __init__(self, **kwargs):
                super().__init__(max_workers=1)
                self.submitted = 0
            def submit(self, *args, **kwargs):
                value = super().submit(*args, **kwargs)
                self.submitted += 1
                if self.submitted == 6:
                    queued.set()
                return value
        def save(track, url, *, budget):
            self.assertTrue(queued.wait(5), 'queue did not become ready')
            observed.append(budget.remaining())
            now[0] += 600
            return {'track': track}
        clock = SimpleNamespace(monotonic=lambda: now[0])
        args = ['download']+[f'{index}=https://eu.doubledouble.top/dl/Example{index}' for index in range(1, 7)]
        with patch.object(download, 'ThreadPoolExecutor', SerialQueue), patch.object(download, 'save_download', save), \
                patch.object(download, 'time', clock), patch.object(budgets, 'time', clock), \
                patch.object(sys, 'argv', args), redirect_stdout(io.StringIO()):
            self.assertEqual(download.main(), 0)
        self.assertEqual(observed, [900]*6)

    def test_optional_lyrics_and_preparation_diagnostics_keep_original_failures(self):
        import stemwise_engine.lyrics_acquire as lyrics
        import stemwise_engine.analysis_queue as queue
        from types import SimpleNamespace
        root = Path(__file__).resolve().parents[3]/'Test suite/work'
        with tempfile.TemporaryDirectory(dir=root) as name:
            directory = Path(name)
            with patch('stemwise_engine.private_diagnostics.retain_error', side_effect=OSError('disk full')):
                result = lyrics.acquire_exact_item_draft({'videoId': 'AAAAAAAAAAA', 'position': 1}, directory/'draft', detector=object(),
                    client=SimpleNamespace(get_watch_playlist=lambda **kwargs: (_ for _ in ()).throw(RuntimeError('provider failed'))))
            self.assertEqual(result['decision'], 'api-error')
            self.assertEqual(result['reason'], 'RuntimeError')
            with patch.object(queue, 'atomic_write_bytes', side_effect=OSError('disk full')):
                reason = queue._preparation_failure({'preparation': {'diagnosticPath': str(directory/'cause.txt')}},
                    RuntimeError('model failed'), directory)
            self.assertEqual(reason, 'native-emission-preparation-failed')

    def test_android_fixture_generator_requires_selected_imports(self):
        import importlib.util
        import stemwise_engine.packages as packages
        root = Path(__file__).resolve().parents[3]
        path = root/'Test suite/android/instrumentation/support/generate_pc_builder_enrichment_fixtures.py'
        spec = importlib.util.spec_from_file_location('audit_pc_fixtures', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        source = Path(packages.__file__).resolve().parent
        actual = module.require_builder_modules(source)
        self.assertEqual(actual['packages'], Path(packages.__file__).resolve())
        with self.assertRaisesRegex(RuntimeError, 'builder/import mismatch'):
            module.require_builder_modules(root/'Test suite/work/different-builder/src')

    def test_selected_registered_recording_hashes_retained_audio_once(self):
        import numpy as np
        import soundfile as sf
        import stemwise_engine.recording_source as source
        import stemwise_media.save_download as downloads
        from stemwise_engine.common import atomic_write_json, sha256_file
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            workspace = Path(name)
            library = workspace/'PC side/qobuz-comparison'
            audio = library/'audio/qobuz/11/song.flac'
            audio.parent.mkdir(parents=True)
            sf.write(audio, np.zeros((4800, 2)), 48000, subtype='PCM_24')
            digest = sha256_file(audio)
            atomic_write_json(audio.parent/'download.json', {'qobuzTrackId': 11,
                'file': audio.name, 'bytes': audio.stat().st_size, 'sha256': digest})
            atomic_write_json(audio.parent/'enrichment-input.json', {
                **source.registration_details(11, 'AAAAAAAAAAA', 'Song', 'Artist'),
                'file': audio.relative_to(library).as_posix()})
            config = {'projectDir': str(workspace/'PC side/stemwise-playlist-builder'),
                      'pcSideRootWindows': str(library.parent),
                      'futureRootWindows': str(workspace/'Future library')}
            with patch.object(downloads, 'hash_bounded', wraps=downloads.hash_bounded) as receipt_hash, \
                    patch.object(source, 'sha256_file', wraps=sha256_file) as identity_hash:
                selected = source.selection(config, 'qobuz:11')
            self.assertEqual(selected.identity['sha256'], digest)
            self.assertEqual(selected.audio, audio)
            self.assertEqual(receipt_hash.call_count + identity_hash.call_count, 1)

    def test_mixed_telemetry_cleanup_preserves_unrelated_families(self):
        import stemwise_engine.telemetry_retention as retention
        from test_telemetry_retention import fixture_workspace
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            workspace = fixture_workspace(name)
            android = workspace/'Engine telemetry/android-mvsep/retained.json'
            android.parent.mkdir(parents=True)
            android.write_bytes(b'owner data')
            pc = workspace/'Engine telemetry/youtube-a1B2c3D4e5F'
            pc.mkdir()
            for index in range(129):
                record = pc/'reports/runs'/f'{index:032x}'/'run.json'
                record.parent.mkdir(parents=True)
                record.write_bytes(b'{"status":"complete"}')
            for apply in (False, True):
                result = retention.cleanup(workspace, apply=apply)
                self.assertEqual(set(result), {pc.name})
                self.assertEqual(result[pc.name]['removedCount'], 1)
                self.assertEqual(len(list(pc.glob('reports/runs/*/run.json'))), 128 if apply else 129)
                self.assertEqual(android.read_bytes(), b'owner data')

    def test_album_binding_preserves_rendition(self):
        from stemwise_media.save_album import identify
        track = {'id': 11, 'title': 'Song', 'artist': 'Artist', 'album': 'Album'}
        tags = {'track': '1', 'title': 'Song', 'artist': 'Artist', 'album': 'Album'}
        self.assertEqual(identify(tags, [track]), track)
        for rendition in ('Live', 'Vocals Only No Music Version'):
            with self.assertRaises(ValueError):
                identify({**tags, 'album': 'Album '+rendition}, [track])
            selected = {**track, 'album': 'Album '+rendition}
            self.assertEqual(identify({**tags, 'album': selected['album']}, [selected]), selected)

    def test_optional_metadata_diagnostic_failure_keeps_unavailable_result(self):
        with patch('stemwise_engine.private_diagnostics.retain_error', side_effect=OSError('disk full')):
            result, detail = metadata_research._metadata_unavailable(fallback(), RuntimeError('provider failed'), Path('.'))
        self.assertEqual(result, fallback())
        self.assertEqual(detail['status'], 'unavailable')
        self.assertIn('RuntimeError', detail['reason'])

    def test_sample_clock_agrees_for_native_identity_delivery_cache_and_exact_eof(self):
        import numpy as np
        import soundfile as sf
        import stemwise_engine.recording_source as recording_source
        import stemwise_engine.original_delivery as original_delivery
        import stemwise_engine.endpoint_vocal_evidence as endpoint_vocal_evidence
        import stemwise_engine.youtube as youtube
        from stemwise_engine.common import atomic_write_json, sha256_file, source_duration_ms
        from test_song_enrichment_v2 import valid_document
        root = Path(__file__).resolve().parents[3]
        with tempfile.TemporaryDirectory(dir=root/'Test suite/work') as name:
            library = Path(name)/'PC side/qobuz-comparison'
            audio = library/'audio/qobuz/11/song.flac'
            audio.parent.mkdir(parents=True)
            for frames, expected in ((47976, 1000), (48024, 1001), (48072, 1002)):
                sf.write(audio, np.zeros((frames, 2), dtype='float32'), 48000, subtype='PCM_24')
                atomic_write_json(audio.parent/'download.json', {'qobuzTrackId': 11,
                    'file': audio.name, 'bytes': audio.stat().st_size, 'sha256': sha256_file(audio)})
                _, identity = recording_source.retained_input(library, 'qobuz:11')
                self.assertEqual(identity['durationMs'], expected)
                self.assertEqual(source_duration_ms(frames, 48000), expected)
                self.assertEqual(youtube.probe_duration_ms(audio), expected)
                self.assertEqual(original_delivery._decoded_duration(audio), expected)
                wav = audio.with_suffix('.wav')
                sf.write(wav, np.zeros((frames, 2), dtype='float32'), 48000, subtype='FLOAT')
                with patch('stemwise_engine.worker_runtime_probe.torch_runtime_identity',
                        return_value={'device': 'cpu', 'torch': 'fixture'}):
                    self.assertEqual(endpoint_vocal_evidence._separation_key(wav)[1:], (expected, frames))
                document = valid_document()
                document['source']['recording'] = identity
                document['lyrics']['tracks'][0]['lines'][0]['endMs'] = expected
                document['lyrics']['tracks'][0]['lines'][0]['words'][-1]['endMs'] = expected
                song_enrichment_v2.validate_generated_groups(document)

    def test_roformer_identity_uses_reviewed_pins_without_installed_model_packages(self):
        import importlib.metadata
        import stemwise_engine.alignment_models as alignment_models
        from stemwise_engine.environment_locks import dependency_identity
        project = Path(alignment_models.__file__).resolve().parent.parent
        reviewed = dependency_identity(project)['environments']['main']['packages']
        with patch.object(importlib.metadata, 'distribution',
                side_effect=importlib.metadata.PackageNotFoundError('einops')):
            identity = alignment_models.roformer_runtime_pins(project)
        self.assertEqual(identity['packages']['einops'], '0.8.2')
        expected = {name: reviewed[name]['version']
                    for name in alignment_models.ROFORMER_RUNTIME_PACKAGES if name in reviewed}
        # The reviewed local wheel is a manifest source, rather than an index lock entry.
        expected['melband-roformer-infer'] = alignment_models.ROFORMER_PACKAGE_VERSION
        self.assertEqual(identity['packages'], expected)

    def test_standard_name_validation_rejects_unbound_names_in_any_function(self):
        import importlib.util
        root = Path(__file__).resolve().parents[3]
        spec = importlib.util.spec_from_file_location('portable_checks', root/'Test suite/host/portable_checks.py')
        checks = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checks)
        path = root/'PC side/stemwise-playlist-builder/src/lyrics_align_pc.py'
        checks.validate_python_names(path.read_text(encoding='utf-8'), filename=str(path))
        for function in ('_align', 'moved_preparation', 'new_entry_point'):
            with self.subTest(function=function), self.assertRaisesRegex(ValueError, 'undefined name'):
                checks.validate_python_names('def '+function+'():\n    return missing_recovery_requirement\n', filename='fixture')

    def test_optional_metadata_expiry_preserves_fallback_and_parent_cancellation(self):
        import stemwise_engine.provider_operations as providers
        now = [0.]
        original = fallback()
        client = FakeClient([])
        def expire_own_stage(*args):
            budget = providers.current_budget()
            budget.deadline = budget.clock() - 1
            budget.remaining()
        client.search_recordings = expire_own_stage
        enriched, result = metadata_research.enrich_metadata(original, 240000, client)
        self.assertEqual(enriched, original)
        self.assertEqual(result['status'], 'unavailable')
        def expire(*args):
            now[0] = 61.
            providers.current_budget().remaining()
        client.search_recordings = expire
        with providers.provider_operation(120, clock=lambda: now[0]):
            enriched, result = metadata_research.enrich_metadata(original, 240000, client)
        self.assertEqual(enriched, original)
        self.assertEqual(result['status'], 'unavailable')
        now[0] = 0.
        with providers.provider_operation(30, clock=lambda: now[0]):
            with self.assertRaises(providers.ProviderDeadline):
                metadata_research.enrich_metadata(original, 240000, client)
        now[0] = 0.
        with providers.provider_operation(120, clock=lambda: now[0]):
            with self.assertRaises(providers.ProviderStageDeadline):
                with providers.optional_provider_stage(20):
                    metadata_research.enrich_metadata(original, 240000, client)
        with providers.provider_operation(120, cancelled=lambda: True):
            with self.assertRaises(providers.ProviderCancelled):
                metadata_research.enrich_metadata(original, 240000, FakeClient([]))

    def test_duplicate_artist_credits_are_normalized_before_publication(self):
        candidate = recording()
        candidate['artist-credit'] *= 2
        enriched, diagnostic = metadata_research.enrich_metadata(fallback(), 240000, FakeClient([candidate]))
        self.assertEqual(diagnostic['status'], 'verified')
        self.assertEqual(enriched['artists'], ['Example Artist'])
        contract._validate_metadata(enriched, '$.metadata')

    def test_invalid_optional_date_falls_back_inside_provider_boundary(self):
        candidate = recording()
        lookup = recording()
        lookup['releases'] = [{'id': RELEASE_ID, 'title': 'Album', 'status': 'Official',
                              'date': '2020-99-99', 'artist-credit': [{'name': 'Example Artist'}],
                              'release-group': {'id': RELEASE_ID, 'primary-type': 'Album'}}]
        original = fallback()
        enriched, diagnostic = metadata_research.enrich_metadata(original, 240000, FakeClient([candidate], lookup))
        contract._validate_metadata(enriched, '$.metadata')
        self.assertEqual(enriched, original)
        self.assertEqual(diagnostic['status'], 'unavailable')

    def test_nearest_downbeats_match_independent_linear_reference(self):
        for beats, downbeats in (([], [10]), ([0, 100], [50, 50, 170, 171]),
                                 ([0, 500, 1000], [-71, -70, 430, 570, 1070])):
            expected = set()
            for downbeat in downbeats:
                if beats:
                    nearest = min(range(len(beats)), key=lambda i: abs(beats[i] - downbeat))
                    if abs(beats[nearest] - downbeat) <= 70:
                        expected.add(nearest)
            self.assertEqual(analyzers._nearest_downbeat_indices(beats, downbeats), expected)

    def test_track_ids_use_consumer_exact_integer_domain(self):
        fixture = Path(__file__).resolve().parents[2] / 'android/fixtures/schemas/recording-track-id-boundaries.json'
        for row in json.loads(fixture.read_text(encoding='utf-8')):
            number, accepted = row['trackId'], row['accepted']
            source = {'provider': 'youtube', 'videoId': 'AAAAAAAAAAA', 'recording': {
                'trackId': number, 'recordingKey': row['recordingKey'], 'file': 'audio/song.flac',
                'sha256': 'a'*64, 'byteCount': 1, 'durationMs': 1000}}
            if accepted:
                self.assertEqual(song_enrichment_v2.validate_source(source), source['videoId'])
            else:
                with self.assertRaises(ValueError):
                    song_enrichment_v2.validate_source(source)


if __name__ == '__main__':
    unittest.main()
