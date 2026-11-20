from __future__ import annotations

import copy
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.owsm_ctc_forced_alignment as worker
from stemwise_engine.ctc_worker import helper_source_identity


def artifact(sha256: str, byte_count: int, name: str) -> dict:
    return {"path": f"/private/{name}", "sha256": sha256, "byteCount": byte_count}


def request_fixture() -> dict:
    return {
        "formatVersion": worker.REQUEST_VERSION,
        "route": worker.ROUTE,
        "durationMs": 4_000,
        "audio": artifact("a" * 64, 12, "audio.wav"),
        "model": {
            "revision": worker.MODEL_REVISION,
            "checkpoint": artifact(
                worker.CHECKPOINT_SHA256, worker.CHECKPOINT_BYTE_COUNT, worker.CHECKPOINT_NAME
            ),
            "config": artifact(worker.CONFIG_SHA256, worker.CONFIG_BYTE_COUNT, worker.CONFIG_NAME),
            "bpe": artifact(worker.BPE_SHA256, worker.BPE_BYTE_COUNT, "bpe.model"),
            "stats": artifact(worker.STATS_SHA256, worker.STATS_BYTE_COUNT, "feats_stats.npz"),
        },
        "source": {
            "revision": worker.ESPNET_SOURCE_REVISION,
            "align": artifact(worker.ALIGN_SHA256, worker.ALIGN_BYTE_COUNT, "s2t_ctc_align.py"),
            "helper": artifact(
                worker.HELPER_SHA256, worker.HELPER_BYTE_COUNT, "s2t_ctc.py"
            ),
        },
        "runtime": {
            "python": worker.PYTHON_VERSION,
            "espnet": worker.ESPNET_VERSION,
            "torch": worker.TORCH_VERSION,
            "torchaudio": worker.TORCHAUDIO_VERSION,
            "cudaRuntime": worker.CUDA_VERSION,
            "cudaDevice": {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16 * 1024**3},
            "ctcSegmentation": worker.CTC_SEGMENTATION_VERSION,
            "numpy": worker.NUMPY_VERSION,
            "soundfile": worker.SOUNDFILE_VERSION,
            "libsndfile": worker.LIBSNDFILE_VERSION,
            "wheel": artifact(
                worker.ESPNET_WHEEL_SHA256,
                worker.ESPNET_WHEEL_BYTE_COUNT,
                worker.ESPNET_WHEEL_NAME,
            ),
            "lock": artifact(
                worker.RUNTIME_LOCK_SHA256,
                worker.RUNTIME_LOCK_BYTE_COUNT,
                worker.RUNTIME_LOCK_NAME,
            ),
        },
        "lines": [
            {
                "sourcePhysicalIndex": 3,
                "lineId": "line-3",
                "words": [
                    {"wordId": "word-1", "acousticText": "واحد"},
                    {"wordId": "word-2", "acousticText": "اثنان"},
                ],
            },
            {
                "sourcePhysicalIndex": 1,
                "lineId": "line-1-repeat",
                "words": [{"wordId": "word-3", "acousticText": "ثلاثة"}],
            },
        ],
    }


class IdentityConverter:
    def __init__(self, tokens: list[str]):
        self.tokens = tokens

    def tokens2ids(self, values: list[str]) -> list[int]:
        return [self.tokens.index(value) for value in values]

    def ids2tokens(self, values: list[int]) -> list[str]:
        return [self.tokens[value] for value in values]


def fake_aligner() -> SimpleNamespace:
    tokens = ["<blank>", "<unk>", "واحد", "اثنان", "ثلاثة"]
    return SimpleNamespace(
        preprocess_fn=SimpleNamespace(
            text_cleaner=lambda value: value,
            tokenizer=SimpleNamespace(text2tokens=lambda value: value.split()),
            token_id_converter=IdentityConverter(tokens),
        ),
        config=SimpleNamespace(char_list=tokens),
    )


class OwsmCtcWorkerTests(unittest.TestCase):
    def test_device_probe_producer_output_is_admitted_only_for_its_pinned_worker_runtime(self):
        from stemwise_engine import model_registry, worker_runtime_probe, lyrics_align_pc as parent
        device = request_fixture()['runtime']['cudaDevice']
        for profile in ('owsm_ctc_forced_alignment', 'nemo_nfa_forced_alignment'):
            pins = model_registry.runtime_profile(profile)
            torch = SimpleNamespace(__version__=pins['TORCH_VERSION'], version=SimpleNamespace(cuda=pins['CUDA_VERSION']),
                cuda=SimpleNamespace(is_available=lambda: True, get_device_name=lambda _: device['name'],
                    get_device_capability=lambda _: tuple(device['capability']),
                    get_device_properties=lambda _: SimpleNamespace(total_memory=device['totalMemoryBytes']),
                    get_arch_list=lambda: ['sm_'+''.join(map(str, device['capability']))]))
            with patch.dict(sys.modules, {'torch': torch}), patch('platform.python_version', return_value=pins['PYTHON_VERSION']):
                response = worker_runtime_probe.cuda_runtime_identity(profile)
            for fault in ('none', 'python', 'torch', 'profile', 'device', 'failed'):
                changed = copy.deepcopy(response)
                if fault in ('python', 'torch'):
                    changed[fault] = 'different-runtime'
                elif fault == 'profile':
                    changed['profileId'] = 'another-profile'
                elif fault == 'device':
                    changed['cudaDevice']['totalMemoryBytes'] = 1
                completed = SimpleNamespace(returncode=int(fault == 'failed'), stdout=json.dumps(changed), stderr='')
                with self.subTest(profile=profile, fault=fault), patch.dict(sys.modules, {'torch': None}), \
                        patch.object(parent, 'run_process_tree', return_value=completed):
                    if fault == 'none':
                        self.assertEqual(parent._worker_cuda_device(Path('/worker/python'), profile, timeout_seconds=5), device)
                    else:
                        with self.assertRaises((RuntimeError, ValueError)):
                            parent._worker_cuda_device(Path('/worker/python'), profile, timeout_seconds=5)

    def test_parent_request_uses_selected_worker_probe_without_parent_torch(self):
        import stemwise_engine.lyrics_align_pc as parent
        from stemwise_engine.model_registry import runtime_profile
        device = request_fixture()['runtime']['cudaDevice']
        pins = runtime_profile('owsm_ctc_forced_alignment')
        response = {'profileId': pins['profileId'], 'python': pins['PYTHON_VERSION'],
            'torch': pins['TORCH_VERSION'], 'cudaRuntime': pins['CUDA_VERSION'], 'cudaDevice': device}
        def probe(command, **kwargs):
            self.assertEqual(Path(command[0]), PROJECT_ROOT.parent/'runtime/espnet-202511-owsm/.venv/bin/python')
            self.assertEqual(command[1:], ['-B', '-m', 'stemwise_engine.worker_runtime_probe',
                '--cuda-profile', 'owsm_ctc_forced_alignment'])
            self.assertGreater(kwargs['timeout'], 0)
            return SimpleNamespace(returncode=0, stdout=json.dumps(response), stderr='')
        with patch.dict(sys.modules, {'torch': None}), patch.object(parent, 'run_process_tree', side_effect=probe) as launched:
            request = parent._owsm_ctc_request({'projectDir': str(PROJECT_ROOT)}, 'ar', Path('/private/audio.wav'),
                {'sha256': 'a'*64, 'byteCount': 12}, request_fixture()['lines'], 4000)
        self.assertEqual(request['runtime']['cudaDevice'], device)
        self.assertEqual(launched.call_count, 1)

    def test_reviewed_language_must_match_the_selected_model_route_for_every_owner(self):
        for language, route in worker.ROUTES_BY_LANGUAGE.items():
            request = request_fixture()
            request['route'] = route
            for line in request['lines']:
                for word in line['words']:
                    word['acousticLanguage'] = 'en-us' if language == 'en' else language
            with self.subTest(route=route):
                self.assertIs(worker._validated_request(request), request)
            for unsupported in ('mul', 'und', '', None, [language], 'ar' if language != 'ar' else 'en-us'):
                changed = copy.deepcopy(request)
                changed['lines'][1]['words'][0]['acousticLanguage'] = unsupported
                with self.subTest(route=route, language=unsupported), self.assertRaisesRegex(ValueError, 'language is unsupported'):
                    worker._validated_request(changed)
            changed = copy.deepcopy(request)
            changed['lines'][0]['words'][0]['acousticText'] = ' \t '
            with self.assertRaisesRegex(ValueError, 'empty'):
                worker._validated_request(changed)
            changed = copy.deepcopy(request)
            changed['lines'][0]['words'][0]['guessedLanguage'] = language
            with self.assertRaisesRegex(ValueError, 'exactly'):
                worker._validated_request(changed)

    def test_parent_request_passes_the_current_worker_device_contract(self):
        import stemwise_engine.lyrics_align_pc as parent
        device = request_fixture()['runtime']['cudaDevice']
        with patch.object(parent, '_worker_cuda_device', return_value=device):
            request = parent._owsm_ctc_request({'projectDir': str(PROJECT_ROOT)}, 'ar', Path('/private/audio.wav'),
                {'sha256': 'a' * 64, 'byteCount': 12}, request_fixture()['lines'], 4000)
        self.assertIs(worker._validated_request(request), request)
        self.assertEqual(request['runtime']['cudaDevice'], device)

    def test_runtime_admits_capable_gpu_names_but_binds_the_actual_device(self):
        from stemwise_engine.model_registry import validate_cuda_device
        expected = request_fixture()['runtime']
        torch = SimpleNamespace(__version__=worker.TORCH_VERSION, version=SimpleNamespace(cuda=worker.CUDA_VERSION),
            cuda=SimpleNamespace(is_available=lambda: True,
                get_device_name=lambda index: expected['cudaDevice']['name'],
                get_device_capability=lambda index: (12, 0),
                get_device_properties=lambda index: SimpleNamespace(total_memory=16 * 1024**3),
                get_arch_list=lambda: ['sm_120']))
        modules = {'torch': torch, 'torchaudio': SimpleNamespace(__version__=worker.TORCHAUDIO_VERSION),
            'numpy': SimpleNamespace(__version__=worker.NUMPY_VERSION),
            'soundfile': SimpleNamespace(__version__=worker.SOUNDFILE_VERSION, __libsndfile_version__=worker.LIBSNDFILE_VERSION),
            'ctc_segmentation': SimpleNamespace()}
        with patch.dict(sys.modules, modules), patch.object(worker.platform, 'python_version', return_value=worker.PYTHON_VERSION), \
                patch.object(worker.importlib.metadata, 'version', side_effect=lambda name:
                    worker.ESPNET_VERSION if name == 'espnet' else worker.CTC_SEGMENTATION_VERSION):
            first = worker._runtime_identity(expected)
            torch.cuda.get_device_name = lambda index: 'Another capable GPU'
            with self.assertRaisesRegex(RuntimeError, 'identity is not pinned'):
                worker._runtime_identity(expected)
            expected['cudaDevice']['name'] = 'Another capable GPU'
            second = worker._runtime_identity(expected)
            self.assertNotEqual(first['cudaDevice'], second['cudaDevice'])
            self.assertEqual(validate_cuda_device(second['cudaDevice'], 'owsm_ctc_forced_alignment'), second['cudaDevice'])
            torch.cuda.get_arch_list = lambda: ['sm_80']
            with self.assertRaisesRegex(RuntimeError, 'unsupported.*binary'):
                worker._runtime_identity(expected)

    def test_preprocessing_reports_the_actual_informative_channel_view(self):
        import numpy as np
        import soundfile as sf
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            audio = Path(name)/'source.wav'
            signal = (.2*np.sin(np.arange(16000)/20)).astype('float32')
            for representation, samples in (('mono', signal[:, None]),
                    ('mid', np.column_stack((signal, signal*.5))),
                    ('left', np.column_stack((signal, -signal)))):
                sf.write(audio, samples, 16000, subtype='FLOAT')
                prepared, identity = worker._prepare_speech(audio)
                expected = samples[:, 0] if representation != 'mid' else samples.mean(axis=1)
                np.testing.assert_array_equal(prepared, expected)
                self.assertEqual(identity['method'], 'informative-recording-view-float32-v1')
                self.assertEqual(identity['representation'], representation)
                self.assertEqual(identity['float32Sha256'], __import__('hashlib').sha256(expected.tobytes()).hexdigest())

    def test_primary_alignment_reuses_only_exact_compatible_producers_and_intact_results(self):
        import hashlib
        import stemwise_engine.lyrics_align_pc as align
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        root = PROJECT_ROOT.parent.parent
        prior = '04044f949a5679de3dbc9fb337cd43e37334cc45fc7685b18a55cc00aff359c5'
        with tempfile.TemporaryDirectory(dir=root / 'Test suite/work') as name:
            work = Path(name)
            project = work / 'project'
            (project / 'src').mkdir(parents=True)
            helper = project / 'src/owsm_ctc_forced_alignment.py'
            current_worker = Path(worker.__file__).read_bytes()
            helper.write_bytes(current_worker)
            decoder = project / 'src/mms_forced_alignment.py'
            decoder.write_bytes((PROJECT_ROOT / 'src/mms_forced_alignment.py').read_bytes())
            for name in ('ctc_worker.py', 'worker_protocol.py', 'worker_publication.py', 'common.py'):
                (project / 'src' / name).write_bytes((PROJECT_ROOT / 'src' / name).read_bytes())
            acoustic = project / 'src/acoustic_inputs.py'
            acoustic_source = (PROJECT_ROOT / 'src/acoustic_inputs.py').read_bytes()
            acoustic.write_bytes(acoustic_source)
            workspace = AlignmentWorkspace(work / 'state', workspace_root=work)
            video = 'AAAAAAAAAAA'
            workspace.job_dir(video).mkdir(parents=True)
            lines = [{'sourcePhysicalIndex': 0, 'lineId': 'line', 'words': [
                {'wordId': 'a', 'acousticText': 'one'}, {'wordId': 'b', 'acousticText': 'two'}]}]
            request = {'lines': lines, 'model': {}, 'source': {}, 'runtime': {}}
            raw = (json.dumps(request, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
            mapping = {'mappingSha256': 'c'*64}
            cache = workspace.job_dir(video) / 'owsm-ctc-public-endpoints-v1-en.private.json'
            original = {'engine': worker.ENGINE, 'route': worker.ROUTES_BY_LANGUAGE['en'],
                'formatVersion': worker.FORMAT_VERSION, 'durationMs': 2000,
                'audio': {'sha256': 'b'*64, 'byteCount': 34},
                'request': {'sha256': hashlib.sha256(raw).hexdigest(), 'byteCount': len(raw)},
                'lineCount': 1, 'lines': [{'lineIndex': 0, 'sourcePhysicalIndex': 0, 'lineId': 'line',
                    'startMs': 100, 'endMs': 900, 'words': [
                        {'wordId': 'a', 'startMs': 100, 'endMs': 400},
                        {'wordId': 'b', 'startMs': 500, 'endMs': 900}]}],
                'cacheVersion': align.OWSM_CTC_CACHE_VERSION, 'videoId': video, 'language': 'en',
                'helperSha256': prior, 'decoderHelperSha256': helper_source_identity(decoder),
                'acousticHelperSha256': align.semantic_source_identity(acoustic,
                    ('informative_views', 'mono_samples', 'mono_representation')),
                'mappingIdentity': mapping}
            for case in ('prior', 'older-prior', 'previous-current', 'current', 'unknown-worker', 'unknown-decoder', 'changed-current-worker', 'changed-mapping', 'changed-result', 'changed-preprocessing', 'reporting-only'):
                helper.write_bytes(current_worker)
                acoustic.write_bytes(acoustic_source)
                value = copy.deepcopy(original)
                if case == 'older-prior':
                    value['helperSha256'] = 'bc63cbded6851ccdf82bd252808cec2439508300253409f58a8e47e803e66798'
                    value['decoderHelperSha256'] = '96609f35a8e4f73b7d1c8b69d2fe76d38af71eb148805bf18ecb7d721d6be740'
                if case == 'previous-current': value['helperSha256'] = 'db7e0c798e6892a532f1e535c3e94374206ca9ee09138ed0c869c9ca2ab3edaf'
                if case in ('current', 'changed-preprocessing', 'reporting-only'):
                    value['helperSha256'] = align.semantic_source_identity(helper)
                if case == 'changed-preprocessing':
                    acoustic.write_bytes(acoustic_source.replace(b'peak*1e-4', b'peak*1e-3'))
                if case == 'reporting-only':
                    acoustic.write_bytes(acoustic_source + b'\n# Diagnostic-only wording changes.\n')
                if case == 'unknown-worker': value['helperSha256'] = '0'*64
                if case == 'unknown-decoder': value['decoderHelperSha256'] = '0'*64
                if case == 'changed-current-worker':
                    value['helperSha256'] = align.semantic_source_identity(helper)
                    helper.write_bytes(current_worker + b'\nNUMERICAL_CHANGE = 1\n')
                if case == 'changed-mapping': value['mappingIdentity'] = {'mappingSha256': 'd'*64}
                if case == 'changed-result': value['lines'][0]['words'][0]['wordId'] = 'other'
                cache.write_text(json.dumps(value))
                saved = cache.read_bytes()
                with patch.object(align, '__file__', str(project / 'src/lyrics_align_pc.py')), \
                        patch.object(align, '_forced_alignment_request', return_value=({'lines': lines}, [], [], mapping)), \
                        patch.object(align, '_owsm_ctc_request', return_value=request), patch.object(
                        align, 'run_process_tree', side_effect=AssertionError('unexpected model execution')):
                    def read():
                        return align._load_or_run_owsm_ctc_alignment(config={'projectDir': str(project)},
                            video_id=video, language='en', sources=[0], duration_ms=2000,
                            workspace=workspace, audio=work/'absent.wav', audio_identity=original['audio'],
                            temp_dir=work/'temp', timeout_seconds=1, occurrence_mapping={}, display_word_lines=[])
                    if case in ('current', 'reporting-only'):
                        result, reused = read()
                        self.assertTrue(reused)
                        self.assertEqual((result[0]['startMs'], result[0]['endMs']), (100, 900))
                    else:
                        with self.assertRaisesRegex(RuntimeError, 'dependency is unavailable'):
                            read()
                self.assertEqual(cache.read_bytes(), saved)
            cache.unlink()
            (work / 'absent.wav').touch()
            python = project.parent / 'runtime/espnet-202511-owsm/.venv/bin/python'
            python.parent.mkdir(parents=True)
            python.touch()
            def launch(command, **options):
                from stemwise_engine.common import process_lock_descriptor, heavy_worker_lock_path
                self.assertIsNone(process_lock_descriptor(heavy_worker_lock_path({'projectDir': str(project)})))
                self.assertEqual(command[-2:], ['--timeout-seconds', '1'])
                self.assertIsNone(options['timeout'])
                raise RuntimeError('isolated-model-boundary')
            with patch.object(align, '_forced_alignment_request', return_value=({'lines': lines}, [], [], mapping)), \
                    patch.object(align, '_owsm_ctc_request', return_value=request), \
                    patch.object(align, 'run_process_tree', side_effect=launch):
                with self.assertRaisesRegex(RuntimeError, 'isolated-model-boundary'):
                    read()
            self.assertFalse(cache.exists())




    def test_contrastive_scores_validate_each_emission_table_once_and_preserve_aliases(self):
        import numpy as np
        import stemwise_engine.endpoint_ctc_evidence as scorer
        from unittest.mock import patch
        probabilities = np.array([[.8, .1, .1], [.1, .8, .1], [.8, .1, .1],
                                  [.1, .1, .8], [.8, .1, .1]])
        logp = np.log(probabilities)
        targets = [[1, 2], [2, 1], [1, 2]]
        expected = [scorer._ctc_forward(logp, t, 0, allow_edge_audio=True)[-1]
                    for t in targets]
        real_isfinite = np.isfinite
        scans = []
        def isfinite(value, *args, **kwargs):
            if isinstance(value, np.ndarray) and value.shape == logp.shape:
                scans.append(1)
            return real_isfinite(value, *args, **kwargs)
        with patch.object(scorer.np, 'isfinite', side_effect=isfinite):
            raw, controls, scores = worker._contrastive_structure_scores(logp, targets, 0)
        self.assertEqual(raw, expected)
        self.assertEqual(controls, [expected[1], expected[0], expected[1]])
        self.assertEqual(scores, [expected[0]-expected[1], expected[1]-expected[0],
                                  expected[0]-expected[1]])
        self.assertEqual(len(scans), 1, 'full-vocabulary validation must be shared across candidates')
        for invalid in (logp + .1, np.full_like(logp, float('nan'))):
            with self.assertRaises(ValueError):
                worker._contrastive_structure_scores(invalid, targets, 0)
        for invalid in ([[]], [[1, 2], [True, 2]], [[0]], [[3]], [[1] * 4]):
            with self.assertRaises(ValueError):
                worker._contrastive_structure_scores(logp, invalid, 0)
        import itertools
        sequences = [[1], [1, 1], [2, 1], [1, 2, 1], [1]]
        totals = []
        for sequence in sequences:
            total = 0.
            for path in itertools.product(range(3), repeat=len(logp)):
                collapsed = [token for token, _ in itertools.groupby(path) if token != 0]
                if collapsed == sequence:
                    total += np.prod([probabilities[t, token] for t, token in enumerate(path)])
            totals.append(float(np.log(total)))
        np.testing.assert_allclose(scorer._ctc_sequence_scores(logp, sequences, 0),
                                   totals, atol=1e-12)



    def test_blank_dominance_cannot_win_by_omitting_performed_repetitions(self):
        import numpy as np
        from stemwise_engine.endpoint_ctc_evidence import _ctc_forward
        rows = []
        for token in (1, 2, 3, 1, 2, 3):
            p = np.full((4, 5), .01)
            p[:, 0], p[:, token] = .85, .12
            rows.extend(p)
            p = np.full((2, 5), .01)
            p[:, 0] = .96
            rows.extend(p)
        targets = [[1, 2, 3], [1, 2, 3, 1, 2, 3]]
        logp = np.log(rows)
        raw = [_ctc_forward(logp, t, 0, allow_edge_audio=True)[-1] for t in targets]
        self.assertGreater(raw[0], raw[1])  # Reproduces the old shorter-target preference.
        actual_raw, controls, scores = worker._contrastive_structure_scores(logp, targets, 0)
        self.assertEqual(actual_raw, raw)
        self.assertGreater(scores[1] - scores[0], __import__("math").log(3))
        flat = np.log(np.tile([.96, .01, .01, .01, .01], (len(rows), 1)))
        _, _, uninformed = worker._contrastive_structure_scores(flat, targets, 0)
        np.testing.assert_allclose(uninformed, 0, atol=1e-10)



    def test_untranscribed_outro_does_not_stretch_the_last_approved_word(self):
        import numpy as np

        probabilities = np.array([
            [.98, .01, .005, .005],
            [.01, .98, .005, .005],
            [.01, .005, .98, .005],
            [.001, .001, .001, .997],
            [.001, .001, .001, .997],
            [.001, .001, .10, .898],
            [.001, .001, .001, .997],
            [.001, .001, .001, .997],
        ], dtype=np.float32)
        lines = [{"lineId": "line-1", "sourcePhysicalIndex": 0,
                  "words": [{"wordId": "word-a"}, {"wordId": "word-b"}]}]
        plan = [{"tokenIds": [1, 2], "words": [
            {"wordId": "word-a", "targetStart": 0, "targetEnd": 1},
            {"wordId": "word-b", "targetStart": 1, "targetEnd": 2}]}]
        task = SimpleNamespace(utt_ids=["line-1"], utt_begin_indices=[0, 3],
            ground_truth_mat=np.array([[0], [1], [2]]),
            config=SimpleNamespace(blank=0, index_duration_in_seconds=.1))
        sustained = probabilities.copy()
        sustained[3:6] = probabilities[2]
        for values, expected_end in ((probabilities, 300), (probabilities[:3], 300), (sustained, 600)):
            # Real CTC decoding: lexical peaks at frames 1/2; an unrelated outro
            # must neither stretch the last word nor clip a word ending at EOF.
            frames = len(values)
            lpz = np.log(values)
            before = lpz.copy()
            aligner = SimpleNamespace(get_lpz=lambda _: (lpz, frames * 1600),
                prepare_segmentation_task=lambda *_a, **_k: task)
            result = worker._decode_lines(aligner, lpz, frames * 1600, ["a b"], plan, lines, frames * 100)
            self.assertEqual([(w["startMs"], w["endMs"]) for w in result[0]["words"]],
                             [(100, 200), (200, expected_end)])
            self.assertTrue(np.array_equal(lpz, before))

    def test_english_route_uses_english_conditioning_without_changing_arabic(self):
        self._check_language_conditioning("english", "<eng>", "hello")

    def test_zulu_route_uses_zulu_conditioning_without_changing_arabic(self):
        self._check_language_conditioning("zulu", "<zul>", "sawubona")

    def test_turkish_route_uses_turkish_conditioning_without_changing_arabic(self):
        self._check_language_conditioning("turkish", "<tur>", "görüşürüz")

    def _check_language_conditioning(self, language, symbol, word_text):
        request = request_fixture()
        request["route"] = f"owsm-ctc-v4-1b-{language}-v1"
        for line in request["lines"]:
            for word in line["words"]:
                word["acousticText"] = word_text
        before = copy.deepcopy(request)
        self.assertIs(worker._validated_request(request), request)
        self.assertEqual(worker._route_config(request["route"])["langSym"], symbol)
        self.assertEqual(worker._route_config(worker.ROUTE)["langSym"], "<ara>")
        self.assertEqual(worker.FROZEN_CONFIG["langSym"], "<ara>")
        with self.assertRaises(ValueError):
            worker._validated_request({**request, "route": "owsm-ctc-auto"})
        self.assertEqual(request, before)
        with tempfile.TemporaryDirectory() as name:
            root = Path(name).resolve()
            paths = {key: root / key for key in ("align", "helper", "config", "checkpoint", "bpe", "stats")}
            for path in paths.values():
                path.touch()
            paths["modelRoot"] = root
            loaded = SimpleNamespace(
                s2t_train_args=SimpleNamespace(token_type="bpe", bpemodel=str(paths["bpe"]),
                    normalize_conf={"stats_file": str(paths["stats"])}),
                config=SimpleNamespace(blank_transition_cost_zero=False,
                    replace_spaces_with_blanks=False, score_min_mean_over_L=30))
            constructor = Mock(return_value=loaded)
            modules = {"espnet2.bin.s2t_ctc_align": SimpleNamespace(
                __file__=str(paths["align"]), CTCSegmentation=constructor),
                "espnet2.tasks.s2t_ctc": SimpleNamespace(__file__=str(paths["helper"]))}
            with patch.dict(worker.os.environ), patch.object(worker.os, "chdir"), \
                    patch.object(worker.importlib, "import_module", side_effect=modules.__getitem__):
                worker._load_aligner(paths, request["route"])
                self.assertEqual(constructor.call_args.kwargs["lang_sym"], symbol)
                worker._load_aligner(paths)
                self.assertEqual(constructor.call_args.kwargs["lang_sym"], "<ara>")

    def test_closed_request_retains_stable_ids_and_rejects_drift(self) -> None:
        request = request_fixture()
        self.assertIs(worker._validated_request(request), request)
        self.assertEqual(
            {
                key: worker.FROZEN_CONFIG[key]
                for key in ("gratisBlank", "replaceSpacesWithBlanks", "scoringLength")
            },
            {"gratisBlank": False, "replaceSpacesWithBlanks": False, "scoringLength": 30},
        )
        changed = copy.deepcopy(request)
        changed["model"]["config"]["sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "artifact identity is not pinned"):
            worker._validated_request(changed)
        changed = copy.deepcopy(request)
        changed["lines"][1]["words"][0]["wordId"] = "word-1"
        with self.assertRaisesRegex(ValueError, "word IDs are not unique"):
            worker._validated_request(changed)
        changed = {**request, "prompt": "forbidden"}
        with self.assertRaisesRegex(ValueError, "exactly"):
            worker._validated_request(changed)

    def test_preflight_checks_every_line_and_word_without_emitting_bodies(self) -> None:
        request = request_fixture()
        line_texts, token_plan, evidence = worker._preflight(fake_aligner(), request)
        self.assertEqual(len(line_texts), 2)
        self.assertEqual([len(line["tokenIds"]) for line in token_plan], [2, 1])
        self.assertEqual((evidence["lineCount"], evidence["wordCount"]), (2, 3))
        self.assertNotIn("واحد", json.dumps(evidence, ensure_ascii=False))

        broken = fake_aligner()
        broken.preprocess_fn.tokenizer.text2tokens = lambda value: ["<unk>"]
        with self.assertRaisesRegex(ValueError, "empty or unknown"):
            worker._preflight(broken, request)
        broken = fake_aligner()
        broken.preprocess_fn.text_cleaner = lambda value: value[:-1]
        with self.assertRaisesRegex(ValueError, "silently changed"):
            worker._preflight(broken, request)

    def test_ctc_token_spans_yield_lexical_line_and_word_endpoints(self) -> None:
        request = request_fixture()
        _, token_plan, _ = worker._preflight(fake_aligner(), request)
        spans = [
            SimpleNamespace(token=2, start=10, end=20, score=-0.1),
            SimpleNamespace(token=3, start=22, end=30, score=-0.2),
            SimpleNamespace(token=4, start=50, end=60, score=-0.3),
        ]
        result = worker._lexical_records(
            request["lines"], token_plan, spans, 25.0, request["durationMs"]
        )
        self.assertEqual(
            (result[0]["startMs"], result[0]["endMs"]), (250.0, 750.0)
        )
        self.assertEqual(
            (result[1]["startMs"], result[1]["endMs"]), (1_250.0, 1_500.0)
        )
        self.assertEqual(
            [word["wordId"] for word in result[0]["words"]],
            ["word-1", "word-2"],
        )
        self.assertIs(type(result[0]["startMs"]), int)
        self.assertNotIn("shadow", worker.ROUTE)
        self.assertNotIn("واحد", json.dumps(result, ensure_ascii=False))


class WorkerFailureTests(unittest.TestCase):
    def test_failed_worker_preserves_private_cause_and_returns_failure(self):
        error = io.StringIO()
        with patch.object(worker, "parser") as parser, patch.object(
            worker, "run", side_effect=ValueError("lexical word span is malformed")
        ), contextlib.redirect_stderr(error):
            parser.return_value.parse_args.return_value = SimpleNamespace(
                request=Path("request"), request_sha256="a" * 64, output=Path("output"),
                structure_comparison=False, timeout_seconds=30,
            )
            self.assertEqual(worker.main(), 1)
        self.assertIn("ValueError: lexical word span is malformed", error.getvalue())


if __name__ == "__main__":
    unittest.main()
