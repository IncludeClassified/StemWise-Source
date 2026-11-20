from __future__ import annotations

import io
import sys
import json
import shutil
import wave
import tempfile
import unittest
from contextlib import ExitStack, redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch


PROJECT_ROOT = (
    Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"
)
sys.path.insert(0, str(PROJECT_ROOT.parents[1] / "Test suite/pc/unit"))

from stemwise_engine.future_song import FutureSongError, FutureSongWorkflow  # noqa: E402
import stemwise_engine.worker_protocol as worker_protocol
import stemwise_engine.alignment_models as alignment_models
import stemwise_engine.alignment_audio as alignment_audio
import stemwise_engine.lyrics_align_pc as lyrics_align_pc  # noqa: E402
from stemwise_engine.lyrics_align_pc import parser  # noqa: E402
from lyric_content_fixtures import retained_content, write_recognition  # noqa: E402
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from process_fixture import prepare_runtime_layout


import stemwise_engine.workflow_diagnostics as workflow_diagnostics

class QwenStructureIntegrationTest(unittest.TestCase):
    def test_failed_native_asr_and_whisper_started_routes_have_one_terminal_event(self):
        _, state, calls = self._run_structure_witness_case(fail_initial=True)
        self.assertEqual(calls, ["whisper"])
        self.assertEqual(state["status"], "retryable-technical")
        events = state["routeAttempts"]
        routes = {row["routeId"] for row in events
                  if row["routeId"].startswith(("mms-asr:", "faster-whisper:"))}
        self.assertTrue(any(route.startswith("mms-asr:") for route in routes))
        self.assertTrue(any(route.startswith("faster-whisper:") for route in routes))
        for route in routes:
            with self.subTest(route=route):
                self.assertEqual([row["status"] for row in events if row["routeId"] == route],
                                 ["started", "failure"])

    def test_required_failed_or_uncertain_witness_cannot_authorize_timing(self):
        witness = {"status": "failure", "failureCode": "fixture-required-witness-failure"}
        for accepted in (False, True):
            result = SimpleNamespace(accepted=accepted, reasons=())
            with self.subTest(accepted=accepted), self.assertRaises(worker_protocol.WorkerProcessError) as raised:
                lyrics_align_pc._require_completed_structure_witness(result, witness)
            self.assertEqual(raised.exception.code, witness["failureCode"])
        for witness in ({"status": "ambiguous"}, {"status": "started"},
                        {"status": "success", "selectedSequenceSupported": False},
                        {"status": "success", "selectedSequenceSupported": True,
                         "selectedSequenceEveryLineDirect": False}):
            with self.subTest(witness=witness), self.assertRaises(lyrics_align_pc.LyricsContentBlocker):
                lyrics_align_pc._require_completed_structure_witness(SimpleNamespace(accepted=True), witness)

    def test_alignment_commits_failed_required_witness_as_technical(self):
        report, state, calls = self._run_structure_witness_case(witness_error=True)
        self.assertEqual(state["status"], "retryable-technical")
        self.assertEqual(state["reasons"], ["fixture-required-witness-failure"])
        self.assertEqual(state["structureWitness"]["status"], "failure")
        self.assertEqual(calls, ["whisper", "qwen"])
        events = [row for row in state["routeAttempts"] if row["routeId"] == lyrics_align_pc.QWEN3_ASR_ROUTE]
        self.assertEqual([row["status"] for row in events], ["started", "failure"])
        self.assertEqual(report["counts"].get("committed", 0), 0)

    def test_confirmed_mix_reaches_endpoint_boundary_with_one_required_witness(self):
        report, state, calls = self._run_structure_witness_case()
        self.assertEqual(calls, ["whisper", "qwen", "endpoint"])
        witness = state["structureWitness"]
        self.assertEqual(witness["status"], "success")
        self.assertEqual(witness["route"], lyrics_align_pc.QWEN3_ASR_ROUTE)
        self.assertEqual(witness["candidateCount"], 1)
        self.assertEqual(witness["passingCandidateCount"], 1)
        self.assertTrue(witness["selectedSequenceEveryLineDirect"])
        self.assertTrue(witness["selectedSequenceSupported"])
        self.assertEqual(witness["metrics"]["direct_line_count"], 2)
        self.assertTrue(state["evidenceAttempts"][0]["accepted"])
        self.assertEqual(state["evidenceAttempts"][0]["metrics"]["matched_word_count"], 8)
        self.assertEqual(state["reasons"], ["fixture-endpoint-boundary"])
        self.assertEqual(report["counts"].get("committed", 0), 0)

    def test_wrong_missing_extra_or_changed_occurrences_stop_without_recovery(self):
        for text in ("alpha beta wrong delta one two three four",
                     "alpha beta gamma delta one two three",
                     "alpha beta gamma delta one two three four extra",
                     "alpha beta gamma delta one two three four alpha beta gamma delta",
                     "one two three four alpha beta gamma delta"):
            with self.subTest(recognized=text):
                report, state, calls = self._run_structure_witness_case(qwen_text=text)
                self.assertEqual(calls, ["whisper", "qwen"])
                self.assertEqual(state["status"], "lyrics-content-unresolved", state)
                self.assertEqual(state["reasons"], ["content-sequence-uncertain"])
                self.assertEqual(report["counts"]["blocked"], 1)

    def test_changed_pronunciation_stops_before_timing_or_recovery(self):
        report, state, calls = self._run_structure_witness_case(
            text="عَلَمٌ كَبِيرٌ جَمِيلٌ هُنَا\none two three four",
            qwen_text="عِلْمٌ كَبِيرٌ جَمِيلٌ هُنَا one two three four")
        self.assertEqual(calls, ["whisper", "qwen"])
        self.assertEqual(state["status"], "lyrics-content-unresolved", state)
        self.assertEqual(state["reasons"], ["content-sequence-uncertain"])
        self.assertEqual(report["counts"]["blocked"], 1)
        recognize = Mock(return_value=("عِلْمٌ كَبِيرٌ جَمِيلٌ هُنَا one two three four", {}, True))
        with self.assertRaises(lyrics_align_pc.LyricsContentBlocker):
            lyrics_align_pc._score_qwen_structure_routes(
                {"text": "عَلَمٌ كَبِيرٌ جَمِيلٌ هُنَا\none two three four"},
                {(0, 1)}, ("ar", "auto"), recognize)
        recognize.assert_called_once_with("ar")

    def test_missing_retained_content_stops_before_required_witness_or_timing(self):
        report, state, calls = self._run_structure_witness_case(retain_evidence=False)
        self.assertEqual(calls, ["whisper"])
        self.assertEqual(state["status"], "retryable-technical")
        self.assertEqual(report["counts"]["failed"], 1)

    def test_required_witness_retires_whisper_model_before_inference(self):
        _, _, calls = self._run_structure_witness_case(own_model=True)
        self.assertEqual(calls, ["whisper", "qwen", "endpoint"])

    def test_current_single_sequence_scorer_never_repairs_content_with_another_language(self):
        original = {"text": "alpha beta gamma delta\none two three four"}
        recognize = Mock(return_value=(original["text"], {"language": "en"}, True))
        scores, provenance, reused = lyrics_align_pc._score_qwen_structure_routes(
            original, {(0, 1)}, ("en",), recognize)
        self.assertEqual(len(scores), 1)
        self.assertTrue(scores[0].accepted)
        self.assertEqual(scores[0].metrics.direct_line_count, 2)
        self.assertEqual(provenance["language"], "en")
        self.assertTrue(reused)
        recognize.assert_called_once_with("en")
        for sequences, text in (({(0, 1)}, "wrong words"), ({(0, 1), (0, 1, 0)}, original["text"])):
            recognize = Mock(return_value=(text, {}, False))
            with self.subTest(sequences=sequences), self.assertRaises(lyrics_align_pc.LyricsContentBlocker):
                lyrics_align_pc._score_qwen_structure_routes(original, sequences, ("en", "auto"), recognize)
            recognize.assert_called_once_with("en")

    def test_whisper_route_matrix_preserves_decode_options_and_order(self):
        unit = 'red blue green gold\nnorth south east west'
        text = '\n\n'.join(('alpha beta gamma delta\none two three four',
                             unit + '\n' + unit, unit + '\n' + unit))
        _, state, calls = self._run_structure_witness_case(text=text, inspect_whisper_routes=True)
        self.assertEqual(calls, [
            (False, False, False), (True, False, True), (False, True, True),
            (False, True, False), (True, True, True)])
        self.assertEqual(state['reasons'], ['fixture-structure-selection-boundary'])
        self.assertIn('contentAdmission', state)

    def _run_structure_witness_case(self, *, qwen_text=None, witness_error=False,
                                    retain_evidence=True, own_model=False, fail_initial=False, inspect_whisper_routes=False,
                                    text="alpha beta gamma delta\none two three four"):
        from test_lyrics_alignment import plain_original, transcript
        from stemwise_engine.common import heavy_worker_lock_path, process_lock_descriptor
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1] / "Test suite/work") as temp:
            root = Path(temp)
            video_id = "abcdefghijk"
            sidecars = root / "owner"
            sidecars.mkdir()
            original = plain_original(text)
            original["source"] = {"name": "Owner"}
            if any("\u0600" <= char <= "\u06ff" for char in text):
                original.update(language="mul", componentLanguages=["ar", "en"])
            raw = json.dumps({"status": "partial", "reason": "Timing pending", "tracks": [original]}).encode()
            sidecar = sidecars / f"{video_id}.lyrics.json"
            sidecar.write_bytes(raw)
            audio = root / "temp" / video_id / "analysis.wav"
            audio.parent.mkdir(parents=True)
            audio.write_bytes(b"known PCM boundary")
            workspace = lyrics_align_pc.AlignmentWorkspace(root / "agent", workspace_root=root)
            _, canonical_sha = lyrics_align_pc.snapshot_canonical_plain(workspace, video_id, raw)
            prior = workspace.job_dir(video_id) / "prior-cache.private.json"
            prior.parent.mkdir(parents=True, exist_ok=True)
            prior.write_bytes(b'{"prior":"preserved"}')
            sources = tuple(index for index, line in enumerate(text.splitlines()) if line.strip())
            produced = []
            calls = []
            model = Mock()
            config = {"projectDir": str(root / "builder"), "tempDir": str(root / "temp"),
                      "analysisTimeoutMinutes": 1}
            prepare_runtime_layout(Path(config['projectDir']))
            def read_whisper(**request):
                if inspect_whisper_routes:
                    calls.append((request['separate_vocals'], request.get('precision', False),
                                  request.get('use_approved_prompt', True)))
                else:
                    calls.append("whisper")
                    self.assertFalse(request["separate_vocals"])
                    self.assertFalse(request["use_approved_prompt"])
                if fail_initial:
                    raise worker_protocol.WorkerProcessError("fixture ASR failure", "fixture-whisper-failure")
                if own_model:
                    self.assertIs(request["get_model"](), model)
                    self.assertIsNotNone(process_lock_descriptor(heavy_worker_lock_path(config)))
                if retain_evidence:
                    _, proof = retained_content(workspace.job_dir(video_id), original, sources, canonical_sha)
                    target = workspace.transcript_path(video_id, request["language"], "unprompted-mix")
                    target.write_bytes(proof.read_bytes())
                    produced.append((target, target.read_bytes()))
                return transcript(text), {"type": "original-mix"}, True
            def read_qwen(**request):
                calls.append("qwen")
                self.assertNotIn("audio_view", request)
                self.assertTrue(request["separate_language_cache"])
                self.assertEqual(request["audio"], audio)
                request["before_run"]()
                if own_model:
                    model.close.assert_called_once()
                    self.assertIsNone(process_lock_descriptor(heavy_worker_lock_path(config)))
                if witness_error:
                    raise worker_protocol.WorkerProcessError("fixture worker failure", "fixture-required-witness-failure")
                recognized = text if qwen_text is None else qwen_text
                proof = write_recognition(workspace.job_dir(video_id), original, (0, 1), recognized=recognized)
                target = workspace.job_dir(video_id) / f"qwen-structure-{request['language']}.private.json"
                target.write_bytes(proof.read_bytes())
                produced.append((target, target.read_bytes()))
                return (recognized,
                        {"language": request["language"] or "auto", "modelRevision": "b" * 40,
                         "modelSnapshotSha256": "b" * 64, "helperSha256": "b" * 64,
                         "runtime": {}, "decoder": {}}, True)
            def endpoint_boundary(*args, **kwargs):
                calls.append("endpoint")
                raise worker_protocol.WorkerProcessError("fixture timing boundary", "fixture-endpoint-boundary")
            args = SimpleNamespace(from_position=None, to_position=None, model="large-v3", device="cpu",
                compute_type="float32", expected_mix_sha256="a" * 64, analysis_audio=None,
                editorial_sha256=None, rerun_transcript=False, no_commit=False, realign_synced=False,
                qwen_structure_witness=True)
            forbidden = {name: Mock(side_effect=AssertionError("content cannot reach recovery")) for name in (
                "_run_roformer_separation", "separate_vocals_demucs", "_load_or_run_mms_alignment",
                "_load_or_run_wav2vec2_alignment", "_load_or_run_owsm_ctc_alignment")}
            with ExitStack() as stack:
                stack.enter_context(patch.object(alignment_audio, "write_audio_provenance", return_value={
                    "mix": {"sha256": "a" * 64, "stream": {"sampleRate": 48000, "frameCount": 240000}},
                    "audioViews": {}}))
                stack.enter_context(patch.object(alignment_models, "FasterWhisperEvidence", return_value=model))
                stack.enter_context(patch.multiple(lyrics_align_pc,
                    _verify_alignment_runtime=Mock(return_value={}),
                    _verify_pinned_stack=Mock(return_value={}),
                    _load_reviewed_lrc_starts=Mock(return_value={}),
                    _load_caption=Mock(return_value=None),
                    validate_child_zip=Mock(return_value={"analysis": {"durationMs": 5000}}),
                    _load_or_transcribe=Mock(side_effect=read_whisper),
                    _qwen3_asr_runtime_provenance=Mock(return_value={"supportedLanguages": ["en", "ar"]}),
                    _load_or_run_qwen3_structure_transcript=Mock(side_effect=read_qwen),
                    _endpoint_language=Mock(side_effect=endpoint_boundary),
                    **forbidden))
                stack.enter_context(patch.object(lyrics_align_pc.mms_asr, "adapters_for",
                    return_value=(lyrics_align_pc.mms_asr.ADAPTER,) if fail_initial else ()))
                if inspect_whisper_routes:
                    from stemwise_engine.lyrics_alignment import AlignmentResult, AlignmentMetrics
                    words = len(text.split())
                    untimed = AlignmentResult(False, ('short-timed-span',), tuple(range(len(sources))), sources,
                        AlignmentMetrics(words, words, words, len(sources), len(sources), 1., 1., 1., 100., .1))
                    stack.enter_context(patch.object(lyrics_align_pc, 'align_known_structure', return_value=untimed))
                    stack.enter_context(patch.object(lyrics_align_pc, 'align_known_structure_multi', return_value=untimed))
                    stack.enter_context(patch.object(lyrics_align_pc.structure_selection.StructureSelectionStage,
                        'select', side_effect=worker_protocol.WorkerProcessError('fixture stop after Whisper routes',
                            'fixture-structure-selection-boundary')))
                if fail_initial:
                    stack.enter_context(patch.object(lyrics_align_pc.mms_asr, "runtime_provenance",
                        side_effect=worker_protocol.WorkerProcessError("fixture native failure", "fixture-native-failure")))
                stack.enter_context(redirect_stdout(io.StringIO()))
                report = lyrics_align_pc._align(
                    config, args,
                    selected_override=[{"position": 1, "videoId": video_id}],
                    paths_override=(sidecars, root / "runtime", root / "models", workspace),
                    temp_root_override=root / "attempt")
            state = json.loads(workspace.state_path(video_id).read_bytes())
            self.assertEqual(sidecar.read_bytes(), raw)
            self.assertEqual(workspace.current_backup_path(video_id).read_bytes(), raw)
            self.assertEqual(prior.read_bytes(), b'{"prior":"preserved"}')
            for path, evidence in produced:
                self.assertEqual(path.read_bytes(), evidence)
            for worker in forbidden.values():
                worker.assert_not_called()
            self.assertEqual(report["counts"].get("committed", 0), 0)
            return report, state, calls


    def test_qwen_failure_survives_attempt_cleanup_without_changing_a_cache(self):
        from test_qwen3_asr_transcribe import output_fixture, generation_fixture
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root/'builder'
            prepare_runtime_layout(project)
            for name in (".venv/bin/python", "src/qwen3_asr_transcribe.py"):
                path = project / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture_dependency = " + repr(path.name) + "\n", encoding="utf-8")
            audio = root / "audio.wav"
            with wave.open(str(audio), "wb") as stream:
                stream.setnchannels(1)
                stream.setsampwidth(2)
                stream.setframerate(16000)
                stream.writeframes(b"\x00\x00" * 1600)
            identity = alignment_audio.file_identity(audio)
            job = root / "job"
            job.mkdir()
            cache = job / "qwen-structure-ar.private.json"
            cache.write_bytes(b'{"old-cache":true}')
            scratch = root / "attempt"
            stderr = "Traceback omitted\nRuntimeError: synthetic processor failure\n"
            run_process = lyrics_align_pc.run_process_tree
            def worker(argv, **kwargs):
                if "--request" not in argv:
                    return run_process(argv, **kwargs)
                request = json.loads(Path(argv[argv.index('--request')+1]).read_bytes())
                output = output_fixture(request)
                output['result'] = generation_fixture('raw raw assistant\nunfinished', request['language'], complete=False)
                Path(argv[argv.index('--output')+1]).write_text(json.dumps(output), encoding='utf-8')
                return SimpleNamespace(returncode=1, stdout="", stderr=stderr)
            with patch.object(lyrics_align_pc, "_qwen3_asr_snapshot", return_value=root), \
                 patch.object(alignment_models, "roformer_offline_env", return_value={}), \
                 patch.object(lyrics_align_pc, "run_process_tree", side_effect=worker):
                with self.assertRaises(worker_protocol.WorkerProcessError) as raised:
                    lyrics_align_pc._load_or_run_qwen3_structure_transcript(
                        config={"projectDir": str(project)}, video_id="abcdefghijk", language="ar",
                        audio=audio, audio_identity=identity,
                        model_cache=root, workspace=SimpleNamespace(job_dir=lambda _: job),
                        temp_dir=scratch, device="auto", timeout_seconds=1,
                        runtime_report={"synthetic": True, "supportedLanguages": ["ar"]}, separate_language_cache=True)
            shutil.rmtree(scratch)
            diagnostic = raised.exception.private_diagnostics[0]
            self.assertEqual((job / diagnostic['file']).read_text(encoding='utf-8'), stderr)
            self.assertEqual(lyrics_align_pc._sha256_file(job / diagnostic['file']), diagnostic['sha256'])
            self.assertEqual(cache.read_bytes(), b'{"old-cache":true}')
            observations = list(job.glob('qwen-observation-*.private.json'))
            self.assertEqual(len(observations), 1)
            retained = json.loads(observations[0].read_bytes())
            self.assertEqual(retained['result']['observation']['bodyDecodedText'], 'raw raw assistant\nunfinished')
            self.assertIsNone(retained['result']['transcription'])
            self.assertNotIn("synthetic processor failure", str(raised.exception))


    def test_complete_single_line_repeat_is_counted_inside_a_larger_stanza(self):
        lines = ["alpha bravo charlie delta", "echo foxtrot golf hotel",
                 "india juliet kilo lima", "mike november oscar papa",
                 "quebec romeo sierra tango", "uniform victor whiskey xray harbor lantern",
                 "yankee zulu cobalt quartz", "silver amber cedar maple"]
        baseline = tuple(range(len(lines)))
        repeated = baseline + (5,)
        recognized = " ".join(lines[index] for index in repeated)
        text = "\n".join(lines)
        shorter = lyrics_align_pc.score_structure_witness(text, baseline, recognized)
        self.assertEqual(shorter.metrics.candidate_word_coverage, 1.0)
        self.assertFalse(shorter.accepted,
                         "A candidate must account for a completely recognized six-word repeat")
        self.assertEqual(shorter.reasons, ("unaccounted-complete-repetition",))
        with self.assertRaisesRegex(ValueError, 'exact approved physical-line sequence'):
            lyrics_align_pc.score_structure_witness(text, repeated, recognized)
        approved_repeated = '\n'.join(lines[index] for index in repeated)
        complete = lyrics_align_pc.score_structure_witness(approved_repeated, range(len(repeated)), recognized)
        self.assertTrue(complete.accepted, complete.reasons)

        # Numerical witness metrics retain their six-word minimum; admission is exact.
        short_lines = [*lines]
        short_lines[5] = "uniform victor whiskey xray harbor"
        short_recognized = " ".join(short_lines[index] for index in repeated)
        short = lyrics_align_pc.score_structure_witness(
            "\n".join(short_lines), baseline, short_recognized)
        self.assertTrue(short.accepted, short.reasons)
        with self.assertRaises(lyrics_align_pc.LyricsContentBlocker):
            lyrics_align_pc.require_exact_sequence("\n".join(short_lines), baseline,
                                                  short_recognized, "c" * 64, "a" * 64)

        # A matching phrase inside another approved line is already accounted for.
        embedded = [*lines]
        embedded[2] += " " + lines[5]
        embedded_score = lyrics_align_pc.score_structure_witness(
            "\n".join(embedded), baseline, " ".join(embedded))
        self.assertTrue(embedded_score.accepted, embedded_score.reasons)


    def test_repeated_occurrences_need_direct_support_despite_high_global_coverage(self):
        lines = ["alpha bravo charlie delta", "echo foxtrot golf hotel",
                 "india juliet kilo lima", "mike november oscar papa",
                 "quebec romeo sierra tango", "uniform victor whiskey xray",
                 "yankee zulu cobalt quartz", "silver amber cedar maple"]
        text = "\n".join(lines)
        baseline = tuple(range(len(lines)))
        recognized_baseline = " ".join(lines)
        for candidate in ((0, 1) + baseline, baseline + (0, 1),
                          baseline[:4] + (0, 1) + baseline[4:]):
            with self.subTest(candidate=candidate):
                with self.assertRaisesRegex(ValueError, 'exact approved physical-line sequence'):
                    lyrics_align_pc.score_structure_witness(text, candidate, recognized_baseline)
                approved = "\n".join(lines[index] for index in candidate)
                sources = tuple(range(len(candidate)))
                result = lyrics_align_pc.score_structure_witness(approved, sources, recognized_baseline)
                self.assertGreaterEqual(result.metrics.candidate_word_coverage, .8)
                with self.assertRaises(lyrics_align_pc.LyricsContentBlocker):
                    lyrics_align_pc.require_exact_sequence({'text': approved}, sources,
                        recognized_baseline, 'c' * 64, 'a' * 64)
                recognized = " ".join(lines[index] for index in candidate)
                lyrics_align_pc.require_exact_sequence({'text': approved}, sources, recognized, 'c' * 64, 'a' * 64)
                supported = lyrics_align_pc.score_structure_witness(approved, sources, recognized)
                self.assertTrue(supported.accepted, supported.reasons)

    def test_qwen_source_caches_are_isolated_and_audio_bound(self):
        from test_qwen3_asr_transcribe import output_fixture
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            project = root/'builder'
            prepare_runtime_layout(project)
            python = project / ".venv/bin/python"
            helper = project / "src/qwen3_asr_transcribe.py"
            for path in (python, helper):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("fixture_dependency = " + repr(path.name) + "\n", encoding="utf-8")
            audio = root / "audio.wav"
            with wave.open(str(audio), "wb") as stream:
                stream.setnchannels(1)
                stream.setsampwidth(2)
                stream.setframerate(16000)
                stream.writeframes(b"\x00\x00" * 1600)
            identity = alignment_audio.file_identity(audio)
            cache_dir = root / "cache"
            cache_dir.mkdir()
            calls = []
            run_process = lyrics_align_pc.run_process_tree
            def worker(argv, **kwargs):
                if "--request" not in argv:
                    return run_process(argv, **kwargs)
                request = json.loads(Path(argv[argv.index("--request") + 1]).read_text())
                calls.append(request)
                Path(argv[argv.index("--output") + 1]).write_text(json.dumps(output_fixture(request)))
                return SimpleNamespace(returncode=0)
            arguments = dict(config={"projectDir": str(project)}, video_id="abcdefghijk", language="ar",
                audio=audio, audio_identity=identity, model_cache=root,
                workspace=SimpleNamespace(job_dir=lambda video_id: cache_dir), temp_dir=root / "temp",
                device="auto", timeout_seconds=1, runtime_report={"synthetic": True, "supportedLanguages": ["ar"]},
                separate_language_cache=True)
            with patch.object(lyrics_align_pc, "_qwen3_asr_snapshot", return_value=root), \
                 patch.object(alignment_models, "roformer_offline_env", return_value={}), \
                 patch.object(lyrics_align_pc, "run_process_tree", side_effect=worker):
                _, mix_provenance, reused = lyrics_align_pc._load_or_run_qwen3_structure_transcript(**arguments)
                self.assertFalse(reused)
                mix_cache = cache_dir / "qwen-structure-ar.private.json"
                original_cache = mix_cache.read_bytes()
                for view in ("htdemucs-vocals", "kimberley-jensen-roformer-vocals", "sucial-v2-roformer-dry-vocals"):
                    _, provenance, reused = lyrics_align_pc._load_or_run_qwen3_structure_transcript(
                        **arguments, audio_view=view)
                    self.assertFalse(reused)
                    self.assertEqual(provenance["audioView"], view)
                    self.assertEqual(mix_cache.read_bytes(), original_cache)
                    self.assertTrue(lyrics_align_pc._load_or_run_qwen3_structure_transcript(
                        **arguments, audio_view=view)[2])
                self.assertNotIn("audioView", mix_provenance)
                self.assertTrue(lyrics_align_pc._load_or_run_qwen3_structure_transcript(**arguments)[2])
                auto_arguments = {**arguments, "language": None, "audio_view": "htdemucs-vocals"}
                self.assertFalse(lyrics_align_pc._load_or_run_qwen3_structure_transcript(**auto_arguments)[2])
                self.assertTrue(lyrics_align_pc._load_or_run_qwen3_structure_transcript(**auto_arguments)[2])
                self.assertTrue(lyrics_align_pc._load_or_run_qwen3_structure_transcript(
                    **arguments, audio_view="htdemucs-vocals")[2])
                for code in ('bs', 'sr', 'hr', 'zu'):
                    _, provenance, reused = lyrics_align_pc._load_or_run_qwen3_structure_transcript(
                        **{**arguments, 'language': code})
                    self.assertIsNone(provenance['language'])
                    self.assertEqual(reused, code != 'bs')
                    self.assertIsNone(calls[-1]['language'])
                    self.assertFalse((cache_dir/f'qwen-structure-{code}.private.json').exists())
                self.assertTrue((cache_dir/'qwen-structure.private.json').is_file())
                self.assertTrue(lyrics_align_pc._load_or_run_qwen3_structure_transcript(
                    **{**arguments, 'language': None})[2])
                self.assertEqual(len(calls), 6)
                with self.assertRaises(ValueError):
                    lyrics_align_pc._load_or_run_qwen3_structure_transcript(**arguments, audio_view="../mix")
                with wave.open(str(audio), "wb") as stream:
                    stream.setnchannels(1)
                    stream.setsampwidth(2)
                    stream.setframerate(16000)
                    stream.writeframes(b"\x01\x00" * 1600)
                with self.assertRaises(ValueError):
                    lyrics_align_pc._load_or_run_qwen3_structure_transcript(**arguments, audio_view="htdemucs-vocals")
                self.assertEqual(mix_cache.read_bytes(), original_cache)
                self.assertEqual(len(calls), 6)


    def test_route_producer_and_diagnostics_agree_on_uncached_outcomes(self):
        from stemwise_engine.future_song import FutureSongWorkflow, FutureSongError
        workflow = object.__new__(FutureSongWorkflow)
        for status, code in (("started", None), ("success", None), ("failure", "RuntimeError")):
            event = lyrics_align_pc._route_attempt_event(
                0, "structure", "owsm-ctc-full-target-structure", status, code=code)
            projected = workflow_diagnostics._alignment_diagnostics({"routeAttempts": [event]})["routeAttempts"][0]
            self.assertEqual(projected, event)
            if status == "started":
                self.assertNotIn("cacheReused", event)
            else:
                self.assertIs(event["cacheReused"], False)
        endpoint_seed_failure = lyrics_align_pc._route_attempt_event(
            0, "endpoint-seed", "htdemucs-vocals", "failed",
            code="wav2vec2-segment-boundary-guard:19:end")
        self.assertEqual(
            workflow_diagnostics._alignment_diagnostics({"routeAttempts": [endpoint_seed_failure]})[
                "routeAttempts"
            ][0],
            endpoint_seed_failure,
        )
        with self.assertRaisesRegex(ValueError, 'route event identity'):
            lyrics_align_pc._route_attempt_event(0, "structure", "htdemucs-vocals", "failed", code="RuntimeError")
        invalid = {'sequence': 0, 'stage': 'structure', 'routeId': 'htdemucs-vocals',
                   'status': 'failed', 'cacheReused': False, 'code': 'RuntimeError'}
        with self.assertRaisesRegex(FutureSongError, "route attempt is invalid"):
            workflow_diagnostics._alignment_diagnostics({"routeAttempts": [invalid]})
        cached = lyrics_align_pc._route_attempt_event(0, "structure", "cached-route", "success", cache_reused=True)
        self.assertIs(workflow_diagnostics._alignment_diagnostics({"routeAttempts": [cached]})["routeAttempts"][0]["cacheReused"], True)
        invalid = {**cached, "cacheReused": "false"}
        with self.assertRaisesRegex(FutureSongError, "cache identity is invalid"):
            workflow_diagnostics._alignment_diagnostics({"routeAttempts": [invalid]})
        invalid = {key: value for key, value in cached.items() if key != "cacheReused"}
        with self.assertRaisesRegex(FutureSongError, "cache identity is invalid"):
            workflow_diagnostics._alignment_diagnostics({"routeAttempts": [invalid]})


    def test_multilingual_qwen_routes_use_only_supported_declared_components(self) -> None:
        original = {"language": "mul", "componentLanguages": ["zu", "ar"],
                    "text": "مرحبا بالعالم مرحبا بالعالم"}
        self.assertEqual(lyrics_align_pc._qwen_structure_languages(original, ["ar", "en"]),
                         ("ar", "auto"))
        self.assertEqual(lyrics_align_pc._qwen_structure_languages(
            {"language": "zu"}, ["ar", "en"]), ("auto",))


    def test_pinned_snapshot_uses_the_audited_tree_digest(self) -> None:
        self.assertEqual(
            lyrics_align_pc.QWEN3_ASR_SNAPSHOT_SHA256,
            "16d6148c1b3c5ad1e791ec3373d73f18023ad559674b421d54afd68d37665073",
        )


    def test_trial_flag_exists_only_on_single_song_future_alignment(self) -> None:
        digest = "a" * 64
        future = parser().parse_args(
            [
                "--config=/private/config.json",
                "future-align",
                "--video-id=abcdefghijk",
                f"--attempt-id={'b' * 32}",
                "--analysis-audio=/private/analysis.wav",
                "--future-root=/private/future-state",
                "--analysis-root=/private/temporary-state",
                f"--expected-mix-sha256={digest}",
                f"--editorial-sha256={digest}",
                f"--canonical-plain-sha256={digest}",
                "--qwen-structure-witness",
            ]
        )
        self.assertTrue(future.qwen_structure_witness)
        with self.assertRaises(SystemExit):
            parser().parse_args(
                ["--config=/private/config.json", "align", "--qwen-structure-witness"]
            )


    def test_report_projection_is_body_free_and_rejects_open_fields(self) -> None:
        record = {
            "family": "qwen3-asr",
            "route": "qwen3-asr-unprompted-mix",
            "status": "success",
            "cacheReused": False,
            "candidateCount": 2,
            "passingCandidateCount": 1,
            "selectedSequenceSupported": True,
            "selectedSequenceEveryLineDirect": True,
            "metrics": {"line_count": 30, "direct_line_coverage": 1.0},
            "lexicalAuthoritySha256": "c" * 64,
            "modelRevision": "d" * 40,
            "modelSnapshotSha256": "e" * 64,
            "helperSha256": "f" * 64,
            "runtime": {"private": "not projected"},
            "decoder": {"private": "not projected"},
        }
        projected = workflow_diagnostics._structure_witness_diagnostics(record)
        for route in lyrics_align_pc.QWEN3_ASR_SOURCE_ROUTES.values():
            self.assertEqual(workflow_diagnostics._structure_witness_diagnostics(
                {**record, "route": route})["route"], route)
        with self.assertRaises(FutureSongError):
            workflow_diagnostics._structure_witness_diagnostics(
                {**record, "route": "qwen3-asr-unprompted-custom"})
        self.assertNotIn("runtime", projected)
        self.assertNotIn("decoder", projected)
        self.assertTrue(projected["selectedSequenceEveryLineDirect"])
        self.assertEqual(workflow_diagnostics._structure_witness_diagnostics({**record, "languageHint": "ar"})["languageHint"], "ar")
        with self.assertRaises(FutureSongError):
            workflow_diagnostics._structure_witness_diagnostics({**record, 'nativeStructureComparison': {}})
        with self.assertRaisesRegex(FutureSongError, "language hint"):
            workflow_diagnostics._structure_witness_diagnostics({**record, "languageHint": "../ar"})

        with self.assertRaises(FutureSongError):
            workflow_diagnostics._structure_witness_diagnostics(
                {**record, "transcription": "must remain private"}
            )


    def test_auto_device_accepts_the_worker_resolved_device(self) -> None:
        runtime = {
            "runtimeVersion": 1,
            "python": "3.10.1",
            "torch": "2.10.0",
            "transformers": "5.16.1",
            "requestedDevice": "auto",
            "resolvedDevice": "cuda",
            "cudaAvailable": True,
        }
        report = {
            "workerFormatVersion": lyrics_align_pc.QWEN3_ASR_FORMAT_VERSION,
            "engine": lyrics_align_pc.QWEN3_ASR_ENGINE,
            "runtime": runtime,
            "decoder": lyrics_align_pc.QWEN3_ASR_DECODER,
            "supportedLanguages": ["ar", "en"],
        }
        with (
            tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory,
            patch.object(
                lyrics_align_pc,
                "run_process_tree",
                return_value=SimpleNamespace(
                    returncode=0, stdout=lyrics_align_pc.json.dumps(report)
                ),
            ),
            patch.object(
                alignment_models, "qualified_python_version", return_value="3.10.1"
            ),
            patch.object(
                lyrics_align_pc,
                "runtime_profile",
                return_value={'packages': {"torch": "2.10.0", "transformers": "5.16.1"}},
            ),
            patch.object(alignment_models, "roformer_offline_env", return_value={}),
        ):
            project = Path(directory)/'builder'
            prepare_runtime_layout(project)
            python = project/'.venv/bin/python'
            helper = project/'src/qwen3_asr_transcribe.py'
            self.assertEqual(
                lyrics_align_pc._qwen3_asr_runtime_provenance(
                    python,
                    helper,
                    device="auto",
                    timeout_seconds=1,
                )["runtime"]["resolvedDevice"],
                "cuda",
            )
            for languages in ([], ["ar", "ar"], ["ar", "EN"]):
                with self.subTest(languages=languages), patch.object(
                    lyrics_align_pc, "run_process_tree",
                    return_value=SimpleNamespace(returncode=0, stdout=lyrics_align_pc.json.dumps(
                        {**report, "supportedLanguages": languages})),
                ):
                    with self.assertRaisesRegex(RuntimeError, "runtime differs"):
                        lyrics_align_pc._qwen3_asr_runtime_provenance(
                            python, helper,
                            device="auto", timeout_seconds=1)



if __name__ == "__main__":
    unittest.main()
