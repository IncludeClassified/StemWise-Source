"""Positive exact Qwen sequence checks in the dedicated lyrics runtime."""
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[3]
import stemwise_engine.lyrics_align_pc as worker
from stemwise_engine.workflow_records import LyricsContentBlocker


class QwenContentSequenceTests(unittest.TestCase):
    def test_exact_unprompted_sequence_uses_real_scoring_without_retry(self):
        text = ('summer bright river voices waking daylight\n'
                'singing softly under silver moon sky\n'
                'winds carry every promise over hills\n'
                'new paths lead us onward after rain')
        recognize = Mock(return_value=(text, {'language': 'en'}, True))
        scores, provenance, reused = worker._score_qwen_structure_routes(
            {'text': text}, {(0, 1, 2, 3)}, ('en', 'auto'), recognize)
        self.assertEqual(len(scores), 1)
        self.assertTrue(scores[0].accepted)
        self.assertEqual(scores[0].metrics.direct_line_count, 4)
        self.assertEqual(scores[0].source_physical_indices, (0, 1, 2, 3))
        self.assertEqual(provenance, {'language': 'en'})
        self.assertTrue(reused)
        recognize.assert_called_once_with('en')

    def test_equal_wording_does_not_choose_an_ambiguous_physical_occurrence(self):
        original = {'text': 'summer bright river voices waking daylight\n'
                            'summer bright river voices waking daylight'}
        recognize = Mock(return_value=(original['text'], {}, True))
        with self.assertRaises(LyricsContentBlocker):
            worker._score_qwen_structure_routes(original, {(0, 1), (1, 0)}, ('en', 'auto'), recognize)
        recognize.assert_called_once_with('en')
