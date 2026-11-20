"""Independent continuous-time controls and held-out mutations for source_timing."""
from __future__ import annotations

from contextlib import redirect_stdout
import copy
import hashlib
import io
import json
from pathlib import Path
import random
import tempfile
import sys
import unittest
from unittest import mock

import numpy as np
from scipy import signal

import source_timing as timing


def specification(seed: int, channels=2) -> dict:
    rng = random.Random(seed)
    return {"kind": "analytic-bandlimited-sum-v1", "channels": [
        [{"cyclesPerFrame": rng.uniform(.025, .40), "amplitude": rng.uniform(.002, .008),
          "phaseRadians": rng.uniform(-3., 3.)} for _ in range(192)] for _ in range(channels)]}


def source(spec: dict, frames=32768, *, delay=0., drift=0.):
    # Cosine evaluation at known physical coordinates is independent of the estimator.
    coordinate = np.arange(frames, dtype=np.float64) * (1 - drift / (frames - 1)) - delay
    return np.array([sum(t["amplitude"] * np.cos(2 * np.pi * t["cyclesPerFrame"] * coordinate +
                                              t["phaseRadians"] - np.pi / 2) for t in terms)
                     for terms in spec["channels"]]).T


class TimingChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = specification(0x535741)
        cls.reference = source(cls.spec)
        cls.runtime_qualified = all(timing.runtime_identity().get(key) == value
                                    for key, value in timing.PROFILE['calibratedRuntime'].items())

    def measured(self, output, reference=None, spec=None):
        return timing.measure_audio(self.reference if reference is None else reference, output, calibrated=True,
                                    analytic_spec=self.spec if reference is None else spec)

    def test_generator_exact_coordinates_and_distinct_reference_channels(self):
        expected = timing.analytic_samples(self.spec, *self.reference.shape)
        self.assertLess(np.max(np.abs(expected - self.reference)), 2e-12)
        self.assertLess(abs(np.corrcoef(self.reference.T)[0, 1]), .1)

    def test_zero_gain_and_polarity_controls(self):
        for gain in (1., .2, -1.7):
            with self.subTest(gain=gain):
                result = self.measured(self.reference * gain)
                self.assertEqual("PASS_WITHIN_BOUND", result["decision"])
                self.assertLess(result["maximumAbsoluteLagSamples48k"], .001)
                self.assertTrue(all(w["polarity"] == (1 if gain > 0 else -1) for w in result["windows"]))

    def test_same_length_integer_shifts_sign_and_magnitude(self):
        for delay in (-128, -64, -2, -1, 1, 2, 64, 128):
            with self.subTest(delay=delay):
                shifted = source(self.spec, delay=delay)
                self.assertEqual(self.reference.shape, shifted.shape)
                result = self.measured(shifted)
                self.assertEqual("FAIL_TIMING", result["decision"])
                self.assertTrue(all(abs(w["delaySamples48k"] - delay) <= .04 for w in result["windows"]))

    def test_impulse_linear_lag_sign_and_search_edge(self):
        reference = np.zeros(8192)
        reference[4000] = 1.
        for delay in (-3, 3):
            returned = np.zeros(8192)
            returned[4000 + delay] = 1.
            result = timing.measure_window(reference, returned)
            self.assertEqual(delay, result["integerLag"])
            self.assertAlmostEqual(delay, result["delaySamples48k"], places=6)
        self.assertEqual("UNVERIFIED", self.measured(source(self.spec, delay=256))["decision"])

    def test_fractional_calibration_then_independent_heldout_controls(self):
        calibration_max = 0.
        heldout_max = 0.
        # Settings are frozen in production; held-out seeds never alter them.
        for seed, shifts in ((7103, (-.5, -.25, -.125, 0., .125, .25, .5)),
                             (8317, (-2.375, -.49, -.251, -.124, .124, .251, .49, 2.375))):
            spec = specification(seed, channels=1)
            reference = source(spec, frames=16384)
            for delay in shifts:
                result = self.measured(source(spec, frames=16384, delay=delay), reference, spec=spec)
                errors = [abs(w["delaySamples48k"] - delay) for w in result["windows"]]
                self.assertEqual(len(result["windows"]), len(errors))
                if seed == 7103:
                    calibration_max = max(calibration_max, *errors)
                else:
                    heldout_max = max(heldout_max, *errors)
                self.assertLess(max(errors), timing.PROFILE["uncertaintySamples48k"])
                if abs(delay) <= .125:
                    self.assertEqual("PASS_WITHIN_BOUND", result["decision"])
                elif abs(delay) >= .49:
                    self.assertEqual("FAIL_TIMING", result["decision"])
                else:
                    self.assertEqual("UNVERIFIED", result["decision"])
        self.assertLess(calibration_max, .04)
        self.assertLess(heldout_max, .04)
        type(self).calibration_observations = {"calibrationMaximumErrorSamples48k": calibration_max,
                                              "heldoutMaximumErrorSamples48k": heldout_max,
                                              "trainingSeed": 7103, "heldoutSeed": 8317,
                                              "longestDriftControlFrames": 131072}

    def test_affine_drift_positive_negative_accumulated_one_sample(self):
        for drift in (-1., 1.):
            spec = specification(8119, channels=1)
            reference = source(spec, frames=131072)
            result = self.measured(source(spec, frames=131072, drift=drift), reference, spec=spec)
            self.assertEqual("FAIL_TIMING", result["decision"])
            trajectory = result["trajectories"][0]
            self.assertGreater(abs(trajectory["accumulatedDriftSamples48k"]), .8)
            self.assertEqual(np.sign(drift), np.sign(trajectory["driftPpm"]))

    def test_ten_minute_sparse_continuous_clock_control_has_explicit_gaps(self):
        spec = specification(9119, channels=1)
        duration_frames = 600 * 48000
        delays = []
        measured_intervals = []
        for start in (0, duration_frames // 2, duration_frames - 8192):
            coordinate = np.arange(start, start + 8192, dtype=float)
            def evaluate(points):
                return sum(t["amplitude"] * np.cos(2 * np.pi * t["cyclesPerFrame"] * points +
                                                  t["phaseRadians"] - np.pi / 2) for t in spec["channels"][0])
            reference = evaluate(coordinate)
            returned = evaluate(coordinate * (1 - 1 / (duration_frames - 1)))
            result = timing.measure_window(reference, returned)
            self.assertIn("delaySamples48k", result)
            delays.append(result["delaySamples48k"])
            measured_intervals.append((start, start + 8192))
        self.assertGreater(delays[-1] - delays[0], .99)
        self.assertLess(abs(delays[-1] - 1.), .04)
        self.assertGreater(measured_intervals[1][0] - measured_intervals[0][1], 0)
        self.assertLess(timing.PROFILE["maximumCalibratedReferenceFrames"], duration_frames)
        type(self).long_diagnostic = {"durationFrames": duration_frames,
            "accumulatedMeasuredDriftSamples48k": delays[-1] - delays[0],
            "measuredIntervals": measured_intervals, "decision": "UNVERIFIED_UNMEASURED_GAPS"}

    def test_compensated_slip_chunk_displacement_and_short_span_limit(self):
        for span in (16384, timing.PROFILE["minimumAffectedSpanFrames"], 32, 1):
            shifted = self.reference.copy()
            start, end = 8192, 8192 + span
            shifted[start:end] = self.reference[start - 1:end - 1]
            result = self.measured(shifted)
            if span >= timing.PROFILE["minimumAffectedSpanFrames"]:
                self.assertNotEqual("PASS_WITHIN_BOUND", result["decision"])
                self.assertTrue(any(t["jumps"] for t in result["trajectories"]))
            else:
                self.assertIn("shorter than 12288", timing.PROFILE["limitations"])
                self.assertEqual("UNVERIFIED", result["decision"])
        for mode in ("skip", "repeat", "swap"):
            damaged = self.reference.copy()
            if mode == "skip":
                damaged[8192:16384] = self.reference[16384:24576]
            elif mode == "repeat":
                damaged[16384:24576] = self.reference[8192:16384]
            else:
                damaged[8192:16384], damaged[16384:24576] = self.reference[16384:24576], self.reference[8192:16384]
            result = self.measured(damaged)
            self.assertNotEqual("PASS_WITHIN_BOUND", result["decision"])

    def test_channel_skew_common_mode_and_residual_concealment(self):
        delayed = source(self.spec, delay=1)
        skewed = self.reference.copy()
        skewed[:, 1] = delayed[:, 1]
        skew_result = self.measured(skewed)
        self.assertEqual("FAIL_TIMING", skew_result["decision"])
        self.assertGreater(skew_result["maximumStereoSkewSamples48k"], .95)
        common = self.measured(delayed)
        self.assertEqual("FAIL_TIMING", common["decision"])
        self.assertLess(common["maximumStereoSkewSamples48k"], .01)
        mixture = self.reference + source(specification(8311))
        remaining = mixture - delayed
        self.assertLess(np.max(np.abs((delayed + remaining) - mixture)), 1e-15)
        self.assertEqual("FAIL_TIMING", self.measured(delayed)["decision"])

    def test_silence_periodic_repetition_suppression_leakage_and_filter_abstain(self):
        periodic = np.sin(np.arange(32768) * 2 * np.pi / 32)[:, None]
        repeated = np.tile(self.reference[:128], (256, 1))
        cases = [np.zeros_like(self.reference), self.reference * 1e-8,
                 self.reference + 10 * source(specification(173)),
                 signal.lfilter([1., .8], [1., -.6], self.reference, axis=0)]
        for target in cases:
            self.assertEqual("UNVERIFIED", self.measured(target)["decision"])
        self.assertEqual("UNVERIFIED", self.measured(periodic, periodic)["decision"])
        self.assertEqual("UNVERIFIED", self.measured(repeated, repeated)["decision"])
        self.assertEqual("UNVERIFIED", self.measured(self.reference[:512], self.reference[:512])["decision"])
        self.assertEqual("UNVERIFIED", timing.measure_audio(self.reference, self.reference, calibrated=False)["decision"])
        musical_unknown = timing.measure_audio(self.reference, source(self.spec, delay=1), calibrated=False)
        self.assertEqual("UNVERIFIED", musical_unknown["decision"])
        self.assertTrue(all(w["decision"] == "UNVERIFIED" for w in musical_unknown["windows"]))
        self.assertGreater(musical_unknown["maximumAbsoluteLagSamples48k"], .95)
        lookalike_leakage = .01 * source(self.spec, delay=1) + self.reference
        leakage_result = self.measured(lookalike_leakage)
        self.assertEqual("UNVERIFIED", leakage_result["decision"])
        self.assertTrue(any(w.get("reason") == "OUTSIDE_EXACT_ANALYTIC_AFFINE_DOMAIN_OR_LEAKAGE" for w in leakage_result["windows"]))

    def test_interval_policy_and_empty_coverage(self):
        self.assertEqual("PASS_WITHIN_BOUND", timing.interval_decision(.125, .04))
        self.assertEqual("UNVERIFIED", timing.interval_decision(.25, .04))
        self.assertEqual("FAIL_TIMING", timing.interval_decision(.5, .04))
        self.assertEqual("UNVERIFIED", timing.worst([]))

    def test_relative_roles_use_their_own_measured_clock_errors(self):
        results = []
        for role, delay in (("vocals", .2), ("bass", -.2)):
            measured = self.measured(source(self.spec, delay=delay))
            self.assertEqual("PASS_WITHIN_BOUND", measured["decision"])
            measured["boundary"] = "prepared"
            results.append({"binding": {"sourceId": "shared-original", "selectedRole": role},
                            "artifacts": {"upload": {"sha256": "same-original-bytes"}}, "boundaries": [measured]})
        relative = timing.relative_timing(results)
        self.assertEqual("FAIL_TIMING", relative["decision"])
        self.assertGreater(relative["maximumRelativeLagSamples48k"], .39)

    def manifest(self, directory: Path) -> tuple[Path, dict]:
        inventory = timing.load_inventory()
        route = inventory["configurations"][0]
        selected, provider = next(iter(route["outputBindings"].items()))
        clock = {"originFrame": 0, "preskipFrames": 0, "trimFrames": 0, "channelOrder": ["left", "right"]}
        binding = dict(sourceId="independent-fixture-1", configurationId=route["configurationId"], renderId=route["renderId"],
                       options=route["options"], selectedRole=selected, providerRole=provider, acceptedJobId="observed-test-job",
                       generation=1, decoderProfile="native-f64-v1", srcProfile="identity-v1", observableRevision=None,
                       processingDate="2026-10-30", uploadClock=dict(sampleRate=48000, **clock))
        def artifact(name):
            path = directory / (name + ".f64")
            self.reference.astype("<f8").tofile(path)
            return dict(path=path.name, sha256=timing.sha256(path), format="f64le", channels=2, frames=len(self.reference),
                        sampleRate=48000, clock=copy.deepcopy(clock), expectedClock=copy.deepcopy(clock), binding=copy.deepcopy(binding))
        document = {"schemaVersion": 1, "binding": binding,
                    "artifacts": {name: artifact(name) for name in ("upload", "raw", "prepared")},
                    "reference": {"artifact": artifact("reference"), "analyticSpecification": self.spec,
                                  "provenance": {"kind": "independent-synchronized", "id": "analytic-fixture-1", "inputPolicy": "isolated-reference",
                                                 "role": selected, "sourceId": binding["sourceId"]}}}
        manifest = directory / "manifest.json"
        manifest.write_text(json.dumps(document), encoding="utf-8")
        return manifest, document

    def test_manifest_actual_artifacts_and_missing_reference_are_not_pass_flags(self):
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, document = self.manifest(Path(directory))
            result = timing.analyze_manifest(manifest)
            self.assertEqual('PASS_WITHIN_BOUND' if self.runtime_qualified else 'UNVERIFIED', result['decision'])
            self.assertEqual('CALIBRATED_RUNTIME' if self.runtime_qualified else 'UNQUALIFIED_ANALYZER_RUNTIME',
                             result['analyzerRuntimeStatus'])
            counts = timing.analyze(manifest)["bindingCounts"]
            self.assertEqual(0, counts["passed"])
            self.assertEqual(80, counts["unverified"])
            self.assertEqual(int(self.runtime_qualified), counts["suppliedMeasurementResults"]["passed"])
            document.pop("reference")
            document["editablePass"] = True
            document["routeQualified"] = True
            manifest.write_text(json.dumps(document), encoding="utf-8")
            result = timing.analyze_manifest(manifest)
            self.assertEqual("UNVERIFIED", result["decision"])
            self.assertEqual("STRUCTURAL_TIMELINE_VALID", result["structuralDecision"])

    def test_result_runtime_identity_uses_actual_loaded_binaries_and_is_cached(self):
        import scipy
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, _ = self.manifest(Path(directory))
            identity = timing.analyze_manifest(manifest)["runtime"]
        self.assertIs(identity, timing.runtime_identity())
        self.assertEqual(sys.version, identity["pythonVersion"])
        self.assertEqual(np.__version__, identity["numpyVersion"])
        self.assertEqual(scipy.__version__, identity["scipyVersion"])
        executable = identity["pythonExecutable"]
        self.assertEqual(str(Path(sys.executable).resolve()), executable["path"])
        self.assertEqual(hashlib.sha256(Path(sys.executable).read_bytes()).hexdigest(), executable["sha256"])
        for artifact in identity["coreExtensions"].values():
            if artifact["module"] is None:
                self.assertEqual("unknown", artifact["status"])
                self.assertIsNone(artifact["sha256"])
                continue
            self.assertEqual("known", artifact["status"])
            loaded = sys.modules[artifact["module"]]
            actual = Path(loaded.__file__).resolve()
            self.assertEqual(str(actual), artifact["path"])
            self.assertEqual(hashlib.sha256(actual.read_bytes()).hexdigest(), artifact["sha256"])

    def test_unqualified_runtime_retains_diagnostics_without_timing_claims(self):
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, _ = self.manifest(Path(directory))
            changed_runtime = copy.deepcopy(timing.runtime_identity())
            changed_runtime["numpyVersion"] = "unqualified-new-version"
            with mock.patch.object(timing, "runtime_identity", return_value=changed_runtime):
                with mock.patch.object(timing.sys, 'argv', ['source_timing.py', '--runtime-check']), redirect_stdout(io.StringIO()):
                    self.assertEqual(2, timing.main())
                result = timing.analyze_manifest(manifest)
            self.assertEqual("UNVERIFIED", result["decision"])
            self.assertEqual("JOB_TIMING_UNVERIFIED", result["jobAdmissionDecision"])
            self.assertEqual("STRUCTURAL_TIMELINE_VALID", result["structuralDecision"])
            self.assertEqual("UNQUALIFIED_ANALYZER_RUNTIME", result["reason"])
            self.assertTrue(all(b["decision"] == "UNVERIFIED" for b in result["boundaries"]))
            self.assertTrue(all(w["decision"] == "UNVERIFIED" for b in result["boundaries"] for w in b["windows"]))

    def test_stale_hash_route_role_job_decoder_clock_and_channel_binding_rejected(self):
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, original = self.manifest(Path(directory))
            mutations = [lambda d: d["artifacts"]["raw"].update(sha256="0" * 64),
                         lambda d: d["binding"].update(options={"add_opt1": "999"}),
                         lambda d: d["binding"].update(providerRole="wrong-role"),
                         lambda d: d["binding"].update(acceptedJobId="replacement-job"),
                         lambda d: d["binding"].update(decoderProfile="stale-decoder"),
                         lambda d: d["artifacts"]["prepared"]["clock"].update(preskipFrames=1),
                         lambda d: d["artifacts"]["prepared"]["clock"].update(trimFrames=1),
                         lambda d: d["artifacts"]["prepared"]["clock"].update(originFrame=1),
                         lambda d: d["artifacts"]["prepared"]["clock"].update(channelOrder=["right", "left"])]
            baseline_bytes = (Path(directory) / "raw.f64").read_bytes()
            for change in mutations:
                document = copy.deepcopy(original)
                change(document)
                manifest.write_text(json.dumps(document), encoding="utf-8")
                result = timing.analyze_manifest(manifest)
                self.assertEqual("FAIL_INTEGRITY", result["decision"], result)
                self.assertEqual("JOB_TIMING_UNVERIFIED", result["jobAdmissionDecision"])
                self.assertEqual(baseline_bytes, (Path(directory) / "raw.f64").read_bytes())
            document = copy.deepcopy(original)
            document["artifacts"]["raw"]["sampleRate"] = 44100
            manifest.write_text(json.dumps(document), encoding="utf-8")
            result = timing.analyze_manifest(manifest)
            self.assertEqual("UNVERIFIED", result["decision"])
            self.assertEqual("UNQUALIFIED_CLOCK_PROFILE", result["reason"])
            document = copy.deepcopy(original)
            document["artifacts"]["raw"].pop("expectedClock")
            document["artifacts"]["raw"]["clock"]["preskipFrames"] = 312
            document["artifacts"]["raw"]["encodedClock"] = {"preSkipFrames": 312, "endTrimFrames": 120}
            manifest.write_text(json.dumps(document), encoding="utf-8")
            result = timing.analyze_manifest(manifest)
            self.assertEqual("UNVERIFIED", result["decision"])
            self.assertEqual("UNQUALIFIED_CLOCK_PROFILE", result["reason"])
            self.assertEqual(312, result["artifacts"]["raw"]["encodedClock"]["preSkipFrames"])

    def test_malformed_bound_identity_types_rejected_even_when_all_snapshots_match(self):
        cases = [("generation", -1), ("generation", True), ("generation", []), ("renderId", True),
                 ("sourceId", {}), ("acceptedJobId", []), ("decoderProfile", {}), ("srcProfile", ""),
                 ("processingDate", False), ("observableRevision", []), ("options", {"add_opt1": 1}),
                 ("sourceId", "bad\nidentity"), ("sourceId", "x" * 513),
                 ("uploadClock", {"sampleRate": True, "originFrame": 0, "preskipFrames": 0, "trimFrames": 0, "channelOrder": ["left", "right"]})]
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, original = self.manifest(Path(directory))
            for key, value in cases:
                document = copy.deepcopy(original)
                document["binding"][key] = value
                for desc in [*document["artifacts"].values(), document["reference"]["artifact"]]:
                    desc["binding"] = copy.deepcopy(document["binding"])
                manifest.write_text(json.dumps(document), encoding="utf-8")
                result = timing.analyze_manifest(manifest)
                self.assertEqual("FAIL_INTEGRITY", result["decision"], (key, value, result))
                self.assertEqual("STRUCTURAL_TIMELINE_INVALID", result["structuralDecision"])
                self.assertEqual("JOB_TIMING_UNVERIFIED", result["jobAdmissionDecision"])

    def test_numeric_overflow_range_and_uncalibrated_gain_abstain_without_nan_json(self):
        for gain in (.01, 2., 1e200):
            measured = self.measured(self.reference * gain)
            self.assertEqual("UNVERIFIED", measured["decision"])
            json.dumps(measured, allow_nan=False)
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, document = self.manifest(Path(directory))
            raw = Path(directory) / "raw.f64"
            (self.reference * 1e200).astype("<f8").tofile(raw)
            document["artifacts"]["raw"]["sha256"] = timing.sha256(raw)
            manifest.write_text(json.dumps(document), encoding="utf-8")
            result = timing.analyze_manifest(manifest)
            self.assertEqual("UNVERIFIED", result["decision"])
            self.assertEqual("NUMERIC_RANGE_UNQUALIFIED", result["reason"])
            output = Path(directory) / "result.json"
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(output)]), redirect_stdout(io.StringIO()):
                self.assertEqual(2, timing.main())
            json.loads(output.read_text(encoding="utf-8"), parse_constant=lambda value: self.fail(value))

    def test_cli_output_status_and_preservation(self):
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            manifest, document = self.manifest(Path(directory))
            output = Path(directory) / "result.json"
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(output)]), redirect_stdout(io.StringIO()):
                self.assertEqual(0 if self.runtime_qualified else 2, timing.main())
            self.assertEqual("JOB_TIMING_VERIFIED_WITHIN_BOUND" if self.runtime_qualified else "JOB_TIMING_UNVERIFIED",
                             json.loads(output.read_text(encoding="utf-8"))["jobAdmissionDecision"])
            document.pop("reference")
            manifest.write_text(json.dumps(document), encoding="utf-8")
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(output)]), redirect_stdout(io.StringIO()):
                self.assertEqual(2, timing.main())
            self.assertEqual("JOB_TIMING_UNVERIFIED", json.loads(output.read_text(encoding="utf-8"))["jobAdmissionDecision"])
            document["reference"] = self.manifest(Path(directory))[1]["reference"]
            shifted_path = Path(directory) / "raw.f64"
            source(self.spec, delay=1).astype("<f8").tofile(shifted_path)
            document["artifacts"]["raw"]["sha256"] = timing.sha256(shifted_path)
            manifest.write_text(json.dumps(document), encoding="utf-8")
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(output)]), redirect_stdout(io.StringIO()):
                self.assertEqual(1 if self.runtime_qualified else 2, timing.main())
            self.assertEqual('JOB_TIMING_FAILED' if self.runtime_qualified else 'JOB_TIMING_UNVERIFIED',
                             json.loads(output.read_text(encoding="utf-8"))["jobAdmissionDecision"])
            before = shifted_path.read_bytes()
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(shifted_path)]), redirect_stdout(io.StringIO()), mock.patch.object(timing.sys, "stderr", io.StringIO()):
                self.assertEqual(2, timing.main())
            self.assertEqual(before, shifted_path.read_bytes())
            document["artifacts"]["raw"]["sha256"] = "0" * 64
            manifest.write_text(json.dumps(document), encoding="utf-8")
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(output)]), redirect_stdout(io.StringIO()):
                self.assertEqual(1, timing.main())
            integrity = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("FAIL_INTEGRITY", integrity["decision"])
            self.assertEqual("JOB_TIMING_UNVERIFIED", integrity["jobAdmissionDecision"])
            original = manifest.read_bytes()
            with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(manifest), "--output", str(manifest)]), redirect_stdout(io.StringIO()), mock.patch.object(timing.sys, "stderr", io.StringIO()):
                self.assertEqual(2, timing.main())
            self.assertEqual(original, manifest.read_bytes())

    def test_cli_qualification_branches_preserve_the_runtime_gate(self):
        prior = self.runtime_qualified
        try:
            for qualified in (True, False):
                identity = dict(timing.PROFILE["calibratedRuntime"])
                if not qualified:
                    identity["pythonRelease"] = "0.0.0-unqualified-test"
                with self.subTest(qualified=qualified), mock.patch.object(timing, "runtime_identity", return_value=identity):
                    self.runtime_qualified = qualified
                    self.test_cli_output_status_and_preservation()
        finally:
            self.runtime_qualified = prior

    def test_cli_replaces_owned_report_after_malformed_wav_in_single_and_bundle_inputs(self):
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            directory = Path(directory)
            manifest, document = self.manifest(directory)
            bundle, output = directory / "bundle.json", directory / "result.json"
            broken = directory / "broken.wav"
            def invoke(path):
                with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(path), "--output", str(output)]), redirect_stdout(io.StringIO()):
                    return timing.main()
            for bundled in (False, True):
                manifest.write_text(json.dumps(document), encoding="utf-8")
                bundle.write_text(json.dumps({"schemaVersion": 1, "targets": [
                    {"manifest": manifest.name, "sha256": timing.sha256(manifest)}]}), encoding="utf-8")
                selected = bundle if bundled else manifest
                self.assertEqual(0 if self.runtime_qualified else 2, invoke(selected))
                previous = output.read_bytes()
                for malformed in (b"not a RIFF waveform", b"RIFF"):
                    with self.subTest(bundled=bundled, malformed=malformed):
                        broken.write_bytes(malformed)
                        invalid = copy.deepcopy(document)
                        invalid["artifacts"]["raw"].update(path=broken.name, sha256=timing.sha256(broken), format="wav-pcm16")
                        manifest.write_text(json.dumps(invalid), encoding="utf-8")
                        bundle.write_text(json.dumps({"schemaVersion": 1, "targets": [
                            {"manifest": manifest.name, "sha256": timing.sha256(manifest)}]}), encoding="utf-8")
                        output.write_bytes(previous)
                        self.assertEqual(1, invoke(selected))
                        result = json.loads(output.read_text(encoding="utf-8"))
                        self.assertEqual("FAIL_INTEGRITY", result["decision"])
                        if bundled:
                            self.assertEqual("FAIL_INTEGRITY", result["targets"][0]["decision"])
                        else:
                            self.assertEqual("JOB_TIMING_UNVERIFIED", result["jobAdmissionDecision"])
                        self.assertEqual(malformed, broken.read_bytes())

    def test_cli_replaces_owned_pass_after_malformed_input_or_bundle_hash_failure(self):
        with tempfile.TemporaryDirectory(prefix="source-timing-", dir=timing.ROOT / "Test suite/work") as directory:
            directory = Path(directory)
            manifest, document = self.manifest(directory)
            output = directory / "result.json"
            def invoke(input_path=manifest, output_path=output):
                with mock.patch.object(timing.sys, "argv", ["source_timing.py", "--analyze", str(input_path), "--output", str(output_path)]), redirect_stdout(io.StringIO()), mock.patch.object(timing.sys, "stderr", io.StringIO()):
                    return timing.main()
            self.assertEqual(0 if self.runtime_qualified else 2, invoke())
            passed = output.read_bytes()
            raw_bytes = (directory / "raw.f64").read_bytes()
            malformed = ["{", "[]", json.dumps(dict(document, artifacts=[])),
                         json.dumps(dict(document, reference=[])), json.dumps(dict(document, reference={"provenance": {}, "artifact": None})),
                         json.dumps(dict(document, unusedNumber=float("nan")))]
            for text in malformed:
                output.write_bytes(passed)
                manifest.write_text(text, encoding="utf-8")
                self.assertEqual(1, invoke())
                result = json.loads(output.read_text(encoding="utf-8"))
                self.assertEqual("FAIL_INTEGRITY", result["decision"])
                self.assertEqual("JOB_TIMING_UNVERIFIED", result["jobAdmissionDecision"])
                self.assertEqual(raw_bytes, (directory / "raw.f64").read_bytes())
            manifest.write_text("{", encoding="utf-8")
            unrelated = directory / "unrelated.json"
            unrelated.write_text("owner content", encoding="utf-8")
            self.assertEqual(2, invoke(output_path=unrelated))
            self.assertEqual("owner content", unrelated.read_text(encoding="utf-8"))
            fresh = directory / "new-failure.json"
            self.assertEqual(1, invoke(output_path=fresh))
            self.assertEqual("FAIL_INTEGRITY", json.loads(fresh.read_text(encoding="utf-8"))["decision"])
            other_manifest = directory / "other-manifest.json"
            other_manifest.write_text("{", encoding="utf-8")
            output.write_bytes(passed)
            self.assertEqual(2, invoke(input_path=other_manifest))
            self.assertEqual(passed, output.read_bytes())
            manifest.write_text(json.dumps(document), encoding="utf-8")
            with mock.patch.object(timing, "analyze", side_effect=AttributeError("unexpected malformed state")):
                self.assertEqual(1, invoke())
            self.assertEqual("FAIL_INTEGRITY", json.loads(output.read_text(encoding="utf-8"))["decision"])
            bundle = directory / "bundle.json"
            bundle_document = {"schemaVersion": 1, "targets": [{"manifest": manifest.name, "sha256": timing.sha256(manifest)}]}
            bundle.write_text(json.dumps(bundle_document), encoding="utf-8")
            self.assertEqual(0 if self.runtime_qualified else 2, invoke(input_path=bundle))
            bundle_passed = output.read_bytes()
            bundle_document["targets"][0]["sha256"] = "0" * 64
            bundle.write_text(json.dumps(bundle_document), encoding="utf-8")
            self.assertEqual(1, invoke(input_path=bundle))
            self.assertEqual("FAIL_INTEGRITY", json.loads(output.read_text(encoding="utf-8"))["decision"])
            output.write_bytes(bundle_passed)
            bundle_document["targets"][0]["manifest"] = "\x00invalid-path"
            bundle.write_text(json.dumps(bundle_document), encoding="utf-8")
            self.assertEqual(1, invoke(input_path=bundle))
            self.assertEqual("FAIL_INTEGRITY", json.loads(output.read_text(encoding="utf-8"))["decision"])
            encoded = directory / "provider-original.json"
            encoded.write_text('{"provider":"retained"}', encoding="utf-8")
            document["artifacts"]["raw"]["encodedArtifact"] = {"path": encoded.name, "sha256": timing.sha256(encoded)}
            manifest.write_text(json.dumps(document), encoding="utf-8")
            before = encoded.read_bytes()
            self.assertEqual(2, invoke(output_path=encoded))
            self.assertEqual(before, encoded.read_bytes())


if __name__ == "__main__":
    unittest.main()
