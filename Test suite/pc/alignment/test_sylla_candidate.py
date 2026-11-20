"""Public private-candidate orchestration; numerical model execution is a fixture boundary."""
import copy
import hashlib
import json
from pathlib import Path
import string
import subprocess
import unittest
from unittest.mock import patch

import test_available_completion as completion
from workflow_fixture import nested_case
from lyric_content_fixtures import write_recognition
from stemwise_engine.common import WorkerRetirementError, atomic_write_json, json_bytes, sha256_file
from stemwise_engine.ctc_worker import canonical_sha256, canonical_time_transform, chunk_time_map
from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
from stemwise_engine import sylla_forced_alignment as core, workflow_command


class SyllaCandidateTests(unittest.TestCase):
    def setUp(self):
        self.fixture = nested_case(self, completion.AvailableCompletionTests)
        self.workflow, self.video = self.fixture.workflow, self.fixture.video
        self.job_dir = self.workflow._job_dir(self.video)
        self.model = Path(self.workflow.config['projectDir']).parent/'model-cache/lyrics-sylla/sylla-600m-v1'/core.MODEL_SPEC['revision']
        self.model.mkdir(parents=True)
        self.vocabulary = {character: i for i, character in enumerate(['<blank>', '|', *string.ascii_lowercase, 'ı', 'ş', 'ü', 'ö', 'ç', 'ğ'])}
        atomic_write_json(self.model/'vocab.json', self.vocabulary)
        self.model_record = {key: core.MODEL_SPEC[key] for key in ('repository', 'revision', 'files')}
        self.model_record['snapshotSha256'] = canonical_sha256(self.model_record['files'])
        runtime = core.MODEL_SPEC['runtime']
        self.runtime = {'runtimeVersion': 1, 'python': runtime['python'], 'packages': runtime['packages'],
            'implementationSha256': copy.deepcopy(runtime['implementationSha256']),
            'cudaRuntime': runtime['cudaRuntime'], 'requestedDevice': 'auto', 'resolvedDevice': 'cuda',
            'cudaAvailable': True, 'cudaDeviceName': 'Fixture external-model boundary',
            'cudaDeviceCapability': [8, 9], 'dtype': 'float16', 'torchThreads': 6, 'deviceIndex': 0,
            'torchProducer': {'device': 'cuda', 'torch': runtime['packages']['torch'], 'cudaDriver': 13000,
                'cudaRuntime': runtime['cudaRuntime'], 'cudnn': '91002',
                'uuid': 'GPU-11111111-1111-1111-1111-111111111111',
                'name': 'Fixture external-model boundary', 'capability': [8, 9], 'totalMemoryBytes': 16000000000}}
        self.inference_calls = 0
        self.corrupt = None
        self.during_worker = None
        model = patch.object(core, 'model_identity', return_value=self.model_record)
        self.model_boundary = model.start()
        self.addCleanup(model.stop)

    def prepare(self, *, text='one two\none two\n', language='en', components=None):
        from stemwise_engine.lyrics_alignment import (AlignmentWorkspace, snapshot_canonical_plain,
            build_lexical_occurrence_mapping, lexical_mapping_identity)
        from stemwise_engine.lyric_content import confirm_content
        draft = self.job_dir/'drafts'
        draft.mkdir(exist_ok=True)
        (draft/'original.txt').write_text(text, encoding='utf-8')
        metadata = {'videoId': self.video, 'originalLanguage': language,
            'originalScript': 'Arab' if language == 'ar' else 'Zyyy' if language == 'mul' else 'Latn',
            'sources': {'original': {'name': 'Independent known-answer fixture'}}}
        if components:
            metadata['componentLanguages'] = components
        atomic_write_json(draft/'lyrics.meta.json', metadata)
        job = self.workflow._load_job(self.video)
        compiled, _, _ = self.workflow._preflight_drafts(job, roles=frozenset({'original'}))
        original, raw = compiled['tracks'][0], json_bytes(compiled, pretty=True)
        owner = self.workflow._owner_sidecar_path(self.video)
        owner.parent.mkdir(parents=True, exist_ok=True)
        owner.write_bytes(raw)
        workspace = AlignmentWorkspace(self.workflow.root, workspace_root=self.workflow.workspace_root)
        snapshot_canonical_plain(workspace, self.video, raw)
        sources = tuple(i for i, line in enumerate(text.splitlines()) if line.strip())
        witness = write_recognition(workspace.job_dir(self.video), original, sources, sha256_file(self.fixture.audio))
        admission = confirm_content(original, sources, hashlib.sha256(raw).hexdigest(),
            sha256_file(self.fixture.audio), evidence_path=witness, evidence_root=workspace.job_dir(self.video))
        state = {'videoId': self.video, 'canonicalPlainSha256': hashlib.sha256(raw).hexdigest(),
            'contentAdmission': admission.to_record(), 'contentBinding': admission.binding,
            'status': 'line-unresolved', 'reasons': ['endpoint-evidence-uncertain']}
        atomic_write_json(workspace.state_path(self.video), state)
        mapping = build_lexical_occurrence_mapping(self.video, original, sources)
        mapping.update(lexical_mapping_identity(mapping))
        atomic_write_json(workspace.mapping_path(self.video), mapping)
        job.update(state='LINE_UNRESOLVED', deliveryAllowed=False)
        self.workflow._save_job(job)
        self.workspace, self.mapping = workspace, mapping
        return {path: path.read_bytes() for path in (*draft.iterdir(), owner,
            workspace.current_backup_path(self.video), workspace.backup_pointer_path(self.video),
            workspace.state_path(self.video), workspace.mapping_path(self.video), witness,
            self.workflow._job_path(self.video), self.fixture.child, self.fixture.audio)}

    def worker(self, workflow, stage, inputs, command, **options):
        from stemwise_engine.runtime_environment import environment_python
        self.assertEqual(Path(command[0]), environment_python('main', Path(workflow.config['projectDir'])))
        if '--runtime-info' in command:
            return subprocess.CompletedProcess(command, 0, json.dumps(self.runtime), '')
        self.inference_calls += 1
        def argument(name):
            return command[command.index('--'+name)+1]
        request = json.loads(Path(argument('request')).read_bytes())
        language = argument('language')
        from stemwise_engine.alignment_audio import file_identity
        audio = file_identity(Path(argument('audio')))
        lines = []
        count = sum(len(line['words']) for line in request['lines'])
        cursor = 0
        for i, line in enumerate(request['lines']):
            words = []
            for word in line['words']:
                start = 50+cursor*900//count
                words.append({'wordId': word['wordId'], 'startMs': start, 'endMs': start+100})
                cursor += 1
            lines.append({'lineIndex': i, 'lineId': line['lineId'], 'sourcePhysicalIndex': line['sourcePhysicalIndex'],
                'startMs': words[0]['startMs'], 'endMs': words[-1]['endMs'], 'words': words})
        transform = canonical_time_transform(source_sample_rate=audio['stream']['sampleRate'],
            source_channel_count=audio['stream']['channelCount'], source_frame_count=audio['stream']['frameCount'],
            target_sample_rate=16000, target_frame_count=16000,
            chunk_target_frames=core.DECODER['coreSeconds']*16000, chunks=[chunk_time_map(0, 16000, 0, 50)])
        result = {'formatVersion': core.FORMAT_VERSION, 'engine': core.ENGINE, 'status': 'candidate',
            'audioSha256': audio['sha256'], 'requestSha256': canonical_sha256(request),
            'model': self.model_record, 'runtime': self.runtime, 'producer': core.producer_identity(),
            'decoder': core.DECODER, 'normalization': {'id': core.NORMALIZATION_VERSION,
                'sourceSha256': core.producer_identity()['sylla_forced_alignment.py']},
            'canonicalTimeTransform': transform,
            'emissionWindows': [{'inputStartSample': 0, 'inputEndSample': 16000, 'coreStartSample': 0,
                'coreEndSample': 16000, 'rawEmissionFrames': 50, 'retainedFirstFrame': 0, 'retainedEndFrame': 50}],
            'targetAdmission': [{**row, 'requestLanguage': language} for row in core.target_admission(request, language, self.vocabulary)],
            'lines': lines, 'supportedAuthority': False, 'publicationAuthority': False, 'corroborationAuthority': False}
        if self.corrupt:
            self.corrupt(result)
        if self.during_worker:
            self.during_worker()
        atomic_write_json(Path(argument('output')), result)
        return subprocess.CompletedProcess(command, 0, '', '')

    def invoke(self):
        args = workflow_command.parser().parse_args(['--config', str(self.workflow.config_path),
            'syllacandidate', '--shadow', '--video-id', self.video])
        with patch.object(FutureSongWorkflow, '_run_primary_worker', autospec=True, side_effect=self.worker):
            return workflow_command.execute(args)

    def test_public_candidate_retains_ids_and_replays_without_publication(self):
        protected = self.prepare(text='one، two\none two\n')
        first, second = self.invoke(), self.invoke()
        self.assertFalse(first['reused'])
        self.assertTrue(second['reused'])
        self.assertEqual(self.inference_calls, 1)
        self.assertFalse(second['supportedAuthority'])
        self.assertFalse(second['publicationAuthority'])
        self.assertFalse(second['corroborationAuthority'])
        packet = json.loads(Path(second['candidate']['path']).read_bytes())
        self.assertEqual(packet['mapping']['performedOccurrences'], self.mapping['performedOccurrences'])
        self.assertNotEqual(packet['request']['lines'][0]['words'][0]['wordId'],
            packet['request']['lines'][1]['words'][0]['wordId'])
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
        self.assertFalse(any(self.workflow.ready.glob('*.zip')))

    def test_public_candidate_owns_persistent_emission_cache_across_lyric_changes(self):
        self.prepare()
        directories = []
        def numerical(workflow, stage, inputs, command, **options):
            if '--runtime-info' not in command:
                self.assertIn('--emission-cache-dir', command)
                directory = Path(command[command.index('--emission-cache-dir')+1])
                self.assertEqual(directory, self.job_dir/'sylla-candidate/emissions')
                self.assertTrue(directory.is_dir())
                directories.append(directory)
            return self.worker(workflow, stage, inputs, command, **options)
        with patch.object(FutureSongWorkflow, '_run_primary_worker', autospec=True, side_effect=numerical):
            from stemwise_engine import sylla_candidate
            first = sylla_candidate.collect(self.workflow, self.workflow._load_job(self.video))
            self.prepare(text='one two three\none two\n')
            second = sylla_candidate.collect(self.workflow, self.workflow._load_job(self.video))
        self.assertNotEqual(first['candidate']['path'], second['candidate']['path'])
        self.assertEqual(len(directories), 2)
        self.assertEqual(directories[0], directories[1])

    def test_default_disabled_action_requires_explicit_per_call_shadow(self):
        protected = self.prepare()
        args = workflow_command.parser().parse_args(['--config', str(self.workflow.config_path),
            'syllacandidate', '--video-id', self.video])
        with patch.object(FutureSongWorkflow, '_run_primary_worker') as worker:
            with self.assertRaisesRegex(FutureSongError, 'disabled'):
                workflow_command.execute(args)
            with self.assertRaisesRegex(FutureSongError, 'disabled'):
                self.workflow.sylla_candidate(self.video)
        worker.assert_not_called()
        self.model_boundary.assert_not_called()
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)

    def test_shadow_candidate_cannot_change_ordinary_validation_delivery_or_corroboration(self):
        from stemwise_engine.editorial_admission import available_lyrics
        from stemwise_engine.endpoint_dispatch import evidence_adapter
        self.prepare()
        job = self.workflow._load_job(self.video)
        audio = self.workflow._completed_analysis_audio(self.workflow._pipeline(), self.video)
        before = available_lyrics(self.workflow, job, audio)
        self.invoke()
        self.assertEqual(available_lyrics(self.workflow, self.workflow._load_job(self.video), audio), before)
        with self.assertRaises(ValueError):
            evidence_adapter(core.ENGINE)
        with patch.object(FutureSongWorkflow, '_run_primary_worker') as worker:
            with self.assertRaises(FutureSongError):
                self.workflow.validate(self.video)
            with self.assertRaises(FutureSongError):
                self.workflow.finalize(self.video)
        worker.assert_not_called()
        self.assertNotIn(self.workflow._load_job(self.video)['state'], {'VALIDATED', 'DELIVERED'})
        self.assertFalse(any(self.workflow.ready.glob('*.zip')))

    def test_unadmitted_or_changed_content_refuses_before_worker(self):
        from stemwise_engine.workflow_records import ContentEvidenceUnavailable, LyricsContentBlocker
        self.prepare()
        state = self.workspace.state_path(self.video)
        state_bytes = state.read_bytes()
        state.unlink()
        with self.assertRaises(ContentEvidenceUnavailable):
            self.invoke()
        state.write_bytes(state_bytes)
        (self.job_dir/'drafts/original.txt').write_text('different words\n', encoding='utf-8')
        with self.assertRaises(LyricsContentBlocker):
            self.invoke()
        self.assertEqual(self.inference_calls, 0)

    def test_arabic_and_mixed_arabic_refuse_before_model_boundary(self):
        for language, text, components in (('ar', 'الحمد لله\nالحمد لله\n', None),
                ('mul', 'one two\nالحمد لله\n', ['en', 'ar'])):
            self.prepare(language=language, text=text, components=components)
            with self.subTest(language=language), self.assertRaisesRegex(ValueError, 'sylla-arabic-unsupported'):
                self.invoke()
        self.model_boundary.assert_not_called()
        self.assertEqual(self.inference_calls, 0)

    def test_stale_or_partial_worker_result_cannot_publish_private_packet(self):
        protected = self.prepare()
        self.corrupt = lambda result: result['lines'][0]['words'].pop()
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertEqual(list((self.job_dir/'sylla-candidate').glob('*.private.json')), [])
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)

    def test_changed_cached_candidate_is_not_replaced_or_reinferred(self):
        self.prepare()
        first = self.invoke()
        path = Path(first['candidate']['path'])
        prior = path.read_bytes()
        value = json.loads(prior)
        value['result']['audioSha256'] = 'f'*64
        atomic_write_json(path, value)
        tampered = path.read_bytes()
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertEqual(path.read_bytes(), tampered)
        self.assertEqual(self.inference_calls, 1)

    def test_changed_original_retains_frozen_raw_result_without_current_authority(self):
        protected = self.prepare()
        original = self.job_dir/'drafts/original.txt'
        self.during_worker = lambda: original.write_text('edited owner text\n', encoding='utf-8')
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertEqual(original.read_text(encoding='utf-8'), 'edited owner text\n')
        packets = list((self.job_dir/'sylla-candidate').glob('*.private.json'))
        self.assertEqual(len(packets), 1)
        frozen = json.loads(packets[0].read_bytes())
        self.assertEqual(frozen['request']['lines'][0]['words'][0]['acousticText'], 'one')
        self.assertFalse(frozen['result']['corroborationAuthority'])
        self.assertFalse(frozen['result']['publicationAuthority'])
        self.assertEqual({path: path.read_bytes() for path in protected if path != original},
            {path: raw for path, raw in protected.items() if path != original})

    def test_unsupported_target_refuses_before_worker(self):
        self.prepare(text='one Ω\none two\n')
        with self.assertRaises(core.TargetAdmissionError):
            self.invoke()
        self.assertEqual(self.inference_calls, 0)

    def test_unqualified_runtime_refuses_before_inference(self):
        protected = self.prepare()
        self.runtime['resolvedDevice'] = 'cpu'
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertEqual(self.inference_calls, 0)
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)

    def test_terminal_or_active_job_refuses_without_worker(self):
        self.prepare()
        job = self.workflow._load_job(self.video)
        for state in ('ALIGNING_LYRICS', 'VALIDATED', 'DELIVERED'):
            job['state'] = state
            atomic_write_json(self.workflow._job_path(self.video), job)
            with self.subTest(state=state), self.assertRaises(FutureSongError):
                self.invoke()
        self.assertEqual(self.inference_calls, 0)

    def test_worker_retirement_failure_preserves_owner_and_existing_numerical_identities(self):
        from stemwise_engine import lyrics_align_pc, ctc_worker
        from stemwise_engine.provenance import endpoint_producer_identity
        protected = self.prepare()
        def identities():
            return {'whisper': lyrics_align_pc._whisper_recognition_cache_identity(),
                'mms': ctc_worker.helper_source_identity(Path(lyrics_align_pc.__file__).with_name('mms_forced_alignment.py')),
                'wav2vec2': lyrics_align_pc._wav2vec2_helper_hashes(Path(lyrics_align_pc.__file__).parent.parent),
                'endpoints': copy.deepcopy(endpoint_producer_identity())}
        before = identities()
        with patch.object(FutureSongWorkflow, '_run_primary_worker', side_effect=WorkerRetirementError('fixture retirement')):
            with self.assertRaises(WorkerRetirementError):
                workflow_command.execute(workflow_command.parser().parse_args(['--config', str(self.workflow.config_path),
                    'syllacandidate', '--shadow', '--video-id', self.video]))
        self.assertEqual(identities(), before)
        self.assertEqual({path: path.read_bytes() for path in protected}, protected)
