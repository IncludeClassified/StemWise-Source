from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

import stemwise_engine.nemo_nfa_forced_alignment as worker
from stemwise_engine.nemo_nfa_forced_alignment import (
    FORMAT_VERSION,
    MODEL_BYTE_COUNT,
    MODEL_REVISION,
    MODEL_SHA256,
    CUDA_VERSION,
    NEMO_LOCK_BYTE_COUNT,
    NEMO_LOCK_SHA256,
    NEMO_SOURCE_REVISION,
    NFA_ALIGN_BYTE_COUNT,
    NFA_ALIGN_SHA256,
    ROUTE,
    TORCH_VERSION,
    _flatten,
    _parse_word_ctm,
    _prepare_mono_audio,
    _regroup,
    _resolved_bindings,
    _run_nfa,
    _runtime_identity,
    _validated_request,
    _verify_source_checkout,
)

CUDA_DEVICE = {'name': 'Fixture CUDA accelerator', 'capability': [12, 0], 'totalMemoryBytes': 16 * 1024**3}


def request_fixture() -> dict:
    return {
        "formatVersion": FORMAT_VERSION,
        "route": ROUTE,
        "cudaDevice": copy.deepcopy(CUDA_DEVICE),
        "durationMs": 10_000,
        "audio": {"path": "/private/audio.wav", "sha256": "a" * 64, "byteCount": 12},
        "model": {
            "path": "/private/model.nemo",
            "revision": MODEL_REVISION,
            "sha256": MODEL_SHA256,
            "byteCount": MODEL_BYTE_COUNT,
        },
        "nfa": {
            "sourceRoot": "/private/nemo",
            "sourceRevision": NEMO_SOURCE_REVISION,
            "alignPath": "/private/nemo/tools/nemo_forced_aligner/align.py",
            "alignSha256": NFA_ALIGN_SHA256,
            "lockPath": "/private/nemo/uv.lock",
            "lockSha256": NEMO_LOCK_SHA256,
            "lockByteCount": NEMO_LOCK_BYTE_COUNT,
        },
        "lines": [
            {
                "sourcePhysicalIndex": 2,
                "lineId": "line-2",
                "words": [
                    {"wordId": "word-1", "acousticText": "واحد"},
                    {"wordId": "word-2", "acousticText": "اثنان"},
                ],
            },
            {
                "sourcePhysicalIndex": 4,
                "lineId": "line-4",
                "words": [{"wordId": "word-3", "acousticText": "ثلاثة"}],
            },
        ],
    }


class NemoNfaWorkerContractTests(unittest.TestCase):
    def test_duration_binding_uses_source_frames_not_resampled_grid(self):
        from stemwise_engine.alignment_audio import file_identity
        for frames in (48021, 48022, 48023, 48024, 9600023):
            with self.subTest(frames=frames), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
                audio = Path(directory) / 'source.wav'
                with wave.open(str(audio), 'wb') as stream:
                    stream.setnchannels(1); stream.setsampwidth(2); stream.setframerate(48000)
                    for offset in range(0, frames, 65536):
                        stream.writeframesraw(b'\0\0' * min(65536, frames-offset))
                self.assertEqual(worker._wav_duration_ms(audio), file_identity(audio)['stream']['durationMs'])

    def test_ctm_projects_only_one_ms_of_eof_rounding_and_rejects_collapsed_words(self):
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as directory:
            path = Path(directory) / 'word.ctm'
            path.write_text('audio 1 0.900 0.101 واحد NA lex NA\n', encoding='utf-8')
            self.assertEqual(_parse_word_ctm(path, 'audio', ['واحد'], 1000), [{'startMs': 900, 'endMs': 1000}])
            for start, span in (('0.900', '0.102'), ('1.000', '0.001')):
                path.write_text(f'audio 1 {start} {span} واحد NA lex NA\n', encoding='utf-8')
                with self.assertRaises(ValueError):
                    _parse_word_ctm(path, 'audio', ['واحد'], 1000)
    def test_inner_cache_binds_actual_preprocessing_but_reuses_unchanged_numerics(self):
        import stemwise_engine.lyrics_align_pc as coordinator
        from stemwise_engine.lyrics_alignment import AlignmentWorkspace
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            root = Path(name)
            project = root/'PC side/builder'
            runtime = project.parent/'runtime/nemo-speech-3.0.0'
            model = (project.parent/'model-cache/lyrics-alignment'
                / 'models--nvidia--stt_ar_fastconformer_hybrid_large_pcd_v1.0'/'snapshots'/MODEL_REVISION/worker.MODEL_ARTIFACT)
            for path in (project/'src/nemo_nfa_forced_alignment.py', runtime/'.venv/bin/python', model,
                    runtime/'tools/nemo_forced_aligner/align.py', runtime/'uv.lock'):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture dependency')
            (project/'src/nemo_nfa_forced_alignment.py').write_bytes(Path(worker.__file__).read_bytes())
            acoustic = project/'src/acoustic_inputs.py'
            current = (PROJECT_ROOT/'src/acoustic_inputs.py').read_bytes()
            acoustic.write_bytes(current)
            audio = root/'audio.wav'
            audio.write_bytes(b'fixture audio')
            identity = {'sha256': hashlib.sha256(audio.read_bytes()).hexdigest(), 'byteCount': audio.stat().st_size}
            workspace = AlignmentWorkspace(root/'state', workspace_root=root)
            lines = [{'sourcePhysicalIndex': 0, 'lineId': 'line', 'words': [{'wordId': 'word', 'acousticText': 'واحد'}]}]
            current_device = [CUDA_DEVICE]
            def launch(command, **kwargs):
                if '--cuda-profile' in command:
                    from stemwise_engine.model_registry import runtime_profile
                    self.assertEqual(Path(command[0]), runtime/'.venv/bin/python')
                    self.assertEqual(command[-1], 'nemo_nfa_forced_alignment')
                    pins = runtime_profile(command[-1])
                    return SimpleNamespace(returncode=0, stdout=json.dumps({'profileId': pins['profileId'],
                        'python': pins['PYTHON_VERSION'], 'torch': pins['TORCH_VERSION'],
                        'cudaRuntime': pins['CUDA_VERSION'], 'cudaDevice': current_device[0]}), stderr='')
                request = json.loads(Path(command[command.index('--request')+1]).read_bytes())
                self.assertEqual(request['cudaDevice'], current_device[0])
                path = Path(command[command.index('--output')+1])
                raw = Path(command[command.index('--request')+1]).read_bytes()
                path.write_text(json.dumps({'engine': worker.ENGINE, 'route': ROUTE, 'formatVersion': FORMAT_VERSION,
                    'durationMs': 2000, 'audio': identity, 'request': {
                        'sha256': command[command.index('--request-sha256')+1], 'byteCount': len(raw)},
                    'lineCount': 1, 'lines': [{'lineIndex': 0, 'sourcePhysicalIndex': 0, 'lineId': 'line',
                        'startMs': 100, 'endMs': 900, 'words': [{'wordId': request['lines'][0]['words'][0]['wordId'],
                            'startMs': 100, 'endMs': 900}]}]}), encoding='utf-8')
                return SimpleNamespace(returncode=0, stdout='', stderr='')
            with patch.object(coordinator, '_forced_alignment_request', return_value=({'lines': lines}, [], [], {'binding': 'current'})), \
                    patch.dict(sys.modules, {'torch': None}), \
                    patch.object(coordinator, 'run_process_tree', side_effect=launch) as run:
                def read():
                    return coordinator._load_or_run_nemo_nfa_alignment(config={'projectDir': str(project)},
                        video_id='abcdefghijk', language='ar', sources=[0], duration_ms=2000, workspace=workspace,
                        audio=audio, audio_identity=identity, temp_dir=root/'attempt', timeout_seconds=1,
                        occurrence_mapping={}, display_word_lines=[])
                self.assertFalse(read()[1])
                self.assertTrue(read()[1])
                acoustic.write_bytes(current+b'\n# Reporting-only note.\n')
                self.assertTrue(read()[1])
                self.assertEqual(sum('--request' in call.args[0] for call in run.call_args_list), 1)
                alternate = {**CUDA_DEVICE, 'name': 'Another capable accelerator'}
                current_device[0] = alternate
                self.assertFalse(read()[1])
                self.assertTrue(read()[1])
                self.assertEqual(sum('--request' in call.args[0] for call in run.call_args_list), 2)
                acoustic.write_bytes(current.replace(b'peak*1e-4', b'peak*1e-3'))
                self.assertFalse(read()[1])
                self.assertEqual(sum('--request' in call.args[0] for call in run.call_args_list), 3)

    def test_stereo_input_is_deterministically_prepared_as_mono(self) -> None:
        import numpy as np
        import soundfile as sf
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite/work") as directory:
            root = Path(directory)
            signal = np.sin(np.arange(140000)*.03).astype('float32')*.5
            for mode in ('mid', 'antiphase', 'silence'):
                source, target = root/'source.wav', root/'target.wav'
                samples = np.column_stack((signal, -signal if mode == 'antiphase' else signal*.5))
                if mode == 'silence':
                    samples[:] = 0
                sf.write(source, samples, 8000, subtype='FLOAT')
                if mode == 'silence':
                    with self.assertRaisesRegex(ValueError, 'uninformative'):
                        _prepare_mono_audio(source, target)
                    continue
                identity = _prepare_mono_audio(source, target)
                decoded, rate = sf.read(target, dtype='float32')
                self.assertEqual((rate, len(decoded)), (8000, len(signal)))
                np.testing.assert_array_equal(decoded, signal if mode == 'antiphase' else signal*.75)
                self.assertEqual(identity['representation'], 'left' if mode == 'antiphase' else 'mid')
                self.assertEqual(identity['sourceChannelCount'], 2)
                self.assertEqual(identity['outputChannelCount'], 1)

    def test_runtime_identity_is_exact_and_requires_cuda(self) -> None:
        torch = SimpleNamespace(
            __version__=TORCH_VERSION,
            version=SimpleNamespace(cuda=CUDA_VERSION),
            cuda=SimpleNamespace(
                is_available=lambda: True,
                get_device_name=lambda index: CUDA_DEVICE['name'],
                get_device_capability=lambda index: tuple(CUDA_DEVICE['capability']),
                get_device_properties=lambda index: SimpleNamespace(total_memory=CUDA_DEVICE['totalMemoryBytes']),
                get_arch_list=lambda: ['sm_120'],
            ),
        )
        with (
            patch('stemwise_engine.nemo_nfa_forced_alignment.platform.python_version', return_value=worker.PYTHON_VERSION),
            patch('stemwise_engine.nemo_nfa_forced_alignment.importlib.metadata.version', return_value=worker.NEMO_VERSION),
            patch.dict(sys.modules, {"torch": torch}),
        ):
            self.assertEqual(_runtime_identity()["torch"], TORCH_VERSION)
            first = _runtime_identity()['cudaDevice']
            torch.cuda.get_device_name = lambda index: 'Another supported accelerator'
            second = _runtime_identity()['cudaDevice']
            self.assertNotEqual(first, second)
            changed = request_fixture()
            changed['cudaDevice'] = second
            self.assertIs(_validated_request(changed), changed)
            self.assertNotEqual(hashlib.sha256(json.dumps(request_fixture()).encode()).hexdigest(),
                                hashlib.sha256(json.dumps(changed).encode()).hexdigest())
            torch.cuda.get_device_capability = lambda index: (7, 0)
            with self.assertRaisesRegex(ValueError, 'capability/resource'):
                _runtime_identity()
            torch.cuda.get_device_capability = lambda index: (12, 0)
            torch.cuda.get_device_properties = lambda index: SimpleNamespace(total_memory=1024)
            with self.assertRaisesRegex(ValueError, 'capability/resource'):
                _runtime_identity()
            torch.cuda.is_available = lambda: False
            with self.assertRaisesRegex(RuntimeError, "CUDA runtime is not pinned"):
                _runtime_identity()

    def test_request_is_closed_pinned_and_preserves_display_owners(self) -> None:
        request = request_fixture()
        self.assertIs(_validated_request(request), request)

        for changed in (
            {**request, "prompt": "forbidden"},
            {**request, "route": "different"},
        ):
            with self.assertRaises(ValueError):
                _validated_request(changed)
        changed = copy.deepcopy(request)
        changed["model"]["sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "not pinned"):
            _validated_request(changed)
        changed = copy.deepcopy(request)
        changed["lines"][0]["words"][0]["acousticText"] = " \t "
        with self.assertRaisesRegex(ValueError, "empty"):
            _validated_request(changed)
        performed = copy.deepcopy(request)
        performed["lines"][0]["sourcePhysicalIndex"] = 8
        performed["lines"][1]["sourcePhysicalIndex"] = 8
        self.assertIs(_validated_request(performed), performed)
        performed["lines"][1]["sourcePhysicalIndex"] = 1
        self.assertIs(_validated_request(performed), performed)

    def test_reviewed_language_must_match_the_arabic_model_for_every_owner(self):
        request = request_fixture()
        for line in request['lines']:
            for word in line['words']:
                word['acousticLanguage'] = 'ar'
        self.assertIs(_validated_request(request), request)
        for language in ('en-us', 'mul', 'und', '', None, ['ar']):
            changed = copy.deepcopy(request)
            changed['lines'][1]['words'][0]['acousticLanguage'] = language
            with self.subTest(language=language), self.assertRaisesRegex(ValueError, 'language is unsupported'):
                _validated_request(changed)

    def test_decimal_ctm_requires_exact_words_then_regroups_without_bodies(self) -> None:
        request = request_fixture()
        records, words = _flatten(request)
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite/work") as directory:
            ctm = Path(directory) / "private.ctm"
            ctm.write_text(
                "audio 1 0.10 0.20 واحد NA lex NA\n"
                "audio 1 0.40 0.30 اثنان NA lex NA\n"
                "audio 1 0.90 0.25 ثلاثة NA lex NA\n",
                encoding="utf-8",
            )
            timings = _parse_word_ctm(ctm, "audio", words, request["durationMs"])
            lines = _regroup(records, timings)
            self.assertEqual(
                [(line["startMs"], line["endMs"]) for line in lines],
                [(100, 700), (900, 1150)],
            )
            self.assertNotIn("acousticText", json.dumps(lines, ensure_ascii=False))
            ctm.write_text(ctm.read_text(encoding="utf-8").replace("اثنان", "مختلف"), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "approved normalized word sequence"):
                _parse_word_ctm(ctm, "audio", words, request["durationMs"])

    def test_local_bindings_and_frozen_request_are_offline_and_unprompted(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite/work") as directory:
            root = Path(directory).resolve()
            source = root / "nemo"
            align = source / "tools" / "nemo_forced_aligner" / "align.py"
            align.parent.mkdir(parents=True)
            align.write_bytes(b"a" * NFA_ALIGN_BYTE_COUNT)
            lock = source / "uv.lock"
            lock.write_bytes(b"lock")
            audio = root / "audio.wav"
            with wave.open(str(audio), "wb") as target:
                target.setnchannels(1)
                target.setsampwidth(2)
                target.setframerate(8_000)
                target.writeframes(b"\0\0" * 80_000)
            audio_raw = audio.read_bytes()
            model = root / "stt_ar_fastconformer_hybrid_large_pcd_v1.0.nemo"
            model.write_bytes(b"model")
            request_path = root / "request.json"
            request_path.write_text("{}", encoding="utf-8")

            def binding(path: Path) -> dict:
                return {
                    "path": str(path),
                    "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "byteCount": path.stat().st_size,
                }

            request = request_fixture()
            request["audio"] = binding(audio)
            request["model"] = {**binding(model), "revision": MODEL_REVISION}
            request["nfa"].update(
                {
                    "sourceRoot": str(source),
                    "alignPath": str(align),
                    "alignSha256": binding(align)["sha256"],
                    "lockPath": str(lock),
                    "lockSha256": binding(lock)["sha256"],
                    "lockByteCount": lock.stat().st_size,
                }
            )
            request_sha = hashlib.sha256(request_path.read_bytes()).hexdigest()
            soundfile = SimpleNamespace(
                __version__=worker.SOUNDFILE_VERSION,
                __libsndfile_version__=worker.LIBSNDFILE_VERSION,
                info=lambda path: SimpleNamespace(
                    format="WAV", samplerate=8_000, frames=80_000, channels=1
                ),
            )
            with patch.dict(sys.modules, {"soundfile": soundfile}):
                self.assertEqual(
                    _resolved_bindings(request_path, request_sha, request),
                    (request_path, audio, model, align, source),
                )
                audio.write_bytes(b"tampered")
                with self.assertRaisesRegex(ValueError, "content binding"):
                    _resolved_bindings(request_path, request_sha, request)
                audio.write_bytes(audio_raw)

            def fake_run(command, source_root, environment):
                self.assertIn("pretrained_name=null", command)
                self.assertFalse(any("pred_text=true" in value for value in command))
                self.assertEqual(environment["HF_HUB_OFFLINE"], "1")
                self.assertEqual(source_root, align.parents[2])
                manifest_arg = next(value for value in command if value.startswith("manifest_filepath="))
                self.assertTrue(Path(manifest_arg.split("=", 1)[1]).is_relative_to(private_root))
                output_arg = next(value for value in command if value.startswith("output_dir="))
                output = Path(output_arg.split("=", 1)[1]) / "ctm" / "words"
                output.mkdir(parents=True)
                (output / "nfa-input.ctm").write_text(
                    "nfa-input 1 0.10 0.20 واحد NA lex NA\n"
                    "nfa-input 1 0.40 0.30 اثنان NA lex NA\n",
                    encoding="utf-8",
                )

            private_root = root / "private-output"
            prepared = {
                "method": "arithmetic-mean-float32",
                "sourceChannelCount": 2,
                "outputChannelCount": 1,
                "sampleRate": 8_000,
                "frameCount": 80_000,
                "sha256": "a" * 64,
                "byteCount": 1,
            }
            with (
                patch('stemwise_engine.nemo_nfa_forced_alignment._execute_nfa', side_effect=fake_run),
                patch(
                    'stemwise_engine.nemo_nfa_forced_alignment._prepare_mono_audio',
                    return_value=prepared,
                ),
            ):
                self.assertEqual(
                    _run_nfa(
                        audio,
                        model,
                        align,
                        source,
                        ["واحد", "اثنان"],
                        10_000,
                        private_root,
                    ),
                    (
                        [{"startMs": 100, "endMs": 300}, {"startMs": 400, "endMs": 700}],
                        prepared,
                    ),
                )

    def test_source_checkout_uses_real_head_and_rejects_tracked_drift(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite/work") as directory:
            repository = Path(directory).resolve()
            subprocess.run(["git", "init", "--quiet", str(repository)], check=True)
            tracked = repository / "tracked.py"
            tracked.write_text("value = 1\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repository), "add", "tracked.py"], check=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(repository),
                    "-c",
                    "user.name=StemWise",
                    "-c",
                    "user.email=local@invalid",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "--quiet",
                    "-m",
                    "fixture",
                ],
                check=True,
            )
            head = subprocess.run(
                ["git", "-C", str(repository), "rev-parse", "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            with patch.object(worker, "NEMO_SOURCE_REVISION", head):
                _verify_source_checkout(repository)
                untracked = repository / "untracked.py"
                untracked.write_text("value = 0\n", encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "not clean"):
                    _verify_source_checkout(repository)
                untracked.unlink()
                tracked.write_text("value = 2\n", encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "not clean"):
                    _verify_source_checkout(repository)
                subprocess.run(["git", "-C", str(repository), "add", "tracked.py"], check=True)
                with self.assertRaisesRegex(RuntimeError, "not clean"):
                    _verify_source_checkout(repository)


if __name__ == "__main__":
    unittest.main()
