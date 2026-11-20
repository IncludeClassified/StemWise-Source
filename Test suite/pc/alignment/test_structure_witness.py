from __future__ import annotations

import sys
import unittest
from dataclasses import fields
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3] / "PC side" / "stemwise-playlist-builder"

from stemwise_engine.lyrics_alignment import (  # noqa: E402
    StructureWitness,
    StructureWitnessMetrics,
    score_structure_witness,
)


class StructureWitnessTest(unittest.TestCase):
    def test_contract_has_no_timing_or_duration_fields(self) -> None:
        names = {
            field.name.lower()
            for contract in (StructureWitness, StructureWitnessMetrics)
            for field in fields(contract)
        }
        banned = ("time", "duration", "start", "end", "ms", "second")
        self.assertFalse(any(part in name for name in names for part in banned))

    def test_fixed_sequence_passes_without_changing_its_sources(self) -> None:
        text = "alpha bravo charlie delta\necho foxtrot golf hotel"
        result = score_structure_witness(text, (0, 1), text)

        self.assertTrue(result.accepted)
        self.assertEqual(result.source_physical_indices, (0, 1))
        self.assertEqual(result.metrics.direct_line_count, 2)
        self.assertEqual(result.metrics.supported_line_count, 2)
        self.assertEqual(result.metrics.matched_word_count, 8)

    def test_mismatch_fails_but_repeated_occurrences_remain_positioned(self) -> None:
        text = "alpha bravo charlie delta\necho foxtrot golf hotel"
        mismatch = score_structure_witness(
            text,
            (0, 1),
            "india juliet kilo lima mike november oscar papa",
        )
        self.assertFalse(mismatch.accepted)

        repeated = "alpha bravo charlie delta\nalpha bravo charlie delta"
        approved = repeated + "\n" + repeated
        chorus = score_structure_witness(approved, (0, 1, 2, 3), approved)
        self.assertTrue(chorus.accepted)
        self.assertEqual(chorus.source_physical_indices, (0, 1, 2, 3))
        self.assertEqual(chorus.metrics.direct_line_count, 4)
        self.assertEqual(chorus.metrics.supported_line_count, 4)

    def test_shorter_sequence_cannot_pass_by_explaining_only_half_the_transcript(self) -> None:
        text = "alpha bravo charlie delta\necho foxtrot golf hotel"
        performed = text + "\n" + text

        self.assertFalse(score_structure_witness(text, (0, 1), performed).accepted)
        self.assertTrue(score_structure_witness(performed, (0, 1, 2, 3), performed).accepted)
        with self.assertRaisesRegex(ValueError, "exact approved physical-line sequence"):
            score_structure_witness(text, (0, 1, 0, 1), performed)

    def test_small_complete_repeat_cannot_hide_inside_high_global_coverage(self) -> None:
        unit = ["silver dawn wakes the valley", "gentle voices rise above mountains"]
        tail = [f"chapter {i} carries another distinct promise onward" for i in range(8)]
        text = "\n".join([*unit, "", *unit, "", *tail])
        # Every approved occurrence has its own physical line. The omitted
        # ten-word unit is too small to trip the global coverage gate alone.
        short = (0, 1, *range(6, 14))
        complete = (0, 1, 3, 4, *range(6, 14))
        performed = " ".join([*unit, *unit, *tail])
        with self.assertRaisesRegex(ValueError, "exact approved physical-line sequence"):
            score_structure_witness(text, short, performed)
        omitted_text = "\n".join([*unit, "", *tail])
        omitted_sources = (0, 1, *range(3, 11))
        rejected = score_structure_witness(omitted_text, omitted_sources, performed)
        self.assertFalse(rejected.accepted)
        self.assertIn("unaccounted-complete-repetition", rejected.reasons)
        self.assertEqual(rejected.source_physical_indices, omitted_sources)
        self.assertTrue(score_structure_witness(text, complete, performed).accepted)
        with self.assertRaisesRegex(ValueError, "exact approved physical-line sequence"):
            score_structure_witness(text, (0, 1, 0, 1, *range(6, 14)), performed)


if __name__ == "__main__":
    unittest.main()
