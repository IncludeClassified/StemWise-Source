"""Exercise native MMS publication/cache before projecting the two admitted EOF clocks."""
import json
import sys
import tempfile
import unittest
import wave
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from test_lyrics_alignment import PROJECT_ROOT, plain_original
from stemwise_engine import alignment_audio, alignment_endpoints, ctc_worker, lyrics_align_pc
from stemwise_engine.lyrics_alignment import AlignmentWorkspace, build_lexical_occurrence_mapping
from stemwise_engine.lyrics_tokenization import display_words
from lyric_content_fixtures import retained_content


class MmsCumulativeClockTests(unittest.TestCase):
    @contextmanager
    def mms_case(self, *, residual=43, public_ms=30_000, request_ms=30_001,
                 last_start=29_000, wav2vec2=False, mix_frames=1_440_023,
                 vocal_base_frames=1_323_021, native_target_frames=480_024, native_ms=30_002):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            root = Path(name)
            project = root / 'project'
            for helper in ('mms_forced_alignment.py', 'wav2vec2_forced_alignment.py', 'ctc_worker.py', 'acoustic_inputs.py',
                           'worker_protocol.py', 'worker_publication.py', 'common.py'):
                target = project / 'src' / helper
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((PROJECT_ROOT / 'src' / helper).read_bytes())
            for model in ('955717e8-8726e21a.th', 'model.pt'):
                target = root / 'model-cache/torch/hub/checkpoints' / model
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(b'external-model-fixture')
            mix, vocals = root / 'mix.wav', root / 'vocals.wav'
            vocal_frames = vocal_base_frames + residual
            for path, rate, frames in ((mix, 48_000, mix_frames), (vocals, 44_100, vocal_frames)):
                with wave.open(str(path), 'wb') as handle:
                    handle.setnchannels(2)
                    handle.setsampwidth(2)
                    handle.setframerate(rate)
                    handle.writeframes(b'\0' * frames * 4)
            workspace = AlignmentWorkspace(root / 'alignment', workspace_root=root)
            video_id = 'abcdefghijk'
            provenance = alignment_audio.write_audio_provenance(workspace, video_id,
                duration_ms=public_ms, mix_audio=mix, vocals_audio=vocals,
                demucs_model_sha256='b' * 64)
            identity = provenance['audioViews']['htdemucs-vocals']
            original = plain_original('early\nlast')
            mapping = build_lexical_occurrence_mapping(video_id, original, (0, 1))
            segments = [dict(segmentIndex=0, startLineIndex=0, endLineIndex=1,
                             windowStartMs=0, windowEndMs=15_000),
                        dict(segmentIndex=1, startLineIndex=1, endLineIndex=2,
                             windowStartMs=15_000, windowEndMs=request_ms)]

            def external_worker(**options):
                request = json.loads(options['request_path'].read_text(encoding='utf-8'))
                self.assertEqual(request['durationMs'], native_ms)
                self.assertEqual(request['segments'][0], segments[0])
                self.assertEqual(request['segments'][1], {**segments[1], 'windowEndMs': native_ms})
                records = []
                for index, (line, start, end) in enumerate(zip(request['lines'],
                        (1_000, last_start), (10_000, native_ms))):
                    records.append(dict(lineIndex=index, sourcePhysicalIndex=index,
                        lineId=line['lineId'], startMs=start, endMs=end,
                        confidence=0.8, minimumWordConfidence=0.8, displayWordCount=1,
                        romanizedWordCount=1, acousticTokenCount=1,
                        words=[dict(wordId=line['words'][0]['wordId'], startMs=start,
                            endMs=end, confidence=0.8, romanizedWordCount=1, acousticTokenCount=1)]))
                transform = ctc_worker.canonical_time_transform(source_sample_rate=44_100,
                    source_channel_count=2, source_frame_count=vocal_frames,
                    target_sample_rate=16_000, target_frame_count=native_target_frames,
                    chunk_target_frames=480_000,
                    chunks=[ctc_worker.chunk_time_map(0, native_target_frames, 0, 1_500)])
                packet = dict(formatVersion=5, engine='torchaudio-mms-fa', sampleRate=16_000,
                    durationMs=native_ms, emissionFrameCount=1_500, canonicalTimeTransform=transform,
                    segmentCount=2, segmentBoundaryGuardMs=250, lineCount=2,
                    displayWordCount=2, romanizedWordCount=2, acousticTokenCount=2, lines=records,
                    confidence={key: 0.8 for key in
                        ('wordP10', 'wordP50', 'wordP90', 'lineP10', 'lineP50', 'lineP90')})
                if wav2vec2:
                    packet.update(formatVersion=4, requestFormatVersion=5,
                                  engine='wav2vec2-ctc-forced-alignment')
                    packet.pop('romanizedWordCount')
                    for line in packet['lines']:
                        line.pop('romanizedWordCount')
                        for word in line['words']:
                            word.pop('romanizedWordCount')
                options['output_path'].write_text(json.dumps(packet), encoding='utf-8')
                return SimpleNamespace(returncode=0, stdout='', stderr=''), request, None

            with patch.object(lyrics_align_pc, 'environment_python', return_value=Path(sys.executable)), \
                    patch.object(lyrics_align_pc, '_mms_runtime_provenance', return_value={}), \
                    patch.object(lyrics_align_pc, '_wav2vec2_runtime_provenance', return_value={}), \
                    patch.object(lyrics_align_pc, '_wav2vec2_model_assets', return_value=(
                        root / 'model-cache/torch/hub/checkpoints/model.pt',
                        {'modelSha256': 'a' * 64, 'snapshotManifestSha256': 'b' * 64})), \
                    patch.object(lyrics_align_pc.models, 'require_pinned_file', return_value='a' * 64), \
                    patch.object(lyrics_align_pc, '_run_alignment_worker_batch', side_effect=external_worker) as worker:
                arguments = dict(config={'projectDir': str(project)}, video_id=video_id,
                    original=original, alignment=SimpleNamespace(source_physical_indices=(0, 1)),
                    occurrence_mapping=mapping, duration_ms=public_ms, request_duration_ms=request_ms,
                    workspace=workspace, temp_root=root, device='cpu', timeout_seconds=5,
                    segments=segments, get_audio=lambda: (vocals, identity, {}))
                cache = lyrics_align_pc._forced_alignment_cache_path(workspace, video_id, 'public-endpoints-v3')
                if wav2vec2:
                    cache = workspace.job_dir(video_id) / 'wav2vec2-public-endpoints-v4-en-htdemucs-vocals.private.json'
                yield arguments, cache, worker, provenance

    def test_admitted_demucs_residual_projects_fresh_and_cached_mms_through_mix_clock(self):
        self.assert_mms_clock()

    def test_mms_direct_public_clock_accepts_admitted_request_two_milliseconds_longer(self):
        self.assert_mms_clock(residuals=(-21,), public_ms=29_998, request_ms=30_000,
            mix_frames=1_439_975, vocal_base_frames=1_322_977,
            native_target_frames=479_985, native_ms=29_999)

    def assert_mms_clock(self, *, residuals=(43, 44), public_ms=30_000, request_ms=30_001,
                         mix_frames=1_440_023, vocal_base_frames=1_323_021,
                         native_target_frames=480_024, native_ms=30_002):
        for residual in residuals:
            with self.subTest(residual=residual), self.mms_case(residual=residual,
                    public_ms=public_ms, request_ms=request_ms, mix_frames=mix_frames,
                    vocal_base_frames=vocal_base_frames, native_target_frames=native_target_frames,
                    native_ms=native_ms) as case:
                arguments, cache, worker, provenance = case
                transform = provenance['audioViews']['htdemucs-vocals']['canonicalTimeTransform']
                self.assertEqual(transform['measuredResidualFrames'], residual)
                self.assertEqual(lyrics_align_pc._worker_pcm_duration_ms(provenance['mix']), request_ms)
                before = None
                for source in ('fresh', 'cache'):
                    with self.subTest(source=source):
                        projected, reused = lyrics_align_pc._load_final_mms_alignment(**arguments)
                        self.assertEqual(reused, source == 'cache')
                        self.assertEqual(projected['durationMs'], native_ms)
                        self.assertEqual(projected['resolvedSegments'], arguments['segments'])
                        self.assertEqual(projected['lines'][0]['startMs'], 1_000)
                        self.assertEqual(projected['lines'][0]['endMs'], 10_000)
                        self.assertEqual(projected['lines'][1]['endMs'], public_ms)
                        self.assertEqual(projected['lines'][1]['words'][0]['endMs'], public_ms)
                        raw = json.loads(cache.read_text(encoding='utf-8'))
                        self.assertEqual(projected['canonicalTimeTransform'], raw['canonicalTimeTransform'])
                        self.assertEqual(projected['lines'][0], raw['lines'][0])
                        self.assertEqual(raw['provenance']['audio']['canonicalTimeTransform'], transform)
                    raw = json.loads(cache.read_text(encoding='utf-8'))
                    self.assertEqual(raw['durationMs'], native_ms)
                    self.assertEqual(raw['lines'][1]['endMs'], native_ms)
                    self.assertEqual(raw['lines'][1]['words'][0]['endMs'], native_ms)
                    self.assertEqual(raw['resolvedSegments'][1]['windowEndMs'], native_ms)
                    if before is not None:
                        self.assertEqual(cache.read_bytes(), before)
                    before = cache.read_bytes()
                self.assertEqual(worker.call_count, 1)

    def test_route_wav2vec2_projects_fresh_and_cached_native_endpoints_through_mix_clock(self):
        self.assert_route_clock()

    def test_route_wav2vec2_preserves_valid_native_eof_when_mix_clock_rounds_shorter(self):
        self.assert_route_clock(public_ms=30_001, request_ms=30_000,
            mix_frames=1_440_000, vocal_base_frames=1_323_000,
            native_target_frames=480_016, native_ms=30_001)

    def test_route_wav2vec2_direct_public_clock_accepts_admitted_request_two_milliseconds_longer(self):
        self.assert_route_clock(residuals=(-21,), public_ms=29_998, request_ms=30_000,
            mix_frames=1_439_975, vocal_base_frames=1_322_977,
            native_target_frames=479_985, native_ms=29_999)

    def assert_route_clock(self, *, residuals=(43, 44), public_ms=30_000, request_ms=30_001,
                           mix_frames=1_440_023, vocal_base_frames=1_323_021,
                           native_target_frames=480_024, native_ms=30_002):
        for residual in residuals:
            with self.subTest(residual=residual), self.mms_case(residual=residual, wav2vec2=True,
                    public_ms=public_ms, request_ms=request_ms, mix_frames=mix_frames,
                    vocal_base_frames=vocal_base_frames, native_target_frames=native_target_frames,
                    native_ms=native_ms) as case:
                arguments, cache, worker, provenance = case
                workspace, video_id = arguments['workspace'], arguments['video_id']
                original, mapping = arguments['original'], arguments['occurrence_mapping']
                audio_sha = provenance['mix']['sha256']
                _, canonical_sha = lyrics_align_pc.snapshot_canonical_plain(workspace, video_id,
                    json.dumps({'tracks': [original]}).encode('utf-8'))
                admission, _ = retained_content(workspace.job_dir(video_id), original, (0, 1),
                                               canonical_sha, audio_sha)
                state = dict(canonicalPlainSha256=canonical_sha, contentAdmission=admission.to_record(),
                             contentBinding=admission.binding, pinnedModels={})
                workspace.write_state(video_id, state)
                stage = alignment_endpoints.EndpointAlignmentStage(
                    request=lyrics_align_pc.EndpointAlignmentRequest(
                        context=lyrics_align_pc.AlignmentContext(config={**arguments['config'], 'analysisTimeoutMinutes': 1},
                            video_id=video_id, workspace=workspace, temp_root=arguments['temp_root'], device='cpu',
                            duration_ms=public_ms, endpoint_duration_ms=request_ms),
                        text=lyrics_align_pc.EndpointText(original=original, alignment=arguments['alignment'],
                            lexical_mapping=mapping, sources=(0, 1), source_occurrences=(0, 1),
                            display_word_lines=[display_words('early'), display_words('last')], acoustic_language_lines=None),
                        evidence=lyrics_align_pc.EndpointEvidence(baseline_segments=arguments['segments'],
                            seeded_mms_segments=None, verifier_candidates=(None, None),
                            verifier_family=alignment_endpoints.WAV2VEC2_FAMILY, verifier_reused=False,
                            wav2vec2_language='en', free_family_candidates={}, independent_inputs={},
                            reviewed_lrc_starts={}, route_names={}, content_admission=admission.to_record()),
                        audio=lyrics_align_pc.EndpointAudio(expected_mix_sha256=audio_sha, analysis_audio=None,
                            canonical_mix=lambda: self.fail('Route verification unexpectedly acquired the mix'),
                            get_endpoint_audio=lambda _: arguments['get_audio']())),
                    services=lyrics_align_pc.EndpointServices(
                        workers=lyrics_align_pc.EndpointWorkers(load_mms=lyrics_align_pc._load_final_mms_alignment,
                            load_wav2vec2=lyrics_align_pc._load_or_run_wav2vec2_alignment,
                            load_owsm=lambda **_: self.fail('Unexpected OWSM work'),
                            load_nfa=lambda **_: self.fail('Unexpected NFA work'),
                            read_audio_provenance=alignment_audio.read_audio_provenance,
                            verifier_candidates=lyrics_align_pc._forced_alignment_verifier_candidates,
                            needs_local_verifier=lyrics_align_pc._needs_route_local_verifier),
                        events=lyrics_align_pc.AlignmentEvents(attempt=lambda *_, **__: None,
                            failure=lambda *_: None, roformer=lambda *_, **__: None),
                        progress=lyrics_align_pc.AlignmentProgress(state=state, endpoint_attempts=[], checkpoint=lambda: None),
                        release_model=lambda: None, runtime_probe_cache={}))
                before = None
                for source in ('fresh', 'cache'):
                    with self.subTest(source=source):
                        candidates, reused, segments = stage.load_route_wav2vec2('htdemucs-vocals',
                            request_variant='baseline', segments=arguments['segments'])
                        self.assertEqual(reused, source == 'cache')
                        self.assertEqual(segments, arguments['segments'])
                        self.assertEqual(candidates[0]['startMs'], 1_000)
                        self.assertEqual(candidates[0]['endMs'], 10_000)
                        self.assertEqual(candidates[1]['endMs'], public_ms)
                        self.assertEqual(candidates[1]['words'][0]['endMs'], public_ms)
                    raw = json.loads(cache.read_text(encoding='utf-8'))
                    self.assertEqual(raw['durationMs'], native_ms)
                    self.assertEqual(raw['lines'][1]['endMs'], native_ms)
                    self.assertEqual(raw['lines'][1]['words'][0]['endMs'], native_ms)
                    self.assertEqual(raw['canonicalTimeTransform']['sourceFrameCount'], vocal_base_frames + residual)
                    self.assertEqual(raw['canonicalTimeTransform']['targetFrameCount'], native_target_frames)
                    self.assertEqual(raw['provenance']['audio']['canonicalTimeTransform'],
                        provenance['audioViews']['htdemucs-vocals']['canonicalTimeTransform'])
                    self.assertEqual(raw['resolvedSegments'][1]['windowEndMs'], native_ms)
                    if before is not None:
                        self.assertEqual(cache.read_bytes(), before)
                    before = cache.read_bytes()
                self.assertEqual(worker.call_count, 1)

    def test_demucs_residual_outside_its_actual_admission_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'material canonical-time length residual'):
            with self.mms_case(residual=45):
                self.fail('A 45-frame Demucs residual was admitted')

    def test_mms_projection_rejects_material_individual_clock_legs(self):
        for values in ({'request_ms': 30_000}, {'public_ms': 29_999}):
            with self.subTest(**values), self.mms_case(**values) as case:
                arguments, _, worker, _ = case
                with self.assertRaisesRegex(ValueError, 'material'):
                    lyrics_align_pc._load_final_mms_alignment(**arguments)
                self.assertEqual(worker.call_count, 0 if 'request_ms' in values else 1)

    def test_mms_projection_rejects_a_tail_collapsing_on_either_clock_leg(self):
        for start in (30_000, 30_001):
            with self.subTest(start=start), self.mms_case(last_start=start) as case:
                arguments, _, _, _ = case
                with self.assertRaisesRegex(ValueError, 'collapses'):
                    lyrics_align_pc._load_final_mms_alignment(**arguments)


if __name__ == '__main__':
    unittest.main()
