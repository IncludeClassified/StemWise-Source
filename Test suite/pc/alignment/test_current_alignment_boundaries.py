"""Current alignment selection, single-line timing and publication races."""
from dataclasses import replace
from pathlib import Path
import tempfile
import json
import unittest
from unittest.mock import patch

from test_lyrics_alignment import accepted_result, transcript, plain_original, PROJECT_ROOT
import stemwise_engine.lyrics_alignment as alignment
import stemwise_engine.lyrics_align_pc as coordinator


class CurrentAlignmentBoundaries(unittest.TestCase):
    def test_current_results_and_diagnostics_contain_only_admitted_occurrences(self):
        result = accepted_result((0, 2), starts=(1000, 2000))
        record = alignment.serialize_result(result)
        self.assertEqual(set(record), {"accepted", "reasons", "metrics", "timedLineCount"})
        self.assertFalse(any("inserted" in key or "omitted" in key or "collapsed" in key
                             for key in record["metrics"]))
        candidate = coordinator._structure_candidate_diagnostics("a1B2c3D4e5F",
            plain_original("same approved words here\n\nsame approved words here"), result)
        self.assertEqual(set(candidate), {"performedOccurrences"})
        self.assertEqual([item["sourcePhysicalIndex"] for item in candidate["performedOccurrences"]], [0, 2])

    def test_publication_rejects_missing_extra_or_reused_physical_lines(self):
        original = plain_original("same approved words here\nsame approved words here")
        for sources in ((0,), (0, 1, 1), (0, 0), (1, 0)):
            with self.subTest(sources=sources):
                result = accepted_result(sources)
                timings = [{"startMs": index * 1000, "endMs": index * 1000 + 500}
                           for index in range(len(sources))]
                with self.assertRaisesRegex(ValueError, "exact approved physical-line sequence"):
                    alignment.build_synced_sidecar({"tracks": [original]}, result, 10000,
                        line_timings=timings)
                with self.assertRaisesRegex(ValueError, "exact approved physical-line sequence"):
                    alignment.score_structure_witness(original["text"], sources,
                        "same approved words here same approved words here")

    def test_crop_and_decoder_audio_budgets_reject_before_sample_allocation(self):
        from stemwise_engine.acoustic_inputs import require_waveform_allocation,AcousticBudgetExceeded
        from stemwise_engine.source_crop import read_source_crop
        from types import SimpleNamespace
        import soundfile
        from unittest.mock import patch
        with patch.object(soundfile,'info',return_value=SimpleNamespace(frames=7200*48000,channels=2,samplerate=48000)), patch.object(soundfile,'read',side_effect=AssertionError('unbounded sample allocation')), patch('stemwise_engine.analysis_resources.available_memory_bytes', return_value=1024):
            with self.assertRaises(AcousticBudgetExceeded):
                require_waveform_allocation('unused.wav',16000)
            with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
                with self.assertRaises(AcousticBudgetExceeded):
                    read_source_crop(Path(directory),{'startMs':0,'endMs':120000})

    def test_paired_results_and_audio_identity_reject_cross_plan_or_incomplete_handoffs(self):
        from stemwise_engine.alignment_phases import AudioIdentity, SegmentPlan, PairedEndpointResult
        plan = [{'segmentIndex': 0, 'startLineIndex': 0, 'endLineIndex': 1,
                 'windowStartMs': 0, 'windowEndMs': 1000}]
        result = PairedEndpointResult.bind({'lines': [{'startMs': 100, 'endMs': 900}]},
            [{'startMs': 100, 'endMs': 900}], plan)
        changed = [{**plan[0], 'windowEndMs': 999}]
        with self.assertRaisesRegex(ValueError, 'plan changed'):
            result.require_plan(changed)
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            PairedEndpointResult.bind({'lines': [{}]}, None, plan)
        with self.assertRaises(ValueError):
            SegmentPlan.from_segments([{**plan[0], 'startLineIndex': 1}])
        identity = AudioIdentity('a'*64, 4000, 48000, 2, 48001).to_record()
        self.assertEqual(identity['stream']['durationMs'], 1000)
        with self.assertRaises(ValueError):
            AudioIdentity('bad', 4000, 48000, 2, 48001)

    def test_owner_edit_race_blocks_publication_and_preserves_every_byte_set(self):
        import stemwise_engine.worker_publication as worker_publication
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT.parents[1]/'Test suite/work') as directory:
            root = Path(directory)
            workspace = alignment.AlignmentWorkspace(root, workspace_root=root)
            sidecar = root/'owner/song.lyrics.json'
            sidecar.parent.mkdir()
            original, owner_edit = b'{"owner":"original"}', b'{"owner":"latest edit"}'
            sidecar.write_bytes(original)
            primitive = worker_publication.replace_preserving_previous
            def race(destination, replacement, previous):
                destination.write_bytes(owner_edit)
                return primitive(destination, replacement, previous)
            with patch.object(worker_publication, 'replace_preserving_previous', side_effect=race):
                with self.assertRaisesRegex(ValueError, 'conflict'):
                    alignment.commit_candidate(workspace, 'a1B2c3D4e5F', sidecar, original, {'candidate': 1})
            marker = sidecar.with_name('.'+sidecar.name+'.publication-conflict.json')
            self.assertTrue(marker.exists())
            record = json.loads(marker.read_text())
            self.assertEqual((sidecar.parent/record['displacedFile']).read_bytes(), owner_edit)
            with self.assertRaisesRegex(ValueError, 'conflict'):
                alignment.load_sidecar(sidecar, root=sidecar.parent)
            sidecar.write_bytes(b'{"owner":"third edit"}')
            with self.assertRaisesRegex(ValueError, 'conflict'):
                alignment.commit_candidate(workspace, 'a1B2c3D4e5F', sidecar, sidecar.read_bytes(), {'candidate': 2})
            self.assertEqual(sidecar.read_bytes(), b'{"owner":"third edit"}')

    def test_reviewed_baseline_prefers_passing_evidence(self):
        passing = accepted_result((0, 1))
        failing = replace(passing, accepted=False, reasons=('short-timed-span',),
                          metrics=replace(passing.metrics, timed_span_ratio=1.0))
        with patch.object(coordinator, '_result_score', side_effect=lambda value: .95 if value is passing else .99):
            selected = coordinator._select_evidence_option(
                [('passing', passing, {}, False), ('failing', failing, {}, False)],
                text='First line\nSecond line',
                reviewed_baseline_structure=True, performed_structure_review_sha256='a'*64)
        self.assertIs(selected[1], passing)

    def test_single_line_uses_direct_matched_word_span_but_tiny_excerpt_fails(self):
        text = 'alpha beta gamma delta epsilon zeta eta theta iota kappa'
        for split in (False, True):
            formatted = text.replace('epsilon zeta', 'epsilon\nzeta') if split else text
            result = alignment.align_known_structure(formatted, tuple(i for i,line in enumerate(formatted.splitlines()) if line.strip()), transcript(text, start=.1, step=1.0), 10000)
            self.assertTrue(result.accepted, result.reasons)
        tiny = alignment.align_known_structure(text, (0,), transcript(text, start=.1, step=.01), 10000)
        self.assertFalse(tiny.accepted)
