from __future__ import annotations

import copy
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.qwen3_asr_transcribe import (
    DECODER,
    ENGINE,
    FORMAT_VERSION,
    _resolved_bindings,
    _snapshot_sha256,
    _validated_output,
    _validated_request,
    _generation_result,
)


class CharacterTokenizer:
    def decode(self, ids, **settings):
        return ''.join('<|im_end|>' if token == 0 else chr(token) for token in ids)


def generation_fixture(text, language='ar', *, complete=True):
    return _generation_result([ord(char) for char in text] + ([0] if complete else []),
        CharacterTokenizer(), language, [0], language_names=['Arabic', 'English'])


def request_fixture() -> dict:
    return {
        "formatVersion": FORMAT_VERSION,
        "audio": {"path": "/private/audio.wav", "sha256": "a" * 64, "byteCount": 12},
        "language": "ar",
        "model": {
            "path": f"/private/snapshots/{'b' * 40}",
            "revision": "b" * 40,
            "snapshotSha256": "c" * 64,
        },
    }


def output_fixture(request: dict) -> dict:
    return {
        "formatVersion": FORMAT_VERSION,
        "engine": ENGINE,
        "audio": {"sha256": request["audio"]["sha256"], "byteCount": request["audio"]["byteCount"]},
        "language": request["language"],
        "result": generation_fixture(('language Arabic<asr_text>' if request['language'] is None else '')
            + 'private result', request['language']),
        "runtime": {
            "runtimeVersion": 1,
            "python": "3.10.1",
            "implementation": "CPython",
            "platform": "private-platform",
            "machine": "x86_64",
            "torch": "2.10.0",
            "transformers": "5.16.1",
            "requestedDevice": "auto",
            "resolvedDevice": "cuda",
            "cudaAvailable": True,
            "cudaDeviceName": "private-gpu",
            "cudaRuntime": "13.0",
            "cudnn": "91000",
        },
        "model": {
            "revision": request["model"]["revision"],
            "snapshotSha256": request["model"]["snapshotSha256"],
            "modelType": "qwen3_asr",
            "modelClass": "Qwen3ASRForConditionalGeneration",
        },
        "decoder": dict(DECODER),
    }


class Qwen3AsrWorkerContractTests(unittest.TestCase):
    def test_lossless_repetition_and_embedded_role_text_survive_actual_result_validation(self):
        lexical = ' sing ' * 25 + 'assistant\nkeep <asr_text> this language English'
        for language in ('ar', None):
            request = {**request_fixture(), 'language': language}
            output = output_fixture(request)
            raw = ('language Arabic<asr_text>' if language is None else '') + lexical
            output['result'] = generation_fixture(raw, language)
            self.assertIs(_validated_output(output, request), output)
            self.assertEqual(output['result']['transcription'], lexical)
            retained = output['result']['observation']
            self.assertEqual(retained['generatedTokenIds'], [ord(char) for char in raw] + [0])
            self.assertEqual(retained['decodedText'], raw + '<|im_end|>')
            changed = copy.deepcopy(output)
            changed['result']['transcription'] = 'keep this'
            with self.assertRaisesRegex(ValueError, 'projection'):
                _validated_output(changed, request)

    def test_invalid_auto_header_and_incomplete_generation_are_retained_technical_results(self):
        request = {**request_fixture(), 'language': None}
        for text, complete in (('lexical text without required header', True),
                               ('language Arabic<asr_text>unfinished', False)):
            output = output_fixture(request)
            output['result'] = generation_fixture(text, None, complete=complete)
            self.assertIs(_validated_output(output, request, require_complete=False), output)
            self.assertIsNone(output['result']['transcription'])
            self.assertEqual(output['result']['observation']['bodyDecodedText'], text)
            with self.assertRaisesRegex(ValueError, 'qwen-asr-output'):
                _validated_output(output, request)

    def test_closed_request_and_output_contracts_reject_conditioning_and_drift(self) -> None:
        request = request_fixture()
        self.assertIs(_validated_request(request), request)
        output = output_fixture(request)
        self.assertIs(_validated_output(output, request), output)

        for prohibited in ("lyrics", "text", "prompt", "hotword", "context", "timestamp"):
            with self.subTest(prohibited=prohibited):
                changed = copy.deepcopy(request)
                changed["model"][prohibited] = "must never enter the worker"
                with self.assertRaisesRegex(ValueError, "prohibited"):
                    _validated_request(changed)

        for container, key in ((request, "unknown"), (request["audio"], "codec")):
            changed = copy.deepcopy(request)
            target = changed if container is request else changed["audio"]
            target[key] = "unexpected"
            with self.assertRaisesRegex(ValueError, "exactly"):
                _validated_request(changed)

        changed_output = output_fixture(request)
        changed_output["decoder"] = {**DECODER, "numBeams": 2}
        with self.assertRaisesRegex(ValueError, "decoder"):
            _validated_output(changed_output, request)
        changed_output = output_fixture(request)
        changed_output["result"]["segments"] = []
        with self.assertRaisesRegex(ValueError, "exactly"):
            _validated_output(changed_output, request)

    def test_local_audio_and_snapshot_are_content_bound_without_loading_a_model(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audio = root / "audio.wav"
            audio.write_bytes(b"private-audio")
            revision = "d" * 40
            model = root / revision
            model.mkdir()
            (model / "config.json").write_text('{"model_type":"qwen3_asr"}', encoding="utf-8")
            (model / "weight.bin").write_bytes(b"tiny-test-weight")
            request = {
                "formatVersion": FORMAT_VERSION,
                "audio": {
                    "path": str(audio.resolve()),
                    "sha256": hashlib.sha256(audio.read_bytes()).hexdigest(),
                    "byteCount": audio.stat().st_size,
                },
                "language": None,
                "model": {
                    "path": str(model.resolve()),
                    "revision": revision,
                    "snapshotSha256": _snapshot_sha256(model),
                },
            }

            self.assertEqual(_resolved_bindings(_validated_request(request)), (audio.resolve(), model.resolve()))
            audio.write_bytes(b"tampered-audio")
            with self.assertRaisesRegex(ValueError, "byte count|SHA-256"):
                _resolved_bindings(request)


if __name__ == "__main__":
    unittest.main()
