from __future__ import annotations

import copy
import hashlib
import io
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import zipfile
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest import mock
from contextlib import redirect_stderr


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import process_birth, terminate_exact

import stemwise_engine.pipeline as pipeline_module  # noqa: E402
import stemwise_engine.youtube as youtube
from stemwise_engine.common import (  # noqa: E402
    ProcessLockBusyError,
    atomic_write_json,
    exclusive_process_lock,
    heavy_worker_lock_path,
)
from stemwise_engine.packages import PackageValidationError, read_child  # noqa: E402
from stemwise_engine.pipeline import StemwisePipeline  # noqa: E402
from test_packages import PNG_1X1, package_config as _package_config, song_document, write_child  # noqa: E402


VIDEO_ID = "a1B2c3D4e5F"


def package_config(root):
    config = _package_config(root)
    config['projectDir'] = str(root / 'PC side/stemwise-playlist-builder')
    return config


def read_attempt_failure(owner, video_id=VIDEO_ID):
    pointer = owner._attempt_failure_path(video_id)
    reference = json.loads(pointer.read_bytes())
    path = pointer.parent / reference['file']
    raw = path.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == reference['sha256']
    assert len(raw) == reference['byteCount']
    return json.loads(raw)


def inventory(pipeline: StemwisePipeline, video_ids: list[str]) -> None:
    atomic_write_json(
        pipeline.inventory_path,
        {
            "snapshotVersion": 1,
            "playlist": {"title": "Awakening"},
            "itemCount": len(video_ids),
            "items": [
                {"position": index, "videoId": video_id, "title": f"Song {index}"}
                for index, video_id in enumerate(video_ids, start=1)
            ],
        },
    )


def youtube_metadata(video_id: str) -> dict:
    url = f"https://www.youtube.com/watch?v={video_id}"
    return {
        "matchStatus": "youtube-fallback",
        "title": f"Song {video_id}",
        "artists": ["Artist"],
        "sources": [{"name": "YouTube", "url": url}],
        "links": [{"type": "youtube", "url": url}],
    }


def create_cover(_info, video_id: str, destination: Path, **_kwargs) -> dict:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(PNG_1X1)
    return {
        "file": "cover.png",
        "kind": "youtube-thumbnail",
        "source": {
            "name": "YouTube thumbnail",
            "url": f"https://www.youtube.com/watch?v={video_id}",
        },
    }


def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class CleanupRootSafetyTests(unittest.TestCase):
    def test_temporary_cleanup_root_must_be_disjoint_from_durable_roots(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            for temp_dir, label in (
                (root / "output" / "temporary", "outputDir"),
                (root, "projectDir"),
            ):
                with self.subTest(temp_dir=temp_dir):
                    config = package_config(root)
                    config["tempDir"] = str(temp_dir)
                    with self.assertRaisesRegex(ValueError, f"overlaps {label}"):
                        StemwisePipeline(config)

    def test_generated_output_root_must_be_disjoint_from_durable_roots(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            pc_side = root / "PC side"
            project = pc_side / "stemwise-playlist-builder"
            durable = (
                ("projectDir", project / "future-songs"),
                ("Ready to Import", root / "Ready to Import"),
                ("private reports", pc_side / "reports"),
                ("model cache", pc_side / "model-cache"),
            )
            for label, output in durable:
                with self.subTest(output=output):
                    config = package_config(root)
                    config.update(
                        projectDir=str(project),
                        outputDir=str(output),
                        pcSideRootWindows=str(pc_side),
                        futureRootWindows=str(project / "future-songs"),
                        futureReadyDirWindows=str(root / "Ready to Import"),
                    )
                    with self.assertRaisesRegex(
                        ValueError, f"Generated output root overlaps {label}"
                    ):
                        StemwisePipeline(config)

    def test_only_bound_future_runtime_may_use_output_inside_future_state(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            pc_side = root / "PC side"
            project = pc_side / "stemwise-playlist-builder"
            future = project / "future-songs"
            state = future / "policy-state" / "current"
            config = package_config(root)
            config.update(
                projectDir=str(project),
                outputDir=str(state / "runtime"),
                lyricsInputDir=str(state / "owner-input"),
                tempDir=str(pc_side / "temporary-analysis"),
                pcSideRootWindows=str(pc_side),
                futureRootWindows=str(future),
                futureReadyDirWindows=str(root / "Ready to Import"),
            )
            self.assertEqual(StemwisePipeline(config).output_dir, state / "runtime")

            config["lyricsInputDir"] = str(state / "wrong-owner")
            with self.assertRaisesRegex(ValueError, "Generated output root overlaps"):
                StemwisePipeline(config)




class AnalysisRetryTests(unittest.TestCase):
    def test_completed_cleanup_preserves_unknown_owners_and_replaced_receipts(self):
        from stemwise_engine.common import create_manifested_directory, manifested_cleanup_receipt, atomic_write_json
        for kind, video in (('unknown-staging',VIDEO_ID), ('lyrics-refresh-staging','BBBBBBBBBBB')):
            with self.subTest(kind=kind, video=video), \
                    tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                owner=StemwisePipeline(package_config(Path(name)))
                path=owner.work_dir/'staging'/f'{VIDEO_ID}-{"a"*32}'
                create_manifested_directory(path.parent,path,owner={'kind':kind,'videoId':video})
                keep=path/'owner.txt';keep.write_bytes(b'preserve unknown ownership')
                with self.assertRaisesRegex(ValueError,'owner differs'):
                    owner._cleanup_completed_song(VIDEO_ID)
                self.assertEqual(keep.read_bytes(),b'preserve unknown ownership')
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner=StemwisePipeline(package_config(Path(name)))
            path=owner.work_dir/'staging'/f'{VIDEO_ID}-{"b"*32}'
            binding={'kind':'native-metadata-refresh','videoId':VIDEO_ID}
            create_manifested_directory(path.parent,path,owner=binding)
            previous=path.stat()
            atomic_write_json(manifested_cleanup_receipt(path), {'manifest': {
                'manifestVersion':1,'owner':binding,'generatedPaths':['.']},
                'path':str(path),'device':previous.st_dev,'inode':previous.st_ino})
            retired=path.with_name('retained-owner')
            path.rename(retired)
            create_manifested_directory(path.parent,path,owner=binding)
            keep=path/'replacement.txt';keep.write_bytes(b'preserve replacement')
            with self.assertRaisesRegex(ValueError,'replaced'):
                owner._cleanup_completed_song(VIDEO_ID)
            self.assertEqual(keep.read_bytes(),b'preserve replacement')
            self.assertTrue(retired.is_dir())
            self.assertTrue(manifested_cleanup_receipt(path).is_file())

    def test_failed_temp_cleanup_reconciles_its_receipt_before_reacquisition(self):
        from stemwise_engine.common import manifested_cleanup_receipt
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner=StemwisePipeline(package_config(Path(name)))
            temporary=owner._temp_dir(VIDEO_ID)
            (temporary/'incomplete.partial').write_bytes(b'incomplete, never accepted')
            owner.save_checkpoint({'videoId':VIDEO_ID,'status':'failed'})
            checkpoint=owner.checkpoint_path(VIDEO_ID).read_bytes()
            unlink=os.unlink
            def interrupted(path,*args,**kwargs):
                unlink(path,*args,**kwargs)
                if Path(path).name=='.stemwise-generated.json': raise KeyboardInterrupt('cleanup interrupted')
            with mock.patch.object(os,'unlink',side_effect=interrupted), self.assertRaises(KeyboardInterrupt):
                owner._cleanup_temp(temporary)
            self.assertTrue(manifested_cleanup_receipt(temporary).exists())
            recovered=owner._temp_dir(VIDEO_ID)
            self.assertEqual([p.name for p in recovered.iterdir()],['.stemwise-generated.json'])
            self.assertFalse(manifested_cleanup_receipt(temporary).exists())
            self.assertEqual(owner.checkpoint_path(VIDEO_ID).read_bytes(),checkpoint)

    def test_public_cached_song_recovers_process_death_after_lyrics_refresh_commit(self):
        from stemwise_engine.common import run_process_tree
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            checkpoint = owner.load_checkpoint(VIDEO_ID)
            path = owner._lyrics_path(VIDEO_ID)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({'status':'unavailable', 'reason':'Updated owner choice', 'tracks':[]}), encoding='utf-8')
            script = '''import json, os, sys
from unittest.mock import patch
from stemwise_engine.pipeline import StemwisePipeline
import stemwise_engine.common as common
config, producer, video = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
from stemwise_engine.provenance import provenance_fingerprint
def provenance(owner):
 owner.provenance=producer
 owner.provenance_sha256=provenance_fingerprint(producer)
 owner._provenance_needs_refresh=False
remove=common.remove_manifested_directory
def cleanup(root,path,**kwargs):
 if kwargs['owner']['kind']=='lyrics-refresh-staging': os._exit(37)
 return remove(root,path,**kwargs)
with patch.object(StemwisePipeline,'_refresh_provenance',provenance), \
     patch.object(common,'remove_manifested_directory',side_effect=cleanup), \
     patch.object(StemwisePipeline,'_analysis',side_effect=AssertionError('inference repeated')):
 with patch('stemwise_engine.pipeline.probe_duration_ms',return_value=1000):
  StemwisePipeline(config).process_song({'videoId':video})
raise AssertionError('committed lyrics refresh cleanup was not reached')
'''
            interrupted = run_process_tree([sys.executable,'-B','-c',script,json.dumps(owner.config),
                json.dumps(owner.provenance),VIDEO_ID], timeout=30, capture_output=True, text=True)
            self.assertEqual(interrupted.returncode,37,interrupted.stderr)
            reader=StemwisePipeline(owner.config)
            reader.provenance=copy.deepcopy(owner.provenance)
            reader.provenance_sha256=checkpoint['provenanceSha256']
            reader._provenance_needs_refresh=False
            committed=reader.child_path(VIDEO_ID).read_bytes(),reader.checkpoint_path(VIDEO_ID).read_bytes()
            with mock.patch.object(reader,'_analysis',side_effect=AssertionError('inference repeated')):
                self.assertEqual(reader.process_song(item),'skipped')
            self.assertEqual((reader.child_path(VIDEO_ID).read_bytes(),reader.checkpoint_path(VIDEO_ID).read_bytes()),committed)
            self.assertEqual(read_child(reader.child_path(VIDEO_ID),reader.config)[0]['lyrics']['reason'],'Updated owner choice')
            self.assertFalse(list((reader.work_dir/'staging').iterdir()))

    def test_cached_song_cleans_each_committed_refresh_owner_and_external_receipt(self):
        from stemwise_engine.common import create_manifested_directory, manifested_cleanup_receipt, atomic_write_json
        for kind in ('lyrics-refresh-staging', 'native-metadata-refresh', 'metadata-review-staging', 'artwork-rollback'):
            for receipt_only in (False, True):
                with self.subTest(kind=kind, receipt_only=receipt_only), \
                        tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                    owner, item = self.retained_rebuild_fixture(Path(name))
                    checkpoint = owner.load_checkpoint(VIDEO_ID)
                    owner.provenance_sha256 = checkpoint['provenanceSha256']
                    path = owner.work_dir/'staging'/f'{VIDEO_ID}-{"a"*32}'
                    binding = {'kind': kind, 'videoId': VIDEO_ID}
                    create_manifested_directory(path.parent, path, owner=binding, workspace_root=owner.workspace_root)
                    if receipt_only:
                        identity = path.stat()
                        atomic_write_json(manifested_cleanup_receipt(path), {'manifest': {
                            'manifestVersion': 1, 'owner': binding, 'generatedPaths': ['.']},
                            'path': str(path), 'device': identity.st_dev, 'inode': identity.st_ino})
                        (path/'.stemwise-generated.json').unlink()
                        path.rmdir()
                    before = owner.child_path(VIDEO_ID).read_bytes(), owner.checkpoint_path(VIDEO_ID).read_bytes()
                    with mock.patch.object(owner, '_analysis', side_effect=AssertionError('must reuse committed analysis')):
                        self.assertEqual(owner.process_song(item), 'skipped')
                    self.assertEqual((owner.child_path(VIDEO_ID).read_bytes(), owner.checkpoint_path(VIDEO_ID).read_bytes()), before)
                    self.assertFalse(path.exists())
                    self.assertFalse(manifested_cleanup_receipt(path).exists())

    def test_status_keeps_prepared_source_metadata_private_without_modifying_checkpoint(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner = StemwisePipeline(package_config(Path(name)))
            owner.save_checkpoint({'videoId': VIDEO_ID, 'status': 'failed', 'preparedInputs': {
                'info': {'description': 'private acquired source text',
                         'http_headers': {'Authorization': 'synthetic-private-token'}}}})
            checkpoint = owner.checkpoint_path(VIDEO_ID).read_bytes()
            report = owner.status()
            self.assertIsNone(report['states'][0]['preparedInputs'])
            for raw in (json.dumps(report), owner.report_path.read_text(encoding='utf-8')):
                self.assertNotIn('private acquired source text', raw)
                self.assertNotIn('synthetic-private-token', raw)
            self.assertEqual(owner.checkpoint_path(VIDEO_ID).read_bytes(), checkpoint)

    def test_committed_package_survives_staging_cleanup_failure_and_retry_only_cleans(self):
        for kind in ('song-package-staging', 'analysis-temporary'):
            with self.subTest(kind=kind):
                self._assert_committed_cleanup_failure(kind)

    def _assert_committed_cleanup_failure(self, kind):
        import stemwise_engine.common as common
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner = StemwisePipeline(package_config(Path(name)))
            owner._provenance_needs_refresh = False
            item = {'videoId': VIDEO_ID, 'position': 1, 'title': 'Fixture'}
            def download(video, temporary, **kwargs):
                audio = temporary/'analysis.wav'
                audio.write_bytes(b'exact checkpointed PCM')
                return {'id': video}, audio
            remove = common.remove_manifested_directory
            def fail_staging(root, path, **kwargs):
                if kwargs['owner']['kind'] == kind:
                    raise OSError('isolated staging cleanup fault')
                return remove(root, path, **kwargs)
            with mock.patch.object(owner, '_song_info', return_value={'id': VIDEO_ID}), \
                    mock.patch.object(owner, '_metadata', side_effect=lambda info, video: youtube_metadata(video)), \
                    mock.patch.object(owner, '_create_cover', side_effect=create_cover), \
                    mock.patch.object(owner, '_acquire_analysis_audio', side_effect=download) as downloads, \
                    mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value, *a, **k: (value, {})), \
                    mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']) as analyses:
                with mock.patch.object(common, 'remove_manifested_directory', side_effect=fail_staging), \
                        mock.patch.object(pipeline_module, 'remove_manifested_directory', side_effect=fail_staging):
                    self.assertEqual(owner.process_song(item), 'complete')
                self.assertEqual(owner.load_checkpoint(VIDEO_ID)['status'], 'complete')
                before = owner.child_path(VIDEO_ID).read_bytes()
                read_child(owner.child_path(VIDEO_ID), owner.config)
                pending = list((owner.work_dir/'staging').iterdir()) if kind == 'song-package-staging' else [
                    path for path in (owner.temp_root/VIDEO_ID).iterdir()]
                self.assertTrue(pending)
                self.assertEqual(owner.process_song(item), 'skipped')
                self.assertFalse(list((owner.work_dir/'staging').iterdir()))
                self.assertFalse((owner.temp_root/VIDEO_ID).exists())
                self.assertEqual(owner.child_path(VIDEO_ID).read_bytes(), before)
                self.assertEqual((downloads.call_count, analyses.call_count), (1, 1))

    def test_initial_phase_binds_actual_pcm_source_and_producer(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner = StemwisePipeline(package_config(Path(name)))
            owner.provenance_sha256 = 'a'*64
            temporary = owner._temp_dir(VIDEO_ID)
            audio = temporary/'analysis.wav'
            audio.write_bytes(b'exact PCM')
            phase = {'source': owner._source_identity(VIDEO_ID), 'info': {'id': VIDEO_ID},
                'producerSha256': owner.provenance_sha256, 'pcm': {'durationMs': 1000,
                    'sha256': hashlib.sha256(audio.read_bytes()).hexdigest(), 'byteCount': audio.stat().st_size},
                'analysis': song_document(VIDEO_ID)['analysis']}
            atomic_write_json(temporary/'initial-phase.json', phase)
            with mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000):
                self.assertEqual(owner._initial_phase(VIDEO_ID, temporary)['analysis'], phase['analysis'])
                owner.provenance_sha256 = 'b'*64
                self.assertNotIn('analysis', owner._initial_phase(VIDEO_ID, temporary))
                phase['source'] = {'provider': 'youtube', 'videoId': 'z9Y8x7W6v5U'}
                atomic_write_json(temporary/'initial-phase.json', phase)
                with self.assertRaisesRegex(ValueError, 'another recording'):
                    owner._initial_phase(VIDEO_ID, temporary)
                phase['source'] = owner._source_identity(VIDEO_ID)
                atomic_write_json(temporary/'initial-phase.json', phase)
                audio.write_bytes(b'altered PCM')
                with self.assertRaisesRegex(ValueError, 'PCM changed'):
                    owner._initial_phase(VIDEO_ID, temporary)
                self.assertEqual(audio.read_bytes(), b'altered PCM')

    def test_first_package_failure_reuses_bound_download_and_analysis_checkpoint(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            config = package_config(Path(name))
            config['keepFailedAudio'] = False
            pipeline = StemwisePipeline(config)
            pipeline._provenance_needs_refresh = False
            item = {'videoId': VIDEO_ID, 'position': 1, 'title': 'Fixture'}
            def download(video, temporary, **kwargs):
                audio = temporary/'analysis.wav'
                audio.write_bytes(b'exact checkpointed PCM')
                return {'id': video}, audio
            create = pipeline_module.create_child_zip
            calls = [0]
            def package(*args, **kwargs):
                calls[0] += 1
                if calls[0] == 1:
                    raise OSError('isolated first publication fault')
                return create(*args, **kwargs)
            with mock.patch.object(pipeline, '_song_info', return_value={'id': VIDEO_ID}), \
                    mock.patch.object(pipeline, '_metadata', side_effect=lambda info, video: youtube_metadata(video)), \
                    mock.patch.object(pipeline, '_create_cover', side_effect=create_cover), \
                    mock.patch.object(pipeline, '_acquire_analysis_audio', side_effect=download) as downloads, \
                    mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value, *args, **kwargs: (value, {})), \
                    mock.patch.object(pipeline, '_analysis', return_value=song_document(VIDEO_ID)['analysis']) as analyses, \
                    mock.patch.object(pipeline_module, 'create_child_zip', side_effect=package):
                self.assertEqual(pipeline.process_song(item), 'failed')
                self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)['stages']['analysis']['status'], 'complete')
                self.assertEqual(pipeline.process_song(item), 'complete')
            self.assertEqual(downloads.call_count, 1)
            self.assertEqual(analyses.call_count, 1)
            document, _ = read_child(pipeline.child_path(VIDEO_ID), config)
            self.assertEqual(document['analysis'], song_document(VIDEO_ID)['analysis'])

    def test_optional_metadata_expiry_packages_fallback_while_cancellation_stops_analysis(self):
        import stemwise_engine.provider_operations as providers
        import zipfile
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                root = Path(name)
                owner = StemwisePipeline(package_config(root))
                item = {'position': 1, 'videoId': VIDEO_ID, 'title': 'Song'}
                def download(video, directory, **kwargs):
                    audio = directory/'analysis.wav'
                    audio.write_bytes(b'fixture decoded audio')
                    return {'id': video, 'title': 'Song', 'uploader': 'Artist'}, audio
                def expired(*args):
                    if cancelled:
                        raise providers.ProviderCancelled('explicit-cancellation')
                    budget = providers.current_budget()
                    budget.deadline = budget.clock() - 1
                    budget.remaining()
                with mock.patch.object(youtube, 'fetch_video_info', return_value={'id': VIDEO_ID}), \
                        mock.patch.object(youtube, 'metadata_from_info', return_value=youtube_metadata(VIDEO_ID)), \
                        mock.patch.object(youtube, 'create_cover_png', side_effect=create_cover), \
                        mock.patch.object(youtube, 'download_analysis_audio', side_effect=download), \
                        mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                        mock.patch.object(owner.metadata_client, 'search_recordings', side_effect=expired), \
                        mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']) as analysis:
                    self.assertEqual(owner.process_song(item), 'failed' if cancelled else 'complete')
                if cancelled:
                    analysis.assert_not_called()
                    self.assertFalse(owner.child_path(VIDEO_ID).exists())
                    self.assertEqual(owner.load_checkpoint(VIDEO_ID)['error'], 'ProviderCancelled')
                else:
                    with zipfile.ZipFile(owner.child_path(VIDEO_ID)) as package:
                        document = json.loads(package.read('song.json'))
                    self.assertEqual(document['metadata'], youtube_metadata(VIDEO_ID))
                    self.assertEqual(owner.load_checkpoint(VIDEO_ID)['stages']['metadata']['research']['status'], 'unavailable')

    def test_status_reports_corrupt_checkpoints_without_repairing_or_losing_their_identity(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner = StemwisePipeline(package_config(Path(name)))
            failures = {VIDEO_ID: b'{', 'b' * 11: b'[]', 'c' * 11: b'{}'}
            for video, raw in failures.items():
                owner.checkpoint_path(video).write_bytes(raw)
            unreadable = owner.checkpoint_path('c' * 11)
            actual_read = pipeline_module.read_json
            def read(path):
                if path == unreadable:
                    raise PermissionError('fixture checkpoint is unreadable')
                return actual_read(path)
            with mock.patch.object(pipeline_module, 'read_json', side_effect=read), \
                    mock.patch.object(owner, '_recover_analysis_replacement') as recover:
                report = owner.status()
                self.assertEqual(report['checkpointCounts'], {'corrupt': 3})
                self.assertEqual({row['videoId'] for row in report['states']}, set(failures))
                self.assertEqual({row['error'] for row in report['states']},
                                 {'JSONDecodeError', 'ValueError', 'PermissionError'})
                recover.assert_not_called()
            for video, raw in failures.items():
                self.assertEqual(owner.checkpoint_path(video).read_bytes(), raw)
            self.assertIsNone(owner.load_checkpoint('d' * 11))

    def test_optional_adapter_cause_is_retained_in_the_pipeline_checkpoint(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            owner = StemwisePipeline(package_config(root))
            item = {'position': 1, 'videoId': VIDEO_ID, 'title': 'Song'}
            def download(video, directory, **kwargs):
                audio = directory/'analysis.wav'
                audio.write_bytes(b'fixture decoded audio')
                return {'id': video, 'title': 'Song', 'uploader': 'Artist'}, audio
            def worker(audio, duration, project, timeout):
                result = song_document(VIDEO_ID)['analysis']
                result['_privateDiagnostics'] = [{'errorType': 'ValueError', 'body': 'private current analyzer cause'}]
                return result
            first, second, third = self.patches(owner)
            with first, second, third, mock.patch.object(youtube, 'download_analysis_audio', side_effect=download), \
                    mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda metadata, *a, **kw: (metadata, {'status': 'no-match'})), \
                    mock.patch.object(pipeline_module, '_run_analysis_single', side_effect=worker):
                outcome = owner.process_song(item)
                self.assertEqual(outcome, 'complete', read_attempt_failure(owner) if outcome == 'failed' else None)
            checkpoint = owner.load_checkpoint(VIDEO_ID)
            reference, = checkpoint['stages']['analysis']['privateDiagnostics']
            self.assertEqual((root/reference['file']).read_text(encoding='utf-8'), 'private current analyzer cause')
            self.assertNotIn('private current', owner.child_path(VIDEO_ID).read_bytes().decode('latin-1'))

    def test_acoustic_stage_records_real_worker_execution_and_interruption(self):
        for interrupted in (False, True):
            with self.subTest(interrupted=interrupted), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
                owner = StemwisePipeline(package_config(Path(name)))
                item = {'position': 1, 'videoId': VIDEO_ID, 'title': 'Song'}
                pcm = b'isolated decoded PCM'
                def download(_video_id, directory, **_kwargs):
                    audio = directory / 'analysis.wav'
                    audio.write_bytes(pcm)
                    return {'id': VIDEO_ID}, audio
                def worker(*_args):
                    time.sleep(.02)
                    if interrupted:
                        raise KeyboardInterrupt('private numerical interruption')
                    return song_document(VIDEO_ID)['analysis']
                first, second, third = self.patches(owner)
                with first, second, third, mock.patch.object(youtube, 'download_analysis_audio', download), \
                        mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                        mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value,*_,**kw: (value, {'status':'no-match'})), \
                        mock.patch.object(pipeline_module, 'run_analysis_worker', worker):
                    if interrupted:
                        with self.assertRaises(KeyboardInterrupt):
                            owner.process_song(item)
                    else:
                        self.assertEqual(owner.process_song(item), 'complete')
                state = owner.load_checkpoint(VIDEO_ID)
                stage = state['stages']['analysis']
                self.assertEqual(stage['status'], 'interrupted' if interrupted else 'complete')
                self.assertFalse(stage['cacheReused'])
                self.assertEqual(stage['cacheReason'], 'no-matching-producer-pcm-cache')
                self.assertEqual(stage['pcmSha256'], hashlib.sha256(pcm).hexdigest())
                self.assertGreaterEqual(stage['elapsedMs'], 20)
                if interrupted:
                    self.assertEqual(state['status'], 'interrupted')
                    failure = read_attempt_failure(owner)
                    self.assertEqual(failure['analysisStage'], stage)

    def test_acquisition_keeps_private_worker_and_timeout_causes_without_publication(self) -> None:
        for scenario in ('download', 'timeout', 'decode', 'stale-decode'):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
                root = Path(name)
                config = package_config(root)
                pipeline = StemwisePipeline(config)
                item = {'position': 1, 'videoId': VIDEO_ID, 'title': 'Song'}
                child = pipeline.child_path(VIDEO_ID)
                write_child(child, VIDEO_ID, config)
                previous = child.read_bytes()
                detail = 'private-' + scenario + '-cause'

                def launch(command, **_kwargs):
                    if scenario == 'timeout':
                        raise subprocess.TimeoutExpired('yt-dlp', 1, stderr=detail.encode())
                    if scenario in ('decode', 'stale-decode'):
                        if '_download-attempt' in command:
                            target = Path(command[-2])
                            target.write_text(json.dumps({'id': VIDEO_ID}))
                            (target.parent / 'source.m4a').write_bytes(b'compressed source')
                            (target.parent / 'analysis.wav').write_bytes(b'old PCM must not count' * 10)
                            return SimpleNamespace(returncode=0, stderr='')
                        return SimpleNamespace(returncode=1 if scenario == 'decode' else 0, stderr=detail)
                    stderr = io.StringIO()
                    with mock.patch.object(youtube, '_download_analysis_audio_attempt',
                                           side_effect=RuntimeError(detail)), redirect_stderr(stderr):
                        self.assertEqual(command[1:3], ['-m', 'stemwise_engine.youtube'])
                        self.assertEqual(command[3], '_download-attempt')
                        code = youtube._download_worker_main(command[4:])
                    return SimpleNamespace(returncode=code, stderr=stderr.getvalue())

                first, second, third = self.patches(pipeline)
                with first, second, third, mock.patch.object(
                        youtube, 'download_analysis_audio', youtube.download_analysis_audio), mock.patch.object(
                        youtube, 'run_process_tree', side_effect=launch):
                    self.assertEqual(pipeline.process_song(item, force=True), 'failed')
                state = pipeline.load_checkpoint(VIDEO_ID)
                self.assertEqual(state['status'], 'failed')
                self.assertEqual(child.read_bytes(), previous)
                self.assertNotIn(detail, json.dumps(state))
                record = read_attempt_failure(pipeline)
                self.assertEqual(record['errorType'], 'RuntimeError')
                self.assertIn(detail, record['privateDiagnostic'])
                self.assertLessEqual(len(record['privateDiagnostic'].encode()), 12000)

    def test_private_failures_are_bounded_retained_and_latest_is_a_reference(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            pipeline = StemwisePipeline(package_config(Path(name)))
            target = pipeline._attempt_failure_path(VIDEO_ID)
            pipeline._record_attempt_failure(VIDEO_ID, RuntimeError('old-' + '\u0627' * 20000), None)
            first = read_attempt_failure(pipeline)
            self.assertLessEqual(len(first['privateDiagnostic'].encode()), 12000)
            pipeline._record_attempt_failure(VIDEO_ID, ValueError('fresh-cause'), None)
            current = read_attempt_failure(pipeline)
            self.assertIn('fresh-cause', current['privateDiagnostic'])
            self.assertNotIn('\u0627', current['privateDiagnostic'])
            self.assertEqual(len(list((target.parent / 'runs').glob('*.json'))), 2)
            self.assertIn('Engine telemetry', target.parts)
            self.assertEqual(first['errorType'], 'RuntimeError')

    def patches(self, pipeline: StemwisePipeline):
        info = {"id": VIDEO_ID, "title": "Song", "uploader": "Artist"}
        return (
            mock.patch.object(youtube, "fetch_video_info", return_value=info),
            mock.patch.object(
                youtube,
                "metadata_from_info",
                side_effect=lambda _info, video_id: youtube_metadata(video_id),
            ),
            mock.patch.object(youtube, "create_cover_png", side_effect=create_cover),
        )

    def test_audio_acquisition_failure_is_retryable_and_preserves_last_child(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            item = {"position": 1, "videoId": VIDEO_ID, "title": "Song"}
            write_child(pipeline.child_path(VIDEO_ID), VIDEO_ID, config)
            previous_child = pipeline.child_path(VIDEO_ID).read_bytes()

            first, second, third = self.patches(pipeline)
            with first, second, third, mock.patch.object(
                youtube,
                "download_analysis_audio",
                side_effect=RuntimeError("temporary 403"),
            ):
                self.assertEqual(pipeline.process_song(item, force=True), "failed")

            state = pipeline.load_checkpoint(VIDEO_ID)
            self.assertEqual(state["status"], "failed")
            self.assertEqual(state["stages"]["temporary-audio"]["status"], "failed")
            self.assertEqual(pipeline.child_path(VIDEO_ID).read_bytes(), previous_child)
            self.assertFalse((pipeline.temp_root / VIDEO_ID).exists())

    def test_next_pass_retries_failed_acquisition_and_can_complete(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            item = {"position": 1, "videoId": VIDEO_ID, "title": "Song"}
            calls = 0

            def download(_video_id: str, temporary: Path, **_kwargs):
                nonlocal calls
                calls += 1
                if calls == 1:
                    raise RuntimeError("temporary 403")
                audio = temporary / "analysis.wav"
                audio.write_bytes(b"test-only placeholder")
                return {"id": VIDEO_ID, "title": "Song", "uploader": "Artist"}, audio

            first, second, third = self.patches(pipeline)
            with first, second, third, mock.patch.object(
                youtube, "download_analysis_audio", side_effect=download
            ), mock.patch.object(
                pipeline_module, "probe_duration_ms", return_value=1_000
            ), mock.patch.object(
                pipeline_module,
                "enrich_metadata",
                side_effect=lambda metadata, _duration, _client, **kw: (
                    metadata,
                    {"status": "no-match"},
                ),
            ), mock.patch.object(
                pipeline, "_analysis", return_value=song_document(VIDEO_ID)["analysis"]
            ):
                self.assertEqual(pipeline.process_song(item), "failed")
                self.assertIn('temporary 403', read_attempt_failure(pipeline)['privateDiagnostic'])
                self.assertEqual(pipeline.process_song(item), "complete")

            state = pipeline.load_checkpoint(VIDEO_ID)
            self.assertEqual(state["status"], "complete")
            self.assertEqual(state["attempt"], 2)
            self.assertTrue(pipeline.child_path(VIDEO_ID).is_file())
            self.assertFalse((pipeline.temp_root / VIDEO_ID).exists())

            self.assertIn('temporary 403', read_attempt_failure(pipeline)['privateDiagnostic'])

    def retained_rebuild_fixture(self, root):
        config = package_config(root)
        config['retainSuccessfulAnalysisAudio'] = True
        owner = StemwisePipeline(config)
        with mock.patch.object(pipeline_module, 'collect_provenance',
                return_value={'fixture': 'retained-rebuild-numerical-producer'}):
            owner._ensure_provenance()
        owner._provenance_needs_refresh = False
        item = {'position': 1, 'videoId': VIDEO_ID, 'title': 'Song'}
        def download(_video, directory, **_kwargs):
            audio = directory / 'analysis.wav'
            audio.write_bytes(b'exact retained PCM')
            return {'id': VIDEO_ID}, audio
        first, second, third = self.patches(owner)
        with first, second, third, mock.patch.object(youtube, 'download_analysis_audio', download), \
                mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value, *_, **kw: (value, {})), \
                mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']):
            self.assertEqual(owner.process_song(item), 'complete')
        owner.provenance_sha256 = 'b' * 64
        return owner, item

    def test_cleanup_rejection_preserves_completed_preparation_binding_and_bytes(self):
        from stemwise_engine.common import atomic_write_json, create_manifested_directory, manifested_cleanup_receipt, remove_manifested_directory
        for fault in ('path', 'scope-root', 'owner', 'receipt', 'prepared-receipt', 'replaced', 'callback', 'callback-receipt'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                owner, _ = self.retained_rebuild_fixture(Path(name))
                owner.config['retainSuccessfulAnalysisAudio'] = False
                temporary = owner.temp_root/VIDEO_ID
                target = temporary
                if fault == 'path':
                    target = Path(name)/'other'/VIDEO_ID
                    create_manifested_directory(target.parent, target,
                        owner={'kind': 'analysis-temporary', 'videoId': VIDEO_ID}, workspace_root=owner.workspace_root)
                elif fault == 'owner':
                    atomic_write_json(temporary/'.stemwise-generated.json', {'manifestVersion': 1,
                        'owner': {'kind': 'unrelated-owner'}, 'generatedPaths': ['.']})
                elif fault in ('receipt', 'prepared-receipt', 'replaced', 'callback-receipt'):
                    metadata = temporary.stat()
                    atomic_write_json(manifested_cleanup_receipt(temporary), {'manifest': {'manifestVersion': 1,
                        'owner': {'kind': 'analysis-temporary', 'videoId': VIDEO_ID}, 'generatedPaths': ['.']},
                        'path': str(temporary) if fault in ('replaced', 'callback-receipt') else str(temporary.parent/'wrong'),
                        'device': metadata.st_dev, 'inode': metadata.st_ino + int(fault == 'replaced')})
                checkpoint = owner.checkpoint_path(VIDEO_ID).read_bytes()
                protected = {path: path.read_bytes() for path in temporary.rglob('*') if path.is_file()}
                if fault.startswith('callback'):
                    with mock.patch.object(owner, '_retire_prepared_inputs', side_effect=ValueError('retirement failed')):
                        with self.assertRaisesRegex(ValueError, 'retirement failed'):
                            owner._cleanup_temp(target)
                else:
                    with self.assertRaises(ValueError):
                        if fault == 'scope-root':
                            remove_manifested_directory(temporary, temporary,
                                owner={'kind': 'analysis-temporary', 'videoId': VIDEO_ID}, workspace_root=owner.workspace_root,
                                before_remove=lambda: owner._retire_prepared_inputs(VIDEO_ID))
                        elif fault == 'receipt':
                            owner._temp_dir(VIDEO_ID)
                        elif fault == 'prepared-receipt':
                            owner.prepared_inputs(VIDEO_ID)
                        else:
                            owner._cleanup_temp(target)
                self.assertEqual(owner.checkpoint_path(VIDEO_ID).read_bytes(), checkpoint)
                self.assertEqual({path: path.read_bytes() for path in temporary.rglob('*') if path.is_file()}, protected)

    def test_completed_preparation_retirement_survives_process_death_before_byte_cleanup(self):
        import sys
        from stemwise_engine.common import run_process_tree
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            owner.config['retainSuccessfulAnalysisAudio'] = False
            temporary = owner.temp_root/VIDEO_ID
            child = owner.child_path(VIDEO_ID).read_bytes()
            script = '''import json,os,sys
from pathlib import Path
from stemwise_engine.pipeline import StemwisePipeline
import stemwise_engine.common as module
owner=StemwisePipeline(json.loads(sys.argv[1]))
temporary=Path(sys.argv[2])
remove=module.remove_disposable_path
def stop(root,path,**kwargs):
 if path==temporary: os._exit(37)
 return remove(root,path,**kwargs)
module.remove_disposable_path=stop
owner._cleanup_temp(temporary)
'''
            result = run_process_tree([sys.executable, '-B', '-c', script, json.dumps(owner.config), str(temporary)],
                timeout=20, text=True, capture_output=True)
            self.assertEqual(result.returncode, 37, result.stderr)
            self.assertTrue(temporary.is_dir())
            self.assertNotIn('preparedInputs', owner.load_checkpoint(VIDEO_ID))
            self.assertEqual(owner.child_path(VIDEO_ID).read_bytes(), child)
            self.assertIsNone(owner.prepared_inputs(VIDEO_ID))
            def download(_video, directory, **_kwargs):
                audio = directory/'analysis.wav'
                audio.write_bytes(b'renewed exact PCM')
                return {'id': VIDEO_ID}, audio
            first, second, third = self.patches(owner)
            with first, second, third, mock.patch.object(youtube, 'download_analysis_audio', download), \
                    mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value,*_,**kw: (value, {})), \
                    mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']):
                self.assertEqual(owner.process_song(item, force=True), 'complete')
            self.assertFalse(temporary.exists())
            self.assertNotIn('preparedInputs', owner.load_checkpoint(VIDEO_ID))

    def test_old_complete_preparation_binding_with_deleted_bytes_rebuilds_and_preserves_failed_replacement(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            owner.config['retainSuccessfulAnalysisAudio'] = False
            old = owner.load_checkpoint(VIDEO_ID)
            owner._cleanup_temp(owner.temp_root/VIDEO_ID)
            owner.save_checkpoint(old)
            owner._temp_dir(VIDEO_ID)
            self.assertIsNone(owner.prepared_inputs(VIDEO_ID))
            self.assertNotIn('preparedInputs', owner.load_checkpoint(VIDEO_ID))
            def download(_video, directory, **_kwargs):
                audio = directory/'analysis.wav'
                audio.write_bytes(b'new exact PCM')
                return {'id': VIDEO_ID}, audio
            first, second, third = self.patches(owner)
            with first, second, third, mock.patch.object(youtube, 'download_analysis_audio', download), \
                    mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda value,*_,**kw: (value, {})):
                with mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']):
                    self.assertEqual(owner.process_song(item, force=True), 'complete')
                before = owner.child_path(VIDEO_ID).read_bytes(), owner.checkpoint_path(VIDEO_ID).read_bytes()
                with mock.patch.object(owner, '_analysis', side_effect=ValueError('replacement failed')):
                    self.assertEqual(owner.process_song(item, force=True), 'failed')
                self.assertEqual((owner.child_path(VIDEO_ID).read_bytes(), owner.checkpoint_path(VIDEO_ID).read_bytes()), before)

    def test_package_revision_reuses_exact_committed_numerics_without_analysis(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            state = owner.load_checkpoint(VIDEO_ID)
            owner.provenance_sha256 = state['provenanceSha256']
            state['generatorVersion'] = 'stale-package-metadata'
            owner.save_checkpoint(state)
            before, cover = read_child(owner.child_path(VIDEO_ID), owner.config)
            with mock.patch.object(owner, '_analysis', side_effect=AssertionError('numerical inference repeated')), \
                    mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000):
                outcome = owner.process_song(item)
                self.assertEqual(outcome, 'complete', read_attempt_failure(owner) if outcome == 'failed' else None)
            after, current_cover = read_child(owner.child_path(VIDEO_ID), owner.config)
            self.assertEqual(after['analysis'], before['analysis'])
            self.assertEqual(current_cover, cover)
            self.assertEqual(after['generator']['version'], owner.config['generatorVersion'])

    def test_stale_analysis_rebuild_reuses_pcm_and_preserves_last_good_on_failure(self):
        for boundary in ('analysis', 'package', 'checkpoint'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
                owner, item = self.retained_rebuild_fixture(Path(name))
                child, checkpoint = owner.child_path(VIDEO_ID), owner.checkpoint_path(VIDEO_ID)
                before = child.read_bytes(), checkpoint.read_bytes()
                pcm = owner.temp_root / VIDEO_ID / 'analysis.wav'
                original_pcm = pcm.read_bytes()
                analysis = mock.Mock(return_value=song_document(VIDEO_ID)['analysis'])
                if boundary == 'analysis':
                    analysis.side_effect = RuntimeError('isolated fresh analyzer failure')
                write = pipeline_module.atomic_write_bytes
                def interrupt_checkpoint(path, data):
                    if Path(path) == checkpoint:
                        raise KeyboardInterrupt('process stopped after child replacement')
                    return write(path, data)
                with mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                        mock.patch.object(owner, '_analysis', analysis), \
                        mock.patch.object(owner, '_song_info', side_effect=AssertionError('no source reacquisition')), \
                        mock.patch.object(owner, '_acquire_analysis_audio', side_effect=AssertionError('no PCM reacquisition')), \
                        mock.patch.object(owner, '_create_cover', side_effect=AssertionError('preserve cover')):
                    if boundary == 'package':
                        with mock.patch.object(pipeline_module, 'create_child_zip', side_effect=RuntimeError('package write failed')):
                            self.assertEqual(owner.process_song(item, force=True), 'failed')
                    elif boundary == 'checkpoint':
                        with mock.patch.object(pipeline_module, 'atomic_write_bytes', side_effect=interrupt_checkpoint):
                            with self.assertRaises(KeyboardInterrupt):
                                owner.process_song(item, force=True)
                    else:
                        self.assertEqual(owner.process_song(item, force=True), 'failed')
                    if boundary != 'checkpoint':
                        self.assertEqual((child.read_bytes(), checkpoint.read_bytes()), before)
                    self.assertEqual(pcm.read_bytes(), original_pcm)
                    # New construction/read recovers an interrupted pair before exposing its checkpoint.
                    if boundary == 'checkpoint':
                        reader = StemwisePipeline(owner.config)
                        reader._ensure_provenance()
                        reader._provenance_needs_refresh = False
                        reader.provenance_sha256 = owner.provenance_sha256
                        with mock.patch.object(reader, '_analysis', side_effect=AssertionError('completed staged analyzer must not rerun')):
                            self.assertEqual(reader.process_song(item, force=True), 'complete')
                        result = reader.load_checkpoint(VIDEO_ID)
                    else:
                        analysis.reset_mock()
                        analysis.side_effect = None
                        self.assertEqual(owner.process_song(item, force=True), 'complete')
                        self.assertEqual(analysis.call_count, 1 if boundary == 'analysis' else 0)
                        result = owner.load_checkpoint(VIDEO_ID)
                    self.assertEqual(result['provenanceSha256'], 'b' * 64)
                    self.assertEqual(result['childSha256'], hashlib.sha256(child.read_bytes()).hexdigest())
                    _document, old_cover = read_child(child, owner.config)
                    self.assertEqual(old_cover, PNG_1X1)
                    self.assertEqual(pcm.read_bytes(), original_pcm)
                    self.assertFalse((owner._analysis_replacement_root(VIDEO_ID) / 'pending.json').exists())
                    self.assertEqual(list(owner._analysis_replacement_root(VIDEO_ID).iterdir()), [])

    def test_analysis_recovery_after_child_edit_still_requires_exact_retained_pcm(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            child = owner.child_path(VIDEO_ID)
            candidate = Path(name)/'metadata.zip'
            candidate.write_bytes(child.read_bytes())
            owner.commit_child_edit(VIDEO_ID, candidate, stage='metadata-review', binding={'reviewSha256': 'a'*64})
            recover = owner._recover_analysis_replacement
            def interrupt(video_id):
                if (owner._analysis_replacement_root(video_id)/'pending.json').exists():
                    raise RuntimeError('analysis prepared; promotion interrupted')
                return recover(video_id)
            with mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']), \
                    mock.patch.object(owner, '_recover_analysis_replacement', side_effect=interrupt):
                self.assertEqual(owner.process_song(item), 'failed')
            pending = owner._analysis_replacement_root(VIDEO_ID)/'pending.json'
            self.assertTrue(pending.exists())
            self.assertNotIn('editBinding', json.loads(pending.read_bytes()))
            audio = owner.temp_root/VIDEO_ID/'analysis.wav'
            audio.write_bytes(b'changed committed PCM')
            before = child.read_bytes(), owner.checkpoint_path(VIDEO_ID).read_bytes()
            with self.assertRaisesRegex(ValueError, 'retained analysis PCM'):
                owner.load_checkpoint(VIDEO_ID)
            self.assertEqual((child.read_bytes(), owner.checkpoint_path(VIDEO_ID).read_bytes()), before)
            self.assertEqual(audio.read_bytes(), b'changed committed PCM')
            self.assertTrue(pending.exists())

    def test_replacement_rejects_unknown_current_child_without_overwriting_it(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            recover = owner._recover_analysis_replacement
            def stop_pending(video_id):
                if (owner._analysis_replacement_root(video_id) / 'pending.json').exists():
                    raise RuntimeError('isolated publication interruption')
                return recover(video_id)
            with mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']), \
                    mock.patch.object(owner, '_recover_analysis_replacement', side_effect=stop_pending):
                self.assertEqual(owner.process_song(item), 'failed')
            child = owner.child_path(VIDEO_ID)
            child.write_bytes(b'unknown concurrent package bytes')
            with self.assertRaisesRegex(ValueError, 'conflicts'):
                owner.load_checkpoint(VIDEO_ID)
            self.assertEqual(child.read_bytes(), b'unknown concurrent package bytes')
            self.assertTrue((owner._analysis_replacement_root(VIDEO_ID) / 'pending.json').is_file())

    def _assert_analysis_replacement_cleanup_interruption(self, boundary):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            owner, item = self.retained_rebuild_fixture(Path(name))
            root = owner._analysis_replacement_root(VIDEO_ID)
            pending = root / 'pending.json'
            pcm = owner.temp_root / VIDEO_ID / 'analysis.wav'
            original_pcm = pcm.read_bytes()
            write_json = pipeline_module.atomic_write_json
            remove = pipeline_module.remove_manifested_directory
            def interrupt_marker(path, value):
                write_json(path, value)
                if Path(path) == pending and value.get('phase') == 'committed':
                    raise KeyboardInterrupt('process stopped after committed marker')
            def interrupt_cleanup(*args, **kwargs):
                remove(*args, **kwargs)
                raise KeyboardInterrupt('process stopped after attempt cleanup')
            fault = (mock.patch.object(pipeline_module, 'atomic_write_json', side_effect=interrupt_marker)
                     if boundary == 'commit-marker' else
                     mock.patch.object(pipeline_module, 'remove_manifested_directory', side_effect=interrupt_cleanup))
            with mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                    mock.patch.object(owner, '_analysis', return_value=song_document(VIDEO_ID)['analysis']) as analysis:
                with fault, self.assertRaises(KeyboardInterrupt):
                    owner.process_song(item, force=True)
                analysis.assert_called_once()
                transaction = json.loads(pending.read_bytes())
                self.assertEqual(transaction['phase'], 'committed')
                attempt = root / transaction['attemptId']
                self.assertEqual(attempt.exists(), boundary == 'commit-marker')
                child, checkpoint = owner.child_path(VIDEO_ID), owner.checkpoint_path(VIDEO_ID)
                committed = child.read_bytes(), checkpoint.read_bytes()
                self.assertEqual(hashlib.sha256(committed[0]).hexdigest(), transaction['newChildSha256'])
                self.assertEqual(hashlib.sha256(committed[1]).hexdigest(), transaction['newCheckpointSha256'])
                reader = StemwisePipeline(owner.config)
                reader.provenance_sha256 = owner.provenance_sha256
                with mock.patch.object(reader, '_analysis', side_effect=AssertionError('committed analyzer must not rerun')):
                    self.assertEqual(reader.process_song(item, force=True), 'complete')
                self.assertEqual((child.read_bytes(), checkpoint.read_bytes()), committed)
                self.assertEqual(pcm.read_bytes(), original_pcm)
                self.assertFalse(pending.exists())
                self.assertFalse(attempt.exists())
                self.assertEqual(list(root.iterdir()), [])

    def test_analysis_replacement_recovers_death_after_committed_marker(self):
        self._assert_analysis_replacement_cleanup_interruption('commit-marker')

    def test_analysis_replacement_recovers_death_after_attempt_cleanup(self):
        self._assert_analysis_replacement_cleanup_interruption('attempt-cleaned')

    def test_retained_analysis_refresh_preserves_complete_pair_when_pcm_is_missing_or_corrupt(self):
        for force in (False, True):
            for damage in ('missing', 'corrupt'):
                with self.subTest(force=force, damage=damage), tempfile.TemporaryDirectory(
                        dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
                    owner, item = self.retained_rebuild_fixture(Path(directory))
                    child, checkpoint = owner.child_path(VIDEO_ID), owner.checkpoint_path(VIDEO_ID)
                    previous = child.read_bytes(), checkpoint.read_bytes()
                    pcm = owner.temp_root / VIDEO_ID / 'analysis.wav'
                    if damage == 'missing':
                        pcm.unlink()
                    else:
                        pcm.write_bytes(b'retained damaged PCM for inspection')
                    retained = {path.name: path.read_bytes() for path in pcm.parent.iterdir()}
                    first, second, third = self.patches(owner)
                    with first, second, third, mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                            mock.patch.object(owner, '_acquire_analysis_audio', side_effect=AssertionError('must not reacquire PCM')) as acquire, \
                            mock.patch.object(owner, '_analysis', side_effect=AssertionError('must not rerun analyzer')) as analyze:
                        self.assertEqual(owner.process_song(item, force=force), 'failed')
                    acquire.assert_not_called()
                    analyze.assert_not_called()
                    failure = read_attempt_failure(owner)
                    self.assertEqual(failure['errorType'], 'RetainedAnalysisBindingError')
                    self.assertIn('retained analysis PCM differs', failure['privateDiagnostic'])
                    self.assertEqual((child.read_bytes(), checkpoint.read_bytes()), previous)
                    self.assertEqual({path.name: path.read_bytes() for path in pcm.parent.iterdir()}, retained)

    def test_future_workflow_retains_and_verifies_only_successful_analysis_pcm(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            config = package_config(root)
            config["retainSuccessfulAnalysisAudio"] = True
            pipeline = StemwisePipeline(config)
            item = {"position": 1, "videoId": VIDEO_ID, "title": "Song"}

            def download(_video_id: str, temporary: Path, **_kwargs):
                audio = temporary / "analysis.wav"
                audio.write_bytes(b"exact retained PCM")
                (temporary / "download-info.json").write_text("{}", encoding="utf-8")
                return {"id": VIDEO_ID, "title": "Song", "uploader": "Artist"}, audio

            first, second, third = self.patches(pipeline)
            with first, second, third, mock.patch.object(
                youtube, "download_analysis_audio", side_effect=download
            ), mock.patch.object(
                pipeline_module, "probe_duration_ms", return_value=1_000
            ), mock.patch.object(
                pipeline_module,
                "enrich_metadata",
                side_effect=lambda metadata, _duration, _client, **kw: (
                    metadata,
                    {"status": "no-match"},
                ),
            ), mock.patch.object(
                pipeline, "_analysis", return_value=song_document(VIDEO_ID)["analysis"]
            ):
                self.assertEqual(pipeline.process_song(item), "complete")
                retained = pipeline.require_retained_analysis_audio(VIDEO_ID)

            self.assertEqual(retained, pipeline.temp_root / VIDEO_ID / "analysis.wav")
            self.assertFalse((retained.parent / "download-info.json").exists())
            self.assertTrue(pipeline.load_checkpoint(VIDEO_ID)["originalAudio"]["retained"])
            before = dict(pipeline.load_checkpoint(VIDEO_ID)["originalAudio"])
            child_before = pipeline.child_path(VIDEO_ID).read_bytes()
            checkpoint_before = pipeline.checkpoint_path(VIDEO_ID).read_bytes()
            pcm_before = retained.read_bytes()
            with mock.patch.object(
                pipeline_module, 'probe_duration_ms', return_value=1_000
            ), mock.patch.object(pipeline, '_analysis', side_effect=RuntimeError('rebuild failed')) as analyze, mock.patch.object(
                pipeline, '_acquire_analysis_audio', side_effect=AssertionError('must reuse retained PCM')) as acquire:
                self.assertEqual(pipeline.process_song(item, force=True), 'failed')
            analyze.assert_called_once_with(retained, 1_000)
            acquire.assert_not_called()
            self.assertEqual(pipeline.child_path(VIDEO_ID).read_bytes(), child_before)
            self.assertEqual(pipeline.checkpoint_path(VIDEO_ID).read_bytes(), checkpoint_before)
            self.assertEqual(retained.read_bytes(), pcm_before)
            with mock.patch.object(
                pipeline_module,
                "probe_duration_ms",
                side_effect=RuntimeError("temporary probe failure"),
            ):
                self.assertEqual(pipeline.process_song(item, force=True), "failed")
            self.assertIn("temporary probe failure", read_attempt_failure(pipeline)["privateDiagnostic"])
            self.assertEqual(pipeline.child_path(VIDEO_ID).read_bytes(), child_before)
            self.assertEqual(pipeline.checkpoint_path(VIDEO_ID).read_bytes(), checkpoint_before)
            self.assertEqual(retained.read_bytes(), pcm_before)

            def manifest_read_failure(*_args, **_kwargs):
                raise ValueError("bounded read failed") from PermissionError(
                    "temporary manifest read failure"
                )

            with mock.patch.object(
                pipeline_module,
                "read_bounded_json",
                side_effect=manifest_read_failure,
            ):
                self.assertEqual(pipeline.process_song(item, force=True), "failed")
            self.assertIn("temporary manifest read failure", read_attempt_failure(pipeline)["privateDiagnostic"])
            self.assertEqual(pipeline.child_path(VIDEO_ID).read_bytes(), child_before)
            self.assertEqual(pipeline.checkpoint_path(VIDEO_ID).read_bytes(), checkpoint_before)
            self.assertEqual(retained.read_bytes(), pcm_before)
            with mock.patch.object(
                pipeline_module, "probe_duration_ms", return_value=1_000
            ):
                self.assertEqual(pipeline.require_retained_analysis_audio(VIDEO_ID), retained)
            sidecar = pipeline._lyrics_path(VIDEO_ID)
            sidecar.parent.mkdir(parents=True, exist_ok=True)
            sidecar.write_text(
                json.dumps(
                    {
                        "status": "unavailable",
                        "reason": "Authorized lyrics changed after analysis.",
                        "tracks": [],
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.object(
                youtube,
                "download_analysis_audio",
                side_effect=AssertionError("lyrics-only refresh must not download"),
            ), mock.patch.object(
                pipeline,
                "_analysis",
                side_effect=AssertionError("lyrics-only refresh must not analyze"),
            ), mock.patch.object(
                pipeline_module, "probe_duration_ms", return_value=1_000
            ):
                self.assertEqual(pipeline.process_song(item), "skipped")
                self.assertEqual(pipeline.require_retained_analysis_audio(VIDEO_ID), retained)
            self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)["originalAudio"], before)
            refreshed, _ = read_child(pipeline.child_path(VIDEO_ID), config)
            self.assertEqual(refreshed["lyrics"]["reason"], "Authorized lyrics changed after analysis.")
            repair_child_before = pipeline.child_path(VIDEO_ID).read_bytes()
            retained.write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "differs from its completed checkpoint"):
                pipeline.require_retained_analysis_audio(VIDEO_ID)
            with mock.patch.object(
                pipeline,
                "load_lyrics_snapshot",
                side_effect=RuntimeError("repair path entered"),
            ):
                self.assertEqual(pipeline.process_song(item, force=True), "failed")
            self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)["status"], "complete")
            with self.assertRaisesRegex(ValueError, 'differs from its completed checkpoint'):
                pipeline.require_retained_analysis_audio(VIDEO_ID)
            self.assertEqual(pipeline.child_path(VIDEO_ID).read_bytes(), repair_child_before)

    def test_full_rebuild_preserves_monotonic_revisions_when_clock_stops_or_regresses(self):
        from datetime import timezone
        for clock in ('2026-10-18T12:00:04Z', '2026-10-18T12:00:05Z'):
            with self.subTest(clock=clock), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
                pipeline = StemwisePipeline(package_config(Path(name)))
                item = {'position': 1, 'videoId': VIDEO_ID, 'title': 'Song'}
                def acquire(_video, temporary):
                    audio = temporary / 'analysis.wav'
                    audio.write_bytes(b'owned PCM fixture')
                    return {'id': VIDEO_ID, 'title': 'Song', 'uploader': 'Artist'}, audio
                first, second, third = self.patches(pipeline)
                with first, second, third, mock.patch.object(pipeline, '_acquire_analysis_audio', side_effect=acquire), \
                        mock.patch.object(pipeline_module, 'probe_duration_ms', return_value=1000), \
                        mock.patch.object(pipeline_module, 'enrich_metadata', side_effect=lambda metadata, *_, **kw: (metadata, {'status':'no-match'})), \
                        mock.patch.object(pipeline, '_analysis', return_value=song_document(VIDEO_ID)['analysis']):
                    with mock.patch.object(pipeline_module, 'utc_now', return_value='2026-10-18T12:00:05Z'):
                        self.assertEqual(pipeline.process_song(item), 'complete')
                    old, _ = read_child(pipeline.child_path(VIDEO_ID), pipeline.config)
                    item['title'] = 'Changed song'
                    with mock.patch.object(pipeline_module, 'utc_now', return_value=clock):
                        self.assertEqual(pipeline.process_song(item, force=True), 'complete')
                    current, _ = read_child(pipeline.child_path(VIDEO_ID), pipeline.config)
                self.assertGreater(datetime.fromisoformat(current['generatedAt'].replace('Z','+00:00')),
                                   datetime.fromisoformat(old['generatedAt'].replace('Z','+00:00')))

    def test_failed_package_commit_restores_exact_complete_child_and_checkpoint(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            item = {"position": 1, "videoId": VIDEO_ID, "title": "Song"}

            def download(_video_id: str, temporary: Path, **_kwargs):
                audio = temporary / "analysis.wav"
                audio.write_bytes(b"exact test PCM")
                return {"id": VIDEO_ID, "title": "Song", "uploader": "Artist"}, audio

            original_stage = pipeline._stage
            fail_package_commit = False

            def stage(*args, **kwargs):
                original_stage(*args, **kwargs)
                if fail_package_commit and args[1:3] == ("package", "complete"):
                    raise OSError("simulated checkpoint durability failure")

            first, second, third = self.patches(pipeline)
            with first, second, third, mock.patch.object(
                youtube, "download_analysis_audio", side_effect=download
            ), mock.patch.object(
                pipeline_module, "probe_duration_ms", return_value=1_000
            ), mock.patch.object(
                pipeline_module,
                "enrich_metadata",
                side_effect=lambda metadata, _duration, _client, **kw: (
                    metadata,
                    {"status": "no-match"},
                ),
            ), mock.patch.object(
                pipeline, "_analysis", return_value=song_document(VIDEO_ID)["analysis"]
            ), mock.patch.object(
                pipeline, "_stage", side_effect=stage
            ):
                self.assertEqual(pipeline.process_song(item), "complete")
                child_before = pipeline.child_path(VIDEO_ID).read_bytes()
                checkpoint_before = pipeline.checkpoint_path(VIDEO_ID).read_bytes()
                fail_package_commit = True
                self.assertEqual(pipeline.process_song(item, force=True), "failed")

            self.assertEqual(pipeline.child_path(VIDEO_ID).read_bytes(), child_before)
            self.assertEqual(
                pipeline.checkpoint_path(VIDEO_ID).read_bytes(),
                checkpoint_before,
            )
            self.assertFalse((pipeline.work_dir / "checkpoint-history").exists())
            failures = list((pipeline._attempt_failure_path(VIDEO_ID).parent / 'runs').glob('*.json'))
            self.assertEqual(len(failures), 1)

    def test_interrupted_private_rebuild_preserves_last_child(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            item = {"position": 1, "videoId": VIDEO_ID, "title": "Song"}
            child = pipeline.child_path(VIDEO_ID)
            write_child(child, VIDEO_ID, config)
            previous_child = child.read_bytes()

            with mock.patch.object(
                youtube, "fetch_video_info", side_effect=KeyboardInterrupt
            ):
                with self.assertRaises(KeyboardInterrupt):
                    pipeline.process_song(item, force=True)

            self.assertEqual(child.read_bytes(), previous_child)
            self.assertEqual(pipeline.load_checkpoint(VIDEO_ID)["status"], "interrupted")
            failure = read_attempt_failure(pipeline)
            self.assertEqual(failure['errorType'], 'KeyboardInterrupt')


class LyricsSnapshotTests(unittest.TestCase):
    @staticmethod
    def sidecar(reason: str) -> dict:
        return {"status": "unavailable", "reason": reason, "tracks": []}

    def test_generator_change_never_uses_lyrics_only_refresh(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            old_config = package_config(root)
            old_pipeline = StemwisePipeline(old_config)
            write_child(old_pipeline.child_path(VIDEO_ID), VIDEO_ID, old_config)
            old_pipeline.save_checkpoint(
                {
                    "videoId": VIDEO_ID,
                    "status": "complete",
                    "generatorVersion": old_config["generatorVersion"],
                    "ownerLyricsSha256": "absent",
                }
            )

            config = dict(old_config, generatorVersion="test-2")
            pipeline = StemwisePipeline(config)
            lyrics_path = pipeline._lyrics_path(VIDEO_ID)
            lyrics_path.parent.mkdir(parents=True, exist_ok=True)
            lyrics_path.write_text(json.dumps(self.sidecar("new lyrics state")), encoding="utf-8")
            lyrics, fingerprint = pipeline.load_lyrics_snapshot(VIDEO_ID)

            with mock.patch.object(pipeline, "_refresh_lyrics_only") as refresh:
                can_skip = pipeline._can_skip(
                    {"position": 1, "videoId": VIDEO_ID},
                    force=False,
                    lyrics=lyrics,
                    owner_lyrics_sha256=fingerprint,
                )
            self.assertFalse(can_skip)
            refresh.assert_not_called()

    def test_owner_sidecar_symlink_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            pipeline = StemwisePipeline(package_config(root))
            outside = root / "outside.json"
            outside.write_text(json.dumps(self.sidecar("external")), encoding="utf-8")
            sidecar = pipeline._lyrics_path(VIDEO_ID)
            sidecar.parent.mkdir(parents=True, exist_ok=True)
            try:
                sidecar.symlink_to(outside)
            except OSError as error:
                self.skipTest(f"symlinks are unavailable: {error}")

            with self.assertRaisesRegex(ValueError, "symlink|escapes"):
                pipeline.load_lyrics_snapshot(VIDEO_ID)

    def test_owner_sidecar_uses_strict_v2_json_only(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            pipeline = StemwisePipeline(package_config(Path(name)))
            sidecar = pipeline._lyrics_path(VIDEO_ID)
            sidecar.parent.mkdir(parents=True, exist_ok=True)
            payloads = (
                b'\xef\xbb\xbf{"status":"unavailable","reason":"x","tracks":[]}',
                b'{"status":"unavailable","status":"unavailable","reason":"x","tracks":[]}',
                b'{"status":"unavailable","reason":"x","tracks":[],"number":NaN}',
                json.dumps({"lyrics": self.sidecar("wrapped v1 shape")}).encode("utf-8"),
            )
            for payload in payloads:
                with self.subTest(payload=payload[:24]):
                    sidecar.write_bytes(payload)
                    with self.assertRaises(ValueError):
                        pipeline.load_lyrics_snapshot(VIDEO_ID)

    def test_refresh_hash_describes_the_exact_packaged_byte_snapshot(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            config = package_config(root)
            pipeline = StemwisePipeline(config)
            write_child(pipeline.child_path(VIDEO_ID), VIDEO_ID, config)
            before, _cover = read_child(pipeline.child_path(VIDEO_ID), config)
            lyrics_path = pipeline._lyrics_path(VIDEO_ID)
            lyrics_path.parent.mkdir(parents=True, exist_ok=True)
            lyrics_path.write_text(json.dumps(self.sidecar("snapshot A")), encoding="utf-8")
            lyrics_a, hash_a = pipeline.load_lyrics_snapshot(VIDEO_ID)
            lyrics_path.write_text(json.dumps(self.sidecar("snapshot B")), encoding="utf-8")

            state = pipeline._new_state(
                {"position": 1, "videoId": VIDEO_ID, "title": "Song"},
                hash_a,
            )
            # A lyrics refresh edits an already published child/checkpoint pair.
            state.update(status="complete", childSha256=hashlib.sha256(
                pipeline.child_path(VIDEO_ID).read_bytes()).hexdigest())
            pipeline.checkpoint_path(VIDEO_ID).parent.mkdir(parents=True, exist_ok=True)
            pipeline.checkpoint_path(VIDEO_ID).write_text(json.dumps(state), encoding="utf-8")
            pipeline._refresh_lyrics_only(VIDEO_ID, state, lyrics_a, hash_a)
            document, _cover = read_child(pipeline.child_path(VIDEO_ID), config)

            self.assertEqual(document["lyrics"]["reason"], "snapshot A")
            self.assertGreater(
                datetime.fromisoformat(document["generatedAt"].replace("Z", "+00:00")),
                datetime.fromisoformat(before["generatedAt"].replace("Z", "+00:00")),
            )
            self.assertEqual(state["ownerLyricsSha256"], hash_a)
            self.assertNotEqual(pipeline.lyrics_fingerprint(VIDEO_ID), hash_a)

class ProcessLockTests(unittest.TestCase):



    def test_pc_heavy_worker_lock_is_shared_across_output_roots(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            project = Path(name).resolve()
            first = heavy_worker_lock_path(
                {"projectDir": str(project), "outputDir": str(project / "one")}
            )
            second = heavy_worker_lock_path(
                {"projectDir": str(project), "outputDir": str(project / "two")}
            )
            self.assertEqual(first, second)
            with exclusive_process_lock(first):
                with self.assertRaises(ProcessLockBusyError):
                    with exclusive_process_lock(second, wait_seconds=0):
                        pass

    def test_exclusive_process_lock_rejects_a_second_holder(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            path = Path(name) / "stemwise.lock"
            with exclusive_process_lock(path):
                with self.assertRaisesRegex(RuntimeError, "already active"):
                    with exclusive_process_lock(path):
                        self.fail("the second lock holder must never run")

    def test_exclusive_process_lock_can_wait_for_a_bounded_queue(self) -> None:
        import fcntl
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            path = Path(name) / "stemwise.lock"
            blocked, admitted = threading.Event(), threading.Event()
            failures = []
            actual_flock = fcntl.flock
            def observe(*args):
                try:
                    return actual_flock(*args)
                except BlockingIOError:
                    blocked.set()
                    raise
            def waiter():
                try:
                    with exclusive_process_lock(path, wait_seconds=3):
                        admitted.set()
                except BaseException as error:
                    failures.append(error)
            thread = threading.Thread(target=waiter)
            try:
                with exclusive_process_lock(path), mock.patch.object(fcntl, 'flock', side_effect=observe):
                    thread.start()
                    self.assertTrue(blocked.wait(2), 'Contender did not reach the actual held lock')
                    self.assertFalse(admitted.is_set())
            finally:
                if thread.ident is not None:
                    thread.join(timeout=5)
            self.assertFalse(thread.is_alive(), 'Queued contender did not retire')
            self.assertEqual(failures, [])
            self.assertTrue(admitted.is_set())


@unittest.skipUnless(os.name == 'posix', 'production worker leases use WSL flock')
class AcousticWorkerLeaseTests(unittest.TestCase):
    def _spawn_fixture(self, command, **options):
        from stemwise_engine.runtime_environment import command_environment
        with command_environment(command, options) as admitted:
            return subprocess.Popen(command, **admitted)

    def _fixture_environment(self, project):
        # Override only the numerical boundary; execute the installed worker and lease owner.
        package = project / 'fixture-packages' / 'stemwise_engine'
        package.mkdir(parents=True)
        (package / '__init__.py').write_text(
            f'__path__ = {[str(project / "src"), str(PROJECT_ROOT / "src")]!r}\n', encoding='utf-8')
        return {**os.environ, 'PYTHONPATH': str(package.parent), 'PYTHONDONTWRITEBYTECODE': '1'}

    def test_forged_inherited_lease_is_rejected_before_child_work(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / 'Test suite/work') as name:
            project = Path(name)
            expected = project / 'stemwise-heavy-worker.lock'
            unrelated = project / 'owner-file'
            expected.write_bytes(b'preserved lock file')
            unrelated.write_bytes(b'preserved owner file')
            with unrelated.open('rb') as handle:
                environment = {**os.environ, 'PYTHONPATH': str(PROJECT_ROOT / 'src'),
                               'PYTHONDONTWRITEBYTECODE': '1',
                               'STEMWISE_PROCESS_LOCK_LEASES': json.dumps([[str(expected), handle.fileno()]])}
                result = subprocess.run([sys.executable, '-B', '-c', 'import stemwise_engine.common as common;print("work started")'],
                                        env=environment, pass_fds=(handle.fileno(),), capture_output=True,
                                        text=True, timeout=5)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn('work started', result.stdout)
            self.assertEqual(expected.read_bytes(), b'preserved lock file')
            self.assertEqual(unrelated.read_bytes(), b'preserved owner file')

    def test_killed_acoustic_worker_retires_allinone_before_lease_reuse(self):
        self._assert_nested_worker_lease(intermediate=False)

    def test_killed_python_intermediate_retires_its_heavy_descendant(self):
        self._assert_nested_worker_lease(intermediate=True)

    def _assert_nested_worker_lease(self, *, intermediate):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / 'Test suite/work') as name:
            project = Path(name)
            source = project / 'src'
            source.mkdir()
            heartbeat = project / 'allinone-heartbeat'
            worker_pid = project / 'acoustic-worker.pid'
            intermediate_pid = project / 'intermediate.pid'
            descendant_code = ('import os,sys,time\nfrom pathlib import Path\n'
                               'path=Path(sys.argv[1]);path.write_text(str(os.getpid())+"\\n")\n'
                               'while True:\n with path.open("a") as out: out.write("x")\n time.sleep(.03)\n')
            launched_code = descendant_code
            if intermediate:
                launched_code = ('import os,sys\nfrom pathlib import Path\nfrom stemwise_engine.common import run_process_tree\n'
                                 f'Path({str(intermediate_pid)!r}).write_text(str(os.getpid()))\n'
                                 f'run_process_tree([sys.executable,"-u","-c",{descendant_code!r},sys.argv[1]],'
                                 'timeout=60,capture_output=True)\n')
            (source / 'analysis_numerics.py').write_text(
                'import os,sys\nfrom pathlib import Path\nfrom stemwise_engine.common import run_process_tree\n'
                'def _analysis_worker(audio,*args):\n'
                f' Path({str(worker_pid)!r}).write_text(str(os.getpid()))\n'
                f' script={launched_code!r}\n'
                ' return run_process_tree([sys.executable,"-u","-c",script,str(audio)],'
                'timeout=60,capture_output=True)\n')
            parent_code = (
                'from pathlib import Path\nfrom stemwise_engine.common import exclusive_process_lock,heavy_worker_lock_path\n'
                'from stemwise_engine.pipeline import run_analysis_worker\n'
                f'project=Path({str(project)!r})\n'
                'with exclusive_process_lock(heavy_worker_lock_path({"projectDir":str(project)})):\n'
                f' run_analysis_worker(Path({str(heartbeat)!r}),1000,project,30)\n')
            environment = self._fixture_environment(project)
            parent = self._spawn_fixture([sys.executable, '-B', '-c', parent_code], env=environment,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            worker = descendant = middle = None
            births = {}
            try:
                deadline = time.monotonic() + 10
                while not heartbeat.exists() and time.monotonic() < deadline and parent.poll() is None:
                    time.sleep(.03)
                self.assertTrue(heartbeat.exists(), parent.stderr.read() if parent.poll() is not None
                                else 'Nested worker did not start')
                worker = int(worker_pid.read_text())
                descendant = int(heartbeat.read_text().splitlines()[0])
                births.update({pid:process_birth(pid) for pid in (worker,descendant)})
                terminate_exact(worker,births[worker])
                if intermediate:
                    middle = int(intermediate_pid.read_text())
                    births[middle] = process_birth(middle)
                    try: terminate_exact(middle,births[middle])
                    except ProcessLookupError: pass
                parent.wait(timeout=3)
                from stemwise_engine.common import ProcessLockBusyError, exclusive_process_lock, heavy_worker_lock_path
                with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)}), wait_seconds=3):
                    pass
                self._assert_worker_stopped(descendant)
            finally:
                for process_id in (descendant, middle, worker):
                    if process_id is not None:
                        try:
                            terminate_exact(process_id,births.get(process_id))
                        except ProcessLookupError:
                            pass
                if parent.poll() is None:
                    parent.kill()
                parent.communicate(timeout=3)

    def _assert_worker_stopped(self, worker_pid):
        deadline = time.monotonic() + 3
        path = Path(f'/proc/{worker_pid}/stat')
        while path.exists() and time.monotonic() < deadline:
            time.sleep(.02)
        self.assertFalse(path.exists(), 'model worker outlived its owner')

    def test_hard_killed_supervisor_retires_worker_before_lease_reuse(self):
        work = PROJECT_ROOT.parent.parent / 'Test suite/work'
        with tempfile.TemporaryDirectory(dir=work) as name:
            project = Path(name)
            source = project / 'src'
            source.mkdir()
            heartbeat = project / 'worker-heartbeat'
            (source / 'analysis_numerics.py').write_text(
                'import os,time\n'
                'def _analysis_worker(audio,*args):\n'
                ' audio.write_text(str(os.getpid())+"\\n")\n'
                ' while True:\n'
                '  with audio.open("a") as output: output.write("x")\n'
                '  time.sleep(.03)\n')
            parent_code = (
                'import sys\nfrom pathlib import Path\nfrom stemwise_engine.common import exclusive_process_lock,heavy_worker_lock_path\nfrom stemwise_engine.pipeline import run_analysis_worker\nproject=Path(sys.argv[1])\nwith exclusive_process_lock(heavy_worker_lock_path({"projectDir":str(project)})):\n run_analysis_worker(project/"worker-heartbeat",1000,project,30)\n')
            environment = self._fixture_environment(project)
            parent = self._spawn_fixture([sys.executable, '-B', '-c', parent_code, str(project)],
                                      env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            worker_pid = None
            worker_birth = None
            try:
                deadline = time.monotonic() + 10
                while not heartbeat.exists() and parent.poll() is None and time.monotonic() < deadline:
                    time.sleep(.03)
                self.assertTrue(heartbeat.exists(), parent.stderr.read().decode() if parent.poll() is not None
                                else 'actual production worker did not start')
                worker_pid = int(heartbeat.read_text().splitlines()[0])
                worker_birth = process_birth(worker_pid)
                parent.kill()
                parent.wait(timeout=3)
                with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(project)}), wait_seconds=3):
                    pass
                self._assert_worker_stopped(worker_pid)
            finally:
                if worker_pid is not None:
                    try: terminate_exact(worker_pid,worker_birth)
                    except ProcessLookupError: pass
                if parent.poll() is None:
                    parent.kill()
                parent.communicate(timeout=3)


class AnalysisDeadlineTests(unittest.TestCase):
    def test_worker_exit_and_protocol_failures_reach_existing_private_failure_record(self):
        from stemwise_engine.common import exclusive_process_lock, heavy_worker_lock_path
        for exit_code, stdout, reason in ((23, '', 'worker-exit'),
                                          (0, '', 'result-marker-invalid'),
                                          (0, 'STEMWISE_ANALYSIS_RESULT={bad', 'result-json-invalid')):
            with self.subTest(exit_code=exit_code, reason=reason):
                completed = subprocess.CompletedProcess(['numerical-worker'], exit_code, stdout, 'private numerical cause')
                with exclusive_process_lock(heavy_worker_lock_path({'projectDir': str(self.project)})), \
                        mock.patch.object(pipeline_module, 'run_process_tree', return_value=completed):
                    with self.assertRaises(RuntimeError) as failure:
                        pipeline_module._run_analysis_single(self.audio, 1000, self.project, 5)
                owner = StemwisePipeline(package_config(self.project))
                owner._record_attempt_failure(VIDEO_ID, failure.exception, None)
                record = read_attempt_failure(owner)
                self.assertEqual(record['workerExitCode'], exit_code)
                self.assertEqual(record['workerFailure'], reason)
                self.assertIn('private numerical cause', record['privateDiagnostic'])

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / 'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.project = Path(temporary.name)
        self.audio = self.project / 'analysis.wav'
        self.audio.write_bytes(b'admitted test input')
        self.producer = {'acousticRuntime': {'execution': {'main': {'device': 'cpu', 'torch': 'fixture'}}}}
        environment = mock.patch.dict(os.environ, {'PYTHONPATH': str(PROJECT_ROOT / 'src')})
        environment.start()
        self.addCleanup(environment.stop)

    def test_analysis_bundle_runs_in_bounded_process_tree(self) -> None:
        pipeline = StemwisePipeline(package_config(self.project) | {"analysisTimeoutMinutes": 1})
        analysis = song_document(VIDEO_ID)['analysis']
        completed = subprocess.CompletedProcess(
            [], 0, 'STEMWISE_ANALYSIS_RESULT='+json.dumps(analysis)+'\n', ""
        )
        import stemwise_engine.analysis_numerics as analysis_numerics
        with (
            mock.patch.object(pipeline_module, "collect_provenance", return_value=self.producer),
            mock.patch.object(
                pipeline_module, "run_process_tree", return_value=completed
            ) as run,
            mock.patch.object(
                analysis_numerics,
                "analyze_beat_this",
                side_effect=AssertionError(
                    "analyzers must not run in the queue process"
                ),
            ),
        ):
            self.assertEqual(
                pipeline._analysis(self.audio, 1_000),
                analysis,
            )
        self.assertEqual(run.call_args.kwargs["timeout"], 60)
        self.assertTrue(run.call_args.kwargs["capture_output"])
        worker_environment = run.call_args.kwargs["env"]
        self.assertEqual(run.call_args.args[0][1:5],
                         ['-B', '-m', 'stemwise_engine.analysis_worker', 'single'])
        self.assertEqual(worker_environment['PYTHONPATH'], os.environ['PYTHONPATH'])
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            imported = pipeline_module.run_process_tree(
                [sys.executable, "-c", 'import stemwise_engine.pipeline as pipeline;print(pipeline.__file__)'],
                cwd=Path(name),
                text=True,
                capture_output=True,
                check=True,
                timeout=10,
                env=worker_environment,
            )
        self.assertEqual(
            Path(imported.stdout.strip()).resolve(),
            Path(pipeline_module.__file__).resolve(),
        )

    def test_analysis_timeout_propagates_after_process_tree_cleanup(self) -> None:
        pipeline = StemwisePipeline(package_config(self.project) | {"analysisTimeoutMinutes": 1})
        with mock.patch.object(pipeline_module, "collect_provenance", return_value=self.producer), mock.patch.object(
            pipeline_module,
            "run_process_tree",
            side_effect=subprocess.TimeoutExpired("analysis-worker", 60),
        ):
            with self.assertRaises(subprocess.TimeoutExpired):
                pipeline._analysis(self.audio, 1_000)

    @unittest.skipIf(os.name == "nt", "process-group assertion requires the WSL runtime")
    def test_timeout_terminates_the_worker_process_tree(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            child_pid_path = Path(name) / "child.pid"
            script = '\nimport signal,sys\nfrom stemwise_engine.common import run_process_tree\ndef terminate(*_): raise KeyboardInterrupt\nsignal.signal(signal.SIGTERM,terminate)\nchild=\'import os,sys,time;from pathlib import Path;Path(sys.argv[1]).write_text(str(os.getpid()),encoding="ascii");time.sleep(60)\'\nrun_process_tree([sys.executable,"-c",child,sys.argv[1]],timeout=60)\n'
            with self.assertRaises(subprocess.TimeoutExpired):
                pipeline_module.run_process_tree(
                    [sys.executable, "-c", script, str(child_pid_path)],
                    text=True,
                    capture_output=True,
                    timeout=5,
                    env={
                        **os.environ,
                        "PYTHONPATH": str(
                            Path(pipeline_module.__file__).resolve().parent
                        ),
                    },
                )
            child_pid = int(child_pid_path.read_text(encoding="ascii"))
            for _ in range(20):
                if not Path(f"/proc/{child_pid}").exists():
                    break
                time.sleep(0.1)
            self.assertFalse(Path(f"/proc/{child_pid}").exists())




if __name__ == "__main__":
    unittest.main()
