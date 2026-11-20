"""Candidate-only recovery readback binds derived audio without loading numerical runtimes."""
import builtins
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from stemwise_engine import workflow_status
from stemwise_engine.common import atomic_write_json
from stemwise_engine.lyric_content import reference_adaptation
from stemwise_engine.lyrics_intake import read_recording_draft
from stemwise_engine.model_registry import model_assets, runtime_profile
from stemwise_engine.private_diagnostics import retain_error
from stemwise_engine.provenance import semantic_source_identity
from stemwise_engine.qwen3_asr_transcribe import DECODER, FORMAT_VERSION, ENGINE
from alignment.test_qwen3_asr_transcribe import output_fixture


class RecoveryViewReadbackTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2]/'work')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.video, self.audio_sha, self.duration = 'abcdefghijk', 'a'*64, 1000
        self.job, self.evidence = self.root/'jobs'/self.video, self.root/'evidence'/self.video
        (self.job/'drafts').mkdir(parents=True)
        self.evidence.mkdir(parents=True)
        (self.job/'drafts/original.txt').write_text('private result\n', encoding='utf-8')
        atomic_write_json(self.job/'drafts/lyrics.meta.json', {'originalLanguage': 'en', 'originalScript': 'Latn'})
        self.pins = model_assets('lyrics_align_pc')
        self.mix = self.identity(self.audio_sha)
        self.views = {'htdemucs-vocals': {**self.identity('b'*64), 'routeId': 'htdemucs-vocals',
            'familyId': 'htdemucs-vocals', 'sourceViewId': 'mix', 'sourceAudioSha256': self.audio_sha,
            'model': 'htdemucs', 'parameters': {'shifts': 0, 'twoStems': 'vocals'},
            'demucsModelSha256': self.pins['PINNED_DEMUCS_SHA256']}}
        package = {'version': self.pins['ROFORMER_PACKAGE_VERSION'],
            'wheelSha256': self.pins['ROFORMER_PACKAGE_WHEEL_SHA256'],
            'sourceManifestSha256': self.pins['ROFORMER_PACKAGE_SOURCE_MANIFEST_SHA256'],
            'sourceCommit': self.pins['ROFORMER_PACKAGE_SOURCE_COMMIT']}
        profile = runtime_profile('main')
        for route_id, digest in zip(self.pins['ROFORMER_ROUTES'], ('c'*64, 'd'*64)):
            definition = self.pins['ROFORMER_ROUTES'][route_id]
            source = self.mix if definition['sourceViewId'] == 'mix' else self.views[definition['sourceViewId']]
            self.views[route_id] = {**self.identity(digest), 'routeId': route_id,
                **{key: definition[key] for key in ('familyId', 'sourceViewId', 'checkpointSha256',
                    'configSha256', 'assetRepository', 'assetRevision')},
                'sourceAudioSha256': source['sha256'],
                'helperSha256': semantic_source_identity(Path(workflow_status.__file__).with_name('roformer_separation.py')),
                'packageVersion': package['version'], 'packageWheelSha256': package['wheelSha256'],
                'packageSourceManifestSha256': package['sourceManifestSha256'], 'packageSourceCommit': package['sourceCommit'],
                'device': 'cuda', 'runtime': {'python': profile['python'],
                    'packages': {name: profile['packages'][name] for name in self.pins['ROFORMER_RUNTIME_PACKAGES']},
                    'package': package, 'requestedDevice': 'cuda', 'resolvedDevice': 'cuda', 'cudaAvailable': True}}
        self.retained = {'provenanceVersion': 3, 'videoId': self.video, 'durationMs': self.duration,
            'mix': self.mix, 'audioViews': self.views}
        self.publish_views()

    def identity(self, digest):
        return {'sha256': digest, 'byteCount': 100,
            'stream': {'sampleRate': 16000, 'channelCount': 1, 'frameCount': 16000, 'durationMs': self.duration}}

    def publish_views(self):
        atomic_write_json(self.evidence/'audio-provenance.private.json', self.retained)

    def publish(self, base, name, value):
        raw = json.dumps(value, ensure_ascii=False).encode('utf-8') if not isinstance(value, bytes) else value
        (base/name).write_bytes(raw)
        return {'path': name, 'sha256': hashlib.sha256(raw).hexdigest()}

    def packet(self, view_id='sucial-v2-roformer-dry-vocals', *, qwen=True, language='en'):
        audio = self.mix if view_id == 'mix' else self.views[view_id]
        provenance = {'audio': copy.deepcopy(audio)}
        if qwen:
            output = output_fixture({'audio': audio, 'language': language, 'model': {
                'revision': self.pins['QWEN3_ASR_REVISION'], 'snapshotSha256': self.pins['QWEN3_ASR_SNAPSHOT_SHA256']}})
            provenance.update(language=language, modelRevision=self.pins['QWEN3_ASR_REVISION'],
                modelSnapshotSha256=self.pins['QWEN3_ASR_SNAPSHOT_SHA256'], decoder=dict(DECODER),
                helperSha256=semantic_source_identity(Path(workflow_status.__file__).with_name('qwen3_asr_transcribe.py')),
                runtime={'workerFormatVersion': FORMAT_VERSION, 'engine': ENGINE, 'decoder': dict(DECODER),
                    'runtime': output['runtime'], 'supportedLanguages': ['ar', 'en']})
            if view_id != 'mix':
                provenance['audioView'] = view_id
            cache = {'cacheVersion': 1, 'videoId': self.video, 'provenance': provenance, 'workerOutput': output}
        else:
            provenance.update(durationMs=self.duration, decoder={'promptMode': 'withheld'})
            cache = {'videoId': self.video, 'provenance': provenance, 'promptSha256': hashlib.sha256(b'').hexdigest(),
                'words': [{'raw': 'private', 'start': 0., 'end': .2}, {'raw': 'result', 'start': .2, 'end': .5}]}
        _, draft_sha, _, metadata_sha = read_recording_draft(self.job)
        candidate = {'formatVersion': 1, 'videoId': self.video, 'audioSha256': self.audio_sha,
            'durationMs': self.duration, 'status': 'pending-recording-review', 'publicationAuthority': False,
            'recognition': self.publish(self.evidence, 'recognition.json', cache),
            'candidate': self.publish(self.job, 'candidate.txt', b'private result\n')}
        return {'formatVersion': 1, 'videoId': self.video, 'audioSha256': self.audio_sha,
            'durationMs': self.duration, 'draftSha256': draft_sha, 'metadataSha256': metadata_sha,
            'primaryRecognition': self.publish(self.evidence, 'primary.json', {'contrary': 'retained'}),
            'candidates': [candidate], 'status': 'pending-recording-review', 'publicationAuthority': False}

    def validate(self, packet):
        return workflow_status.validate_recovery_candidates(self.job, self.evidence, packet,
            video_id=self.video, audio_sha=self.audio_sha, duration_ms=self.duration)

    def test_qwen_and_whisper_views_read_back_without_numerical_imports_or_workers(self):
        original_import = builtins.__import__
        def passive_import(name, *args, **kwargs):
            if name in {'stemwise_engine.alignment_audio', 'stemwise_engine.alignment_models',
                    'stemwise_engine.lyrics_align_pc', 'stemwise_engine.recording_evidence'} or name.split('.')[0] in {
                    'torch', 'numpy', 'faster_whisper', 'ctranslate2', 'transformers'}:
                raise AssertionError('Passive readback imported '+name)
            return original_import(name, *args, **kwargs)
        for qwen in (True, False):
            for view_id in ('mix', *self.views):
                packet = self.packet(view_id, qwen=qwen)
                with self.subTest(qwen=qwen, view=view_id), patch.object(builtins, '__import__', side_effect=passive_import), \
                        patch('stemwise_engine.common.run_process_tree', side_effect=AssertionError('passive worker')):
                    self.assertIs(self.validate(packet), packet)
                    cache = json.loads((self.evidence/'recognition.json').read_bytes())
                    expected_sha = self.audio_sha if view_id == 'mix' else self.views[view_id]['sha256']
                    self.assertEqual(workflow_status.candidate_recognition_sha(cache['provenance'],
                        self.audio_sha, self.duration, self.evidence), expected_sha)

    def test_tampered_parent_model_helper_and_runtime_pins_reject_dry_view(self):
        original = copy.deepcopy(self.retained)
        parent = 'kimberley-jensen-roformer-vocals'
        for route, key, value in ((parent, 'sourceAudioSha256', 'e'*64),
                (parent, 'checkpointSha256', 'e'*64), (parent, 'configSha256', 'e'*64),
                (parent, 'helperSha256', 'e'*64), (parent, 'packageWheelSha256', 'e'*64),
                ('sucial-v2-roformer-dry-vocals', 'sourceAudioSha256', self.audio_sha)):
            self.retained = copy.deepcopy(original)
            packet = self.packet()
            self.retained['audioViews'][route][key] = value
            self.publish_views()
            with self.subTest(route=route, key=key), self.assertRaises(ValueError):
                self.validate(packet)
        self.retained = copy.deepcopy(original)
        self.retained['audioViews'][parent]['runtime']['python'] = '0.0.0'
        self.publish_views()
        with self.assertRaises(ValueError):
            self.validate(self.packet())

    def test_real_roformer_cache_projection_ignores_only_stage_metrics(self):
        metrics = {'preprocessingRuntimeMs': 10, 'modelLoadRuntimeMs': 20,
            'inferenceRuntimeMs': 30, 'writeRuntimeMs': 40, 'runtimeMs': 100,
            'peakCudaMemoryBytes': 1024}
        for qwen in (True, False):
            for route in self.pins['ROFORMER_ROUTES']:
                with self.subTest(qwen=qwen, route=route):
                    for view in self.views.values():
                        if view['routeId'] in self.pins['ROFORMER_ROUTES']:
                            view['stageMetrics'] = dict(metrics)
                    self.publish_views()
                    packet = self.packet(route, qwen=qwen)
                    cache = json.loads((self.evidence/'recognition.json').read_bytes())
                    # Both real recognizers retain the numerical producer's identity without stage metrics.
                    del cache['provenance']['audio']['stageMetrics']
                    packet['candidates'][0]['recognition'] = self.publish(self.evidence, 'recognition.json', cache)
                    self.assertIs(self.validate(packet), packet)
                    self.views[route]['stageMetrics']['runtimeMs'] += 1
                    self.views[route]['stageMetrics']['peakCudaMemoryBytes'] += 2048
                    self.publish_views()
                    self.assertIs(self.validate(packet), packet)
                    self.views[route]['byteCount'] += 1
                    self.publish_views()
                    with self.assertRaises(ValueError):
                        self.validate(packet)
                    self.views[route]['byteCount'] -= 1

    def test_frozen_view_witness_preserves_old_dry_chain_after_rebuild(self):
        packet = self.packet()
        original = copy.deepcopy(self.retained)
        packet['candidates'][0]['audioViewWitness'] = self.publish(
            self.evidence, 'view-witness.json', original)
        self.views['kimberley-jensen-roformer-vocals']['sha256'] = 'e'*64
        del self.views['sucial-v2-roformer-dry-vocals']
        self.publish_views()
        # The ASR's own PCM and complete prior chain still exist in its immutable witness.
        # A newly materialized view cannot redefine those bytes or their Original.
        self.assertIs(self.validate(packet), packet)
        self.assertEqual(json.loads((self.evidence/'view-witness.json').read_bytes()), original)
        runtime = self.root/'runtime'
        atomic_write_json(runtime/'work/checkpoints'/f'{self.video}.json',
            {'originalAudio': {'sha256': self.audio_sha, 'durationMs': self.duration}})
        projection = workflow_status.recovery_candidates_readback(
            {'videoId': self.video, 'recordingRecoveryCandidates': packet}, self.job, self.evidence, runtime,
            lambda path: 'project/'+path.relative_to(self.root).as_posix())
        self.assertEqual(projection['candidates'][0]['audioViewWitness']['path'],
            'project/'+(self.evidence/'view-witness.json').relative_to(self.root).as_posix())

    def test_frozen_view_witness_rejects_changed_parent_runtime_original_and_recognizer(self):
        original = copy.deepcopy(self.retained)
        parent, dry = 'kimberley-jensen-roformer-vocals', 'sucial-v2-roformer-dry-vocals'
        for fault in ('missing-parent', 'parent-hash', 'parent-model', 'parent-runtime',
                'original', 'reference-bytes', 'recognizer'):
            self.retained = copy.deepcopy(original)
            self.views = self.retained['audioViews']
            packet = self.packet()
            witness = copy.deepcopy(original)
            if fault == 'missing-parent':
                del witness['audioViews'][parent]
            elif fault == 'parent-hash':
                witness['audioViews'][parent]['sha256'] = 'e'*64
            elif fault == 'parent-model':
                witness['audioViews'][parent]['checkpointSha256'] = 'e'*64
            elif fault == 'parent-runtime':
                witness['audioViews'][parent]['runtime']['python'] = '0.0.0'
            elif fault == 'original':
                witness['mix']['sha256'] = 'e'*64
            packet['candidates'][0]['audioViewWitness'] = self.publish(
                self.evidence, 'view-witness.json', witness)
            if fault == 'reference-bytes':
                (self.evidence/'view-witness.json').write_bytes(b'changed')
            elif fault == 'recognizer':
                cache = json.loads((self.evidence/'recognition.json').read_bytes())
                cache['provenance']['helperSha256'] = 'e'*64
                packet['candidates'][0]['recognition'] = self.publish(self.evidence, 'recognition.json', cache)
            self.views[parent]['sha256'] = 'e'*64
            self.views.pop(dry)
            self.publish_views()
            with self.subTest(fault=fault), self.assertRaises(ValueError):
                self.validate(packet)

    def test_collector_preserves_healthy_old_views_before_missing_whisper_rebuilds_kim(
            self, *, previous_hint='en', current_hint='en', expect_reuse=True, interrupted_witness=False):
        from contextlib import nullcontext
        from types import SimpleNamespace
        from unittest.mock import Mock
        from stemwise_engine import alignment_audio, alignment_models, lyrics_align_pc as align
        from stemwise_engine import recording_evidence as recording
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        from stemwise_engine.common import sha256_file
        import numpy as np
        import soundfile as sf
        audio = self.root/'mix.wav'
        sf.write(audio, np.ones(16000, dtype=np.float32)*.1, 16000, subtype='FLOAT')
        self.audio_sha = sha256_file(audio)
        self.mix.update(sha256=self.audio_sha, byteCount=audio.stat().st_size)
        for view in self.views.values():
            if view['sourceViewId'] == 'mix':
                view['sourceAudioSha256'] = self.audio_sha
        workspace = AlignmentWorkspace(self.root, workspace_root=self.root)
        self.evidence = workspace.job_dir(self.video)
        self.evidence.mkdir(parents=True)
        self.publish_views()
        def metadata(hint):
            return {'originalLanguage': hint} if hint is not None else {
                'originalLanguage': 'mul', 'componentLanguages': ['ar', 'en']}
        atomic_write_json(self.job/'drafts/lyrics.meta.json', metadata(previous_hint))
        kim, dry = 'kimberley-jensen-roformer-vocals', 'sucial-v2-roformer-dry-vocals'
        prior = self.packet(kim, language=previous_hint)
        (self.evidence/'recognition.json').rename(self.evidence/'old-kim.json')
        prior['candidates'][0]['recognition']['path'] = 'old-kim.json'
        other = self.packet(dry, language=previous_hint)
        (self.evidence/'recognition.json').rename(self.evidence/'old-dry.json')
        other['candidates'][0]['recognition']['path'] = 'old-dry.json'
        prior['candidates'].extend(other['candidates'])
        mix = self.packet('mix', language=previous_hint)
        primary = self.evidence/'primary-mix.json'
        (self.evidence/'recognition.json').rename(primary)
        prior['primaryRecognition'] = {**mix['candidates'][0]['recognition'], 'path': primary.name}
        if interrupted_witness:
            digest = prior['candidates'][1]['recognition']['sha256']
            self.publish(self.evidence, f'content-recovery-view-{digest}.private.json', copy.deepcopy(self.retained))
            (self.evidence/'old-kim.json').unlink()
            (self.evidence/'old-dry.json').rename(self.evidence/f'qwen-structure-{dry}-{previous_hint or "auto"}.private.json')
            self.views[kim]['sha256'] = 'e'*64
            self.views.pop(dry)
            self.publish_views()
        else:
            atomic_write_json(self.job/'job.json', {'videoId': self.video, 'recordingRecoveryCandidates': prior})
        atomic_write_json(self.job/'drafts/lyrics.meta.json', metadata(current_hint))
        config = {'projectDir': str(self.root/'PC side/builder'), 'analysisTimeoutMinutes': 1}
        snapshot = (Path(config['projectDir']).parent/'model-cache/lyrics-alignment'/
            'models--Qwen--Qwen3-ASR-1.7B-hf'/'snapshots'/align.QWEN3_ASR_REVISION)
        if not interrupted_witness:
            snapshot.mkdir(parents=True)
        owner = SimpleNamespace(compute_type='float16', close=Mock())
        rebuilt = []
        def materialize(route):
            rebuilt.append(route)
            self.views[kim]['sha256'] = 'e'*64
            self.views.pop(dry, None)
            self.publish_views()
            return audio, self.views[kim], {}
        views = SimpleNamespace(outputs={}, runtime_cache={},
            get_endpoint_audio=Mock(side_effect=materialize), close=owner.close)
        def failed_whisper(**kwargs):
            kwargs['get_audio']()
            raise RuntimeError('the missing timed route still refuses')
        identity = copy.deepcopy(self.mix)
        identity['canonicalTimeTransform'] = alignment_audio.identity_transform(identity['stream'])
        draft, draft_sha, metadata, metadata_sha = read_recording_draft(self.job)
        with patch.object(alignment_audio, 'file_identity', return_value=copy.deepcopy(self.mix)), \
                patch.object(alignment_audio, 'read_audio_provenance', side_effect=lambda *a, **k:
                    json.loads((self.evidence/'audio-provenance.private.json').read_bytes())), \
                patch.object(alignment_audio, 'AlignmentAudioViews', return_value=views), \
                patch.object(alignment_models, 'WhisperModelOwner', return_value=owner), \
                patch('stemwise_engine.lyrics_alignment.resolve_compute_type', return_value='float16'), \
                patch.object(alignment_models, 'require_roformer_wheel'), \
                patch.object(alignment_models, 'roformer_assets'), \
                patch.object(align, '_load_or_transcribe', side_effect=RuntimeError('missing timed mix')), \
                patch.object(align, '_load_or_transcribe_roformer', side_effect=failed_whisper), \
                patch.object(align, 'environment_lease', return_value=nullcontext()), \
                patch.object(align, 'environment_python', return_value=self.root/'unused-python'), \
                patch.object(align, '_qwen3_asr_runtime_provenance',
                    return_value=json.loads(primary.read_bytes())['provenance']['runtime']), \
                patch.object(recording, '_load_or_run_qwen3_structure_transcript',
                    side_effect=AssertionError('a healthy retained Qwen observation was re-inferred')) as qwen:
            packet = recording.collect_disagreement_candidate(config, workspace, self.video, audio, identity,
                {'text': draft, 'language': metadata['originalLanguage'],
                    'componentLanguages': metadata.get('componentLanguages', [])}, draft_sha, metadata_sha, primary,
                {'unresolved': True}, diagnostic=True)
        self.assertEqual(rebuilt, [kim])
        if expect_reuse:
            qwen.assert_not_called()
        else:
            self.assertEqual(qwen.call_count, 1)
            self.assertEqual(qwen.call_args.kwargs['language'], current_hint)
        self.assertEqual(len(packet['candidates']), 1 if interrupted_witness else 2)
        self.assertTrue(all('audioViewWitness' in candidate for candidate in packet['candidates']))
        self.assertIs(self.validate(packet), packet)
        bound = [json.loads((self.evidence/candidate['recognition']['path']).read_bytes())['provenance']['audio']['sha256']
            for candidate in packet['candidates']]
        self.assertEqual(bound, ['d'*64] if interrupted_witness else ['c'*64, 'd'*64])

    def test_changed_requested_hint_does_not_complete_route_from_other_hint(self):
        for before, after in ((None, 'en'), ('en', None)):
            with self.subTest(previous=before, current=after):
                self.setUp()
                self.test_collector_preserves_healthy_old_views_before_missing_whisper_rebuilds_kim(
                    previous_hint=before, current_hint=after, expect_reuse=False)

    def test_interrupted_witness_handoff_recovers_without_packet_or_other_parent_asr(self):
        self.test_collector_preserves_healthy_old_views_before_missing_whisper_rebuilds_kim(interrupted_witness=True)

    def test_changed_qwen_producer_candidate_or_duration_rejects(self):
        for field in ('helperSha256', 'modelSnapshotSha256', 'duration', 'candidate'):
            packet = self.packet()
            cache = json.loads((self.evidence/'recognition.json').read_bytes())
            if field == 'candidate':
                packet['candidates'][0]['candidate'] = self.publish(self.job, 'candidate.txt', b'approved words\n')
            else:
                if field == 'duration':
                    cache['provenance']['audio']['stream']['durationMs'] += 1
                else:
                    cache['provenance'][field] = 'e'*64
                packet['candidates'][0]['recognition'] = self.publish(self.evidence, 'recognition.json', cache)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(packet)

    def test_reference_adaptation_accepts_qwen_string_without_fake_word_times(self):
        (self.job/'drafts/original.txt').write_text('private result result\n', encoding='utf-8')
        packet = self.packet()
        adaptation, projected = reference_adaptation({'text': 'private result result\n', 'language': 'en'}, 'private result')
        packet['candidates'][0]['referenceAdaptation'] = adaptation
        if projected is not None:
            packet['candidates'][0]['referenceCandidate'] = self.publish(self.job, 'performed.txt', projected.encode('utf-8'))
        self.assertIs(self.validate(packet), packet)
        self.assertFalse(packet['publicationAuthority'])

    def test_flat_native_mix_and_derived_original_distinction(self):
        self.assertEqual(workflow_status.candidate_recognition_sha({'familyId': 'mms-asr',
            'audioSha256': self.audio_sha}, self.audio_sha, self.duration, self.evidence), self.audio_sha)
        provenance = {'audio': {**self.mix, 'routeId': 'htdemucs-vocals'}, 'audioView': 'htdemucs-vocals'}
        with self.assertRaises(ValueError):
            workflow_status.candidate_recognition_sha(provenance, self.audio_sha, self.duration, self.evidence)

    def test_route_failure_diagnostics_bind_current_inputs_and_readback_paths(self):
        packet = self.packet()
        failure = {'familyId': 'qwen3-asr', 'routeId': 'htdemucs-vocals', 'code': 'optional-route-failed'}
        context = {key: packet[key] for key in ('audioSha256', 'durationMs', 'draftSha256', 'metadataSha256')}
        context.update({key: failure[key] for key in ('familyId', 'routeId')})
        failure['diagnostics'] = [retain_error(self.job/'route-failure.private.txt', RuntimeError('retained failure'), context=context)]
        packet['routeFailures'] = [failure]
        self.assertIs(self.validate(packet), packet)
        runtime = self.root/'runtime'
        (runtime/'work/checkpoints').mkdir(parents=True)
        atomic_write_json(runtime/'work/checkpoints'/f'{self.video}.json', {'originalAudio': {
            'sha256': self.audio_sha, 'durationMs': self.duration}})
        result = workflow_status.recovery_candidates_readback({'videoId': self.video, 'recordingRecoveryCandidates': packet},
            self.job, self.evidence, runtime, str)
        diagnostic = self.job/failure['diagnostics'][0]['file']
        self.assertEqual(result['routeFailures'][0]['diagnostics'][0]['file'], str(diagnostic))
        diagnostic.write_bytes(b'changed cause')
        with self.assertRaises(ValueError):
            self.validate(packet)
        failure['diagnostics'] = [retain_error(self.job/'stale-failure.private.txt', RuntimeError('old failure'),
            context={**context, 'audioSha256': 'e'*64})]
        with self.assertRaises(ValueError):
            self.validate(packet)

    def test_nemo_route_failures_are_visible_only_for_the_mix(self):
        for route in ('mix', 'htdemucs-vocals'):
            packet = self.packet()
            failure = {'familyId': 'nemo-arabic-fastconformer', 'routeId': route,
                'code': 'nemo-lexical-worker-failed'}
            context = {key: packet[key] for key in
                ('audioSha256', 'durationMs', 'draftSha256', 'metadataSha256')}
            context.update({key: failure[key] for key in ('familyId', 'routeId')})
            failure['diagnostics'] = [retain_error(self.job/'nemo-failure.private.txt',
                RuntimeError('retained native failure'), context=context)]
            packet['routeFailures'] = [failure]
            with self.subTest(route=route):
                if route == 'mix':
                    self.assertIs(self.validate(packet), packet)
                else:
                    with self.assertRaisesRegex(ValueError, 'route failure is invalid'):
                        self.validate(packet)
