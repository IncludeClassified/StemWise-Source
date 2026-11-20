from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
import wave
import weakref
from fractions import Fraction
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
sys.path.insert(0, str(PROJECT_ROOT.parents[1] / "Test suite/pc/unit"))
from stemwise_engine.worker_protocol import (WorkerProcessError, WORKER_FAILURE_DIAGNOSTIC_BYTES, raise_worker_failure, technical_diagnostic_code)
from lyric_content_fixtures import write_recognition
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout

from stemwise_engine.common import GENERATED_MANIFEST_NAME, create_manifested_directory
from stemwise_engine.lyrics_alignment import (
    TRANSCRIPT_CACHE_VERSION,
    AlignmentMetrics,
    AlignmentResult,
    AlignmentWorkspace,
    FasterWhisperEvidence,
    QualityGate,
    align_known_structure_multi,
    align_known_structure,
    build_lexical_occurrence_mapping,
    build_synced_sidecar,
    commit_candidate,
    deduplicated_lyrics_prompt,
    internal_repeat_structure_kind,
    load_sidecar,
    lexical_mapping_identity,
    parse_youtube_json3,
    separate_vocals_demucs,
    save_alignment_artifacts,
    serialize_result,
    snapshot_canonical_plain,
    transcript_cache_matches,
    transcription_languages,
    whisper_word_candidates,
)
from stemwise_engine.mms_forced_alignment import _aggregate_records, _request_plan, _romanized_words
from stemwise_engine.ctc_worker import canonical_time_transform, chunk_time_map, emission_boundary_to_target_frame
from stemwise_engine.lyrics_tokenization import display_words
import stemwise_engine.worker_protocol as worker_protocol
from stemwise_engine.alignment_endpoints import ENDPOINT_MMS_ROUTES
from stemwise_engine.alignment_endpoints import NEMO_NFA_FAMILY, OWSM_CTC_FAMILY, UNPROMPTED_WHISPER_FAMILY, WAV2VEC2_FAMILY, WAV2VEC2_ROUTE, WORD_START_AGREEMENT_MS, WORKER_SEGMENT_BOUNDARY_GUARD_MS, _build_bootstrap_segments, _component_phonemizer_languages, _apply_independent_endpoint_consensus, _free_structure_witness_family, _merge_endpoint_passes, _merge_endpoint_route_passes, _merge_short_bootstrap_segment, _validate_occurrence_identity, _ordered_verifier_fallback_candidates, _refine_public_timings, _validate_forced_alignment_segments
from stemwise_engine.lyrics_align_pc import CAPTION_NEGATIVE_CACHE_TTL_SECONDS, ConditionalCascadeTechnicalError, _emission_cache_key, MMS_CACHE_VERSION, MMS_REQUEST_VERSION, PINNED_WAV2VEC2_MODELS, _cleanup_owned_attempt_root, _closed_endpoint_witness_candidates, _current_cache_identity, _endpoint_language, _endpoint_verifier_family, _align, _forced_alignment_verifier_candidates, _forced_alignment_cache_path, _mms_cache_matches, _load_caption, _load_or_run_mms_alignment, _load_or_run_wav2vec2_alignment, _load_or_transcribe, _needs_route_local_verifier, _ordered_endpoint_candidate_path, _partition_endpoint_route_failures, _read_optional_cache_json, _needs_additional_structural_evidence, _run_conditional_cascade, _run_future_alignment_attempt, _select_evidence_option, _structure_candidate_diagnostics, _transcribe_with_language_fallback, _validate, _validate_numeric_mms_result, _wav2vec2_helper_hashes, _wav2vec2_runtime_provenance, _worker_pcm_duration_ms, _endpoint_request_timing, _project_endpoint_eof, parser as lyrics_align_parser
from stemwise_engine.alignment_audio import write_audio_provenance
from syncalong.textnorm import normalize
from syncalong.transcribe import WordTimestamp


def transcript(text: str, *, start: float = 1.0, step: float = 0.35) -> list[WordTimestamp]:
    words: list[WordTimestamp] = []
    when = start
    for raw in text.split():
        words.append(WordTimestamp(word=normalize(raw), raw=raw, start=when, end=when + 0.2))
        when += step
    return words


def plain_original(text: str) -> dict[str, object]:
    return {
        "id": "original",
        "kind": "original",
        "language": "en",
        "script": "Latn",
        "format": "plain",
        "text": text,
    }


def write_test_wav(path: Path, *, duration_ms: int = 1_000) -> None:
    frame_count = 48_000 * duration_ms // 1_000
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(2)
        handle.setsampwidth(2)
        handle.setframerate(48_000)
        handle.writeframes(b"\0" * frame_count * 4)


def mms_timeline(duration_ms: int, *, emission_frames: int = 100) -> dict[str, object]:
    source_frames = 48_000 * duration_ms // 1_000
    target_frames = 16_000 * duration_ms // 1_000
    chunk_ratio = Fraction(target_frames, emission_frames)
    return {
        "sampleRate": 16_000,
        "durationMs": duration_ms,
        "emissionFrameCount": emission_frames,
        "canonicalTimeTransform": {
            "sourceSampleRate": 48_000,
            "sourceChannelCount": 2,
            "sourceFrameCount": source_frames,
            "monoChannelCount": 1,
            "targetSampleRate": 16_000,
            "targetFrameCount": target_frames,
            "expectedTargetFrameCount": target_frames,
            "resamplingRatioNumerator": 1,
            "resamplingRatioDenominator": 3,
            "offsetTargetFrames": 0,
            "trimSourceFrames": 0,
            "padSourceFrames": 0,
            "measuredResidualFrames": 0,
            "measuredResidualMs": 0.0,
            "chunkTargetFrames": 480_000,
            "chunks": [
                {
                    "targetStartFrame": 0,
                    "targetEndFrame": target_frames,
                    "emissionStartFrame": 0,
                    "emissionEndFrame": emission_frames,
                    "emissionToTargetRatioNumerator": chunk_ratio.numerator,
                    "emissionToTargetRatioDenominator": chunk_ratio.denominator,
                }
            ],
        },
    }


def omission_repeat_fixture() -> tuple[str, str]:
    opening = "Alpha beta gamma delta\nOne two three four"
    absent = (
        "Amber bronze copper silver\n"
        "Circle square triangle diamond\n"
        "Winter spring summer autumn\n"
        "Mercury venus saturn neptune"
    )
    refrain = "Red blue green gold\nNorth south east west"
    closing = "Quiet bright gentle strong\nRiver mountain forest ocean"
    approved = "\n\n".join((opening, absent, refrain, closing))
    performed = " ".join((opening, refrain, refrain, closing))
    return approved, performed


def accepted_result(
    sources: tuple[int, ...],
    *,
    starts: tuple[int, ...] | None = None,
) -> AlignmentResult:
    starts = starts or tuple(1_000 + index * 1_000 for index in range(len(sources)))
    metrics = AlignmentMetrics(
        lyric_word_count=max(8, len(sources) * 4),
        transcript_word_count=max(8, len(sources) * 4),
        matched_word_count=max(8, len(sources) * 4),
        directly_matched_line_count=len(sources),
        line_count=len(sources),
        lyric_word_coverage=1.0,
        transcript_word_coverage=1.0,
        direct_line_coverage=1.0,
        average_word_score=100.0,
        timed_span_ratio=0.8,
    )
    return AlignmentResult(
        accepted=True,
        reasons=(),
        starts_ms=starts,
        source_physical_indices=sources,
        metrics=metrics,
    )


def line_timings(result: AlignmentResult, duration_ms: int) -> list[dict[str, int]]:
    return [
        {
            "startMs": start,
            "endMs": (
                result.starts_ms[index + 1] - 1
                if index + 1 < len(result.starts_ms)
                else duration_ms - 1
            ),
        }
        for index, start in enumerate(result.starts_ms)
    ]




class LyricsAlignmentTests(unittest.TestCase):
    def test_shared_alignment_cache_keeps_validation_errors_and_publication_ownership(self):
        import stemwise_engine.lyrics_align_pc as runner
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            path, failure = Path(name)/'numeric.private.json', Path(name)/'worker.failure.private.txt'
            path.write_text('{"producer":"prior"}', encoding='utf-8')
            prior = path.read_bytes()
            failure.write_bytes(b'preserve this cause until replacement commits')
            def invalid(_): raise ValueError('wrong model-specific identity')
            self.assertIsNone(runner._admit_alignment_cache(path, invalid))
            def unexpected(_): raise RuntimeError('unexpected validator failure')
            with self.assertRaisesRegex(RuntimeError, 'unexpected validator'):
                runner._admit_alignment_cache(path, unexpected)
            with patch.object(runner, 'atomic_write_json', side_effect=OSError('cache disk unavailable')):
                with self.assertRaises(OSError):
                    runner._publish_alignment_cache(path, {'producer':'next'}, failure_path=failure)
            self.assertEqual(path.read_bytes(), prior)
            self.assertTrue(failure.is_file())
            runner._publish_alignment_cache(path, {'producer':'next'}, failure_path=failure)
            self.assertEqual(json.loads(path.read_bytes()), {'producer':'next'})
            self.assertFalse(failure.exists())

    def test_mixed_endpoint_witness_retains_context_and_filters_only_output(self):
        from stemwise_engine.alignment_endpoints import _independent_endpoint_inputs
        from stemwise_engine.lyrics_align_pc import _forced_alignment_request

        original = plain_original("Zulu words\nنور صباح")
        original.update(language="mul", componentLanguages=["ar", "zu"])
        mapping = build_lexical_occurrence_mapping("KbKqkR3Q6SA", original, (0, 1, 1))
        words = [display_words(line) for line in ("Zulu words", "نور صباح", "نور صباح")]
        before = json.dumps(mapping)
        groups = _independent_endpoint_inputs(original, mapping, words, {"ar", "en"})
        self.assertEqual(set(groups), {"ar"})
        indices, subset, selected_words = groups["ar"]
        self.assertEqual(indices, [1, 2])
        self.assertEqual(subset["performedOccurrences"], mapping["performedOccurrences"])
        self.assertEqual(selected_words, words)
        request, _, _, _ = _forced_alignment_request("KbKqkR3Q6SA", [0, 1, 1], subset,
                                                    selected_words, duration_ms=10_000)
        self.assertEqual([row["lineId"] for row in request["lines"]],
                         [row["occurrenceId"] for row in mapping["performedOccurrences"]])
        self.assertEqual(len(request["lines"]), 3)

        # The Arabic-only NeMo model still receives only its homogeneous subset.
        narrow = _independent_endpoint_inputs(original, mapping, words, {"ar"}, subset=True)
        indices, subset, selected_words = narrow["ar"]
        self.assertEqual([o["performedIndex"] for o in subset["performedOccurrences"]], [0, 1])
        self.assertEqual([o["occurrenceId"] for o in subset["performedOccurrences"]],
                         [o["occurrenceId"] for o in mapping["performedOccurrences"][1:]])
        self.assertEqual([o["words"] for o in subset["performedOccurrences"]],
                         [o["words"] for o in mapping["performedOccurrences"][1:]])
        request, _, _, _ = _forced_alignment_request("KbKqkR3Q6SA", [1, 1], subset,
                                                    selected_words, duration_ms=10_000)
        self.assertEqual(len(request["lines"]), 2)
        self.assertEqual(json.dumps(mapping), before)
        from stemwise_engine.owsm_ctc_forced_alignment import ROUTES_BY_LANGUAGE
        complete = _independent_endpoint_inputs(original, mapping, words, set(ROUTES_BY_LANGUAGE))
        self.assertEqual(set(complete), {"ar", "zu"})
        zulu_indices, zulu_mapping, zulu_words = complete["zu"]
        self.assertEqual(zulu_indices, [0])
        self.assertEqual(zulu_mapping["performedOccurrences"], mapping["performedOccurrences"])
        self.assertEqual(zulu_words, words)
        self.assertEqual(complete["ar"], groups["ar"])
        self.assertEqual(json.dumps(mapping), before)
        ambiguous = {**original, "componentLanguages": ["fr", "tr"]}
        self.assertEqual(_independent_endpoint_inputs(ambiguous, mapping, words, {"en", "ar"}), {})


    def test_unprompted_precision_does_not_carry_earlier_decoder_text(self):
        from stemwise_engine.lyrics_align_pc import _whisper_decoder_config

        def transcribe(_audio, **options):
            self.assertIsNone(options["initial_prompt"])
            self.assertFalse(options["condition_on_previous_text"])
            self.assertEqual(options["beam_size"], 10)
            self.assertTrue(options["word_timestamps"])
            return [], SimpleNamespace(language="ar", language_probability=1.0)

        evidence = FasterWhisperEvidence.__new__(FasterWhisperEvidence)
        evidence.timeout_seconds = 5
        evidence._model = SimpleNamespace(transcribe=transcribe)
        evidence.transcribe(Path("unused.wav"), language="ar", prompt=None, precision=True)
        self.assertFalse(_whisper_decoder_config(precision=True, prompted=False)["conditionOnPreviousText"])
        self.assertTrue(_whisper_decoder_config(precision=True, prompted=True)["conditionOnPreviousText"])

    def test_precision_prompt_keeps_decoder_room_without_duplicate_lyrics(self):
        from faster_whisper import WhisperModel
        decoder = WhisperModel.__new__(WhisperModel)
        decoder.max_length = 448
        # Each prompt input is separately clipped by faster-whisper to 223 tokens.
        # Supplying the same long prompt twice leaves no room for output tokens.
        tokenizer = SimpleNamespace(sot_prev=1, sot_sequence=[2, 3, 4],
                                    encode=lambda _: list(range(300)))
        def transcribe(_audio, **options):
            prompt = decoder.get_prompt(tokenizer, tokenizer.encode(options["initial_prompt"]),
                                        hotwords=options.get("hotwords"))
            self.assertLessEqual(len(prompt), 227)
            self.assertEqual(options["initial_prompt"], "approved lyrics")
            self.assertTrue(options["condition_on_previous_text"])
            return [], SimpleNamespace(language="ar", language_probability=1.0)
        evidence = FasterWhisperEvidence.__new__(FasterWhisperEvidence)
        evidence.timeout_seconds = 5
        evidence._model = SimpleNamespace(transcribe=transcribe)
        evidence.transcribe(Path("unused.wav"), language="ar", prompt="approved lyrics", precision=True)

    def test_numeric_endpoint_cache_requires_exact_line_word_hull(self) -> None:
        for engine, version in (("torchaudio-mms-fa", 5), ("wav2vec2-ctc-forced-alignment", 4)):
            with self.subTest(engine=engine):
                result = {
                    "formatVersion": version, "engine": engine, **mms_timeline(5_000),
                    "lines": [{
                        "lineIndex": 0, "lineId": "line-1", "sourcePhysicalIndex": 0,
                        "startMs": 100, "endMs": 1_000, "confidence": .9,
                        "minimumWordConfidence": .9, "displayWordCount": 1, "romanizedWordCount": 1, "acousticTokenCount": 1,
                        "words": [{"wordId": "word-1", "startMs": 100, "endMs": 1_000,
                                   "confidence": .9, "acousticTokenCount": 1, "romanizedWordCount": 1}],
                    }],
                    "confidence": {key: .9 for key in (
                        "wordP10", "wordP50", "wordP90", "lineP10", "lineP50", "lineP90"
                    )},
                }
                if engine != 'torchaudio-mms-fa':
                    result['lines'][0].pop('romanizedWordCount')
                    result['lines'][0]['words'][0].pop('romanizedWordCount')
                arguments = dict(expected_sources=[0], duration_ms=5_000,
                                 expected_line_ids=["line-1"], expected_word_ids=[["word-1"]],
                                 expected_format_version=version, expected_engine=engine)
                _validate_numeric_mms_result(result, **arguments)
                for boundary, value in (("startMs", 0), ("endMs", 4_000)):
                    malformed = json.loads(json.dumps(result))
                    malformed["lines"][0][boundary] = value
                    with self.subTest(boundary=boundary), self.assertRaisesRegex(ValueError, "line.*word"):
                        _validate_numeric_mms_result(malformed, **arguments)

    def test_worker_failure_keeps_only_a_bounded_private_stderr_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "worker.output.private.json"
            durable = Path(temporary) / "job" / "worker.failure.private.txt"
            stderr = "discarded-prefix" + "x" * WORKER_FAILURE_DIAGNOSTIC_BYTES
            completed = subprocess.CompletedProcess([], 9, stdout="", stderr=stderr)
            with self.assertRaises(WorkerProcessError) as raised:
                raise_worker_failure(
                    "worker failed",
                    completed,
                    output,
                    diagnostic_path=durable,
                )
            self.assertEqual(raised.exception.code, "process-exit")
            self.assertNotIn("discarded-prefix", str(raised.exception))
            reference = raised.exception.private_diagnostics[0]
            retained = durable.parent / reference['file']
            self.assertEqual(retained.read_text(encoding="utf-8"), "x" * 65_536)
            self.assertEqual(hashlib.sha256(retained.read_bytes()).hexdigest(), reference['sha256'])
            self.assertFalse(
                output.with_name("worker.output.private.failure.private.txt").exists()
            )
            code = technical_diagnostic_code(RuntimeError(stderr), durable)
            self.assertRegex(code, r'^RuntimeError:sha256:[0-9a-f]{64}$')
            second = durable.parent / code.diagnostics[0]['file']
            self.assertEqual(second.stat().st_size, WORKER_FAILURE_DIAGNOSTIC_BYTES)
            self.assertNotIn("discarded-prefix", second.read_text(encoding="utf-8"))
            self.assertTrue(retained.is_file())

    def test_alignment_runtime_pins_child_package_validator(self) -> None:
        from stemwise_engine.model_registry import runtime_profile
        self.assertEqual('12.3.0', runtime_profile('lyrics')['packages']['pillow'])

    def test_wav2vec2_runtime_requires_v3_overlap_identity(self) -> None:
        runtime = {
            "runtimeVersion": 3,
            "engine": "wav2vec2-ctc-forced-alignment",
            "supportedRequestVersions": [MMS_REQUEST_VERSION],
            "torch": "2.7.0",
            "espeak": "1.52.0",
            "resolvedDevice": "cpu",
            "chunkSeconds": 30,
            "strideSeconds": 5,
            "retainedCoreSeconds": 20,
            "emissionMap": "shared-global-overlap-trimmed",
        }

        def identify(value: dict[str, object]) -> SimpleNamespace:
            return SimpleNamespace(returncode=0, stdout=json.dumps(value), stderr="")

        with (
            patch(
                'stemwise_engine.alignment_models.wav2vec2_runtime_pins',
                return_value={"torch": "2.7.0"},
            ),
            patch('stemwise_engine.lyrics_align_pc.run_process_tree', return_value=identify(runtime)),
        ):
            self.assertEqual(
                _wav2vec2_runtime_provenance(
                    Path("python"), Path("worker.py"), device="cpu", timeout_seconds=5
                ),
                runtime,
            )

        stale = {**runtime, "strideSeconds": 6}
        with (
            patch(
                'stemwise_engine.alignment_models.wav2vec2_runtime_pins',
                return_value={"torch": "2.7.0"},
            ),
            patch('stemwise_engine.lyrics_align_pc.run_process_tree', return_value=identify(stale)),
            self.assertRaisesRegex(RuntimeError, "differs from exact dependency pins"),
        ):
            _wav2vec2_runtime_provenance(
                Path("python"), Path("worker.py"), device="cpu", timeout_seconds=5
            )

    def test_wav2vec2_runtime_reused_only_within_matching_attempt(self) -> None:
        runtime = {
            "runtimeVersion": 3,
            "engine": "wav2vec2-ctc-forced-alignment",
            "supportedRequestVersions": [MMS_REQUEST_VERSION],
            "torch": "2.7.0", "espeak": "1.52.0", "resolvedDevice": "cpu",
            "chunkSeconds": 30, "strideSeconds": 5, "retainedCoreSeconds": 20,
            "emissionMap": "shared-global-overlap-trimmed",
        }
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            helper = project / "src" / "wav2vec2_forced_alignment.py"
            helper.parent.mkdir()
            helper.write_text("worker", encoding="utf-8")
            for name in ('ctc_worker.py', 'acoustic_inputs.py', 'worker_protocol.py', 'worker_publication.py', 'common.py'):
                (helper.parent / name).write_bytes((PROJECT_ROOT / 'src' / name).read_bytes())
            (helper.parent / "mms_forced_alignment.py").write_text("request", encoding="utf-8")
            python = project / "python"
            python.write_text("interpreter", encoding="utf-8")
            cache = {}
            with (
                patch('stemwise_engine.alignment_models.wav2vec2_runtime_pins', return_value={"torch": "2.7.0"}) as pins,
                patch('stemwise_engine.lyrics_align_pc.run_process_tree', return_value=SimpleNamespace(
                    returncode=0, stdout=json.dumps(runtime), stderr=""
                )) as worker,
            ):
                def probe(device="cpu", attempt=cache):
                    return _wav2vec2_runtime_provenance(
                        python, helper, device=device, timeout_seconds=5, attempt_cache=attempt
                    )
                self.assertEqual(probe(), runtime)
                self.assertEqual(probe(), runtime)
                self.assertEqual(worker.call_count, 1)
                probe(attempt={})
                self.assertEqual(worker.call_count, 2)
                probe(device="auto")
                self.assertEqual(worker.call_count, 3)
                helper.write_text("version = 2", encoding="utf-8")
                probe()
                self.assertEqual(worker.call_count, 4)
                with patch.dict(os.environ, {"CUDA_VISIBLE_DEVICES": "changed"}):
                    probe()
                self.assertEqual(worker.call_count, 5)
                python.write_text("changed interpreter", encoding="utf-8")
                probe()
                self.assertEqual(worker.call_count, 6)
                pins.return_value = {"torch": "changed"}
                for _ in range(2):
                    with self.assertRaisesRegex(RuntimeError, "differs from exact dependency pins"):
                        probe()
                self.assertEqual(worker.call_count, 8)

    def test_mms_runtime_reuses_attempt_identity_and_rechecks_new_attempt(self) -> None:
        from stemwise_engine.lyrics_align_pc import _mms_runtime_provenance

        runtime = {
            "runtimeVersion": 1, "bundle": "torchaudio.pipelines.MMS_FA",
            "requestedDevice": "cpu", "resolvedDevice": "cpu",
            "supportedRequestVersions": [MMS_REQUEST_VERSION], "torch": "2.7.0",
        }
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            helper = project / "src" / "mms_forced_alignment.py"
            helper.parent.mkdir()
            helper.write_text("worker", encoding="utf-8")
            for name in ('ctc_worker.py', 'acoustic_inputs.py', 'worker_protocol.py', 'worker_publication.py', 'common.py'):
                (helper.parent / name).write_bytes((PROJECT_ROOT / 'src' / name).read_bytes())
            python = project / "python"
            python.write_text("interpreter", encoding="utf-8")
            cache = {}
            with (
                patch('stemwise_engine.alignment_models.mms_runtime_pins', return_value={"torch": "2.7.0"}),
                patch('stemwise_engine.lyrics_align_pc.run_process_tree', return_value=SimpleNamespace(
                    returncode=0, stdout=json.dumps(runtime), stderr=""
                )) as worker,
            ):
                for _ in range(3):
                    self.assertEqual(_mms_runtime_provenance(
                        python, helper, device="cpu", timeout_seconds=5, attempt_cache=cache
                    ), runtime)
                self.assertEqual(worker.call_count, 1)
                _mms_runtime_provenance(
                    python, helper, device="cpu", timeout_seconds=5, attempt_cache={}
                )
                self.assertEqual(worker.call_count, 2)
                helper.write_text("version = 2", encoding="utf-8")
                _mms_runtime_provenance(
                    python, helper, device="cpu", timeout_seconds=5, attempt_cache=cache
                )
                self.assertEqual(worker.call_count, 3)

                # Separate virtual environments may point to the same Python binary.
                alias = project / "other-venv" / "python"
                alias.parent.mkdir()
                try:
                    alias.symlink_to(python)
                except OSError:
                    self.skipTest("symlink creation is unavailable")
                _mms_runtime_provenance(
                    alias, helper, device="cpu", timeout_seconds=5, attempt_cache=cache
                )
                self.assertEqual(worker.call_count, 4)

    def test_technical_diagnostic_code_never_contains_private_error_text(self) -> None:
        private = "C:/private/lyrics/source.txt secret words"
        code = technical_diagnostic_code(RuntimeError(private))
        self.assertEqual(code, "RuntimeError")
        self.assertNotIn(private, code)

    def test_worker_failure_exposes_only_a_closed_wav2vec2_subcode(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "worker.output.private.json"
            for message, expected in (
                (
                    "Wav2Vec2 segment 0 emission is too short for its target",
                    "wav2vec2-segment-too-short:0",
                ),
                (
                    "forced-alignment segment 3 touches its start boundary guard",
                    "wav2vec2-segment-boundary-guard:3:start",
                ),
                (
                    "forced-alignment segment 8 touches its end boundary guard",
                    "wav2vec2-segment-boundary-guard:8:end",
                ),
            ):
                completed = subprocess.CompletedProcess(
                    [],
                    1,
                    stdout="",
                    stderr="private traceback omitted\nSTEMWISE_WORKER_FAILURE=" + json.dumps({"formatVersion": 1, "code": expected}),
                )
                with self.assertRaises(RuntimeError) as raised:
                    raise_worker_failure(
                        "Wav2Vec2 forced alignment failed", completed, output
                    )
                self.assertEqual(
                    technical_diagnostic_code(raised.exception), expected
                )

    def test_generic_worker_failures_have_stable_body_free_identities(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "worker.output.private.json"

            def code(message: str) -> str:
                completed = subprocess.CompletedProcess(
                    [], 1, stdout="", stderr=f"Traceback omitted\nValueError: {message}\n"
                )
                with self.assertRaises(WorkerProcessError) as raised:
                    raise_worker_failure("worker failed", completed, output)
                return technical_diagnostic_code(raised.exception)

            first = code("first private failure")
            self.assertEqual(first, code("first private failure"))
            self.assertEqual(first, code("second private failure"))
            self.assertEqual(first, "process-exit")
            self.assertNotIn("private", first)


    def test_required_exact_content_witness_failure_is_retryable_technical(self):
        from test_alignment_content_admission import AlignmentContentAdmissionTests
        helper = AlignmentContentAdmissionTests()
        report, state = helper.run_coordinator('one two three four five six seven eight',
            qwen_failure=RuntimeError('injected required witness boundary'))
        self.assertEqual(report['counts']['failed'], 1)
        self.assertEqual(state['structureWitness']['status'], 'failure')
        self.assertEqual(state['status'], 'retryable-technical')

    def test_whisper_loader_uses_verified_local_snapshot_path_only(self) -> None:
        calls: list[tuple[tuple[object, ...], dict[str, object]]] = []

        def whisper_model(*args: object, **kwargs: object) -> object:
            calls.append((args, kwargs))
            return object()

        with tempfile.TemporaryDirectory() as name:
            snapshot = Path(name) / "snapshots" / "exact-commit"
            snapshot.mkdir(parents=True)
            with patch.dict(
                sys.modules,
                {"faster_whisper": SimpleNamespace(WhisperModel=whisper_model)},
            ):
                FasterWhisperEvidence("large-v3", snapshot, device="cpu", compute_type="int8")

        self.assertEqual(calls[0][0], (str(snapshot.resolve()),))
        self.assertEqual(
            calls[0][1],
            {"device": "cpu", "compute_type": "int8", "local_files_only": True},
        )

    def test_v1_synced_sidecar_is_rejected_without_migration(self) -> None:
        legacy = {
            "status": "complete",
            "tracks": [
                {
                    "id": "original",
                    "kind": "original",
                    "language": "en",
                    "format": "synced",
                    "source": {"name": "Reviewed"},
                    "lines": [
                        {"startMs": 100, "text": "First line"},
                        {"startMs": 900, "text": "Second line"},
                    ],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            path = root / "owner" / "KbKqkR3Q6SA.lyrics.json"
            path.parent.mkdir()
            legacy_raw = json.dumps(legacy).encode("utf-8")
            path.write_bytes(legacy_raw)

            with self.assertRaises(Exception):
                load_sidecar(path, root=path.parent)
            self.assertEqual(path.read_bytes(), legacy_raw)
            self.assertFalse((root / "PC side").exists())

    def test_sidecar_loader_rejects_symlinked_owner_input(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            owner = root / "owner"
            owner.mkdir()
            target = root / "external.json"
            target.write_text("{}", encoding="utf-8")
            linked = owner / "KbKqkR3Q6SA.lyrics.json"
            linked.symlink_to(target)

            with self.assertRaisesRegex(ValueError, "symlink"):
                load_sidecar(linked, root=owner)

    def test_mms_normalizer_maps_latin_diacritics_and_ipa_without_text_loss(self) -> None:
        self.assertEqual(
            _romanized_words("Gönül şığ ŋə ʃɑ"),
            ["gonul", "sig", "nge", "sha"],
        )
        self.assertEqual(_romanized_words("Привет мир"), ["privet", "mir"])
        self.assertEqual(_romanized_words("नेपाल"), ["nepaal"])

    def test_mms_v5_preserves_numeric_display_word_intervals_without_text(self) -> None:
        request = {
            "formatVersion": 5,
            "durationMs": 1_000,
            "segments": [
                {
                    "segmentIndex": 0,
                    "startLineIndex": 0,
                    "endLineIndex": 1,
                    "windowStartMs": 0,
                    "windowEndMs": 1_000,
                }
            ],
            "lines": [
                {
                    "lineId": "occurrence-27d5a77a803f39b225b2bf71",
                    "sourcePhysicalIndex": 4,
                    "words": [
                        {"wordId": "occurrence-word-37bb71b67df0712e83b3300e", "acousticText": "can't"},
                        {"wordId": "occurrence-word-07560f65df5bed78b288e80c", "acousticText": "Gönül"},
                    ],
                }
            ],
        }
        records, transcript_words, duration, segments = _request_plan(request)
        self.assertEqual(duration, 1000)
        self.assertEqual(segments, request['segments'])
        self.assertEqual(transcript_words, ["can't", "gonul"])
        lines = _aggregate_records(
            records,
            [
                {"startMs": 100, "endMs": 240, "confidence": 0.8, "tokenCount": 3},
                {"startMs": 260, "endMs": 520, "confidence": 0.7, "tokenCount": 4},
            ],
        )
        self.assertEqual(lines[0]["startMs"], 100)
        self.assertEqual(lines[0]["endMs"], 520)
        self.assertEqual(
            [word["wordId"] for word in lines[0]["words"]],
            [
                "occurrence-word-37bb71b67df0712e83b3300e",
                "occurrence-word-07560f65df5bed78b288e80c",
            ],
        )
        self.assertNotIn("text", json.dumps(lines))

    def test_mms_transform_records_resampling_and_rejects_material_residual(self) -> None:
        chunks = [
            chunk_time_map(0, 16_000, 0, 50)
        ]
        transform = canonical_time_transform(
            source_sample_rate=48_000,
            source_channel_count=2,
            source_frame_count=48_000,
            target_sample_rate=16_000,
            target_frame_count=16_000,
            chunk_target_frames=480_000,
            chunks=chunks,
        )
        self.assertEqual(transform["resamplingRatioNumerator"], 1)
        self.assertEqual(transform["resamplingRatioDenominator"], 3)
        self.assertEqual(transform["measuredResidualFrames"], 0)
        self.assertEqual(transform["chunks"], chunks)
        unequal_chunks = [
            chunk_time_map(0, 480_000, 0, 100),
            chunk_time_map(480_000, 640_000, 100, 200),
        ]
        self.assertEqual(
            emission_boundary_to_target_frame(100, unequal_chunks), 480_000
        )
        self.assertEqual(
            emission_boundary_to_target_frame(150, unequal_chunks), 560_000
        )
        with self.assertRaisesRegex(RuntimeError, "material canonical-time"):
            canonical_time_transform(
                source_sample_rate=48_000,
                source_channel_count=2,
                source_frame_count=48_000,
                target_sample_rate=16_000,
                target_frame_count=16_002,
                chunk_target_frames=480_000,
                chunks=chunks,
            )

    def test_mms_short_final_tail_survives_the_producer_consumer_contract(self) -> None:
        from stemwise_engine.acoustic_inputs import chunk_ranges
        from stemwise_engine.lyrics_align_pc import _validate_mms_time_transform

        def result(sample_count, ranges):
            chunks = [chunk_time_map(start, stop, index * 100, (index + 1) * 100)
                      for index, (start, stop) in enumerate(ranges)]
            return {'sampleRate': 16_000, 'durationMs': (sample_count * 1000 + 8000) // 16_000,
                    'emissionFrameCount': len(chunks) * 100,
                    'canonicalTimeTransform': canonical_time_transform(
                        source_sample_rate=48_000, source_channel_count=2,
                        source_frame_count=sample_count * 3, target_sample_rate=16_000,
                        target_frame_count=sample_count, chunk_target_frames=480_000, chunks=chunks)}

        for multiple in (1, 2, 3):
            for tail in (0, 1, 160, 399, 400):
                with self.subTest(multiple=multiple, tail=tail):
                    count = multiple * 480_000 + tail
                    value = result(count, chunk_ranges(count, 480_000))
                    _validate_mms_time_transform(value, value['durationMs'])
        for ranges in (((0, 480_001), (480_001, 960_000)), ((0, 480_400),)):
            with self.subTest(oversized=ranges):
                value = result(ranges[-1][1], ranges)
                with self.assertRaisesRegex(ValueError, 'chunk map is inconsistent'):
                    _validate_mms_time_transform(value, value['durationMs'])
        value = result(480_001, ((0, 480_001),))
        value['canonicalTimeTransform']['chunks'][0]['targetStartFrame'] = False
        with self.assertRaisesRegex(ValueError, 'chunk map is inconsistent'):
            _validate_mms_time_transform(value, value['durationMs'])

    def test_whisper_and_mms_share_stable_mapping_ids(self) -> None:
        seen: dict[str, object] = {}
        original = {
            "id": "original",
            "kind": "original",
            "language": "en",
            "script": "Latn",
            "format": "plain",
            "text": "hello world",
        }
        mapping = build_lexical_occurrence_mapping(
            "KbKqkR3Q6SA", original, (0,)
        )

        def fake_worker(command: list[str], **_: object) -> SimpleNamespace:
            request_path = Path(command[command.index("--request") + 1])
            output_path = Path(command[command.index("--output") + 1])
            request = json.loads(request_path.read_text(encoding="utf-8"))
            seen["request"] = request
            request_line = request["lines"][0]
            output_path.write_text(
                json.dumps(
                    {
                        "formatVersion": 5,
                        "engine": "torchaudio-mms-fa",
                        **mms_timeline(1_000),
                        "segmentCount": 1,
                        "segmentBoundaryGuardMs": 250,
                        "displayWordCount": 2, "romanizedWordCount": 2, "acousticTokenCount": 2,
                        "lineCount": 1,
                        "lines": [
                            {
                                "lineIndex": 0,
                                "lineId": request_line["lineId"],
                                "sourcePhysicalIndex": 0,
                                "startMs": 100,
                                "endMs": 800,
                                "confidence": 0.8,
                                "minimumWordConfidence": 0.8,
                                "displayWordCount": 2, "romanizedWordCount": 2, "acousticTokenCount": 2,
                                "words": [
                                    {
                                        "wordId": request_line["words"][0]["wordId"],
                                        "startMs": 100,
                                        "endMs": 400,
                                        "confidence": 0.8,
                                        "acousticTokenCount": 1, "romanizedWordCount": 1,
                                    },
                                    {
                                        "wordId": request_line["words"][1]["wordId"],
                                        "startMs": 500,
                                        "endMs": 800,
                                        "confidence": 0.8,
                                        "acousticTokenCount": 1, "romanizedWordCount": 1,
                                    }
                                ],
                            }
                        ],
                        "confidence": {
                            "wordP10": 0.8,
                            "wordP50": 0.8,
                            "wordP90": 0.8,
                            "lineP10": 0.8,
                            "lineP50": 0.8,
                            "lineP90": 0.8,
                        },
                    }
                ),
                encoding="utf-8",
            )
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            root = Path(name)
            helper = root / "mms.py"
            helper.write_text("# test", encoding="utf-8")
            for name in ("ctc_worker.py", "acoustic_inputs.py", "worker_protocol.py", "worker_publication.py", "common.py"):
                (root / name).write_bytes((PROJECT_ROOT / "src" / name).read_bytes())
            audio = root / "vocals.wav"
            write_test_wav(audio)
            temp_dir = root / "temp"
            temp_dir.mkdir()
            workspace = AlignmentWorkspace(root / "agent-research", workspace_root=root)
            audio_identity = write_audio_provenance(
                workspace,
                "KbKqkR3Q6SA",
                duration_ms=1_000,
                mix_audio=audio,
            )["mix"]
            with (
                patch('stemwise_engine.lyrics_align_pc._run_alignment_worker_batch', side_effect=lambda **k: (
                    fake_worker(['--request', str(k['request_path']), '--output', str(k['output_path'])]),
                    k['request'], None)),
                patch('stemwise_engine.alignment_audio.file_identity', return_value=audio_identity),
            ):
                mms_options = dict(
                    config={"projectDir": str(root), "tempDir": str(root / "temp")},
                    video_id="KbKqkR3Q6SA",
                    variant="baseline",
                    sources=[0],
                    acoustic_track_kind="original",
                    duration_ms=1_000,
                    workspace=workspace,
                    helper=helper,
                    mms_python=root / "python",
                    vocals_audio=audio,
                    temp_dir=temp_dir,
                    device="cpu",
                    timeout_seconds=5,
                    provenance={"audio": audio_identity},
                    occurrence_mapping=mapping,
                    display_word_lines=[display_words("hello world")],
                )
                produced, reused = _load_or_run_mms_alignment(**mms_options)
                self.assertFalse(reused)
                with patch('stemwise_engine.lyrics_align_pc._run_alignment_worker_batch', side_effect=AssertionError('cache hit must not execute a model')):
                    cached, reused = _load_or_run_mms_alignment(**{**mms_options, 'vocals_audio':None})
                self.assertTrue(reused)
                self.assertEqual(cached, produced)

                project=root/'wave-project'
                prepare_runtime_layout(project)
                (project/'.venv/bin').mkdir(parents=True)
                (project/'.venv/bin/python').touch()
                (project/'src').mkdir()
                for helper_name in ('wav2vec2_forced_alignment.py','mms_forced_alignment.py',
                        'ctc_worker.py','acoustic_inputs.py','worker_protocol.py','worker_publication.py','common.py'):
                    (project/'src'/helper_name).write_bytes((PROJECT_ROOT/'src'/helper_name).read_bytes())
                def wave_worker(**options):
                    completed=fake_worker(['--request',str(options['request_path']),'--output',str(options['output_path'])])
                    packet=json.loads(options['output_path'].read_bytes())
                    packet.update(formatVersion=4,engine='wav2vec2-ctc-forced-alignment',requestFormatVersion=5)
                    packet.pop('romanizedWordCount')
                    for line in packet['lines']:
                        line.pop('romanizedWordCount')
                        for word in line['words']: word.pop('romanizedWordCount')
                    options['output_path'].write_text(json.dumps(packet),encoding='utf-8')
                    return completed,options['request'],None
                wave_options=dict(config={'projectDir':str(project)},video_id='KbKqkR3Q6SA',language='en',
                    sources=[0],duration_ms=1000,workspace=workspace,audio=audio,audio_identity=audio_identity,
                    temp_dir=temp_dir,device='cpu',timeout_seconds=5,occurrence_mapping=mapping,
                    display_word_lines=[display_words('hello world')])
                with patch('stemwise_engine.lyrics_align_pc._wav2vec2_model_assets',return_value=(root/'model',{})), \
                        patch('stemwise_engine.lyrics_align_pc._wav2vec2_runtime_provenance',return_value={}), \
                        patch('stemwise_engine.lyrics_align_pc._run_alignment_worker_batch',side_effect=wave_worker) as worker:
                    wave, reused, _ = _load_or_run_wav2vec2_alignment(**wave_options)
                    self.assertFalse(reused)
                    cached, reused, _ = _load_or_run_wav2vec2_alignment(**{**wave_options,'audio':None})
                    self.assertTrue(reused)
                    self.assertEqual(cached,wave)
                    self.assertEqual(worker.call_count,1)
                    self.assertEqual([word['startMs'] for word in cached['lines'][0]['words']],[100,500])
                    cache=next(workspace.job_dir('KbKqkR3Q6SA').glob('wav2vec2-public-endpoints-*.private.json'))
                    damaged=json.loads(cache.read_bytes());damaged['lines'][0]['words'][0]['startMs']=-1
                    cache.write_text(json.dumps(damaged),encoding='utf-8')
                    with self.assertRaises(FileNotFoundError):
                        _load_or_run_wav2vec2_alignment(**{**wave_options,'audio':None})
                    self.assertEqual(worker.call_count,1)

        whisper = whisper_word_candidates(
            original["text"],
            (0,),
            [transcript("hello world")],
            2_000,
            occurrence_mapping=mapping,
            transcript_labels=["faster-whisper:en"],
        )
        request = seen["request"]
        self.assertEqual(request["formatVersion"], 5)
        self.assertEqual(request["durationMs"], 1_000)
        self.assertEqual(request["segments"][0]["endLineIndex"], 1)
        self.assertEqual(whisper[0]["route"], "faster-whisper:en")
        self.assertEqual(request["lines"][0]["lineId"], whisper[0]["lineId"])
        self.assertEqual(
            [word["wordId"] for word in request["lines"][0]["words"]],
            [word["wordId"] for word in whisper[0]["words"]],
        )
        self.assertNotIn("occurrence-000000", json.dumps(request))

    def test_mms_rejects_audio_bytes_that_do_not_match_bound_identity(self) -> None:
        original = plain_original("hello world")
        mapping = build_lexical_occurrence_mapping(
            "KbKqkR3Q6SA", original, (0,)
        )
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
            root = Path(name)
            helper = root / "mms.py"
            helper.write_text("# test", encoding="utf-8")
            for name in ("ctc_worker.py", "acoustic_inputs.py", "worker_protocol.py", "worker_publication.py", "common.py"):
                (root / name).write_bytes((PROJECT_ROOT / "src" / name).read_bytes())
            audio = root / "vocals.wav"
            write_test_wav(audio)
            workspace = AlignmentWorkspace(root / "alignment", workspace_root=root)
            identity = write_audio_provenance(
                workspace,
                "KbKqkR3Q6SA",
                duration_ms=1_000,
                mix_audio=audio,
            )["mix"]
            identity = {**identity, "sha256": "0" * 64}
            with self.assertRaisesRegex(ValueError, "differs from its bound identity"):
                _load_or_run_mms_alignment(
                    config={"projectDir": str(root), "tempDir": str(root / "temp")},
                    video_id="KbKqkR3Q6SA",
                    variant="baseline",
                    sources=[0],
                    acoustic_track_kind="original",
                    duration_ms=1_000,
                    workspace=workspace,
                    helper=helper,
                    mms_python=root / "python",
                    vocals_audio=audio,
                    temp_dir=root / "temp",
                    device="cpu",
                    timeout_seconds=1,
                    provenance={"audio": identity},
                    occurrence_mapping=mapping,
                    display_word_lines=[display_words("hello world")],
                )

    def test_whisper_candidates_require_complete_approved_display_words(self) -> None:
        text = "\u2003\tHello 👩‍🎤 world\u00a0\n\u00a0Don't stop now"
        original = {
            "id": "original",
            "kind": "original",
            "language": "en",
            "script": "Latn",
            "format": "plain",
            "text": text,
        }
        mapping = build_lexical_occurrence_mapping(
            "KbKqkR3Q6SA", original, (0, 1)
        )
        candidates = whisper_word_candidates(
            text,
            (0, 1),
            [transcript("Hello world Don't stop now")],
            10_000,
            occurrence_mapping=mapping,
            transcript_labels=["faster-whisper:en"],
        )
        self.assertEqual([len(line["words"]) for line in candidates], [2, 3])
        self.assertEqual(
            [[word["wordId"] for word in line["words"]] for line in candidates],
            [
                [word["occurrenceWordId"] for word in occurrence["words"]]
                for occurrence in mapping["performedOccurrences"]
            ],
        )
        self.assertEqual(
            [(word["startUtf16"], word["endUtf16"]) for word in candidates[0]["words"]],
            [(2, 7), (14, 19)],
        )
        incomplete = whisper_word_candidates(
            text,
            (0, 1),
            [transcript("Hello world Don't now")],
            10_000,
            occurrence_mapping=mapping,
            transcript_labels=["faster-whisper:en"],
        )
        self.assertIsNotNone(incomplete[1])
        self.assertIsNone(incomplete[1]["words"])

    def test_whisper_candidates_do_not_mix_routes_across_repeated_lines(self) -> None:
        text = "alpha beta\nmiddle bridge\nalpha beta"
        original = plain_original(text)
        mapping = build_lexical_occurrence_mapping(
            "KbKqkR3Q6SA", original, (0, 1, 2)
        )

        candidates = whisper_word_candidates(
            text,
            (0, 1, 2),
            [
                transcript("alpha beta middle bridge", start=1.0),
                transcript("middle bridge alpha beta", start=10.0),
            ],
            20_000,
            occurrence_mapping=mapping,
            transcript_labels=["route-first", "route-second"],
        )

        self.assertEqual(
            {candidate["route"] for candidate in candidates if candidate is not None},
            {"route-first"},
        )
        self.assertLess(candidates[0]["startMs"], 5_000)
        self.assertEqual(
            [candidate is not None for candidate in candidates],
            [True, True, False],
        )
        self.assertEqual(
            whisper_word_candidates(
                text,
                (0, 1, 2),
                [[], []],
                20_000,
                occurrence_mapping=mapping,
                transcript_labels=["route-first", "route-second"],
            ),
            (None, None, None),
        )

    def test_endpoint_identity_requires_distinct_authority_occurrences(self) -> None:
        _validate_occurrence_identity((0, 1, 2))
        with self.assertRaisesRegex(ValueError, "distinct source identities"):
            _validate_occurrence_identity((0, 1, 0))



    def test_distinct_physical_lines_with_same_lexical_body_are_safe(self) -> None:
        _validate_occurrence_identity((0, 1, 2))


    def test_route_local_fallback_never_replaces_primary_ready_lines(self) -> None:
        primary = tuple({"owner": f"mix-{index}", "startMs": index*1000,
                         "endMs": index*1000+500} for index in range(5))
        fallback = tuple({"owner": f"local-{index}", "startMs": index*1000+10,
                          "endMs": index*1000+510} for index in range(5))
        summary = {
            "occurrenceCandidates": [
                {"publishDecision": {"result": "words"}},
                {"publishDecision": {"result": "line-only", "code": "word-end-disagreement"}},
                {"publishDecision": {"result": "unresolved", "code": "line-start-disagreement"}},
                {"publishDecision": {"result": "unresolved", "code": "line-end-disagreement"}},
                {"publishDecision": {"result": "unresolved", "code": "line-end-disagreement"}},
            ]
        }
        selected = _ordered_verifier_fallback_candidates(
            summary,
            primary,
            fallback,
            (0, 1, 2, 3, 4),
        )
        self.assertIs(selected[0], primary[0])
        self.assertIs(selected[1], primary[1])
        self.assertIs(selected[2], fallback[2])
        self.assertIs(selected[3], fallback[3])
        self.assertIs(selected[4], fallback[4])

    def test_verifier_mixing_cannot_jump_back_to_an_earlier_repeat(self) -> None:
        primary = ({"startMs": 204000, "endMs": 206000},
                   {"startMs": 242620, "endMs": 254420},
                   {"startMs": 254690, "endMs": 284730})
        fallback = ({"startMs": 204010, "endMs": 206010},
                    {"startMs": 221720, "endMs": 226000},
                    {"startMs": 226640, "endMs": 228900})
        summary = {"occurrenceCandidates": [
            {"publishDecision": {"result": "words"}},
            {"publishDecision": {"result": "line-only", "code": "low-forced-word-support"}},
            {"publishDecision": {"result": "unresolved", "code": "line-start-disagreement"}},
        ]}
        before = json.dumps((summary, primary, fallback))
        selected = _ordered_verifier_fallback_candidates(summary, primary, fallback, (0, 1, 2))
        # Both input streams are ordered, but their old per-line mixture was not.
        self.assertEqual([(v["startMs"], v["endMs"]) for v in selected],
                         [(204010, 206010), (221720, 226000), (226640, 228900)])
        self.assertEqual(json.dumps((summary, primary, fallback)), before)
        broken = (fallback[0], {"startMs": 240000, "endMs": 250000}, fallback[2])
        with self.assertRaisesRegex(ValueError, "globally ordered"):
            _ordered_verifier_fallback_candidates(summary, primary, broken, (0, 1, 2))
        self.assertEqual(json.dumps((summary, primary, fallback)), before)

    def test_route_local_verifier_trigger_is_static(self) -> None:
        low_support = {
            "occurrenceCandidates": [
                {"publishDecision": {"result": "unresolved", "code": "low-forced-line-support"}}
            ]
        }
        boundary = {
            "occurrenceCandidates": [
                {"publishDecision": {"result": "unresolved", "code": "line-end-disagreement"}}
            ]
        }
        self.assertTrue(_needs_route_local_verifier(low_support, (0,)))
        self.assertTrue(_needs_route_local_verifier(boundary, (0,)))
        with self.assertRaisesRegex(ValueError, "distinct source identities"):
            _needs_route_local_verifier({"occurrenceCandidates": [{}, {}]}, (0, 0))

    def test_low_mms_support_does_not_hide_the_vocal_verifier(self) -> None:
        # Low MMS confidence must not pin a failed mix timestamp over an available
        # vocal candidate. Changing the verifier must not waive MMS confidence.
        forced = {"lines": [
            {"lineId": "first", "sourcePhysicalIndex": 0, "startMs": 1000,
             "endMs": 2000, "confidence": .01, "words": []},
            {"lineId": "second", "sourcePhysicalIndex": 1, "startMs": 3000,
             "endMs": 4000, "confidence": .8, "words": []},
        ]}
        for index, line in enumerate(forced['lines']):
            line['words'] = [{'wordId': f'word-{index}', 'startMs': line['startMs'],
                'endMs': line['endMs'], 'confidence': line['confidence'], 'acousticTokenCount': 1}]
        primary = tuple({"lineId": line["lineId"], "route": WAV2VEC2_ROUTE,
                         "startMs": start, "endMs": end, "words": []}
                        for line, start, end in zip(forced["lines"], (30000, 3000), (40000, 4000)))
        route = "wav2vec2-ctc-forced-on-htdemucs-vocals"
        fallback = tuple({**candidate, "route": route, "startMs": line["startMs"],
                          "endMs": line["endMs"]}
                         for candidate, line in zip(primary, forced["lines"]))
        before = json.dumps((forced, primary, fallback), sort_keys=True)
        _, baseline = _refine_public_timings(forced, primary)
        selected = _ordered_verifier_fallback_candidates(baseline, primary, fallback, (0, 1))
        self.assertIs(selected[0], fallback[0])
        self.assertIs(selected[1], primary[1])
        public, result = _refine_public_timings(
            forced, selected, allowed_wav2vec2_routes=frozenset((WAV2VEC2_ROUTE, route)))
        self.assertEqual(public, [{"startMs": 1000, "endMs": 2000}, {"startMs": 3000, "endMs": 4000}])
        self.assertEqual(result["resultClass"], "line-unresolved")
        self.assertEqual(result["lineReadyCount"], 1)
        self.assertEqual(result["occurrenceCandidates"][1]["publishDecision"]["result"], "line-only")
        first = result["occurrenceCandidates"][0]
        self.assertNotIn("selectedPublisherFamily", first)
        self.assertEqual(first["publishDecision"],
                         {"result": "unresolved", "code": "low-forced-line-support"})
        self.assertEqual(first["familyCandidates"][WAV2VEC2_FAMILY]["startMs"], 1000)
        self.assertEqual(json.dumps((forced, primary, fallback), sort_keys=True), before)





    def test_only_exact_caption_and_precision_unprompted_whisper_own_structure(self) -> None:
        self.assertEqual(
            _free_structure_witness_family("youtube-caption:manual"),
            "youtube-caption",
        )
        self.assertEqual(
            _free_structure_witness_family("faster-whisper-precision-unprompted:en"),
            "unprompted-faster-whisper",
        )
        for rejected in (
            "faster-whisper:en",
            "faster-whisper-precision:en",
            "faster-whisper-precision-unprompted:unknown",
            "faster-whisper-unknown:en",
            "unknown-whisper:en",
        ):
            self.assertIsNone(_free_structure_witness_family(rejected))

    def test_endpoint_cascade_retains_one_bounded_private_failure(self) -> None:
        failures = []
        def fail(route):
            raise ValueError("route root cause " + "x" * WORKER_FAILURE_DIAGNOSTIC_BYTES + " private-tail")
        with tempfile.TemporaryDirectory() as temp:
            diagnostic = Path(temp) / "endpoint.failure.private.txt"
            with self.assertRaises(ConditionalCascadeTechnicalError):
                _run_conditional_cascade(("route-1",), fail,
                    on_failure=lambda route, code: failures.append((route, code)), diagnostic_path=diagnostic)
            self.assertEqual(failures[0][0], 'route-1')
            self.assertRegex(failures[0][1], r'^ValueError:sha256:[0-9a-f]{64}$')
            reference = failures[0][1].diagnostics[0]
            detail = (diagnostic.parent / reference['file']).read_bytes()
            self.assertEqual(hashlib.sha256(detail).hexdigest(), reference['sha256'])
            self.assertLessEqual(len(detail), WORKER_FAILURE_DIAGNOSTIC_BYTES)
            self.assertIn(b"private-tail", detail)
            self.assertNotIn("private-tail", failures[0][1])

    def test_endpoint_cascade_stops_at_first_quality_success(self) -> None:
        attempted: list[str] = []

        def evaluate(route: str) -> tuple[bool, str]:
            attempted.append(route)
            return route == "second", route

        selected, value = _run_conditional_cascade(
            ("first", "second", "third"), evaluate
        )
        self.assertEqual((selected, value), ("second", "second"))
        self.assertEqual(attempted, ["first", "second"])

        attempted.clear()

        def technically_then_unresolved(route: str) -> tuple[bool, str]:
            attempted.append(route)
            if route == "first":
                raise RuntimeError("worker failed")
            return False, route

        with self.assertRaises(ConditionalCascadeTechnicalError):
            _run_conditional_cascade(
                ("first", "second", "third"), technically_then_unresolved
            )
        self.assertEqual(attempted, ["first", "second", "third"])

        def technically_then_resolved(route: str) -> tuple[bool, str]:
            if route == "first":
                raise RuntimeError(route)
            return True, route

        selected, value = _run_conditional_cascade(
            ("first", "second"), technically_then_resolved
        )
        self.assertEqual((selected, value), ("second", "second"))

        with self.assertRaises(ConditionalCascadeTechnicalError):
            _run_conditional_cascade(
                ("first", "second"),
                lambda route: (_ for _ in ()).throw(RuntimeError(route)),
            )

    def test_completed_endpoint_routes_recover_only_redundant_failures(self) -> None:
        attempts = [
            {"routeId": f"{route}:baseline"}
            for route in (
                "htdemucs-vocals",
                "kimberley-jensen-roformer-vocals",
                "sucial-v2-roformer-dry-vocals",
            )
        ]
        failures = [
            {
                "stage": "structure",
                "routeId": "kimberley-jensen-roformer-vocals",
                "code": "process-exit",
            },
            {
                "stage": "endpoint-consensus",
                "routeId": "owsm",
                "code": "process-exit",
            },
            {
                "stage": "endpoint-bootstrap",
                "routeId": "htdemucs-vocals:anchored-wav2vec2",
                "code": "process-exit",
            },
            {
                "stage": "endpoint",
                "routeId": "htdemucs-vocals",
                "code": "process-exit",
            },
        ]
        recovered, blocking = _partition_endpoint_route_failures(failures, attempts)
        self.assertEqual(recovered, failures)
        self.assertEqual(blocking, [])

        required_failure = {
            "stage": "endpoint-route-verifier",
            "routeId": "sucial-v2-roformer-dry-vocals",
            "code": "process-exit",
        }
        recovered, blocking = _partition_endpoint_route_failures(
            failures + [required_failure], attempts
        )
        self.assertEqual(recovered, failures)
        self.assertEqual(blocking, [required_failure])

    def test_endpoint_cascade_preserves_completed_baselines_after_refinement_failure(self):
        routes = ENDPOINT_MMS_ROUTES
        for missing_baseline, required_verifier in ((False, False), (True, False), (False, True)):
            with self.subTest(missing_baseline=missing_baseline, required_verifier=required_verifier):
                attempts, failures = [], []
                pending = {"summary": {"resultClass": "line-unresolved"},
                           "timings": [{"startMs": 100, "endMs": 900}]}
                before = json.loads(json.dumps(pending))
                def evaluate(route):
                    if route != routes[0] or not missing_baseline:
                        attempts.append({"routeId": f"{route}:baseline"})
                    if route == routes[0]:
                        if required_verifier:
                            failures.append({"stage": "endpoint-route-verifier", "routeId": route,
                                             "code": "process-exit"})
                        raise ValueError("merged endpoint timings are not globally ordered")
                    return False, pending
                def failed(route, code):
                    failures.append({"stage": "endpoint", "routeId": route, "code": code})
                kwargs = dict(on_failure=failed, recover_completed_failures=lambda:
                    not _partition_endpoint_route_failures(failures, attempts)[1])
                if missing_baseline or required_verifier:
                    with self.assertRaises(ConditionalCascadeTechnicalError):
                        _run_conditional_cascade(routes, evaluate, **kwargs)
                else:
                    selected, value = _run_conditional_cascade(routes, evaluate, **kwargs)
                    self.assertEqual(selected, routes[-1])
                    self.assertIs(value, pending)
                    self.assertEqual(value["summary"]["resultClass"], "line-unresolved")
                    self.assertEqual(pending, before)
                    self.assertEqual(_partition_endpoint_route_failures(failures, attempts),
                                     (failures, []))

    def test_endpoint_cascade_cannot_recover_without_a_completed_result(self):
        attempts, failures = [], []
        def evaluate(route):
            attempts.append({"routeId": f"{route}:baseline"})
            raise ValueError("optional refinement failed")
        def failed(route, code):
            failures.append({"stage": "endpoint", "routeId": route, "code": code})
        with self.assertRaises(ConditionalCascadeTechnicalError):
            _run_conditional_cascade(ENDPOINT_MMS_ROUTES, evaluate, on_failure=failed,
                recover_completed_failures=lambda:
                    not _partition_endpoint_route_failures(failures, attempts)[1])
        self.assertEqual(_partition_endpoint_route_failures(failures, attempts), (failures, []))

    def test_bootstrap_plan_uses_disjoint_guarded_windows(self) -> None:
        verifier_lines = tuple(
            {
                "startMs": start,
                "endMs": end,
            }
            for start, end in (
                (100, 900),
                (3_000, 3_600),
                (5_000, 5_600),
                (8_000, 8_600),
                (12_000, 12_700),
                (15_000, 15_600),
            )
        )
        segments = _build_bootstrap_segments(6, 20_000, verifier_lines)
        self.assertEqual(
            [item["endLineIndex"] for item in segments],
            [1, 2, 3, 4, 5, 6],
        )
        self.assertEqual(
            [item["windowEndMs"] for item in segments],
            [1_950, 4_300, 6_800, 10_300, 13_850, 20_000],
        )
        for left in segments[:-1]:
            boundary = left["endLineIndex"]
            cut = left["windowEndMs"]
            self.assertGreaterEqual(
                cut - verifier_lines[boundary - 1]["endMs"],
                WORKER_SEGMENT_BOUNDARY_GUARD_MS,
            )
            self.assertGreaterEqual(
                verifier_lines[boundary]["startMs"] - cut,
                WORKER_SEGMENT_BOUNDARY_GUARD_MS,
            )
        self.assertIsNone(_validate_occurrence_identity((0, 1, 2, 3, 4, 5), segments))
        with self.assertRaisesRegex(ValueError, "distinct source identities"):
            _validate_occurrence_identity((0, 1, 2, 3, 4, 0), segments)

        blocked = tuple(dict(line) for line in verifier_lines)
        blocked[2]["startMs"] = 3_900
        merged = _build_bootstrap_segments(6, 20_000, blocked)
        self.assertEqual(
            [item["endLineIndex"] for item in merged],
            [1, 3, 4, 5, 6],
        )

    def test_short_bootstrap_segment_merges_without_changing_coverage(self) -> None:
        segments = [
            {
                "segmentIndex": 0,
                "startLineIndex": 0,
                "endLineIndex": 2,
                "windowStartMs": 0,
                "windowEndMs": 2_000,
            },
            {
                "segmentIndex": 1,
                "startLineIndex": 2,
                "endLineIndex": 3,
                "windowStartMs": 2_000,
                "windowEndMs": 3_000,
            },
            {
                "segmentIndex": 2,
                "startLineIndex": 3,
                "endLineIndex": 5,
                "windowStartMs": 3_000,
                "windowEndMs": 5_000,
            },
        ]
        self.assertEqual(
            _merge_short_bootstrap_segment(segments, 1),
            [
                segments[0],
                {
                    "segmentIndex": 1,
                    "startLineIndex": 2,
                    "endLineIndex": 5,
                    "windowStartMs": 2_000,
                    "windowEndMs": 5_000,
                },
            ],
        )

    def test_boundary_recovery_removes_the_reported_fence(self) -> None:
        segments = [dict(segmentIndex=i, startLineIndex=i, endLineIndex=i+1,
                         windowStartMs=i*1000, windowEndMs=(i+1)*1000)
                    for i in range(3)]
        before = json.dumps(segments)
        for boundary, expected in (("start", [(0, 2, 0, 2000), (2, 3, 2000, 3000)]),
                                   ("end", [(0, 1, 0, 1000), (1, 3, 1000, 3000)])):
            with tempfile.TemporaryDirectory() as name:
                with self.assertRaises(WorkerProcessError) as raised:
                    raise_worker_failure("worker failed", subprocess.CompletedProcess(
                        [], 1, stderr="STEMWISE_WORKER_FAILURE=" + json.dumps({"formatVersion": 1, "code": f"wav2vec2-segment-boundary-guard:1:{boundary}"})),
                        Path(name) / "output.json")
                self.assertEqual(raised.exception.code,
                                 f"wav2vec2-segment-boundary-guard:1:{boundary}")
                merged = _merge_short_bootstrap_segment(segments, 1, boundary=boundary)
                self.assertEqual([(s["startLineIndex"], s["endLineIndex"], s["windowStartMs"],
                                   s["windowEndMs"]) for s in merged], expected)
                self.assertEqual([s["segmentIndex"] for s in merged], [0, 1])
                self.assertEqual(json.dumps(segments), before)
        for index, boundary in ((0, "start"), (2, "end"), (1, "unknown")):
            with self.subTest(index=index, boundary=boundary):
                with self.assertRaises(ValueError):
                    _merge_short_bootstrap_segment(segments, index, boundary=boundary)
                self.assertEqual(json.dumps(segments), before)

    def test_bootstrap_plan_intersects_family_gaps_at_guard_fencepost(self) -> None:
        publisher = (
            {"startMs": 0, "endMs": 900},
            {"startMs": 1_600, "endMs": 4_000},
        )
        verifier = (
            {"startMs": 0, "endMs": 1_000},
            {"startMs": 1_500, "endMs": 4_000},
        )
        segments = _build_bootstrap_segments(2, 5_000, publisher, verifier)
        self.assertEqual(segments[0]["windowEndMs"], 1_250)
        verifier[1]["startMs"] = 1_499
        self.assertIsNone(
            _build_bootstrap_segments(2, 5_000, publisher, verifier)
        )


    def test_parent_segment_contract_matches_worker_half_open_windows(self) -> None:
        segments = [
            {
                "segmentIndex": 0,
                "startLineIndex": 0,
                "endLineIndex": 1,
                "windowStartMs": 0,
                "windowEndMs": 4_000,
            },
            {
                "segmentIndex": 1,
                "startLineIndex": 1,
                "endLineIndex": 3,
                "windowStartMs": 4_000,
                "windowEndMs": 10_000,
            },
        ]
        _validate_forced_alignment_segments(segments, 3, 10_000)
        with self.assertRaisesRegex(ValueError, "malformed"):
            _validate_forced_alignment_segments(
                [
                    segments[0],
                    {**segments[1], "windowStartMs": 3_999},
                ],
                3,
                10_000,
            )

    def test_worker_duration_and_wav_helper_identity_are_exact(self) -> None:
        # Streamed workers use exact rational sample counts.
        for frames, expected_ms in ((7938196, 180005), (16782848, 380564), (10849281, 246016)):
            self.assertEqual(_worker_pcm_duration_ms(
                {"stream": {"sampleRate": 44100, "frameCount": frames}}
            ), expected_ms)
        self.assertEqual(
            _worker_pcm_duration_ms(
                {"stream": {"sampleRate": 48_000, "frameCount": 48_024}}
            ),
            1_001,
        )
        self.assertEqual(
            _worker_pcm_duration_ms(
                {"stream": {"sampleRate": 48_000, "frameCount": 22}}
            ),
            1,
        )
        hashes = _wav2vec2_helper_hashes(PROJECT_ROOT)
        self.assertEqual(
            set(hashes),
            {"mms_forced_alignment.py", "wav2vec2_forced_alignment.py"},
        )
        self.assertTrue(all(len(value) == 64 for value in hashes.values()))

    def test_endpoint_requests_preserve_native_rounding_and_canonical_eof(self) -> None:
        canonical = 204103
        segments = [
            {"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 1,
             "windowStartMs": 0, "windowEndMs": 100000},
            {"segmentIndex": 1, "startLineIndex": 1, "endLineIndex": 2,
             "windowStartMs": 100000, "windowEndMs": canonical},
        ]
        for frames, expected in ((9000961, 204103), (9000962, 204104), (9000900, 204102)):
            with self.subTest(frames=frames):
                identity = {"stream": {"sampleRate": 44100, "frameCount": frames}}
                native, adapted = _endpoint_request_timing(identity, canonical, segments)
                self.assertEqual(native, expected)
                self.assertEqual(adapted[0], segments[0])
                self.assertEqual(adapted[1], {**segments[1], "windowEndMs": expected})
                self.assertEqual(segments[1]["windowEndMs"], canonical)
        with self.assertRaisesRegex(ValueError, "material"):
            _endpoint_request_timing(
                {"stream": {"sampleRate": 44100, "frameCount": 9001049}},
                canonical, segments,
            )
        with self.assertRaises(ValueError):
            _endpoint_request_timing(
                {"stream": {"sampleRate": 16000, "frameCount": 204102 * 16}},
                canonical, [{"segmentIndex": 0, "startLineIndex": 0,
                             "endLineIndex": 1, "windowStartMs": 204102,
                             "windowEndMs": canonical}],
            )
        raw = {"durationMs": 204104, "lines": [
            {"startMs": 204000, "endMs": 204104, "words": [
                {"startMs": 204000, "endMs": 204104},
            ]},
        ]}
        projected = _project_endpoint_eof(raw, canonical)
        self.assertEqual(projected["lines"][0]["endMs"], canonical)
        self.assertEqual(projected["lines"][0]["words"][0]["endMs"], canonical)
        self.assertEqual(raw["lines"][0]["endMs"], 204104)
        self.assertEqual(raw["lines"][0]["words"][0]["endMs"], 204104)
        self.assertEqual(projected["durationMs"], raw["durationMs"])
        for native in (canonical - 1, canonical + 1):
            raw_plan = {"durationMs": native, "lines": [{
                "startMs": canonical - 100, "endMs": native, "words": []}],
                "resolvedSegments": [{"segmentIndex": 0, "startLineIndex": 0,
                    "endLineIndex": 1, "windowStartMs": 0, "windowEndMs": native}]}
            mapped = _project_endpoint_eof(raw_plan, canonical)
            self.assertEqual(mapped['resolvedSegments'][0]['windowEndMs'], canonical)
            self.assertEqual(raw_plan['resolvedSegments'][0]['windowEndMs'], native)
        raw["lines"][0]["words"][0]["startMs"] = canonical
        with self.assertRaisesRegex(ValueError, "collapses"):
            _project_endpoint_eof(raw, canonical)

    def test_native_mms_and_verifier_eof_reach_canonical_public_time(self):
        import copy
        import stemwise_engine.lyrics_align_pc as worker
        import stemwise_engine.alignment_audio as audio_store
        canonical, native = 30000, 30001
        original = plain_original('last')
        mapping = build_lexical_occurrence_mapping('abcdefghijk', original, (0,))
        occurrence = mapping['performedOccurrences'][0]
        segments = [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
            'windowStartMs': 0, 'windowEndMs': native}]
        raw = {'durationMs': native, 'route': WAV2VEC2_ROUTE, 'resolvedSegments': segments,
            'lines': [{'lineId': occurrence['occurrenceId'], 'startMs': 29000, 'endMs': native,
                'words': [{'wordId': occurrence['words'][0]['occurrenceWordId'],
                    'startMs': 29000, 'endMs': native}]}]}
        identity = {'stream': {'sampleRate': 48000, 'channelCount': 2,
            'frameCount': native * 48, 'durationMs': native}}
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            root = Path(directory)
            project = root/'project'
            prepare_runtime_layout(project)
            for path in (project/'.venv/bin/python', project/'src/mms_forced_alignment.py',
                    root/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th',
                    root/'model-cache/torch/hub/checkpoints/model.pt'):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture')
            before = copy.deepcopy(raw)
            with patch.object(worker, '_mms_runtime_provenance', return_value={}), \
                    patch.object(audio_store, 'read_audio_provenance', return_value={'audioViews': {'htdemucs-vocals': identity}}), \
                    patch.object(worker, '_forced_alignment_provenance', return_value={'audio': identity}), \
                    patch.object(worker, '_load_or_run_mms_alignment', return_value=(raw, True)) as load:
                arguments = dict(config={'projectDir': str(project)}, video_id='abcdefghijk',
                    original=original, alignment=SimpleNamespace(source_physical_indices=(0,)),
                    occurrence_mapping=mapping, duration_ms=canonical, request_duration_ms=native,
                    workspace=SimpleNamespace(job_dir=lambda _: root), temp_root=root, device='cpu', timeout_seconds=1,
                    segments=segments)
                projected, reused = worker._load_final_mms_alignment(**arguments)
                self.assertTrue(reused)
                self.assertEqual(load.call_args.kwargs['duration_ms'], native)
                self.assertEqual(projected['lines'][0]['endMs'], canonical)
                self.assertEqual(projected['lines'][0]['words'][0]['endMs'], canonical)
                self.assertEqual(projected['resolvedSegments'], segments)
                candidates = _forced_alignment_verifier_candidates(raw, [display_words('last')],
                    expected_route=WAV2VEC2_ROUTE, duration_ms=canonical)
                self.assertEqual(candidates[0]['endMs'], canonical)
                self.assertEqual(candidates[0]['words'][0]['endMs'], canonical)
                self.assertEqual(raw, before)
                raw['lines'][0]['endMs'] = canonical + 2
                with self.assertRaises(ValueError):
                    worker._load_final_mms_alignment(**arguments)
                with self.assertRaises(ValueError):
                    _forced_alignment_verifier_candidates(raw, [display_words('last')],
                        expected_route=WAV2VEC2_ROUTE, duration_ms=canonical)

    def test_mms_repair_plans_use_requested_mix_time_while_public_endpoints_use_original(self):
        import copy
        import stemwise_engine.lyrics_align_pc as worker
        import stemwise_engine.alignment_audio as audio_store
        original = plain_original('early\nlast')
        mapping = build_lexical_occurrence_mapping('abcdefghijk', original, (0, 1))
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            root = Path(directory)
            project = root/'project'
            prepare_runtime_layout(project)
            for path in (project/'.venv/bin/python', project/'src/mms_forced_alignment.py',
                    root/'model-cache/torch/hub/checkpoints/955717e8-8726e21a.th',
                    root/'model-cache/torch/hub/checkpoints/model.pt'):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'fixture')
            for canonical, mix, native in ((30000, 30000, 29999), (30000, 30000, 30000),
                    (30000, 30000, 30001), (30000, 30001, 30001), (30000, 30001, 30000),
                    (30001, 30000, 30000), (30001, 30000, 30001),
                    (30000, 30001, 30002), (30002, 30001, 30000),
                    (29998, 30000, 29999), (30002, 30000, 30001)):
                with self.subTest(original=canonical, mix=mix, native=native):
                    segments = [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                        'windowStartMs': 0, 'windowEndMs': 15000},
                        {'segmentIndex': 1, 'startLineIndex': 1, 'endLineIndex': 2,
                         'windowStartMs': 15000, 'windowEndMs': mix}]
                    native_segments = [dict(segments[0]), {**segments[1], 'windowEndMs': native}]
                    raw = {'durationMs': native, 'resolvedSegments': native_segments, 'lines': [
                        {'lineId': occurrence['occurrenceId'], 'startMs': start, 'endMs': end,
                         'words': [{'wordId': occurrence['words'][0]['occurrenceWordId'],
                                    'startMs': start, 'endMs': end}]}
                        for occurrence, start, end in zip(mapping['performedOccurrences'],
                            (1000, 29000), (10000, native))]}
                    identity = {'stream': {'sampleRate': 48000, 'channelCount': 2,
                        'frameCount': native*48, 'durationMs': native}}
                    before = copy.deepcopy(raw)
                    with patch.object(worker, '_mms_runtime_provenance', return_value={}), \
                            patch.object(audio_store, 'read_audio_provenance', return_value={
                                'audioViews': {'htdemucs-vocals': identity}}), \
                            patch.object(worker, '_forced_alignment_provenance', return_value={'audio': identity}), \
                            patch.object(worker, '_load_or_run_mms_alignment', return_value=(raw, True)) as load:
                        projected, reused = worker._load_final_mms_alignment(
                            config={'projectDir': str(project)}, video_id='abcdefghijk', original=original,
                            alignment=SimpleNamespace(source_physical_indices=(0, 1)), occurrence_mapping=mapping,
                            duration_ms=canonical, request_duration_ms=mix, workspace=SimpleNamespace(job_dir=lambda _: root),
                            temp_root=root, device='cpu', timeout_seconds=1, segments=segments)
                    self.assertTrue(reused)
                    self.assertEqual(load.call_args.kwargs['duration_ms'], native)
                    self.assertEqual(load.call_args.kwargs['segments'], native_segments)
                    self.assertEqual(projected['resolvedSegments'], segments)
                    self.assertEqual(projected['lines'][0], raw['lines'][0])
                    self.assertEqual(projected['lines'][1]['endMs'], min(native, canonical))
                    self.assertEqual(projected['lines'][1]['words'][0]['endMs'], min(native, canonical))
                    self.assertEqual(raw, before)

    def test_mapping_hash_invalidates_and_numeric_artifact_stays_text_free(self) -> None:
        original = {
            "id": "original",
            "kind": "original",
            "language": "en",
            "script": "Latn",
            "format": "plain",
            "text": "alpha beta\ngamma delta",
        }
        mapping = build_lexical_occurrence_mapping(
            "KbKqkR3Q6SA", original, (0, 1)
        )
        identity = lexical_mapping_identity(mapping)
        changed = dict(original, text="alpha beta\ngamma omega")
        reordered = build_lexical_occurrence_mapping(
            "KbKqkR3Q6SA", original, (1, 0)
        )
        self.assertNotEqual(
            identity["lexicalAuthoritySha256"],
            lexical_mapping_identity(
                build_lexical_occurrence_mapping("KbKqkR3Q6SA", changed, (0, 1))
            )["lexicalAuthoritySha256"],
        )
        self.assertNotEqual(
            identity["mappingSha256"],
            lexical_mapping_identity(reordered)["mappingSha256"],
        )

        result = accepted_result((0, 1), starts=(100, 1_000))
        with tempfile.TemporaryDirectory() as name:
            workspace = AlignmentWorkspace(Path(name))
            saved = save_alignment_artifacts(
                workspace,
                "KbKqkR3Q6SA",
                candidate=None,
                result=result,
                lexical_mapping=mapping,
                public_line_timings=[
                    {"startMs": 100, "endMs": 900},
                    {"startMs": 1_000, "endMs": 1_800},
                ],
            )
            artifact = json.loads(
                workspace.mapping_path("KbKqkR3Q6SA").read_text(encoding="utf-8")
            )
        self.assertEqual(saved["mappingSha256"], identity["mappingSha256"])
        self.assertEqual(artifact["numeric"]["startsMs"], [100, 1_000])
        self.assertEqual(
            artifact["numeric"]["publicLineTimings"][1]["endMs"], 1_800
        )
        self.assertNotIn("alpha", json.dumps(artifact))
        self.assertRegex(saved["mappingArtifactSha256"], r"^[0-9a-f]{64}$")
        structure = _structure_candidate_diagnostics(
            "KbKqkR3Q6SA",
            original,
            accepted_result((0, 1)),
        )
        self.assertEqual(
            [item["sourcePhysicalIndex"] for item in structure["performedOccurrences"]],
            [0, 1],
        )
        self.assertEqual(
            [item["startMs"] for item in structure["performedOccurrences"]],
            [1_000, 2_000],
        )
        self.assertEqual(set(structure), {"performedOccurrences"})
        self.assertNotIn("alpha", json.dumps(structure).lower())

    def test_anchored_retry_fills_only_baseline_unresolved_lines(self) -> None:
        def occurrence(index: int, result: str) -> dict[str, object]:
            decision = {"result": result}
            if result == "unresolved":
                decision["code"] = "line-start-disagreement"
            return {
                "occurrenceId": f"occurrence-{'1' * 23}{index}",
                "performedIndex": index,
                "sourcePhysicalIndex": index,
                "familyCandidates": {
                    "mms-fa": {
                        "route": "fixed-mms-route",
                        "startMs": 100 + index * 1_000,
                        "endMs": 900 + index * 1_000,
                        "confidence": 0.8,
                        "words": [],
                    },
                    "language-wav2vec2": {
                        "route": WAV2VEC2_ROUTE,
                        "startMs": 120 + index * 1_000,
                        "endMs": 880 + index * 1_000,
                    },
                },
                "publishDecision": decision,
                **({"selectedPublisherFamily": "mms-fa"} if result != "unresolved" else {}),
            }

        baseline_timings = [
            {"startMs": 100, "endMs": 900},
            {"startMs": 1_100, "endMs": 1_900},
        ]
        anchored_timings = [
            {"startMs": 130, "endMs": 880, "words": []},
            {"startMs": 1_130, "endMs": 1_880, "words": []},
        ]
        baseline = {
            "lineCount": 2,
            "lineReadyCount": 1,
            "reviewedLrcStartEvidenceCount": 0,
            "reviewedLrcAmbiguousOccurrenceCount": 0,
            "occurrenceCandidates": [
                occurrence(0, "words"),
                occurrence(1, "unresolved"),
            ],
        }
        anchored = {
            "lineCount": 2,
            "occurrenceCandidates": [
                occurrence(0, "words"),
                occurrence(1, "words"),
            ],
        }

        baseline_result = (baseline_timings, baseline)
        published, summary = _merge_endpoint_passes(
            baseline_result,
            (anchored_timings, anchored),
            stability_family=WAV2VEC2_FAMILY,
        )

        self.assertEqual(summary["resultClass"], "committed-words")
        self.assertEqual(summary["baselinePublishedLineCount"], 1)
        self.assertEqual(summary["anchoredFilledLineCount"], 1)
        self.assertIs(published[0], baseline_timings[0])
        self.assertIs(published[1], anchored_timings[1])
        low_support = json.loads(json.dumps(baseline))
        low_support["occurrenceCandidates"][1]["publishDecision"][
            "code"
        ] = "low-forced-line-support"
        rejected, rejected_summary = _merge_endpoint_passes(
            (baseline_timings, low_support),
            (anchored_timings, anchored),
            stability_family=WAV2VEC2_FAMILY,
        )
        self.assertEqual(rejected, baseline_timings)
        self.assertEqual(rejected_summary["resultClass"], "line-unresolved")
        self.assertEqual(rejected_summary["lineReadyCount"], 1)
        self.assertEqual(rejected_summary["occurrenceCandidates"][0]["publishDecision"], {"result": "words"})
        self.assertEqual(rejected_summary["occurrenceCandidates"][1]["publishDecision"],
                         {"result": "unresolved", "code": "low-forced-line-support"})
        self.assertNotIn("selectedPublisherFamily", rejected_summary["occurrenceCandidates"][1])
        self.assertEqual(rejected_summary["anchoredFilledLineCount"], 0)
        retained, retained_summary = _merge_endpoint_passes(
            (baseline_timings, low_support),
            (anchored_timings, anchored),
            stability_family=WAV2VEC2_FAMILY,
            include_unresolved=True,
        )
        self.assertEqual(retained, baseline_timings)
        self.assertEqual(retained_summary["resultClass"], "line-unresolved")
        cumulative, cumulative_summary = _merge_endpoint_route_passes(
            (retained, retained_summary),
            (anchored_timings, anchored),
        )
        self.assertEqual(len(cumulative), 2)
        self.assertNotEqual(cumulative_summary["resultClass"], "line-unresolved")

        unstable = json.loads(json.dumps(anchored))
        unstable["occurrenceCandidates"][1]["familyCandidates"][
            WAV2VEC2_FAMILY
        ]["startMs"] += WORD_START_AGREEMENT_MS + 1
        rejected, rejected_summary = _merge_endpoint_passes(
            baseline_result,
            (anchored_timings, unstable),
            stability_family=WAV2VEC2_FAMILY,
        )
        self.assertEqual(rejected, baseline_timings)
        self.assertEqual(rejected_summary["resultClass"], "line-unresolved")
        self.assertEqual(rejected_summary["lineReadyCount"], 1)
        self.assertEqual(rejected_summary["occurrenceCandidates"][1]["publishDecision"],
                         {"result": "unresolved", "code": "line-start-disagreement"})
        self.assertEqual(rejected_summary["anchoredFilledLineCount"], 0)

        contradiction = json.loads(json.dumps(baseline))
        contradiction["occurrenceCandidates"][1]["publishDecision"]["code"] = "qualified-endpoint-contradiction"
        recovered, recovered_summary = _merge_endpoint_passes((baseline_timings, contradiction),
            (anchored_timings, anchored), stability_family=WAV2VEC2_FAMILY)
        self.assertEqual(recovered, [baseline_timings[0], anchored_timings[1]])
        self.assertEqual(recovered_summary["anchoredFilledLineCount"], 1)
        rejected, rejected_summary = _merge_endpoint_passes((baseline_timings, contradiction),
            (anchored_timings, unstable), stability_family=WAV2VEC2_FAMILY)
        self.assertEqual(rejected, baseline_timings)
        self.assertEqual(rejected_summary["anchoredFilledLineCount"], 0)
        self.assertEqual(rejected_summary["occurrenceCandidates"][1]["publishDecision"],
                         {"result": "unresolved", "code": "qualified-endpoint-contradiction"})
        self.assertNotIn("selectedPublisherFamily", rejected_summary["occurrenceCandidates"][1])

        with self.assertRaisesRegex(ValueError, "globally ordered"):
            _merge_endpoint_passes(
                baseline_result,
                (
                    [
                        anchored_timings[0],
                        {"startMs": 800, "endMs": 1_880, "words": []},
                    ],
                    anchored,
                ),
            )

    def test_endpoint_routes_can_contribute_distinct_qualified_lines(self) -> None:
        def occurrence(index: int, ready: bool, route: str) -> dict[str, object]:
            decision = {"result": "line-only" if ready else "unresolved"}
            decision["code"] = (
                "qualified-line-only" if ready else "line-end-disagreement"
            )
            return {
                "occurrenceId": f"occurrence-{'2' * 23}{index}",
                "performedIndex": index,
                "sourcePhysicalIndex": index,
                "familyCandidates": {
                    "mms-fa": {
                        "route": route,
                        "startMs": index * 1_000,
                        "endMs": index * 1_000 + 800,
                        "confidence": 0.8,
                        "words": [],
                    }
                },
                "publishDecision": decision,
                **({"selectedPublisherFamily": "mms-fa"} if ready else {}),
            }

        timings = [
            {"startMs": 0, "endMs": 800},
            {"startMs": 1_000, "endMs": 1_800},
        ]
        first = {
            "lineCount": 2,
            "reviewedLrcStartEvidenceCount": 0,
            "reviewedLrcAmbiguousOccurrenceCount": 0,
            "occurrenceCandidates": [
                occurrence(0, True, "first"),
                occurrence(1, False, "first"),
            ],
        }
        second = {
            "lineCount": 2,
            "reviewedLrcStartEvidenceCount": 0,
            "reviewedLrcAmbiguousOccurrenceCount": 0,
            "occurrenceCandidates": [
                occurrence(0, False, "second"),
                occurrence(1, True, "second"),
            ],
        }
        merged, summary = _merge_endpoint_route_passes(
            (timings, first), (timings, second)
        )
        self.assertEqual(merged, timings)
        self.assertEqual(summary["resultClass"], "committed-lines")
        self.assertEqual(summary["lineReadyCount"], 2)
        self.assertEqual(summary["cumulativeRouteFilledLineCount"], 1)
        self.assertEqual(
            summary["occurrenceCandidates"][1]["familyCandidates"]["mms-fa"][
                "route"
            ],
            "second",
        )



    def test_bootstrap_reruns_both_endpoint_families_in_identical_windows(self) -> None:
        self._check_bootstrap_after_consensus(False)

    def test_qualified_consensus_fences_recover_a_misplaced_mms_occurrence(self):
        occurrences = []
        for i, (start, end) in enumerate(((1000, 2000), (3000, 3800), (5000, 6000))):
            occurrences.append({"occurrenceId": f"occurrence-{i}", "performedIndex": i,
                "sourcePhysicalIndex": i, "familyCandidates": {
                    "mms-fa": {"startMs": 9000+i*2000, "endMs": 10000+i*2000, "confidence": .01},
                    WAV2VEC2_FAMILY: {"startMs": start, "endMs": end},
                    OWSM_CTC_FAMILY: {"startMs": start+20, "endMs": end+20}},
                "publishDecision": {"result": "unresolved", "code": "low-forced-line-support"}
                    if i == 1 else {"result": "line-only", "code": "independent-endpoint-consensus-line-only"},
                **({"selectedPublisherFamily": WAV2VEC2_FAMILY,
                    "supportingEndpointFamilies": [WAV2VEC2_FAMILY, OWSM_CTC_FAMILY]} if i != 1 else {})})
        before = json.loads(json.dumps(occurrences))
        raw_mms = tuple(c["familyCandidates"]["mms-fa"] for c in occurrences)
        verifier = tuple(c["familyCandidates"][WAV2VEC2_FAMILY] for c in occurrences)
        segments = _build_bootstrap_segments(3, 16000, raw_mms, verifier,
                                             qualified_occurrences=occurrences)
        self.assertEqual(segments, [
            {"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 1, "windowStartMs": 0, "windowEndMs": 2510},
            {"segmentIndex": 1, "startLineIndex": 1, "endLineIndex": 2, "windowStartMs": 2510, "windowEndMs": 4410},
            {"segmentIndex": 2, "startLineIndex": 2, "endLineIndex": 3, "windowStartMs": 4410, "windowEndMs": 16000}])
        timings = [{"startMs": s, "endMs": e} for s, e in ((1000, 2000), (3000, 3800), (5000, 6000))]
        summary = {"lineCount": 3, "lineReadyCount": 2, "reviewedLrcStartEvidenceCount": 0,
                   "reviewedLrcAmbiguousOccurrenceCount": 0, "occurrenceCandidates": occurrences}
        recovered = json.loads(json.dumps(summary))
        target = recovered["occurrenceCandidates"][1]
        target.update(publishDecision={"result": "line-only", "code": "word-end-disagreement"},
                      selectedPublisherFamily="mms-fa")
        target["familyCandidates"]["mms-fa"].update(startMs=3050, endMs=3850, confidence=.9)
        target["familyCandidates"][WAV2VEC2_FAMILY].update(startMs=3010, endMs=3830)
        anchored_timings = [*timings[:1], {"startMs": 3050, "endMs": 3850}, *timings[2:]]
        args = ((timings, summary), (anchored_timings, recovered))
        output, result = _merge_endpoint_passes(*args, stability_family=WAV2VEC2_FAMILY,
                                               repair_windows=segments, include_unresolved=True)
        self.assertEqual(result["anchoredFilledLineCount"], 1)
        self.assertEqual(output, anchored_timings)
        self.assertEqual(output[0], timings[0])
        self.assertEqual(output[2], timings[2])
        for index in (0, 2):
            self.assertEqual(result["occurrenceCandidates"][index]["publishDecision"], occurrences[index]["publishDecision"])
            self.assertEqual(result["occurrenceCandidates"][index]["selectedPublisherFamily"], WAV2VEC2_FAMILY)
        self.assertEqual(occurrences, before)
        # A newly qualified contradictory candidate must still revoke the outer
        # line's authority; low-confidence planning hypotheses above cannot do so.
        conflicting = json.loads(json.dumps(recovered))
        conflicting["occurrenceCandidates"][0]["familyCandidates"]["mms-fa"]["confidence"] = .9
        _, contradicted = _merge_endpoint_passes((timings, summary), (anchored_timings, conflicting),
            stability_family=WAV2VEC2_FAMILY, repair_windows=segments, include_unresolved=True)
        revoked = contradicted["occurrenceCandidates"][0]
        self.assertEqual(revoked["publishDecision"],
                         {"result": "unresolved", "code": "qualified-endpoint-contradiction"})
        self.assertNotIn("selectedPublisherFamily", revoked)
        self.assertEqual(occurrences, before)
        # No fence, an unstable verifier, a clipped word or an in-window shift
        # must not excuse the original MMS movement guard.
        for fault in ("missing-window", "verifier", "clipped", "within-window"):
            base = json.loads(json.dumps(summary))
            other = json.loads(json.dumps(recovered))
            if fault == "verifier": other["occurrenceCandidates"][1]["familyCandidates"][WAV2VEC2_FAMILY]["startMs"] = 3401
            if fault == "clipped": other["occurrenceCandidates"][1]["familyCandidates"]["mms-fa"]["endMs"] = 4300
            if fault == "within-window": base["occurrenceCandidates"][1]["familyCandidates"]["mms-fa"].update(startMs=2600, endMs=3800)
            _, rejected = _merge_endpoint_passes((timings, base), (anchored_timings, other),
                stability_family=WAV2VEC2_FAMILY, repair_windows=None if fault == "missing-window" else segments,
                include_unresolved=True)
            self.assertEqual(rejected["anchoredFilledLineCount"], 0, fault)
        unresolved = json.loads(json.dumps(occurrences))
        for c in unresolved: c["publishDecision"] = {"result": "unresolved", "code": "line-start-disagreement"}
        self.assertIsNone(_build_bootstrap_segments(3, 16000, raw_mms, verifier,
                                                  qualified_occurrences=unresolved))

    def test_unresolved_anchor_retains_corrected_candidate_without_qualifying_it(self):
        occurrences = []
        for i, (start, end) in enumerate(((1000, 2000), (3000, 3800), (5000, 6000))):
            occurrences.append({"occurrenceId": f"occurrence-{i}", "performedIndex": i,
                "sourcePhysicalIndex": i, "familyCandidates": {
                    "mms-fa": {"startMs": start, "endMs": end, "confidence": .8, "words": []},
                    WAV2VEC2_FAMILY: {"startMs": start, "endMs": end}},
                "publishDecision": {"result": "unresolved", "code": "line-start-disagreement"}
                    if i == 1 else {"result": "line-only", "code": "word-end-disagreement"},
                **({"selectedPublisherFamily": "mms-fa"} if i != 1 else {})})
        occurrences[1]["familyCandidates"]["mms-fa"].update(startMs=11000, endMs=12000)
        occurrences[1]["familyCandidates"][WAV2VEC2_FAMILY]["endMs"] = 3630
        summary = {"lineCount": 3, "lineReadyCount": 2, "reviewedLrcStartEvidenceCount": 0,
                   "reviewedLrcAmbiguousOccurrenceCount": 0, "occurrenceCandidates": occurrences}
        before = json.loads(json.dumps(summary))
        recovered = json.loads(json.dumps(summary))
        target = recovered["occurrenceCandidates"][1]
        target["publishDecision"]["code"] = "line-end-disagreement"
        target["familyCandidates"]["mms-fa"].update(startMs=3050, endMs=4100)
        target["familyCandidates"][WAV2VEC2_FAMILY]["startMs"] = 3010
        timings = [{"startMs": s, "endMs": e} for s, e in ((1000, 2000), (3000, 3800), (5000, 6000))]
        windows = [{"segmentIndex": i, "startLineIndex": i, "endLineIndex": i+1,
                    "windowStartMs": start, "windowEndMs": end}
                   for i, (start, end) in enumerate(((0, 2510), (2510, 4410), (4410, 16000)))]
        output, result = _merge_endpoint_passes((timings, summary), (timings, recovered),
            stability_family=WAV2VEC2_FAMILY, repair_windows=windows, include_unresolved=True)
        self.assertEqual(result["lineReadyCount"], 2)
        self.assertEqual(result["anchoredFilledLineCount"], 0)
        pending = result["occurrenceCandidates"][1]
        self.assertEqual(pending["publishDecision"]["result"], "unresolved")
        self.assertNotIn("selectedPublisherFamily", pending)
        self.assertEqual(pending["familyCandidates"]["mms-fa"], target["familyCandidates"]["mms-fa"])
        self.assertIs(output[0], timings[0])
        self.assertIs(output[2], timings[2])
        self.assertEqual(summary, before)
        routed, route_summary = _merge_endpoint_route_passes((timings, summary), (output, result))
        self.assertEqual(route_summary["lineReadyCount"], 2)
        self.assertEqual(route_summary["cumulativeRouteFilledLineCount"], 0)
        self.assertEqual(route_summary["occurrenceCandidates"][1]["familyCandidates"], target["familyCandidates"])
        self.assertIs(routed[0], timings[0])
        self.assertIs(routed[2], timings[2])
        for fault in ("clipped", "unstable-verifier", "unsafe-identity", "low-support"):
            other = json.loads(json.dumps(recovered))
            bad = other["occurrenceCandidates"][1]
            if fault == "clipped": bad["familyCandidates"]["mms-fa"]["endMs"] = 4300
            elif fault == "unstable-verifier": bad["familyCandidates"][WAV2VEC2_FAMILY]["startMs"] = 3401
            elif fault == "unsafe-identity": bad["publishDecision"]["code"] = "unsafe-occurrence-identity"
            else: bad["familyCandidates"]["mms-fa"]["confidence"] = .09
            _, rejected = _merge_endpoint_passes((timings, summary), (timings, other),
                stability_family=WAV2VEC2_FAMILY, repair_windows=windows, include_unresolved=True)
            self.assertEqual(rejected["occurrenceCandidates"][1], occurrences[1], fault)

        for fault in ("no-right-fence", "unstable-verifier", "unsafe-identity", "low-support", "missing-qualified-neighbor", "supporting-family-veto"):
            base, other = json.loads(json.dumps(summary)), json.loads(json.dumps(recovered))
            bad = other["occurrenceCandidates"][1]
            if fault == "no-right-fence": bad["familyCandidates"]["mms-fa"]["endMs"] = 4801
            elif fault == "unstable-verifier": bad["familyCandidates"][WAV2VEC2_FAMILY]["startMs"] = 3401
            elif fault == "unsafe-identity": bad["publishDecision"]["code"] = "unsafe-occurrence-identity"
            elif fault == "low-support": bad["familyCandidates"]["mms-fa"]["confidence"] = .09
            elif fault == "missing-qualified-neighbor":
                for state in (base, other):
                    state["lineReadyCount"] = 1
                    state["occurrenceCandidates"][2]["publishDecision"] = {"result": "unresolved", "code": "line-end-disagreement"}
                    state["occurrenceCandidates"][2].pop("selectedPublisherFamily")
            else:
                for state in (base, other):
                    neighbor = state["occurrenceCandidates"][0]
                    for family in ("mms-fa", WAV2VEC2_FAMILY): neighbor["familyCandidates"][family]["endMs"] = 1500
                    neighbor["familyCandidates"][OWSM_CTC_FAMILY] = {"startMs": 1000, "endMs": 1900}
                    neighbor["supportingEndpointFamilies"] = ["mms-fa", OWSM_CTC_FAMILY]
                bad["publishDecision"]["code"] = "line-start-disagreement"
                bad["familyCandidates"]["mms-fa"]["startMs"] = 2500
                bad["familyCandidates"][OWSM_CTC_FAMILY] = {"startMs": 2300, "endMs": 3600}
            _, rejected = _merge_endpoint_route_passes((timings, base), (timings, other))
            self.assertEqual(rejected["occurrenceCandidates"][1]["familyCandidates"],
                             base["occurrenceCandidates"][1]["familyCandidates"], fault)

    def test_partial_independent_consensus_does_not_skip_remaining_bootstrap(self) -> None:
        self._check_bootstrap_after_consensus(True)

    def test_mms_guard_failure_widens_and_reruns_both_endpoint_families(self):
        self._check_bootstrap_after_consensus(False, mms_guard_failure=True)

    def test_repaired_endpoint_pair_never_refines_after_partner_failure(self):
        for family in ('verifier', 'mms'):
            with self.subTest(family=family):
                self._check_bootstrap_after_consensus(False, repair_failure=family)

    def test_first_anchored_verifier_failure_keeps_baseline_and_original_diagnostic(self):
        self._check_bootstrap_after_consensus(False, bootstrap_failure=True)

    def test_anchored_recovery_rechecks_cached_independent_consensus(self):
        self._check_bootstrap_after_consensus(True, anchored_consensus=True)


    def test_unencodable_target_blocks_before_endpoint_recovery(self):
        self._check_bootstrap_after_consensus(False, unsupported_target=True)

    def test_unknown_occurrence_or_forced_support_blocks_before_bootstrap(self):
        for reason in ("unsafe-occurrence-identity", "low-forced-line-support"):
            with self.subTest(reason=reason):
                self._check_bootstrap_after_consensus(False,
                    identity_consensus=True, uncertain_reason=reason)

    def _check_bootstrap_after_consensus(self, partial_consensus: bool, *, mms_guard_failure: bool = False,
                                       anchored_consensus: bool = False,
                                       identity_consensus: bool = False, unsupported_target: bool = False,
                                       repair_failure: str | None = None, bootstrap_failure: bool = False,
                                       uncertain_reason: str = "unsafe-occurrence-identity") -> None:
        from stemwise_engine.lyrics_align_pc import Wav2Vec2TargetUnsupported
        verifier_family = WAV2VEC2_FAMILY
        video_id = "KbKqkR3Q6SA"
        text = "alpha beta gamma delta\nalpha beta gamma delta"
        lyrics = {"status": "partial", "reason": "Fixture timing pending",
                  "tracks": [{**plain_original(text), "source": {"name": "Fixture"}}]}
        alignment = accepted_result((0, 1))
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            sidecars = root / "owner"
            output = root / "runtime"
            sidecars.mkdir()
            output.mkdir()
            raw = json.dumps(lyrics).encode("utf-8")
            (sidecars / f"{video_id}.lyrics.json").write_bytes(raw)
            workspace = AlignmentWorkspace(root / "agent", workspace_root=root)
            evidence = write_recognition(workspace.job_dir(video_id), lyrics["tracks"][0], (0, 1))
            for language_hint in transcription_languages(lyrics["tracks"][0]):
                workspace.transcript_path(video_id, language_hint, "unprompted-mix").write_bytes(evidence.read_bytes())
            args = SimpleNamespace(
                from_position=None,
                to_position=None,
                model="large-v3",
                device="cpu",
                compute_type="float32",
                expected_mix_sha256="a" * 64,
                analysis_audio=root / "mix.wav",
                editorial_sha256=None,
                rerun_transcript=False,
                no_commit=True,
                realign_synced=False,
            )
            audio_provenance = {
                "mix": {"stream": {"sampleRate": 16_000, "frameCount": 160_000, "durationMs": 10_000}},
                "audioViews": {
                    "htdemucs-vocals": {
                        "routeId": "htdemucs-vocals",
                        "stream": {"sampleRate": 16_000, "frameCount": 160_000},
                    }
                },
            }
            baseline_timings = line_timings(alignment, 10_000)
            anchored_timings = line_timings(alignment, 10_000)

            def occurrence(index: int, result: str) -> dict[str, object]:
                decision = {"result": result}
                if result == "unresolved":
                    decision["code"] = "line-start-disagreement"
                return {
                    "occurrenceId": f"occurrence-{index}",
                    "performedIndex": index,
                    "sourcePhysicalIndex": index,
                    "familyCandidates": {
                        "mms-fa": {
                            "startMs": (1_000, 3_000)[index],
                            "endMs": (1_999, 9_000)[index],
                            "words": [],
                        },
                        verifier_family: {
                            "startMs": (1_000, 3_000)[index],
                            "endMs": (1_999, 9_000)[index],
                            "words": [],
                        },
                    },
                    "publishDecision": decision,
                    **({"selectedPublisherFamily": "mms-fa"} if result != "unresolved" else {}),
                }

            baseline_summary = {
                "resultClass": "line-unresolved",
                "lineCount": 2,
                "lineReadyCount": 0,
                "reviewedLrcStartEvidenceCount": 0,
                "reviewedLrcAmbiguousOccurrenceCount": 0,
                "occurrenceCandidates": [
                    occurrence(0, "unresolved"),
                    occurrence(1, "unresolved"),
                ],
            }
            anchored_summary = {
                "resultClass": "committed-words",
                "lineCount": 2,
                "lineReadyCount": 2,
                "reviewedLrcStartEvidenceCount": 0,
                "reviewedLrcAmbiguousOccurrenceCount": 0,
                "occurrenceCandidates": [
                    occurrence(0, "words"),
                    occurrence(1, "words"),
                ],
            }
            owsm_candidates = ({"route": "owsm", "startMs": 1020, "endMs": 2020}, None)
            if identity_consensus:
                baseline_timings = anchored_timings = [
                    {"startMs": 1000, "endMs": 1999}, {"startMs": 3000, "endMs": 9000}]
                for item in baseline_summary["occurrenceCandidates"]:
                    item["publishDecision"]["code"] = uncertain_reason
                owsm_candidates = (owsm_candidates[0], {"route": "owsm", "startMs": 3020, "endMs": 9020})
            if partial_consensus:
                baseline_timings = [{"startMs": 1000, "endMs": 1999}, {"startMs": 3000, "endMs": 9000}]
                anchored_timings = [{"startMs": 1000, "endMs": 1999}, {"startMs": 3100, "endMs": 9100}]
                for index, delta in enumerate((0, 500)):
                    candidate = baseline_summary["occurrenceCandidates"][index]["familyCandidates"][WAV2VEC2_FAMILY]
                    candidate.update(startMs=candidate["startMs"]+delta, endMs=candidate["endMs"]+delta)
                anchored_summary["occurrenceCandidates"][0] = json.loads(json.dumps(baseline_summary["occurrenceCandidates"][0]))
                second = anchored_summary["occurrenceCandidates"][1]
                second["publishDecision"] = {"result": "line-only", "code": "word-end-disagreement"}
                second["familyCandidates"]["mms-fa"].update(startMs=3100, endMs=9100)
                second["familyCandidates"][WAV2VEC2_FAMILY].update(startMs=3300, endMs=9300)
            if anchored_consensus:
                # The repaired MMS/verifier pair now agrees with retained OWSM evidence.
                # Neither baseline candidate pair qualified the second occurrence.
                owsm_candidates = (owsm_candidates[0], {"route": "owsm", "startMs": 3100, "endMs": 9600})
                baseline_summary["occurrenceCandidates"][1]["familyCandidates"][WAV2VEC2_FAMILY].update(
                    startMs=3500, endMs=9000)
                second = anchored_summary["occurrenceCandidates"][1]
                second["familyCandidates"]["mms-fa"].update(startMs=3100, endMs=9300)
                second["familyCandidates"][WAV2VEC2_FAMILY].update(startMs=3300, endMs=9300)
                second["publishDecision"] = {"result": "unresolved", "code": "line-start-disagreement"}
                anchored_timings[1] = {"startMs": 3100, "endMs": 9300}
                anchored_summary.update(resultClass="line-unresolved", lineReadyCount=0)
            if partial_consensus:
                # These orchestration fixtures must obey current occurrence/word ownership.
                # The numeric producer remains inert; no empty/fabricated identity grants recovery.
                owners = build_lexical_occurrence_mapping(video_id, lyrics['tracks'][0], (0, 1))['performedOccurrences']
                ranges = display_words(text.splitlines()[0])
                for summary in (baseline_summary, anchored_summary):
                    for owner, row in zip(owners, summary['occurrenceCandidates']):
                        row['occurrenceId'] = owner['occurrenceId']
                        for candidate in row['familyCandidates'].values():
                            candidate['confidence'] = .8
                            candidate['words'] = [{'occurrenceWordId': word['occurrenceWordId'],
                                'startUtf16': start, 'endUtf16': end,
                                'startMs': candidate['startMs']+i*100,
                                'endMs': candidate['startMs']+i*100+80,
                                'confidence': .8, 'acousticTokenCount': 1}
                                for i,(word,(start,end,_)) in enumerate(zip(owner['words'], ranges))]
            expected_timings, expected_evidence = _merge_endpoint_passes(
                (baseline_timings, json.loads(json.dumps(baseline_summary))),
                (anchored_timings, json.loads(json.dumps(anchored_summary))),
                stability_family=WAV2VEC2_FAMILY,
            )
            expected_evidence.update(
                mixVerifierPublishedLineCount=0,
                routeLocalVerifierAttempted=True,
                routeLocalVerifierFilledLineCount=0,
            )
            model_identity = {
                "modelSha256": "b" * 64,
                "snapshotManifestSha256": "c" * 64,
            }
            verifier_calls, mms_calls = [], []
            def repair_verifier(**kwargs):
                verifier_calls.append(kwargs)
                if len(verifier_calls) == 4 and repair_failure == 'verifier':
                    raise WorkerProcessError('synthetic unavailable', 'process-exit')
                return {}, False, model_identity
            def repair_mms(**kwargs):
                mms_calls.append(kwargs)
                if len(mms_calls) == 3:
                    raise WorkerProcessError('synthetic unavailable', 'process-exit')
                return {'lines': [{'startMs': 1000, 'endMs': 1999}, {'startMs': 3000, 'endMs': 9000}],
                    **({'resolvedSegments': [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 2,
                        'windowStartMs': 0, 'windowEndMs': 10000}]} if len(mms_calls) == 2 else {})}, False
            with (
                # The new unresolved-consensus case isolates the primary route.
                patch('stemwise_engine.alignment_endpoints.ENDPOINT_MMS_ROUTES',
                      ("htdemucs-vocals",) if partial_consensus or anchored_consensus or repair_failure or bootstrap_failure else ENDPOINT_MMS_ROUTES),
                patch('stemwise_engine.lyrics_align_pc._endpoint_verifier_family',
                      return_value=WAV2VEC2_FAMILY if unsupported_target else verifier_family),
                patch('stemwise_engine.lyrics_alignment.whisper_word_candidates', return_value=(
                    {"route": "faster-whisper-precision-unprompted:en", "lineId": "line-0", "words": (),
                     "startMs": 1000, "endMs": 1999},
                    {"route": "faster-whisper-precision-unprompted:en", "lineId": "line-1", "words": (),
                     "startMs": 3000, "endMs": 9000})),
                patch('stemwise_engine.lyrics_align_pc._load_or_run_owsm_ctc_alignment',
                      side_effect=None if partial_consensus or identity_consensus else WorkerProcessError("synthetic OWSM unavailable", "process-exit"),
                      return_value=(owsm_candidates, False)) as owsm,
                patch.multiple(
                    "stemwise_engine.lyrics_align_pc",
                    _verify_alignment_runtime=lambda *_a, **_k: {},
                    _verify_pinned_stack=lambda *_a, **_k: {},
                    _wav2vec2_model_assets=lambda *_a, **_k: (root / "model", {}),
                ),
                patch.multiple(
                    "stemwise_engine.alignment_audio",
                    write_audio_provenance=lambda *_a, **_k: audio_provenance,
                    read_audio_provenance=lambda *_a, **_k: audio_provenance,
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc.validate_child_zip',
                    return_value={"analysis": {"durationMs": 10_000}},
                ),
                patch('stemwise_engine.lyrics_align_pc._load_reviewed_lrc_starts', return_value={}),
                patch('stemwise_engine.lyrics_align_pc.internal_repeat_structure_kind', return_value="none"),
                patch('stemwise_engine.lyrics_align_pc._load_caption', return_value=None),
                patch(
                    'stemwise_engine.lyrics_align_pc._load_or_transcribe',
                    return_value=(transcript(text), {"type": "original-mix"}, False),
                ),
                patch('stemwise_engine.lyrics_align_pc.align_known_structure', return_value=alignment),
                patch(
                    'stemwise_engine.lyrics_align_pc._needs_additional_structural_evidence',
                    return_value=unsupported_target,
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc._load_or_run_wav2vec2_alignment',
                    side_effect=repair_verifier if repair_failure else ((Wav2Vec2TargetUnsupported("target vocabulary unsupported"),) if unsupported_target else (
                        ({}, False, model_identity),
                        ({}, False, model_identity),
                        WorkerProcessError("anchored verifier unavailable", "anchored-verifier-unavailable") if bootstrap_failure else
                        ({}, False, model_identity) if mms_guard_failure else WorkerProcessError(
                            "Wav2Vec2 forced alignment failed",
                            "wav2vec2-segment-boundary-guard:0:end",
                        ),
                        ({}, False, model_identity),
                    )),
                ) as wav2vec2,
                patch(
                    'stemwise_engine.lyrics_align_pc._forced_alignment_verifier_candidates',
                    return_value=(
                        {"startMs": 1_000, "endMs": 1_999},
                        {"startMs": 3_000, "endMs": 9_000},
                    ),
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc._load_final_mms_alignment',
                    side_effect=repair_mms if repair_failure else [
                        (
                            {
                                "lines": [
                                    {"startMs": 1_000, "endMs": 1_999},
                                    {"startMs": 3_000, "endMs": 9_000},
                                ]
                            },
                            False,
                        ),
                        *([WorkerProcessError("MMS forced alignment failed", "wav2vec2-segment-boundary-guard:0:end")]
                          if mms_guard_failure else []),
                        ({"lines": [
                            {"startMs": 1000, "endMs": 1999},
                            {"startMs": 3000, "endMs": 9000},
                        ]}, False),
                    ],
                ) as mms,
                patch(
                    'stemwise_engine.alignment_endpoints._refine_public_timings',
                    side_effect=(
                        (baseline_timings, baseline_summary),
                        *([(baseline_timings, json.loads(json.dumps(anchored_summary)))] if identity_consensus else
                          [(baseline_timings, json.loads(json.dumps(baseline_summary)))]),
                        (anchored_timings, anchored_summary),
                    ),
                ) as refine,
                patch('stemwise_engine.lyrics_align_pc.build_synced_sidecar', return_value=lyrics),
            ):
                report = _align(
                    {
                        "projectDir": str(root / "builder"),
                        "tempDir": str(root / "temp"),
                        "analysisTimeoutMinutes": 1,
                    },
                    args,
                    selected_override=[{"position": 1, "videoId": video_id}],
                    paths_override=(sidecars, output, root / "models", workspace),
                    temp_root_override=root / "attempt",
                )

            if unsupported_target or identity_consensus:
                self.assertEqual(report["results"][0]["status"], "lyrics-content-unresolved", report["results"][0])
                self.assertEqual(report["results"][0]["reasons"], [
                    "pronunciation-support-unavailable" if unsupported_target else
                    "content-occurrence-uncertain" if uncertain_reason == 'low-forced-line-support' else
                    "failure-not-confirmed-timing-only"])
                owsm.assert_not_called()
                if unsupported_target:
                    wav2vec2.assert_called_once()
                    mms.assert_not_called()
                    refine.assert_not_called()
                else:
                    wav2vec2.assert_called_once()
                    mms.assert_called_once()
                    refine.assert_called_once()
                self.assertFalse(workspace.candidate_path(video_id).exists())
                self.assertEqual((sidecars / f"{video_id}.lyrics.json").read_bytes(), raw)
                return
            if bootstrap_failure:
                self.assertEqual(refine.call_count, 2, report['results'][0])
                self.assertEqual(mms.call_count, 1)
                self.assertEqual(report['results'][0]['status'], 'retryable-technical', report['results'][0])
                state = json.loads(workspace.state_path(video_id).read_text(encoding='utf-8'))
                failures = state['routeFailures'] + state.get('recoveredRouteFailures', [])
                self.assertIn({'stage': 'endpoint-bootstrap', 'routeId': 'htdemucs-vocals:anchored-wav2vec2',
                               'code': 'anchored-verifier-unavailable'}, failures, state)
                self.assertEqual(len(failures), 2, failures)
                self.assertEqual(state['endpointEvidence']['occurrenceCandidates'], baseline_summary['occurrenceCandidates'])
                self.assertEqual((sidecars / f'{video_id}.lyrics.json').read_bytes(), raw)
                return
            if repair_failure:
                self.assertEqual(refine.call_count, 2, report['results'][0])
                self.assertEqual(len(verifier_calls), 4)
                self.assertEqual(len(mms_calls), 2 if repair_failure == 'verifier' else 3)
                self.assertEqual((sidecars / f'{video_id}.lyrics.json').read_bytes(), raw)
                self.assertEqual(report['results'][0]['status'], 'retryable-technical', report['results'][0])
                return
            if partial_consensus:
                self.assertIn("anchored", [call.kwargs["request_variant"] for call in mms.call_args_list],
                              "partial consensus must not suppress the remaining anchored recovery")
            self.assertEqual(
                report["results"][0]["status"], "line-unresolved", report["results"][0]
            )
            state = json.loads(workspace.state_path(video_id).read_text(encoding="utf-8"))
            mapping = json.loads(workspace.mapping_path(video_id).read_text(encoding="utf-8"))
            self.assertEqual(
                [call.kwargs["request_variant"] for call in wav2vec2.call_args_list],
                ["baseline", "baseline", "anchored", "anchored"],
            )
            self.assertEqual(
                [call.kwargs.get("audio_view_id", "mix") for call in wav2vec2.call_args_list],
                [
                    "mix",
                    "htdemucs-vocals",
                    "htdemucs-vocals",
                    "htdemucs-vocals",
                ],
            )
            self.assertEqual(
                [call.kwargs["request_variant"] for call in mms.call_args_list],
                ["baseline", "anchored", "anchored"] if mms_guard_failure else ["baseline", "anchored"],
            )
            mms_segments = mms.call_args_list[-1].kwargs["segments"]
            self.assertEqual(mms_segments, wav2vec2.call_args_list[-1].kwargs["segments"],
                             "both endpoint families must use the final widened windows")
            self.assertEqual(wav2vec2.call_args_list[1].kwargs["segments"], [
                {
                    "segmentIndex": 0,
                    "startLineIndex": 0,
                    "endLineIndex": 2,
                    "windowStartMs": 0,
                    "windowEndMs": 10_000,
                }
            ])
            self.assertEqual(wav2vec2.call_args_list[2].kwargs["segments"], [
                {"segmentIndex": 0, "startLineIndex": 0, "endLineIndex": 1,
                 "windowStartMs": 0, "windowEndMs": 2499},
                {"segmentIndex": 1, "startLineIndex": 1, "endLineIndex": 2,
                 "windowStartMs": 2499, "windowEndMs": 10000}])
            self.assertEqual(mms_segments, [{"segmentIndex": 0, "startLineIndex": 0,
                "endLineIndex": 2, "windowStartMs": 0, "windowEndMs": 10000}])
            owsm.assert_called_once()
            if anchored_consensus:
                self.assertEqual([{key: line[key] for key in ("startMs", "endMs")}
                    for line in mapping["numeric"]["publicLineTimings"]], [
                    {"startMs": 1000, "endMs": 1999}, {"startMs": 3100, "endMs": 9300}])
                evidence = state["endpointEvidence"]
                self.assertEqual(evidence["lineReadyCount"], 2)
                self.assertEqual(evidence["anchoredFilledLineCount"], 1)
                self.assertEqual(evidence["occurrenceCandidates"][1]["supportingEndpointFamilies"],
                                 ["mms-fa", WAV2VEC2_FAMILY, OWSM_CTC_FAMILY])
                self.assertEqual(evidence["occurrenceCandidates"][1]["publishDecision"]["result"], "line-only")
                self.assertEqual((sidecars / f"{video_id}.lyrics.json").read_bytes(), raw)
                return
            if partial_consensus:
                self.assertEqual([{key: line[key] for key in ("startMs", "endMs")}
                    for line in mapping["numeric"]["publicLineTimings"]], [
                    {"startMs": 1000, "endMs": 1999}, {"startMs": 3100, "endMs": 9100}])
                evidence = state["endpointEvidence"]
                self.assertEqual(evidence["lineReadyCount"], 2)
                self.assertEqual(evidence["anchoredFilledLineCount"], 1)
                self.assertEqual(evidence["occurrenceCandidates"][0]["supportingEndpointFamilies"],
                                 ["mms-fa", WAV2VEC2_FAMILY, OWSM_CTC_FAMILY])
                self.assertEqual((sidecars / f"{video_id}.lyrics.json").read_bytes(), raw)
                return
            self.assertEqual(state["endpointEvidence"], expected_evidence)
            self.assertEqual(mapping["numeric"]["publicLineTimings"], expected_timings)
            self.assertEqual(
                state["routeFailures"],
                [
                    {"stage": "endpoint-consensus",
                     "routeId": "owsm-ctc", "code": "process-exit"},
                ],
            )

    def test_endpoint_quality_failure_advances_to_the_next_vocal_view(self) -> None:
        self._check_endpoint_view_cascade()

    def test_closed_witnesses_bind_original_pcm_duration_not_resampled_duration(self):
        for frames, milliseconds in ((11820214, 246254), (15262198, 317962)):
            with self.subTest(frames=frames):
                self._check_endpoint_view_cascade((frames, milliseconds))

    def test_english_endpoint_recovery_uses_owsm_but_never_arabic_nemo(self):
        self._check_endpoint_view_cascade((480000, 10000), language="en")

    def _check_endpoint_view_cascade(self, native_pcm=None, language="ar"):
        video_id = "KbKqkR3Q6SA"
        text = "alpha beta gamma delta\none two three four"
        lyrics = {"status": "partial", "reason": "Fixture timing pending",
                  "tracks": [{**plain_original(text), "source": {"name": "Fixture"}}]}
        if native_pcm:
            lyrics["tracks"][0]["language"] = language
        alignment = accepted_result((0, 1))
        duration = native_pcm[1] if native_pcm else 10000
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            sidecars = root / "owner"
            output = root / "runtime"
            sidecars.mkdir()
            output.mkdir()
            raw = json.dumps(lyrics).encode("utf-8")
            (sidecars / f"{video_id}.lyrics.json").write_bytes(raw)
            workspace = AlignmentWorkspace(root / "agent", workspace_root=root)
            evidence = write_recognition(workspace.job_dir(video_id), lyrics["tracks"][0], (0, 1))
            for language_hint in transcription_languages(lyrics["tracks"][0]):
                workspace.transcript_path(video_id, language_hint, "unprompted-mix").write_bytes(evidence.read_bytes())
            args = SimpleNamespace(
                from_position=None,
                to_position=None,
                model="large-v3",
                device="cpu",
                compute_type="float32",
                expected_mix_sha256="a" * 64,
                analysis_audio=root / "mix.wav",
                editorial_sha256=None,
                rerun_transcript=False,
                no_commit=True,
                realign_synced=False,
            )
            audio_provenance = {
                "mix": {"stream": {"sampleRate": 16_000, "frameCount": 160_000, "durationMs": 10_000}},
                "audioViews": {"kimberley-jensen-roformer-vocals": {}},
            }
            if native_pcm:
                audio_provenance["mix"]["stream"] = {
                    "sampleRate": 48000, "frameCount": native_pcm[0],
                    "durationMs": native_pcm[1],
                }
                self.assertEqual(_worker_pcm_duration_ms(audio_provenance["mix"]),
                                 native_pcm[1] + (1 if language == "ar" else 0))
            mapping = build_lexical_occurrence_mapping(video_id, lyrics['tracks'][0], (0, 1))
            worker_duration = _worker_pcm_duration_ms(audio_provenance['mix'])
            complete_lines = []
            for index, owner in enumerate(mapping['performedOccurrences']):
                start = 1000+index*2000
                words = [{'wordId':word['occurrenceWordId'],'startMs':start+i*200,'endMs':start+i*200+180,
                    'confidence':.9,'acousticTokenCount':1} for i,word in enumerate(owner['words'])]
                complete_lines.append({'lineId':owner['occurrenceId'],'sourcePhysicalIndex':index,
                    'startMs':start,'endMs':words[-1]['endMs'],'confidence':.9,'words':words})
            def mms_result(**options):
                lines = json.loads(json.dumps(complete_lines))
                if options['audio_view_id'] == 'htdemucs-vocals':
                    for line in lines:
                        line['startMs'] += 1000
                        line['endMs'] += 1000
                        for word in line['words']:
                            word['startMs'] += 1000
                            word['endMs'] += 1000
                return {'durationMs':worker_duration,'lines':lines}, False
            with (
                patch('stemwise_engine.lyrics_align_pc._load_or_run_owsm_ctc_alignment', side_effect=RuntimeError("synthetic worker unavailable")) as owsm,
                patch('stemwise_engine.lyrics_align_pc._load_or_run_nemo_nfa_alignment', side_effect=RuntimeError("synthetic worker unavailable")) as nemo,
                patch.multiple(
                    "stemwise_engine.lyrics_align_pc",
                    _verify_alignment_runtime=lambda *_a, **_k: {},
                    _verify_pinned_stack=lambda *_a, **_k: {},
                    _wav2vec2_model_assets=lambda *_a, **_k: (root / "model", {}),
                ),
                patch.multiple(
                    "stemwise_engine.alignment_audio",
                    write_audio_provenance=lambda *_a, **_k: audio_provenance,
                    read_audio_provenance=lambda *_a, **_k: audio_provenance,
                ),
                patch.multiple(
                    "stemwise_engine.lyrics_align_pc",
                    _needs_route_local_verifier=lambda *_a, **_k: False,
                    _build_initial_alignment_segments=lambda *_a, **_k: [{'segmentIndex': 0,
                        'startLineIndex': 0, 'endLineIndex': 2, 'windowStartMs': 0, 'windowEndMs': worker_duration}],
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc.validate_child_zip',
                    return_value={"analysis": {"durationMs": duration}},
                ),
                patch('stemwise_engine.lyrics_align_pc._load_reviewed_lrc_starts', return_value={}),
                patch(
                    'stemwise_engine.lyrics_align_pc.internal_repeat_structure_kind',
                    return_value="none",
                ),
                patch('stemwise_engine.lyrics_align_pc._load_caption', return_value=None),
                patch(
                    'stemwise_engine.lyrics_align_pc._load_or_transcribe',
                    return_value=(transcript(text), {"type": "original-mix"}, False),
                ),
                patch('stemwise_engine.lyrics_align_pc.align_known_structure', return_value=alignment),
                patch(
                    'stemwise_engine.lyrics_align_pc._needs_additional_structural_evidence',
                    return_value=False,
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc._load_or_run_wav2vec2_alignment',
                    return_value=(
                        {'durationMs':worker_duration,'route':WAV2VEC2_ROUTE,'lines':complete_lines},
                        False,
                        {"modelSha256":"b"*64,"snapshotManifestSha256":"c"*64},
                    ),
                ) as wav2vec2,
                patch(
                    'stemwise_engine.lyrics_align_pc._load_final_mms_alignment',
                    side_effect=mms_result,
                ) as mms,
            ):
                report = _align(
                    {
                        "projectDir": str(root / "builder"),
                        "tempDir": str(root / "temp"),
                        "analysisTimeoutMinutes": 1,
                    },
                    args,
                    selected_override=[{"position": 1, "videoId": video_id}],
                    paths_override=(sidecars, output, root / "models", workspace),
                    temp_root_override=root / "attempt",
                )

            if native_pcm:
                if language == "en":
                    nemo.assert_not_called()
                for worker in ((owsm,) if language == "en" else (owsm, nemo)):
                    self.assertEqual(worker.call_count, 1, report["results"][0])
                    self.assertEqual(worker.call_args.kwargs["duration_ms"], native_pcm[1])
                    self.assertEqual(worker.call_args.kwargs["audio"], args.analysis_audio)
                    self.assertEqual(worker.call_args.kwargs["language"], language)
                self.assertEqual((sidecars / f"{video_id}.lyrics.json").read_bytes(), raw)
            state = json.loads(
                workspace.state_path(video_id).read_text(encoding="utf-8")
            )
            self.assertEqual(
                report["results"][0]["status"],
                "candidate",
                {
                    "routeFailures": report["results"][0].get("routeFailures"),
                    "reasons": report["results"][0].get("reasons"),
                },
            )
            self.assertEqual(state['endpointEvidence']['resultClass'],'committed-words')
            candidate = json.loads(workspace.candidate_path(video_id).read_bytes())
            self.assertEqual([len(line['words']) for line in candidate['tracks'][0]['lines']],[4,4])
            self.assertEqual(wav2vec2.call_count, 1)
            self.assertEqual(
                [call.kwargs["audio_view_id"] for call in mms.call_args_list],
                ["htdemucs-vocals", "kimberley-jensen-roformer-vocals"],
            )
            self.assertEqual([attempt['routeId'] for attempt in state['endpointRouteAttempts']], [
                'htdemucs-vocals:baseline', 'kimberley-jensen-roformer-vocals:baseline',
                'kimberley-jensen-roformer-vocals:cumulative'])
            self.assertEqual(state["selectedEndpointAttemptIndex"], 2)
            self.assertEqual(
                state["endpointRouteAttempts"][1]["routeId"],
                "kimberley-jensen-roformer-vocals:baseline",
            )
            cumulative = state['endpointRouteAttempts'][2]['endpointEvidence']
            self.assertTrue(cumulative['cumulativeRouteMerge'])
            self.assertEqual(cumulative['cumulativeRouteFilledLineCount'], 2)
            self.assertEqual([row['occurrenceId'] for row in cumulative['occurrenceCandidates']],
                [row['occurrenceId'] for row in mapping['performedOccurrences']])
            self.assertEqual(
                state["endpointEvidence"]["selectedPublisherFamilyCounts"],
                {"mms-fa": 2},
            )
            self.assertEqual(
                state["roformerCascade"]["selectedEndpointRoute"],
                "kimberley-jensen-roformer-vocals",
            )

    def test_forced_alignment_candidate_preserves_display_ranges(self) -> None:
        candidates = _forced_alignment_verifier_candidates(
            {
                "route": WAV2VEC2_ROUTE,
                "durationMs": 1000,
                "lines": [
                    {
                        "lineId": "occurrence-27d5a77a803f39b225b2bf71",
                        "startMs": 100,
                        "endMs": 900,
                        "words": [
                            {
                                "wordId": "occurrence-word-37bb71b67df0712e83b3300e",
                                "startMs": 100,
                                "endMs": 400,
                            }
                        ],
                    }
                ]
            },
            [[(2, 7, "private")]],
            expected_route=WAV2VEC2_ROUTE,
            duration_ms=1000,
        )

        self.assertEqual(candidates[0]["words"][0]["startUtf16"], 2)
        self.assertEqual(candidates[0]["words"][0]["endUtf16"], 7)
        self.assertNotIn("private", json.dumps(candidates))

    def test_forced_alignment_candidate_cannot_be_relabelled(self) -> None:
        with self.assertRaisesRegex(ValueError, "wrong audio route"):
            _forced_alignment_verifier_candidates(
                {"route": WAV2VEC2_ROUTE, "lines": []},
                [],
                expected_route="wav2vec2-ctc-forced-on-htdemucs-vocals",
                duration_ms=1000,
            )

    def test_route_local_wav2vec2_rejects_wrong_audio_identity(self) -> None:
        common = {
            "config": {},
            "video_id": "KbKqkR3Q6SA",
            "language": "en",
            "sources": [0],
            "duration_ms": 1_000,
            "workspace": AlignmentWorkspace(Path("unused")),
            "audio": None,
            "temp_dir": Path("unused"),
            "device": "cpu",
            "timeout_seconds": 1,
            "occurrence_mapping": {},
            "display_word_lines": [[(0, 1, "a")]],
        }
        with self.assertRaisesRegex(ValueError, "different view"):
            _load_or_run_wav2vec2_alignment(
                **common,
                audio_identity={
                    "routeId": "kimberley-jensen-roformer-vocals",
                    "stream": {"sampleRate": 16_000, "frameCount": 16_000},
                },
                audio_view_id="htdemucs-vocals",
            )
        with self.assertRaisesRegex(ValueError, "duration differs"):
            _load_or_run_wav2vec2_alignment(
                **common,
                audio_identity={
                    "stream": {"sampleRate": 16_000, "frameCount": 32_000}
                },
            )
        with self.assertRaisesRegex(ValueError, "canonical mix"):
            _load_or_run_wav2vec2_alignment(
                **common,
                audio_identity={
                    "routeId": "htdemucs-vocals",
                    "sha256": "a" * 64,
                    "byteCount": 1,
                    "stream": {"sampleRate": 16_000, "frameCount": 16_000},
                },
            )

    def test_wav2vec2_rejects_audio_bytes_that_do_not_match_bound_identity(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            project = root / "builder"
            prepare_runtime_layout(project)
            (project / ".venv" / "bin").mkdir(parents=True)
            (project / "src").mkdir()
            (project / ".venv" / "bin" / "python").write_bytes(b"python")
            (project / "src" / "wav2vec2_forced_alignment.py").write_bytes(b"helper")
            (project / "src" / "mms_forced_alignment.py").write_bytes(b"helper")
            audio = root / "audio.wav"
            write_test_wav(audio)
            workspace = AlignmentWorkspace(root / "alignment", workspace_root=root)
            identity = write_audio_provenance(
                workspace,
                "KbKqkR3Q6SA",
                duration_ms=1_000,
                mix_audio=audio,
            )["mix"]
            identity = {**identity, "sha256": "0" * 64}
            request = {
                "segments": [
                    {
                        "segmentIndex": 0,
                        "startLineIndex": 0,
                        "endLineIndex": 1,
                        "windowStartMs": 0,
                        "windowEndMs": 1_000,
                    }
                ]
            }
            with (
                patch(
                    'stemwise_engine.lyrics_align_pc._wav2vec2_model_assets',
                    return_value=(root / "model", {}),
                ),
                patch('stemwise_engine.lyrics_align_pc._wav2vec2_runtime_provenance', return_value={}),
                patch(
                    'stemwise_engine.lyrics_align_pc._forced_alignment_request',
                    return_value=(request, [], [], {}),
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc._wav2vec2_helper_hashes',
                    return_value={
                        "wav2vec2_forced_alignment.py": "a" * 64,
                        "mms_forced_alignment.py": "b" * 64,
                    },
                ),
                patch('stemwise_engine.lyrics_align_pc._read_optional_cache_json', return_value=None),
            ):
                with self.assertRaisesRegex(ValueError, "differs from its bound identity"):
                    _load_or_run_wav2vec2_alignment(
                        config={"projectDir": str(project)},
                        video_id="KbKqkR3Q6SA",
                        language="en",
                        sources=[0],
                        duration_ms=1_000,
                        workspace=workspace,
                        audio=audio,
                        audio_identity=identity,
                        temp_dir=root / "temp",
                        device="cpu",
                        timeout_seconds=1,
                        occurrence_mapping={},
                        display_word_lines=[[(0, 1, "a")]],
                    )

    def test_wav2vec2_static_capability_requires_exact_current_request_and_exit(self) -> None:
        from stemwise_engine.lyrics_align_pc import run_process_tree as run_real_process
        original = plain_original("alpha beta")
        words = [display_words(original["text"])]
        mapping = build_lexical_occurrence_mapping("KbKqkR3Q6SA", original, [0])
        for fault in (None, "stale-request", "wrong-engine", "wrong-version", "boolean-version", "supported",
                      "extra-results", "wrong-exit", "malformed", "missing"):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / 'Test suite/work') as name:
                root = Path(name)
                project = root / "builder"
                prepare_runtime_layout(project)
                (project / ".venv/bin").mkdir(parents=True)
                (project / "src").mkdir()
                (project / ".venv/bin/python").write_bytes(b"python")
                for helper in ("wav2vec2_forced_alignment.py", "mms_forced_alignment.py",
                               "ctc_worker.py", "acoustic_inputs.py", "worker_protocol.py", "worker_publication.py", "common.py"):
                    (project / "src" / helper).write_bytes((PROJECT_ROOT / "src" / helper).read_bytes())
                audio = root / "audio.wav"
                write_test_wav(audio)
                workspace = AlignmentWorkspace(root / "alignment", workspace_root=root)
                identity = write_audio_provenance(workspace, "KbKqkR3Q6SA",
                    duration_ms=1000, mix_audio=audio)["mix"]
                def worker(command, **_kwargs):
                    if "--request" not in command:
                        return run_real_process(command, **_kwargs)
                    request = json.loads(Path(command[command.index("--request") + 1]).read_bytes())
                    output = Path(command[command.index("--output") + 1])
                    packet = {"formatVersion": 1, "engine": "wav2vec2-target-capability",
                        "requestSha256": hashlib.sha256(json.dumps(request, ensure_ascii=False,
                            sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest(),
                        "supported": False}
                    if fault == "stale-request": packet["requestSha256"] = "0" * 64
                    elif fault == "wrong-engine": packet["engine"] = "other"
                    elif fault == "wrong-version": packet["formatVersion"] = 2
                    elif fault == "boolean-version": packet["formatVersion"] = True
                    elif fault == "supported": packet["supported"] = True
                    elif fault == "extra-results": packet["lines"] = []
                    if fault != "missing":
                        output.write_text("{" if fault == "malformed" else json.dumps(packet), encoding="utf-8")
                    return subprocess.CompletedProcess(command, 1 if fault == "wrong-exit" else 3,
                                                       stdout="", stderr="")
                with patch('stemwise_engine.lyrics_align_pc._wav2vec2_model_assets', return_value=(root / "model", {})), \
                     patch('stemwise_engine.lyrics_align_pc._wav2vec2_runtime_provenance', return_value={}), \
                     patch('stemwise_engine.lyrics_align_pc.run_process_tree', side_effect=worker):
                    try:
                        _load_or_run_wav2vec2_alignment(config={"projectDir": str(project)},
                            video_id="KbKqkR3Q6SA", language="hy", sources=[0], duration_ms=1000,
                            workspace=workspace, audio=audio, audio_identity=identity,
                            temp_dir=root / "temp", device="cpu", timeout_seconds=1,
                            occurrence_mapping=mapping, display_word_lines=words)
                    except Exception as error:
                        self.assertEqual(type(error).__name__,
                            'Wav2Vec2TargetUnsupported' if fault is None else 'WorkerProcessError')
                    else:
                        self.fail("a capability packet cannot publish acoustic results")
                self.assertFalse(list(workspace.job_dir("KbKqkR3Q6SA").glob(
                    "wav2vec2-public-endpoints-*.private.json")), "capability is not an alignment cache")
                diagnostics = list(workspace.job_dir("KbKqkR3Q6SA").glob("wav2vec2-mix-baseline.failure.private.*.txt"))
                self.assertEqual(bool(diagnostics), fault is not None)
                if fault is not None:
                    self.assertTrue(all(path.is_file() for path in diagnostics))

    def test_word_disagreement_falls_back_for_the_whole_line(self) -> None:
        line_id = "occurrence-27d5a77a803f39b225b2bf71"
        ids = [[
            "occurrence-word-37bb71b67df0712e83b3300e",
            "occurrence-word-07560f65df5bed78b288e80c",
        ]]
        forced = {
            "formatVersion": 5,
            "engine": "torchaudio-mms-fa",
            **mms_timeline(2_000),
            "lines": [
                {
                    "lineIndex": 0,
                    "lineId": line_id,
                    "sourcePhysicalIndex": 2,
                    "startMs": 100,
                    "endMs": 1_000,
                    "confidence": 0.7,
                    "minimumWordConfidence": 0.6,
                    "displayWordCount": 2, "romanizedWordCount": 2, "acousticTokenCount": 2,
                    "words": [
                        {
                            "wordId": ids[0][0],
                            "startMs": 100,
                            "endMs": 400,
                            "confidence": 0.7,
                            "acousticTokenCount": 1, "romanizedWordCount": 1,
                        },
                        {
                            "wordId": ids[0][1],
                            "startMs": 500,
                            "endMs": 1_000,
                            "confidence": 0.6,
                            "acousticTokenCount": 1, "romanizedWordCount": 1,
                        },
                    ],
                }
            ],
            "confidence": {
                "wordP10": 0.6,
                "wordP50": 0.65,
                "wordP90": 0.7,
                "lineP10": 0.7,
                "lineP50": 0.7,
                "lineP90": 0.7,
            },
        }
        _validate_numeric_mms_result(
            forced,
            expected_sources=[2],
            duration_ms=2_000,
            expected_line_ids=[line_id],
            expected_word_ids=ids,
        )
        overlapping_lines = json.loads(json.dumps(forced))
        second = json.loads(json.dumps(forced["lines"][0]))
        second.update(
            lineIndex=1,
            lineId="occurrence-second",
            sourcePhysicalIndex=3,
            startMs=900,
            endMs=1_800,
        )
        second_ids = ["occurrence-word-third", "occurrence-word-fourth"]
        second["words"][0].update(
            wordId=second_ids[0], startMs=1_000, endMs=1_300
        )
        second["words"][1].update(
            wordId=second_ids[1], startMs=1_400, endMs=1_700
        )
        overlapping_lines["lines"].append(second)
        with self.assertRaisesRegex(ValueError, "lines overlap"):
            _validate_numeric_mms_result(
                overlapping_lines,
                expected_sources=[2, 3],
                duration_ms=2_000,
                expected_line_ids=[line_id, "occurrence-second"],
                expected_word_ids=[ids[0], second_ids],
            )
        guarded = {
            **forced,
            "segmentCount": 1,
            "segmentBoundaryGuardMs": 250,
            "displayWordCount": 2, "romanizedWordCount": 2, "acousticTokenCount": 2,
            "lineCount": 1,
        }
        full_segment = [
            {
                "segmentIndex": 0,
                "startLineIndex": 0,
                "endLineIndex": 1,
                "windowStartMs": 0,
                "windowEndMs": 2_000,
            }
        ]
        _validate_numeric_mms_result(
            guarded,
            expected_sources=[2],
            duration_ms=2_000,
            expected_line_ids=[line_id],
            expected_word_ids=ids,
            expected_segments=full_segment,
        )
        with self.assertRaisesRegex(ValueError, "segment edge guard"):
            _validate_numeric_mms_result(
                guarded,
                expected_sources=[2],
                duration_ms=2_000,
                expected_line_ids=[line_id],
                expected_word_ids=ids,
                expected_segments=[
                    {
                        **full_segment[0],
                        "windowStartMs": 1,
                    }
                ],
            )
        overlapping = json.loads(json.dumps(guarded))
        overlapping["lines"][0]["words"][1].update(startMs=300, endMs=600)
        with self.assertRaisesRegex(ValueError, "display-word timing"):
            _validate_numeric_mms_result(
                overlapping,
                expected_sources=[2],
                duration_ms=2_000,
                expected_line_ids=[line_id],
                expected_word_ids=ids,
                expected_segments=full_segment,
            )
        wav_guarded = json.loads(json.dumps(guarded))
        wav_guarded.pop("romanizedWordCount")
        for line in wav_guarded["lines"]:
            line.pop("romanizedWordCount")
            for word in line["words"]:
                word.pop("romanizedWordCount")
        wav_guarded.update(
            formatVersion=4,
            requestFormatVersion=5,
            engine="wav2vec2-ctc-forced-alignment",
        )
        _validate_numeric_mms_result(
            wav_guarded,
            expected_sources=[2],
            duration_ms=2_000,
            expected_line_ids=[line_id],
            expected_word_ids=ids,
            expected_format_version=4,
            expected_engine="wav2vec2-ctc-forced-alignment",
            expected_segments=full_segment,
        )
        wav_guarded["requestFormatVersion"] = 4
        with self.assertRaisesRegex(ValueError, "segment result metadata"):
            _validate_numeric_mms_result(
                wav_guarded,
                expected_sources=[2],
                duration_ms=2_000,
                expected_line_ids=[line_id],
                expected_word_ids=ids,
                expected_format_version=4,
                expected_engine="wav2vec2-ctc-forced-alignment",
                expected_segments=full_segment,
            )
        shifted = json.loads(json.dumps(forced))
        shifted["canonicalTimeTransform"]["targetFrameCount"] += 10
        with self.assertRaisesRegex(ValueError, "canonical-time transform"):
            _validate_numeric_mms_result(
                shifted,
                expected_sources=[2],
                duration_ms=2_000,
                expected_line_ids=[line_id],
                expected_word_ids=ids,
            )
        verifier = (
            {
                "lineId": line_id,
                "route": WAV2VEC2_ROUTE,
                "startMs": 120,
                "endMs": 980,
                "words": (
                {
                    "wordId": ids[0][0],
                    "startUtf16": 0,
                    "endUtf16": 5,
                    "startMs": 120,
                    "endMs": 420,
                },
                {
                    "wordId": ids[0][1],
                    "startUtf16": 6,
                    "endUtf16": 11,
                    "startMs": 510,
                    "endMs": 980,
                },
                ),
            },
        )
        published, summary = _refine_public_timings(forced, verifier)
        self.assertEqual(summary["resultClass"], "committed-words")
        self.assertNotIn("primaryEndpointRoute", summary)
        self.assertNotIn("independentVerifierRoutes", summary)
        generic = json.loads(json.dumps(verifier))
        generic[0]["route"] = "faster-whisper:en"
        with self.assertRaisesRegex(ValueError, "closed Wav2Vec2 family"):
            _refine_public_timings(forced, tuple(generic))
        route_local = json.loads(json.dumps(verifier))
        route_local[0]["route"] = "wav2vec2-ctc-forced-on-htdemucs-vocals"
        with self.assertRaisesRegex(ValueError, "closed Wav2Vec2 family"):
            _refine_public_timings(forced, tuple(route_local))
        _refine_public_timings(
            forced,
            tuple(route_local),
            allowed_wav2vec2_routes=frozenset(
                ("wav2vec2-ctc-forced-on-htdemucs-vocals",)
            ),
        )
        with self.assertRaisesRegex(ValueError, "closed Wav2Vec2 family"):
            _refine_public_timings(
                forced,
                tuple(route_local),
                allowed_wav2vec2_routes=frozenset(
                    ("wav2vec2-ctc-forced-on-kimberley-jensen-roformer-vocals",)
                ),
            )
        with self.assertRaisesRegex(ValueError, "closed Wav2Vec2 family"):
            _refine_public_timings(
                {"lines": [{}, {}]},
                ({"route": "route-a"}, {"route": "route-b"}),
            )
        self.assertEqual(len(published[0]["words"]), 2)
        occurrence = summary["occurrenceCandidates"][0]
        self.assertEqual(occurrence["occurrenceId"], line_id)
        self.assertEqual(
            [
                word["occurrenceWordId"]
                for word in occurrence["familyCandidates"]["mms-fa"]["words"]
            ],
            ids[0],
        )
        self.assertEqual(
            [
                word["occurrenceWordId"]
                for word in occurrence["familyCandidates"]["language-wav2vec2"][
                    "words"
                ]
            ],
            ids[0],
        )
        self.assertEqual(
            set(occurrence["familyCandidates"]),
            {"mms-fa", "language-wav2vec2"},
        )
        self.assertEqual(occurrence["selectedPublisherFamily"], "mms-fa")
        self.assertEqual(occurrence["publishDecision"], {"result": "words"})
        exact_gate = json.loads(json.dumps(verifier))
        exact_gate[0].update(startMs=400, endMs=1_400)
        exact_gate[0]["words"][0].update(startMs=400, endMs=800)
        exact_gate[0]["words"][1].update(startMs=800, endMs=1_400)
        self.assertEqual(
            _refine_public_timings(forced, tuple(exact_gate))[1]["resultClass"],
            "committed-lines",  # The first word witnesses only touch at 400 ms.
        )
        over_start = json.loads(json.dumps(exact_gate))
        over_start[0]["startMs"] = 401
        self.assertEqual(
            _refine_public_timings(forced, tuple(over_start))[1]["resultClass"],
            "line-unresolved",
        )
        over_end = json.loads(json.dumps(exact_gate))
        over_end[0]["endMs"] = 1_401
        self.assertEqual(
            _refine_public_timings(forced, tuple(over_end))[1]["resultClass"],
            "line-unresolved",
        )
        disagreeing = json.loads(json.dumps(verifier))
        disagreeing[0]["words"][1]["endMs"] = 1_500
        fallback, summary = _refine_public_timings(
            forced, tuple(disagreeing)
        )
        self.assertEqual(summary["resultClass"], "committed-lines")
        self.assertNotIn("words", fallback[0])
        self.assertEqual(
            summary["occurrenceCandidates"][0]["publishDecision"],
            {"result": "line-only", "code": "invalid-word-interval"},
        )
        unresolved, summary = _refine_public_timings(forced, (None,))
        self.assertEqual(summary["resultClass"], "line-unresolved")
        self.assertEqual(unresolved, [])
        self.assertEqual(
            summary["occurrenceCandidates"][0]["publishDecision"],
            {
                "result": "unresolved",
                "code": "missing-language-wav2vec2-boundary",
            },
        )
        self.assertNotIn(
            "language-wav2vec2",
            summary["occurrenceCandidates"][0]["familyCandidates"],
        )
        self.assertNotIn("selectedPublisherFamily", summary["occurrenceCandidates"][0])
        lrc_conflict, summary = _refine_public_timings(forced, verifier, {2: 1_000})
        self.assertEqual(summary["resultClass"], "line-unresolved")
        self.assertEqual(summary["unresolvedLineReasons"], {"reviewed-lrc-start-disagreement": 1})
        self.assertEqual(summary["reasons"], ["reviewed-lrc-start-disagreement"])
        self.assertEqual(summary["reviewedLrcStartEvidenceCount"], 1)
        self.assertEqual(summary["occurrenceCandidates"][0]["reviewedLrcStartMs"], 1_000)
        self.assertEqual(lrc_conflict, [])
        forced["lines"][0]["confidence"] = 0.0
        self.assertEqual(_refine_public_timings(forced, verifier)[1]["resultClass"], "line-unresolved")
        with tempfile.TemporaryDirectory() as name:
            endpoint_cache = _forced_alignment_cache_path(
                AlignmentWorkspace(Path(name)),
                "KbKqkR3Q6SA",
                "public-endpoints-v3-anchored",
            )
            self.assertEqual(
                endpoint_cache.name,
                "mms-public-endpoints-v3-anchored.private.json",
            )

    def test_endpoint_verifier_uses_the_whole_track_language(self):
        from stemwise_engine.workflow_records import LyricsContentBlocker
        for language in ('tr', 'en'):
            self.assertEqual(_endpoint_verifier_family({'language': language}), WAV2VEC2_FAMILY)
        with patch('stemwise_engine.lyrics_align_pc._phonemizer_languages', return_value=frozenset({'zu', 'ur', 'en-us'})):
            self.assertEqual(_endpoint_verifier_family({'language': 'zu'}), WAV2VEC2_FAMILY)
            with self.assertRaises(LyricsContentBlocker):
                _endpoint_verifier_family({'language': 'zz'})
            with self.assertRaises(LyricsContentBlocker):
                _endpoint_verifier_family({})
            multilingual = plain_original('اردو متن\nEnglish words')
            multilingual.update(language='mul', componentLanguages=['ur', 'en'])
            self.assertEqual(_endpoint_language(multilingual), 'en')
            with self.assertRaises(LyricsContentBlocker):
                _endpoint_verifier_family(multilingual)
            multilingual_words = [display_words(line) for line in multilingual['text'].splitlines()]
            self.assertEqual(_component_phonemizer_languages(multilingual, multilingual_words),
                             [['ur', 'ur'], ['en-us', 'en-us']])
            self.assertEqual(_endpoint_verifier_family(multilingual, multilingual_words), WAV2VEC2_FAMILY)
        with patch('stemwise_engine.lyrics_align_pc._phonemizer_languages', side_effect=RuntimeError('runtime broken')):
            with self.assertRaisesRegex(RuntimeError, 'runtime broken'):
                _endpoint_verifier_family({'language': 'zu'})


    def test_independent_endpoint_consensus_fills_only_one_unique_cluster(self) -> None:
        def occurrence(index: int, start: int, end: int, code: str) -> dict[str, object]:
            return {
                "occurrenceId": f"occurrence-{index}",
                "performedIndex": index,
                "sourcePhysicalIndex": index,
                "familyCandidates": {
                    "mms-fa": {"route": "mms", "startMs": start, "endMs": end,
                        "confidence": .01 if code == 'low-forced-line-support' else .8,
                        "words": [{"occurrenceWordId": f'word-{index}', "startMs": start,
                            "endMs": end, "confidence": .01 if code == 'low-forced-line-support' else .8,
                            "acousticTokenCount": 1}]},
                    WAV2VEC2_FAMILY: {
                        "route": "wav2vec2",
                        "startMs": start + 20,
                        "endMs": end + 20,
                    },
                },
                "publishDecision": {"result": "unresolved", "code": code},
            }

        baseline_timings = [
            {"startMs": 100, "endMs": 900},
            {"startMs": 2_000, "endMs": 2_800},
        ]
        baseline_summary = {
            "lineCount": 2,
            "occurrenceCandidates": [
                occurrence(0, 100, 900, "line-start-disagreement"),
                occurrence(1, 2_000, 2_800, "low-forced-line-support"),
            ],
            "reviewedLrcStartEvidenceCount": 0,
            "reviewedLrcAmbiguousOccurrenceCount": 0,
        }
        del baseline_summary["occurrenceCandidates"][1]["familyCandidates"][WAV2VEC2_FAMILY]
        owsm = (
            {"route": "owsm", "startMs": 120, "endMs": 920},
            {"route": "owsm", "startMs": 2_600, "endMs": 3_400},
        )
        nemo = (
            {"route": "nemo", "startMs": 140, "endMs": 940},
            {"route": "nemo", "startMs": 2_620, "endMs": 3_420},
        )
        published, summary = _apply_independent_endpoint_consensus(
            (baseline_timings, baseline_summary),
            {OWSM_CTC_FAMILY: owsm, NEMO_NFA_FAMILY: nemo},
        )
        self.assertEqual(summary["resultClass"], "committed-lines")
        self.assertEqual(summary["independentConsensusFilledLineCount"], 2)
        self.assertEqual(
            summary["selectedPublisherFamilyCounts"],
            {"mms-fa": 1, OWSM_CTC_FAMILY: 1},
        )
        self.assertEqual(published, [{"startMs": 100, "endMs": 900}, {"startMs": 2_600, "endMs": 3_400}])

        ambiguous = list(nemo)
        ambiguous[0] = {"route": "nemo", "startMs": 800, "endMs": 1_600}
        withheld, summary = _apply_independent_endpoint_consensus(
            (baseline_timings, baseline_summary),
            {OWSM_CTC_FAMILY: owsm, NEMO_NFA_FAMILY: tuple(ambiguous)},
        )
        self.assertEqual(len(withheld), 2)
        self.assertEqual(summary["independentConsensusFilledLineCount"], 1)
        self.assertEqual(summary["independentConsensusAmbiguousLineCount"], 1)
        retained, retained_summary = _apply_independent_endpoint_consensus(
            (baseline_timings, baseline_summary),
            {OWSM_CTC_FAMILY: owsm, NEMO_NFA_FAMILY: tuple(ambiguous)},
            include_unresolved=True,
        )
        self.assertEqual(len(retained), 2)
        self.assertEqual(retained_summary["resultClass"], "line-unresolved")

    def test_ordered_endpoint_path_looks_ahead_without_inventing_boundaries(self) -> None:
        points = [[(0, 1800), (0, 1500)], [(1700, 3500), (1800, 3700)],
                  [(3600, 5000), (3750, 5000)]]
        options = [[({"startMs": start, "endMs": end}, f"family-{i}")
                    for i, (start, end) in enumerate(row)] for row in points]
        before = json.dumps(options)
        path = _ordered_endpoint_candidate_path(options)
        self.assertEqual(path, [options[0][0], options[1][1], options[2][1]])
        self.assertEqual(json.dumps(options), before)
        self.assertIsNone(_ordered_endpoint_candidate_path([options[0],
            [({"startMs": 1400, "endMs": 3000}, "family-0")]]))

    def test_consensus_selects_existing_ordered_publishers_and_preserves_fixed_lines(self) -> None:
        timings = [{"startMs": 1000, "endMs": 2000}, {"startMs": 3000, "endMs": 4500}]
        occurrences = [{
            "occurrenceId": f"occurrence-{i}", "performedIndex": i, "sourcePhysicalIndex": i,
            "familyCandidates": {"mms-fa": {"route": "mms", **timing, "confidence": 0. if i == 0 else .8,
                "words": [{"occurrenceWordId": f'word-{i}', **timing,
                    "confidence": 0. if i == 0 else .8, "acousticTokenCount": 1}]}},
            "publishDecision": {"result": "unresolved", "code": "line-start-disagreement"},
        } for i, timing in enumerate(timings)]
        summary = {"lineCount": 2, "occurrenceCandidates": occurrences,
            "reviewedLrcStartEvidenceCount": 0, "reviewedLrcAmbiguousOccurrenceCount": 0}
        witnesses = {
            OWSM_CTC_FAMILY: ({"route": "owsm", "startMs": 1100, "endMs": 3001},
                              {"route": "owsm", "startMs": 3001, "endMs": 4520}),
            NEMO_NFA_FAMILY: ({"route": "nemo", "startMs": 1120, "endMs": 3020}, None),
        }
        before = json.dumps((timings, summary, witnesses))
        published, result = _apply_independent_endpoint_consensus((timings, summary), witnesses)
        self.assertEqual(result["lineReadyCount"], 2)
        self.assertEqual(published, [{"startMs": 1100, "endMs": 3001},
                                     {"startMs": 3001, "endMs": 4520}])
        self.assertEqual(result["selectedPublisherFamilyCounts"], {OWSM_CTC_FAMILY: 2})
        self.assertEqual(json.dumps((timings, summary, witnesses)), before)

        fixed_timings = [dict(published[0], words=[{"startMs": 1100, "endMs": 3001}]), timings[1]]
        fixed_summary = json.loads(json.dumps(summary))
        fixed_summary["occurrenceCandidates"][0].update(
            publishDecision={"result": "words"}, selectedPublisherFamily="mms-fa")
        fixed_summary["occurrenceCandidates"][0]["familyCandidates"]["mms-fa"].update(published[0], confidence=.8)
        fixed_summary["occurrenceCandidates"][0]["familyCandidates"]["mms-fa"]["words"][0].update(
            published[0], confidence=.8)
        preserved, result = _apply_independent_endpoint_consensus((fixed_timings, fixed_summary), witnesses)
        self.assertEqual(result["lineReadyCount"], 2)
        self.assertIs(preserved[0], fixed_timings[0])
        self.assertEqual(preserved[1], published[1])

        impossible = dict(witnesses)
        impossible[OWSM_CTC_FAMILY] = (dict(witnesses[OWSM_CTC_FAMILY][0], endMs=3300),
                                       witnesses[OWSM_CTC_FAMILY][1])
        impossible[NEMO_NFA_FAMILY] = (dict(witnesses[NEMO_NFA_FAMILY][0], endMs=3320), None)
        withheld, result = _apply_independent_endpoint_consensus((timings, summary), impossible)
        self.assertEqual(withheld, [])
        self.assertEqual(result["lineReadyCount"], 0)

    def test_unresolved_placeholder_cannot_veto_later_independent_consensus(self) -> None:
        timings = [{"startMs": 100, "endMs": 900},
                   {"startMs": 4000, "endMs": 4900},
                   {"startMs": 5000, "endMs": 5900}]
        vocal = [(100, 900), (1200, 1800), (2500, 2900)]
        occurrences = [{
            "occurrenceId": f"occurrence-{i}", "performedIndex": i, "sourcePhysicalIndex": i,
            "familyCandidates": {"mms-fa": {"route": "mms", **timing, "confidence": .8 if i == 0 else 0.},
                WAV2VEC2_FAMILY: {"route": "vocal", "startMs": vocal[i][0], "endMs": vocal[i][1]}},
            "publishDecision": ({"result": "line-only", "code": "word-start-disagreement"} if i == 0 else
                                {"result": "unresolved", "code": "line-start-disagreement"}),
            **({"selectedPublisherFamily": "mms-fa"} if i == 0 else {}),
        } for i, timing in enumerate(timings)]
        summary = {"lineCount": 3, "lineReadyCount": 1, "occurrenceCandidates": occurrences,
            "reviewedLrcStartEvidenceCount": 0, "reviewedLrcAmbiguousOccurrenceCount": 0}
        witnesses = {OWSM_CTC_FAMILY: (None,
            {"route": "owsm", "startMs": 1400, "endMs": 2300},
            {"route": "owsm", "startMs": 2540, "endMs": 2940})}
        before = json.dumps((timings, summary, witnesses))
        planned, result = _apply_independent_endpoint_consensus(
            (timings, summary), witnesses, include_unresolved=True)
        self.assertEqual(result["lineReadyCount"], 2)
        self.assertEqual(result["occurrenceCandidates"][1]["publishDecision"]["result"], "unresolved")
        self.assertEqual(planned, [{"startMs": 100, "endMs": 900},
                                  {"startMs": 1200, "endMs": 1800},
                                  {"startMs": 2500, "endMs": 2900}])
        cumulative, cumulative_summary = _merge_endpoint_route_passes(
            (timings, summary), (planned, result))
        self.assertEqual(cumulative_summary["lineReadyCount"], 2)
        self.assertEqual(cumulative, planned)
        anchored, anchored_summary = _merge_endpoint_passes(
            (timings, summary), (planned, result),
            stability_family=WAV2VEC2_FAMILY, include_unresolved=True)
        self.assertEqual(anchored_summary["lineReadyCount"], 2)
        self.assertEqual(anchored, planned)
        public, _ = _apply_independent_endpoint_consensus((timings, summary), witnesses)
        self.assertEqual(len(public), 3)  # Regional decisions, not row existence, grant timing authority.
        self.assertEqual(json.dumps((timings, summary, witnesses)), before)

        fixed = json.loads(json.dumps(summary))
        fixed["lineReadyCount"] = 2
        fixed["occurrenceCandidates"][1]["publishDecision"] = {"result": "line-only", "code": "word-start-disagreement"}
        fixed["occurrenceCandidates"][1]["selectedPublisherFamily"] = "mms-fa"
        fixed["occurrenceCandidates"][1]["familyCandidates"]["mms-fa"]["confidence"] = .8
        fixed["occurrenceCandidates"][1]["familyCandidates"][WAV2VEC2_FAMILY].update(timings[1])
        fixed_witnesses = {OWSM_CTC_FAMILY: (None, None, witnesses[OWSM_CTC_FAMILY][2])}
        planned, result = _apply_independent_endpoint_consensus(
            (timings, fixed), fixed_witnesses, include_unresolved=True)
        self.assertEqual(planned, timings)
        self.assertEqual(result["occurrenceCandidates"][2]["publishDecision"]["result"], "unresolved")

    def test_closed_endpoint_witness_requires_exact_numeric_lexical_identity(self) -> None:
        request_lines = [
            {
                "sourcePhysicalIndex": 4,
                "lineId": "line-4",
                "words": [
                    {"wordId": "word-1", "acousticText": "one"},
                    {"wordId": "word-2", "acousticText": "two"},
                ],
            }
        ]
        result = {
            "formatVersion": 1,
            "engine": "closed",
            "route": "closed-route",
            "durationMs": 2_000,
            "request": {"sha256": "a" * 64, "byteCount": 12},
            "audio": {"sha256": "b" * 64, "byteCount": 34},
            "lineCount": 1,
            "lines": [
                {
                    "lineIndex": 0,
                    "sourcePhysicalIndex": 4,
                    "lineId": "line-4",
                    "startMs": 100,
                    "endMs": 900,
                    "words": [
                        {"wordId": "word-1", "startMs": 100, "endMs": 400},
                        {"wordId": "word-2", "startMs": 500, "endMs": 900},
                    ],
                }
            ],
        }
        candidates = _closed_endpoint_witness_candidates(
            result,
            expected_engine="closed",
            expected_route="closed-route",
            expected_format_version=1,
            duration_ms=2_000,
            request_sha256="a" * 64,
            request_byte_count=12,
            audio_identity={"sha256": "b" * 64, "byteCount": 34},
            request_lines=request_lines,
        )
        self.assertEqual((candidates[0]["startMs"], candidates[0]["endMs"]), (100, 900))
        result["lines"][0]["startMs"] = 100.0
        with self.assertRaisesRegex(ValueError, "line result"):
            _closed_endpoint_witness_candidates(
                result,
                expected_engine="closed",
                expected_route="closed-route",
                expected_format_version=1,
                duration_ms=2_000,
                request_sha256="a" * 64,
                request_byte_count=12,
                audio_identity={"sha256": "b" * 64, "byteCount": 34},
                request_lines=request_lines,
            )

    def test_future_alignment_parser_preserves_id_and_attempt_token(self) -> None:
        attempt_id = "d" * 32
        parsed = lyrics_align_parser().parse_args(
            [
                "--config",
                "config.json",
                "future-align",
                "--video-id=-AbCdEf1234",
                f"--attempt-id={attempt_id}",
                "--analysis-audio=retained/analysis.wav",
                "--future-root=future-state",
                "--analysis-root=temporary-state",
                f"--expected-mix-sha256={'a' * 64}",
                f"--editorial-sha256={'b' * 64}",
                f"--canonical-plain-sha256={'c' * 64}",
            ]
        )
        self.assertEqual(parsed.video_id, "-AbCdEf1234")
        self.assertEqual(parsed.attempt_id, attempt_id)
        self.assertEqual(parsed.analysis_audio, Path("retained/analysis.wav"))
        self.assertEqual(parsed.future_root, Path("future-state"))
        self.assertEqual(parsed.analysis_root, Path("temporary-state"))
        self.assertEqual(parsed.expected_mix_sha256, "a" * 64)
        self.assertEqual(parsed.editorial_sha256, "b" * 64)
        self.assertEqual(parsed.canonical_plain_sha256, "c" * 64)

    def test_future_alignment_failure_and_retry_retain_canonical_plain_hash(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            generated_root = root / "generated"
            generated_root.mkdir()
            workspace = AlignmentWorkspace(root / "future-songs", workspace_root=root)
            args = SimpleNamespace(model="large-v3", canonical_plain_sha256="c" * 64)
            paths = (
                root / "owner-input",
                root / "runtime",
                root / "model-cache",
                workspace,
            )
            with patch('stemwise_engine.lyrics_align_pc._align', side_effect=RuntimeError("failed")):
                for attempt_id in ("a" * 32, "b" * 32):
                    with self.assertRaisesRegex(RuntimeError, "failed"):
                        _run_future_alignment_attempt(
                            {"analysisTimeoutMinutes": 1},
                            args,
                            video_id="KbKqkR3Q6SA",
                            attempt_id=attempt_id,
                            workspace_root=root,
                            generated_root=generated_root,
                            future_workspace=workspace,
                            future_paths=paths,
                        )
                    state = json.loads(
                        workspace.state_path("KbKqkR3Q6SA").read_text(encoding="utf-8")
                    )
                    self.assertEqual(state["attemptId"], attempt_id)
                    self.assertEqual(state["canonicalPlainSha256"], "c" * 64)
                    self.assertEqual(state["status"], "retryable-technical")
                    self.assertNotIn("lyrics", json.dumps(state).lower())

    def test_standalone_alignment_snapshots_plain_before_later_failure(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            video_id = "KbKqkR3Q6SA"
            sidecars = root / "owner-input"
            sidecars.mkdir()
            (sidecars / f"{video_id}.lyrics.json").write_bytes(b"fixture")
            workspace = AlignmentWorkspace(root / "alignment", workspace_root=root)
            lyrics = {"status": "partial", "tracks": [plain_original("Private exact words")]}
            original_raw = json.dumps(lyrics, sort_keys=True).encode("utf-8")
            expected_sha = hashlib.sha256(original_raw).hexdigest()
            args = SimpleNamespace(
                from_position=None,
                to_position=None,
                model="large-v3",
                device="cpu",
                compute_type="float32",
                expected_mix_sha256="a" * 64,
                analysis_audio=root / "analysis.wav",
                realign_synced=False,
                no_commit=False,
            )
            with (
                patch('stemwise_engine.lyrics_align_pc._verify_alignment_runtime', return_value={}),
                patch('stemwise_engine.lyrics_align_pc._verify_pinned_stack', return_value={}),
                patch('stemwise_engine.lyrics_align_pc._whisper_snapshot_path', return_value=root / "whisper"),
                patch('stemwise_engine.lyrics_align_pc.load_sidecar', return_value=(lyrics, original_raw)),
                patch('stemwise_engine.lyrics_align_pc.validate_child_zip', side_effect=RuntimeError("failed")),
            ):
                report = _align(
                    {},
                    args,
                    selected_override=[{"position": 1, "videoId": video_id}],
                    paths_override=(sidecars, root / "runtime", root / "models", workspace),
                    temp_root_override=root / "temporary",
                )
            backup = workspace.current_backup_path(video_id)
            self.assertEqual(backup.read_bytes(), original_raw)
            self.assertEqual(report["results"][0]["canonicalPlainSha256"], expected_sha)
            self.assertNotIn("Private exact words", json.dumps(report))

    def test_future_alignment_rejects_stale_canonical_plain_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            video_id = "KbKqkR3Q6SA"
            sidecars = root / "owner-input"
            sidecars.mkdir()
            (sidecars / f"{video_id}.lyrics.json").write_bytes(b"fixture")
            workspace = AlignmentWorkspace(root / "alignment", workspace_root=root)
            lyrics = {"status": "partial", "tracks": [plain_original("Current words")]}
            original_raw = json.dumps(lyrics, sort_keys=True).encode("utf-8")
            expected_sha = hashlib.sha256(original_raw).hexdigest()
            _, stale_sha = snapshot_canonical_plain(
                workspace,
                video_id,
                b'{"stale":"authority"}',
            )
            args = SimpleNamespace(
                from_position=None,
                to_position=None,
                model="large-v3",
                device="cpu",
                compute_type="float32",
                expected_mix_sha256="a" * 64,
                analysis_audio=root / "analysis.wav",
                canonical_plain_sha256=expected_sha,
                realign_synced=False,
                no_commit=False,
            )
            with (
                patch('stemwise_engine.lyrics_align_pc._verify_alignment_runtime', return_value={}),
                patch('stemwise_engine.lyrics_align_pc._verify_pinned_stack', return_value={}),
                patch('stemwise_engine.lyrics_align_pc._whisper_snapshot_path', return_value=root / "whisper"),
                patch('stemwise_engine.lyrics_align_pc.load_sidecar', return_value=(lyrics, original_raw)),
            ):
                report = _align(
                    {},
                    args,
                    selected_override=[{"position": 1, "videoId": video_id}],
                    paths_override=(sidecars, root / "runtime", root / "models", workspace),
                    temp_root_override=root / "temporary",
                    state_fields_override={"canonicalPlainSha256": expected_sha},
                )
            self.assertNotEqual(stale_sha, expected_sha)
            self.assertEqual(report["counts"]["failed"], 1)
            self.assertEqual(report["results"][0]["canonicalPlainSha256"], expected_sha)
            self.assertRegex(report["results"][0]["reasons"][0], r"^ValueError:sha256:[0-9a-f]{64}$")
            self.assertNotIn("Current words", json.dumps(report))

    def test_corpus_validator_calls_contract_validator(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            project = root / "builder"
            sidecars = project / "owner-input"
            output = root / "output"
            sidecars.mkdir(parents=True)
            (output / "children").mkdir(parents=True)
            (output / 'work').mkdir()
            (output / 'work/inventory.json').write_text(json.dumps({'items':[{'videoId':'example1234'}]}), encoding='utf-8')
            (sidecars / "example1234.lyrics.json").write_text("{}", encoding="utf-8")
            lyrics = {
                "tracks": [
                    {
                        "kind": "original",
                        "format": "synced",
                        "lines": [
                            {"startMs": 1000, "endMs": 2000, "text": "Approved words"}
                        ],
                    }
                ]
            }
            with (
                patch('stemwise_engine.lyrics_align_pc.load_sidecar', return_value=(lyrics, None)),
                patch(
                    'stemwise_engine.lyrics_align_pc.validate_child_zip',
                    return_value={"analysis": {"durationMs": 10_000}},
                ),
                patch('stemwise_engine.lyrics_align_pc.validate_lyrics') as validator,
            ):
                result = _validate(
                    {"projectDir": str(project), "outputDir": str(output)},
                    SimpleNamespace(video_id=[]),
                )
        self.assertTrue(result["valid"])
        self.assertEqual(result["syncedValidated"], 1)
        validator.assert_called_once_with(lyrics, duration_ms=10_000)

    def test_corpus_auto_validation_excludes_retained_noncampaign_sidecars(self):
        with tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[3]/'Test suite/work') as name:
            root = Path(name)
            project, output = root/'builder', root/'output'
            sidecars = project/'owner-input'
            sidecars.mkdir(parents=True)
            (output/'work').mkdir(parents=True)
            (output/'work/inventory.json').write_text('{"items":[]}', encoding='utf-8')
            retained = sidecars/'z9Y8x7W6v5U.lyrics.json'
            retained.write_bytes(b'preserved alternative; never automatically loaded')
            config = {'projectDir':str(project), 'outputDir':str(output)}
            with patch('stemwise_engine.lyrics_align_pc.load_sidecar', side_effect=ValueError('exact requested content remains blocked')) as load:
                result = _validate(config, SimpleNamespace(video_id=[]))
                load.assert_not_called()
                self.assertEqual(result['skippedIds'], ['z9Y8x7W6v5U'])
                explicit = _validate(config, SimpleNamespace(video_id=['z9Y8x7W6v5U']))
                self.assertEqual(explicit['invalid'], [{'videoId':'z9Y8x7W6v5U', 'code':'ValueError'}])
            self.assertEqual(retained.read_bytes(), b'preserved alternative; never automatically loaded')

    def test_corpus_validator_redacts_private_failure_text(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            project = root / "builder"
            sidecars = project / "owner-input"
            output = root / "output"
            sidecars.mkdir(parents=True)
            (output / "children").mkdir(parents=True)
            (output / 'work').mkdir()
            (output / 'work/inventory.json').write_text(json.dumps({'items':[{'videoId':'example1234'}]}), encoding='utf-8')
            (sidecars / "example1234.lyrics.json").write_text("{}", encoding="utf-8")
            with patch(
                'stemwise_engine.lyrics_align_pc.load_sidecar',
                side_effect=ValueError("C:/private/lyrics.txt secret words"),
            ):
                result = _validate(
                    {"projectDir": str(project), "outputDir": str(output)},
                    SimpleNamespace(video_id=[]),
                )
        self.assertEqual(
            result["invalid"],
            [{"videoId": "example1234", "code": "ValueError"}],
        )
        self.assertNotIn("private", json.dumps(result))

    def test_unsupported_whisper_language_falls_back_to_auto_without_retagging(self) -> None:
        class FakeModel:
            def __init__(self) -> None:
                self.languages = []

            def transcribe(self, audio, *, language, prompt, precision=False):
                self.languages.append(language)
                if language == "tn":
                    raise ValueError("'tn' is not a valid language code")
                return transcript("Alpha beta gamma delta"), {"detectedLanguage": "en"}

        model = FakeModel()
        words, evidence = _transcribe_with_language_fallback(
            model,
            Path("fixture.wav"),
            language="tn",
            prompt="approved words",
            precision=True,
        )
        self.assertEqual(model.languages, ["tn", None])
        self.assertEqual(len(words), 4)
        self.assertEqual(evidence["requestedLanguage"], "tn")
        self.assertEqual(
            evidence["languageFallback"], "auto-detect-unsupported-whisper-code"
        )

    def test_caption_network_failure_is_nonfatal_for_local_fallback(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root)
            events = []
            with patch(
                'stemwise_engine.lyrics_align_pc.download_youtube_caption_evidence',
                side_effect=RuntimeError("transient provider throttle"),
            ):
                result = _load_caption(
                    video_id="KbKqkR3Q6SA",
                    original=plain_original("Alpha beta gamma delta"),
                    workspace=workspace,
                    temp_root=root / "temp",
                    record_outcome=lambda *event: events.append(event),
                )
        self.assertIsNone(result)
        self.assertEqual(events, [("transient-failure", False, "RuntimeError")])

    def test_corrupt_caption_cache_is_recomputed_atomically(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root)
            cache = workspace.caption_path("KbKqkR3Q6SA")
            cache.parent.mkdir(parents=True)
            original = plain_original("Private fixture")
            cache.write_text(
                json.dumps(
                    {
                        "cacheVersion": TRANSCRIPT_CACHE_VERSION,
                        "videoId": "KbKqkR3Q6SA",
                        "language": "en",
                        "cacheIdentity": _current_cache_identity(original),
                        "provenance": {"source": "youtube-caption"},
                        "words": [{"malformed": True}],
                    }
                ),
                encoding="utf-8",
            )
            events = []
            with patch(
                'stemwise_engine.lyrics_align_pc.download_youtube_caption_evidence', return_value=None
            ) as download:
                result = _load_caption(
                    video_id="KbKqkR3Q6SA",
                    original=original,
                    workspace=workspace,
                    temp_root=root / "temp",
                    record_outcome=lambda *event: events.append(event),
                )
            self.assertIsNone(result)
            download.assert_called_once()
            self.assertTrue(json.loads(cache.read_text(encoding="utf-8"))["unavailable"])
            self.assertEqual(events, [("unavailable", False, None)])

    def test_optional_cache_json_treats_damaged_entries_as_misses(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            cache = Path(name) / "cache.json"
            cache.write_text('{"valid":true}', encoding="utf-8")
            self.assertEqual(_read_optional_cache_json(cache), {"valid": True})
            cache.write_text('{"truncated":', encoding="utf-8")
            self.assertIsNone(_read_optional_cache_json(cache))
            cache.write_text('["not-an-object"]', encoding="utf-8")
            self.assertIsNone(_read_optional_cache_json(cache))
            cache.write_text('{"oversized":"xxxxxxxxxxxxxxxx"}', encoding="utf-8")
            with patch('stemwise_engine.lyrics_align_pc.MAX_JSON_BYTES', 16):
                self.assertIsNone(_read_optional_cache_json(cache))

    def test_unprompted_precision_holdout_is_cached_separately(self) -> None:
        class FakeModel:
            def __init__(self) -> None:
                self.prompts: list[str | None] = []

            def transcribe(self, audio, *, language, prompt, precision=False):
                self.prompts.append(prompt)
                return transcript("Alpha beta gamma delta"), {"wordCount": 4}

        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            audio = root / "audio.wav"
            write_test_wav(audio, duration_ms=10_000)
            workspace = AlignmentWorkspace(root)
            model = FakeModel()
            arguments = {
                "video_id": "KbKqkR3Q6SA",
                "original": plain_original("Private approved words"),
                "workspace": workspace,
                "model_name": "large-v3",
                "model_cache": root / "models",
                "language": "en",
                "separate_vocals": False,
                "project_dir": root,
                "device": "cpu",
                "compute_type": "float32",
                "duration_ms": 10_000,
                "get_model": lambda: model,
                "temp_root": root / "temp",
                "timeout_seconds": 30,
                "rerun": False,
                "precision": True,
                "use_approved_prompt": False,
            }
            with patch(
                'stemwise_engine.lyrics_align_pc.download_analysis_audio',
                return_value=({}, audio),
            ):
                first = _load_or_transcribe(**arguments)
                second = _load_or_transcribe(**arguments)
                edited = _load_or_transcribe(**{
                    **arguments, "original": plain_original("Edited words and repeated lines")
                })
                self.assertTrue(edited[2], "unprompted recognition does not consume the lyric draft")
                self.assertEqual(edited[0], first[0])
                cache = workspace.transcript_path(
                    "KbKqkR3Q6SA", "en", "precision-unprompted-mix"
                )
                stale = json.loads(cache.read_text())
                stale["provenance"]["decoder"]["conditionOnPreviousText"] = True
                cache.write_text(json.dumps(stale))
                refreshed = _load_or_transcribe(**arguments)
                self.assertFalse(json.loads(cache.read_text())["provenance"]["decoder"]["conditionOnPreviousText"])
                committed = cache.read_bytes()
                with patch.object(model, 'transcribe', side_effect=RuntimeError('replacement recognition failed')):
                    with self.assertRaisesRegex(RuntimeError, 'replacement recognition failed'):
                        _load_or_transcribe(**{**arguments, 'rerun': True})
                self.assertEqual(cache.read_bytes(), committed)
        self.assertEqual(model.prompts, ["", ""])
        self.assertEqual(first[1]["promptMode"], "withheld")
        self.assertFalse(first[2])
        self.assertTrue(second[2])
        self.assertFalse(refreshed[2])
        self.assertTrue(cache.name.endswith("precision-unprompted-mix.private.json"))

    def test_future_transcription_consumes_retained_pcm_without_reacquisition(self) -> None:
        class FakeModel:
            def __init__(self) -> None:
                self.audio: Path | None = None

            def transcribe(self, audio, *, language, prompt, precision=False):
                self.audio = Path(audio)
                return transcript("Alpha beta gamma delta"), {"wordCount": 4}

        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            audio = root / "analysis.wav"
            write_test_wav(audio)
            model = FakeModel()
            with patch(
                'stemwise_engine.lyrics_align_pc.download_analysis_audio',
                side_effect=AssertionError("future alignment must not reacquire audio"),
            ):
                _, _, reused = _load_or_transcribe(
                    video_id="KbKqkR3Q6SA",
                    original=plain_original("Private approved words"),
                    workspace=AlignmentWorkspace(root / "alignment"),
                    model_name="large-v3",
                    model_cache=root / "models",
                    language="en",
                    separate_vocals=False,
                    project_dir=root,
                    device="cpu",
                    compute_type="float32",
                    duration_ms=1_000,
                    get_model=lambda: model,
                    temp_root=root / "temp",
                    timeout_seconds=30,
                    rerun=False,
                    expected_mix_sha256=hashlib.sha256(audio.read_bytes()).hexdigest(),
                    analysis_audio=audio,
                )
        self.assertFalse(reused)
        self.assertEqual(model.audio, audio)

    def test_prompted_recognition_reuses_unchanged_inputs_after_downstream_changes(self) -> None:
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parent.parent / "Test suite/work") as name:
            root = Path(name)
            audio = root / "audio.wav"
            write_test_wav(audio)
            model = SimpleNamespace(transcribe=Mock(return_value=(
                transcript("Alpha beta gamma delta"), {"wordCount": 4})))
            arguments = dict(
                video_id="KbKqkR3Q6SA", original=plain_original("Alpha beta"),
                workspace=AlignmentWorkspace(root / "alignment", workspace_root=root),
                model_name="large-v3", model_cache=root / "models", language="en",
                separate_vocals=False, project_dir=root,
                device="cpu", compute_type="float32", duration_ms=1000,
                get_model=lambda: model, temp_root=root / "temp", timeout_seconds=30,
                rerun=False, analysis_audio=audio,
                expected_mix_sha256=hashlib.sha256(audio.read_bytes()).hexdigest())
            with patch('stemwise_engine.lyrics_align_pc._current_cache_identity', return_value={"alignment": "old"}) as identity:
                first = _load_or_transcribe(**arguments)
                self.assertFalse(first[2])
                identity.return_value = {"alignment": "new"}
                reused = _load_or_transcribe(**arguments)
                self.assertTrue(reused[2], "Downstream code does not change the prompted recognition request")
                self.assertEqual(reused[0], first[0])
                self.assertEqual(model.transcribe.call_count, 1)
                changed_prompt = _load_or_transcribe(**{
                    **arguments, "original": plain_original("Changed approved words")})
                self.assertFalse(changed_prompt[2])
                self.assertEqual(model.transcribe.call_args.kwargs["prompt"], "Changed approved words")
                raw = audio.read_bytes()
                audio.write_bytes(raw[:-2] + b"\x01\x00")
                changed_audio = _load_or_transcribe(**{
                    **arguments, "original": plain_original("Changed approved words"),
                    "expected_mix_sha256": hashlib.sha256(audio.read_bytes()).hexdigest()})
                self.assertFalse(changed_audio[2])
                self.assertEqual(model.transcribe.call_count, 3)

    def test_alignment_rejects_pcm_that_differs_from_completed_analysis(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            audio = root / "audio.wav"
            write_test_wav(audio)
            workspace = AlignmentWorkspace(root / "agent-research", workspace_root=root)
            with self.assertRaisesRegex(RuntimeError, "does not match completed analysis"):
                write_audio_provenance(
                    workspace,
                    "KbKqkR3Q6SA",
                    duration_ms=1_000,
                    mix_audio=audio,
                    expected_mix_sha256="0" * 64,
                )
            self.assertFalse(workspace.audio_provenance_path("KbKqkR3Q6SA").exists())

    def test_demucs_vocal_separation_disables_random_shifts(self) -> None:
        seen: list[str] = []

        def fake_demucs(command: list[str], **_: object) -> SimpleNamespace:
            from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
            self.assertIsNotNone(process_lock_descriptor(heavy_worker_lock_path({'projectDir': str(project)})))
            self.assertEqual(command[0], str(project.parent/'custom/main/bin/python3.10'))
            seen.extend(command)
            output = Path(command[command.index("--out") + 1]) / "htdemucs" / "song"
            output.mkdir(parents=True)
            (output / "vocals.wav").write_bytes(b"R" * 45)
            return SimpleNamespace(returncode=0, stdout="", stderr="")

        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            project = root/'builder'
            prepare_runtime_layout(project)
            layout_path = root/'runtime-layout.json'
            layout = json.loads(layout_path.read_text(encoding='utf-8'))
            layout['environments']['main'] = 'custom/main/bin/python3.10'
            layout_path.write_text(json.dumps(layout), encoding='utf-8')
            audio = root / "mix.wav"
            audio.write_bytes(b"mix")
            with patch('stemwise_engine.lyrics_alignment.run_process_tree', side_effect=fake_demucs):
                separate_vocals_demucs(
                    audio,
                    root / "out",
                    project_dir=project,
                    device="cpu",
                    timeout_seconds=5,
                )
        index = seen.index("--shifts")
        self.assertEqual(seen[index : index + 2], ["--shifts", "0"])

    def test_precision_vocals_cache_is_distinct_from_standard_vocals(self) -> None:
        class FakeModel:
            def __init__(self) -> None:
                self.calls: list[tuple[str | None, bool]] = []

            def transcribe(self, audio, *, language, prompt, precision=False):
                self.calls.append((prompt, precision))
                return transcript("Alpha beta gamma delta"), {"wordCount": 4}

        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            audio = root / "audio.wav"
            vocals = root / "vocals.wav"
            write_test_wav(audio, duration_ms=10_000)
            write_test_wav(vocals, duration_ms=10_000)
            demucs_model = (
                root
                / "torch"
                / "hub"
                / "checkpoints"
                / "955717e8-8726e21a.th"
            )
            demucs_model.parent.mkdir(parents=True)
            demucs_model.write_bytes(b"pinned-test-model")
            workspace = AlignmentWorkspace(root / "alignment")
            model = FakeModel()
            arguments = {
                "video_id": "KbKqkR3Q6SA",
                "original": plain_original("Private approved words"),
                "workspace": workspace,
                "model_name": "large-v3",
                "model_cache": root / "models",
                "language": "en",
                "separate_vocals": True,
                "project_dir": root,
                "device": "cpu",
                "compute_type": "float32",
                "duration_ms": 10_000,
                "get_model": lambda: model,
                "temp_root": root / "temp",
                "timeout_seconds": 30,
                "rerun": False,
                "use_approved_prompt": True,
            }
            with (
                patch(
                    'stemwise_engine.lyrics_align_pc.download_analysis_audio',
                    return_value=({}, audio),
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc.separate_vocals_demucs',
                    return_value=vocals,
                ),
            ):
                standard_first = _load_or_transcribe(**arguments, precision=False)
                standard_second = _load_or_transcribe(**arguments, precision=False)
                precision_first = _load_or_transcribe(**arguments, precision=True)
                precision_second = _load_or_transcribe(**arguments, precision=True)

            standard_cache = workspace.transcript_path(
                "KbKqkR3Q6SA", "en", "vocals"
            )
            precision_cache = workspace.transcript_path(
                "KbKqkR3Q6SA", "en", "precision-vocals"
            )
            standard_payload = json.loads(standard_cache.read_text(encoding="utf-8"))
            precision_payload = json.loads(precision_cache.read_text(encoding="utf-8"))

            # Borrowed PCM belongs to the attempt, not a transcript's scratch
            # directory. Each decoder may request it; cache hits must not.
            shared_model = FakeModel()
            provider = Mock(return_value=vocals)
            shared_arguments = {**arguments, "get_model": lambda: shared_model,
                                "vocals_provider": provider}
            with patch('stemwise_engine.lyrics_align_pc.download_analysis_audio', return_value=({}, audio)), \
                 patch('stemwise_engine.lyrics_align_pc.separate_vocals_demucs',
                       side_effect=AssertionError("borrowed vocals must not be separated again")):
                for precision in (False, True):
                    first = _load_or_transcribe(**{**shared_arguments, "rerun": True}, precision=precision)
                    cached = _load_or_transcribe(**shared_arguments, precision=precision)
                    self.assertFalse(first[2])
                    self.assertTrue(cached[2])
                    self.assertTrue(vocals.is_file())
            self.assertEqual(provider.call_count, 2)
            self.assertEqual([call[1] for call in shared_model.calls], [False, True])

        self.assertEqual(len(model.calls), 2)
        self.assertEqual([value[1] for value in model.calls], [False, True])
        self.assertFalse(standard_first[2])
        self.assertTrue(standard_second[2])
        self.assertFalse(precision_first[2])
        self.assertTrue(precision_second[2])
        self.assertNotEqual(standard_cache.name, precision_cache.name)
        self.assertEqual(standard_payload["model"], "large-v3+htdemucs")
        self.assertEqual(
            precision_payload["model"], "large-v3+htdemucs+precision"
        )
        self.assertEqual(
            standard_payload["provenance"]["decoder"]["beamSize"], 5
        )
        self.assertEqual(
            precision_payload["provenance"]["decoder"]["beamSize"], 10
        )
        transform = standard_payload["provenance"]["audio"]["canonicalTimeTransform"]
        self.assertEqual(transform["separatorLatencyFrames"], 0)
        self.assertEqual(
            transform["separatorLatencyStatus"],
            "zero-by-offline-index-contract",
        )
        self.assertEqual(transform["measuredResidualFrames"], 0)


    def test_json3_parser_ignores_rolling_append_updates(self) -> None:
        payload = {
            "events": [
                {
                    "tStartMs": 1000,
                    "dDurationMs": 2000,
                    "segs": [
                        {"utf8": "Alpha beta", "tOffsetMs": 0},
                        {"utf8": " gamma", "tOffsetMs": 1000},
                    ],
                },
                {
                    "tStartMs": 2000,
                    "dDurationMs": 1000,
                    "aAppend": 1,
                    "segs": [{"utf8": " gamma"}],
                },
                {
                    "tStartMs": 3000,
                    "dDurationMs": 1000,
                    "segs": [{"utf8": "Delta"}],
                },
            ]
        }
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / "caption.json3"
            path.write_text(json.dumps(payload), encoding="utf-8")
            words = parse_youtube_json3(path)
        self.assertEqual([word.word for word in words], ["alpha", "beta", "gamma", "delta"])

    def predicted_word_case(self, *, wav=False):
        original = {**plain_original("🎵 Café l'été\n\n🎵 Café l'été"), 'language':'fr', 'source': {'name':'Known fixture'}}
        companion = {'id':'english','kind':'translation','language':'en','script':'Latn','format':'plain',
            'basedOnTrackId':'original','text':'Coffee in summer\n\nCoffee in summer','source':{'name':'Reviewed fixture'}}
        lyrics = {'status':'partial','reason':'Fixture timing pending','tracks':[original,companion]}
        mapping = build_lexical_occurrence_mapping('KbKqkR3Q6SA',original,(0,2))
        known = (((120,380),(800,1670)),((2410,2790),(4000,4970)))
        lines = []
        for index,(owner,intervals) in enumerate(zip(mapping['performedOccurrences'],known)):
            words = [{'wordId':word['occurrenceWordId'],'startMs':start,'endMs':end,
                      'confidence':.02,'acousticTokenCount':1,'romanizedWordCount':1}
                     for word,(start,end) in zip(owner['words'],intervals)]
            lines.append({'lineIndex':index,'sourcePhysicalIndex':owner['sourcePhysicalIndex'],
                'lineId':owner['occurrenceId'],'startMs':intervals[0][0],'endMs':intervals[-1][1],
                'confidence':.02,'minimumWordConfidence':.02,'displayWordCount':2,
                'acousticTokenCount':2,'romanizedWordCount':2,'words':words})
        forced = {'formatVersion':4 if wav else 5,
            'engine':'wav2vec2-ctc-forced-alignment' if wav else 'torchaudio-mms-fa',
            **mms_timeline(5000),'segmentCount':1,'segmentBoundaryGuardMs':250,
            'displayWordCount':4,'acousticTokenCount':4,'romanizedWordCount':4,'lineCount':2,
            'confidence':{key:.02 for key in ('wordP10','wordP50','wordP90','lineP10','lineP50','lineP90')},'lines':lines}
        if wav:
            forced['requestFormatVersion'] = MMS_REQUEST_VERSION
            forced.pop('romanizedWordCount')
            for line in lines:
                line.pop('romanizedWordCount')
                for word in line['words']: word.pop('romanizedWordCount')
        forced = json.loads(json.dumps(forced))
        _validate_numeric_mms_result(forced,expected_sources=[0,2],duration_ms=5000,
            expected_line_ids=[owner['occurrenceId'] for owner in mapping['performedOccurrences']],
            expected_word_ids=[[word['occurrenceWordId'] for word in owner['words']] for owner in mapping['performedOccurrences']],
            expected_format_version=4 if wav else 5,expected_engine=forced['engine'],expected_segments=[{
                'segmentIndex':0,'startLineIndex':0,'endLineIndex':2,'windowStartMs':0,'windowEndMs':5000}])
        return lyrics,mapping,forced

    def test_inventory_cli_counts_every_accepted_format_and_only_invalid_sidecars_as_invalid(self):
        import io
        from contextlib import redirect_stdout, redirect_stderr
        import stemwise_engine.lyrics_align_pc as worker
        from stemwise_engine.lyrics_alignment import build_predicted_sidecar, inventory_summary
        lyrics, mapping, forced = self.predicted_word_case()
        regional = build_predicted_sidecar(lyrics, mapping, forced, 5000,
            basis='Known complete acoustic prediction; timing unverified.')
        result = accepted_result((0, 2))
        synced = build_synced_sidecar(lyrics, result, 5000, line_timings=line_timings(result, 5000))
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            root = Path(directory)
            project = root/'builder'
            sidecars = project/'owner-input'
            sidecars.mkdir(parents=True)
            for name, value, track_format in (
                    ('plain', lyrics, 'plain'), ('synced', synced, 'synced'),
                    ('regional', regional, 'regional'),
                    ('unavailable', {'status': 'unavailable', 'reason': 'Known lyric absence', 'tracks': []}, None)):
                path = sidecars/f'{name}.lyrics.json'
                path.write_text(json.dumps(value), encoding='utf-8')
                loaded, _ = load_sidecar(path, root=sidecars)
                self.assertEqual(loaded['tracks'][0]['format'] if loaded['tracks'] else None, track_format)
            invalid = sidecars/'invalid.lyrics.json'
            invalid.write_text('{}', encoding='utf-8')
            with self.assertRaises(ValueError):
                load_sidecar(invalid, root=sidecars)
            before = {path: path.read_bytes() for path in sidecars.iterdir()}
            expected = {'sidecars': 5, 'plainOriginal': 1, 'syncedOriginal': 1,
                'regionalOriginal': 1, 'unavailable': 1, 'invalid': 1}
            stdout, stderr = io.StringIO(), io.StringIO()
            with patch.object(worker, 'load_config', return_value={
                    'projectDir': str(project), 'outputDir': str(root/'runtime')}), \
                    redirect_stdout(stdout), redirect_stderr(stderr):
                code = worker.main(['--config', str(root/'config.json'), 'inventory'])
            self.assertEqual(code, 0, stderr.getvalue())
            self.assertEqual(json.loads(stdout.getvalue()), expected)
            self.assertEqual(inventory_summary(sidecars), expected)
            self.assertEqual({path: path.read_bytes() for path in sidecars.iterdir()}, before)

    def test_predicted_sidecar_preserves_complete_acoustic_words_as_estimates(self):
        import copy
        import stemwise_engine.lyrics_alignment as lyrics_module
        from stemwise_engine.song_enrichment_v2 import require_publication_lyrics
        for wav in (False,True):
            with self.subTest(wav=wav):
                lyrics,mapping,forced = self.predicted_word_case(wav=wav)
                before = copy.deepcopy((lyrics,mapping,forced))
                candidate = lyrics_module.build_predicted_sidecar(lyrics,mapping,forced,5000,
                    basis='Complete-target acoustic CTC prediction; timing is unverified.')
                self.assertEqual((lyrics,mapping,forced),before)
                self.assertEqual(candidate['status'],'partial')
                self.assertEqual(candidate['tracks'][1],lyrics['tracks'][1])
                original = candidate['tracks'][0]
                self.assertEqual(original['format'],'regional')
                self.assertNotIn('text',original)
                self.assertEqual([line['text'] for line in original['lines']], ["🎵 Café l'été"]*2)
                self.assertEqual([(line['timing']['startMs'],line['timing']['endMs']) for line in original['lines']],
                    [(120,1670),(2410,4970)])
                self.assertEqual([[(word['startUtf16'],word['endUtf16']) for word in line['words']]
                    for line in original['lines']], [[(3,7),(8,13)]]*2)
                self.assertEqual([[(word['timing']['startMs'],word['timing']['endMs']) for word in line['words']]
                    for line in original['lines']], [[(120,380),(800,1670)],[(2410,2790),(4000,4970)]])
                self.assertNotEqual(mapping['performedOccurrences'][0]['words'],mapping['performedOccurrences'][1]['words'])
                for line in original['lines']:
                    self.assertEqual((line['contentStatus'],line['occurrenceStatus']),('admitted','supported'))
                    for timing in [line['timing'],*[word['timing'] for word in line['words']]]:
                        self.assertEqual(timing['status'],'estimated')
                        self.assertEqual(timing['basis'],'Complete-target acoustic CTC prediction; timing is unverified.')
                self.assertEqual(candidate['tokenizationVersion'],mapping['tokenizationVersion'])
                with self.assertRaises(ValueError): require_publication_lyrics(candidate)
                from stemwise_engine.editorial_admission import project_timed_companion
                from stemwise_engine.song_enrichment_v2 import validate_song
                from installed_contract import build_installed_document, validate_installed_document
                public = copy.deepcopy(candidate)
                original = public['tracks'][0]
                ipa = {'id': 'ipa', 'kind': 'transliteration', 'language': 'fr-fonipa', 'script': 'Latn',
                    'format': 'plain', 'basedOnTrackId': original['id'], 'text': 'kafe lete\n\nkafe lete',
                    'source': {'name': 'Known reviewed pronunciation fixture'}}
                public['tracks'] = [original, project_timed_companion(original, public['tracks'][1], (0, 2)),
                    project_timed_companion(original, ipa, (0, 2))]
                public.update(status='complete')
                public.pop('reason')
                document = {'formatVersion': 2, 'kind': 'stemwise.song-enrichment',
                    'source': {'provider': 'youtube', 'videoId': mapping['videoId']},
                    'generatedAt': '2026-11-13T12:00:00Z', 'generator': {'name': 'stemwise-pc', 'version': '2.1.0'},
                    'lyrics': public, 'artwork': {'file': 'cover.png', 'kind': 'other', 'source': {'name': 'Fixture'}}}
                validate_song(document, installed_duration_ms=5000)
                require_publication_lyrics(public)
                installed = build_installed_document(document, original_sha256='a'*64,
                    media={'original.opus': {'sha256': 'a'*64, 'byteLength': 1234}}, content_revision=1,
                    payload_sha256='b'*64, artwork_sha256='c'*64, artwork_bytes=100)
                readback = json.loads(json.dumps(installed))
                validate_installed_document(readback, expected_video_id=mapping['videoId'], expected_original_sha256='a'*64)
                self.assertEqual(readback['lyrics'], public)
                self.assertEqual(public['tracks'][0], candidate['tracks'][0])
                self.assertTrue(all(line['timing']['status'] == 'estimated' for track in public['tracks'] for line in track['lines']))

    def test_predicted_sidecar_rejects_incomplete_or_changed_word_targets_and_bounds(self):
        import copy
        import stemwise_engine.lyrics_alignment as lyrics_module
        lyrics,mapping,forced = self.predicted_word_case()
        mutations = {
            'missing-line': lambda value:value['lines'].pop(),
            'wrong-occurrence': lambda value:value['lines'][1].update(lineId=value['lines'][0]['lineId']),
            'wrong-source': lambda value:value['lines'][1].update(sourcePhysicalIndex=1),
            'missing-word': lambda value:value['lines'][1]['words'].pop(),
            'repeated-word': lambda value:value['lines'][1]['words'][1].update(wordId=value['lines'][0]['words'][0]['wordId']),
            'wrong-word': lambda value:value['lines'][0]['words'][0].update(wordId='wrong'),
            'missing-start': lambda value:value['lines'][0]['words'][0].pop('startMs'),
            'missing-end': lambda value:value['lines'][0]['words'][0].pop('endMs'),
            'endless-word': lambda value:value['lines'][0]['words'][0].update(endMs=None),
            'reversed-word': lambda value:value['lines'][0]['words'][0].update(endMs=119),
            'overlapping-words': lambda value:value['lines'][0]['words'][1].update(startMs=300),
            'outside-recording': lambda value:value['lines'][1]['words'][1].update(endMs=5001),
            'wrong-line-bound': lambda value:value['lines'][0].update(endMs=1600),
            'unsupported-producer': lambda value:value.update(engine='line-endpoint-prediction'),
        }
        for fault,mutate in mutations.items():
            with self.subTest(fault=fault):
                changed = copy.deepcopy(forced)
                mutate(changed)
                with self.assertRaises(ValueError):
                    lyrics_module.build_predicted_sidecar(lyrics,mapping,changed,5000,basis='Acoustic prediction')
        changed = copy.deepcopy(mapping)
        changed['performedOccurrences'][1]['words'][0]['occurrenceWordId'] = 'wrong'
        for invalid in (changed,build_lexical_occurrence_mapping('KbKqkR3Q6SA',lyrics['tracks'][0],(0,))):
            with self.assertRaises(ValueError):
                lyrics_module.build_predicted_sidecar(lyrics,invalid,forced,5000,basis='Acoustic prediction')
        with self.assertRaises(ValueError):
            lyrics_module.build_predicted_sidecar(lyrics,mapping,forced,5000,basis=' ')

    def owner_prediction_case(self, root):
        from stemwise_engine.common import atomic_write_json, json_bytes, sha256_file
        from stemwise_engine.lyrics_intake import read_recording_draft
        from stemwise_engine.lyric_content import OWNER_VERIFICATION_FILE
        import stemwise_engine.lyrics_align_pc as worker
        lyrics,mapping,forced = self.predicted_word_case()
        video = mapping['videoId']
        workspace = AlignmentWorkspace(root/'workflow', workspace_root=root)
        job = workspace.root.parent/'jobs'/video
        draft = job/'drafts'
        draft.mkdir(parents=True)
        (draft/'original.txt').write_bytes(lyrics['tracks'][0]['text'].encode('utf-8'))
        atomic_write_json(draft/'lyrics.meta.json', {'videoId':video,'originalLanguage':lyrics['tracks'][0]['language'],
            'originalScript':'Latn','sources':{'original':lyrics['tracks'][0]['source']}})
        _,canonical = snapshot_canonical_plain(workspace,video,json_bytes(lyrics))
        audio = root/'analysis'/video/'analysis.wav'
        audio.parent.mkdir(parents=True)
        write_test_wav(audio,duration_ms=5000)
        stream = {'sampleRate':48000,'channelCount':2,'frameCount':240000,'durationMs':5000}
        identity = {'sha256':sha256_file(audio),'byteCount':audio.stat().st_size,'stream':stream}
        _,draft_sha,_,metadata_sha = read_recording_draft(job)
        proof = job/OWNER_VERIFICATION_FILE
        atomic_write_json(proof, {'formatVersion':1,'videoId':video,'audioSha256':identity['sha256'],
            'draftSha256':draft_sha,'metadataSha256':metadata_sha,
            'mappingSha256':lexical_mapping_identity(mapping)['mappingSha256'],'spokenTargets':None,
            'reviewer':'Independent known-answer fixture','completedAt':'2026-11-13T19:00:00Z',
            'decision':'owner-verified-complete-performed-lyrics','timing':'allow-complete-predicted-words',
            'rationale':'The complete words and both performed occurrences were independently checked.'})
        project = root/'pc/builder'
        prepare_runtime_layout(project)
        (project/'.venv/bin').mkdir(parents=True)
        (project/'.venv/bin/python').touch()
        (project/'src').mkdir()
        for helper in ('mms_forced_alignment.py','ctc_worker.py','acoustic_inputs.py',
                       'worker_protocol.py','worker_publication.py','common.py'):
            (project/'src'/helper).write_bytes((PROJECT_ROOT/'src'/helper).read_bytes())
        model = project.parent/'model-cache/torch/hub/checkpoints/model.pt'
        model.parent.mkdir(parents=True)
        model.write_bytes(b'inert numerical model boundary')
        runtime = {'runtimeVersion':1,'bundle':'torchaudio.pipelines.MMS_FA',
            'supportedRequestVersions':[MMS_REQUEST_VERSION],'requestedDevice':'cpu','resolvedDevice':'cpu',
            'cudaAvailable':False, **worker.models.mms_runtime_pins(project)}
        temporary = root/'request'
        temporary.mkdir()
        return SimpleNamespace(lyrics=lyrics,mapping=mapping,forced=forced,video=video,workspace=workspace,
            canonical=canonical,audio=audio,identity=identity,proof=proof,project=project,model=model,
            runtime=runtime,temporary=temporary,
            config={'projectDir':str(project),'outputDir':str(root/'output'),'analysisTimeoutMinutes':20})

    def test_owner_word_prediction_runs_complete_acoustic_target_and_reuses_exact_cache(self):
        import copy
        from contextlib import ExitStack
        from stemwise_engine.common import atomic_write_json, sha256_file
        import stemwise_engine.lyrics_align_pc as worker
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            case = self.owner_prediction_case(Path(name))
            calls = []
            def numerical_boundary(**kwargs):
                calls.append(kwargs['request'])
                self.assertEqual(kwargs['request']['segments'],[{'segmentIndex':0,'startLineIndex':0,
                    'endLineIndex':2,'windowStartMs':0,'windowEndMs':5000}])
                self.assertEqual([line['lineId'] for line in kwargs['request']['lines']],
                    [line['occurrenceId'] for line in case.mapping['performedOccurrences']])
                self.assertEqual(kwargs['audio'],case.audio)
                self.assertEqual(kwargs['producer']['mmsModelSha256'],sha256_file(case.model))
                atomic_write_json(kwargs['output_path'],case.forced)
                return SimpleNamespace(returncode=0,stdout='',stderr=''),kwargs['request'],None
            options = (case.config,case.workspace,case.video,case.lyrics,case.canonical,
                case.audio,case.identity['sha256'],case.temporary)
            with ExitStack() as stack:
                stack.enter_context(patch.object(worker,'PINNED_MMS_SHA256',sha256_file(case.model)))
                stack.enter_context(patch.object(worker.audio_store,'file_identity',return_value=case.identity))
                probe = stack.enter_context(patch.object(worker,'_run_numerical_process',return_value=
                    SimpleNamespace(returncode=0,stdout=json.dumps(case.runtime),stderr='')))
                boundary = stack.enter_context(patch.object(worker,'_run_alignment_worker_batch',side_effect=numerical_boundary))
                first = worker.run_owner_word_prediction(*options,device='cpu')
                second = worker.run_owner_word_prediction(*options,device='cpu')
                self.assertEqual(first[:4],second[:4])
                self.assertEqual((first[-1],second[-1]),(False,True))
                self.assertEqual(len(calls),1)
                self.assertEqual(boundary.call_count,1)
                self.assertEqual(probe.call_count,2)  # A new operation rechecks its observed runtime.
                candidate,mapping,forced,admission,cache,_ = first
                self.assertEqual(forced['provenance']['audioViewId'],'mix')
                self.assertEqual(forced['provenance']['audioFamilyId'],'canonical-original-pcm')
                self.assertEqual(candidate['tracks'][1],case.lyrics['tracks'][1])
                self.assertTrue(cache.is_file())
                self.assertFalse(case.workspace.state_path(case.video).exists())
                self.assertFalse(case.workspace.candidate_path(case.video).exists())
                response = worker._owner_word_prediction_response(case.workspace,case.video,candidate,mapping,
                    forced,admission,case.canonical,case.identity['sha256'])
                self.assertNotIn(case.lyrics['tracks'][0]['text'],json.dumps(response))
                # Status/Task readback must not inspect PCM or launch a runtime/model.
                with patch.object(worker.audio_store,'file_identity',side_effect=AssertionError('passive PCM probe')), \
                        patch.object(worker,'_run_numerical_process',side_effect=AssertionError('passive runtime')), \
                        patch.object(worker.models,'require_pinned_file',side_effect=AssertionError('passive model weights')), \
                        patch.object(worker,'_run_alignment_worker_batch',side_effect=AssertionError('passive model')):
                    load = lambda:worker.load_owner_word_prediction(case.project,case.workspace,case.video,response,
                        original=case.lyrics['tracks'][0],indices=(0,2),canonical_sha=case.canonical,
                        audio_sha=case.identity['sha256'])
                    self.assertEqual(load(),(candidate,mapping,forced,admission))
                    model_bytes = case.model.read_bytes()
                    case.model.unlink()
                    self.assertEqual(load(),(candidate,mapping,forced,admission))
                    case.model.write_bytes(model_bytes)
                    wrong_model = copy.deepcopy(forced)
                    wrong_model['provenance']['mmsModelSha256'] = 'f'*64
                    wrong_response = worker._owner_word_prediction_response(case.workspace,case.video,candidate,mapping,
                        wrong_model,admission,case.canonical,case.identity['sha256'])
                    with self.assertRaises(ValueError):
                        worker.load_owner_word_prediction(case.project,case.workspace,case.video,wrong_response,
                            original=case.lyrics['tracks'][0],indices=(0,2),canonical_sha=case.canonical,
                            audio_sha=case.identity['sha256'])
                    helper = case.project/'src/mms_forced_alignment.py'
                    original_bytes = helper.read_bytes()
                    helper.write_bytes(original_bytes+b'\nCHANGED_PRODUCER = True\n')
                    with self.assertRaises(ValueError): load()
                    helper.write_bytes(original_bytes)
                    artifact = case.workspace.job_dir(case.video)/response['candidate']['path']
                    saved = artifact.read_bytes()
                    artifact.write_bytes(saved+b' ')
                    with self.assertRaises(ValueError): load()
                    artifact.write_bytes(saved)
                    retained = case.workspace.job_dir(case.video)/admission.evidence_path
                    retained_bytes = retained.read_bytes()
                    retained.unlink()
                    with self.assertRaises(worker.ContentEvidenceUnavailable): load()
                    self.assertFalse(retained.exists())
                    retained.write_bytes(retained_bytes)
                    case.proof.unlink()
                    with self.assertRaises(worker.LyricsContentBlocker): load()

    def test_owner_word_prediction_requires_exact_owner_before_pcm_or_model_and_preserves_terminal(self):
        from contextlib import ExitStack
        from stemwise_engine.common import sha256_file, WorkerRetirementError
        from stemwise_engine.provider_operations import ProviderCancelled, ProviderDeadline
        import stemwise_engine.lyrics_align_pc as worker
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
            case = self.owner_prediction_case(Path(name))
            options = (case.config,case.workspace,case.video,case.lyrics,case.canonical,
                case.audio,case.identity['sha256'],case.temporary)
            proof_bytes = case.proof.read_bytes()
            case.proof.unlink()
            with patch.object(worker.audio_store,'file_identity',side_effect=AssertionError('unapproved PCM')), \
                    patch.object(worker,'_run_numerical_process',side_effect=AssertionError('unapproved runtime')):
                with self.assertRaises(worker.LyricsContentBlocker):
                    worker.run_owner_word_prediction(*options,device='cpu')
            case.proof.write_bytes(proof_bytes)
            before = case.workspace.current_backup_path(case.video).read_bytes()
            for stop in (ProviderCancelled('owner cancelled'),ProviderDeadline('parent deadline'),
                         WorkerRetirementError('unretired worker')):
                with self.subTest(stop=type(stop).__name__), ExitStack() as stack:
                    stack.enter_context(patch.object(worker,'PINNED_MMS_SHA256',sha256_file(case.model)))
                    stack.enter_context(patch.object(worker.audio_store,'file_identity',return_value=case.identity))
                    stack.enter_context(patch.object(worker,'_run_numerical_process',return_value=
                        SimpleNamespace(returncode=0,stdout=json.dumps(case.runtime),stderr='')))
                    stack.enter_context(patch.object(worker,'_run_alignment_worker_batch',side_effect=stop))
                    with self.assertRaises(type(stop)):
                        worker.run_owner_word_prediction(*options,device='cpu')
                    self.assertEqual(case.workspace.current_backup_path(case.video).read_bytes(),before)
                    self.assertFalse(case.workspace.state_path(case.video).exists())
                    self.assertFalse(list(case.workspace.job_dir(case.video).glob('owner-word-prediction-*.private.json')))
            # Actual runtime and batch consumers must honor a terminal packet even at rc0.
            import io
            from contextlib import redirect_stderr
            for stop in (ProviderCancelled('cancelled'),ProviderDeadline('deadline'),WorkerRetirementError('retirement')):
                stream = io.StringIO()
                with redirect_stderr(stream): worker_protocol.emit_worker_failure(stop)
                completed = SimpleNamespace(returncode=0,stdout=json.dumps(case.runtime),stderr=stream.getvalue())
                with self.subTest(boundary='runtime',stop=type(stop).__name__), \
                        patch.object(worker,'_run_numerical_process',return_value=completed):
                    with self.assertRaises(type(stop)):
                        worker._mms_runtime_provenance(case.project/'.venv/bin/python',
                            case.project/'src/mms_forced_alignment.py',device='cpu',timeout_seconds=20,
                            diagnostic_path=case.temporary/'runtime.failure.private.txt')
                request,_,_,_ = worker._forced_alignment_request(case.video,[0,2],case.mapping,
                    [display_words(case.lyrics['tracks'][0]['text'].splitlines()[source]) for source in (0,2)],
                    duration_ms=5000)
                request_path = case.temporary/'terminal.request.json'
                from stemwise_engine.common import atomic_write_json
                atomic_write_json(request_path,request)
                with self.subTest(boundary='batch',stop=type(stop).__name__), \
                        patch('stemwise_engine.alignment_model_queue.prepare_emissions'), \
                        patch.object(worker,'_run_numerical_process',return_value=completed):
                    with self.assertRaises(type(stop)):
                        worker._run_alignment_worker_batch(config=case.config,
                            python=case.project/'.venv/bin/python',helper=case.project/'src/mms_forced_alignment.py',
                            audio=case.audio,audio_sha256=case.identity['sha256'],request=request,
                            request_path=request_path,output_path=case.temporary/'terminal.output.json',device='cpu',
                            timeout_seconds=20,cache_key='f'*64,producer={},environment={},
                            failure_path=case.temporary/'batch.failure.private.txt',repair=False)

    def test_owner_word_prediction_cli_emits_only_refs_after_complete_words_and_retirement(self):
        import copy
        import io
        from contextlib import ExitStack, nullcontext, redirect_stdout, redirect_stderr
        from stemwise_engine.common import atomic_write_json, sha256_file, WorkerRetirementError
        import stemwise_engine.lyrics_align_pc as worker
        for disposition in ('complete','missing-end','retirement'):
            with self.subTest(disposition=disposition), tempfile.TemporaryDirectory(
                    dir=PROJECT_ROOT.parents[1]/'Test suite/work') as name:
                case = self.owner_prediction_case(Path(name))
                prior = case.workspace.state_path(case.video)
                atomic_write_json(prior,{'status':'previous valid private state'})
                prior_bytes = prior.read_bytes()
                def numerical_boundary(**kwargs):
                    result = copy.deepcopy(case.forced)
                    if disposition == 'missing-end': result['lines'][1]['words'][1].pop('endMs')
                    atomic_write_json(kwargs['output_path'],result)
                    return SimpleNamespace(returncode=0,stdout='',stderr=''),kwargs['request'],None
                stdout,stderr = io.StringIO(),io.StringIO()
                with ExitStack() as stack:
                    stack.enter_context(patch.object(worker,'load_config',return_value=case.config))
                    stack.enter_context(patch.object(worker,'model_operation_slot',side_effect=lambda *_:nullcontext()))
                    stack.enter_context(patch.object(worker,'PINNED_MMS_SHA256',sha256_file(case.model)))
                    stack.enter_context(patch.object(worker.audio_store,'file_identity',return_value=case.identity))
                    stack.enter_context(patch.object(worker,'_run_numerical_process',return_value=
                        SimpleNamespace(returncode=0,stdout=json.dumps(case.runtime),stderr='')))
                    stack.enter_context(patch.object(worker,'_run_alignment_worker_batch',side_effect=numerical_boundary))
                    if disposition == 'retirement':
                        stack.enter_context(patch.object(worker._alignment_sessions._Session,'close',
                            side_effect=WorkerRetirementError('fixture owner has not retired')))
                    with redirect_stdout(stdout),redirect_stderr(stderr):
                        code = worker.main(['--config',str(Path(name)/'inert-config.json'),'owner-word-prediction',
                            '--video-id',case.video,'--future-root',str(case.workspace.root.parent),
                            '--analysis-root',str(case.audio.parents[1]),'--analysis-audio',str(case.audio),
                            '--expected-mix-sha256',case.identity['sha256'],
                            '--canonical-plain-sha256',case.canonical,'--device','cpu'])
                    self.assertEqual(prior.read_bytes(),prior_bytes)
                    self.assertFalse(case.workspace.candidate_path(case.video).exists())
                    artifacts = list(case.workspace.job_dir(case.video).glob('owner-word-prediction-*.private.json'))
                    if disposition == 'complete':
                        self.assertEqual(code,0,stderr.getvalue())
                        response = json.loads(stdout.getvalue())
                        self.assertEqual(response['status'],'owner-predicted-words')
                        self.assertEqual(len(artifacts),4)
                        self.assertNotIn('tracks',stdout.getvalue())
                        self.assertNotIn('performedOccurrences',stdout.getvalue())
                        worker.load_owner_word_prediction(case.project,case.workspace,case.video,response,
                            original=case.lyrics['tracks'][0],indices=(0,2),canonical_sha=case.canonical,
                            audio_sha=case.identity['sha256'])
                    else:
                        self.assertEqual(code,70 if disposition == 'retirement' else 2,stderr.getvalue())
                        self.assertFalse(artifacts)
                        self.assertEqual(stdout.getvalue(),'')
                        if disposition == 'retirement':
                            with self.assertRaises(WorkerRetirementError):
                                worker_protocol.require_worker_success(
                                    SimpleNamespace(returncode=code,stdout=stdout.getvalue(),stderr=stderr.getvalue()),
                                    code='owner predicted words',diagnostic_path=Path(name)/'parent.failure.private.txt')

    def test_known_words_are_timed_without_being_rewritten(self) -> None:
        text = "Alpha beta gamma delta\nOne two three four\n\nRed blue green gold\nNorth south east west"
        result = align_known_structure(text, tuple(i for i,line in enumerate(text.splitlines()) if line.strip()), transcript(text), 8_000)
        self.assertTrue(result.accepted, result.reasons)
        self.assertEqual(len(result.starts_ms), 4)
        self.assertEqual(list(result.starts_ms), sorted(result.starts_ms))

    def test_compact_vocal_passage_does_not_require_half_song_span(self) -> None:
        text = "Alpha beta gamma delta\nOne two three four\nRed blue green gold\nNorth south east west"
        result = align_known_structure(text, tuple(i for i,line in enumerate(text.splitlines()) if line.strip()), transcript(text, start=5.0, step=0.1), 20_000)
        self.assertTrue(result.accepted, result.reasons)
        self.assertLess(result.metrics.timed_span_ratio, 0.45)

    def test_compact_gate_rejects_a_small_match_inside_a_larger_transcript(self) -> None:
        text = "Alpha beta gamma delta\nOne two three four\nRed blue green gold\nNorth south east west"
        unrelated = " ".join(f"noise{index}" for index in range(16))
        result = align_known_structure(
            text, tuple(i for i,line in enumerate(text.splitlines()) if line.strip()),
            transcript(f"{unrelated} {text} {unrelated}", start=1.0, step=0.1),
            20_000,
        )
        self.assertFalse(result.accepted)
        self.assertEqual(result.reasons, ("low-transcript-word-coverage",))
        self.assertLess(
            result.metrics.transcript_word_coverage,
            QualityGate().compact_min_transcript_word_coverage,
        )


    def test_companions_reuse_original_occurrence_timing(self) -> None:
        original = "Alpha beta gamma delta\nOne two three four\n\nRed blue green gold\nNorth south east west"
        performed = (
            "Alpha beta gamma delta One two three four "
            "Alpha beta gamma delta One two three four "
            "Red blue green gold North south east west"
        )
        original = original.replace("\n\n", "\n")
        original = "\n".join(original.splitlines()[:2] * 2 + original.splitlines()[2:])
        result = align_known_structure(original, (0, 1, 2, 3, 4, 5), transcript(performed), 15_000)
        sidecar = {
            "status": "partial",
            "reason": "Timing unavailable.",
            "tracks": [
                {
                    "id": "original-en",
                    "kind": "original",
                    "language": "en",
                    "script": "Latn",
                    "format": "plain",
                    "text": original,
                    "source": {"name": "owner-supplied"},
                }
            ],
        }
        overlapping = line_timings(result, 15_000)
        overlapping[0]["endMs"] = overlapping[1]["startMs"] + 1
        with self.assertRaisesRegex(ValueError, "timings overlap"):
            build_synced_sidecar(
                sidecar,
                result,
                15_000,
                line_timings=overlapping,
            )
        synced = build_synced_sidecar(
            sidecar,
            result,
            15_000,
            line_timings=line_timings(result, 15_000),
        )
        track = synced["tracks"][0]
        self.assertEqual(track["format"], "synced")
        self.assertNotIn("text", track)
        self.assertEqual(len(track["lines"]), 6)
        self.assertEqual(track["lines"][0]["text"], track["lines"][2]["text"])
        self.assertEqual(track["lines"][1]["text"], track["lines"][3]["text"])

    def test_wordless_original_with_all_required_companions_reports_timing_incomplete(self):
        import copy
        from stemwise_engine.editorial_admission import complete_original_word_timing, require_completed_lyrics
        text = 'Un deux trois quatre\nCinq six sept huit\n\nRouge bleu vert jaune\nNord sud est ouest'
        tracks = [{**plain_original(text),'language':'fr','source':{'name':'Known French fixture'}},
            {'id':'english','kind':'translation','language':'en','script':'Latn','format':'plain',
             'basedOnTrackId':'original','text':'One two three four\nFive six seven eight\n\nRed blue green yellow\nNorth south east west',
             'source':{'name':'Reviewed translation fixture'}},
            {'id':'ipa','kind':'transliteration','language':'fr-fonipa','script':'Latn','format':'plain',
             'basedOnTrackId':'original','text':'œ̃ dø tʁwa katʁ\nsɛ̃k sis sɛt ɥit\n\nʁuʒ blø vɛʁ ʒon\nnɔʁ syd ɛ wɛst',
             'source':{'name':'Reviewed IPA fixture'}}]
        lyrics = {'status':'partial','reason':'Timing pending','tracks':tracks}
        original = copy.deepcopy(lyrics)
        result = align_known_structure(text,(0,1,3,4),transcript(text),10000)
        self.assertTrue(result.accepted,result.reasons)
        timings = line_timings(result,10000)
        candidate = build_synced_sidecar(lyrics,result,10000,line_timings=timings)
        self.assertEqual(lyrics,original)
        self.assertEqual(candidate['status'],'partial')
        self.assertEqual(candidate['reason'],'Original-language word timing remains incomplete; '
            'every performed word requires a start and end.')
        self.assertFalse(complete_original_word_timing(candidate))
        self.assertEqual([track['kind'] for track in candidate['tracks']],
            ['original','translation','transliteration'])
        for source, projected in zip(tracks,candidate['tracks']):
            self.assertEqual([line['text'] for line in projected['lines']],
                [line for line in source['text'].splitlines() if line])
            self.assertEqual([(line['startMs'],line['endMs']) for line in projected['lines']],
                [(line['startMs'],line['endMs']) for line in timings])
        with self.assertRaisesRegex(ValueError, 'lyrics-completion-required'):
            require_completed_lyrics(candidate, duration_ms=10000)

    def test_fixed_multi_evidence_runs_one_mapping_per_transcript_without_unused_single_passes(self):
        import stemwise_engine.lyrics_alignment as alignment
        text = 'Alpha beta gamma delta\nOne two three four\nRed blue green gold\nNorth south east west'
        first = transcript('Alpha beta gamma delta One two three four', start=1.0)
        second = transcript('Red blue green gold North south east west', start=5.0)
        with patch.object(alignment, '_align_single_for_sources', side_effect=AssertionError('unused single pass')), \
                patch.object(alignment, '_dp_align', wraps=alignment._dp_align) as mappings:
            merged = alignment.align_known_structure_multi(text, (0, 1, 2, 3), [first, second], 10000)
        self.assertTrue(merged.accepted, merged.reasons)
        self.assertEqual(merged.source_physical_indices, (0, 1, 2, 3))
        self.assertEqual(merged.starts_ms, (1000, 2400, 5000, 6400))
        self.assertEqual(merged.metrics.matched_word_count, 16)
        self.assertEqual(mappings.call_count, 2)

    def test_multilingual_passes_merge_disjoint_line_anchors(self) -> None:
        text = (
            "Alpha beta gamma delta\nOne two three four\n"
            "Red blue green gold\nNorth south east west"
        )
        first = transcript("Alpha beta gamma delta One two three four", start=1.0)
        second = transcript("Red blue green gold North south east west", start=5.0)
        merged = align_known_structure_multi(text, tuple(i for i,line in enumerate(text.splitlines()) if line.strip()), [first, second], 10_000)
        self.assertTrue(merged.accepted, merged.reasons)
        self.assertEqual(merged.metrics.directly_matched_line_count, 4)
        self.assertEqual(merged.metrics.matched_word_count, 16)
        self.assertGreater(merged.metrics.transcript_word_coverage, 0.0)


    def test_fixed_structure_merge_never_reinserts_reviewed_repeats(self) -> None:
        first = "Alpha beta gamma delta\nOne two three four"
        second = "Red blue green gold\nNorth south east west"
        approved = f"{first}\n\n{second}"
        repeated_pass = transcript(f"{first} {first}", start=1.0)
        closing_pass = transcript(second, start=8.0)

        merged = align_known_structure_multi(
            approved, (0, 1, 3, 4), [repeated_pass, closing_pass], 15_000
        )

        self.assertEqual(merged.source_physical_indices, (0, 1, 3, 4))


    def test_asr_prompt_represents_repeated_lines_once(self) -> None:
        repeated = "Alpha beta gamma delta"
        text = "\n".join(
            (repeated, repeated, repeated, "Quartz violet silver copper")
        )
        prompt = deduplicated_lyrics_prompt(text, max_chars=60)
        self.assertEqual(prompt.count(repeated), 1)
        self.assertIn("Quartz violet silver copper", prompt)


    def test_early_prompted_mutation_forces_an_independent_pass(self) -> None:
        text = "Alpha beta gamma delta\nOne two three four\nRed blue green gold"
        mutation = accepted_result((0, 2))
        self.assertTrue(
            _needs_additional_structural_evidence(
                [("faster-whisper:en", mutation, {}, False)],
                text=text,
                repeat_structure_kind="none",
            )
        )
        baseline = accepted_result((0, 1, 2))
        self.assertFalse(
            _needs_additional_structural_evidence(
                [("youtube-caption:manual", baseline, {"captionKind": "manual"}, False)],
                text=text,
                repeat_structure_kind="none",
            )
        )


    def test_emission_reuse_binds_audio_model_runtime_and_helpers(self) -> None:
        provenance = {"audio": {"sha256": "a" * 64, "byteCount": 100},
                      "model": {"sha256": "b" * 64}, "runtime": {"torch": "2.5"},
                      "decoder": {"chunkSeconds": 30}}
        helpers = {"mms_forced_alignment.py": "c" * 64,
                   "wav2vec2_forced_alignment.py": "d" * 64}
        key = _emission_cache_key(provenance, helpers, "cpu")
        self.assertRegex(key, r"^[0-9a-f]{64}$")
        self.assertEqual(key, _emission_cache_key(dict(reversed(list(provenance.items()))), helpers, "cpu"))
        for field in provenance:
            changed = json.loads(json.dumps(provenance))
            changed[field] = {"changed": True}
            with self.subTest(field=field):
                self.assertNotEqual(key, _emission_cache_key(changed, helpers, "cpu"))
        self.assertNotEqual(key, _emission_cache_key(provenance, {**helpers, "mms_forced_alignment.py": "e" * 64}, "cpu"))
        self.assertNotEqual(key, _emission_cache_key(provenance, helpers, "cuda"))

    def test_alignment_caches_are_bound_to_complete_provenance(self) -> None:
        cache_identity = _current_cache_identity(plain_original("Private fixture"))
        provenance = {
            "audio": {"sha256": "a" * 64, "byteCount": 100},
            "durationMs": 10_000,
            "fasterWhisperVersion": "1.2.3",
            "modelSnapshot": "snapshot-a",
            "device": "cpu",
            "computeType": "float32",
            "decoder": {"beamSize": 5},
        }
        transcript_cache = {
            "cacheVersion": TRANSCRIPT_CACHE_VERSION,
            "videoId": "KbKqkR3Q6SA",
            "model": "large-v3",
            "language": "en",
            "promptSha256": "b" * 64,
            "provenance": provenance,
            "cacheIdentity": cache_identity,
            "words": [],
        }
        arguments = {
            "video_id": "KbKqkR3Q6SA",
            "model": "large-v3",
            "language": "en",
            "prompt_sha256": "b" * 64,
        }
        self.assertTrue(
            transcript_cache_matches(
                transcript_cache,
                provenance=provenance,
                cache_identity=cache_identity,
                **arguments,
            )
        )
        changed = json.loads(json.dumps(provenance))
        changed["audio"]["sha256"] = "c" * 64
        self.assertFalse(
            transcript_cache_matches(
                transcript_cache,
                provenance=changed,
                cache_identity=cache_identity,
                **arguments,
            )
        )
        changed_identity = json.loads(json.dumps(cache_identity))
        changed_identity["schemaSha256"] = "9" * 64
        self.assertFalse(
            transcript_cache_matches(
                transcript_cache,
                provenance=provenance,
                cache_identity=changed_identity,
                **arguments,
            )
        )

        mapping_identity = {
            "mappingVersion": 4,
            "tokenizationVersion": "stemwise.display-words.v2",
            "lexicalAuthoritySha256": "f" * 64,
            "mappingSha256": "0" * 64,
        }
        mms_cache = {
            "cacheVersion": MMS_CACHE_VERSION,
            "videoId": "KbKqkR3Q6SA",
            "variant": "baseline",
            "acousticTrackKind": "original",
            "requestSha256": "d" * 64,
            "helperSha256": "e" * 64,
            "engine": "torchaudio-mms-fa",
            "provenance": provenance,
            "mappingIdentity": mapping_identity,
            "lines": [],
            "confidence": {},
        }
        self.assertTrue(
            _mms_cache_matches(
                mms_cache,
                video_id="KbKqkR3Q6SA",
                variant="baseline",
                acoustic_track_kind="original",
                request_sha256="d" * 64,
                helper_sha256="e" * 64,
                provenance=provenance,
                mapping_identity=mapping_identity,
            )
        )
        changed = json.loads(json.dumps(provenance))
        changed["modelSnapshot"] = "snapshot-b"
        self.assertFalse(
            _mms_cache_matches(
                mms_cache,
                video_id="KbKqkR3Q6SA",
                variant="baseline",
                acoustic_track_kind="original",
                request_sha256="d" * 64,
                helper_sha256="e" * 64,
                provenance=changed,
                mapping_identity=mapping_identity,
            )
        )
        changed_mapping = dict(mapping_identity, mappingSha256="1" * 64)
        self.assertFalse(
            _mms_cache_matches(
                mms_cache,
                video_id="KbKqkR3Q6SA",
                variant="baseline",
                acoustic_track_kind="original",
                request_sha256="d" * 64,
                helper_sha256="e" * 64,
                provenance=provenance,
                mapping_identity=changed_mapping,
            )
        )

    def test_cache_identity_binds_lexical_and_final_display_authority(self) -> None:
        plain = _current_cache_identity(plain_original("Alpha beta"))
        decorated = _current_cache_identity(plain_original("Alpha beta!"))
        self.assertEqual(
            set(plain),
            {
                "cacheIdentityVersion",
                "lexicalAuthoritySha256",
                "finalDisplaySha256",
                "helpersSha256",
                "normalization",
                "tokenizer",
                "graphemePolicySha256",
            },
        )
        self.assertEqual(
            plain["lexicalAuthoritySha256"], decorated["lexicalAuthoritySha256"]
        )
        self.assertIn("captionParser", plain["helpersSha256"])
        self.assertNotIn("wav2vec2_forced_alignment.py", plain["helpersSha256"])
        self.assertNotEqual(plain["finalDisplaySha256"], decorated["finalDisplaySha256"])

    def test_unprompted_recognition_identity_tracks_decoder_and_runtime_not_alignment(self) -> None:
        import stemwise_engine.alignment_audio as alignment_audio
        import stemwise_engine.alignment_models as alignment_models
        import stemwise_engine.lyrics_align_pc as cli
        import shutil
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite/work") as name:
            root = Path(name)
            for filename in ("lyrics_alignment.py", "lyrics_align_pc.py"):
                shutil.copy2(Path(cli.__file__).parent / filename, root / filename)
            with patch.object(cli, "__file__", str(root / "lyrics_align_pc.py")):
                original = cli._whisper_recognition_cache_identity()
                producer = root / "lyrics_alignment.py"
                raw = producer.read_bytes()
                producer.write_bytes(raw + b"\n# unrelated alignment change\n")
                self.assertEqual(cli._whisper_recognition_cache_identity(), original)
                changed = raw.replace(b'"beam_size": 10 if precision else 5',
                                      b'"beam_size": 11 if precision else 5', 1)
                self.assertNotEqual(raw, changed)
                stamp = producer.stat()
                producer.write_bytes(changed)
                os.utime(producer, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                self.assertNotEqual(cli._whisper_recognition_cache_identity(), original)
                producer.write_bytes(raw)
                real_version = cli._package_version
                with patch.object(cli, "_package_version", side_effect=lambda value:
                                  "changed" if value == "ctranslate2" else real_version(value)):
                    self.assertNotEqual(cli._whisper_recognition_cache_identity(), original)

    def test_negative_caption_cache_expires(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root)
            original = plain_original("Private fixture")
            cache_identity = _current_cache_identity(original)
            cache = workspace.caption_path("KbKqkR3Q6SA")
            cache.parent.mkdir(parents=True)
            cache.write_text(
                json.dumps(
                    {
                        "cacheVersion": TRANSCRIPT_CACHE_VERSION,
                        "videoId": "KbKqkR3Q6SA",
                        "language": "en",
                        "cacheIdentity": cache_identity,
                        "checkedAtUnix": 1_000,
                        "unavailable": True,
                        "words": [],
                    }
                ),
                encoding="utf-8",
            )
            with (
                patch('stemwise_engine.lyrics_align_pc.time.time', return_value=1_001),
                patch('stemwise_engine.lyrics_align_pc.download_youtube_caption_evidence') as download,
            ):
                self.assertIsNone(
                    _load_caption(
                        video_id="KbKqkR3Q6SA",
                        original=original,
                        workspace=workspace,
                        temp_root=root / "temp",
                    )
                )
                download.assert_not_called()
            with (
                patch(
                    'stemwise_engine.lyrics_align_pc.time.time',
                    return_value=1_000 + CAPTION_NEGATIVE_CACHE_TTL_SECONDS + 1,
                ),
                patch(
                    'stemwise_engine.lyrics_align_pc.download_youtube_caption_evidence',
                    return_value=None,
                ) as download,
            ):
                self.assertIsNone(
                    _load_caption(
                        video_id="KbKqkR3Q6SA",
                        original=original,
                        workspace=workspace,
                        temp_root=root / "temp",
                    )
                )
                download.assert_called_once()

    def test_owned_attempt_cleanup_keeps_marker_until_nested_cleanup_succeeds(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            workspace_root = Path(name)
            generated_root = workspace_root / "future"
            root = generated_root / "KbKqkR3Q6SA" / ("a" * 32)
            create_manifested_directory(
                generated_root,
                root,
                owner={
                    "kind": "lyrics-alignment-attempt",
                    "videoId": "KbKqkR3Q6SA",
                    "attemptId": "a" * 32,
                },
                workspace_root=workspace_root,
            )
            nested = root / "nested"
            nested.mkdir()
            marker = root / GENERATED_MANIFEST_NAME
            (nested / "leftover.tmp").write_bytes(b"temporary")
            with patch('stemwise_engine.common.shutil.rmtree', side_effect=OSError("interrupted")), patch(
                'stemwise_engine.common._recycle_disposable_path', side_effect=OSError("recycling interrupted")
            ):
                with self.assertRaises(OSError):
                    _cleanup_owned_attempt_root(
                        root,
                        generated_root=generated_root,
                        workspace_root=workspace_root,
                        video_id="KbKqkR3Q6SA",
                        attempt_id="a" * 32,
                    )
            self.assertTrue(marker.is_file())
            _cleanup_owned_attempt_root(
                root,
                generated_root=generated_root,
                workspace_root=workspace_root,
                video_id="KbKqkR3Q6SA",
                attempt_id="a" * 32,
            )
            self.assertFalse(root.exists())

    def test_canonical_backup_pointer_tracks_latest_owner_correction(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root)
            sidecar = root / "owner" / "KbKqkR3Q6SA.lyrics.json"
            first_raw = b'{"tracks":[{"text":"A"}]}'
            second_raw = b'{"tracks":[{"text":"B"}]}'
            sidecar.parent.mkdir(parents=True)
            sidecar.write_bytes(first_raw)
            first_path, first_sha = commit_candidate(
                workspace,
                "KbKqkR3Q6SA",
                sidecar,
                first_raw,
                {"candidate": 1},
            )
            sidecar.write_bytes(second_raw)
            second_path, second_sha = commit_candidate(
                workspace,
                "KbKqkR3Q6SA",
                sidecar,
                second_raw,
                {"candidate": 2},
            )
            self.assertNotEqual(first_sha, second_sha)
            self.assertTrue(first_path.is_file())
            self.assertEqual(workspace.current_backup_path("KbKqkR3Q6SA"), second_path)
            self.assertEqual(second_path.read_bytes(), second_raw)

    def test_commit_candidate_creates_fresh_canonical_backup(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root)
            video_id = "KbKqkR3Q6SA"
            sidecar = root / "owner" / f"{video_id}.lyrics.json"
            original_raw = b'{"tracks":[{"text":"Fresh"}]}'
            candidate = {"candidate": 1}
            snapshotted, snapshotted_sha = snapshot_canonical_plain(
                workspace,
                video_id,
                original_raw,
            )
            sidecar.parent.mkdir(parents=True, exist_ok=True)
            sidecar.write_bytes(original_raw)
            backup, backup_sha = commit_candidate(
                workspace,
                video_id,
                sidecar,
                original_raw,
                candidate,
            )
            self.assertEqual((backup, backup_sha), (snapshotted, snapshotted_sha))
            self.assertEqual(backup.read_bytes(), original_raw)
            self.assertEqual(json.loads(sidecar.read_text(encoding="utf-8")), candidate)
            self.assertEqual(
                json.loads(
                    workspace.backup_pointer_path(video_id).read_text(encoding="utf-8")
                ),
                {
                    "pointerVersion": 1,
                    "videoId": video_id,
                    "fileName": backup.name,
                    "sha256": backup_sha,
                },
            )

    def test_canonical_snapshot_retry_reuses_backup_after_pointer_interruption(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            workspace = AlignmentWorkspace(root)
            video_id = "KbKqkR3Q6SA"
            previous, _ = snapshot_canonical_plain(
                workspace,
                video_id,
                b'{"tracks":[{"text":"Previous"}]}',
            )
            pointer_path = workspace.backup_pointer_path(video_id)
            pointer_bytes = pointer_path.read_bytes()
            original_raw = b'{"tracks":[{"text":"Crash-safe"}]}'
            backup_sha = hashlib.sha256(original_raw).hexdigest()
            backup = (
                workspace.canonical_plain
                / f"{video_id}.{backup_sha}.lyrics.json"
            )

            with patch(
                'stemwise_engine.lyrics_alignment.atomic_write_json',
                side_effect=OSError("interrupted before pointer"),
            ):
                with self.assertRaisesRegex(OSError, "before pointer"):
                    snapshot_canonical_plain(workspace, video_id, original_raw)

            backup_mtime = backup.stat().st_mtime_ns
            self.assertEqual(backup.read_bytes(), original_raw)
            self.assertEqual(pointer_path.read_bytes(), pointer_bytes)
            self.assertEqual(workspace.current_backup_path(video_id), previous)

            self.assertEqual(
                snapshot_canonical_plain(workspace, video_id, original_raw),
                (backup, backup_sha),
            )
            self.assertEqual(backup.stat().st_mtime_ns, backup_mtime)
            self.assertEqual(workspace.current_backup_path(video_id), backup)

    def test_canonical_backup_has_no_pointerless_legacy_reader(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            workspace = AlignmentWorkspace(Path(name))
            old = workspace.canonical_plain / "KbKqkR3Q6SA.lyrics.json"
            old.parent.mkdir(parents=True)
            old.write_bytes(b"old unfinished artifact")
            with self.assertRaisesRegex(FileNotFoundError, "pointer is missing"):
                workspace.current_backup_path("KbKqkR3Q6SA")

    def test_repeat_structure_classifier_fails_closed_on_mixed_forms(self) -> None:
        opening = "Alpha beta gamma delta\nOne two three four"
        unit = "Red blue green gold\nNorth south east west"
        doubled = f"{unit}\n{unit}"
        all_doubled = "\n\n".join((opening, doubled, doubled))
        all_single = "\n\n".join((opening, unit, unit))
        mixed = "\n\n".join((opening, doubled, doubled, unit))
        independent = "\n\n".join(
            (
                opening,
                doubled,
                doubled,
                "Amber bronze copper silver\nCircle square triangle diamond\n"
                "Amber bronze copper silver\nCircle square triangle diamond",
                "Amber bronze copper silver\nCircle square triangle diamond\n"
                "Amber bronze copper silver\nCircle square triangle diamond",
            )
        )
        self.assertEqual(internal_repeat_structure_kind(all_doubled), "collapsible")
        self.assertEqual(internal_repeat_structure_kind(all_single), "none")
        self.assertEqual(internal_repeat_structure_kind(mixed), "unsupported")
        self.assertEqual(internal_repeat_structure_kind(independent), "unsupported")


class MultilingualRecoveryTests(unittest.TestCase):
    def _native(self, fields):
        from test_alignment_content_admission import AlignmentContentAdmissionTests
        from stemwise_engine.lyrics_alignment import AlignmentMetrics, AlignmentResult
        result = AlignmentResult(True, (), (100, 2500), (0, 1),
            AlignmentMetrics(8, 8, 8, 2, 2, 1., 1., 1., 100., .8))
        report, state = AlignmentContentAdmissionTests().run_coordinator(
            'one two three four five six seven eight', lexical_result=result,
            original_fields=fields)
        return report, state

    def test_native_kurdish_route_runs_before_whisper_and_keeps_unresolved_authority(self):
        report, state = self._native({'language': 'ku', 'script': 'Latn'})
        self.assertEqual(report['counts']['failed'], 1)
        self.assertEqual(state['status'], 'retryable-technical')
        self.assertIsNotNone(state['contentAdmission'])
        self.assertTrue(any(row['routeId'] == 'mms-asr:kmr-script_latin:mix' for row in state['routeAttempts']))

    def test_native_worker_failure_is_technical_not_an_acoustic_abstention(self):
        from test_alignment_content_admission import AlignmentContentAdmissionTests
        report, state = AlignmentContentAdmissionTests().run_coordinator(
            'one two three four five six seven eight', original_fields={'language': 'ku', 'script': 'Latn'},
            native_failure=RuntimeError('injected native worker boundary'))
        self.assertEqual(report['counts']['failed'], 1)
        self.assertEqual(state['status'], 'retryable-technical')
        self.assertNotIn('contentAdmission', state)
        self.assertTrue(any(row['routeId'] == 'mms-asr:kmr-script_latin:mix' and row['status'] == 'failure'
                            for row in state['routeAttempts']))

    def test_multilingual_zulu_reaches_native_recognizer_with_its_own_adapter(self):
        report, state = self._native({'language': 'mul', 'script': 'Zyyy', 'componentLanguages': ['ar', 'en', 'zu']})
        self.assertEqual(report['counts']['blocked'], 1)
        self.assertEqual(state['reasons'], ['pronunciation-support-unavailable'])
        self.assertNotIn('routeAttempts', state)
        self.assertNotIn('contentAdmission', state)
        # Arabic and Zulu have distinct scripts; the native adapter is reachable
        # without inventing ownership between multiple Latin-script languages.
        report, state = self._native({'language': 'mul', 'script': 'Zyyy', 'componentLanguages': ['ar', 'zu']})
        self.assertEqual(report['counts']['blocked'], 1)
        self.assertEqual(state['status'], 'lyrics-content-unresolved')
        self.assertEqual(state['reasons'], ['pronunciation-support-unavailable'])
        self.assertNotIn('contentAdmission', state)
        self.assertTrue(any(row['routeId'] == 'mms-asr:zul:mix' for row in state['routeAttempts']))

    def test_language_plan_keeps_all_declared_components_once(self):
        self.assertEqual(transcription_languages({'language': 'mul', 'componentLanguages': ['ar', 'ur', 'en', 'ar'],
            'text': 'مرحبا عالم'}), ['ar', 'ur', 'en', None])
        self.assertEqual(transcription_languages({'language': 'en'}), ['en'])
        self.assertEqual(transcription_languages({'language': 'und'}), [None])

    def test_content_mismatch_blocks_before_other_language_recovery(self):
        from test_alignment_content_admission import AlignmentContentAdmissionTests
        report, state = AlignmentContentAdmissionTests().run_coordinator('one two wrong four five six seven eight',
            original_fields={'language': 'mul', 'script': 'Zyyy', 'componentLanguages': ['ar', 'zu']})
        self.assertEqual(report['counts']['blocked'], 1)
        self.assertEqual(state['reasons'], ['content-sequence-uncertain'])


if __name__ == "__main__":
    unittest.main()
