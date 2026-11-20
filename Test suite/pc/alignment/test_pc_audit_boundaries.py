"""Real publication/admission boundaries; model execution is unnecessary here."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np
import soundfile as sf

from test_future_song import WORKSPACE, PROJECT
from stemwise_engine.alignment_attempt import AttemptInputs, RouteOutcomeLedger
from stemwise_engine.common import atomic_write_json
from stemwise_engine.editorial_admission import unresolved_qa_issues
from stemwise_engine.source_acquisition import collect_normal_youtube_candidates
from stemwise_engine.future_song import FutureSongWorkflow
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, commit_candidate
import stemwise_engine.worker_protocol as worker_protocol
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.lyrics_align_pc as align
from stemwise_engine.lyrics_qa import audit_text
from stemwise_engine.mms_forced_alignment import _romanized_words
from stemwise_engine.private_diagnostics import retain
from stemwise_engine.source_crop import ensure_source_crop
import stemwise_engine.worker_publication as worker_publication


import stemwise_engine.workflow_diagnostics as workflow_diagnostics

def setUpModule():
    from unit.workflow_fixture import isolate_pipeline_runtime
    isolate_pipeline_runtime()


class PcAuditBoundaryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=WORKSPACE / 'Test suite/work')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_future_alignment_startup_failure_keeps_private_trace_before_worker_start(self):
        import contextlib
        import io
        import stemwise_engine.recording_source as recording_source
        video_id, attempt_id = 'abcdefghijk', 'a'*32
        project, future, analysis = self.root/'pc/builder', self.root/'future', self.root/'analysis'
        project.mkdir(parents=True)
        audio = analysis/video_id/'analysis.wav'
        audio.parent.mkdir(parents=True)
        audio.write_bytes(b'fixture')
        job_path = future/'jobs'/video_id/'job.json'
        job_path.parent.mkdir(parents=True)
        job_path.write_text(json.dumps({'videoId': video_id, 'state': 'ALIGNING_LYRICS',
            'activeAlignmentAttemptId': attempt_id, 'recordingInput': {'recordingKey': 'qobuz:12'}}))
        before = job_path.read_bytes()
        config = {'projectDir': str(project), 'outputDir': str(self.root/'output')}
        stderr = io.StringIO()
        with patch.object(align, 'load_config', return_value=config), \
                patch.object(recording_source, 'alignment_config', side_effect=ModuleNotFoundError('private-startup-cause')), \
                patch.object(align, 'model_operation_slot', return_value=contextlib.nullcontext()), \
                patch.object(align, '_run_future_alignment_attempt') as run, contextlib.redirect_stderr(stderr):
            result = align.main(['--config', str(project/'config.json'), 'future-align', '--device', 'cpu',
                f'--video-id={video_id}', f'--attempt-id={attempt_id}',
                f'--analysis-audio={audio}', f'--future-root={future}', f'--analysis-root={analysis}',
                '--expected-mix-sha256='+'b'*64, '--editorial-sha256='+'c'*64,
                '--canonical-plain-sha256='+'d'*64])
        self.assertEqual(result, 2)
        run.assert_not_called()
        self.assertNotIn('private-startup-cause', stderr.getvalue())
        diagnostic = future/'lyrics-alignment/jobs'/video_id/f'attempt-{attempt_id}.failure.private.txt'
        self.assertTrue(diagnostic.is_file())
        self.assertIn(b'private-startup-cause', diagnostic.read_bytes())
        self.assertLessEqual(diagnostic.stat().st_size, 12000)
        self.assertEqual(job_path.read_bytes(), before)



    def test_model_registry_matches_advertised_editorial_runtime_asset_and_policy_views(self):
        import stemwise_engine.mms_asr as mms_asr, stemwise_engine.model_registry as model_registry, stemwise_engine.lyrics_qa as lyrics_qa, stemwise_engine.owsm_ctc_forced_alignment as owsm_ctc_forced_alignment
        request = dict(profiles=lyrics_qa._LANGUAGE_PROFILES,native_assets=mms_asr.ASSETS_BY_ADAPTER,
            owsm_routes=owsm_ctc_forced_alignment.ROUTES_BY_LANGUAGE)
        result = model_registry.validate_runtime_views(**request)
        self.assertIn('zu',result['editorialLanguages'])
        self.assertIn('zul',result['nativeAdapters'])
        changed = copy.deepcopy(request['native_assets'])
        changed['zul']['config.json'] = '0'*64
        with self.assertRaises(ValueError):
            model_registry.validate_runtime_views(**{**request,'native_assets':changed})
        with self.assertRaises(ValueError):
            model_registry.validate_runtime_views(**{**request,'owsm_routes':{}})

    def test_owned_socket_result_survives_retirement_longer_than_transport_timeout(self):
        import stemwise_engine.analysis_queue as queue
        import os
        import socket
        import threading
        import time
        import uuid
        audio = self.root / 'audio.wav'
        audio.write_bytes(b'bound source')
        request = {'token': uuid.uuid4().hex, 'audio': str(audio),
            'audioSha256': hashlib.sha256(audio.read_bytes()).hexdigest(),
            'timeout': 15, 'created': time.monotonic_ns() - 300_000_000_000}
        atomic_write_json(self.root / 'request.json', request)
        response = {'requestSha256': hashlib.sha256(queue.json_bytes(request)).hexdigest(),
                    'analysis': {'durationMs': 1000}}
        errors = []
        actual_retire = queue._retire_request
        def slow_retire(*args):
            result = actual_retire(*args)
            threading.Event().wait(5.2)
            return result
        def send():
            try:
                queue._send(request, response)
            except BaseException as error:
                errors.append(error)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(queue._address(request['token']))
            listener.listen()
            listener.settimeout(15)
            sender = threading.Thread(target=send)
            with patch.object(queue, '_peer_has_model_lease', return_value=True), \
                    patch.object(queue, '_retire_request', side_effect=slow_retire):
                sender.start()
                try:
                    result = queue._receive(listener, self.root/'lease', self.root, request)
                finally:
                    sender.join(timeout=20)
        self.assertFalse(sender.is_alive())
        self.assertEqual(errors, [])
        self.assertEqual(result, response)
        self.assertTrue((self.root / 'retired.json').is_file())

    def test_complete_socket_response_remains_owned_after_producer_disconnects(self):
        import stemwise_engine.analysis_queue as queue
        import socket
        import struct
        import threading
        import time
        import uuid
        request = {'token': uuid.uuid4().hex, 'timeout': 15, 'created': time.monotonic_ns()}
        atomic_write_json(self.root / 'request.json', request)
        response = {'requestSha256': hashlib.sha256(queue.json_bytes(request)).hexdigest(), 'error': 'fixture-error'}
        def send_then_close():
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.connect(queue._address(request['token']))
                self.assertEqual(connection.recv(1), b'1')
                raw = queue.json_bytes(response)
                connection.sendall(struct.pack('!Q', len(raw)) + raw)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(queue._address(request['token']))
            listener.listen()
            listener.settimeout(15)
            sender = threading.Thread(target=send_then_close)
            with patch.object(queue, '_peer_has_model_lease', return_value=True):
                sender.start()
                try:
                    result = queue._receive(listener, self.root/'lease', self.root, request)
                finally:
                    sender.join(timeout=20)
        self.assertFalse(sender.is_alive())
        self.assertEqual(result, response)
        self.assertTrue((self.root / 'retired.json').is_file())

    def test_source_candidate_retry_preserves_original_referenced_bytes(self):
        destination = self.root / 'candidates'
        first = collect_normal_youtube_candidates({'id': 'AAAAAAAAAAA', 'description': 'original provider body'},
            destination, video_id='AAAAAAAAAAA')
        old_path = Path(first['records'][0]['path'])
        before = old_path.read_bytes()
        second = collect_normal_youtube_candidates({'id': 'AAAAAAAAAAA', 'description': 'changed provider body'},
            destination, video_id='AAAAAAAAAAA')
        self.assertEqual(old_path.read_bytes(), before)
        self.assertNotEqual(first['records'][0]['path'], second['records'][0]['path'])

    def test_commit_rejects_edit_during_inference_without_touching_it(self):
        workspace = AlignmentWorkspace(self.root)
        sidecar = self.root / 'owner/AAAAAAAAAAA.lyrics.json'
        sidecar.parent.mkdir()
        sidecar.write_bytes(b'new owner bytes')
        with self.assertRaisesRegex(ValueError, 'Owner sidecar changed'):
            commit_candidate(workspace, 'AAAAAAAAAAA', sidecar, b'old bytes', {'candidate': 1})
        self.assertEqual(sidecar.read_bytes(), b'new owner bytes')

    def test_edit_after_last_check_is_retained_as_explicit_publication_conflict(self):
        workspace = AlignmentWorkspace(self.root)
        sidecar = self.root / 'owner/AAAAAAAAAAA.lyrics.json'
        sidecar.parent.mkdir()
        sidecar.write_bytes(b'old bytes')
        actual_replace = worker_publication.replace_preserving_previous
        def edit_then_replace(destination, replacement, displaced):
            destination.write_bytes(b'new owner bytes')
            return actual_replace(destination, replacement, displaced)
        with patch.object(worker_publication, 'replace_preserving_previous', side_effect=edit_then_replace):
            with self.assertRaisesRegex(ValueError, 'publication conflict requires recovery'):
                commit_candidate(workspace, 'AAAAAAAAAAA', sidecar, b'old bytes', {'candidate': 1})
        retained = [path for path in sidecar.parent.iterdir() if path != sidecar]
        self.assertTrue(any(path.read_bytes() == b'new owner bytes' for path in retained))

    def test_synced_realign_compares_synced_input_and_preserves_plain_authority(self):
        workspace = AlignmentWorkspace(self.root)
        sidecar = self.root / 'owner/AAAAAAAAAAA.lyrics.json'
        sidecar.parent.mkdir()
        sidecar.write_bytes(b'synced owner bytes')
        backup, _ = commit_candidate(workspace, 'AAAAAAAAAAA', sidecar,
            b'canonical plain bytes', {'candidate': 1}, expected_current_raw=b'synced owner bytes')
        self.assertEqual(backup.read_bytes(), b'canonical plain bytes')

    def test_interrupted_crop_rebuilds_once_but_committed_corruption_is_preserved(self):
        path = self.root / 'source.wav'
        path.write_bytes(b'RIFF-incomplete')
        samples = np.full((480, 2), .25, dtype=np.float32)
        ensure_source_crop(path, samples, 48000, soundfile=sf, numpy=np)
        self.assertTrue(path.with_suffix('.crop.json').is_file())
        original = path.read_bytes()
        with patch.object(sf, 'write', side_effect=AssertionError('No rewrite')):
            ensure_source_crop(path, samples, 48000, soundfile=sf, numpy=np)
        path.write_bytes(original[:-10])
        with self.assertRaisesRegex(ValueError, 'Committed source crop differs'):
            ensure_source_crop(path, samples, 48000, soundfile=sf, numpy=np)
        self.assertEqual(path.read_bytes(), original[:-10])

    def test_crop_encoding_failure_never_leaves_a_durable_partial(self):
        path = self.root / 'source.wav'
        def fail(target, *_args, **_kwargs):
            Path(target).write_bytes(b'RIFF-incomplete')
            raise OSError('interrupted encoding')
        with patch.object(sf, 'write', side_effect=fail):
            with self.assertRaises(OSError):
                ensure_source_crop(path, np.ones((48, 2), dtype=np.float32), 48000, soundfile=sf, numpy=np)
        self.assertEqual(list(self.root.iterdir()), [])

    def test_crop_retry_after_checkpoint_failure_commits_integrity_before_return(self):
        path = self.root/'source.wav'
        samples = np.arange(96, dtype=np.float32).reshape(48, 2) / 100
        with patch('stemwise_engine.source_crop.atomic_write_json', side_effect=OSError('checkpoint interrupted')):
            with self.assertRaisesRegex(OSError, 'checkpoint interrupted'):
                ensure_source_crop(path, samples, 48000, soundfile=sf, numpy=np)
        retained = path.read_bytes()
        ensure_source_crop(path, samples, 48000, soundfile=sf, numpy=np)
        checkpoint = path.with_suffix('.crop.json')
        self.assertTrue(checkpoint.is_file())
        self.assertEqual(path.read_bytes(), retained)
        path.write_bytes(b'corrupt current crop')
        with self.assertRaises(ValueError):
            ensure_source_crop(path, samples, 48000, soundfile=sf, numpy=np)
        self.assertEqual(path.read_bytes(), b'corrupt current crop')

    def test_delivered_endpoint_refresh_requires_prior_validation_and_publication(self):
        import copy
        from stemwise_engine.common import read_bounded_json
        from stemwise_engine.workflow_states import save_job
        path = self.root/'job.json'
        previous = {'state': 'DELIVERED', 'validation': {'kind': 'capability-completion', 'runId': 'a'*32},
            'delivery': {'sha256': 'b'*64}}
        following = {'state': 'WAITING_FOR_DISPLAY_REVIEW', 'delivery': previous['delivery'],
            'capabilityRefresh': {'previousValidation': previous['validation']},
            'endpointReview': {'result': 'accepted'}}
        for fault in ('prior-validation', 'publication', 'unaccepted'):
            atomic_write_json(path, previous)
            before = path.read_bytes()
            bad = copy.deepcopy(following)
            if fault == 'prior-validation':
                bad['capabilityRefresh']['previousValidation']['runId'] = 'c'*32
            elif fault == 'publication':
                bad['delivery']['sha256'] = 'd'*64
            else:
                bad['endpointReview']['result'] = 'pending'
            with self.subTest(fault=fault), self.assertRaises(ValueError):
                save_job(path, bad)
            self.assertEqual(path.read_bytes(), before)
        save_job(path, following)
        self.assertEqual(read_bounded_json(path, max_bytes=4096, root=self.root)['progress'],
            {'stage': 'validation', 'outcome': 'pending'})

    def test_inventory_isolates_malformed_reasons(self):
        workspace = AlignmentWorkspace(self.root)
        for index, reasons in enumerate(('invalid', None, True, -1)):
            video = f'AAAAAAAAAA{index}'
            atomic_write_json(workspace.job_dir(video) / 'state.json',
                {'videoId': video, 'status': 'committed-words', 'reasons': reasons})
        atomic_write_json(workspace.job_dir('BBBBBBBBBBB') / 'state.json',
            {'videoId': 'BBBBBBBBBBB', 'status': 'committed-words'})
        result = align._job_summary(workspace)
        statuses = {job['videoId']: job['status'] for job in result['jobs']}
        self.assertEqual(statuses['BBBBBBBBBBB'], 'committed-words')
        self.assertEqual(sum(value == 'corrupt' for value in statuses.values()), 4)

    def test_explicit_validation_never_succeeds_without_the_requested_artifact(self):
        sidecars, output = self.root / 'owner', self.root / 'output'
        sidecars.mkdir()
        args = SimpleNamespace(video_id=['AAAAAAAAAAA'])
        with patch.object(align, '_paths', return_value=(sidecars, output, self.root, None)):
            missing = align._validate({}, args)
        self.assertFalse(missing['valid'])
        self.assertEqual(missing['invalid'], [{'videoId': 'AAAAAAAAAAA', 'code': 'requested-sidecar-missing'}])

    def test_model_initialization_hard_exit_retains_exact_private_causes_and_context(self):
        import subprocess
        import sys
        import textwrap
        for index, message in enumerate(('missing local model asset', 'unsupported numerical runtime')):
            root = self.root / str(index)
            root.mkdir()
            script = textwrap.dedent("\n                import contextlib, sys\n                from pathlib import Path\n                from unittest.mock import patch\n                import stemwise_engine.lyrics_align_pc as align\n                import stemwise_engine.alignment_models as alignment_models\n                from stemwise_engine.lyrics_alignment import AlignmentWorkspace\n                root = Path(sys.argv[1])\n                workspace = AlignmentWorkspace(root/'alignment', workspace_root=root)\n                owner = alignment_models.WhisperModelOwner(config={'analysisTimeoutMinutes':1}, model_name='large-v3',\n                    snapshot=root/'snapshot', device='cpu', compute_type='float32')\n                owner.failure_owner = (workspace, 'AAAAAAAAAAA', {'videoId':'AAAAAAAAAAA'})\n                with patch.object(alignment_models, 'heavy_worker_stage', return_value=contextlib.nullcontext()),                         patch.object(alignment_models, 'FasterWhisperEvidence', side_effect=RuntimeError(sys.argv[2])):\n                    owner.get()\n                raise AssertionError('initialization failure must hard-exit')\n            ")
            import os
            environment = {**os.environ, 'PYTHONPATH': str(PROJECT/'src'), 'PYTHONDONTWRITEBYTECODE': '1'}
            completed = subprocess.run([sys.executable, '-B', '-c', script, str(root), message],
                env=environment, capture_output=True, text=True, timeout=30)
            self.assertEqual(completed.returncode, 70, completed.stderr)
            self.assertNotIn(message, completed.stderr)
            state = json.loads((root/'alignment/lyrics-alignment/jobs/AAAAAAAAAAA/state.json').read_bytes())
            reference, = state['privateDiagnostics']
            body = (root/'alignment/lyrics-alignment/jobs/AAAAAAAAAAA'/reference['file']).read_bytes()
            self.assertIn(message.encode(), body)
            self.assertIn(b'whisper-initialization', body)
            self.assertIn(b'float32', body)
            self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
            self.assertLessEqual(len(body), 12000)
            projected = workflow_diagnostics._alignment_diagnostics(state)
            self.assertEqual(projected['privateDiagnostics'], [reference])

    def test_optional_research_retains_distinct_bounded_private_causes(self):
        import stemwise_engine.lyrics_acquire as lyrics_acquire, stemwise_engine.metadata_research as metadata_research
        from unittest.mock import Mock
        for index, message in enumerate(('provider missing asset', 'provider unexpected runtime')):
            root = self.root/str(index)
            root.mkdir()
            client = Mock()
            client.get_watch_playlist.side_effect = RuntimeError(message)
            record = lyrics_acquire.acquire_exact_item_draft({'videoId': 'AAAAAAAAAAA', 'position': 1,
                'title': 'Title', 'uploader': 'Artist'}, root/'draft', client=client, detector=object())
            reference, = record['privateDiagnostics']
            body = (root/reference['file']).read_bytes()
            self.assertIn(message.encode(), body)
            self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
            self.assertNotIn(message, json.dumps(record))
            client.search_recordings.side_effect = RuntimeError(message)
            original = {'title': 'Title', 'artists': ['Artist'], 'matchStatus': 'youtube-fallback'}
            result, outcome = metadata_research.enrich_metadata(original, 240000, client, diagnostics_dir=root)
            self.assertEqual(result, original)
            reference, = outcome['privateDiagnostics']
            body = (root/reference['file']).read_bytes()
            self.assertIn(message.encode(), body)
            self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
            self.assertNotIn(message, json.dumps(outcome))

    @patch('stemwise_engine.pipeline.collect_provenance',
        new=lambda **_: {'fixture': 'child-edit-numerical-producer'})
    def test_child_checkpoint_edits_recover_process_death_at_each_promotion(self):
        import stemwise_engine.pipeline as pipeline_module
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import create_child_zip, read_child
        from test_packages import package_config, write_child
        from stemwise_engine.common import sha256_file
        for boundary in ('child', 'checkpoint', 'commit', 'cleanup'):
            with self.subTest(boundary=boundary):
                root = self.root / boundary
                root.mkdir()
                config = package_config(root)
                pipeline = StemwisePipeline(config)
                video = 'AAAAAAAAAAA'
                child = pipeline.child_path(video)
                write_child(child, video, config)
                old_document, cover = read_child(child, config)
                state = pipeline._new_state({'videoId': video}, 'absent')
                state.update(status='complete', childSha256=sha256_file(child))
                pipeline.save_checkpoint(state)
                candidate = root / 'candidate.zip'
                changed = copy.deepcopy(old_document)
                changed['metadata']['title'] = 'Reviewed correction'
                png = root / 'cover.png'
                png.write_bytes(cover)
                create_child_zip(candidate, changed, png, config)
                actual_bytes, actual_json = pipeline_module.atomic_write_bytes, pipeline_module.atomic_write_json
                actual_cleanup = pipeline_module.remove_manifested_directory
                def write_bytes(path, *args, **kwargs):
                    result = actual_bytes(path, *args, **kwargs)
                    if (boundary == 'child' and path == child or
                            boundary == 'checkpoint' and path == pipeline.checkpoint_path(video)):
                        raise SystemExit('process death')
                    return result
                def write_json(path, value, *args, **kwargs):
                    result = actual_json(path, value, *args, **kwargs)
                    if boundary == 'commit' and path.name == 'pending.json' and value.get('phase') == 'committed':
                        raise SystemExit('process death')
                    return result
                def cleanup(*args, **kwargs):
                    result = actual_cleanup(*args, **kwargs)
                    if boundary == 'cleanup':
                        raise SystemExit('process death')
                    return result
                with patch.object(pipeline_module, 'atomic_write_bytes', side_effect=write_bytes), \
                        patch.object(pipeline_module, 'atomic_write_json', side_effect=write_json), \
                        patch.object(pipeline_module, 'remove_manifested_directory', side_effect=cleanup):
                    with self.assertRaises(SystemExit):
                        pipeline.commit_child_edit(video, candidate, stage='metadata-review',
                            binding={'reviewSha256': 'a'*64})
                recovered = StemwisePipeline(config)
                current = recovered.load_checkpoint(video)
                self.assertEqual(current['childSha256'], sha256_file(child))
                self.assertEqual(read_child(child, config)[0]['metadata']['title'], 'Reviewed correction')
                self.assertFalse((recovered._analysis_replacement_root(video)/'pending.json').exists())
                self.assertTrue(recovered._can_skip({'videoId': video}, force=False,
                    lyrics=changed['lyrics'], owner_lyrics_sha256='absent'))

    def test_compute_defaults_and_explicit_overrides_match_device_support(self):
        from stemwise_engine.lyrics_alignment import resolve_compute_type, FasterWhisperEvidence
        import ctranslate2
        with patch.object(ctranslate2, 'get_supported_compute_types', return_value={'float32', 'int8'}):
            self.assertEqual(resolve_compute_type('cpu'), 'float32')
            self.assertEqual(resolve_compute_type('cpu', 'int8'), 'int8')
            with self.assertRaisesRegex(ValueError, 'Unsupported Whisper compute'):
                FasterWhisperEvidence('large-v3', self.root/'missing', device='cpu', compute_type='float16')
        with patch.object(ctranslate2, 'get_supported_compute_types', return_value={'float32', 'float16'}):
            self.assertEqual(resolve_compute_type('cuda'), 'float16')

    def test_command_startup_rejections_retain_distinct_private_causes_before_model_work(self):
        import contextlib
        import io
        import ctranslate2
        from unittest.mock import Mock
        workspace = AlignmentWorkspace(self.root/'startup', workspace_root=self.root)
        paths = (self.root, self.root, self.root, workspace)
        references = []
        runtime = Mock(side_effect=RuntimeError('missing pinned runtime initialization asset'))
        with patch.object(align, 'load_config', return_value={}), \
                patch.object(align, '_paths', return_value=paths), \
                patch.object(ctranslate2, 'get_supported_compute_types', return_value={'float32'}), \
                patch.object(align, '_verify_alignment_runtime', runtime), \
                patch.object(align, 'FasterWhisperEvidence') as model:
            for compute in ('float16', 'int16', None):
                command = ['--config', str(self.root/'config.json'), 'preload-model', '--device', 'cpu']
                if compute is not None:
                    command += ['--compute-type', compute]
                stderr = io.StringIO()
                with contextlib.redirect_stderr(stderr):
                    self.assertEqual(align.main(command), 2)
                record = json.loads((workspace.root/'preload.failure.json').read_bytes())
                reference, = record['privateDiagnostics']
                body = (workspace.root/reference['file']).read_bytes()
                self.assertEqual(len(body), reference['byteCount'])
                self.assertLessEqual(len(body), 12000)
                self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])
                self.assertIn(b'preload-model-startup', body)
                self.assertEqual(record['context']['configPath'], str((self.root/'config.json').resolve()))
                self.assertEqual(record['context']['device'], 'cpu')
                self.assertNotIn('Unsupported Whisper', stderr.getvalue())
                self.assertNotIn('missing pinned', stderr.getvalue())
                if compute is not None:
                    self.assertIn(('Unsupported Whisper compute type '+repr(compute)).encode(), body)
                    runtime.assert_not_called()
                else:
                    self.assertIn(b'missing pinned runtime initialization asset', body)
                    runtime.assert_called_once()
                references.append(reference)
            model.assert_not_called()
        self.assertEqual(len({reference['sha256'] for reference in references}), 3)
        for reference in references:
            self.assertTrue((workspace.root/reference['file']).is_file())

    def test_actual_preload_main_hard_exit_retains_initialization_cause(self):
        import os
        import subprocess
        import sys
        import textwrap
        script = textwrap.dedent("\n            import contextlib, sys\n            from pathlib import Path\n            from unittest.mock import patch\n            import ctranslate2\n            import stemwise_engine.lyrics_align_pc as align\n            from stemwise_engine.lyrics_alignment import AlignmentWorkspace\n            root = Path(sys.argv[1])\n            workspace = AlignmentWorkspace(root/'preload', workspace_root=root)\n            with patch.object(align, 'load_config', return_value={}),                     patch.object(align, '_paths', return_value=(root,root,root,workspace)),                     patch.object(ctranslate2, 'get_supported_compute_types', return_value={'float32'}),                     patch.object(align, '_verify_alignment_runtime', return_value={}),                     patch.object(align, '_verify_whisper_snapshot', return_value=(root,{})),                     patch.object(align, 'model_operation_slot', return_value=contextlib.nullcontext()),                     patch.object(align, 'heavy_worker_stage', return_value=contextlib.nullcontext()),                     patch.object(align, 'FasterWhisperEvidence', side_effect=RuntimeError('preload native model init failed')):\n                align.main(['--config',str(root/'config.json'),'preload-model','--device','cpu'])\n            raise AssertionError('preload failure must hard-exit')\n        ")
        environment = {**os.environ, 'PYTHONPATH': str(PROJECT/'src'), 'PYTHONDONTWRITEBYTECODE': '1'}
        completed = subprocess.run([sys.executable, '-B', '-c', script, str(self.root)],
            env=environment, capture_output=True, text=True, timeout=30)
        self.assertEqual(completed.returncode, 70, completed.stderr)
        self.assertNotIn('preload native model init failed', completed.stderr+completed.stdout)
        workspace = AlignmentWorkspace(self.root/'preload', workspace_root=self.root)
        record = json.loads((workspace.root/'preload.failure.json').read_bytes())
        reference, = record['privateDiagnostics']
        body = (workspace.root/reference['file']).read_bytes()
        self.assertIn(b'preload native model init failed', body)
        self.assertEqual(record['context']['stage'], 'preload')
        self.assertEqual(record['context']['computeType'], 'float32')
        self.assertEqual(len(body), reference['byteCount'])
        self.assertLessEqual(len(body), 12000)
        self.assertEqual(hashlib.sha256(body).hexdigest(), reference['sha256'])

    def test_cpu_command_defaults_reach_current_preload_and_both_alignment_entries(self):
        import contextlib
        import io
        import ctranslate2
        from unittest.mock import Mock
        project = self.root/'pc/builder'
        project.mkdir(parents=True)
        future, analysis = self.root/'future', self.root/'analysis'
        audio = analysis/'AAAAAAAAAAA/analysis.wav'
        audio.parent.mkdir(parents=True)
        audio.write_bytes(b'retained fixture')
        job = future/'jobs/AAAAAAAAAAA/job.json'
        atomic_write_json(job, {'videoId': 'AAAAAAAAAAA', 'state': 'ALIGNING_LYRICS',
                               'activeAlignmentAttemptId': 'a'*32})
        config = {'projectDir': str(project), 'outputDir': str(self.root/'output'),
                  'tempDir': str(self.root/'temp')}
        workspace = AlignmentWorkspace(self.root/'alignment', workspace_root=self.root)
        paths = (self.root/'sidecars', self.root/'output/children', self.root/'models', workspace)
        model = Mock()
        observed = []
        def future_worker(config, args, **kwargs):
            result = real_align(config, args, selected_override=[], paths_override=paths)
            observed.append(args.compute_type)
            return result
        real_align = align._align
        def playlist_worker(config, args):
            result = real_align(config, args, selected_override=[], paths_override=paths)
            observed.append(args.compute_type)
            return result
        with patch.object(ctranslate2, 'get_supported_compute_types', return_value={'float32', 'int8'}), \
                patch.object(align, 'load_config', return_value=config), \
                patch.object(align, '_paths', return_value=paths), \
                patch.object(align, '_verify_alignment_runtime', return_value={}), \
                patch.object(align, '_verify_whisper_snapshot', return_value=(self.root, {})), \
                patch.object(align, 'FasterWhisperEvidence', return_value=model) as factory, \
                patch.object(align, 'model_operation_slot', side_effect=lambda _: contextlib.nullcontext()), \
                patch.object(align, 'heavy_worker_stage', side_effect=lambda _: contextlib.nullcontext()), \
                patch.object(align, '_align', side_effect=playlist_worker), \
                patch.object(align, '_run_future_alignment_attempt', side_effect=future_worker), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(align.main(['--config', str(project/'config.json'),
                                         'preload-model', '--device', 'cpu']), 0)
            factory.assert_called_once_with(align.DEFAULT_MODEL, self.root,
                                             device='cpu', compute_type='float32')
            model.close.assert_called_once()
            self.assertEqual(align.main(['--config', str(project/'config.json'),
                                         'align', '--device', 'cpu']), 0)
            # The empty selection is deliberately uncommitted; this tests command
            # setup and retained-input admission, not inference qualification.
            self.assertEqual(align.main(['--config', str(project/'config.json'),
                'future-align', '--device', 'cpu', '--video-id', 'AAAAAAAAAAA',
                '--attempt-id', 'a'*32, '--analysis-audio', str(audio),
                '--future-root', str(future), '--analysis-root', str(analysis),
                '--expected-mix-sha256', 'b'*64, '--editorial-sha256', 'c'*64,
                '--canonical-plain-sha256', 'd'*64]), 1)
        self.assertEqual(observed, ['float32', 'float32'])

    def test_interval_interpolation_weights_durations_between_observed_anchors(self):
        from stemwise_engine.lyrics_alignment import _fill_line_timestamps
        for counts, expected in (((20, 1, 1), [0, 10000, 10500]),
                                 ((1, 20, 1), [0, 500, 10500]),
                                 ((2, 1, 3, 99), [0, 3500, 5250, 10500])):
            lines = [SimpleNamespace(words=['word']*count) for count in counts]
            transcript = [SimpleNamespace(end=11)]
            actual = _fill_line_timestamps(lines, {0: 0, len(lines)-1: 10.5}, transcript, 12000)
            self.assertEqual(actual, expected)

    def test_ambiguous_caption_patterns_allow_exact_recording_literal_review(self):
        for text, code in (('Music', 'caption-artifact-label'),
                           ('Meet me at 5:00 tonight', 'embedded-timestamp')):
            audit = audit_text(text, 'en', 'Latn')
            issue = next(item for item in audit['issues'] if item['code'] == code)
            resolution = {'formatVersion': 1, 'videoId': 'AAAAAAAAAAA',
                'recordingSha256': 'a'*64, 'textSha256': audit['sha256'],
                'trackKind': 'original', 'issue': issue, 'decision': 'literal-performed-content',
                'reviewer': 'owner', 'completedAt': '2026-10-23T00:00:00Z',
                'rationale': 'Confirmed exact literal lyric in this recording'}
            self.assertEqual(unresolved_qa_issues(audit, [resolution], video_id='AAAAAAAAAAA',
                recording_sha256='a'*64, track_kind='original'), [])
            with self.assertRaises(ValueError):
                unresolved_qa_issues(audit, [resolution], video_id='AAAAAAAAAAA',
                    recording_sha256='b'*64, track_kind='original')

    def test_literal_warning_requires_exact_recording_text_and_issue(self):
        audit = audit_text('Chorus\n', 'en', 'Latn')
        issue = next(i for i in audit['issues'] if i['code'] == 'editorial-role-label')
        resolution = {'formatVersion': 1, 'videoId': 'AAAAAAAAAAA', 'recordingSha256': 'a'*64,
            'textSha256': audit['sha256'], 'trackKind': 'original', 'issue': issue,
            'decision': 'literal-performed-content', 'reviewer': 'owner',
            'completedAt': '2026-10-20T00:00:00Z', 'rationale': 'Literal sung word confirmed against the recording'}
        args = dict(video_id='AAAAAAAAAAA', recording_sha256='a'*64, track_kind='original')
        self.assertEqual(unresolved_qa_issues(audit, [], **args), [issue])
        self.assertEqual(unresolved_qa_issues(audit, [resolution], **args), [])
        with self.assertRaises(ValueError):
            unresolved_qa_issues(audit, [resolution], **{**args, 'recording_sha256': 'b'*64})
        changed = {**audit, 'sha256': 'c'*64}
        with self.assertRaises(ValueError):
            unresolved_qa_issues(changed, [resolution], **args)
        mandatory = {**resolution, 'issue': {'code': 'embedded-html', 'severity': 'error'}}
        with self.assertRaises(ValueError):
            unresolved_qa_issues(audit, [mandatory], **args)

    def test_numerical_orthography_keeps_ordinary_j(self):
        for word in ('joy', 'jump', 'jazz', 'ujrzalem', 'jego'):
            self.assertIn('j', _romanized_words(word)[0])


    def test_attempt_identity_and_two_route_failures_survive_successful_retry(self):
        for name in ('sources.json', 'config.json', 'worker.py'):
            (self.root / name).write_bytes(name.encode())
        inputs = AttemptInputs.capture('a'*32, 'b'*64, self.root/'sources.json', self.root/'config.json', self.root)
        record = inputs.to_record()
        (self.root/'sources.json').write_bytes(b'new mutable source')
        self.assertEqual(AttemptInputs.from_record(record), inputs)
        ledger = RouteOutcomeLedger()
        for index, route in enumerate(('faster-whisper:en', 'precision-vocal')):
            code = worker_protocol.technical_diagnostic_code(RuntimeError('PRIVATE cause '+route), self.root / 'route.private.txt')
            ledger.record(align._route_attempt_event(index, 'structure', route, 'failure', code=code))
            reference = ledger.events[-1]['privateDiagnostics'][0]
            path = self.root / reference['file']
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), reference['sha256'])
            self.assertEqual(len(path.read_bytes()), reference['byteCount'])
        ledger.record(align._route_attempt_event(2, 'structure', 'another-language', 'success'))
        self.assertEqual(len(ledger.failures), 2)
        diagnostics = workflow_diagnostics._alignment_diagnostics({'routeAttempts': ledger.events})
        self.assertEqual(len(diagnostics['routeAttempts'][0]['privateDiagnostics']), 1)
        self.assertNotIn('PRIVATE cause', json.dumps(diagnostics))

    def test_failed_run_retains_the_attempt_inputs_when_editorial_and_sources_change(self):
        from stemwise_engine.lyrics_alignment import ALIGNMENT_VERSION
        workflow = FutureSongWorkflow.__new__(FutureSongWorkflow)
        workflow.workspace_root = WORKSPACE
        workflow.root = self.root/'future'
        workflow.jobs = workflow.root/'jobs'
        workflow.runtime = workflow.root/'runtime'
        workflow.owner = workflow.root/'owner-input'
        workflow.pc_side_root = self.root
        workflow.reports = self.root/'reports'
        workflow.config_path = self.root/'config.json'
        workflow.config_path.write_text('{}')
        workflow.config = {'projectDir': str(PROJECT), 'tempDir': str(self.root/'temp')}
        video_id, run_id = 'AAAAAAAAAAA', 'a'*32
        job_dir = workflow.jobs/video_id
        job_dir.mkdir(parents=True)
        source = job_dir/'sources.json'
        source.write_bytes(b'original source')
        draft = self.root/'draft.txt'
        draft.write_bytes(b'original editorial')
        inputs = AttemptInputs.capture(run_id, hashlib.sha256(draft.read_bytes()).hexdigest(),
            source, workflow.config_path, PROJECT/'src')
        job = {'videoId': video_id, 'policy': {}, 'activeAlignmentAttemptId': run_id,
               'alignmentAttemptInputs': inputs.to_record()}
        state = {'attemptId': run_id, 'alignmentVersion': ALIGNMENT_VERSION}
        state_path = workflow._alignment_state_path(video_id)
        atomic_write_json(state_path, state)
        workflow._load_job = lambda _video_id: job
        workflow._editorial_sha = lambda _job: hashlib.sha256(draft.read_bytes()).hexdigest()
        draft.write_bytes(b'newer owner editorial')
        source.write_bytes(b'newer source')
        workflow._commit_failed_alignment_attempt(video_id, 'changed-inputs')
        record = json.loads((workflow.report_store.report_root(video_id)/'runs'/run_id/'run.json').read_bytes())
        self.assertEqual(record['producingInputs'], inputs.to_record())
        self.assertEqual(record['checkpointKey']['authority']['editorialSha256'], inputs.editorial_sha256)
        self.assertEqual(record['artifacts']['sourcesSha256'], inputs.sources_sha256)
        self.assertEqual(record['observedInputChanges'], {
            'editorialSha256': hashlib.sha256(draft.read_bytes()).hexdigest(),
            'sourcesSha256': hashlib.sha256(source.read_bytes()).hexdigest()})

    def test_worker_json_serialization_and_replace_failures_preserve_previous_bytes(self):
        target = self.root / 'result.json'
        target.write_bytes(b'old result')
        for durable in (True, False):
            with self.assertRaises(ValueError):
                worker_publication.publish_json(target, {'bad': float('nan')}, durable=durable)
            with patch.object(worker_publication.os, 'replace', side_effect=OSError('publication failed')):
                with self.assertRaises(OSError):
                    worker_publication.publish_json(target, {'ok': True}, durable=durable)
            self.assertEqual(target.read_bytes(), b'old result')
            self.assertEqual(list(self.root.iterdir()), [target])
        with patch.object(worker_publication.os, 'fsync', side_effect=OSError('flush failed')):
            with self.assertRaises(OSError):
                worker_publication.publish_json(target, {'ok': True})
        self.assertEqual(target.read_bytes(), b'old result')
        with patch('stemwise_engine.common._fsync_directory', side_effect=OSError('directory sync failed')):
            with self.assertRaises(OSError):
                worker_publication.publish_json(target, {'ok': True})
        self.assertEqual(json.loads(target.read_bytes()), {'ok': True})
        self.assertEqual(list(self.root.iterdir()), [target])
