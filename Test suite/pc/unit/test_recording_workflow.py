"""Native recordings use the editorial pipeline without borrowing old timing or approvals."""
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import numpy as np
import soundfile as sf

PROJECT = Path(__file__).resolve().parents[3]
import stemwise_engine.source_acquisition as source_acquisition
from stemwise_engine.common import atomic_write_json, sha256_file
from test_packages import package_config, song_document


class RecordingWorkflowTest(unittest.TestCase):
    def test_native_pcm_recovery_preserves_live_decoder_staging_and_fences_retry(self):
        from stemwise_engine import youtube
        from stemwise_engine.common import WorkerRetirementError, ProcessLockBusyError, process_lock_descriptor
        workflow = self.workflow()
        owner = workflow._pipeline()
        video = workflow.video_id
        reference = youtube.decode_analysis_audio(self.audio, self.workspace/'reference', timeout_seconds=30)
        state = {'videoId':video, 'status':'complete', 'recordingInput':workflow.recording.identity,
            'originalAudio':{'retained':True, 'sha256':sha256_file(reference),
                'byteCount':reference.stat().st_size, 'durationMs':1000}}
        original, held, paths = self.audio.read_bytes(), [], []
        def decoder(command, **options):
            path = Path(command[-1])
            path.write_bytes(b'live decoder PCM')
            paths.append(path)
            held.append(os.dup(process_lock_descriptor(path.parent/'.stemwise-worker.lock')))
            raise WorkerRetirementError('decoder still owns staging')
        try:
            with patch.object(youtube, 'run_process_tree', side_effect=decoder):
                with self.assertRaises(WorkerRetirementError):
                    owner._require_retained_analysis_audio(video, state)
            self.assertEqual(paths[0].read_bytes(), b'live decoder PCM')
            with self.assertRaises(ProcessLockBusyError):
                owner._require_retained_analysis_audio(video, state)
            self.assertEqual(paths[0].read_bytes(), b'live decoder PCM')
            os.close(held.pop())
            restored = owner._require_retained_analysis_audio(video, state)
            self.assertEqual(restored.read_bytes(), reference.read_bytes())
            self.assertFalse(paths[0].parent.exists())
            self.assertEqual(self.audio.read_bytes(), original)
        finally:
            for descriptor in held:
                os.close(descriptor)

    def test_accepted_native_owner_survives_earlier_unconfirmed_candidate(self):
        import stemwise_media.quick_batch as batch
        from stemwise_engine.recording_source import selection
        from stemwise_engine.editorial_review import POLICY_VERSION
        workflow = self.workflow()
        job = {'videoId':workflow.video_id, 'state':'WAITING_FOR_EDITORIAL', 'policyVersion':POLICY_VERSION,
               'recordingInput':workflow.recording.identity,'deliveryAllowed':True}
        self.write(workflow._job_path(workflow.video_id),job)
        path = self.library/'catalogue.json'
        data = json.loads(path.read_bytes())
        owner = data['rows'][0]
        owner.update(position=2,metadataSelection={'status':'selected_candidate','trackId':11,'basis':'owner'})
        earlier = {'position':1,'videoId':'lmnopqrstuv','title':'Native',
            'candidates':[{'id':22,'title':'Native','artist':'Artist'}],
            'metadataSelection':{'status':'deferred','basis':'unconfirmed'},
            'refinedAudioComparison':{'status':'unconfirmed','candidates':[]}}
        data['rows'].insert(0,earlier)
        source = self.library/'audio/youtube/lmnopqrstuv/source.webm'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'reference')
        self.write(source.parent/'download.json',{'videoId':'lmnopqrstuv','file':source.name})
        self.write(path,data)
        data = batch.read_catalogue(path)
        earlier = data['rows'][0]
        with patch.object(batch,'ROOT',self.library), patch.object(batch,'decode',return_value=np.zeros((16,2))), \
                patch.object(batch,'analyze_waveforms',return_value={'classification':'unconfirmed'}):
            batch.compare_rows(data,refine=True,alternatives=True)
            batch.export_library(data)
        self.assertIn(11,{item['id'] for item in earlier['candidates']})
        selected = selection(self.config,'qobuz:11')
        self.assertEqual(selected.reference_ids[0],workflow.video_id)
        reloaded = self.workflow(verify_media=False)
        self.assertEqual(reloaded.video_id,workflow.video_id)
        self.assertEqual(reloaded.status(reloaded.video_id)['state'],'WAITING_FOR_EDITORIAL')
        duplicate = workflow._job_path('lmnopqrstuv')
        self.write(duplicate,{**job,'videoId':'lmnopqrstuv'})
        with self.assertRaisesRegex(ValueError,'owner'):
            selection(self.config,'qobuz:11')

    def test_public_native_replacement_preserves_explicit_primary_youtube(self):
        import stemwise_media.audio_library as audio
        import stemwise_media.quick_batch as batch
        catalogue = self.library/'catalogue.json'
        data = json.loads(catalogue.read_bytes())
        source = self.library/'audio/youtube/abcdefghijk/source.webm'
        source.parent.mkdir(parents=True)
        source.write_bytes(b'selected source')
        receipt = source.parent/'download.json'
        self.write(receipt,{'videoId':'abcdefghijk','file':source.name})
        data['rows'][0]['ownerAudioSelection'] = {'source':'youtube','file':source.relative_to(self.library).as_posix()}
        self.write(catalogue,data)
        self.write(self.library/'comparison-results/library-files.json',{'flac':[],
            'playlist':[{'videoId':'abcdefghijk','source':'youtube','trackId':None}],
            'catalogueSha256':sha256_file(catalogue)})
        before = source.read_bytes(),receipt.read_bytes(),catalogue.read_bytes()
        with patch.object(audio,'ROOT',self.library),patch.object(batch,'ROOT',self.library), \
                patch('sys.stdout',new_callable=io.StringIO),patch('sys.stderr',new_callable=io.StringIO):
            self.assertEqual(audio.main(['register','--qobuz','11','--youtube','abcdefghijk',
                '--title','Native','--artist','Artist','--replace-youtube']),2)
        self.assertEqual((source.read_bytes(),receipt.read_bytes(),catalogue.read_bytes()),before)

    def test_registration_rejects_conflicting_references_before_publication(self):
        import stemwise_engine.recording_source as recording_source
        from stemwise_engine.delivery_selection import catalogue
        from types import SimpleNamespace
        owner = SimpleNamespace(pc_side_root=self.library.parent, workspace_root=self.workspace)
        for track in (22, 33):
            audio = self.library / f'audio/qobuz/{track}/future.flac'
            audio.parent.mkdir(parents=True)
            shutil.copy2(self.audio, audio)
            from qobuz_fixtures import write_download
            write_download(audio.parent, {'qobuzTrackId': track, 'file': audio.name})
        recording_source.register_input(self.library, 22, 'lmnopqrstuv', 'Song', 'Artist')
        before = catalogue(owner)
        for reference in ('lmnopqrstuv', 'Lmnopqrstuv', 'Abcdefghijk'):
            with self.subTest(reference=reference), self.assertRaises(ValueError):
                recording_source.register_input(self.library, 33, reference, 'Other', 'Artist')
            self.assertFalse((self.library/'audio/qobuz/33/enrichment-input.json').exists())
            self.assertEqual(catalogue(owner), before)
        # An explicit selection may retain an alternative source under the same exact reference.
        recording_source.register_input(self.library, 33, 'abcdefghijk', 'Alternative', 'Artist')
        self.assertEqual(catalogue(owner), before)

    def test_current_command_entry_constructs_native_status_and_rejects_invalid_source_without_mutation(self):
        import contextlib
        import stemwise_engine.workflow_command as workflow_command
        from stemwise_engine.future_song import FutureSongWorkflow

        def policy(workflow):
            workflow.contract_identity = {'song-enrichment-v2.schema.json': '3'*64,
                'installed-enrichment-v2.schema.json': '4'*64, 'contract-limits.json': '5'*64}
            return {}

        before = self.old_job.read_bytes()
        for key, expected in (('qobuz:11', 0), ('qobuz:00', 1)):
            output, error = io.StringIO(), io.StringIO()
            with patch.object(FutureSongWorkflow, '_validate_policy', policy), \
                    contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
                result = workflow_command.main(['--config', str(self.config_path), '--recording-key', key, 'status'])
            self.assertEqual(result, expected, error.getvalue())
            if result == 0:
                value = json.loads(output.getvalue())
                self.assertEqual(value['state'], 'NOT_STARTED')
                self.assertEqual(value['recordingInput']['recordingKey'], key)
                self.assertEqual(value['recordingInput']['sha256'], sha256_file(self.audio))
            else:
                self.assertEqual(output.getvalue(), '')
                self.assertIn('error', json.loads(error.getvalue()))
            self.assertEqual(self.old_job.read_bytes(), before)

    def test_native_construction_creates_only_its_exact_source_namespace(self):
        from dataclasses import FrozenInstanceError
        workflow = self.workflow()
        from stemwise_engine.future_song import FutureSongWorkflow
        self.assertIs(type(workflow), FutureSongWorkflow)
        self.assertFalse((self.builder/'future-songs/policy-state/current').exists())
        self.assertTrue(workflow.jobs.is_dir())
        self.assertEqual(workflow.root, workflow.source_context.root)
        self.assertNotIn('_result_binding', workflow.__dict__)
        with self.assertRaises(FrozenInstanceError):
            workflow.source_context.root = self.workspace/'wrong'

    def test_owner_metadata_repackages_native_child_without_repeating_acoustics(self):
        import stemwise_engine.pipeline as pipeline
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import read_child
        from PIL import Image
        def provenance(consumer):
            consumer.provenance_sha256 = 'a' * 64
            consumer._provenance_needs_refresh = False
        def cover(consumer, info, video_id, destination, **kwargs):
            Image.new('RGB', (32, 32), 'red').save(destination)
            return song_document(video_id)['artwork']
        def analysis(wav, duration):
            from stemwise_engine.waveform import build_waveform
            value = song_document('abcdefghijk')['analysis']
            value['waveform'] = build_waveform(wav, duration_ms=duration)
            return value
        config = {**self.config, 'localRecordingKey': 'qobuz:11', 'retainSuccessfulAnalysisAudio': True}
        item = {'videoId':'abcdefghijk', 'title':'Provider'}
        with patch.object(StemwisePipeline, '_refresh_provenance', provenance), \
                patch.object(StemwisePipeline, '_create_cover', cover), \
                patch.object(StemwisePipeline, '_analysis', side_effect=analysis) as acoustic, \
                patch.object(pipeline, 'enrich_metadata', side_effect=lambda value,*_,**kw: (value, {})):
            first = StemwisePipeline(config)
            self.assertEqual(first.process_song(item), 'complete', first.load_checkpoint(item['videoId']))
            before, _ = read_child(first.child_path(item['videoId']), first.config)
            pcm = first.require_retained_analysis_audio(item['videoId']).read_bytes()
            catalogue_path = self.library / 'catalogue.json'
            catalogue = json.loads(catalogue_path.read_bytes())
            catalogue['ownerTrackMetadata'] = {'11': {'title':'Owner title','artist':'Owner artist','album':'Owner album'}}
            self.write(catalogue_path, catalogue)
            failed = StemwisePipeline(config)
            saved_child = failed.child_path(item['videoId']).read_bytes()
            saved_checkpoint = failed.checkpoint_path(item['videoId']).read_bytes()
            with patch.object(failed, 'commit_child_edit', side_effect=OSError('checkpoint publication failed')) as commit:
                self.assertTrue(failed._can_skip(item, force=False, lyrics=before['lyrics'], owner_lyrics_sha256='absent'))
                commit.assert_not_called()
                self.assertEqual(failed.process_song(item), 'failed')
                self.assertEqual(commit.call_args.kwargs['stage'], 'metadata-refresh')
            self.assertEqual(failed.child_path(item['videoId']).read_bytes(), saved_child)
            self.assertEqual(failed.checkpoint_path(item['videoId']).read_bytes(), saved_checkpoint)
            crash = """import json, os, sys
from unittest.mock import patch
from stemwise_engine.pipeline import StemwisePipeline
import stemwise_engine.common as common
import stemwise_engine.recording_analysis as recording
config, producer = json.loads(sys.argv[1]), json.loads(sys.argv[2])
def provenance(owner):
 owner.provenance_sha256 = 'a'*64
 owner._provenance_needs_refresh = False
remove = common.remove_manifested_directory
def cleanup(root,path,**kwargs):
 if kwargs['owner']['kind'] == 'native-metadata-refresh': os._exit(37)
 return remove(root,path,**kwargs)
with patch.object(StemwisePipeline,'_refresh_provenance',provenance), \
     patch.object(recording,'acoustic_producer_identity',return_value=producer), \
     patch.object(common,'remove_manifested_directory',side_effect=cleanup), \
     patch.object(StemwisePipeline,'_analysis',side_effect=AssertionError('inference repeated')):
 StemwisePipeline(config).process_song({'videoId':'abcdefghijk','title':'Provider'})
raise AssertionError('refresh cleanup boundary was not reached')
"""
            from stemwise_engine.common import run_process_tree
            interrupted = run_process_tree([sys.executable, '-B', '-c', crash, json.dumps(config),
                json.dumps(first.load_checkpoint(item['videoId'])['acousticProducerIdentity'])],
                timeout=30, capture_output=True, text=True)
            self.assertEqual(interrupted.returncode, 37, interrupted.stderr)
            second = StemwisePipeline(config)
            committed = second.child_path(item['videoId']).read_bytes(), second.checkpoint_path(item['videoId']).read_bytes()
            self.assertEqual(second.process_song(item), 'skipped')
            self.assertFalse(list((second.work_dir/'staging').iterdir()))
            self.assertEqual((second.child_path(item['videoId']).read_bytes(), second.checkpoint_path(item['videoId']).read_bytes()), committed)
            after, cover_bytes = read_child(second.child_path(item['videoId']), second.config)
            self.assertEqual(after['metadata']['title'], 'Owner title')
            self.assertEqual(after['metadata']['artists'], ['Owner artist'])
            self.assertEqual(after['metadata']['album']['title'], 'Owner album')
            self.assertGreater(after['generatedAt'], before['generatedAt'])
            self.assertEqual(after['analysis'], before['analysis'])
            self.assertEqual(second.require_retained_analysis_audio(item['videoId']).read_bytes(), pcm)
            self.assertEqual(second.process_song(item), 'skipped')
            self.assertEqual(acoustic.call_count, 1)


    def test_missing_native_pcm_recovers_exact_checkpoint_without_model_analysis(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.youtube import decode_analysis_audio, probe_duration_ms
        from stemwise_engine.common import create_manifested_directory, remove_manifested_directory
        workflow = self.workflow()
        pipeline = workflow._pipeline()
        video_id = workflow.video_id
        owner = {'kind': 'analysis-temporary', 'videoId': video_id}
        temporary = create_manifested_directory(pipeline.temp_root, pipeline.temp_root / video_id,
            owner=owner, workspace_root=self.workspace)
        pcm = decode_analysis_audio(self.audio, temporary, timeout_seconds=30)
        state = {'videoId': video_id, 'status': 'complete', 'recordingInput': workflow.recording[3],
                 'originalAudio': {'retained': True, 'sha256': sha256_file(pcm),
                     'byteCount': pcm.stat().st_size, 'durationMs': probe_duration_ms(pcm)}}
        expected = pcm.read_bytes()
        remove_manifested_directory(pipeline.temp_root, temporary, owner=owner, workspace_root=self.workspace)
        with patch.object(pipeline, '_analysis', side_effect=AssertionError('Do not redo inference')):
            restored = pipeline._require_retained_analysis_audio(video_id, state)
        self.assertEqual(restored.read_bytes(), expected)
        # Derived PCM can be replaced only by the exact completed decode.
        restored.write_bytes(b'changed PCM')
        with patch.object(pipeline, '_analysis', side_effect=AssertionError('Do not redo inference')):
            self.assertEqual(pipeline._require_retained_analysis_audio(video_id, state).read_bytes(), expected)

    def test_native_pcm_recovery_rejects_wrong_checkpoint_and_cleans_only_new_audio(self):
        workflow = self.workflow()
        pipeline = workflow._pipeline()
        state = {'videoId': workflow.video_id, 'status': 'complete', 'recordingInput': workflow.recording[3],
                 'originalAudio': {'retained': True, 'sha256': '0' * 64,
                     'byteCount': 384114, 'durationMs': 1000}}
        source = self.audio.read_bytes()
        with self.assertRaises(ValueError):
            pipeline._require_retained_analysis_audio(workflow.video_id, state)
        self.assertFalse((pipeline.temp_root / workflow.video_id).exists())
        self.assertEqual(self.audio.read_bytes(), source)


    def test_recording_selection_does_not_initialize_analysis_provenance(self):
        import stemwise_engine.recording_analysis as recording_analysis
        import stemwise_engine.recording_source as recording_source
        before = self.old_job.read_bytes()
        with patch.object(recording_analysis, 'acoustic_producer_identity',
                          side_effect=AssertionError('Status selection must not probe model runtimes')):
            result = recording_source.selection(self.config, 'qobuz:11')
        self.assertEqual(result[2], self.audio)
        self.assertEqual(result[3]['sha256'], sha256_file(self.audio))
        self.assertEqual(self.old_job.read_bytes(), before)

    def test_alignment_runtime_can_select_and_probe_native_flac_without_comparison_dependencies(self):
        script = "\nimport sys\nfrom pathlib import Path\nfrom stemwise_engine.recording_source import alignment_config\nfrom stemwise_media.quick_batch import qobuz_file\nfrom stemwise_media.compare import probe\naudio = qobuz_file(11, root=Path(sys.argv[1]))\nassert audio == Path(sys.argv[2])\nmetadata = probe(audio)\nassert metadata['codec_name'] == 'flac'\nassert int(metadata['sample_rate']) == 48000 and metadata['channels'] == 2\nassert metadata['duration'] == 1.0\n"
        builder = PROJECT / 'PC side/stemwise-playlist-builder'
        result = subprocess.run([str(builder / '.venv-lyrics-align/bin/python'), '-c', script,
                                 str(self.library), str(self.audio)],
            env={**os.environ, 'PYTHONPATH': str(builder / 'src')},
            capture_output=True, text=True, check=False, timeout=60)
        self.assertEqual(result.returncode, 0, result.stderr)

    def setUp(self):
        from contextlib import ExitStack
        import stemwise_engine.recording_analysis as recording_analysis
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.dict(os.environ, {'STEMWISE_ALLIN1_PYTHON': ''}))
        self.acoustic_runtime = {'packages': {'torch': {'version': 'qualified-v1'}}}
        runtime = patch.object(recording_analysis, 'collect_acoustic_provenance',
                               side_effect=lambda **kwargs: self.acoustic_runtime)
        stack.enter_context(runtime)
        self.temporary = tempfile.TemporaryDirectory(dir=PROJECT / 'Test suite/work')
        stack.enter_context(self.temporary)
        self.workspace = Path(self.temporary.name)
        self.pc = self.workspace / 'PC side'
        self.builder = self.pc / 'stemwise-playlist-builder'
        self.builder.mkdir(parents=True)
        self.config = package_config(self.workspace)
        self.config.update(playlistUrl='https://youtube.com/playlist?list=fixture',
            outputDirWindows=str(self.pc / 'generated-packages'),
            pcSideRootWindows=str(self.pc), futureRootWindows=str(self.builder / 'future-songs'),
            futureReadyDirWindows=str(self.workspace / 'Ready to Import'),
            tempDirWindows=str(self.pc / 'temporary-analysis'))
        self.config_path = self.builder / 'config.json'
        atomic_write_json(self.config_path, self.config)
        self.library = self.pc / 'qobuz-comparison'
        self.audio = self.library / 'audio/qobuz/11/native.flac'
        self.audio.parent.mkdir(parents=True)
        samples = np.column_stack((np.linspace(-.2, .2, 48000), np.linspace(.3, -.3, 48000)))
        sf.write(self.audio, samples, 48000, subtype='PCM_24')
        self.write(self.audio.parent / 'download.json', {'qobuzTrackId':11,'file':self.audio.name,'bytes':self.audio.stat().st_size,'sha256':sha256_file(self.audio)})
        self.write(self.library / 'comparison-results/library-files.json', {
            'flac': [{'trackId': 11, 'file': 'audio/qobuz/11/native.flac', 'title': 'Native', 'artist': 'Artist'}],
            'playlist': [{'videoId': 'abcdefghijk', 'source': 'qobuz', 'trackId': 11}]})
        self.write(self.library / 'catalogue.json', {'rows': [{
            'videoId': 'abcdefghijk', 'position': 1, 'candidates': [{'id': 11, 'title': 'Native', 'artist': 'Artist'}]}]})
        selection_path = self.library / 'comparison-results/library-files.json'
        selected = json.loads(selection_path.read_bytes())
        selected['catalogueSha256'] = sha256_file(self.library / 'catalogue.json')
        self.write(selection_path, selected)
        self.old_root = self.builder / 'future-songs/policy-state/stemwise-future-song-21'
        self.old_job = self.old_root / 'jobs/abcdefghijk/job.json'
        self.write(self.old_job, {'videoId': 'abcdefghijk', 'state': 'DELIVERED'})
        self.old_bytes = self.old_job.read_bytes()

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        atomic_write_json(path, value)


    def legacy_workflow(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        with patch.object(FutureSongWorkflow, '_validate_policy', return_value={}):
            workflow = FutureSongWorkflow(self.config_path)
        workflow.report_store.workflow_operation_state = Mock(return_value='LINE_UNRESOLVED')
        workflow.report_store.workflow_operation_bindings = Mock(return_value={})
        workflow.report_store.commit_workflow_operation_record = Mock()
        for name in ('_new_action', '_validate_action', '_finalize_action', '_retry_action'):
            setattr(workflow, name, Mock(return_value={'fixture': 'executed'}))
        workflow._load_job = Mock(return_value=json.loads(self.old_bytes))
        return workflow

    def test_superseded_youtube_actions_reject_before_work_or_state_changes(self):
        from stemwise_engine.future_song import FutureSongError
        audio_sha = sha256_file(self.audio)
        for binding in ('library-selection', 'download-tombstone'):
            if binding == 'download-tombstone':
                self.write(self.library / 'comparison-results/library-files.json', {'playlist': []})
                self.write(self.library / 'audio/youtube/abcdefghijk/download.json',
                    {'videoId': 'abcdefghijk', 'status': 'replaced_by_flac', 'replacedBy': {'trackId': 11}})
            for action in ('new', 'validate', 'finalize', 'retry', 'recording_evidence',
                           'endpoint_evidence', 'endpoint_adjudicate'):
                with self.subTest(binding=binding, action=action):
                    workflow = self.legacy_workflow()
                    if action == 'endpoint_adjudicate':
                        call = lambda: workflow.endpoint_evidence('abcdefghijk', vocal=True)
                    elif action == 'new':
                        call = lambda: workflow.new('https://youtube.com/watch?v=abcdefghijk')
                    else:
                        call = lambda: getattr(workflow, action)('abcdefghijk')
                    with self.assertRaisesRegex(FutureSongError, 'Approved FLAC replaces') as caught:
                        call()
                    self.assertEqual(workflow._workflow_operation_failure_code(caught.exception),
                                     'superseded-recording')
                    workflow.report_store.commit_workflow_operation_record.assert_not_called()
                    for name in ('_new_action', '_validate_action', '_finalize_action', '_retry_action'):
                        getattr(workflow, name).assert_not_called()
                    self.assertEqual(self.old_job.read_bytes(), self.old_bytes)
                    self.assertEqual(sha256_file(self.audio), audio_sha)

    def test_unmatched_youtube_still_reaches_normal_workflow(self):
        self.write(self.library / 'comparison-results/library-files.json', {
            'playlist': [{'videoId': 'abcdefghijk', 'source': 'youtube'}]})
        workflow = self.legacy_workflow()
        self.assertEqual(workflow.new('https://youtube.com/watch?v=lmnopqrstuv'), {'fixture': 'executed'})
        workflow._new_action.assert_called_once_with('https://youtube.com/watch?v=lmnopqrstuv', max_file_bytes=None)
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)

    def test_unreadable_selection_fails_closed_without_claiming_a_replacement(self):
        from stemwise_engine.future_song import FutureSongError
        (self.library / 'comparison-results/library-files.json').write_text('{broken')
        workflow = self.legacy_workflow()
        with self.assertRaisesRegex(FutureSongError, 'selection cannot be verified') as caught:
            workflow.new('https://youtube.com/watch?v=abcdefghijk')
        self.assertEqual(workflow._workflow_operation_failure_code(caught.exception), 'future-song-error')
        workflow._new_action.assert_not_called()
        workflow.report_store.commit_workflow_operation_record.assert_not_called()
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)

    def embed_native_content(self):
        from PIL import Image
        image = self.audio.parent / 'fixture-cover.png'
        Image.new('RGB', (48, 64), (25, 85, 145)).save(image)
        output = self.audio.parent / 'tagged.flac'
        command = ['ffmpeg', '-v', 'error', '-i', str(self.audio), '-i', str(image),
                   '-map', '0:a', '-map', '1:v', '-c', 'copy', '-disposition:v', 'attached_pic']
        command += [str(output)]
        subprocess.run(command, check=True, capture_output=True, timeout=15)
        output.replace(self.audio)
        self.write(self.audio.parent/'download.json', {'qobuzTrackId':11,'file':self.audio.name,'bytes':self.audio.stat().st_size,'sha256':sha256_file(self.audio)})

    def test_embedded_flac_cover_precedes_youtube_and_preserves_real_dimensions(self):
        from stemwise_engine.pipeline import StemwisePipeline
        self.embed_native_content()
        workflow = self.workflow()
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            pipeline = workflow._pipeline()
        with patch('stemwise_engine.youtube.fetch_video_info', side_effect=AssertionError('YouTube is artwork fallback')):
            info = pipeline._song_info(workflow.video_id)
            destination = self.audio.parent / 'rendered.png'
            artwork = pipeline._create_cover(info, workflow.video_id, destination)
        from PIL import Image
        with Image.open(destination) as image:
            self.assertEqual(image.size, (48, 64))
            self.assertEqual(image.getpixel((0, 0)), (25, 85, 145))
        self.assertEqual(artwork['kind'], 'album-cover')
        self.assertEqual(artwork['source']['url'], 'https://play.qobuz.com/track/11')
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)

    def test_missing_flac_cover_uses_the_existing_youtube_renderer(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from PIL import Image
        workflow = self.workflow()
        payload = io.BytesIO()
        Image.new('RGB', (64, 48), (145, 85, 25)).save(payload, format='PNG')
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            pipeline = workflow._pipeline()
        with patch('stemwise_engine.youtube.fetch_video_info', return_value={'id': workflow.video_id}) as fetch, \
                patch('stemwise_engine.youtube._download_image', return_value=payload.getvalue()):
            artwork = pipeline._create_cover(pipeline._song_info(workflow.video_id),
                workflow.video_id, self.audio.parent / 'fallback.png')
        fetch.assert_called_once_with(workflow.video_id)
        self.assertEqual(artwork['kind'], 'youtube-thumbnail')
        self.assertEqual(artwork['source']['url'], f'https://www.youtube.com/watch?v={workflow.video_id}')

    def test_embedded_cover_uses_actual_front_resolution_and_only_falls_back_when_fronts_are_invalid(self):
        from PIL import Image
        from types import SimpleNamespace
        from stemwise_engine import recording_source
        payloads = {}
        for index, size, color in ((0, (64, 64), '#3581aa'), (1, (96, 80), '#aa8135'),
                                   (2, (128, 128), '#35aa81')):
            with Image.new('RGB', size, color) as image, io.BytesIO() as encoded:
                image.save(encoded, format='PNG')
                payloads[index] = encoded.getvalue()
        streams = [
            {'index': 0, 'width': 128, 'height': 128, 'disposition': {'attached_pic': 1}, 'tags': {'comment': 'Cover (front)'}},
            {'index': 1, 'width': 99999, 'height': 99999, 'disposition': {'attached_pic': 1}, 'tags': {'comment': 'Cover (front)'}},
            {'index': 2, 'width': 128, 'height': 128, 'disposition': {'attached_pic': 1}, 'tags': {'comment': 'Cover (back)'}},
        ]
        for fronts_valid in (True, False):
            with self.subTest(fronts_valid=fronts_valid):
                def extract(command, **_):
                    if command[0] == 'ffprobe':
                        return SimpleNamespace(stdout=json.dumps({'streams': streams}))
                    index = int(command[command.index('-map')+1].split(':')[1])
                    return SimpleNamespace(stdout=payloads[index] if fronts_valid or index == 2 else b'invalid')
                destination = self.workspace/'embedded-ranked.png'
                with patch.object(recording_source, 'run_process_tree', side_effect=extract):
                    self.assertTrue(recording_source.embedded_cover(self.audio, destination,
                                                                  max_pixels=128, max_bytes=1000000))
                with Image.open(destination) as chosen:
                    self.assertEqual(chosen.size, (96, 80) if fronts_valid else (128, 128))
                    self.assertEqual(chosen.getpixel((0, 0)), (170, 129, 53) if fronts_valid else (53, 170, 129))

    def test_embedded_cover_later_terminal_failure_preserves_previous_destination(self):
        from PIL import Image
        from types import SimpleNamespace
        from stemwise_engine import recording_source
        from stemwise_engine.common import WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        with Image.new('RGB', (64, 64), '#3581aa') as image, io.BytesIO() as encoded:
            image.save(encoded, format='PNG')
            payload = encoded.getvalue()
        streams = [{'index': index, 'width': 64-index, 'height': 64-index,
                    'disposition': {'attached_pic': 1}, 'tags': {'comment': 'Cover (front)'}} for index in range(2)]
        for failure in (ProviderCancelled('cancelled'), ProviderDeadline('deadline'), WorkerRetirementError('not retired')):
            with self.subTest(failure=type(failure).__name__):
                destination = self.workspace/'embedded-preserved.png'
                previous = b'previous committed cover'
                destination.write_bytes(previous)
                def extract(command, **_):
                    if command[0] == 'ffprobe':
                        return SimpleNamespace(stdout=json.dumps({'streams': streams}))
                    if command[command.index('-map')+1] == '0:1':
                        raise failure
                    return SimpleNamespace(stdout=payload)
                with patch.object(recording_source, 'run_process_tree', side_effect=extract):
                    with self.assertRaises(type(failure)):
                        recording_source.embedded_cover(self.audio, destination, max_pixels=128, max_bytes=1000000)
                self.assertEqual(destination.read_bytes(), previous)

    def test_native_artwork_replaces_an_existing_cover_without_reanalysis_and_rejects_forgery(self):
        self.assert_native_artwork_replacement('WAITING_FOR_EDITORIAL')

    def test_missing_lyrics_does_not_block_native_artwork_selection(self):
        self.assert_native_artwork_replacement('NEEDS_LYRICS')

    def assert_native_artwork_replacement(self, state):
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.future_song import FutureSongError
        from stemwise_engine.packages import create_child_zip, read_child
        from PIL import Image
        self.embed_native_content()
        workflow = self.workflow()
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            pipeline = workflow._pipeline()
        workflow.policy_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(PROJECT / 'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json',
                     workflow.policy_dir / 'lyrics-policy.json')
        shutil.copytree(PROJECT/'StemWise/contracts', workflow.workspace_root/'StemWise/contracts', dirs_exist_ok=True)
        workflow.policy = workflow._validate_policy()
        job = workflow._base_job(workflow.video_id, f'https://youtube.com/watch?v={workflow.video_id}')
        job.update(state=state, durationSeconds=1,
                   outcome={'code': 'authority-unresolved'}, error='lyric-order-unresolved')
        job_dir = workflow._job_dir(workflow.video_id)
        self.write(job_dir / 'job.json', job)
        sources = {'recordingInput': workflow.recording[3],
                   'lyricEvidenceSources': [{'videoId': workflow.video_id}]}
        self.write(job_dir / 'sources.json', sources)
        self.write(job_dir / 'agent-review.json', {'lyricEvidenceVideoIds': [workflow.video_id],
                                                'authorityOutcome': 'authority-unresolved'})
        authority_before = (job_dir / 'agent-review.json').read_bytes()
        document = song_document(workflow.video_id)
        document['source'] = pipeline._source_identity(workflow.video_id)
        child = pipeline.child_path(workflow.video_id)
        cover = job_dir / 'fixture-cover.png'
        Image.new('RGB', (32, 32), 'red').save(cover)
        create_child_zip(child, document, cover, workflow._runtime_config())
        pipeline.save_checkpoint({'videoId': workflow.video_id, 'status': 'complete',
            'childSha256': sha256_file(child), 'stages': {}, 'recordingInput': workflow.recording[3]})
        audio_before = self.audio.read_bytes()
        # The bound FLAC cover must be independent of unresolved lyric adjudication.
        with patch.object(workflow, '_validate_authority_outcome',
                          side_effect=AssertionError('Embedded artwork must not consult lyric authority')), \
                patch.object(workflow, '_pipeline', return_value=pipeline), \
                patch.object(workflow, '_write_task'), \
                patch('stemwise_engine.youtube.fetch_video_info', side_effect=AssertionError('No YouTube cover')), \
                patch.object(pipeline, '_analysis', side_effect=AssertionError('No acoustic rerun')):
            result = workflow._select_artwork_action(workflow.video_id, 'https://play.qobuz.com/track/11')
            selected = workflow._load_job(workflow.video_id)
            with patch.object(pipeline.source, 'embedded_cover', side_effect=AssertionError('Approval regenerated imagery')):
                workflow._require_reviewed_artwork(selected, pipeline)
            selected_child, selected_job = child.read_bytes(), (job_dir / 'job.json').read_bytes()
            with patch.object(workflow, '_save_job', side_effect=OSError('fixture save failure')):
                with self.assertRaisesRegex(OSError, 'save failure'):
                    workflow._select_artwork_action(workflow.video_id, 'https://play.qobuz.com/track/11')
            self.assertEqual(child.read_bytes(), selected_child)
            self.assertEqual((job_dir / 'job.json').read_bytes(), selected_job)
        updated, pixels = read_child(child, workflow._runtime_config())
        self.assertEqual(updated['metadata'], document['metadata'])
        self.assertEqual(updated['analysis'], document['analysis'])
        self.assertEqual(updated['lyrics'], document['lyrics'])
        self.assertEqual((job_dir / 'agent-review.json').read_bytes(), authority_before)
        self.assertEqual(result['state'], state)
        self.assertEqual(result['outcome'], {'code': 'authority-unresolved'})
        self.assertEqual(result['error'], 'lyric-order-unresolved')
        with patch.object(workflow, '_validate_authority_outcome',
                          return_value=('authority-unresolved', {}, sources)):
            before = child.read_bytes()
            with self.assertRaisesRegex(FutureSongError, 'requires resolved'):
                workflow._select_artwork_action(workflow.video_id,
                    f'https://youtube.com/watch?v={workflow.video_id}')
            self.assertEqual(child.read_bytes(), before)
        self.assertGreater(updated['generatedAt'], document['generatedAt'])
        self.assertEqual(result['artworkReview']['sourceVideoId'], 'qobuz:11')
        with Image.open(io.BytesIO(pixels)) as image:
            self.assertEqual(image.size, (48, 64))
            self.assertEqual(image.getpixel((0, 0)), (25, 85, 145))
        self.assertEqual(self.audio.read_bytes(), audio_before)
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)
        Image.new('RGB', (48, 64), 'blue').save(cover)
        create_child_zip(child, updated, cover, workflow._runtime_config())
        selected['artworkReview']['artworkSha256'] = sha256_file(cover)
        with self.assertRaisesRegex(FutureSongError, 'committed artwork'):
            workflow._require_reviewed_artwork(selected, pipeline)

    def workflow(self, key='qobuz:11', *, verify_media=True):
        from stemwise_engine.future_song import FutureSongWorkflow
        from stemwise_engine.recording_workflow import RecordingWorkflow
        def policy(workflow):
            workflow.contract_identity = {'song-enrichment-v2.schema.json': '3'*64,
                'installed-enrichment-v2.schema.json': '4'*64, 'contract-limits.json': '5'*64}
            return {}
        with patch.object(FutureSongWorkflow, '_validate_policy', policy):
            return RecordingWorkflow(self.config_path, key, verify_media=verify_media)

    def test_status_projects_receipts_with_bounded_actual_audio_reads_and_deep_operations_reverify(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        rng = np.random.default_rng(17)
        sf.write(self.audio, rng.integers(-32768, 32767, size=(480_000, 2), dtype=np.int16),
                 48000, subtype='PCM_16')
        self.write(self.audio.parent / 'download.json', {'qobuzTrackId': 11, 'file': self.audio.name,
            'bytes': self.audio.stat().st_size, 'sha256': sha256_file(self.audio)})
        count = 0
        original_open = Path.open
        audio_path = self.audio.resolve()
        class Reader:
            def __init__(self, stream): self.stream = stream
            def __getattr__(self, name): return getattr(self.stream, name)
            def read(self, size=-1):
                nonlocal count
                value = self.stream.read(size)
                count += len(value)
                return value
            def readinto(self, buffer):
                nonlocal count
                size = self.stream.readinto(buffer)
                count += size
                return size
            def __enter__(self): return self
            def __exit__(self, *args): return self.stream.__exit__(*args)
        def opened(path, *args, **kwargs):
            stream = original_open(path, *args, **kwargs)
            return Reader(stream) if path.resolve() == audio_path else stream
        with patch.object(Path, 'open', opened), patch.object(FutureSongWorkflow,
                '_validated_snapshot_is_current', side_effect=AssertionError('Status must not rehash a delivery')):
            workflow = self.workflow(verify_media=False)
            from stemwise_engine.editorial_review import POLICY_VERSION
            job = {'videoId': workflow.video_id, 'policyVersion': POLICY_VERSION,
                   'recordingInput': workflow.recording[3], 'deliveryAllowed': True}
            child = workflow.ready / f'{workflow.video_id}.stemwise.zip'
            child.parent.mkdir(parents=True, exist_ok=True)
            child.write_bytes(b'committed private package')
            job.update(state='DELIVERED', delivery={'sha256': sha256_file(child), 'bytes': child.stat().st_size,
                'readyToImport': True})
            self.write(workflow._job_path(workflow.video_id), job)
            self.write(workflow.ready / 'recording.json', workflow._result_binding(job))
            result = workflow.status(workflow.video_id)
            self.assertEqual(result['state'], 'DELIVERED')
            self.assertEqual(result['deliveryVerification']['status'], 'not-rechecked')
            self.assertFalse(result['phoneDeliveryAllowed'])
            self.assertLess(count, 128 * 1024, f'Status read {count} audio bytes')
            projection_reads = count
            deep = self.workflow()
            self.assertEqual(deep.recording[3], workflow.recording[3])
            self.assertGreaterEqual(count - projection_reads, self.audio.stat().st_size)

    def test_future_download_enters_native_workflow_and_survives_inventory_refresh(self):
        import stemwise_media.audio_library as audio_library
        import stemwise_media.quick_batch as quick_batch
        from stemwise_engine.pipeline import StemwisePipeline
        future = self.library / 'audio/qobuz/22/future.flac'
        initial = json.loads((self.library / 'catalogue.json').read_bytes())
        initial['rows'][0]['ownerAudioSelection'] = {
            'source': 'qobuz', 'trackId': 11, 'file': 'audio/qobuz/11/native.flac'}
        self.write(self.library / 'catalogue.json', initial)
        from stemwise_engine.delivery_selection import catalogue as selected_catalogue
        from types import SimpleNamespace
        owner = SimpleNamespace(pc_side_root=self.pc, workspace_root=self.workspace)
        with self.assertRaisesRegex(ValueError, 'projection is stale'):
            selected_catalogue(owner)
        with patch.object(quick_batch, 'ROOT', self.library):
            quick_batch.export_library(initial)
        self.assertEqual(selected_catalogue(owner), {'abcdefghijk': 'qobuz:11'})
        catalogue = (self.library / 'catalogue.json').read_bytes()
        def download(track, **_):
            self.assertEqual(track, 22)
            future.parent.mkdir(parents=True)
            shutil.copy2(self.audio, future)
            from qobuz_fixtures import write_download
            write_download(future.parent, {'qobuzTrackId': 22, 'file': future.name})
            return {'status': 'saved'}
        with patch.object(audio_library, 'ROOT', self.library), patch.object(quick_batch, 'ROOT', self.library), \
                patch.object(audio_library, 'download_qobuz', download), \
                patch('sys.stdout', new_callable=io.StringIO) as stdout:
            code = audio_library.main(['download', '--provider', 'doubledouble', '22',
                '--youtube', 'lmnopqrstuv', '--title', 'Future song', '--artist', 'Future artist'])
        self.assertEqual(code, 0)
        result = json.loads(stdout.getvalue())
        self.assertEqual(result['recordingKey'], 'qobuz:22')
        self.assertFalse(result['analysisStarted'])
        workflow = self.workflow('qobuz:22')
        self.assertEqual(workflow.video_id, 'lmnopqrstuv')
        self.assertEqual(workflow.recording[2], future)
        self.assertEqual(workflow.recording[5]['title'], 'Future song')
        with patch.object(quick_batch, 'ROOT', self.library):
            quick_batch.export_library(json.loads(catalogue))
        files = json.loads((self.library / 'comparison-results/library-files.json').read_text())
        self.assertEqual({item['trackId'] for item in files['flac']}, {11, 22})
        self.assertEqual(len(files['playlist']), 1)
        import stemwise_media.enrichment_plan as enrichment_plan
        state = self.old_root
        with patch.object(enrichment_plan, 'ROOT', self.library), patch.object(quick_batch, 'ROOT', self.library):
            plan = enrichment_plan.build_plan(files, json.loads(catalogue), state)
        self.assertNotIn('qobuz:22', {item['recordingKey'] for item in plan['audioRecordings']})
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            consumer = workflow._pipeline()
        temporary = workflow.analysis_temp / 'decode'
        temporary.mkdir()
        with patch('stemwise_engine.youtube.download_analysis_audio', side_effect=AssertionError('No YouTube substitute')):
            _, wav = consumer._acquire_analysis_audio(workflow.video_id, temporary)
        decoded, _ = sf.read(wav, dtype='float32', always_2d=True)
        expected, _ = sf.read(future, dtype='float32', always_2d=True)
        np.testing.assert_array_equal(decoded, expected)
        workflow._job_dir(workflow.video_id).mkdir(exist_ok=True)
        workflow._write_task({'videoId': workflow.video_id, 'state': 'NEEDS_LYRICS'})
        task = (workflow._job_dir(workflow.video_id)/'AGENT_TASK.txt').read_text(encoding='utf-8')
        self.assertIn('Native recording: qobuz:22', task)
        self.assertIn("-RecordingKey 'qobuz:22'", task)
        refreshed = json.loads((self.library / 'catalogue.json').read_bytes())
        self.assertEqual(refreshed['rows'], json.loads(catalogue)['rows'])
        self.assertEqual(refreshed['librarySelectionCounts']['flacFiles'], 2)
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)
        self.assertFalse(workflow._job_path(workflow.video_id).exists())
        (self.library / 'catalogue.json').unlink()
        (self.library / 'comparison-results/library-files.json').unlink()
        fresh = self.workflow('qobuz:22')
        self.assertEqual(fresh.recording[3], workflow.recording[3])
        self.assertEqual(fresh.video_id, 'lmnopqrstuv')

    def test_future_registration_rejects_rebinding_bad_codec_and_owner_rejection(self):
        import stemwise_media.audio_library as audio_library
        import stemwise_media.quick_batch as quick_batch
        future = self.library / 'audio/qobuz/22/future.flac'
        future.parent.mkdir(parents=True)
        shutil.copy2(self.audio, future)
        from qobuz_fixtures import write_download
        write_download(future.parent, {'qobuzTrackId': 22, 'file': future.name})
        args = ['register', '--qobuz', '22', '--youtube', 'lmnopqrstuv',
                '--title', 'Future song', '--artist', 'Future artist']
        with patch.object(audio_library, 'ROOT', self.library), patch.object(quick_batch, 'ROOT', self.library), \
                patch.object(audio_library, 'download_qobuz', side_effect=AssertionError('No network')), \
                patch('sys.stdout', new_callable=io.StringIO), patch('sys.stderr', new_callable=io.StringIO):
            self.assertEqual(audio_library.main(args), 0)
            binding = future.parent / 'enrichment-input.json'
            original = binding.read_bytes()
            self.assertEqual(audio_library.main(args), 0)
            changed = args.copy()
            changed[changed.index('lmnopqrstuv')] = '01234567890'
            self.assertEqual(audio_library.main(changed), 2)
            self.assertEqual(binding.read_bytes(), original)
            self.write(future.parent / 'download.json', {'qobuzTrackId': 22, 'file': future.name, 'status': 'owner_rejected'})
            self.assertEqual(audio_library.main(args), 2)
            self.assertEqual(binding.read_bytes(), original)
            from qobuz_fixtures import write_download
            write_download(future.parent, {'qobuzTrackId': 22, 'file': future.name})
            future.write_bytes(b'Not a FLAC')
            self.assertEqual(audio_library.main(args), 2)
            self.assertEqual(binding.read_bytes(), original)

    def test_incomplete_future_download_selection_is_rejected_before_network(self):
        import stemwise_media.audio_library as audio_library
        with patch.object(audio_library, 'download_qobuz', side_effect=AssertionError('No network')), \
                patch('sys.stdout', new_callable=io.StringIO), patch('sys.stderr', new_callable=io.StringIO):
            self.assertEqual(audio_library.main(['download', '--provider', 'doubledouble', '22',
                '--youtube', 'lmnopqrstuv', '--title', 'Future song']), 2)
            self.assertEqual(audio_library.main(['download', '--provider', 'youtube', 'lmnopqrstuv',
                '--youtube', 'lmnopqrstuv', '--title', 'Future song', '--artist', 'Future artist']), 2)
        self.assertFalse((self.library / 'audio/qobuz/22').exists())

    def test_editorial_and_alignment_state_are_recording_scoped(self):
        workflow = self.workflow()
        self.assertNotEqual(workflow.root, self.old_root)
        self.assertEqual(workflow.video_id, 'abcdefghijk')
        self.assertIn(sha256_file(self.audio), str(workflow.root))
        self.assertFalse(workflow.ready.is_relative_to(self.workspace / 'Ready to Import'))
        self.assertEqual(workflow.report_store.report_root(workflow.video_id), self.workspace /
            'Engine telemetry/qobuz-11/reports' / sha256_file(self.audio) / 'abcdefghijk')
        self.assertEqual(workflow._runtime_config()['localRecordingKey'], 'qobuz:11')
        old_report_root = workflow.report_store.ensure_report_identity(workflow.video_id)
        old_report_identity = (old_report_root / 'identity.json').read_bytes()
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)
        self.write(workflow._job_path(workflow.video_id), {'state': 'LINE_UNRESOLVED'})
        old_scoped = workflow.root
        sf.write(self.audio, np.zeros((48000, 2)), 48000, subtype='PCM_24')
        from qobuz_fixtures import write_download
        write_download(self.audio.parent, {'qobuzTrackId': 11, 'file': self.audio.name})
        changed = self.workflow()
        self.assertNotEqual(changed.root, old_scoped)
        self.assertNotEqual(changed.report_store.report_root(changed.video_id), old_report_root)
        self.assertEqual((old_report_root / 'identity.json').read_bytes(), old_report_identity)
        self.assertTrue((old_scoped / 'jobs/abcdefghijk/job.json').exists())

    def test_youtube_workflow_constructor_uses_the_shared_project_telemetry_group(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        from stemwise_engine.workflow_context import telemetry_job_root
        with patch.object(FutureSongWorkflow, '_validate_policy', return_value={}):
            workflow = FutureSongWorkflow(self.config_path)
        video_id = 'a1B2c3D4e5F'
        reports = workflow.report_store.ensure_report_identity(video_id)
        self.assertEqual(reports, telemetry_job_root(self.workspace, f'youtube:{video_id}') / 'reports')
        self.assertEqual(workflow.report_store.report_root(video_id), reports)
        self.assertEqual(json.loads((reports / 'identity.json').read_bytes())['videoId'], video_id)
        self.assertFalse((self.pc / 'reports').exists())

    def test_alignment_child_cannot_use_old_or_foreign_namespace(self):
        from stemwise_engine.recording_source import alignment_config
        workflow = self.workflow()
        job = {'videoId': workflow.video_id, 'recordingInput': workflow.recording[3]}
        config = alignment_config(workflow.config, job, workflow.root)
        self.assertEqual(config['recordingInput'], workflow.recording[3])
        with self.assertRaisesRegex(ValueError, 'workspace'):
            alignment_config(workflow.config, job, self.old_root)
        wrong = {**job, 'recordingInput': {**job['recordingInput'], 'sha256': 'f' * 64}}
        with self.assertRaisesRegex(ValueError, 'input'):
            alignment_config(workflow.config, wrong, workflow.root)

    def test_completed_result_requires_its_native_binding_as_well_as_the_validated_zip(self):
        from stemwise_engine.future_song import FutureSongWorkflow
        workflow = self.workflow()
        job = {'videoId': workflow.video_id, 'state': 'DELIVERED', 'deliveryAllowed': True,
               'delivery': {'sha256': 'a' * 64, 'readyToImport': True}}
        # Existing ZIP/content verification is tested separately; this checks the extra native binding.
        with patch.object(FutureSongWorkflow, '_validated_snapshot_is_current', return_value=True), \
                patch('stemwise_engine.native_delivery.current', return_value=[]) as phone:
            self.assertEqual(workflow._status_summary(job)['state'], 'STATUS_ERROR')
            binding = workflow._result_binding(job)
            self.write(workflow.ready / 'recording.json', binding)
            self.assertEqual(workflow._status_summary(job)['state'], 'DELIVERY_AUDIO_REQUIRED')
            phone.return_value = [{'videoId': workflow.video_id, 'sha256': 'c' * 64}]
            self.assertEqual(workflow._status_summary(job)['state'], 'DELIVERED')
            binding['input'] = {**binding['input'], 'sha256': 'b' * 64}
            self.write(workflow.ready / 'recording.json', binding)
            self.assertEqual(workflow._status_summary(job)['state'], 'STATUS_ERROR')






    def test_editorial_acoustic_result_is_reused_by_native_analyzer_without_decoding(self):
        workflow = self.workflow()
        import stemwise_engine.pipeline as pipeline
        import stemwise_engine.recording_analysis as recording_analysis
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.waveform import build_waveform
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            consumer = workflow._pipeline()
        temporary = workflow.analysis_temp / 'decode'
        temporary.mkdir()
        _, wav = consumer._acquire_analysis_audio(workflow.video_id, temporary)
        result = song_document(workflow.video_id)['analysis']
        result['waveform'] = build_waveform(wav, duration_ms=1000)
        result['toolRuns'] = [{'name': r['name'], 'status': 'complete', 'version': '1'}
            for r in result['toolRuns']]
        with patch.object(pipeline, 'run_analysis_worker', return_value=result) as worker, \
                patch.object(pipeline, 'collect_provenance', return_value={'acousticRuntime': self.acoustic_runtime}):
            self.assertEqual(consumer._analysis(wav, 1000), result)
        worker.assert_called_once()
        with patch.object(recording_analysis, 'run_analysis_worker', side_effect=AssertionError('No inference')), \
                patch.object(recording_analysis, 'decode_analysis_audio', side_effect=AssertionError('No decode')):
            cached = recording_analysis.analyze(workflow.config, 'qobuz:11')
        self.assertTrue(cached['reused'])
        self.assertEqual(cached['state'], 'ANALYSIS_COMPLETE')
        self.assertEqual(cached['pcm']['sha256'], sha256_file(wav))
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)

    def test_completed_checkpoint_cannot_bypass_changed_acoustic_producer(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import create_child_zip
        from test_packages import PNG_1X1
        workflow = self.workflow()
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            consumer = workflow._pipeline()
        item = {'videoId': workflow.video_id, 'position': 1}
        document = song_document(workflow.video_id)
        document['source']['recording'] = workflow.recording[3]
        cover = workflow.runtime / 'cover.png'
        cover.write_bytes(PNG_1X1)
        create_child_zip(consumer.child_path(workflow.video_id), document, cover, consumer.config)
        state = consumer._new_state(item, 'a' * 64)
        state['status'] = 'complete'
        state['childSha256'] = sha256_file(consumer.child_path(workflow.video_id))
        consumer.save_checkpoint(state)
        arguments = {'force': False, 'lyrics': document['lyrics'], 'owner_lyrics_sha256': 'a' * 64}
        self.assertTrue(consumer._can_skip(item, **arguments))
        original = consumer.checkpoint_path(workflow.video_id).read_bytes()
        self.acoustic_runtime = {'packages': {'torch': {'version': 'qualified-v2'}}}
        self.assertFalse(consumer._can_skip(item, **arguments))
        self.assertEqual(consumer.checkpoint_path(workflow.video_id).read_bytes(), original)

    def test_native_source_change_before_cache_publication_is_rejected(self):
        workflow = self.workflow()
        import stemwise_engine.pipeline as pipeline
        import stemwise_engine.recording_source as recording_source
        import stemwise_engine.recording_analysis as recording_analysis
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.waveform import build_waveform
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            consumer = workflow._pipeline()
        temporary = workflow.analysis_temp / 'decode'
        temporary.mkdir()
        _, wav = consumer._acquire_analysis_audio(workflow.video_id, temporary)
        result = song_document(workflow.video_id)['analysis']
        result['waveform'] = build_waveform(wav, duration_ms=1000)
        result['toolRuns'] = [{'name': r['name'], 'status': 'complete', 'version': '1'}
            for r in result['toolRuns']]
        cache_root = recording_analysis.selected_input(workflow.config, 'qobuz:11')[2]
        publish = recording_analysis.cache_completed_analysis
        def replace_before_publish(*args, **kwargs):
            sf.write(self.audio, np.zeros((48000, 2)), 48000, subtype='PCM_24')
            return publish(*args, **kwargs)
        with patch.object(pipeline, 'run_analysis_worker', return_value=result), \
                patch.object(pipeline, 'collect_provenance', return_value={'acousticRuntime': self.acoustic_runtime}), \
                patch.object(recording_analysis, 'cache_completed_analysis', side_effect=replace_before_publish) as publication:
            with self.assertRaises(ValueError):
                consumer._analysis(wav, 1000)
        publication.assert_called_once()
        self.assertFalse((cache_root/'job.json').exists())
        with self.assertRaises(ValueError):
            recording_analysis.status(workflow.config, 'qobuz:11')
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)




    def test_native_decoder_and_completed_acoustic_result_reused_without_youtube_audio(self):
        workflow = self.workflow()
        import stemwise_engine.pipeline as pipeline
        from stemwise_engine.recording_analysis import analyze
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.waveform import build_waveform
        def worker(wav, duration, *_):
            value = song_document('abcdefghijk')['analysis']
            value['waveform'] = build_waveform(wav, duration_ms=duration)
            value['toolRuns'] = [{'name': r['name'], 'status': 'complete', 'version': '1'} for r in value['toolRuns']]
            return value
        with patch('stemwise_engine.recording_analysis.run_analysis_worker', worker):
            analyze(workflow.config, 'qobuz:11')
        # Fixture substitutes release/model metadata, never the source decoder or cache check.
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            consumer = workflow._pipeline()
        temporary = workflow.analysis_temp / 'decoder'
        temporary.mkdir(parents=True)
        with patch('stemwise_engine.youtube.download_analysis_audio', side_effect=AssertionError('No YouTube audio')):
            _, wav = consumer._acquire_analysis_audio(workflow.video_id, temporary)
        native, rate = sf.read(self.audio, dtype='float32', always_2d=True)
        decoded, decoded_rate = sf.read(wav, dtype='float32', always_2d=True)
        np.testing.assert_array_equal(native, decoded)
        self.assertEqual((rate, decoded_rate), (48000, 48000))
        with patch.object(pipeline, 'run_analysis_worker', side_effect=AssertionError('Do not repeat acoustic analysis')):
            self.assertEqual(consumer._analysis(wav, 1000)['durationMs'], 1000)
        sf.write(wav, np.zeros((48000, 2)), 48000, subtype='FLOAT')
        with self.assertRaisesRegex(ValueError, 'PCM'):
            consumer._analysis(wav, 1000)
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)

    def test_portable_native_result_retains_its_recording_binding(self):
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import PackageValidationError, validate_document
        workflow = self.workflow()
        with patch.object(StemwisePipeline, '_refresh_provenance'):
            consumer = workflow._pipeline()
        document = song_document(workflow.video_id)
        document['source'] = consumer._source_identity(workflow.video_id)
        self.assertEqual(document['source'].get('recording'), workflow.recording[3])
        validate_document(document, workflow.video_id)
        validate_document(document, workflow.video_id, recording_input=workflow.recording[3])
        document['source'].pop('recording')
        with self.assertRaisesRegex(PackageValidationError, 'recording'):
            validate_document(document, workflow.video_id, recording_input=workflow.recording[3])

    def test_full_editorial_setup_receives_native_pcm_and_pending_review(self):
        self._check_full_editorial_setup()

    def test_unavailable_youtube_reference_keeps_native_analysis_and_source_review_usable(self):
        self.embed_native_content()
        self._check_full_editorial_setup(unavailable_reference=True)

    def test_native_reference_acquisition_keeps_identity_errors_fatal(self):
        import stemwise_engine.future_song as future_song
        workflow = self.workflow()
        with patch.object(future_song.youtube, 'fetch_video_info',
                          side_effect=RuntimeError('Metadata belongs to another video')):
            with self.assertRaisesRegex(RuntimeError, 'another video'):
                workflow._collect_normal_candidates(workflow._song_info(workflow.video_id),
                    workflow._job_dir(workflow.video_id) / 'candidates', video_id=workflow.video_id)
        with self.assertRaisesRegex(future_song.FutureSongError, 'reference differs'):
            workflow._song_info('lmnopqrstuv')

    def _check_full_editorial_setup(self, *, unavailable_reference=False):
        import stemwise_engine.pipeline as pipeline
        import stemwise_engine.future_song as future_song
        from stemwise_engine.pipeline import StemwisePipeline
        from stemwise_engine.packages import read_child, PackageValidationError
        from PIL import Image
        from stemwise_engine.waveform import build_waveform
        workflow = self.workflow()
        workflow._windows_path = lambda path: str(path)
        workflow.policy_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(PROJECT / 'PC side/stemwise-playlist-builder/future-songs/policy/lyrics-policy.json',
                     workflow.policy_dir / 'lyrics-policy.json')
        shutil.copytree(PROJECT/'StemWise/contracts', workflow.workspace_root/'StemWise/contracts', dirs_exist_ok=True)
        workflow.policy = workflow._validate_policy()
        def provenance(consumer):
            consumer.provenance = {'acousticRuntime': self.acoustic_runtime}
            consumer.provenance_sha256 = 'a' * 64
            consumer._provenance_needs_refresh = False
        def analysis(wav, duration, *_):
            decoded, rate = sf.read(wav, dtype='float32', always_2d=True)
            expected, _ = sf.read(self.audio, dtype='float32', always_2d=True)
            np.testing.assert_array_equal(decoded, expected)
            self.assertEqual(rate, 48000)
            result = song_document(workflow.video_id)['analysis']
            result['waveform'] = build_waveform(wav, duration_ms=duration)
            result['toolRuns'] = [{'name': r['name'], 'status': 'complete', 'version': '1'} for r in result['toolRuns']]
            return result
        def cover(info, video_id, destination, **_):
            Image.new('RGB', (64, 64), 'red').save(destination)
            return song_document(video_id)['artwork']
        def acquire(item, draft):
            self.assertEqual(item['videoId'], workflow.video_id)
            self.write(draft / 'lyrics.meta.json', {'videoId': workflow.video_id, 'originalLanguage': 'en',
                'originalScript': 'Latn', 'sources': {'original': {'name': 'Text reference'}}})
            (draft / 'original.txt').write_text('First line\nSecond line\n', encoding='utf-8')
            return {'videoId': workflow.video_id, 'decision': 'acquired'}
        from contextlib import ExitStack
        with ExitStack() as stack:
            stack.enter_context(patch.object(StemwisePipeline, '_refresh_provenance', provenance))
            from yt_dlp.utils import DownloadError
            stack.enter_context(patch.object(future_song.youtube, 'fetch_video_info',
                return_value={'id': workflow.video_id, 'title': 'YouTube reference'},
                side_effect=DownloadError('Video unavailable') if unavailable_reference else None))
            stack.enter_context(patch('stemwise_engine.youtube.download_analysis_audio', side_effect=AssertionError('No YouTube audio')))
            stack.enter_context(patch.object(pipeline, 'run_analysis_worker', analysis))
            stack.enter_context(patch('stemwise_engine.youtube.create_cover_png', cover))
            stack.enter_context(patch.object(pipeline, 'enrich_metadata', side_effect=lambda value, *_, **kw: (value, {})))
            stack.enter_context(patch.object(source_acquisition, 'collect_normal_youtube_candidates', return_value={'usableLyricCandidateCount': 0}))
            acquisition = stack.enter_context(patch.object(source_acquisition, 'acquire_exact_item_draft', side_effect=acquire))
            stack.enter_context(patch.object(source_acquisition, 'discover_counterpart_candidates',
                side_effect=AssertionError('Known exact lyric fixture must not contact counterpart providers')))
            result = workflow.new('https://youtube.com/watch?v=abcdefghijk')
            self.assertEqual(result['state'], 'WAITING_FOR_EDITORIAL')
            acquisition.assert_called_once()
            self.assertEqual((workflow._job_dir(workflow.video_id) / 'drafts/original.txt').read_text(encoding='utf-8'),
                'First line\nSecond line\n')
            consumer = workflow._pipeline()
            actual = workflow._completed_analysis_audio(consumer, workflow.video_id)
            self.assertEqual(actual['durationMs'], 1000)
            self.assertEqual(actual['sha256'], sha256_file(actual['path']))
            child = consumer.child_path(workflow.video_id)
            document, _ = read_child(child, workflow._runtime_config())
            self.assertEqual(document['source']['recording'], workflow.recording[3])
            self.assertEqual(document['metadata']['title'], 'Native')
            portable, _ = read_child(child, package_config(self.workspace))
            self.assertEqual(portable['source']['recording'], workflow.recording[3])
            review = json.loads((workflow._job_dir(workflow.video_id) / 'agent-review.json').read_text())
            self.assertEqual(review['authorityOutcome'], 'pending')
            sources = json.loads((workflow._job_dir(workflow.video_id) / 'sources.json').read_text())
            self.assertEqual(sources['recordingInput'], workflow.recording[3])
            if unavailable_reference:
                check = sources['exactSurfaceChecks']['normalYouTube']
                self.assertFalse(check['exactVideoIdConfirmed'])
                self.assertEqual(check['reason'], 'DownloadError')
                self.assertEqual(check['usableLyricCandidateCount'], 0)
                self.assertEqual(check['records'], [])
            task = (workflow._job_dir(workflow.video_id) / 'AGENT_TASK.txt').read_bytes()
            workflow._write_task(workflow._load_job(workflow.video_id))
            self.assertEqual((workflow._job_dir(workflow.video_id) / 'AGENT_TASK.txt').read_bytes(), task)
            preserved = {path: path.read_bytes() for path in (
                workflow._job_path(workflow.video_id),
                workflow._job_dir(workflow.video_id) / 'agent-review.json',
                workflow._job_dir(workflow.video_id) / 'sources.json',
                workflow._job_dir(workflow.video_id) / 'drafts/original.txt',
                child, self.audio)}
            with patch.object(workflow, '_finalize_action') as publish:
                with self.assertRaisesRegex(future_song.FutureSongError,
                        'Finalize is not permitted during editorial/pending'):
                    workflow.finalize(workflow.video_id)
                publish.assert_not_called()
            self.assertEqual(workflow._load_job(workflow.video_id)['state'], 'WAITING_FOR_EDITORIAL')
            self.assertEqual({path: path.read_bytes() for path in preserved}, preserved)
        self.assertFalse(workflow.ready.exists())
        self.assertTrue(self.audio.exists())
        self.assertEqual(self.old_job.read_bytes(), self.old_bytes)

    def test_detached_native_new_keeps_recording_key_and_does_not_require_a_youtube_url(self):
        launcher = self.pc / 'STEMWISE_AGENT.ps1'
        from test_agent_launcher import copy_launcher
        copy_launcher(launcher)
        windows = subprocess.check_output(['wslpath', '-w', str(launcher)], text=True).strip()
        pc_windows = subprocess.check_output(['wslpath', '-w', str(self.pc)], text=True).strip()
        builder_windows = subprocess.check_output(['wslpath', '-w', str(self.builder)], text=True).strip()
        powershell = '/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe'
        self.write(self.pc/'runtime-layout.json',json.loads((PROJECT/'PC side/runtime-layout.json').read_bytes()))
        # Discover actual startup paths; fixture files replace dependencies, not dispatch/supervision.
        query = (f". '{builder_windows}\\command-spec.ps1'; $a=[Management.Automation.Language.Parser]::ParseFile('{windows}',[ref]$null,[ref]$null); "
            "$f=$a.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] "
            "-and $n.Name -eq 'Get-StemWiseActionDependencies'},$true); Invoke-Expression $f.Extent.Text; "
            f"@(Get-StemWiseActionDependencies -Action New -AgentRoot '{pc_windows}' -Builder '{builder_windows}')|ConvertTo-Json -Compress")
        dependencies = json.loads(subprocess.check_output([powershell, '-NoProfile', '-NonInteractive', '-Command', query], text=True))
        for windows_path in dependencies:
            path = Path(subprocess.check_output(['wslpath', '-u', windows_path], text=True).strip())
            if path.exists():
                continue
            if path.name == 'by-sha256':
                path.mkdir(parents=True)
            else:
                self.write(path, {})
        self.write(self.builder / 'src/recording_workflow.py', {})
        self.write(self.builder / 'src/recording_source.py', {})
        self.write(self.builder / 'src/pipeline.py', {})
        self.write(self.builder / 'src/recording_analysis.py', {})
        (self.builder / 'future-song.ps1').write_text(
            "param([string]$Command,[string]$RecordingKey)\n"
            "$o=@{command=$Command;key=$RecordingKey}|ConvertTo-Json -Compress\n"
            "[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'observed.json'),$o)\nexit 0\n")
        launched = subprocess.run([powershell, '-NoProfile', '-NonInteractive', '-File', windows,
            '-Action', 'New', '-RecordingKey', 'qobuz:11'], capture_output=True, text=True, timeout=20)
        self.assertEqual(launched.returncode, 0, launched.stderr[-1200:])
        receipt = json.loads(launched.stdout)
        self.assertIsNone(receipt['videoId'])
        self.assertEqual(receipt['recordingKey'], 'qobuz:11')
        self.assertIn("-Action Status -RecordingKey 'qobuz:11'", receipt['statusCommand'])
        completion = Path(subprocess.check_output(['wslpath', '-u', receipt['completion']], text=True).strip())
        deadline = time.monotonic() + 10
        while not completion.exists() and time.monotonic() < deadline:
            time.sleep(.05)
        self.assertTrue(completion.exists())
        self.assertEqual(json.loads(completion.read_text(encoding='utf-8-sig'))['exitCode'], 0)
        self.assertEqual(json.loads((self.builder / 'observed.json').read_text()), {'command': 'new', 'key': 'qobuz:11'})


if __name__ == '__main__':
    unittest.main()
