"""Synthetic fixtures exercise current CTC summaries and candidate dispatch."""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'Test suite/pc/unit'))
import stemwise_engine.endpoint_candidate_prediction as prediction
import stemwise_engine.endpoint_ctc_evidence as ctc
import stemwise_engine.endpoint_mms_prediction as mms
from stemwise_engine.acoustic_inputs import AcousticBudget


class AcousticPhrasePredictionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = ROOT / 'Test suite/work/accuracy-timing'
        cls.created = not cls.work.exists()
        cls.work.mkdir(exist_ok=True)

    @classmethod
    def tearDownClass(cls):
        if cls.created:
            cls.work.rmdir()

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(dir=self.work)
        self.root = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()


    def test_live_mms_posterior_retains_curves_without_changing_the_original_decode(self):
        probability = np.full((8,3),.001)
        probability[np.arange(8),[0,1,1,0,0,2,2,0]] = .998
        logp, edges = np.log(probability), np.arange(9)*20.
        before = mms.decode(logp,[1,2],[0,1],['w0','w1'],edges,{},estimator='posterior')
        summaries = []
        after = mms.decode(logp,[1,2],[0,1],['w0','w1'],edges,{},estimator='posterior',boundary_summaries=summaries)
        self.assertEqual(after,before)
        self.assertEqual([row['wordId'] for row in summaries],['w0','w1'])
        self.assertTrue(all(len(row[boundary]) <= 8 for row in summaries for boundary in ('onset','offset')))
        summaries.clear()
        with patch.object(mms,'CURRENT_BUDGET',AcousticBudget(available_bytes=3000)):
            limited = mms.decode(logp,[1,2],[0,1],['w0','w1'],edges,{},
                estimator='posterior',boundary_summaries=summaries)
        self.assertEqual(limited,before)
        self.assertEqual(summaries,[])
        summaries.clear()
        mms.decode(np.log(np.tile([.998,.001,.001],(8,1))),[1,2],[0,1],['w0','w1'],edges,{},
            estimator='posterior',boundary_summaries=summaries)
        self.assertEqual(summaries,[], 'target conditioning over blank-only features cannot create acoustic information')

    def test_live_ctc_summaries_share_one_forward_backward_pass(self):
        probability = np.full((8,3),.001)
        probability[np.arange(8),[0,1,1,0,0,2,2,0]] = .998
        summaries = []
        with patch.object(ctc,'_ctc_forward',wraps=ctc._ctc_forward) as forward:
            ctc.boundary_distributions(np.log(probability),[1,2],0,0,1,summary_sink=summaries,
                word_ranges=[('w0',0,0),('w1',1,1)],origin_ms=100,step_ms=20)
        self.assertEqual(forward.call_count,1)
        self.assertEqual([row['wordId'] for row in summaries],['w0','w1'])
        expected = ctc.boundary_distributions(np.log(probability),[1,2],0,0,1)
        summaries.clear()
        with patch.object(ctc,'CURRENT_BUDGET',AcousticBudget(available_bytes=3000)):
            limited = ctc.boundary_distributions(np.log(probability),[1,2],0,0,1,
                summary_sink=summaries,word_ranges=[('w0',0,0),('w1',1,1)])
        for actual,reference in zip(limited,expected):
            np.testing.assert_array_equal(actual,reference)
        self.assertEqual(summaries,[])
        summaries.clear()
        ctc.boundary_distributions(np.log(np.tile([.998,.001,.001],(8,1))),[1,2],0,0,1,
            summary_sink=summaries,word_ranges=[('w0',0,0),('w1',1,1)])
        self.assertEqual(summaries,[])


    def test_candidate_contexts_run_without_prediction_time_or_call_quota(self):
        contexts = [self.root/str(index) for index in range(5)]
        for context in contexts:
            context.mkdir()
            (context/'request.private.json').write_text(json.dumps({'language':'en'}),encoding='utf-8')
        with patch.object(prediction,'_model',return_value=('model',{'identity':'exact'})) as model, \
                patch.object(prediction,'infer_records',return_value={'records':[]}) as infer:
            prediction._infer_contexts(self.root,contexts)
        self.assertEqual(model.call_count,1)
        self.assertEqual(infer.call_count,5)
        for context in contexts:
            self.assertEqual(json.loads((context/'candidate-prediction.json').read_bytes()),{'records':[]})


if __name__ == '__main__':
    unittest.main()
